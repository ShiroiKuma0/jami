---
name: jami-build
description: Build the user's patched fork of GNU Jami for Android (package shiroikuma.jami, label "白い熊 GNU Jami"), installable side-by-side with the official Jami (cx.ring) from F-Droid/Play. Use this skill any time the user mentions Jami, GNU Jami, jami-client-android, shiroikuma.jami, their Jami fork, the Jami daemon/contrib build, asks to pull a new Jami version, rebase their changes, or rebuild Jami. The fork carries install-identity edits, a yellow-on-black theme and a per-element fonts & colours feature, plus one re-applied daemon-contrib fix; the daemon (C++ libjami + all contrib) compiles from source for arm64. Default to assuming this skill applies when in doubt during a Jami-for-Android session.
---

---

## Claude Code mode (vs sandbox mode)

This skill was originally written for the Claude.ai sandbox workflow (recon clone at `/home/claude/jami-recon`, python `swap()` + `git diff --cached`, save patch to `/mnt/user-data/outputs/`, user applies on host). **In Claude Code you ARE on the host, working directly in `~/git/shiroikuma-jami`** — so:

- Edit files in place with the standard edit tools; no recon clone, no python swap dance, no `present_files`.
- Run `gradlew`, `adb`, `git` directly via bash; no paste-ready shell blocks for the user, no patches as deliverables — work happens directly in the git tree.
- The **push workflow is unchanged**: commit → push to `origin custom`, with the same staging discipline (only `jami-android/app/src/main` for app-source changes; only the named build-config files for build-config changes; **never** `daemon/`, generated SWIG bindings under `net/jami/daemon/`, or the gnutls `sed` to `daemon/contrib/src/gnutls/rules.mak`).
- All banked facts below — build invariants, ColorPrefs roles, layout-editing lessons, the `newDsl=false` migration constraint, the daemon-gitlink-must-match-upstream rule — apply identically.

Below this section, references to "the sandbox" or "the recon clone" describe how the same banked facts were *originally exercised*; the facts themselves carry over.

---

# GNU Jami — patched fork build skill

