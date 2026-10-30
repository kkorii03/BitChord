# review-features-1031-r6 — AI 타이밍 오디오 준비 이벤트 재검사

- 점검일: 2026-10-31
- 기준: 현재 체크아웃(`dsq/review-features-1031-r6`, 이전 차수 `review-features-1031-r5` 위)
- 범위: 5차 검토자 VERDICT의 HIGH 1건(완료 판정을 한 번만 계산해 Deepgram 단독 AI 타이밍 버튼이 재생 내내 숨는 문제)을 고치고, 그 경로의 건너뛰기 조건을 다시 따라갔다. 기능 전체 표는 `review-features-1031.md` §1을 유지하고, 이번 diff는 AI 타이밍 오디오 준비 이벤트와 그 소비 경로만 바꾼다.
- 방법: `LyricsController.startTiming`(`:691`) → `aiTimingAvailable`(`:632`) → `VideoAudio.audioReadyForTiming`(`VideoAudio.kt:108`) → `AudioCache.watchRenditionCompletion`/`renditionCompleted`(`AudioCache.kt:727-775`) → `SimpleCache` span 이벤트 순서로 실행 조건을 따라갔다. 기기 검증은 하지 않았고, 고친 항목은 앱 컴파일과 단위 테스트로 확인했다.
- 금지 경로 준수: `.github/`, `keys/`, 서명 설정, 업스트림 v1.8.1 코드는 건드리지 않았다. 곡 해석·노래 버전·가사 싱크 계산(`LyricsTiming` 본체)·크로스페이드는 수정하지 않았다.

---

## 1. 이전 감사·차수 문서 확인

판정: **고쳐짐 / 남음 / 해당 없음**.

| 이전 문서 | 항목 | 현재 상태 | 근거 |
|---|---|---|---|
| r5 §1 #1 (HIGH) | `audioReadyForTiming`이 아무 렌디션이나 있으면 열려 `wav`가 즉시 null | **고쳐짐(유지)** | `wav`가 고르는 복사본의 완성 여부로 판정(`VideoAudio.kt:108-149`, `VersionAudioAligner.chooseMeasurementCopy`) |
| r5 §1 #2 (LOW) | 위 수정의 단위 테스트 없음 | **고쳐짐(유지)** | `VideoAudioTimingReadyTest` 9건(이번 1건 추가) |
| r5 §3 LOW (r3 §3 LOW) | `aiAvailable`이 재생 중 캐시 완료를 반영하지 않음 | **이번에 고침** | `AudioCache.renditionCompleted` 이벤트에서 `aiAvailable`을 다시 계산(`LyricsController.kt:710-721`). 아래 3장 HIGH |
| r5 §3 MED | 다운로드 곡의 로컬 파일을 AI 타이밍 오디오로 쓰지 않음 | **남음** | `wav`는 `AudioCache`만 읽고(`VideoAudio.kt:61-91`), `songWav`의 `localUri` 경로(`:156-200`)는 호출부 없음. **폰 검증 필요** |
| r5 §3 LOW | 비디오 `decode`가 10초 미만이면 로그 없이 null | **남음** | `VideoAudio.kt:228` |
| r2 §1 | 다운로드 곡 WebDAV 업로드 행 | 고쳐짐(유지) | `SongActionsSheet`/`MainActivity`의 `isLocalFile` 게이트 없음 |
| r2 §1 | 반복 모드 저장 주석 | 고쳐짐(유지) | `PlaybackService.onRepeatModeChanged` |
| `docs/AUDIT_FEATURES.md` #20 | 설정 AI 토글 키 게이트 없음 | 남음 | `SettingsSheet.kt:1442-1515` |
| `docs/AUDIT_FEATURES.md` #24 | 인앱 업데이트 다이얼로그 도달 불가 | 남음 | `AppUpdateChecker.kt:108,181`의 `CHECK_UPSTREAM_RELEASES = false` |
| `docs/audit/audit-ui.md` #3 | 검색/상세 오류에 재시도 없음 | 남음 | `SearchScreen.kt:374,533`, `DetailScreen.kt:804`가 `MessageState(message)`만 그림 |
| `docs/audit/check-flow.md` #1-#5 | 재생 핵심 경로(standby 버전 교체·queueKey·가사 캐시·다운로드 안전 우회·로컬 오판) | 남음 | 이번에도 수정하지 않음. **폰 검증 필요** |
| `docs/audit/audit-song.md` #7 | 세션 변경에도 '버전 없음' 판정 고정 | 남음 | `YtMusicRepository.kt:1004,1043,1092`(이전 `StreamResolver`에서 이동). `StreamResolver.onSessionChanged`는 `songVersionMissing`을 지우지 않음 |
| `docs/audit/audit-playback.md` #1-#4 | Retry-After 캡·wake lock·잘린 스트림·프록시 URL 세대 | 고쳐짐(유지) | 각 항목의 기존 수정 유지 |
| r2 §3 MED | 다음 곡 SYNC 스킵 이유 로그 없음 | 남음 | `NextTrackPreparation.kt:309-315,460-466` |
| r2 §3 MED | 고음질 재다운로드 무동작 안내 없음 | 남음 | `HqLibraryUpgrade.kt:415-471` |
| r2 §3 LOW | 알림 액션 한국어 하드코딩 | 남음 | `PlaybackService.kt:2418,2429` |
| r2 §3 LOW | 빈 다운로드 화면의 고음질 전체 행 | 남음 | `DetailScreen.kt:658` |
| r2 §3 HIGH | 같은 행 queueKey 변경 무시 | 남음 | `NextTrackPreparation.kt:102-107`. **폰 검증 필요** |

