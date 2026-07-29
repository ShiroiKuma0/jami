# 白い熊 GNU Jami — `20260717-01+148`

A downstream fork of [GNU Jami](https://github.com/savoirfairelinux/jami-client-android) for Android. Installs **side-by-side** with official Jami (app id `shiroikuma.jami`, label 白い熊 GNU Jami). Everything below is built on top of stock.

## Chat-files panel, and resting on push instead of the full DHT (new in +142–+148)

### 🗂 A panel for what the chats are actually storing

- **Three folded levels — accounts → conversations → files**, biggest first at every level, each with
  its own file count and byte total, and the grand total pinned at the top. Reached from a **new row
  in the Export / Import section**, beside the backup panel rather than in place of it.
- **Sizes come from the filesystem, never from the commit's `totalSize`.** A file that was never
  downloaded occupies nothing, and a page about disk usage has to say so. Both trees are counted: the
  client payloads under `conversation_data/<account>/<conv>/`, plus any daemon-side entry with no
  client twin — the only case where those bytes exist nowhere else.
- **Deleting removes both copies**, the payload and the daemon's link. A hard link left behind keeps
  the inode, and the freed space would never appear.
- **Sent / received / orphaned sub-folds** inside each conversation, each with its own tally and
  checkbox, so a whole direction is one tick. Fixed order rather than biggest-first: a
  classification, not a ranking.
- **Tap a thumbnail or the underlined file name to open it** — pictures and videos in the app's own
  `MediaViewerActivity`, anything else via the system chooser — so a photo can be recognised before it
  is thrown away. Tapping anywhere else in the row still ticks it; the targets hug their content, so
  the space beside a short filename is still row whitespace. A payload living only in the daemon's
  directory is outside every path `file_paths.xml` declares, so it is staged into the declared cache
  path first rather than failing.
- **Per-file save to disk** via the system document picker, and a per-row delete.

### 🪶 Soft delete — free your space without touching their chat

- `ConversationModule::Impl::editMessage` refuses any commit it did not author
  (`commit->authorId == username_`), so a **received** file's message can never be retracted. The
  panel never pretends otherwise: every row says which it is, and the warning splits the counts.
- For **your own** files that limit becomes a choice worth having. **Free space** removes the local
  copies and leaves every message standing — the peer keeps its copy, nothing changes in their chat,
  and the file stays downloadable here for as long as somebody in the conversation still has it.
  **Delete messages** is the old behaviour: gone for every member and every one of your devices.
- It works because every layer below is direction-agnostic — `Conversation::downloadFile` never asks
  who authored the commit, `askForFileChannel` with no device id walks every device of every member,
  the serving side just streams `dt->path(fileId)`, and the commit's `sha3sum` verifies whatever comes
  back. If the peer is offline the request persists (`waitForTransfer` → `saveWaiting`) and replays on
  the next sync.
- **Cancel keeps the positive slot**, "Delete messages" is the only red button and sits furthest from
  the thumb, and when nothing in the selection is yours no false choice is offered at all.
- **Nothing is deleted before the affected conversations have been read**: until then our own files
  cannot be told from received ones and the warning would be a guess. Reading is the daemon's own
  message search filtered to file commits — the same call the media gallery makes — run when a
  conversation is unfolded and never for the whole device up front, because that is a git log walk per
  chat. The search is bounded: searching a conversation the daemon has not loaded emits nothing at
  all, not even the finished signal.

### 🔋 The resting mode moves to DHT proxy + push

- Measured on-device over 1 h 59 m with four accounts backgrounded: the full local DHT node moved
  **502 011 rx + 533 929 tx WiFi packets — 145 pkt/s, 83 % of every packet the device moved**, 94 MB/h
  at a ~180 B mean datagram, 19 % of one core with kernel CPU exceeding user CPU, and a **WiFi radio
  that slept 289 ms** out of the whole window. Not a wakelock problem (2.57 s of partial wakelocks in
  that span) — a packet-rate one: one opendht UDP node per account, every listen re-sent to four peers
  every 30 s, which is also the CGNAT keepalive and so cannot simply be lengthened.
- Full DHT **bypasses push entirely**, so a working FCM leg bought nothing and the phone could never
  sleep. `full_dht_mode` and `full_dht_while_charging` now default off, with a one-shot migration for
  installs that already wrote them; both switches stay in Settings, and full DHT remains the
  escalation path a proxy-implicated wedge triggers.
- **The background account-deactivation system existed only in the `withFirebase` flavour** — grace
  windows, call and foreground-service exemptions, episode cap, process lifecycle observer — while the
  shipped flavour is `withUnifiedPush`, so accounts stayed fully active for the life of the process.
  Hoisted into the abstract application class so every flavour inherits it.
- **Push payload classification is shared**, so a UnifiedPush arrival earns the same call and message
  grace windows as an FCM one instead of being treated as noise, consuming a per-value-id dedupe cache
  so it runs exactly once per push.

### 💾 保存復元 hardening (in +103–+141, released as `20260717-01+141`)

The Export/Import panel became a real phone migration: the chat corpus and its attachments travel
inside the backup and the daemon finds them already on disk. Restore reuses the archive's own account
id — no new device certificate — unpacking **before** creating the account; re-importing on the same
device merges instead of duplicating. Hardened against four failure modes found on-device: the daemon
wiping a still-pending account at the next start, account-id reuse blocked by leftover husks, a
rename-or-copy install that aborted on the first conflict while reporting success, and a
`ZipOutputStream.setLevel()` call that corrupted one entry in 3258 of a 1.6 GiB archive. Full detail
in the [`20260717-01+141` release notes](https://github.com/ShiroiKuma0/jami/releases/tag/20260717-01%2B141).

## Home-screen shortcuts, and the end of the proxy-mode data burn (new in +92–+99)

**Two things this round: a feature you asked for, and an RCA that overturned what we thought the
data cost was.**

### 🔗 Home-screen shortcuts to a chat or a call

- **From the launcher's "Add shortcut"**, pick **白い熊 GNU Jami** and walk three dialogs: account →
  contact → **chat or call**. Multi-account is handled properly (the account step is skipped when
  there is only one), the contact list carries avatars and a search box once it exceeds eight
  entries, and everything is **trilingual** (English default, Japanese, Czech) driven by the system
  locale rather than hardcoded.
- **Also reachable in-app** from the search-bar overflow (「ショートカットを作成」), which pins the
  result through `requestPinShortcut` instead of handing it back to the launcher.
- **The icon is the contact's avatar, badged**: the Jami mark flush in the bottom-right corner with a
  hairline ring at the logo's own line weight, and a **yellow-traced chat or phone glyph** hugging the
  bottom-left edge with a thin black rim so it reads over a light photo. The badge colours are
  settable roles (`SHORTCUT_ICON` / `SHORTCUT_FILL`) in **UI fonts & colours → Launcher shortcuts**.
- **Tapping opens the chat or places the call directly** — the same intents the in-app UI uses, so a
  chat shortcut still switches to the right account first.
- **Icons survive app updates.** The first build lost the chat shortcut's avatar on every install:
  Lightning Launcher's `MPReceiver.updatePackage()` repaints the icon of **any** desktop item whose
  component is a MAIN/LAUNCHER activity of the updated package. Shortcuts now target a dedicated
  `ShortcutLaunchActivity` — not a launcher activity, so it is never matched, and exported so a
  legacy launcher item can actually start it, with an unguessable per-shortcut token gating that
  exported surface.

### 📉 The proxy "data blast" was never proxy overhead — it was a recovery loop

Two phones on the same Wi-Fi, same build: this one at **281–309 MiB/h**, the second at **24.6**. The
investigation ended somewhere unexpected.

- **Mode is irrelevant to data cost.** On a phone that is not recovering in a loop, DHT proxy and
  full DHT cost the same: **24.6 vs 24.8 MiB/h** measured. Per account the two phones agree at
  **25–40 MiB/h**, so four accounts ⇒ ~100–170 MiB/h — exactly what full DHT costs here. **The whole
  gap was churn.**
- **The loop, measured:** proxy ON → ~6 min → all four accounts read deaf → 75-s probe unanswered →
  "receive path wedged" → proxy OFF + re-register + backfill → healthy in 44 s → 10-min linger →
  proxy ON → repeat. Six cycles in two hours.
- **The verdict was false, and the app's own log proved it**: `push rx after 124s/101s` at 16:45:50
  and 16:47:36, then `no real inbound 418s` at 16:50:15 and a wedge at 16:50:30 — with
  `SK-PROXYDIAG` showing all four proxy clients alive and pushes landing 30 s before the verdict.
- **Two independent causes, both ours.** `PushEvidence.noteRealPush()` recorded pushes but was never
  fed into `InboundEvidence`, whose evidence sites are all peer-originated callbacks — even though
  push is the *only* carrier of new values in proxy mode. And the deafness probe is a presence
  re-arm, which in proxy mode is a no-op on the wire, so it **cannot be answered**.
- **Why each recovery cost so much:** `enableProxy()` shuts the DHT down and builds a **new**
  `DhtProxyClient`, which can only issue a fresh SUBSCRIBE — and the proxy answers a new listener
  with the **full value set of every key**, while a RESUBSCRIBE on a live one returns nothing at all.
  One old account key measured **1,348,230 bytes per subscribe**, across four accounts, six times in
  two hours.

**Fixed:**

- **Recovery no longer touches the proxy** unless the proxy leg is implicated — a real push proves it
  delivers. It re-registers and re-arms presence with the subscriptions left standing.
- **A delivered push counts as inbound evidence** (global clock only, so per-account deafness stays
  honest).
- **A push inside the probe window refutes a uniform wedge**, mirroring the existing error-storm veto.
- **The silent-wedge fuse probes before it hammers** and no longer quarters itself during the startup
  window — that combination fired a blind recovery after every restart, including every install.
- **The 10-min proxy linger applies only to proxy-implicated wedges**, so the search-bar hexagon and
  the per-account Advanced switch stop disagreeing; when they still differ, Advanced now says why.

### 📊 The data meter where you actually look

- The **Data pill** on the Connection dashboard (and the Data button on the Connection monitor) now
  opens one shared dialog: a **live measurement** with start/stop and a ticking counter, your **saved
  measurements**, and the **automatic hourly log** with its recording state and window.

### ⓘ A help page that matches reality

- New **"Data cost"** card carrying the measured figures, what a recovery costs and why, and an
  honest account of the CRL landfill: **~990 permanent, unsigned revocation lists** from 2024–2025 on
  the oldest account's key that **cannot be deleted, overwritten or filtered out** — unsigned values
  admit no edit policy, permanent ones never expire, and the proxy ignores any query a client sends
  on listen. They fade only as the machines holding them restart. That, plus account count, is the
  entire difference between an old phone and a fresh one.
- The "silent account" explanation was corrected: silence alone is never acted on any more.

### Also

- Account settings → Advanced explains a watchdog-held proxy instead of just reading "off"; the
  recovery log names the real reason the proxy returned rather than always claiming the charger.
- The shortcut picker's account rows no longer paint a misleading red presence dot.



## DHT data-efficiency root fix, transport hostility indicator, unattended data log (new in +87–+91)

**The 2026-07-26 root-cause batch.** After the +78–+86 work the data burn had fallen but refused to
decay past ~118 MiB/h, and idle CPU sat far above where it belonged. Decoding every value the four
accounts held on the DHT found the real cause — and it was an **upstream bug**, not a fork one.

- **The account key was a landfill of revocation lists.** On every account registration the daemon
  republished **every CRL it had ever pinned** — not just the current one. One account key carried
  **266 values / 274 KB: 35 distinct CRLs spanning Oct 2024 → Jul 2026**, each stored 8 times over.
  Three facts compounded: the whole history is republished (and the receive side pins every CRL it
  hears, so the set only grows); each put builds a `dht::Value` with an unset id, which opendht then
  fills with a **random** one, so a re-put *adds a copy* instead of replacing; and the puts are
  **permanent**, so all of it is re-announced to the ~8 closest nodes every 10 minutes forever. That
  is why it plateaued instead of decaying. The put side alone came to ~30 MB/h across four accounts
  before any listen traffic.
- **Fixed at the root** (`patches/jami-publish-current-crl-only.patch`): publish only the newest CRL,
  selected by `getUpdateTime()`, under a **content-derived value id** so re-registration overwrites
  instead of accumulating. Proven safe first — the CRLs are strictly cumulative, and the newest lists
  **8 revoked serials, exactly the union of all 35**, so no revocation is lost. The post-revocation
  announce got the same treatment.
- **The device announcement had the identical defect**, leaving one orphaned copy per daemon run
  (12 copies of a single announcement measured on one key). Now pinned to an id derived from
  `getToSign()` — the very blob `checkSignature()` verifies, which does **not** include the id, so a
  stable id cannot invalidate a signature.
- **Measured result: 661 values / 611 KB → 9 values / 7.4 KB** across the four accounts — three of
  them at the theoretical floor of one CRL plus one announcement, one copy each. Idle CPU roughly
  halved alongside it, settling at ~16.5 % of one core (~2 % of an 8-core device) with the screen off.
- **This is the daemon's own source**, not a contrib package — the first such patch in the fork. A
  complete, submittable upstream report ships in `patches/UPSTREAM-REPORT-crl-landfill.md`.

**Transport hostility, surfaced at last.** The UDP/TCP egress probe had existed since the
restricted-network work, but ran only *reactively* — after a recovery had already failed, throttled
to once per 10 minutes — and its answer went nowhere but the recovery log.

- **A Transport row in the connection monitor** shows the live verdict with **its age**:
  `UDP ✓ TCP ✓ — full DHT viable`, or in red `UDP ✗ TCP ✓ — hostile network, DHT proxy advised`, or
  `no egress at all`. **Tap re-tests, long-press explains.** A stale reading is never shown as live.
- **It probes on network change**, from the existing debounced settle path — the only moment
  hostility can actually change, and after flapping stops rather than during it.
- **Advisory notification** on the transition into hostility (in English, Japanese and Czech),
  suppressed while restricted mode is already engaged since that path posts its own.
- **Strictly advisory: it never switches mode.** The top-bar hexagon remains the one and only control
  that changes DHT mode.

**Monitor help page audited and corrected.** It had described *NOT SYNCING* as "registered but
isolated >2.5 min" when the classifier actually raises it on an outgoing message unacknowledged for
90 s, and it **omitted the DEAF / "NOT RECEIVING" state entirely** — the probe-verified verdict added
in the metric redesign. All five health states now mirror the classifier exactly, in its priority
order. A new **Transport** section explains, prominently, the one case where DHT proxy is clearly
right: the full DHT rides UDP, so a UDP-blocking network (hotel, café, campus, corporate Wi-Fi, some
carriers) breaks it completely while the proxy keeps working over TCP.

**Unattended data-usage log.** The manual measurement session needed a human at both ends; comparing
a mode change across a night needs neither.

- **One line per window**, appended from the watchdog's existing ~1-minute tick — no alarm, no
  worker, no extra wakeups.
- **Switchable** (default on) with explicit `— sampling started —` / `— sampling stopped —` markers,
  so a gap in the history can never be mistaken for a crash or a dead app.
- **Settable window, 1 minute to 6 hours**, via a slider with a live lines-per-day readout. Windows
  land on the check tick and say so — that tick is the sampling clock.
- Readable from a new **Data** button in the connection monitor, and from Online recovery in settings.

**日本語 in the app language picker.** `locales_config.xml` listed 43 locales without `ja`, so the
370 Japanese strings that already shipped were unreachable from the in-app picker — only by setting
the whole phone to Japanese. Czech was already present and complete.

## Data-runaway & false-wedge overhaul, 保存復元 automation, data meter (new in +78–+86)

**The 2026-07-25 root-cause batch** — a full forensic day: a 36 GiB/15 h data burn, an overnight
7-minute false-recovery limit cycle (56 incidents), and a ~400 % CPU runaway were traced to three
interlocking causes and fixed at the root:

- **False-wedge limit cycle killed.** The watchdog's echo-suppression regime (`pushMode`) was keyed
  to the DHT-mode *preference* while the charging/wedge machinery switched the *daemon* to full DHT
  underneath it — so every probe's own answers were discarded and the watchdog "recovered" a healthy
  system every 7 minutes, all night. It now tracks the daemon's actual proxy state. The give-up
  counter no longer resets on the transient post-recover health blip, and the wedge linger (5→10 min)
  now outlasts the old cycle period so repeat-wedge backoff really engages.
- **The data hog: blanket subscription refresh made conditional.** The fork's 3-minute proxy
  subscription re-arm re-downloaded every subscribed key's **full value set** every cycle (measured:
  1650 values / 2.5 MB on one landfilled account key; ~29 MB bursts; tens of GB/day). It now re-arms
  only after 10 minutes of genuine client silence. Post-recovery backfill syncs are capped at 6/hour
  (each one fanned fresh connection offers to every device of every contact).
- **Push-token rotation.** Generations of stale server-side proxy subscriptions kept pushing 2–3/s
  to the same FCM token (a standing 600 KB backlog inside microG, a Firebase thread at ~24 % CPU).
  The token is now rotated once after the update and automatically on repeat wedges (6-hour
  throttle) — orphaning the whole pile at Google's side within seconds.
- **Per-key data forensics built in.** Subscription requests now log per-request `bytes=` and the
  DHT `key=` (visible in release builds), and proxy connect-deadline events are logged too — a
  landfilled key names itself in logcat instead of needing a day of detective work.
- **"Full DHT on while charging" toggle** (Online recovery, default **on**): charging = the robust
  full DHT because battery is free; switch it off to keep the proxy's low-data profile on the charger.
- **Data-usage meter** (Online recovery): flip it on and the app measures its own real data use —
  live elapsed / ↓rx / ↑tx line while measuring (kernel per-UID counters, reboot-safe accumulation);
  flipping it off writes a history record (duration, bytes, DHT mode incl. the daemon-actual state,
  network) viewable in a themed history dialog.
- **Pinned recovery notification.** Repeating uniform-wedge notifications now update one pinned
  notification with a ×N counter instead of rotating through 20 slots and overwriting the shade.
- **Account Settings dot honesty.** The avatar dot on the account-settings page showed a hardcoded
  red (the builder's OFFLINE default); it now reflects real account health.

**保存復元 state-export automation** (the 自由作業盤 batch-backup wire contract):

- New exported, token-gated broadcast receiver: `shiroikuma.jami.action.EXPORT_STATE` runs the
  Export/Import backup **headlessly** (same engine, same restorable zip — accounts included), and
  `…action.LIST_CATEGORIES` returns the category catalogue. Replies are fresh broadcasts with a
  correlation id (the only reply channel that survives EMUI); progress broadcasts carry **real
  counts** (`区分 n/7`), throttled to 500 ms. Directory precedence: caller-supplied path → the
  configured export directory. Distinct `automation disabled` / `bad token` errors; the full
  10-point acceptance checklist passed on-device.
- **The automation token never travels in a backup zip** — excluded on export and refused on import,
  so a restored backup can never leak or re-plant the credential.
- **Export filenames simplified**: `shiroikuma-jami_<yyyy-MM-dd_HH-mm-ss>.zip` — app name + stamp,
  no version; the latest-export scan still recognises the older names.
- **Automation controls moved into the Export/Import section** of the UI page (master switch,
  tap-to-copy token, regenerate, and a details link to the full usage page) — the backup automation
  lives next to the backups it drives.

**UI:**

- **⋮ long-press → UI settings.** In a chat, long-pressing the toolbar's overflow button opens the
  "白い熊 GNU Jami UI" settings page; its normal tap menu is untouched.
- **Unread accounts pop in the account picker.** In "Select account", an account with unread
  messages gets a row-wide box in the unread badge's own style (settable fill/border colours),
  inset from the dialog border so the box, badge, and dialog outlines read as three clean lines.

## Honest connection status + actionable stuck messages (new in +67–+77)

- **Status-dot freeze fixed.** The top-bar account dot is refreshed by a 2-second connectivity poll; a single exception in that poll (e.g. during a watchdog-triggered daemon restart) used to terminate the RxJava chain permanently, freezing the dot on its last colour while the dashboard showed the truth. The poll now resubscribes after 5 seconds and records the swallowed error in the recovery log, so the dot can no longer fossilize on stale-yellow.
- **Every account-status dot follows verified health, not registration.** The top-bar dot, the side-bar (nav) avatar, the dashboard rows, the monitor rows, **and the "Select account" picker** all colour their dot by the account's real health verdict — red for `NOT SYNCING` / `NOT RECEIVING`, blue for connecting, yellow only when genuinely healthy. A red account row is never paired with a green dot.
- **Stuck messages are actionable.** Each "message not delivered" line on the dashboard and monitor now:
  - **names the recipient unambiguously** — profile name **plus** the account username **plus** the id tail — so two of your own identities that share a display name (e.g. two accounts both named 白い熊) are never confused or shown as pointing "to themselves";
  - shows **how long it's been stuck** and **what it is** (📎 filename for a file, 💬 preview for text);
  - is **tappable → it opens that conversation** directly.
- **Red vs blue by cause.** A stuck message is **red — "message not delivered"** only when it's a genuine fault you can act on: a **same-device sync stall** (one of your own accounts failing to sync to another on the same phone), or a peer that is **connected yet never acknowledged**. It's **blue — "delivering…"** when you're simply reaching a recipient who is **offline or only announced** (away) — that's benign, leaves the account healthy, isn't counted as "needs attention", and ages out after 24 h. A message queued to an offline external contact can no longer turn your account red.
- **Verified-unreachable contact demotion.** A contact whose last outgoing message is stuck with no live channel is shown **red** on its presence dot; a stale `AVAILABLE` announce is held down until the message actually delivers or a real connected channel appears — so the dot can't claim a reachability the network can't back. (Deliberately distinct from the softer blue "delivering…" line: the dot is the strict "can't reach them" signal.)
- **File- and call-aware stuck detection.** The stuck scan walks to the newest outgoing **text *or* file** in a conversation (instead of only the last event), so an undelivered file followed by missed calls is caught — and delivery is judged by the per-recipient receipt map with the sender's *own* status entry excluded, fixing a false "delivered" that hid stuck messages in any conversation you had open.
- **Self-match fix.** The same-device detector no longer matches your own participant entry, so a one-to-one chat with an external contact is never mislabelled a same-device stall "to myself".
- **Same-device stalls drive recovery.** Because your own accounts on one phone are reachable by definition, a message stuck between them is treated as real local-fault evidence and drives the auto-recovery (with the existing once-then-escalate-then-stand-down ledger), instead of being written off as a benign offline wait.
- **Account picker restyle.** The "Select account" dialog is now about **half width** with **larger, tighter rows** (bigger avatars and names), rendered in a **scrollable container** so the **"+ Add account"** row is always present and can never be clipped off the bottom; the "+ Add account" row itself is compact.

## Export / Import of every setting + accounts, UI-page restyle (new in +65/+66)

- **Export / Import — the first section of the UI page.** Backs up **everything settable in the app into one timestamped zip** (`shiroikuma-jami-export_….zip`) and restores it category by category:
  - **Accounts (Jami archives)** — every password-less Jami account's daemon archive travels inside the zip (`accounts/<id>.gz` + metadata); password-protected archives are skipped and named in the result dialog. Import restores each archive through the daemon (wizard-style, with the fork's connectivity defaults) and **skips identities already on the device**, so a re-import never duplicates an account.
  - **Fonts & sizes** — the full per-element font config *plus the imported `.ttf`/`.otf` files themselves*, re-pointed to the new install on import.
  - **Colours**, **UI behaviour** (split view, dot/fold scales, app language), **online recovery & connectivity** (watchdog config, DHT mode, push backend), **automation & protected contacts**, and the stock **app settings**.
  - **Settable export directory** (SAF, persisted) with a **latest-export status line** queried in the background every time the page opens; no directory set → a save-as picker fallback.
  - Import is a **per-key merge — never a wipe** — so unknown keys survive and old exports load into new versions; the format is a zip of typed-JSON files plus a manifest.
  - Flow polish: the panel has select-all + per-category checkboxes and an ArcaneChat-style pill row (Cancel left, Import + Export right); the finished dialogs carry the yellow border; export-OK / import-"Later" close the whole chain (dialog → panel → page), "Restart now" relaunches the app, and failures flash while leaving the panel open.
- **kxkb-style restyle of the whole UI page** — section headings are 20 sp bold with **text-wide** underlines, sections are separated by thin full-width hairlines, element headings are 17 sp bold with thinner text-wide underlines, and the whole page uses the tighter kxkb indent ladder.

## Call-screen fixes (new in +64)

- **In-call avatar** no longer sits in a grey box; the presence dot stays **yellow during an active call** (you're demonstrably connected to whoever you're talking to).
- **Camera toggle button** un-reversed — camera **on** is a filled yellow button with a black plain-camera icon (the app's "active" look, like Speaker); camera **off** is an outlined button with a yellow crossed-camera icon.
- **Quiet-account wedge tuning** — the per-account recovery no longer fires on a single unanswered presence probe when nothing is actually stuck; a genuinely stuck message still recovers immediately, but a merely-idle account needs a second consecutive miss, removing the occasional needless recovery on quiet accounts.

## Connectivity — three-fold push & adaptive resilience (headline of this release)

- **Three-fold connection mode** in one build — Local DHT node / **Firebase (FCM)** / **UnifiedPush** — chosen at runtime from the connectivity dialog. Firebase is initialized programmatically from GNU Jami's own public project (no `google-services.json`, plugin not applied) and **works through microG** — validated on-device: microG issues a token for the fork's package and delivers.
- **Adaptive push→streaming fallback.** A watchdog measures whether *real* pushes actually arrive (not just whether the server answers a self-test). When the push leg is dead — rate-limited server, filtering VPN, dead distributor — it clears the push token and switches proxy subscriptions to **streaming LISTEN** so messages keep flowing, then returns to push when the leg recovers. FCM (which has no phone-to-self self-test) exits via timed optimistic re-entry with a doubling hold on relapse; UnifiedPush uses an end-to-end ntfy self-test.
- **Permanent foreground service while streaming**, so the process survives an outage on any device (streaming needs a live daemon; push, its normal reviver, is what's dead). Your setting is backed up and restored on exit. Adaptive state persists across a process restart.
- **Push starvation trigger** — a verified wedge with no real push for 10 minutes drops to streaming regardless of any self-test (catches the case where the proxy→server leg is dead but the phone→server leg works).
- **Fresh-install default is push ON (Firebase)**, degrading safely to streaming when microG is unavailable.
- **Verified-health metrics.** Every connectivity indicator — top-bar dot, dashboard, per-contact dot — is driven by probe-verified reception (a 60-second silent presence probe, an active call, real push arrivals), never a registration flag. A contact in a live call can never show red; a "connected but receiving nothing" account is caught. New `NOT RECEIVING` state.
- **Probe-gated recovery.** A per-account wedge only recovers after a silent presence re-arm probe goes unanswered for 60 s, killing the false-positive recovery loop that fired on quiet evenings. A repeat verified wedge escalates to a proxy off→on cycle to force a fresh proxy endpoint.
- **Post-recovery back-fill** — after any recovery the app actively pulls the messages that piled up while deaf, instead of waiting for senders to retry.
- **Crash-safe re-register ledger** — the unregister→register nudge briefly persists a disabled flag; if the process dies inside that window an account could be left disabled on disk. A ledger detects and auto-re-enables any account *we* disabled, on the next start.
- **The ⬡ hub icon** now encodes the mode by **shape** (solid hexagon = full DHT, thick hollow hexagon = proxy) and the live state by **colour** (yellow healthy / blue recovering-or-streaming-fallback / red current-mode-broken). One tap switches every account.
- Diagnostics: OpenDHT proxy-client subscription instrumentation (logcat `SK-PROXYDIAG`), a push-cadence gap log, and a push-endpoint line on the connection dashboard. The settings-page Full-DHT toggle now logs and verifies the switch. Telemetry no longer reports bogus "on full DHT" durations.

## Connectivity — carried forward from earlier releases

- **dhtnet LAN-interface fix** — upstream binds UPnP/NAT-PMP to the first interface (cellular on a phone with mobile data up), so both silently fail forever; this fork prefers the real Wi-Fi/Ethernet interface so UPnP/NAT-PMP reach the router and ICE gets direct-connect candidates.
- **dhtnet UPnP circuit breaker** — skips UPnP mapping when the router has no working IGD instead of blocking every ICE setup for seconds.
- **OpenDHT 4.2.0 proxy fixes** — connect-deadline resilience (a black-holed cached proxy endpoint no longer strands delivery forever) and a 3-minute subscription refresh (a lapsed push subscription is rebuilt by the daemon itself).
- **pjproject stuck-epoll eviction** — disarms dead sockets that spun ICE poll threads at ~80 Hz (the 12 % idle-CPU regression), gated to error-carrying events only.
- **Self-healing watchdog** with error-storm detection, passive deafness clock, restricted-network (hostile-Wi-Fi) mode, and per-contact live monitor with a receipt-verified Message ping.

## UI & theming

- Full **yellow-on-black theme** across chat list, bubbles, toolbars, dialogs, cards, badges, generated avatars and the home top bar.
- **UI fonts & colours** — per-element family/weight/size **and** runtime text/fill/border/tint colours, with an **RGBA colour picker** (alpha per role) and external `.ttf`/`.otf` import.
- **Yellow-border dialogs everywhere** — a drop-in that restores the black-fill / 2 dp yellow-border / yellow-pill-button chrome on ~55 upstream dialogs that Material stripped.
- **Full yellow settings chrome** — settings icons, headings, switches (yellow dot on a traced black track, both states), the account-summary page (black toolbar, yellow title/values/pencils/icons), the Online/Offline pill and the two-tone invitation envelope — all themed, with a settable "Settings icons" colour role.
- **Call UI** — control toolbar fixed (never auto-hides, always expanded), earpiece/speaker toggle, separate mute and Bluetooth toggles, black-yellow theme.
- Line-driven sizing so larger fonts stay legible; presence-dot colours; split-view toggle for foldables/tablets.

## Media, messaging & integrations

- **Media viewer** with swipe-through and swipe-down-to-hide / swipe-up-to-restore, a hidden-media browser, and plain yellow action icons.
- **Protected contacts** — a companion app marks contacts protected over a local broadcast; their messages post a deliberately vague, contentless notification (masking media too), companion-settable, queryable back.
- **In-app message forwarding** — long-press → 「白い熊 Jami」で共有 forwards through Jami's own account/conversation picker, no system share sheet.
- **Automation intents** — token-gated exported send / call / open intents to drive Jami headlessly.
- **Registered-name resolution** — stuck lookups retried automatically, plus an on-demand "Look up name" action.
- **Missed-call notification** for swarm conversations (was gated off upstream).
- A **Location-sharing status** tool that shows and can stop all real location shares without waking the service.

## Install identity & packaging

- App id `shiroikuma.jami`, label **白い熊 GNU Jami**, own FileProvider authority and signing key — coexists with official `cx.ring`.
- Built from source for `arm64-v8a`, including the full libjami daemon + contrib. AGP-9 built-in-Kotlin build-config migration.

---

Built on [GNU Jami](https://jami.net) by Savoir-faire Linux and the Jami community. Licensed under the GNU GPL, same as upstream.
