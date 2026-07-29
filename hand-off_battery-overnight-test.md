# Hand-off — measuring the proxy resting state

**Opened:** 2026-07-29. **Status:** Stage 1 built, pushed, installed, and confirmed working.
**Waiting on:** 白い熊 calls the measurement window when it suits him — **not overnight** (he always
charges overnight, and charging invalidates the test). Then we decide whether to build Stage 2.
**Shipped as:** `origin/custom` `f9025c1a9`, APK `shiroikuma-jami_20260717-01+143_arm64-v8a.apk`,
installed on the Mate XT at 2026-07-29 09:48:40.

> Nothing here depends on the chat that produced it. Everything needed to finish the job — the
> baseline, the anchor snapshot, the commands, the decision criteria, the open questions and the
> unbuilt Stage 2 — is in this file.

---

## 1. Why this exists

A battery monitor 白い熊 is developing in another chat flagged `shiroikuma.jami` at ~153 pkt/s while
backgrounded, with deep Doze never engaging once.

**Baseline, measured before the change** — `dumpsys batterystats --charged shiroikuma.jami`, uid
10954, window 2026-07-29 06:59:38 → 08:58:45 (1 h 59 m 7 s), four accounts, full DHT, backgrounded:

| Metric | Value |
|---|---|
| Jami WiFi packets | 502 011 rx + 533 929 tx = **145 pkt/s** |
| Whole-device WiFi packets | 1 244 286 → Jami was **83 % of everything** |
| Jami WiFi bytes | 84.2 MB rx + 102.2 MB tx ≈ **94 MB/h**, mean datagram ~180 B |
| Jami CPU | 9 m 32 s usr + **13 m 0 s krn** = 19 % of one core, continuous |
| **WiFi sleep time** | **289 ms out of 1 h 59 m (0.004 %)** |
| Jami partial wakelocks | **2.57 s total** |

Two conclusions: it was never a wakelock problem, it was a packet-rate problem; and the packets are
tiny, so this is DHT chatter, not data transfer.

**Cause.** `UiPrefs.isFullDhtMode()` defaulted to `true`, so `proxy_server` stayed empty in
`JamiAccount::initDhtConfig` and each of the four accounts ran a real opendht UDP node. Push is
bypassed by design in that mode (`ConnectionWatchdog.kt`: `if (UiPrefs.isFullDhtMode(c)) return //
full DHT does not ride push`), so a working FCM leg bought nothing — the daemon had to stay awake to
hear anything. The floor is structural: every DHT `listen` is re-sent to `LISTEN_NODES = 4` nodes
every `LISTEN_EXPIRE_TIME = 30 s` (opendht `dht.h`), and that 30 s is also the CGNAT keepalive, so it
cannot simply be lengthened. On top of our own listens we answered strangers' queries — rx ≈ tx in
every logged window means the phone was *serving* the DHT, not just using it.

**白い熊's decision (2026-07-29):** *"we don't have to stay on Full DHT — we can be fully on DHT
Proxy, and only switch to Full DHT on connection issues; that's what we programmed most of the
dynamic-switching functionality for."* Priorities unchanged: **#1 connection permanence and
stability, #2 battery.** Proxy is chosen because the escalation machinery makes it safe, not because
battery outranks reliability.

---

## 2. What shipped in `+143`

Commit `f9025c1a9`, *battery: rest on DHT proxy + push, escalate to full DHT only on trouble*,
10 files, +455/−259. Daemon gitlink stayed pinned to upstream (`f0e2a6a67`), no generated SWIG
bindings committed — both verified after the push.

**a. The resting mode flips.** `UiPrefs.isFullDhtMode()` and `isFullDhtWhileCharging()` now default
to `false`, with a one-shot migration keyed `proxy_default_20260729` that also rewrites values an
install had already stored. Both switches remain in Settings → UI fonts & colours → Online recovery;
only defaults and stored values moved.

**b. The machinery that lets the phone sleep now exists in the shipped flavor.** This was the
finding that shaped the work: `deactivateProxyAccountsForBackground()` /
`restoreProxyAccountsAfterBackground()` (`AccountService.kt`), with push-grace windows, call and
foreground-service exemptions, an episode cap and a process lifecycle observer, lived **only** in
`JamiApplicationFirebase`. The shipped flavor is `withUnifiedPush`, whose application class had none
of it — so four accounts stayed fully active for the life of the process. It is also a no-op for
non-proxy accounts by construction, so it could never have helped in full-DHT mode anyway. All of it
moved into the abstract `JamiApplication` in `main`, registered from the base `onCreate`.

