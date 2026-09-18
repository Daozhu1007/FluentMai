# Changelog

All notable user-facing changes to FluentMai are recorded here.

## [0.2.9-beta] - 2026-09-18

- 新增 PC 爬取规则：默认“效率优先”，批量获取各难度最好成绩页中的 PC，其余显示上限；关闭后逐曲获取完整 PC。保留已有数据，并提供可复制的详细导入诊断。
- 导入状态新增随主题变化的灰底绿色进度条，显示当前阶段、页面数量、请求/解析状态与失败情况。
- 谱面详情及首页成绩卡显示 PC；补充最近游玩记录，趋势页新增游玩热力图，Rating 历史默认折叠。
- 接入水鱼拟合定数，新增 PC、拟合定数和拟合分差排序；拟合分差统一为官方定数减水鱼拟合定数。
- 新增自定义谱面缩略框和详情页组件，以及恢复默认设置。缩略框四项属性可以替换，详情属性可以隐藏。
- 谱面筛选布局精简，排序种类与升降序分开；收藏筛选配色统一，统计详情改为分组文本，缩略框版本名简写为 DX202X。
- 拟合分差与 SSS+ 容错新增适配亮暗主题的红黄绿渐变，采用对称分段二次曲线；Xaleid◆scopiX 全部难度不参与容错颜色参考范围计算。
- B50 大图支持双指缩放，优化玩家信息框布局、显示开关与收藏品背景的显示比例。
- 本次仅发布 Android Beta APK，沿用现有测试签名；iOS 保持原版本。

## [0.2.6-beta] - 2026-09-15

- 设置底部新增“其他 / 实验性功能”双节点动画开关，默认关闭并记住选择。
- 实验性功能开启后，首页新增 B50 大图生成器：五列 B35 / B15；玩家资料来自官方导入，B50、单曲 Rating、总 Rating 和评级框全部按本地成绩计算，不依赖落雪 Token。
- 微信捕获与 Cookie 导入保存最小化玩家装扮信息；兼容官方哈希图片地址，并尝试补充官方收藏页中明确标记为正在使用的姓名框/背景。
- B50 精简标题及底栏，顶部展示 Rating 汇总；成绩卡片显示难度全称、居中的 DX/标准标识、右对齐单曲 Rating，以及更大的 FC/FS 图标和空状态灰圆。
- 背景选择采用“经典白天 / 经典夜晚”缩略图预览；长图保持背景原始比例，下载按钮经确认后保存完整 PNG 到相册。
- B50 玩家头像、称号和白底名字嵌入姓名框；段位在名字右侧，Rating 在名字上方，友人对战等级在 Rating 右侧。
- B50 新增五项持久化显示开关，以及未加载图片的具体详情；细调星星、FC/FS 图标和空状态大小。
- 谱面查询新增独立谱面收藏与“全部 / 仅收藏 / 仅未收藏”三态筛选，收藏永久保存在本机。
- 工具箱移除版本入口和无损判定选项，新增随曲库更新的当前版本理论 Rating；首页移除查看已游玩谱面入口。
- 默认在启动后台检查 GitHub 最新 Android Release（含预发布）；可在设置关闭。下载失败切换公共加速线路，安装前检查完整性、包名、版本和签名，并请求系统安装。
- 本版只发布 Android，iOS 保持现有版本。

## [0.2.5-android-beta.3] - 2026-09-10

- 修复未游玩难度的空成绩页被误判为请求失败、导致其他难度成绩无法导入的问题；微信捕获和 Cookie 导入均适用。

## [0.2.5-android-beta.2] - 2026-09-10

- 替换 Android 应用图标，原图铺满桌面图标的可见区域。
- 谱面详情的彩色标识仅显示精确定数，使用不透明难度色底与白字。
- 牌子进度左侧等级标识改为不透明难度色底与白字。
- Re:MASTER 统一使用更偏白的浅紫色，白字增加轻微阴影以便辨认。

## [0.2.5-android-beta.1] - 2026-09-09

