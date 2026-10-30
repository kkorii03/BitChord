# review-features-1031 — 화면·메뉴·재생 기능 검사

- 점검일: 2026-10-31
- 기준: 현재 체크아웃(`task/fix-1030` 계열)
- 범위: 곡 메뉴, 재생 화면, 큐, 보관함(다운로드·재생목록·좋아요·고음질), 검색, 아티스트·앨범·재생목록, 위젯·알림.
- 방법: 이전 감사 문서(`docs/AUDIT_*.md`, `docs/audit/*.md`)를 먼저 읽고 현재 코드에서 다시 확인했다. 새 문제는 실행 조건을 따라가며 코드 근거만 적었다. 실제 기기 검증은 하지 않았고, 이번에 고친 항목만 `:app:compileProdReleaseKotlin`과 단위 테스트로 확인했다.
- 금지 경로 준수: `.github/`, `keys/`, 서명 설정, 업스트림 v1.8.1 코드는 건드리지 않았다. 곡 해석·노래 버전·가사 싱크 계산·크로스페이드 본체는 수정하지 않았다.

---

## 1. 결과 표 — 기능별 진입점과 실제 처리

판정: **정상** = 진입점·처리·효과가 이어짐, **작동안함** = 처리 경로가 없거나 도달 불가, **조건오류** = 실행 조건이 의도와 다름, **안내불일치** = 안내와 실제 결과가 다름.

