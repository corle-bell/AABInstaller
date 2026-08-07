# AAB Installer

在 Android 手机上直接选择 `.aab` 文件，本地完成 AAB → 当前设备 APK 集 → 系统安装的完整闭环，无需电脑与 adb。

## 模块结构

```
app              UI、文件选择（SAF）、签名配置、链接下载/扫码、缓存清理
bundletool-core  AAB 解析 + 设备匹配 APK 集生成 + 签名（基于 Google bundletool）
installer        PackageInstaller Session 多 APK 原子安装
```

## 功能

- 本地选择 AAB → 转换 → 安装
- **签名配置**：导入/导出密钥库，配置别名、密钥库密码、别名密码，选用当前签名
- **链接安装**：手动输入或扫码填入 URL，下载 AAB 后转换安装；下载记录可多选删除
- **清理转换缓存**：释放 `cache/convert` 等临时文件（不影响下载与签名）

## 技术要点

- **bundletool 直接跑在手机上**：依赖 Maven 版 `com.android.tools.build:bundletool`，
  根据当前设备 ABI、SDK、密度、语言和系统特性生成并提取匹配的 base/split APK。
- **多 APK 安装**：使用 `PackageInstaller.Session` 原子安装 base、ABI、资源和
  install-time feature splits，对齐 bundletool `install-apks` 的核心行为。
- **产物校验**：每次转换均将所有 APK 的文件名、大小与 MD5 输出到界面日志和 Logcat。
- **aapt2**：Android 原生 aapt2 伪装为 `libaapt2.so` 放入 jniLibs，运行时注入。
- **签名**：默认内置调试签名；可导入 JKS/PKCS12，密码保存在应用私有目录（测试工具场景）。

## 构建

```bash
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

本机 JDK 路径写到 `gradle-local.properties`（参考 `gradle-local.properties.example`），不会提交到 Git，也不影响 CI。

## GitHub Actions（手动编译）

仓库包含 `.github/workflows/android-build.yml`，**仅支持手动触发**（`workflow_dispatch`），不会在 push/PR 时自动运行。

在 GitHub 仓库页：**Actions → Android Build → Run workflow**，可选择 `debug` 或 `release`。完成后在 Artifacts 下载 APK。

## 运行要求

- Android 8.0+（minSdk 26）
- arm64-v8a / armeabi-v7a
- 安装未知应用权限；链接下载需网络；扫码需相机权限
