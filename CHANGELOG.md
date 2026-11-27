# 白い熊 GNU Jami — changelog

Our releases, newest first. A downstream fork of
[GNU Jami](https://github.com/savoirfairelinux/jami-client-android) for Android, installing
**side-by-side** with official Jami (app id `shiroikuma.jami`, label 白い熊 GNU Jami). Everything
listed is built on top of stock.

---

## `20261106-01+2026-11-18.16-14.g0fc04d09+038` — 2026-11-27

Built on upstream 20261106-01 at `0fc04d099`, the same base as `+030`. Builds `+031` to `+037`
were steps towards this one and are not released separately.

### 🔕 An incoming call stays quiet while a call in another app is up

- **No ring into the ear.** With the audio in communication mode a ringtone comes out of the
  earpiece. While a call of another app is up — WhatsApp, the phone's own, any other — an incoming
  Jami call does not ring, does not raise the full-screen call screen, and takes neither the audio
  focus nor the audio route of the other call.
- **One vibration**, two short pulses; none when the phone is on silent.
- **A card at the top of the display**: who is calling, and the pills Hide, Decline and Answer. A
  touch on the card opens the full call screen. On the lock screen the card cannot show; there the
  vibration and the entry in the notification shade remain.
- **Messages, invitations, file transfers and missed calls are posted silently** while any call is
  up, a Jami call included.
- The ringtone is muted the moment a call goes up anywhere, so that it cannot sound before the
  app has seen the incoming call. A new notification channel, “Incoming calls during another call”.

### 🩺 The watchdog asks the daemon about the proxy, not the log

- **No verdict depends on a diagnostics switch any more.** The watchdog acquitted a quiet account
  by reading a diagnostic line of the proxy client from the log. Since all diagnostics are off by
  default, it was blind and painted every quiet account “cannot receive”. It now asks the daemon
  directly (`getProxyRxStats`, patches `opendht-proxy-rx-stats` and `jami-proxy-rx-stats`).
- **A file of presence changes, per contact** (Settings → Diagnostics, off by default,
  `presence-changes.txt`): what the daemon reported for a contact, what the dot showed, and why a
  reported presence was held back; also when the accounts were put to sleep and woken.

### 🌐 App language: a new dialog and a short list

- **日本語 and English are pinned on top** as two pills, outside the scrolling part. Below them one
  framed box scrolls by itself: “System default” first, then every other language by its own name.
- **Every line carries its flag**, bundled as an image.
- **The list is cut to eleven languages** — Japanese, English, and those translated to four fifths
  or more of upstream's strings: Czech, German, Estonian, Spanish, Finnish, French, Romanian,
  Chinese simplified and traditional. One row per language. A language chosen earlier that is no
  longer listed stays in force and is shown as the current row.

### 📊 The “Data” panel redrawn

- The data panel of the connection monitor is laid out anew in the black-yellow look, with boxes
  for received and sent traffic in colours of their own, both settable in the UI settings.

### 📦 Packaging

- `arm64-v8a`, flavour `withUnifiedPush`, signed. Installs side-by-side with official Jami.

---

## `20261106-01+2026-11-18.16-14.g0fc04d09+030` — 2026-11-26

Built on upstream 20261106-01 at `0fc04d099`, the same base as `+026`. Builds `+027` to `+029`
were steps towards this one and are not released separately.

### 🩺 The watchdog no longer judges an account for what happened while it slept

- **A probe that overlaps a sleep is void.** With push enabled, accounts in the background are
  switched off and woken by a push for about 30 seconds. The watchdog's probe needs 60 seconds for
  its verdict and was started the moment the accounts woke — so it could never finish before they
  slept again, and an account that cannot answer was marked “unanswered”. On 2026-11-26 every one
  of the day's unanswered probes followed a deactivation by 4–75 seconds, and they added up to 12
  “verified wedge” recoveries. Each of those re-registers the account and drops its peer
  connections. Such a probe now gives no strike and no verdict; the log says `probe void`.
- **Silence is counted only while an account can hear.** After a sleep the clock starts at the
  wake-up. An account that slept 96 minutes no longer wakes as “deaf 96 m”. The health list in the
  connection dashboard uses the same clock.
- **Just-woken accounts are no longer re-registered on the spot.** The “startup stall” and “all
  accounts unregistered” checks counted the sleep as a stall (“none registered 6362 s”) and
  re-registered all accounts the instant they woke. Both clocks now start at the wake-up.
- **A noise push that wakes the accounts gives them the normal 30-second window.** They used to be
  switched off again 5–7 seconds later, a restore in which nothing could be fetched and no peer's
  request — a file pull, a sync — could be answered. At most one such wake per 10 minutes, as before.
- A consequence: an account that is only ever awake for half a minute at a time is not probed for
  a wedge during those wake-ups. The push-leg check still runs while it sleeps.

### 🫀 Bridge: an “alive” ping that is not a chat message (bridge version 6)

- **`bridge_ping`** (`account`, `peer`, `text`) sends an account message of our own type,
  `application/x-sk-alive`, to another account. It dials the peer like a normal send and is
  committed to no conversation — a regular health signal no longer adds a permanent commit to a
  chat every time it fires. Returns `token`.
- **Only the newest undelivered ping per peer is kept**, so a peer that is unreachable for a day
  does not leave a queue behind.
- **On the receiving phone** a ping goes to a small journal per account (the last 300, kept across
  restarts), is announced by the existing `BRIDGE_EVENT` broadcast with `type` = `alive` and an
  empty `conversation`, and is read with **`bridge_alive_since`** (`account`, `after_seq`,
  `limit`). Fields: `seq`, `type`, `account`, `from`, `text`, `ts`.
- A ping is accepted only from an account that shares a conversation with the receiving one. It
  raises no notification, no sound and no unread count.
- A ping wakes sleeping accounts as a message does. (In `+029` it did not: the push classifier did
  not know the type and dropped every ping after the first as noise.)
- `bridge_describe` reports version `6` and lists both methods.

### 🧩 Patches

- `patches/jami-alive-ping.patch`, guard `SK-ALIVE` in `daemon/src/im/message_engine.cpp`; applied
  after `jami-read-receipt-retry.patch`, whose `isSilent()` it extends.
- The read-receipt change of `+026` is filed upstream as Gerrit change 37384.

### 📦 Packaging

- `withUnifiedPush` flavour, signed release, `arm64-v8a`. Installs over `+026` in place.

---

## `20261106-01+2026-11-18.16-14.g0fc04d09+026` — 2026-11-26

Built on upstream 20261106-01 at `0fc04d099`, the same base as `+021`. Builds `+022` to `+025`
were steps towards this one and are not released separately.

### ↔️ The split between the list and the chat can be dragged

- **Long-press the split and drag** to make the conversation list narrower or wider; both panes
  resize as you move. The list keeps at least 160 dp and the chat at least 300 dp.
- **A thin accent line now marks the split**, with a **handle** in its middle. A sideways move on
  the handle drags at once, without the hold; a plain tap there still reaches what is underneath.
- **Drag or flick the split to the far left and the list is hidden**: the chat fills the screen,
  and the handle waits at the left edge to bring the list back. That strip is kept free of the
  system's back swipe.
- The position, and whether the list is hidden, are remembered across restarts. The single-pane
  layout (folded screen, or split view switched off) is unchanged.

### 📋 Conversation list

- **Thin lines between the rows**, in the existing “List divider” colour role. They start at the
  text rather than under the avatar, and are left out above and below the selected row.
- **A long contact name is cut at the end**, with no “…”. It used to be shortened in the middle
  (“Jan…oda”), which hid the part that tells two names apart. The name in the chat's top bar is
  treated the same way.

### 📎 Sent files

- **A sent file that nobody has downloaded yet says so** after its size: “not downloaded yet”
  (「未転送」, „zatím nestaženo“). In a swarm a file is never pushed — the receiver pulls it, and
  above the receiver's automatic-download size they have to tap it first. Until now such a file
  looked exactly like a delivered one. The note gives way to the progress bar when a pull starts
  and to “handed over” when it finishes. It appears only on files sent since this install began
  recording hand-overs.
- **The status icon no longer covers the time** on a sent file's card: the card moves clear of
  the icon when one is shown.

### ✅ Read receipts are queued instead of dropped

- A read receipt used to be sent only over a message connection that happened to be open at the
  instant the message was displayed. With none open it was dropped — not queued, not retried — so
  the sender kept seeing “delivered” for a message that had been read.
- The receipt now goes through the daemon's message queue like a normal message: a connection to
  the contact is requested and the receipt is sent once they are reachable. A newer receipt
  replaces one still waiting for the same contact and conversation.
- This fixes the receipts **this phone sends**. A receipt you are waiting for is sent by the other
  side, so it improves when that side runs this build too, or when upstream takes the change — it
  has been prepared for GNU Jami's Gerrit.

### 🧩 Patches

- `patches/jami-read-receipt-retry.patch`, guard `SK-READRETRY` in
  `daemon/src/jamidht/jamiaccount.cpp` (also `jamiaccount.h` and `im/message_engine.cpp`); applied
  after `jami-late-answer-redial.patch`.

### 📦 Packaging

- `withUnifiedPush` flavour, signed release, `arm64-v8a`. Installs over `+021` in place.

---

## `20261106-01+2026-11-18.16-14.g0fc04d09+021` — 2026-11-26

Built on upstream 20261106-01 at `0fc04d099`, the same base as `+020`.

### 📨 A message to a sleeping phone no longer waits for the next trigger

- **A late answer now makes the sender dial again at once.** A phone in deep sleep sees a
  connection request only when it next wakes, and answers after the 30 seconds the sender waits.
  That answer used to be dropped, and nothing dialled again until something unrelated did — a new
  message, a call. Measured on 2026-11-26: a message to a phone that had slept for five hours sat
  for six minutes behind an answer that had come 53 seconds after the request, and went through
  the moment a call made a second attempt.
- A late answer is proof that the other phone is awake right now. The sender now asks for the
  message connection again immediately; once it is up, everything waiting for that phone is
  delivered and the conversations are synchronised. At most one such redial per device every 20
  seconds.
- It acts on the sending side, so it helps against any peer that answers late, whatever mode that
  peer runs in. It does not shorten the time a sleeping phone needs before it notices a request.
- With **Settings → Diagnostics → Connections** on, the connection trace shows
  `late-answer redial` each time it fires.

### 🧩 Patches

- `patches/dhtnet-late-answer-redial.patch` adds `ConnectionManager::onLateAnswer` to dhtnet;
  `patches/jami-late-answer-redial.patch` uses it in the daemon. Guard `SK-LATEANSWER` in both.

### 📦 Packaging

- `withUnifiedPush` flavour, signed release, `arm64-v8a`. Installs over `+020` in place.

---

## `20261106-01+2026-11-18.16-14.g0fc04d09+020` — 2026-11-24

Built on upstream 20261106-01 at `0fc04d099`, the same base as `+019`.

### 🎨 UI page

- **Section icons in seven colours, by meaning.** Every section heading and its chip in the index
  is coloured by what kind of thing the section is: yellow for the base and its settings, green
  for controls, light blue for words, heavy blue for the frame and its boxes, orange where the app
  reaches out, red for warnings, rose for a brief flash. All seven are settable under Settings
  pages.
- **Every section has its own icon** — most shared one letter or one drop before.
- **A new default order** in which no two neighbouring sections share a colour. An order you have
  dragged is kept; “Reset section order” shows the new one.

### 🧭 Menu icons

- **Four defaults moved to colours that are easier to tell apart**: call and connect are heavy
  green `#00A550`, message heavy blue `#0066FF`, contact light blue `#00B0FF`, block and mute rose
  `#FF3D7F`. A colour you have set yourself is kept.

### 📡 Connection reliability

- **The quiet-node safety net no longer switches itself off.** On full DHT, three hours of
  unbroken silence earn one precautionary recover. It was filed as a wedge incident and counted as
  a strike, so after two quiet stretches the watchdog stood down until something arrived. It is
  now a plain recover: no incident, no strike, no notification.

### 📦 Download

- `shiroikuma-jami_20261106-01+2026-11-18.16-14.g0fc04d09+020_arm64-v8a.apk` — Android, arm64,
  signed. Installs side-by-side with official Jami.

## `20261106-01+2026-11-18.16-14.g0fc04d09+019` — 2026-11-24

Built on upstream 20261106-01 at `0fc04d099`, the same base as `+003`. Sixteen builds of work:
the whole app in Japanese and Czech, a far steadier relay node, live progress on sent files, and a
reworked call screen.

### 🌐 Languages

- **Full Japanese translation.** Every string of the app is Japanese now — the fork's own and the
  third of upstream's that upstream's Japanese translation lacks (479 strings). No screen shows
  English between Japanese lines.
- **Full Czech translation.** Upstream has no Czech at all; the fork carries one for the whole app
  (about 1,130 upstream strings plus all of its own).
- **The fork's texts moved into resources.** Settings pages, the connection monitor, the contact
  live monitor, data-usage screens, export / import and the diagnostics pages built their texts in
  code and stayed English in every language; they are translated like everything else now.
- **Three Japanese terms unified across the app**, upstream's screens included: proxy is 「代理」,
  device is 「端末」, appearance is 「外観」.
- **Call history entries fixed in Japanese**: upstream's wording treated the call's duration as a
  name. They read 「着信（…）」 and 「発信（…）」 now.
- **“App language” is the first section of the UI page** and the first chip of its index, with a
  globe in light blue. Its row is a pill that opens the language list.
- **A new language list.** Plain rows with the language in its own language, the country beneath
  it and the tag in a column at the right; only the current language sits in a pill. The list
  opens scrolled to the current choice, and right-to-left languages keep the same layout.
- **“Directory”, never “folder”**, in all three languages.

### 📡 Connection reliability

- **No re-registration six seconds into every start.** The first network report of a process was
  counted as a change of network, so every full-DHT account was unregistered and registered again
  while the daemon was still building its first connections. It is no longer a change.
- **A quiet relay is not a wedged one.** On full DHT, silence alone made the watchdog recover every
  few minutes — 45 times a day on a phone that only waits for messages, each time dropping every
  peer connection. Silence now earns one recover per three hours; a stuck message, a failed network
  check and a lost registration keep their own, immediate paths.
- **Automation sends wait for registration**, up to 15 seconds, instead of dialling on connections
  that are being torn down right after a start or during a recovery.
- **A failed fetch is retried once** (daemon patch `SK-FETCHRETRY`). A message announced by a peer
  whose process had just been restarted could sit until the next unrelated notification, because
  the fetch ran into a dead git channel and nothing tried again.

### 📤 Sent files

- **A progress bar on the sent-file bubble**, with the live byte count, in its own colour roles.

### 📞 Call screen

- **Decline, hang up and “End call” are dark red with white inside**; answer is yellow with a
  black phone. Each colour is a settable role under Calls.
- **The “End call” button of an outgoing, still ringing call is visible again** — it was drawn
  underneath the control sheet — and it goes away when the call connects.
- **“Message” on the incoming-call screen is “Quick reply”**, also in the call notification.

### 🔬 Diagnostics

- **“Connection trace” switch**: keeps the daemon's connection lines in a file of the diagnostics
  directory (`connection-trace.txt`, two files of 8 MiB at most). Logcat is not a record — on some
  phones it rolls over within minutes. Off by default.
- **Channel diagnostics** (daemon patch `SK-CHANDIAG`): every incoming channel request with its
  verdict, and the swarm channel's results, under the Connections switch.

### 🤖 Automation bridge

- **`bridge_mark_read`** clears a conversation's unread state (bridge version 5).

### 🎨 UI page

- **Every chip of the section index carries its section's icon.**
- A new colour role, “Section icon: words, written and read”.

### 📦 Download

- `shiroikuma-jami_20261106-01+2026-11-18.16-14.g0fc04d09+019_arm64-v8a.apk` — Android, arm64,
  signed. Installs side-by-side with official Jami.

## `20261106-01+2026-11-18.16-14.g0fc04d09+003` — 2026-11-22

Built on upstream 20261106-01 at `0fc04d099`, the same base as `+002`. This release puts back four
things the 20261106-01 sync had quietly undone in the main view.

### 🩹 Restored after the sync

- **The message entry box has its yellow outline again.** Upstream turned the box into a Material
  card, which discards the foreground drawable our outline was attached to — so the entry row sat
  on the black background with nothing around it. The outline is now the card's own 2 dp stroke,
  in the field-frame colour role.
- **Chat-list rows grow with the font again.** The row had gone back to a fixed 72 dp, which cut
  the second line (date and last message) in half at larger font sizes. It is a 72 dp minimum
  once more.
- **Reaction chips** keep their 5 dp corners instead of upstream's new 12 dp.
- **Incoming file cards** reach the right edge of the screen again instead of stopping at
  upstream's new guideline.

## `20261106-01+2026-11-18.16-14.g0fc04d09+002` — 2026-11-22

**New upstream base: 20261106-01** (“Tim Berners-Lee”), at upstream commit `0fc04d099` — 62 commits
past the previous base, with the daemon moved to `e62dfcb61` (OpenDHT 4.4.99, a newer dhtnet,
libgit2 1.9.7) and the build moved to NDK 30.

The base is four commits short of upstream's tip on purpose. Those four (synchronised contact
labels, share-to-label, a dependency bump and a daemon-API rename) call daemon functions newer
than the daemon upstream itself pins, so upstream's tip does not compile. They arrive with the
next sync.

