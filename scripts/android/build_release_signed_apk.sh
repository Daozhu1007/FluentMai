#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# FluentMai -- build a release-signed Android APK and verify its identity.
#
# This script intentionally contains NO keystore, NO password and NO machine
# specific paths. Signing credentials are supplied through an external
# properties file (see docs/WINDOWS_ANDROID_SIGNING.md).
#
# Usage:
#   scripts/android/build_release_signed_apk.sh [variant]
#     variant defaults to "release"
#
# Environment (all optional):
#   FLUENTMAI_SIGNING_PROPERTIES  path to keystore.properties (outside the repo)
#   ANDROID_SDK_ROOT              Android SDK root
#
# Exit codes: 0 = signed & verified, non-zero = aborted (never emits a
# mis-signed artifact silently).
# -----------------------------------------------------------------------------
set -euo pipefail

VARIANT="${1:-release}"
GRADLE_TASK=":app:assemble${VARIANT^}"

EXPECTED_CERT_SHA256="A6:F2:59:48:06:3D:A5:23:CD:42:2D:A4:01:D6:F0:19:79:92:6E:DC:AA:55:62:C8:D3:0E:BF:3D:D0:B8:98:0E"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

if [[ -z "${FLUENTMAI_SIGNING_PROPERTIES:-}" ]]; then
  DEFAULT_PROPS="${REPO_ROOT}/../fluentmai-signing-local/keystore.properties"
  if [[ -f "$DEFAULT_PROPS" ]]; then
    export FLUENTMAI_SIGNING_PROPERTIES="$DEFAULT_PROPS"
  fi
fi

if [[ -z "${FLUENTMAI_SIGNING_PROPERTIES:-}" || ! -f "${FLUENTMAI_SIGNING_PROPERTIES:-}" ]]; then
  echo "ERROR: signing properties not found." >&2
  echo "       Set FLUENTMAI_SIGNING_PROPERTIES to a keystore.properties" >&2
  echo "       file located OUTSIDE this repository." >&2
  exit 2
fi
echo "Using signing properties: $FLUENTMAI_SIGNING_PROPERTIES"

echo "==> Building $VARIANT (fingerprint guard is enforced by Gradle)"
./gradlew "$GRADLE_TASK" -PfluentmaiSigningProps="$FLUENTMAI_SIGNING_PROPERTIES"

APK="$(find "app/build/outputs/apk/${VARIANT}" -name '*.apk' -type f | head -n 1)"
if [[ -z "$APK" || ! -f "$APK" ]]; then
  echo "ERROR: no APK produced for $VARIANT." >&2
  exit 3
fi
echo "==> APK: $APK"

# --- locate apksigner -------------------------------------------------------
if [[ -z "${ANDROID_SDK_ROOT:-}" ]]; then
  for c in "$HOME/AppData/Local/Android/Sdk" "${LOCALAPPDATA:-}/Android/Sdk" "${ANDROID_HOME:-}"; do
    [[ -n "$c" && -d "$c" ]] && ANDROID_SDK_ROOT="$c" && break
  done
fi
APKSIGNER=""
if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
  APKSIGNER="$(find "$ANDROID_SDK_ROOT/build-tools" -maxdepth 2 \( -name 'apksigner.bat' -o -name 'apksigner' \) 2>/dev/null | sort -r | head -n 1 || true)"
fi
if [[ -z "$APKSIGNER" ]]; then
  APKSIGNER="$(command -v apksigner || true)"
fi
[[ -z "$APKSIGNER" ]] && { echo "ERROR: apksigner not found." >&2; exit 4; }

echo "==> Verifying signature"
"$APKSIGNER" verify --verbose --print-certs "$APK"

ACTUAL="$(  "$APKSIGNER" verify --print-certs "$APK" \
          | grep -i 'certificate SHA-256 digest' \
          | head -n 1 \
          | sed 's/.*digest: *//' \
          | tr 'a-f' 'A-F' )"

NORM_EXPECTED="$(echo "$EXPECTED_CERT_SHA256" | tr -d ':')"
NORM_ACTUAL="$(echo "$ACTUAL" | tr -d ':')"

if [[ "$NORM_EXPECTED" != "$NORM_ACTUAL" ]]; then
  echo "ERROR: certificate fingerprint mismatch!" >&2
  echo "  expected: $EXPECTED_CERT_SHA256" >&2
  echo "  actual  : $ACTUAL" >&2
  exit 5
fi

echo
echo "FLUENTMAI_RELEASE_APK_VERIFIED"
echo "  apk                     : $APK"
echo "  signer certificate SHA-256: $EXPECTED_CERT_SHA256"