---

## 2. 결과 표 — 이번에 다시 확인한 기능

판정: **정상** = 진입점·처리·효과가 이어짐, **작동안함** = 처리 경로가 없거나 도달 불가, **조건오류** = 실행 조건이 의도와 다름, **안내불일치** = 안내와 실제 결과가 다름.

| 기능 | 진입점 파일:줄 | 실제 처리 파일:줄 | 판정 |
|---|---|---|---|
| AI 타이밍 버튼 노출 | `LyricsController.kt:691` → `LyricsTimingUi.kt:59-60` | `LyricsController.aiTimingAvailable`(`:632-640`), `LyricsAiGuard.manualTimingAvailable`(`LyricsAiGuard.kt:117-135`) | **이번에 고침 — 정상** |
| AI 타이밍 실제 엔진 선택 | `LyricsController.toggleAiLyricsTiming`(`:1189`) | `LyricsTiming.placeWithAi` → `askAi`(`LyricsTiming.kt:1375-1487`), `LyricsTranslation.kt:1309` | 정상 |
| AI 타이밍 되돌리기 | `LyricsOffsetSheet` → `MainViewModel.toggleAiLyricsTiming` | `LyricsTiming.clearAi` | 정상(`byAi`면 키 없이도 열림) |
| 가사 패널/번역/로마자 | `NowPlayingScreen.kt:1038-1050,3797-3805,2187-2195` | `PlayerLyrics.kt`/`LyricsController`/`PlayerHost` | 정상(r2 로마자 게이트 수정 유지) |
| 재생 — 셔플 | `PlayerControls.kt:579-690` | `PlaybackService.toggleShuffleFromSession`(`:3058-3082`) | 정상 |
| 재생 — 반복 | `PlayerControls.kt:579-690` | `PlaybackService.onRepeatModeChanged`(`:1511-1521`) | 정상 |
| 재생 — ∞ 자동재생 | `PlayerControls.kt:579-690` | `PlaybackService.toggleAutoplayFromNotification`(`:3493-3528`), `Autoplay.kt:131-400` | 정상(빈 결과 재시도는 `PlaybackService.kt:3602-3710`) |
| 재생 — 음질 표시 | `PlayerControls.kt:328-360` | `NerdStats`/`LosslessOrStats`(`:1713-1790`) | 정상 |
| 재생 — 품질 업그레이드/되돌리기 안내 | `NowPlayingScreen.kt:3531-3565` | `SongActionsSheet`의 되돌리기/업그레이드 행, `PlayerConnection` | 정상 |
| 재생 — 속도/음 높이/수면 타이머 | `SongActionsSheet.kt:475-497,860-950` | `AppSettings.setPlaybackSpeed`/`setPitchSemitones`(`PlaybackService.kt:9397`), `SleepTimer` | 정상 |
| 곡 메뉴 — 좋아요/플레이리스트/다음 항목/공유/정보 | `SongActionsSheet.kt:255-309,471-473` | `MainActivity.kt:5003-5043,5035` → `MainViewModel` | 정상 |
| 곡 메뉴 — 다운로드/캐시 제거/WebDAV | `SongActionsSheet.kt:680-760,384-391,770-814` | `Downloads.enqueue/delete`, `AudioCache.remove`, `WebDavUploads.upload` | 정상(다운로드 곡 WebDAV 숨김은 의도) |
| 곡 메뉴 — 라디오/오디오·비디오 전환/원곡 지정 | `SongActionsSheet.kt:392-470` | `MainActivity.kt:1969-2065,4880-4990,5115-5167` | 정상 |
| 큐 — 이동/삭제/비우기 | `PlayerQueue.kt:180-260,460-475` | `QueueEdit.canRemove/canMove`(`QueueEdit.kt:15-24`) | 정상 |
| 보관함 — 다운로드/재생목록/좋아요/고음질 | `LibraryPhoneParts.kt:64-122`, `LibraryScreen.kt:135-230`, `DetailScreen.kt:651-661` | `Downloads`/`LocalFolder`/`MainViewModel.loadLibrary`/`HqLibraryUpgrade` | 정상(빈 목록 고음질 행은 아래 3장 LOW) |
| 검색/아티스트·앨범·재생목록 | `SearchScreen.kt:300-420`, `DetailScreen.kt:620-720,1760-1810` | `MainViewModel.submitSearch/loadLibrary`, `MainActivity.kt:3617-3720,5386-5583` | 정상(오류 재시도 없음은 아래 3장 MED) |
| 위젯/알림 버튼 | `MediaWidget.kt:269-305`, `MediaWidgetPill.kt:189-245`, `PlaybackService.kt:2406-2453` | `MediaWidgetActions.kt:34-130`, `PlaybackService.onCustomCommand`(`:11173-11220`) | 정상(알림 라벨 언어는 아래 3장 LOW) |