**c. Shared push classification.** `cx.ring.services.PushWakeupClassifier` (new file `PushWakeup.kt`)
holds the `pt`-field classification that used to live inside the withFirebase FCM service. Both of
`withUnifiedPush`'s arrival paths — `onMessage()` for UnifiedPush and `onMessageReceived()` for FCM —
now classify and call `onBackgroundPushReceived(...)` *before* the daemon is handed the payload.
**It consumes a per-value-id dedupe cache, so it must be called exactly once per push** — the FCM
service deliberately keeps its coarse substring test for the foreground-service decision only.

**d. Guard: never sleep into deafness.** `ConnectionWatchdog.backgroundSleepSafe(c)` requires
*positive* proof the push leg delivers — a real push seen by this process, or a passed self-test —
and refuses while `noPushAdaptive`, `pushLegDown == true`, `isRestrictedNet`, or a settling recovery
is in play. On FCM there is no end-to-end self-test (the phone cannot POST to its own token), so a
real push arrival is the only proof available and the accounts stay awake until one lands.

**e. Guard: deactivated is not deaf.** A sleeping account is unregistered and silent by design.
Without this the watchdog would read it as a total outage — `heuristicTick`'s 0-registered branch
would fire `fullRecover` once a minute. The watchdog now stands its detectors down while every
account is asleep and skips deactivated accounts in `perAccountTick` / `verifyStuckPeers`.
`AccountService.isBackgroundDeactivated()` verifies against the account's **actual** state, not just
the restore ledger, because `setAccountsActive()` reactivates proxy accounts on every connectivity
change and would otherwise leave the watchdog blind to a real wedge.

---

## 3. FINDINGS — the first 24 minutes (09:48 → 10:13)

### Every stage of the chain fired

From `/sdcard/Android/data/shiroikuma.jami/files/recovery-log.txt`:

```
09:48:43  migrated to the proxy resting state — full DHT is now the escalation path only
09:49:44  DHT proxy ON — wedge linger cleared (was off 0s)
09:51:17  all accounts asleep (background battery optimization) — detectors stood down …
09:53:09  push rx after 102s
09:54:18  accounts awake again — detectors resumed
09:55:18  all accounts asleep (background battery optimization) — detectors stood down …
09:56:15  push rx after 62s
09:56:18  accounts awake again — detectors resumed
09:57:18  all accounts asleep (background battery optimization) — detectors stood down …
09:58:43  push rx after 85s
10:02:47  push rx after 79s
```

At 10:13: **5 sleep cycles, 6 logged push arrivals, 0 wedges, 0 recoveries, 4/4 healthy
throughout.** The hourly log flipped to `mode=proxy/pref=proxy`.

### The one result that is already unambiguous

**WiFi sleep time went from 289 ms in two hours to 5 m 38 s.** Essentially all of that accrued in the
23 minutes since the proxy came up — roughly **a quarter of the proxy period with the radio asleep,
against 0.004 % before.**

That it happened *while moving more bytes, not fewer*, is the point. The traffic stopped being a
constant 145-per-second drizzle that pins the chip awake and became bursts on one TLS stream with
real idle gaps between them. That shape change is the mechanism by which Doze becomes reachable, and
it is already working.

### Why the rest of the numbers cannot decide it yet

Differencing the 08:58:45 baseline against 10:12:56 gives 585 014 Jami packets and 132.5 MB over
4464 s — but that window is ~51 min of full DHT plus ~23 min of proxy. Backing out the known
full-DHT rate leaves roughly **100 pkt/s and ~135 MB/h** for the proxy stretch, i.e. *more* bytes
than full DHT.

That is the expected one-off cost, not a result: establishing proxy subscriptions re-downloads the
**full value set of every key**, on four accounts at once. ~50 MB in 23 min matches the ~60 MiB
figure measured for that before. This is exactly the "discard the first window after a mode switch"
rule from the agreed measurement protocol.

---

## 4. The measurement — 白い熊 calls it

**Not overnight.** 白い熊 always charges overnight, and charging changes the picture (see §5), so an
overnight run would not measure the resting state. **One clean hour in the daytime is enough for the
conclusion** — his call, whenever it suits.

### Conditions

- Phone backgrounded, screen off, on WiFi, **off the charger**.
- No installs, no adb pushes, no manual recoveries during the window — each contaminates it.
- Start the clock at least ~15 min after the last install or mode switch, so the subscription burst
  in §3 is outside the window.

### The anchor — no `batterystats --reset` needed

