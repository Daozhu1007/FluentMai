import SwiftUI

struct CatalogView: View {
    @EnvironmentObject private var model: AppModel

    let scrollToTopRequestID: Int
    let onOpenChart: (CatalogChartItem) -> Void

    @State private var query = ""
    @State private var levelQuery = ""
    @State private var selectedGenre = "全部分区"
    @State private var selectedDifficulty: Int?
    @State private var chartMode: CatalogChartMode = .all
    @State private var playStatus: ChartPlayStatus = .all
    @State private var sortMode: ChartSortMode = .constantDescending

    private var filteredItems: [CatalogChartItem] {
        let normalizedQuery = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedLevel = levelQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        let filtered = model.chartItems.filter { item in
            let score = model.score(for: item)
            if selectedGenre != "全部分区", item.song.genre != selectedGenre { return false }
            if let selectedDifficulty, item.chart.difficulty != selectedDifficulty { return false }
            if chartMode == .standard, item.chart.type.lowercased() != "standard" { return false }
            if chartMode == .dx, item.chart.type.lowercased() != "dx" { return false }
            if playStatus == .played, score == nil { return false }
            if playStatus == .unplayed, score != nil { return false }
            if !normalizedLevel.isEmpty,
               item.chart.level.caseInsensitiveCompare(normalizedLevel) != .orderedSame,
               abs(item.chart.levelValue - (Double(normalizedLevel) ?? -100)) > 0.0001 { return false }
            if normalizedQuery.isEmpty { return true }
            let fields = [
                item.song.title, item.song.artist, item.song.genre, item.chart.noteDesigner,
                String(item.song.id), item.song.bpm.map(String.init) ?? ""
            ] + model.aliases(for: item.song.id)
            return fields.contains { $0.localizedCaseInsensitiveContains(normalizedQuery) }
        }
        return filtered.sorted(by: compare)
    }

    var body: some View {
        Group {
            switch model.catalogState {
            case .loading:
                ProgressView("正在读取本地公开曲库…")
            case .failed(let message):
                ContentUnavailableView("曲库不可用", systemImage: "exclamationmark.triangle", description: Text(message))
            case .ready:
                content
            }
        }
        .background(FluentPalette.background)
        .navigationBarHidden(true)
    }

