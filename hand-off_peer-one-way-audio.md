# Hand-off — peer's one-way audio (his mic silent to us)

**Opened:** 2026-07-27. **Status:** diagnosis only, nothing built, nothing changed.
**Blocked on:** physical/adb access to the *contact's* phone (not 白い熊's Mate XT).

---

## 1. Symptom as reported

A contact who has our app (`shiroikuma.jami`) installed cannot carry out audio calls with us:

- Call connects. **He hears 白い熊 perfectly.**
- **白い熊 hears nothing** from him — not faint, not choppy: dead.
- `RECORD_AUDIO` is **granted** on his phone.
- **His microphone hardware is fine** — normal cellular calls and other messengers from the same
  phone sound correct to the other party.

So the broken leg is exactly one: **his capture → our playback**.

---

## 2. Where the fault must live

His **capture side**, not the network. Reasoning:

- Jami's media rides an ICE-negotiated pair, and ICE only completes after connectivity checks
  succeed **in both directions**. A call that connects and carries our voice to him is a path that
  can also carry his voice back. Genuine one-way RTP is a SIP-era failure mode that Jami's ICE
  handshake largely designs out.
- His mic works in every other app → not hardware, not a global mute.
- Playback (his RX) is unrestricted on Android; capture is the thing the OS silently gates.

Android's key property here: when it refuses an app the microphone, it does **not** error — it
hands over a stream of **digital zeros**. Everything looks healthy at every layer. That is precisely
the signature of this bug.

---

## 3. Prime suspect — the Android 17 / API 37 microphone-FGS gap

`jami-android/app/src/main/java/cx/ring/service/CallNotificationService.kt:57`, from upstream commit
`c5afb2195` ("fix callnotification crash for android 17", 2026-06-19). We inherit it; our
`jami-android/app/build.gradle.kts` has `compileSdk = 37` / `targetSdk = 37`.

```kotlin
if (Build.VERSION.SDK_INT >= 37) {
    // API 37+: microphone and camera FGS types require an eligible foreground
    // state and crash when starting from a background push wakeup. Omit both —
    // RECORD_AUDIO and CAMERA still work via the foreground activity once the
    // user answers the call.
    val pm = packageManager
    val callServiceType =
        if (pm.hasPermission(Manifest.permission.FOREGROUND_SERVICE_PHONE_CALL))
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL else 0
    ...
```

On API 37 the call foreground service starts **without** `FOREGROUND_SERVICE_TYPE_MICROPHONE`.
Android only permits recording while the app is in an *eligible* state — a visible activity, or an
FGS typed `microphone`. Upstream's assumption is that the answered-call **activity** supplies the
eligibility. When it does not — he answers straight from the notification shade, the screen locks,
he switches apps, the activity is destroyed and rebuilt, or a fold/unfold event backgrounds it —
capture goes to zeros while **playback keeps working normally**.

That reproduces the reported asymmetry exactly, and it is device-specific (only phones on API 37),
which explains why this one contact is affected and nobody else is.

**This is upstream Jami's code, not one of our patches.** Official `cx.ring` on Android 17 has the
same gap. If confirmed, it is an upstream bug → candidate for `/jami-gerrit`.

Contrast with the API 34–36 branch at `CallNotificationService.kt:75`: there the microphone type
*is* requested, but only when `FOREGROUND_SERVICE_MICROPHONE` **and** `RECORD_AUDIO` are both
granted **at the instant the service starts**. He has `RECORD_AUDIO`, so that path should be fine —
but verify rather than assume, since a permission can read "granted" while the appop is not (see §4).

---

## 4. Other candidates, ranked

1. **Appop `RECORD_AUDIO` set to `ignore` while the permission reads "granted".**
   This is how OEM privacy managers (Huawei, Xiaomi, Samsung, plus some third-party firewalls)
   implement "mic blocked for this app" without revoking the permission. The app receives **silence,
   no error**, and every other app is unaffected. Very strong fit for the symptom, and trivially
   checkable over adb — see §6. Rank this *equal-first* with §3 until measured.
2. **OEM background-mic / "protected apps" restriction** — battery-optimization and
   app-launch-management lists produce the identical silent-zeros behaviour, on *any* Android
   version. Same foreground test distinguishes it.
3. **Another process owns the mic** — a call recorder, a voice assistant, or a stale HFP/SCO
   session. Capture for `VOICE_COMMUNICATION` is effectively exclusive; the loser gets silence.
4. **Bluetooth SCO** — with a headset paired, Jami routes capture to the BT mic
   (`HardwareServiceImpl` does explicit BT routing, `BluetoothWrapper`). A headset whose SCO link
   fails yields silence in Jami while other apps, on a different path, sound fine.
5. **Stale capture stream** — audio layer initialized before the permission was effective; stays
   dead until the app is force-stopped.
6. **In-call mute** — `CallPresenter.muteMicrophoneToggled` / `Call.isAudioMuted`. Beneath
   mentioning, but rule it out; it costs one glance at his call screen.
7. **Hardware AEC zeroing the capture path** on an unusual routing. The daemon exposes
   `JamiService.setNoiseSuppressState()` (`JamiService.java:432`) but **our UI does not surface it** —
   it would have to be poked from the daemon config or added. Last resort only.

---

## 5. Remote tests — no device access needed, do these first

Ordered cheapest-first. Ask him to run through them and report.

1. **Green mic privacy dot** — does it appear in his status bar during the Jami call? **Absent → the
   app never held the mic**, which nearly settles it toward §3/§4.1. This single observation is the
   highest-value datum in this document.
