#!/bin/bash
# Axon Detector — manual build (no Gradle).
# Toolchain: JDK 17 + Android SDK platform android-34 / build-tools 34.0.0
# expected under ~/workspace/tools/ (override with JDK_HOME / ANDROID_SDK).
# Packages the bundled web UI (source/assets/www) into a WebView shell with
# a native BLE scanner bridge (source/java).
set -euo pipefail

SRC="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$SRC")"
BUILD="$ROOT/build"
OUT="$ROOT/AxonDetector.apk"

export JAVA_HOME="${JDK_HOME:-$HOME/workspace/tools/jdk17}"
SDK="${ANDROID_SDK:-$HOME/workspace/tools/android-sdk}"
BT="$SDK/build-tools/34.0.0"
AJAR="$SDK/platforms/android-34/android.jar"
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "$BUILD/classes" "$BUILD/dex" "$SRC/gen" "$BUILD/tmp"
# /tmp may be a tiny tmpfs — keep Java temp files on the real disk
export TMPDIR="$BUILD/tmp"
export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$BUILD/tmp"

echo "== aapt2 compile =="
"$BT/aapt2" compile --dir "$SRC/res" -o "$BUILD/res.zip"

echo "== aapt2 link (with assets) =="
"$BT/aapt2" link -o "$BUILD/unsigned.apk" -I "$AJAR" \
    --manifest "$SRC/AndroidManifest.xml" --java "$SRC/gen" \
    -A "$SRC/assets" "$BUILD/res.zip"

echo "== javac =="
find "$SRC/java" "$SRC/gen" -name '*.java' > "$BUILD/sources.txt"
javac -encoding UTF-8 -cp "$AJAR" -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "== d8 =="
jar cf "$BUILD/classes.jar" -C "$BUILD/classes" .
"$BT/d8" --lib "$AJAR" --min-api 26 --output "$BUILD/dex" "$BUILD/classes.jar"

echo "== package dex =="
cp "$BUILD/unsigned.apk" "$BUILD/pack.apk"
(cd "$BUILD/dex" && zip -q -X "../pack.apk" classes.dex)

echo "== zipalign =="
"$BT/zipalign" -f -p 4 "$BUILD/pack.apk" "$BUILD/aligned.apk"

echo "== keystore + sign =="
# This app's own keystore lives in build/ — keep it: future updates to
# com.bain.axondetector must be signed with this same key to install over.
KS="$BUILD/axon.keystore"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -alias axonkey \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass android -keypass android \
        -dname "CN=Axon Detector,O=Bain,C=US" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$OUT" "$BUILD/aligned.apk"

echo "== verify =="
"$BT/apksigner" verify --print-certs "$OUT"
"$BT/aapt2" dump badging "$OUT" | head -8
ls -la "$OUT"