    private var content: some View {
        let items = filteredItems
        return ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 12) {
                    Color.clear.frame(height: 1).id("charts-top")
                    header(resultCount: items.count)
                    filters
                    stats(items: items)

                    if items.isEmpty {
                        ContentUnavailableView(
                            "没有匹配的谱面",
                            systemImage: "magnifyingglass",
                            description: Text("尝试重置筛选条件。")
                        )
                        .frame(minHeight: 260)
                    } else {
                        ForEach(items.prefix(500)) { item in
                            ChartCard(item: item, score: model.score(for: item)) {
                                onOpenChart(item)
                            }
                        }
                    }
                }
                .frame(maxWidth: 1_000)
                .padding(16)
                .frame(maxWidth: .infinity)
            }
            .onChange(of: scrollToTopRequestID) { _, request in
                guard request > 0 else { return }
                withAnimation(.easeInOut(duration: 0.28)) { proxy.scrollTo("charts-top", anchor: .top) }
            }
        }
    }

    private func header(resultCount: Int) -> some View {
        FluentCard {
            HStack {
                VStack(alignment: .leading, spacing: 9) {
                    Text("谱面查询")
                        .font(.title2.bold())
                    HStack {
                        MetricPill(label: "曲库", value: String(model.chartItems.count))
                        MetricPill(label: "结果", value: String(resultCount))
                    }
                }
                Spacer()
                Image(systemName: "arrow.clockwise")
                    .font(.title3)
                    .foregroundStyle(FluentPalette.primary)
                    .accessibilityLabel("离线曲库已就绪")
            }
        }
    }

    private var filters: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("筛选条件")
                    .font(.headline)
                Spacer()
                Button("重置", systemImage: "xmark") { resetFilters() }
                    .disabled(!hasActiveFilters)
            }

            TextField("曲名 / 别名 / ID / BPM / 曲师 / 谱师", text: $query)
                .textFieldStyle(.roundedBorder)
                .textInputAutocapitalization(.never)
            TextField("等级或内部定数：13、13+、13.3", text: $levelQuery)
                .textFieldStyle(.roundedBorder)
                .keyboardType(.decimalPad)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    filterMenu(selectedDifficulty.map(difficultyName) ?? "全部难度") {
                        Button("全部难度") { selectedDifficulty = nil }
                        ForEach(0..<5, id: \.self) { value in
                            Button(difficultyName(value)) { selectedDifficulty = value }
                        }
                    }
                    filterMenu(selectedGenre) {
                        Button("全部分区") { selectedGenre = "全部分区" }
                        ForEach(model.genres, id: \.self) { genre in
                            Button(genre) { selectedGenre = genre }
                        }
                    }
                    filterMenu(playStatus.rawValue) {
                        ForEach(ChartPlayStatus.allCases) { status in
                            Button(status.rawValue) { playStatus = status }
                        }
                    }
                    filterMenu(chartMode.rawValue) {
                        ForEach(CatalogChartMode.allCases) { mode in
                            Button(mode.rawValue) { chartMode = mode }
                        }
                    }
                    filterMenu(sortMode.rawValue) {
                        ForEach(ChartSortMode.allCases) { mode in
                            Button(mode.rawValue) { sortMode = mode }
                        }
                    }
                }
            }
        }
        .padding(.horizontal, 4)
    }

    private func stats(items: [CatalogChartItem]) -> some View {
        let played = items.reduce(into: 0) { count, item in
            if model.score(for: item) != nil { count += 1 }
        }
        return FluentCard {
            Text("\(items.count) 谱面 · \(played) 已游玩 · \(max(0, items.count - played)) 未游玩")
                .font(.subheadline.weight(.medium))
        }
    }

    private func filterMenu<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        Menu(content: content) {
            HStack(spacing: 5) {
                Text(title).lineLimit(1)
                Image(systemName: "chevron.down")
            }
        }
        .buttonStyle(.bordered)
    }

    private var hasActiveFilters: Bool {
        !query.isEmpty || !levelQuery.isEmpty || selectedGenre != "全部分区" || selectedDifficulty != nil
            || chartMode != .all || playStatus != .all || sortMode != .constantDescending
    }

    private func resetFilters() {
        query = ""
        levelQuery = ""
        selectedGenre = "全部分区"
        selectedDifficulty = nil
        chartMode = .all
        playStatus = .all
        sortMode = .constantDescending
    }

    private func compare(_ left: CatalogChartItem, _ right: CatalogChartItem) -> Bool {
        let leftScore = model.score(for: left)
        let rightScore = model.score(for: right)
        switch sortMode {
        case .constantDescending:
            return stableCompare(left, right, primary: left.chart.levelValue > right.chart.levelValue)
        case .constantAscending:
            return stableCompare(left, right, primary: left.chart.levelValue < right.chart.levelValue)
        case .toleranceAscending:
            return stableOptionalCompare(left, right, left.sssPlusTolerance, right.sssPlusTolerance, ascending: true)
        case .toleranceDescending:
            return stableOptionalCompare(left, right, left.sssPlusTolerance, right.sssPlusTolerance, ascending: false)
        case .ratingDescending:
            return stableCompare(left, right, primary: (leftScore?.rating ?? -1) > (rightScore?.rating ?? -1))
        case .songIDAscending:
            return stableCompare(left, right, primary: left.song.id < right.song.id)
        case .versionDescending:
            return stableCompare(left, right, primary: left.chart.version > right.chart.version)
        case .versionAscending:
            return stableCompare(left, right, primary: left.chart.version < right.chart.version)
        case .achievementAscending:
            return stableCompare(left, right, primary: (leftScore?.achievement ?? 999) < (rightScore?.achievement ?? 999))
        case .achievementDescending:
            return stableCompare(left, right, primary: (leftScore?.achievement ?? -1) > (rightScore?.achievement ?? -1))
        case .titleAscending:
            return left.song.title.localizedCaseInsensitiveCompare(right.song.title) == .orderedAscending
        case .titleDescending:
            return left.song.title.localizedCaseInsensitiveCompare(right.song.title) == .orderedDescending
        }
    }

    private func stableCompare(_ left: CatalogChartItem, _ right: CatalogChartItem, primary: Bool) -> Bool {
        switch sortMode {
        case .constantDescending where left.chart.levelValue != right.chart.levelValue,
             .constantAscending where left.chart.levelValue != right.chart.levelValue,
             .ratingDescending where model.score(for: left)?.rating != model.score(for: right)?.rating,
             .songIDAscending where left.song.id != right.song.id,
             .versionDescending where left.chart.version != right.chart.version,
             .versionAscending where left.chart.version != right.chart.version,
             .achievementAscending where model.score(for: left)?.achievement != model.score(for: right)?.achievement,
             .achievementDescending where model.score(for: left)?.achievement != model.score(for: right)?.achievement:
            return primary
        default:
            if left.song.id != right.song.id { return left.song.id < right.song.id }
            return left.chart.difficulty < right.chart.difficulty
        }
    }

    private func stableOptionalCompare(
        _ left: CatalogChartItem,
        _ right: CatalogChartItem,
        _ leftValue: Int?,
        _ rightValue: Int?,
        ascending: Bool
    ) -> Bool {
        switch (leftValue, rightValue) {
        case let (.some(l), .some(r)) where l != r: return ascending ? l < r : l > r
        case (.some, .none): return true
        case (.none, .some): return false
        default:
            if left.chart.levelValue != right.chart.levelValue { return left.chart.levelValue > right.chart.levelValue }
            return left.id < right.id
        }
    }
}

