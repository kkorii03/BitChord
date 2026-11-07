# Lane line-accuracy — offline per-line report

- Branch: `task/offline-replay`
- File: `app/src/test/java/com/music/bitchord/playback/smart/SyncReplayTest.kt`
- Corpus: `SYNC_REPLAY_DIR`, built by `scripts/replay_pack.py`; stays outside the repo.
- Build: not run locally (no Gradle/JVM/ADB in this lane). APIs checked by reading
  `LyricsTiming`, `RefinePolicy`, `LyricsPerLine`, `LyricsWordAlign`, `LyricsTimeMap`.

## Scope

A second JUnit test, `line accuracy report is printed`, replays each corpus song
through the app's own order, keeping every stage as its own snapshot:

1. `provider` — the `base=none` sheet's own line times,
2. `base` — `LyricsTiming.fitHeard` on the heard words, then `LyricsTiming.apply`
   with only that adjustment,
3. `refine` — `RefinePolicy.compute` on those lines, then `LyricsTiming.apply`
   with the refine on top,
4. `perline` — the `LyricsPerLine` layer through `LyricsTiming.apply`, using the
   same words the app passes (no hand correction, no HQ clock).

No hand correction, no HQ clock, no Android audio: the same pure pieces the
baseline test already uses. Every stage is scored on its own, per song and in
totals, so a regression can be attributed to provider, fit, refine or per-line.

The test also runs the same stage table for two variants:

- `first90` — the fit, refine and per-line layer run on heard words whose start
  is within the first 90 s, then all stages are scored against the whole song.
- `held` — the base fit runs on every other sung-line anchor and is scored only
  on the anchors held out from it. Refine and per-line are not meaningful here
  (they would see the held words), so `held` reports `provider` and `base` only.

## Scoring

`truth` in each corpus file is `[line, sung ms]` from the independent score.
Each shown line is matched by its 1-based `n` (falling back to its position)
and scored as `signed = shown - sung`, with `abs(signed)` as the error.

Per song, per variant, per stage, and in totals the report prints only ids,
numbers and a verdict:

- `lines` — scored lines,
- `med` — median absolute error in ms,
- `bias` — median signed error in ms,
- `in02`, `in03`, `in05` — shares of lines within 200/300/500 ms,
- `max` — the worst absolute error in ms,
- `cov` — lines heard near their shown time / lines that should have been heard
  (a truth line inside the heard span whose own letters were at least 50%
  matched), or `-` when no lines are eligible,
- `drift`, `span` — signed-error slope in ms per minute and the sung-time span
  it was measured over,
- `worst` — the worst line (and its song in the totals),
- `verdict` — `pass`/`warn`/`fail`, or `n/a` with no scored lines,
- `old` — the legacy `old_verdict`.

Strict rules, copied from `scripts/synctest.py` `score()`; every rule must hold
for `pass`:

- at least 8 scored lines,
- median absolute error <= 120 ms,
- `abs(bias)` <= 80 ms,
- at least 95% of lines within 300 ms (`in03`),
- at least 85% of lines within 200 ms (`in02`),
- worst absolute error <= 800 ms,
- coverage >= 0.4 when lines are eligible,
- no drift above 60 ms/min across a span of at least 90 s (signed error vs sung
  time, lines more than 500 ms off the median left out, as in `synctest.py`).

`verdict` is `warn` (not `fail`) while the median absolute error is at or below
300 ms, matching `synctest.py`; otherwise it is `fail`. The report also prints
`old=`, the legacy `old_verdict` rule kept by `synctest.py` (median <= 200 ms
and at least 90% within 300 ms), for comparing past runs.

The report is diagnostic only: it is wrapped so a report failure prints a label
and never fails the run. The existing baseline assertions are unchanged.

## Not recorded here

No corpus run was performed in this lane, and no corpus results are written in
this file. The numbers depend on the private corpus and must come from an actual
`SyncReplayTest` run. Nothing about lyric text is printed: only song ids,
numbers and `pass`/`fail`.
