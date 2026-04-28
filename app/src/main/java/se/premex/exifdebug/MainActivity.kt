package se.premex.exifdebug

import android.Manifest
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import java.io.File

/**
 * Reference Android app for "what actually preserves EXIF GPS".
 *
 * Five pickers + five EXIF read methods. Pick a photo through any picker;
 * the app runs all five read methods against the resulting URI and shows
 * which combinations recover GPS, with annotations explaining each behaviour.
 *
 * Logs everything to logcat under tag `ExifDebug` so reproductions can be
 * captured with `adb logcat -s ExifDebug`.
 */
private const val TAG = "ExifDebug"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ExifDebugScreen() }
    }
}

data class PickResult(
    val pickerKind: PickerKind,
    val uri: Uri,
    val sizeBytes: Long?,
    val mimeType: String?,
    val displayName: String?,
    val readResults: List<ExifReadResult>,
)

@Composable
private fun ExifDebugScreen() {
    val context = LocalContext.current
    val resolver = context.contentResolver
    var pick by remember { mutableStateOf<PickResult?>(null) }
    var lastError by remember { mutableStateOf<String?>(null) }
    // Guided mode: a TestRecorder walks through every picker once and produces
    // a Markdown summary report at the end. The recorder is the source of truth
    // for "which step are we on"; null means free / manual mode.
    var recorder by remember { mutableStateOf<TestRecorder?>(null) }
    // Bumped each time `recorder.record()` mutates the in-memory state, so
    // remember-derived UI re-renders. Plain field mutation isn't observable.
    var recorderTick by remember { mutableStateOf(0) }
    var report by remember { mutableStateOf<String?>(null) }

    fun process(uri: Uri?, kind: PickerKind) {
        if (uri == null) {
            Log.i(TAG, "${kind.name}: cancelled (uri=null)")
            return
        }
        Log.i(TAG, "${kind.name}: picked uri=$uri")
        try {
            val p = readAllMethods(resolver, uri, kind)
            pick = p
            lastError = null
            Log.i(TAG, "${kind.name}: size=${p.sizeBytes} mime=${p.mimeType} name=${p.displayName}")
            p.readResults.forEach { r ->
                Log.i(TAG, "  ${r.method.label}: ${r.status} latLon=${r.latLong}")
                r.error?.let { Log.w(TAG, "    error: $it") }
            }
            // If a guided test is running and this pick matches the current
            // step, advance. Free-mode picks (e.g. user testing one picker
            // off-script) record nothing.
            recorder?.let { rec ->
                if (rec.currentPicker == kind) {
                    rec.record(p)
                    recorderTick++
                    if (rec.isComplete) {
                        rec.logReport()
                        report = rec.renderReport()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "${kind.name}: process threw", e)
            lastError = "${e::class.simpleName}: ${e.message}"
        }
    }

    val pickPhotoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri -> process(uri, PickerKind.PICK_VISUAL_MEDIA) }
    val openDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> process(uri, PickerKind.OPEN_DOCUMENT) }
    val getContent = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri -> process(uri, PickerKind.GET_CONTENT) }

    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        if (success) process(pendingCameraUri, PickerKind.TAKE_PICTURE)
        else Log.i(TAG, "TakePicture: cancelled")
    }

    fun launchCamera() {
        val file = File(context.cacheDir, "captures/cap_${System.currentTimeMillis()}.jpg")
            .apply { parentFile?.mkdirs() }
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file,
        )
        pendingCameraUri = uri
        takePicture.launch(uri)
    }

    val mediaLocationPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> Log.i(TAG, "ACCESS_MEDIA_LOCATION granted=$granted") }
    val readMediaImagesPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> Log.i(TAG, "READ_MEDIA_IMAGES granted=$granted") }
    val cameraPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) launchCamera() else Log.i(TAG, "CAMERA denied") }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        val safeInsets = WindowInsets.safeDrawing.asPaddingValues()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = safeInsets.calculateStartPadding(androidx.compose.ui.unit.LayoutDirection.Ltr) + 16.dp,
                end = safeInsets.calculateEndPadding(androidx.compose.ui.unit.LayoutDirection.Ltr) + 16.dp,
                top = safeInsets.calculateTopPadding() + 16.dp,
                bottom = safeInsets.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "EXIF Picker Lab",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Pick the same photo through each picker. Each pick is read 4 different ways. " +
                        "Green rows = GPS recovered.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // Guided test recorder — drives the user through every picker and
            // emits a paste-into-a-GitHub-issue Markdown report at the end.
            recorder?.let { rec -> recorderTick // re-read on tick
                item {
                    GuidedTestCard(
                        recorder = rec,
                        onSkip = {
                            rec.skip()
                            recorderTick++
                            if (rec.isComplete) {
                                rec.logReport()
                                report = rec.renderReport()
                            }
                        },
                        onCancel = {
                            recorder = null
                            report = null
                        },
                    )
                }
            }
            if (recorder == null && report == null) {
                item {
                    Button(
                        onClick = { recorder = TestRecorder(); report = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("▶ Run guided test (5 pickers)") }
                }
            }
            report?.let { md ->
                item {
                    ReportCard(
                        markdown = md,
                        onShare = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "EXIF Picker Lab — test report")
                                putExtra(Intent.EXTRA_TEXT, md)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share report"))
                        },
                        onClose = { report = null; recorder = null },
                    )
                }
            }

            item {
                Text("Pickers", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            pickPhotoPicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(PickerKind.PICK_VISUAL_MEDIA.label) }
                    Button(
                        onClick = { openDocument.launch(arrayOf("image/*")) },
                        modifier = Modifier.weight(1f),
                    ) { Text(PickerKind.OPEN_DOCUMENT.label) }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { getContent.launch("image/*") },
                        modifier = Modifier.weight(1f),
                    ) { Text(PickerKind.GET_CONTENT.label) }
                    Button(
                        onClick = {
                            cameraPermission.launch(Manifest.permission.CAMERA)
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(PickerKind.TAKE_PICTURE.label) }
                }
            }
            item {
                Button(
                    onClick = {
                        val ms = pickLatestMediaStoreImage(resolver)
                        if (ms != null) process(ms, PickerKind.MEDIASTORE_LATEST)
                        else lastError = "MediaStore query returned no images. Grant READ_MEDIA_IMAGES?"
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(PickerKind.MEDIASTORE_LATEST.label) }
            }

            item {
                Text(
                    "Permissions",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        OutlinedButton(
                            onClick = { mediaLocationPermission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Grant ACCESS_MEDIA_LOCATION") }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        OutlinedButton(
                            onClick = { readMediaImagesPermission.launch(Manifest.permission.READ_MEDIA_IMAGES) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Grant READ_MEDIA_IMAGES") }
                    }
                }
            }

            lastError?.let { msg ->
                item {
                    Text(
                        "Error: $msg",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            pick?.let { p ->
                item { PickerSummaryCard(p) }
                item {
                    Text(
                        "Read methods",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(p.readResults) { r -> ReadMethodCard(r) }
            }
        }
    }
}

@Composable
private fun PickerSummaryCard(p: PickResult) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                p.pickerKind.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                p.pickerKind.behaviour,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AsyncImage(
                model = p.uri,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            SelectionContainer {
                Text(
                    "URI: ${p.uri}\n" +
                        "Authority: ${p.uri.authority ?: "—"}\n" +
                        "Display name: ${p.displayName ?: "—"}\n" +
                        "MIME: ${p.mimeType ?: "—"}\n" +
                        "Size: ${p.sizeBytes ?: "?"} bytes",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}

@Composable
private fun ReadMethodCard(r: ExifReadResult) {
    val ok = r.latLong != null
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .background(if (ok) Color(0xFFE6F4EA) else Color(0xFFFCEAEA))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                r.method.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                r.method.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                r.status,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            r.latLong?.let { (lat, lon) ->
                Text(
                    "lat=$lat\nlon=$lon",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
            r.error?.let { e ->
                SelectionContainer {
                    Text(
                        e,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (r.sampleTags.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFFFFFF)),
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        r.sampleTags.forEach { (k, v) ->
                            Text(
                                "$k = $v",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Run every [ExifReadMethod] against [uri] and bundle the results. */
private fun readAllMethods(resolver: ContentResolver, uri: Uri, kind: PickerKind): PickResult {
    val info = queryUriMeta(resolver, uri)
    val results = ExifReadMethod.entries.map { method ->
        runExifMethod(resolver, uri, method)
    }
    return PickResult(
        pickerKind = kind,
        uri = uri,
        sizeBytes = info.size,
        mimeType = info.mime ?: resolver.getType(uri),
        displayName = info.displayName,
        readResults = results,
    )
}

private data class UriMeta(val size: Long?, val mime: String?, val displayName: String?)

private fun queryUriMeta(resolver: ContentResolver, uri: Uri): UriMeta {
    return try {
        resolver.query(uri, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return UriMeta(null, null, null)
            val sizeIdx = c.getColumnIndex("_size")
            val nameIdx = c.getColumnIndex("_display_name")
            UriMeta(
                size = if (sizeIdx >= 0) c.getLong(sizeIdx) else null,
                mime = resolver.getType(uri),
                displayName = if (nameIdx >= 0) c.getString(nameIdx) else null,
            )
        } ?: UriMeta(null, null, null)
    } catch (e: Exception) {
        Log.w(TAG, "queryUriMeta($uri) failed", e)
        UriMeta(null, null, null)
    }
}

@Composable
private fun GuidedTestCard(
    recorder: TestRecorder,
    onSkip: () -> Unit,
    onCancel: () -> Unit,
) {
    val current = recorder.currentPicker
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .background(Color(0xFFE8F0FE))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Guided test — step ${(recorder.currentStep + 1).coerceAtMost(recorder.totalSteps)} of ${recorder.totalSteps}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (current != null) {
                Text(
                    "Tap the highlighted button below to test ${current.label}. " +
                        "Pick the SAME known-GPS photo for each step so the comparison is apples-to-apples.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    current.behaviour,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "All steps recorded. Scroll down to see the report.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f)) {
                    Text("Skip step")
                }
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun ReportCard(
    markdown: String,
    onShare: () -> Unit,
    onClose: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .background(Color(0xFFEAF8EF))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "✓ Test report ready",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Logged to logcat under tag ExifDebug between TEST REPORT START / END markers. " +
                    "Share button copies the Markdown for pasting into a GitHub issue or feeding " +
                    "to an AI agent.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Text("Share / copy report")
                }
                OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) {
                    Text("Close")
                }
            }
            SelectionContainer {
                Text(
                    markdown,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}

/** Direct MediaStore query for the most recent image. Lets us hand a real
 *  MediaStore URI to setRequireOriginal() — the only kind it actually upgrades. */
private fun pickLatestMediaStoreImage(resolver: ContentResolver): Uri? {
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
    val projection = arrayOf(MediaStore.Images.Media._ID)
    return try {
        resolver.query(
            collection, projection, null, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            if (!c.moveToFirst()) return null
            val id = c.getLong(0)
            android.content.ContentUris.withAppendedId(collection, id)
        }
    } catch (e: Exception) {
        Log.w(TAG, "pickLatestMediaStoreImage failed", e)
        null
    }
}
