# 白い熊 GNU Jami — `20260807-01+2026-08-17.17-36.gf0c774eb+004`

A downstream fork of [GNU Jami](https://github.com/savoirfairelinux/jami-client-android) for Android. Installs **side-by-side** with official Jami (app id `shiroikuma.jami`, label 白い熊 GNU Jami). Everything below is built on top of stock.

An automation release. The fork implements **sister-app automation contract v2**: the authorization token becomes optional, and a new caller-verified data door lets a backup orchestrator export this app *with its data* and put it back on a wiped phone. It also fixes a manifest omission that had been silently discarding every automation reply this fork ever sent.

---

## 🔓 The gate — a switch that is ON, and a token that is OFF

v1 shipped closed: automation defaulted to disabled, and every caller had to present a 48-character secret pasted out of this app's settings. That is the wrong shape for a restore. **A pasted secret cannot survive a wipe**, and the case this family now serves is a clean phone where nothing has been configured and nobody has pasted anything — a gate that only works once the phone is already set up is no gate for setting the phone up.

- The master switch now defaults **ON**; it remains the way to close this app off entirely.
- A new **「Use authorization token?」** switch defaults **OFF**.
- **A token sent to an app that does not require one is ignored, never refused.** Tokens live in task arguments that outlive the setting they were pasted for; refusing them would turn "one switch was turned off" into "half the batch mysteriously fails".
- The whole surface now routes through **one** refusal function, so "disabled" and "bad token" cannot drift apart across entry points.
- The token row appears in settings only while it is actually being asked for — a 48-character secret sitting under an off switch invites you to paste it somewhere it will do nothing.

## 🔐 Sending and calling still require the token

Opening the gate is right for reading and restoring this app's own data. It is **not** right for the operations that act as *you*.

`SEND_MESSAGE`, `PLACE_CALL` and `PLACE_VIDEO_CALL` require the token **regardless of the switches**. A message sent through the automation surface is indistinguishable from one you typed, and a call opens the microphone and rings a real contact — that is impersonation, not data access. The clean-phone argument has no force there either: restoring a wiped phone never requires sending a message from it. And the data door's caller verification does not reach them, because that lives on the provider while these arrive at an exported Activity with no caller identity check of any kind.

`OPEN_CONVERSATION` relaxes with the rest — it only brings a conversation to the foreground, neither speaking as you nor handing anything back to the caller. `GET_PROTECTED_CONTACTS` relaxes; `SET_PROTECTED_CONTACTS` stays unauthenticated exactly as before.

> This carve-out is **this fork's own decision and is not settled**. The contract as written relaxes these along with everything else; 白い熊 has the question and may yet choose flat v2, which is a one-line change. Nothing in the backup path depends on it either way.

## 🚪 The data door — a verified caller and a file descriptor

A `ContentProvider` at `shiroikuma.jami.automation`, alongside the existing broadcast surface rather than replacing it, exposing `describe` / `export` / `import` / `cancel`.

- **A broadcast cannot tell you who sent it.** With the token off that would let any app on the phone harvest every sister app's data. The provider gets the caller from the framework and checks it three ways: an **exact package name** (never a prefix — a prefix is not an identity, since any sideloaded app may name itself `shiroikuma.anything`), a **uid cross-check** against the kernel's answer, and a **pinned signing certificate**, which matters most precisely on a clean phone where a caller package may not be installed yet and its name is therefore free to take.
- **The payload moves through a `ParcelFileDescriptor` the caller opens** — not a path, not a URI. A backup is not a stable directory while it is being written, encryption and checksums are per known file, and a descriptor is a capability that expires when it is closed. A side effect worth having: the automation path no longer needs All-Files-Access.
- **`import` exists only here** and never gets a broadcast action. An import overwrites this app's data, and the broadcast receiver is exported with no permission.
- Long work runs in a foreground service with a wakelock, reporting real counts and never a percentage, with the existing heartbeat kept so a caller does not time out mid-archive. A refusal is always returned rather than thrown across the binder.
- Export streams straight into the descriptor through the same engine the Export/Import panel uses; import **spools to disk rather than memory**, because a Jami archive carries the entire chat corpus and every attachment.

The capability header declares **`requires_launch_first: true`**, and this fork is the contract's anticipated exception. Restoring a Jami account is not a file copy — the daemon is driven throughout an import, including an account adoption that completes only across a daemon restart. A never-launched process has no daemon, so an import there would half-restore real accounts and report success over it.

## 📡 Every automation reply since 2026-07-25 was being discarded

The manifest had **no `<queries>` element at all**, and no `QUERY_ALL_PACKAGES` to have masked its absence.

Automation replies are sent with `setPackage(replyPackage)`, and on Android 11+ that is filtered **silently** when the target package is not visible to this app. So the 保存復元 export ran, wrote a correct archive, reported its progress — and the reply naming the written path and size was dropped every single time. From the outside the feature had simply never worked.

Both callers are now declared: 応用管理 for the data door and 自由作業盤 for the batch. Listing only one answers that caller and stays inaudible to the other.

## 🩹 One device-specific correctness fix

The descriptor is read through the pre-Tiramisu `Bundle.getParcelable` overload below API 33. The typed `getParcelable(String, Class)` compiles cleanly against a modern `compileSdk` and then throws `NoSuchMethodError` at runtime on this phone, which reports `SDK_INT = 31`.

## 🏗 Build

Built on upstream `f0c774eb0` (`20260807-01`), same base as `+003` — this release carries no upstream sync. `arm64-v8a`, `withUnifiedPush` flavour, signed release APK. The daemon submodule gitlink remains pinned to upstream.