| 기능 | 진입점 파일:줄 | 실제 처리 파일:줄 | 판정 |
|---|---|---|---|
| 곡 메뉴 — 좋아요/보관함 | `app/src/main/java/com/music/bitchord/ui/components/SongActionsSheet.kt:255-268` | `MainActivity.kt:5035`, `MainViewModel.toggleLike` → `YtMusicRepository.rate` | 정상 |
| 곡 메뉴 — 플레이리스트에 추가 | `SongActionsSheet.kt:272-278` | `MainActivity.kt:5043` → `loadPlaylists()`/`playlistTarget` → `PlaylistPickerSheet` | 정상 |
| 곡 메뉴 — 다음 항목 재생/대기열 추가 | `SongActionsSheet.kt:286-300` | `MainActivity.kt:5003-5004` → `playNext`/`addToQueue` → `addMediaItem` (`MainActivity.kt:2067-2131`) | 정상 |
| 곡 메뉴 — 공유 | `SongActionsSheet.kt:305-309` | `MainActivity.kt` share intent (`EXTRA_TEXT` YouTube URL) | 정상 |
| 곡 메뉴 — 앨범/아티스트 열기 | `SongActionsSheet.kt:313-345` | `MainActivity.kt:5055-5078` → `openPage`/`openDetail` | 정상 |
| 곡 메뉴 — 다운로드/다운로드 삭제 | `SongActionsSheet.kt:680-760` | `Downloads.enqueue` / `Downloads.delete` (`Downloads.kt:1106-1133`) | 정상 |
| 곡 메뉴 — 고음질로 다시 받기 | `SongActionsSheet.kt:370-383` | `MainActivity.kt:5169-5177` → `HqLibraryUpgrade.requestUpgrade` (`HqLibraryUpgrade.kt:391-471`) | **안내불일치** (아래 N5) |
| 곡 메뉴 — 캐시에서 제거 | `SongActionsSheet.kt:384-391` | `MainActivity.kt:5063-5067` → `removeFromCache` | 정상 |
| 곡 메뉴 — WebDAV 업로드 | `SongActionsSheet.kt:770-814` | `MainActivity.kt:5020-5033` → `WebDavUploads.upload` | 정상 (기기 파일만 노출, 다운로드 곡 제외는 의도) |
| 곡 메뉴 — 라디오 시작 | `SongActionsSheet.kt:392-397` | `MainActivity.kt:1969-2065` → `loadAutoplayTracks`/`beginRadioQueue` | 정상 |
| 곡 메뉴 — 원곡으로 되돌리기/품질 업그레이드 | `SongActionsSheet.kt:402-440` | `MainActivity.kt:5115-5167` → `PlayerConnection.revertToOriginal`/`upgradeQuality` | 정상 (조건은 아래 표) |
| 곡 메뉴 — 오디오/비디오 전환 | `SongActionsSheet.kt:422-439` | `MainActivity.kt:4880-4990` → `switchToMusicOnly`/`switchToVideo`/`swapToVersion` | 정상 |
| 곡 메뉴 — 커버 원곡 제목/커버 채널 지정 | `SongActionsSheet.kt:443-470` | 클립보드 복사 / `AppSettings.setCoverChannel` | 정상 |
| 곡 메뉴 — 곡 정보 | `SongActionsSheet.kt:471-473` | `SongInfoDialog`/`SongInfoPage` | 정상 |
| 곡 메뉴 — 수면 타이머 | `SongActionsSheet.kt:475-481` | `SleepTimer.start/startAfterTrack/cancel`; 크로스페이드 경로 `CrossfadeController.kt:1160-1169,1324-1332` | 정상 |
| 곡 메뉴 — 재생 속도/음 높이 | `SongActionsSheet.kt:483-497,860-950` | `AppSettings.setPlaybackSpeed/setPitchSemitones` → `PlaybackService.kt:9397` 관찰 | 정상 |
| 곡 메뉴 — 시크릿 모드 | `SongActionsSheet.kt:499-505` | `AppSettings.setPrivateListening` (`MainActivity.kt:5094-5108`) | 정상 |
| 곡 메뉴 — 가사 오프셋 | `SongActionsSheet.kt:507-514` | `MainActivity.kt:5239-5245` → `showLyricsOffset` → `LyricsOffsetSheet` | 정상 |
| 곡 메뉴 — 로그 복사/문제 표시 | `SongActionsSheet.kt:520-540` | `TrackLog.forTrack`, `TrackDiagnostics.mark` (`MainActivity.kt:5190-5238`) | 정상 |
| 재생 — 셔플 | `PlayerControls.kt:579-690` | `MainActivity.kt:2916` → `PlaybackService.toggleShuffleFromSession` (`PlaybackService.kt:3058-3082`) | 정상 |
| 재생 — 반복(끔/전체/한 곡) | `PlayerControls.kt:579-690` | `MainActivity.kt:2917-2931` + `PlaybackService.onRepeatModeChanged` (`:1511-1521`) | 정상 (외부 변경 저장은 이번 수정) |
| 재생 — ∞ 자동재생 | `PlayerControls.kt:579-690` | `MainActivity.kt:2934-2938` → `PlaybackService.toggleAutoplayFromNotification` (`:3493-3528`) | 정상 |
| 재생 — 가사 패널 | `NowPlayingScreen.kt:1038-1050` | `PlayerLyrics.kt`/`LyricsController` | 정상 |
| 재생 — 번역 | `NowPlayingScreen.kt:3797-3805` | `PlayerLyrics.kt:3181-3483` → `PlayerHost.translateLyrics` | 정상 |
| 재생 — 로마자 | `NowPlayingScreen.kt:2187-2195`, `:3778-3787` | `PlayerLyrics.kt:3391-3440` → `PlayerHost.romanizeLyrics` | **작동안함 → 이번 수정** (N1) |
| 재생 — AI 타이밍 | `NowPlayingScreen.kt:1737` | `LyricsOffsetSheet` → `MainViewModel.toggleAiLyricsTiming` → `LyricsController` | **조건오류 → 이번 수정** (N2) |
| 재생 — 음질 표시 | `PlayerControls.kt:328-360` | `NerdStats`가 실제 디코더 포맷을 측정 (`LosslessOrStats`, `PlayerControls.kt:1713-1790`) | 정상 |
| 재생 — 품질 업그레이드/되돌리기 안내 | `NowPlayingScreen.kt:3531-3565` | `SongActionsSheet`의 되돌리기/업그레이드 행 | 정상 |
| 큐 — 곡 이동/삭제/비우기 | `PlayerQueue.kt:180-260,460-475` | `MainActivity.kt:2980-3010` → `QueueEdit.canRemove/canMove` (`QueueEdit.kt:15-24`) | 정상 |
| 보관함 — 다운로드 | `LibraryPhoneParts.kt:64-70` | `MainActivity.kt:3420-3490` → `Downloads`/`LocalFolder` | 정상 |
| 보관함 — 재생목록/좋아요 | `LibraryScreen.kt:135-230` | `MainViewModel.loadLibrary`, `LibraryScreen` shelf | 정상 |
| 보관함 — 고음질(다운로드 페이지) | `DetailScreen.kt:651-661` | `HqLibraryUpgradeAllRow` → `HqLibraryUpgrade.startReplacementPass` (`DownloadManagerSheet.kt:339-378`) | 정상 (빈 목록에서는 무동작, N9) |
| 보관함 — WebDAV | `LibraryPhoneParts.kt:88-94` | `MainViewModel.remoteSongsState` → `WebDavRepository.getSongs` | **안내불일치 → 이번 수정** (N4) |
| 보관함 — SMB/캐시/Spotify/스마트 | `LibraryPhoneParts.kt:71-122` | 각 `SmbRepository`/`AudioCache.cachedSongs`/`SpotifyLibrary`/`SmartPlaylists` | 정상 |
| 검색 — 검색/필터/기록 | `SearchScreen.kt:300-420` | `MainViewModel.submitSearch`, `SearchHistory` | 정상 |
| 검색 — 오류 상태 | `SearchScreen.kt:374,533` | `MessageState(results.message)`만 표시 | **조건오류** (재시도 없음, N7) |
| 아티스트·앨범·재생목록 — 재생/셔플/저장/구독 | `DetailScreen.kt:620-720,1760-1810` | `MainActivity.kt:3617-3720,5386-5583` | 정상 |
| 아티스트·앨범·재생목록 — 오류 상태 | `DetailScreen.kt:804` | `MessageState(state.message)`만 표시 | **조건오류** (재시도 없음, N7) |
| 위젯 — 재생/이전/다음/좋아요/셔플 | `MediaWidget.kt:269-305`, `MediaWidgetPill.kt:189-245` | `MediaWidgetActions.kt:34-130` → `MediaController`/session commands | 정상 |
| 알림 — 좋아요/셔플/라디오/되돌리기 | `PlaybackService.kt:2406-2453` | 같은 파일 `onCustomCommand` (`:11173-11220`) | 정상 (라벨 언어 N8) |

