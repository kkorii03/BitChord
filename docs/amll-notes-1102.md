# AMLL lyric-player notes (2026-11-02)

Read-only research. Source: `https://github.com/amll-dev/applemusic-like-lyrics`,
commit `6a6d90a81b96b0322ab056c49dced00d603895cd` (2026-11-01), cloned to
`/tmp/amll`. I read `packages/core` first, then the React full page. No code was
copied; everything below is behaviour, numbers and a design sketch in my own
words.

Files that carry the behaviour:

- `packages/core/src/lyric-player/base/index.ts` — frame loop, layout application,
  stagger, opacity/blur/scale policy, spring parameter updates, visibility.
- `packages/core/src/lyric-player/base/layout.ts` — viewport math, prefix-sum
  line heights, overscan, interlude and bottom-line anchors.
- `packages/core/src/lyric-player/base/scroll.ts` — touch/wheel/inertia, user
  scroll suspension, resume delay.
- `packages/core/src/lyric-player/base/timeline.ts` — playing/highlighted rows,
  seek handling, interlude gap detection.
- `packages/core/src/lyric-player/base/spring.ts`,
  `packages/core/src/utils/spring.ts` — spring policy and solver.
- `packages/core/src/lyric-player/base/interlude-dots.ts` — three-dot show.
- `packages/core/src/lyric-player/base/line.ts`,
  `packages/core/src/lyric-player/base/group.ts` — emphasis threshold and group
  transforms.
- `packages/core/src/lyric-player/dom/lyric-line.ts`,
  `dom/animation/mask/*`, `dom/animation/emphasize/index.ts`,
  `dom/animation/float/index.ts`, `dom/line-brightness.ts`,
  `styles/lyric-player.module.css` — word fill, long-word treatment, brightness
  layers, translation/roman/duet styling.
- `packages/core/src/bg-render/palette/*`,
  `packages/core/src/bg-render/mesh-renderer/*` — artwork colours and fluid mesh.
- `packages/react-full/src/components/PrebuiltLyricPlayer/index.tsx`,
  `packages/react-full/src/states/configAtoms.ts`,
  `packages/react/src/bg-render.tsx`, `packages/react/src/lyric-player.tsx` —
  full now-playing wiring, FPS, static mode, frame driving.

Our equivalents examined: `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerLyrics.kt`,
`LyricClock.kt`, `MeshGradient.kt`, `ArtworkMeshBackdrop.kt`,
`shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricLine.kt`,
`LyricGaps.kt`, `HeardWords.kt`, and `ko/ui/player/*`.

---

## 1. Line layout and motion

### AMLL

Default viewport: the focal line is anchored at 35% of the player height,
vertically centred on its own box (`alignAnchor = center`,
`alignPosition = 0.35`, overscan 300 px). The React full page overrides this:
25% and centre in the wide layout, 10% and top in the tall phone layout
(`PrebuiltLyricPlayer/index.tsx`). A line's height is measured by
`ResizeObserver`; until measured it falls back to `containerHeight / 5`.
Positions are prefix sums, not per-row layout queries.

Spring policy (`base/spring.ts`):

- Slow (seek, interlude, first/last line, no interval): stiffness 90, damping 15.
- Medium (end of song): stiffness 140, damping 22.
- Normal playback: interval between the current and previous line is clamped to
  100..800 ms; a ratio is `(1 - (interval - 100) / 700) ** 0.2` so it stays
  biased fast; stiffness is `170 + ratio * 50` (170..220) and damping is
  `sqrt(stiffness) * 2.2`. The solver uses mass 1 by default; `posY` starts from
  mass 0.9, and the per-line scale spring uses mass 2, stiffness 100, damping 25;
  background-line scale uses mass 1, stiffness 50, damping 20.
- A continuous touch drag bypasses the spring and snaps positions
  (`LayoutReason.ContinuousScroll.snapPosY = true`); the wheel keeps the spring
  so discrete notches still glide. Seek and rebuild reset the interlude and
  disable staggered entry.

