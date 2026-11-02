# Battery / ANR audit of the 11-03 merge wave

Scope: the DeepSeek lanes merged into `task/u19-tg` on the night of 2026-11-02/03
(HEAD `e2de4702`), plus the two Opus fix merges that landed in the same window.
No earlier code was re-audited except where a lane touched it.

| merge | lane | production files |
|---|---|---|
| `94241b55` | cloud/cache-lock-r3 | `AudioCache.kt`, `CommittedSpanReader.kt` |
| `0a58ffcb` | cloud/sync-cold-r2 | `LyricsTiming.kt`, `SongCheckWindow.kt` |
| `6896b527` | cloud/sync-repeat-r2 | `LyricsTiming.kt`, `AudioDecoder.kt`, `PlaybackService.kt` |
| `92ed1c99` | prep-cancel | `NextTrackPreparation.kt`, `TrackAnalyzer.kt`, `VideoAudio.kt`, `Deepgram.kt` |
| `17408b20` | fix/ui-coverage-opus | scripts and docs only |
| `cfa1e02d` | fix/ai-not-applied-opus | `LyricsController.kt`, `LyricsTiming.kt`, `MainViewModel.kt` |
| `bdc2c33f` | cloud/ci-uncaught-leak-1103 | test only |
| `7a8c3941` | cloud/u19reg-player-1103 | `CanvasArtworkPlayer.kt`, `NowPlayingScreen.kt` |
| `79081fa4` | cloud/u19reg-hq-1103 | `QualityUpgrade.kt`, `LyricsController.kt` |
| `89feaff9` | cloud/u19reg-telegram-1103 | strings only |
| `37d78b50` | cloud/u19reg-lyrics-1103-r2 | `LyricsController.kt`, shared player/search UI |
| `75a9df7a` | cloud/u19reg-settings-1103-r2 | `SettingsSheet.kt`, `KoLyricsEntry.kt`, `MainActivity.kt` |
| `caae5418` | cloud/u19reg-startup-1103-r2 | `MainViewModel.kt` |
| `2d4bed83` | cloud/u19reg-queue-1103-r2 | `PlaybackService.kt`, `LyricsTiming.kt` |
| `4cab07c4` | cloud/u19reg-artist-1103b | `MainActivity.kt`, `InnertubeParser.kt` |
| `e2de4702` | cloud/ui-crawl-audit-1103-r3 | strings, `ReplayModel.kt`, `ReplayPoster.kt` |

## Verdict by category

| category | verdict |
|---|---|
| new periodic polling / timers | **none.** The only new `delay` is the one-shot `STARTUP_DEFER_MS` (`MainViewModel.kt:1457`), not a loop. All other added `delay`/`while` lines are JVM tests or the finite `CommittedSpanReader.read`/`ByteLru` loops. |
| work moved onto the main thread | **none.** `LyricsTiming` still reads/writes through `withContext(Dispatchers.IO)`; `VideoAudio` and `TrackAnalyzer` keep their IO/analysis dispatchers; `Deepgram` runs its call on `Dispatchers.IO`. |
| wakeups / work while paused or screen off | one real regression found and fixed below (the cold song check ran a second full pass with the screen off). Existing residual screen-off work is listed under "left" and is unchanged by these merges. |
| unbounded caches | **no new unbounded app cache in the merged lanes.** `loggedAiOtherLyrics` is capped at 3,000 (`LyricsTiming.kt:783`); `lateMeasurements`/`AnalysisCancel` hold only in-flight keys and are cleared in `finally`; `ByteLru` is byte-bounded; `CommittedSpanReader` holds one reader per committed span for the life of one decode. `MusicVideoEngine.missing` is unbounded within a session but comes from direct commit `488eab6d`, not a merged lane (noted below). |
| coroutines that outlive their screen | **none new.** Measurements still run on `measureScope` but now stop at `wanted()`; the next-prep job and its mix pass are cancelled with the bundle; the startup coroutine is `viewModelScope` and ends after one pass. |

## Fixed: screen-off cold song check decoded and transcribed twice