### ⬆️ What comes from upstream

- **Threaded replies** — an optional, per-account setting that groups replies under their root
  message, with a thread view, separate drafts per thread and a slide transition. Off by default.
- **Feeds** — a new one-to-many conversation type with its own creation dialog, details page and
  leave action.
- **Filter chips above the chat list** — All, Contacts, Groups, Feeds; joinable Feeds are kept
  apart from invitations.
- **Conversation permissions** — the daemon now grants messaging, calls, invitations and
  moderation per conversation, and the app enforces them.
- **Conversation details** gain a contact section with the dates a contact was added and a
  conversation joined; the chat-list long-press menu gains a details entry as its first row.
- **Redesigned contact picker** — a dialog instead of a bottom sheet; whole groups can be picked
  and expand to their members.
- **Linked devices** show their label and last-sync time.
- Fixes: shared content is no longer sent again when a conversation is restored; a stationary
  peer stays on the location map; playback mute follows the daemon during calls; invitations keep
  their received order; attachments group within an hour and their bubbles follow the text
  bubbles' corners.
- A Huawei HMS push flavor now exists upstream; this fork still ships the UnifiedPush flavor.

### 🔁 Two of our fixes are now upstream's

- The typing indicator that froze after one bounce (Gerrit 36780) — our copy dropped out.
- The name-server resolver that replayed a failed DNS lookup forever (Gerrit 36883, daemon
  `ab2dd626d`) — `jami-namedirectory-reresolve.patch` is retired.