---

## 2. 이전 감사 문서 확인

| 이전 문서 | 항목 | 현재 상태 | 근거 |
|---|---|---|---|
| `docs/AUDIT_FEATURES.md` #1 | 재생 실패가 화면에 안 보임 | 고쳐짐 | 서비스가 `reportPlaybackFailure`로 토스트 (`PlaybackService.kt:5584-5587`) |
| `docs/AUDIT_FEATURES.md` #2 | WebDAV 오류가 빈 목록으로 바뀜 | **이번에 고침** | `WebDavRepository.kt:29-33`가 `getOrThrow` |
| `docs/AUDIT_FEATURES.md` #3 | 다운로드 삭제 실패에도 기록 삭제 | 고쳐짐 | `Downloads.delete`가 성공 시에만 `forget` (`Downloads.kt:1106-1133`) |
| `docs/AUDIT_FEATURES.md` #4 | 앨범/재생목록 삭제가 전부 실패해도 기록 삭제 | 고쳐짐 | `deleteCollection`이 `failedAny`면 기록 유지 (`Downloads.kt:1262-1285`) |
| `docs/AUDIT_FEATURES.md` #5 | '음악이 아닌 오디오 표시' 토글이 무동작 | 고쳐짐 | 끄면 `IS_MUSIC` 조건을 넣지 않음 (`LocalMediaRepository.kt:233-260`) |
| `docs/AUDIT_FEATURES.md` #6-14 | 데스크톱 설정/MPRIS/스크로블/다운로드 | 해당 없음 | 이번 범위는 Android 앱 |
| `docs/AUDIT_FEATURES.md` #15 | 스크로블 길이 `H:MM:SS` 미지원 | 고쳐짐 | `Song.durationMillis()` 사용 (`ScrobbleManager.kt:204-209`, `ListenBrainzManager.kt:27-36`) |
| `docs/AUDIT_FEATURES.md` #16 | Spotify 가져오기 첫 검색 결과 사용 | 고쳐짐 | `TrackMatcher.best`/`bestMatch` 사용 (`SpotifyImporter.kt:177-205,277-340`) |
| `docs/AUDIT_FEATURES.md` #17 | 잘못된 로컬 플레이리스트 한 줄이 전체를 버림 | 고쳐짐 | `LocalPlaylistStore.parsePlaylists`가 행 단위로 건너뜀 |
| `docs/AUDIT_FEATURES.md` #18 | 큐 삭제/이동 인덱스 범위 초과 | 고쳐짐 | `QueueEdit.canRemove/canMove` (`QueueEdit.kt:15-24`) |
| `docs/AUDIT_FEATURES.md` #19 | 알림 권한을 다운로드 때만 요청 | 고쳐짐 | 첫 재생에서 요청 (`MainActivity.kt:2476-2488,2620-2628`) |
| `docs/AUDIT_FEATURES.md` #20 | AI 가사/타이밍이 빈 키에서도 켜짐 | **남음** | `aiLyrics`/`autoLyricsTiming`/`aiTimingAhead` 토글에 키 게이트 없음 (`SettingsSheet.kt:1442-1515`) |
| `docs/AUDIT_FEATURES.md` #21 | 가사 제공처 오류가 전부 '없음' | 고쳐짐 | `LyricsFailure`를 전달 (`LyricsRepository.kt:723-784`) |
| `docs/AUDIT_FEATURES.md` #22 | 수동 제공처 목록에 비활성 소스 노출 | 고쳐짐 | `enabledSources`로 필터 (`LyricsProviderSheet.kt:67-69`) |
| `docs/AUDIT_FEATURES.md` #23 | 재시작 후 셔플 해제가 아이콘만 바뀜 | 고쳐짐 | `QueueShuffle.persistedOrder/restorePersisted` (`QueueShuffle.kt:170-205`) |
| `docs/AUDIT_FEATURES.md` #24 | 인앱 업데이트 다이얼로그 도달 불가 | **남음** | `CHECK_UPSTREAM_RELEASES = false` (`AppUpdateChecker.kt:108,179`) |
| `docs/AUDIT_FEATURES.md` #25 | 데스크톱 언어 혼용 | 해당 없음 | 데스크톱 |
| `docs/audit/audit-ui.md` #1 | 재생/볼륨/가사 슬라이더 TalkBack 미노출 | 고쳐짐 | `ThinSlider`에 `progressBarRangeInfo`/`setProgress` (`ThinSlider.kt:159-163`) |
| `docs/audit/audit-ui.md` #2 | 영어 예외 원문 노출 | 고쳐짐 | `FriendlyError.message` 사용 (`MainViewModel.kt:3836-3837`) |
| `docs/audit/audit-ui.md` #3 | 앨범/검색 오류에 재시도 없음 | **남음** | `SearchScreen.kt:374,533`, `DetailScreen.kt:804` |
| `docs/audit/audit-ui.md` #7 | 가사 없음이 막다른 길 | 부분 고쳐짐 | 상태 줄은 항상 제공처 선택을 연다 (`NowPlayingScreen.kt:3768-3776`) |
| `docs/audit/audit-song.md` #1 | `typeUnknown`이 버전 조회를 건너뜀 | 고쳐짐 | `SongVersionPolicy.wantsSongVersion` 사용 (`YtMusicRepository.kt:732`) |
| `docs/audit/audit-song.md` #4 | 다운로드 영상이 `localUri` 때문에 앨범 폴백을 못 씀 | 고쳐짐 | `CatalogSongMapper.map(allowLocalVideo)` (`CatalogSongMapper.kt:176-185`) |
| `docs/audit/audit-song.md` #7 | '버전 없음' 판정이 세션 변경에도 고정 | **남음** | `StreamResolver.onSessionChanged`가 `songVersionMissing`을 지우지 않음 (`StreamResolver.kt:407-418`) |
| `docs/audit/audit-playback.md` #1 | 긴 Retry-After가 로더를 멈춤 | 고쳐짐 | `HqRetryPolicy.MAX_WAIT_MS` 캡 (`ChunkedDataSource.kt:460-467`) |
| `docs/audit/audit-playback.md` #2 | 휴대폰 `setWakeMode` 없음 | 고쳐짐 | `updateWakeMode` (`PlaybackService.kt:4013-4034`) |
| `docs/audit/audit-playback.md` #3 | 잘린 스트림을 EOF로 보고 | 고쳐짐 | `truncated stream at ...` 예외 (`ChunkedDataSource.kt:408`) |
| `docs/audit/audit-playback.md` #4 | 프록시 라우트 변경 후 옛 URL 사용 | 고쳐짐 | `routeGeneration` 검사 (`ProxyPlayback.kt:221-243`) |
| `docs/audit/check-flow.md` #1 | 크로스페이드 standby가 다음 행 버전 교체를 못 따라감 | **남음** | `NextTrackPreparation.resolveVersion`은 active만 `replaceMediaItem` (`:386-395`); `rearmIncoming` 호출은 HQ 경로뿐 (`PlaybackService.kt:6159`) |
| `docs/audit/check-flow.md` #2 | 실행 중 같은 행의 queueKey 변화를 무시 | **남음** | `schedule`이 `job?.isActive`면 `shouldCancel`보다 먼저 return (`NextTrackPreparation.kt:101-107`) |
| `docs/audit/check-flow.md` #3 | 행 단위 가사 캐시가 새 videoId에 적용 | **남음** | `lyricsByRow` 키가 `snapshot.rowId` (`NextTrackPreparation.kt:77-80,175,217-218`) |
| `docs/audit/check-flow.md` #4 | 다음 행 버전 교체가 다운로드 안전 규칙을 우회 | **남음** | `resolved.toMediaItem()` 기본값 사용 (`NextTrackPreparation.kt:386`) |
| `docs/audit/check-flow.md` #5 | 스트리밍 곡을 저장 파일 존재만으로 로컬 판정 | **남음** | 마지막 fallback `Downloads.verifiedSavedUri(mediaId) != null` (`PlaybackService.kt:7953`) |
| `docs/audit/check-flow.md` #6-9 | HQ 교체 정산/재검사/큐 URI | **남음** | `docs/audit/check-flow.md` 그대로. 이번 범위 밖 |

