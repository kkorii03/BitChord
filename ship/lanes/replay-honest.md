# Replay honesty — status report

Scope: the offline sync replay (`SyncReplayTest`) and the corpus packer
(`scripts/replay_pack.py`). No playback or sync production code was changed.

## The answer

The replay was marking its own homework on the per-line stage. The moves are
computed from the app's whisper-large words, and the truth the moves are scored
against is built from the same whisper-large transcript on the same audio in
82 of 85 corpus songs (40 from the app's own WAV, 42 from the YouTube opus
copy); 3 songs have no whisper-large word file left in the current rounds. The
second model in the independent score (whisper-medium) only gates which lines
count (both models must place a line within 0.2 s); it does not replace the
timing. So a line moved onto a whisper-large mishearing can score as perfect.

The two measures also disagree on the same songs. On rs2/rs4, the independent
score (whisper-large + whisper-medium, `scripts/synctest.py`) gives 70 PASS,
100 WARN, 21 FAIL over 191 scored rows, median bias -0.011 s, median absolute
error 0.020 s. A good-sheet rule simulated on those logs passes 22 of 52 songs
as shipped and 27 of 52 at best (`bench/perline_gate.py`), yet on the replay
that rule changed no song's verdict while a hold-range change moved 33 to 36
(from the audit brief). A measure that cannot see the layer's mistakes cannot
judge the layer.

