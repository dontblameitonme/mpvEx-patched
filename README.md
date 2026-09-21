![banner](fastlane/metadata/android/en-US/images/featureGraphic.png)

# mpvExtended (Patched Edition)
[![GitHub release (latest SemVer)](https://img.shields.io/github/v/release/marlboro-advance/mpvex.svg?logo=github&label=GitHub&cacheSeconds=3600)](https://github.com/marlboro-advance/mpvex/releases/latest)
[![GitHub all releases](https://img.shields.io/github/downloads/marlboro-advance/mpvex/total?logo=github&cacheSeconds=3600)](https://github.com/marlboro-advance/mpvex/releases/latest)

> This repository is a custom patched edition of [mpvExtended](https://github.com/marlboro-advance/mpvEx), featuring extensive refactoring and enhancements for **smart bilingual subtitle separation with independent rendering control**, **dynamic height-based volume gesture sensitivity**, **synchronized bilingual subtitle delay & speed**, and **deep performance optimizations eliminating cold-start and UI jank**.

---

## 🛠️ Patched Features & Maintenance Guide

### 1. Smart Bilingual Subtitle Splitting & Independent Secondary Subtitle Rendering
* **Background & Technical Challenges**:
  * Native `libmpv` **lacks** properties such as `secondary-sub-font`, `secondary-sub-color`, and `secondary-sub-font-size`. Setting them previously failed silently.
  * When secondary subtitles were exported as plain text SRT, mpv treated them as unstyled text and forced them to inherit primary subtitle `sub-*` styles. Furthermore, mpv's `--secondary-sub-ass-override` defaults to `strip`, stripping all ASS styling.
* **Implementation & Solution**:
  * **[BilingualSubtitleParser.kt](app/src/main/java/app/marlboroadvance/mpvex/utils/media/BilingualSubtitleParser.kt)**:
    * Whether the original source is ASS or SRT, secondary subtitles are always synthesized into standard ASS format with a dedicated `Style: Secondary,...` header declaration. All dialogue events specify `Secondary` as their style.
    * Implemented `applySecondarySubStyleOverrides(preferences)`, which translates UI preferences into mpv's native `sub-ass-style-overrides` syntax (e.g. `Secondary.Fontname=...`, `Secondary.Fontsize=...`, `Secondary.PrimaryColour=...`, `Secondary.OutlineColour=...`, `Secondary.Bold=...`, `Secondary.Outline=...`) alongside `secondary-sub-ass-override=scale`.
    * Because the primary subtitle uses `Default` (or its source style), overrides prefixed with `Secondary.*` apply exclusively to the secondary subtitle. This achieves 100% style decoupling and independent control between primary and secondary subtitles.
  * **[SecondarySubtitleSettingsCard.kt](app/src/main/java/app/marlboroadvance/mpvex/ui/player/controls/components/panels/SecondarySubtitleSettingsCard.kt)**:
    * Connected bold, italic, alignment, font family, font size, border style, border size, shadow offset, scale factor, bilingual line spacing, color pickers (text, outline, background), and reset actions to `applySecondarySubStyleOverrides`.
    * Extended bilingual line spacing to `-40.0` through `+40.0` with a granular `0.2` step. Outline thickness is calibrated into 20 equal intervals across `0.0` to `4.0`.

### 2. Dynamic Height-Based Volume Gesture Sensitivity
* **Background & Technical Challenges**:
  * [GestureHandler.kt](app/src/main/java/app/marlboroadvance/mpvex/ui/player/controls/GestureHandler.kt) previously hardcoded volume gesture sensitivity to `0.017f`. Traversing 25 device volume steps required ~1500px of scrolling; with 100% volume boost enabled, traversing required >5800px. This made right-side volume adjustments require multiple full swipes.
* **Implementation & Solution**:
  * Replaced static sensitivity with a dynamic formula scaled against screen height (`slideHeight = size.height * 0.6f`):
    * Device volume: `val volumeGestureSens = (viewModel.maxVolume.toFloat() / slideHeight).coerceAtLeast(0.035f)`
    * MPV volume boost: `val mpvVolumeGestureSens = (boostRange / slideHeight).coerceAtLeast(0.12f)`
    * Screen brightness: `val brightnessGestureSens = (1.0f / slideHeight).coerceAtLeast(0.001f)`
  * A ~60% vertical swipe on the right side smoothly traverses the entire volume spectrum (and boost range), providing a natural and symmetric feel matching left-side brightness adjustments across all display resolutions and orientations.

### 3. Synchronized Bilingual Subtitle Delay & Speed
* **Background & Technical Challenges**:
  * [SubtitleDelayPanel.kt](app/src/main/java/app/marlboroadvance/mpvex/ui/player/controls/components/panels/SubtitleDelayPanel.kt) previously only modified `sub-delay` and `sub-speed`, leaving the secondary subtitle track desynchronized.
* **Implementation & Solution**:
  * Subtitle delay changes, speed adjustments, and reset operations now dispatch synchronously to both `sub-delay`/`sub-speed` and `secondary-sub-delay`/`secondary-sub-speed`, maintaining exact synchronization between both tracks.

### 4. Cold-Start, Player Entry & UI Jank Performance Optimizations
* **Investigation & Fixes**:
  * **Main Thread I/O Elimination**: [SubtitleOps.kt](app/src/main/java/app/marlboroadvance/mpvex/utils/media/SubtitleOps.kt) previously executed subtitle file reading, regex splitting, and a 30-iteration polling loop with `delay(50)` on `Dispatchers.Main`. All operations have been completely migrated to `Dispatchers.IO`, freeing the Android main looper.
  * **Redundant JNI Flooding Removed**: Removed `sid` and `secondary-sid` property observers in [MPVView.kt](app/src/main/java/app/marlboroadvance/mpvex/ui/player/MPVView.kt) and [PlayerActivity.kt](app/src/main/java/app/marlboroadvance/mpvex/ui/player/PlayerActivity.kt), eliminating 30+ repetitive synchronous JNI calls during track initialization and switching.
  * **Lazy Font Discovery**: Font scanning in `SecondarySubtitleSettingsCard` is deferred via `LaunchedEffect(isExpanded)`, preventing heavy filesystem scans and TTF parsing during app startup or player entry until the user actually expands the settings card.
  * **Compose Render Optimization**: Streamlined [ControlsButton.kt](app/src/main/java/app/marlboroadvance/mpvex/ui/player/controls/components/ControlsButton.kt) by rendering `ImageVector` directly via `Icon(imageVector)` instead of wrapping each button in `rememberVectorPainter(icon)`, eliminating frame drops during player controls fade animations.
