# FluentMai

[English](README.md) | [简体中文](README.zh-CN.md)

| 平台 | 状态 | 下载 | 说明 |
| --- | --- | --- | --- |
| Android | Beta | [Android APK](https://github.com/Daozhu1007/FluentMai/releases/tag/v0.2.5-android-beta.3) | 当前主要维护平台；debug 签名测试包 |
| iOS | Experimental Beta | [unsigned IPA](https://github.com/Daozhu1007/FluentMai/releases/tag/v0.2.4-beta) | 需要用户自签，iOS 17+ |
| Windows | Alpha / Development | 暂仅源码 | `windows/` 下的独立桌面实现 |

FluentMai 是面向 maimai DX 国服玩家的非官方 local-first 成绩工具。这个产品仓库同时承载三套相互独立的平台实现：Android 是当前成熟度最高的主要维护平台，iOS 与 Windows 仍处于实验阶段。

三端不承诺功能、存储格式或发版时间完全一致。Android 与 iOS 只在 `core/model` 共享部分 Kotlin Multiplatform 领域逻辑；Windows 保持独立的 Python/PyQt6 技术线。Android 的 Room 数据迁移与 schema 保证不适用于 iOS 或 Windows。

## 平台指南

- [Android](docs/platforms/android.md) — Kotlin、Jetpack Compose、Room 与当前公开 Beta。
- [iOS](docs/platforms/ios.md) — SwiftUI + KMP 领域层，以及需要自签的真实 `iphoneos arm64` unsigned IPA。
- [Windows](docs/platforms/windows.md) — 独立 PyQt6 桌面应用，目前暂仅提供源码。

## 产品范围

FluentMai 的共同方向是把成绩导入、校验、浏览与分析尽量留在本地，但当前能力因平台而异：

- Android 功能最完整，包含 Wahlap 导入、Room 持久化、quarantine、B35/B15 与 Rating、谱面查询、牌子进度、趋势、推分建议和用户主动发起的可选上传。
- iOS 是使用共享 KMP 成绩/Rating 领域层的实验性 SwiftUI 客户端。现有 IPA 是真实设备构建，但安装前必须由用户自行签名。
- Windows 使用独立的本地 SQLite 数据库，提供手动导入、成绩与谱面浏览、曲库缓存和 PyQt6 桌面 UI；它不共享 Android 数据库或迁移链。

详细边界见[产品范围](docs/PRODUCT_SCOPE.md)、[隐私模型](docs/PRIVACY_MODEL.md)与[第三方声明](THIRD_PARTY_NOTICES.md)。

## 发布

当前移动端发布：

- `v0.2.5-android-beta.3` — Android debug-signed 测试 APK。
- `v0.2.4-beta` — iOS unsigned `iphoneos arm64` IPA（保持不变）。
- Windows 尚无公开 Release；Actions 中的构建产物只作为开发验证证据，不等于 Windows 发布。

未来平台独立 tag 使用：

- `vX.Y.Z-android-beta.N`
- `vX.Y.Z-ios-alpha.N`
- `vX.Y.Z-windows-alpha.N`

各平台独立发版；此前的 `v0.2.4-beta` 将 Android 与 iOS 测试产物同步在同一个 tag 下。

## 仓库结构

- `app/`、`feature/` 与大部分 `core/` — Android 应用及 Kotlin 领域/数据层。
- `core/model/` — Android 与 iOS 共享的 Kotlin Multiplatform 领域代码。
- `iosApp/` — 实验性 SwiftUI 应用。
- `windows/` — 独立 Python/PyQt6 Windows 应用。
- `scripts/ios/`、`scripts/windows/` — 平台构建与验证脚本。
- `docs/platforms/` — 各平台状态、环境、构建和限制说明。
- `.github/workflows/` — 按路径隔离的 Android、iOS 与 Windows 验证。

## 构建与测试

Windows 环境下构建 Android：

```powershell
.\gradlew.bat test
.\gradlew.bat :app:assembleDebug
```

iOS 需要 macOS、Xcode、JDK 17 与 XcodeGen，详见 [iOS 平台指南](docs/platforms/ios.md)。

Windows：

```powershell
python -m pip install -r windows/requirements.txt
python -m pytest -q windows/tests
.\scripts\windows\smoke_test.ps1 -Mode source
.\scripts\windows\build_portable.ps1
```

完整 Windows 命令与已知限制见 [windows/README.md](windows/README.md)。

## 截图

当前截图展示 Android Beta，数据均为本地合成或测试数据。

| 首页 B50 | 统一谱面浏览器 | 谱面详情 |
| --- | --- | --- |
| <img src="docs/screenshots/home-b50.png" width="260" alt="FluentMai Android 首页 B50"> | <img src="docs/screenshots/chart-browser.png" width="260" alt="FluentMai Android 谱面浏览器"> | <img src="docs/screenshots/chart-detail.png" width="260" alt="FluentMai Android 谱面详情"> |

认证 URL、Token、Cookie、原始导入页面、数据库、日志和用户成绩不会进入仓库。

## 隐私与安全

- 不得提交真实 Cookie、Authorization header、Import Token、LXNS Token、完整认证 URL、原始 Wahlap HTML、用户数据库或诊断 dump。
- Android 与 Windows 使用各自独立的本地数据库；当前没有跨平台迁移保证。
- 向社区服务上传始终是用户主动触发的可选操作。
- 发布签名凭据、证书和密码不会保存在仓库中。
- Windows 源码与 CI 构建不捆绑无明确分发权的游戏曲绘；封面仅从用户缓存或运行时数据源解析。

## 免责声明

FluentMai 是独立的非官方玩家工具，与 SEGA、华立、Diving Fish、LXNS 及其运营方不存在隶属、授权或背书关系。游戏名称、曲绘、音乐元数据和商标归各自权利方所有。上游服务可能随时变化，请为重要数据保留独立备份。
