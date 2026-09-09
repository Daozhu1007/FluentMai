# FluentMai iOS 实验版：Windows + iPhone 自签安装指南

本指南面向没有 Mac、只有 Windows 电脑和 iPhone 的普通测试者。你下载的是 **unsigned（未签名）IPA**，必须用你自己的 Apple Account（原 Apple ID）在本地签名后才能安装。它不是 App Store 或 TestFlight 版本，也不能在 iPhone 上直接点一下就安装。

> 安全底线：Apple Account、密码和双重认证验证码只能由你本人在自己的设备上输入。不要发给 FluentMai 开发者，不要写进 GitHub Issue，也不要使用来源不明的公共/在线签名网站。

## 1. 下载正确文件

打开 [FluentMai v0.2.4 Beta](https://github.com/Daozhu1007/FluentMai/releases/tag/v0.2.4-beta)，在 Assets 中下载：

- `FluentMai-v0.2.4-beta-ios-unsigned.ipa`：要安装的正式命名文件；
- `SHA256SUMS.txt`：校验值；
- `ios-device-build-manifest.json`：真实构建环境和静态验证记录；
- `IOS_SIDELOAD_GUIDE.zh-CN.md`：本指南的可下载副本。

`FluentMai-ios-unsigned.ipa` 是内容相同的稳定别名。两者任选一个即可，不要同时安装。**IPA 不需要解压**；AltStore/AltServer 需要的就是 `.ipa` 文件本身。

## 2. 可选但推荐：校验 SHA-256

把 IPA 和 `SHA256SUMS.txt` 放在同一文件夹。在该文件夹空白处按住 Shift 并右键，选择“在终端中打开”，运行：

```powershell
Get-FileHash .\FluentMai-v0.2.4-beta-ios-unsigned.ipa -Algorithm SHA256
```

将输出的 `Hash` 与 `SHA256SUMS.txt` 中同名文件前的 64 位字符逐字比较。大小写不影响结果；任何字符不同都应删除文件并从上述 GitHub Release 重新下载。

## 3. 在 Windows 准备 Apple 组件和 AltServer

下面以 AltStore Classic 的本地自签方案为主。请先阅读 [AltStore 官方 Windows 安装页](https://faq.altstore.io/altstore-classic/how-to-install-altstore-windows)。其标准安装路径目前要求：

1. 从 Apple 官方直装来源安装最新版 **iTunes** 和 **iCloud for Windows**，不要使用 Microsoft Store 版本。官方 AltStore 页面提供了 Apple 的当前下载入口。
2. 打开 iTunes 和 iCloud 至少一次，完成必要初始化；不需要把任何账号信息告诉 FluentMai 开发者。
3. 从 [AltStore 官网](https://altstore.io/) 下载 Windows 版 AltServer。
4. 解压 `AltInstaller.zip`，运行 `Setup.exe` 完成安装。
5. 从 Windows 开始菜单找到 AltServer，选择“以管理员身份运行”。它通常只显示在任务栏右下角托盘中；若 Windows 防火墙询问，允许其访问你的私人网络。

如果你已安装 Microsoft Store 版 iTunes/iCloud，不要盲目混装。先按 AltStore 官方故障排查页处理，再继续。

## 4. 通过 USB 连接并信任电脑

1. 用支持**数据传输**的 USB 线连接 iPhone 和 Windows 电脑，解锁 iPhone，并保持屏幕亮起。
2. Windows/iTunes 出现信任提示时选择继续；iPhone 出现“要信任此电脑吗？”时点“信任”，再输入 iPhone 锁屏密码。Apple 的 [Windows 连接排查](https://support.apple.com/108643) 和 [“信任此电脑”说明](https://support.apple.com/109054) 给出了完整步骤。
3. 在 iTunes 中确认能看到 iPhone。按 AltStore 官方说明开启该设备的“通过 Wi-Fi 与此 iPhone 同步”，以后刷新会更方便；第一次仍建议保留 USB 连接。

如果误点了“不信任”，可在 iPhone 的“设置 → 通用 → 传输或还原 iPhone → 还原 → 还原位置与隐私”后重新连接。此操作会重置位置与隐私授权，请先理解影响。

## 5. 把 AltStore 安装到 iPhone

1. 确保 AltServer 正在 Windows 托盘中运行，iPhone 已解锁并连接。
2. 单击 AltServer 托盘图标，选择 `Install AltStore`，再选择你的 iPhone。
3. 在你自己的电脑上输入你自己的 Apple Account 邮箱和密码。AltStore 官方说明这些凭据用于向 Apple 验证和配置本地自签；任何人都不应索取它们。
4. 如 Apple 要求双重认证，只在 Apple/AltServer 的本机流程中完成。不要把密码或验证码截图发给开发者。
5. 等待 AltServer 提示安装成功。若主屏幕暂时没出现 AltStore，可稍等或重启 iPhone 后再看。
6. 在 iPhone“设置 → 通用 → VPN 与设备管理”（不同 iOS 版本名称可能略有不同）找到与你 Apple Account 对应的开发者条目，确认信任。

## 6. 按需要启用开发者模式

iOS 16 及更高版本通常要求开发者模式：

1. 打开“设置 → 隐私与安全性 → 开发者模式”；
2. 开启后按提示重启 iPhone；
3. 重启解锁后再次确认“打开”。

如果菜单暂时不存在，先完成一次 AltStore 安装并重新连接设备，再检查。不要安装不明描述文件来“解锁”该选项。

## 7. 用自己的 Apple Account 签名并安装 FluentMai

1. 保持 Windows 上 AltServer 运行，并让 iPhone 通过 USB 或已配置的同一 Wi-Fi 与电脑连通。
2. 把下载的 IPA 保存到 iPhone“文件”App（可以在 iPhone 上从 GitHub Release 下载，也可以通过你信任的本地方式传入）。不要解压。
3. 打开 AltStore，进入 `My Apps`，点左上角 `+`，从“文件”中选择 `FluentMai-v0.2.4-beta-ios-unsigned.ipa`。
4. 若 AltStore 要求登录，只使用你自己的 Apple Account；不要使用开发者或陌生人提供的共享账号。
5. 等待签名、传输和安装完成。期间保持 AltServer 运行、网络稳定，不要拔线或锁定设备。
6. 安装成功后，`My Apps` 会出现 FluentMai，主屏幕/App 资源库也会出现 FluentMai 图标。

AltServer 也支持在 Windows 上按住 Shift 单击托盘图标后选择 `Sideload .ipa…` 直接侧载；这是 AltServer 的官方能力，但不会获得 AltStore 的自动刷新体验，因此本指南优先使用上面的 AltStore 流程。

## 8. 第一次启动和版本确认

1. 第一次启动前确认“VPN 与设备管理”中的开发者已信任、开发者模式已开启。
2. 打开 FluentMai。首次读取内置离线曲库可能需要短暂等待。
3. 在 AltStore `My Apps` 或 iPhone“设置 → 通用 → iPhone 储存空间 → FluentMai”中检查版本；当前源码生成包的 App 版本为 `0.2.4`，构建号为 `1`。
4. 本预览包的 Bundle Identifier 为 `dev.fluentmai.ios`；自签工具为满足 Apple 个人团队规则可能对最终签名标识作受控调整，这不代表下载的原始 IPA 来自别处。

## 9. 测试范围与反馈材料

当前 iOS 已按 Android 的“首页 / 导入 / 谱面 / 工具”结构组织，适合测试独立页面状态、重复点击底栏回到当前页面顶部、B35/B15、牌子进度与推分建议、谱面筛选与排序、谱面详情、SSS+ 容错、Rating Trend 和计算工具。iOS 普通应用无法运行 Android 的本地 VPN Hook，Wahlap/云端网络适配也仍为平台专用；iOS JSON 备份不是 Android 数据库迁移格式。

报告问题时请提供：

- iPhone 型号、iOS 版本；
- FluentMai 版本与构建号；
- 从打开 App 开始的最短复现步骤；
- 发生时间、页面截图或录屏；
- 如有崩溃，在“设置 → 隐私与安全性 → 分析与改进 → 分析数据”中查找对应时间的 FluentMai 崩溃记录。

截图方法：同时按侧边键和音量加键。录屏方法：在控制中心添加并使用“屏幕录制”。发送前遮挡 Apple Account、通知、姓名、设备标识和其他个人信息。不要提交 Apple Account、密码、验证码、Cookie、Token、登录链接、证书或描述文件。

## 10. 7 天到期、刷新和重新签名

免费 Apple Account 的个人团队描述文件通常 7 天到期。Apple 的 [开发者账号说明](https://developer.apple.com/help/account/basics/about-your-developer-account/) 明确列出了免费个人团队的 7 天周期和数量限制；AltStore 也说明侧载 App 会在 7 天后无法打开，并会尝试后台刷新。

在到期前：

1. 让安装 AltStore 时使用的 Windows 电脑开机并运行 AltServer；
2. 让 iPhone 与电脑处于同一 Wi-Fi，或直接用 USB 连接；
3. 打开 AltStore → `My Apps` → `Refresh All`，等 FluentMai 的剩余天数恢复；
4. 刷新失败时先用 USB 重试，再检查 AltServer、iTunes/iCloud、信任关系和防火墙。

到期后也可以按相同步骤重新签名/安装。尽量继续使用同一 Apple Account、同一台电脑和同一种签名方式。

## 11. 保护本地数据和覆盖更新

FluentMai 的 iOS 成绩、别名和趋势数据保存在 App 本地。**卸载 App 可能删除这些数据**。可在“导入”页面导出 iOS JSON 备份并在之后恢复；该文件不承诺与 Android 数据库互通。

- 不要把“卸载重装”当作第一排障步骤。
- 更新新 IPA 时，先保留旧 App，使用同一 Apple Account/AltStore 流程导入新 IPA，尽量让系统按同一 Bundle Identifier 覆盖安装。
- 覆盖安装通常比卸载后安装更有机会保留容器数据，但实验版不作数据零丢失保证；重要记录请另行截图留存。
- 如果 AltStore 要求停用 App，可先了解其 `Deactivate` 数据备份机制；官方说明见 [Activating Apps](https://faq.altstore.io/altstore-classic/activating-apps)。不要在没有备份意识的情况下手动删除 FluentMai。

## 12. 常见错误排查

### AltServer 看不到 iPhone

- 解锁 iPhone，重新插拔支持数据的 USB 线并更换 USB 口；
- 在 iPhone 和 Windows/iTunes 两端重新完成“信任”；
- 确认 iTunes 能看到设备，iCloud 和 Apple 移动设备相关服务可用；
- 重启 iPhone、AltServer 和 Windows；
- 按 Apple 的[设备无法识别排查](https://support.apple.com/108643)更新 Windows、Apple 软件和驱动。

### AltServer 没有托盘图标或连接超时

- 从开始菜单以管理员身份重新运行 AltServer，并展开任务栏隐藏图标；
- 允许 AltServer 访问私人网络，确认安全软件没有拦截其本地通信；
- 第一次安装或排障优先用 USB，不依赖 Wi-Fi 刷新。

### “Incorrect Apple ID or password”或验证失败

- 只在你自己的电脑上重新输入，确认邮箱、密码和双重认证流程无误；
- 不要把凭据交给开发者代试；
- 仍失败时按 [AltStore 官方错误说明](https://faq.altstore.io/altstore-classic/error-codes)处理，不要转向不明在线签名站。

### “Invalid format”或 IPA 无效

- 确认选择的是 `.ipa`，没有解压、改名成 `.zip` 或二次压缩；
- 重新从本项目 GitHub Release 下载并校验 SHA-256；
- 不要使用聊天群、网盘或第三方重打包版本。

### 无法打开、开发者不受信任或需要开发者模式

- 在“VPN 与设备管理”中信任你自己的开发者条目；
- 在“隐私与安全性”中开启开发者模式并按提示重启；
- 确认签名未超过 7 天，过期就连接 AltServer 刷新或重新签名。

### 达到 3 个 App 或 10 个 App ID 限制

免费 Apple Account 通常同一设备最多 3 个活跃侧载 App，App ID 也有周期限制。AltStore 的 [App IDs 说明](https://faq.altstore.io/altstore-classic/app-ids)会显示到期时间；停用不需要的侧载 App 或等待旧 App ID 到期，不要借用陌生账号绕过限制。

### 更新后变成两个 App，或提示 Bundle ID 冲突

通常是换了 Apple Account、签名工具或签名规则。先不要卸载旧 App；记录旧 App 的签名方式，尝试用原 Apple Account 和 AltStore 覆盖。若最终必须卸载，应先接受本地数据可能丢失的风险并保存截图。

## 重要声明

- 这是 unsigned、自签、实验性 iOS 测试版，不是 App Store/TestFlight 版本。
- CI 已验证 `iphoneos arm64` 构建、IPA 结构、架构、Bundle、资源、KMP 静态链接和无签名状态；**尚未据此宣称真实 iPhone 安装成功**。
- 免费签名通常需要周期性刷新；Apple 的限制可能随账号和系统状态变化。
- 当前 iOS 功能可能少于 Android，Android 数据迁移到 iOS 不作保证。
- 卸载可能删除 iOS 本地数据；更新时尽量保持同一 Bundle Identifier 和签名方式并覆盖安装。
- 不要使用来历不明的公共签名网站，不要向任何人提交签名凭据。