A reset would wipe the battery history that 白い熊's **other chat's battery-monitor project** is
accumulating. Difference against the saved anchor instead:

**`~/tmp/jami-batterystats-anchor-20260729-1012.txt`** — full dump taken 2026-07-29 10:12:56,
`Time on battery: 3h 13m 31s 255ms`, `Start clock time: 2026-07-29-06-59-38`.

Key values from it, in case the file is ever lost:

| | at 08:58:45 (baseline, full DHT) | at 10:12:56 (anchor) |
|---|---|---|
| Time on battery | 1 h 59 m 6 s 771 ms | 3 h 13 m 31 s 255 ms |
| Jami Wi-Fi packets | 502 011 rx / 533 929 tx | 785 305 rx / 835 649 tx |
| Jami Wi-Fi bytes | 84.20 MB rx / 102.15 MB tx | 161.51 MB rx / 157.32 MB tx |
| Jami CPU | 9 m 32.34 s usr + 13 m 0.61 s krn | 16 m 9.25 s usr + 20 m 5.11 s krn |
| WiFi sleep time | 289 ms | 5 m 38.25 s |
| Device Wi-Fi packets | 665 262 rx / 579 024 tx | 1 109 937 rx / 914 460 tx |
| Device Wi-Fi bytes | 289.93 MB rx / 115.29 MB tx | 580.64 MB rx / 174.68 MB tx |

**The differencing is only valid while `Start clock time` still reads `2026-07-29-06-59-38`.** A
charge cycle resets it — if it has changed, the anchor is void; take a fresh anchor, wait an hour,
and difference against that instead.

### The pull (one read-only adb batch, disconnect at the end)

```bash
adb connect 192.168.1.73:5555

adb shell 'dumpsys batterystats --charged shiroikuma.jami' > ~/tmp/jami-bs-read.txt
#   compare against ~/tmp/jami-batterystats-anchor-20260729-1012.txt:
#     "Time on battery:"  (and CHECK "Start clock time:" is unchanged)
#     the "u0a954:" block  -> "Wi-Fi network:" line, and "Proc shiroikuma.jami:" CPU
#     global "Wifi Statistics" -> "WiFi Sleep time" / "Wifi kernel active time"

adb shell 'dumpsys deviceidle | grep -E "mState=|mLightState="'
adb shell 'dumpsys deviceidle | grep -A20 "Idling history"'
adb shell 'dumpsys netstats detail --uid 10954' > ~/tmp/jami-netstats-read.txt   # 7200-s buckets, arbiter

adb shell 'tail -20 /sdcard/Android/data/shiroikuma.jami/files/data-hourly-log.txt'
adb shell 'grep -E "asleep|awake again|push rx|WEDGE|recover|DHT proxy (ON|OFF)|deaf" \
  /sdcard/Android/data/shiroikuma.jami/files/recovery-log.txt | tail -120'

adb disconnect 192.168.1.73:5555
```

`adb` must always run **unsandboxed** (`dangerouslyDisableSandbox: true`), and the wireless session
disconnected at the end of the batch — a standing session pinned the WiFi radio awake and drained
1322 mAh on 2026-07-18.

### Numbers to beat, and what passes

| Metric | Baseline (full DHT) | Stage 1 target |
|---|---|---|
| Jami packet rate | **145 pkt/s** | order of magnitude lower |
| Jami data | **94 MB/h** | well under 10 MB/h at idle |
| Jami CPU | **19 % of a core** | low single digits |
| WiFi sleep time | **0.004 %** | already ~25 %; expect more once settled |
| Deep Doze (`mState`) | never `IDLE` | `IDLE` reached at least once |

**Reliability outranks every one of those.** Stage 1 passes only if the recovery log shows no new
wedge/recover loop, no `verified WEDGE`, no `mode-switch-deaf` incident — **and** a message sent to
each of the four accounts after a long screen-off idle arrives promptly rather than on next unlock.
That hand test exercises push → restore → daemon fetch end to end and is the one that matters. If
reliability regressed, that outranks any battery figure; see §7 for rollback.

---

## 5. Question answered — should "Full DHT while charging" go back ON?

白い熊 asked this on 2026-07-29, given that he charges every night. **Recommendation: leave it OFF.**
It is a real question, not a settled one, so here is the whole reasoning.

**The case for ON is genuine.** Battery is free on the charger, so concern #2 does not apply; full
DHT is wedge-proof, with no single proxy link to fail; and overnight is the longest unattended
window, i.e. where an undetected wedge would hurt most.