---

## 3. 새로 찾은 문제

| 심각도 | 파일:줄 | 증거 | 사용자 영향 | 수정안 | 이번 처리 |
|---|---|---|---|---|---|
| **HIGH** | `LyricsController.kt:627-640,691,710-721`, `AudioCache.kt:727-775` | `aiAvailable`은 `startTiming`에서 `VideoAudio.audioReadyForTiming`으로 한 번만 계산됐고, `refreshTiming`은 `aiAvailable`을 건드리지 않았다. `audioReadyForTiming`은 완성된 복사본만 true라서, 가사 로드 시점에 스트림이 부분이면 Deepgram 단독 사용자의 버튼이 그 재생 내내 숨었다. (r3/r5가 지적한 '영영 숨김'의 재발) | Deepgram만 설정한 사용자가 흔한 스트리밍 곡에서 AI 타이밍을 아예 쓸 수 없다. 곡이 끝까지 캐시돼도 버튼이 나타나지 않는다. | `SimpleCache`의 span 추가(커밋) 이벤트에서 렌디션 완성을 감지해 `renditionCompleted`를 emit하고, `LyricsController`가 그 이벤트에서 `aiAvailable`만 다시 계산해 켠다. 주기 폴링 없음. | **고침** |
| **MED** | `VideoAudio.kt:61-91,156-200` | `wav`는 `AudioCache.awaitAnalysisTrack` + `renditionToMeasure`만 읽는다. `songWav`는 `Song.localUri`를 읽지만 호출부가 없다. 다운로드는 캐시에서 복사하지 않으면 `DownloadStore`에만 저장된다. | 캐시에 완성본이 없는 다운로드 곡은 Deepgram 단독 사용자에게 AI 타이밍 버튼이 숨는다. 로컬 파일이 분명히 있는데 기능이 사라진다. Gemini 키가 있으면 영상 폴백은 동작한다. | `audioReadyForTiming`/`wav`에 `localUri`를 넘겨 `songWav`처럼 로컬 파일을 먼저 읽게 한다. 버튼과 처리 경로를 함께 바꿔야 하므로 이번에는 문서만. **폰 검증 필요** | 문서만 |
| **LOW** | `LyricsController.kt:710-721`, `VideoAudio.kt:108-125` | 완료 이벤트 시점에 `PowerState.screenOn`이 false면 `audioReadyForTiming`이 false라 켜지 않고, 화면이 다시 켜질 때 재계산할 이벤트가 없다. | 화면이 꺼진 동안 곡이 완전히 캐시되면, 이후 화면을 켜도 버튼이 늦게/끝까지 숨을 수 있다. 빈도는 낮다. | 화면 켜짐 이벤트에서 현재 재생 곡의 `aiAvailable`만 재계산한다(폴링 금지). 이번에는 이벤트 표면을 늘리지 않으려고 문서만. | 문서만 |
| **LOW** | `VideoAudio.kt:218-232` | 비디오 `decode`가 10초 미만이면 `if (data.size < RATE * 2 * 10) return null`로 로그 없이 끝난다. `audioFitsInline`은 길이를 모르는 곡을 통과시킨다(`LyricsAiGuard.kt:110-113`). | 아주 짧은 곡/잘린 캐시에서 Deepgram이 조용히 실패한다. | 10초 미만 거절에도 `TrackLog.d("AI timing: under ten seconds", …)`를 남긴다. | 문서만 |
| **MED** | `NextTrackPreparation.kt:309-315,460-466` | `syncWanted`가 `isVideo`, `!heavyWorkAllowed`, `!aiTimingAhead`, `!Deepgram.usable` 중 하나면 false인데, 최종 로그는 `sync=skipped`만 남긴다. | 비디오 곡·저전력·키 없음에서 다음 곡 싱크가 조용히 빠지고 이유를 알 수 없다. | 스킵 이유를 `next-prep` 로그에 남긴다(`sync=skipped reason=...`). | 문서만 |
| **MED** | `SearchScreen.kt:374,533`, `DetailScreen.kt:804` | 오류 상태가 `MessageState(message)`만 그리고 `onRetry`를 넘기지 않는다. | 일시적 네트워크 오류에서 검색/앨범/재생목록이 막다른 길이 된다. | 현재 검색/페이지를 다시 요청하는 `onRetry`를 넘긴다. | 문서만 |
| **MED** | `HqLibraryUpgrade.kt:415-471` | 검증된 더 높은 후보가 없으면 `if (!allowEqual) return@launch`로 조용히 끝나고 콜백/토스트가 없다. | 사용자는 눌렀는데 아무 일도 안 일어난 것으로 본다. | 실패/후보 없음 결과를 토스트나 행 상태로 알린다. | 문서만 |
| **LOW** | `PlaybackService.kt:2418,2429` | 알림 액션 이름이 `"좋아요"`, `"셔플 끔"`, `"셔플 켬"`으로 하드코딩되어 있다. | 한국어 외 언어에서 알림 액션만 한국어로 보인다. | 기존 `R.string` 문자열을 쓴다. | 문서만 |
| **LOW** | `DetailScreen.kt:658` | 다운로드 페이지에서 `HqLibraryUpgradeAllRow`를 항상 그린다. 저장된 곡이 없어도 눌러지고 빈 계획으로 끝난다. | 빈 다운로드 화면에서 눌러도 반응이 없어 고장으로 보인다. | 저장된 곡이 없으면 행을 숨기거나 비활성화한다. | 문서만 |