Per-line cascade (`base/index.ts`): the first row on screen starts immediately;
each following row starts 50 ms after the previous one, and after the focal row
the increment is divided by 1.05 each time (a geometric decay). Only rows whose
bottom is not above the viewport top contribute to the delay counter. This is a
time delay per line, not an index lookup.

Scale, opacity, blur:

- Scale: active line 100%, inactive 97%. Background line: active 100%,
  inactive 75%. So the current line does not grow; the others step back.
- Opacity: highlighted/current 0.85; non-dynamic (one word per line) 0.2;
  word-synced non-active lines 1.0 (the gradient mask does the actual
  dimming). The code explicitly keeps opacity 1 for word-synced rows because the
  mask, not the layer, carries the highlight.
- Blur: active line and touch-scroll rows get 0. Otherwise level is
  `1 + distance`, where distance is measured from the focal row above and from
  the latest highlighted row below; narrow viewports (<= 1024 px) multiply by
  0.8. The DOM clamps the rendered filter to 5 px. CSS transitions opacity and
  filter over 400 ms.
- Brightness for the masked word fill is a separate two-copy trick; see §2.

User scrolling (`base/scroll.ts`):

- A 10 px movement confirms intent to scroll and suspends auto-align; wheel input
  counts immediately. Continuous touch uses direct offsets, the wheel uses
  150 ms idle debounce. Fling inertia uses a 16.67 ms reference frame, decays
  velocity by 0.95 per reference frame, ignores frames longer than 100 ms, and
  stops below 0.05 px/ms.
- Auto-align resumes only when `now - lastScrollEnd >= 500 ms`; the resume is
  checked on the next timeline change, not by a timer. It additionally requires
  a changed scroll target (or the end of an interlude), no interlude focus, the
  target row actually playing and in the render range. On resume the scroll
  offset is reset to 0 and the focus follows the song again.
- `MAX_FRAME_DELTA` is 100 ms: after a page was suspended, the first delta is
  clamped so springs do not overshoot across seconds.

### Compose sketch, in words

One `LazyListState` plus a spring-backed target offset. Keep a `currentLine`
state derived from the lyric clock; when it changes, compute the target offset
for that row (top/centre anchor) and animate with a `spring(stiffness,
dampingRatio)` whose values are chosen from the gap to the previous line, using
the same 100..800 ms clamp. For taps/seeks first `scrollToItem`, then run the
spring. During a drag, observe `isScrollInProgress`/drag interactions and set
`autoFollow = false`; do not launch a repeating timer. When the drag ends,
start one suspended coroutine that waits 500 ms; if the finger has not returned
and the focus row is still on screen, set `autoFollow = true` and animate back.
The per-row offset/stagger is a single `withFrameNanos`-driven float, not one
`Animatable` per row: each row's start delay is `min(index-focus, N)` times the
50 ms cascade. Only rows in the visible window + a small overscan are composed.
When the screen is off or playback is paused, stop the frame loop; the state
already holds the final offset.

### What our app already has

`PlayerLyrics.kt` has a `LazyColumn`, a focus row, a 350..500 ms `scrollLead`,
a shared `ScrollRun` clock, three stagger steps at 6% of the scroll duration,
per-row alpha/blur/scale falloff, and user-drag suspension with event-driven
resume after 600 ms (or 5 s when the active row is off screen). It does not use
a physical spring: the handover is a fixed `tween` with a cubic-bezier. It also
uses index-distance ladders rather than AMLL's `1 + distance` blur and 0.85
active opacity.

Missing / worth borrowing: interval-adaptive spring, 50 ms per-line cascade with
`/1.05` decay, distance-based blur (cap 5 px, 0.8 on narrow screens), 0.97
inactive scale, and the 500 ms resume rule. Keep our stronger readability floor
and our 600 ms/5 s browse behaviour; those are deliberate.

---

