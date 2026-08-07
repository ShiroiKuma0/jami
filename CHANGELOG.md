# 白い熊 GNU Jami — `20260731-01.2026-08-06.g5926177b+012`

A downstream fork of [GNU Jami](https://github.com/savoirfairelinux/jami-client-android) for Android. Installs **side-by-side** with official Jami (app id `shiroikuma.jami`, label 白い熊 GNU Jami). Everything below is built on top of stock.

An upstream sync whose main effect is **subtraction**: a fix this fork has been carrying since `+008` is now part of GNU Jami, so the fork stops carrying it. The patch stack is one commit shorter than it was yesterday, and nothing was lost.

---

## 🔁 The typing-indicator fix is upstream's now, not ours

`+008` shipped a fix for an animator leak in the typing indicator, and its release notes described it as this fork's work. That is no longer accurate, and this entry corrects it.

The bug was **stock GNU Jami**: `configureForTypingIndicator` built a fresh `AnimatedVectorDrawableCompat` on every bind and registered a callback that restarted it from `onAnimationEnd`. Nothing ever stopped one — an `AnimatedVectorDrawable` is only stopped by an explicit `stop()`, and recycling the row, detaching the view and backgrounding the app all do not. Every typing indicator ever shown therefore left an immortal animator requesting Choreographer frames at the panel's refresh rate, for the life of the process. Measured on a 90 Hz device, backgrounded, screen off: **up to 93 main-thread wakes/s and 12–76 % of a core**, growing over hours, cleared only by force-stopping.

It was found here, filed upstream as [Gerrit 35522](https://review.jami.net/c/jami-client-android/+/35522), and **merged into GNU Jami on 2026-08-07** as `af3fbe15f` — Code-Review +2, Verified +1.

So this release **drops the fork's copy** (`e3145b7b5`) and runs upstream's. The two versions were verified equivalent line by line before dropping ours — identical logic down to the `if (anim != null && !anim.isRunning) anim.start()` guard, differing only in comment wording and one local variable's name. Every Android Jami user gets this now, which was the point of sending it up.

---

## ⬆️ Synced to upstream `5926177b`

Two upstream commits, one of them the merge above. The other is a documentation correction in upstream's own README: the pkg-config rebuild instruction is now `./bootstrap && make .pkg-config` rather than `./bootstrap && make`.

**Upstream's version string did not move** — still `20260731-01`, still versionCode 502. This is exactly the case the commit pin exists for: without it this build and `+011` would be indistinguishable from their names alone. The pin advances instead:

```
20260731-01.2026-08-06.g5926177b+012
             └ base date ┘└ base ┘└ build ┘
```

Note what did **not** happen: the build counter did not reset. It keys off the upstream *version name*, which stood still, so it went 11 → 12. Had it reset to 1 while `upstreamVersionCode` stayed at 502, the computed `versionCode` would have gone **backwards** — an update Android refuses to install. The pin orders the name; the counter guarantees the code; they move independently on purpose.

The daemon submodule did not move either, so the C++ daemon and its contrib were not rebuilt.

---

## 🌐 Carried forward from `+011`

The full-locale rebrand shipped in the previous release and is unchanged here: every user-visible mention of the app reads **白い熊 GNU Jami** across all 100 locales — including the ~25 that transliterate the name into their own script, and Bulgarian, which was still shipping the app's pre-2018 name "Ring". See the `+011` notes for the detail.