- Android 外观开关的点击反馈裁剪至圆角轨道内，避免超出轨道边界。
- 牌子进度的已完成/未完成谱面使用随主题适配的绿/红背景，状态及要求文字使用主题中性色。
- 导入模块与谱面列表统一使用首页谱面卡片背景，移除导入模块的主题色叠加及未游玩谱面的独立底色。
- 本次仅发布 Android；iOS 保持 v0.2.4-beta，不重新构建或上传。

- 缩小 Android 外观开关，整条轨道点击按亮色、跟随系统、深色循环；系统档绿色随当前主题变化。
- 亮色主题的谱面卡片改用白色描边与浅灰白底色，移除灰色外阴影。

- Android 设置移除上传/隐私说明，外观新增亮色、跟随系统、深色三段动画开关；主题选择会在重启后保留。

- Android 牌子进度页面回顶改为连续按像素滚动，避免不同高度卡片之间重新定位产生的停顿。

- Fixed an Android crash when entering upload tokens: let Android Keystore generate the AES-GCM IV and handle storage failures without closing the app.
- 修复 Android 输入上传 Token 时的闪退：由 Android Keystore 生成 AES-GCM 随机参数，并在保存异常时显示提示。

### English

- Android now securely remembers Diving Fish and LXNS upload tokens across app restarts using Android Keystore-backed encrypted storage.

### 简体中文

- Android 现在会使用 Android Keystore 支持的加密存储长期保留水鱼与落雪上传 Token，关闭并重新打开应用后会自动回填。

## [0.2.4-beta] - 2026-09-09

### English

- Rebuilt the iOS client around Android's current Home, Import, Charts, and Tools information architecture.
- Preserved every tab's navigation, input, filters, and scroll position; reselecting the active bottom item scrolls the current page to the top without popping subpages.
- Added iOS plate progress, Rating recommendations, chart query/detail, SSS+ tolerance display and sorting, calculators, trends, settings, and JSON backup/restore.
- Published synchronized Android and iOS test artifacts. The Android APK remains debug-signed; the iOS device IPA remains unsigned and requires self-signing.

### 简体中文

- 按 Android 最新版的“首页 / 导入 / 谱面 / 工具”信息架构重构 iOS 客户端。
- 各底栏页签保留导航、输入、筛选和滚动位置；再次点击当前底栏项会将当前页面滚动到顶部，且不会退出附属页面。
- iOS 新增牌子进度、推分建议、谱面查询与详情、SSS+ 容错显示与排序、计算工具、趋势、设置和 JSON 备份恢复。
- 同步发布 Android 与 iOS 测试产物；Android APK 仍为 debug 签名，iOS 真机 IPA 仍为未签名并需要用户自签。

## [0.2.3-beta] - 2026-09-08

### English

- Chart details now show SSS+ tolerance: the maximum number of Tap Great judgements a chart can absorb while still reaching SSS+ (100.5000%).
- Added ascending/descending tolerance sorting to the chart query; charts without complete note data sort last.
- Removed the obsolete "tolerance / loss" placeholder from the player-best section.

### 简体中文

- 谱面详情新增 SSS+ 容错：显示保持 SSS+（100.5000%）达成率下最多可承受的 Tap Great 判定数。
- 谱面查询新增容错升序/降序排序；物量数据不完整的谱面排在最后。
- 移除了玩家成绩区已过时的「容错 / 失分」占位项。

## [0.2.2-beta] - 2026-09-07

### English

- Improved Android navigation: reselecting the active tab scrolls the current page back to the top.
- Home, Import, Charts, and Tools now keep their UI state and scroll position when switching tabs, and subpages (Plate Progress, Rating Recommendations, chart details, Settings) are preserved per tab.

### 简体中文

- 改进 Android 导航体验：再次点击当前底部导航项时，当前页面滚动回顶部。
- 首页、导入、谱面、工具四个页签切换时各自保留界面状态与滚动位置；子页面（盘段进度、评分推荐、谱面详情、设置）按页签独立保留。

## [0.2.1-beta] - 2026-08-10

### English

- Fixed Best 15 to include scores from the current content batch, so the latest charts now count toward Rating.

### 简体中文

- 修复 Best 15 未纳入当前内容批次的问题，最新谱面成绩现在会计入 Rating。

## [0.2.0-beta] - 2026-07-15

### English

#### Highlights

