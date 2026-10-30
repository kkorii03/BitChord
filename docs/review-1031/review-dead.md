# review-dead — 있지만 작동하지 않는 기능 / 작동하지만 진입점이 없는 기능

- 점검일: 2026-10-31 (러너 기준 2026-10-30)
- 기준: 현재 워크스페이스 HEAD `faf6558` (얕은 클론이라 `7d956f21..HEAD` 이력은 없다. 업스트림/포크 구분은 기존 감사 문서와 현재 파일의 주석으로만 판단했다.)
- 범위: `app/`, `shared/`, `sharedUi/`의 UI 진입점, 설정 플래그, 재생 준비·자동 재생·HQ·가사 경로.
- 방법: `grep`으로 호출부를 확인하고 해당 함수만 읽었다. 코드 근거 없는 추측은 쓰지 않았다. 재생 핵심 경로(곡 해석·노래 버전·가사 싱크 계산·크로스페이드)는 수정하지 않았다.
- 이번에 고친 것: `Autoplay` 커버 시드 0곡 폴백(3차에서 마커 텍스트 대신 배열 표식 기준으로 수정), `PlaybackService.onDestroy` 수면 타이머 정리, Replay 장르 차트 탭(2차에서 검색 소스·mood 상태 정리 추가). 나머지는 문서만.

---

## 1. 이전 감사 문서 대조표

