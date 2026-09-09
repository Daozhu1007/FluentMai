# Privacy Model

The local Room database is the app's source of truth for imported score data.

The app must not persist or log:

- Cookie values
- Raw HTML
- Full authentication URLs
- Wahlap login and Cookie-import input values

Wahlap auth URLs and Cookie import values remain only in current UI/app state. Diving Fish and LXNS upload tokens are persisted only after the user enters them: their values are encrypted with AES-GCM using a non-exportable key held by Android Keystore, and only ciphertext is stored in app-private preferences. They are never written to Room, included in Android backup (`allowBackup=false`), or logged. Clearing a token field removes its stored value; clearing app data or uninstalling removes the stored tokens.

Logs and user-visible diagnostic text must be redacted before display or printing. Redaction covers credential fields, authentication URLs, HTML blocks/tags, input values, and token-like API response text.

Upload responses from Diving Fish and LXNS are treated as untrusted diagnostic text and are sanitized before being shown in the app.
