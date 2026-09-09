import SwiftUI

@main
struct FluentMaiIOSApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            ResponsiveRootView()
                .environmentObject(model)
                .tint(FluentPalette.primary)
        }
    }
}
