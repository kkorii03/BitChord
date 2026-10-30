# playback-b 잔여 지적 검토 (2026-10-31)

기준: `task/fix-1030` 통합판. 지적 50건을 현재 코드에서 재확인했다. 같은 파일의 서로 다른 증상은 별도 행으로 남겼다.

| # | 지적 요약 | 판정 | 근거 파일:줄 | 처리 |
|---|---|---|---|---|
| 481 | `parseWorkTitle`이 번역쌍 dash를 credit으로 오인 | 이미 고쳐짐 | `TrackMatcher.kt:1368` (`isTranslationPair`) | 현재 파서는 dash 루프에서 번역쌍을 먼저 보존한다. |
| 464 | 파이프 선분리가 비작품 tail을 버림 | 이미 고쳐짐 | `TrackMatcher.kt:1067` | `PIPE_TAIL`은 tail에 OST/Soundtrack 단어가 있을 때만 제거한다. |
| 471 | `Live at Wembley`류 tail을 byline으로 뒤집음 | 고침 | `TrackMatcher.kt:1000,1368,1253` | tail이 take 단어로 시작하면 역전을 거부하고 head를 제목으로 유지. 회귀 테스트 추가. |
| 587 | work-only 타깃이 업로더를 artist로 유지해 artist mismatch | 남김 | `TrackMatcher.kt:1016-1032` | 실제 공연자와 업로더를 안전하게 구분할 근거가 없어 same-artist 규칙을 약화하지 않았다. 후속 작업 필요. |
| 383 | `TrackAnalyzer.results`가 세션 내내 증가 | 고침 | `smart/TrackAnalyzer.kt:132,556-568` | 256개 상한과 LRU 유사 축출 추가. running 트랙은 제외. |
| 632/648/697 | AI 타이밍 버튼이 시작 시점 준비도로 영구 숨김 | 고침 | `ui/LyricsController.kt:748,778-786`, `AudioCache.kt:770-797` | 완료 이벤트 구독은 기존에 있었고, 구독 시작 직후 현재 준비도를 한 번 재계산해 경합도 막았다. |
| 536 | VideoAudio 준비도가 `wav`의 7분/10초 cap을 반영 안 함 | 남김 | `smart/VideoAudio.kt:108-155` | 선택 복사본 완료 여부는 일치시키고 있다. 길이 cap은 렌디션에 길이 정보가 없어 이번 범위에서 안전하게 판정할 수 없었다. |
| 397 | `songSyncGate`가 reset 전 캡처 답을 덮어씀 | 이미 고쳐짐 | `smart/LyricsTiming.kt:2248-2305` | `startedGeneration` 비교로 대체된 파일의 답을 버린다. |
| 383(2) | 다운로드/로컬 비디오 재측정이 `served` 이벤트를 못 받음 | 이미 고쳐짐 | `ui/LyricsController.kt:950-966`, `LocalTimingPolicy.kt:65` | 로컬 파일은 `measureWaitsForStream=false`라 15분 대기 루프에 들어가지 않는다. |
| 670 | `HeldElsewhere`에서 claim을 버려 4Hz 재시도 | 이미 고쳐짐 | `AudioCache.kt:2828-2845` | claim을 유지하고 reader 해제 이벤트에서 한 번만 재시도한다. |
| 607 | skip ramp가 bail tick보다 짧아 클릭 | 이미 고쳐짐 | `CrossfadeController.kt:3704-3709` | `SKIP_SILENCE_MS=45ms` > `BAIL_STEP_MS=15ms`. |
| 453 | `open()`의 pre-release가 분석 HEAD를 재시작 | 고침 | `AudioCache.kt:1468-1475,1517-1531` | `releaseHeld(allowRetry=false)`로 터미널 release에서만 재시도. |
| 418 | rate-limited passthrough가 호스트를 두 번 벌점 | 고침 | `playback/ChunkedDataSource.kt:136-144` | rate-limit이면 로컬 `noteFailure`를 생략. |
| 400 | openChunk 초기 실패가 기록되지 않음 | 이미 고쳐짐 | `ChunkedDataSource.kt:199-209` | 실패 지점에서 `failureNoted` 가드로 기록한다. |
| 390 | `fallback=normal` refill이 D3에서 두 번 집계 | 고침 | `scripts/featurehealth.py:1030-1044` | deferred video line과 append를 pair로 차감. 테스트 기대값 3/2로 수정. |
| 468 | MediaSessionArtwork grant 캐시가 영구적 | 고침 | `playback/MediaSessionArtwork.kt:108-112,150-157` | `apply()`마다 재부여(`always=true`). |
| 535 | 위젯 좋아요가 계정 전환 후 남음 | 고침 | `widget/MediaWidgetSnapshot.kt:130-139`, `ui/MainViewModel.kt:3930-3936,4012-4018` | 로그아웃/계정 clear에서 persisted like를 지운다. |
| 71 | PartySync:207 (설명 없음) | 판정 불가 | `playback/PartySync.kt:207` | 지적 본문이 없어 재현 조건을 특정할 수 없다. |
| 412 | malformed duration이 호환으로 통과 | 고침 | `data/CatalogSongMapper.kt:580-597` | 공백은 미상, 비공백 파싱 실패는 fail-closed. 테스트 추가. |
| 355 | corrupt currentRate를 raw position으로 사용 | 고침 | `playback/HqSwapPolicy.kt:79-84` | `bad-current-rate`로 거부. 테스트 갱신/추가. |
| 518 | radio 실패 시 normal(로컬/캐시) 경로를 건너뜀 | 고침 | `playback/Autoplay.kt:292-326` | 빈 radio로 normal 경로를 먼저 시도하고 모두 비면 기존 실패 처리. |
| 499 | lyricsSettings를 캐시 기록 시점에 샘플 | 이미 고쳐짐 | `NextTrackPreparation.kt:169-180,222` | run 시작 snapshot의 `lyricsSettings`가 stateKey/lyricsByRow에 함께 쓰인다. |
| 557 | `rememberServed`가 ALL URL stamp를 잃음 | 이미 고쳐짐 | `StreamResolver.kt:2390-2406` | `ProxyPlayback.generationFor(url)`로 registered stamp를 복원. |
| 438 | coalesced joiner가 새 route로 stamp | 이미 고쳐짐 | `StreamResolver.kt:530-550` | walk 내부에서 stamp를 한 번 계산해 Deferred가 공유. |
| 393 | walk 중 ALL bypass 진입 시 proxy로 오표기 | 이미 고쳐짐 | `StreamResolver.kt:547`, `ProxyPlayback.kt:149` | 시작/종료 모두 ALL일 때만 proxy-minted. |
| 407 | shuffle 토글이 수동 드래그 순서를 지움 | 이미 고쳐짐 | `QueueWindow.kt:533-557` | `preShuffleOrder`로 복원. |
| 567 | repeat-all 경계의 next-cycle row가 reorder에서 누락 | 이미 고쳐짐 | `PlaybackService.kt:8925-8932` | active/planned cycle을 나눠 `reorderPlannedCycle`까지 적용. |
| 512 | 재생 중 CONTEXT 삭제가 저장되지 않음 | 이미 고쳐짐 | `QueueWindow.kt:581-592`, `PlaybackService.kt:11696-11725` | `pendingExcluded`로 커서 이동 후 적용하고 현재 행도 보고. |
| 459 | OST tail의 언어 take가 사라짐 | 이미 고쳐짐 | `TrackMatcher.kt:1205-1216` | `keptSoundtrackTakeWords`가 언어 take를 유지. |
| 547 | 언어 버전 + packaging이 version으로 오분류 | 이미 고쳐짐 | `TrackMatcher.kt:1596-1603` | `isLanguageVersion`이 NOISE/package 단어를 허용. |
| 524 | `Our Soundtrack`/`My OST`의 bare tail 제거 | 이미 고쳐짐 | `YouTubeDisplayCleaner.kt:156-162`, `TrackMatcher.kt:1143-1152` | display 경로는 work/labelPackaged를 요구하고, bare tail은 작품명이 있을 때만 제거. |
| 709 | party autoplay target null일 때 다음 항목으로 안 넘어감 | 고침 | `PlaybackService.kt:1664-1685` | party의 실제 next와 일치할 때만 채택해 seek/prepare. |
| 618 | 현재 트랙의 lossless prefetch를 다음 행 스케줄이 취소 | 고침 | `AudioCache.kt:2240-2246` | `mediaId == currentMediaId`면 취소하지 않음. |
| 354 | recoverMidPlay 성공 후에도 failed로 기록 | 고침 | `ChunkedDataSource.kt:180-210` | 복구 성공 시 `transferFailed`를 세우지 않고 return. |
| 533 | audioSwitch가 bare label에서도 soundtrack 경로 진입 | 고침 | `TrackMatcher.kt:946-957` | work가 없고 dash/pipe label도 아니면 soundtrack 경로를 쓰지 않음. |
| 519 | `SOUNDTRACK_WORK_TAIL`이 Lost/Ghost/Post의 `ost`를 먹음 | 고침 | `TrackMatcher.kt:2037-2040` | 라벨 앞에 `(?<!\p{L})` 경계 추가. Lost/Ghost/Post 테스트 추가. |
| 701 | repeat-all 경계의 next-cycle 동일 행이 남아 재생 | 이미 고쳐짐 | `PlaybackService.kt:8830-8870` | `pruneExcludedBoundedRows`가 upcoming/current blocked 행을 제거. |
| 539 | `reorderPlannedCycle`이 이후 planned cycle을 안 지움 | 이미 고쳐짐 | `QueueWindow.kt:646-652` | `cycleId`보다 큰 plannedCycles를 제거. |
| 749 | ALL scope를 walk 시작/끝으로만 추론 | 남김 | `StreamResolver.kt:533-553`, `ProxyPlayback.kt:149` | 실제 mint 경로를 `resolveUncached`가 반환하도록 바꾸는 큰 변경이 필요해 이번 정리 범위에서 남겼다. |
| 444 | settingsKey에 syncedLyrics 누락 | 고침 | `NextTrackPreparation.kt:947-962,169-176` | `syncedLyrics`와 next-prep 안전 스위치를 identity에 추가. |
| 93 | TrackMatcher:1123 (설명 없음) | 판정 불가 | `TrackMatcher.kt:1123` | 지적 본문이 없어 특정 동작을 판정할 수 없다. |
| 590 | AI 준비도 테스트가 실제 이벤트 배선을 검증하지 않음 | 남김 | `app/src/test/.../VideoAudioTimingReadyTest.kt`, `shared/.../LyricsAiGuardTest.kt` | 순수 함수 테스트만 있어 이벤트/리스너 경로 테스트 추가가 필요하다. |
| 433 | VideoAudio 준비도 HIGH 수정의 앱 테스트 없음 | 남김 | `smart/VideoAudio.kt:108-155` | Android 캐시 없이 검증할 seam이 부족해 후속 테스트 과제로 남김. |
| 531 | 다운로드 alias에 원본 channelId를 붙임 | 고침 | `SongVersionController.kt:731-737` | alias가 원본과 같을 때만 channelId 유지. |
| 579 | 빈 큐에서 `coerceIn(0,-1)` 예외 | 고침 | `PlaybackService.kt:8769` | `mediaItemCount == 0`이면 snapshot 저장을 건너뜀. |
| 536(2) | 비셔플 수동 순서가 이후 cycle에서 canonical로 복귀 | 고침 | `QueueWindow.kt:737-755` | 수동 순서의 이후 cycle을 이전 permutation에서 파생. 테스트 추가. |
| 462 | VideoRadio 업로드가 재생 중 카탈로그 FLAC으로 교체됨 | 고침 | `HqSwapPolicy.kt:74-77`, `PlaybackService.kt:6170-6185` | `videoRadio`/`isVideo`를 결정에 전달하고 `video-radio`로 거부. 테스트 추가. |
| 623 | next-prep 예산이 pre-resolve 시간을 소모 | 고침 | `PrefetchController.kt:236-243` | 정의적 verdict 이후 전체 검색 예산을 사용. |

## 남긴 항목
- 587(TrackMatcher work-only): 현재 테스트가 요구하는 "실제 공연자는 유지"와 리뷰의 "업로더 무시"를 동시에 만족하려면 work/album 기반 별도 매칭 규칙이 필요하다. same-artist 안전 규칙을 약화하지 않기 위해 남겼다.
- 536(VideoAudio 7분/10초): 렌디션 메타데이터에 재생 길이가 없어 준비도에서 안전하게 판정할 수 없다.
- 749: 실제 mint route 반환 리팩터링 필요.
- 433/590: 이벤트 배선을 실제로 구동하는 테스트 seam이 아직 없다.
- 71, 93: 지적 본문이 비어 있어 판정 불가.
