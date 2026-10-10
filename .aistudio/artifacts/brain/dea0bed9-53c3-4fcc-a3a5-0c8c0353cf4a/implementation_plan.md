# ASR Audio Engine Performance & Overlay Refactoring Plan

A comprehensive architectural plan to resolve runtime log errors, optimize the ASR model extraction pipeline (~134 MB archive), eliminate main-thread jank, fix floating overlay touch/drag responsiveness, and provide resilient offline translation fallbacks.

---

### User Review & Critical Decisions

> [!IMPORTANT]
> The architectural directions were confirmed during interactive clarification and will guide this implementation:

- **Confirmed Decision 1 (ASR Model Extraction)**: Use 64 KB buffered streams (`BufferedInputStream` + `BufferedOutputStream`) with staged progress updates (`Downloading -> Extracting(entryName) -> Ready`) strictly offloaded to `Dispatchers.IO`.
- **Confirmed Decision 2 (Floating Subtitle Overlay)**: Retain transparent touch pass-through (`FLAG_NOT_TOUCH_MODAL` with precise touch bounds or touch-passthrough mode) and equip the subtitle window with a distinct interactive drag header with handle grip, collapse/expand toggle, and lock toggle.
- **Confirmed Decision 3 (Firebase / ML Kit Translation Fallbacks)**: Implement a self-contained offline translation mock/dictionary fallback when `google-services.json` or Firebase ML Kit services are unavailable on device, avoiding log spam and translation pipeline failures.

---

### 1. Overview & Core Concept

- **What It Does**: Hardens the live audio translation app by fixing stutter/jank (Davey frame drops), accelerating multi-lingual speech model preparation, making the floating subtitle overlay smooth and responsive to touch gestures without blocking underlying apps, and ensuring clean service execution across Android 14+.
- **Target Persona**: Users watching foreign videos, joining live multilingual audio streams, or needing real-time subtitles across Android apps without lag or visual hitches.
- **Key Value**: Instantaneous, buttery-smooth subtitle positioning, rock-solid ASR model initialization, zero UI thread blocking during file I/O or storage checks, and error-free execution.

---

### 2. User Experience & Visual Design

#### Key User Flows
1. **Model Management**:
   - The user opens Model Management. Storage calculations and file readiness checks execute seamlessly in the background without UI stutter.
   - When downloading the ~134 MB multilingual model, the UI displays clear staged progress: download percentage followed by extracting filename stages (`Extracting: encoder-epoch-75...`).
   - On completion, the model card immediately reflects `Ready` with test recognition availability.

2. **Floating Subtitle Overlay**:
   - The overlay appears over target apps. It displays a sleek semi-transparent frosted header with a dedicated drag grip, minimize/collapse button, and quick-lock button.
   - Dragging the handle moves the window smoothly across screen boundaries using immediate `WindowManager.updateViewLayout` updates with throttled recomposition.
   - When locked or when tapping outside the drag handle, touches pass through effortlessly to the underlying video/app.

3. **Live Audio & Translation Pipeline**:
   - Audio capture (internal playback or mic) feeds into `SpeechRecognizerEngine` without audio driver thread contention.
   - If ML Kit translation services fail or lack Google Play services/Firebase config, the engine gracefully falls back to the local dictionary translator without throwing exceptions or showing red crash banners.

#### Visual Identity & Material 3 Styling
- **Theme**: Premium Dark Charcoal (`#121316`) with Dynamic Indigo accents (`#4F46E5`), Cyan transcription badges, and Amber translation highlights.
- **Floating Pill/Box Design**: Rounded 16.dp corners, elevation shadow, blur backdrop effect, compact typography (14.sp to 16.sp legible high-contrast text with outline stroke for readability against diverse video backgrounds).

---

### 3. Key Product Decisions & Trade-Offs

- **Decision 1: 64 KB Buffered Tar.bz2 Decompression on `Dispatchers.IO`**
  - *Chosen Approach*: Wrap `BZip2CompressorInputStream` in a 64 KB `BufferedInputStream` and write out entries using 64 KB `BufferedOutputStream`. Emit periodic progress events (`ModelDownloadState.Extracting(entryName)`) to the ViewModel StateFlow.
  - *Why*: Apache Commons `BZip2CompressorInputStream` unbuffered byte-by-byte reads are notoriously CPU-heavy and slow. 64 KB buffering matches Linux page cache chunks and cuts extraction time by 3x–5x while preventing ANRs.
  - *Alternatives Considered*: Direct NIO memory-mapped extraction (requires native decompression bridges which are brittle with bz2).

