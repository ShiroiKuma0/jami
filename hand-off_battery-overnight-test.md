# Hand-off — measuring the proxy resting state

**Opened:** 2026-07-29. **Status:** Stage 1 shipped; **measured 2026-07-29 16:53 — see §3b.**
**Result in one line:** proxy at rest costs **1.3 MiB/h**, but the background sleep/wake cycle that
shipped alongside it costs **~13 MiB per cycle** and ran ~10×/h, so the app actually burned
**133 MiB/h — no better than full DHT.** The resting mode is right; the deactivation machinery on
top of it is wrong.
**Stage 2a + 2b BUILT 2026-07-29 17:25 as `+149`** — see §8a/§8b for what changed and §10 for the
re-measurement that has to follow. **Uncommitted** (feature code); only the version counter is
pushed, as `39caeb228`.
**Waiting on:** 白い熊 to test `+149`, then a clean screen-off WiFi hour, then "Push".
**Measured build was:** `shiroikuma-jami_20260717-01+148`, whose battery/watchdog code is identical
to `f9025c1a9` / `+143` (the `+144`–`+148` commits are a chat-files feature and version bumps —
verified with `git diff --name-only f9025c1a9..HEAD`, no overlap).

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

## 3b. THE MEASUREMENT — taken 2026-07-29 16:53 ✅

The saved anchor turned out to be **void** — `Start clock time` had moved to `2026-07-29-11-58-13`
(a charge cycle at ~11:58). That was lucky rather than costly: the *new* batterystats window is
**4 h 41 m entirely on battery and entirely in proxy mode**, beginning 2 h 10 m after the install,
so the settling burst of §3 is well outside it. No differencing needed — the dump is the measurement.

### The result that decides everything

Per-hour sleep/wake cycles from `recovery-log.txt`, against the 30-minute buckets of
`data-hourly-log.txt`:

| hour | sleep cycles | push rx | data (rx+tx per 30 min) |
|---|---|---|---|
| 10:00 | 10 | 12 | 37.0 + 28.0, then 72.6 + 13.2 MiB |
| 11:00 | 9 | 14 | 40.4 + 8.4, then 74.9 + 12.2 MiB |
| 12:00 | 11 | 20 | 75.1 + 13.8, then 50.2 + 11.1 MiB |
| 13:00 | 6 | 14 | 65.4 + 13.8, then 25.0 + 6.7 MiB |
| 14:00 | 5 (last at 14:23) | 10 | 74.9 + 12.6, then 31.0 + 7.0 MiB |
| **15:00** | **0** | 17 | **470 KiB + 459 KiB, then 368 + 393 KiB** |
| **16:00** | 1 (16:46) | 13 | **209 + 209 KiB**, then 6.2 + 1.9 MiB (WiFi switch) |

**The push rate is flat across every row (10–20/h). The data rate moves 100×.** The only variable
that tracks it is the sleep/wake count.

- With cycles (10:02 → 14:38, ~4 h 34 m): **608 MiB → 133 MiB/h.**
- Without cycles (14:38 → 16:11, ~1 h 33 m): **2.06 MiB → 1.33 MiB/h.**
- Full-DHT baseline, for scale: **110–130 MiB/h.**

So **proxy with the churn is no cheaper than full DHT**, and **proxy without it is ~85× cheaper than
full DHT.** Dividing through: **~13 MiB per sleep/wake cycle**, across four accounts.

### Why — confirmed in code, not inferred

`deactivateProxyAccountsForBackground()` calls `JamiService.setAccountActive(id, false, true)` —
`shutdownConnections=true`, a full teardown. Every restore therefore rebuilds each account's proxy
listen subscriptions from scratch, and **re-establishing a proxy subscription re-downloads the full
value set of every key** (the same mechanism as the ~50 MB settling burst in §3). The cadence makes
it fire constantly: `BACKGROUND_DEACTIVATION_DELAY_MS = 5 s` and `PUSH_GRACE_MS = 30 s`
(`JamiApplication.kt`), against pushes arriving every 60–180 s. Push → wake → 30 s grace → sleep →
push 60 s later → wake. Roughly ten full teardown/rebuild rounds an hour.

