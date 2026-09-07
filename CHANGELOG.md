# Changelog

All notable user-facing changes to FluentMai are recorded here.

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

[0.2.0-beta]: https://github.com/Daozhu1007/FluentMai/releases/tag/v0.2.0-beta
[0.1.0-beta.1]: https://github.com/Daozhu1007/FluentMai/releases/tag/v0.1.0-beta.1