---

## 3. 새로 찾은 문제

심각도는 이 앱의 우선순위(올바른 곡 > 가사 싱크 > 안정성 > 배터리 > 속도 > 음질 > UI)를 반영했다.

| 심각도 | 파일:줄 | 증거 | 사용자 영향 | 수정안 | 이번 수정 |
|---|---|---|---|---|---|
| **HIGH** | `sharedUi/.../player/NowPlayingScreen.kt:2182-2195`, `:3779-3787` | 로마자 토글이 `if (onAiLyricsTiming == null)` 안에만 있었다. Android는 `onAiLyricsTiming = viewModel::toggleAiLyricsTiming`을 항상 넘긴다(`MainActivity.kt:3083`). 동기화 드로어(`LyricsOffsetSheet`)에는 로마자 컨트롤이 없다. | Android에서 로마자 표시를 아예 켤 수 없다. 한국어/일본어 가사 사용자에게 기능 하나가 통째로 사라진다. | AI 타이밍과 무관하게 토글을 항상 표시한다. | 고침 |
| **MED** | `sharedUi/.../player/NowPlayingScreen.kt:1737` | 동기화 드로어의 AI 타이밍 버튼이 `songMeaningAvailable && lyricsTiming.adjustable`이었다. `songMeaningAvailable`은 Gemini 키 유무다(`AndroidPlayerHost.kt:181-184`). AI 타이밍은 Deepgram으로 돈다. | Gemini 키 없이 Deepgram만 쓰는 사용자는 AI 타이밍 버튼을 볼 수 없다. | Gemini와 무관하게 `lyricsTiming.adjustable`만 본다. | 고침 |
| **MED** | `app/.../playback/PlaybackService.kt:1511-1521` | `onRepeatModeChanged`가 모드를 바꾸기만 하고 `AppSettings.setRepeatMode`를 호출하지 않았다. 알림/Android Auto/차량 컨트롤은 이 경로로만 모드를 바꾼다. | 알림에서 반복을 바꿔도 앱을 다시 켜면 예전 모드로 돌아간다. | 세션에서 온 변경도 저장한다. 같은 값은 쓰지 않는다. | 고침 |
| **MED** | `app/.../data/webdav/WebDavRepository.kt:29-33` | `WebDavClient.listLibrary(...).getOrNull() ?: return emptyList()`라서 401/네트워크 실패가 성공한 빈 목록이 됐다. `RemoteListing`은 실패만 오류로 그린다(`RemoteListing.kt:13-17`). | 잘못된 비밀번호/죽은 서버가 "오디오 파일 없음"으로 보여 사용자가 폴더 경로를 의심하게 된다. | 실패를 그대로 던져 `RemoteListing`이 원인을 표시하게 한다. | 고침 |
| **MED** | `app/.../MainActivity.kt:5169-5180`, `app/.../download/HqLibraryUpgrade.kt:415-471` | '고음질로 다시 받기' 행은 다운로드 곡이면 항상 열린다. `requestUpgrade`는 검증된 더 높은 후보가 없으면 `if (!allowEqual) return@launch`로 조용히 끝난다. 콜백/토스트가 없다. | 사용자는 눌렀는데 아무 일도 안 일어난 것으로 본다. 행은 계속 활성이라 반복해서 누르게 된다. | 검증 실패/후보 없음 결과를 행 상태나 토스트로 알리거나, 그 상태에서는 행을 비활성화한다. | 문서만 |
| **MED** | `app/.../playback/NextTrackPreparation.kt:460-466`, `:309-315` | `syncWanted`가 `isVideo`, `!heavyWorkAllowed`, `!aiTimingAhead`, `!Deepgram.usable` 중 하나면 false. 최종 로그는 `sync=skipped`만 남기고 이유를 남기지 않는다. | 비디오 곡, 저전력 모드, AI 타이밍 끔, Deepgram 키 없음에서 다음 곡 싱크가 조용히 빠진다. 사용자는 왜인지 알 수 없고 로그로도 추적이 어렵다. | 스킵 이유를 `next-prep` 로그에 남긴다(`sync=skipped reason=video/low-power/...`). | 문서만 |
| **MED** | `sharedUi/.../screens/SearchScreen.kt:374,533`, `app/.../screens/DetailScreen.kt:804` | 오류 상태가 `MessageState(message)`만 그린다. `MessageState`는 `actionLabel/onAction`을 지원하지만 넘기지 않는다. | 일시적 네트워크 오류에서 검색/앨범/재생목록 화면이 막다른 길이 된다. 뒤로 나갔다 다시 들어와야 한다. | `onRetry`를 넘겨 현재 검색/페이지를 다시 요청한다. | 문서만 |
| **LOW** | `app/.../playback/PlaybackService.kt:2416,2427` | 알림 액션 이름이 `"좋아요"`, `"셔플 끔"`, `"셔플 켬"`으로 하드코딩되어 있다. | 한국어 외 언어에서 알림 액션만 한국어로 보인다. | `R.string.like`, `R.string.shuffle_on/off` 등 기존 문자열을 쓴다. | 문서만 |
| **LOW** | `app/.../screens/DetailScreen.kt:651-661`, `app/.../ui/components/DownloadManagerSheet.kt:339-378` | 다운로드 페이지에서 `HqLibraryUpgradeAllRow`를 항상 그린다. 저장된 곡이 없어도 탭이 가능하고 `startReplacementPass`는 빈 계획으로 끝난다. | 빈 다운로드 화면에서 눌러도 반응이 없어 고장으로 보인다. | 저장된 곡이 없으면 행을 숨기거나 비활성화한다. | 문서만 |

