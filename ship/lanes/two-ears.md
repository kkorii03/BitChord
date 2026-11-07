# Lane two-ears — move a line only when two hearings agree

- Branch: `task/two-ears` (based on `origin/task/offline-replay`)
- Current final commit: `5c54d012d` — adds `SyncReplayTwoEarsTest.kt`, the `--tests` entry in `sync-replay.yml`, and this lane note; no shared code changed (this note is a doc-only amend of that commit, so the code tree is unchanged)
- Current final CI: `Sync replay` run `42338920952` on `replay/two-ears-exp`, commit `5c54d012d` — **red**, but the red is the pre-existing `SyncReplayTest` guard, not this experiment (see CI note)
- Earlier run (historical, superseded): `42337404431` on `bc841ec3f` — same red, same cause
- Corpus: 85 songs, 32 of them have `heard2` (Groq, first 90 s only)
- Verdict: **the idea does not help.** Every two-hearing strategy is worse than today's single-hearing `LyricsPerLine` on the median, in both halves, in both languages.

## A–E table (lines inside `heard2`'s 90 s)

`med` = median absolute error in ms; `in200/300/500` = share of lines within 0.2/0.3/0.5 s; `worst` = largest error in ms `@song:line`; `strict` = songs meeting synctest's strict rules. `dev` = first half of the songs, `conf` = second half, `all` = both.

### English (`en`)

| Strategy | Half | lines | med | in200 | in300 | in500 | worst | strict |
|---|---|---|---|---|---|---|---|---|
| A | dev | 102 | 36 | 91% | 94% | 96% | 12460@fsCxgDq2T9k:7 | 3/6 |
| B | dev | 102 | 39 | 92% | 95% | 96% | 12460@fsCxgDq2T9k:7 | 3/6 |
| C | dev | 102 | 137 | 64% | 76% | 92% | 12460@fsCxgDq2T9k:7 | 0/6 |
| D150 | dev | 102 | 56 | 88% | 94% | 96% | 12460@fsCxgDq2T9k:7 | 3/6 |
| D200 | dev | 102 | 62 | 86% | 92% | 96% | 12460@fsCxgDq2T9k:7 | 2/6 |
| D300 | dev | 102 | 62 | 87% | 94% | 96% | 12460@fsCxgDq2T9k:7 | 2/6 |
| E | dev | 102 | 42 | 91% | 94% | 96% | 12460@fsCxgDq2T9k:7 | 3/6 |
| A | conf | 55 | 46 | 82% | 85% | 85% | 1443@RrfF9cuJuQU:26 | 1/6 |
| B | conf | 55 | 60 | 82% | 91% | 91% | 1244@RrfF9cuJuQU:12 | 1/6 |
| C | conf | 55 | 95 | 73% | 82% | 91% | 3797@rlYOsUfpi3M:1 | 1/6 |
| D150 | conf | 55 | 68 | 73% | 80% | 87% | 1280@RrfF9cuJuQU:12 | 1/6 |
| D200 | conf | 55 | 70 | 75% | 80% | 87% | 1280@RrfF9cuJuQU:12 | 1/6 |
| D300 | conf | 55 | 101 | 73% | 78% | 87% | 1280@RrfF9cuJuQU:12 | 1/6 |
| E | conf | 55 | 60 | 84% | 91% | 95% | 974@RrfF9cuJuQU:13 | 1/6 |
| A | all | 157 | 39 | 88% | 91% | 92% | 12460@fsCxgDq2T9k:7 | 4/12 |
| B | all | 157 | 45 | 89% | 94% | 94% | 12460@fsCxgDq2T9k:7 | 4/12 |
| C | all | 157 | 118 | 67% | 78% | 92% | 12460@fsCxgDq2T9k:7 | 1/12 |
| D150 | all | 157 | 58 | 83% | 89% | 93% | 12460@fsCxgDq2T9k:7 | 4/12 |
| D200 | all | 157 | 66 | 82% | 88% | 93% | 12460@fsCxgDq2T9k:7 | 3/12 |
| D300 | all | 157 | 67 | 82% | 89% | 93% | 12460@fsCxgDq2T9k:7 | 3/12 |
| E | all | 157 | 50 | 89% | 93% | 96% | 12460@fsCxgDq2T9k:7 | 4/12 |

### Korean (`ko`)