| 이전 문서·항목 | 현재 근거 | 상태 |
|---|---|---|
| `audit-playback` 1 — 긴 Retry-After가 로더를 무기한 멈춤 | `shared/.../net/HqRetryPolicy.kt:45` `MAX_WAIT_MS=8s`, `:72` tooLong 분기 | 고쳐짐 |
| `audit-playback` 2 — 휴대폰 `setWakeMode` 없음 | `PlaybackService.kt:4006,4027` | 고쳐짐 |
| `audit-playback` 3 — 잘린 스트림을 EOF로 보고 | `ChunkedDataSource.kt:408` `IOException("truncated stream...")` | 고쳐짐 |
| `audit-playback` 4 — 프록시 라우트 세대 무효화 없음 | `ProxyPlayback.kt:74-79,224,243`, `StreamResolver.kt:287` | 고쳐짐 |
| `audit-playback` 5 — 모듈 우선 해석만 120초 상한 밖 | `PlaybackService.kt:2041` `withTimeout(RESOLVE_TIMEOUT_MS)` | 고쳐짐 |
| `audit-playback` 6 — 삭제된 로컬 파일에 3회 재시도 | `PlaybackService.kt:4610` `isPermanent`에 `FileNotFoundException` 없음. `:4745`의 `FILE_NOT_FOUND` 분기는 Media3 재시도 뒤다 | 남음(부분) |
| `audit-playback` 8 — 실패한 전송도 `ok=true` | `ChunkedDataSource.kt:431` 여전히 `ok = true` | 남음 |
| `audit-playback` 9 — 서비스 파괴 후 수면 타이머 잔존 | 이번에 `PlaybackService.kt:9955`에 `SleepTimer.cancel()` 추가 | 고쳐짐 |
| `audit-playback` 10 — 크로스페이드 IDLE 250ms/1s ticker | `CrossfadeController.kt:896-902,3602,3609` | 남음 |
| `audit-playback` 11 — 화면 꺼짐 선반입 | 저전력은 `BatteryPolicy.cachePrefetchBytesAllowed`로 막았고, 화면 꺼짐은 의도적으로 허용 | 부분 고쳐짐 |
| `audit-playback` 12 — 첫 소리 120초 상한·진행 표시 없음 | `PlaybackService.kt` `RESOLVE_TIMEOUT_MS=120_000L` | 남음 |
| `audit-playback` 13 — `onDestroy` 메인 2초 대기 | `PlaybackService.kt:9994` `awaitTermination(2, SECONDS)` | 남음 |
| `audit-playback` 14 — 429/폴백 복구 중복 가드 없음 | `PlaybackService.kt:5402` `alternateLookups`만 존재 | 남음 |
| `audit-song` 1 — `typeUnknown` 행 버전 조회 생략 | `YtMusicRepository.kt:732` `wantsSongVersion(isVideo,typeUnknown)` | 고쳐짐 |
| `audit-song` 2 — 피아노·Inst·MR·오르골 어레인지 통과 | `TrackMatcher.kt:488-518`에 `8bit`, `lullaby`, `video game`, `커버`는 있으나 맨 `piano`, `inst`, `mr`, `오르골`, `music box`, `orchestra` 없음 | 남음(부분) |
| `audit-song` 3 — 앨범 단위 live/acoustic/remix 무시 | `TrackMatcher.kt:434-435,751-752` `NOVOCAL_TAKES`+요청 버전만 반영. `PERFORMANCE_TAKES` 없음 | 남음 |
| `audit-song` 4 — 다운로드 영상이 앨범 폴백을 못 씀 | `CatalogSongMapper.kt:180-185` `allowLocalVideo` | 고쳐짐 |
| `audit-song` 5 — 파일 길이 null이면 영상 길이로 대체 | `Downloads.kt:938-939` `localDurationMs(...) ?: durations.maxOf { it.second }` | 남음 |
| `audit-song` 6 — MV 재다운로드가 `isVideo`를 잃음 | `Downloads.kt:1030` suspect에 `typeUnknown=true`, `:1978` 재조회 | 고쳐짐 |
| `audit-song` 7 — 세션 변경에도 “버전 없음” 고정 | `YtMusicRepository.kt`에 `songVersionMissing.clear()` 없음. `StreamResolver.onSessionChanged`는 자기 캐시만 지움 | 남음 |
| `audit-song` 9 — 대상이 영상이 아니어도 90초 드리프트 | `TrackMatcher.kt:772` `allowVideoDrift = creditedArtist != null` | 남음 |
| `audit-song` 13 — counterpart 페어링 dead code | `officialVideo`는 프로덕션 호출부가 `CanvasRepository`의 검색 변형뿐이고, `parseCounterpart`는 테스트만 호출 | 남음(문서) |
| `audit-song` 15 — MV 복구 Work REPLACE | `MvSoundRedownload.kt:111` `APPEND_OR_REPLACE` | 고쳐짐 |
| `check-flow` 2·3 — NextPrep이 `rowId`만 키로 씀 | `NextTrackPreparation.kt:71,140,149` `attempted`/`lyricsByRow`가 rowId 키. queueKey 변경 시 `NextPrepCache`만 clear | 남음 |
| `check-hq-chain` 4 — 영구 거절 TTL/수동 우회 없음 | `HqVerificationStore.kt:141-157` 거절은 키 집합뿐. `HqVerifyPolicy.cooldownRemaining`/`shouldRetryMissingReference`는 테스트만 호출 | 남음 |
| `check-hq-chain` 7 — 다운로드 배지가 등급을 잃음 | `Downloads.kt:1489,1510` `hqTier` 저장, `DownloadedQuality.tierOf(badge, storedHqTier)` | 고쳐짐 |
| `check-mv-paths` 1 — 다운로드 행이 영상 표식을 잃음 | `Downloads.kt:658` `typeUnknown = meta.typeUnknown`, `:2731` 메타 필드 | 고쳐짐 |
| `check-sync-regress` 1 — NextPrep rowId 캐시 | 위 `check-flow` 2·3과 같은 코드 | 남음 |
| `check-sync-regress` 5 — `resetSongCheck`가 version/override를 남김 | `LyricsTiming.kt:1862-1885` `syncCheckVersion=0`, `removeRecheck` | 고쳐짐 |
| `check-sync-regress` 8 — 답 없는 재검사를 version=1로 기록 | `DownloadedSyncCheck.kt:117-122` 모든 분기 `recordVersion=true` | 남음 |
| `check-crash` 1 — NextPrep이 메인에서 `bestTier` 파싱 | `NextTrackPreparation.kt:416-424` `existingHq`가 `HqVerificationStore.bestTier`를 그대로 호출, `run`은 Main scope | 남음 |
| `check-crash` 3 — 설정 파일 무제한 읽기 | `SettingsTransfer.kt:127,292` `MAX_IMPORT_BYTES=1MB` | 고쳐짐 |
| `check-data` 1 — `lyricsForPrep`이 override 무시 | `TranslationAhead.kt:137-151` 파일 가사를 먼저 반환, `:186`의 override 경로를 타지 않음 | 남음 |
| `check-data` 2 — 숫자 범위 미검증 | `SettingsTransfer.kt:461-470` `normalize`가 INT/FLOAT 범위·유한값 검증 | 고쳐짐 |
| `check-data` 3 — `hqOffsetMs`/`hqRate` 미사용 | `Downloads.kt:2738-2744`에 저장만 하고 `playback`/`ui`에서 읽는 곳 0 | 남음 |
| `check-data` 4 — HQ 자동 prefetch가 Wi-Fi 전용 우회 | `HqLibraryUpgrade.kt:54,601,659` `hqPassAllowsMeteredData(manualPass)` | 고쳐짐 |
| `check-net` 1 — Deepgram 실패를 검사 완료로 기록 | `DownloadedSyncCheck.kt:117-122` 위와 같음 | 남음 |
| `check-net` 2 — 잘린 profile을 영구 거절 | `HqAudioVerify.kt:421,423,814` reference·candidate 모두 `completeEnough` | 고쳐짐 |
| `check-net` 5 — 실패한 HQ 교체의 `hqOverrides` 잔존 | `Downloads.kt:2597-2600` `fail()`이 `hqOverrides`를 지우지 않음 | 남음 |
| `check-net` 6 — HQ 실패 무기억 재시도 | `HqLibraryUpgrade.kt` `failures`/`HqLibraryAutoRetryPolicy` | 고쳐짐 |
| `audit-settings-ui` HIGH 1·2, MED 3건, 속도 재설정, 취침 타이머 | `AppSettings.kt:2928-3006`, `HqLibraryUpgrade.kt:54`, `SettingsTransfer.kt:127`, `SongActionsSheet.kt:934-935` | 고쳐짐 |
| `audit-battery` H1·H2·H3 | `BatteryPolicy.automixAnalysisActive`, `cachePrefetchBytesAllowed`, `MvSoundRedownload.APPEND_OR_REPLACE` | 고쳐짐 |
| `audit-lyrics-sync` HIGH 4건 | 해당 문서의 수정 목록과 현재 코드(`FileLyricsOrigin.knownVideoId`, `resetSongCheck`, `videoTimingMayRun`, AI 파일 키) 일치 | 고쳐짐 |

