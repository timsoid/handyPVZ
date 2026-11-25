#!/usr/bin/env bash
# Builds signed release APKs and publishes a GitHub Release with BOTH assets:
#   handyPVZ-<version>.apk        phone app (embeds the watch APK for the built-in installer)
#   handyPVZ-wear-<version>.apk   watch app, standalone, for people who cannot use the built-in installer
# Requires: keystore.properties (git-ignored), gh authenticated as the repo owner.
set -euo pipefail
cd "$(dirname "$0")/.."

[ -f keystore.properties ] || { echo "keystore.properties not found: refusing to publish unsigned APKs" >&2; exit 1; }
[ -n "${JAVA_HOME:-}" ] || export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

VERSION=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -1)
WEAR_VERSION=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' wear/build.gradle.kts | head -1)
[ "$VERSION" = "$WEAR_VERSION" ] || { echo "versionName differs: app=$VERSION wear=$WEAR_VERSION" >&2; exit 1; }
TAG="v$VERSION"

./gradlew clean :app:assembleRelease

OUT=build/release
rm -rf "$OUT" && mkdir -p "$OUT"
cp app/build/outputs/apk/release/app-release.apk "$OUT/handyPVZ-$VERSION.apk"
cp wear/build/outputs/apk/release/wear-release.apk "$OUT/handyPVZ-wear-$VERSION.apk"

# Both APKs must be signed with the same key, and the one embedded in the phone APK must match
BT=$(ls "$ANDROID_HOME/build-tools" 2>/dev/null || ls "$HOME/Library/Android/sdk/build-tools")
APKSIGNER="${ANDROID_HOME:-$HOME/Library/Android/sdk}/build-tools/$(echo "$BT" | sort -V | tail -1)/apksigner"
cert() { "$APKSIGNER" verify --print-certs "$1" | sed -n 's/.*SHA-256 digest: //p' | head -1; }
unzip -p "$OUT/handyPVZ-$VERSION.apk" assets/wear_app.apk > "$OUT/embedded-wear.apk"
[ "$(cert "$OUT/handyPVZ-$VERSION.apk")" = "$(cert "$OUT/handyPVZ-wear-$VERSION.apk")" ] || { echo "phone/wear signing keys differ" >&2; exit 1; }
[ "$(cert "$OUT/embedded-wear.apk")" = "$(cert "$OUT/handyPVZ-wear-$VERSION.apk")" ] || { echo "embedded wear APK is not signed with the release key" >&2; exit 1; }
rm "$OUT/embedded-wear.apk"

(cd "$OUT" && shasum -a 256 handyPVZ-*.apk > SHA256SUMS.txt)
cat "$OUT/SHA256SUMS.txt"

NOTES="${NOTES_FILE:-}"
if [ -z "$NOTES" ]; then NOTES=$(mktemp); echo "handyPVZ $VERSION" > "$NOTES"; fi
gh release create "$TAG" "$OUT"/handyPVZ-"$VERSION".apk "$OUT"/handyPVZ-wear-"$VERSION".apk "$OUT/SHA256SUMS.txt" \
  --title "handyPVZ $VERSION" --notes-file "$NOTES" --target main
