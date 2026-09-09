import SwiftUI

enum AppSection: String, CaseIterable, Identifiable, Hashable {
    case home
    case importData
    case charts
    case tools

    var id: String { rawValue }

    var title: String {
        switch self {
        case .home: "首页"
        case .importData: "导入"
        case .charts: "谱面"
        case .tools: "工具"
        }
    }

    var systemImage: String {
        switch self {
        case .home: "house.fill"
        case .importData: "play.fill"
        case .charts: "magnifyingglass"
        case .tools: "wrench.and.screwdriver.fill"
        }
    }
}

struct ResponsiveRootView: View {
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var selectedSection = AppSection.home
    @State private var scrollRequests: [AppSection: Int] = [:]

    var body: some View {
        Group {
            if horizontalSizeClass == .regular {
                HStack(spacing: 0) {
                    navigationRail
                    Divider()
                    tabLayers
                }
            } else {
                VStack(spacing: 0) {
                    tabLayers
                    Divider()
                    bottomBar
                }
            }
        }
        .background(FluentPalette.background.ignoresSafeArea())
    }

    private var tabLayers: some View {
        ZStack {
            ForEach(AppSection.allCases) { section in
                SectionNavigationHost(
                    section: section,
                    scrollToTopRequestID: scrollRequests[section, default: 0],
                    onSelectTab: select
                )
                .opacity(section == selectedSection ? 1 : 0)
                .allowsHitTesting(section == selectedSection)
                .accessibilityHidden(section != selectedSection)
            }
        }
    }

    private var bottomBar: some View {
        HStack(spacing: 0) {
            ForEach(AppSection.allCases) { section in
                Button {
                    select(section)
                } label: {
                    VStack(spacing: 4) {
                        Image(systemName: section.systemImage)
                            .font(.system(size: 19, weight: .semibold))
                        Text(section.title)
                            .font(.caption2.weight(.semibold))
                    }
                    .foregroundStyle(section == selectedSection ? FluentPalette.primary : .secondary)
                    .frame(maxWidth: .infinity, minHeight: 52)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(section == selectedSection ? .isSelected : [])
            }
        }
        .padding(.horizontal, 4)
        .padding(.bottom, 2)
        .background(.bar)
    }

    private var navigationRail: some View {
        VStack(spacing: 10) {
            Text("FluentMai")
                .font(.headline)
                .padding(.vertical, 12)
            ForEach(AppSection.allCases) { section in
                Button {
                    select(section)
                } label: {
                    VStack(spacing: 6) {
                        Image(systemName: section.systemImage)
                            .font(.title3)
                        Text(section.title)
                            .font(.caption)
                    }
                    .foregroundStyle(section == selectedSection ? FluentPalette.primary : .secondary)
                    .frame(width: 86, height: 64)
                    .background(
                        section == selectedSection ? FluentPalette.primary.opacity(0.12) : .clear,
                        in: RoundedRectangle(cornerRadius: 18, style: .continuous)
                    )
                }
                .buttonStyle(.plain)
            }
            Spacer()
        }
        .padding(.horizontal, 8)
        .background(.bar)
    }

    private func select(_ section: AppSection) {
        if selectedSection == section {
            scrollRequests[section, default: 0] += 1
        } else {
            selectedSection = section
        }
    }
}

private struct SectionNavigationHost: View {
    let section: AppSection
    let scrollToTopRequestID: Int
    let onSelectTab: (AppSection) -> Void

    var body: some View {
        switch section {
        case .home:
            HomeNavigationHost(scrollToTopRequestID: scrollToTopRequestID, onSelectTab: onSelectTab)
        case .importData:
            NavigationStack {
                ImportView(scrollToTopRequestID: scrollToTopRequestID)
            }
        case .charts:
            ChartsNavigationHost(scrollToTopRequestID: scrollToTopRequestID)
        case .tools:
            ToolsNavigationHost(scrollToTopRequestID: scrollToTopRequestID)
        }
    }
}

private enum HomeRoute: Hashable {
    case played
    case progress(PlayerProgressDestination)
    case chart(CatalogChartItem)
}

private struct HomeNavigationHost: View {
    let scrollToTopRequestID: Int
    let onSelectTab: (AppSection) -> Void
    @State private var path: [HomeRoute] = []

    var body: some View {
        NavigationStack(path: $path) {
            ScoresView(
                scrollToTopRequestID: scrollToTopRequestID,
                onOpenPlayed: { path.append(.played) },
                onOpenProgress: { path.append(.progress($0)) },
                onOpenChart: { path.append(.chart($0)) }
            )
            .navigationDestination(for: HomeRoute.self) { route in
                switch route {
                case .played:
                    PlayedChartsView(
                        scrollToTopRequestID: scrollToTopRequestID,
                        onOpenChart: { path.append(.chart($0)) }
                    )
                case .progress(let destination):
                    PlayerProgressView(
                        initialDestination: destination,
                        scrollToTopRequestID: scrollToTopRequestID,
                        onOpenChart: { path.append(.chart($0)) }
                    )
                case .chart(let item):
                    SongDetailView(item: item, scrollToTopRequestID: scrollToTopRequestID)
                }
            }
        }
    }
}

private enum ChartsRoute: Hashable {
    case detail(CatalogChartItem)
}

private struct ChartsNavigationHost: View {
    let scrollToTopRequestID: Int
    @State private var path: [ChartsRoute] = []

    var body: some View {
        NavigationStack(path: $path) {
            CatalogView(
                scrollToTopRequestID: scrollToTopRequestID,
                onOpenChart: { path.append(.detail($0)) }
            )
            .navigationDestination(for: ChartsRoute.self) { route in
                switch route {
                case .detail(let item):
                    SongDetailView(item: item, scrollToTopRequestID: scrollToTopRequestID)
                }
            }
        }
    }
}

private enum ToolsRoute: Hashable {
    case settings
}

private struct ToolsNavigationHost: View {
    let scrollToTopRequestID: Int
    @State private var path: [ToolsRoute] = []

    var body: some View {
        NavigationStack(path: $path) {
            ToolsView(
                scrollToTopRequestID: scrollToTopRequestID,
                onOpenSettings: { path.append(.settings) }
            )
            .navigationDestination(for: ToolsRoute.self) { route in
                switch route {
                case .settings:
                    SettingsView(scrollToTopRequestID: scrollToTopRequestID)
                }
            }
        }
    }
}
