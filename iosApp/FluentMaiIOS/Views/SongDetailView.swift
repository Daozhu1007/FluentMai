import SwiftUI

struct SongDetailView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let item: CatalogChartItem
    let scrollToTopRequestID: Int

    @State private var selectedChart: SongChart
    @State private var aliasDraft = ""
    @State private var editingScore = false

    init(item: CatalogChartItem, scrollToTopRequestID: Int) {
        self.item = item
        self.scrollToTopRequestID = scrollToTopRequestID
        _selectedChart = State(initialValue: item.chart)
    }

    private var selectedItem: CatalogChartItem {
        model.chartItems.first { $0.song.id == item.song.id && $0.chart.id == selectedChart.id }
            ?? CatalogChartItem(song: item.song, chart: selectedChart, sssPlusTolerance: nil)
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 14) {
                    Color.clear.frame(height: 1).id("detail-top")
                    detailHeader
                    difficultySwitcher
                    detailSection("歌曲") {
                        detailValue("Song ID", String(item.song.id))
                        detailValue("谱面身份", "\(item.song.id) · \(selectedChart.typeLabel) · \(selectedChart.difficultyLabel)")
                        detailValue("曲师", item.song.artist.isEmpty ? "--" : item.song.artist)
                        detailValue("类别", item.song.genre.isEmpty ? "--" : item.song.genre)
                        detailValue("BPM", item.song.bpm.map(String.init) ?? "--")
                        detailValue("歌曲版本", String(item.song.version))
                        detailValue("谱面版本", String(selectedChart.version))
                    }
                    detailSection("谱面") {
                        detailValue("类型", selectedChart.typeLabel)
                        detailValue("难度", "\(selectedChart.difficultyLabel) \(selectedChart.level)")
                        detailValue("定数", selectedChart.levelValue.formatted(.number.precision(.fractionLength(1))))
                        detailValue("谱师", selectedChart.noteDesigner.isEmpty ? "--" : selectedChart.noteDesigner)
                        detailValue("总 Note", selectedChart.notes.map { String($0.total) } ?? "--")
                        detailValue("Note 明细", noteDetails)
                        detailValue("SSS+容错", selectedItem.sssPlusTolerance.map(String.init) ?? "--")
                    }
                    playerBest
                    aliases
                }
                .frame(maxWidth: 1_000)
                .padding(16)
                .frame(maxWidth: .infinity)
            }
            .background(FluentPalette.background)
            .navigationTitle(item.song.title)
            .navigationBarTitleDisplayMode(.inline)
            .navigationBarBackButtonHidden(true)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { dismiss() } label: { Image(systemName: "chevron.left") }
                        .accessibilityLabel("返回")
                }
            }
            .onChange(of: scrollToTopRequestID) { _, request in
                guard request > 0 else { return }
                withAnimation(.easeInOut(duration: 0.28)) { proxy.scrollTo("detail-top", anchor: .top) }
            }
        }
        .sheet(isPresented: $editingScore) {
            ScoreEditorSheet(
                song: item.song,
                chart: selectedChart,
                existingScore: model.score(for: item.song.id, chart: selectedChart)
            )
            .environmentObject(model)
        }
    }

    private var detailHeader: some View {
        FluentCard {
            HStack(alignment: .top, spacing: 14) {
                JacketArtView(songID: item.song.id, title: item.song.title)
                .frame(width: 108, height: 108)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                VStack(alignment: .leading, spacing: 7) {
                    Text(item.song.title)
                        .font(.title3.bold())
                    Text("Song \(item.song.id) · \(selectedChart.typeLabel) · \(selectedChart.difficultyLabel)")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    Text("\(selectedChart.level)  \(selectedChart.levelValue.formatted(.number.precision(.fractionLength(1))))")
                        .font(.headline.monospacedDigit())
                        .foregroundStyle(difficultyColor)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(difficultyColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 8))
                }
            }
        }
    }

    private var difficultySwitcher: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("切换谱面").font(.headline)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack {
                    ForEach(item.song.allCharts) { chart in
                        if chart == selectedChart {
                            Button("\(chart.typeLabel) \(chart.difficultyLabel) \(chart.level)") {
                                selectedChart = chart
                            }
                            .buttonStyle(.borderedProminent)
                        } else {
                            Button("\(chart.typeLabel) \(chart.difficultyLabel) \(chart.level)") {
                                selectedChart = chart
                            }
                            .buttonStyle(.bordered)
                        }
                    }
                }
            }
        }
        .padding(.horizontal, 4)
    }

    private var playerBest: some View {
        let score = model.score(for: item.song.id, chart: selectedChart)
        return detailSection("玩家最佳") {
            detailValue("达成率", score.map { $0.achievement.formatted(.number.precision(.fractionLength(4))) + "%" } ?? "未游玩")
            detailValue("Rating 贡献", score.map { String($0.rating) } ?? "--")
            detailValue("FC", score?.fullCombo?.uppercased() ?? "--")
            detailValue("FS", score?.fullSync?.uppercased() ?? "--")
            detailValue("DX Score", score?.dxScore.map(String.init) ?? "--")
            Button(score == nil ? "录入本地成绩" : "更新本地成绩") { editingScore = true }
                .buttonStyle(.borderedProminent)
        }
    }

    private var aliases: some View {
        detailSection("别名与数据来源") {
            detailValue("别名", model.aliases(for: item.song.id).isEmpty
                ? "暂无已映射别名"
                : model.aliases(for: item.song.id).joined(separator: "、"))
            HStack {
                TextField("添加本地别名", text: $aliasDraft)
                    .textFieldStyle(.roundedBorder)
                Button("添加") {
                    model.addAlias(aliasDraft, for: item.song.id)
                    aliasDraft = ""
                }
                .disabled(aliasDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            detailValue("别名数据", "本地缓存；基本字段搜索始终可用")
        }
    }

    private var noteDetails: String {
        guard let notes = selectedChart.notes else { return "--" }
        return "Tap \(notes.tap) · Hold \(notes.hold) · Slide \(notes.slide) · Touch \(notes.touch) · Break \(notes.breakCount)"
    }

    private func detailSection<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        FluentCard {
            VStack(alignment: .leading, spacing: 11) {
                Text(title).font(.headline)
                content()
            }
        }
    }

    private func detailValue(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption).foregroundStyle(.secondary)
            Text(value).font(.body)
        }
    }

    private var difficultyColor: Color {
        switch selectedChart.difficulty {
        case 0: .green
        case 1: .orange
        case 2: .red
        case 3: .purple
        default: .indigo
        }
    }
}