**Four reasons it still loses:**

1. **"Battery is free while charging" is true of charge and false of battery health.** Sustained heat
   during charging is the dominant driver of lithium-ion capacity fade — more than cycle count.
   Full DHT means 19 % of a core plus a radio pinned awake, layered on top of charging heat, for
   ~8 hours every night. That is the worst thermal combination available, applied nightly.
2. **A daily ~50 MB re-subscription tax, paid at the worst moment.** With the toggle ON, plugging in
   flips all four accounts to full DHT and unplugging flips them back — and re-establishing proxy
   subscriptions re-downloads the full value set of every key (measured ~50 MB in 23 min, §3). So
   every morning starts with a churn window exactly when he picks the phone up.
3. **It re-introduces what we just removed, for a third of every day** — ~750 MB a night, ~23 GB a
   month. Tolerable on unmetered home WiFi, not if he ever charges on a hotspot.
4. **The robustness argument is already covered.** The whole design is "proxy rests, full DHT
   escalates". If the proxy wedges at 3 am the watchdog detects it in minutes and escalates to full
   DHT on its own. Sitting in full DHT pre-emptively buys little, while making the mode oscillate on
   a schedule unrelated to connection health.

**The contrast in plain terms.** Toggle OFF: on the charger the accounts still background-deactivate
(nothing about charging blocks it), so the phone is asleep almost all night. Toggle ON: four full
DHT nodes, awake, all night, every night.

**What would change the answer:** if the pending measurement shows the proxy is flaky at rest, the
right response is to fix that, not to paper over it eight hours a day. Revisit then, not before.

---

## 6. ⚠ Open concern — push-driven wake churn

In the first 24 minutes the phone cycled **asleep → push → awake → asleep about every two minutes**,
with pushes arriving every 60–100 s. If that cadence holds, the accounts are woken nearly as often as
they are put to sleep, and the win shrinks — possibly to nothing.

Why, in code: `PushWakeupClassifier` counts `sync` as a **message** push, and message pushes both
bypass the `NONCALL_RESTORE_COOLDOWN_MS` (3 min) gate and trigger
`hardwareService.connectivityChanged(true)`, which makes every conversation's swarm re-maintain its
buckets and re-open device channels. With four accounts and many subscriptions, `sync` traffic alone
can keep that firing.

**Caveat:** that window followed an install, exactly when a re-sync burst is expected. Judge the
cadence from the measurement window, not from §3. **Count `push rx` lines and `asleep`/`awake again`
pairs per hour — that ratio is the thing to look at.**

**Candidate fixes, in preference order — none built, do not assume:**

1. Give `sync` its own class: it should restore accounts (something did change) but **not** trigger
   `connectivityChanged(true)`, which is the expensive half.
2. Subject `sync` to the `NONCALL_RESTORE_COOLDOWN_MS` gate that other non-call pushes already get,
   leaving genuine `im-gitmessage-id` and `invite` on the fast path.
3. Raise `BACKGROUND_DEACTIVATION_DELAY_MS` / `PUSH_GRACE_MS` so a burst is absorbed by one awake
   episode instead of several — cheapest to try, but trades latency for churn.

---

## 7. Rollback

No rebuild needed. In-app: Settings → UI fonts & colours → **Online recovery** → turn **"Full DHT —
proxy off, robustness-first"** back ON. That restores the previous standing mode immediately
(`onDhtModeSwitched` re-applies it and runs a verification probe). The top-bar hexagon does the same
with one tap. Nothing else in `+143` changes behaviour while full-DHT mode is on, because
`deactivateProxyAccountsForBackground()` only touches proxy-enabled accounts.

To revert the code entirely: `git revert f9025c1a9`.

---

## 8. Stage 2 — designed, approved by 白い熊, NOT built

Held back deliberately so Stage 1's delta is attributable. **Decide whether to build it after the
measurement.** All of it targets the cost of the *escalation* path and the charger case.

**a. `client_mode` — a new daemon patch.** opendht already has `Config::client_mode` — *"node will
not be used by other nodes to store data"* (`opendht/include/opendht/callbacks.h`) — and Jami never
sets it. Peers honouring it skip `onNewNode()` for us (`network_engine.cpp`), so we leave their
routing tables and stop being queried, while our own searches, listens and puts are untouched —
**no effect on reachability or delivery**. One line in `JamiAccount::initDhtConfig`
(`daemon/src/jamidht/jamiaccount.cpp`), guard string `SK-CLIENTMODE`, shipped as
`patches/jami-dht-client-mode.patch` in the same category as `jami-publish-current-crl-only.patch` —
it patches the daemon's **own** source, so no `rules.mak` `$(APPLY)` line and no contrib rebuild. Add
it to the per-build re-apply list in `CLAUDE.md` and `.claude/skills/jami-build/SKILL.md`. Unknown
msgpack keys are ignored by older parsers (`parsed_message.h`), so it degrades gracefully — but Jami
only moved to opendht 4.0 on 2026-05-11, so much of the network will still query us. **Expect a
partial win; measure it, do not assume it.**

