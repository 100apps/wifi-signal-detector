#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk" ]]; then echo '请设置 ANDROID_HOME 或 ANDROID_SDK_ROOT' >&2; exit 1; fi
jar="$sdk/platforms/android-35/android.jar"
tools="$sdk/build-tools/35.0.1"
if [[ ! -f "$jar" || ! -d "$tools" ]]; then echo '缺少 Android SDK Platform 35 或 Build Tools 35.0.1' >&2; exit 1; fi

mkdir -p build/classes build/dex .signing
mapfile -d '' sources < <(find src -name '*.java' -print0)
javac --release 8 -classpath "$jar" -d build/classes "${sources[@]}"
"$tools/aapt" package -f -M AndroidManifest.xml -S res -A assets -I "$jar" -F build/unsigned.apk
mapfile -d '' classes < <(find build/classes -name '*.class' -print0)
"$tools/d8" --min-api 26 --output build/dex "${classes[@]}"
zip -q -j build/unsigned.apk build/dex/classes.dex
"$tools/zipalign" -f 4 build/unsigned.apk build/aligned.apk

if [[ -n "${SIGNING_KEYSTORE:-}" ]]; then
  keystore="$SIGNING_KEYSTORE"
  if [[ -z "${ANDROID_KEYSTORE_PASSWORD:-}" ]]; then echo '缺少 ANDROID_KEYSTORE_PASSWORD' >&2; exit 1; fi
else
  keystore='.signing/debug.jks'
  export ANDROID_KEYSTORE_PASSWORD=android
  if [[ ! -f "$keystore" ]]; then
    keytool -genkeypair -keystore "$keystore" -storepass android -keypass android -alias wifimap -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=WiFi Signal Detector,O=Local Build' -noprompt
  fi
fi

"$tools/apksigner" sign --ks "$keystore" --ks-key-alias wifimap --ks-pass env:ANDROID_KEYSTORE_PASSWORD --key-pass env:ANDROID_KEYSTORE_PASSWORD --out wifi-signal-detector.apk build/aligned.apk
"$tools/apksigner" verify --verbose wifi-signal-detector.apk
echo '已生成 wifi-signal-detector.apk'
