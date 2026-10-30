# review-features-1031-r2 — 화면·메뉴·재생 기능 재검사

- 점검일: 2026-10-31
- 기준: 현재 체크아웃(`dsq/review-features-1031-r2`, 이전 차수 `review-features-1031` 위)
- 범위: 이전 차수 결과에 대한 업로드 검증자 지적 3건 처리 + 이전 감사 문서의 기능 항목 재확인.
- 방법: 코드 경로를 따라가며 실행 조건을 확인했다. 실제 기기 검증은 하지 않았고, 이번에 고친 항목만 컴파일·단위 테스트로 확인했다.
- 금지 경로 준수: `.github/`, `keys/`, 서명 설정, 업스트림 v1.8.1 코드는 건드리지 않았다. 곡 해석·노래 버전·가사 싱크 계산·크로스페이드 본체는 수정하지 않았다.

---

## 1. 업로드 검증자 지적 처리

| 지적 | 심각도 | 위치 | 이번 처리 | 근거 |
|---|---|---|---|---|
| AI 타이밍 버튼이 제공자 키 없이도 열려, 누르면 `aiFailed`로 끝남 | **MED** | `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LyricsTimingUi.kt:57-58`, 사용처 `sharedUi/.../NowPlayingScreen.kt:1737` | **고침** | `LyricsTimingUi.aiAvailable`을 추가하고 `LyricsController.startTiming`에서 `LyricsTranslation.geminiKeyUsable \|\| Deepgram.usable`로 채운다(`app/.../LyricsController.kt:664-667`). 버튼은 `adjustable && (byAi \|\| aiAvailable)`일 때만 열린다. 수동 요청은 Deepgram/Whisper 뒤 Gemini로 폴백하므로(`app/.../LyricsTiming.kt:1336-1348`, `:1452-1466`) 두 키 중 하나만 있어도 의미가 있다. 이미 표시된 AI 답을 되돌리는 것은 키가 없어도 되므로 `byAi`를 남겼다. |
| 다운로드 곡에서 WebDAV 업로드 행이 사라짐 | **MED** | `app/.../ui/components/SongActionsSheet.kt:799-805`, `app/.../MainActivity.kt:5024-5026` | **고침** | 행과 콜백 모두 `SongOrigins.isLocalFile`을 요구했다. `SongOrigins.of`는 `localUri != null && YouTube id`인 다운로드를 `DOWNLOADED_YOUTUBE`로 판정하고 `isLocalFile`은 false다(`shared/.../SongOrigins.kt:37-46`). 반면 `WebDavUploads.isUploadable`은 content/file URI만 보므로 다운로드의 저장 URI를 이미 받아들인다. 두 곳의 `isLocalFile` 게이트를 제거하고 `isUploadable`만 남겼다. 판정 규칙은 `WebDavUploads.isUploadableSource`로 분리해 단위 테스트한다. |
| 반복 모드 주석이 알림도 바꾼다고 잘못 말함 | **LOW** | `app/.../playback/PlaybackService.kt:1514-1519` | **고침(주석만)** | `notificationButtons()`는 좋아요·셔플만 반환한다(`PlaybackService.kt:2408-2435`). 세션발 반복 모드 저장 경로는 Android Auto/차량 컨트롤에만 해당하므로 주석을 그렇게 고쳤다. 동작 변경은 없다. |

---

## 2. 이전 감사 문서 확인

판정: **고쳐짐 / 남음 / 해당 없음**. 이번 차수에서 새로 고친 것은 "이번에 고침"으로 표시했다.