### 🧩 Where the fork deliberately differs from this upstream

- **The daemon still starts with the app process.** Upstream now starts it on the first screen or
  push, to gate its new device-transfer backup. The fork's watchdog, automation receivers and boot
  path rely on a running daemon, so the eager start stays; upstream's backup gate is kept.
- **Connectivity handling stays in the fork's own code** — debounced network settle, reconnect on
  a path change, the recovery watchdogs. Upstream's new network monitor is not started, since
  running both would report every change to the daemon twice.
- **Push wake-up keeps the fork's path** (expiry wake, noise cooldown).
- **Contact picker:** upstream's redesign, with our “Create new SWARM chat” label. Its rows no
  longer show presence dots.
- **Chat-list long-press menu:** upstream's new order, then “Look up name” and “Contact live
  monitor”.

### 🌌 Wallpaper

- Upstream's release replaced the welcome background — the picture behind the empty conversation
  pane — with grey-mauve waves. **The starry dark-blue sky is back**, and stays across future
  syncs.

### 🎨 Look

- Upstream's changed and new night colours are folded into the single colour table; the new
  message-input card colour is fed from a role, so the input bar stays black with its yellow frame.
- The new upstream screens (filter chips, Feed dialog, thread view, contact picker, contact
  section) take their colours from the role loader only. They have not had a pass of their own
  yet.

