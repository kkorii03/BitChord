# playback-a 잔여 지적 검토 (1031)

기준: `task/fix-1030` 통합판 현재 트리. 지적 52건 중 같은 문제의 중복 2쌍(776/757, 394/620)을 합쳐 50행으로 정리했다.
판정 표기: **이미 고쳐짐** / **고침** / **틀림** / **남김**.

| # | 지적 | 요약 | 판정 | 근거 (현재 코드) | 처리 |
|---|---|---|---|---|---|
| 1 | 776 MED / 757 MED | AI 타이밍 버튼이 Gemini 키만 보던 문제. Deepgram-only, 한국어/무음, 3줄 미만에서도 노출 | 이미 고쳐짐 | `app/src/main/java/com/music/bitchord/ui/LyricsController.kt:688`, `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsAiGuard.kt:117`, `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LyricsTimingUi.kt:60` | `manualTimingAvailable(geminiUsable, deepgramUsable, audioAvailable, duration, title...)`로 실제 수동 경로 가능 여부를 계산하고 `aiTimingActionEnabled = adjustable && (byAi || aiAvailable)` 유지 |
| 2 | 469 LOW | 알림 반복 모드 영속화 주석이 실제 알림 버튼과 불일치 | 이미 고쳐짐 | `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:2546`, `:2596` | 주석이 “Android Auto/차량 컨트롤만, 폰 알림은 favorite/shuffle뿐”으로 정정됨. 알림에 repeat 버튼을 추가하지 않음 |
| 3 | 469 HIGH | 커버 시드 fallback이 `kept` 비면 모든 커버를 버려 station이 빔 | 이미 고쳐짐 | `app/src/main/java/com/music/bitchord/playback/Autoplay.kt:459` | 커버 시드일 때 `isKaraokeOrTributeArrangement`만 제외하고 일반 커버는 아티스트 무관 유지 |
| 4 | 298 MED | `sharesArtist` 예외가 시드 아티스트의 karaoke/tribute까지 통과 | 이미 고쳐짐 | `Autoplay.kt:459`, `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/sources/TrackMatcher.kt:500` | karaoke/tribute/instrumental 검사를 먼저 적용. 같은 아티스트 예외는 일반 커버에만 남음 |
| 5 | 416 HIGH | definitive `IO_BAD_HTTP_STATUS`가 같은 id 재해석 10초를 탐 | 이미 고쳐짐 | `PlaybackService.kt:5217` | `verdict == null && !StreamResolver.isUnplayable(mediaId)`로 `trySameVideoFirst` 제한 |
| 6 | 394 MED / 620 MED | 실패한 refill 뒤 같은 mediaId가 다시 current가 되면 pending advance가 남아 자동 스킵 | 이미 고쳐짐 | `PlaybackService.kt:1643`, `:3962`, `:4492` | timeline target 일치/불일치, `onTrackBecameCurrent`, refill `finally`에서 pending/target 해제. refill이 append하지 않으면 같은 id 재생 시에도 해제 |
| 7 | 426 MED | `copyright`/`저작권` 단독 매칭이 국가 제한 저작권 차단을 영구 불가로 오판 | 이미 고쳐짐 | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/UnplayableReason.kt:100`, `:327` | `regionScoped`가 있으면 copyright 단독 판정을 취소하고 proxy retry 유지 |
| 8 | 402 MED | `prepare()` 직후 release 대기 없이 source를 버려 cache discard 실패 | 이미 고쳐짐 | `PlaybackService.kt:5231`, `:5310`, `:5457`, `:5965`, `:8073` | 회수 경로마다 `RECOVERY_DELAY_MS`(350ms) 뒤 discard. `replayAsManifest`는 swap 전에 discard |
| 9 | 432 HIGH | `prepareNextPaid` 결과가 local `prepared`에만 들어가 free timing이 cache/log에 남음 | 이미 고쳐짐 | `app/src/main/java/com/music/bitchord/playback/NextTrackPreparation.kt:343` | paid 결과를 `syncTiming`에 다시 대입 |
| 10 | 392 MED | next-row heavy timing이 30초 paid gate 없이 `placeWithAi` 호출 | 이미 고쳐짐 | `app/src/main/java/com/music/bitchord/playback/TranslationAhead.kt:333`, `:368`, `:384` | calibration/fallback 모두 `paidGate()` 통과 뒤에만 paid 호출 |
| 11 | 515 HIGH | 403 재해석이 container를 바꾸면 MP4/WebM이 한 파일에 이어붙음 | 고침 | `app/src/main/java/com/music/bitchord/download/Downloader.kt:115` | 재해석 결과의 `downloadExtension`이 원래 stream과 다르면 길이 검사 전에 중단. `resolveForDownload`의 container 불변식 위에 명시적 방어 추가 |
| 12 | 498 MED | private 다운로드 WebM-only 계약 제거, AAC fallback/log 누락 | 이미 고쳐짐 | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/StreamResolver.kt:1587`, `:1640`, `:1657`, `:1734` | private(`requireM4a=false`)는 `downloadExtension == "webm"`/`isWebmOpus`만 수용. export만 m4a. KDoc과 코드 일치 |
| 13 | 442 MED | 만료된 proxy pin을 잊기만 해 새 rendition이 partial cache 뒤에 이어짐 | 고침 | `app/src/main/java/com/music/bitchord/playback/StreamChoice.kt:84`, `:110`, `PlaybackService.kt:2075` | 만료를 1회성으로 표시하고 resolver가 fresh resolve 전에 `AudioCache.discard(keepComplete=true)` 실행. 테스트 `StreamChoiceTest.an expired choice...` |
| 14 | 453 MED | repeat-one/중복 row에서 definitive skip이 같은 id로 무한 반복, cap 우회 | 고침 | `app/src/main/java/com/music/bitchord/playback/UnplayableRecovery.kt:41`, `PlaybackService.kt:5664` | verdict 전용 상한 `MAX_CONSECUTIVE_VERDICT_SKIPS=20` 추가. 정상적인 불가 row 연속 통과는 유지하고 동일 id loop만 정지. 테스트 `UnplayableRecoveryTest.a definitive run is bounded...` |
| 15 | 464 MED | `analysisHeads`가 clear된 뒤 in-flight round가 claim만 남김 | 이미 고쳐짐 | `app/src/main/java/com/music/bitchord/playback/AudioCache.kt:2827`, `app/src/main/java/com/music/bitchord/playback/AnalysisHeadRetry.kt:57` | `analysisHeadMayClaimOrRetry`가 `inFlight`를 claim 조건에 포함. `add` 실패 시 claim 제거 |
| 16 | 419 MED | `clearPartialHead`가 complete와 live-reader lock을 구분 못 해 claim 방치 | 이미 고쳐짐 | `AudioCache.kt:2948`, `AnalysisHeadRetry.kt:85` | `AnalysisHeadClear` enum으로 Proceed/AlreadyComplete/HeldElsewhere 구분. HeldElsewhere는 reader release 때 재시도 |
| 17 | 398 MED | skip 시 standby volume을 0f로 즉시 설정해 click | 이미 고쳐짐 | `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt:978`, `:2331` | `bailSilenceLeaving` + `CrossfadeGain.skipSilence(...SKIP_SILENCE_MS)`로 45ms ramp |
| 18 | 481 MED | `NO_SOURCE` 미발생 + transient pending 429가 영구 재다운로드 | 이미 고쳐짐 | `app/src/main/java/com/music/bitchord/playback/smart/HqAudioVerify.kt:108`, `:563`, `:666` | `PendingRateLimit.transientAttempts`와 `MAX_PENDING_TRANSIENT_ATTEMPTS`로 bounded settle |
| 19 | 409 HIGH | mid-title OST 분기가 take까지 통째로 삭제 | 이미 고쳐짐 | `TrackMatcher.kt:1114`, `:1126`, `:1206` | suffix를 먼저 분류하고 `keptSoundtrackTakeWords`로 Live/Remix/언어 take 보존 |
| 20 | 499 MED | `classify`가 언어 단어 포함 segment를 모두 언어 버전으로 만듦 | 이미 고쳐짐 | `TrackMatcher.kt:1553`, `:1588` | `isLanguageVersion(segment)`일 때만 언어 버전. “Korean Drama OST”/“English Translation”은 context |
| 21 | 436 MED | 언어 버전 분기가 translation pair 뒤라 Hangul 제목이 alt-core가 됨 | 이미 고쳐짐 | `TrackMatcher.kt:1369` | `isLanguageVersion(tail)`를 `isTranslationPair`보다 먼저 검사 |
| 22 | 413 MED | CJK core가 raw `text`에서 만들어져 `Live) Version` 잔여 | 이미 고쳐짐 | `TrackMatcher.kt:1419`, `:1449` | `droppedTail`만큼 `coreText`에서도 trailing token 제거 후 CJK core 생성 |
| 23 | 436 MED | 일부 query 실패에도 원본 row를 “no version”으로 확정 | 이미 고쳐짐 | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/YtMusicRepository.kt:960` | `audioSwitchSearchIsConclusive(queriesRun, failedQueries, catalogueFailed)`가 모두 성공일 때만 cache |
| 24 | 455 HIGH | active same-row job이 queueKey보다 먼저 반환, attempted/lyrics가 rowId 키 | 이미 고쳐짐 | `NextTrackPreparation.kt:114`, `shared/src/commonMain/kotlin/com/music/bitchord/playback/NextPrepPolicy.kt:225`, `NextTrackPreparation.kt:196` | `alreadyHandled`가 rowId+queueKey를 비교하고 `stateKey(queueKey, videoId)`로 attempted/lyrics 키 변경 |
| 25 | 441 MED | RECHECK override가 fromFile이라 `timed()`가 거부 | 이미 고쳐짐 | `NextTrackPreparation.kt:834`, `app/src/main/java/com/music/bitchord/playback/LocalTimingPolicy.kt` | `downloadedTimingTimeable(..., recheckOverride=found.recheckOverride, ...)` 사용 |
| 26 | 369 MED | 패널 재-open이 LaunchedEffect를 다시 돌리지 않아 transient miss 재시도 불가 | 이미 고쳐짐 | `app/src/main/java/com/music/bitchord/ui/LyricsController.kt:450`, `app/src/main/java/com/music/bitchord/MainActivity.kt:3109` | `retryLyricsIfMissed()`를 panel open 이벤트에 연결. found/running이면 no-op |
| 27 | 448 HIGH | `own.match`만 보고 fitted 없는 refused answer를 verification으로 기록 | 이미 고쳐짐 | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/DownloadedSyncCheck.kt:126`, `LyricsTiming.kt:2373` | `ownApplied = own.fitted != null`; refusal은 match가 있어도 answer/version으로 기록하지 않음 |
| 28 | 334 HIGH | lock-screen/notification/car timing 경로에 substitute guard 누락 | 이미 고쳐짐 | `PlaybackService.kt:10973` | `StreamChoice.isSubstitute(currentSong.videoId)`를 timing base/measure에 동일 적용 |
| 29 | 295 MED | `NextPrepCache`가 substitute 여부를 키에 포함하지 않음 | 이미 고쳐짐 | `NextTrackPreparation.kt:935`, `:10890` | `Ready.substituted`를 저장/비교하고 현재 substitute 상태로 lookup |
| 30 | 269 MED | `resetSongCheck`가 `songSyncGate`를 잡지 않아 in-flight recheck가 old file answer를 씀 | 이미 고쳐짐 | `LyricsTiming.kt:1861`, `:2130`, `:2316` | reset과 `recheckDownloaded`가 같은 Mutex + `songSyncGeneration` 검증 |
| 31 | 324 MED | 8초 재측정 loop와 250ms `awaitVideoOpening` polling | 이미 고쳐짐 | `app/src/main/java/com/music/bitchord/ui/LyricsController.kt:953`, `LyricsTiming.kt:1096`, `:1126` | `AudioCache.served`/`awaitRenditionPrefix` 이벤트 대기로 교체. wall-clock/시도 횟수 bound 유지 |
| 32 | 325 MED | skip 경로에 `lyrics-sync:` 이유 로그 누락 | 고침 | `LyricsTiming.kt:1673`, `:1898`, 기존 `:2263`-`:2316` | rate-limit로 skip되는 song-check/re-check에도 이유 로그 추가. 나머지 guard/engine/switch 경로는 이미 로그 보유 |
| 33 | 457 MED | 성공한 빈 radio 응답을 실패처럼 shortcut해 normal path를 건너뜀 | 이미 고쳐짐 | `Autoplay.kt:296`, `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/videoradio/VideoRadioPolicy.kt:496` | PARTIAL shortcut은 `fallback.isFailure`일 때만. 성공한 빈 목록은 normal path 진행 |
| 34 | 441 MED | normal path가 0행이면 유효 video row 1-2개도 버림 | 이미 고쳐짐 | `Autoplay.kt:332`, `VideoRadioPolicy.kt:507` | normalRows=0 && picked>0이면 `KEEP_PICK` → `videoRadioRows` 반환 |
| 35 | 468 MED | party `queueAdd`가 일부만 수락해도 모든 id를 suggested로 기록 | 이미 고쳐짐 | `PlaybackService.kt:4037`, `shared/src/commonMain/kotlin/com/music/bitchord/data/listentogether/PartyQueueReading.kt` | `partyTopUpLanded(before, suggestions, after)`가 실제 echo된 prefix/occurrence만 반환 |
| 36 | 429 MED | repeat-one 탈출 시 `autoplaySeed`가 남아 top-up skip | 이미 고쳐짐 | `PlaybackService.kt:1624`, `Autoplay.kt:119` | `repeatOneSeedShouldClear`일 때 job cancel + `autoplaySeed=null` 후 load |
| 37 | 398 MED | `keepTrackSpecific`가 region/bot reason을 timeout으로 덮어씀 | 이미 고쳐짐 | `UnplayableReason.kt:306`, `StreamResolver.kt:1637` | `keepProxyWorthy`를 별도로 보존하고 proxy/bot 판단에 사용 |
| 38 | 461 MED | ALL-scope URL이 완료 시점 route/scope로 stamp됨 | 이미 고쳐짐 | `StreamResolver.kt:535`, `:546` | walk 시작 시 `mintedGeneration`/`onAllScope`를 capture해 `ResolvedWalk`/`remember`에 전달 |
| 39 | 415 MED | 429가 resolver `recent`만 지우고 앱 pin은 남김 | 이미 고쳐짐 | `StreamResolver.kt:2000`, `:2021`, `app/src/main/java/com/music/bitchord/data/innertube/AndroidStreamHooks.kt:58` | `rateLimitedListener`가 `StreamChoice.forget`/`QualityUpgrade.forget`/`forgetUrl`까지 수행 |
| 40 | 544 MED | queue edit 전에 queued `savePlaybackState`가 새 snapshot을 덮음 | 이미 고쳐짐 | `app/src/main/java/com/music/bitchord/playback/LastPlayed.kt:60`, `:138`, `PlaybackService.kt:8989` | snapshot마다 `queueGeneration++`, state write는 capture한 generation이 다르면 skip |
| 41 | 451 MED | `syncValue`가 `nextPrepLyrics`를 못 받아 `switch-off` 도달 불가 | 이미 고쳐짐 | `shared/src/commonMain/kotlin/com/music/bitchord/playback/NextPrepPolicy.kt:148`, `NextTrackPreparation.kt:496` | `SyncFacts.lyricsOff = !switches.nextPrepLyrics`; `synced-off`보다 먼저 `lyrics-off` |
| 42 | 413 MED | screen-on 대기 뒤 `giveUpAt`을 재검사하지 않음 | 이미 고쳐짐 | `LyricsController.kt:1392` | `lyricsMeasurementAllowed`가 매번 `nowMs < giveUpAt` 재검사 |
| 43 | 321 HIGH | network capabilities null(비행기 모드)을 online으로 판정 | 이미 고쳐짐 | `AudioCache.kt:676`, `:3437` | `offlineFromCapabilities(hasCapabilities=false, ...)`가 offline 반환 |
| 44 | 443 MED | `SocketTimeoutException`이 `InterruptedIOException`이라 실패 transfer가 성공 처리 | 이미 고쳐짐 | `app/src/main/java/com/music/bitchord/playback/ChunkedDataSource.kt:32` | `isTransferCancellation = e is InterruptedIOException && e !is SocketTimeoutException` |
| 45 | 675 HIGH | `markerSeed`가 markerless/다른 커버 단어 seed에서 일반 커버를 버림 | 이미 고쳐짐 | `Autoplay.kt:459` | `originalTitle` marker 텍스트 대신 karaoke/tribute만 제외 |
| 46 | 408 MED | 커버 테스트가 실제 production seed 모양이 아님 | 이미 고쳐짐 | `app/src/test/java/com/music/bitchord/AutoplayCoverSeedTest.kt:47` | “아이유 · 싱어 커버” byline + markerless upload title + English/Korean cover row 회귀 테스트 추가 |
| 47 | 473 MED | `played>=30s` early return이 end-20s/remaining<=waitMs를 무시 | 이미 고쳐짐 | `PlaybackService.kt:10741`, `:10746` | `canStartStep(remaining)` 선검사, `remaining <= waitMs`면 paid 대기 취소 |
| 48 | 427 LOW | calibration sample을 `paidGate` 전에 claim | 이미 고쳐짐 | `TranslationAhead.kt:333` | gate 성공 뒤 `claimCalibrationSample` |
| 49 | 560 HIGH | OST 무크레딧 row가 uploader artist로 남아 catalogue row 거부 | 이미 고쳐짐 | `TrackMatcher.kt:1017`, `:1034`, `shared/src/jvmTest/kotlin/com/music/bitchord/data/sources/AudioSwitchOstTitleTest.kt:248` | `noPerformer`일 때 `bestSameArtist`가 title/work로 허용. “Following Sophie | Joker OST” match 테스트 존재 |
| 50 | 495 MED | `audioSwitchQueries`가 `titleVariants(...).first()`만 사용 | 이미 고쳐짐 | `TrackMatcher.kt:1034`, `:113` | `queries(targetOf(song))`로 통합해 alt-script/translation query 유지 |