2. **Record a voice message in Jami** (in-app recorder) and send it.
   - Silent → the app cannot capture *at all* → §4.1 / §4.2 / §4.3.
   - Audible → capture works in-app; the failure is specific to the **in-call** path → §3 hard.
3. **Call with the Jami call screen kept on-screen the entire time** — unlocked, foreground, no app
   switching, and **not** answered from the notification shade. If our audio appears, it is §3 (or
   §4.2). This is the decisive test for the prime suspect.
4. **Video call.** If his video arrives but his audio does not, the media path is *proven* fine and
   the fault is definitively capture.
5. **Force-stop Jami, relaunch, call again** (tests §4.5), and separately **turn Bluetooth off** for
   one call (tests §4.4).

---

## 6. On-device debug plan — when we have adb on *his* phone

> **Hard rule (from `~/.claude/CLAUDE.md`): every `adb` invocation runs with
> `dangerouslyDisableSandbox: true`.** The sandbox blocks adb's server socket and the device list
> comes back empty. Do not waste a sandboxed attempt first.
>
> **Hard rule: `adb disconnect <ip>:5555` at the end of every batch** if this is a wireless session —
> a standing session pins the WiFi radio and burned 1322 mAh on 2026-07-18. Not per-command; per
> batch. Never disconnect while an interactive `scrcpy` mirror is live.

### 6a. Establish the facts

```
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.manufacturer
adb shell getprop ro.product.model
adb shell dumpsys package shiroikuma.jami | grep -E "versionName|versionCode"
```

`ro.build.version.sdk` = 37 → §3 goes from suspect to near-certain.

### 6b. The appop check (§4.1) — do this even if the permission reads granted

```
adb shell cmd appops get shiroikuma.jami RECORD_AUDIO
adb shell cmd appops get shiroikuma.jami
adb shell dumpsys package shiroikuma.jami | grep -A 30 "runtime permissions"
```

Anything other than `allow` for `RECORD_AUDIO` is the answer. Test the fix directly:

```
adb shell cmd appops set shiroikuma.jami RECORD_AUDIO allow
```

Also check the global sensor-privacy mic toggle (should be off/unblocked):

```
adb shell dumpsys sensor_privacy
```

### 6c. Foreground-service types actually granted (§3)

**During a live call**, with the call backgrounded:

```
adb shell dumpsys activity services shiroikuma.jami
```

Look at the `foregroundServiceType` / `fgServiceTypes` field on `CallNotificationService`. If
`microphone` is **absent** while `phoneCall` is present, §3 is confirmed on the wire.

### 6d. Who holds the mic, and is the stream real (§4.3, §4.4)

**During a live call:**

```
adb shell dumpsys audio | grep -iE "mode|mic|record|communication|device"
adb shell dumpsys media.audio_flinger | grep -iE "input|record|active"
```

`dumpsys audio` lists active recorders and the current audio mode — Jami should show as a recorder
with `MODE_IN_COMMUNICATION` (set at `HardwareServiceImpl.kt:325`). If Jami is not listed as an
active recorder at all, the capture stream was never opened. If it is listed but we still hear
nothing, look at routing / BT SCO.

### 6e. Logcat

```
adb logcat -c
# ...place the call, let it fail, ~30 s...
adb logcat -d -v time | grep -iE "HardwareServiceImpl|CallNotificationService|CallFragment|AudioRecord|AudioFlinger|AudioPolicy|ActivityManager.*shiroikuma|appops|SecurityException|FOREGROUND_SERVICE" > ~/tmp/peer-audio-logcat.txt
```

Watch for `AudioPolicyManager`/`AudioFlinger` lines about a silenced or muted input, and for
`ActivityManager` FGS-type complaints. Note the EMUI caveat from prior work: **EMUI eats release-app
`Log.d`/`Log.w`** — if his phone is a Huawei, do not conclude "no log ⇒ no code path"; rely on
`dumpsys` instead.

### 6f. In-app diagnostic log

Jami's own diagnostic log is the daemon-side view. **It only writes to `/sdcard/tmp` when SAVED via
the download icon — "Stop" discards it.** Always tell him to press SAVE.

---

## 7. If §3 confirms — what a fix would look like

Not built, not designed in detail; sketch only, and the choice is 白い熊's.

- The upstream comment says the mic/camera FGS types "crash when starting from a background push
  wakeup" on API 37. So the fix is **not** to unconditionally restore the types — that is the crash
  upstream was fixing.
- Plausible shape: request `FOREGROUND_SERVICE_TYPE_MICROPHONE` **at answer time** (a second
  `startForeground` from an eligible state, once the user has actually accepted the call), rather
  than at incoming-call-notification time. Keep the bare `phoneCall` type for the ringing phase.
- This belongs upstream — see `.claude/skills/jami-gerrit` and the `jami-gerrit-filing` memory:
  Claude prepares the whole patch, **白い熊 runs the single push command and types the Gerrit
  password**.

---

## 8. Open questions to close before touching code

1. His **Android version** (`ro.build.version.sdk`). 37 → §3.
2. Whether he **answers from the notification / lock screen** or from the open app.
3. Whether he runs **our fork** (`shiroikuma.jami`) or **official `cx.ring`**, and which build — this
   decides whether a fix is something we can ship him directly or strictly an upstream report.
4. His phone **manufacturer** — Huawei/Xiaomi/Samsung raises §4.1 and §4.2 sharply.
5. Result of the §5 remote tests, especially the green-dot observation and the voice-message test.

---

## 9. Housekeeping

- Nothing in the tree was modified for this investigation. This file is **uncommitted** — commit it
  or delete it deliberately.
- No build was made and none is implied. Per `~/.claude/CLAUDE.md`, build only on an explicit
  instruction from 白い熊.