---

## 4. 건너뛰는 조건 · 흔한 상황 · 로그 여부

| 기능/단계 | 건너뛰는 조건 (코드) | 흔한 상황인가 | 건너뛸 때 로그 | 비고 |
|---|---|---|---|---|
| 다음 곡 준비 — VERSION | `preferMusicOnly` 꺼짐, `videoRadio`, `isVideo/typeUnknown` 아님, 오프라인 로컬 파일 (`NextTrackPreparation.kt:320-327`) | 기본 설정에 따라 매우 흔함 | 최종 `next-prep`에 `version=<id>`만; 스킵 이유 없음 | 정상 스킵도 이유를 남기면 진단이 쉬워진다 |
| 다음 곡 준비 — LYRICS | `nextPrepLyrics` 꺼짐 또는 `syncedLyrics` 꺼짐 (`:184-190,200-207`) | 가사 동기화를 끈 사용자에게 흔함 | 최종 `lyrics=none` | 스킵 이유 없음 |
| 다음 곡 준비 — SYNC | 비디오 곡, 저전력, `aiTimingAhead` 꺼짐, Deepgram 불가 (`:460-466`) | 비디오 곡·저전력·키 없는 사용자에게 흔함 | `sync=skipped`만 | **MED**: 이유 없음 |
| 다음 곡 준비 — HQ | 비디오, 로컬 파일, 모듈 트랙, 대체 소스 없음 (`:432-437`) | 다운로드 곡 사용자에게 흔함 | `hq=none` | 이유 없음 |
| 다음 곡 준비 — MIX | 스마트 페이드 꺼짐, 파티, 비디오+`automixVideos` 꺼짐 (`:491-500`) | 파티/비디오 사용자에게 흔함 | `mix=none` | 이유 없음 |
| 다음 곡 준비 — 같은 행 queueKey 변경 | `schedule`이 active job이면 `shouldCancel` 전에 return (`:101-107`) | 버전 교체/큐 편집 중 | 없음 | **HIGH**, 이전 감사 남음 |
| 곡 버전 조회 | `isSongVersionMissing`이면 조회 안 함 (`YtMusicRepository.kt:1099`) | 게이트/프록시 실패 뒤 흔함 | `song version ... kept (none)` | 세션 변경에도 안 지워짐 (이전 감사 남음) |
| 자동재생 top-up | `autoplayTopUpNeeded` false, 파티 supplier 아님, `autoplaySeed == current.videoId` (`PlaybackService.kt:3565-3600`) | 파티 리스너, 반복 전체, 앞에 5곡 이상 있을 때 흔함 | 대부분 없음 | 설계된 스킵이지만 진단 로그가 없다 |
| 자동재생 — 커버 필터 | `TrackMatcher.isCoverArrangement`면 제거 (`Autoplay.kt:355-398`) | 커버 곡 라디오에서 흔함 | `autoplay: dropped ... cover/karaoke` 있음 | 정상. 빈 결과는 `onEmpty`로 기록 |
| 고음질 다운로드 교체 | 소스 쿨다운/검증 실패/`!allowEqual` (`HqLibraryUpgrade.kt:415-471`) | 429/타임아웃 뒤 흔함 | 일부만 `download-check` | 수동 요청은 결과 안내 없음 |
| WebDAV/SMB 업로드 행 | `isLocalFile` 아님, 설정 없음 (`SongActionsSheet.kt:770-814`) | 다운로드 곡/원격 곡 | 없음 | 의도된 숨김. 다운로드 곡 업로드는 기능 자체가 없음 |
| 수면 타이머 '이 곡 후' | 크로스페이드/자동믹스 경로는 별도 페이드아웃 (`CrossfadeController.kt:1160-1169,1324-1332`) | 크로스페이드 사용자 | 없음 | 현재 코드상 정상 처리 |
| 위젯/알림 버튼 | 큐 없음, 로컬 파일 좋아요 불가 (`MediaWidgetActions.kt:74-130`, `PlaybackService.kt:2390-2403`) | 첫 실행/로컬 파일 | 없음 | 의도된 무동작. 위젯은 앱 열기로 대체 |

