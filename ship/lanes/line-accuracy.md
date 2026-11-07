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

## Corpus run 2026-11-07 (`Sync replay` run 42337090753)

`3 tests completed, 1 failed`: the plain-number score test and the diagnostic
report passed, the baseline assertion failed. The same six songs failed on the
two runs before this change too (42335384847, 42337404431), so their ids and
figures are:

| id | fit | expected | error | baseline | tolerance |
| --- | --- | --- | --- | --- | --- |
| `-RJSbO8UZVY` | 18490 | 2525 | 15965 | 2525 | 1500 |
| `8gyLR4NfMiI` | 22480 | 2094 | 20386 | 2094 | 300 |
| `OxgiiyLp5pk` | 25670 | -265 | 25935 | 265 | 300 |
| `YQHsXMglC9A` | 75050 | 1899 | 73151 | 1899 | 300 |
| `ekzHIouo8Q4` | 11677 | -36 | 11713 | 36 | 300 |
| `l4Z9kkYAggc` | 440 | 97 | 343 | 12 | 300 |

The other 79 of 85 songs stay inside baseline + tolerance. The report puts the
cause on the provider sheet rather than on the fit: for `OxgiiyLp5pk` the
`base` stage scores 180 ms median but its first-line shift is 25670 ms, and for
`ekzHIouo8Q4` the provider scores 173 ms while the replay fit moves the sheet
11503 ms. `scripts/replay_pack.py` prefers the whole-song `.opus` whisper-large
words over the app's own 90 s file, so for these songs the replay fits a
different transcript than the round the baseline was scored in. That pairing is
the thing to settle before this gate can be trusted; it lives in the pack
script, not in this test.

## Corpus run 2026-11-07 (`Sync replay` run 42338789271)

Branch `replay/accuracy` at `fff25ee0c`, pushed to trigger the workflow. The run
concluded `failure`: the corpus fetch and all three tests ran, the plain score
test and the diagnostic report passed, the baseline assertion failed. There was
no infrastructure failure.

- Job `replay` (128301629280): 6m52s; `Replay` step 6m11s.
- Corpus: `songs=85 truth=85 missing=0`.
- Gradle: `3 tests completed, 1 failed`, `BUILD FAILED in 6m 11s`.
- Score test: `songs=85 missing=0 pass=79 fail=6`.
- Verdict counts: `reject=1 keep=84 refine=41 expectedReject=4 expectedKeep=0 verdictPass=1 verdictKnown=4`.
- Failure message: `java.lang.AssertionError: 6 songs below baseline`.

### Stage totals

Both variants, every stage, scored against the whole song. Numbers are exactly
as printed by the report; `worst` is `song:line:abs_ms`.

| variant | stage | lines | med | bias | in02 | in03 | in05 | max | cov | drift | span | worst | verdict |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| full | provider | 2253 | 164 | -60 | 55% | 63% | 74% | 39620 | 97% | -8 | 357860 | `J7p4bzqLvCw:38:39620` | warn |
| full | base | 2253 | 72 | 5 | 78% | 84% | 89% | 83090 | 97% | 1 | 357860 | `YQHsXMglC9A:6:83090` | warn |
| full | refine | 2253 | 70 | 3 | 79% | 85% | 89% | 83090 | 97% | 1 | 357860 | `YQHsXMglC9A:6:83090` | warn |
| full | perline | 2253 | 50 | -10 | 84% | 88% | 90% | 83090 | 97% | 2 | 357860 | `YQHsXMglC9A:6:83090` | warn |
| first90 | provider | 2253 | 164 | -60 | 55% | 63% | 74% | 39620 | 96% | -8 | 357860 | `J7p4bzqLvCw:38:39620` | warn |
| first90 | base | 2253 | 82 | -5 | 75% | 81% | 86% | 40080 | 96% | 0 | 357860 | `A4HLeFXyFEY:39:40080` | warn |
| first90 | refine | 2253 | 75 | -7 | 78% | 83% | 87% | 40080 | 96% | -1 | 357860 | `A4HLeFXyFEY:39:40080` | warn |
| first90 | perline | 2253 | 67 | -10 | 78% | 83% | 87% | 40080 | 96% | 2 | 357860 | `A4HLeFXyFEY:39:40080` | warn |
| held | provider | 1140 | 170 | -58 | 54% | 62% | 73% | 31799 | 97% | -4 | 353960 | `NqLB6ub1BOw:35:31799` | warn |
| held | base | 1140 | 79 | 9 | 76% | 81% | 87% | 81725 | 97% | 6 | 353960 | `YQHsXMglC9A:19:81725` | warn |

The six baseline failures, with the baseline fields this run asserted against:

| id | expected | baselineErr | tolerance | stress | replay fit | error | pass |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `-RJSbO8UZVY` | 2525 | 2525 | 1500 | 1 | 18490 | 15965 | 0 |
| `8gyLR4NfMiI` | 2094 | 2094 | 1500 | 1 | 22480 | 20386 | 0 |
| `OxgiiyLp5pk` | -265 | 265 | 300 | 0 | 25670 | 25935 | 0 |
| `YQHsXMglC9A` | 1899 | 1899 | 1500 | 1 | 75050 | 73151 | 0 |
| `ekzHIouo8Q4` | -36 | 36 | 300 | 0 | 11677 | 11713 | 0 |
| `l4Z9kkYAggc` | 97 | 12 | 300 | 0 | 440 | 343 | 0 |

Their `full` stage rows (the other variants are in the run log):