---

## 2. 새로 찾은 문제

| 심각도 | 파일:줄 | 증거 | 사용자 영향 | 수정안 |
|---|---|---|---|---|
| HIGH | `app/.../data/settings/AppSettings.kt:526-528,1314-1316`; `shared/.../playback/SyncSafetyPolicy.kt:39-40`; `app/.../playback/NextTrackPreparation.kt:182-187` | 세 스위치 기본값 false. `nextPrepSteps`가 false면 `LYRICS`·`SYNC`를 통째로 제거 | “다음 곡 가사 준비”가 기본 상태에서 가사·싱크를 아예 준비하지 않는다. 사용자가 개발자 옵션을 켜기 전까지 next-prep의 절반이 죽어 있다. | 안전 기본값이라 이번에는 바꾸지 않음. 최소한 `sync=skipped (next-prep lyrics off)`처럼 이유를 로그에 남긴다. |
| HIGH | `AppSettings.kt:526-528`; `app/.../playback/smart/LyricsTiming.kt:1952-1956`; `app/.../data/lyrics/LyricsOverrideStore.kt:21-26,37-44` | `recheckAllowed`·`overrideRead/WriteAllowed`가 기본 false | 다운로드 파일의 가사 재검증과 저장된 override 읽기가 기본적으로 전부 no-op. 재검증 기능이 있어도 사용자에게는 없는 것과 같다. | 안전 기본값 유지. 설정 UI에서 “꺼져 있어 동작하지 않음”을 명시하고, 스킵 시 로그를 남긴다. |
| HIGH | `AppSettings.kt:526-528`; `app/.../playback/smart/LyricsTiming.kt:385-392` | `hqAdjustmentAllowed` 기본 false. 게다가 `hqOffsetMs`/`hqRate`는 저장만 되고 읽히지 않는다(`check-data` 3) | HQ로 교체된 파일은 검증된 offset/rate가 있어도 가사가 YouTube 클럭으로 표시된다. “무손실 검증 통과 후에도 실제로는 다른 클럭” 문제의 한 축. | 안전 기본값 유지. `hqOffsetMs`/`hqRate`를 로컬 파일 재생에서 읽는 경로를 별도 작업으로 연결. |
| HIGH | `AppSettings.kt:596,1269`; `app/.../playback/SongVersionController.kt:81,116`; `NextTrackPreparation.kt:321`; `PrefetchController.kt:66` | `preferMusicOnly` 기본 false. 이 값이 false면 곡 버전 조회·다음 곡 버전 준비·오디오 선반입이 모두 즉시 return | 한국 포크의 핵심인 “뮤직비디오→노래 버전”이 새 설치 기본값에서 전부 꺼져 있다. 설정을 켜기 전까지 MV 소리가 그대로 난다. | 재생 핵심 경로라 이번에는 수정하지 않음. 기본값을 바꿀지, 첫 실행에서 안내할지 결정 필요. |
| MED | `app/.../playback/NextTrackPreparation.kt:71,140,149,160-165` | `attempted`가 `rowId`만 키로 한다. VERSION 단계가 같은 행의 videoId를 바꿔도 이전 단계 시도 기록이 남는다 | 버전 교체 뒤 새 녹음에서 LYRICS/SYNC가 “이미 시도함”으로 건너뛰어 옛 가사/싱크가 재사용될 수 있다. `check-flow` 2·3, `check-sync-regress` 1과 같은 원인. | `attempted`/`lyricsByRow`를 `queueKey`(rowId+videoId)로 키잉. 핵심 경로라 문서만. |
| MED | `NextTrackPreparation.kt:312` | next-prep 마지막 로그가 `sync=${if (timing != null) "applied" else "skipped"}` 한 단어뿐 | 173번 중 154번 건너뛴 이유(스위치 off, isVideo, timeMs 없음, Deepgram 불가, 이미 검증됨)를 로그로 구분할 수 없다 | 스킵 사유를 함께 기록. 계산은 건드리지 않는다. |
| MED | `app/.../playback/ChunkedDataSource.kt:427-431` | `close()`가 항상 `ok = true`로 `HqHostTracker.noteTransfer` 호출 | 403/타임아웃으로 끊긴 호스트도 성공 처리량으로 남아 다음 후보 선택에서 계속 앞선다 | `transferFailed` 플래그를 open/read/openChunk 실패에서 세우고 `ok = !transferFailed`, 실패 시 `noteFailure`. 이번에는 문서만. |
| MED | `shared/.../sources/HqVerifyPolicy.kt:63,94` | `cooldownRemaining`·`shouldRetryMissingReference` 호출부가 테스트뿐 | 수동 “고음질로 다시 받기”가 자동 경로의 쿨다운/기억된 거절에 막혀도 우회하지 못한다. 정책은 있는데 연결이 없다. | `HqAudioVerify`/`HqLibraryUpgrade` 수동 경로에 연결하거나 함수를 삭제. HQ 경로라 문서만. |
| MED | `shared/.../net/HqAutoDownloadPolicy.kt:85` | `shouldStartNow` 호출부 0 | “재생 중·호스트 사용 중·동시 1개” 정책이 자동 HQ 다운로드에 적용되지 않는다. `HqLibraryUpgrade.scheduleIfEnabled`는 예약 시점만 보고, worker 실행 중 재생이 시작되면 막지 않는다 | `HqLibraryUpgrade.runOne`에서 이 정책을 확인하거나 dead 함수를 삭제. 다운로드 경로라 문서만. |
| MED | `app/.../ui/replay/ReplayScreen.kt:232-243` (수정 전 `onClick = {}`) | 장르 차트 행은 `ReplayChartRow`의 `clickable`을 타는데 콜백이 빈 람다였다 | Replay 장르 행을 눌러도 아무 일이 없다. 다른 세 차트(곡·아티스트·앨범)는 이동/재생한다 | **고침**: `onOpenGenre`를 추가해 검색 탭에서 장르명을 검색한다. |
| MED | `app/.../playback/Autoplay.kt:343-354` (수정 전 `:355,389,457`) | 커버 시드의 제목에 마커가 없으면 `isCoverArrangement`가 방송국의 커버 행을 전부 제거해 배치가 0이 된다 | 커버 곡을 시드로 AutoPlay를 켜면 새 곡을 못 채우고 같은 곡이 반복된다. 사용자가 지적한 “커버 곡에서 0곡” 경로 | **고침**: 필터가 배치를 전부 비우고 시드가 커버면, 카라오케/트리뷰트/인스트루멘탈 표식이 없는 행을 아티스트와 무관하게 남긴다. 일반 커버는 유지하고 이형 배열은 계속 제거. |
| MED | `app/.../playback/PlaybackService.kt:9955` (수정 전 없음) | `SleepTimer`는 프로세스 전역 객체인데 `onDestroy`가 취소하지 않았다 | 수면 타이머를 켠 채 서비스를 스와이프하면 같은 프로세스의 다음 재생이 옛 deadline에 일시정지될 수 있다 | **고침**: `onDestroy`에서 `SleepTimer.cancel()`. |
| LOW | `shared/.../playback/HandoffQueuePolicy.kt:58` | `tailAfterIncoming` 호출부가 테스트뿐. `CrossfadeController.reconcileQueue`가 같은 계산을 직접 한다(`:1985-2000`) | 같은 규칙의 두 구현이 있다. 지금은 Crossfade 쪽만 돈다 | 한쪽으로 통합. 크로스페이드 경로라 문서만. |
| LOW | `shared/.../videoradio/VideoRadioPolicy.kt:457` | `videoRadioFallbackStage` 호출부 0. `Autoplay`가 분기를 직접 구현 | 폴백 단계 규칙이 테스트에만 있고 실제 경로는 다른 코드 | 정책을 실제 분기에 사용하거나 삭제. |
| LOW | `app/.../playback/QueueHistory.kt:17,27` | `skippedByQueueJump`·`queueStartingAt` 호출부가 테스트뿐. 실제 점프는 `QueueCoordinator.jumpToQueueItem`→`QueueTimeline.computeJumpOrder` | 큐 점프 히스토리 규칙이 두 벌 | 한쪽으로 통합. 큐 동작이라 문서만. |
| LOW | `app/.../data/settings/AppSettings.kt:961`; `app/.../ui/screens/AccountAndScrobblingScreen.kt:382-404` | `scrobblingAvailable`이 `true` 상수라 else(중지 안내) 분기가 도달 불가 | 죽은 UI 분기 | 상수를 실제 기능 플래그로 만들거나 else 분기 삭제. |
| LOW | `app/.../ui/screens/SettingsSheet.kt:597` | 설정 검색창 `onSubmit = {}` | 엔터를 눌러도 아무 일이 없다. 필터는 입력 중에 이미 적용되므로 기능 손실은 없다 | 키보드 닫기/첫 결과로 스크롤 등 최소 동작을 주거나 `onSubmit`을 제거. |