## 2. Word-by-word fill

### AMLL

The fill is a **moving gradient mask over each word**, not a per-letter colour
assignment. `dom/animation/mask/animator-web.ts` creates a `mask-position` keyframe
timeline for every word; `animator-calc.ts` is a CSS `calc()` fallback for
engines without Web Animations mask support.

- Fade width is `wordHeight * wordFadeWidth`; default `wordFadeWidth` is 0.5
  (half a full-width character). The docs note 1.0 approximates Apple Music on
  Android, 0.5 Apple Music on iPad. The gradient itself keeps fixed stops at
  alpha 1.0 (lit) and 0.4 (unlit), so the mask can be applied once and only its
  position animated.
- Each word gets a timeline across the whole line, including the pauses before
  it and the words after it. The first word and last word get extra travel of
  1.5× and 0.5× the fade width so the edge of the line is fully swept.
- The mask moves across the word's measured width at a speed derived from the
  word's own duration. A word with no duration is clamped to at least 1 ms.
- Ruby segments can split a word's progress per syllable; plain words advance
  their full width in their duration.
- `LineBrightness` composites a bright masked copy with an unmasked, uniformly
  dim copy, cross-fading the two opacities (0.2 → 0, 0 → 1 over 300 ms for a
  cold activation). This lets the browser composite instead of rebuilding a
  gradient per frame.

Emphasis on long words (`dom/animation/emphasize/index.ts`,
`base/line.ts:shouldEmphasize`):

- Qualifies when the word lasts **>= 1000 ms**. For non-CJK it must also be
  **2..7 trimmed characters**; CJK only needs the duration. This is why a held
  CJK syllable and a held English word both get the treatment, while a short
  "a" does not.
- Duration used is `max(1000, word duration)`. A per-character delay spreads
  letters by `duration / 2.5 / anchorCharCount`; the whole move runs over the
  word.
- Scale peak is `1 + progress * 0.1 * amount`; horizontal push is
  `-progress * 0.03 * amount * (chars/2 - charIndex)` em; vertical rise is
  `-progress * 0.025 * amount` em; a white text-shadow reaches
  `min(0.3, blur * 0.3)` em. `amount` is `(duration/2000)` cubed below 1 and
  square-rooted above 1, then ×0.6, capped at 1.2; `blur` is
  `(duration/3000)` treated the same, ×0.5, capped at 0.8.
- The last word of a line multiplies amount by 1.6, blur by 1.5 and duration by
  1.2. Background vocals double the float height.
- Ordinary word float: `max(1000, word duration)`, delay from line start,
  `-0.05 em` (`-0.1 em` for background), ease-out. It is a separate animation
  from the emphasis sequence.

Words without times and special rows:

- `utils/optimize-lyric.ts` resets a zero-timestamp single word to the line's
  own start/end, so a line-synced source still produces one timed word. CJK text
  without word times is split evenly across the word span by
  `utils/lyric-split-words.ts`.
- There is no real interpolation for an entire untimed word list; the player
  treats a line with <= 1 word as non-dynamic and renders it as one block rather
  than pretending to know per-word times. (Our app goes further: estimates,
  heard-word alignment, and syllable runs.)
- Background vocal (`isBG`) is a second line, opacity 0.4, font size
  `max(0.7em, 10px)`. If it starts before the lead it is placed above; after, it
  slides in from below with an 80 px travel, and its word float is 2× the lead's.
  The two line timestamps are synchronised by the optimiser.
- Duet lines carry `isDuet`: text-align right, 15% padding kept clear on the
  other side, and the whole wrapper aligns to the right edge.
- Translation and romanisation are extra sub-lines under the main line:
  `translatedLyric`, `romanLyric`, opacity 0.3, font `max(0.5em, 10px)`,
  line-height 1.5em, opacity transition 200 ms with a 250 ms delay. Per-word
  romanisation can also be drawn inline under each word (`romanWord`, 0.5em,
  0.3em right padding). The React page can hide or swap translation and
  romanisation without changing the timing data.

