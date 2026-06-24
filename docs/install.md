# Install Kernel Browser

Kernel Browser releases ship as ABI split APKs. Install the APK that matches your device CPU.

| Device | APK |
|---|---|
| Modern phones, including Galaxy S24 Ultra | `arm64-v8a` |
| Older 32-bit Android phones | `armeabi-v7a` |
| Android emulator | `x86_64` |

## S24 Ultra

Download the `arm64-v8a` APK from the latest GitHub release:

```text
KernelBrowser-1.10-v1.10-safari-suggestions-2026-06-24-arm64-v8a.apk
```

If Android says the app is not installed, uninstall any older debug build first, then install the release APK again.

## Verify From ADB

```powershell
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r app\build\outputs\installable\KernelBrowser-1.10-v1.10-safari-suggestions-2026-06-24-arm64-v8a.apk
```

> [!NOTE]
> APK size is mostly GeckoView native code. ABI split releases avoid shipping native libraries for CPUs your phone cannot use.
