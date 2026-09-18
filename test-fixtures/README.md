# Cross-platform golden fixtures

These UTF-8 TSV fixtures are intentionally independent of Android Room and Windows SQLite.
Kotlin and Python tests read the same files so Rating, B50, search, plate, score-loss,
recommendation, and version semantics cannot drift behind platform-specific UI formatting.