### Compose sketch, in words

Keep the line's `TextLayoutResult` cached. For each word compute its horizontal
span from the cached layout. Draw a lit copy over a dim copy, clipped to a
rectangle whose right edge is `x(word) + x(word) * progress`. Soften the edge
with a horizontal gradient whose width is `fontHeight * 0.5` (not a fixed dp),
so the feather scales with the type. This is the same picture as AMLL's
`mask-position` without per-frame gradient allocation. For long words, split the
word into grapheme spans at layout time and give each a delayed scale/translate/
shadow driven by one shared frame clock; do not create one `Animatable` per
letter. Emphasis is a draw-time function of `positionMs`, not a composition
animation, so a line outside its `animatesFromMs..animatesUntilMs` window reads
only a constant. Background vocals, duet lanes, translation and romanisation are
additional rows in the same item; their own timing states decide whether they
are drawn, as we already do.

### What our app already has

`PlayerLyrics.kt` already does the important part: a clip-rect sweep with a
30 dp feather (DstIn gradient), per-word/syllable spans, a per-letter
`GrowingWord` treatment for held words (thresholds 1.1 s solo, 1.36 s + 0.14 s
per extra short letter, 1.05 s for four characters, 900 ms + 200 ms/char for
longer; max 7 letters; block scripts excluded because per-letter motion breaks
their glyph shaping), a 2 dp word lift, a blurred bloom, backing vocals with
their own clock, duet lanes and translation/romanisation sub-lines. It also has
heard-word timing and estimated words, which AMLL does not.

Missing / different: the fade width is fixed 30 dp instead of `0.5 × font
height`; the sweep is a clip rather than a mask-position; per-word inline
romanisation is not present; the translation sub-line is much heavier (20 sp,
alpha 0.85) than AMLL's 0.5em/0.3; AMLL's emphasis threshold is duration-only
for CJK, while ours deliberately excludes block scripts. The first three are
worth borrowing; the last two are deliberate design choices.

---

## 3. Interludes: the three-dot indicator

### AMLL

`base/timeline.ts` marks an interlude when the gap between the latest known end
of any line and the next line's start is **>= 7000 ms**; the anchor is the last
line before the gap (`-1` before the first line). A running interlude takes
focus only when no lyric row is highlighted.

`base/interlude-dots.ts`:

- Not shown if the remaining time cannot fit entry + exit. Entry total is
  `2 * 80 ms + 750 ms = 910 ms`; exit total is `750 + 250 = 1000 ms`.
- A 500 ms hold before entering (except a natural intro, where anchor is -1 and
  delay is 0); seeking always forces the 500 ms hold.
- Container fades in over 180 ms. Each dot fades in over 750 ms, staggered
  80 ms, so the third finishes at 910 ms.
- Breathing period is nominally 4000 ms. If the body is shorter than 3000 ms it
  degrades to "fallback-hold": scale stays 1, dots simply enter and stay lit.
  Otherwise the period is divided into whole cycles of the body, and the scale
  runs 0.4 ↔ 1.25 with an eased sine-like curve.
- Dots light in sequence: each occupies roughly one third of the body; the third
  lights only partly and finishes during the exit. Resting opacity is 0.2, lit
  opacity 0.9; the lighting curve is a cubic bezier (0.56, 0.01, 0.45, 1).
- Exit: scale grows to 1.25 over 750 ms, then shrinks to 0.4 over 250 ms; the
  last 250 ms also fades out. A dismiss without an interlude boundary is a
  150 ms fade.
- Dot size is 0.3em with a 0.18em gap, container height clamp(0.5em, 1vh, 3em).
  If the next lyric line is a duet, the dots are right-aligned.

### Compose sketch, in words

