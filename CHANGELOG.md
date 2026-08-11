# 白い熊 GNU Jami — `20260807-01.2026-08-07.g46f48193+002`

A downstream fork of [GNU Jami](https://github.com/savoirfairelinux/jami-client-android) for Android. Installs **side-by-side** with official Jami (app id `shiroikuma.jami`, label 白い熊 GNU Jami). Everything below is built on top of stock.

A feature release on the same upstream base as `+001`: home-screen shortcuts gain a **third kind** — a call that rings with the phone already on the loudspeaker.

---

## 🔊 Speaker-call shortcuts

The launcher shortcut picker used to offer two kinds — open the chat, or place a call. There is now a third: **place a call on speaker**. It dials exactly like the call shortcut, but the phone is on the loudspeaker from the moment the call starts, so a shortcut on the home screen is a genuine one-tap hands-free call rather than a call plus a hunt for the speaker button.

Its icon carries a **loudspeaker glyph** where the call shortcut carries a handset — same avatar, same Jami badge, same yellow trace with a black rim so it still reads over a light photo, and the same settable badge colours. The three kinds are told apart at a glance.

Each kind now owns its **own shortcut id**, so one contact can hold a chat shortcut, a call shortcut and a speaker-call shortcut pinned side by side. Creating one never displaces another.

---

## 🎧 Why it survives the callee answering

The interesting part is not asking for the loudspeaker — it is keeping it.

Jami recomputes the wanted audio route on every call-state change, from nothing more than *"is this incoming, and does it have video?"*. A route chosen while an outgoing call is still ringing is therefore **discarded the instant the other side picks up** and the state goes `RINGING → CURRENT`. This fork already fixed that for a route the user picks by hand, by remembering the choice in `mUserSelectedOutput` and re-asserting it instead of overwriting it.

So the speaker shortcut deliberately routes through the **same path as tapping the speaker button** rather than poking the audio manager directly. It inherits that fix for free: the loudspeaker is still there when the call connects, which is the only moment that actually matters.

Two smaller decisions worth recording:

- **It fires on the first audio state that offers a loudspeaker**, not earlier. The route list simply does not exist until the system call connection is up, so there is nothing to select before then.
- **It is a one-shot latch, and it disarms only on a real dispatch.** Once the loudspeaker is selected the request is spent, so the speaker button remains a free toggle for the rest of the call — the shortcut sets the starting position, it does not hold it. And because an audio state can arrive before the conference exists to receive the choice, the dispatch now reports whether it landed; a request that could not be delivered stays armed instead of being silently swallowed and losing the speaker for the whole call.

The shortcut forces the loudspeaker unconditionally, including when a headset is attached — a speaker shortcut that quietly deferred to whatever was plugged in would not be one.

---

## 🌐 Trilingual, like the rest of the picker

The new entry and its shortcut label are written for **English, Japanese and Czech**, matching the rest of the shortcut picker, and follow the system locale.

---

## 🏗 Build

The three build-system defects narrated in `+001` — the patch-checksum gate being blind to a package's own version, dependency order deciding which `rules.mak` a package is extracted with, and patch failures scrolling past a block that cannot take `set -e` — are now **closed in the canonical build block**, with the reasoning recorded inline. This release is the first built through the repaired block: contrib passed its sanity gate with no rejected hunks and every package stamped.

Build counter `+001` → `+002` on an unchanged upstream base (`versionCode 5030001 → 5030002`).
