package se.premex.exifdebug

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface

/**
 * The four ways we can hand image bytes to [ExifInterface], plus a fifth that
 * upgrades the URI via [MediaStore.setRequireOriginal] before opening it.
 * Each is the same library reading the same file from a different angle —
 * differences in output are caused entirely by what the picker / provider
 * is willing to expose.
 */
enum class ExifReadMethod(val label: String, val description: String) {
    FILE_DESCRIPTOR(
        label = "openFileDescriptor → FileDescriptor",
        description = "Most robust. ExifInterface mmaps + seeks freely.",
    ),
    INPUT_STREAM(
        label = "openInputStream",
        description = "Stream-only. Works on most JPEGs.",
    ),
    INPUT_STREAM_FULL_IMAGE(
        label = "openInputStream STREAM_TYPE_FULL_IMAGE_DATA",
        description = "Tells ExifInterface to scan the whole stream.",
    ),
    REQUIRE_ORIGINAL_FD(
        label = "setRequireOriginal → FileDescriptor",
        description = "Upgrades the URI to ?requireOriginal=1 first. " +
            "Only works for MediaStore-backed URIs you got via MediaStore APIs " +
            "and have ACCESS_MEDIA_LOCATION granted for.",
    ),
}

/**
 * Result of one read attempt — keeps the GPS pair if found, plus a status
 * string and any error caught. Combined into [PickResult] which has one of
 * these per [ExifReadMethod].
 */
data class ExifReadResult(
    val method: ExifReadMethod,
    val latLong: Pair<Double, Double>?,
    val status: String,
    val error: String? = null,
    /** Selected non-GPS tags so we can tell whether the read succeeded but
     *  the file itself simply has no GPS, vs. the read partially failed. */
    val sampleTags: List<Pair<String, String>> = emptyList(),
)

private const val TAG = "ExifDebug"

/** Run a single [ExifReadMethod] against [uri], returning a structured result. */
fun runExifMethod(
    resolver: ContentResolver,
    uri: Uri,
    method: ExifReadMethod,
): ExifReadResult {
    return try {
        val exif: ExifInterface = when (method) {
            ExifReadMethod.FILE_DESCRIPTOR -> {
                val pfd = resolver.openFileDescriptor(uri, "r")
                    ?: return ExifReadResult(method, null, "openFileDescriptor returned null")
                pfd.use { ExifInterface(it.fileDescriptor) }
            }
            ExifReadMethod.INPUT_STREAM -> {
                val stream = resolver.openInputStream(uri)
                    ?: return ExifReadResult(method, null, "openInputStream returned null")
                stream.use { ExifInterface(it) }
            }
            ExifReadMethod.INPUT_STREAM_FULL_IMAGE -> {
                val stream = resolver.openInputStream(uri)
                    ?: return ExifReadResult(method, null, "openInputStream returned null")
                stream.use { ExifInterface(it, ExifInterface.STREAM_TYPE_FULL_IMAGE_DATA) }
            }
            ExifReadMethod.REQUIRE_ORIGINAL_FD -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    return ExifReadResult(
                        method, null,
                        "skipped — setRequireOriginal needs API 29+",
                    )
                }
                val upgraded = MediaStore.setRequireOriginal(uri)
                val pfd = resolver.openFileDescriptor(upgraded, "r")
                    ?: return ExifReadResult(method, null, "openFileDescriptor returned null")
                pfd.use { ExifInterface(it.fileDescriptor) }
            }
        }
        val latLon = exif.latLong
        val pair = latLon
            ?.takeUnless { it[0] == 0.0 && it[1] == 0.0 }
            ?.let { it[0] to it[1] }
        val status = when {
            pair != null -> "✓ GPS recovered"
            latLon != null -> "✗ GPS in file but zeroed (stripped/redacted)"
            else -> "✗ No GPS tag in EXIF"
        }
        Log.i(TAG, "method=$method uri=$uri status=$status")
        ExifReadResult(
            method = method,
            latLong = pair,
            status = status,
            sampleTags = listOf(
                "DateTimeOriginal" to exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL).orEmpty(),
                "Make" to exif.getAttribute(ExifInterface.TAG_MAKE).orEmpty(),
                "Model" to exif.getAttribute(ExifInterface.TAG_MODEL).orEmpty(),
                "ImageWidth" to exif.getAttribute(ExifInterface.TAG_IMAGE_WIDTH).orEmpty(),
                "ImageLength" to exif.getAttribute(ExifInterface.TAG_IMAGE_LENGTH).orEmpty(),
            ).filter { it.second.isNotBlank() },
        )
    } catch (e: SecurityException) {
        Log.w(TAG, "method=$method uri=$uri SecurityException", e)
        ExifReadResult(
            method = method,
            latLong = null,
            status = "✗ SecurityException",
            error = e.message,
        )
    } catch (e: Exception) {
        Log.w(TAG, "method=$method uri=$uri threw", e)
        ExifReadResult(
            method = method,
            latLong = null,
            status = "✗ ${e::class.simpleName}",
            error = e.message,
        )
    }
}
