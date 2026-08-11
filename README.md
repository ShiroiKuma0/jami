<div align="center">

<img src="jami-android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="120" alt="白い熊 GNU Jami app icon" />

# 白い熊 GNU Jami

**Private, peer-to-peer messaging & calling — themed and tuned to taste.**

A fork of [GNU Jami](https://jami.net) with **major additions**: a **fork-wide rebrand** that makes
the app name itself correctly in all 100 languages, a full yellow-on-black theme, a
per-element **UI fonts & colours** system with an RGBA colour picker, deep **connectivity
resilience** — a **three-fold connection mode** (full DHT / Firebase / UnifiedPush) with an
**adaptive push→streaming fallback** that survives a dead push leg, a self-healing recovery watchdog
with **probe-verified** health, and a live **connection monitor** (per-contact too) — a swipeable
**media viewer** with hide/restore, **protected contacts** with vague notifications (masking media
too), **in-app message forwarding**, token-gated **automation intents** plus **保存復元 batch-backup automation**, a **DHT data-efficiency fix** that cut the fork's own DHT footprint ~80x, a live **data-usage
meter** with unattended logging, smarter **registered-name** lookups, **home-screen shortcuts** straight to a chat, a call, or a **hands-free call on speaker**, a **split-view** toggle, and **保存復元** — a one-file backup carrying every
setting, every account, and the **entire chat history with its attachments** to a new phone — a
**chat-files panel** that shows what those chats are actually storing, down to the individual
picture, with a **soft delete** that frees your space without touching anybody else's chat, and a
worked-over **calling** experience: **call recording** that lands in the chat as a playable message, a
visible **call timer**, a speaker choice that survives pick-up, and a fix for a device class whose
microphone hands the app nothing but digital silence.

**📥 Latest release: [`20260807-01.2026-08-07.g46f48193+002`](https://github.com/ShiroiKuma0/jami/releases/latest)** — [all releases & APK downloads »](https://github.com/ShiroiKuma0/jami/releases)

</div>

---

## Installs side-by-side with official Jami

This fork ships as **`shiroikuma.jami`** (label **白い熊 GNU Jami**) with its own FileProvider
authority, so it installs **right next to** the official `cx.ring` from F-Droid / Play — two apps,
two accounts, no conflict. It's signed with its own key, so it never installs *over* official Jami
(Android refuses mismatched keys); grab the APK from the [releases page](https://github.com/ShiroiKuma0/jami/releases)
and install it as its own app.

Built for **arm64-v8a**; the C++ Jami daemon and all of its contrib are compiled from source. Jami
itself is unchanged underneath — same protocol, same distributed network, same end-to-end
encryption — this fork only adds a thick layer of personalization and a few reliability and
automation tools on top.

## 🏷 It calls itself by name — in every language it ships

Renaming an app is not renaming its label. Upstream's translations write "Jami" into the running
text of roughly four and a half thousand strings, so on a phone set to Czech the About page read
**"O Jami"**, and the fork's identity survived only in the launcher icon. Every one of those
mentions now reads **白い熊 GNU Jami**, across all **100 locales**.

Grammar is preserved rather than flattened — the suffix carries the case, the brand carries the
name: Finnish `白い熊 GNU Jamilla`, Hungarian `白い熊 GNU Jamit`, Basque `白い熊 GNU Jamira`,
German `白い熊 GNU Jami-Konto`. Languages that never spelled the name in Latin at all are covered
too, including the ones that transliterate into their own script — Serbian `Јами`, Korean `자미`,
Japanese `ジャミ`, Hebrew `ג'אמי`, Tamil `ஜாமி` — and Bulgarian, which was still shipping the app's
**pre-2018 name, "Ring"**.

Left deliberately untouched: the credits and sponsor lines that name upstream and its team, the
`JamiId` identifier, and every `jami.net` link. Crediting Savoir-faire Linux with this fork would
be a lie, and the branding stops where the truth does.

## 🎨 Yellow-on-black theme

The fork's signature look: black backgrounds with a `#FFFF00` foreground, applied consistently
across the chat list, conversation bubbles, toolbars, the search and compose bars, **every dialog
app-wide** (including Android's own preference dialogs — yellow-outlined pill buttons on a bordered
black card), **every bottom sheet** — contact picker, member actions, QR share, colour and emoji
pickers, the in-call speaker chooser and the rest — every **pill button**, file and
link-preview cards, unread/pending badges, and the
account-selection dialog. Generated (no-photo) avatars are drawn black with yellow initials and a
yellow ring; presence dots, the home top bar and the expanded search view are themed to match. The
settings screen carries the same black/yellow treatment, down to the switches.

## 🔤 UI fonts & colours

A single **UI fonts & colours** screen lets you restyle every text surface independently. For each
element — chat text, conversation title, chat-list name / preview / date, message time, link-preview
title/description/domain, file name, settings text, the search hint and more — you can pick a **font
family, weight and size**, *and* set its **text, fill and border colours**.

- **RGBA-slider colour picker** with a live preview swatch and a two-way `#AARRGGBB` hex field, so
  every colour (including translucency) is exactly what you choose.
- **Bubble, card and badge fills + borders** are recoloured at runtime — sent/received bubbles,
  link-preview and file cards, and the account badge.
- **Import your own fonts** (`.ttf` / `.otf`) straight from storage — no permission needed.
- Every control **defaults to the current palette value**, so anything you leave unset looks exactly
  like stock. Changes apply live when you leave the screen.
- Line-driven sizing keeps larger fonts legible — list rows, avatars and the message status icon grow
  with the text instead of clipping, and the **status-icon height** is itself adjustable.
- The **connection-monitor state colours** (connected/healthy, connecting, problem) are settable here
  too, and an **app-language override** lets you run the UI in a language independent of the phone
  locale.

Reachable from the chat-list overflow (**UI fonts & colours**) and from **Settings → Appearance**.

## 💾 保存復元 — move to a new phone with your history intact

One file carries **everything**: your settings, your Jami **accounts**, your **entire chat history**,
and the **attachments** inside it. Stock Jami re-clones each conversation from whoever else still
holds it — slow, data-hungry, and permanently lossy for a self-chat or a one-to-one whose peer wiped
their side. Here the history travels with you and the daemon finds it already on disk, fetching only
what happened since the backup.

Tick the categories — **Accounts**, **chat texts**, **chat files** (off by default, they're the bulk),
fonts & sizes, colours, UI behaviour, connectivity, automation & protected contacts, app settings —
and Export. The archive is written to a plain directory you choose, verified entry by entry, and only
then given its final name; a partial write can never masquerade as a backup. Both directions run in a
foreground service with a wakelock and live counts, and either can be stopped mid-run.

Restore starts from the **archive**, then shows what's inside it. Accounts come back under their
original identity — no new device certificate, no re-authorization. Re-importing onto a device that
already has the account **merges**: existing conversations are left alone (swarm history is
append-only, so what's on the device is always the superset), missing ones are dropped in, and only
attachments actually absent are copied back. Nothing duplicates, whatever you import twice.

The panel is reachable from the account wizard too — so a **fresh install can restore before it has
any account at all**, which is the whole point of a migration tool.

## 🗂 Chat files — see what the chats are storing, and put it down

The backup made the shape of the problem plain: gigabytes, almost all of it old attachments nobody
will open again. This panel is where they can be put down. Three folded levels — **accounts →
conversations → files**, biggest first at every level, each with its own file count and byte total,
and the grand total pinned at the top. Sizes come from the filesystem, never from what a message
claims: a file that was never downloaded occupies nothing, and a page about disk usage should say so.

Tap a thumbnail or the file's name to **open it** — pictures and videos in the app's own viewer,
anything else in whatever app claims the type — because you should be able to recognise a photo
before throwing it away. Tap anywhere else in the row to tick it. Inside a conversation the files sit
under **↑ Sent by you** and **↓ Received** headings, each with its own tally and checkbox, so a whole
direction is one tick.

Deleting is a real delete, not a swept-away file that syncs straight back. Both copies of the bytes
go — the payload and the daemon's link — and for **your own** files there is a choice the swarm
actually supports:

- **Free space** — the local copies go, every message stays exactly where it is. The other side keeps
  its copy, nothing changes in their chat, and the file remains downloadable here for as long as
  anybody in the conversation still has it.
- **Delete messages** — the message goes with the file, for every member and every one of your
  devices, permanently.

Received files can only ever lose their local copy — the daemon refuses to edit a commit it did not
author — so for those no false choice is offered, and the warning splits the counts instead of
promising something that would be refused.

## 🔗 Home-screen shortcuts

Add a shortcut from the launcher and pick account → contact → **chat, call, or call on speaker**. The
icon is the contact's avatar badged with the Jami mark and a yellow-traced chat, handset or
loudspeaker glyph, so the three kinds are told apart at a glance; the badge colours are settable like
everything else. Tapping opens that conversation or places the call directly — and the speaker kind
rings with the phone **already on the loudspeaker**, which survives the callee answering rather than
snapping back to the earpiece the way a route chosen while ringing normally would. Each kind has its
own identity, so one contact can hold all three pinned side by side. Also available in-app from the
search-bar overflow, and the picker speaks English, Japanese and Czech by system locale.

---

## 📶 Connectivity, self-healing & a live connection monitor

Jami can quietly drift offline; this fork helps it heal itself **and** shows you the truth about its
links.

**Three-fold connection mode with an adaptive fallback.** Pick how the app stays reachable — a
**local DHT node**, **Firebase/FCM** (works through microG, no Google Play needed), or a custom
**UnifiedPush** distributor — from one dialog. The battery-cheap modes ride a push subscription, and
this fork adds the missing safety net: a watchdog measures whether **real pushes actually arrive**,
and when the push leg dies (a rate-limited server, a filtering VPN, a dead distributor) it
automatically clears the push token and switches the proxy to **streaming reception** so messages
keep flowing — then quietly returns to push when the leg recovers. It even keeps the process alive
for the outage's duration so streaming can't be reaped. The push path never silently strands you.

**Verified health, not guesses.** Every connectivity indicator is driven by what the app can *prove*
it received — a 60-second silent presence probe, an active call, real push arrivals — never a
registration flag that lies. A contact you're on a call with can never show red; an account that
reads "connected" but receives nothing is caught and recovered.

**Port mapping that actually works on phones.** Upstream dhtnet binds UPnP/NAT-PMP to the *first*
network interface it finds — on a real phone with mobile data up that's the cellular link, so both
protocols silently fail forever (`UPNP_E_INVALID_INTERFACE`) and no router port is ever opened. This
fork patches the daemon's interface selection to prefer the actual LAN interface (Wi-Fi/Ethernet),
so UPnP and NAT-PMP reach your real router and ICE gets direct-connect candidates — a bug still
present upstream.

**Self-healing watchdog.** A background watchdog watches for the wedge — nothing connected, or an
outgoing message that never confirms (which also catches **group swarms** a raw connection table can't
see) — and recovers automatically by re-registering on the full DHT. It holds the proxy off while
charging (reliability is free when you're plugged in) and manages it intelligently otherwise.

**Two-tier recovery — the ⚡ lightning.** Tap for a **smart recover**: it just re-registers when your
links are healthy, and drops to the full DHT only when nothing is connected, so fixing one stuck
contact never needlessly disables the proxy. Long-press for a **hard reset** — full DHT +
re-register, unconditionally. The lightning glows blue while recovering. After any recovery it also
**back-fills**: it actively pulls the messages that piled up while you were deaf, instead of waiting
for senders to notice you again.

**The ⬡ hub — mode by shape, state by colour.** The connection-mode icon is a **solid hexagon** for
full DHT (the heavy mode — your device is a node) and a **thick hollow hexagon** for proxy (the light
mode); its **colour** is the live state — yellow when the chosen mode is receiving fine, blue while
recovering or riding the streaming fallback, red when the current mode is verifiably broken. One tap
switches every account at once.

**Per-contact live monitor.** Tap any contact's (or group's) avatar to open a live monitor for just
them — it self-refreshes every couple of seconds. Each member's channels are colour-coded exactly like
the full monitor (connecting → negotiating ICE → securing TLS → connected) with device IDs, link uptime
and channel names; an offline contact reads "watching the DHT…" rather than a blank. A **Message ping**
forces a channel open, and its verdict is honest — it waits for the ⌁'s **delivery receipt** instead of
guessing from the channel, so it never cries "unreachable" prematurely. If a message is already stuck it
routes you to the account-wide ⚡ recovery instead of pinging into the void. Group swarms included.

**Honest presence + the full monitor.** The chat-list dot is a real-time light — **yellow = a live
connection right now, blue = online but no open pipe, red = offline** — and it's delivery-aware, so a
contact with a message stuck more than a few seconds never reads connected. The all-accounts
**connection monitor** (tap the dot) sorts problems to the top, names exactly what's stuck, reads
"connecting" as blue (never a fault), and folds three levels deep to each contact's device
connections. **Long-press the dot for an in-app help page** explaining every icon and what to do when
something's wrong — its button references render as the real pills, and its own headings, body and pill
colours (plus fonts) are settable too. Every colour, and the fold-triangle size, lives in *UI fonts & colours*.

**Actionable, honestly-coloured stuck messages.** When a message won't go through, the dashboard and
monitor don't just flag it — they explain and act. Each stuck line **names the recipient
unambiguously** (profile name + username + id tail, so two identities sharing a display name are never
confused), shows **how long it's been stuck and what it is** (📎 file or 💬 text preview), and is
**tappable — it opens that very conversation**. The colour tells you *whose* problem it is: **red** is
a genuine fault you can fix — a same-device sync stall between your own accounts, or a peer that's
connected yet never acknowledged — and **blue "delivering…"** means you're simply reaching someone
who's away (their message will land when they return). A message queued to an offline contact never
makes your account look broken, and every account-status dot — top bar, side avatar, dashboard,
monitor, and the account picker — follows the *verified health*, so a red row is never paired with a
falsely-green dot.

**Dual-backend push in one build.** The app ships both **Firebase/FCM** and **UnifiedPush** in a
single APK — switch between them (or a local DHT node) at runtime. Firebase is wired to work through
**microG**, so you get Google-style push with **no Google Play Services**; UnifiedPush pairs with any
distributor (e.g. [ntfy](https://ntfy.sh)), including a self-hosted one. Whichever you pick, the
adaptive fallback above covers it when the push server fails.

## ⏳ A typing indicator that never stopped animating

Every time a contact typed at you, Jami built a fresh animated drawable for the "…" indicator and
told it to **restart itself forever** when it ended. Nothing ever stopped one. Recycling the row
didn't, closing the conversation didn't, backgrounding the app didn't — each one went on demanding a
frame from the display pipeline at the panel's refresh rate, for the life of the process, and they
**piled up**.

Measured here on a 90 Hz panel with the app backgrounded and the screen off: **up to 93 main-thread
wakes per second and 76 % of a core**, climbing over hours, with a force-stop the only cure. A
profile of the main thread put it squarely on the animated-vector path under the frame scheduler.

Now the drawable is reused rather than rebuilt on every bind, it is **stopped when the row is
recycled**, and the self-restarting callback is gone — it was redundant, since two of the
indicator's three bounce animations already loop forever on their own. Verified with a contact
actively typing: **0.5–3.9 wakes/s, against 64/s minutes earlier on the same phone**.

This one was **stock GNU Jami**, not something this fork introduced — it needed only a chatty contact
and a few hours of uptime, so it affected every Android Jami user. Found here, filed upstream as
[Gerrit 35522](https://review.jami.net/c/jami-client-android/+/35522), and **merged into GNU Jami on
2026-08-07** (`af3fbe15f`). As of `+012` this fork carries no patch of its own for it — it simply runs
upstream's fix. Listed here because the investigation happened in this fork, not because it is still
a difference from stock.

---

## 🔥 A core burned by one unreadable socket — and the blindness that hid it

The app sat at **109 % CPU** with the screen off. One thread was doing all of it: 100.0 % of a core,
**never sleeping once**, and 70 % of the burn in system calls that returned immediately.

An ICE candidate socket had entered a permanent error state — bound to a cellular interface that was
up but had lost its route, while WiFi and a VPN held the default. Every read failed instantly, the
network layer logged it and asked to read again, and epoll re-reported at once: **15 774 iterations
per second, for 24.8 hours**, while the phone was moving 17 packets per second.

Three defences in this fork should have caught it. Every one was structurally blind: one waited for
an event nobody handled (this one *was* handled), one had never executed in its entire existence, and
one was watching for the wrong ICE state — because the diagnostic printed that state as a bare number
against a legend that was off by one, so `RUNNING` had been read as `FAILED` and a whole census was
inverted. Both diagnostics now print the state **by name**.

The replacement gates on behaviour rather than state: a poll that neither blocked nor delivered any
payload, a thousand times running, is capped at 100 Hz — **0.6 % of a core instead of 100 %**.
Requiring both conditions is what keeps real traffic untouched.

**109 % → 3.23 %** over a 10.6-hour run, idling **under 1 %** since.

The deeper find was why it took a profiler at all: the daemon set its network layer's log level to
zero, so the callback installed on the very next line was **never invoked for anything**. Every
pjsip and pjnath diagnostic was discarded, permanently, with no way to raise it on Android. A socket
failing sixteen thousand times a second could burn a core for a day and leave no trace. That layer
can speak now — and a message that was being formatted at 16 kHz and thrown away, costing ~13 % of
the burning core, is rate-limited.

---

## 📉 DHT data efficiency — an upstream bug, fixed at the root

Jami's daemon republished **every certificate revocation list an account had ever seen** on each
registration — and because each put drew a fresh random value id, every re-registration *added* a
copy rather than replacing one. All of it permanent, re-announced to the network every 10 minutes,
forever. One account key here carried **266 values / 274 KB: 35 CRLs going back to October 2024**,
where 1.5 KB would do.

This fork publishes only the **current** CRL, under a content-derived id so re-registration
overwrites instead of piling up — verified safe first, since the newest list contains exactly the
union of every older one. The device announcement got the same treatment. Result across four
accounts: **661 values / 611 KB → 9 values / 7.4 KB**, with idle CPU roughly halved. A complete
upstream report ships in the repo.

---

## 🔋 Resting on push, escalating to full DHT only on trouble

Measured on-device over two hours with four accounts backgrounded, the full local DHT node moved
**145 packets a second — 83 % of every packet the device sent or received** — and left the WiFi radio
asleep for 289 ms of the entire window. Not a wakelock problem: a packet-rate one, and it bypasses
push entirely, so the phone could never sleep. The standing mode is now **DHT proxy + push**, with
full DHT kept as the escalation path a wedge triggers. Both switches remain yours in Settings.

The background account-deactivation machinery that makes resting actually cheap — grace windows, call
and foreground-service exemptions, an episode cap — existed only in the Firebase flavour and never ran
in the shipped one; it now lives in the shared application class, so every flavour inherits it, and a
UnifiedPush arrival earns the same wake grace as an FCM one.

## 🧭 Transport check — when DHT proxy is actually worth it

The full DHT rides UDP, so a network that blocks UDP breaks it completely no matter how healthy
everything else looks — the one case where DHT proxy is clearly the right mode. The connection
monitor now shows a **Transport** row: `UDP ✓ TCP ✓ — full DHT viable`, or in red
`UDP ✗ TCP ✓ — hostile network, DHT proxy advised`, always stamped with how old the reading is.
Tap to re-test, hold for the explanation; it re-tests itself whenever the network actually changes,
and tells you when a network turns hostile. It only ever **advises** — switching modes stays your
decision, on the hexagon.

---

## 🖼 Media viewer with hide/restore

Full-screen media opens into a **swipeable viewer**: swipe sideways through the pictures and videos
of the same direction (sent or received) without going back to the chat. **Swipe down** to hide the
current item from the swipe set (it stays in the conversation, marked "Suppressed" when opened
directly); **swipe up** to restore it. A dedicated button browses only your hidden media so nothing
is ever lost, and the action bar is plain yellow icons — share, download, open — instead of stock's
white pill.

## 🛡 Protected contacts

A companion app can mark contacts as **protected** over a local broadcast: a message from one of
them posts a deliberately **vague, contentless notification** — nothing identifying on the
lock-screen, Wear or Android Auto — with a companion-settable title/body and a private marker the
companion keys on. The stored list can be queried back (ordered-broadcast read-back), and every
other sender's notifications are untouched.

## ↪️ In-app message forwarding

Long-press any message or file → **「白い熊 Jami」で共有** forwards it to another chat through
Jami's **own** picker — no system share sheet. Pick the **sending account** from an avatar list,
pick the target conversation (with search and a content preview), and the message lands staged in
that chat's composer. The long-press menu itself is black/yellow, colour-settable, and pinned in
place so background sync can no longer make it dance.

## 🤖 Automation intents & 保存復元 batch backup

Token-gated, exported **send / call / open** intents let external scripts and automation apps drive
Jami headlessly — send a message, place a call, or open a conversation from anywhere on the device,
guarded by a secret token so only your own automations can trigger them. The same token also gates
the **保存復元 state-export contract**: an external backup orchestrator can list the export
categories and trigger a full headless backup (real-count progress broadcasts, a broadcast reply
with the written path and size), producing the exact same restorable zip as the Export/Import
panel — with the token itself never travelling inside any backup.

## 🔎 Registered-name resolution

Username lookups that get stuck are retried automatically, and a dedicated **“Look up name”** action
lets you resolve a registered name on demand instead of waiting on a silent failure.

## 🪟 Split-view toggle

On foldables and tablets, Jami shows the conversation list and the open chat side by side. A toggle
(in **Settings → Appearance** and the chat-list overflow menu) forces **single-pane** when you'd
rather focus on one screen at a time.

## 🎙 One-way audio — when the phone runs a microphone that isn't there

Some devices open a capture stream, report it healthy, light the microphone privacy indicator, and
deliver a continuous run of **digital zeros**. The call connects, the other side hears you, and you are
inaudible — with no error anywhere to explain it.

This fork measures instead of guessing. The capture chain is sampled at both ends — the buffer exactly
as the platform hands it over, and again after the audio processor — so silence can be attributed to
the device rather than to anything the app did. When the raw signal reads **exactly** zero for five
seconds, which a live microphone never does because one always leaks a noise floor, the input is
re-opened off the low-latency `VOICE_COMMUNICATION` path that is implicated in this class of fault. The
decision is remembered per device, so it is paid once rather than at every app start.

Measured on the affected phone: `peak=0.0000` at ~510 frames/s on the fast path, and real audio in the
very next second after the switch. Upstream has neither the detection nor a fallback — and since the
OpenSL layer was removed there is nothing left to fall back to.

---

## 📼 Call recording, in the conversation

Upstream's phone UI has no way to record a call at all, though the daemon has always been able to. This
fork adds a **Record** control to the in-call sheet, and — the part that makes it usable — the finished
recording appears **in the chat as a normal audio message**: play it, scrub the waveform, Share it, Save
it, delete it.

That took teaching several layers that a message need not come from the swarm: a recording is a local
artefact with no message id, so it is indexed beside the files themselves, inserted where its timestamp
belongs rather than pinned to the bottom of the conversation forever, and given a real identity so
deleting it removes the right row. Recordings land in the app's own visible storage — the daemon's
fallback is a private directory the user cannot reach.

---

## ⏱ A call timer you can actually see, and a speaker that stays on

- **The running duration is visible.** Upstream computes it every second and writes it into a view it
  hides for the whole call, so it has never appeared. It now sits at the top of the call screen in
  bold yellow, `M:SS` and `H:MM:SS` past the hour.
- **The speaker survives pick-up.** Choosing the speaker while an outgoing call rang out used to be
  undone the instant the callee answered: every call-state change re-asserted a route derived only from
  the call type. Your choice is now remembered for the call and re-asserted instead of overwritten.
- **A voice message opens its menu on a long press anywhere in the bubble** — the play button and the
  waveform swallowed the gesture, leaving Save and Share reachable only at the bubble's edge.

---

## 💬 Quality-of-life

- Settable styled **“flash” messages** (toasts) for in-app feedback.
- A resizable, real-time account-status **dot** in the top bar (tap for full status, long-press for help).
- A **“Location sharing status”** tool (overflow menu) that shows which conversations are really
  sharing your location — and can stop them all — without ever waking the sharing service.
- A black/yellow knot **launcher icon** so the fork is easy to spot.

---

## Built on GNU Jami

This is a downstream personalization of [GNU Jami](https://jami.net) for Android
([savoirfairelinux/jami-client-android](https://github.com/savoirfairelinux/jami-client-android)).
All credit for Jami — the protocol, the daemon, the network and the client — goes to its authors at
Savoir-faire Linux and the Jami community. Like upstream, this fork is licensed under the
**GNU General Public License v3.0** (see [`COPYING`](COPYING)).
