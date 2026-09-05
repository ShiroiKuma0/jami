# 白い熊 GNU Jami — `20260807-01+2026-08-17.17-36.gf0c774eb+005`

A downstream fork of [GNU Jami](https://github.com/savoirfairelinux/jami-client-android) for Android. Installs **side-by-side** with official Jami (app id `shiroikuma.jami`, label 白い熊 GNU Jami). Everything below is built on top of stock.

A one-decision release. `+004` shipped automation contract v2 with a deliberate exception carved out of it, and flagged that exception as unresolved. It is now resolved: **no token is necessary by default, across the whole automation surface.**

---

## 🔓 The carve-out is gone — one gate, no exceptions

`+004` required the authorization token for `SEND_MESSAGE`, `PLACE_CALL` and `PLACE_VIDEO_CALL` regardless of the switches, on the reasoning that those operations act as *you* rather than read data, and that restoring a wiped phone never requires sending a message from it. That release recorded the question as open rather than presenting it as settled.

白い熊 was shown the complete automation surface — every entry point, what gates it, and what removing the exception would expose — and settled it the other way. The `acting` flag is removed, `refuse()` returns to the contract's canonical two-argument form, and every entry point is now gated identically:

- **Master switch off** — nothing is reachable.
- **Master switch on, token off (the default)** — the whole surface answers, backups and acting operations alike.
- **Token on** — the whole surface requires it, in one move.

## ⚠️ What that means, stated plainly

**With the token off, any app on this device can send a Jami message or place a call as you.**

This is a deliberate choice and it is recorded as one rather than left to be discovered. The reason it cannot be narrowed further: `SEND_MESSAGE`, `PLACE_CALL`, `PLACE_VIDEO_CALL` and `OPEN_CONVERSATION` arrive at an **exported Activity**, and the 保存復元 broadcast actions at an **exported receiver**. Neither can tell who is calling, so the token is the only gate available to them — there is no middle setting between open and token-required on those entry points.

The **data door is the exception**, and it is unchanged. `describe` / `export` / `import` / `cancel` still verify the caller three ways — exact package name, a uid cross-check against the kernel's answer, and a pinned signing certificate — because a `ContentProvider` *can* see who is asking. That verification was never what the token was doing, and it is not weakened here.

`SET_PROTECTED_CONTACTS` and `GET_PROTECTED_CONTACTS` remain unauthenticated, as they have been since they were added — they were never behind the token and are unaffected by this change.

## 📝 The records were corrected, not quietly edited

Every place that asserted the old behaviour has been rewritten to describe the new one, including the two user-facing settings descriptions that would otherwise have promised a protection the build no longer offers:

- The **Export/Import** section's token note and the **Automation** page's description now say that with the token off any app may also send and call, and that the backup door checks package, uid and signature either way.
- The **README** header tagline and automation section no longer describe sending and calling as permanently token-gated.
- The code comments that argued *for* the carve-out now record who decided against it, when, and what it exposes — so the reasoning is preserved as history rather than deleted as if it had never applied.

## 🛡 A refused foreground start is now answered, not fatal

Also in this build, from the concurrent contract rollout: a broadcast is itself a background start on API 31+, so starting the export service could throw `ForegroundServiceStartNotAllowedException` — and an exception escaping `onReceive` takes the whole process down.

The allowance to start a foreground service comes from recent interaction, so a hands-on test always has one and the unattended batch this contract exists for does not. The failure is inversely correlated with how closely anyone is watching, which is why it surfaced first on the provider path.

Catching it is only half the fix. A silent non-export makes a working app indistinguishable from one that never implemented the contract, because the caller just waits out its timeout — so the refusal is now **answered**, in the same wording the data door already used. Only the platform's own message leaves the app; no account, contact or conversation state travels with it.

## 🏗 Build

Built on upstream `f0c774eb0` (`20260807-01`), same base as `+003` and `+004` — no upstream sync in this release. `arm64-v8a`, `withUnifiedPush` flavour, signed release APK. The daemon submodule gitlink remains pinned to upstream.
