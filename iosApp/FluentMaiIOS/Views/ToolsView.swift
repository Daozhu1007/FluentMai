import Charts
import FluentMaiShared
import SwiftUI

private enum ToolSection: String, CaseIterable, Identifiable {
    case rating = "Rating"
    case achievement = "失分 / 容错"
    case versions = "版本资料"
    case kaleid = "Kaleid×Scope"
    case trend = "趋势"

    var id: String { rawValue }
}

private struct AchievementPreview {
    let maximum: Double
    let loss: Double
    let result: Double
    let tolerated: Int
}

struct ToolsView: View {
    @EnvironmentObject private var model: AppModel
    let scrollToTopRequestID: Int
    let onOpenSettings: () -> Void

    @State private var section: ToolSection = .rating
    @State private var levelValue = 14.0
    @State private var achievement = 100.5
    @State private var tap = 500
    @State private var hold = 50
    @State private var slide = 80
    @State private var touch = 40
    @State private var breakCount = 20
    @State private var noteKind = "TAP"
    @State private var judgement = "GREAT"
    @State private var occurrences = 1
    @State private var targetAchievement = 100.5
    @State private var preview: AchievementPreview?

    private let domain = IosDomainBridge()
    private let noteKinds = ["TAP", "HOLD", "SLIDE", "TOUCH", "BREAK"]
    private let judgements = ["CRITICAL_PERFECT", "PERFECT_HIGH", "PERFECT", "GREAT", "GOOD", "MISS"]

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Color.clear.frame(height: 1).id("tools-top")
                    header
                    sectionPicker
                    switch section {
                    case .rating: ratingCalculator
                    case .achievement: achievementCalculator
                    case .versions: versionReference
                    case .kaleid: kaleidStatus
                    case .trend: ratingTrend
                    }
                }
                .frame(maxWidth: 1_000)
                .padding(20)
                .frame(maxWidth: .infinity)
            }
            .background(FluentPalette.background)
            .navigationBarHidden(true)
            .onChange(of: scrollToTopRequestID) { _, request in
                guard request > 0 else { return }
                withAnimation(.easeInOut(duration: 0.28)) { proxy.scrollTo("tools-top", anchor: .top) }
            }
        }
    }

    private var header: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text("工具箱").font(.title2.bold())
                Text("公式、版本资料与本地 Rating 时间轴")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Button(action: onOpenSettings) {
                Image(systemName: "gearshape.fill").font(.title3)
            }
            .buttonStyle(.bordered)
            .accessibilityLabel("应用设置")
        }
    }

    private var sectionPicker: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 108), spacing: 8)], alignment: .leading, spacing: 8) {
            ForEach(ToolSection.allCases) { value in
                if value == section {
                    Button(value.rawValue) { section = value }
                        .buttonStyle(.borderedProminent)
                } else {
                    Button(value.rawValue) { section = value }
                        .buttonStyle(.bordered)
                }
            }
        }
    }

    private var ratingCalculator: some View {
        toolCard("单曲 Rating 计算", subtitle: "公式输入只有谱面定数与达成率；达成率按 100.5% 封顶参与 Rating。") {
            HStack {
                numberField("谱面定数", value: $levelValue, digits: 1)
                numberField("达成率 %", value: $achievement, digits: 4)
            }
            let rating = model.previewRating(levelValue: levelValue, achievement: achievement)
            VStack(alignment: .leading, spacing: 6) {
                Text("单曲 Rating \(rating)").font(.title2.bold())
                Text("系数 \(domain.calculateCoefficient(achievement: achievement).formatted(.number.precision(.fractionLength(1))))")
                Text("floor(\(levelValue.formatted(.number.precision(.fractionLength(1)))) × min(\(achievement.formatted(.number.precision(.fractionLength(4)))), 100.5000)% ÷ 100 × 系数)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(12)
            .background(FluentPalette.primary.opacity(0.09), in: RoundedRectangle(cornerRadius: 10))
            Text("Rating 阶段系数与边界由共享 core:model 回归测试锁定；结果向下取整。")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private var achievementCalculator: some View {
        toolCard("谱面失分与达成率", subtitle: "计算指定判定的单次损失，以及目标达成率最多容许的同类判定数量。") {
            VStack(spacing: 8) {
                Stepper("Tap：\(tap)", value: $tap, in: 0...3_000)
                Stepper("Hold：\(hold)", value: $hold, in: 0...1_000)
                Stepper("Slide：\(slide)", value: $slide, in: 0...1_000)
                Stepper("Touch：\(touch)", value: $touch, in: 0...1_000)
                Stepper("Break：\(breakCount)", value: $breakCount, in: 0...1_000)
            }
            Picker("判定对象", selection: $noteKind) {
                ForEach(noteKinds, id: \.self) { Text($0).tag($0) }
            }
            Picker("判定", selection: $judgement) {
                ForEach(judgements, id: \.self) { Text($0.replacingOccurrences(of: "_", with: " ")).tag($0) }
            }
            Stepper("出现次数：\(occurrences)", value: $occurrences, in: 0...max(selectedNoteCount, 0))
            numberField("目标达成率 %", value: $targetAchievement, digits: 4)
            Button("计算失分") { calculateAchievement() }
                .buttonStyle(.borderedProminent)
                .disabled(!achievementInputIsValid)
            if let preview {
                VStack(alignment: .leading, spacing: 7) {
                    Text("结果").font(.headline)
                    LabeledContent("全 Critical Perfect 理论值", value: preview.maximum.formatted(.number.precision(.fractionLength(4))) + "%")
                    LabeledContent("单个该判定失分", value: preview.loss.formatted(.number.precision(.fractionLength(6))) + "%")
                    LabeledContent("当前结果", value: preview.result.formatted(.number.precision(.fractionLength(4))) + "%")
                    LabeledContent("目标可容忍次数", value: String(preview.tolerated))
                }
                .padding(12)
                .background(FluentPalette.primary.opacity(0.09), in: RoundedRectangle(cornerRadius: 10))
            }
        }
    }

    private var versionReference: some View {
        toolCard("版本名称与牌子对照", subtitle: "与 Android 使用相同的主版本编号口径。") {
            ForEach(versionRows, id: \.0) { row in
                LabeledContent(row.0, value: row.1)
                if row.0 != versionRows.last?.0 { Divider() }
            }
        }
    }

    private var kaleidStatus: some View {
        toolCard("Kaleid×Scope", subtitle: "门曲与解锁条件只接受可审查、可更新的数据源。") {
            Label("数据源待接入", systemImage: "hourglass")
                .font(.headline)
            Text("当前 iOS 包没有捏造门曲或解锁条件；接入经审核目录后再显示。")
                .foregroundStyle(.secondary)
        }
    }

    private var ratingTrend: some View {
        toolCard("Rating Trend", subtitle: "只绘制真实本地记录，不根据现有分数反推过去时间点。") {
            if model.userData.ratingHistory.isEmpty {
                ContentUnavailableView("还没有 Rating 记录", systemImage: "chart.xyaxis.line")
                    .frame(minHeight: 180)
            } else {
                Chart(model.userData.ratingHistory) { point in
                    LineMark(x: .value("时间", point.recordedAt), y: .value("Rating", point.rating))
                        .foregroundStyle(FluentPalette.primary)
                    AreaMark(x: .value("时间", point.recordedAt), y: .value("Rating", point.rating))
                        .foregroundStyle(FluentPalette.primary.opacity(0.12))
                }
                .frame(height: 240)
            }
        }
    }

    private func toolCard<Content: View>(
        _ title: String,
        subtitle: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        FluentCard {
            VStack(alignment: .leading, spacing: 13) {
                Text(title).font(.headline)
                Text(subtitle).font(.subheadline).foregroundStyle(.secondary)
                content()
            }
        }
    }

    private func numberField(_ title: String, value: Binding<Double>, digits: Int) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            TextField(title, value: value, format: .number.precision(.fractionLength(digits)))
                .keyboardType(.decimalPad)
                .textFieldStyle(.roundedBorder)
        }
    }

    private var selectedNoteCount: Int {
        switch noteKind {
        case "TAP": tap
        case "HOLD": hold
        case "SLIDE": slide
        case "TOUCH": touch
        default: breakCount
        }
    }

    private var achievementInputIsValid: Bool {
        tap + hold + slide + touch + breakCount > 0
            && occurrences >= 0 && occurrences <= selectedNoteCount
            && targetAchievement >= 0 && targetAchievement <= (breakCount > 0 ? 101 : 100)
    }

    private func calculateAchievement() {
        let result = domain.calculateAchievement(
            tap: Int32(tap), hold: Int32(hold), slide: Int32(slide), touch: Int32(touch),
            breakCount: Int32(breakCount), noteKind: noteKind, judgement: judgement,
            occurrences: Int32(occurrences), targetAchievement: targetAchievement
        )
        preview = AchievementPreview(
            maximum: result.maximumAchievement,
            loss: result.lossPerJudgement,
            result: result.resultingAchievement,
            tolerated: Int(result.toleratedOccurrences)
        )
    }

    private var versionRows: [(String, String)] {
        [
            ("舞萌DX 2026", "25500 · 当前"), ("舞萌DX 2025", "25000"),
            ("舞萌DX 2024", "24000"), ("舞萌DX 2023", "23000"),
            ("舞萌DX 2022", "22000"), ("舞萌DX 2021", "21000"),
            ("舞萌DX", "20000"), ("经典世代", "10000–19900")
        ]
    }
}
