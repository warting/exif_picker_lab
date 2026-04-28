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
        behaviour = "System photo picker (ACTION_PICK_IMAGES). " +
            "Returns a redacted copy — EXIF GPS is stripped by design. " +
            "setRequireOriginal() does not apply. Source: Android docs.",
    ),
    OPEN_DOCUMENT(
        label = "OpenDocument",
        behaviour = "Storage Access Framework (ACTION_OPEN_DOCUMENT). " +
            "Returns a DocumentProvider URI to the original file in theory, " +
            "but on Pixel devices may route through a redacting provider in practice.",
    ),
    GET_CONTENT(
        label = "GetContent",
        behaviour = "Legacy chooser (ACTION_GET_CONTENT). The system asks an " +
            "app — Files / Gallery / Photos — to provide the image. The " +
            "selected app's URI typically exposes the original bytes intact.",
    ),
    TAKE_PICTURE(
        label = "TakePicture",
        behaviour = "ACTION_IMAGE_CAPTURE into a FileProvider URI we own. " +
            "EXIF is whatever the camera app stamps — modern Android camera " +
            "apps include GPS when location services are on.",
    ),
    MEDIASTORE_LATEST(
        label = "MediaStore (latest)",
        behaviour = "Direct MediaStore query for the latest image. URIs come " +
            "from MediaStore.Images.Media.EXTERNAL_CONTENT_URI — the only " +
            "kind setRequireOriginal() actually upgrades. Requires " +
            "READ_MEDIA_IMAGES on API 33+.",
    ),
}
