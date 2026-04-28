# EXIF Picker Lab

> **Why does my picked photo have no EXIF GPS on Android?** Because the picker stripped it. This app shows you which combinations of *picker* and *EXIF read method* actually preserve `GPSLatitude` / `GPSLongitude` on real Android devices — side by side, on one screen, with explanatory annotations.

[![Android](https://img.shields.io/badge/Android-API%2026%2B-3DDC84?logo=android)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack-Compose-4285F4?logo=jetpackcompose)](https://developer.android.com/jetpack/compose)

## What this is

A reference Android app that pits **5 image pickers** against **4 EXIF read methods** so you can see which combinations preserve GPS metadata on the device you actually ship to. Tap a picker, pick the same known-GPS photo, watch the read-method cards turn green or red.

Built because the documentation on this is scattered and the failure modes are silent — `PickVisualMedia` strips EXIF GPS by design, `setRequireOriginal()` throws `SecurityException` on the wrong URI kind, `OpenDocument` sometimes routes through redacting providers. There is no single source of truth that maps picker → EXIF availability across devices, so this app exists to *measure* on yours.

## Demo

Pick the same known-GPS photo through each picker; each pick is read four ways:

```
┌──────────────────────────────────────────────────┐
│ Picker: GetContent                               │
│ URI: content://com.android.providers.media...    │
│ Authority: com.android.providers.media.documents │
│ Size: 4 832 110 bytes  MIME: image/jpeg          │
├──────────────────────────────────────────────────┤
│ openFileDescriptor → FileDescriptor              │
│ ✓ GPS recovered                                  │
│ lat=59.236123  lon=17.982456                     │
├──────────────────────────────────────────────────┤
│ openInputStream                                  │
│ ✓ GPS recovered                                  │
├──────────────────────────────────────────────────┤
│ setRequireOriginal → FileDescriptor              │
│ ✗ SecurityException                              │
│ Permission Denial: reading ?requireOriginal=1    │
│ requires ACTION_OPEN_DOCUMENT or related APIs    │
└──────────────────────────────────────────────────┘
```

## Pickers compared

| Picker | Contract | What it does |
|---|---|---|
| **PickVisualMedia** | `ACTION_PICK_IMAGES` | Android's modern photo picker. Returns a redacted copy — EXIF GPS is stripped *by design*. `setRequireOriginal()` does not apply. No path to recover GPS through this picker. |
| **OpenDocument** | `ACTION_OPEN_DOCUMENT` | Storage Access Framework. Should return DocumentProvider URIs to original files; on some Pixel devices routes through a redacting provider in practice. |
| **GetContent** | `ACTION_GET_CONTENT` | Legacy chooser — the system asks an app (Files / Gallery / Photos) to provide the image. The selected app's URI typically exposes the original bytes intact. |
| **TakePicture** | `ACTION_IMAGE_CAPTURE` | Camera writes a fresh JPEG to a `FileProvider` URI we own. EXIF is whatever the camera app stamped — modern camera apps include GPS when location services are on *and* the camera app's own "save location" toggle is on. |
| **MediaStore (latest)** | `MediaStore.Images.Media` | Direct query. Returns the only kind of URI `setRequireOriginal()` actually upgrades. Requires `READ_MEDIA_IMAGES` (API 33+). |

## EXIF read methods compared

| Method | When to use |
|---|---|
| `openFileDescriptor` → `ExifInterface(FileDescriptor)` | **Recommended**. Most robust — lets ExifInterface mmap and seek freely. |
| `openInputStream` → `ExifInterface(InputStream)` | Stream-only. Works for most JPEGs but can't seek. |
| `openInputStream` → `ExifInterface(stream, STREAM_TYPE_FULL_IMAGE_DATA)` | Tells ExifInterface to scan the whole stream. Edge cases for HEIF / non-JPEG. |
| `MediaStore.setRequireOriginal(uri)` → FileDescriptor | Upgrades the URI to `?requireOriginal=1` first. Only succeeds on MediaStore URIs *and* with `ACCESS_MEDIA_LOCATION` granted. Throws `SecurityException` on picker-supplied URIs. |

## Findings (Pixel 10, Android 16)

| | PickVisualMedia | OpenDocument | GetContent | TakePicture | MediaStore + AML |
|---|---|---|---|---|---|
| EXIF GPS | ✗ stripped | ✗ redacted | ✓ preserved | ✓ if camera stamps | ✓ original |

`setRequireOriginal()` is the **wrong tool for picker URIs** — it errors instead of helping. Use it only against URIs you fetched from `MediaStore.Images.Media` directly.

## Build & run

```bash
git clone https://github.com/warting/exif_picker_lab
cd exif_picker_lab
./gradlew :app:installDebug
adb shell am start -n se.premex.exifdebug/.MainActivity
adb logcat -s ExifDebug
```

## Guided test mode

Tap **▶ Run guided test (5 pickers)** in the app to walk through every picker in order and produce a single Markdown report at the end. The report:

- Captures device facts (manufacturer, model, Android version, fingerprint).
- Renders a picker × read-method matrix with `✓` / `✗` per cell.
- Includes per-picker URI / authority / MIME / size details.
- Logs the entire thing to logcat between `=== TEST REPORT START ===` / `=== TEST REPORT END ===` markers so it can be captured with `adb logcat -s ExifDebug`.
- Has a Share button so you can paste it into a GitHub issue or hand it to an AI agent (Claude Code, Cursor, etc.) and ask it to root-cause the findings.

This is the recommended way to file device-specific reports — it produces a uniform format that's easy to compare across submissions.

## Permissions

| Permission | Why |
|---|---|
| `ACCESS_MEDIA_LOCATION` | Required (API 29+) for `setRequireOriginal()` to ever return GPS. Runtime-granted; in-app button asks. |
| `READ_MEDIA_IMAGES` | Required (API 33+) for the MediaStore-query picker. |
| `CAMERA` | Required for the TakePicture path. |

## When to use each picker in production

```text
Need user-facing photo pick?
├── Need original EXIF (GPS, date)?
│   ├── Yes → use ACTION_GET_CONTENT
│   └── No  → use PickVisualMedia (zero permissions, polished UX)
└── Need to enumerate / list photos?
    └── Use MediaStore + setRequireOriginal + ACCESS_MEDIA_LOCATION
```

## Project layout

```
exif_picker_lab/
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/se/premex/exifdebug/
│       │   ├── MainActivity.kt        ← UI, picker launchers
│       │   ├── ExifReadMethod.kt      ← the 4 ways to read EXIF
│       │   └── Pickers.kt             ← annotations per picker
│       └── res/xml/file_paths.xml     ← FileProvider for TakePicture
├── README.md                          ← you are here
├── AGENTS.md                          ← project map for AI coding agents
├── llms.txt                           ← machine-readable summary for LLMs
└── LICENSE
```

## Contributing

PRs welcome — especially device-specific findings. Open an issue with:

1. Device + Android version
2. Which picker
3. Which read method
4. Result (✓ / ✗) + EXIF dump from logcat (`adb logcat -s ExifDebug`)

## License

Apache 2.0 — see [LICENSE](LICENSE).

## Keywords

Android EXIF · `PickVisualMedia` strips GPS · `setRequireOriginal` `SecurityException` · `ACCESS_MEDIA_LOCATION` · `ACTION_OPEN_DOCUMENT` vs `ACTION_GET_CONTENT` · ExifInterface FileDescriptor · GPSLatitude GPSLongitude · Android 13 photo picker EXIF · `MediaStore.Images.Media` · `requireOriginal=1` · Jetpack Compose
