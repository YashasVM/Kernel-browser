# Build And Verify

Kernel Browser is built with Gradle and Android Studio's bundled JBR.

## Release Build

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat testDebugUnitTest assembleRelease --no-daemon
```

Linux / macOS (JDK 17 or newer on `JAVA_HOME`, Android SDK via `ANDROID_HOME`
or `local.properties`; the fetch script needs `curl`, `unzip`, `python3` and
`sha256sum` or `shasum`):

```sh
sh scripts/fetch_extensions.sh --clean
./gradlew testDebugUnitTest assembleRelease --no-daemon
sh scripts/verify_apks.sh
```

`scripts/verify_apks.sh` checks that every APK in `app/build/outputs/installable/`
bundles uBlock Origin including its `_locales/` directory (AGP's default asset
filter strips `_*` directories, which breaks bundled extensions). Pass APK paths
or a directory to check other files.

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

## CI

`.github/workflows/android.yml` runs on every push and pull request:

- `test`: `./gradlew testDebugUnitTest` on JDK 21 (Temurin). Test reports are
  uploaded as the `unit-test-reports` artifact when the job fails.
- `build`: fetches extensions (`extensions-cache/` is cached, keyed on the hash
  of `scripts/fetch_extensions.sh`), runs `assembleRelease`, then
  `scripts/verify_apks.sh`. Each ABI APK is uploaded as its own artifact,
  named after the APK file, and kept for 7 days. Download only `arm64-v8a`
  for current phones.

A new push to the same branch or pull request cancels the older run. CI release
APKs are signed with the runner's throwaway debug keystore, so they cannot be
installed over a locally built APK without uninstalling first.
