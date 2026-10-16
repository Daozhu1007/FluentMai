# AUTH-NET-TIMEOUT-1 — 成绩页进展感知型超时

日期：2026-10-17（Asia/Shanghai）  
目标版本基线：0.3.2-beta / `327d1a4`  
分支：`codex/auth-net-timeout-1`  
VERDICT：**READY_FOR_CTO_REVIEW**

## 结论与证据边界

SCORE_PAGE 改为受控流式读取：收到新的非空响应体数据块才算进展。持续有数据的下载没有固定总时长预算；连接、响应开始、读取空闲和响应体大小分别受约束。微信授权导入和手动 Cookie 导入共用同一下载实现。

这项修复解决的是固定总预算无法区分缓慢传输与实际停滞的问题。既有手机诊断不能证明 EXPERT 超时前持续收到数据，本报告不把该假设当作已确认根因，也没有在真实账号或手机上重新授权、导入。

## 旧机制与 Ktor 3.0.1 核实

依赖锁定于 `io.ktor:ktor-client-core:3.0.1`、`ktor-client-cio:3.0.1`，未升级库。

旧 SCORE_PAGE 配置 `requestTimeoutMillis=60000`、连接超时 15000 ms、最多三次尝试。`HttpTimeout` 在发送阶段启动延迟协程，时间到后取消 request execution context；收到响应体数据不会重置它。因此整个调用尚未结束时，固定预算可以中断正在传输的响应。

此外，原有 `client.get(...)` 通过 `HttpStatement.execute()` 保存完整响应，随后才调用 `bodyAsText()`。只在 `bodyAsText()` 外添加计时器无法观测此前已经发生的下载，必须切换到 `prepareGet(...).execute { response -> ... }`。

本次按实际版本源码核实：

| API / 实现 | 实际语义及本次用途 |
| --- | --- |
| `HttpTimeout.requestTimeoutMillis` | 总请求计时，不随数据块进展重置。仅 SCORE_PAGE 显式使用 `HttpTimeoutConfig.INFINITE_TIMEOUT_MS` 关闭这一计时。 |
| `connectTimeoutMillis` | CIO 在建立 TCP socket 的代码块中执行连接计时；保留 15000 ms。DNS/TLS/响应等待还受下述响应开始窗口约束。 |
| `socketTimeoutMillis` | 底层 socket 的读写不活动计时，既不是“取得完整应用层数据块”的回调，也不提供本次所需阶段诊断；本次没有把它当作进展监测器。 |
| CIO `getRequestTimeout` | 请求带有 `HttpTimeoutCapability` 时由插件接管，CIO 自身默认 15000 ms 请求计时被禁用，不会成为隐藏短总预算。 |
| `HttpStatement.execute(block)` | 跳过默认保存响应体，允许按需读取；finally 中执行响应清理。本次在其作用域内消费 channel。 |
| `bodyAsChannel()` / `readAvailable(ByteArray)` | 读取传输分帧处理后的响应体字节；只接受返回值大于零作为进展。零字节结果不重置窗口，EOF 进入完整性验证。 |

核对依据为 Ktor **3.0.1 tag**，而非后续版本文档：

