![banner](fastlane/metadata/android/en-US/images/featureGraphic.png)

# mpvExtended (Patched Edition)
[![GitHub release (latest SemVer)](https://img.shields.io/github/v/release/marlboro-advance/mpvex.svg?logo=github&label=GitHub&cacheSeconds=3600)](https://github.com/marlboro-advance/mpvex/releases/latest)
[![GitHub all releases](https://img.shields.io/github/downloads/marlboro-advance/mpvex/total?logo=github&cacheSeconds=3600)](https://github.com/marlboro-advance/mpvex/releases/latest)

> 本仓库基于 [mpvExtended](https://github.com/marlboro-advance/mpvEx) 进行定制维护与增强，针对**双语字幕智能拆分与第二字幕独立样式控制**、**右侧屏幕音量手势滑动灵敏度**、**双语字幕延迟严格同步**以及**应用冷启动与组件加载掉帧性能**进行了深度重构与优化。

---

## 🛠️ 本补丁版本修改与维护说明 (Patched Changelog & Maintenance Guide)

### 1. 双语字幕智能拆分与第二字幕独立渲染系统
* **背景与问题**：
  * 原版 `libmpv` 原生**不支持** `secondary-sub-font`、`secondary-sub-color`、`secondary-sub-font-size` 等属性，直接调用会静默失败。
  * 若将第二字幕作为纯文本 SRT 输出，mpv 会将其当作未样式化字幕，强制继承主字幕的 `sub-*` 属性；且 mpv 的 `secondary-sub-ass-override` 默认值为 `strip`，会剥除所有 ASS 样式。
* **重构实现**：
  * **[BilingualSubtitleParser.kt](file:///C:/Users/root/Downloads/mpvEx-patched-master/app/src/main/java/app/marlboroadvance/mpvex/utils/media/BilingualSubtitleParser.kt)**：
    * 无论原文件为 ASS 还是 SRT，第二字幕均统一输出为标准 ASS 格式，并在头部写入专用的 `Style: Secondary,...` 独立样式声明；Dialogue 行指定 `Secondary` 样式。
    * 提供 `applySecondarySubStyleOverrides(preferences)` 方法，通过 mpv 原生的 `sub-ass-style-overrides` 属性（如 `Secondary.Fontname=...`、`Secondary.Fontsize=...`、`Secondary.PrimaryColour=...`、`Secondary.OutlineColour=...`、`Secondary.Bold=...`、`Secondary.Outline=...`）以及 `secondary-sub-ass-override=scale` 实时驱动第二字幕。
    * 主字幕样式为 `Default`，通过 `Secondary.*` 前缀进行的样式覆写仅对第二字幕生效，实现了主副字幕样式的彻底解耦与独立控制。
  * **[SecondarySubtitleSettingsCard.kt](file:///C:/Users/root/Downloads/mpvEx-patched-master/app/src/main/java/app/marlboroadvance/mpvex/ui/player/controls/components/panels/SecondarySubtitleSettingsCard.kt)**：
    * 粗体、斜体、对齐方式、字体、字号、边框样式、边框粗细、阴影偏移、缩放比例、双语字幕间距以及颜色选择器（文本/边框/背景）和重置逻辑，全部打通至 `applySecondarySubStyleOverrides`。
    * 行距支持 `-40.0` 至 `+40.0` 调节，步长精细为 `0.2`；边框粗细在 `0.0 ~ 4.0` 之间提供 20 个等间距区间。

### 2. 音量手势滑动灵敏度重构
* **背景与问题**：
  * [GestureHandler.kt](file:///C:/Users/root/Downloads/mpvEx-patched-master/app/src/main/java/app/marlboroadvance/mpvex/ui/player/controls/GestureHandler.kt) 原先将音量手势灵敏度写死为 `0.017f`，调整 25 阶音量需滑动约 1500px，若开启音量放大则需滑动超过 5800px，导致屏幕右侧大幅调整音量需要连续滑动多次。
* **重构实现**：
  * 改为根据当前屏幕高度 `size.height * 0.6f` 动态计算灵敏度：
    * 设备音量：`val volumeGestureSens = (viewModel.maxVolume.toFloat() / slideHeight).coerceAtLeast(0.035f)`
    * 音量放大：`val mpvVolumeGestureSens = (boostRange / slideHeight).coerceAtLeast(0.12f)`
    * 屏幕亮度：`val brightnessGestureSens = (1.0f / slideHeight).coerceAtLeast(0.001f)`
  * 使得上下滑动约 60% 屏幕高度即可平滑覆盖全区间，左右两侧手感完全一致且自适应各种屏幕分辨率与横竖屏方向。

### 3. 字幕时间调整（Subtitle Delay）双语严格同步
* **背景与问题**：
  * [SubtitleDelayPanel.kt](file:///C:/Users/root/Downloads/mpvEx-patched-master/app/src/main/java/app/marlboroadvance/mpvex/ui/player/controls/components/panels/SubtitleDelayPanel.kt) 原先只更新了 `sub-delay` 与 `sub-speed`，遗漏了第二字幕属性。
* **重构实现**：
  * 在延迟变动、语速变动及重置时，同步下发属性到 `secondary-sub-delay` 与 `secondary-sub-speed`，主副字幕时间轴时刻严格对齐。

### 4. 性能掉帧、冷启动与初次加载卡顿优化
* **排查与修复**：
  * **主线程阻塞彻底消除**：此前 [SubtitleOps.kt](file:///C:/Users/root/Downloads/mpvEx-patched-master/app/src/main/java/app/marlboroadvance/mpvex/utils/media/SubtitleOps.kt) 在主线程执行大文件的读取、正则解析与拆分写入，并在主线程执行 30 次带 `delay(50)` 的轮询。现已全部移至 `Dispatchers.IO`，不再阻塞主线程 Looper。
  * **消除 JNI 调用洪峰**：移除了 [MPVView.kt](file:///C:/Users/root/Downloads/mpvEx-patched-master/app/src/main/java/app/marlboroadvance/mpvex/ui/player/MPVView.kt) 与 [PlayerActivity.kt](file:///C:/Users/root/Downloads/mpvEx-patched-master/app/src/main/java/app/marlboroadvance/mpvex/ui/player/PlayerActivity.kt) 中对 `sid` 和 `secondary-sid` 的监听器，杜绝轨道切换时重复触发 30+ 次同步 JNI 调用。
  * **延迟字体加载**：在 `SecondarySubtitleSettingsCard` 中将字体目录扫描改为懒加载（`LaunchedEffect(isExpanded)`），只有在用户真正展开第二字幕面板时才会扫描解析字体文件。
  * **Compose 渲染轻量化**：优化 [ControlsButton.kt](file:///C:/Users/root/Downloads/mpvEx-patched-master/app/src/main/java/app/marlboroadvance/mpvex/ui/player/controls/components/ControlsButton.kt) 的图标绘制，直接调用 `Icon(imageVector)` 避免多层包装，消除控制栏动画掉帧。

---

**mpvExtended is a fork of [mpv-android](https://github.com/mpv-android/mpv-android), built on the libmpv library. It aims
to combine the powerful features of mpv with an easy to use interface and additional
features.**

- Simpler and Easier to Use UI
- Material3 Expressive Design
- Advanced Configuration and Scripting
- Enhanced Playback Features
- Picture-in-Picture (PiP)
- Background Playback
- High-Quality Rendering
- Network Streaming
- File Management
- Completely free and open source and without any ads or excessive permissions
- Media picker with tree and folder view modes
- External Subtitle support
- Zoom gesture
- External Audio support
- Search Functionality
- SMB/FTP/WebDAV support
- Custom Playlist management support

**This project is still in development and is expected to have bugs. Please report any bugs you find in
the [Issues](https://github.com/marlboro-advance/mpvEx/issues) section.**

---

## Installation

### Stable Release
Download the latest stable version from the [GitHub releases page](https://github.com/marlboro-advance/mpvEx/releases).

[![Download Release](https://img.shields.io/badge/Download-Release-blue?style=for-the-badge)](https://github.com/marlboro-advance/mpvEx/releases)

Or you can get the stable releases here

[<img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroidButtonGreyBorder_nofont.png" height="50" alt="Get it at IzzyOnDroid">](https://apt.izzysoft.de/packages/app.marlboroadvance.mpvex)

### Preview Builds
For testing purposes only

[![Download Preview Builds](https://img.shields.io/badge/Download-Preview%20Builds-red?style=for-the-badge)](https://marlboro-advance.github.io/mpvEx/)

---

## Showcase
<div class="image-row" align="center">
  <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/player.png" width="98%" />
</div>

<div class="image-row" align="center" justify-content="space-between">
  <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/folderscreen.png" width="23.5%"/>
  <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/videoscreen.png" width="23.5%"/>
  <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/about.png" width="23.5%"/>
  <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/pip.png" width="23.5%"/>
</div>

<div class="image-row" align="center">
  <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/framenavigation.png" width="48.5%" />
  <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/chapters.png" width="48.5%" />
</div>

---

## Building

### Prerequisites

- JDK 17
- Android SDK with build tools 34.0.0+
- Git (for version information in builds)

### APK Variants

The app generates multiple APK variants for different CPU architectures:

- **universal**: Works on all devices (larger size)
- **arm64-v8a**: Modern 64-bit ARM devices (recommended for most users)
- **armeabi-v7a**: Older 32-bit ARM devices
- **x86**: Intel/AMD 32-bit devices
- **x86_64**: Intel/AMD 64-bit devices

---

## Releases

### Setting Up Release Signing

To enable automatic signing for release builds in GitHub Actions, you need to configure the
following secrets in your GitHub repository:

1. Navigate to your repository on GitHub
2. Go to **Settings** → **Secrets and variables** → **Actions**
3. Add the following repository secrets:

| Secret Name              | Description                                          |
|--------------------------|------------------------------------------------------|
| `SIGNING_KEYSTORE`       | Base64-encoded keystore file (`.jks` or `.keystore`) |
| `SIGNING_KEY_ALIAS`      | The alias name used when creating the keystore       |
| `SIGNING_STORE_PASSWORD` | Password for the keystore file                       |
| `KEY_PASSWORD`           | Password for the key (can be same as store password) |

#### Encoding Your Keystore

To encode your keystore file to base64:

**Linux/macOS:**

```bash
base64 -i your-keystore.jks | tr -d '\n' > keystore.txt
```

**Windows (PowerShell):**

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("your-keystore.jks")) | Out-File -FilePath keystore.txt -NoNewline
```

Copy the contents of `keystore.txt` and paste it as the value for the `SIGNING_KEYSTORE` secret.

### Creating a Release

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`
2. Commit the changes
3. Create and push a tag:
   ```bash
   git tag -a v1.0.0 -m "Release version 1.0.0"
   git push origin v1.0.0
   ```
4. GitHub Actions will automatically build, sign, and create a draft release

### Creating a Preview Release

1. Create and push a preview tag:
   ```bash
   git tag -a v1.0.0-preview.1 -m "Preview release"
   git push origin v1.0.0-preview.1
   ```
2. GitHub Actions will create a pre-release automatically

---

## Acknowledgments

- [mpv-android](https://github.com/mpv-android)
- [mpvKt](https://github.com/abdallahmehiz/mpvKt)
- [Next player](https://github.com/anilbeesetti/nextplayer)
- [Gramophone](https://github.com/FoedusProgramme/Gramophone)

---

## Support the Project <img src="https://raw.githubusercontent.com/Tarikul-Islam-Anik/Animated-Fluent-Emojis/master/Emojis/Smilies/Heart%20with%20Ribbon.png" alt="Heart with Ribbon" width="25" height="25" />

If you find mpvExtended useful, consider supporting the development:

[![UPI](https://img.shields.io/badge/UPI-aadiinarvekar@upi-blue?style=for-the-badge&logo=google-pay&logoColor=white)](upi://pay?pa=aadiinarvekar@upi)

---
## Star History <img src="https://raw.githubusercontent.com/Tarikul-Islam-Anik/Animated-Fluent-Emojis/master/Emojis/Travel%20and%20places/Star.png" alt="Star" width="25" height="25" />

<a href="https://www.star-history.com/#marlboro-advance/mpvEx&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=marlboro-advance/mpvEx&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=marlboro-advance/mpvEx&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=marlboro-advance/mpvEx&type=date&legend=top-left" />
 </picture>
</a>