Represent an interlude as a real item in the lyric list, present only while the
clock is inside it (or while the row-swell animation is closing). Its height is
driven by one state: open while `clock in [start, end)`, close on exit. Draw
three circles in one `Canvas`. A single frame-driven progress value `t` in
0..1 over the interlude controls left-to-right fill: dot `i` is lit by
`clamp(t * 3 - i)`. For the "breathing" look, scale the whole dot row with a
4 s periodic function of `t` (0.4..1.25, eased) only when the interlude is at
least 3 s; otherwise hold at 1.0. Entry/exit can be one 180/910 ms envelope and
a 250 ms fade. Gate the whole draw on the item being in the visible window and
on foreground so a break in a scrolled-away part of the list costs nothing.
With low-power/reduce-animation, snap to the resting three dots and skip the
breath.

### What our app already has

We already insert `LyricLine(isGap)` rows, draw three dots, light them
left-to-right over the break (each owns one third), rest at alpha 0.25, use
13 dp dots / 5 dp gaps, and swell the row open/closed over 400/350 ms. We open a
gap at **4000 ms** (`LyricGaps.kt`), and we have an entry row-swell rather than
an interlude-scale envelope.

Missing: 7 s threshold, 4 s breathing scale, staged entry (180 ms container /
750 ms dots / 80 ms stagger), the two-stage exit, the short-interlude fallback,
and the right-alignment when the next line is a duet. These are small Canvas
changes and map well to Compose.

---

## 4. Background: artwork colours and the fluid mesh

### AMLL

Palette (`bg-render/palette/*`):

- The image is first shrunk so its longest edge is **64 px**; fully transparent
  pixels are skipped; the rest becomes an RGB histogram. The comment says
  64×64 is enough and keeps K-Means to a few milliseconds, so it runs
  synchronously on the main thread.
- Three selectors: K-Means (max 250 iterations, deterministic mulberry32 seeded
  from the histogram, optional K-Means++), an octree quantiser (depth 8), and
  `auto`, which runs both and picks first by number of distinct colours, then by
  LAB-space spatial diversity. Dark palettes prefer more spread; light palettes
  prefer less; the `"dominant"` intent (used for a full-screen background)
  always prefers spread and skips the light/dark filtering, so a cover's true
  mid-tone colour survives.
- The mesh consumes a **32×32** resized copy, not the palette itself. Before
  upload it applies contrast 0.4 around 128, saturation 3.0, contrast 1.7,
  brightness 0.75, then a box blur with radius 2 repeated 4 times.

Mesh (`bg-render/mesh-renderer/index.ts`, shaders):

- A bicubic Hermite patch mesh. Control grids are 4×4 or 5×5 presets; 20% of
  loads use a random 6×6 control grid; subdivision is 50. A vertex carries
  position, colour and UV.
- The fragment shader rotates the UV around (0.2, 0.2) by
  `angle = (t / 10000 + volume) * 2`, scales it by `1 - volume * 2`, applies a
  radial vignette (0.6..1.0) and a tiny screen-space dither. `volume` is the
  smoothed low-frequency band; smoothing uses `delta / 100`.
- A new cover creates a new mesh with alpha 0 and cross-fades over roughly
  500 ms (`delta / 500` per frame, capped 1.1). Old meshes are disposed once
  the newest is fully opaque.
- Default renderer FPS cap is 60 (configurable; `BaseRenderer` docs mention 30
  as the older default). Render scale is 0.75 in core and configurable from the
  React page (default 1 there). Static mode stops the RAF after the cross-fade;
  `pause()` cancels the RAF and `resume()` starts it. React-full sets static
  mode when the lyrics page is closed; the `BackgroundRender` wrapper also
  pauses when it is told `playing=false`, although the prebuilt full page does
  not pass a playback flag to its backdrop. `MeshGradientRenderer` itself has no
  `visibilitychange` listener; hidden-tab rAF throttling and the static flag are
  the effective gate. The lyric player itself does check page visibility and
  skips group updates when hidden.

### Compose sketch, in words

