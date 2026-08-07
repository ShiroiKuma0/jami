# 白い熊 GNU Jami — `20260731-01.2026-08-03.g8746fd4b+011`

A downstream fork of [GNU Jami](https://github.com/savoirfairelinux/jami-client-android) for Android. Installs **side-by-side** with official Jami (app id `shiroikuma.jami`, label 白い熊 GNU Jami). Everything below is built on top of stock.

This release does one thing, thoroughly: **the app now calls itself by its own name in every language it ships.** It sounds like a rename. It was not — the name was hiding in three different disguises, and two of them are invisible to the obvious approach.

Same upstream base as the previous release (`8746fd4b`); no daemon or connectivity changes.

---

## 🏷 The app did not know its own name

The fork has been `shiroikuma.jami`, label **白い熊 GNU Jami**, since the beginning — but a label is not an identity. Upstream's translators wrote "Jami" into the *running text* of the interface, in every language, and none of that was touched by renaming the app. On a phone set to Czech the About page read:

```xml
<string name="menu_item_about">O Jami</string>
```

That is the whole bug, repeated roughly four and a half thousand times across **100 locales**. Every user-visible mention now reads **白い熊 GNU Jami**.

**Grammar survives.** These languages inflect the app name, and the inflection is meaning, not decoration — so the suffix stays and only the stem is replaced:

| | before | after |
|---|---|---|
| Finnish | `Skannaa tämä koodi Jamilla` | `Skannaa tämä koodi 白い熊 GNU Jamilla` |
| Hungarian | `Nem sikerült elindítani a Jamit` | `Nem sikerült elindítani a 白い熊 GNU Jamit` |
| Basque | `Ongi etorri Jamira` | `Ongi etorri 白い熊 GNU Jamira` |
| German | `Jami-Konto` | `白い熊 GNU Jami-Konto` |
| Danish | `på Jamis distribuerede platform` | `på 白い熊 GNU Jamis distribuerede platform` |

Only text *content* is rewritten — never an attribute — so resource ids, style names like `Theme.Jami.*`, and `href`s were structurally out of reach rather than merely avoided.

---

## 🀄 The pass that silently skipped nine languages

The first sweep reported success and had quietly done nothing at all in Chinese, and it looked complete from the outside.

The guard said *"only replace 'Jami' when it isn't in the middle of another word"* — sensible, since it protects the `JamiId` identifier. But in Python, `\w` is **Unicode-aware**, and a Han character is a word character. Chinese does not put a space before a Latin name:

```
無法啟動Jami          ← 動 is a "word character", so "Jami" reads as mid-word
不是Jami的二維碼
```

Every Chinese, and any similarly-written, locale was therefore skipped — by a guard that was doing exactly what it was told. The boundary has to be **ASCII-only**: only a preceding Latin letter or digit means we are inside another word.

---

## 🔤 Twenty-five languages that never wrote "Jami" at all

The larger discovery, and the reason a single find-and-replace could never have finished this job: a substantial share of Jami's translations **transliterate the name into their own script**. No Latin-based pattern can see any of them, and their absence looks identical to "already done".

| | before | after |
|---|---|---|
| Serbian | `О Јамију` | `О 白い熊 GNU Jamiју` |
| Korean | `자미 회의` | `白い熊 GNU Jami 회의` |
| Japanese | `ジャミに連絡する!` | `白い熊 GNU Jamiに連絡する!` |
| Hebrew | `ג'אמי היא תוכנה חופשית` | `白い熊 GNU Jami היא תוכנה חופשית` |
| Tamil | `ஜாமி பற்றி` | `白い熊 GNU Jami பற்றி` |
| Armenian | `Ջամիի մասին` | `白い熊 GNU Jamiի մասին` |
| Ukrainian | `на Джамі!` | `на 白い熊 GNU Jami!` |

Also Arabic, Persian, Azerbaijani, Bengali, Hindi, Marathi, Nepali, Gujarati, Kannada, Malayalam, Telugu, Thai, Greek, Belarusian, Kazakh, Tatar, Mongolian and Acehnese.

**Bulgarian was still shipping the app's pre-2018 name.** `description` read *"**Ring** е безплатен софтуер за универсална комуникация"* — Ring being what Jami was called before it was renamed, still sitting in the translation years later.

And the transliterations are not even internally consistent: Hindi spells it four different ways across its own files (`जामी`, `जैमी`, `जेमी`, `जमी`), Belarusian four (`Джані`, `Джамі`, `Джэмі`, `Ямі`). That is why this converged over several rounds instead of one — each round's leftovers exposed the next spelling.

Replacements here are confined to the string ids the **English source** names the app in. That whitelist is what makes it safe to replace a token like Hindi `जमी`, which is also an ordinary word: outside those specific strings it is never touched.

---

## ✋ What still says "Jami", deliberately

Branding stops where it would start stating things that are not true:

- **`sponsor_section`** — sits directly above the Savoir-faire Linux logo: *"Jami is free software developed and supported by"*. Under our brand, that credits SFL with developing this fork.
- **`end_note`** — the contributors list, ending *"contact the Jami team at contact@jami.net"*. Rebranded, it invites people to write to upstream about our fork.
- **`jami_id_copy` / `jami_id_share`** — "JamiId" is the identifier's own name, not a mention of the app.
- **Every `jami.net` URL and the `contact@jami.net` address** — untouched by design.
- **38 strings** in French, Spanish, Bulgarian, Persian, Norwegian and Slovak where the translator dropped the app name entirely (*"le compte"*, *"la plataforma"*, *"профилът ви"*). There is no name in them to rebrand; inserting one would be translation work, not branding.

---

## 🧩 Beyond the translation files

- **Six hardcoded Kotlin literals** that no string file covers: the automation toast, the restored-account name fallback, the account-row fallback, and two clipboard labels.
- **The short form is gone.** Eight fork strings said "白い熊 Jami" without the *GNU*; the app's label has always been 白い熊 GNU Jami, so they now agree.
- **Verified in the built APK, not just in the sources** — `aapt2` confirms the branded values are compiled into `resources.arsc`, and all 296 changed resource files re-parse as valid XML.
