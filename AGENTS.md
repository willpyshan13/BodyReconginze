# Repository Guidelines

## Project Structure & Module Organization

The C++ motion-analysis core is in `src/` with its public ABI in `include/` and tests in `tests/`. `android/` contains Kotlin/JNI bindings; `ios/` contains Swift bindings. The Android demo follows Gradle conventions in `demo-android/src/main/`, with pose adapters in `java/com/bodyrecongnize/demo/pose/`, coach flow and history in `java/com/bodyrecongnize/demo/coach/`, and JVM tests in `demo-android/src/test/`. `CoachActivity.kt` hosts the multi-exercise coach. `LabActivity.kt` and `lab/` implement the six-screen AI validation lab, actual frame extraction, event provenance and retained analysis runs; pure lab behavior tests are in `demo-android/src/test/`. Bundled ONNX/TFLite models, exact-time video frames and legacy unused voice assets are in `demo-android/src/main/assets/`; bundled videos are in `demo-android/src/main/res/raw/`. `assets/` contains source material and provenance, and `tools/` contains reproducible media/model preparation utilities.

## Build, Test, and Development Commands

Use JDK 17 and an Android SDK for Gradle commands. Run `make test` for C++ behavior tests, `swift build` for the Swift package, `./gradlew :demo-android:assembleDebug` for the APK, and `./gradlew :demo-android:testDebugUnitTest` for pose-coordinate and latency-statistics tests. Install with `adb install -r demo-android/build/outputs/apk/debug/demo-android-debug.apk`. See README.md for SDK requirements, device launch commands, and media regeneration.

## Coding Style & Naming Conventions

Follow the formatter and linter adopted with the first implementation language; commit their configuration so the rules are reproducible. Until then, use consistent indentation within each file, descriptive names, and names that match the language's usual conventions. Keep filenames and directory names predictable, and avoid mixing unrelated changes in one module.

## Testing Guidelines

C++ behavior tests use the Makefile/CTest entry points. Android pure-logic tests use JUnit 4 through Gradle. Verify inference and rendered overlays on a connected device when changing model preprocessing, keypoint mappings, or playback; JVM tests alone do not validate model outputs or Android compatibility. Compare models on the same input frames and distinguish inference latency from the configured playback sampling rate. Do not claim accuracy from unlabelled video or compare uncalibrated confidence scores as accuracy.

## Commit & Pull Request Guidelines

No Git history is available here, so there is no established commit-message convention. Use short, imperative subjects such as `Add input validation`, with a body when the reason is not obvious. Pull requests should explain the change, include the commands run and their results, link relevant issues, and attach screenshots for visible interface changes.

## UI Design Approval (Required)

When the user requests a feature, first briefly analyze its purpose, usage scenario, scope, and key constraints, then directly produce a concrete visual mockup or interactive design preview in the same turn. Do not stop after analysis, merely promise to design later, or ask whether the user wants a mockup. Use available context and reasonable assumptions; request clarification only when missing information prevents a meaningful design. The standard flow is: feature request → analysis → visual design → user approval → implementation and verification. Keep design samples clearly distinct from actual measured or recorded data.

Before implementing any new UI, UI redesign, or material layout/interaction change, first produce a reviewable design proposal with visual previews, screen states, and navigation. Obtain explicit approval from the user for that proposal before modifying application UI code, packaging it, or installing it on a device. Silence is not approval. Design prototypes and design documents may be created before approval; they must remain separate from production application code. If the proposed design changes materially after approval, present the revised portion for approval before implementing it. Preserve the user's preference for silent operation: no automatic speech, audio, or sound effects.
