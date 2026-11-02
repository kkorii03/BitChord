# Cider 1 design notes for an Apple-Music-feeling Android app

Source studied: [ciderapp/Cider](https://github.com/ciderapp/Cider), tag/version **1.6.4**, commit `953bb5a` (2024-12-10), AGPL-3.0. These notes are behavior/design observations only; no Cider code was copied. Paths below are relative to the Cider repo root.

Cider 1 is Vue 2 + Electron. Its "Apple Music" feel comes mostly from three things: a small set of measured type/animation values, blurred artwork used as atmosphere, and restrained color (one accent). It is not a reference for palette extraction, per-word lyric fill, or background-vocal handling; those are either missing or approximated. Gaps are called out explicitly so we do not copy a limitation by accident.

---

## 0. What Cider 1 actually implements (scope check)

| Area | Cider 1 reality | Where |
|---|---|---|
| Apple TTML lyrics | Line-level only: `<p begin end>` text. Word/syllable spans are ignored. | `src/renderer/main/vueapp.js:3821-3866` |
| Word timing | Only via Musixmatch richsync, not Apple TTML. Word text + offset per line. | `src/renderer/main/vueapp.js:3542-3546`, `3577-3593` |
| Word fill ("karaoke") | No continuous fill. Each word snaps on at its timestamp with a 0.35 s opacity ease. | `src/renderer/views/components/lyrics-view.ejs:174-179`, `src/renderer/style.less:1761-1767` |
| Past vs future lines | Not distinguished. Only "active" vs "not active". | `src/renderer/style.less:1788-1800` |
| Auto-scroll | Always follows the active line; there is no working user-scroll pause/auto-return. | `src/renderer/views/components/lyrics-view.ejs:122-159, 204-213` |
| Translation | Musixmatch translation list matched by exact line text; shown under the line. | `src/renderer/main/vueapp.js:3626-3655` |
| Background vocals | Not parsed/rendered. No `ttm:role="x-bg"` handling. | (absent) |
| Player background | Blurred, rotating, duplicated artwork. Not an animated gradient and no local palette extraction. | `src/renderer/style.less:272-323` |
| Accent color | User setting, default `#fc3c44`. Apple-provided artist `bgColor`/`textColor1` used only on artist/profile headers. | `src/renderer/main/vueapp.js:365-377`; `src/renderer/views/pages/artist.ejs:268-281` |
| Quality badges | Local-file Lossless and a spatial-DSP badge; no catalog-derived Dolby Atmos badge. | `src/renderer/views/app/chrome-bottom.ejs:56-73` |

The most important takeaway: Cider 1 is a good reference for **line-level lyric presentation, scroll offset, dot interludes, background blur, and button motion**. It is a poor reference for **word fill, translation timing, user-scroll return, background vocals, palette extraction, and Atmos detection**.

---

## 1. Lyrics view

### 1.1 Layout, size, spacing, alignment

**Cider behavior**
- Drawer (default lyrics panel) is 300 px wide (`src/renderer/style.less:435-465`). Lyrics body is a vertical flex column with a bottom fade mask (`src/renderer/style.less:1712-1734`).
- Line type in the drawer: **26 px**, left aligned, `transform-origin: left center`, 10 px margin on all sides plus `margin-left: 5%` (`src/renderer/style.less:1744-1758`).
- Fullscreen right column: lyrics container is **80% width, 62 vh high**, and line size is **35 px** (`src/renderer/less/fullscreen.less:254-278`).
- Modular/fullscreen lyrics mode: centered, `font-weight: 600`, `font-size: 2em`, only the active line is displayed, lines animate in with `fsLyricIn` (`src/renderer/style.less:2228-2305`).
- Line height is not set explicitly; spacing comes from the 10 px margins and the h3 default. The visual rhythm is "large text, tight block, generous side gutter".
- Translation line is **1.6 rem**, weight **450**, with `filter: contrast(0.5)` to dim it (`src/renderer/style.less:1836-1856`). It sits directly under the original line and inherits the line's layout.
- Clicking a line seeks to `lyric.startTime`; unsynced lines use `9999999` and are ignored (`src/renderer/views/components/lyrics-view.ejs:5, 119-121`).

**Compose translation**
- Drawer: `fontSize = 26.sp`, left aligned, `padding(start = 20.dp, top = 10.dp, bottom = 10.dp)`, no extra line height beyond `lineHeight = 32-34.sp` if we want a slightly airier block.
- Fullscreen: `35.sp`; modular/"focus" lyrics: `2em` equivalent (~32.sp), weight 600, active line only.
- Translation: `16.sp`, weight 450, `alpha = 0.65`, `maxLines = 2`, `TextOverflow.Ellipsis`, 2-4 dp top gap.
- Keep the bottom fade with `Modifier.drawWithContent` + vertical gradient (transparent in the last 10-12% of the list height). Do not use a full-screen overlay that forces recomposition.
- Tap target: the whole line row is clickable; call the player's seek once, do not pause first.

### 1.2 Current / past / future styling

**Cider behavior**
- Inactive line: `opacity 0.75`, `scale 0.8`, `filter: blur(1px)` (`src/renderer/style.less:1744-1759, 1798-1800`).
- Active line: `opacity 1`, `scale 1`, no blur (`src/renderer/style.less:1788-1796`).
- Transitions: **transform 0.2 s** and **opacity 0.35 s** with the same Apple ease `cubic-bezier(0.42, 0, 0.58, 1)` (`src/renderer/style.less:19, 1793-1795`).
- Inactive-to-active scale can take 0.5 s because the base `.lyric-line` transition is 0.5 s (`style.less:1751`); active is faster at 0.2 s.
- Past and future lines look identical in Cider. If we want Apple-like history, that is our addition.
- Hovering a line removes its blur and adds a 10% white rounded rect (`rgba(200,200,200,0.1)`, `scale 1.06`, radius 10 px) (`style.less:1770-1786, 1810-1812`).

**Compose translation**
- Data-driven two-state model: `activeIndex` only. Inactive: `alpha 0.75f`, `graphicsLayer { scaleX = scaleY = 0.8f }`; active: `alpha 1f`, scale 1.
- Use `animateFloatAsState` with `tween(200, easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f))` for scale and `tween(350, ...)` for alpha. Do not animate every line; only the line whose state changes.
- `Modifier.blur(1.dp)` is API 31+ and costs a render pass. On phones, prefer alpha + scale only, or apply blur only to the active line's two neighbours. Cider blurs the whole non-active list, which is expensive at 60 Hz.
- Optional enhancement: past lines `alpha 0.45`, future `alpha 0.7`; still animate only index changes. Keep a single `LyricLineUiState(active, past)` so the list does not recompose on every position tick.
- Press feedback on the lyric line: `interactionSource` + 1.0→0.98 press scale, 10 dp rounded background at 10% white. No ripple across the whole line.

### 1.3 Word-by-word / karaoke and per-word timing

**Cider behavior**
- Apple TTML path reads only `<p>` begin/end and `textContent`; child word spans and their timings are not used (`vueapp.js:3821-3866`). So Apple's syllable timing is discarded.
- The word-level path is Musixmatch richsync: each line has `ts/te/x` and a `l[]` array of `{c, o}` where `c` is word text and `o` is the word's offset from line start (`vueapp.js:3577-3593`).
- Rendering emits one `<span class="verse">` per word with `lyricstart = line.startTime` and `versestart = word.o` (`lyrics-view.ejs:7-14`).
- On each position update, every word gets `verse-active` if `time + delay >= lyricstart + versestart` (`lyrics-view.ejs:168-181`). This is a **snap on the word boundary**, not a fill.
- Active line words start at `opacity 0.6`; the active word goes to `opacity 1` with a **0.35 s** ease (`style.less:1761-1767`).
- There is no interpolation inside a word and no easing based on word duration. The visual result is a quick highlight per word, not Apple's left-to-right fill.

**Compose translation (doing better, still cheap)**
- Parse per-word `begin/end` from TTML (`<span begin end>` inside `<p>`) or use richsync offsets plus the next word's offset / line end as the word end.
- For each active word compute `fraction = ((position - wordStart) / (wordEnd - wordStart)).coerceIn(0f, 1f)`.
- Render the line twice and clip the highlighted copy:
  - bottom layer: full line at `alpha 0.55`;
  - top layer: full line at `alpha 1`, clipped to `width * fillFraction` (use `Modifier.drawWithContent` + `clipRect`, or a `ShaderBrush` sweep). Avoid two `Text` layouts per frame if possible: measure once, then draw twice with the same `TextLayoutResult`.
- Ease inside the word with smoothstep or `CubicBezierEasing(0.42f, 0f, 0.58f, 1f)`; duration is the word duration, not a fixed 0.35 s. This is the main Apple-like improvement over Cider.
- Battery: drive `position` from the existing player-position stream, not `withFrameNanos`. If a frame-accurate fill is needed, run a frame loop only while `isPlaying && lifecycle >= RESUMED && screenOn`; stop it on pause, screen off, or when the active line is not visible. Do not tick at 60 Hz for a 100 ms text change.
- For many lines, only the active line needs the fill; all other lines render the static low-alpha text. Keep the per-line data immutable.

### 1.4 Scrolling: keeping the current line in place

**Cider behavior**
- On active-line change, it measures the line's offset inside the scroll parent and calls `parent.scrollTo({ top: elementTop - yoffset, behavior: "smooth" })` (`lyrics-view.ejs:134-158`).
- `yoffset` is **128 px** by default in the drawer, **120 px** in fullscreen (`lyrics-view.ejs:154`; `fullscreen.ejs:150`). This keeps the current line about 120 px below the top, not centered.
- Scroll is browser-native smooth, not a custom spring.
- Cider does **not** implement "user scrolls, auto-scroll pauses, returns after N seconds". The intended guard `checkIfScrollIsStatic` is broken: it references an undefined `position` and is created as an interval in `methods`, so it is not a real user-interaction flag (`lyrics-view.ejs:204-213`; a duplicate dead interval exists at `src/renderer/index.js:192-200`). In practice Cider yanks the view back on the next line change even if the user scrolled.
- Active-line detection adds **+0.1 s** normally, **-2.5 s** when AirPlay 2 is active (to compensate for cast latency) and only updates at line boundaries (`lyrics-view.ejs:122-126`).

**Compose translation**
- Use `LazyColumn` + `LazyListState`. On `activeIndex` change, `animateScrollToItem(activeIndex, scrollOffset = -(120.dp in px))` in a coroutine; cancel the previous job so seeks do not queue.
- Add a user-scroll guard: observe `interactionSource` drags/scrolls; on the first user scroll set `autoScrollPaused = true`; relaunch a 4 s `delay` after the last interaction, then animate back to the current line. Cider lacks this; Apple does it. This is a deliberate improvement, not a copied behavior.
- Use `tween(400, easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f))`, or a critically damped `spring(dampingRatio = 1f, stiffness = 400f)`; avoid a bouncy spring for long jumps.
- Keep the 120-128 dp top offset; do not center the active line. Add a bottom fade mask so the next lines do not end abruptly.
- Update only when the active index changes. Do not scroll on every position tick.

### 1.5 Instrumental gaps: the breathing dots

**Cider behavior**
- TTML parser inserts a pseudo-line `lrcInstrumental` when the gap between the previous line's end and the next line's start is **greater than 5 s**, and always inserts one from time 0 to the first line (`vueapp.js:3831-3856`).
- LRC/richsync sources also prepend a 0→first-start instrumental line (`vueapp.js:3569-3575, 3586-3592`).
- The pseudo-line renders as three `<div>` dots instead of text (`lyrics-view.ejs:25-35`). The same pseudo-line is skipped from normal text rendering (`lyrics-view.ejs:4`).
- Dots: **10 px diameter**, **3 px margin**, white, 50% radius (`style.less:1818-1834`).
- Idle animation: `lyricWaitingLine` **6 s infinite** with `cubic-bezier(0.42, 0, 0.58, 1)`, each dot `opacity 0.25 → 1 → 0.25`, `scale 0.85 → 1 → 0.85` (`style.less:1823-1826, 1888-1910`).
- Additionally, while an instrumental line is active, Cider times each dot to a quarter of the line: each dot gets `dotOpacity` over `duration / 4` seconds, and the previous dots are set opaque (`lyrics-view.ejs:56-109`). This produces a three-step "breathing" progression through long gaps. It is driven by position updates, not a CSS clock, but in practice it is a slow three-dot pulse.
- If the lyrics text contains both "纯音乐" and "欣赏" (QQ's instrumental marker), the whole view falls back to the no-lyrics state (`lyrics-view.ejs:196-202`).

**Compose translation**
- Insert a synthetic `LyricLine.Instrumental(start, end)` when `next.start - previous.end > 5_000` ms, plus the initial 0→first line.
- Render three 10 dp circles with 3 dp spacing, center/top aligned where a line would be.
- Use `rememberInfiniteTransition` with a 6 s cycle and phase offsets of 0, 2 s, 4 s (or the 1/4-of-line timing if the instrumental is shorter). Animate only alpha and scale (0.85→1), not blur.
- Stop the infinite transition when the current line is not instrumental, when playback is paused, or when the screen is off. In Compose, an infinite transition still runs if it remains in composition; gate it with the lifecycle and `isPlaying`.
- Do not use a repeating timer in a ViewModel; derive visibility from state.

### 1.6 Translation lines

**Cider behavior**
- Translation is fetched separately from Musixmatch (`crowd.track.translations.get`) and matched to lyric lines by exact text equality, allowing one leading space (`vueapp.js:3626-3655`).
- It is stored per lyric line as `translation` and rendered under the original (`lyrics-view.ejs:20-22`).
- There is no separate timing: the translation appears with its line.
- Style: 1.6 rem, weight 450, contrast 0.5 (dimmer) (`style.less:1836-1856`).
- Apple TTML itself often carries translation spans; Cider's TTML path does not parse them, so translation depends on the Musixmatch fallback.

**Compose translation**
- Model translation as an optional field on the line, not a parallel list. One stable `key` per line keeps `LazyColumn` cheap.
- Use `16.sp`, `alpha = 0.65f`, `maxLines = 2`, `overflow = Ellipsis`; indent it with the same start padding as the original.
- If the source is TTML, parse `<span ttm:role="translation">` or the documented translation attribute once at load time; keep the same per-line model.
- Do not animate translation separately; let it fade with the line (`alpha` follows the line state).

### 1.7 Background vocals

**Cider behavior**
- Not implemented in Cider 1. No `x-bg`, no `ttm:role`, no secondary-vocal span handling. Apple TTML background-vocal spans are flattened into the main line text if they are present in `textContent`.
- That is a real limitation, not a design choice to copy.

**Compose translation**
- Parse `ttm:role="x-bg"` (and the older `x-bg` convention) into a `background` field on the line.
- Render the main line normally; render background vocals below it at ~0.8× size (e.g. 21.sp when main is 26.sp), `alpha 0.6`, indented ~24 dp, italic optional. Do not make the background line a separate seek/active line; it shares the main line's timing.
- Exclude background text from translation matching and from the line's `TextLayoutResult` fill layer.
- This is a visible Apple Music cue and is cheap: it is static text, no per-frame work.

---

## 2. Now playing screen

### 2.1 Background: blurred artwork, not gradient or palette

**Cider behavior**
- The background is the current artwork, heavily blurred and enlarged, with a slow rotation:
  - Image element is **200% width** of the container.
  - Filter: `brightness(200%) blur(180px) saturate(280%) contrast(2)` for the main window (`style.less:272-283`).
  - Two copies: layer A rotates **35 s linear infinite**; layer B rotates in reverse with a **10 s delay** (`style.less:281, 311-316`). Opacity crossfade is **0.25 s** with the Apple ease (`style.less:278`).
  - Fullscreen variant: `brightness(85%) saturate(95%) blur(180px) contrast(0.9) opacity(0.9)` (`fullscreen.less:239-250`; same in `miniplayer.less:140-151`).
  - Drawer variant: `brightness(80%) blur(180px) saturate(180%) contrast(1)` (`style.less:451-459`).
- The source image for the fullscreen background is requested at only **30×30** and stretched; the blur hides the pixelation (`fullscreen.ejs:6-11`). Artwork for the foreground is requested at **600×600** (`fullscreen.ejs:46-50`).
- The rotation is disabled when the window loses focus: `animateBackground = false`, `body.stopanimation`, and animated artwork videos pause (`vueapp.js:4730-4745`). It is also disabled by the no-GPU class and reduce-visuals themes (`style.less:2732-2742`; `themes/reduce_visuals.less:7-12`).
- Cider loads the background by polling every **200 ms** until the new track's artwork is available (`vueapp.js:4400-4441`). That is not a pattern to copy on Android.
- There is **no local color extraction or animated gradient** in the player. `--keyColor` is a user-set accent (`vueapp.js:365-377`); artist headers use Apple's `artwork.bgColor`/`textColor1` (`views/pages/artist.ejs:268-281`).

**Compose translation**
- Simplest Apple-like result: cache a **32-64 px** downscaled bitmap of the artwork, draw it as a full-bleed `Image` with `contentScale = Crop`, `graphicsLayer { scaleX = scaleY = 1.15f }`, and a cached `RenderEffect` blur (API 31+) or a pre-blurred bitmap. Do not re-blur every frame.
- Crossfade only on track change: two layers, old and new, `animateFloatAsState` alpha over **250 ms**. Keep the old bitmap until the new one is loaded, so there is no black flash.
- Optional rotation: one `rememberInfiniteTransition` rotating the layer 360° over **35 s**, with a reduce-motion check. Gate it on `Lifecycle.State.RESUMED`, `isPlaying` or screen-on, and `LocalView.isShown`; stop it when the app is backgrounded. Better on phones: skip rotation and use a very slow scale/translation drift, or no motion at all.
- Palette: if we want Apple's color wash, run AndroidX `Palette` on a 64 px bitmap **off the main thread**, cache by track id, and use the dominant/vibrant swatch for controls/seek. Cider 1 does not do this; Apple does. Fall back to the blurred art if extraction fails.
- Keep a dark scrim (e.g. 30-40% black vertical gradient) behind text so contrast is stable over bright artwork.
- Battery: no polling. Subscribe to the player's `currentItem`/metadata flow; load once per track. Blur/rotate only when the composable is visible and the app is resumed.

### 2.2 Artwork size and transitions

**Cider behavior**
- Fullscreen artwork is a **50 vh × 50 vh** square; controls are constrained to the same **50 vh** width (`fullscreen.less:344-357`). The foreground request is 600 px (`fullscreen.ejs:47-50`).
- Artwork fades in from `opacity 0` with `transition: opacity .25s linear` once the image loads (`mediaitem-artwork.ejs:68-71, 95-100`).
- Mini player uses a 300×300-ish window; artwork fills the window, and controls live in a bottom overlay with `background: #0000009e`, `backdrop-filter: blur(10px)`, opacity 0 → 1 on hover (`miniplayer.less:249-277`; `miniplayer.ejs` top).
- Mini ↔ full in Cider is a separate Electron window resize (`vueapp.js:5032-5055`), not a shared-element animation. The visible transition is `fsModeSwitch`: **1 s**, `scale 1.1 → 1`, `opacity 0 → 1`, Apple ease (`style.less:2408-2419`). The lyrics drawer slides in with `transform: translateX(400px)` over **0.25 s** (`style.less:2431-2438`); sidebar width animates over **0.35 s** (`style.less:2421-2429`).
- Fullscreen and mini are separate views with the same artwork/controls, so there is continuity of content but no true geometric morph.

**Compose translation**
- Use a shared-element/container transform from the mini-player artwork bounds to the full-screen artwork bounds over **300-400 ms**; Material motion's `SharedTransitionLayout` / `sharedBounds` is the closest equivalent. Cider's 1 s scale/fade is too slow for a phone.
- On entry, combine `scale 1.08 → 1` with `alpha 0.9 → 1` over ~250-300 ms. Do not scale the whole player UI; only the artwork and the background.
- Crossfade the artwork bitmap over **250 ms** after the new image is decoded; keep the previous image underneath.
- The full-screen player should be reachable from the mini player with a single tap; the back gesture/down-swipe collapses to mini. Keep the artwork bounds stable during the transition to avoid a jump.
- Mini overlay controls: use a bottom scrim with blur only when visible; on Android, `Modifier.blur` on a large surface is costly. Prefer a dark gradient; enable blur only on capable devices / when the mini player is expanded.

### 2.3 Progress bar behavior

**Cider behavior**
- Position comes from the player event (`playbackTimeDidChange`), which updates `playerLCD.playbackDuration`; there is no timer in Cider for progress (`vueapp.js:1090-1100`).
- While dragging, `userInteraction = true` and `desiredDuration` is shown; the UI displays the drag value instead of playback position (`vueapp.js:2020-2033, 2090-2098`).
- Fullscreen range: `step 0.01`, 4 px track with 10% white, thumb 12 px accent circle, hidden (`opacity 0`, `scale 0.5`) until hover, then `opacity 1`, `scale 1` over **0.1 s** (`fullscreen.less:433-463`). Time labels are 10 px and only shown while the progress area is hovered (`fullscreen.ejs:60-79`; `fullscreen.less:416-430`).
- Bottom chrome: track 5 px, thumb 16 px accent, hidden until hover; time labels are always visible; after seek, `userInteraction` is cleared after **1 s** (`chrome-bottom.ejs:137-153`; `directives.less:120-154`).
- Fill is a CSS linear gradient from the accent color to a dark track, computed as a percentage (`vueapp.js:2020-2033`).

**Compose translation**
- Use a custom `Slider` track: 4 dp height, rounded, inactive `Color.White.copy(alpha = 0.1f)`, active accent; thumb 12 dp, `alpha 0f` unless dragging/hovered, `scale 0.5 → 1` over 100 ms.
- Show elapsed/remaining labels only while interacting or on the expanded player; fade them in over ~100 ms, don't reserve a row that jumps.
- Keep `dragFraction` local; update the displayed fraction while dragging, call `seekTo` once on release. Do not seek on every pixel.
- The progress fraction should come from the player's position updates. If the player emits ~4 Hz, the bar updates at ~4 Hz; that is fine for a 4 px line and saves battery. Do not run a frame loop just for the progress bar.
- `Slider` semantics: set `stateDescription` / content description for accessibility; 48 dp touch target even if the visual thumb is 12 dp.

### 2.4 Controls layout

**Cider behavior**
- Fullscreen controls center a row: shuffle, previous, play/pause (or stop for radio), next, repeat; row has `margin-top: 2vh` (`fullscreen.less:399-420`).
- Small buttons: **40×32 px**, radius 6 px, opacity 0.7 (`elements.less:1804-1844`). Main play/pause/prev/next: **40×36 px**, 38 px icon, opacity 0.7 (`elements.less:1761-1802, 1912-1941`). Volume is a separate right-side group on wide layouts, below the progress on narrow ones.
- Hover feedback is a rounded full-size background that scales from **0.5 → 1** and fades **0 → 1** over **0.1 s** Apple ease (`elements.less:1777-1801, 1819-1843`). Press sets `--selected-click` (`rgba(80,80,80,0.3)`), and some controls scale to **0.9/0.95/0.96** (`style.less:843-860, 1473, 1557, 2103`; `fullscreen.less:211`).
- Disabled controls use `opacity 0.25` and no pointer events (`elements.less:1942-1952`).

**Compose translation**
- 40×32 dp icon buttons with 6 dp radius; main play 40×36 dp with a 38 dp glyph. Use 48 dp minimum touch targets via padding, even if the visual is smaller.
- Hover/press: animate a rounded background's alpha and scale from 0.5→1 over 100 ms on press/hover; on touch, a short press ripple plus a 0.96 scale is enough.
- Play/pause icon: `AnimatedContent` or `Crossfade` over 150-200 ms; do not rotate or bounce the button.
- Put shuffle/repeat on the sides of the transport row and keep their active state as a subtle filled pill (Cider uses 25% white for active small buttons, `elements.less:1846-1848`).
- Respect reduced motion: skip scale, keep alpha.

### 2.5 Quality badges (Lossless, Dolby Atmos)

**Cider behavior**
- Badges sit in a right-aligned `chrome-icon-container` next to the track metadata (`chrome-bottom.ejs:56-73`; same in `chrome-top.ejs:127-142`).
- Every badge is **15×15 px**, `background-size: contain`, `margin-left: 0.5em`, and `filter: contrast(0)` so the icon follows the text color (`style.less:1335-1379`).
- Lossless appears only when `localFilesMetadata.lossless` is true, with a tooltip like `bitDepth-bit / sampleRate/1000 kHz container` (`chrome-bottom.ejs:62-66`).
- The "spatial" badge reflects a DSP toggle (`cfg.audio.maikiwiAudio.spatial`), not the track's actual Atmos flag (`chrome-bottom.ejs:58-61`).
- There is no Apple catalog "Dolby Atmos" badge in Cider 1. Do not claim Cider has one.

**Compose translation**
- Render badges as 15 dp-high icons with 4-6 dp gap, tinted to the surrounding text (`LocalContentColor`), optionally `alpha 0.85`. Put them after artist/album in a single `Row` with `Arrangement.spacedBy(4.dp)`.
- Source the truth from the API/metadata, not a DSP toggle: Apple Music catalog exposes audio traits/variants such as lossless, hi-res lossless, Dolby Atmos, and Dolby Audio. For local files, derive from bit depth/sample rate/codec.
- Tooltips/semantics: `"24-bit/48 kHz ALAC"`, `"Lossless"`, `"Dolby Atmos"`; use `contentDescription` so TalkBack conveys the badge. Long-press/overflow can show the full technical string.
- Do not show both a generic "Lossless" and a "Hi-Res Lossless" badge for the same track unless the source really has two distinct states. Keep the badge row stable to avoid layout jumps when metadata arrives.

---

## 3. Small Apple-like touches worth copying

### 3.1 Hover / press feedback

**Cider behavior**
- Buttons use a **100 ms** background reveal (scale 0.5→1, alpha 0→1), not a color snap (`elements.less:1777-1801`).
- Press uses `--selected-click: rgba(80,80,80,0.3)` and a small scale-down (0.9 for volume, 0.95 for the overflow button, 0.96 for some menu items) (`style.less:34-36, 1557, 2103`; `fullscreen.less:211`).
- Selection highlight is `--selected: rgba(130,130,130,0.3)`; hover is `rgba(200,200,200,0.1)` (`style.less:34-36`).
- Lyrics lines get a 10% white rounded hover plate at `scale 1.06`; queue rows switch to the selected background; the active small button gets a 25% white fill.

**Compose translation**
- Define one `ApplePress` modifier: `interactionSource` + `animateFloatAsState` for scale (1→0.96 on press, 100 ms) and a rounded background alpha (0→0.1 on hover, 0→0.3 selected). Reuse it everywhere.
- On touch devices there is no hover; use press only, plus a subtle 150 ms ripple. Do not add hover-only affordances that hide critical controls on phones.
- Keep selection states consistent across queue, lyrics, and tabs.

### 3.2 Mini ↔ full player

**Cider behavior**
- Separate views with a **1 s** scale 1.1→1 + fade transition; mini is a resized Electron window with controls appearing on hover (`style.less:2408-2419`; `vueapp.js:5032-5055`; `miniplayer.less:264-277`).
- Drawer: 0.25 s slide `translateX(400px)`; sidebar: 0.35 s margin animation (`style.less:2421-2438`).

**Compose translation**
- Use a shared container transform over **300-400 ms**; animate the artwork bounds and the background together. The mini player's artwork becomes the full player's artwork without a cut.
- Collapse with the system back gesture and a downward drag on the artwork. Keep the media controls pinned to the bottom in both states.
- Avoid the 1 s duration; it feels sluggish on a phone. Use standard Material motion easing, not a custom bounce.

### 3.3 Queue view

**Cider behavior**
- Queue panel is a flex column with header, scroll body, and sticky footer (`helpers.less:409-436`).
- Rows: 42 px artwork (image request 32), **9 px top/bottom padding**, title **14 px**, subtitle **0.7 em / opacity 0.7**, duration **14 px / min-width 60 px** (`elements.less:325-396`; `queue.ejs:25-54`).
- Hover/selected row uses `--selected`; active uses `--selected-click`; explicit badge is 36×12 (`elements.less:352-363, 404-410`).
- Interactions: single-click selects (with Ctrl/Shift multi-select), double-click plays that index, drag reorders, right-click opens a context menu (`queue.ejs:22-29, 106-115, 117-...`).
- The list renders at most **50 items** from the current queue position (`queue.ejs:91-97`), which keeps the DOM small for very long queues.
- Footer has Queue/History tabs and Clear All when more than one item; autoplay infinity button is 32×32 with 15% white background (`queue.ejs:59-71`; `helpers.less:424-436`).
- Fullscreen queue column is **60 vh wide × 62 vh high**, lyrics column **80% × 62 vh** (`fullscreen.less:254-300`).

**Compose translation**
- `LazyColumn` with stable `key = queueItem.id`; `Modifier.animateItemPlacement()` for reorder; 42 dp artwork, 9 dp vertical padding, title 14 sp, subtitle 11-12 sp at 0.7 alpha, duration 14 sp with `minWidth 60.dp`.
- Long-press starts drag (or use a drag handle); single tap selects, double tap plays. Do not require a right-click.
- Window the list if queues can be thousands long: render a bounded slice around the current position, or rely on `LazyColumn` (it already windows, so the 50-item cap is not needed).
- Keep the footer sticky outside the `LazyColumn`; animate the Queue/History tab indicator over 150 ms.
- Artwork requests: decode at the display size (42-56 dp) plus density; do not request 500 px thumbnails for queue rows.

---

## 4. Battery rules for the Compose implementation

1. **No polling.** Cider's 200 ms background-artwork poll (`vueapp.js:4400-4441`) and 50 ms scroll interval (`index.js:192-200`) are anti-patterns. Subscribe to player metadata/position flows.
2. **No per-frame work when paused or off-screen.** Gate every infinite transition (`rememberInfiniteTransition`) and frame loop on `Lifecycle.State.RESUMED`, `isPlaying`, and the composable being visible. Compose keeps an infinite transition alive while it remains composed.
3. **Position → UI at the player's cadence.** For lyrics and seek, update only when the active line/word changes. For word fill, animate only the active word; do not rebuild the whole list.
4. **Cache expensive renders.** Blur once per artwork (or use a small downscaled bitmap) and cache it; never apply a 180 px blur to a full-resolution bitmap every frame.
5. **Animate small properties.** Use `graphicsLayer` alpha/scale/translation, not layout changes. Avoid `Modifier.blur` on large lists on low-end devices; offer a reduce-visuals fallback, like Cider's no-GPU/reduce-visuals themes (`style.less:2732-2742`).
6. **Stop motion on pause.** Lyric auto-scroll, dots, background rotation, and word fill should all settle to a static state when the user pauses. Cider disables background rotation when the window blurs (`vueapp.js:4730-4745`); do the equivalent with lifecycle.
7. **One accent color.** Cider's restraint (single `--keyColor`, default `#fc3c44`) is a large part of the Apple feel. If we add palette extraction, use one extracted color at low saturation, not a rainbow gradient.

---

## 5. Summary: the 8 most valuable ideas for us, ranked

1. **Line-level layout with a 120-128 dp top offset and 26/35 sp type** — the quickest way to feel like Apple Music; do not center the active line.
2. **Active line = scale 1 / alpha 1, inactive = scale 0.8 / alpha 0.75 / 1 px blur**, with 200 ms scale and 350 ms alpha using `cubic-bezier(0.42,0,0.58,1)`. Add past/future distinction ourselves; Cider lacks it.
3. **Continuous per-word fill** driven by word start/end and the player position, clipped over a low-alpha base — Cider only snaps words; this is the single biggest lyric upgrade.
4. **Instrumental gaps become three breathing dots** after a >5 s gap, 10 px dots, 6 s pulse — cheap, distinctive, and easy to stop when paused.
5. **Blurred artwork atmosphere, not a gradient**: 32-64 px source, 1.15× crop, one cached blur, 250 ms crossfade on track change; skip rotation on phones or gate it to the resumed screen.
6. **One accent color and a 4 px seek bar with a hidden 12 dp thumb** — Cider's 0.1 s thumb reveal and 10% track are exactly the Apple restraint we want.
7. **Mini → full shared artwork bounds over 300-400 ms**, not Cider's 1 s scale/fade; collapse with back/down-swipe.
8. **Queue rows at 42 dp artwork / 14 sp title / 9 dp vertical padding, with stable keys and drag reorder**; keep the footer sticky and skip the 50-item cap because `LazyColumn` already windows.

Skipped / not copied: Cider's palette extraction (absent), auto-return after user scroll (absent/broken), background vocals (absent), Atmos detection (absent), and its 200 ms polling (deliberately avoided). Risk: Cider 1 is AGPL-3.0, so only behavior and measurements above are notes; no source code should be ported.
