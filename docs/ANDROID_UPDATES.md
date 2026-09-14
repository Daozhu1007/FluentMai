# Android automatic updates

The setting defaults to enabled. Each activity session checks once in the background; configuration changes reuse the ViewModel and do not repeat checks. Disabling it prevents startup checks. Network failures do not block the UI or show an unsolicited error.

Release discovery reads the repository's Releases list, not the latest-release endpoint (which omits prereleases). Drafts and releases without an Android APK are ignored. Versions are compared numerically, including beta/rc revisions. The official GitHub API and a public HTTPS proxy are queried with bounded timeouts; the newest valid candidate is retained.

Downloads require explicit user confirmation. GitHub is tried first, then `https://gh-proxy.org/` as a public fallback. Both the Releases API and a published APK were reachable through the fallback during verification; this is not a guarantee for every mainland ISP or future service availability. No player data, cookies or upload tokens are sent to GitHub or the proxy.

Only APK asset links from `Daozhu1007/FluentMai` are accepted. Downloads are size-bounded (200 MiB), require HTTPS, and are checked against the advertised size and GitHub SHA-256 digest when available. Android then checks the archive package name, higher versionCode, expected versionName and exact signing-certificate set against the installed app. A tampered proxy response cannot authorize an APK signed with another key. Missing/invalid archives and partial downloads are removed.

Installation uses a non-exported FileProvider restricted to the app-private updates cache. The system's unknown-app-source permission and package installer require user action; no silent installation is attempted. The repository does not contain signing keys. Existing compatible debug-signed Beta installations can be overwritten without uninstalling.

The first release containing this updater is v0.2.6 Beta; older releases without updater code cannot acquire it automatically.
