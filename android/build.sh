#!/usr/bin/env bash
# Builds NotiForward.apk with plain Android SDK tools (no Gradle, no Android Studio).
# Needs: JDK, aapt, dalvik-exchange (dx), zipalign, apksigner, android.jar (API 23).
# Ubuntu/Debian: apt install android-sdk-platform-23 aapt dalvik-exchange zipalign apksigner
#
# Signing: uses $KEYSTORE (default: release.keystore next to this script).
# Outside CI a missing keystore is generated; in CI it must be provided, because
# Android only installs updates signed with the same key as the installed app.
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_JAR=${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
KEYSTORE=${KEYSTORE:-release.keystore}
KEYSTORE_PASSWORD=${KEYSTORE_PASSWORD:-notiforward}
KEY_ALIAS=${KEY_ALIAS:-notiforward}

if [ ! -f "$KEYSTORE" ]; then
  if [ "${CI:-}" = "true" ]; then
    echo "error: keystore $KEYSTORE not found (set the KEYSTORE_BASE64 repository secret)" >&2
    exit 1
  fi
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASSWORD" \
    -alias "$KEY_ALIAS" -keypass "$KEYSTORE_PASSWORD" -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=NotiForward" >/dev/null
  echo "Generated new signing key $KEYSTORE (keep it: updates need the same key)"
fi

rm -rf build && mkdir -p build/gen build/classes
aapt package -f -m -J build/gen -M AndroidManifest.xml -S res -I "$ANDROID_JAR" -F build/unsigned.apk
javac -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -d build/classes \
  $(find src build/gen -name '*.java')
dalvik-exchange --dex --output=build/classes.dex build/classes
(cd build && aapt add unsigned.apk classes.dex >/dev/null)
zipalign -f 4 build/unsigned.apk build/aligned.apk
apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KEYSTORE_PASSWORD" --ks-key-alias "$KEY_ALIAS" \
  --key-pass "pass:$KEYSTORE_PASSWORD" --out build/NotiForward.apk build/aligned.apk
apksigner verify build/NotiForward.apk
echo "Built $(pwd)/build/NotiForward.apk"
