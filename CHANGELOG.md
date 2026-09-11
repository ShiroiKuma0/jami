# 白い熊 GNU Jami — changelog

Our releases, newest first. A downstream fork of
[GNU Jami](https://github.com/savoirfairelinux/jami-client-android) for Android, installing
**side-by-side** with official Jami (app id `shiroikuma.jami`, label 白い熊 GNU Jami). Everything
listed is built on top of stock.

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