### 🌐 Languages

- Every string upstream changed or added that names the app says **白い熊 GNU Jami** again, in
  all locales.

### 🔧 Under the hood

- `jami-audio-rtp-diag.patch` is regenerated for upstream's rewritten SRTP read path.
- All other daemon and contrib patches apply to the new sources unchanged.
- Contrib libraries that did not move in this sync are still NDK 29 builds, linked into an NDK 30
  daemon.

---

## `20261023-01+2026-10-23.21-54.g2ec1e272+036` — 2026-11-22

Same upstream base (**20261023-01** at `2ec1e272f`). Two paragraphs of the connectivity help page
described a state that ended in July; both are corrected, in English, Japanese and Czech.

### 📖 Connectivity help page

- **Automatic recovery** said that every account runs on the full DHT by default. It now says what
  the app does: every account rests on the **DHT proxy with push**, the light mode that lets the
  phone sleep; the watcher switches to the full DHT only when the proxy link itself is stuck, and
  returns once things are stable. The hexagon in the search bar switches the mode by hand.
- **Data cost** said that both modes cost the same. It now gives the later measurement, on a phone
  with four accounts: the full DHT moved about **110–130 MiB/h** and never let the Wi-Fi radio
  sleep; the proxy with push rests at about **5 MiB/h**. The earlier one-account figures
  (24.6 vs 24.8 MiB/h) are kept in the text, named as the older measurement.

Everything else is as in `+034`, including its note that the new look was compared in the code
and not walked through on a device before release.

---

## `20261023-01+2026-10-23.21-54.g2ec1e272+034` — 2026-11-22

Same upstream base (**20261023-01** at `2ec1e272f`). The whole look is rebuilt on one rule set —
black ground, yellow ink, outlined pills — and **every colour is now a setting**.

This build changes how every screen is painted. It was compared in the code, not walked through
on a device before release; if something looks wrong, `+032` is the build before the change.

### 🎨 Every colour is a role you can set

- **58 new colour roles**, beside the existing per-surface ones, each a swatch row on the
  **白い熊 GNU Jami UI** page in the section of the thing it colours: Foundation, Text, Buttons,
  Dialogs, Switches, Check boxes and radio buttons, Menus, Lists, Text fields, Top bars, Call
  controls, Settings pages. *Default* in the picker resets one role; **Reset all colours** is in
  the new Maintenance section. The roles travel in the colours export.
- Nothing is pinned any more: a colour you change reaches every screen, upstream's own included.
  Screens already open rebuild themselves when you leave the page.
- The app is dark only now.

### 🔘 Buttons, switches, dialogs

- **Every button is an outlined pill**, never a filled slab. The main action of a block has a
  thick frame and bold label, every other a thin frame; a button that deletes is red.
- **Floating buttons are outlined rings.**
- **Switches** are an outlined track with a solid dot — right and bright when on, left and a
  darker yellow when off.
- **Dialogs:** the confirming button is the main pill; a dialog that deletes confirms in red.

### 📋 Menus you can read at a glance

- Every action in a menu is a **full-width pill with its icon**, and the icon is coloured by what
  the row does: call green, message blue, contact cyan, share orange, block magenta, delete red.
  A **›** marks a row that opens something; “Split view” has a switch instead of a check box.
- This covers the ⋮ menus, the + menu of the message bar, the message long-press menu, the chat
  list's action sheet and the member sheet. A long menu opened low on the screen shifts up
  instead of scrolling.