The §6 concern was therefore **right about the churn and wrong about the cost centre**: the expense
is not `connectivityChanged(true)`, it is the account deactivation itself.

Note what stopped the churn — accidentally. At 14:39 the watchdog logged a wedge incident, and
`backgroundSleepSafe()` refuses to sleep while a recovery is settling. That elevated state held for
two hours, the accounts stayed registered, and the data rate fell off a cliff. **Accounts awake and
idle on proxy is the cheap state.**

### The other numbers

| Metric | Baseline (full DHT) | Measured (proxy, 4 h 41 m) | Target | Verdict |
|---|---|---|---|---|
| Jami data | 94 MB/h | **1.33 MiB/h** at rest; 133 MiB/h while churning | <10 MB/h | ✅ at rest, ❌ as shipped |
| Jami WiFi packets | 145 pkt/s | 49 051 pkt total → **2.9 pkt/s** | order lower | ✅ (50×) |
| WiFi sleep time | 0.004 % | **85.5 %** (4 h 0 m 50 s) | ~25 %+ | ✅ but contaminated |
| Jami CPU | 19 % of a core | 28 m 28 s → **10.1 %** | low single digits | ⚠ partial |
| Deep Doze | never `IDLE` | never `IDLE` (light-idle only) | reach `IDLE` | ❌ not shown |
| Wedges / recoveries | — | 3 uniform-wedge, 1 error-storm | none | ⚠ see below |

**Two honesty caveats on the battery half.** The phone was on **cellular from ~12:04 to ~16:11** and
the screen was **on for 2 h 47 m of the 4 h 41 m**, so the 85.5 % WiFi-sleep figure is inflated (the
radio was idle because traffic was on rmnet) and the CPU figure is inflated by the churn. **The data
finding is rock solid; the battery finding is directionally good but not cleanly attributable.** A
clean screen-off WiFi hour after the fix is what settles it.

**⚠ 285 MB of metered cellular data.** Jami moved 244.91 MB rx + 39.95 MB tx over mobile in this
window (`metered=true` on the ident), nearly all of it churn. That is a real bill, not just a battery
question.

### Reliability — the part that outranks battery

Four incidents: `#1` 14:39, `#3` 15:42, `#4` 15:54 all `uniform-wedge` (4/4 silent through a 75 s
probe), plus `#2` 15:34 `error-storm-uncorroborated`. **Every one of them was a false positive**, and
the guards caught them: each recovery logged *"recovering WITHOUT touching the proxy (real push Ns
ago — the proxy leg delivers)"*, and from 14:54 onward every probe logged *"4/4 silent, but a real
push landed Ns ago — receive path alive, no recover."* Nothing escalated to full DHT, and no
recover loop formed.

But the underlying defect from `jami-proxy-false-wedge-loop-2026-07-26` is still live: **the presence
re-arm probe is structurally unanswerable in proxy mode.** It came back 4/4 silent *every single
time* for two hours. `watchdog-incidents.log` shows why — `SK-PROXYDIAG` logs the proxy listen stream
going quiet then *"listen rx RESUMED after 690s silence"*, `603s`, `752s`. The proxy holds the stream
idle for 10–12 minutes at a stretch, so a 75 s probe cannot possibly be answered. The wedge detector
is effectively firing on *push gap > ~3 min*, and only the push-recency veto keeps it from looping.
**That veto is currently the only thing standing between us and a recover loop — it should not be
carrying that alone.**

---

## 4. The measurement — SUPERSEDED by §3b, kept for method

> The measurement has been taken; §3b holds the result. This section stays because the command batch,
> the anchor rules and the pass criteria are still the right method for the **re-measurement** after
> the fix — which should be a clean screen-off hour on WiFi, off the charger.

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

## 6. ⚠ Open concern — push-driven wake churn — **CONFIRMED, cause relocated**

> **Read §3b first.** The churn is real and it is the whole problem — but the cost is the *account
> deactivation*, not `connectivityChanged(true)`. The three candidate fixes at the end of this
> section are consequently reprioritised in §8a. Kept below as written for the reasoning trail.

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