| 이전 문서 | 항목 | 현재 상태 | 근거 |
|---|---|---|---|
| `docs/AUDIT_FEATURES.md` #1 | 재생 실패가 화면에 안 보임 | 고쳐짐 | `PlaybackService.reportPlaybackFailure` |
| `docs/AUDIT_FEATURES.md` #2 | WebDAV 오류가 빈 목록으로 바뀜 | 고쳐짐 | `WebDavRepository.kt:25-33`의 `getOrThrow` |
| `docs/AUDIT_FEATURES.md` #3 | 다운로드 삭제 실패에도 기록 삭제 | 고쳐짐 | `Downloads.delete` 성공 시에만 `forget` |
| `docs/AUDIT_FEATURES.md` #4 | 앨범/재생목록 삭제가 전부 실패해도 기록 삭제 | 고쳐짐 | `Downloads.deleteCollection`의 `failedAny` |
| `docs/AUDIT_FEATURES.md` #5 | '음악이 아닌 오디오 표시' 토글이 무동작 | 고쳐짐 | `LocalMediaRepository`의 `IS_MUSIC` 조건 |
| `docs/AUDIT_FEATURES.md` #15 | 스크로블 길이 `H:MM:SS` 미지원 | 고쳐짐 | `Song.durationMillis()` 사용 |
| `docs/AUDIT_FEATURES.md` #16 | Spotify 가져오기 첫 검색 결과 사용 | 고쳐짐 | `TrackMatcher.best`/`bestMatch` |
| `docs/AUDIT_FEATURES.md` #17 | 잘못된 로컬 플레이리스트 한 줄이 전체를 버림 | 고쳐짐 | `LocalPlaylistStore.parsePlaylists` 행 단위 |
| `docs/AUDIT_FEATURES.md` #18 | 큐 삭제/이동 인덱스 범위 초과 | 고쳐짐 | `QueueEdit.canRemove/canMove` |
| `docs/AUDIT_FEATURES.md` #19 | 알림 권한을 다운로드 때만 요청 | 고쳐짐 | 첫 재생에서 요청 |
| `docs/AUDIT_FEATURES.md` #20 | AI 가사/타이밍이 빈 키에서도 켜짐 | **남음** | 설정 토글은 여전히 키 게이트가 없다(`app/.../ui/screens/SettingsSheet.kt:1442-1515`). 이번에 고친 것은 재생 화면의 AI 타이밍 **버튼** 게이트다. |
| `docs/AUDIT_FEATURES.md` #21 | 가사 제공처 오류가 전부 '없음' | 고쳐짐 | `LyricsFailure` 전달 |
| `docs/AUDIT_FEATURES.md` #22 | 수동 제공처 목록에 비활성 소스 노출 | 고쳐짐 | `enabledSources` 필터 |
| `docs/AUDIT_FEATURES.md` #23 | 재시작 후 셔플 해제가 아이콘만 바뀜 | 고쳐짐 | `QueueShuffle.persistedOrder/restorePersisted` |
| `docs/AUDIT_FEATURES.md` #24 | 인앱 업데이트 다이얼로그 도달 불가 | **남음** | `CHECK_UPSTREAM_RELEASES = false` (`AppUpdateChecker.kt:108,181`) |
| `docs/audit/audit-ui.md` #1 | 재생/볼륨/가사 슬라이더 TalkBack 미노출 | 고쳐짐 | `ThinSlider`의 `progressBarRangeInfo`/`setProgress` |
| `docs/audit/audit-ui.md` #2 | 영어 예외 원문 노출 | 고쳐짐 | `FriendlyError.message` |
| `docs/audit/audit-ui.md` #3 | 앨범/검색 오류에 재시도 없음 | **남음** | `SearchScreen.kt:374,533`, `DetailScreen.kt:804`가 `MessageState(message)`만 그린다 |
| `docs/audit/audit-ui.md` #7 | 가사 없음이 막다른 길 | 부분 고쳐짐 | 상태 줄은 항상 제공처 선택을 연다(`NowPlayingScreen.kt:3768-3776`) |
| `docs/audit/audit-song.md` #1 | `typeUnknown`이 버전 조회를 건너뜀 | 고쳐짐 | `SongVersionPolicy.wantsSongVersion` |
| `docs/audit/audit-song.md` #4 | 다운로드 영상이 `localUri` 때문에 앨범 폴백을 못 씀 | 고쳐짐 | `CatalogSongMapper.map(allowLocalVideo)` |
| `docs/audit/audit-song.md` #7 | '버전 없음' 판정이 세션 변경에도 고정 | **남음** | `StreamResolver.onSessionChanged`가 `songVersionMissing`을 지우지 않는다(`StreamResolver.kt:407-416`) |
| `docs/audit/audit-playback.md` #1 | 긴 Retry-After가 로더를 멈춤 | 고쳐짐 | `HqRetryPolicy.MAX_WAIT_MS` 캡 |
| `docs/audit/audit-playback.md` #2 | 휴대폰 `setWakeMode` 없음 | 고쳐짐 | `updateWakeMode` |
| `docs/audit/audit-playback.md` #3 | 잘린 스트림을 EOF로 보고 | 고쳐짐 | `truncated stream at ...` 예외 |
| `docs/audit/audit-playback.md` #4 | 프록시 라우트 변경 후 옛 URL 사용 | 고쳐짐 | `routeGeneration` 검사 |
| `docs/audit/check-flow.md` #1 | 크로스페이드 standby가 다음 행 버전 교체를 못 따라감 | **남음** | `NextTrackPreparation.resolveVersion`은 active만 `replaceMediaItem` |
| `docs/audit/check-flow.md` #2 | 실행 중 같은 행의 queueKey 변화를 무시 | **남음** | `NextTrackPreparation.schedule`이 `job?.isActive`면 `shouldCancel` 전에 return (`:101-107`) |
| `docs/audit/check-flow.md` #3 | 행 단위 가사 캐시가 새 videoId에 적용 | **남음** | `lyricsByRow` 키가 `snapshot.rowId` |
| `docs/audit/check-flow.md` #4 | 다음 행 버전 교체가 다운로드 안전 규칙을 우회 | **남음** | `resolved.toMediaItem()` 기본값 사용 |
| `docs/audit/check-flow.md` #5 | 스트리밍 곡을 저장 파일 존재만으로 로컬 판정 | **남음** | `Downloads.verifiedSavedUri(mediaId)` fallback |
| `docs/audit/check-flow.md` #6-9 | HQ 교체 정산/재검사/큐 URI | **남음** | `docs/audit/check-flow.md` 그대로. 이번 범위 밖 |
| 이전 차수 `review-features-1031` | WebDAV 목록 실패를 오류로 전달 | 고쳐짐(유지) | `WebDavRepository.kt:25-33` |
| 이전 차수 `review-features-1031` | Android 로마자 토글 복구 | 고쳐짐(유지) | `NowPlayingScreen.kt:2182-2195`, `:3779-3787` |
| 이전 차수 `review-features-1031` | 세션발 반복 모드 저장 | 고쳐짐(유지) | `PlaybackService.onRepeatModeChanged` + `PlaybackParametersMath.shouldPersistRepeatMode` |

