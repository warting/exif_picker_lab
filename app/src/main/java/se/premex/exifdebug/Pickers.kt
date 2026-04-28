package se.premex.exifdebug

/**
 * Catalog of ways to ask the user for a photo on Android — each with a one-
 * line summary of why we expect EXIF GPS to survive (or not). The [behaviour]
 * is shown next to the picker in the UI so users don't need to read the
 * Android docs to understand the result.
 */
enum class PickerKind(val label: String, val behaviour: String) {
    PICK_VISUAL_MEDIA(
        label = "PickVisualMedia",
        behaviour = "System photo picker (ACTION_PICK_IMAGES). Returns a " +
            "redacted copy — EXIF GPS is stripped by design and there is " +
            "no recovery path. setRequireOriginal() throws " +
            "UnsupportedOperationException for picker URIs.",
    ),
    OPEN_DOCUMENT(
        label = "OpenDocument",
        behaviour = "Storage Access Framework (ACTION_OPEN_DOCUMENT). " +
            "Returns a com.android.providers.media.documents URI; reading " +
            "via openFileDescriptor / openInputStream gives the original " +
            "file with EXIF intact. setRequireOriginal() throws " +
            "SecurityException (extra ACTION_OPEN_DOCUMENT-grade permission " +
            "needed) — but you don't need it.",
    ),
    GET_CONTENT(
        label = "GetContent",
        behaviour = "Legacy chooser (ACTION_GET_CONTENT) — but the system " +
            "now routes it through a picker variant whose URI " +
            "(content://media/picker_get_content/…) is treated as a " +
            "MediaStore URI. EXIF is intact; setRequireOriginal() also " +
            "succeeds. Recommended when you need EXIF GPS from a user-" +
            "picked photo.",
    ),
    TAKE_PICTURE(
        label = "TakePicture",
        behaviour = "ACTION_IMAGE_CAPTURE into a FileProvider URI we own. " +
            "EXIF is whatever the camera app stamps. GPS is gated by the " +
            "camera app's own 'Save location' toggle, which is independent " +
            "from system location permission — Pixel Camera defaults to " +
            "OFF. If GPS is missing, ask the user to enable it in their " +
            "camera app's settings.",
    ),
    MEDIASTORE_LATEST(
        label = "MediaStore (latest)",
        behaviour = "Direct MediaStore query for the latest image. URIs come " +
            "from MediaStore.Images.Media.EXTERNAL_CONTENT_URI — the only " +
            "kind setRequireOriginal() actually upgrades. Requires " +
            "READ_MEDIA_IMAGES on API 33+.",
    ),
}
