# Android development from Windows and WSL

Threadline's source may live in WSL while Android Studio, the emulator, Java,
and the Android SDK run on Windows. That works, but it creates three address and
path boundaries that are easy to confuse.

## Recommended setup

Use Android Studio on Windows for the SDK, emulator, build output, Logcat, and
app installation. Install:

- JDK 17;
- Android SDK Platform 37 and matching build tools;
- Android SDK Platform-Tools;
- Android Emulator; and
- one Google APIs x86_64 emulator image.

A smaller Pixel profile is usually less demanding than Pixel 9. Pixel 9 on
Android 15 was usable for the Phase 0 proof, but sluggish.

Android Studio's SDK Manager and Device Manager are the preferred installation
path. The command-line tools remain useful for automation, but are sensitive to
Java environment and PowerShell line continuation.

## PowerShell rules that avoided false failures

Set Java and the SDK explicitly when a terminal does not inherit Android
Studio's environment:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
$Sdk = "$env:LOCALAPPDATA\Android\Sdk"
& "$env:JAVA_HOME\bin\java.exe" -version
```

Keep `avdmanager` flags and their values in the same PowerShell command. A line
break after `--name` makes the next line a new command:

```powershell
"no" | & "$Sdk\cmdline-tools\latest\bin\avdmanager.bat" create avd --force --name Threadline_API_35 --package "system-images;android-35;google_apis_playstore;x86_64"
```

List and launch:

```powershell
& "$Sdk\emulator\emulator.exe" -list-avds
& "$Sdk\emulator\emulator.exe" -avd Threadline_API_35
```

## Emulator networking

The address depends on which machine owns the listening socket:

| Caller | Server listener | Address to use |
|---|---|---|
| Windows or WSL host | `127.0.0.1:2222` | `127.0.0.1:2222` |
| Android emulator | Windows host loopback-forwarded fixture | `10.0.2.2:2222` |
| Android emulator | the emulator itself | `127.0.0.1` |

For Threadline's fixture, enter `10.0.2.2` in the emulator. `127.0.0.1`
inside Android means Android itself, not Windows or WSL.

The fixture remains bound to host loopback only. The emulator's `10.0.2.2`
alias reaches that host loopback without exposing SSH on the LAN.

## Installing an APK across the WSL boundary

PowerShell strings must not contain display wrapping or copied continuation
prompts. Keep the UNC path literal and copy the APK to a normal Windows path
before calling Windows `adb.exe`:

```powershell
$Sdk = "$env:LOCALAPPDATA\Android\Sdk"
$Adb = "$Sdk\platform-tools\adb.exe"
$SourceApk = "\\wsl.localhost\<distribution>\home\<user>\path\to\threadline\app\build\outputs\apk\debug\app-debug.apk"
$LocalApk = "$env:TEMP\threadline-debug.apk"
Copy-Item -LiteralPath $SourceApk -Destination $LocalApk -Force
& $Adb -e wait-for-device
& $Adb -e install -r $LocalApk
& $Adb -e shell am start -n io.github.r055le.threadline.debug/dev.threadline.MainActivity
```

If `adb` reports that the activity does not exist, inspect the earlier install
command first. A missing APK and an uninstalled package are more likely than an
activity-name problem.

Standard debug builds use `io.github.r055le.threadline.debug`, while signed
alpha releases use `io.github.r055le.threadline`. They install side by side and
never share private data. Two builds of either identity that use different
signing keys still cannot update one another.

The older pre-alpha debug identity `dev.threadline` is not an update target for
either new identity. Remove it only after its disposable profiles, trust, keys,
and transcript history are no longer needed.

## Fixture connection values

- Display name: `Local fixture`
- Host: `10.0.2.2`
- Port: `2222`
- Username: `threadline`
- Password: the ignored local value in `fixtures/openssh/.env`

Never paste the password into documentation, commits, bug reports, or Logcat.

## Isolated validation install

To exercise destructive fixture scenarios such as host-key rotation without
touching the normal app's known-host database, build with a separate
application ID:

```powershell
.\gradlew.bat :app:assembleDebug `
  "-Pthreadline.applicationId=io.github.r055le.threadline.validation"
```

The property is opt-in and is treated as the exact application ID without the
standard `.debug` suffix. The validation APK has separate Android app data, so
it can be installed beside the normal debug and release apps and removed after
the test.