### 📞 Call screen

- Answer, decline, hang-up and the in-call controls are **unfilled rings**. A control that is
  switched on (muted, camera off) turns red instead of filling yellow.

### 🧭 The UI page

- **App settings** is the first, thick pill.
- A **chip index** under it: tap a chip to unfold its section and jump there; long-press and drag
  to reorder the sections.
- **Every section folds**, and starts folded. Order and fold state are remembered and exported.
  **Reset section order** is in Maintenance.
- Rows have icons; actions are pills that hug their label; a switch and its explanation share a
  thin rule down the left.

### ⚙️ Settings lists

- The icons of the app's settings and account-settings rows are coloured by what the row does.

### Not covered

- The Android TV screens are left as upstream draws them.

---

## `20261023-01+2026-10-23.21-54.g2ec1e272+032` — 2026-11-22

Same upstream base (**20261023-01** at `2ec1e272f`). Sent files now tell you what happened to
them, the automation bridge can accept and track a file on a phone nobody touches, and a leak that
kept every old screen in memory is closed.

### 📎 Sent files: you can see the bytes leave

- The blue “sent” marker only ever meant that the file *message* was fetched; the bytes are pulled
  by the receiver afterwards, and stock shows the sender nothing about that. The sent-file bubble
  now does: while a peer pulls the file its size line reads **`4.2 MB / 17.84 MB`**, refreshed about
  once a second, and once every byte has been written it reads **`17.84 MB · handed over`**
  (転送済 / předáno). The note survives a restart.
- What it proves: all bytes were written to a peer. Not which peer in a group, and not that the
  receiver verified its copy. The “sent” marker itself is unchanged.
- The byte count comes from a new daemon patch, `patches/jami-outgoing-transfer-progress.patch`:
  the daemon announces the start of a pull to the sending side and serves the running count.

### 🤖 Automation bridge, version 4

- **`bridge_accept_file` works on a phone nobody opens.** It used to need the message loaded in the
  app's own UI model, which a headless phone does not have after a restart — the accept answered
  “message not loaded” and silently never happened. It now asks the daemon directly, and returns
  what it did: `started`, `already_finished` or `already_in_progress`, or the daemon's refusal.
- **New `bridge_file_state`** reports where a received file stands — `finished`, `in_progress`,
  `accepted_waiting`, `not_started` or `unknown` — with bytes of total. An interrupted download
  shows as `accepted_waiting` with a byte count, and the next accept resumes it.
- The sending phone's journal now also gets a `TRANSFER_ONGOING` file-state event when a peer
  starts pulling.

### 🧠 Memory

- **Leak fixed:** the accessibility manager kept a listener from every home screen ever created,
  and with it the whole screen — seven Activity objects for one live screen after a week. The
  listener is now removed with its view; the backup panel releases its screen the same way.
- **Settings → Diagnostics → Memory & retained screens** (off by default): one line per interval in
  `diag/memory-log.txt` with total, native, Java and graphics memory, the thread count, the live
  screens and any destroyed screen that is still held, named by class.

### 🛟 Watchdog

- A “not receiving” verdict on an account is now withdrawn by anything that arrives after it, even
  while the accounts sleep. Before, such an account could wake still marked red and be escalated to
  a full proxy cycle although it had just received.

### 🎨 Settings and texts

- Every row on the 白い熊 settings pages that does something when tapped is now an outlined pill;
  the main action of a block has the thick frame.
- The hexagon's long-press tooltip and its help paragraph described the shapes the wrong way round.
  Corrected in English, Japanese and Czech: **filled = DHT proxy, hollow = full DHT**.

### 🩺 Diagnostic files

- No diagnostic file is named `*.log` any more — EMUI's nightly clean-up deletes those. The
  watchdog incidents file is `watchdog-incidents.txt`; old files are renamed on start.

---

## `20261023-01+2026-10-23.21-54.g2ec1e272+026` — 2026-11-03

Same upstream base (**20261023-01** at `2ec1e272f`). Diagnostics get one home for their files,
Export and Purge, and a cleaner page.

### 🩺 Diagnostic files: one directory, Export and Purge

- Every diagnostic file now lives in `/sdcard/Android/data/shiroikuma.jami/files/diag/`, readable
  over adb: the recovery log, watchdog incidents, both data-usage logs, the presence map — and the
  push log, which used to sit in private storage that adb cannot read. Files from earlier builds
  are moved in on the next start; where a file already exists there, the old content is put in
  front of it.
- **Settings → Diagnostics → Diagnostic data** shows what is stored: number of files, size, and the
  first and last time logged.
- **Export diagnostic data** opens a directory picker (first at `/sdcard/tmp`, afterwards wherever
  you exported last) and copies every file into a new `<date_time>_jami-diag` directory there.
- **Purge diagnostic data** first lists each kind of file with its count, size and time span, then
  each day with its span, then the total — and deletes only on **Delete forever**.

### 🎨 A tighter, gold-on-black Diagnostics page

- Minimal spacing; sub-headings underlined with their switches indented beneath them.
- Each switch is a block: a bold label, its log tag in bold monospace with where the lines go, and
  the description — grouped by a thin gold rule down the left. No grey text left: explanations are
  dim gold, a new colour you can change as **Settings notes & item rules** in the Settings group of the 白い熊 GNU Jami UI colours.

---

## `20261023-01+2026-10-23.21-54.g2ec1e272+023` — 2026-11-02

Same upstream base (**20261023-01** at `2ec1e272f`). One fix: messages that arrive late keep their
right place in the chat.

### 🔀 Late messages no longer stay at the bottom of the chat

- After a connection gap, a contact's late messages were shown as the newest ones, and messages
  you sent afterwards landed **above** them; only restarting Jami put them in order. The daemon
  merged them correctly and, since the 20261023-01 daemon, re-sorts the history after such a merge
  and reports each moved message — but the app recorded the new positions without moving anything
  on screen. It now re-orders the chat as soon as the re-sort arrives. Filed upstream as Gerrit
  change 36892.