## 검증

- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` 통과.
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.StreamChoiceTest' --tests 'com.music.bitchord.playback.UnplayableRecoveryTest' --console=plain -q` 통과.
- `./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.sources.AudioSwitchOstTitleTest' --tests 'com.music.bitchord.data.sources.TrackMatcher*' --tests 'com.music.bitchord.data.lyrics.DownloadedSyncCheck*' --tests 'com.music.bitchord.data.lyrics.LyricsAiGuardTest' --tests 'com.music.bitchord.data.videoradio.VideoRadioPolicyTest' --tests 'com.music.bitchord.playback.NextPrepPolicyTest' --tests 'com.music.bitchord.playback.NextPrepSkipReasonTest' --console=plain -q` 통과.

## 마지막 10줄 요약

1. 지적 수: 52건(중복 2쌍 통합 50행).
2. 이미 고쳐짐: 48건.
3. 고침: 4건(442 StreamChoice stale pin, 453 verdict skip cap, 515 downloader container guard, 325 lyrics-sync skip log).
4. 틀림: 0건.
5. 남김: 0건 — 모든 지적을 현재 코드에서 판정하고 처리함.
6. 변경 파일: `StreamChoice.kt`, `PlaybackService.kt`, `UnplayableRecovery.kt`, `Downloader.kt`, `LyricsTiming.kt`, `StreamChoiceTest.kt`, `UnplayableRecoveryTest.kt`, 이 문서.
7. 442: 만료 pin은 cache partial discard 후 fresh resolve.
8. 453: definitive skip은 20회 상한, 정상 불가 row 통과는 유지.
9. 515: 재해석 container 변경 시 명시적 abort.
10. 325: rate-limit skip 두 곳에 `lyrics-sync:` 이유 로그 추가.