The user maintains a downstream-renamed build of [jami-client-android](https://github.com/savoirfairelinux/jami-client-android) on Android, used on a Huawei Mate XT alongside the official F-Droid/Play Jami (`cx.ring`). The fork's purpose is a distinct package + label so it installs side-by-side, plus the minimal build fixes needed to compile cleanly on the user's desktop toolchain.

Only the **Android client repo** is forked — not the `jami-project` meta-tree. `savoirfairelinux/jami-client-android` is its own actively-mirrored GitHub repo. It has exactly one submodule, `daemon` → `https://review.jami.net/jami-daemon` (Gerrit, **not** GitHub); GitHub never mirrors submodule contents, so the daemon is fetched from Gerrit on clone/submodule-update. The user's machine reaches review.jami.net fine.

## Project identity

| Item | Value |
|------|-------|
| Upstream repo | `savoirfairelinux/jami-client-android` (remote `upstream`, HTTPS, fetch-only) |
| Fork repo | `git@github.com:ShiroiKuma0/jami.git` (remote `origin`, SSH — push here) |
| Local working tree | `~/git/shiroikuma-jami` |
| Android app subdir (Gradle root) | `jami-android/` |
| Native submodule | `daemon` → `https://review.jami.net/jami-daemon`, built from source |
| Custom applicationId | `shiroikuma.jami` |
| Custom app label | `白い熊 GNU Jami` |
| Java/Kotlin namespace (unchanged) | `cx.ring` |
| Product flavor | `withUnifiedPush` (Google-free push via a UnifiedPush distributor, e.g. ntfy) → task `assembleWithUnifiedPushRelease`. Needs an installed UnifiedPush distributor + DHT proxy ON so backgrounded accounts deactivate (idle CPU ~0%). The old `noPush` flavor kept the daemon awake 24/7 (10-35% idle) and is retired. Also keep **local peer discovery / mDNS OFF** unless on a multi-device LAN — it's useless for a single internet-connected device. |
| Target ABI | `arm64-v8a` only, via `-Parchs=arm64-v8a` |
| Custom signing keystore | `~/.android-keystores/jami-custom.jks` (PKCS12, alias `jami-custom`, passphrase `jami-shiroikuma`) |
| Output APK directory | `~/tmp/` (local backup) + on-device `/sdcard/tmp/` |
| Build host | Tuxedo OS (Ubuntu Noble base) |
| Build JDK | OpenJDK 21 at `/usr/lib/jvm/java-21-openjdk-amd64` (Java target is 17; 21 host builds it fine) |
| Android SDK | `~/android-sdk` (`sdkmanager` at `~/android-sdk/cmdline-tools/latest/bin/sdkmanager`) |
| NDK | `29.0.14206865` (SDK **beta** channel) |
| CMake | `4.1.2` (SDK **beta** channel) — invoked by AGP externalNativeBuild |
| build-tools / platform / compileSdk | `36.1.0` / `android-36` / `36` |
| SWIG | ≥ **4.2** required (apt `swig` 4.2.0 on Noble is OK) |
| APK filename | `shiroikuma-jami_<versionName>_arm64-v8a.apk` (versionName already carries the `+N` build tail; no timestamp) |

`versionName` (e.g. `20260515-01`) and `versionCode` (e.g. `494`) come from upstream, but `custom` rewrites both to carry a per-build `+N` tail (see **Build versioning & quieter logs** under Customization layers), so every rebuild is an Android upgrade, never a downgrade.

Apply the `shell-block-formatting` conventions: cyan `>>>` echo prefix on every command, stderr recolored red via the ANSI-C-quoted `r()` helper — `r() { "$@" 2> >(sed $'s/.*/\033[1;31m&\033[0m/' >&2); }` — and a `read -p` pause gate before the (expensive) Gradle build.

## Branch / remote model (same as SimpleX / ArcaneChat)

| Branch | Purpose | Update mode |
|--------|---------|-------------|
| `master` | Mirrors `savoirfairelinux/jami-client-android`. Never carries our changes. | Fast-forward only |
| `custom` | Carries the install edits + theme/fonts layers below. | Rebased onto each upstream tip at sync |

`origin` = the user's fork (SSH, push). `upstream` = savoirfairelinux (HTTPS, fetch only). The daemon submodule's commit is pinned by the superproject gitlink, so it follows upstream automatically on rebase; re-run `git submodule update --init --recursive` after every checkout/rebase.

## The four install customizations on `custom` (install-critical, committed)

1. **applicationId** — `jami-android/app/build.gradle.kts`: add `applicationId = "shiroikuma.jami"` to `defaultConfig`. (Upstream sets only `namespace = "cx.ring"`; with no applicationId it defaults to the namespace. `namespace` stays `cx.ring` — it's the R/BuildConfig package.)
2. **App label** — `jami-android/app/src/main/res/values/strings.xml`: `app_name` (translatable=false) → `白い熊 GNU Jami` (was `Jami`).
3. **JAMI_DATADIR** — `jami-android/app/build.gradle.kts`: the CMake arg `-DJAMI_DATADIR=/data/data/$namespace/files` bakes in `cx.ring`; since applicationId now differs, change it to `-DJAMI_DATADIR=/data/data/shiroikuma.jami/files` so the daemon's data path matches the real install dir.
4. **FileProvider authority** — `jami-android/app/src/main/AndroidManifest.xml`: the `<provider android:name="androidx.core.content.FileProvider">` hardcodes `android:authorities="cx.ring.file_provider"`. Change to `android:authorities="${applicationId}.file_provider"`. **This is mandatory** — without it the install is refused with `INSTALL_FAILED_CONFLICTING_PROVIDER` (the authority collides with official cx.ring), and even if it installed it would crash on file-share because `ContentUri.kt` builds the authority from `BuildConfig.APPLICATION_ID` (= `shiroikuma.jami.file_provider`). The `${applicationId}` placeholder makes manifest and code agree. (The only other declared authority, `${applicationId}.androidx-startup`, is already placeholder-based — nothing else collides.)

These commit cleanly and rebase without conflict in normal cases.

## Customization layers on `custom`

Beyond the four install edits above, `custom` carries a **yellow-on-black theme**, a **UI fonts & colors** feature (per-element fonts *and* runtime text colours), and a **split-view toggle**. All of it replays on rebase; commit subjects are self-describing (`git log master..custom`). The stack is **31 commits** over upstream as of 2026-05-29 (tip `d142957`): the two install commits, the colour-theme steps, the line-driven sizing, the status-icon/badge/avatar/top-bar passes, the split-view toggle, the fonts-settings reshape, the squashed **UI fonts & colors** commit, the **presence-dot colours + black-yellow settings theming** commit, then the Stage B / build-config run: **bubble fill+border colours**, the **AGP-9 built-in-Kotlin build-config migration + libjamiclient warning suppression** (one commit), the **RGBA-slider colour picker**, **link-preview / file card fill+border + account-badge colours**, **status-icon / online-offline-icon / unread-row-border colours**, a **file-card overflow fix** (outgoing card left-anchor), and an **online/offline menu icon swap fix** (`MenuItemCompat.setIconTintList` instead of mutating-and-reassigning the icon drawable). Stage B (the runtime-recolour pass over bubbles → cards/badges → misc surfaces) is **complete**.

### Yellow-on-black theme
Black backgrounds + `#FFFF00` foreground across chat list, chat text, toolbars, bubbles, compose/search bars, dialogs, file messages, link-preview cards, unread/pending badges, generated avatars and the home top bar. Key resources:
- `values/colors.xml` + `values-night/colors.xml` (`background`->#000000, `colorOnSurface`->#FFFF00); styles `ShiroikumaToolbar` / `ShiroikumaDialog` + `TextAppearance.Shiroikuma.SearchBar` in `values/styles.xml`; drawable `dialog_black_yellow.xml`.
- **Settings-screen theming (black/yellow).** The custom settings screen (`frag_settings.xml`) uses `MaterialSwitch` toggles (via `CustomMaterialSwitch` -> `material_switch_*_tint` CSLs) and group cards styled `CustomRelativeLayout` whose background is `@drawable/rounded_background`. Toggles are yellow-when-on via `material_switch_track_checked`->#FFFF00 + `material_switch_thumb_checked`->#000000 — set in **both** `values/colors.xml` AND `values-night/colors.xml`, because Dark-theme forces night mode so the **night** values are what render (the original blue was night's `color_primary_light`). Cards are black via `rounded_background.xml` -> black fill + 2dp #FFFF00 stroke (matches `dialog_black_yellow`); **do NOT repoint `@color/settings_option_background`** — it also colours the *unchecked* switch track, which must stay grey. Leave the unchecked switch colours alone so "off" still reads. `rounded_background` is shared with `frag_acc_summary.xml` + `menu_conversation.xml`, which get the same on-theme look.
- **Presence dots** (chat-list avatars, global): `AvatarDrawable.presenceConnectedColor`/`presenceAvailableColor` read `ColorPrefs.getColor(PRESENCE_CONNECTED/PRESENCE_AVAILABLE)` (defaults == the old `online_indicator` yellow / `available_indicator` light-blue), exposed as the "Presence dots" group in the UI fonts & colors screen; refresh on the existing live-refresh since avatars rebuild on bind.
- Bubble/card shapes (black fill + 2dp #FFFF00 stroke + rounded corners): `textmsg_bg_*`, `filemsg_background_*`, `linkpreview_bg_*`, `textmsg_bg_preview`.
- **Unread/pending badges** (black fill, 2dp yellow rounded-square border, **bold yellow** count): drawable `badge_black_yellow.xml` backs the `invitationBadge` TextView in `res/layout/item_account.xml` (account-selection dialog); `AccountAdapter` now **recolours it at runtime** from `BADGE_FILL/BORDER` when the badge is shown (mutates its `GradientDrawable`). The `frag_invitation_card.xml` two-tone `new_invitation` envelope vector is **not** runtime-recoloured — that layout has no Kotlin binding site.
- **Generated (no-photo) avatars** in `java/cx/ring/views/AvatarDrawable.kt`: black fill (`color = Color.BLACK` in the text/placeholder branch), yellow initials/placeholder (`textPaint` colour + the placeholder `setColorFilter` -> `Color.YELLOW`), and a yellow ring drawn in `draw()` **only when `bitmaps == null`** (photo avatars get no ring), stroke width ~8% of the radius via the new `avatarRingPaint` member. The `getAvatarColor` palette is now unused (harmless warning).
- **Home top bar** (`HomeFragment.kt` + `res/layout/frag_home.xml`): the account avatar is the SearchBar `navigationIcon`, rebuilt per current account. Enlarged ~50% via `AvatarDrawable.setInSize(54dp)` (36dp default) with the `BitmapUtils.withPadding` inset trimmed 6dp->2dp — the Toolbar nav button uses CENTER scaleType, so a larger drawable intrinsic size simply draws bigger. The "Search or add" hint is bumped to 24sp through `android:textAppearance="@style/TextAppearance.Shiroikuma.SearchBar"`; Material `SearchBar` **does** honour `android:textAppearance` for the hint (confirmed on-device).
- Runtime tint-clearing in `MessageBubble.setBubbleColor`, `ConversationAdapter` (file icons/bubbles) and `AccountAdapter`.

Lessons banked: M3 dialogs are grey via `colorSurfaceContainerHigh` (tonal), not `colorSurface`; the search bar grey was a Material **elevation overlay** (`elevationOverlayEnabled=false` to kill it); "black border" meant black fill + **yellow** outline; for the Chip badge use chip-specific attrs (chipBackgroundColor/chipStrokeColor/chipStrokeWidth/chipCornerRadius), not `android:background`/`backgroundTint`; scope the avatar ring to `bitmaps == null` so photo avatars aren't ringed; the SearchBar nav icon scales with the drawable's intrinsic size (`setInSize`), not via the toolbar.

### UI fonts & colors (per-element fonts + runtime text colours)
A single **"UI fonts & colors"** screen lets each text surface pick **family + weight + size** AND, per element, **text colour(s)** — every colour defaulting to the current palette value, so an unset element looks exactly as before. Reached two ways: the **App Settings overflow menu -> "UI fonts & colors"** (jumps straight in), and **Settings -> Appearance -> "UI fonts & colors"** (row `settings_fonts_layout`; the "Appearance" header in `frag_settings.xml` MUST carry explicit `layout_width`/`layout_height` — `SettingsHeader` omits them and inflation crashes otherwise — this bit us once). External `.ttf`/`.otf` import from `/sdcard` via SAF (no permission), stored in `filesDir/fonts`, referenced as `file:<path>`.

Foundation (all under `jami-android/app/src/main`, package `cx.ring`):
- `java/cx/ring/utils/FontPrefs.kt` — `shiroikuma_fonts` prefs; per-category family/weight/size + DEFAULT fallback (`effectiveFamily/Weight/Size`); imported-font registry. Categories: `DEFAULT`, `CHAT_TEXT`, `CONV_TITLE`, `LIST_TITLE`, `LIST_PREVIEW`, `SETTINGS`, plus **`LIST_DATE`, `MSG_TIME`, `SEARCH_HINT`** (added with the colours work).
- `java/cx/ring/utils/ColorPrefs.kt` — **sibling to FontPrefs for per-element runtime colours** (text, fills, borders, tints). `shiroikuma_colors` prefs, `color_<role>` keys, `isSet`/`getColor`/`setColor`/`reset`, and a central `defaultColor(ctx, role)` so an unset role returns the exact current value (default `YELLOW 0xFFFFFF00`; `R.color.textColorSecondary` grey for `LIST_DATE`/`MSG_TIME`/`LINK_DOMAIN`; `R.color.available_indicator` for `PRESENCE_AVAILABLE`; `BLACK 0xFF000000` for all `*_FILL` roles; `R.color.grey_500` for `STATUS_SENDING`/`STATUS_SUCCESS`/`STATUS_OFFLINE`; green `0xFF4CAF50` for `STATUS_ONLINE`). Roles: text — `LIST_NAME`, `LIST_PREVIEW`, `LIST_DATE`, `CONV_TITLE`, `MSG_SENT`, `MSG_RECEIVED`, `MSG_TIME`, `LINK_TITLE`, `LINK_DESC`, `LINK_DOMAIN`, `FILE_NAME`, `FILE_ARROW`, `SEARCH_HINT`, `SETTINGS`; presence — `PRESENCE_CONNECTED`, `PRESENCE_AVAILABLE`; bubble/card fill+border — `MSG_SENT_FILL/BORDER`, `MSG_RECEIVED_FILL/BORDER`, `LINK_CARD_FILL/BORDER`, `FILE_CARD_FILL/BORDER`, `BADGE_FILL/BORDER`; status/indicator tints — `STATUS_SENDING`, `STATUS_SUCCESS`, `STATUS_ONLINE`, `STATUS_OFFLINE`, `UNREAD_BORDER`. "Unset" is tracked by key presence (0 is a valid colour). **Pattern for icon tints whose intrinsic look must be preserved (`STATUS_*`): gate on `isSet` — tint only when the user has picked, else leave the drawable's intrinsic colour / null tint.**
- `java/cx/ring/utils/FontUtil.kt` — `resolveTypeface`, idempotent `apply`/`applyTree`/`installSettingsFont`, `chatTextLineHeightPx`/`statusIconSizePx`, `FAMILIES`/`WEIGHTS`. `installSettingsFont` now also paints the `SETTINGS` colour over the tree (`applySettingsColor`/`colorTree`). **Critical guard: `applyTree` and `colorTree` skip any subtree whose root `tag == SKIP_SETTINGS_FONT_TAG`.** The fonts screen tags its own root with that constant — otherwise the settings-font `OnGlobalLayoutListener` (registered on the parent `SettingsFragment` view, which contains the fonts `fragment_container`) re-applies the SETTINGS font/size over the whole screen on every layout pass, flattening the group/element heading sizes and overwriting every live preview. That was the root cause of "group headers not bigger" + "size/weight/font changes don't show in the preview."
- `java/cx/ring/settings/FontsSettingsFragment.kt` + `res/layout/frag_fonts_settings.xml` — plain `Fragment`. Data-driven `groups -> elements`; each element has an optional font category + a list of colour roles. Renders per element: a bold **group header** (30sp, full-width underline), an indented **element heading** (18sp, **text-width** underline drawn as a `LayerDrawable` bottom band on a wrap_content view — a `MATCH_PARENT` underline `View` stretches full width inside a wrap_content parent, which was the "underline spans whole line" bug), then further-indented font controls (family/weight/size value rows + live preview + SeekBar) and/or colour rows (swatch + label + hex/"Default"). **Colour picker = `MaterialAlertDialog` with a live preview swatch, four 0-255 SeekBars (Alpha/Red/Green/Blue) with numeric labels, and a two-way `#AARRGGBB` hex field** (slider→hex and valid-hex→sliders, with a `var updating` guard + the SeekBar `fromUser` check to break the loop); alpha is exposed for every role so any colour can be translucent. "Default" resets the role; OK commits the live value. (Earlier hex-field + 7-preset picker and the standalone `FontPickerDialog.kt`/`dialog_font_picker.xml` are gone/dead.) `onCreateView` sets `root.tag = FontUtil.SKIP_SETTINGS_FONT_TAG`; **`onDestroyView` calls `(activity as? HomeActivity)?.refreshThemedViews()`** for live refresh. **To add an element: one entry in `groups` + the matching `FontUtil.apply` / `ColorPrefs.getColor` at that surface's bind site.**

Apply-points wired (fonts after any typeface reset; colours via `ColorPrefs.getColor`, default == current so unset is a no-op):
- `CHAT_TEXT` font -> `MessageBubble.updateStandard`; **per-message text + time colour + `MSG_TIME` font** -> `MessageBubble.applyShiroikuma(textColor, timeColor)`, called from `ConversationAdapter` after `updateStandard`/`updateEmoji` with `MSG_SENT`/`MSG_RECEIVED` (chosen by `interaction.isIncoming`) and `MSG_TIME`.
- `CONV_TITLE` font + colour -> `ConversationFragment` (`contactTitle`).
- `LIST_TITLE`/`LIST_PREVIEW`/`LIST_DATE` fonts + `LIST_NAME`/`LIST_PREVIEW`/`LIST_DATE` colours -> `SmartListViewHolder` (`convParticipant`/`convLastItem`/`convLastTime`), after the read/unread bold block.
- Link preview (`LINK_TITLE`/`LINK_DESC`/`LINK_DOMAIN`) + file message (`FILE_NAME` on name+size, `FILE_ARROW` as `imageTintList` on the download button) -> `ConversationAdapter` at those bind sites. **Card fill+border** (`LINK_CARD_*`, `FILE_CARD_*`) and **bubble fill+border** (`MSG_*_FILL/BORDER`) are recoloured at runtime by `ConversationAdapter.applyBubbleColors` / `applyCardColors` — both `mutate()` the shape's `GradientDrawable` (or the reply `LayerDrawable`'s `main_bubble` layer) and set `solid` + 2dp `stroke`; called after `setBackgroundResource`/`updateMessageBackground`. The file card's call also clears `mFileInfoLayout`'s tint so the fill shows. **Account/invitation count badge** (`BADGE_FILL/BORDER`) is recoloured in `AccountAdapter` when the `invitationBadge` is made visible (mutates its `GradientDrawable` bg).
- `SEARCH_HINT` font + hint colour -> `HomeFragment` on `searchBar.textView` (Material `SearchBar.getTextView()`).
- `SETTINGS` font + colour -> `FontUtil.installSettingsFont` (SettingsFragment / BasePreferenceFragment / VideoSettingsFragment).
- **Status-icon + online/offline-icon + unread-row tints (Stage B, shipped).** `MessageStatusView.updateSending`/`updateSuccess` tint the SENDING and SUCCESS icons from `STATUS_SENDING`/`STATUS_SUCCESS`, **gated on `isSet`** so the current look (grey sending / intrinsic success) is preserved until a colour is picked; the DISPLAYED state stays as read-receipt **avatars** (not colourable). `HomeFragment` tints the account online/offline top-bar menu icon (`STATUS_ONLINE`/`STATUS_OFFLINE`, isSet-gated). `SmartListViewHolder` recolours the 2dp stroke of the unread-row `background_item_smartlist_unread` layer-list from `UNREAD_BORDER` (default yellow). All exposed in a **"Status & indicators"** group. (The status-icon *height* below is separate and shipped earlier.)
- **NOT done — theme-level surfaces (app background + accent `colorOnSurface`).** Both are static theme attributes (`@color/background` is also hardcoded in ~13 layouts), resolved at inflation. A runtime `ColorPrefs` dial would be invisible (root colour sits behind opaque surfaces) or a no-op (`recreate()` re-reads the same static colour) — so they were deliberately left out rather than ship dead controls. If ever wanted, the real path is a few **predefined theme variants** switched via `recreate()` (fixed choices, not the RGBA picker) — a separate effort.

**Live refresh** (changes apply on leaving the screen, not only after an account switch): `HomeActivity.refreshThemedViews()` -> `mHomeFragment?.refreshSmartListTheme()` (-> `SmartListFragment.refreshTheme()` -> `notifyDataSetChanged`) + `fConversation?.refreshTheme()` (-> `notifyDataSetChanged`); called from `FontsSettingsFragment.onDestroyView`. Necessary because Settings is an `R.id.frame` overlay, so the chat list behind it never receives `onResume` — only an account switch (a data change) forced a re-bind before.

**Menu entry:** `menu_ui_fonts_colors` in `res/menu/smartlist_menu.xml` (overflow, under "App Settings") -> `HomeFragment` handler -> `HomeActivity.goToAdvancedSettings(openFonts = true)`, which sets an `open_fonts` argument that `SettingsFragment.onViewCreated` consumes via `view.post { goToFontsSettings() }`.

**Line-driven sizing** (so larger fonts stay legible instead of clipping):
- `res/layout/item_smartlist.xml` row: fixed `72dp` height -> `wrap_content` + `minHeight=72dp`.
- `SmartListViewHolder`: the avatar (`photo`) is sized from `convParticipant.lineHeight + convLastItem.lineHeight` (+3dp), floored at 56dp. **Use `val b = binding; if (b != null) { … }`, never `run { }`** — a `run{}` lambda loses the nullable-`binding` smart-cast and fails to compile.
- Message **status icon** height: `MessageStatusView.setIconSize(px)`; `ConversationAdapter.configureDisplayIndicator` (runs for **every** message type) sets it to `FontUtil.statusIconSizePx(context)`. Configurable via `FontPrefs.getStatusIconLines` (key `status_icon_lines`, default `1.0` × chat-text line height); control at **Settings -> Appearance -> "Message status icon height"** (`SettingsFragment.showStatusIconSizeDialog`, 0.5x-3.0x). Outgoing text bubble (`item_conv_msg_me.xml`) + image are constrained `End_toStartOf="@id/status_icon"` so a large icon doesn't overlap.

Recycle caveat: applies/colours are non-destructive (only change when set), and the live-refresh re-binds the list + open conversation on screen exit, so changes show immediately there. The conversation **title** is no longer recycler-bound but `ConversationFragment.refreshTheme()` now re-applies its `CONV_TITLE` font+colour to `binding.contactTitle`, so a title change also lands on fonts-screen exit (visible live in split-view; on a single pane it's seen on return).

### Split-view toggle
A toggle to force single-pane — disable the side-by-side list+conversation that the tablet/foldable layout shows on wide/unfolded screens. Stored in **`UiPrefs`** (`java/cx/ring/utils/UiPrefs.kt`, prefs file `shiroikuma_ui`, key `split_view`, default **on**). Exposed two ways, both writing the same pref: **Settings -> Appearance -> "Split view"** (the `settings_split_view` switch, wired in `SettingsFragment`) and a **checkable item in the search-bar overflow menu** (`menu_split_view` in `res/menu/smartlist_menu.xml`, handled in `HomeFragment`'s `setOnMenuItemClickListener`, with initial `isChecked` set right after `inflateMenu`).
- `HomeActivity` owns application of the pref: `isSplitViewEnabled` / `setSplitViewEnabled` / `applySplitViewPref`; `applySplitViewPref` sets `mBinding?.panel?.forceSinglePane` and is called in `onStart`, so a change made in settings takes effect on return to the list.
- `TwoPaneLayout.forceSinglePane` (the custom SlidingPaneLayout fork) is the mechanism. **Force it in the `onMeasure` first pass** — `lp.slideable = forceSinglePane || widthRemaining < 0` — so `mSlideableView` and the slide-mode (full-width) child measurement are set up exactly as on a narrow screen. **Do NOT force `canSlide = true` late (after the measure loop): that leaves `mSlideableView` null and the app crashes with an NPE when opening a conversation.** (This cost one build.)

### Build versioning & quieter logs
Two committed `jami-android/app/build.gradle.kts` tweaks (localized to that file; re-derive on rebase like the install-identity edits):
> **Build-warning state (current).** The two AGP-9 warnings that used to print — `WARNING: … android.suppressUnsupportedOptionWarnings … is experimental` (#1a) and `w: ⚠ Deprecated 'org.jetbrains.kotlin.android' plugin usage` (#1b) — are **GONE** as of the built-in-Kotlin migration commit, as are the `:libjamiclient` Kotlin `w:` lines. **Two harmless warnings still print and are NOT repo-fixable — do not chase them unless the user explicitly asks:**
> 1. `[CXX5304] This version only understands SDK XML versions up to 3 but … version 4 …` — a native-build note from an **NDK/SDK-tooling version skew on the build machine** (the NDK's bundled SDK parser is older than the installed SDK packages). Fixable only by aligning the NDK / cmdline-tools versions locally, never by a source patch.
> 2. javac `ノート:` deprecation/unchecked notes (host-locale Japanese) — javac **mandatory** notes from the app's Java sources + the SWIG-generated daemon bindings. No reliable global mute flag (`-nowarn`/`-Xlint:none` don't suppress mandatory notes); only durable fix is `@SuppressWarnings` at each site, and the generated bindings regenerate each build. Can be **enumerated** with a one-off `-Xlint:deprecation,unchecked` JavaCompile arg if the user ever wants the offender list.

- **Per-build version tail.** `versionCode` and `versionName` honour a Gradle property `shiroikumaBuild` (default 0): `versionCode = <upstreamCode> * 10000 + shiroikumaBuild`, `versionName = "<upstreamBase>" + (if N>0 then "+N")`. A build invoked with `-PshiroikumaBuild=N` comes out as `20260515-01+N` / versionCode `4940000+N` — always an upgrade over prior builds and above the next upstream code bump. The build block keeps **N in a TRACKED, COMMITTED counter** `jami-android/shiroikuma-build.txt` (one line: `<upstreamBase> <N>`): it increments per **successful** build and **resets to 1 when the upstream base changes**; consumed only on success. APK name is `shiroikuma-jami_<versionName>_arm64-v8a.apk` (no timestamp). **The counter is in-repo (not `~/tmp`) on purpose — a `~/tmp` counter was ephemeral and got lost, resetting N to 1 and producing a *downgrade* APK. On every successful build the block stages ONLY `jami-android/shiroikuma-build.txt`, commits it (`build: bump version tail to <vn>`), and pushes to `origin custom` — this push is the one exception to "no push until the user says Push" (the user requested the bump number always be committed+pushed). It is narrow: never let that commit pick up `daemon/`, generated JNI, or feature code.**
- **Build config — AGP-9 built-in Kotlin (committed, `jami-android/gradle.properties` + both `build.gradle.kts`).** `android.builtInKotlin=true`, and the standalone `alias(libs.plugins.kotlin.android)` is **removed** from `:app` and the root `plugins { }` (built-in Kotlin provides it → removes #1b). **`android.newDsl=false` MUST stay** — the `protobuf-gradle-plugin` casts the Android extension to the legacy `BaseExtension`, which doesn't exist under the new DSL (config-time `GroovyCastException` at `ProtobufPlugin.doApply`; this cost a failed build). `android.suppressUnsupportedOptionWarnings` is kept and **self-included** (`=android.builtInKotlin,android.newDsl,android.suppressUnsupportedOptionWarnings`) so it no longer warns about itself → removes #1a. Built-in Kotlin tolerates the legacy DSL here (verified by a fast `./gradlew :app:help --console=plain` config check before the full build). **`:libjamiclient/build.gradle.kts` has `suppressWarnings = true`** in its `kotlin { compilerOptions { } }` (mirroring `:app`) to silence the pre-existing upstream `w:` lines that surface on a clean recompile. `@file:Suppress("DEPRECATION")` atop `:app`'s `build.gradle.kts` still silences the build-script DSL deprecation.
- **The `BasePreferenceFragment.java` unchecked note** (`@SuppressWarnings("unchecked")` on `onCreatePreferences`) is committed and independent of the above — leave it.
- **Migration caveat for rebases:** built-in Kotlin fights upstream's deliberate `builtInKotlin=false` (blame `90da59f`, chosen for the Hilt+KSP+protobuf+kapt+native-CMake build). It can't be statically verified in the sandbox. If a future rebase/upstream bump breaks the build on this, suspect the `kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }` block / `tasks.withType<KotlinCompile>()` / the `JvmTarget`/`KotlinCompile` imports in `:app`; fallback is to restore `builtInKotlin=false` + the `kotlin.android` plugin alias in `:app`+root and re-mute via the self-included suppress line.
- All these `gradle.properties` / `build.gradle.kts` edits sit on upstream's flags, so they are clobbered every rebase and replay with the rest of the `custom` stack.

## The daemon-contrib fix (NOT committed — re-applied each build)

This lives in the `daemon` submodule, so it is applied as an **idempotent sed in the build block** rather than committed, and re-applies itself on every pull:

**gnutls brotli/zstd off.** `daemon/contrib/src/gnutls/rules.mak` adds `--without-brotli`/`--without-zstd` to gnutls's configure only for macOS and iOS — the Android branch is missing them, and Jami's contrib has no `brotli`/`zstd` package to build. On a desktop host (which has brotli/zstd dev headers lying around), gnutls's configure auto-detects them, enables the compression shim, then the arm64 cross-compile dies with `fatal error: 'brotli/encode.h' file not found` (the `yaml-cpp` CMake `find_package` error after it is just cascade fallout once contrib's `make` aborts). The fix appends the two flags to the unconditional `--without-idn` line:

```bash
grep -q -- '--without-idn --without-brotli' daemon/contrib/src/gnutls/rules.mak \
  || sed -i 's/--without-idn/--without-idn --without-brotli --without-zstd/' daemon/contrib/src/gnutls/rules.mak
```

**Guard gotcha (cost a wasted build):** do NOT guard on `grep -q -- '--without-brotli'` — that string already exists in the macOS/iOS blocks, so the guard always passes and the sed never runs. Guard on the **combined** string `--without-idn --without-brotli`, which only exists once our edit is in.

If a gnutls rebuild after applying the fix still fails on the brotli header, the previous run left a brotli-configured tree behind; clean just that package and rebuild:
`rm -rf daemon/contrib/build-aarch64-linux-android/gnutls daemon/contrib/build-aarch64-linux-android/.gnutls`

## Other build traps

- **NDK 29 / CMake 4.1.2 are on the SDK beta channel.** Install with `sdkmanager --channel=1`; a stable-channel sdkmanager won't list them.
- **`[CXX5304] … SDK XML versions up to 3 … version 4 was encountered`** (printed twice, once per native config pass) is an **environment toolchain mismatch** in the daemon's native build: the installed cmdline-tools/sdkmanager wrote a package `package.xml` at schema **v4**, but the NDK's bundled SDK-meta parser only understands **≤v3**. It is **harmless** and **NOT fixable in project config** — there is no Gradle/CMake suppression for it. It clears only by aligning the cmdline-tools and NDK versions (update or pin both via `sdkmanager`). Do not chase it and do not try to silence it in the build files.
- **Host-lib contamination is a class, not a one-off.** The desktop has more dev libraries than upstream's clean CI container, so other contrib packages can pick up host libs the same way gnutls did. If a new contrib package fails on a missing Android-sysroot header for something the host has, the fix is the same shape: disable that feature in the package's `rules.mak` configure line.
- **Release is unsigned upstream.** The declared `signingConfigs { config }` is never attached to `release`, and there's no `keystore.bin` in-tree. So we build the unsigned release and zipalign + apksigner it ourselves.
- **pkg-config note (not yet hit).** Upstream warns that after a clean, Jami's own cross-compile pkg-config may not rebuild and fall back to the system one. If shared-lib location errors appear, `cd daemon/extras/tools && ./bootstrap && make`.
- **Moving the local repo breaks the daemon contrib's baked paths.** contrib bakes *absolute* install prefixes into its generated pkg-config `.pc` files and CMake metadata. After relocating the working tree (this happened on the `~/git/jami` -> `~/git/shiroikuma-jami` rename), the CMake **configure** step dies with `CMake Error ... Imported target "PkgConfig::dhtnet" includes non-existent path ".../OLD/daemon/contrib/aarch64-linux-android/include"`. Fix without a full contrib rebuild — rewrite the old prefix across contrib metadata, drop the CMake configure cache, rebuild:
  ```bash
  OLD=$HOME/git/jami; NEW=$HOME/git/shiroikuma-jami
  grep -rlI -- "$OLD/" daemon/contrib | while read f; do sed -i "s|$OLD/|$NEW/|g" "$f"; done
  rm -rf jami-android/app/.cxx       # CMake configure cache only; compiled contrib is kept, reconfigure is ~1s
  ```
  Bulletproof fallback if a non-text config can't be rewritten: `rm -rf daemon/contrib/aarch64-linux-android daemon/contrib/build-aarch64-linux-android` and rebuild (slow, tens of minutes).
- **Conversation-item ConstraintLayout edits can't be previewed in the sandbox — change ONE constraint at a time and verify on-device.** Stacking several constraint changes at once cost two regressions on the outgoing file card (`item_conv_file_me.xml`): a multi-change patch that moved the status tick beside the card + re-anchored both ends first overflowed the card off the **left**, then a follow-up blanked the whole conversation (a render failure RecyclerView couldn't recover from). The shipped fix is a **single** line and nothing else. Banked facts about these layouts: outgoing **text bubble** (`message_content`) and **image** already constrain `End_toStartOf="@id/status_icon"` (they reserve the tick column); the **file card** historically did not. The **download button is `GONE` on a sent (outgoing) file**, so anchoring the card's `Start` to it (`Start_toStartOf="@id/file_download_button"`, upstream) left `constrainedWidth` with no firm left bound and a long filename overflowed. **Shipped fix (commit `dddd66a`): `fileInfoLayout` → `Start_toStartOf="parent"`; status tick and right edge left exactly as upstream (tick stays *below* the card).** The "tick beside / right-anchored" idea is NOT in the tree — do not reintroduce it without on-device iteration. The **incoming** file card (`item_conv_file_peer.xml`) was deliberately left untouched: it's bounded by the download button in the normal case, with the same button-`GONE` edge case latent but lower-risk — only revisit if incoming cards are actually seen to overflow.

## One-time toolchain preflight

Host deps (Ubuntu/Noble), SDK beta packages, and the stable keystore. Run once.

```bash
r() { "$@" 2> >(sed $'s/.*/\033[1;31m&\033[0m/' >&2); }

r sudo apt-get update
r sudo apt-get install -y build-essential autoconf automake autogen autopoint libtool m4 nasm yasm ninja-build pkg-config gettext bison bc bzip2 curl unzip zip python-is-python3 cmake swig libpcre2-dev libpcre3-dev

r swig -version   # must be >= 4.2

export SDKMANAGER=$(find ~/android-sdk/cmdline-tools -name sdkmanager -type f 2>/dev/null | head -1)
r bash -c 'yes | "$SDKMANAGER" --channel=1 --licenses'
r "$SDKMANAGER" --channel=1 "ndk;29.0.14206865" "cmake;4.1.2" "build-tools;36.1.0" "platforms;android-36"

mkdir -p ~/.android-keystores
[ -f ~/.android-keystores/jami-custom.jks ] || r keytool -genkeypair -v -keystore ~/.android-keystores/jami-custom.jks -storetype PKCS12 -keyalg RSA -keysize 4096 -validity 10000 -alias jami-custom -dname "CN=shiroikuma jami, O=shiroikuma, C=JP" -storepass jami-shiroikuma -keypass jami-shiroikuma
```

## First-time clone

```bash
r() { "$@" 2> >(sed $'s/.*/\033[1;31m&\033[0m/' >&2); }

cd ~/git
r git clone git@github.com:ShiroiKuma0/jami.git shiroikuma-jami
cd ~/git/shiroikuma-jami
r git remote add upstream https://github.com/savoirfairelinux/jami-client-android.git
r git fetch upstream
r git checkout -b custom master
r git submodule update --init --recursive   # daemon from review.jami.net, large
```

Then apply the four customizations (edits 1–4 above), commit on `custom`, and push. After that, every build uses the block below.

## Per-change delivery workflow (STRICT — the user tests between every step)

The user builds, sideloads and tests **every** change on-device before it is ever committed. Follow this ordering exactly, with no exceptions:

1. **Deliver the patch + the apply-and-build block ONLY.** Generate the change as a `.patch` (see `patch-naming`), present it, and give the shell block that applies it and builds/signs/sideloads. **Then stop.** Do **NOT** include any `git commit`, `git push`, `git fetch`, rebase, or `git reset --hard` code in that turn — not even "for when you're ready". Pre-empting the push is wrong every single time.
2. **Wait for the user to test.** They may report problems; iterate with more build-only patches. Multiple fix patches can stack on the uncommitted working tree (build block stays `git checkout custom` to keep prior uncommitted patches in place).
3. **Only when the user explicitly says "Push"** do you provide, in that turn: the commit + push block (stage explicit `jami-android/app/src/main` paths — never the daemon submodule or generated JNI — `git commit`, `git push origin custom`), and then the sync/verify in the recon clone (`git fetch fork custom`, check the new tip's content + that the `daemon` gitlink still equals upstream, then `git reset --hard fork/custom`).

One logical change per patch; build-only until the user says "Push". If several related uncommitted patches were tested together, they may be squashed into one commit at push time when that reads as a single logical change.

## Build + sign + deploy

> **Use the block below verbatim — do not hand-roll a fresh one from memory.** Every glitch in practice has come from re-deriving the block instead of lifting it: dropping the `JAVA_HOME` export (Gradle aborts on JDK 11), guessing the build-tools path (it is `~/android-sdk/build-tools/36.1.0`, exported onto `PATH` so `zipalign`/`apksigner` are called bare — never `$BT/zipalign`), adding a `$(date)` stamp to the APK name (there is none — `versionName` carries `+N`), and omitting the loud connect-phone gate before `adb push`. Copy this block; change only the patch path and the `-PshiroikumaBuild` plumbing.


The daemon and all contrib compile from source on the **first** build (long — tens of minutes, a few hundred MB downloaded; LTO is on). Subsequent builds reuse the cache and finish in minutes; contrib only recompiles when the daemon submodule moves.

> **MANDATORY — never emit a build block without the `JAVA_HOME` export below.** The host's default `java` on `PATH` is **JDK 11**; Gradle 9.x aborts at startup with `Gradle requires JVM 17 or later to run. Your build is currently configured to use JVM 11.` before compiling a single line. The `export JAVA_HOME=<JDK21>` + `PATH` lines are not optional boilerplate — they are the fix. If `java -version` still shows 11 after exporting, check for a stale `org.gradle.java.home` in `gradle.properties` / `~/.gradle/gradle.properties`.

```bash
r() { "$@" 2> >(sed $'s/.*/\033[1;31m&\033[0m/' >&2); }

export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$HOME/android-sdk/build-tools/36.1.0:$JAVA_HOME/bin:$PATH"

cd ~/git/shiroikuma-jami
r git checkout custom

# daemon-contrib fix (idempotent; submodule, not committed)
r bash -c "grep -q -- '--without-idn --without-brotli' daemon/contrib/src/gnutls/rules.mak || sed -i 's/--without-idn/--without-idn --without-brotli --without-zstd/' daemon/contrib/src/gnutls/rules.mak"

# SWIG JNI bindings (compile.sh's prerequisite step)
( cd daemon/bin/jni && PACKAGEDIR="$HOME/git/shiroikuma-jami/jami-android/libjamiclient/src/main/java" ./make-swig.sh )

# per-build +N tail (TRACKED in-repo counter; resets when the upstream base changes)
VG="$HOME/git/shiroikuma-jami/jami-android/app/build.gradle.kts"
counter="$HOME/git/shiroikuma-jami/jami-android/shiroikuma-build.txt"
base_vn=$(grep -oP 'versionName = "\K[^"]+' "$VG" | head -1)
code_base=$(grep -oP 'versionCode = \K[0-9]+' "$VG" | head -1)
stored_vn=""; stored_n=0
[ -f "$counter" ] && read stored_vn stored_n < "$counter"
if [ "$stored_vn" = "$base_vn" ]; then N=$((stored_n + 1)); else N=1; fi
versionName="${base_vn}+${N}"
apk_name="shiroikuma-jami_${versionName}_arm64-v8a.apk"
echo -e "\033[1;36m>>> Will produce: $apk_name (versionCode $((code_base*10000+N)))\033[0m"

read -p $'\033[1;33m>>> Continue with the daemon + app build? (y/n) \033[0m' ans
if [[ "$ans" =~ ^[Yy]$ ]]; then
  cd ~/git/shiroikuma-jami/jami-android
  build_ok=0
  r ./gradlew -Parchs=arm64-v8a -PshiroikumaBuild="$N" assembleWithUnifiedPushRelease && build_ok=1

  if [ "$build_ok" != 1 ]; then
    # Only sign on success — a failed Gradle run leaves a stale APK in the output dir that ls would happily pick up and sign.
    echo -e '\033[1;31m>>> Gradle FAILED — NO APK signed. Paste What went wrong / Caused by. Do NOT install any leftover APK.\033[0m'
  else
    echo "$base_vn $N" > "$counter"   # consume the build number only on success
    # Persist the bump number in git so it can never be lost (a lost counter = downgrade APK).
    # Narrow stage: ONLY the counter file. Then commit + push to origin custom.
    ( cd ~/git/shiroikuma-jami \
        && git add jami-android/shiroikuma-build.txt \
        && git commit -m "build: bump version tail to ${base_vn}+${N}" \
        && git push origin custom ) \
      && echo -e "\033[1;36m>>> counter ${base_vn}+${N} committed + pushed\033[0m" \
      || echo -e "\033[1;31m>>> counter commit/push failed — push jami-android/shiroikuma-build.txt manually\033[0m"
    unsigned_apk=$(ls -t app/build/outputs/apk/withUnifiedPush/release/*.apk 2>/dev/null | head -1)
    r ls -lh "$unsigned_apk"
    r zipalign -p -f 4 "$unsigned_apk" /tmp/jami-aligned.apk
    r apksigner sign --ks ~/.android-keystores/jami-custom.jks --ks-key-alias jami-custom --ks-pass pass:jami-shiroikuma --key-pass pass:jami-shiroikuma --out /tmp/jami-signed.apk /tmp/jami-aligned.apk
    r apksigner verify --verbose /tmp/jami-signed.apk

    # local backup FIRST, unconditionally — a missing cable never costs the build
    r bash -c "mkdir -p ~/tmp && cp /tmp/jami-signed.apk ~/tmp/\"$apk_name\""
    r ls -lh ~/tmp/"$apk_name"

    echo -e '\033[1;33m============================================================\033[0m'
    echo -e '\033[1;33m>>> CONNECT YOUR PHONE NOW -- USB plugged in, USB debugging ON.\033[0m'
    echo -e '\033[1;33m>>> The signed APK is already saved in ~/tmp regardless of push.\033[0m'
    echo -e '\033[1;33m============================================================\033[0m'

    read -t 0.1 -n 10000 _flush 2>/dev/null || true   # flush stray newline from the pasted block
    read -p $'\033[1;33m>>> Phone connected? Press ENTER to adb push, or type n to skip: \033[0m' pushans
    if [[ ! "$pushans" =~ ^[Nn]$ ]]; then
      if r adb push /tmp/jami-signed.apk "/sdcard/tmp/$apk_name"; then
        echo -e "\033[1;36m>>> pushed -- install /sdcard/tmp/$apk_name via the phone file manager\033[0m"
      else
        echo -e "\033[1;31m>>> push failed -- sideload ~/tmp/$apk_name via KDE Connect / Bluetooth instead.\033[0m"
      fi
    else
      echo -e "\033[1;36m>>> Skipped. Sideload ~/tmp/$apk_name however you like.\033[0m"
    fi
  fi
else
  echo "Aborted."
fi
```

`assembleWithUnifiedPushRelease` triggers the CMake daemon build automatically (the CMake tasks are wired as dependencies of Kotlin compilation), so there's no separate daemon step beyond the SWIG generation.

## Sync to a new upstream version

```bash
r() { "$@" 2> >(sed $'s/.*/\033[1;31m&\033[0m/' >&2); }

cd ~/git/shiroikuma-jami
r git fetch upstream
r git checkout master
r git merge --ff-only upstream/master
r git push origin master
r git checkout custom
r git rebase master
r git push --force-with-lease origin custom
r git submodule update --init --recursive   # daemon advances with the superproject gitlink
```

Then run the build block (the gnutls sed re-applies itself). Cheap guard each sync: diff upstream's `.gitmodules` against ours in case the daemon URL/path ever changes — both repos are public on GitHub and readable directly. If any custom commit conflicts on rebase, re-derive it against the new files: the install-identity targets (`defaultConfig`, `app_name`, `JAMI_DATADIR`, the FileProvider `<provider>` authority) are stable across versions, and the theme/fonts edits are localized to the files listed in **Customization layers** above.

## Deploy / install notes

- Build output: `~/tmp/shiroikuma-jami_<versionName>_arm64-v8a.apk` (**no timestamp** — `versionName` already carries the `+N` tail; matches the APK-filename row in the env table), plus `/sdcard/tmp/<same>` when the phone is connected. Install on-device via the file manager. Never `adb install`/`adb uninstall` — the user installs manually.
- The keystore is stable, so updates over an existing `shiroikuma.jami` build install in place without uninstall.
- Coexists with official `cx.ring` Jami: different applicationId **and** different FileProvider authority (edit #4). Do not try to install over official Jami — different signing keys, Android refuses.
- If a previous failed attempt left a partial `shiroikuma.jami` record, uninstall that (not `cx.ring`) before installing the fixed APK.

---

**Commit convention — no Claude attribution.** Never add a `Co-Authored-By: Claude …` / "Generated with Claude" trailer to commit messages or PR bodies; end the message at the last line of the body. This overrides the harness default. (Global rule: `~/.claude/CLAUDE.md`.)
