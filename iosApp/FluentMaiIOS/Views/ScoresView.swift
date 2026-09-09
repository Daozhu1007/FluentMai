import SwiftUI

struct ScoresView: View {
    @EnvironmentObject private var model: AppModel

    let scrollToTopRequestID: Int
    let onOpenPlayed: () -> Void
    let onOpenProgress: (PlayerProgressDestination) -> Void
    let onOpenChart: (CatalogChartItem) -> Void

    private var summary: RatingSummary { model.ratingSummary }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 14) {
                    Color.clear.frame(height: 1).id("home-top")
                    header
                    quickActions(proxy: proxy)

                    if model.userData.scores.isEmpty {
                        ContentUnavailableView(
                            "还没有导入成绩",
                            systemImage: "chart.bar",
                            description: Text("前往“导入”获取成绩，或在谱面详情中手动录入。")
                        )
                        .frame(maxWidth: .infinity, minHeight: 260)
                    } else {
                        scoreSection("旧版本 Best 35", scores: summary.oldBest, id: "old-best")
                        scoreSection("当前版本 Best 15", scores: summary.newBest, id: "new-best")
                    }
                }
                .frame(maxWidth: 1_000)
                .padding(16)
                .frame(maxWidth: .infinity)
            }
            .background(FluentPalette.background)
            .navigationBarHidden(true)
            .onChange(of: scrollToTopRequestID) { _, request in
                guard request > 0 else { return }
                withAnimation(.easeInOut(duration: 0.28)) { proxy.scrollTo("home-top", anchor: .top) }
            }
        }
    }

    private var header: some View {
        FluentCard {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Text("成绩")
                        .font(.title2.bold())
                    Spacer()
                    Text(String(summary.totalRating))
                        .font(.title2.monospacedDigit().bold())
                        .foregroundStyle(FluentPalette.primary)
                        .padding(.horizontal, 13)
                        .padding(.vertical, 7)
                        .background(FluentPalette.primary.opacity(0.12), in: Capsule())
                }
                HStack(spacing: 8) {
                    MetricPill(label: "本地成绩", value: String(model.userData.scores.count))
                    MetricPill(label: "曲库谱面", value: String(model.chartItems.count))
                }
            }
        }
    }

    private func quickActions(proxy: ScrollViewProxy) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("B50 快速跳转")
                .font(.subheadline.weight(.semibold))
            ScrollView(.horizontal, showsIndicators: false) {
                HStack {
                    Button("旧版本 B35 · \(summary.oldBest.count) 张") {
                        withAnimation { proxy.scrollTo("old-best", anchor: .top) }
                    }
                    .buttonStyle(.bordered)
                    Button("当前版本 B15 · \(summary.newBest.count) 张") {
                        withAnimation { proxy.scrollTo("new-best", anchor: .top) }
                    }
                    .buttonStyle(.bordered)
                }
            }
            HStack(spacing: 4) {
                Button("查看已游玩谱面", action: onOpenPlayed)
                Button("牌子进度") { onOpenProgress(.plates) }
                Button("推分建议") { onOpenProgress(.recommendations) }
            }
            .font(.subheadline)
            .buttonStyle(.borderless)
            .foregroundStyle(FluentPalette.primary)
        }
        .padding(.horizontal, 4)
    }

    @ViewBuilder
    private func scoreSection(_ title: String, scores: [ScoreEntry], id: String) -> some View {
        HStack {
            Text(title).font(.headline)
            Spacer()
            Text(String(scores.count)).foregroundStyle(.secondary)
        }
        .id(id)

        if scores.isEmpty {
            FluentCard {
                Text("暂无符合条件的成绩")
                    .foregroundStyle(.secondary)
            }
        } else {
            ForEach(Array(scores.enumerated()), id: \.element.id) { index, score in
                ScoreCard(score: score, rank: index + 1) {
                    if let item = model.chartItem(for: score) { onOpenChart(item) }
                }
            }
        }
    }
}

struct ScoreCard: View {
    let score: ScoreEntry
    let rank: Int?
    let onOpen: () -> Void

    var body: some View {
        Button(action: onOpen) {
            FluentCard {
                VStack(alignment: .leading, spacing: 10) {
                    HStack(alignment: .top) {
                        if let rank {
                            Text("#\(rank)")
                                .font(.caption.monospacedDigit().bold())
                                .foregroundStyle(FluentPalette.primary)
                        }
                        VStack(alignment: .leading, spacing: 3) {
                            Text(score.title)
                                .font(.headline)
                                .foregroundStyle(.primary)
                                .lineLimit(2)
                            Text("\(score.chartType.uppercased()) · \(score.difficultyLabel) · \(score.level)")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Text("+\(score.rating)")
                            .font(.headline.monospacedDigit())
                            .foregroundStyle(FluentPalette.primary)
                    }
                    HStack {
                        Text("\(score.achievement, format: .number.precision(.fractionLength(4)))%")
                            .font(.title3.monospacedDigit().bold())
                        Spacer()
                        if let fullCombo = score.fullCombo { Text(fullCombo.uppercased()) }
                        if let fullSync = score.fullSync { Text(fullSync.uppercased()) }
                    }
                    .font(.caption.weight(.semibold))
                }
            }
        }
        .buttonStyle(.plain)
    }
}

struct PlayedChartsView: View {
    @EnvironmentObject private var model: AppModel
    let scrollToTopRequestID: Int
    let onOpenChart: (CatalogChartItem) -> Void

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 12) {
                    Color.clear.frame(height: 1).id("played-top")
                    ForEach(model.userData.scores.sorted { $0.playedAt > $1.playedAt }) { score in
                        ScoreCard(score: score, rank: nil) {
                            if let item = model.chartItem(for: score) { onOpenChart(item) }
                        }
                    }
                }
                .padding(16)
            }
            .background(FluentPalette.background)
            .navigationTitle("已游玩谱面")
            .onChange(of: scrollToTopRequestID) { _, request in
                guard request > 0 else { return }
                withAnimation { proxy.scrollTo("played-top", anchor: .top) }
            }
        }
    }
}