---

## 3. 이번에 새로 확인한 문제

이번 차수에서 고친 MED 2건은 1장에 있다. 아래는 이번에 직접 고치지 않은 것들이다.

| 심각도 | 파일:줄 | 증거 | 사용자 영향 | 수정안 |
|---|---|---|---|---|
| **HIGH** | `app/.../playback/NextTrackPreparation.kt:101-107`, `:386-395` | 같은 행에서 `queueKey`가 바뀌어도 active job이면 `shouldCancel` 전에 return한다. standby 교체는 active 행만 `replaceMediaItem` 한다. | 버전 교체/큐 편집 직후 다음 곡 준비가 옛 행 기준으로 남아, 다음 곡 버전·가사가 어긋날 수 있다. | `job?.isActive` return보다 `shouldCancel`을 먼저 평가하고, standby에도 버전 교체를 적용한다. **폰 검증 필요**(재생 핵심 경로). |
| **MED** | `sharedUi/.../screens/SearchScreen.kt:374,533`, `app/.../screens/DetailScreen.kt:804` | 오류 상태가 `MessageState(message)`만 그린다. `MessageState`는 `actionLabel/onAction`을 지원하지만 넘기지 않는다. | 일시적 네트워크 오류에서 검색/앨범/재생목록이 막다른 길이 된다. | 현재 검색어/페이지를 다시 요청하는 `onRetry`를 넘긴다. |
| **MED** | `app/.../MainActivity.kt:5169-5180`, `app/.../download/HqLibraryUpgrade.kt:415-471` | '고음질로 다시 받기'는 다운로드 곡이면 항상 열리지만, 검증된 더 높은 후보가 없으면 `if (!allowEqual) return@launch`로 조용히 끝난다. 콜백/토스트가 없다. | 눌렀는데 아무 일도 안 일어난 것으로 보이고, 행은 계속 활성이라 반복해서 누르게 된다. | 검증 실패/후보 없음 결과를 행 상태나 토스트로 알리거나 그 상태에서 행을 비활성화한다. |
| **MED** | `app/.../playback/NextTrackPreparation.kt:460-466` | `syncWanted`가 `isVideo`, `!heavyWorkAllowed`, `!aiTimingAhead`, `!Deepgram.usable` 중 하나면 false인데 최종 로그는 `sync=skipped`만 남긴다. | 비디오 곡, 저전력, AI 타이밍 끔, Deepgram 키 없음에서 다음 곡 싱크가 조용히 빠지고 이유를 알 수 없다. | 스킵 이유를 `next-prep` 로그에 남긴다(`sync=skipped reason=video/low-power/...`). |
| **MED** | `app/.../ui/screens/SettingsSheet.kt:1442-1515` | `autoLyricsTiming`/`aiTimingAhead`/`aiLyrics` 토글에 Deepgram/Gemini 키 게이트가 없다. | 키가 없어도 자동 AI 기능을 켤 수 있어, 켠 뒤에도 아무 일이 없어 고장으로 보인다. | 키가 없을 때 토글을 비활성화하거나 키 설정 행으로 안내한다. |
| **LOW** | `app/.../playback/PlaybackService.kt:2408-2435` | 알림 액션 이름이 `"좋아요"`, `"셔플 끔"`, `"셔플 켬"`으로 하드코딩되어 있다. | 한국어 외 언어에서 알림 액션만 한국어로 보인다. | `R.string.like`, `R.string.shuffle_on/off` 등 기존 문자열을 쓴다. |
| **LOW** | `app/.../screens/DetailScreen.kt:651-661`, `app/.../ui/components/DownloadManagerSheet.kt:339-378` | 다운로드 페이지에서 `HqLibraryUpgradeAllRow`를 항상 그린다. 저장된 곡이 없어도 탭할 수 있고 `startReplacementPass`는 빈 계획으로 끝난다. | 빈 다운로드 화면에서 눌러도 반응이 없어 고장으로 보인다. | 저장된 곡이 없으면 행을 숨기거나 비활성화한다. |
| **LOW** | `app/.../ui/LyricsController.kt:660-668` | `aiAvailable`은 `startTiming`에서 한 번만 계산된다. 같은 곡 재생 중에 Deepgram/Gemini 키를 추가·제거해도 다음 `startTiming` 전까지 버튼 노출이 바뀌지 않는다. | 키를 막 추가한 사용자는 다음 곡/가사 재로딩까지 AI 타이밍 버튼을 못 본다. | `refreshTiming` 갱신 시 다시 계산하거나, 키 흐름을 관찰해 `aiAvailable`을 갱신한다. |