## 8. Stage 2 — reprioritised by the measurement, NOT built

The measurement (§3b) put a new item at the front that did not exist when this list was written, and
demoted most of the rest. **Nothing here is built. Needs 白い熊's go-ahead.**

### a. ⭐ Stop tearing the accounts down on a 30-second cadence — the whole win

This is worth ~100× on data and is the only item the measurement actually demands. Three options,
cheapest first:

1. **Do not background-deactivate proxy accounts at all.** The evidence says an awake, registered,
   idle proxy account costs **1.33 MiB/h** and holds nothing but a couple of idle TLS streams that
   the radio is free to sleep under. The deactivation machinery was written in the `withFirebase`
   flavor against a full-DHT cost model and hoisted into `main` in `+143` on the assumption it would
   help here; in proxy mode it is strictly counterproductive. Simplest change, largest win, and it
   also removes the "sleep into deafness" risk class that §2d and §2e exist to guard.
2. **Keep it but make it rare** — deactivate only after, say, 15–30 min with no push, and raise
   `PUSH_GRACE_MS` well above the observed 60–180 s push interval so a burst is absorbed by one
   episode. Retains a sleep path for genuinely quiet nights; still pays ~13 MiB whenever it fires.
3. **Make restore cheap** instead of rare — deactivate without `shutdownConnections`, or restore
   without re-subscribing from zero. Best end state in principle, much the largest change, and it
   depends on daemon behaviour we have not verified.

**Recommendation: (1), with (2) available later if a clean measurement shows idle-registered proxy
costs real battery.** Do (1) first precisely because it is one flag's worth of change and makes the
next measurement trivially attributable.

### b. ⭐ Give the wedge detector something answerable in proxy mode

Not a battery item — a reliability one, and by 白い熊's own ordering it outranks everything else
here. The presence re-arm probe went unanswered **4/4, every time, for two hours** (§3b), because the
proxy listen stream is legitimately silent for 600–750 s. Only the push-recency veto prevented a
recover loop. Either lengthen the probe verdict window past the observed proxy silence, or replace
the probe in proxy mode with something the proxy will actually answer (an HTTP round-trip against the
`dhtproxy` endpoint the `SK-PROXYDIAG` counters already track). Until then the detector is firing on
*push gap*, which is not the same signal.

### c. `client_mode` — a new daemon patch. *(was 8a — still valid, now lower priority)*

Targets the *escalation* path, not the resting state, so it no longer moves the main number. opendht already has `Config::client_mode` — *"node will
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

**d. Per-account escalation.** `applyProxyState` calls `accounts.setProxyEnabled(false)`, which flips
**all four** accounts to full DHT for a wedge on one. Use the existing per-account `setAccountProxy`
(`AccountService.kt`) and the per-account state already in `acctStates`, so only the implicated
account escalates. Keep the all-accounts path for `hardReset` and the manual Recover, which are
deliberately global.

**e. Bounded presence tracking.** `AccountService.resubscribeAccountPresence()` calls
`subscribeBuddy(..., true)` for every contact of every conversation and never releases them, unlike
upstream's UI-refCounted path (`ContactService.kt`). After the first recovery every contact of all
four accounts is tracked forever; in proxy mode each is a proxy subscription, and each re-subscribe
re-downloads the key's full value set. Bound the permanent set to what the watchdog and UI need —
contacts in recently active conversations, plus the ≤3 probe peers per account the watchdog already
picks — and release the rest on background transition. **This visibly affects presence dots for
non-recent contacts while backgrounded**: they go stale rather than wrong, and refresh on foreground
return. Flagged because it touches a surface 白い熊 built deliberately — needs his explicit
go-ahead.

**f. Cosmetic.** The hourly log now reads `mode=proxy/pref=proxy` on every normal line, because that
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

---

## 10. What `+149` actually changed (built 2026-07-29 17:25, UNCOMMITTED)