We do not need a WebGL port to get the feel. Our existing four-blob mesh already
has the right visual idea. For a more AMLL-like surface, keep a tiny
artwork-derived colour grid (our `ArtworkMeshBackdrop` already does a 36-cell
version), and animate only a phase value on one `withFrameNanos` loop. Each
frame updates a few control points or a rotation/scale uniform and redraws one
image/brush. Drive it at 30 fps max (skip frames whose delta is below 33 ms),
run it only while the player is foreground and the surface is visible, and
freeze the last frame when hidden or paused. Cross-fade a new palette/mesh over
500-1400 ms. During a full-screen player we should prefer a static or
low-frequency mesh; the current app already settles the blobs after the track
change, which is the important battery decision.

### What our app already has

`MeshGradient.kt` extracts four colours with AndroidX Palette from a 128 px
decode (24 swatches, filters off), deduplicates by hue/lightness, tunes HSL
(saturation ×1.35, lightness 0.28..0.58), and draws four radial blobs with
smootherstep falloff (alpha 0.85, radius 0.62 of the max dimension, 64 dp blur,
1.3× overdraw). It drifts 0.45π over 8 s on a track change and then rests;
continuous orbit is opt-in and off for full screens. `ArtworkMeshBackdrop.kt`
reads the cover into a 36-cell mesh, rotates/flips it below the seam, caches a
pre-blurred bitmap and crossfades without re-blurring on every frame. We already
read `rememberIsForeground()` in `MeshGradientBackground` but do not use it to
gate the drift loop.

Missing: a true control-point mesh shader, FPS/render-scale settings, and an
explicit foreground/visibility gate around all background animation. We are
already better than AMLL on "settle instead of orbit forever", which matters
more for battery.

---

## 5. Performance tricks

AMLL's useful patterns:

- **Only visible rows are mounted.** `dom/lyric-group.ts` inserts a line element
  when it enters `viewport + overscan` and removes it when it leaves. Overscan is
  300 px, plus a 40% viewport motion buffer so springs do not pop in at the edge.
- **O(1) layout.** Precomputed prefix sums of row heights; a reusable
  `RenderInstruction` pool avoids GC; `content-visibility: auto` and
  `contain: layout style paint` keep offscreen work out of style/layout.
- **Timeline diffs.** `timeline.ts` returns exactly which rows started, stopped,
  highlighted, or changed focus. `syncTime` returns immediately when nothing
  changed; layout runs only on a diff.
- **Cheap per-frame writes.** Style strings are compared before writing; opacity
  changes below 0.05 and blur changes below 0.05 are skipped; transform writes
  below 0.001 px are skipped. The interlude clock is synced from the media
  position so a suspended page cannot leave it drifting.
- **Mask rebuild signature.** `lyric-line.ts` hashes word sizes, line times,
  container width, fade width and non-dynamic flag; if nothing changed, it reuses
  the existing mask instead of rebuilding all word animations. The first size
  observation after mount is ignored to avoid a duplicate build.
- **Brightness window.** Only focus/highlight rows ±2 carry the cloned bright/
  dim copies; outside that window the copies are removed after a 500 ms settle.
  This keeps the expensive composite path to a handful of rows.
- **Animations pause.** Word/line animations are explicitly `pause()`d, the
  interlude dot state goes `idle`, and `isPageVisible` short-circuits the player
  update loop on `pagehide`.
- **Frame delta clamp.** `MAX_FRAME_DELTA = 100 ms` prevents a resume frame from
  jumping a spring across a whole suspension.
- **No per-row animation objects.** The scroll itself and all row scales share a
  few springs; the stagger is applied as a target-position delay, not as one
  animation per line.
- **Background cost controls.** FPS cap, render scale, static mode after fade,
  pause on pause, and disposal of old meshes/textures.

### What our app already has