- Replaced overlapping score lists with one responsive, stateful chart browser and one shared chart detail flow.
- Corrected B35/B15 version semantics so future content batches no longer leave B15 empty.
- Added player statistics and data-driven plate progress with corrected mainland China version/plate names and auditable requirements.
- Added community-alias search and offline Simplified/Traditional Chinese normalization alongside title, ID, BPM, artist, and designer search.
- Expanded filtering across difficulty, version, category, chart constant, play status, achievement/rank, FC, FS, and SD/DX.
- Added a single-chart Rating calculator, chart-aware note-loss/achievement calculator with manual mode, version reference, Rating Trend, and explainable improvement suggestions.
- Improved chart-page transitions and search responsiveness, and added phone-landscape and tablet layouts.
- Kept optional Diving Fish and LXNS upload flows while preserving Room as the local source of truth.

#### Important privacy change

- Upload tokens are held only for the current app session and are no longer newly persisted.
- New raw Wahlap pages are processed in memory and are no longer newly persisted.
- Upgrading does not proactively delete token or raw-page caches that may already exist from an older build.

#### Known limitations

- Changes to upstream pages or APIs may affect import and synchronization.
- Kaleid×Scope's complete, auditable data source is not connected; the app shows an unavailable state instead of invented content.
- iOS has not been formally released and there is no general-user iOS download.
- This Beta may still contain UI issues or device-specific compatibility problems; not every Android device has been verified.

#### Upgrade notes

- The APK supports an in-place upgrade from the existing compatible debug-signed installation.
- Do not uninstall the old version and do not clear app data.
- Users should retain an external backup of important score data.
- The published APK is a debug-signed Beta test build because no trusted release keystore or Android release-signing workflow is available in the repository.

### 简体中文

#### 重点更新

- 将重复的成绩列表收束为唯一、可恢复状态且支持响应式布局的谱面浏览器，并统一谱面详情入口。
- 修正 B35/B15 版本语义，未来内容批次不再导致 B15 为空。
- 新增玩家统计与数据驱动牌子进度，并修正国服版本/牌子名称和可审计规则。
- 加入社区别名与离线简繁归一化搜索，同时支持曲名、ID、BPM、曲师和谱师。
- 完整覆盖难度、版本、类别、定数、游玩状态、达成率/成绩、FC、FS 与 SD/DX 组合筛选。
- 新增单曲 Rating、选择谱面自动读取 Note 数的失分/达成率计算器（保留手动模式）、版本对照、Rating Trend 与可解释推分建议。
- 显著改善谱面页面切换和搜索响应，并支持手机横屏与平板布局。
- 保留可选的 Diving Fish、LXNS 上传流程，本地 Room 仍是成绩事实来源。

#### 重要隐私变化

- 上传 Token 只保留在当前应用会话，不再新增持久化。
- 新的原始 Wahlap 页面只在内存请求链中处理，不再新增持久化。
- 应用升级不会主动删除旧版本可能已经存在的 Token 或原始页面缓存。

#### 已知限制

- 上游页面或 API 变化可能影响导入与同步。
- Kaleid×Scope 尚未接入完整且可审计的数据源；应用会显示不可用状态，不伪造内容。
- iOS 尚未正式发布，也没有面向普通用户的 iOS 下载。
- Beta 版本仍可能存在 UI 或设备兼容问题，尚未验证所有 Android 设备。

#### 升级说明

- 在签名兼容的现有安装上支持直接覆盖升级。
- 不需要卸载旧版本，也不应清除应用数据。
- 用户仍应自行保留重要成绩的外部备份。
- 仓库没有受信任的 release keystore 或 Android release signing workflow，因此发布产物为 debug-signed Beta 测试构建。

## [0.1.0-beta.1] - 2026-07-04

- First public Android Beta preview with Wahlap import, local Room storage, B50 browsing, optional community-service upload, quarantine, and diagnostic redaction.

[0.2.4-beta]: https://github.com/Daozhu1007/FluentMai/releases/tag/v0.2.4-beta
[0.2.0-beta]: https://github.com/Daozhu1007/FluentMai/releases/tag/v0.2.0-beta
[0.1.0-beta.1]: https://github.com/Daozhu1007/FluentMai/releases/tag/v0.1.0-beta.1
