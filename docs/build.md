# Build And Verify

Kernel Browser is built with Gradle and Android Studio's bundled JBR.

## Release Build

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat testDebugUnitTest assembleRelease --no-daemon
```

## APK Signing Check

```powershell
$apk='app\build\outputs\installable\KernelBrowser-1.10-v1.10-safari-suggestions-2026-06-24-arm64-v8a.apk'
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\apksigner.bat" verify --verbose $apk
```

## APK Metadata Check

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\aapt.exe" dump badging $apk |
    Select-String "package:|native-code|application-icon"
```

The S24 Ultra should install the `arm64-v8a` APK.