- [HttpTimeout.kt](https://github.com/ktorio/ktor/blob/3.0.1/ktor-client/ktor-client-core/common/src/io/ktor/client/plugins/HttpTimeout.kt)
- [HttpStatement.kt](https://github.com/ktorio/ktor/blob/3.0.1/ktor-client/ktor-client-core/common/src/io/ktor/client/statement/HttpStatement.kt)
- [CIO Endpoint.kt](https://github.com/ktorio/ktor/blob/3.0.1/ktor-client/ktor-client-cio/jvmAndPosix/src/io/ktor/client/engine/cio/Endpoint.kt)
- [CIOEngineConfig.kt](https://github.com/ktorio/ktor/blob/3.0.1/ktor-client/ktor-client-cio/jvmAndPosix/src/io/ktor/client/engine/cio/CIOEngineConfig.kt)
- [HTTP body framing](https://github.com/ktorio/ktor/blob/3.0.1/ktor-http/ktor-http-cio/common/src/io/ktor/http/cio/HttpBody.kt)

## 新状态与约束

| 阶段 | 控制方法 | 结束 / 错误条件 |
| --- | --- | --- |
| TCP 建立 | Ktor CIO `connectTimeoutMillis=15000` | 仅捕获真实 `ConnectTimeoutException` 时记 `connect_timeout`。连接拒绝等其他错误仍走原有 IO 分类，不伪称超时。 |
| 等待 HTTP 响应头 | 从开始执行流式请求到首个非空 body 块，共用 90000 ms 开始窗口 | 无进展到期记 `response_start_timeout`；未观察到头时 HTTP status 仍为 null。 |
| 等待首个 body 数据块 | 同一开始窗口继续计时，响应头不会重置它 | 已观察到头时保留 status，但零字节 body 仍不能无限等待。 |
| 持续读取 body | 每次非空块后重新等待，空闲上限 90000 ms | 总耗时无上限；超过窗口无新 body 字节记 `read_idle_timeout`。最后的数据块之后等待 chunk 结束分帧也受此约束。 |
| 完整性与页面校验 | EOF、有效 Content-Length、已有鉴权/类型/成绩页结构校验 | 字节数不匹配或可确认的 EOF 读错误记 `body_incomplete`；无效 HTML 仍按原有页面校验重试。 |

90000 ms 是宽松的空闲窗口，比旧的整个请求 60000 ms 还长，允许普通移动网络短暂卡顿；它不是新的总下载上限。无首字节时单次最多约 90 秒，读取过程中每次真正进展重新开始空闲窗口。仍保留成绩页最多三次尝试及原退避抖动策略；正在传输的请求不会为了超过 60/120 秒而重发。

响应开始阶段使用结构化子协程 watchdog；首个非空块取消它。后续通过 `withTimeoutOrNull` 约束下一次非空读取。计时器自产生的超时转换成可重试 IO 错误，外部协程取消继续传播，不会当作网络重试。

## 完整性、资源与取消

- 最大 body 为 **8 MiB（8388608 字节）**。有效声明长度超过上限时提前拒绝；无长度的 chunked 流逐块计数，超过上限立即拒绝。`body_too_large` 不重试，避免重复下载同一个过大页面。
- 每次读取至多 32 KiB，缓存仅保留受上限约束的 body。通过 `ByteArrayOutputStream.toString(charset)` 一次解码，保留响应 charset / UTF-8 fallback；跨块的多字节字符不会逐块解码损坏。没有在下载期间保存第二份 Ktor 全量响应。
- 8 MiB 约为已知约 0.8M 字符页面的数倍余量，实际保护按字节执行。解码字符串和 Jsoup DOM 会额外使用内存；本次没有宣称实测了 Android 峰值堆内存。
- 有 Content-Length 且无 Transfer-Encoding 时比较完整收到的 body 字节数。无效 chunk framing 使用 Ktor 的真实读失败；无结构化证据的错误不根据文本猜测分类。
- Jsoup 会修复截断文档。原校验只在空成绩列表分支要求 `</html>`，完整卡片存在时可能提前通过。本次把结束标记要求移到所有成绩页的入口，保留已有鉴权失败、错误页、内容类型及结构校验。
- 半页不会从下载函数成功返回给解析和持久化。下载失败由原有限重试处理；无效 HTML 在生产客户端的原验证边界重试。
- 读取的 finally 取消 channel；异常退出还会先取消 response 的调用作用域，再让 `execute(block)` 清理并等待其子任务，结构化作用域取消 watchdog。测试曾发现只声明超大长度却不发正文时，单靠默认 cleanup 可能等待仍在读网络的任务，随后被开始计时器覆盖分类；该分支现已显式取消调用，提前拒绝可立即完成。用户/宿主取消继续抛出 `CancellationException`，不重试、不返回半页、不继续下一难度或写入导入批次。
- 字节上限保证无限数据流不能无限增长内存；没有添加最低吞吐量或总时长限制。极慢但始终有非空数据的请求可以很长，这是批准的产品语义，仍可由宿主取消。

## 外层生命周期、重试及持久化审计

`WahlapImportRunner`、`RealWahlapImportAdapter` 没有包裹完整成绩下载的总时长限制。前台服务十分钟计时仅用于等待授权，`beginImport()` 会取消；两小时 WakeLock 到期只释放 CPU 唤醒保障，不取消下载。Android/OEM 后台限制和长时间锁屏仍需真实设备验收。

本轮没有改服务任务准入、executionId 所有权判断、START_NOT_STICKY、Room 数据结构或持久化顺序。下载进展只记录到当前请求 meta，不新增后台下载任务、定时持久化或 per-chunk UI 回调。不会因进展事件重复入库或把旧请求结果写到新任务状态。

OAuth authorize / callback 的请求预算仍为 30 秒且各单次尝试，callback 的单次使用门闩保留。Cookie Jar、会话关闭、回调头、User-Agent、Referer、微信 Hook/VPN、HOME 验证及 Supplemental 请求头没有改动。LOGIN_HOME 仍为 30 秒 / 最多三次；SUPPLEMENTAL_PAGE 仍为 45 秒 / 最多两次。

成绩页最终失败仍由 adapter 记录对应难度失败，其他成功页继续解析、去重、隔离和 upsert。已有成绩不删除；全部没有有用数据时仍为 FAILED，有用数据与失败并存仍为 PARTIAL，无失败仍为 COMPLETE。

## 诊断与 UI

JSON 保留 `schema_version=1` 以及原字段，新增可选请求聚合字段：

- `response_bytes`：各次尝试实际读到的 body 字节总数，包含失败尝试。
- `progress_chunks`：非空应用层读取次数总数，不等同于服务端 chunk 数或 TCP 包数。
- `last_download_failure`：最后一次实际观察到的分类，可为 `connect_timeout`、`response_start_timeout`、`read_idle_timeout`、`body_incomplete`、`body_too_large`、`cancelled`；未观察时为 null。后续成功不会抹掉此前观察到的失败。

原 `response_chars` 继续表示完整响应解码后的字符数，含义没有改成字节。新增计数沿用现有数值及 48 KiB 报告容量约束，枚举通过显式 allowlist 序列化；旧 JSON 缺少新字段时仍可读取。结构化诊断不增加 URL、HTML、Cookie、异常文本或设备信息。

长请求保留当前难度的 Loading / “正在读取”状态；真正停滞后重试会显示“读取停滞，正在重试（n/3）”，不会推进逻辑页数或提前标记失败。既有百分比表示已处理逻辑页面，不是未知长度的 body 下载百分比，本次没有伪造下载比例。

## 测试结果与复现

最终相关测试共发现 **447 项：446 通过、1 跳过、0 失败 / 0 错误**。新增关键测试 **21/21 通过，无跳过**。最后一次长下载实测 **126329 ms，851580 字节，1 个 HTTP 请求**，完成 3 条成绩解析、保存和一个导入批次。

| 模块 | 通过 | 跳过 | 最终失败 |
| --- | ---: | ---: | ---: |
| app（含新增网络测试、授权/Cookie/Header、前台服务和诊断） | 259 | 0 | 0 |
| core/importer | 111 | 1 | 0 |
| core/database（短临时目录复测） | 15 | 0 | 0 |
| feature/import（导入 UI） | 12 | 0 | 0 |
| core/model | 47 | 0 | 0 |
| core/privacy | 2 | 0 | 0 |
| 合计 | **446** | **1** | **0** |

跳过项为既有 `JapaneseConstantCatalogTest.optionalLiveSnapshotParsesAndReportsCoverage`，需要可选的 `FLUENTMAI_JP_LIVE_FIXTURE`，本环境没有设置。没有跳过本任务的任何关键场景。

[脱敏测试结果清单](AUTH-NET-TIMEOUT-1-TEST-RESULTS.json) 包含模块/测试类计数、新增 21 项的逐项结果及墙钟采样；仅摘录原始 JUnit XML 的安全字段，不包含原始日志、hostname、工作区绝对路径或系统输出全文。所有新增网络场景使用仅绑定 `127.0.0.1` 的原始 HTTP/1.1 fixture 和仓库合成 HTML，不访问华立，不依赖真实授权或手机。

| 验收场景 | 实际测试和断言 |
| --- | --- |
| 1. 快速正常页 | `fastCompletePageParsesAndPersistsOnce`：真实 HTTP、完整 HTML、3 条解析/保存、一个请求和一个导入批次。 |
| 2. 超过 60 / 120 秒 | `continuousLargeScoreSurvivesMoreThan120SecondsWithOneRequest`：22 个真实 chunk，间隔 6 秒，总体超过 120 秒；65 秒时仍 Loading、难度仍为 EXPERT、页数未推进；最终只请求一次并完整解析/保存。使用生产 90 秒窗口。 |
| 3. 响应开始 / 首字节停滞 | `acceptedConnectionWithoutHeadersHitsResponseStartTimeout`、`headersWithoutFirstBodyByteStillHitResponseStartTimeout`：分别验证未收头与已收头，都是开始超时，收到字节为零，连接关闭。 |
| 4. 中途停滞 | `stalledBodyStopsAfterThreeAttemptsAndDoesNotPersistPartialHtml`：发送含完整成绩卡的半页后停住；三次 read idle 失败、前两次重试、最终零持久化调用；两条生产客户端还有停滞后恢复的验证。 |
| 5. 大页与短暂停顿 | 上述长测试使用约 0.85 MB 页面；`briefPausesResetIdleWindowOnEachNonemptyRead` 在块之间暂停 120 ms，低于注入的 500 ms idle，仍完整成功。 |
| 6. 断开 / 不完整 | `declaredLengthTruncationCannotSucceedEvenWithClosingHtmlTag`：发送完整 HTML 却少于声明长度，三次失败、零写入；`missingChunkTerminatorDoesNotReturnPartialBody` 验证 chunk 中断；两条生产客户端还验证传输完整但 HTML 截断时三次重试、零写入。 |
| 7. 主动取消 | `cancellationClosesActiveReadAndDoesNotRetryOrPersist`：先确认应用已实际读到非空块，再取消，3 秒内结束并断开连接；一次请求、无重试、零写入，记录 cancelled。 |
| 8. 其他请求与导入结果 | 实际 HTTP 验证 callback/home/supplemental 仍使用总预算；原 callback 单次、会话 Cookie、Header/UA、HOME 与 Supplemental contract 测试回归。两种生产客户端都验证 EXPERT 三次失败时其他难度仍保存，旧成绩仍存在且为 PARTIAL。 |
| 尺寸保护 | `excessiveContentLengthFailsBeforeReadingAndWithoutRetry` 验证只发超大 Content-Length、不发 body，也能立刻拒绝并关连接；`unendingChunkedStreamIsCappedAndConnectionClosed` 验证未知长度无限流在 8 MiB 上限触发、最多额外读取一个 32 KiB 块且不重试。 |
| 字符集与隐私 | `multibyteCharsetDecodesAfterAllChunksAreReceived` 把 UTF-16LE 字节拆开传输后正确解码；`ImportDownloadDiagnosticsTest` 四项覆盖枚举、旧 JSON、新计数聚合/上限及异常文本不进入 JSON。 |

新增测试类：`WahlapProgressTimeoutTest`（14 项）、`WahlapProgressClientTest`（3 项，每项分别运行 OAuth 与手动 Cookie 客户端）、`ImportDownloadDiagnosticsTest`（4 项），共 21 项 JUnit 测试。既有核心 validator/fetcher 测试同时更新断言。

完整复现（Windows，项目原有 JDK / Android SDK / Gradle 依赖就绪）：

```powershell
.\gradlew.bat :app:testDebugUnitTest :core:importer:test :core:database:testDebugUnitTest :feature:import:testDebugUnitTest :core:model:jvmTest :core:privacy:test --offline
```

如首次环境缺少依赖，去掉 `--offline` 让 Gradle 正常解析依赖。超长网络测试使用真实墙钟 126 秒左右，不能用虚拟协程时间替代；其余停滞测试将同一个生产 profile 的窗口注入为 500/750 ms，以快速验证相同控制路径。测试夹具会关闭所有 socket 并校验工作线程退出。

Windows 环境的首次扩展运行中，应用 259 项与 importer 已通过，但两个既有磁盘迁移测试出现 legacy SQLite `SQLITE_CANTOPEN`，因此 Gradle 停止后续任务。将**测试 JVM** 的临时目录改短后，database 15 项全部通过，数据库代码及迁移测试均未修改。这与 Windows 临时路径长度问题一致；未改变生产数据库路径或 Room 行为。为可复现交付附带可选的 test-only init script：

```powershell
.\gradlew.bat :core:database:testDebugUnitTest :feature:import:testDebugUnitTest :core:model:jvmTest :core:privacy:test --offline --continue --init-script docs/research/auth-net/AUTH-NET-TIMEOUT-1-WINDOWS-TEST.init.gradle -PauthNetTestTmp=D:/fmt1
```

该脚本只创建指定的短临时目录并向 Test 任务传入 `java.io.tmpdir`，不删除文件。附带结果清单汇总各模块最终执行结果，不把首次失败算作通过，也不把 skip 算作通过。

## 人工验收及已知限制

1. Owner 在经批准的后续测试包中，用真实设备分别验证 EXPERT / MASTER 的长下载、Wi-Fi/移动网络切换及锁屏后台行为；本轮没有执行这类授权或手机操作。
2. 验证真实页面大小距 8 MiB 上限的余量以及 Android 内存；若后续真实完整页面超过上限，应根据测量调整尺寸保护，不能恢复短总请求预算。
3. 本地受控测试证明超时、完整性、取消、解析及导入边界，不证明本次历史 EXPERT 故障的服务器原因，也不覆盖所有真实 TLS/DNS/运营商故障。
4. 本轮保留既有取消入口；读取协程取消和服务销毁可释放请求，没有扩展导入页整体设计。
5. 未改版本号、签名、发布 APK、master、Release 或微信授权交互。原工作区既有修改全部留在原位置，本分支从干净基线的独立 worktree 实现。

## GitHub 交付

实现、测试及脱敏材料提交：[`4813c33eecb207b69b9c7635731f7f74433ae5df`](https://github.com/Daozhu1007/FluentMai/commit/4813c33eecb207b69b9c7635731f7f74433ae5df)。

- 分支：`codex/auth-net-timeout-1`
- [流式下载实现永久链接](https://github.com/Daozhu1007/FluentMai/blob/4813c33eecb207b69b9c7635731f7f74433ae5df/app/src/main/kotlin/dev/fluentmai/android/WahlapScorePageDownload.kt)
- [真实 HTTP 进展与超时测试永久链接](https://github.com/Daozhu1007/FluentMai/blob/4813c33eecb207b69b9c7635731f7f74433ae5df/app/src/test/kotlin/dev/fluentmai/android/WahlapProgressTimeoutTest.kt)
- [两条生产客户端回归永久链接](https://github.com/Daozhu1007/FluentMai/blob/4813c33eecb207b69b9c7635731f7f74433ae5df/app/src/test/kotlin/dev/fluentmai/android/WahlapProgressClientTest.kt)
- [脱敏测试清单永久链接](https://github.com/Daozhu1007/FluentMai/blob/4813c33eecb207b69b9c7635731f7f74433ae5df/docs/research/auth-net/AUTH-NET-TIMEOUT-1-TEST-RESULTS.json)

本报告通过后续仅文档提交交付。报告自身的最终提交 SHA 及报告永久链接由 Owner 最终交付消息提供，也可从当前 GitHub 文件所属 commit 查看；上述实现和证据链接固定于已验证的源码提交。