---

## `20261023-01+2026-10-23.21-54.g2ec1e272+022` — 2026-11-01

Same upstream base (**20261023-01** at `2ec1e272f`). A fix for conversations that would not open
and messages that were silently lost, the bridge's wake on send, a quieter watchdog for quiet
accounts, and every diagnostic moved behind a switch.

### 🧊 Conversations open again, sent messages are no longer lost

- In a large chat (one had 5,621 messages) with many replies, Jami asked the daemon for the full
  history once **per quoted message it had not loaded yet** — hundreds of requests, each holding a
  daemon worker while waiting for the same lock. For an hour every worker was taken: opening any
  conversation showed an empty screen, and messages sent in that time were never saved. Now at most
  one such request per conversation runs at a time; the same startup issues 3 instead of 698, and a
  conversation opens in tens of milliseconds. Filed upstream as Gerrit change 36891.

### 🤖 Bridge

- A message the bridge sends from a sleeping account now wakes the accounts first and keeps them up
  for 90 s, so it is delivered within about a second instead of at the next unrelated wake-up.
  `bridge_wake` and `bridge_keep_awake` are available but not needed.
- Messages the daemon re-reports without an actual edit are no longer recorded as edits.

### 📶 Watchdog

- A push addressed to an account's own key now counts as proof that the account is receiving. A
  quiet account whose contacts were offline was being called "not receiving" and had its proxy
  cycled again and again, although its own key was receiving pushes.

### 🩺 Diagnostics

- **Settings → Diagnostics**: every diagnostic, native and app-side, behind its own switch, grouped
  in sub-sections, all **off by default** and free while off; a switch takes effect within 3 s.
- New traces for chasing the problems above: history loads and the daemon's worker pool, each fetch
  and merge from a peer and which message a new one attaches to, and each step of opening a chat.

---

## `20261023-01+2026-10-23.21-54.g2ec1e272+008` — 2026-10-31

Same upstream base as `+001` (**20261023-01** at `2ec1e272f`). A bot bridge for 白い熊 自由作業盤,
a fix for name search, a fix for chats between your own accounts on one phone, and the invitation
screens in the theme.

### 🤖 A bridge for 白い熊 自由作業盤

- New `bridge_*` methods on the caller-verified automation door: list accounts and conversations
  (title, members, roles), **watch** a conversation, catch up on its events, **send text** with a
  reply-to (returns the new message's id), **send a picture or file**, accept an incoming file and
  open it, **react** and remove a reaction, **edit** and **delete** a message, ask a message's
  **delivered / read** state, and a **status** call (registration, connection mode, last incoming
  activity, last push, watchdog state).
- Every message, edit, reaction, file-transfer state and per-recipient delivery state of a watched
  conversation goes into a journal and out as a live broadcast to the one signed 自由作業盤 app.
  Conversations that are not watched never leave Jami.

### 🔎 Name search works again after a bad start

- When Jami started before the network was usable, every name search failed instantly — even
  your own name — until the app was restarted. The daemon looked the name server up once and
  replayed that failure forever. A search that gets no answer now makes the next one look the
  server up again. Filed upstream as Gerrit change 36883.

### 🔗 Your own accounts can reach each other again

- Messages between two accounts on the same phone never arrived, and accepting an invitation
  from one of your own accounts stayed on "Downloading conversation from peer". Our own July
  change, which lets co-resident accounts find each other inside the app, never filled in the
  sender's id, so every secure handshake between them was rejected. Fixed; delivery is immediate.
- Every failure on the connection path (no answer, connection setup, handshake, certificate and
  trust checks) is now named in the log; release builds used to discard all of them.
- The watchdog no longer resets the network or switches all accounts off and on because a message
  to one of your own accounts is stuck — no network recovery can help there, and it made things
  worse.

### 🎨 Invitations in the theme

- The "Invitations received" card, its list and the Block / Decline / Accept bar are black with
  yellow text and borders instead of light blue and green. Their colours are settable under
  UI fonts & colors → Invitations.

---

## `20261023-01+2026-10-23.21-54.g2ec1e272+001` — 2026-10-27

Rebased onto upstream **20261023-01** at `2ec1e272f` (16 app commits), with a new daemon
(`1c057cc2` → `a39cf186`, 82 commits). A fix for the typing indicator, a fix for swiping in the
media viewer, and our native patches carried onto the new dhtnet. The build counter restarts at
`+001` because upstream's version moved (versionCode 506 × 10000).

### 💬 Typing indicator animates again

- When the other person is typing, the three dots **bounce continuously** again. Since an August build
  they played one bounce and then sat frozen, or flickered between frozen dots and
  nothing while the typing signal came and went.
- The cause was our own fix for a leaked animator (one never-ending animation per typing
  indicator ever shown, still drawing frames in the background — up to 76 % of a core). It
  removed the restart that keeps the bounce going, on the mistaken belief that the animation
  repeats by itself; Android ignores the repeat setting on an animator set.
- The restart is back, but bounded: one animation per indicator row, running only while the row
  is on screen and the conversation is visible. Leaving the chat, scrolling the row away or
  backgrounding the app stops it, so the leak stays fixed.

### 🖼 Swiping in the media viewer works again

- In the full-screen viewer, swiping had stopped moving to the next picture — it stayed on the
  one you tapped. Upstream's fix for a loading deadlock stopped asking the daemon for each file's
  state while reading search results, so every picture arrived as "not downloaded" and the swipe
  set, which only takes completed files, kept none of them. The viewer now asks for each
  candidate's state itself, in the background, before building the set. The same-direction scope
  and the swipe-down behaviour are unchanged.

### 📶 From upstream 20261023-01

- **Calls survive a network change** — Wi-Fi to cellular and back: the app follows the default
  network and tells the daemon when its addresses change, so a running call moves to the new
  network; if the network drops mid-call, accounts stay up for 20 s while the daemon recovers the
  call. This is merged with our own connectivity watchdog: the address-change handover is
  upstream's, while a lost network is still debounced the fork's way rather than switching the
  accounts off at once, and the 20 s in-call grace applies to it too.
- **Calls and conferences:** incoming-call notifications no longer hang when Android's Telecom
  never answers; conference participant names are looked up without blocking the daemon thread
  (a possible stall); the host is kept after a peer leaves, and a conference that drops back to a
  1:1 call keeps being tracked.
- **Video:** a reworked camera pipeline (stable encoding, a camera that resumes when a preview is
  reopened, no crash when the camera service is unavailable), the camera state restored after
  screen sharing, and a new **conference resolution** setting (720p / 1080p / 1440p) under the
  video settings.
- **Daemon:** FFmpeg 6.1.3 → **9.0.2**, webrtc-audio-processing → **2.1**, swarm fixes (rotating
  away from a member gone offline, recovering a swarm with nothing left to track, merge-order
  fixes), SIP handover and re-INVITE fixes, a refreshed CA certificate bundle.

### 🔧 Native patches

- All nine of our dhtnet patches were regenerated for the new dhtnet `818ed67e`, which made the
  UPnP mapping wait configurable. Our **UPnP circuit breaker** keeps working on top of it, and no
  longer counts a miss in upstream's new "use only mappings already open" mode (used for call
  handover) as a router failure — that would have tripped it on every handover.