---

## 4. 건너뛰는 조건 · 흔한 상황 · 로그 여부

| 기능/단계 | 건너뛰는 조건 (코드) | 흔한 상황인가 | 건너뛸 때 로그 | 비고 |
|---|---|---|---|---|
| AI 타이밍 버튼 | `!adjustable` 또는 (`!byAi` && `!aiAvailable`) (`LyricsTimingUi.kt:57-58`) | 키가 하나도 없는 사용자 | 없음(UI 숨김) | 이번에 고침. 숨김이 의도된 상태다. |
| WebDAV 업로드 행 | 서버 미설정, `!WebDavUploads.isUploadable` (WebDAV id, content/file URI 아님) (`SongActionsSheet.kt:805`, `MainActivity.kt:5024-5026`) | 스트리밍 곡, SMB 원격 행 | 없음(UI 숨김) | 이번에 고침. 다운로드 곡은 이제 노출된다. |
| 다음 곡 준비 — VERSION | `preferMusicOnly` 꺼짐, `videoRadio`, `isVideo/typeUnknown` 아님, 오프라인 로컬 파일 (`NextTrackPreparation.kt:320-327`) | 기본 설정에 따라 매우 흔함 | 최종 `next-prep`에 `version=<id>`만; 스킵 이유 없음 | 정상 스킵도 이유를 남기면 진단이 쉬워진다 |
| 다음 곡 준비 — LYRICS | `nextPrepLyrics` 꺼짐 또는 `syncedLyrics` 꺼짐 (`:184-190,200-207`) | 가사 동기화를 끈 사용자에게 흔함 | 최종 `lyrics=none` | 스킵 이유 없음 |
| 다음 곡 준비 — SYNC | 비디오 곡, 저전력, `aiTimingAhead` 꺼짐, Deepgram 불가 (`:460-466`) | 비디오 곡·저전력·키 없는 사용자에게 흔함 | `sync=skipped`만 | **MED**: 이유 없음 |
| 다음 곡 준비 — HQ | 비디오, 로컬 파일, 모듈 트랙, 대체 소스 없음 (`:432-437`) | 다운로드 곡 사용자에게 흔함 | `hq=none` | 이유 없음 |
| 다음 곡 준비 — MIX | 스마트 페이드 꺼짐, 파티, 비디오+`automixVideos` 꺼짐 (`:491-500`) | 파티/비디오 사용자에게 흔함 | `mix=none` | 이유 없음 |
| 다음 곡 준비 — 같은 행 queueKey 변경 | `schedule`이 active job이면 `shouldCancel` 전에 return (`:101-107`) | 버전 교체/큐 편집 중 | 없음 | **HIGH**, 이전 감사 남음 |
| 곡 버전 조회 | `isSongVersionMissing`이면 조회 안 함 (`YtMusicRepository.kt:1099`) | 게이트/프록시 실패 뒤 흔함 | `song version ... kept (none)` | 세션 변경에도 안 지워짐 |
| 자동재생 top-up | `autoplayTopUpNeeded` false, 파티 supplier 아님, `autoplaySeed == current.videoId` (`PlaybackService.kt:3565-3600`) | 파티 리스너, 반복 전체, 앞에 5곡 이상 있을 때 흔함 | 대부분 없음 | 설계된 스킵이지만 진단 로그가 없다 |
| 자동재생 — 커버 필터 | `TrackMatcher.isCoverArrangement`면 제거 (`Autoplay.kt:355-398`) | 커버 곡 라디오에서 흔함 | `autoplay: dropped ... cover/karaoke` 있음 | 정상. 빈 결과는 `onEmpty`로 기록 |
| 고음질 다운로드 교체 | 소스 쿨다운/검증 실패/`!allowEqual` (`HqLibraryUpgrade.kt:415-471`) | 429/타임아웃 뒤 흔함 | 일부만 `download-check` | 수동 요청은 결과 안내 없음 |
| 수면 타이머 '이 곡 후' | 크로스페이드/자동믹스 경로는 별도 페이드아웃 (`CrossfadeController.kt:1160-1169,1324-1332`) | 크로스페이드 사용자 | 없음 | 현재 코드상 정상 처리 |
| 위젯/알림 버튼 | 큐 없음, 로컬 파일 좋아요 불가 (`MediaWidgetActions.kt:74-130`, `PlaybackService.kt:2390-2403`) | 첫 실행/로컬 파일 | 없음 | 의도된 무동작. 위젯은 앱 열기로 대체 |

