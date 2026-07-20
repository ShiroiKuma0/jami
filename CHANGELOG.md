# Changelog

All notable fork-specific changes to **白い熊 GNU Jami** (`shiroikuma.jami`), a downstream fork of
[GNU Jami](https://github.com/savoirfairelinux/jami-client-android). Versions are the upstream
release date-code plus a per-build `+N` tail.

## 20260706-01+12 — 2026-07-20

The self-healing connectivity release, built in one day against a live outage: links died at 10:42
with a TLS/ICE error storm, then **52 minutes of silence** — the daemon believed it was connected,
nothing outgoing looked stuck, and no recovery ran until a manual hard reset at 11:47. Root causes
found on the way: the router's UPnP/NAT-PMP service had been switched off in its configuration, and
NAT bindings expired while the CPU slept, leaving the DHT deaf with a clean log. Builds `+9`–`+11`
were interim steps of this arc (each superseded within hours) and were never released.

### The watchdog: four reactive layers (all traffic-free in steady state)
- **Error-storm monitor** — tails the app's own error log (no permissions needed) and reacts within
  seconds to a burst of ≥8 TLS-fatal/ICE-failure lines in 60 s: smart recover, hard reset on
  recurrence within 10 min. Fresh inbound evidence vetoes the trigger, so benign mass-teardowns
  can never fire it.
- **Verified-deafness clock** — a passive inbound-evidence clock (messages, receipts, presence
  announces, typing, trust requests, calls — timestamped at the daemon-callback chokepoints).
  5 minutes of silence only raises suspicion; a silent presence probe (a DHT subscription re-arm on
  the three best-presence peers per account — invisible to contacts, no message sent) must then go
  unanswered for 60 s before the smart→hard ladder runs. Presence announces are themselves
  ~10–15 min periodic, so a bare silence clock false-fired on quiet evenings; the probe gate ended
  that while allowing the limit to *drop* from 10 to 5 minutes.
- **Presence-triaged stuck messages** — an undelivered message alone is never treated as local
  fault (a recipient on an airplane had the old heuristic full-recovering every 3 minutes,
  a self-sustaining loop whose constant re-registration also prevented delivery from settling).
  Each stuck message is triaged by the recipient's live presence: connected-yet-not-ACKing is
  damning; offline is benign; ambiguous merely halves the deafness limit. Two or more distinct
  non-offline stuck recipients with inbound also quiet count as breadth evidence. A fingerprint
  ledger makes every wedge recovery once-only: unchanged evidence gets a single hard escalation,
  then a 60-minute stand-down with one notification.
- **Restricted-network mode** — after a recovery visibly fails, a 2-second egress diagnosis (raw
  STUN Binding over UDP plus a bare TCP connect to the TURN server — zero Jami traffic) classifies
  the hostile-WiFi trap: UDP blocked while TCP works. There the normal recovery (dropping to the
  full DHT — which is UDP) would be exactly wrong, so the mode pins the DHT proxy ON, degrades
  full recovery to re-register-only, lets TURN-TCP relays carry traffic, and says so honestly
  (「制限ネットワーク検出（UDP遮断）→ プロキシ固定ON・中継モード」). Exit requires two
  consecutive UDP passes at 2-minute re-tests; the mode survives app restarts.

### Forensics & visibility
Every trigger appends a full incident record (what fired, why, and the recent daemon error lines)
to `Android/data/shiroikuma.jami/files/watchdog-incidents.log` and posts a Japanese notification
(「白い熊 Jami 自動回復」) — outages become visible and diagnosable after the fact without ever
watching the phone.

### Fixes
- **UPnP circuit breaker** (per-build contrib patch `patches/dhtnet-upnp-circuit-breaker.patch`) —
  with UPnP enabled but the router's UPnP service dead, every single ICE transport setup blocked
  for up to 4 s waiting for a port mapping that could never be granted. Mapping requests are now
  skipped outright when no valid IGD is present, and six consecutive mapping failures open a
  5-minute breaker (half-open probe after cooldown; any success closes it). UPnP can stay enabled
  everywhere, forever: a healthy router grants mappings, a broken one costs nothing.

## 20260706-01+8 — 2026-07-19

An upstream sync plus a critical correction to the `+6` epoll fix. Build `+7` (the bare upstream
sync, still carrying the v1 epoll patch) was delivered for testing but never released; `+8`
supersedes it.

### Upstream sync
Rebased onto `savoirfairelinux/jami-client-android` master `b4db1f447` (same `20260706-01` base,
three new commits):
- **Conference call notifications un-stalled** — `placeCallObservable()` never resolved
  `Call.systemConnection` when bypassing Telecom, permanently blocking the call-notification
  pipeline after adding a participant to a conference; stale merged-conference notifications are
  also cleared when a conference collapses back to a single call.
- **Mute audio output during calls** — mute state is tracked in-app and silence is emitted while
  playback frames keep draining, so no stale audio builds up while muted.
- Play-store deploy tooling (fastlane) no longer re-uploads the store listing — not applicable to
  this fork's builds.

### Fixes
- **v2 of the stuck-epoll-socket eviction — live links are now untouchable** (correction to the
  `+6` patch, which turned out to destabilize established TLS/ICE links: benign unconsumed
  readable/writable epoll reports — pjsip's own "innocent cases", sporadic on perfectly healthy
  sockets — could both delay real packets by up to 500 ms (v1 had removed pjlib's 10 ms sleep cap)
  and, worse, accumulate 5 strikes and `EPOLLONESHOT`-disarm a **live** socket, silencing its
  inbound traffic entirely. The starved link then died with a TLS "non-properly terminated" error
  and reconnected, several times a minute — each cycle burning a DH/RSA handshake plus a wave of
  RSA re-decryptions of DHT values, which showed up as CPU alternating between ~15% and ~60%.)
  v2 restores pjlib's 10 ms sleep cap verbatim and counts eviction strikes **only** for unhandled
  events carrying `EPOLLERR`/`EPOLLHUP` — the level-triggered signature only dead sockets produce.
  A healthy socket can never be disarmed; a true zombie still goes quiet after ~50 ms, fully
  preserving the `+6` fix for the 12% background-CPU creep.

## 20260706-01+6 — 2026-07-19

One root-cause fix for the regression the `+4` release itself uncovered: with UPnP/NAT-PMP alive
again, background CPU crept back up to a constant ~12%. Build `+5` was an interim diagnostic
artifact (it only added the `profileable` manifest flag used to hunt the bug) and was never
released; `+6` supersedes it.

### Fixes
- **Background-CPU creep: ICE poll threads spinning on dead sockets** (pjlib bug, fixed via the
  fork's per-build contrib patch `patches/pjproject-evict-stuck-epoll-sockets.patch`). Every
  dhtnet `IceTransport` runs its own event-loop thread around `pj_ioqueue_poll()`. When one of a
  transport's sockets dies — a failed TCP connect, or a candidate socket whose network interface
  vanished in a WiFi↔mobile flip — level-triggered epoll re-reports `EPOLLERR`/`EPOLLHUP` on every
  poll, forever, and pjnath has no pending operation left that could consume the event. pjlib's
  anti-busy-loop backoff for exactly this "returned events, none consumed" case is capped at
  **10 ms**, so each affected transport woke ~80–100 times per second (~1.2% CPU each) for as long
  as it lived — and long-lived links accumulate one such spinner per connectivity event, which is
  why CPU grew stepwise from ~1% to 12%+ over hours. Diagnosed on-device with `simpleperf` DWARF
  call graphs (the smoking gun: `pj_ioqueue_poll → pj_thread_sleep`, the only sleep site in
  `ioqueue_epoll.c`, plus per-thread birth times showing batches born at connectivity events).
  Fixed in two layers:
  1. the unconsumed-event backoff now sleeps the caller's full remaining poll budget instead of
     10 ms (real traffic never takes this branch, so latency is unaffected);
  2. after 5 consecutive unconsumed reports, a new `ioqueue_note_unhandled()` disarms the
     offending fd with `EPOLLONESHOT` — the kernel delivers one final report and goes quiet.
     Any consumed event resets the strike counter, and any later posted operation re-arms
     level-triggered reporting via `epoll_ctl(MOD)`, so sockets that come back to life recover
     automatically. Closing keys, ONESHOT-configured queues and `EPOLLEXCLUSIVE` registrations
     (where `MOD` is invalid) are left alone.
  Measured result: background CPU **~12% → ~3%** (the pre-`+4` baseline), no 80 Hz poller threads.

### Packaging
- **The app is now `<profileable android:shell="true"/>`** — the standard zero-cost production
  flag that lets `simpleperf` attach from an adb shell. It is what made this diagnosis possible
  and stays in for future ones; it does not weaken release optimizations, signing, or security.

## 20260706-01+4 — 2026-07-17

Two root-cause fixes, both found by on-device tracing on the Mate XT after the app sat at a
sustained ~25% CPU while fully backgrounded. Builds `+2`/`+3` were interim artifacts of the fix
cycle (`+3` was accidentally linked against the unpatched dhtnet library) and were never released;
`+4` supersedes them.

### Fixes
- **Idle-CPU burn: leaked infinite spinner animators** (upstream bug, fixed in the fork's app
  source). `SwitchButton.startImageAnimation()` created a brand-new infinite `ObjectAnimator` on
  every call and nothing ever cancelled one — `showImage(false)` only hid the drawable, and the
  animators even survive the account-summary screen's destruction. Since the fragment calls it on
  *every* account update while an account is in the "trying" registration state, each reconnect
  flap added another immortal animator demanding 60 Hz Choreographer callbacks forever. Traced as
  surfaceflinger waking the main thread exactly every 16.7 ms with the app invisible (~18-21% CPU
  on the main thread alone). Now a single reusable member animator: idempotent start, cancelled on
  hide, window detach and window-invisible, restarted when the spinner becomes visible again.
  Measured result: idle CPU ~24-27% → **0-1%**, process threads 300 → ~120, main-thread wake-ups
  60/s → ~7/s.
- **UPnP and NAT-PMP silently dead whenever mobile data was up** (dhtnet bug, still present in
  upstream dhtnet master as of 2026-07-17). dhtnet's `ip_utils::getHostName()` (Linux
  `SIOCGIFCONF` branch, `MIN_INTERFACE = 1`) stops at the *first* up non-loopback IPv4 interface —
  in kernel ifindex order that is the cellular `rmnet0` (`<NOARP,UP>`, no multicast, carrier-grade
  NAT `/32`), never `wlan0`. libupnp then failed every init with `UPNP_E_INVALID_INTERFACE` (the
  repeating `E/pupnp.cpp` logcat line, one per ~2 min retry), and NAT-PMP derived and queried a
  phantom carrier "gateway" (`100.80.246.1`). Net effect: **no router port mapping at all while
  mobile data was on** — ICE ran without server-reflexive candidates from either protocol, leaning
  entirely on STUN/TURN. The fix teaches the interface scan to prefer `BROADCAST+MULTICAST`,
  non-`POINTOPOINT` interfaces (Wi-Fi/Ethernet), falling back to the old first-match when no such
  interface exists. Verified on-device: PUPnP initializes cleanly, and NAT-PMP now holds a live
  session with the real router (`192.168.1.73 → 192.168.1.1:5351`).

### Packaging / build
- The dhtnet fix ships as **`patches/dhtnet-prefer-lan-interface.patch`** (tracked in this repo)
  and is applied to the daemon's contrib at build time, gnutls-style — an `$(APPLY)` line seeded
  into `daemon/contrib/src/dhtnet/rules.mak` for fresh extractions plus a direct patch of the
  already-extracted tree — with the `daemon/` submodule left pristine and pinned to upstream. The
  build docs now also record that contrib must be rebuilt via `make .dhtnet` directly after
  patching (the Gradle daemon build silently ignores a removed contrib stamp).

## 20260706-01+1 — 2026-07-16

A pure upstream sync: rebased onto upstream **20260706-01** (versionCode 500, new daemon). No
fork-side changes — every feature is carried forward unchanged.

### Upstream merge (20260702-01 → 20260706-01, savoirfairelinux/jami-client-android)
- **Android 16 local-network permission** — the app now declares and requests the new
  `ACCESS_LOCAL_NETWORK` permission with an explanatory dialog, and points to the device settings
  when access has been blocked. Without the grant, calls to devices on the local network may be
  routed through a relay with lower quality instead of connecting directly.
- **Daemon update** (`0448032a` → `20a033c9`): **opendht** contrib update; the **declined-request
  sync ping-pong** is stopped (a declined conversation request no longer bounces endlessly between
  a user's devices); two message-edit fixes (the first message in an edit history no longer gets
  the wrong body overwrite, and body overwrites are cleared correctly); conference video bitrate is
  now sized from the mixer; jamid's CMake gains VIDEO / SHM / PLUGINS build options.
- **Dependency bumps** — Hilt 2.60, core-ktx 1.19.0, lifecycle 2.11.0, protobuf 4.35.1,
  okhttp 5.4.0.
- Translation churn absorbed (Lithuanian trimmed, Romanian updated).

## 20260702-01+2 — 2026-07-06

A maintenance sync: rebased onto the upstream tip to pick up one targeted fix. No fork-side changes —
every feature below (see `+1`) is carried forward unchanged; the daemon did not move.

### Upstream merge (savoirfairelinux/jami-client-android → `25c01ae74`)
- **Device-link race fix** — linking a new device to an account could stall permanently on the
  *second* attempt: two JNI callbacks (`deviceAuthStateChanged` / `addDeviceStateChanged`) bypassed
  the daemon-event executor queue, so the `TOKEN_AVAILABLE` event could fire before the import
  ViewModel subscribed and be silently dropped, leaving the import flow stuck. Both callbacks are
  now dispatched through the executor, and an unexpected state transition logs instead of killing
  the RxJava subscription chain.

## 20260702-01+1 — 2026-07-03

Rebased onto upstream **20260702-01** (versionCode 499, new daemon), and rolled up everything built
since `+83`: the swipeable media viewer with hide/restore, the protected-contacts companion
integration (now with a read-back query), the black/yellow long-press menu with in-app message
forwarding, the location-sharing status tool, and settable call-event pill colours.

### Upstream merge (20260619-01 → 20260702-01, savoirfairelinux/jami-client-android)
- **Android 14+ call-notification crash fix** — the call foreground service now starts only once the
  Telecom connection reaches a presentable state, instead of crashing when the notification fired
  too early.
- **Daemon update** (`ae7bf02a` → `0448032a`): two **dhtnet** updates, the account manager now sets
  up its DHT **before** opening UPnP (startup ordering), account config is saved directly to file,
  and the video mixer uses area-averaging cell scaling.
- **Gradle wrapper 9.5 → 9.6**; translation and CI churn absorbed.

### Media viewer: suppress/restore, reliable swipe, yellow action bar
- **Swipe sideways** through the pictures and videos of the same direction; **swipe down** hides the
  current item from the swipe set (persisted per message), **swipe up** restores it — both flash a
  confirmation. An item opened directly from the chat is always shown, with a **"Suppressed"
  badge**, so it can be restored from where it lives.
- **A view-suppressed button** browses only hidden media of the current type (landing on the most
  recently hidden item), so nothing is ever lost; tapping it again returns to the normal set.
- **Gesture arbitration fixed** — a near-vertical swipe was being stolen by the pager as a page
  change; drags more vertical than horizontal are now locked to hide/restore, and detection is
  distance-based so a slow deliberate swipe works like a flick. Zoomed images still pan.
- The white "Share" pill is replaced by **plain yellow icons**: share and download bottom-right,
  open and view-suppressed bottom-left.

### Protected contacts (companion integration)
- A companion app can mark contacts **protected** over a local broadcast
  (`SET_PROTECTED_CONTACTS`: '|'-separated registered names or ring ids, replace/add/remove, with a
  `jami-cmd://protect` deep link as a secondary path). Names are also resolved to ring ids via the
  name service so a protected sender matches even on their first message; entries are never logged.
- A message from a protected sender posts a **vague, contentless notification** on a silent secret
  channel — local-only, no name, text, avatar or count on the lock-screen / Wear / Android Auto —
  carrying a private marker extra the companion keys on. All other senders are unchanged.
- The vague notification's **title/body are companion-settable** (`protected_title` /
  `protected_body`; absent extras restore the defaults).
- **New in this release: `GET_PROTECTED_CONTACTS`** — an ordered-broadcast read-back that answers
  `RESULT_OK` with the stored list ('|'-separated, lowercase, sorted) or the literal `EMPTY`,
  so the companion can verify what is actually stored.

### Conversation long-press menu: black/yellow, in-app forward, pinned
- The message long-press menu is now **black with a yellow border and yellow text/icons**, settable
  as Text / Fill / Border under *UI fonts & colours → "Message menu"*.
- **「白い熊 Jami」で共有** (other locales: *白い熊 Jami share*) forwards the message through Jami's
  **in-app** share picker instead of the system chooser: a **"from account" selector row** (avatar +
  name, opening the avatar account list) repopulates the conversation list per account; picking a
  chat opens it with the text staged in the composer (files send on open). A forwarded share also
  switches the active account to the target's account.
- **The menu no longer "dances"** — it is shown at fixed coordinates captured at long-press
  (`showAtLocation`), so background re-layouts (reconnection, presence, status re-binds) can no
  longer drag it around, and the reactions subscription no longer forces reposition passes.

### Location sharing
- **"Location sharing status"** (overflow menu): a read-only black/yellow dialog listing which
  conversations are genuinely sharing (with **"Stop all sharing"**), or explaining that a service
  shown "running" with no notification is just a harmless idle binding — it never starts the
  service to answer.
- **The phantom "running" service is gone** — an open conversation no longer holds the
  location-service bind for its lifetime; the one-shot check unbinds immediately.

### Theming
- **Call-event pills** (started/missed call rows) are settable — black fill with green text +
  border by default — under *UI fonts & colours → "Call events"*.
- **Popup menus** (overflow etc.) carry a yellow border, and the overflow icon itself is yellow.

## 20260619-01+83 — 2026-06-30

Synced onto the latest upstream tip (`576f22274`, same `20260619-01` base), and added a per-contact
snapshot cache, a fuller dialog theme, and a self-describing probe message.

### Upstream merge (savoirfairelinux/jami-client-android → 576f22274)
- **Android 15+ boot-crash fix** — boot-time sync is deferred to a JobScheduler job instead of starting
  a foreground service from the `BOOT_COMPLETED` receiver (which threw
  `ForegroundServiceStartNotAllowedException` with "run on startup" enabled).
- **Video reliability** — prevents a stale `codecStarted` after an encoder fallback, avoiding a video freeze.
- **Video resolution applies without an app restart** — the camera re-registers when the resolution
  preference changes (phone + Android TV).
- Translation bumps.

### Contact live monitor
- **Exit→reopen snapshot cache** — after a Message ping, leaving and reopening the monitor no longer loses
  the picture: it re-shows the last attempt's channels under "↻ last attempt — Ns ago (HH:MM:SS)" with a
  memo (they reconnect soon if reachable, else likely offline; tap Message ping, or the ⚡ lightning if
  it's your own link). Live data replaces it; snapshots older than 90 s aren't shown.
- **Header pill** now shows the title big with a two-line "● live — re-checks every 2 s" note beside it.
- **The ⌁ probe message is two lines** — "Connection refresh:" then "⌁ HH:mm:ss" — so it's self-describing
  in the conversation.

### Theming
- **App-wide dialogs** are now pure black with yellow body text, **yellow-outlined pill buttons**, and a
  yellow border — so confirmation dialogs (e.g. Delete contact) match the rest of the theme.
- **Message-edit screen** themed: a black editor card with the compose-input border and a transparent
  reply group.

## 20260619-01+77 — 2026-06-29

A focused follow-up to the connectivity layer: the per-contact monitor's verdict is now grounded in the
delivery receipt (no more premature "unreachable"), the help page is restyled and fully settable, and
"Atomic reset" is renamed to "Hard reset". Still on upstream base **20260619-01** (versionCode 498).

### Contact live monitor
- **Honest, receipt-based verdict.** The dialog reads the ⌁ ping's **delivery receipt** as ground
  truth: "delivering…" → "delivered ✓", softening to "may be offline" only after a long wait — it never
  declares "unreachable" from channel state or a short timeout (that earlier verdict was a false
  negative and was removed).
- **Always shows what's happening.** A contact with no open channel reads an explicit state — e.g.
  "watching the DHT… opens the moment they're reachable" for an offline contact — instead of a blank.
- **Richer connected rows.** Each live channel shows how long it has been up and its channel names,
  alongside the device id (tap to copy).
- **Streamlined buttons.** The account-wide **Recover** button is gone (recovery belongs to the ⚡
  lightning); the dialog is **Message ping ⌁** (left) + **Close** (right), with a "● re-checks every
  2 s" live footer.
- **Escalation, not blind retries.** If your last message is still undelivered, Message ping no longer
  just fires again — it explains the daemon is already retrying and routes you to the ⚡ lightning,
  making clear that the lightning recovers your **whole account (all chats)**, with a "Send another
  anyway" fallback.

### Connectivity help page (long-press the account dot)
- **Restyled** for clarity: blue headings, yellow body, bold gestures, tighter structure.
- **Button names render as pills** that look like the real Contact-live-monitor buttons, so the guide
  matches the UI.
- **Fully settable** under *UI fonts & colours → "Connectivity help page"*: headings (colour + font &
  size), body text (colour + font & size), and button-pill text / border / fill colours. Headings
  default to the vivid `#0000FF` reachable blue.

### Naming
- **"Atomic reset" → "Hard reset"** everywhere — the lightning long-press, the help page, the recovery
  log, and the README.

## 20260619-01+67 — 2026-06-28

The connectivity study became a full self-healing connectivity layer: an automatic recovery watchdog,
a two-tier manual recover, honest real-time presence, and a focused per-contact connection monitor —
all on-device-tested across `+30`…`+67`. Still on upstream base **20260619-01** (versionCode 498).

### Connectivity default corrected
- The earlier "DHT proxy on" default proved wrong in practice — proxy-on routes every connection-setup
  through one link that, when it wedges, strands all external delivery (in and out) while the UI still
  shows "connected". New accounts now default to the reliable config: **DHT proxy OFF, UPnP + TURN on,
  local discovery off**.

### Self-healing online-recovery watchdog
- A background watchdog detects the wedge — nothing connected for a while, **or** an outgoing message
  that never confirms (`NOT SYNCING`, which also catches group swarms a raw connection table can't see)
  — and recovers automatically by dropping to the full DHT and re-registering.
- **Charging-aware:** while charging it holds the proxy off (full DHT, maximum reliability — free when
  plugged in) and manages it normally on battery.
- A rolling, timestamped recovery **log** records what it did and for how long; an optional test-swarm
  **canary** can drive detection actively.

### Smart vs atomic recover (the ⚡ lightning)
- **Tap = smart recover:** re-register on the current proxy when links are healthy; drop to the full
  DHT only when nothing is connected — so clearing one stuck contact no longer needlessly disables the
  proxy (and its battery savings) for everyone.
- **Long-press = atomic reset:** force full DHT + re-register unconditionally — the big hammer.
- The lightning glows **blue while a recover is settling**, yellow when idle.

### Per-contact live connection monitor
- Tap any contact's (or group's) **avatar** in the chat list — or a no-connection row in the full
  monitor — to open a focused live monitor: the avatar + resolved name, then **each member's channels**
  colour-coded like the monitor (connecting → negotiating ICE → securing TLS → connected) with device
  IDs (tap to copy).
- **Message ping (⌁):** sends a tiny probe that forces a direct channel to open (the only thing that
  does — a typing nudge isn't enough), so you can watch the link come up.
- **Recover** right inside the dialog, plus a live verdict: a ping that never connects concludes
  **"offline or unreachable"** instead of sitting on a misleading "reachable".

### Honest real-time presence
- The chat-list dot now means exactly what the daemon reports: **yellow = a live P2P connection right
  now, blue = announced online but no open channel, red = offline.**
- **Delivery-aware:** a conversation whose last outgoing message has been stuck more than a few seconds
  no longer reads connected — so "delivered but red" / "yellow but undelivered" mismatches are gone.
  Group dots are wired to the same status.

### Top-bar icons + in-app help
- **Sync ↻** — tap: the connection/recovery log; long-press: **pin DHT proxy off** (full DHT, maximum
  reliability; Sync turns blue while pinned).
- **Account dot ●** — tap: the connection-status dialog; long-press: a new **connectivity help page**
  that draws every icon, explains its tap/long-press, and recommends a fix for each common situation.
- **Lightning ⚡** — tap: smart recover; long-press: atomic reset.
- Icons are tightly grouped (the lightning is a sized action view), and the connection-status dialog
  gained "Sync now" + "Recovery" controls.

### Fixes
- **Tapping a group avatar no longer crashes** — the per-contact monitor iterates members instead of
  the single-contact accessor that throws for group swarms.
- The connection-status dialog and the per-contact monitor no longer assume a 1:1 conversation shape.

## 20260619-01+29 — 2026-06-26

A measured connectivity study turned into a set of self-healing connection-health features. Still on
upstream base **20260619-01** (versionCode 498); `+10`…`+28` were on-device test iterations and `+29`
is the shipped result.

### Connectivity: measured, then defaulted
- Ran a controlled on-device A/B test of every connectivity setting (UPnP, DHT proxy, local peer
  discovery, TURN), measuring delivery success, latency and idle CPU. Result: **"Use DHT proxy" is the
  only setting that materially matters** — proxy on ≈ 0–2% idle CPU with reliable external delivery
  (including an instant push-wake on an incoming external message), proxy off ≈ 10–12% CPU; UPnP, local
  peer discovery and TURN had no measurable effect on delivery. The one cost of proxy-on: a message
  between your **own same-device accounts** can strand until nudged.
- New accounts now default to **UPnP + TURN + local peer discovery + DHT proxy all ON**.

### “Sync now” — clear stranded inter-account messages
- A new **sync icon** in the chat-list top bar (left of the connection dot). Under DHT-proxy mode a
  message to one of your own same-device accounts (or a same-device group swarm) can sit undelivered;
  tapping Sync briefly drops every account to the full-DHT path and back, flushing those messages. The
  toggle is **sequential and spaced** — doing it all at once / too fast crashed the daemon, so it now
  mirrors the safe manual sequence.

### Honest connection health (dot + monitor + status dialog)
- The “needs attention” alarm is now driven by the **actual undelivered message** (an outgoing text
  whose delivery never confirms) in same-device conversations — which also catches **group swarms** the
  connection table can’t even see. A genuinely stuck message turns the account red and rings the dot;
  normal connecting and idle never false-alarm.
- **Account dot ring:** red when a message is stuck, **blue while an account is still connecting**.
- **Connection monitor:** problem accounts sort to the top; a red **“⚠ message not delivered → \<chat\>”**
  row (with the account avatar) names exactly what is stuck; “connecting” reads blue and is never itself
  a problem; your other same-device accounts show **“reachable”** instead of a false “offline”; all
  accounts are **folded by default**; the fold indicator is a filled ▶/▼ triangle whose **size is
  settable** in UI fonts & colours.
- **Connection-status dialog (tap the dot):** now a **3-level foldable** view mirroring the monitor —
  account → contact (with avatar) → individual device connections — with the full-page **Monitor** button
  kept.

### Fixes
- The **Diagnostic logs** screen no longer auto-pops the “Last crash report” sheet on every visit (it is
  still reachable via the bug icon).

## 20260619-01+9 — 2026-06-25

Connectivity, push and a rebuilt connection monitor, plus app-wide dialog theming. Still on upstream
base **20260619-01** (versionCode 498). The `+2`…`+8` builds were on-device test iterations of this
work; `+9` is the shipped result.

### Connectivity & push
- Ship the **`withUnifiedPush` flavor** (Google-free push): paired with a UnifiedPush distributor
  (e.g. ntfy) and the DHT proxy, backgrounded accounts deactivate and wake on a push, dropping idle
  CPU to near zero with **no Google/Firebase dependency**. Retires the always-awake `noPush` flavor,
  which kept four same-device accounts at 10–35% idle and overheated the phone.
- Diagnosed (no code change needed): the daemon's frequent `Broken pipe` / "TLS non-properly
  terminated" ERROR logs are **normal swarm-conversation sync teardown**, not connectivity failures;
  local peer discovery (mDNS) only helps on a multi-device LAN and is otherwise unnecessary.

### Connection monitor (rebuilt)
- The monitor and the account-dot **status dialog now cover all accounts**, not just the current one.
- **Honest health model:** an account is **healthy** (yellow) when registered and actually syncing,
  **connecting** (blue) while it establishes, and red only when genuinely **offline** (unregistered)
  or **not-syncing** (registered but isolated > 2.5 min). Short-lived sync connections are shown
  neutral and **never flagged red** — the monitor no longer cries wolf over normal churn, and the dot
  alarm ring fires only for a real, otherwise-invisible isolation.
- **Per-account avatars** on foldable account headers, large fold chevrons, indented sub-rows, and a
  failing-first layout; tap any connection for **device detail** (device / contact IDs, status, copy)
  with a **per-account Reconnect**; an **icon & colour legend** behind a “?”.
- Opening the monitor from the status dialog returns to that dialog on Back.

### Theme & dialogs
- **Every dialog is black/yellow app-wide** now — the app theme routes `alertDialogTheme` /
  `materialAlertDialogTheme` through the fork's dialog style, so even Android's own **preference
  dialogs** (e.g. the DHT-proxy-address editor) match; hand-built dialogs share a `DialogTheme` helper
  (black fill, yellow rounded border, yellow buttons).

### UI fonts & colours
- The screen is renamed **白い熊 GNU Jami UI** and gains an **app-language override** (run the UI in a
  chosen language independent of the phone locale).
- Five new **settable connection-monitor colour roles** — connected/healthy (defaults to `#FFFF00`),
  in-progress/connecting (blue), problem (red) — recolourable like every other role.

### Build / infra
- The **`jami-build` skill now builds `withUnifiedPush`** as the canonical flavor (output under
  `app/build/outputs/apk/withUnifiedPush/release/`).
- Version tail advanced to **20260619-01+9**.

## 20260619-01+1 — 2026-06-21

First public release of the fork, built on upstream GNU Jami **20260619-01** (versionCode 498) with
the full customization stack below. Installs side-by-side with the official `cx.ring`.

### Install identity
- Repackaged as **`shiroikuma.jami`** with the label **白い熊 GNU Jami**, so it installs alongside
  official Jami (`namespace` stays `cx.ring` for R/BuildConfig; only the `applicationId` differs).
- FileProvider authority derived from `${applicationId}` — fixes `INSTALL_FAILED_CONFLICTING_PROVIDER`
  against official Jami and keeps file-sharing working.
- `JAMI_DATADIR` repointed to the real install dir so the daemon's data path matches.
- Black/yellow knot **launcher icon** (yellow edge-trace).

### Theme — yellow-on-black
- Black backgrounds with `#FFFF00` foreground across the chat list, chat text (dates grey), toolbars,
  conversation bubbles (black fill + yellow border), the compose and search bars, the start-conversation
  button, and a yellow border around unread conversation rows.
- Black search bar with yellow hint/text (elevation overlay disabled); file messages get a yellow
  paperclip and black bubble + black icon square, both yellow-bordered; black/yellow link-preview card.
- Compose-bar thumbs-up shows a yellow outlined-thumb icon while still sending the emoji (display
  decoupled from payload); account-selection dialog black with yellow border, text and add-account icon.
- Themed account badges, fallback (generated) avatars and the home top bar; chat-list presence-dot
  colours; black/yellow settings screen (including switches); themed conversation long-press bottom
  sheet and the expanded home search view.

### UI fonts & colours
- New **UI fonts & colours** screen: per-element **font family / weight / size** for chat text,
  conversation title, chat-list title / preview / date, message time, search hint and settings text,
  each defaulting to the current value.
- Per-element **runtime colours** (text, fill, border, tint) for message bubbles, link-preview and
  file cards, the account badge, status icons, the online/offline icon, the unread-row border, presence
  dots, and every themed text surface — "unset" falls back to the current palette so nothing changes
  until you pick.
- **RGBA-slider colour picker** with a live preview swatch and a two-way `#AARRGGBB` hex field.
- External font import (`.ttf` / `.otf`) from storage via SAF, stored in app files.
- Grouped, inline fonts-settings layout with live preview; live refresh on leaving the screen; reachable
  from the chat-list overflow and Settings → Appearance.
- Line-driven sizing of list rows, avatars and the message status icon; configurable message-status-icon
  height kept beside the bubble; resizable account dot with reliable presence swap.

### Connectivity
- Connectivity resilience with on-device connection diagnostics (DHT reconnect + a diagnostic indicator).
- One-tap **recover Offline / disabled accounts** reconnect action.

### Automation
- Token-gated, exported **send / call / open** automation intents for external scripts.

### Names
- Registered-name resolution: retry stuck lookups, plus a **“Look up name”** action.

### UI & navigation
- Dual-pane **split-view toggle** (Settings → Appearance + chat-list overflow) to force single-pane on
  wide/foldable screens.
- Settable styled **“flash” messages** (toasts).
- Account **online/offline toggle** icon in the chat-list top bar.

### Build / infra
- AGP-9 built-in-Kotlin build-config migration (`android.builtInKotlin=true`, `android.newDsl=false`),
  with libjamiclient warning suppression and quieter Kotlin build logs.
- Per-build `+N` version tail tracked in an in-repo counter (`jami-android/shiroikuma-build.txt`), so
  every rebuild is an upgrade.
- Re-applied gnutls `--without-brotli --without-zstd` contrib fix at build time (not committed).
- Agent config: `CLAUDE.md` + `.claude/skills/` (`jami-build`, `upstream-new-version`,
  `publish-version`); gitignored `local.properties` SDK pin documented; upstream `FUNDING.yml` removed.

### Fixes
- Fully-qualify `RtlGridLayoutManager` so it resolves under `shiroikuma.jami` (avoids a runtime
  ClassNotFound / lintVital `RelativeClassResolution` failure caused by `applicationId ≠ namespace`).
- Outgoing file card no longer overflows when the filename is long.
- Online/offline menu icon now switches shape correctly on toggle.