- **Decision 2: Dedicated Drag Handle vs. Full-Box Dragging**
  - *Chosen Approach*: Implement a dedicated pill-shaped control header at the top of the floating subtitle window. Drag gestures on this header move the overlay, while the subtitle text area can toggle click-through transparency.
  - *Why*: Avoids conflict between drag gestures and subtitle text scrolling or underlying video touches, providing intuitive window control.

- **Decision 3: Eliminating Main Thread StatFs and File Scans**
  - *Chosen Approach*: Replace synchronous `File.exists()`, `File.length()`, and `StatFs.availableBytes` calls in ViewModel initialization and Composable rendering with asynchronous IO coroutines (`withContext(Dispatchers.IO)`), caching results in UI state.
  - *Why*: Eliminates the observed 2,300ms+ Davey drops and 136 skipped frames reported by Choreographer.

- **Decision 4: Resilient Translation Fallback**
  - *Chosen Approach*: Wrap `LocalTranslatorEngine` with a safety layer: when ML Kit translator model download or Firebase fails (or `google-services.json` is missing), route to an integrated offline translation module with phonetic/dictionary fallback.
  - *Why*: Guarantees zero crash conditions on fresh installs or emulators lacking Play Services.

---

### 4. Technical Architecture & Data Strategy

```
┌────────────────────────────────────────────────────────┐
│               AudioTranslatorViewModel                 │
│   (StateFlow: storageMb, modelStates, isOverlayActive) │
└───────────▲───────────────────────────────▲────────────┘
            │                               │
            │ State updates                 │ IO Dispatchers
┌───────────┴──────────────┐   ┌────────────┴─────────────┐
│  ModelManagementScreen   │   │  FloatingSubtitleService │
│  - Storage cards         │   │  - WindowManager View    │
│  - Download/Extract UI   │   │  - LayoutParams config   │
└──────────────────────────┘   └────────────┬─────────────┘
                                            │
                               ┌────────────▼─────────────┐
                               │  DraggableSubtitleBox    │
                               │  - Drag Header (Grip)    │
                               │  - Text Display Pane     │
                               │  - Touch pass-through    │
                               └──────────────────────────┘
                                            │
┌───────────────────────────────────────────┴─────────────┐
│                 Core Pipeline (IO / Audio)              │
│  ┌───────────────────────┐   ┌────────────────────────┐ │
│  │   ModelManager        │   │ SpeechRecognizerEngine │ │
│  │   - 64KB bz2 extract  │   │ - Native mlock safe    │ │
│  │   - Progress updates  │   │ - Sherpa-ONNX stream   │ │
│  └───────────────────────┘   └────────────────────────┘ │
│  ┌────────────────────────────────────────────────────┐ │
│  │   LocalTranslatorEngine                            │ │
│  │   - Safe MLKit + Fallback offline dictionary       │ │
│  └────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────┘
```

#### Detailed Execution Phases:

1. **Phase 1: Model Extraction & Storage Scan IO Offloading**
   - Refactor `ModelManager.extractTarBz2` to use 64 KB buffers for both read and write streams.
   - Update `ModelDownloadState` to support `Extracting(entryName: String, percent: Float)`.
   - Update `ModelManager.isModelReady` and `getAvailableStorageMb` to always run on `Dispatchers.IO`.
   - Update `AudioTranslatorViewModel` to fetch storage and model readiness asynchronously without blocking ViewModel creation.

2. **Phase 2: Floating Overlay Touch & WindowManager Optimization**
   - Configure `FloatingSubtitleService` with optimal `WindowManager.LayoutParams`: `FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN or FLAG_NOT_TOUCH_MODAL`.
   - Refine `DraggableSubtitleBox.kt`:
     - Add a dedicated Drag Bar header with handle icon and close/lock buttons.
     - Ensure drag gesture pointer consumption (`change.consume()`) coordinates with `onDrag(deltaX, deltaY)` to smoothly invoke `windowManager.updateViewLayout`.
     - Support touch pass-through when overlay is locked.

3. **Phase 3: Native Logs, DRM & Translation Safety**
   - Audit `SpeechRecognizerEngine` native initialization: catch mlock and filter init warnings gracefully with fallback thread priorities.
   - Guard `LocalTranslatorEngine` with an offline mock dictionary translation mode when Firebase/ML Kit models cannot be fetched.
   - Verify `AudioCaptureService` foreground service startup sequence on Android 14+.

4. **Phase 4: Verification & Compilation**
   - Run `gradle :app:testDebugUnitTest` to verify unit and Robolectric tests pass.
   - Run `compile_applet` to ensure pristine, warning-free compilation.