---

## 5. 이번 수정과 테스트

### 수정 파일

1. `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LyricsTimingUi.kt`
   - `aiAvailable` 추가, `aiTimingActionEnabled`를 `adjustable && (byAi || aiAvailable)`로 수정.
2. `app/src/main/java/com/music/bitchord/ui/LyricsController.kt`
   - `startTiming`에서 `aiAvailable = geminiKeyUsable || Deepgram.usable` 설정.
3. `app/src/main/java/com/music/bitchord/ui/components/SongActionsSheet.kt`
   - `WebDavUploadRow`의 `isLocalFile` 인자·게이트 제거.
4. `app/src/main/java/com/music/bitchord/MainActivity.kt`
   - 업로드 콜백에서 `songIsLocalFile` 게이트 제거, `isUploadable`만 사용.
5. `app/src/main/java/com/music/bitchord/data/webdav/WebDavUploads.kt`
   - `isUploadableSource(videoId, scheme)` 순수 판정 추가, `isUploadable`이 이를 사용.
6. `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt`
   - 반복 모드 저장 주석을 Android Auto/차량으로 정정(동작 변경 없음).

### 테스트 파일

- `sharedUi/src/jvmTest/kotlin/com/music/bitchord/ui/player/LyricsControlPolicyTest.kt`
  - `aiTimingNeedsAnAdjustableSongAndAUsableProvider`
  - `aiTimingStaysOfferedWhenTheShownAnswerCanBeTakenBack`
  - `romanizationStaysAvailableWheneverThereAreLyrics`(기존)