**b. Per-account escalation.** `applyProxyState` calls `accounts.setProxyEnabled(false)`, which flips
**all four** accounts to full DHT for a wedge on one. Use the existing per-account `setAccountProxy`
(`AccountService.kt`) and the per-account state already in `acctStates`, so only the implicated
account escalates. Keep the all-accounts path for `hardReset` and the manual Recover, which are
deliberately global.

**c. Bounded presence tracking.** `AccountService.resubscribeAccountPresence()` calls
`subscribeBuddy(..., true)` for every contact of every conversation and never releases them, unlike
upstream's UI-refCounted path (`ContactService.kt`). After the first recovery every contact of all
four accounts is tracked forever; in proxy mode each is a proxy subscription, and each re-subscribe
re-downloads the key's full value set. Bound the permanent set to what the watchdog and UI need —
contacts in recently active conversations, plus the ≤3 probe peers per account the watchdog already
picks — and release the rest on background transition. **This visibly affects presence dots for
non-recent contacts while backgrounded**: they go stale rather than wrong, and refresh on foreground
return. Flagged because it touches a surface 白い熊 built deliberately — needs his explicit
go-ahead.

**d. Cosmetic.** The hourly log now reads `mode=proxy/pref=proxy` on every normal line, because that
suffix fires whenever the pref is proxy rather than only when the actual state diverges. Emit it only
on divergence (`ConnectionWatchdog.tick()`, the `DataMeter.hourlyTick` block) so normal lines read
`proxy` and escalation lines keep today's `fullDHT/pref=proxy`.

---

## 9. Navigation

| What | Where |
|---|---|
| Mode prefs + one-shot migration | `jami-android/app/src/main/java/cx/ring/utils/UiPrefs.kt` — `isFullDhtMode`, `isFullDhtWhileCharging`, `migrateToProxyRestingState` |
| Proxy state machine, escalation, new guards | `jami-android/app/src/main/java/cx/ring/utils/ConnectionWatchdog.kt` — `applyProxyState`, `fullRecover`, `backgroundSleepSafe`, `tick` (the `asleep` gate) |
| Background deactivation | `jami-android/app/src/main/java/cx/ring/application/JamiApplication.kt` — `deactivateRunnable`, `onBackgroundPushReceived`, `scheduleBackgroundDeactivation`, `onPushTokenRegistered` |
| Account activation + ledger | `jami-android/libjamiclient/src/main/kotlin/net/jami/services/AccountService.kt` — `deactivateProxyAccountsForBackground`, `restoreProxyAccountsAfterBackground`, `isBackgroundDeactivated`, `allJamiAccountsBackgroundDeactivated` |
| Push classification | `jami-android/app/src/main/java/cx/ring/services/PushWakeup.kt` |
| Flavor wiring | `app/src/withUnifiedPush/java/cx/ring/application/JamiApplicationUnifiedPush.kt` (`handleBackgroundWakeup`), `app/src/withUnifiedPush/java/cx/ring/services/JamiFirebaseMessagingService.kt` |
| Settings rows | `jami-android/app/src/main/java/cx/ring/settings/FontsSettingsFragment.kt` — `addOnlineRecoverySection` |
| On-device logs | `/sdcard/Android/data/shiroikuma.jami/files/` — `recovery-log.txt`, `data-hourly-log.txt`, `watchdog-incidents.log` |
| Anchor snapshot | `~/tmp/jami-batterystats-anchor-20260729-1012.txt` |

Build and deliver with the canonical block in `.claude/skills/jami-build/SKILL.md` followed by
`/after-build`. Note: `/after-build` picks the newest APK in `~/tmp/`, and parallel sessions building
other apps can win that race — **push the Jami APK by explicit filename.**

Related memories: `jami-battery-overnight-test-pending` (points here), `jami-mode-choice-open` (the
decision and the 「データ計測」/「A/B計測」 triggers), `jami-online-recovery-watchdog`,
`opendht-wedge-handoff`, `jami-build-launch-detached`.
