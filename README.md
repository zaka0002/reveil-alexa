# Réveil Alexa

Hands-free "Alexa" for Meta Portals running Android 10.

Meta removed Alexa from the Portal, and on Android 10 a background app's microphone gets silenced, so no wake-word app keeps hearing you once another app, like your launcher, is in front. Réveil Alexa listens for "Alexa" whatever is on screen and hands the turn to the Portal's own Alexa client.

The easiest way to install it is [JailPortal](https://github.com/zaka0002/jailportal): one click installs it and sets every permission below.

## How it works

- Listens offline with [Vosk](https://alphacephei.com/vosk/) and `vosk-model-small-fr-0.22`, restricted to the grammar `["alexa", "[unk]"]`. No audio leaves the Portal.
- Fires only on a **finished** recognition that **ends with "alexa"**, with word confidence ≥ 0.85. Partial results and "alexa …" in the middle of speech are ignored, which keeps TV and conversation from setting it off.
- On a hit it releases the mic and beeps, brings Meta's Alexa client (`com.amazon.alexa.multimodal.falcon/.SIMActivity`) to the front — on Android 10 its own capture is silenced otherwise — and 0.8 s later sends it `com.amazon.alexa.multimodal.falcon.LISTEN`. Falcon's `TURN_DONE` broadcast ends the turn; one that comes within 1.5 s is the known cold-start abort, so LISTEN is sent once more. It then waits for Alexa's mic and speech to go quiet and listens again. (Same handoff as [Portal HA Bridge](https://github.com/RoadRunner-1024/portal-ha-bridge).) Without falcon it opens the Amazon Alexa phone app instead.
- Android 10 on the Portal silences a background app's capture every so often (about every 10 s while another app holds a mic, every ~30 s otherwise). A watchdog sees it (`AudioRecordingConfiguration.isClientSilenced()`) and reopens the mic while an invisible activity is on top for under a second, the only state in which the capture is allowed again. Other apps' captures count as "busy" only if they aren't silenced themselves: HA Bridge, for one, keeps a silenced capture open all the time. The first 0.8 s after a reopen are ignored.
- Starts on boot and has no home-screen icon: it only runs in the background. Open its status screen (state, last word heard, trigger count) with `adb shell am start -n fr.portal.reveilalexa/.MainActivity`.

Say "Alexa", wait for the beep, then give your command.

## Requirements

- A Portal with ADB enabled (Settings → Debug → ADB Enabled).
- Meta's Alexa client **falcon** (`com.amazon.alexa.multimodal.falcon`), signed in at amazon.com/code. Get it from [starbrightlab/hey-dist](https://github.com/starbrightlab/hey-dist/releases/tag/v0.1.0) (SHA-256 `76133f807e492e46aaf58e6ae503e623a93af840eaa3eccfb8630f1ecab3268d`), or let JailPortal install it. It needs `pm grant` of `RECORD_AUDIO`, `READ_PHONE_STATE` and `INTERACT_ACROSS_USERS` and `appops set … SYSTEM_ALERT_WINDOW allow`; without them it stays disconnected and says it has no internet. Remove `com.amazon.dee.app` first: it blocks falcon's install.
- If you also run Portal HA Bridge, turn off its **Alexa support** (Settings → Voice & Assistants) so both don't answer.

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
