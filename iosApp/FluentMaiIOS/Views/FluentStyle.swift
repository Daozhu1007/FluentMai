import SwiftUI

enum FluentPalette {
    static let primary = Color(red: 36 / 255, green: 107 / 255, blue: 90 / 255)
    static let secondary = Color(red: 115 / 255, green: 92 / 255, blue: 15 / 255)
    static let tertiary = Color(red: 122 / 255, green: 64 / 255, blue: 90 / 255)
    static let background = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 16 / 255, green: 20 / 255, blue: 24 / 255, alpha: 1)
            : UIColor(red: 251 / 255, green: 252 / 255, blue: 248 / 255, alpha: 1)
    })
    static let surface = Color(uiColor: .secondarySystemBackground)
}

struct FluentCard<Content: View>: View {
    private let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        content
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(Color(uiColor: .secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(Color(uiColor: .separator).opacity(0.35), lineWidth: 1)
            }
    }
}

struct MetricPill: View {
    let label: String
    let value: String

    var body: some View {
        HStack(spacing: 5) {
            Text(label)
                .foregroundStyle(.secondary)
            Text(value)
                .fontWeight(.semibold)
        }
        .font(.caption)
        .padding(.horizontal, 10)
        .padding(.vertical, 7)
        .background(FluentPalette.primary.opacity(0.09), in: Capsule())
    }
}

struct JacketArtView: View {
    let songID: Int
    let title: String

    var body: some View {
        AsyncImage(url: URL(string: "https://assets2.lxns.net/maimai/jacket/\(songID).png")) { phase in
            switch phase {
            case .success(let image):
                image.resizable().scaledToFill()
            case .failure:
                placeholder
            case .empty:
                ZStack {
                    placeholder
                    ProgressView().controlSize(.small)
                }
            @unknown default:
                placeholder
            }
        }
        .accessibilityLabel("\(title) 曲绘")
        .clipped()
    }

    private var placeholder: some View {
        ZStack {
            Color(uiColor: .tertiarySystemFill)
            Image(systemName: "music.note")
                .font(.title)
                .foregroundStyle(FluentPalette.primary)
        }
    }
}
