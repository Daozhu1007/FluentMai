# Windows Android Release Signing

FluentMai's Android releases must be signed with the **original FluentMai signing key**
so that builds stay upgrade-compatible with the existing Beta APKs.

That key is **never stored in this repository**. This document describes how to
configure Windows to build release-signed APKs without ever putting a keystore,
a password, or a machine-specific secret into Git, a script, a build log, or a
public artifact.

---

## Signing identity (public values)

| Field | Value |
| --- | --- |
| Key alias | `androiddebugkey` |
| Entry type | `PrivateKeyEntry` (a private key is required) |
| Keystore type | `PKCS12` |
| Certificate SHA-256 | `A6:F2:59:48:06:3D:A5:23:CD:42:2D:A4:01:D6:F0:19:79:92:6E:DC:AA:55:62:C8:D3:0E:BF:3D:D0:B8:98:0E` |
| Application ID | `dev.fluentmai.android` |

The certificate fingerprint is a public constant. It is pinned in
`app/build.gradle.kts` (`expectedCertSha256`) and re-checked on every release
build and by the helper script. **A mismatch aborts the build.**

---

## Where the secrets live (outside the repo)

Two locations, both outside `D:\Code\FluentMai`:

1. **Keystore** — private directory, ACL-restricted to the owner account:
   `%USERPROFILE%\.fluentmai-signing-private\fluentmai-original-signing.keystore`

2. **Credentials file** — a `keystore.properties` next to the repo:
   `D:\Code\fluentmai-signing-local\keystore.properties`

   ```properties
   storeFile=C:/Users/<you>/.fluentmai-signing-private/fluentmai-original-signing.keystore
   storePassword=<keystore password>
   keyAlias=androiddebugkey
   keyPassword=<key password>
   storeType=PKCS12
   ```

`keystore.properties`, `*.keystore` and friends are listed in `.gitignore`.
Do not remove those entries.

The default Windows `debug.keystore` (`%USERPROFILE%\.android\debug.keystore`)
is **not** touched. Debug builds keep using it; only `release` uses the handed-over key.

---

## Building a release-signed APK

```bash
# from the repository root, with signing available
./gradlew :app:assembleRelease

# or, with automatic identity verification afterwards:
bash scripts/android/build_release_signed_apk.sh
```

If `keystore.properties` cannot be found, `:app:assembleRelease` fails on purpose
rather than emitting an unsigned or wrongly-signed artifact. Normal debug builds
(`:app:assembleDebug`) are unaffected.

Resolution order:

1. `-PfluentmaiSigningProps=<path>` (explicit)
2. `FLUENTMAI_SIGNING_PROPERTIES` environment variable
3. `<repo>/../fluentmai-signing-local/keystore.properties` (default local drop)
4. `<repo>/keystore.properties` (legacy, git-ignored)

An **explicit** path (1 or 2) is authoritative: if it names a file that does not
exist, the build fails with a clear error instead of silently falling back to a
different keystore. This prevents signing a release with an unintended identity.

To point at a different credentials file:

```bash
./gradlew :app:assembleRelease -PfluentmaiSigningProps=/path/to/keystore.properties
# or
FLUENTMAI_SIGNING_PROPERTIES=/path/to/keystore.properties bash scripts/android/build_release_signed_apk.sh
```

---

## Verifying the result

```bash
"$ANDROID_SDK_ROOT/build-tools/<ver>/apksigner" verify --print-certs app/build/outputs/apk/release/app-release.apk
```

The reported `certificate SHA-256 digest` must equal the fingerprint above.

---

## What is intentionally committed, and what is not

Committed: this document, the Gradle signing configuration (no secrets),
`scripts/android/build_release_signed_apk.sh` (no secrets).

Never committed: the keystore, its password, `keystore.properties`, any
unencrypted backup, or this machine's private paths.