---

## 3. 기능별 “실행 조건”과 조용히 건너뛰는 상황

| 기능 | 실행 조건(코드) | 흔한 거짓 상황 | 건너뛸 때 로그 |
|---|---|---|---|
| 다음 곡 버전 조회 | `preferMusicOnly && wantsSongVersion && !videoRadio && !(localUri && offline)` (`NextTrackPreparation.kt:319-326`) | 새 설치 기본값 `preferMusicOnly=false`; 오프라인 다운로드; video radio 행 | 없음(최종 로그의 version 값만) |
| 다음 곡 가사 준비 | `syncedLyrics && syncSafetyNextPrep` (`NextTrackPreparation.kt:182-187`, `SyncSafetyPolicy.kt:39-40`) | **기본값 off**. 사용자가 개발자 옵션을 켜지 않은 모든 재생 | `sync=skipped`만, 이유 없음 |
| 다음 곡 싱크 검증 | 위 + `syncWanted(song)`: `!isVideo && heavyWorkAllowed && aiTimingAhead && Deepgram.usable` (`:458-463`) | 커버/비디오 행(`isVideo`), 저전력, Deepgram 키 없음 | 위와 같음 |
| 다운로드 가사 재검증 | `syncSafetyDownloadRecheck` (`LyricsTiming.kt:1952-1956`) | **기본값 off** | 없음(null 반환) |
| HQ offset/rate 재적용 | `syncSafetyHqSync` + `StreamChoice.isSubstitute` (`LyricsTiming.kt:385-392`) | **기본값 off**; 로컬 파일 재생에는 `StreamChoice`가 없음 | 없음(null 반환) |
| 자동 HQ 다운로드 | `autoReplaceHqDownloads || autoPrefetchHq`, 포그라운드, `!PlaybackActivity.active` (`HqLibraryUpgrade.kt:244-260`) | 백그라운드, 재생 중, 예약 디바운스 15초 | 일부 로그 있음(예약 거절은 무로그) |
| AutoPlay 커버 필터 | `isCoverArrangement(candidate, seed)` (`Autoplay.kt`) | 시드가 커버인데 제목에 마커가 없으면 전부 제거 → 0곡. 폴백은 `isKaraokeOrTributeArrangement`로 일반 커버를 구제 | 제거 개수는 로그, “왜 0인지”는 없음 → **이번에 폴백을 마커 무관 조건으로 수정** |
| 노래 버전 조회(서비스) | `preferMusicOnly` (`SongVersionController.kt:81,116`) | 기본값 off | `music only off` 사유 로그 있음(`:424`) |
| Replay 장르 차트 | `summary.genres`가 비어 있지 않음 (`ReplayScreen.kt:232`) | 장르 데이터 없으면 차트 자체가 안 보임 | 해당 없음 → **탭 콜백 연결** |

