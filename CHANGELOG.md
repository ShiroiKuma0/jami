# 白い熊 GNU Jami — changelog

Our releases, newest first. A downstream fork of
[GNU Jami](https://github.com/savoirfairelinux/jami-client-android) for Android, installing
**side-by-side** with official Jami (app id `shiroikuma.jami`, label 白い熊 GNU Jami). Everything
listed is built on top of stock.

---

## `20260904-01+2026-09-08.19-39.g3c0b6ad1+017` — 2026-09-15

One fault, one remedy, and the remedy in two places — automatic and manual. Builds `+015` and
`+016` were sizing passes on the new control and were never released. The `daemon` submodule
gitlink did not move, so the native side is byte-for-byte what `+014` shipped.

### 🔌 The watchdog now switches the accounts off and on

**The fault.** For three days the four accounts on one phone refused each other's device
certificates — `[TLS-SOCKET] Refusing peer certificate`, nothing delivered between 白い熊's own
accounts, conversations with real contacts entirely unaffected. In one log buffer: **6 connections
established with external contacts, 9 certificate refusals between his own accounts, zero
established.** Both the group chats involved were blamed in turn and neither was at fault.

**Why it lasted three days.** The watchdog detected it correctly every time — the red dots and the
`undelivered to reachable peer(s)` lines were right — and then applied `forceReconnectAccount`,
several verified-wedge recoveries and two hard resets against it. None of them touched it. The
escalation ladder ended at *"giving up for 60m"*. Switching all four accounts off and on by hand
cleared it in seconds, and that action was not on the ladder.

**The fix.** It now reads `recover → HARD reset → toggle all accounts off/on → give up`.
`toggleAccountsRegistration()` performs exactly what the account switches in Settings perform —
both call `sendRegister` — with the two properties the manual action had and the existing nudge
does not:

- **all implicated accounts are down at the same time**, so no half of a sibling pair can keep
  stale state alive while the other restarts (the old path only ever touched one account);
- **8 seconds down**, not 1.5.

Which of the two is the operative one is not yet known, and the code says so rather than trimming
either on a guess.

Two safeguards. Accounts that are **disabled are skipped and never re-enabled** — an account
switched off deliberately must stay off, which is the standing complaint against `fullRecover`.
And the crash-safe re-register ledger is marked exactly as `reconnectOne` marks it, because
`sendRegister(false)` persists `ACCOUNT_ENABLE` and a process death inside the window would
otherwise leave accounts disabled on disk.

### ⚫ A per-account on/off dot in the connection dashboard

Beside each account's ⚡, a 36dp dot: tap to switch that account off, tap again to switch it back
on, with a flash naming which — in all three locales, like the rest of the dashboard.

It is the **same dot as the search bar's**, not a lookalike: the colour and shape decision now
lives in a single `dotLook()` that both call, so the two cannot drift apart. Hollow grey when the
account is switched off, red on a problem, blue while connecting or recovering, yellow when
healthy. The dashboard's copy is driven by that row's own health verdict rather than the global
alarm counters the search bar uses.

The ⚡ keeps its own tap (recover just this account) untouched — this is a new control in a new
slot, not a re-mapped one.

---

**Asset:** `shiroikuma-jami_20260904-01+2026-09-08.19-39.g3c0b6ad1+017_arm64-v8a.apk` — `arm64-v8a`,
`withUnifiedPush` flavour, signed release. Installs over `+014` in place; no uninstall needed.

---

## `20260904-01+2026-09-08.19-39.g3c0b6ad1+014` — 2026-09-15

Two connectivity fixes, both found by measurement rather than by reading, and both about the same
distinction: telling a real failure apart from an account that is merely quiet. Builds `+010`
through `+013` were diagnostic and experimental steps toward these and were never released — the
code below is the whole delta from `+009`. The `daemon` submodule gitlink did not move, so the
native side is byte-for-byte what `+009` shipped.

### 📨 A sleeping account no longer starves for want of a wake-up

**The bug.** With the background battery optimization on, every account is deactivated when the app
is backgrounded. A deactivated client does not fetch, so the values on its own DHT key age out, so
the proxy has nothing left to send it but **expiration** notices — and the push handler dropped every
one of those before any restore. The sleep phase starved its own wake-up, and a message sent to such
an account simply never arrived until the app was opened by hand.

**How it was measured.** Twelve controlled trials, each with the receiving phone backgrounded and
untouched for ten minutes after a send from the other phone. Eleven delivered in 3–88 s. The twelfth
did not, and the new trace shows exactly why — in the ten minutes after that send: **62 pushes
classified, all 62 flagged expired, ten of them carrying the real
`application/im-gitmessage-id/<swarm>` type, 0 restores, 0 wakes.** The eleven that worked did so
only because at least one push happened to arrive unexpired. That is the entire difference between
working and not.

**The fix.** `exp` genuinely means the value has left the DHT — opendht's proxy server sets it only
when the value expired — so discarding it is correct in isolation. It is wrong while **asleep** and
the expiry **names a call or message type**: there it is the only evidence we will ever get that the
key changed. Such an expiry now spends exactly one restore, rate-limited to one per 10 minutes so a
burst of 62 buys one wake rather than 62, and carries the ordinary 30-second message grace window
because the fetch it exists to permit took 3–88 s across the trials.

**Verified.** Six fresh trials after the fix: 6/6 delivered, the new path firing in five of them with
the asleep count collapsing 4 → 0 on a single restore. Two days of ordinary use since: 17 expiry
wakes, and hourly data unchanged at 0.6–2.4 MiB/h — the fix costs nothing measurable.

### 🔴 The account dot now means a concluded wedge, not a suspicion

`accountVerifiedDeaf()` read `strikes > 0`, so a **single** unanswered presence probe turned an
account's dot red. That is precisely the evidence the recovery path discards — it requires a second
strike before acting, calling one miss "often just bad luck, not a wedge" — while the accessor's own
comment promised an immunity to quiet-evening false positives that the code did not have. The UI was
stricter than the logic it claims to mirror.

It now lights on an explicit flag, set only where the watchdog actually **concludes** a wedge and
acts on it, and cleared wherever a strike clears. Deliberately not `strikes >= 2`: a message stuck to
a peer that reads CONNECTED legitimately recovers on the first strike and is a real wedge. All five
health surfaces read the one accessor, so they cannot disagree.

Measured across the same two days: **7 strike-1 events over the four accounts, every one of them
logging "nothing stuck to a reachable peer", against zero genuine stuck-message wedges.** Every red
dot in that window was this false positive, and the new gate would have lit none of them.

The per-account proxy-delivery evidence is now also consulted **before** the probe instead of only
after one has failed, so a quiet-but-alive account never collects the strike nor pays for the
resubscribe it triggers.

### 🔬 `SK-WAKE` — the push→restore path is observable at last

Every decision on that path is written to the **persistent** recovery log: what the proxy actually
sent (`pt`, id count, whether `exp` is set, the full key set), how it was classified, which branch
dropped it and on which of the three conditions, and how many accounts were asleep before and after
each restore. EMUI discards this package's release logcat, and logcat on these phones rotates within
about two minutes — which is why this path had never been observable on-device, and why both bugs
above survived as long as they did.

---

**Asset:** `shiroikuma-jami_20260904-01+2026-09-08.19-39.g3c0b6ad1+014_arm64-v8a.apk` — `arm64-v8a`,
`withUnifiedPush` flavour, signed release. Installs over `+013` in place; no uninstall needed.

---

## `20260904-01+2026-09-08.19-39.g3c0b6ad1+009` — 2026-09-11

An upstream-sync release, and nothing else. The fork's own code is **unchanged from `+008`** — no
feature, no fix, no behaviour change. What moved is the upstream commit our stack is rebased on, and
therefore the base pin in the version string: `ge3d1d428` (2026-09-04) → `g3c0b6ad1` (2026-09-08).

### 🔄 Rebased onto upstream `3c0b6ad1`

Upstream advanced by exactly one commit, *docker: make cargo usable by any build uid*. It repairs
their **containerised build images**: `CARGO_HOME` pointed at the toolchain install
`/usr/local/cargo`, which is root-owned and deliberately read-only, so the `yffi` (Y-CRDT) contrib's
cargo could not write its registry cache and every release build had failed since `yffi` landed. The
`chown` meant to fix it lived only in the `test` stage, which the Play Store pipeline never runs, and
moving it would not have helped either — cqfd runs the container under the host uid, which has no
user in the image to chown to. Their fix keeps the toolchain read-only and points `CARGO_HOME` at
`/tmp/cargo-home`, a directory any uid can write.

**It changes nothing in this APK**, because we never build in their container: this fork compiles
natively on the build host, and the same `yffi`/cargo problem was solved here a month ago with a
`rustup` directory override pinned to toolchain 1.97.1. The commit touches two Dockerfiles and no
other file.

All 479 of our commits replayed onto the new base with **zero conflicts**.

### 🧊 Identical native code

The `daemon` submodule gitlink did not move (`ce493889c` before and after), so the C++ daemon and
every contrib package — our patched `pjproject`, `opendht` and `dhtnet` among them — are byte-for-byte
what `+008` shipped. Nothing was re-extracted and nothing recompiled; the patch-set checksum gate saw
unchanged patches *and* unchanged upstream package versions and correctly did nothing.

### 🔢 Version bookkeeping

`upstreamVersionName` is still `20260904-01`, so the build counter **did not reset** — it advanced
8 → 9, exactly as the pin/counter split requires. The pin orders releases by the upstream commit they
sit on; the counter guarantees `versionCode` never goes backwards. Only the pin moved here.

---

**Asset:** `shiroikuma-jami_20260904-01+2026-09-08.19-39.g3c0b6ad1+009_arm64-v8a.apk` — `arm64-v8a`,
`withUnifiedPush` flavour, signed release. Installs over `+008` in place; no uninstall needed.

---

## `20260904-01+2026-09-04.22-30.ge3d1d428+008` — 2026-09-11

Same upstream base as `+002` — no rebase, a pure fork delta. One day's work, all of it traced back
from a single report: *"something is regularly switching all of my accounts offline."*

### 🔌 The watchdog was the one switching them off

The uniform-wedge detector had exactly one way to acquit a quiet night — *a real push landed N
seconds ago* — and on a handset with **no push backend installed at all** that acquittal is
structurally unreachable, because the last-push clock stays at zero for the life of the process.
So ordinary night-time silence convicted every time, and the recovery it triggered calls
`sendRegister(false)` on every account: the user's four accounts switching themselves offline,
literally.

Measured over 48 h on the affected phone: **29 uniform-wedge incidents, 32 global recoveries, and
30 episodes with every account unregistered** — nine of 4–9 minutes and one of 72. The comparison
phone, which has microG, recorded **zero of each** in the same window.

Two fixes:

- **The detector now also accepts proxy-delivery evidence** — the same per-account signal the
  per-account detector has trusted since July, which this path simply never read. At 04:53:33 all
  four proxies reported delivering 121–126 s earlier; at 04:54:32 the old code declared
  "subscriptions presumed dead" and tore down all four. A genuinely dead subscription reads very
  differently and is still convicted — one proxy sat at 4138 s with every stream ended that same
  morning.
- **The all-unregistered rescue was gated exactly backwards.** It refused to act for the 10–30
  minutes after a wedge — which is precisely when the recovery's own teardown leaves nothing
  registered. A recovery that cannot re-fire while its own damage is on screen is not a recovery.
  It now acts once the state has stood for two minutes regardless, throttled so it cannot loop, and
  records an incident.

### 📣 A phone that cannot receive push now says so, loudly

The condition was completely silent: the only trace was a log line EMUI drops in release builds,
and the UI's response was to **hide** the "Push notifications" row — the state announced itself by
removing the one control that explained it.

Now a **high-importance notification on its own channel** names the fault and the remedy (microG,
GmsCore + GSF, device registration, Cloud Messaging, and that Jami must be restarted because the
token is fetched only at process start). It distinguishes *no Play Services installed* from
*installed but issued no token*, writes a `push-provider-missing` incident, turns the connection
dashboard's push row red with the reason instead of a bare "no endpoint", and cancels itself when a
token appears. It is dismissible and separately silenceable, so choosing to run without Play
Services is not punished.

### ⚡ Push engages the moment a provider appears

A token arriving while the adaptive no-push fallback is streaming used to wait out the full
30-minute probe cadence before being registered. On the day microG was installed the startup probe
ran at 10:15:40 and Firebase answered at 10:15:40.718 — a **0.7-second race that cost 30 minutes**
of streaming with a perfectly good token sitting unregistered. The exit is now taken as soon as the
token arrives, provided the deliberate streaming hold has already expired.

### 🗂 Backup categories regrouped around what they are

The 保存復元 categories had been drawn around preference *files* rather than concepts, and it
showed. "UI behaviour" was not a concept at all — it was the leftovers of one store after the
recovery keys were carved out of it: four unrelated keys, two of them sizes (which is what "Fonts &
sizes" claims to own) and one a language (while a category called "App settings" sat beside it).

- **`ui` is retired.** Its scale keys join **`fonts`**; `split_view` and the app language join
  **`app_settings`**, renamed **"Jami settings"** since it means upstream Jami's own stores rather
  than everything. Deliberately **not** aliased — a caller still naming `ui` gets a loud
  `ERROR:unknown category` rather than a silent partial restore.
- **`connectivity` is new**, splitting the push backend and DHT mode back out of **"Online
  recovery"**. They were never recovery settings; they rode along because they share a file, and
  restoring `recovery` onto another handset imposed that phone's push backend and DHT mode on it.
  A wrong push backend now announces itself — but **nothing announces a flipped DHT mode**, and a
  restore could silently move a phone onto the mode measured at 145 pkt/s with the WiFi radio
  asleep 289 ms in two hours. *A setting that fails loudly can survive being carried; one that
  fails silently must not be.*

Verified against the installed build rather than the source: the category listing answers the nine
ids with `ui` gone and `connectivity` on, a full backup run produced `connectivity.json` in the
archive, and both companion apps were updated in step.

### 🌐 Exported labels follow the app's chosen language

Android's per-app locale does not reach the application context below API 33 — the compatibility
layer wraps *activity* contexts only. So the category labels this app publishes to other apps were
resolved in the **system** locale no matter what language the user had chosen: every screen would
follow the choice and this one listing would not. Now they resolve against the chosen language.

---

## `20260904-01+2026-09-04.22-30.ge3d1d428+002` — 2026-09-08

A downstream fork of [GNU Jami](https://github.com/savoirfairelinux/jami-client-android) for Android. Installs **side-by-side** with official Jami (app id `shiroikuma.jami`, label 白い熊 GNU Jami). Everything below is built on top of stock.

The first release on a new upstream base: **`20260807-01` → `20260904-01`**, 32 client commits and 41 daemon commits, with all three of the contrib packages this fork patches moving at once. Plus three colour defaults.

---

### 🔄 New upstream base — what arrives with it

Upstream's own work in this window, none of it ours:

- **Answering a call could kill the whole process.** A `CallStyle` notification was handed to the notification service asynchronously, and by the time it ran the service record could already be gone — `CannotPostForegroundServiceNotificationException`, process dead. Fixed upstream.
- **A concurrency bug in call notifications.** `manageCallNotification()` is reached from two threads and mutated a plain `LinkedHashMap` with no synchronisation; removing and re-adding an entry to reorder it left the map momentarily empty, and a concurrent reader could act on that. Start and stop are now serialised.
- **A file-transfer overhaul** — transfers are hydrated before listeners are notified so locally stored files are not auto-accepted, unavailable conversations retry with bounded backoff, and manual retry is consistent on phone and TV.
- **The file card was rebuilt**: the separate download button is gone and the file icon itself is now the download button, with an inline progress bar.
- **Collaborative documents**, a substantial new feature — a bundled editor, a documents list per conversation, version history, and documents appearing in the chat. This fork's colour system does not know about it yet, so those screens render in stock colours for now.

### ✅ One of our fixes landed upstream by itself

The outgoing file card's overflow fix — anchoring `fileInfoLayout` to the parent so a long filename cannot escape the card — is now **upstream's own behaviour**, arrived at independently while they rebuilt that layout. Our commit for it was dropped from the stack during the rebase because it had become a no-op. That is the second of our fixes to be made redundant this way, and the right outcome every time.

### 🎨 Three new colour defaults

| Setting | Was | Now |
| --- | --- | --- |
| Presence dots → Reachable (available) | light blue `available_indicator` | **`#0000FF`** |
| Status & indicators → Sending icon | grey | **`#399EFF`** |
| Status & indicators → Sent / delivered | untinted, the drawable's own grey | **`#0000FF`** |

The two status roles needed more than a new value. They were **gated on having been set by hand**: the icons took a colour from the role only once one had been picked, and otherwise kept their intrinsic grey. Under that gate a default is unreachable by construction, so changing the number alone would have shipped nothing visible. The gate is gone for both and the roles are always applied.

That gate was deliberate, and its purpose was to preserve the stock look until asked. Wanting real defaults supersedes it — the point of a default is to be what you see before touching anything. A colour picked by hand still wins, exactly as before.

### 🔧 The file icon keeps its settable colour

Upstream deleted the view that the **`FILE_ARROW`** role tinted. Rather than let the role quietly die with it, it follows the arrow to its new home on the file icon itself — same glyph, same colour, still settable. Both it and upstream's own value are `#FFFF00` here, so an unset role looks exactly as it did.

The card's theming was re-derived onto upstream's new structure at the same time: no runtime tint on the icon background, so the authored black square with its yellow border shows through, and none on the card, so the authored yellow stroke survives. Upstream's icon is otherwise left alone — this fork used to force a paperclip there, and that would now destroy the download affordance.

### 🧩 Two opendht patches re-derived for 4.4.0 — one of them upstreamed itself

The daemon moves opendht `4.3.1 → 4.4.0`, dhtnet to a new commit, and pjproject to a new commit. Both of our opendht patches lost a hunk.

The interesting one is the **push-refetch hardening**. Half of that patch — refusing to sweep the value cache unless the fetch actually succeeded — **has been adopted by opendht itself**, and improved on: 4.4.0 expires only the values the fresh fetch did *not* return, instead of replaying the whole previous set. Carrying our version forward unchanged would have *undone* that. So the patch now wraps our request-coalescing around upstream's more precise expiry, which also retires a residual our own comment used to concede: a fetch that failed after delivering some values left their reference count one high, costing a missed expiry later. Upstream's approach makes that case disappear rather than merely soften it.

Why it matters: that sweep is what a failed fetch used to turn into *every contact going offline at once* from a single network blip — and this fork's watchdog reads exactly that signal to decide whether the connection is wedged.

### 🛠 Two build failures that will never cost a build again

Both are now fixed in the canonical build block rather than in a scratch copy, so the next sync inherits them.

**A re-extract does not make a bumped contrib package safe.** Every contrib compile line puts the shared install prefix ahead of the package's own headers, so a freshly extracted package compiles against its own *stale installed* headers and fails on symbols its new source introduced. pjproject's new TCP-keepalive tuning did exactly this — four undeclared identifiers, contrib dead. It reads precisely like a broken patch and is not one; the tell-tale is that the installed header is older than the tarball. The gate now purges a package's installed footprint whenever it re-extracts, and purges only the three packages this fork patches, never the whole prefix.

**A guard on a patch that creates a file must check the file.** One patch here adds a new source file, and that file is untracked in the daemon submodule — so its two halves drift apart in either direction. An upstream sync needs a hard reset for the submodule to advance, which reverts the tracked half and leaves the new file behind; remove the file alone and the mirror image happens, where the marker survives, the guard skips the patch, and the build dies on a missing include. The guard now tests both halves and heals a half-applied tree before re-applying.

### 🏗 Build

Built on upstream `e3d1d428c` (`20260904-01`, versionCode 504) — a new base, so the build counter restarts at `+002` while `versionCode` continues upward at `5040002`. `arm64-v8a`, `withUnifiedPush` flavour, signed release APK. The daemon submodule gitlink remains pinned to upstream.

Upstream's build now requires **Node.js and npm** for the collaborative editor's bundle, and moves to Gradle 9.7 with AGP 9.4.0-rc01. Both were verified against this fork's own build constraints, including the legacy-DSL setting the protobuf plugin still forces.