The smallest honest change: keep the old totals exactly as they are, and add a
second hearing the app never fitted on. `heard2` is Groq's whisper-large-v3
(ko/ja) or whisper-large-v3-turbo (other) on the same 90 s WAV the app
recorded (`bench/replay_second_ear.py`). The test now scores every stage
against `truth2`, the lines that second hearing placed itself (>= 0.5 of the
line's letters matched), and prints `totals ears2 <stage>` and
`totals ears2 held <stage>` beside the old totals. 32 of 85 corpus songs carry
`heard2`; 771 truth rows are reachable through it.

The second ear is a different measurement, not a copy: on the 16 songs where
it can be matched word-for-word to the app's hearing, 842 words, its per-song
median is -100 ms (range -270 to -20 ms) and only 9 of 16 are within 100 ms
(en 6 songs -110 ms, ko 9 songs -90 ms, multi 1 song -100 ms).

The old target is also not an independent answer. `expectedOffsetMs` is within
300 ms of the offset the independent truth needs in 75 of 85 songs; 10 songs
are off by more than 300 ms (worst 25.9 s). The recorded `baselineErrorMs`
understates the error against truth by more than 300 ms in 11 songs, e.g.
J7p4bzqLvCw 10 vs 12370 ms, OxgiiyLp5pk 265 vs 25890 ms, NqLB6ub1BOw 16 vs
18228 ms, WzI1qz1wZY4 5 vs 5160 ms. The baseline was measured against the same
whisper-large truth, so it cannot certify the fit.

## 1. What each side sees, per stage

| Stage | App (production) | Replay (test) | Independent scorer (`synctest.py`) |
| --- | --- | --- | --- |
| provider | provider sheet from the `base=none` block | same sheet from the corpus | the app's displayed lines (final block) as `shown` |
| base | `LyricsTiming.fitHeard` on the heard words | same call, same words | not fitted; measures the display |
| refine | `RefinePolicy.compute(lines, heard)` | same call | not fitted; measures the display |
| perline | `LyricsPerLine.apply` on the heard words | same call | not fitted; measures the display |
| app | the recorded screen (`shown_s`) | scored from `truth`'s third number | `shown_s` is the same screen |
| ears2 | — | every stage scored against `truth2` | — |
| held | — | base fitted on even-index lines, scored on odd-index lines | — |

The replay's `heard` is the whisper-large word file the corpus was packed
from: `.app.whisper-large.words.json` when it reaches the sheet's last line,
otherwise `.opus.whisper-large.words.json` when the score named the video
itself. The independent score's `sung` is whisper-large + whisper-medium
agreement (0.2 s), or forced alignment where it agrees with whisper within
0.1 s. The replay's `truth` is `[line, sung ms]` from `lines.csv`, plus
`shown ms` when the score recorded it.

Held-out split: per song, the non-blank sheet lines are indexed; even
positions are the fit half, odd positions the held half (both need >= 3
lines). The base stage is fitted on the fit half only and scored on the held
half; the provider stage is scored on the same held half. This is the only
split where the fit never sees the lines it is scored on. It is a split of
lines, not of songs, and the truth is still the old truth (or `truth2` for
`ears2 held`).

Independence table:

| Measure | Words that move the line | Words that time the truth | Independent? |
| --- | --- | --- | --- |
| replay perline vs old truth | whisper-large (app or opus) | whisper-large + whisper-medium, same audio | no for 82/85 (same large transcript); 3 unknown |
| replay perline vs `ears2` | whisper-large (app or opus) | Groq whisper-large-v3 / -turbo, same 90 s WAV | yes (different provider and run), same audio, only placed lines |
| independent scorer | the app's display | whisper-large + whisper-medium, same audio | partly (two models must agree; large is shared) |
| app stage | the recorded display | old truth | no (same truth as above) |
| held | fit on half the lines | old truth or `truth2` | held out for the fit, not for the truth |

## 2. rs2/rs4 measurement

Corpus: 85 songs, rounds `rs` 37 and `rs2` 48. 53 songs are also scored in
`phone-out/rs2` or `rs4`; 44 of them in the same round, 9 only in another
round (3JWTaaS7LdU, 8gyLR4NfMiI, G6M-qNC5B0E, G8YNFjruzPk, NPZtKA6343o,
O5qOB4cDr7s, pB-5XG-DbAA, Sr7dWdf4Z3U, WAQ5_7YFAVo). The 9 are a different
recording or code era and are not compared. 32 corpus songs have no
independent score at all.

The 44 same-round songs: independent verdicts PASS 17, WARN 20, FAIL 7;
independent `in03` mean 0.804; the recorded display's `in03` mean 0.805. The
two `in03` numbers are the same 2253 rows, so they agree by construction; the
real comparison is the strict verdict against the old within-0.3 rule
(median <= 200 ms and >= 90% within 0.3 s). 31 of 44 agree. All 13
disagreements are WARN songs whose display is still >= 90% within 0.3 s, i.e.
the strict verdict failed on another rule (coverage, drift, `in02`, `max_abs`):

| Song | Round | Verdict | in03 | median abs (s) | Source |
| --- | --- | --- | --- | --- | --- |
| xK5bBgpt4XA | rs2\auto88 | WARN | 0.93 | 0.055 | Gemini |
| vRjfYdwM5L4 | rs2\auto91 | WARN | 0.91 | 0.110 | Gemini |
| qsqvJpOgrWw | rs2\auto92 | WARN | 0.94 | 0.023 | Unison |
| ekzHIouo8Q4 | rs2\auto93 | WARN | 1.00 | 0.071 | lrc.red |
| XbGs_qK2PQA | rs2\fleet33 | WARN | 0.91 | 0.073 | LRCLIB |
| wwGRYpAoY78 | rs2\fleet34 | WARN | 0.96 | 0.043 | lrc.red |
| uaejCvCMu5I | rs2\fleet35 | WARN | 0.91 | 0.020 | VIBE |
| j-seAiANLG8 | rs2\fleet37 | WARN | 0.95 | 0.030 | BiniLyrics |
| l4Z9kkYAggc | rs2\fleet37 | WARN | 0.90 | 0.040 | VIBE |
| INLFlN-PZq4 | rs2\fleet38 | WARN | 0.90 | 0.037 | VIBE |
| Vyo2m2CbdUs | rs2\fleet38 | WARN | 0.92 | 0.124 | lrc.red |
| NqLB6ub1BOw | rs2\fleet39 | WARN | 0.94 | 0.030 | KuGou |
| REmUidcJt5I | rs2\fleet39 | WARN | 0.97 | 0.051 | lrc.red |

The independent score over all rs2/rs4 rows (191 rows: 136 rs2, 55 rs4):

| Provider | n | PASS | WARN | FAIL | bias (s) | median abs (s) |
| --- | --- | --- | --- | --- | --- | --- |
| lrc.red | 73 | 47 | 18 | 8 | -0.016 | 0.020 |
| LRCLIB | 25 | 6 | 14 | 5 | -0.030 | 0.033 |
| VIBE | 24 | 2 | 22 | 0 | +0.003 | 0.011 |
| BiniLyrics | 24 | 11 | 9 | 4 | -0.019 | 0.022 |
| KuGou | 11 | 2 | 8 | 1 | -0.007 | 0.022 |
| Unison | 9 | 0 | 9 | 0 | +0.011 | 0.011 |
| Gemini | 6 | 1 | 3 | 2 | +0.011 | 0.049 |
| LyricsPlus | 1 | 0 | 1 | 0 | +0.030 | 0.030 |
| PaxSenix | 1 | 0 | 1 | 0 | -0.034 | 0.034 |

12 rows are swapped renditions. The app's recorded display against the same
truth, by language (85 songs, 2253 lines). Bias and median absolute error are
medians of the per-song values; "within 0.3 s" is the mean of each song's
share, not a pooled share:

| Language | Songs | Lines | Bias (s) | Median abs (s) | Within 0.3 s |
| --- | --- | --- | --- | --- | --- |
| all | 85 | 2253 | -0.010 | 0.068 | 0.728 |
| ko | 44 | 914 | -0.007 | 0.054 | 0.802 |
| en | 34 | 1197 | -0.016 | 0.079 | 0.692 |
| multi | 4 | 94 | +0.252 | 0.259 | 0.500 |
| ja | 3 | 48 | -0.003 | 0.440 | 0.356 |

20 songs have a display bias over 300 ms; the worst are WANYQ3JC298 -14.430 s
(ja, lrc.red), pB-5XG-DbAA -13.016 s (en, BiniLyrics), v59g4HRNIkc -8.220 s
(ko, KuGou), A4HLeFXyFEY -8.115 s (ko, VIBE), fsCxgDq2T9k +6.815 s (en,
lrc.red). The recorded `baselineErrorMs` matches the display's signed median
within 1.5 ms in all 85 songs, so the baseline is a faithful copy of the same
measurement, not an independent check.

## 3. Second-ear bias and target mismatch

Second ear vs the app's hearing: 16 songs matched, 842 words, per-song median
-100 ms (min -270, max -20), 9 of 16 within 100 ms. By language: en 6 songs
-110 ms, ko 9 songs -90 ms, multi 1 song -100 ms. The largest per-song gaps:
7PgA6kyqVTc -270 ms (20 words), lga4m0pWicc -210 ms (30), WzI1qz1wZY4 -200 ms
(11), INLFlN-PZq4 -140 ms (41), eA59kfZ6rak -120 ms (95), fsCxgDq2T9k -120 ms
(91), TuzfXgUpboA -120 ms (27), NPZtKA6343o -100 ms (45).

Target mismatch: `expectedOffsetMs` vs the offset the independent truth needs,
75 of 85 within 300 ms, 78 within 1000 ms, max 25890 ms. The 10 songs over
300 ms: 7PgA6kyqVTc +1170, A4HLeFXyFEY +3435, J7p4bzqLvCw -12370,
NqLB6ub1BOw -18228, OxgiiyLp5pk -25890, WzI1qz1wZY4 -5160, YQHsXMglC9A +709,
dQw4w9WgXcQ -860, fDb0tKHcZhg +4197, l4Z9kkYAggc -383. The recorded
`baselineErrorMs` is understated by more than 300 ms in 11 songs (65RQZICv4iI
460 vs 800, 7PgA6kyqVTc 10 vs 1170, J7p4bzqLvCw 10 vs 12370, NqLB6ub1BOw 16 vs
18228, OxgiiyLp5pk 265 vs 25890, RrfF9cuJuQU 23 vs 698, WzI1qz1wZY4 5 vs 5160,
eqKhhG4FVoQ 20 vs 440, fDb0tKHcZhg 102 vs 4197, l4Z9kkYAggc 12 vs 383,
lga4m0pWicc 10 vs 360).

Provider timing against the independent truth over the whole corpus: 85 songs,
2253 lines, median absolute error 174 ms, 57.8% within 0.3 s (ko 63.8%, en
56.9%, ja 23.6%, multi 24.5%). By provider: lrc.red 70 ms / 78.6%, VIBE 260 ms
/ 52.5%, LRCLIB 400 ms / 41.4%, BiniLyrics 327 ms / 49.3%, KuGou 480 ms /
33.6%, Gemini 462 ms / 56.4%.

## 4. What changed

| File | Change |
| --- | --- |
| `app/src/test/java/com/music/bitchord/playback/smart/SyncReplayTest.kt` | `heard2` field, `truth` third number, `app` stage, `report()` takes a truth map, `ears2` / `ears2 held` totals, one new unit test |
| `scripts/replay_pack.py` | `line_truth` writes `shown ms` when `lines.csv` has it, `keep_heard2` carries a second hearing only for the same song and round, self-check for both, `second hearing:` count |
| `ship/lanes/replay-honest.md` | this report |

No app production code was changed. No other file was kept modified. No
workflow file was changed: `.github/workflows/sync-replay.yml` already runs
`*SyncReplayTest` (line 48), and `.github/workflows/ko-wip-check.yml` contains
no `SyncReplayTest` reference (its only replay names are the pre-existing
`CacheRenditionLosslessReplayTest` and `LaunchReplayTest`).

Kotlin test details:

- `Song.heard2: List<Word> = emptyList()`; `truth` rows are `[line, sung]` or
  `[line, sung, shown]`.
- `fullStages` is `provider, base, refine, perline, app`; `app` is added only
  when every truth row carries a display time, so an old corpus scores as
  before.
- `report(tag, stages, labels, tallies, song, numbers, coverage, only, truth)`
  now requires the truth map. All five call sites pass one: `full`,
  `first90`, `held` pass `sung`; `ears2` and `ears2 held` pass `truth2`.
- `scoreLines` is split into `sungByLine`, `shownByLine`, `recordedLines`,
  `truthFromPlacement` and `scoreAgainst`; the old `scoreLines` unit test is
  unchanged and still passes `truth` rows (median 225, bias -225, w200 2,
  w300 3, worst line 4 at 1000 ms).
- `truthFromPlacement` keeps a line only when the second hearing placed it
  with `matchedShare >= HEARD_SHARE` (0.5); a line it did not place is left
  out, not scored against another hearing's answer.
- Old totals text and order are unchanged; `totals ears2 <stage>` and
  `totals ears2 held <stage>` are printed after them from separate tallies.
  `ears2` coverage is not reported (no second-ear coverage definition), so
  those rows show `cov=-`.
- The baseline test (`recorded songs stay at or above the baseline`) keeps its
  reject/keep/refine/pass counting and its refusal rule unchanged.

Packer details:

- `line_truth` returns `[line, sung ms, shown ms]`; the third number is left
  out when an old score has no `shown_s` column.
- `keep_heard2(old, vid, round_name)` returns a previously recorded `heard2`
  only for the same song and the same round, so a paid second hearing is not
  erased and not moved onto another recording.
- `self_check()` asserts the second-hearing rules next to the existing
  sheet-pairing, sheet-match and `inside()` checks.
- `main()` reads the existing corpus file before writing, keeps `heard2` when
  it applies, and prints `second hearing: N`.

## 5. Verification performed

No Gradle, no JVM, no emulator, no phone. Python checks used
`C:\Users\jang\Desktop\BitChord\tools\python\python.exe` (3.13.16, outside
PATH; the PATH `python.exe` is the Store stub):

- `python -m py_compile scripts/replay_pack.py` — OK
- `import replay_pack; replay_pack.self_check()` — OK
- end-to-end pack into `%TEMP%\replaycheck` with `--out`/`--baseline` there:
  33 rounds, 76 songs, 17 stress, 0 second hearing (the temp corpus starts
  empty, so nothing to keep), 24155 words, 5013 lines. No repo file written.
- corpus/baseline parity: `phone-out/replay` has 85 files and
  `bench/replay/baseline.json` 85 ids, sets equal (no missing id, no extra).
  32 of 85 corpus files carry `heard2`; all 85 carry `truth`.
- schema check of every corpus file: `expectedOffsetMs`/`baselineErrorMs` int,
  `stress` bool, truth rows all int, words `{t,s,e}` int, sheet `{n,t,text}`
  int; baseline `stress` all int (0/1). 0 issues.
- Kotlin was checked by reading every called API, not by compiling:
  `LyricsTiming.fitHeard` (internal; the test is in the same module/friend
  path), `Timing(base, manualMs)`, `Timing.copy(...)`, `apply(lines, timing)`,
  `shownShiftMs(lines, Adjustment)`, `PerLineWords(...)`,
  `Adjustment.Mapped.matchPercent`, `RefinePolicy.compute(lines, heard)` /
  `Result(applied, medianMs, slopePerMin, model)` / `Model.wide`,
  `LyricsMismatch.judgeSheet` -> `Verdict.rejected`,
  `Deepgram.spellableLines`, `LyricsWordAlign.placeLinesDetailed` /
  `Placement(starts, attempted, matchedShare)` / `Heard(text, startMs, endMs)`,
  `LyricLine(timeMs, text)`. The `fitHeard(..., spellable) { fit, moved -> }`
  call matches the production call at `LyricsTiming.kt:3827`. Braces and
  parentheses in the test balance (150/150, 349/349).

## 6. What this does not prove

- The test was not compiled and not executed against the corpus. Type errors
  would only show under Gradle.
- The `ears2` and `app` stages were not exercised on data (needs the JVM).
  They are diagnostic only and cannot fail the baseline test by design.
- `app` needs a `shown_s` column in `lines.csv`; an old score without it just
  omits that stage.
- `heard2` exists for 32 of 85 songs and reaches 771 truth rows. The second
  ear is the same 90 s audio and the same model family (whisper-large-v3 vs
  whisper-large), so it breaks the exact run, not the family; a line both
  hearings mishear the same way still scores as perfect.
- 3 songs have no whisper-large word file in the current rounds, so their
  provenance cannot be re-checked; 9 songs are scored only in another round
  and were not compared.
- `bench/replay_second_ear.py` is outside this tree (shared `bench`), so a new
  second hearing has to be produced there before `ears2` prints anything new.
- The temp end-to-end run found 76 songs against the repo baseline's 85 (the
  current `phone-out/rs|rs2` rounds differ from the snapshot the baseline was
  built from). Every generated id is in the baseline, so the missing-id
  assertion is not at risk, but the numbers should not be compared directly.
