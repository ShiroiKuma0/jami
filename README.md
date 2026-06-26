<div align="center">

<img src="jami-android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="120" alt="白い熊 GNU Jami app icon" />

# 白い熊 GNU Jami

**Private, peer-to-peer messaging & calling — themed and tuned to taste.**

A fork of [GNU Jami](https://jami.net) with **major additions**: a full yellow-on-black theme, a
per-element **UI fonts & colours** system with an RGBA colour picker, **connectivity resilience** —
a live multi-account **connection monitor**, **Google-free push**, and one-tap account recovery —
token-gated **automation intents**, smarter **registered-name** lookups, and a **split-view** toggle.

**📥 Latest release: [`20260619-01+29`](https://github.com/ShiroiKuma0/jami/releases/latest)** — [all releases & APK downloads »](https://github.com/ShiroiKuma0/jami/releases)

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

## 🎨 Yellow-on-black theme

The fork's signature look: black backgrounds with a `#FFFF00` foreground, applied consistently
across the chat list, conversation bubbles, toolbars, the search and compose bars, **every dialog
app-wide** (including Android's own preference dialogs) and the long-press bottom sheet, file and
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

## 📶 Connectivity, push & a real connection monitor

Jami can quietly drift offline; this fork helps it heal itself **and** shows you the truth about its
links. It builds the **Google-free `withUnifiedPush` flavor**: paired with a UnifiedPush distributor
(e.g. [ntfy](https://ntfy.sh)) and the DHT proxy, backgrounded accounts deactivate and wake on a
push — so idle CPU drops to near zero **with no Google/Firebase dependency**, while incoming external
messages still wake the app instantly. (That tradeoff was *measured*, not guessed: a controlled
on-device A/B test of every connectivity setting showed the DHT proxy is the one lever that matters.)
It also adds DHT reconnect logic and a one-tap action to **recover Offline / disabled accounts**.

**The alarm tracks your actual messages, not just sockets.** When a message you sent hasn't been
delivered, the account turns red and the chat-list dot rings — and because it reads true message
delivery, it catches **group swarms** that a raw connection view can't even see. The dot rings **red**
for a stuck message and **blue** while an account is merely connecting; normal churn and idle never
cry wolf. And the proxy's one downside — a message between your *own* same-device accounts occasionally
stranding — is a one-tap fix: the new **Sync** icon (left of the dot) briefly cycles connectivity to
flush those messages through.

The **connection monitor** — tap the account dot, or open it from Settings — is an all-accounts
diagnostics screen that reports that *true* health. Problem accounts sort to the top with a red
**"⚠ message not delivered → \<chat\>"** line naming exactly what's stuck; "connecting" reads blue and
is never itself treated as a fault; your other same-device accounts read **reachable** rather than a
misleading "offline". Accounts are folded by default and fold open — three levels deep — to each
contact and its individual device connections; the same foldable, three-level view is now also built
right into the **tap-the-dot status dialog**. Every colour, and the fold-triangle size, is settable in
*UI fonts & colours*.

## 🤖 Automation intents

Token-gated, exported **send / call / open** intents let external scripts and automation apps drive
Jami headlessly — send a message, place a call, or open a conversation from anywhere on the device,
guarded by a secret token so only your own automations can trigger them.

## 🔎 Registered-name resolution

Username lookups that get stuck are retried automatically, and a dedicated **“Look up name”** action
lets you resolve a registered name on demand instead of waiting on a silent failure.

## 🪟 Split-view toggle

On foldables and tablets, Jami shows the conversation list and the open chat side by side. A toggle
(in **Settings → Appearance** and the chat-list overflow menu) forces **single-pane** when you'd
rather focus on one screen at a time.

## 💬 Quality-of-life

- Settable styled **“flash” messages** (toasts) for in-app feedback.
- An **account online/offline toggle** right in the chat-list top bar.
- A resizable account-avatar dot with reliable presence swapping.
- A black/yellow knot **launcher icon** so the fork is easy to spot.

---

## Built on GNU Jami

This is a downstream personalization of [GNU Jami](https://jami.net) for Android
([savoirfairelinux/jami-client-android](https://github.com/savoirfairelinux/jami-client-android)).
All credit for Jami — the protocol, the daemon, the network and the client — goes to its authors at
Savoir-faire Linux and the Jami community. Like upstream, this fork is licensed under the
**GNU General Public License v3.0** (see [`COPYING`](COPYING)).