private struct ScoreEditorSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss

    let song: Song
    let chart: SongChart
    let existingScore: ScoreEntry?

    @State private var achievement: Double
    @State private var playedAt: Date

    init(song: Song, chart: SongChart, existingScore: ScoreEntry?) {
        self.song = song
        self.chart = chart
        self.existingScore = existingScore
        _achievement = State(initialValue: existingScore?.achievement ?? 100.0)
        _playedAt = State(initialValue: existingScore?.playedAt ?? Date())
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("谱面") {
                    LabeledContent("曲目", value: song.title)
                    LabeledContent("难度", value: "\(chart.typeLabel) · \(chart.difficultyLabel) · \(chart.level)")
                    LabeledContent("定数", value: chart.levelValue.formatted(.number.precision(.fractionLength(1))))
                }
                Section("成绩") {
                    TextField("达成率", value: $achievement, format: .number.precision(.fractionLength(4)))
                        .keyboardType(.decimalPad)
                    DatePicker("游玩时间", selection: $playedAt)
                }
                Section("即时计算") {
                    LabeledContent("单曲 Rating", value: String(model.previewRating(levelValue: chart.levelValue, achievement: achievement)))
                }
            }
            .navigationTitle(existingScore == nil ? "录入成绩" : "更新成绩")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        model.saveScore(song: song, chart: chart, achievement: achievement, playedAt: playedAt)
                        dismiss()
                    }
                    .disabled(!achievement.isFinite || !(0...101).contains(achievement))
                }
            }
        }
    }
}