| Strategy | Half | lines | med | in200 | in300 | in500 | worst | strict |
|---|---|---|---|---|---|---|---|---|
| A | dev | 77 | 50 | 94% | 96% | 99% | 590@T1wssdlybp4:26 | 2/9 |
| B | dev | 77 | 41 | 95% | 95% | 96% | 907@REmUidcJt5I:39 | 1/9 |
| C | dev | 77 | 105 | 74% | 84% | 96% | 640@INLFlN-PZq4:2 | 0/9 |
| D150 | dev | 77 | 63 | 92% | 97% | 99% | 617@T1wssdlybp4:26 | 1/9 |
| D200 | dev | 77 | 65 | 91% | 97% | 99% | 617@T1wssdlybp4:26 | 1/9 |
| D300 | dev | 77 | 68 | 94% | 97% | 99% | 617@T1wssdlybp4:26 | 2/9 |
| E | dev | 77 | 47 | 94% | 94% | 95% | 907@REmUidcJt5I:39 | 1/9 |
| A | conf | 83 | 150 | 57% | 64% | 77% | 17827@NqLB6ub1BOw:14 | 1/9 |
| B | conf | 83 | 53 | 71% | 78% | 83% | 17827@NqLB6ub1BOw:14 | 1/9 |
| C | conf | 83 | 187 | 53% | 67% | 80% | 18165@NqLB6ub1BOw:14 | 0/9 |
| D150 | conf | 83 | 93 | 73% | 80% | 82% | 17827@NqLB6ub1BOw:14 | 1/9 |
| D200 | conf | 83 | 110 | 72% | 80% | 82% | 17983@NqLB6ub1BOw:14 | 0/9 |
| D300 | conf | 83 | 108 | 71% | 78% | 82% | 17997@NqLB6ub1BOw:14 | 0/9 |
| E | conf | 83 | 60 | 70% | 76% | 83% | 17827@NqLB6ub1BOw:14 | 1/9 |
| A | all | 160 | 82 | 74% | 79% | 88% | 17827@NqLB6ub1BOw:14 | 3/18 |
| B | all | 160 | 50 | 83% | 86% | 89% | 17827@NqLB6ub1BOw:14 | 2/18 |
| C | all | 160 | 130 | 63% | 76% | 88% | 18165@NqLB6ub1BOw:14 | 0/18 |
| D150 | all | 160 | 76 | 83% | 88% | 90% | 17827@NqLB6ub1BOw:14 | 2/18 |
| D200 | all | 160 | 82 | 81% | 88% | 90% | 17983@NqLB6ub1BOw:14 | 1/18 |
| D300 | all | 160 | 82 | 82% | 88% | 90% | 17997@NqLB6ub1BOw:14 | 2/18 |
| E | all | 160 | 50 | 81% | 84% | 89% | 17827@NqLB6ub1BOw:14 | 2/18 |

### Other (`multi`, 2 lines total — too small to mean anything)

| Strategy | Half | lines | med | in200 | in300 | in500 | worst | strict |
|---|---|---|---|---|---|---|---|---|
| A | dev | 1 | 28 | 100% | 100% | 100% | 28@nFYwcndNuOY:15 | 0/1 |
| B | dev | 1 | 50 | 100% | 100% | 100% | 50@nFYwcndNuOY:15 | 0/1 |
| C | dev | 1 | 70 | 100% | 100% | 100% | 70@nFYwcndNuOY:15 | 0/1 |
| D150 | dev | 1 | 10 | 100% | 100% | 100% | 10@nFYwcndNuOY:15 | 0/1 |
| D200 | dev | 1 | 10 | 100% | 100% | 100% | 10@nFYwcndNuOY:15 | 0/1 |
| D300 | dev | 1 | 10 | 100% | 100% | 100% | 10@nFYwcndNuOY:15 | 0/1 |
| E | dev | 1 | 50 | 100% | 100% | 100% | 50@nFYwcndNuOY:15 | 0/1 |
| A | conf | 1 | 0 | 100% | 100% | 100% | 0@— | 0/1 |
| B | conf | 1 | 73 | 100% | 100% | 100% | 73@NPZtKA6343o:11 | 0/1 |
| C | conf | 1 | 0 | 100% | 100% | 100% | 0@— | 0/1 |
| D150 | conf | 1 | 36 | 100% | 100% | 100% | 36@NPZtKA6343o:11 | 0/1 |
| D200 | conf | 1 | 36 | 100% | 100% | 100% | 36@NPZtKA6343o:11 | 0/1 |
| D300 | conf | 1 | 36 | 100% | 100% | 100% | 36@NPZtKA6343o:11 | 0/1 |
| E | conf | 1 | 73 | 100% | 100% | 100% | 73@NPZtKA6343o:11 | 0/1 |
| A | all | 2 | 14 | 100% | 100% | 100% | 28@nFYwcndNuOY:15 | 0/2 |
| B | all | 2 | 61 | 100% | 100% | 100% | 73@NPZtKA6343o:11 | 0/2 |
| C | all | 2 | 35 | 100% | 100% | 100% | 70@nFYwcndNuOY:15 | 0/2 |
| D150 | all | 2 | 23 | 100% | 100% | 100% | 36@NPZtKA6343o:11 | 0/2 |
| D200 | all | 2 | 23 | 100% | 100% | 100% | 36@NPZtKA6343o:11 | 0/2 |
| D300 | all | 2 | 23 | 100% | 100% | 100% | 36@NPZtKA6343o:11 | 0/2 |
| E | all | 2 | 61 | 100% | 100% | 100% | 73@NPZtKA6343o:11 | 0/2 |