struct ChartCard: View {
    let item: CatalogChartItem
    let score: ScoreEntry?
    let onOpen: () -> Void

    var body: some View {
        Button(action: onOpen) {
            FluentCard {
                HStack(alignment: .top, spacing: 12) {
                    JacketArtView(songID: item.song.id, title: item.song.title)
                    .frame(width: 72, height: 72)
                    .clipShape(RoundedRectangle(cornerRadius: 10))

                    VStack(alignment: .leading, spacing: 5) {
                        Text(item.song.title)
                            .font(.headline)
                            .foregroundStyle(.primary)
                            .lineLimit(2)
                        Text(item.song.artist.isEmpty ? item.song.genre : item.song.artist)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                        HStack(spacing: 7) {
                            Text("\(item.chart.typeLabel) \(item.chart.difficultyLabel)")
                            Text(item.chart.level)
                            Text(item.chart.levelValue.formatted(.number.precision(.fractionLength(1))))
                        }
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(difficultyColor)
                    }
                    Spacer(minLength: 4)
                    VStack(alignment: .trailing, spacing: 5) {
                        if let score {
                            Text("\(score.achievement, format: .number.precision(.fractionLength(4)))%")
                                .font(.caption.monospacedDigit().bold())
                            Text("R \(score.rating)")
                                .font(.caption2)
                                .foregroundStyle(FluentPalette.primary)
                        } else {
                            Text("未游玩")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        if let tolerance = item.sssPlusTolerance {
                            Text("容错 \(tolerance)")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .buttonStyle(.plain)
    }

    private var difficultyColor: Color {
        switch item.chart.difficulty {
        case 0: .green
        case 1: .orange
        case 2: .red
        case 3: .purple
        default: .indigo
        }
    }
}

func difficultyName(_ difficulty: Int) -> String {
    let labels = ["BASIC", "ADVANCED", "EXPERT", "MASTER", "Re:MASTER"]
    return labels.indices.contains(difficulty) ? labels[difficulty] : "难度 \(difficulty)"
}