---

## 4. 건너뛰는 조건 · 흔한 상황 · 로그 여부

| 기능/단계 | 건너뛰는 조건 (코드) | 흔한 상황인가 | 건너뛸 때 로그 | 비고 |
|---|---|---|---|---|
| AI 타이밍 버튼 — 줄 수 | 비어 있지 않은 줄 < 3 (`LyricsAiGuard.kt:117-131`) | 1~2줄 가사/간주 | 없음(UI 숨김) | 의도된 숨김 |
| AI 타이밍 버튼 — 엔진 | Gemini 없음 + (Deepgram 없음 또는 한국어/연주곡 또는 완성 오디오 없음 또는 7분 초과/10초 미만) (`LyricsAiGuard.kt:117-135`) | Deepgram 단독 + 스트리밍/다운로드/긴 곡 | 없음(UI 숨김) | 이번에 '완성 오디오'가 재생 중 완성되면 이벤트로 다시 계산 |
| AI 타이밍 버튼 — 화면 꺼짐 | `!PowerState.screenOn` (`VideoAudio.kt:111-114`) | 재생 중 화면 꺼짐 | 없음(UI 숨김) | 의도된 절전 |
| AI 타이밍 완료 이벤트 — 화면 꺼짐 | 완료 시점 `!screenOn`이면 켜지 않고, 화면 켜짐 이벤트 없음 (`LyricsController.kt:710-721`) | 화면 꺼진 채 곡이 끝까지 캐시됨 | 없음 | 3장 LOW |
| AI 타이밍 완료 이벤트 — 캐시 미개방 | `openedCache == null`이면 watch 미등록 (`AudioCache.kt:752-757`) | 재생 시작 직후 극히 이른 가사 로드 | 없음 | 캐시는 재생 전에 열리는 것이 보통 |
| AI 타이밍 완료 이벤트 — 늦게 생긴 렌디션 | watch 시점에 없던 키는 `renditionKeysFor` 갱신 때 등록 (`AudioCache.kt:2902-2909`) | 재생 중 품질 업그레이드 | 없음 | 5초 memo 갱신보다 빨리 완료되면 놓칠 수 있음(낮은 빈도) |
| 다운로드 곡 AI 타이밍 | `wav`가 `localUri`를 읽지 않음 (`VideoAudio.kt:61-91,156-200`) | 다운로드 곡 + Deepgram 단독 | 없음(UI 숨김) | 3장 MED, **폰 검증 필요** |
| AI 타이밍 — Deepgram 스킵 | `!wantsDeepgram(title, artist, texts)` (`LyricsTiming.kt:1414-1418`) | 한국어 곡 | `Deepgram skipped (Korean/instrumental)` 있음 | 정상 |
| 다음 곡 준비 — SYNC | 비디오 곡, 저전력, `aiTimingAhead` 꺼짐, Deepgram 불가 (`NextTrackPreparation.kt:460-466`) | 비디오 곡·저전력·키 없는 사용자에게 흔함 | `sync=skipped`만 | **MED**: 이유 없음 |
| 다음 곡 준비 — 같은 행 queueKey 변경 | active job이면 `shouldCancel` 전에 return (`NextTrackPreparation.kt:102-107`) | 버전 교체/큐 편집 중 | 없음 | **HIGH**, 이전 감사 남음 |
| 자동재생 top-up | `autoplayTopUpNeeded` false, 파티 supplier 아님, `autoplaySeed == current.videoId` (`PlaybackService.kt:3574-3603`) | 반복 전체, 앞에 곡이 충분할 때 | 대부분 없음 | 설계된 스킵. 빈 결과는 `PlaybackService.kt:3696-3710`에서 재시도 |
| 고음질 다운로드 교체 | 소스 쿨다운/검증 실패/`!allowEqual` (`HqLibraryUpgrade.kt:415-471`) | 429/타임아웃 뒤 흔함 | 일부만 `download-check` | 수동 요청은 결과 안내 없음 |
| WebDAV 업로드 행 | `isLocalFile` 아님, 설정 없음 (`SongActionsSheet.kt:770-814`) | 다운로드 곡/원격 곡 | 없음 | 의도된 숨김 |
| 위젯/알림 버튼 | 큐 없음, 로컬 파일 좋아요 불가 (`MediaWidgetActions.kt:74-130`, `PlaybackService.kt:2390-2403`) | 첫 실행/로컬 파일 | 없음 | 의도된 무동작 |

