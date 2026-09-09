import SwiftUI
import UniformTypeIdentifiers

struct ImportView: View {
    @EnvironmentObject private var model: AppModel
    let scrollToTopRequestID: Int

    @State private var cookieInput = ""
    @State private var divingFishToken = ""
    @State private var lxnsToken = ""
    @State private var importStatus = "未开始"
    @State private var uploadStatus = "未开始"
    @State private var isImportingFile = false
    @State private var isExportingFile = false
    @State private var exportDocument = BackupDocument(data: Data())

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Color.clear.frame(height: 1).id("import-top")
                    Text("导入与上传").font(.title2.bold())
                    hookCard
                    cookieCard
                    localBackupCard
                    statusCard
                    uploadCard
                    uploadStatusCard
                }
                .frame(maxWidth: 1_000)
                .padding(20)
                .frame(maxWidth: .infinity)
            }
            .background(FluentPalette.background)
            .navigationBarHidden(true)
            .onChange(of: scrollToTopRequestID) { _, request in
                guard request > 0 else { return }
                withAnimation(.easeInOut(duration: 0.28)) { proxy.scrollTo("import-top", anchor: .top) }
            }
        }
        .fileImporter(isPresented: $isImportingFile, allowedContentTypes: [.json]) { result in
            switch result {
            case .success(let url):
                let access = url.startAccessingSecurityScopedResource()
                defer { if access { url.stopAccessingSecurityScopedResource() } }
                do {
                    try model.importBackup(Data(contentsOf: url))
                    importStatus = "成功 · 已导入 \(model.userData.scores.count) 条本地成绩"
                } catch {
                    importStatus = "失败 · \(error.localizedDescription)"
                }
            case .failure(let error):
                importStatus = "失败 · \(error.localizedDescription)"
            }
        }
        .fileExporter(
            isPresented: $isExportingFile,
            document: exportDocument,
            contentType: .json,
            defaultFilename: "fluentmai-ios-backup"
        ) { result in
            if case .failure(let error) = result { importStatus = "导出失败 · \(error.localizedDescription)" }
        }
    }

    private var hookCard: some View {
        FluentCard {
            VStack(alignment: .leading, spacing: 10) {
                Label("微信 Hook 导入", systemImage: "link")
                    .font(.headline)
                Text("iOS 不允许普通应用创建 Android 使用的本地 VPN 抓包服务。该入口不会请求或伪装 VPN 权限。")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Button("启动捕获", systemImage: "play.fill") { importStatus = "iOS 平台不支持本地 VPN Hook" }
                    .buttonStyle(.borderedProminent)
                    .disabled(true)
                Text("状态：iOS 平台受限")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var cookieCard: some View {
        FluentCard {
            VStack(alignment: .leading, spacing: 10) {
                Text("Wahlap Cookie 导入").font(.headline)
                SecureField("Cookie / Reqable 请求头", text: $cookieInput)
                    .textFieldStyle(.roundedBorder)
                Button("使用 Cookie 导入本地成绩", systemImage: "play.fill") {
                    importStatus = "iOS 的 Wahlap 请求适配仍在接入；输入未保存也未发送"
                }
                .buttonStyle(.borderedProminent)
                .disabled(true)
                Text("敏感输入仅保留在当前页面内存中，切勿通过截图或日志分享。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var localBackupCard: some View {
        FluentCard {
            VStack(alignment: .leading, spacing: 10) {
                Text("iOS 本地备份").font(.headline)
                Text("可导入或导出本机的成绩、别名与 Rating 趋势；不会读取 Android 数据库。")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                HStack {
                    Button("导入 JSON", systemImage: "square.and.arrow.down") { isImportingFile = true }
                        .buttonStyle(.borderedProminent)
                    Button("导出 JSON", systemImage: "square.and.arrow.up") {
                        do {
                            exportDocument = BackupDocument(data: try model.backupData())
                            isExportingFile = true
                        } catch {
                            importStatus = "导出失败 · \(error.localizedDescription)"
                        }
                    }
                    .buttonStyle(.bordered)
                }
            }
        }
    }

    private var statusCard: some View {
        FluentCard {
            VStack(alignment: .leading, spacing: 7) {
                Text("导入状态").font(.headline)
                Text(importStatus)
                Text("本地已有 \(model.userData.scores.count) 条成绩。")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var uploadCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("上传").font(.headline)
            SecureField("水鱼 Import Token", text: $divingFishToken)
                .textFieldStyle(.roundedBorder)
            Button("上传到水鱼", systemImage: "icloud.and.arrow.up") {
                uploadStatus = "iOS 上传客户端尚未接入；Token 未保存也未发送"
            }
            .buttonStyle(.borderedProminent)
            .disabled(true)
            SecureField("落雪 LXNS User Token", text: $lxnsToken)
                .textFieldStyle(.roundedBorder)
            Button("上传到落雪", systemImage: "icloud.and.arrow.up") {
                uploadStatus = "iOS 上传客户端尚未接入；Token 未保存也未发送"
            }
            .buttonStyle(.borderedProminent)
            .disabled(true)
        }
        .padding(.horizontal, 4)
    }

    private var uploadStatusCard: some View {
        FluentCard {
            VStack(alignment: .leading, spacing: 7) {
                Text("上传状态").font(.headline)
                Text(uploadStatus)
                Text("本地已有 \(model.userData.scores.count) 条成绩可上传。")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

struct BackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    var data: Data

    init(data: Data) { self.data = data }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
