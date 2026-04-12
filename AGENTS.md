# Repository Guidelines

## Project Structure & Module Organization
This repository is a two-module Android project:

- `app/`: main application module. Kotlin sources live in `app/src/main/java/com/vyy/imageprocessingpractice/`, UI resources in `app/src/main/res/`, local unit tests in `app/src/test/`, and instrumentation tests in `app/src/androidTest/`.
- `openCV460/`: vendored OpenCV Android SDK module. Treat it as third-party code and avoid edits unless the change is required for SDK integration.

Most app logic is still centered in `MainActivity.kt`, while image-processing routines are grouped under `processes/` and shared helpers live under `utils/`.

## Build, Test, and Development Commands
Run commands from the repository root:

- `./gradlew :app:assembleDebug`: build the debug APK.
- `./gradlew :app:testDebugUnitTest`: run JVM unit tests in `app/src/test`.
- `./gradlew :app:connectedDebugAndroidTest`: run device/emulator tests in `app/src/androidTest`.
- `./gradlew :app:lintDebug`: run Android lint for the app module.

Use Android Studio for interactive debugging and CameraX/device testing.

## Coding Style & Naming Conventions
Use Kotlin with 4-space indentation and idiomatic Android naming:

- `PascalCase` for classes and files, for example `MainActivity`, `LungSegmentation`.
- `lowerCamelCase` for functions and properties.
- `ALL_CAPS` for constants in `utils/Constants.kt`.
- Resource names should stay lowercase with underscores, for example `activity_main.xml`.

Keep new processing code in `processes/` instead of growing `MainActivity` further. Note that the project currently mixes package names (`com.vyy.imageprocessingpractice`) with Gradle namespace/application IDs; do not rename these casually.

## Testing Guidelines
Unit tests use JUnit4. Instrumentation tests use AndroidX JUnit and Espresso. Name test files after the class under test, such as `ThresholdTest`, and prefer descriptive test names like `otsuThreshold_returnsBinaryMask`.

Add at least one unit test for new algorithm logic. Add instrumentation coverage when a change affects camera, permissions, or UI flows.

## Commit & Pull Request Guidelines
Recent history uses short, sentence-style subjects such as `OpenCV 4.6 added.` and `Lung segmentation updated.` Keep commits focused and use imperative summaries.

Pull requests should include:

- a short description of the change,
- linked issue or task when applicable,
- screenshots or screen recordings for UI/image-output changes,
- notes about device/emulator coverage for camera-related behavior.

Do not commit `local.properties`, keystores, or build outputs.