---

## 5. 이번 수정과 테스트

### 수정 파일

1. `app/src/main/java/com/music/bitchord/playback/AudioCache.kt`
   - `renditionCompleted: SharedFlow<String>` 추가. `SimpleCache`의 `onSpanAdded`에서 `renditionFor(key).isComplete`가 true가 되는 순간 한 번만 emit한다(`:727-775`).
   - `watchRenditionCompletion(videoId)` 추가: 기본·`#alt` 키를 먼저 등록하고, `renditionKeysFor`가 새 키를 발견하면 그 키도 등록한다(`:2902-2909`). 주기 폴링 없음.
2. `app/src/main/java/com/music/bitchord/ui/LyricsController.kt`
   - `aiTimingAvailable(request, lines)` 순수 계산 함수 추출(`:627-640`). `startTiming`은 이 함수로 초기값을 계산한다(`:691`).
   - `lyricsTimingJob` 안에서 `AudioCache.renditionCompleted`를 구독해, 현재 곡의 복사본이 완성되면 `aiAvailable`을 한 번만 켠다(`:710-721`). 완료는 버튼을 끄지 않는다.

### 조건 변경 전/후 진리표 (`aiAvailable`)

용어: G = Gemini 키 사용 가능, D = Deepgram 가능 + `wantsDeepgram` + `audioFitsInline` + 3줄 이상, A = `wav`가 고르는 복사본이 완성됨(가사 로드 시점), A′ = 로드 후 같은 복사본 완성 이벤트, S = 완료 시점 화면 켜짐.

