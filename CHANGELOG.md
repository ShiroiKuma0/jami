# 白い熊 GNU Jami — `20260731-01+001`

A downstream fork of [GNU Jami](https://github.com/savoirfairelinux/jami-client-android) for Android. Installs **side-by-side** with official Jami (app id `shiroikuma.jami`, label 白い熊 GNU Jami). Everything below is built on top of stock.

---

## ⬆️ Now on upstream Jami `20260731-01`

Rebased onto upstream `20260731-01` (versionCode 502), which also advances the C++ daemon by 34
commits. Two of those are this fork's own work coming home: **“audio: honour the format AAudio
granted a stream”** and **“audio: keep the granted hardware input format”** — filed to GNU Jami's
Gerrit as changes 35396/35397 out of the one-way-audio investigation described further down, and now
merged upstream, so every Jami user gets them rather than only this fork.

What upstream brings that you can see:

- **Blocked group members stay visible**, badged Blocked / Invited / Admin, and the member action
  becomes Block / Unblock instead of removing them outright.
- **An account-loading deadlock is fixed.** The daemon's callback thread and the client's
  per-conversation locks could each block on the other; conversation callbacks are now copied off the
  daemon thread and dispatched per conversation.
- **No duplicate notification** for a file transfer the client already knows about.
- **A commit-announcement storm fixed at the source.** A swarm test that always reported “first
  connection” made the daemon re-announce the current commit to every device of every member —
  upstream measured 12041 announcements in three hours. That is squarely the outbound DHT traffic
  this fork has spent months chasing.
- **A conversation that fell silent after returning to the foreground** now reconnects instead of
  waiting for a process restart.
- Name lookups always answer, and git sockets are shut down when a repository is removed rather than
  leaving fetches blocked on a dead socket.

**A note on measurements:** the daemon's traffic profile moved in both directions in this release —
the announcement-storm fix removes traffic, upstream's new mobile-wake machinery adds some. Any
data-usage baseline taken before this build is **not** comparable to one taken after it.

## 🎨 The theme finishes the job: black sheets and black pills

- **Every bottom sheet is now black with a 2 dp yellow border.** They had kept Material's grey tonal
  surface, because a `BottomSheetDialog` paints its own container — a background on the fragment's
  own root still leaves grey around the rounded corners. One shared helper now paints the container,
  and re-applies after layout, since the sheet installs its own background on its first layout pass.
  Covers the contact picker, member actions, peer services, QR share and scan, the colour and emoji
  pickers, the exposed-service editor, the crash-report viewer and the in-call speaker chooser. The
  voice recorder keeps its frosted-glass panel where the device can blur, now with the same yellow
  border, and falls back to the plain black sheet where it cannot.
- **Pill buttons match.** “Start conversation” had a black border on a black fill — invisible. It and
  every other extended action button (“Create group”, “Start logs”, “Add service”) are now black with
  a yellow border and yellow label, from two theme-level styles rather than per-layout attributes, so
  new ones inherit it. A disabled pill dims to 40 % so it still reads as unavailable, and the
  “Add contact” banner drops its hard-coded green.

## 🔴 Presence dots that mean what they say

A dot was rendered even when the model carrying it had never loaded presence — and that model pins
the status to OFFLINE, so the dot came out **red meaning “not loaded”**, indistinguishable from red
meaning “offline”. The contact picker showed every contact red while the chat list behind it showed
them connected.

- The avatar builder now honours the model instead of forcing a dot on. That alone retires the false
  red dot from launcher share shortcuts (it was being baked into the adaptive icon), notification
  avatars, the chat-files browser and the profile-photo dialog.
- Where a dot is genuinely wanted it is now fed real presence: the contact picker subscribes per row
  to the same live model the chat list uses, and the conversation-details header stops defaulting to
  the presence-less one. This is what makes same-daemon accounts read connected — they hold a live
  link and broadcast no presence, so only the live-connection union gets them right.

## 🧩 Under the hood: the daemon patch stack, reconciled

The fork carries 23 daemon and contrib patches that are re-applied on every build. Twenty survived
the bump untouched; three needed work, and the reasons are worth recording.

- **The capture patch was retired and rebuilt.** Its format half is upstream now, so re-applying it
  rejected two hunks and fuzz-inserted a duplicate declaration next to upstream's — which does not
  compile. Worse, the rejected hunk was the one feeding the silence detector, so a build that
  survived would have carried the digital-silence fallback as dead code. What remains ours is that
  fallback and the raw-capture measurement that triggers it.
- **The swarm redial backoff learned when not to apply.** Upstream added a second entry into exactly
  the speculative path the backoff gates — the reconnect a phone performs on every return to the
  foreground. The backoff now lifts entirely when the routing table holds no active node, keeping the
  measured idle-traffic saving without ever delaying a reconnect from zero.
- **Absent-device de-listing was retargeted.** The bin it used acquired two new meanings upstream: an
  entry there is now a mobile **wake-up target**, costing an extra encrypted DHT put per commit
  announcement — from a patch written to *reduce* outbound traffic — and the call itself expires on
  2026-10-01, which would have made the patch a silent no-op two months from now. It now removes the
  device from the speculative-dial pool directly, behind a live-socket check, with neither side
  effect.


## Calling: a microphone that isn't there, a recorder, and a timer (new in +175–+190)

### 🎙 One-way audio — measured, then fixed

A contact could hear us perfectly and was inaudible in return, with `RECORD_AUDIO` granted, the
microphone privacy indicator lit, and no error anywhere. Guessing was replaced with instrumentation.

- **Per-call audio-path counters** (`SK-AUDIODIAG`) bracket the receive path: raw RTP off the socket
  before decrypt, decoded frames with their RMS, and our own captured frames — the last so the log
  proves the working leg is healthy and the probe itself is sound. A diagnostic that only ever reports
  zeros cannot tell a dead path from a dead probe.
- **The peer's `voice_activity`** is logged. The daemon parses that SIP INFO and then drops it in a
  one-to-one call; it is the only view of the far side's capture without holding that phone.
- What it found: the peer's audio arriving as **5900 frames of bit-exact zero** across two calls, at
  ~50 packets/s with no loss and no decrypt error — and then, from the peer's own phone,
  `tx rms=0.0000 peak=0.0000` for forty consecutive seconds **at the encoder input**. That excluded
  transport, SRTP, the codec, mute and a format mismatch by measurement rather than argument.
- **A two-point capture measurement** brackets the audio processor, so an all-zero transmission can be
  attributed to the platform or to our own processing rather than to neither. It cleared the processor:
  the zeros arrived from the device.
- **The fix.** After the raw capture reads exactly zero for five seconds — which a live microphone never
  does, since one always leaks a noise floor — the input is re-opened off the low-latency
  `VOICE_COMMUNICATION` path, both of which are implicated in this class of HAL fault. Unreachable on a
  working microphone. Confirmed on the affected device: `peak=0.0000` at ~510 frames/s on the fast path,
  real audio in the next second at ~61. **Remembered per device** via a marker beside the daemon's own
  configuration, so the five seconds are paid once rather than at every app start.
- **Two upstream bugs found on the way**, both fixed here: `hardwareInputFormatAvailable()` logged the
  granted capture format and discarded it, so the audio processor was sized from playback alone and any
  microphone wider than the speaker got a processor narrower than its own input; and the AAudio
  callbacks cast the platform buffer to `float*` unconditionally while `getStreamFormat()` in the same
  file already admits an I16 grant — a 2× over-read past the end of the buffer and a heap overflow on
  capture.

### 📼 Call recording that lands in the chat

- **A Record control in the in-call sheet.** Upstream's phone UI has none, though the daemon API has
  always been there. Appended after every existing control, so nothing moved.
- **Recordings go to visible storage.** The client never set a record path, and the daemon falls back to
  its own home directory — app-private storage the user cannot reach. The confirmation names the full
  path when recording stops.
- **The finished recording appears in the conversation** as a normal audio message: play, waveform,
  Share, Save, delete. The daemon names the file exactly once, through a signal whose handler upstream
  logged and dropped, so nothing else in the app was ever told where it went.
- **Two signals, not one.** `RecordPlaybackFilepath` fires for both the start and the stop of a
  recording with the same path, so it cannot say whether one finished; `RecordPlaybackStopped` can, but
  carries no call id and arrives during call teardown on a hang-up. The path signal resolves the
  conversation while the call is alive, the stop signal adds the bubble from that remembered answer.
- **A local recording is not a swarm message**, and three layers assumed otherwise: a swarm history is
  never sorted, so an appended file sat at the bottom of the conversation permanently and became the
  chat-list preview; both delete actions assumed a message id, one deleting nothing and the other
  crashing the app; and the view matched non-swarm rows by an id that every swarm message leaves at
  zero, so a working delete would have removed some other message.
- **Two FileProvider roots.** Without one containing the recordings, `getUriForFile()` threw for every
  recording — no playback, no waveform, no duration, no Share, while Save worked because it copies the
  file directly. The second is `cacheDir/Huawei`: upstream's own Huawei workaround copies there and then
  asks for a URI on that path, which nothing declared, so it could never succeed on the devices it
  exists for.

### ⏱ The call screen

- **The running duration is visible at last.** Upstream computes it every second and writes it into
  `call_status_txt`, which lives inside a container that `initNormalStateDisplay()` hides the moment a
  call is answered — so the timer has been ticking into a hidden view on every call. Now a root-level
  view at the top of the screen, bold yellow on a bordered pill so it reads over video, `M:SS` and
  `H:MM:SS` past the hour.
- **The speaker survives pick-up.** Every call-state change re-asserted a route list derived only from
  "incoming or video", overwriting an explicit choice at exactly the moment the callee answered. The
  chosen output is remembered for the call and re-asserted instead.
- **Incoming-call buttons in the fork's colours** — Accept yellow with black text and icon, Decline red
  with yellow text and icon, without disturbing the in-call End-call or hold-screen buttons that share
  those colour resources.
- **A voice message's menu opens on a long press anywhere in its bubble.** The play button and the
  waveform consumed the gesture — the waveform even seeks on touch-down — so Open / Share / Save /
  Delete were reachable only on the few pixels of bubble around them. The waveform now detects a hold
  itself and restores the pre-press position, so holding never doubles as a seek.
- The message menu **dismisses after deleting a file**, like every other action in it.

## Data, battery and honest health reporting (new in +149–+174)

### 📉 The peer-key refetch — the fork's dominant idle data cost

- Each account listens on `SHA1("peer:" + deviceId)`, where peers post connection requests. Those values
  are never withdrawn — opendht has no delete — and every dial mints a fresh random value id, so
  retries accumulate rather than replace. A push carries only the changed ids; the client discarded them
  and re-fetched the **whole** key. Measured at **148 / 128 / 80 KiB per push** and **81 % of all
  inbound bytes**, against 4–9 KiB for a healthy key.
- **Post-push refetches are coalesced** per listener — a measured **4.1×** reduction.
- **A failed GET no longer erases the listener's value cache**, which used to expire every cached value
  and report everything offline at once.
- **Speculative redials are damped** with a 60 s base doubling to a 30 min cap, gating only the
  swarm's speculative dial and leaving reuse, FIND responses and every user-initiated path untouched.
- **An absent device leaves the dial pool.** The presence OFFLINE edge was delivered and discarded, so a
  device entered the known-node set on its first announce and was never removed — 73 % of outbound
  offers died unanswered at the 30 s timeout, 94 % of them from that one selector.
- **Co-resident accounts rendezvous in-process** instead of through the DHT. They were 35 % of all
  transports.
- **DHT client mode** is declared, and per-kind DHT message statistics plus passively-learned node
  counts are logged so the traffic can be attributed.

### 🔋 Background behaviour that pays for itself

- **Account deactivation returns with a duty cycle** that is measured to be worth it, rather than the
  earlier all-or-nothing that cost more in re-download than it saved.
- **The permanent presence-subscription set is bounded**, one-off subscriptions are released, and what
  is actually held is recorded rather than assumed.
- **The all-accounts connection stream is shared** and slowed right down in the background, instead of
  every observer opening its own poll.
- **A re-delivered trust request is confirmed once per run**, not on every re-delivery — each
  confirmation was one encrypted put per device, with an RSA sign, and profiled at 49 % of CPU samples.

### 📶 A watchdog that claims only what it can prove

- **A proxy-mode wedge is judged on evidence the proxy can actually give**, not on a probe it cannot
  answer — the old probe was unanswerable in proxy mode and drove an ~18-minute false-wedge limit cycle.
- **Recovery is scoped to the account a stuck message came from**, rather than every account.
- **No re-registration on proof that nothing is wrong.**
- **The inbound-test log cursor is fixed** — it could never show anything.
- **ICE churn is named and bounded**: every connection logs who asked, who answered and who hung up; a
  failed ICE session no longer burns a core, with transports counted to prove it.
- The **Full DHT switches are gone** from the UI, and the hexagon reads "on" when the proxy is on.
- The **data dialog shows proxy subscriptions** and no longer offers to delete its own log.

## 🎨 UI & theming

- **The About screen** carries the fork's identity: the black-and-yellow traced icon in place of the
  blue knot, yellow row icons, yellow bold underlined labels, a yellow title reading
  **About 白い熊 GNU Jami** in English and Japanese, and a yellow credit line.
- **Credits** matches: a black button with yellow text and border, a black sheet with a yellow border
  — set on the sheet container, because a `BottomSheetDialog` paints that itself and would otherwise
  leave grey around the corners — and its four section headings yellow, bold and underlined.

## 📝 Documentation

Hand-off notes for the ICE connection churn (with the wrong turns kept, not tidied away), the proxy
resting-state measurement, and an upstream report of five defects in the DHT connection/notification
path, each with pristine-source citations.

---

**Install:** `shiroikuma-jami_20260731-01+001_arm64-v8a.apk` below — arm64 only, signed, installs
alongside official Jami. Existing installs update in place.