Both items of §8a/§8b, built together at 白い熊's instruction. APK
`shiroikuma-jami_20260717-01+149_arm64-v8a.apk`, pushed to `/sdcard/tmp/` (md5
`82571a739fe3621c54794c84297dcbcf`, verified host vs device). Only the version counter is committed
(`39caeb228`); the feature code is deliberately uncommitted until tested.

### a. Background deactivation OFF

`JamiApplication.BACKGROUND_DEACTIVATION_ENABLED = false`, guarded at all three entry points
(`scheduleBackgroundDeactivation`, the `deactivateRunnable` itself, and the direct `postDelayed` on
push arrival). Off rather than tuned because **no cadence wins**: a teardown costs ~13 MiB, resting
costs 1.33 MiB/h, so an episode needs ~10 h to break even and pushes every 60–180 s guarantee it
never gets one.

The machinery is kept, not deleted — the guards, the stand-down and the ledger are all still correct,
and flipping that one `val` back to `true` is the whole rollback.

**Verified at the bytecode level, not assumed.** The three deactivation-path log strings
(`deactivating accounts`, `Background deactivation held`, `Push unavailable while backgrounded`) are
all PRESENT in `+148`'s `classes.dex` and all GONE from `+149`'s — R8 constant-folded the guard and
eliminated the branch as dead code. `reactivating accounts` (the foreground restore) correctly
survives.

**NOT changed:** `hardwareService.connectivityChanged(true)` on message pushes. It was the leading
suspect in §6 and the measurement exonerates it — it fired ~17×/h straight through the 1.33 MiB/h
stretch at no measurable cost.

### b. The wedge detector gets an answerable question

The root cause was sharper than "the probe cannot be answered": **the watchdog already held the right
threshold and was not using it.** `proxyImplicated` treats a push within `PROXY_ALIVE_MS` (10 min) as
proof the proxy leg delivers, while the uniform-probe verdict convicted on a 135 s window. Every
false wedge on 2026-07-29 logged both, one second apart — convicting and acquitting on the same
evidence.

1. **`uniformPushAliveWindow(c)`** — in proxy mode the uniform veto now uses that same
   `PROXY_ALIVE_MS`, so the two agree. Full DHT keeps the original narrow window (no push leg there
   to vouch for anything, so a stale push timestamp must not excuse silence). **No new magic number.**
2. **`ProxyProbe.kt`** (new) — one HTTP round-trip to the node-info endpoint every opendht proxy
   serves at `/`. Any status line counts as reachable; a **`SocketTimeoutException` counts as
   UNREACHABLE**, because a proxy that takes our TCP and never answers is precisely the wedge (this
   was written the wrong way round first and corrected before the build).
3. **`askProxyBeforeRecovering`** — reached only when all accounts are silent in proxy mode with no
   recent push, and placed as the **last** gate so `recentWedge` and the `UNIFORM_PROBE_FAIL_CAP`
   stand-down still apply first. Three graduated outcomes, because a false escalation to full DHT
   costs ~110 MiB/h and a radio that never sleeps:
   · proxy **unreachable** → real evidence → recover + escalate (`fullRecover(proxySuspect = true)`);
   · reachable, **first strike** → up and merely quiet → re-register only, proxy kept, no mode flip;
   · reachable, **repeat strike** → the cheap remedy did not restore inbound → escalate.
   New `fullRecover(proxySuspect: Boolean?)` parameter lets a caller with real evidence override the
   push-timestamp guess. New incident type `proxy-unreachable`.

### What to check on `+149`

1. **`recovery-log.txt` must no longer contain `asleep`/`awake again` pairs at all.** If it does, the
   guard did not take.
2. **The hourly log should read ~0.5–1 MiB per 30-min bucket** at idle, not 25–75 MiB.
3. **No `uniform-wedge` incident whose own recover line then says "the proxy leg delivers"** — that
   self-contradicting pair is exactly what §8b removes.
4. **Messages to all four accounts after a long screen-off idle must still arrive promptly.**
   Reliability outranks every number above; this is the test that matters.
5. Then the real re-measurement: **a clean hour, screen off, on WiFi, off the charger** (§4's method
   still applies — take a fresh anchor first, since the 10:12 one is void).