---

## 4. 호출부 0인 코드 목록 (삭제하지 않음)

아래는 프로덕션 호출부가 0이고 테스트만 참조한다. 이번에는 삭제하지 않았다. 이유: (a) 테스트가 의도된 정책을 고정하고 있어 지우면 정책 자체가 사라지고, (b) 업스트림 v1.8.1 코드와 포크 코드를 이력 없이 확정할 수 없기 때문이다. 다음 작업에서 정책 연결 여부를 먼저 결정해야 한다.

| 파일:줄 | 함수 | 참조 |
|---|---|---|
| `shared/.../sources/HqVerifyPolicy.kt:63` | `cooldownRemaining` | 테스트만 |
| `shared/.../sources/HqVerifyPolicy.kt:94` | `shouldRetryMissingReference` | 테스트만 |
| `shared/.../net/HqAutoDownloadPolicy.kt:85` | `shouldStartNow` | 테스트만 |
| `shared/.../playback/HandoffQueuePolicy.kt:58` | `tailAfterIncoming` | 테스트만 |
| `shared/.../videoradio/VideoRadioPolicy.kt:457` | `videoRadioFallbackStage` | 테스트만 |
| `app/.../playback/QueueHistory.kt:17,27` | `skippedByQueueJump`, `queueStartingAt` | 테스트만 |
| `shared/.../data/model/SongOrigins.kt:54` | `showsAccountActions` | 테스트만(UI는 `isLocalFile`을 직접 씀) |
| `shared/.../data/sources/PlayingAudioFormat.kt:40` | `isLosslessOrAtmos` | 테스트만 |
| `shared/.../data/sources/HqTier.kt:108` | `isFakeHighQuality` | 테스트만 |

