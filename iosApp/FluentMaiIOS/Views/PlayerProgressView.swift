import SwiftUI

enum PlayerProgressDestination: String, Hashable, CaseIterable, Identifiable {
    case plates = "牌子进度"
    case recommendations = "推分建议"

    var id: String { rawValue }
}

struct PlayerProgressView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss

    let scrollToTopRequestID: Int
    let onOpenChart: (CatalogChartItem) -> Void

    @State private var destination: PlayerProgressDestination
    @State private var selectedDifficulty: Int?
    @State private var onlyIncomplete = true
    @State private var targetAchievement = 100.5

    init(
        initialDestination: PlayerProgressDestination,
        scrollToTopRequestID: Int,
        onOpenChart: @escaping (CatalogChartItem) -> Void
    ) {
        self.scrollToTopRequestID = scrollToTopRequestID
        self.onOpenChart = onOpenChart
        _destination = State(initialValue: initialDestination)
    }

    private var plateItems: [CatalogChartItem] {
        model.chartItems.filter { item in
            selectedDifficulty == nil || item.chart.difficulty == selectedDifficulty
        }.filter { item in
            guard onlyIncomplete else { return true }
            return (model.score(for: item)?.achievement ?? 0) < 100.0
        }.sorted { $0.chart.levelValue > $1.chart.levelValue }
    }

    private var recommendations: [(CatalogChartItem, Int, Int)] {
        model.chartItems.compactMap { item in
            guard let score = model.score(for: item) else { return nil }
            let current = score.rating
            let target = model.previewRating(levelValue: item.chart.levelValue, achievement: targetAchievement)
            let gain = max(0, target - current)
            guard gain > 0 else { return nil }
            return (item, current, gain)
        }.sorted {
            if $0.2 != $1.2 { return $0.2 > $1.2 }
            return $0.0.chart.levelValue > $1.0.chart.levelValue
        }.prefix(100).map { $0 }
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    Color.clear.frame(height: 1).id("progress-top")
                    header
                    if destination == .plates {
                        plateControls
                        plateSummary
                        ForEach(plateItems.prefix(200)) { item in
                            ChartCard(item: item, score: model.score(for: item)) { onOpenChart(item) }
                        }
                    } else {
                        recommendationControls
                        recommendationSummary
                        ForEach(recommendations, id: \.0.id) { item, current, gain in
                            recommendationCard(item: item, current: current, gain: gain)
                        }
                    }
                }
                .frame(maxWidth: 1_000)
                .padding(16)
                .frame(maxWidth: .infinity)
            }
            .background(FluentPalette.background)
            .navigationTitle(destination.rawValue)
            .navigationBarTitleDisplayMode(.inline)
            .navigationBarBackButtonHidden(true)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { dismiss() } label: { Image(systemName: "chevron.left") }
                        .accessibilityLabel("返回首页")
                }
            }
            .onChange(of: scrollToTopRequestID) { _, request in
                guard request > 0 else { return }
                if request > 0 {
                    // Jump close to the start before animating to avoid the long hitch seen on large plate lists.
                    proxy.scrollTo("progress-top", anchor: .top)
                }
            }
        }
    }

    private var header: some View {
        FluentCard {
            VStack(alignment: .leading, spacing: 12) {
                Text(destination.rawValue).font(.title2.bold())
                Text(destination == .plates
                    ? "规则数据不足时不会宣布完成；当前结果来自本机已保存成绩。"
                    : "按真实谱面定数和本地成绩估算单谱面 Rating 提升，不评估技术风格。")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Picker("页面", selection: $destination) {
                    ForEach(PlayerProgressDestination.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
            }
        }
    }

    private var plateControls: some View {
        FluentCard {
            VStack(alignment: .leading, spacing: 10) {
                Text("查看条件").font(.headline)
                Toggle("只看未完成", isOn: $onlyIncomplete)
                Picker("难度", selection: $selectedDifficulty) {
                    Text("全部难度").tag(Int?.none)
                    ForEach(0..<5, id: \.self) { value in
                        Text(difficultyName(value)).tag(Int?.some(value))
                    }
                }
            }
        }
    }

    private var plateSummary: some View {
        let played = model.userData.scores.count
        let sssPlus = model.userData.scores.filter { $0.achievement >= 100.5 }.count
        let sss = model.userData.scores.filter { $0.achievement >= 100.0 }.count
        return FluentCard {
            VStack(alignment: .leading, spacing: 8) {
                Text("进度摘要").font(.headline)
                HStack {
                    MetricPill(label: "已游玩", value: String(played))
                    MetricPill(label: "SSS", value: String(sss))
                    MetricPill(label: "SSS+", value: String(sssPlus))
                }
                Text("FC/AP 牌子需要导入完整判定字段；缺失时仅展示可核验的达成率进度。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var recommendationControls: some View {
        FluentCard {
            VStack(alignment: .leading, spacing: 10) {
                Text("目标与范围").font(.headline)
                Text("目标达成率会按“只提升这一张谱面”估算 Rating 变化。")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                TextField("目标达成率", value: $targetAchievement, format: .number.precision(.fractionLength(4)))
                    .keyboardType(.decimalPad)
                    .textFieldStyle(.roundedBorder)
            }
        }
    }

    private var recommendationSummary: some View {
        FluentCard {
            VStack(alignment: .leading, spacing: 8) {
                Text("计算口径").font(.headline)
                HStack {
                    MetricPill(label: "当前 B50", value: String(model.ratingSummary.totalRating))
                    MetricPill(label: "建议", value: String(recommendations.count))
                }
            }
        }
    }

    private func recommendationCard(item: CatalogChartItem, current: Int, gain: Int) -> some View {
        Button { onOpenChart(item) } label: {
            FluentCard {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text(item.song.title).font(.headline).foregroundStyle(.primary)
                        Spacer()
                        Text("+\(gain)")
                            .font(.headline.monospacedDigit())
                            .foregroundStyle(FluentPalette.primary)
                    }
                    Text("\(item.chart.typeLabel) · \(item.chart.difficultyLabel) · \(item.chart.level)（定数 \(item.chart.levelValue.formatted(.number.precision(.fractionLength(1))))）")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text("当前 \(current) → 目标 \(current + gain)")
                        .font(.subheadline.monospacedDigit())
                }
            }
        }
        .buttonStyle(.plain)
    }
}
