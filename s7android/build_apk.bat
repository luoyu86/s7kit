@echo off
rem ============================================================================
rem build_apk.bat - S7Scale command-line build (no Gradle, no 3rd-party deps)
rem Pipeline: aapt2 compile/link -> javac -> d8 -> inject classes.dex ->
rem           zipalign -> apksigner (debug keystore auto-generated) -> dist\S7Scale.apk
rem SDK: first run auto-downloads platforms;android-34 + build-tools;34.0.0
rem      dl.google.com first, auto-fallback to tencent mirror.
rem Requires: JDK21 at C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot,
rem           Windows built-in curl/tar, local Python (same as s7_provision.bat).
rem NOTE: keep this file pure ASCII (cmd parses batch in OEM codepage).
rem ============================================================================
setlocal enabledelayedexpansion
cd /d "%~dp0"

set "JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "PY=C:\Users\luoyu\AppData\Local\Python\bin\python.exe"
if not exist "%PY%" set "PY=python"

set "SDK=%~dp0sdk"
set "BT=%SDK%\build-tools\34.0.0"
set "PLATFORM=%SDK%\platforms\android-34"
set "AJAR=%PLATFORM%\android.jar"
set "BUILD=%~dp0build"
set "DIST=%~dp0dist"
set "KEYSTORE=%~dp0debug.keystore"

echo [1/8] Checking Android SDK ...
if not exist "%BT%\aapt2.exe" call :get_sdk
if errorlevel 1 goto fail
if not exist "%AJAR%" ( echo [!] android.jar missing & goto fail )
echo       SDK OK: %SDK%

echo [2/8] Cleaning build dir ...
if exist "%BUILD%" rd /s /q "%BUILD%" >nul 2>&1
mkdir "%BUILD%" 2>nul
mkdir "%BUILD%\gen" 2>nul
mkdir "%BUILD%\classes" 2>nul
mkdir "%BUILD%\dex" 2>nul
if not exist "%DIST%" mkdir "%DIST%"

echo [3/8] aapt2 compile resources ...
"%BT%\aapt2.exe" compile --dir src\res -o "%BUILD%\res.zip" || goto fail

echo [4/8] aapt2 link ...
"%BT%\aapt2.exe" link -o "%BUILD%\app.base.apk" -I "%AJAR%" --manifest src\AndroidManifest.xml -R "%BUILD%\res.zip" --java "%BUILD%\gen" --auto-add-overlay --min-sdk-version 26 --target-sdk-version 34 || goto fail

echo [5/8] javac (UTF-8 sources) ...
rem -parameters: JDK21 javac emits MethodParameters with NULL names for synthetic
rem params of nested anonymous classes, which crashes d8 8.2.2 (build-tools 34).
rem Naming all params avoids null entries; ART ignores the attribute at runtime.
"%JAVA_HOME%\bin\javac.exe" -encoding UTF-8 -parameters -source 1.8 -target 1.8 -classpath "%AJAR%" -d "%BUILD%\classes" src\java\com\example\s7scale\*.java "%BUILD%\gen\com\example\s7scale\R.java" || goto fail

echo [6/8] d8 to dex ...
"%JAVA_HOME%\bin\jar.exe" cf "%BUILD%\classes.jar" -C "%BUILD%\classes" .
call "%BT%\d8.bat" --release --min-api 26 --lib "%AJAR%" --output "%BUILD%\dex" "%BUILD%\classes.jar" || goto fail
if not exist "%BUILD%\dex\classes.dex" ( echo [!] d8 produced no classes.dex & goto fail )

echo [7/8] Inject dex + zipalign ...
"%PY%" inject_dex.py "%BUILD%\app.base.apk" "%BUILD%\dex\classes.dex" "%BUILD%\app.unsigned.apk" || goto fail
"%BT%\zipalign.exe" -f -p 4 "%BUILD%\app.unsigned.apk" "%BUILD%\app.aligned.apk" || goto fail

echo [8/8] apksigner sign ...
if not exist "%KEYSTORE%" (
  echo       Generating debug.keystore ...
  "%JAVA_HOME%\bin\keytool.exe" -genkeypair -v -keystore "%KEYSTORE%" -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname "CN=Android Debug,O=Android,C=US" >nul || goto fail
)
call "%BT%\apksigner.bat" sign --ks "%KEYSTORE%" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out "%DIST%\S7Scale.apk" "%BUILD%\app.aligned.apk" || goto fail

call "%BT%\apksigner.bat" verify --print-certs "%DIST%\S7Scale.apk" || goto fail
"%BT%\zipalign.exe" -c 4 "%DIST%\S7Scale.apk" && echo [OK] zipalign check passed

echo.
echo ==================================================================
echo   BUILD OK: %DIST%\S7Scale.apk
echo ==================================================================
exit /b 0

:get_sdk
echo       SDK not found, downloading ...
if not exist "%SDK%" mkdir "%SDK%"
call :fetch "%SDK%\dl_platform.zip" "platform-34-ext7_r03.zip" || exit /b 1
call :fetch "%SDK%\dl_buildtools.zip" "build-tools_r34-windows.zip" || exit /b 1
echo       Extracting platform-34 ...
"%PY%" sdk_extract.py "%SDK%\dl_platform.zip" "%SDK%\platforms\android-34" || exit /b 1
echo       Extracting build-tools 34.0.0 ...
"%PY%" sdk_extract.py "%SDK%\dl_buildtools.zip" "%SDK%\build-tools\34.0.0" || exit /b 1
del "%SDK%\dl_platform.zip" "%SDK%\dl_buildtools.zip" >nul 2>&1
if not exist "%BT%\aapt2.exe" ( echo [!] aapt2.exe still missing & exit /b 1 )
exit /b 0

:fetch
rem %1=destination file  %2=repo artifact name; google first, then tencent mirror
if exist "%~1" exit /b 0
echo       Downloading %~2 (dl.google.com) ...
curl -fsSL --retry 2 --max-time 600 -o "%~1" "https://dl.google.com/android/repository/%~2"
if errorlevel 1 (
  echo       dl.google.com failed, trying tencent mirror ...
  curl -fsSL --retry 2 --max-time 600 -o "%~1" "https://mirrors.cloud.tencent.com/AndroidSDK/%~2"
  if errorlevel 1 ( echo [!] both sources failed: %~2 & exit /b 1 )
)
exit /b 0

:fail
echo.
echo [FAILED] build aborted, see errors above.
exit /b 1
