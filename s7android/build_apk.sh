#!/usr/bin/env bash
# ============================================================================
# build_apk.sh - S7Scale command-line build for macOS/Linux (mirror of build_apk.bat)
# Pipeline: aapt2 compile/link -> javac -> d8 -> inject classes.dex ->
#           zipalign -> apksigner (debug keystore auto-generated) -> dist/S7Scale.apk
# SDK: first run auto-downloads platforms;android-34 + build-tools;34.0.0
#      dl.google.com first, auto-fallback to tencent mirror.
# Requires: JDK (javac/keytool on PATH), curl, python3.
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")"

PY="${PY:-python3}"
command -v python3 >/dev/null || PY=python
# macOS: brew 装的 JDK 未注册到 /usr/libexec/java_home, PATH 里的 java 可能命中
# 系统 stub(/usr/bin/java) 报 "Unable to locate a Java Runtime" —— 显式定位真实 JDK 前置
if [ -x /usr/local/bin/java ]; then
    _JH="$(dirname "$(dirname "$(readlink -f /usr/local/bin/java)")")"
    if [ -x "$_JH/bin/javac" ]; then
        export JAVA_HOME="$_JH"
        export PATH="$JAVA_HOME/bin:$PATH"
    fi
fi
SDK="$PWD/sdk"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34"
AJAR="$PLATFORM/android.jar"
BUILD="$PWD/build"
DIST="$PWD/dist"
KEYSTORE="$PWD/debug.keystore"

# ---------------------------------------------------------------- helpers
fetch() {
    # $1=destination file  $2=repo artifact name; google first, then tencent mirror
    [ -f "$1" ] && return 0
    echo "       Downloading $2 (dl.google.com) ..."
    if ! curl -fsSL --retry 2 --max-time 600 -o "$1" "https://dl.google.com/android/repository/$2"; then
        echo "       dl.google.com failed, trying tencent mirror ..."
        curl -fsSL --retry 2 --max-time 600 -o "$1" "https://mirrors.cloud.tencent.com/AndroidSDK/$2" \
            || { echo "[!] both sources failed: $2"; exit 1; }
    fi
}

echo "[1/8] Checking Android SDK ..."
if [ ! -x "$BT/aapt2" ]; then
    echo "       SDK not found, downloading ..."
    mkdir -p "$SDK"
    case "$(uname -s)" in
        Darwin) BT_ZIP="build-tools_r34-macosx.zip" ;;
        Linux)  BT_ZIP="build-tools_r34-linux.zip" ;;
        *)      echo "[!] unsupported OS"; exit 1 ;;
    esac
    fetch "$SDK/dl_platform.zip" "platform-34-ext7_r03.zip"
    fetch "$SDK/dl_buildtools.zip" "$BT_ZIP"
    echo "       Extracting android.jar from platform-34 (disk-space friendly) ..."
    mkdir -p "$PLATFORM"
    "$PY" -c "
import sys, zipfile
z = zipfile.ZipFile('$SDK/dl_platform.zip')
name = next(n for n in z.namelist() if n.rsplit('/', 1)[-1] == 'android.jar' and n.count('/') <= 1)
open('$AJAR', 'wb').write(z.read(name))
print('[extract]', name, '->', '$AJAR')
"
    echo "       Extracting build-tools 34.0.0 ..."
    # 展平成功即可; 末尾清空壳目录可能被环境删除保护拦截, 不视为失败
    "$PY" sdk_extract.py "$SDK/dl_buildtools.zip" "$BT" || true
    rmdir "$BT"/android-* 2>/dev/null || true
    chmod -R +x "$BT" 2>/dev/null || true
    rm -f "$SDK/dl_platform.zip" "$SDK/dl_buildtools.zip"
fi
[ -x "$BT/aapt2" ] || { echo "[!] aapt2 still missing"; exit 1; }
[ -f "$AJAR" ] || { echo "[!] android.jar missing"; exit 1; }
echo "       SDK OK: $SDK"

echo "[2/8] Cleaning build dir ..."
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$DIST"

echo "[3/8] aapt2 compile resources ..."
"$BT/aapt2" compile --dir src/res -o "$BUILD/res.zip"

echo "[4/8] aapt2 link ..."
"$BT/aapt2" link -o "$BUILD/app.base.apk" -I "$AJAR" \
    --manifest src/AndroidManifest.xml -R "$BUILD/res.zip" \
    --java "$BUILD/gen" --auto-add-overlay \
    --min-sdk-version 26 --target-sdk-version 34

echo "[5/8] javac (UTF-8 sources) ..."
javac -encoding UTF-8 -parameters -source 1.8 -target 1.8 \
    -Xlint:-options -classpath "$AJAR" -d "$BUILD/classes" \
    src/java/com/example/s7scale/*.java "$BUILD/gen/com/example/s7scale/R.java"

echo "[6/8] d8 to dex ..."
jar cf "$BUILD/classes.jar" -C "$BUILD/classes" .
"$BT/d8" --release --min-api 26 --lib "$AJAR" --output "$BUILD/dex" "$BUILD/classes.jar"
[ -f "$BUILD/dex/classes.dex" ] || { echo "[!] d8 produced no classes.dex"; exit 1; }

echo "[7/8] Inject dex + zipalign ..."
"$PY" inject_dex.py "$BUILD/app.base.apk" "$BUILD/dex/classes.dex" "$BUILD/app.unsigned.apk"
"$BT/zipalign" -f -p 4 "$BUILD/app.unsigned.apk" "$BUILD/app.aligned.apk"

echo "[8/8] apksigner sign ..."
if [ ! -f "$KEYSTORE" ]; then
    echo "       Generating debug.keystore ..."
    keytool -genkeypair -v -keystore "$KEYSTORE" -alias androiddebugkey \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass android -keypass android \
        -dname "CN=Android Debug,O=Android,C=US" >/dev/null
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android \
    --key-pass pass:android --ks-key-alias androiddebugkey \
    --out "$DIST/S7Scale.apk" "$BUILD/app.aligned.apk"
"$BT/apksigner" verify --print-certs "$DIST/S7Scale.apk"
"$BT/zipalign" -c 4 "$DIST/S7Scale.apk" && echo "[OK] zipalign check passed"

echo
echo "=================================================================="
echo "  BUILD OK: $DIST/S7Scale.apk"
echo "=================================================================="
