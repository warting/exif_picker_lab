# AGENTS.md

> Project map for AI coding agents (Claude Code, GitHub Copilot, Cursor, Aider, etc.)
> following the [agents.md](https://agentsmd.net/) convention.

## What this project is

An Android reference / measurement app: pick a photo through any of 5 Android image-picker contracts, then run the resulting URI through 4 EXIF read methods. The UI shows which combinations recover GPS metadata.

It is **not** a library — there is nothing to depend on. It's a single-Activity Compose app intended to be cloned, run on the device under question, and used to settle "does picker X preserve EXIF on device Y" questions empirically.

## Quick orientation

| Path | What it does |
|---|---|
| `app/src/main/java/se/premex/exifdebug/MainActivity.kt` | Single Activity. All UI. Holds the picker `ActivityResultContract` launchers and renders a `LazyColumn` with the per-pick result cards. |
| `app/src/main/java/se/premex/exifdebug/ExifReadMethod.kt` | Enum of 4 EXIF read approaches + the function that runs one against a URI and returns a structured result. |
| `app/src/main/java/se/premex/exifdebug/Pickers.kt` | Enum of the 5 pickers with one-liner annotations explaining each picker's behaviour. Shown as labels in the UI. |
| `app/src/main/AndroidManifest.xml` | Permissions + `FileProvider` for `TakePicture`. |
| `app/src/main/res/xml/file_paths.xml` | `FileProvider` paths config. |

## Conventions

- **Kotlin 2.3, Jetpack Compose, Material 3.** No XML layouts. No View-based code.
- **No DI framework.** This is a single-screen demo; pulling in Hilt would be overkill. Pickers are state-hoisted at the top of `ExifDebugScreen()`.
- **Logcat tag is `ExifDebug`.** Every picker decision and read result is mirrored to logcat so reproductions can be captured with `adb logcat -s ExifDebug`. Don't use Toasts for diagnostic info — use the on-screen cards (visible to the user) and Log.i/Log.w (for capture).
- **No third-party libraries beyond what's already in `gradle/libs.versions.toml`.** Coil for image preview, AndroidX ExifInterface, ActivityResult contracts. Adding a dependency requires a strong case in the PR description.
- **Comments explain *why*, not *what*.** Especially around quirks like "why don't we call setRequireOriginal" and "why GetContent works where OpenDocument doesn't on Pixel".

## How to add a new picker

1. Add a new entry to `PickerKind` in `Pickers.kt` with a one-line `behaviour` description.
2. Add a `rememberLauncherForActivityResult` for the new contract in `ExifDebugScreen`.
3. Add a button calling `.launch(...)` to one of the existing picker rows.
4. Route the result through `process(uri, PickerKind.YOUR_NEW_KIND)` — the rest of the pipeline (read methods, UI cards, logcat) is automatic.

## How to add a new EXIF read method

1. Add a new entry to `ExifReadMethod` in `ExifReadMethod.kt` with `label` + `description`.
2. Add a `when` branch in `runExifMethod` that produces an `ExifInterface` instance.
3. The rest is automatic — `runAllMethods` iterates `ExifReadMethod.entries`.

## What not to do

- Don't refactor the UI into separate ViewModel / state classes. The state is local to one screen and trivial; a ViewModel would obscure rather than clarify.
- Don't merge picker-specific result rendering into custom composables per picker. The per-pick UI is shared deliberately so users can compare "same data through different reads" without code-duplication noise.
- Don't introduce Compose `MutableState` outside `ExifDebugScreen`. This app's appeal is that you can read the entire flow top-to-bottom in `MainActivity.kt`.

## Build, install, test

```bash
./gradlew :app:installDebug
adb shell am start -n se.premex.exifdebug/.MainActivity
adb logcat -s ExifDebug
```

There are no automated tests. Validation is empirical: run the app, pick a known-GPS photo, check the result.

## Common questions you may encounter

- "Why does PickVisualMedia not show GPS?" → it strips by design; this is documented Android behaviour.
- "Why does setRequireOriginal throw?" → only valid on MediaStore-backed URIs, not picker URIs.
- "Why is GetContent deprecated and we still use it?" → it's deprecated *for the photo-picker use case*, but the photo picker can't preserve EXIF GPS — so when EXIF matters, GetContent is the right choice despite the deprecation.

## Consuming a guided-test report (for AI agents)

When the user runs the in-app guided test, a Markdown report is logged to logcat under tag `ExifDebug` between explicit markers. Capture and parse it as follows:

```bash
adb logcat -s ExifDebug | sed -n '/=== TEST REPORT START ===/,/=== TEST REPORT END ===/p'
```

The report contains:
- Device facts (manufacturer, model, Android version, fingerprint) — useful for narrowing PR scope.
- A picker × read-method matrix (`✓` / `✗` / `—` per cell) — read this to identify the working combinations on this device.
- Per-picker URI, authority, MIME, size, display name, and per-method status with full error messages — useful for diagnosing edge cases.

Suggested workflow when a user shows you this output and asks you to fix their app's EXIF flow:

1. Find the first picker in the matrix with a green column for `FD` (FileDescriptor read). That's the picker their production app should use.
2. If the user's app is using `PickVisualMedia` or `OpenDocument`, open a PR replacing it with `ActionResultContracts.GetContent()` and a `"image/*"` MIME filter. Cite the matrix rows in the PR description as evidence.
3. Confirm the read path in their app uses `openFileDescriptor` + `ExifInterface(FileDescriptor)`, not `setRequireOriginal()` (which is the wrong tool for picker URIs and will throw `SecurityException`).