- The daemon's own patches (SIP log level, current-CRL-only, trust-request confirm-once, swarm
  redial backoff, absent-device de-listing, DHT client mode, audio diagnostics, capture-silence
  fallback, import APIs) all carried over unchanged.

---

## `20260925-01+2026-10-18.01-53.gf0de9b4c+006` — 2026-10-20

Decline an incoming call with a predefined message. Same base as `+005` — upstream
**20260925-01** at `f0de9b4c9`; the `daemon` submodule gitlink did not move (`1c057cc2`), so the
native side is unchanged.

### 💬 Decline with a message

- **On the incoming-call screen**, a new **Message** circle (メッセージ / Zpráva) sits beside
  **Silence**, directly above Accept; Silence, Decline and Accept are unchanged. One tap silences
  the ringtone and the vibration immediately, then opens a picker.
- **The picker** lists the caller's own replies first, under *For &lt;name&gt;*, then the general
  ones, and has a text box with a send arrow for a new message.
- **Choosing a reply** refuses the call and sends the text into the caller's own conversation —
  their 1:1 swarm, or the contact itself for a legacy contact. The send runs in the background
  service, so it completes even though the call screen closes at once. A call declined this way
  raises **no missed-call notification**.
- **A newly typed message** is offered for keeping once it has gone: **For everyone**,
  **Only for &lt;name&gt;**, or **Don't save**.
- **Cancelling** the picker leaves the call ringing, silenced, so Decline and Accept still work.
- **The incoming-call notification** gets a third action, **Message**, which opens the call screen
  straight onto the picker.
- **Settings — 白い熊 GNU Jami UI → Call decline messages:** the general list inline (tap a
  message to edit, move up, move down or delete it; **+ Add message**), one row per contact that
  has replies of their own, and **+ Add contact…** with the same searchable contact picker as the
  home-screen shortcuts.
- **A contact's details screen** has a **Decline messages** row for 1:1 chats, opening the same
  editor for that contact.
- The general list starts with four replies in the phone's language (English, Japanese, Czech);
  after that they are ordinary items, and deleting them all leaves the list empty. Per-contact
  lists are keyed by the contact's Jami id, so they follow the contact across all accounts.
- The replies travel in **保存復元** backups with the app settings.

---

## `20260925-01+2026-10-18.01-53.gf0de9b4c+005` — 2026-10-20

Forward a whole series of pictures in one go, on upstream's fix for a file-acceptance freeze.
Rebased onto upstream **20260925-01** at `f0de9b4c9` (three new commits; upstream's version
number did not change, so the build counter carries on from the unreleased `+002`–`+004` rather
than restarting — a `+001` would have had a lower version code than the build already installed).
The `daemon` submodule gitlink did not move (`1c057cc2`), so the native side is unchanged.

### 🖼 Select several messages and forward them at once

- The long-press menu of a text, picture, video, voice message or completed file gets a new item,
  **複数選択** (Select messages / Vybrat zprávy). It starts a select mode with that message
  already marked; every existing menu item is unchanged.
- While selecting, a **tap** anywhere on a message's row — or a long hold — marks or unmarks it,
  and marked rows carry a translucent wash of the message-menu border colour. Links and players
  in the chat do not react to taps while the mode is on.
- A bar over the composer, in the message-menu colours, shows **✕**, the count (“3件選択”) and
  **「白い熊 GNU Jami」で共有**. Back or ✕ leaves the mode.
- Sharing opens the in-app share picker once for the whole selection. Pictures and files are sent
  to the chosen chat **one after another, in conversation order**; marked texts are joined, one
  paragraph per message, into that chat's composer.
- Receiving side, which also benefits shares from other apps: a multi-file share now sends its
  files sequentially (so they keep their order, and one failure no longer stops the rest), and a
  text item that rides along with files reaches the composer instead of being dropped.

### 🧬 Upstream since `1fb1959e6`

- **File transfer:** accepting a file — from the chat or its notification — no longer calls the
  daemon's `downloadFile()` on the main thread, where a conversation with a large history could
  freeze the app for seconds (Google Play “not responding” reports); automatic acceptance stops
  blocking a computation thread too.
- **Collaborative documents:** a picture whose bytes arrive after the document names it now
  appears when they land, instead of staying blank until the document is reopened.
- **CI:** upstream's Docker image creates its `jenkins` build user again (no effect on this build).

---

## `20260925-01+2026-10-07.17-04.g1fb1959e+001` — 2026-10-11

Rebased onto upstream **20260925-01** (`1fb1959e6`), with the creation of a new swarm chat
repaired at both ends: the crash behind it, and the sheet that hid its button. The `daemon`
submodule gitlink **moved** (`ce493889` → `1c057cc2`), so the whole native side — the daemon and
the patched contrib packages — was rebuilt from source, and every fork patch was re-verified
against it.

