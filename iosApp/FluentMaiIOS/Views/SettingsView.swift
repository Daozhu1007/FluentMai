import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let scrollToTopRequestID: Int

    @State private var versionDraft = 25_500

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(spacing: 12) {
                    Color.clear.frame(height: 1).id("settings-top")
                    settingsCard(
                        title: "外观",
                        primary: "跟随 iOS 系统深色模式",
                        secondary: "成绩卡片、谱面卡片与底栏会一起切换。"
                    ) {
                        Text("系统控制").font(.caption).padding(8).background(.quaternary, in: Capsule())
                    }
                    settingsCard(
                        title: "上传",
                        primary: "敏感凭据不落盘",
                        secondary: "水鱼和 LXNS Token 只保留在导入页面当前会话中。"
                    )
                    versionCard
                    settingsCard(
                        title: "诊断",
                        primary: "隔离记录",
                        secondary: "当前没有解析失败或未知格式记录。"
                    ) {
                        Text("0 条").font(.caption).padding(8).background(.quaternary, in: Capsule())
                    }
                    settingsCard(
                        title: "隐私",
                        primary: "本地优先",
                        secondary: "原始 Cookie、Token 与授权 URL 不写入本地文件；备份只包含成绩、别名和趋势。"
                    )
                    aboutCard
                }
                .frame(maxWidth: 1_000)
                .padding(20)
                .frame(maxWidth: .infinity)
            }
            .background(FluentPalette.background)
            .navigationTitle("设置")
            .navigationBarTitleDisplayMode(.inline)
            .navigationBarBackButtonHidden(true)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { dismiss() } label: { Image(systemName: "chevron.left") }
                        .accessibilityLabel("返回工具箱")
                }
            }
            .onAppear { versionDraft = model.userData.currentVersionId }
            .onChange(of: scrollToTopRequestID) { _, request in
                guard request > 0 else { return }
                withAnimation(.easeInOut(duration: 0.28)) { proxy.scrollTo("settings-top", anchor: .top) }
            }
        }
    }

    private var versionCard: some View {
        FluentCard {
            VStack(alignment: .leading, spacing: 9) {
                Text("Rating 版本").font(.caption.weight(.semibold)).foregroundStyle(FluentPalette.primary)
                Text("当前运营大版本").font(.headline)
                TextField("版本编号", value: $versionDraft, format: .number)
                    .keyboardType(.numberPad)
                    .textFieldStyle(.roundedBorder)
                Button("应用版本") { model.setCurrentVersion(versionDraft) }
                    .buttonStyle(.bordered)
                    .disabled(versionDraft <= 0 || versionDraft == model.userData.currentVersionId)
                Text("该编号决定当前版本 B15 与旧版本 B35 的分桶。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var aboutCard: some View {
        FluentCard {
            VStack(alignment: .leading, spacing: 9) {
                Text("关于").font(.caption.weight(.semibold)).foregroundStyle(FluentPalette.primary)
                Text("FluentMai").font(.title2.bold())
                Text("v\(appVersion) · 本地优先的舞萌 DX 成绩导入、查询与上传工具")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                LabeledContent("开发者", value: "Limitime")
                LabeledContent("邮箱", value: "Daozhu1007@outlook.com")
                LabeledContent("项目", value: "Daozhu1007 / FluentMai")
                Text("FluentMai 是独立社区工具，与 SEGA、华立、Diving Fish、LXNS 官方均无从属关系。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func settingsCard<Trailing: View>(
        title: String,
        primary: String,
        secondary: String,
        @ViewBuilder trailing: () -> Trailing
    ) -> some View {
        FluentCard {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(title).font(.caption.weight(.semibold)).foregroundStyle(FluentPalette.primary)
                    Text(primary).font(.headline)
                    Text(secondary).font(.subheadline).foregroundStyle(.secondary)
                }
                Spacer()
                trailing()
            }
        }
    }

    private func settingsCard(title: String, primary: String, secondary: String) -> some View {
        settingsCard(title: title, primary: primary, secondary: secondary) { EmptyView() }
    }

    private var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.2.4"
    }
}