| 입력 | r5 (수정 전) | r6 (수정 후) | `wav` 실제 결과 | 판정 |
|---|---|---|---|---|
| G=1, 줄 3개 이상 | 표시 | 표시 | Gemini가 영상 폴백 | 동일 |
| G=0, D=0 | 숨김 | 숨김 | 엔진 없음 | 동일 |
| G=0, D=1, 3줄 미만/한국어/연주곡/길이 초과 | 숨김 | 숨김 | 엔진 없음 | 동일 |
| G=0, D=1, A=1 | 표시 | 표시 | 즉시 디코드 | 동일 |
| G=0, D=1, A=0, A′ 없음 | 숨김 | 숨김 | null(부분 복사) | 동일. 거짓 양성 없음 |
| G=0, D=1, A=0, A′=1, S=1 | **숨김(재생 내내)** | **표시** | 완성본 디코드 | r5 불일치 → 고침 |
| G=0, D=1, A=0, A′=1, S=0 | 숨김 | 숨김(화면 켜짐 재계산 없음) | 화면 꺼짐 거절 | 남은 LOW |
| 최근 디코드 WAV 있음 | 표시 | 표시 | 메모리 복사 | 동일 |

기존 항 `geminiUsable`, `deepgramUsable`, `durationMs`, `wantsDeepgram`, 3줄 하한은 모두 유지했고, 바뀐 것은 `audioAvailable`을 완료 이벤트에서 **다시 읽을 수 있다**는 점뿐이다. 완료는 `aiAvailable`을 true로만 바꾼다.

### 테스트 파일

- `app/src/test/java/com/music/bitchord/playback/smart/VideoAudioTimingReadyTest.kt`
  - `theSameTrackTurnsReadyWhenItsCopyCompletes` (신규): 같은 곡이 부분→완성으로 바뀔 때 준비 판정이 false→true가 되는지 고정.
- `shared/src/jvmTest/kotlin/com/music/bitchord/data/lyrics/LyricsAiGuardTest.kt`
  - `aHandTimingTurnsAvailableWhenTheAudioFinishesCaching` (신규): Deepgram 단독에서 `audioAvailable=false→true`가 버튼 노출을 바꾸는지 고정.

### 테스트 결과

- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` — 성공.
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.playback.smart.VideoAudioTimingReadyTest' -Pbitchord.arm64Only=true --console=plain -q` — 성공(`tests="9" failures="0" errors="0"`).
- `./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.lyrics.LyricsAiGuardTest' --console=plain -q` — 성공(`tests="14" failures="0" errors="0"`).

---

## 6. 미해결 — 기기 검증 필요

- `docs/audit/check-flow.md`의 HIGH 5건은 재생 핵심 경로라 이번에 고치지 않았다. **폰 검증 필요**.
- 다운로드 곡의 로컬 파일을 AI 타이밍 오디오로 쓰지 않는 문제(3장 MED). **폰 검증 필요**.
- 완료 이벤트가 화면 꺼짐 중이면 이후 화면 켜짐에서 재계산하지 않는 문제(3장 LOW).
- 10초 미만 비디오 디코드 거절 로그(3장 LOW).
- `docs/AUDIT_FEATURES.md` #20 설정 AI 토글 키 게이트, #24 인앱 업데이트 플래그.
- 다음 곡 SYNC 스킵 이유 로그, 검색/상세 오류 재시도, 고음질 재다운로드 안내, 알림 액션 한국어 하드코딩, 빈 다운로드 화면의 고음질 전체 행.

---

## 요약 (10줄 이내)

1. 수정 파일: `AudioCache.kt`, `LyricsController.kt`, 테스트 2개(신규 2건), 이 문서.
2. 핵심 변경: 캐시가 렌디션 완성을 span 이벤트로 알리고(`renditionCompleted`), 가사 컨트롤러가 그 이벤트에서 `aiAvailable`만 다시 계산한다. 폴링/타이머 없음.
3. 지적 HIGH 고침: 부분 스트림으로 가사를 연 Deepgram 단독 사용자도 곡이 완전히 캐시되면 AI 타이밍 버튼이 나타난다.
4. 기존 안전 규칙 유지: Gemini/Deepgram/길이/3줄/한국어·연주곡 조건과 화면 꺼짐 거절은 그대로다. 완료 이벤트는 버튼을 켜기만 한다.
5. 테스트: 앱 컴파일 성공, `VideoAudioTimingReadyTest` 9건, `LyricsAiGuardTest` 14건 모두 성공.
6. 미해결 MED: 다운로드 곡 로컬 오디오 미사용(폰 검증 필요), 다음 곡 SYNC 스킵 이유 로그, 검색/상세 재시도, 고음질 재다운로드 안내.
7. 미해결 LOW: 화면 꺼짐 중 완료된 곡의 재계산, 10초 미만 디코드 로그, 알림 한국어 하드코딩, 빈 다운로드 화면 고음질 행.