---

## 5. 이번 수정과 테스트

### 수정 파일

1. `app/src/main/java/com/music/bitchord/data/webdav/WebDavRepository.kt`
   - 목록 실패를 `emptyList()`로 바꾸지 않고 던진다. `RemoteListing`이 원인을 그린다.
2. `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt`
   - AI 타이밍 버튼에서 Gemini `songMeaningAvailable` 조건 제거.
   - 로마자 토글의 `onAiLyricsTiming == null` 게이트 제거(세로/가로 두 곳).
3. `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LyricsTimingUi.kt`
   - `aiTimingActionEnabled`, `romanizationActionEnabled` 순수 함수 추가.
4. `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt`
   - `onRepeatModeChanged`에서 세션발 반복 모드도 저장.
5. `app/src/main/java/com/music/bitchord/playback/PlaybackParametersMath.kt`
   - `shouldPersistRepeatMode(stored, incoming)` 추가.
6. `app/src/test/java/com/music/bitchord/WebDavTest.kt`
   - 401 목록이 빈 보관함이 아니라 실패로 전달되는지 검사.
7. `app/src/test/java/com/music/bitchord/playback/PlaybackParametersMathTest.kt`
   - 반복 모드 저장 조건 검사.
8. `sharedUi/src/jvmTest/kotlin/com/music/bitchord/ui/player/LyricsControlPolicyTest.kt`
   - AI 타이밍/로마자 노출 조건 검사.