### 👥 Creating a swarm chat works again

**The crash.** Tapping the create button in the new-swarm sheet did nothing visible — the app
quietly crashed a background thread instead. The daemon announced the new conversation through
its callback dispatcher a moment *after* the client went looking for it, so the lookup found
nothing and a forced non-null dereference threw. Upstream fixed exactly this race in
`71b8496e2` ("conversation: fix crash when creating a group"), which this sync brings in; a
failure to create is now reported with a message instead of a silent crash.

**The sheet.** The contact picker opened half-collapsed, so its only action button sat below the
fold until the sheet was dragged up — and it was labelled with upstream's generic **“Select
contacts”**, which does not say that it creates anything. The sheet now opens **fully expanded**,
and when it is creating a new swarm, the button reads **“Create new SWARM chat”** (新しいSWARMチャットを作成,
Vytvořit nový SWARM chat). When the same picker is used to add members to an existing chat, it
keeps “Select contacts”.

### 🧬 Upstream 20260925-01

- **Daemon (34 commits):** OpenDHT 4.4.1; a newer pjproject; libarchive built without crypto;
  swarm hardening — device certificates verified against member keys, conversation ids and
  file ids validated before they touch the disk, clone retries backed off per device rather than
  per conversation, tracked device sets kept current; SRTP replay protection and AES-256 suites;
  ICE request authorisation fixed; several call-path lock-order and lifetime fixes; collaborative
  documents replicated on announcement.
- **Client:** the collaborative-document editor gains font and size choices, a reorganised
  formatting toolbar and the document's own name in its title.

### 🔧 Fork patches carried forward

- `patches/jami-delist-absent-devices.patch` regenerated: upstream's “keep tracked device sets
  current” moved the device-offline handling into a new branch, and our absent-device de-listing
  (`SK-ABSENTNODE`) now sits inside it. Its behaviour is unchanged.
- Every other daemon and contrib patch (pjproject ×2, OpenDHT ×5, dhtnet ×9, plus the
  daemon-source patches) applied cleanly to the new sources unchanged.

---

## `20260904-01+2026-09-08.19-39.g3c0b6ad1+020` — 2026-10-08

A real incoming-call screen, and the end of a push outage the app inflicted on itself. Builds
`+018` and `+019` carried the push-leg repairs and were never released, so they are folded in
here. The `daemon` submodule gitlink did not move, so the native side is byte-for-byte what `+014`
shipped.

### 📞 A full-screen incoming-call screen, with a Silence button

Until now an incoming call arrived as a notification and nothing else. The reflex explanation —
a missing permission, a missing full-screen intent, a missing lock-screen flag — was wrong on
every count: the manifest already declared `USE_FULL_SCREEN_INTENT`, the notification already
carried `setFullScreenIntent(viewIntent, true)` on a high-importance `CATEGORY_CALL` channel, and
the call activity already asked to show over the lock screen and turn the screen on.

It was plain Android semantics. **A full-screen intent goes full-screen only when the device is
locked or the screen is off; unlocked, it is documented to degrade to a heads-up notification** —
and unlocked is the case one actually lives in. (The newer Android 13/14 restrictions on
full-screen intents never applied here at all: this phone runs Android 12.)

So the screen is now raised directly on an incoming ring, the way the Android TV path always did —
once per call, so pressing Home while it rings does not fight back. That is a background activity
start, which Android 10 and later allow only to an app holding **"display over other apps"**, so
the fork now asks for it on every app open until it is granted; the grant can be withdrawn at any
time, and without it a call falls silently back to the bare notification.

The screen itself keeps the caller's picture above a large centred name and rebuilds the controls
as three circles: **Decline** left, **Accept** right, and **Silence** centred above them.

**Silence stops the ringing without refusing the call** — the caller keeps hearing it ring, which
is the whole point. Muting the ringtone alone would not have done it: the buzzing comes from the
notification channel, not from the audio layer, and a channel's vibration cannot be changed once
the channel exists. So a silenced call is quietly re-posted on a non-vibrating twin channel, in
place, under the same notification the call's foreground service is already holding.

### 🔑 A failed token re-fetch no longer kills push for two days

**The fault.** The repeat-wedge remedy deleted the FCM token and fetched a new one. It had to
delete first — Firebase has no "replace", and asking for a token hands back the one you already
have — but the re-fetch had no handler beyond a log line, and it runs from the recovery path,
which is to say *exactly* when the network is already wedged and a re-fetch is at its most likely
to fail. On 2026-09-22 it failed. The last push ever received landed 65 seconds later, and push
stayed dead for **48 hours**.

**Why it lasted.** Nothing retried, and nothing noticed. Worse, the app went on holding the
deleted token: because `pushToken` stayed non-null, every "is push usable?" check downstream kept
passing, and the accounts kept being put to sleep with nothing left that could wake them. The one
detector that could have caught it was reachable only from the uniform-wedge escalation, and that
escalation is vetoed on every pass by the proxy's own good health — the DHT proxies' HTTPS listens
stay perfectly fine while push is dead. **Those are two different legs:** a proxy answering a
listen says nothing about whether anything can still wake the phone. So the app logged
*"no push for 108699s"* and concluded *"receive path alive, no recover"*, for two days.

**The fix, in four parts.** The re-fetch retries on a backoff, and when the retries are exhausted
the token is declared **lost** rather than held — a token that can never receive anything again
must not go on authorising background sleep. Push evidence older than 45 minutes no longer
authorises sleep at all. A standing push-leg death watch asks the only question that matters — is
anything still waking us? — from the push clock alone, where no proxy-health evidence can veto it,
and it runs even while the accounts are asleep, which is precisely the state it exists to break.
And manual **Recover** now repairs the push leg too, graded cheapest first: fresh does nothing,
stale re-announces the token already held, and past-dead replaces it and goes straight to
streaming mode rather than making anyone wait out the standing watch.

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
