---
description: Build and run the Android IP camera server app on a connected device
---

# Run Skill

Use this skill to build, install, and launch the Android app on a connected device.

## Build Debug APK

```bash
./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

## Install on Connected Device

```bash
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Launch on Device

```bash
~/Library/Android/sdk/platform-tools/adb shell am start -n com.ipcamera.videoserver/.ui.MainActivity
```

## One-Liner (build + install + launch)

```bash
./gradlew :app:assembleDebug && \
  ~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk && \
  ~/Library/Android/sdk/platform-tools/adb shell am start -n com.ipcamera.videoserver/.ui.MainActivity
```

## Check Logcat

```bash
~/Library/Android/sdk/platform-tools/adb logcat -s CameraServerService,WebServer,FtpServer,IpMonitor
```

## Check Connected Devices

```bash
~/Library/Android/sdk/platform-tools/adb devices -l
```
