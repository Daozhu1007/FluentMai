import Foundation

struct ScoreEntry: Codable, Identifiable, Hashable {
    let id: String
    let songId: Int
    let title: String
    let chartType: String
    let difficulty: Int
    let difficultyLabel: String
    let level: String
    let levelValue: Double
    let chartVersion: Int
    let achievement: Double
    let rating: Int
    let playedAt: Date
    var fullCombo: String? = nil
    var fullSync: String? = nil
    var dxScore: Int? = nil
}

struct RatingHistoryPoint: Codable, Identifiable, Hashable {
    let id: UUID
    let recordedAt: Date
    let rating: Int
}

struct UserData: Codable {
    var schemaVersion: Int = 1
    var currentVersionId: Int = 25_500
    var scores: [ScoreEntry] = []
    var aliases: [String: [String]] = [:]
    var ratingHistory: [RatingHistoryPoint] = []

    static let empty = UserData()
}

struct RatingSummary {
    let newBest: [ScoreEntry]
    let oldBest: [ScoreEntry]
    let totalRating: Int
    let ineligibleCount: Int
    let outsideBestCount: Int
}
