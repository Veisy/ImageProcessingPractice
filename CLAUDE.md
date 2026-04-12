# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Android app (Kotlin) for studying image processing algorithms. Uses OpenCV 4.6.0 for advanced operations and CameraX for camera capture. All UI logic currently lives in MainActivity (no MVVM/MVI architecture).

## Build & Run

```bash
# Build debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew test

# Run instrumented tests (requires device/emulator)
./gradlew connectedAndroidTest

# Clean build
./gradlew clean assembleDebug
```

Requires JDK 17. AGP 8.8.0, Kotlin 1.9.25.

## Modules

- **`:app`** — Main application module (namespace: `com.vyy.imagemosaicing`, applicationId: `com.vyy.imagepixelate`)
- **`:openCV460`** — Local OpenCV 4.6.0 library module (namespace: `org.opencv`), uses CMake for native code

Note: The namespace and applicationId don't match the actual source package `com.vyy.imageprocessingpractice` — this is intentional legacy naming.

## Architecture

- **No formal architecture pattern** — MainActivity handles UI, permissions, camera, gallery, and image processing orchestration
- **Image processing algorithms** are modular pure functions in `app/src/main/java/com/vyy/imageprocessingpractice/processes/`
- **Coroutines** (`lifecycleScope.async(Dispatchers.Default)`) used for async processing
- **ViewBinding** for view access
- **ActivityResultContracts** for permissions and intents
- Undo stack: `ArrayDeque`-based image history (max 20 entries)

## Key Source Paths

- Image processing algorithms: `app/src/main/java/com/vyy/imageprocessingpractice/processes/`
  - ColorConversions, SpatialFilters, FrequencyFilters, Threshold, Pixelation, LungSegmentation, Resize, ImageArithmetic, Crop, Rotation, SpecialImageOperations
- Constants (filter type IDs): `app/src/main/java/com/vyy/imageprocessingpractice/utils/Constants.kt`
- App entry point: `ImageProcessingPracticeApplication.kt` (configures CameraX for back camera only)

## Dependencies

- CameraX 1.5.1 (camera capture)
- Glide 4.16.0 (image loading)
- OpenCV 4.6.0 (local module, native via CMake)
- compileSdk/targetSdk: 35, minSdk: 23