## Rest beyond 90 s (only A and B have words there)

| Strategy | Language | lines | med | in200 | in300 | in500 | worst |
|---|---|---|---|---|---|---|---|
| A | en | 229 | 43 | 97% | 98% | 98% | 3877@RrfF9cuJuQU:37 |
| B | en | 229 | 40 | 93% | 96% | 97% | 2686@fsCxgDq2T9k:70 |
| A | ko | 211 | 88 | 72% | 79% | 82% | 31786@NqLB6ub1BOw:35 |
| B | ko | 211 | 50 | 83% | 87% | 89% | 31913@NqLB6ub1BOw:35 |
| A | multi | 12 | 47 | 100% | 100% | 100% | 146@nFYwcndNuOY:30 |
| B | multi | 12 | 48 | 100% | 100% | 100% | 160@nFYwcndNuOY:47 |

## Songs made noticeably worse than B (median +100 ms or more on the same lines)

`C 7PgA6kyqVTc +180`, `C G6M-qNC5B0E +199`, `D150 G6M-qNC5B0E +100`, `D200 G6M-qNC5B0E +100`, `C KGo_pX4b4fc +172`, `D300 KGo_pX4b4fc +138`, `C NqLB6ub1BOw +196`, `D150 RrfF9cuJuQU +349`, `D200 RrfF9cuJuQU +349`, `D300 RrfF9cuJuQU +349`, `D150 Vyo2m2CbdUs +139`, `D200 Vyo2m2CbdUs +139`, `C _tIrGCUMHCY +173`, `D150 _tIrGCUMHCY +106`, `D200 _tIrGCUMHCY +106`, `D300 _tIrGCUMHCY +101`, `C fsCxgDq2T9k +272`

## Verdict: negative

- **D never beats B.** Median error, dev/conf: en 56/68 (D150) vs 39/60 (B); ko 63/93 vs 41/53. The 150 ms gate is the least bad and still loses by 17–40 ms. 200 and 300 ms are worse still.
- **C alone is much worse than B** (en 137/95, ko 105/187). `heard2` is a 90 s slice of a different transcript; on its own it is not a usable clock.
- **E ties B at best** (en conf 60 vs 60, ko all 50 vs 50) and loses elsewhere (en dev 42 vs 39, ko dev 47 vs 41, ko conf 60 vs 53). Picking the hearing that matched more words does not beat the app's current single hearing.
- **Strict-rule songs do not improve.** B 4/12 en, 2/18 ko; D150 4/12, 2/18. No gain.
- **The rest beyond 90 s is unaffected** (A vs B only), and B is better there too (ko 50 vs 88).
- The premise was that two hearings agreeing would filter mishearings. In this corpus the second hearing is a 90 s Groq pass, and where it disagrees with Deepgram the disagreement is not noise around a correct line — gating on it throws away good moves and keeps bad ones. The idea is not worth shared code.

## Wiring

None. No shared code changed, nothing wired into the app. Had it helped, `LyricsPerLine.compute` would need an optional second transcript (behaving exactly as now without one), and the app would have to keep the rough words instead of replacing them — `SongHearing.send`/`GroqWhisper` were not touched, and today the app discards the first hearing's words once the second arrives, so the second transcript is not available at the point `LyricsPerLine` runs. That plumbing is the real cost, and the numbers do not justify it.

## CI note

Current final run `42338920952` (`replay/two-ears-exp`, `5c54d012d`) is **red**: `3 tests completed, 1 failed`, `FAILED: java.lang.AssertionError: 6 songs below baseline`. That assertion is in the pre-existing `SyncReplayTest.kt` (`assertTrue("$failures songs below baseline", failures == 0)`), which this branch did not modify; its own summary line is `songs=85 missing=0 pass=79 fail=6`. The two-ears test itself passed and printed the table above (`two-ears: songs=85 with-heard2=32`). So the red run is a pre-existing baseline guard on the replay corpus, not a failure of this experiment.

The earlier run `42337404431` (`bc841ec3f`) was red for the same reason and is kept only as history.

## Method

`SyncReplayTwoEarsTest.kt` runs only when `SYNC_REPLAY_DIR` is set and passes trivially without it. It applies the whole-song fit, then A–E per line, scores against `truth` only for lines inside `heard2`'s range, reports the remainder separately, splits songs into dev/conf halves, and prints ids and numbers only (the corpus holds lyrics). No Gradle/JVM run on this machine; all numbers are from the CI log.