We use `LazyColumn` (only visible items are composed), a draw-only lyric clock,
`animatesFromMs/animatesUntilMs` so a line outside its window does not read the
clock, a `koShouldDrawLyricFrame` 16 ms gate, cached `wordSpans`/`sweepSpans`,
one shared `ScrollRun` clock for all stagger, no per-row `Animatable`, low-power
and reduce-animation paths, a pre-blurred cached backdrop, and a background that
settles after a track change. That is already close to AMLL's discipline.

Missing: a formal "brightness window" is not needed because we draw directly,
but we should make sure the `MeshGradientBackground` phase loop is gated on
foreground/visibility, and we could cache per-line text layouts the way we
cache spans. AMLL's mask signature is not directly applicable to Compose, but
the equivalent is: recompute draw geometry only when the line's layout or
timing changes, not every frame.

---

## Comparison with our lyrics screen

| Area | AMLL | Our app (`PlayerLyrics.kt` etc.) |
|---|---|---|
| Row positions | prefix sums, spring, 50 ms cascade `/1.05` | LazyColumn, tween, 3-step 6% cascade |
| Scroll resume | event-driven, 500 ms after scroll end | event-driven, 600 ms / 5 s browse |
| Active opacity/scale | 0.85 / 1.0, inactive 0.97 | 1.0 / 1.0, inactive 0.98 |
| Blur | `1 + distance`, cap 5, 0.8 narrow | quantised 0/1/2 dp ladder |
| Word fill | moving gradient mask, 0.5 × font height | clip + 30 dp DstIn feather |
| Long-word emphasis | >= 1 s, 2..7 non-CJK, per grapheme | length/duration thresholds, block scripts excluded |
| Interlude | >= 7 s, breathe + entry/exit + fallback | >= 4 s, fill only + row swell |
| Background | fluid bicubic mesh, 60 fps cap, static/pause | palette blobs that settle; 36-cell static mesh |
| Hidden/paused | lyric loop skips; mesh paused/static by React | frame loop paused; mesh phase not foreground-gated |

What our app likely already covers well: the sweep, per-letter held-word
animation, bloom, backing vocal, duet lane, translation/romanisation, heard
words, visible-only list, clock interpolation, battery-aware background.

What is genuinely missing or weaker: physical spring handover, time-based
stagger, distance-based blur and AMLL's opacity/scale values, a fade width tied
to font height, interlude breathing and entry/exit, a dynamically deformable
mesh with FPS/render-scale controls, and an explicit foreground gate for the
mesh drift.

---

What I did NOT do: no source edits, no build/test run (this is documentation only), and I did not port AMLL's WebGL mesh or its AGPL code. Risk: the spring, 7 s interlude threshold and subtitle weight are observable UI changes and need product sign-off; our current 4 s threshold and heavier subtitles are deliberate choices that AMLL's numbers should not silently override.

## Summary: 8 most valuable changes, ranked

1. Replace the fixed lyric handover tween with a spring chosen from the gap to the previous line (90..220 stiffness, `sqrt(k)*2.2` damping), keeping the 3-step stagger and a low-power tween fallback.
2. Make the word feather `0.5 × font height` (adjustable 0.5..1.0) instead of a fixed 30 dp clip, preserving the cheap DstIn draw.
3. Give the three interlude dots a breathing envelope (0.4..1.25 scale, 4 s period), staged entry/exit, and the 7 s threshold, with a static fallback.
4. Gate and cap the background: foreground/visibility pause, 30/60 fps cap, render-scale option; keep our settle-after-drift battery behaviour.
5. Align line hierarchy with AMLL: active opacity 0.85, inactive scale 0.97, blur `1 + distance` capped at 5 px (0.8 on narrow screens).
6. Move long-word emphasis to a duration-first threshold (>= 1 s; 2..7 chars for non-CJK) with per-letter stagger, last-word boost and 2× background float.
7. Lighten translation/romanisation to AMLL's subtitle weight (0.5em, alpha 0.3) and add per-word romanisation under the word where the source has it.
8. Cache per-line text layout/draw geometry and keep every row outside its animation window clock-free; stop all background/phase work when hidden.