### 테스트 결과

- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` — 성공.
- `./gradlew :sharedUi:jvmTest --tests 'com.music.bitchord.ui.player.LyricsControlPolicyTest' --console=plain -q` — 성공.
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.WebDavTest' --tests 'com.music.bitchord.playback.PlaybackParametersMathTest' -Pbitchord.arm64Only=true --console=plain -q` — 성공.

---

## 6. 미해결 — 기기 검증 필요

- `docs/audit/check-flow.md`의 HIGH 5건은 이번에 고치지 않았다. 곡 해석/노래 버전/가사 싱크/크로스페이드 본체라 폰 검증 없이 바꾸지 않는다.
- `docs/audit/audit-song.md` #7: 세션 변경 시 `songVersionMissing` 초기화가 없다.
- `docs/AUDIT_FEATURES.md` #20: AI 가사/타이밍 키 게이트.
- `docs/AUDIT_FEATURES.md` #24: 인앱 업데이트 플래그가 꺼져 있다(의도 여부 확인 필요).
- 검색/상세 오류 재시도(N7), 고음질 재다운로드 무동작 안내(N5), 알림 액션 한국어 하드코딩(N8), 빈 다운로드 화면의 고음질 전체 행(N9)은 문서에만 남겼다.

---

## 요약 (10줄 이내)

1. 수정 파일: `WebDavRepository.kt`, `NowPlayingScreen.kt`, `LyricsTimingUi.kt`, `PlaybackService.kt`, `PlaybackParametersMath.kt`, 테스트 3개.
2. 핵심 변경: WebDAV 실패를 빈 목록이 아닌 오류로 전달, Android 로마자 토글 복구, AI 타이밍의 Gemini 키 오게이트 제거, 알림/차량 반복 모드 저장.
3. 테스트: `:app:compileProdReleaseKotlin`, `:sharedUi:jvmTest`(LyricsControlPolicyTest), `:app:testProdReleaseUnitTest`(WebDavTest, PlaybackParametersMathTest) 모두 성공.
4. 미해결: next-prep queueKey/가사 캐시/standby 버전 교체/다운로드 안전 우회/로컬 오판은 `docs/audit/check-flow.md`에 남김.
5. 미해결: 검색·상세 재시도 없음, 고음질 재다운로드 무동작 안내, 알림 한국어 하드코딩, 빈 다운로드 화면 고음질 행은 이 문서 3장에 남김.