삭제한 코드는 없다.

---

## 5. 이번 수정의 조건 진리표 (AutoPlay 커버 폴백, 3차)

기존 항은 그대로 두고 커버 시드 분기만 고쳤다. 2차의 `seed.originalTitle` 기준 재필터는 업로드 제목 자체에 마커가 없거나 다른 표기("(Cover)" vs "커버")면 일반 커버를 다시 버렸고, 게다가 폴백이 원래 필터 결과가 빈 경우에만 돌아 한국어 커버 행 하나가 살아남으면 영어 `(Cover)` 행은 계속 버려졌다. 3차는 시드가 커버이면 처음부터 마커 텍스트 대신 배열 표식으로 거른다.

기존 폴백(1차): `!isCoverArrangement(candidate) || sharesArtist(candidate.artist, seed.artist)`

2차 폴백(배치가 빈 경우만): `!isCoverArrangement(candidate, markerSeed)`, `markerSeed = seed.copy(title = seed.originalTitle ?: seed.title)`

새 규칙(3차): 시드가 커버(`originalTitle != null` 또는 `isCoverArrangement(seed)`)이면 `!isKaraokeOrTributeArrangement(candidate, seed)` — 후보 제목·아티스트·앨범에 시드가 갖지 않은 `KARAOKE_TRIBUTE_MARKERS`가 하나도 없으면 유지. 일반 커버 표식(`cover`, `covers`, `커버`)은 이 목록에 없다. 일반 시드는 기존 `!isCoverArrangement(candidate, seed)` 그대로다.

| 시드가 커버인가 | 배치 | 1차 결과 | 2차 결과 | 3차 결과 |
|---|---|---|---|---|
| 아니오 | 비커버 행 있음 | 비커버 행 | 같음 | 같음 |
| 아니오 | 전부 커버 | 빈 목록 | 빈 목록(안전 규칙 유지) | 빈 목록(일반 시드 규칙 유지) |
| 예 | 비커버 행 있음 | 비커버 행 | 같음 | 비커버 행 + 일반 커버(하드 배열 제외) |
| 예 | 다른 아티스트의 일반 커버만 | 빈 목록(0곡) | 업로드 제목에 마커가 있을 때만 유지 | 일반 커버 유지 |
| 예 | 같은 아티스트 일반 커버만 | 같은 아티스트 커버 | 업로드 제목에 마커가 있을 때만 유지 | 일반 커버 유지 |
| 예 | 업로드 제목이 무표식/다른 표기인 일반 커버 | 빈 목록(0곡) | 빈 목록(0곡) | 일반 커버 유지 |
| 예 | 한국어 `커버` 행 + 영어 `(Cover)` 행 | 영어 행 제거 | 한국어 행만 유지 | 둘 다 유지 |
| 예 | 같은 아티스트 카라오케/트리뷰트 | 유지(누수) | 제거 | 제거 |
| 예 | 카라오케/트리뷰트만 | 빈 목록 | 빈 목록 | 빈 목록(안전 규칙 유지) |