- Cause: `cloud/sync-cold-r2` (`0a58ffcb`) made a cold song check read a 40 s
  opening, then always follow with the 90 s opening
  (`LyricsTiming.kt:3000-3025`). The song check is allowed with the screen off
  by the 11-01 user decision, so in a pocket every cold song decoded 130 s of
  audio and made up to two Whisper requests where v1.9 made one 90 s pass.
- Fix: `SongCheckWindow.firstPassSeconds(screenOn, fullSeconds)`
  (`SongCheckWindow.kt:24-32`; used at `LyricsTiming.kt:3002-3013`) makes the first pass the full opening when the
  screen is off, and `verifySongRequest` returns after it. Screen-on keeps the
  new 40 s + 90 s refinement; screen-off is back to the single full pass with
  the same answer v1.9 stored.
- Truth table (`needsFullOpening`/`fullMayReplace` terms are untouched):

| screen | first pass | second pass |
|---|---|---|
| on | 40 s | full, when `needsFullOpening` and `fullMayReplace` allow |
| off | full | **none** |

- Test: `shared/src/jvmTest/.../SongCheckFirstPassTest.kt`
  `a screen-off first pass is the full opening, not the short one`.
  Fail-before: unresolved `firstPassSeconds` (compile failure). Pass-after:
  BUILD SUCCESSFUL.

## Left (not fixed)

| severity | file:line | observation | why left |
|---|---|---|---|
| MEDIUM | `CommittedSpanReader.kt:54` | Every committed span file is opened when a decode starts. For a rendition with many committed spans this costs one descriptor each until the decode closes them; the cache can evict the file meanwhile, which is why the open is eager. | The alternative (open on first read) reintroduces the evicted-span `no-first-sample` failure this lane fixed. Span count is normally small; no cap was added because a cap would drop readable committed bytes. |
| LOW | `MainViewModel.kt:1457` | The deferred account/update work waits 2 s with `delay`. It is a single one-shot wait, not a loop, and it is `viewModelScope`-bound. | Firing it on a real event (Home's first row) needs a new signal; the delay is bounded and costs no repeated wakeup. |
| LOW | `MusicVideoPlayer.kt:127-137` (direct commit `488eab6d`, not a merged lane) | `_missing` accumulates every video id whose picture failed for the session; cleared only on a sign-in/session change. | Strings only, and outside the merged-lane scope. |
| LOW | `LyricsTiming.kt:776-783` | `loggedAiOtherLyrics` clears at 3,000 instead of evicting; concurrent adds can momentarily exceed the cap. | Bounded diagnostic set, not audio/cache memory. |

## Existing residual battery items, unchanged by this wave

These were already listed in `docs/review-1031/battery-efficiency-r3b.md` and
none of the merged lanes touches their conditions: the analysis-head waits in
`AudioCache` (`delay(250)`/`delay(40)`/`delay(100)`), the crossfade ticker in
`Phase.IDLE`, next-track URL/version/HQ lookahead ignoring `lowPower`, the
download workers running beside analysis, and the Hq automatic pass not
checking `heavyWorkAllowed`. They are not regressions from this merge wave.

## Tests

- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` — pass.
- `./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.lyrics.SongCheckFirstPassTest'` — 4 tests, 0 failures after the fix; before it, compile failed on the unresolved `firstPassSeconds`.
- `./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.lyrics.SongCheck*'` — pass.
- `./gradlew :app:testProdReleaseUnitTest --tests '*LyricsTiming*Test' --tests '*SongCheck*Test'` — 17 classes, 67 tests, 0 failures.
- Merged-lane tests also re-run clean: `CommittedSpanReaderTest`,
  `QueueHistoryHookTest`, `LyricsTimingFilesDirRetryTest`,
  `StartupLaunchWiringTest`, `QualityUpgradeRefusalTest`,
  `PlayerMergeWiringTest`, `KoLyricsMotionTest`, `SettingsForkRegressionTest`,
  `ArtistPageVideoShelfTest`.

## Not done

- No device profiling; this is a code audit only.
- Did not touch `.github/`, keys, signing, or any of the residual issues above.