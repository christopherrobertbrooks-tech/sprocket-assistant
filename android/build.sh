#!/usr/bin/env bash
# Builds Sprocket (dev.sprocket.app) without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner. Needs the Android
# SDK (platform 35, build-tools 35.0.0: $ANDROID_HOME, else ~/.local/android/sdk) and a JDK 17+ (~/.local/android/jdk if
# there, else the one on your PATH). Output: android/build/sprocket.apk. Its own signing key is made once in
# ~/.local/android (keep it: an update must be signed with the same key).
set -euo pipefail
A=$HOME/.local/android; SDK=${ANDROID_HOME:-$A/sdk}; BT=$SDK/build-tools/35.0.0; PLAT=$SDK/platforms/android-35/android.jar
if [ -d "$A/jdk" ]; then export JAVA_HOME=$A/jdk PATH=$A/jdk/bin:$PATH; fi
mkdir -p "$A"
cd "$(dirname "$0")"; rm -rf build; mkdir -p build/gen build/classes build/dex
VERSION_CODE=${VERSION_CODE:-1} VERSION_NAME=${VERSION_NAME:-0.1}
# TEST=1: "Sprocket Test" (dev.sprocket.test), installed beside the real Sprocket and never replacing it (2026-11-10:
# the rebuild with hands is tried here first). Same code; only the app id, its names and the photo provider differ.
OUT=sprocket MANIFEST=AndroidManifest.xml SRC=src RENAME=()
if [ "${TEST:-0}" = 1 ]; then
  OUT=sprocket-test MANIFEST=build/AndroidManifest.xml SRC=build/src RENAME=(--rename-manifest-package dev.sprocket.test)
  sed -e 's/android:label="Sprocket"/android:label="Sprocket Test"/' -e 's/android:label="Sprocket: /android:label="Sprocket Test: /' \
      -e 's/dev\.sprocket\.app\.photos/dev.sprocket.test.photos/' AndroidManifest.xml > build/AndroidManifest.xml
  cp -r src build/src
  sed -i 's/dev\.sprocket\.app\.photos/dev.sprocket.test.photos/' build/src/dev/sprocket/app/PhotoProvider.java
fi
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$PLAT" --manifest "$MANIFEST" -R build/res.zip -A assets --java build/gen --auto-add-overlay \
  --min-sdk-version 26 --target-sdk-version 35 --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" "${RENAME[@]}"
javac -source 11 -target 11 -encoding UTF-8 -Xlint:-options -classpath "$PLAT" -d build/classes $(find "$SRC" build/gen -name '*.java')
"$BT/d8" --release --min-api 26 --lib "$PLAT" --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -P 16 -f 4 build/unsigned.apk build/aligned.apk
KS=$A/sprocket-release.jks PW=$A/sprocket-release.pw
if [ ! -f "$KS" ]; then
  (umask 077; head -c 24 /dev/urandom | base64 > "$PW")
  keytool -genkeypair -keystore "$KS" -storepass:file "$PW" -keypass:file "$PW" -alias sprocket -keyalg RSA -keysize 3072 \
    -validity 10000 -dname "CN=Sprocket" -noprompt 2>/dev/null
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "file:$PW" --out "build/$OUT.apk" build/aligned.apk
"$BT/apksigner" verify "build/$OUT.apk" && echo "signed and verified: $(du -h "build/$OUT.apk" | cut -f1) build/$OUT.apk"
