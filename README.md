# Réveil Alexa

Hands-free "Alexa" for Meta Portals running Android 10.

Meta removed Alexa from the Portal, and the Amazon Alexa app's own hands-free mode is disabled there. Réveil Alexa listens for "Alexa" on the device and opens Alexa's voice screen for you.

The easiest way to install it is [JailPortal](https://github.com/zaka0002/jailportal): one click installs it and sets every permission below.

## How it works

- Listens offline with [Vosk](https://alphacephei.com/vosk/) and `vosk-model-small-fr-0.22`, restricted to the grammar `["alexa", "[unk]"]`. No audio leaves the Portal.
- Fires only on a **finished** recognition that **ends with "alexa"**, with word confidence ≥ 0.85. Partial results and "alexa …" in the middle of speech are ignored, which keeps TV and conversation from setting it off.
- On a hit it releases the mic, opens `com.amazon.dee.app/com.amazon.alexa.voice.VoiceHandsFreeSearchActivity` and beeps, waits until Alexa has stopped listening and speaking, then listens again.
- Android 10 on the Portal silences a background app's capture every so often. A watchdog sees it (`AudioRecordingConfiguration.isClientSilenced()`) and reopens the mic while an invisible activity is on top for under a second, the only state in which the capture is allowed again. It ignores the first 2.5 s after every reopen, when Vosk tends to hear "alexa" in noise.
- Starts on boot and has no home-screen icon: it only runs in the background. Open its status screen (state, last word heard, trigger count) with `adb shell am start -n fr.portal.reveilalexa/.MainActivity`.

Say "Alexa", wait for the beep, then give your command.

## Requirements

- A Portal with ADB enabled (Settings → Debug → ADB Enabled).
- Amazon Alexa **2.2.508613.0** (`com.amazon.dee.app`), signed in. That version opens its voice screen on the Portal.

## Manual install

```bash
adb install -r reveil-alexa.apk
adb shell pm grant fr.portal.reveilalexa android.permission.RECORD_AUDIO
adb shell appops set fr.portal.reveilalexa SYSTEM_ALERT_WINDOW allow
adb shell dumpsys deviceidle whitelist +fr.portal.reveilalexa
adb shell am start -n fr.portal.reveilalexa/.MainActivity
```

`SYSTEM_ALERT_WINDOW` is what lets it open Alexa from the background on Android 10. Repeat the `appops` line after every reinstall.

Events (heard words, confidence, ms since the mic opened, mic revives) go to a journal readable over ADB, since the Portal's logcat only keeps about a minute:

```bash
adb shell cat /sdcard/Android/data/fr.portal.reveilalexa/files/journal.txt
```

## Build

JDK 17 and the Android SDK (platform 35):

```bash
./gradlew assembleRelease
```

The APK is `app/build/outputs/apk/release/app-release.apk`. After changing anything under `app/src/main/assets/model-fr`, bump `versionCode`: the model is copied out of the APK only when it changes.

## Limits

- The model is French; "Alexa" said with a strong English accent may score lower.
- Each mic revive puts an invisible window on top for under a second, about every 30 s when idle. A touch at that exact moment can be lost.
- Uses about 20–30 % of one CPU core and 200 MB of RAM.

## License

MIT for this app's code. [Vosk](https://github.com/alphacep/vosk-api) and the `vosk-model-small-fr-0.22` model are Apache-2.0, by Alpha Cephei. Not affiliated with Meta or Amazon; "Alexa" is a trademark of Amazon.