- `app/src/test/java/com/music/bitchord/WebDavUploadsTest.kt`
  - `aFinishedYouTubeDownloadCanBeUploaded`
  - `aDeviceFileCanBeUploaded`
  - `aStreamOrRemoteFileLibraryRowCannotBeUploaded`
  - `aRowAlreadyOnTheServerCannotBeUploadedAgain`

### 테스트 결과

- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` — 성공.
- `./gradlew :sharedUi:jvmTest --tests 'com.music.bitchord.ui.player.LyricsControlPolicyTest' --console=plain -q` — 성공.
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.WebDavUploadsTest' -Pbitchord.arm64Only=true --console=plain -q` — 성공.

---

## 6. 미해결 — 기기 검증 필요

- `docs/audit/check-flow.md`의 HIGH 5건(다음 곡 준비 queueKey/가사 캐시/standby 버전 교체/다운로드 안전 우회/로컬 오판)은 재생 핵심 경로라 이번에 고치지 않았다. **폰 검증 필요**.
- `docs/audit/audit-song.md` #7: 세션 변경 시 `songVersionMissing` 초기화가 없다.
- `docs/AUDIT_FEATURES.md` #20: 설정의 AI 가사/타이밍 토글 키 게이트.
- `docs/AUDIT_FEATURES.md` #24: 인앱 업데이트 플래그가 꺼져 있다(의도 여부 확인 필요).
- 검색/상세 오류 재시도, 고음질 재다운로드 무동작 안내, 알림 액션 한국어 하드코딩, 빈 다운로드 화면의 고음질 전체 행은 이 문서 3장에 남겼다.
- AI 타이밍 `aiAvailable`의 재생 중 키 변경 반영은 3장 LOW에 남겼다.

---

## 요약 (10줄 이내)

1. 수정 파일: `LyricsTimingUi.kt`, `LyricsController.kt`, `SongActionsSheet.kt`, `MainActivity.kt`, `WebDavUploads.kt`, `PlaybackService.kt`(주석), 테스트 2개.
2. 핵심 변경: AI 타이밍을 실제 제공자 키가 있을 때만 노출, 다운로드 곡의 WebDAV 업로드 행 복구, 알림 반복 모드 주석 정정.
3. 테스트: `:app:compileProdReleaseKotlin`, `:sharedUi:jvmTest`(LyricsControlPolicyTest), `:app:testProdReleaseUnitTest`(WebDavUploadsTest) 모두 성공.
4. 미해결: check-flow HIGH 5건은 재생 핵심 경로라 `docs/audit/check-flow.md`에 남기고 폰 검증 필요.
5. 미해결: 검색/상세 재시도, 고음질 재다운로드 안내, AI 설정 토글 게이트, 알림 한국어 라벨은 이 문서 3장에 남김.