핵심: 일반 커버는 마커 표기와 아티스트에 무관하게 남고, 카라오케/트리뷰트/인스트루멘탈은 시드가 같은 표식을 갖지 않는 한 아티스트와 무관하게 제거된다. 일반 시드(비커버)의 동작은 바뀌지 않는다.

---

## 6. 이번에 바꾼 파일과 테스트

- `app/src/main/java/com/music/bitchord/playback/Autoplay.kt` — 커버 시드 0곡 폴백 `autoplayRowsAfterCoverFilter`를 마커 텍스트가 아닌 `isKaraokeOrTributeArrangement` 기준으로 수정. 방송국/세션 라디오/최종 게이트에 적용.
- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/sources/TrackMatcher.kt` — `COVER_MARKERS`를 `KARAOKE_TRIBUTE_MARKERS` + 일반 커버 표식으로 분리하고 `isKaraokeOrTributeArrangement`를 추가(기존 `isCoverArrangement` 판정은 동일).
- `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` — `onDestroy`에서 `SleepTimer.cancel()`.
- `app/src/main/java/com/music/bitchord/ui/replay/ReplayScreen.kt` — `onOpenGenre` 콜백, 장르 차트 탭 연결.
- `app/src/main/java/com/music/bitchord/MainActivity.kt` — 장르 탭을 검색 탭 + `searchFor(genre)`로 연결. 2차에서 `closeMoodGenre()`, `onQueryChange("")`, `setSearchSource(YOUTUBE)`를 `searchFor` 앞에 추가.
- `app/src/test/java/com/music/bitchord/AutoplayCoverSeedTest.kt` — 새 테스트 4건:
  - `a cover seed keeps ordinary covers by any artist`
  - `a production cover seed with a markerless upload title keeps English covers`
  - `karaoke and tribute uploads from other names are still dropped`
  - `a cover seed drops karaoke and tribute even from its own artist`
  - `a cover seed with only karaoke rows still yields nothing`

검증:
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` — 통과.
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.AutoplayCoverSeedTest' --console=plain -q` — 통과(4건).

---

## 7. 남은 문제 (수정하지 않음)

1. `SyncSafetyPolicy` 3개 스위치 기본 off로 next-prep 가사/싱크, 다운로드 재검증, HQ 싱크 재적용이 사실상 죽어 있다. 안전 기본값이라 코드는 유지하고 로그만 보강해야 한다.
2. `preferMusicOnly` 기본 off로 곡 버전 기능 전체가 새 설치에서 꺼져 있다. 재생 핵심 경로라 기기 검증 후 결정.
3. NextPrep `rowId` 키 캐시(버전 교체 뒤 옛 가사/싱크 재사용), 메인 스레드 `bestTier` 조회, `sync=skipped` 무로그.
4. `DownloadedSyncCheck`가 답 없는 검사를 완료로 기록, `lyricsForPrep` override 무시, HQ offset/rate 미독.
5. `ChunkedDataSource` 실패 전송 성공 기록, `hqOverrides` 잔존, HQ 영구 거절 TTL/수동 우회 미연결.
6. 위 4번의 dead 정책 함수들을 연결할지 삭제할지 결정 필요.

---

## 8. 2차 검증 지적 처리 (r2)

> 3차에서 `markerSeed` 조건은 폐기됐다. 아래 r2 항목 중 “일반 커버는 아티스트와 무관하게 남는다”는 서술은 3차 코드에서만 참이다. 최종 조건은 5절과 9절을 따른다.

