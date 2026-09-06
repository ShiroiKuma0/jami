# 白い熊 GNU Jami — `20260807-01+2026-08-17.17-36.gf0c774eb+006`

A downstream fork of [GNU Jami](https://github.com/savoirfairelinux/jami-client-android) for Android. Installs **side-by-side** with official Jami (app id `shiroikuma.jami`, label 白い熊 GNU Jami). Everything below is built on top of stock.

A one-fix release, and the fix is for a feature that has been in the code since July and had never once worked on a phone.

---

## ⋮ Long-press the conversation's overflow to open UI settings

Long-pressing the **⋮ in a conversation's toolbar** now jumps straight to **UI fonts & colours**, the same as long-pressing the ⋮ in the chat list has always done.

That gesture was written on 2026-07-25 and shipped in every release since. It worked when built for debugging and did nothing whatsoever on the phone — a silent, total failure with no error, no log line, and nothing to distinguish it from a gesture that had never been implemented.

## 🔍 Why it never worked: a name that only exists before minification

The chat list's ⋮ is a menu item this fork declares itself, so the code finds it by id. A conversation toolbar's ⋮ is built by the Android support library from the inside; it has no id to look up, and the code identified it by class name instead:

```kotlin
b.javaClass.simpleName == "OverflowMenuButton"
```

Release builds are minified, and minification renames classes. The mapping file of the shipped `+005` build records exactly what happened to that one:

```
androidx.appcompat.widget.ActionMenuPresenter$OverflowMenuButton -> x6:
```

So on every phone the test compared `"x6"` against `"OverflowMenuButton"`, never matched, and quietly attached the long-press listener to nothing. A debug build is not minified, which is why the gesture worked wherever it was tried and nowhere it was used.

The button is now identified by **what it is rather than what it is called**: the last child of the toolbar's menu row that is an image view. Ordinary toolbar buttons are text views underneath, so the overflow is the only one that matches — and nothing in that test survives being renamed, because nothing in it is a name.

Matching on the button's accessibility description would have been the obvious alternative and is not available: that string is private to the support library, and referencing a private resource fails the release lint gate outright. Both dead ends are now recorded in the source beside the fix, so neither gets tried again.

## 🏗 Build

Built on upstream `f0c774eb0` (`20260807-01`), the same base as `+003` through `+005` — no upstream sync in this release. `arm64-v8a`, `withUnifiedPush` flavour, signed release APK. The daemon submodule gitlink remains pinned to upstream.