| id | stage | lines | med | bias | in02 | in03 | in05 | max | cov | drift | worst | verdict |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `-RJSbO8UZVY` | provider | 14 | 2656 | -2500 | 21% | 21% | 21% | 13620 | 78% | 18 | 40:13620 | fail |
| `-RJSbO8UZVY` | base | 14 | 15990 | 15990 | 0% | 0% | 0% | 32110 | 78% | 18 | 40:32110 | fail |
| `-RJSbO8UZVY` | refine | 14 | 15990 | 15990 | 0% | 0% | 0% | 32110 | 78% | 18 | 40:32110 | fail |
| `-RJSbO8UZVY` | perline | 14 | 16015 | 16015 | 0% | 0% | 0% | 32030 | 78% | -62 | 40:32030 | fail |
| `8gyLR4NfMiI` | provider | 14 | 2545 | -2093 | 14% | 14% | 21% | 14640 | 100% | 1249 | 102:14640 | fail |
| `8gyLR4NfMiI` | base | 14 | 20386 | 20386 | 0% | 0% | 0% | 27145 | 100% | 1249 | 90:27145 | fail |
| `8gyLR4NfMiI` | refine | 14 | 20386 | 20386 | 0% | 0% | 0% | 27145 | 100% | 1249 | 90:27145 | fail |
| `8gyLR4NfMiI` | perline | 14 | 20866 | 20866 | 0% | 0% | 0% | 26488 | 100% | -780 | 90:26488 | fail |
| `OxgiiyLp5pk` | provider | 16 | 25625 | -25625 | 0% | 0% | 0% | 28095 | 100% | 14 | 19:28095 | fail |
| `OxgiiyLp5pk` | base | 16 | 180 | 45 | 50% | 75% | 94% | 2425 | 100% | 14 | 19:2425 | warn |
| `OxgiiyLp5pk` | refine | 16 | 180 | 45 | 50% | 75% | 94% | 2425 | 100% | 14 | 19:2425 | warn |
| `OxgiiyLp5pk` | perline | 16 | 155 | 155 | 50% | 75% | 88% | 2266 | 100% | -2 | 20:2266 | warn |
| `YQHsXMglC9A` | provider | 10 | 2155 | -1190 | 0% | 0% | 0% | 8040 | 88% | -1424 | 6:8040 | fail |
| `YQHsXMglC9A` | base | 10 | 73860 | 73860 | 0% | 0% | 0% | 83090 | 88% | -1424 | 6:83090 | fail |
| `YQHsXMglC9A` | refine | 10 | 73860 | 73860 | 0% | 0% | 0% | 83090 | 88% | -1424 | 6:83090 | fail |
| `YQHsXMglC9A` | perline | 10 | 73920 | 73920 | 0% | 0% | 0% | 83090 | 88% | -1367 | 6:83090 | fail |
| `ekzHIouo8Q4` | provider | 32 | 173 | -173 | 59% | 94% | 100% | 458 | 100% | -5 | 27:458 | warn |
| `ekzHIouo8Q4` | base | 32 | 11503 | 11503 | 0% | 0% | 0% | 11713 | 100% | -5 | 15:11713 | fail |
| `ekzHIouo8Q4` | refine | 32 | 11503 | 11503 | 0% | 0% | 0% | 11713 | 100% | -5 | 15:11713 | fail |
| `ekzHIouo8Q4` | perline | 32 | 11463 | 11463 | 0% | 0% | 0% | 11697 | 100% | -36 | 8:11697 | fail |
| `l4Z9kkYAggc` | provider | 20 | 480 | -480 | 5% | 10% | 60% | 1263 | 100% | -84 | 26:1263 | fail |
| `l4Z9kkYAggc` | base | 20 | 115 | -40 | 75% | 90% | 90% | 823 | 100% | -84 | 26:823 | warn |
| `l4Z9kkYAggc` | refine | 20 | 115 | -40 | 75% | 90% | 90% | 823 | 100% | -84 | 26:823 | warn |
| `l4Z9kkYAggc` | perline | 20 | 57 | -30 | 75% | 80% | 100% | 472 | 100% | -30 | 62:472 | warn |

### Findings from this run

- On the whole corpus the fit is the big gain: median absolute error falls
  `provider 164 -> base 72 ms`, with `in03 63% -> 84%`. Refine adds almost
  nothing to the totals (`72 -> 70`). Per-line lowers the median again
  (`70 -> 50`) and lifts `in03` to 88%.
- The `first90` variant is worse than `full` at every fitted stage:
  `base 82`, `refine 75`, `perline 67` against `72 / 70 / 50`. It does cap the
  worst error at 40080 ms instead of 83090 ms, so the missed second half of the
  song stops one catastrophic outlier but costs typical accuracy.
- `held` keeps the fit close to `full` on the anchors it never saw:
  `base med 79` on 1140 lines against `72` on the full 2253, so the fit is not
  just memorising its own anchors.
- Every stage in every totals row is `warn`, never `pass`; the run still fails
  because the baseline gate is per song. The report cannot make the gate pass.
- The six failures split by where the damage is:
  `OxgiiyLp5pk` has a good `base` stage (med 180) while its provider sheet is
  shifted 25.6 s, yet the score test's own `fit` is 25670; `ekzHIouo8Q4` has a
  good provider (173) that the base fit moves by 11.5 s; `-RJSbO8UZVY`,
  `8gyLR4NfMiI` and `YQHsXMglC9A` get a wrong 16-74 s shift from the base fit;
  `l4Z9kkYAggc` is only 343 ms off against a 300 ms tolerance. These are
  per-song pairing/transcript symptoms, consistent with the earlier note that
  the replay transcript differs from the baseline round, not a single fitting
  bug.

## Not recorded here

Corpus runs recorded here: 42337090753 and 42338789271; the numbers depend on the
private corpus. Nothing about lyric text is printed: only song
ids, numbers and `pass`/`fail`.