| 지적 | 처리 | 근거 |
|---|---|---|
| HIGH — `Autoplay.kt:349` 폴백에서 `!isCoverArrangement(it)` 분기가 도달 불가라 다른 아티스트의 일반 커버가 계속 버려져 커버 시드 방송국이 0곡이 된다 | **고침** | 폴백을 `seed.originalTitle`을 포함한 `markerSeed` 기준 `isCoverArrangement(it, markerSeed)`로 다시 걸렀다(2차). 3차에서 `markerSeed`를 폐기하고 `isKaraokeOrTributeArrangement`로 바꿔 일반 커버가 실제로 아티스트와 무관하게 남는다 — 5·9절. 테스트 `a cover seed keeps ordinary covers by any artist`. |
| MED — `Autoplay.kt:349` `|| sharesArtist(...)` 예외가 시드 아티스트 명의의 카라오케/트리뷰트까지 남겨 기존 가드를 무력화한다 | **고침** | `sharesArtist` 예외를 제거했다. 카라오케/트리뷰트 표식은 시드가 그 표식을 갖지 않는 한 아티스트와 무관하게 제거된다. 테스트 `a cover seed drops karaoke and tribute even from its own artist`. |
| MED — `MainActivity.kt:3304-3307` 장르 콜백이 일반 탭 전환을 건너뛰어 LIBRARY/ADDONS 소스에서는 YouTube 검색이 돌지만 화면은 로컬/애드온 결과가 남고, `selectedMoodGenre`가 남아 제목/뒤로가기 상태가 틀어진다 | **고침** | `navigator.clear()` 뒤 `viewModel.closeMoodGenre()`, `selectedTab = TAB_SEARCH`, `viewModel.onQueryChange("")`, `viewModel.setSearchSource(SearchSource.YOUTUBE)`를 `viewModel.searchFor(genre)` 앞에 둔다. 빈 쿼리로 소스를 바꿔 `setSearchSource`가 옛 검색을 한 번 더 돌리지 않게 했다. |

이번 라운드에서 삭제한 코드는 없다.

남은 HIGH/MED 중 재생 핵심 경로(곡 해석·노래 버전·가사 싱크 계산·크로스페이드)에 속하는 항목은 **실 검증 필요**: SyncSafety 3스위치 기본 off, `preferMusicOnly` 기본 off, NextPrep `rowId` 캐시·`bestTier` 메인 조회, `DownloadedSyncCheck` 완료 기록, `lyricsForPrep` override, HQ offset/rate, `ChunkedDataSource` 실패 전송 성공 기록. 코드/줄 근거는 1·2·7절과 이전 감사 문서에 있다.

---

## 9. 3차 검증 지적 처리 (r3)

| 지적 | 처리 | 근거 |
|---|---|---|
| HIGH — `Autoplay.kt:348-352` `markerSeed`가 `originalTitle`의 마커 단어를 기준으로 다시 걸러, 업로드 제목이 무표식이거나 `(Cover)`/`커버` 표기가 다르면 일반 커버가 여전히 전부 버려진다 | **고침** | 시드가 커버이면 `isKaraokeOrTributeArrangement` 기준으로 마커 텍스트 대신 배열 표식만 본다. 폴백이 배치가 빈 경우에만 돌던 조건도 없애 한국어 `커버` 행이 하나 남아도 영어 `(Cover)` 행을 유지한다. 일반 커버는 아티스트와 무관하게 남고 카라오케/트리뷰트/인스트루멘탈은 제거된다. `TrackMatcher.kt`에 `isKaraokeOrTributeArrangement` 추가, `Autoplay.kt autoplayRowsAfterCoverFilter`에 적용. |
| MED — `AutoplayCoverSeedTest.kt:31-45` 테스트가 실제 커버 행 모양(바이라인 `원곡자 · 싱어 커버`, 무표식 업로드 제목)이 아니다 | **고침** | `a production cover seed with a markerless upload title keeps English covers` 추가: `artist = "아이유 · 싱어 커버"`, `originalTitle = "밤편지 - 싱어"`, 행은 `(Cover)`/`(커버)`/`(Karaoke Version)`. |
| MED — `docs/review-1031/review-dead.md:134-139`(및 `:75`, `:176`) 진리표·요약이 "일반 커버는 아티스트와 무관하게 유지"라고 하지만 2차 코드는 `seed.originalTitle`의 마커 일치를 요구해 근거가 없었다 | **고침** | 5절 진리표를 1·2·3차 결과로 다시 쓰고, 2절·8절 문구를 실제 3차 조건(`isKaraokeOrTributeArrangement`)으로 고쳤다. |
