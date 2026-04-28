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
| **PickVisualMedia** | `ACTION_PICK_IMAGES` | Android's modern photo picker. Returns a redacted copy — EXIF GPS is stripped *by design*. `setRequireOriginal()` throws `UnsupportedOperationException` for picker URIs. **No recovery path.** |
| **OpenDocument** | `ACTION_OPEN_DOCUMENT` | Storage Access Framework. Returns a `com.android.providers.media.documents` URI; `openFileDescriptor` / `openInputStream` reads give the original file with **EXIF intact**. `setRequireOriginal()` throws `SecurityException` but you don't need it. |
| **GetContent** | `ACTION_GET_CONTENT` | Legacy on paper — but the system now routes it through a picker variant whose URI (`content://media/picker_get_content/…`) is treated as a MediaStore URI. **EXIF intact AND `setRequireOriginal()` succeeds.** Recommended when you need EXIF GPS from a user-picked photo. |
| **TakePicture** | `ACTION_IMAGE_CAPTURE` | Camera writes a fresh JPEG to a `FileProvider` URI we own. EXIF is whatever the camera app stamps. **GPS is gated by the camera app's own "Save location" toggle** — Pixel Camera defaults to OFF, independent of system location permission. If GPS is missing, the user has to flip that toggle. |
| **MediaStore (latest)** | `MediaStore.Images.Media` | Direct query. Returns the only kind of URI `setRequireOriginal()` actually upgrades. Requires `READ_MEDIA_IMAGES` (API 33+). |

## EXIF read methods compared

| Method | When to use |
|---|---|
| `openFileDescriptor` → `ExifInterface(FileDescriptor)` | **Recommended**. Most robust — lets ExifInterface mmap and seek freely. |
| `openInputStream` → `ExifInterface(InputStream)` | Stream-only. Works for most JPEGs but can't seek. |
| `openInputStream` → `ExifInterface(stream, STREAM_TYPE_FULL_IMAGE_DATA)` | Tells ExifInterface to scan the whole stream. Edge cases for HEIF / non-JPEG. |
| `MediaStore.setRequireOriginal(uri)` → FileDescriptor | Upgrades the URI to `?requireOriginal=1` first. Only succeeds on MediaStore URIs *and* with `ACCESS_MEDIA_LOCATION` granted. Throws `SecurityException` on picker-supplied URIs. |

## Findings (Pixel 10 Pro XL, Android 16)

| | PickVisualMedia | OpenDocument | GetContent | TakePicture | MediaStore (latest) |
|---|---|---|---|---|---|
| `openFileDescriptor → FD` | ✗ stripped | ✓ | ✓ | ✗ camera didn't stamp | ✓ |
| `openInputStream` | ✗ | ✓ | ✓ | ✗ | ✓ |
| `STREAM_TYPE_FULL_IMAGE_DATA` | ✗ | ✓ | ✓ | ✗ | ✓ |
| `setRequireOriginal → FD` | ✗ `UnsupportedOperationException` | ✗ `SecurityException` | ✓ | ✗ | ✓ |

Take-aways:

1. **Three pickers give you EXIF on Android 16:** `OpenDocument`, `GetContent`, and `MediaStore`. Pick whichever has the UX you want.
2. **`setRequireOriginal()` is the wrong tool for picker URIs** — it throws on `PickVisualMedia` (`UnsupportedOperationException`) and `OpenDocument` (`SecurityException`). Use it only against URIs you fetched from `MediaStore.Images.Media` directly, or via `GetContent` (whose URI now happens to be MediaStore-backed).
3. **`PickVisualMedia` is unrecoverable** if you need EXIF GPS — switch pickers, don't try to massage the URI.
4. **`TakePicture` only gets GPS if the camera app stamped it.** That's gated by the camera app's own "Save location" toggle (Pixel Camera defaults to OFF), independent of system location permission. Live device GPS at the moment of capture is a fine fallback for fresh photos.

## Build & run

```bash
git clone https://github.com/warting/exif_picker_lab
cd exif_picker_lab
./gradlew :app:installDebug
adb shell am start -n se.premex.exifdebug/.MainActivity
adb logcat -s ExifDebug
```

## Guided test mode

Tap **▶ Run guided test (5 pickers)** in the app to walk through every picker in order. The screen hides everything except the current step's single button; permissions are requested up front in one batch so the run isn't interrupted mid-test.

When all five pickers are recorded the screen shows a phone-friendly summary — one row per picker, green/red badge for "did *any* read method recover GPS", four small chips showing the per-method outcome. **No Markdown shown to the user** — humans don't want to read that on a phone.

### For AI agents

The full Markdown report (device facts + picker × read-method matrix + per-picker URI / authority / MIME / size / per-method status + error strings) is logged to logcat under tag `ExifDebug` between explicit start/end markers:

```bash
adb logcat -s ExifDebug | sed -n '/=== TEST REPORT START ===/,/=== TEST REPORT END ===/p'
```

If you're an AI coding agent helping the user root-cause an EXIF GPS bug:

1. Have the user run the guided test in this app.
2. Capture the bracketed logcat output above.
3. Use the matrix to identify which picker(s) preserve GPS on the user's device.
4. Cross-reference against the `Pickers.kt` annotations in this repo for *why* each picker behaves as it does.
5. Open a PR against the user's app changing whichever picker contract they're using to one that preserves EXIF (typically `ACTION_GET_CONTENT`), with the relevant logcat excerpt cited in the PR description as evidence.

See [AGENTS.md](AGENTS.md) for project-internal conventions.

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
