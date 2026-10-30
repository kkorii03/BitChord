# 성능·배터리 재검토 3차 (review-perf-1031-r3)

기준: `dsq/review-perf-1031-r3` (직전 2차 커밋 `d40fb30` 위). 방법: `docs/AUDIT_PERFORMANCE.md`,
`docs/audit/*.md`와 2차 문서(`review-perf-1031.md`)를 먼저 읽고 현재 코드 경로와 대조했다.
이번 차수는 2차 문서 5절이 "폰 검증 필요"로 남긴 **BitChordApp 루트 재구성(HIGH)** 과
**재생 코어 MEDIUM** 을 코드로 고치는 것이 필수 조건이었다.

## 1. 이전 감사 대조

### 1.1 `docs/AUDIT_PERFORMANCE.md` TOP 20 (2차 문서와 동일 판정, 이번 확인)

| # | 이전 항목 | 상태 | 현재 근거 |
|---:|---|---|---|
| 1 | 콜드 스타트 메인 저장소 `join()` | 고쳐짐 | `BitChordApplication.kt`의 `StartupWork.background`, join 없음 |
| 2 | `BitChordApp` 전체가 하나의 재시작 범위 | **이번 수정** | 화면별 collect를 소비 지점으로 내렸다. 3절 N5 |
| 3 | `canvasFrame` 3초 전체 재구성 | 고쳐짐 | `CanvasAwareArtworkMesh`가 자식에서 `State`를 읽는다 |
| 4 | 가사 클럭 프레임마다 전체 스캔 | 고쳐짐 | `LyricLineIndex` + `derivedStateOf` |
| 5 | 재생 서비스 500ms 가사 티커 | 고쳐짐 | 줄 경계까지 계산해 한 번만 깨운다 |
| 6 | 셀룰러 기본 LOSSLESS | 부분 | 신규 설치 HIGH, 기존 설치는 마이그레이션 유지 |
| 7 | `reportProgress` 메인 prefs/JSON | 고쳐짐 | `progressWork` 단일 스레드 |
| 8 | 취향 모델 1.5초 디바운스 | 고쳐짐 | 30초 디바운스 + 백그라운드 flush |
| 9 | 청취 기록 6샘플마다 전체 월 JSON | 고쳐짐 | 2분 스로틀, pause/정지 flush |
| 10 | `MainViewModel.init` 즉시 전 화면 로드 | 고쳐짐 | Home만 즉시, 나머지 지연 |
| 11 | 보관함 선반 메시 항상 `continuous` | 고쳐짐 | `isPlaying` + 포그라운드·저전력 게이트 |
| 12 | 가사 행마다 RenderEffect blur | 남음 | 자동 스크롤 중 `LINE_FALLOFF_BLUR` 재생성 |
| 13 | 곡마다 가사 타이밍·번역 선행 | 고쳐짐 | `TranslationAheadPolicy.plan` |
| 14 | 스마트 페이드 곡마다 전체 디코드 | 부분 | 저전력·파티에서는 시작하지 않음 |
| 15 | 마퀴 무한 | 고쳐짐 | `marqueeShouldCrawl(...)` |
| 16 | 대기열 44dp 썸네일 원본 URL | 고쳐짐 | `artworkAt(ROW_ART_PX)` |
| 17 | 가사 행 key 없음·`in` 스캔 | 고쳐짐 | key + `activeRowSet` |
| 18 | 길게 누를 때 메인 GPU→비트맵 | 고쳐짐 | `scope.launch` + 1024px 상한 |
| 19 | 설정 화면 `positionMs` 2/초 | 고쳐짐 | `PlaybackPosition` 객체 전달 |
| 20 | `remember` 없는 목록 가공 | 부분 | Library는 `remember`, Explore/Search는 남음 |

### 1.2 `docs/audit/audit-battery.md`

| 항목 | 상태 | 현재 근거 |
|---|---|---|
| HIGH AutoMix 저전력 무시 | 고쳐짐 | `BatteryPolicy.automixAnalysisActive` |
| HIGH 다음 곡 선반입 저전력 무시 | 고쳐짐 | `cachePrefetchBytesAllowed` |
| HIGH MV 소리 복구 `REPLACE` | 고쳐짐 | `APPEND_OR_REPLACE` |
| MED 아티스트 백필 1.5초 네트워크 | 남음 | 문서만. `ArtistFacts`는 화면 꺼짐 게이트 없음 |
| MED 크로스페이드 ticker IDLE에도 계속 | 남음 | 재생 코어, 문서만 |
| MED 분석 헤드 250/40/100ms 폴링 | 남음 | 재생 코어, 문서만 |
| MED 곡 버전·HQ 조회 저전력 무시 | 남음 | 재생 코어, 문서만 |
| MED `DownloadService` 4 worker 100ms 폴링 | 남음 | `DownloadService.kt` |
| MED 다운로드·분석 디코드 동시 실행 | 남음 | 재생 코어, 문서만 |
| LOW 영상 가사 측정 250ms 확인 | 남음 | 가사 싱크 코어, 문서만 |

### 1.3 그 밖의 감사 문서 (HIGH/MED)

| 문서 | 이전 HIGH/MED | 현재 상태 |
|---|---|---|
| `check-crash.md` | 다음 곡 준비 메인 `bestTier` 파싱 | 남음(재생 코어) |
| | HQ 검증 병렬 디코드 전역 세마포어 | 남음(재생 코어) |
| | 설정 가져오기 무제한 `readBytes` OOM | 고쳐짐 (32MB 상한) |
| | `AudioCache.hqHeadReady` 누수 | 남음 |
| | 시작 시 HQ 계획 파싱 | 남음 |
| `check-data.md` | NextPrep override 무시 | 남음(가사 코어) |
| | 설정/백업 숫자 범위 미검증 | 고쳐짐 |
| | HQ 교체 offset/rate 미독 | 남음(가사 코어) |
| | HQ 자동 패스 Wi-Fi 우회 | 고쳐짐 |
| | 소스 변경 시 옛 HQ verdict 유지 | 남음 |
| | 설정 공유 HQ 키 누락 | 고쳐짐 |
| `check-net.md` | Deepgram 실패 `version=1` 영구 기록 | 남음(가사 코어) |
| | 잘린 프로파일 영구 거부 | 남음(재생 코어) |
| | 긴 Retry-After 같은 URL 재시도 | 남음(재생 코어) |
| | HQ override 잔존 | 남음 |
| `check-flow.md` | 자동 믹스 standby 버전 미추종 등 | 남음(모두 재생 코어) |
| `check-lyrics.md` / `check-sync-regress.md` | 교체 파일 재검증·RECHECK override 제거 | 고쳐짐 |
| | NextPrep override, HQ shift 이중 적용 등 | 남음(가사 코어) |
| `check-concurrency.md` | `resetSongCheck`가 `songSyncGate` 밖 | 남음(가사 코어) |
| | 나머지 MED 6건 | 남음(재생 코어) |

## 2. 2차 문서에서 남긴 것 / 새로 확인한 것

| 심각도 | 파일:줄 | 증거 | 사용자 영향 | 이번 처리 |
|---|---|---|---|---|
| HIGH | `app/.../MainActivity.kt:625` | `BitChordApp` 한 함수가 87회 `collectAsStateWithLifecycle` | 좋아요·검색·가사·HQ 경쟁 어느 하나만 바뀌어도 루트 전체 재구성 | **수정** (N5) |
| MED | `app/.../data/spotify/LocalPlaylistStore.kt:69` | `init`이 메인에서 `load()` → 전체 JSON 파싱 | 큰 플레이리스트 사용자의 첫 프레임 지연 | **수정** (N6) |
| MED | `app/.../playback/smart/TrackAnalyzer.kt:130`, `:1519` | `release()`가 `results`를 지우지 않음 | 스마트 페이드 장시간 사용 시 곡 수만큼 메모리 증가 | **수정** (N7, 재생 코어) |
| MED | `app/.../playback/PlaybackService.kt:389` | `songCache`가 무제한 `ConcurrentHashMap` | 긴 세션에서 해석한 곡 객체가 계속 쌓임 | **수정** (N8, 재생 코어) |
| MED | `sharedUi/.../player/PlayerLyrics.kt:2246` | 줄 전환마다 blur RenderEffect 재생성 | 가사 자동 스크롤 중 프레임 비용 | 문서만 (폰 검증 필요) |
| MED | `app/.../data/stats/ArtistFacts.kt:192`, `:335` | 화면 꺼짐·저전력과 무관하게 1.5초 네트워크 | 화면 꺼도 아티스트 백필이 라디오를 깨움 | 문서만 |
| MED | `app/.../download/DownloadService.kt:118`, `:289` | 큐가 빈 동안 4 worker가 100ms 폴링 | 다운로드 시작/종료 순간 불필요한 wake-up | 문서만 |
| LOW | `sharedUi/.../screens/ExploreScreen.kt:126`, `SearchScreen.kt:337` | `LazyListScope` 안에서 `flatMap`/`distinctBy` 재계산 | 큰 목록 스크롤 중 소량 할당 | 문서만 |
| LOW | `sharedUi/.../screens/LibraryScreen.kt:398` | `items(visibleItems)`에 key 없음 | 선반 재사용 시 슬롯 상태 흔들림 | 문서만 |
| LOW | `app/.../playback/CrossfadeController.kt:1015` | 릴리스에서도 `Log.d` 문자열 생성 | 전환마다 문자열 할당 | 문서만 |

## 3. 이번 수정

### N5. `BitChordApp` 루트 재구성 축소 — `MainActivity.kt`

- 전: 검색어·검색 결과·제안·좋아요·고정·HQ 경쟁·가사·가사 상태·플레이리스트·홈 추가 로드·
  탐색/보관함/기록/릴리스 보관함 상태를 모두 `BitChordApp` 본문에서 읽었다. 그중 하나만
  바뀌어도 5,600줄 루트와 모든 시트가 다시 실행됐다.
- 후: 각 상태를 실제로 그리는 지점으로 collect를 내렸다.
  - 검색 탭: `query`/`results`/`filter`/`searchSource`/`searchHistory`/`suggestions`/
    `searchLoadingMore`/`searchScrollReset`/`libraryResults`/`addonResults`/`explore`를
    `TAB_SEARCH` 분기 안에서 읽는다. 상단 검색 필드는 자체 람다에서 `query`/`searchSource`를 읽는다.
  - 탐색/보관함/기록/릴리스 페이지: 각 분기 안에서 `explore`/`moodGenreShelves`/`chartCountries`/
    `library`/`history`/`releaseLibrary`를 읽는다.
  - 플레이어·트랙 메뉴: `likeStatuses`/`pinnedToOriginal`/`qualityUpgradesInFlight`/가사 7종/
    `playlists`를 `nowPlaying`·`songMenuBody`·`browseMenuBody`·플레이리스트 선택 시트 안에서 읽는다.
  - 홈: `homeLoadingMore`/`homeRecentlyPlayedLoading`을 홈 분기 안에서 읽는다.
- 진리표(전 → 후):
  - 전: 검색 입력/좋아요/가사/HQ 경쟁/플레이리스트/홈 추가 로드 변화 → 루트 + 현재 화면 재구성.
  - 후: 같은 변화 → 그 값을 읽는 화면/시트만 재구성. 루트는 내비게이션·탭·플레이어 표시처럼
    셸이 실제로 그리는 상태에만 반응한다.
- 남은 루트 읽기: `savedDownloads`/`savedCollections`(다운로드 릴리스 계산과 갱신 효과),
  `player`(곡·재생·대기열), `partyState`, `refreshing`은 셸이 직접 쓰므로 이번에 옮기지 않았다.
- 검증: `./gradlew :app:compileProdReleaseKotlin` 통과. **폰 검증 필요**
  (스크롤 중 좋아요/검색 입력 시 프레임 드랍, 첫 프레임 시간은 기기 실측).

### N6. 로컬 플레이리스트 백그라운드 로드 — `LocalPlaylistStore.kt`

- 전: `AppSettings.init`이 메인에서 `LocalPlaylistStore.init`을 부르고, 그 안에서
  `prefs.getString` + `parsePlaylists`(전체 JSON)를 동기로 실행했다.
- 후: `init`은 `StartupWork.background("local-playlists")`로 읽기를 넘긴다. `LoadOnce` 게이트가
  백그라운드 읽기와 첫 쓰기를 한 단계로 묶어, 쓰기가 먼저 와도 디스크의 행을 읽은 뒤 저장한다.
  `getPlaylist`/`exportAll`도 같은 게이트를 지나므로 백업이 빈 목록을 내보내지 않는다.
- **폰 검증 필요** (콜드 스타트 첫 프레임, 로컬 플레이리스트가 많은 계정에서 읽기 지연).

### N7. 스마트 페이드 분석 캐시 정리 — `TrackAnalyzer.kt` (재생 코어 MEDIUM)

- 전: `release()`가 `headResults`/`tailResults`는 지우지만 `results`는 남겼다.
- 후: `results.clear()`를 추가해 서비스가 내려갈 때 분석 결과도 함께 버린다. 디코드·추론 경로와
  전환 판단은 바꾸지 않았다.
- **폰 검증 필요** (스마트 페이드가 켜진 상태에서 장시간 재생 후 메모리).

### N8. 곡 캐시 상한 — `PlaybackService.kt` (재생 코어 MEDIUM)

- 전: `songCache`가 무제한 `ConcurrentHashMap`이라 검색·브라우즈·다운로드·로컬 스캔 행이
  프로세스 수명 동안 쌓였다.
- 후: `MAX_SONG_CACHE_ENTRIES = 2048`, `cacheSong`이 넘칠 때 오래된 임의 항목을 버린다.
  캐시는 최적화일 뿐이라 빠진 행은 다시 해석되며, 해석/재생 로직은 바뀌지 않았다.
  `songCacheEvictionCount`는 순수 함수로 분리해 상한 계산을 단위 테스트한다.
- **폰 검증 필요** (Android Auto 브라우즈·긴 세션에서 재해석 빈도와 메모리).

## 4. 테스트 결과

- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` 통과.
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.data.spotify.LoadOnceTest' --tests 'com.music.bitchord.playback.SongCacheEvictionCountTest' --tests 'com.music.bitchord.data.spotify.LocalPlaylistStoreTest' --tests 'com.music.bitchord.data.spotify.LocalPlaylistBackupTest' --console=plain -q` 통과.
- 새 테스트: `LoadOnceTest` 3건, `SongCacheEvictionCountTest` 3건.

## 5. 남은 것 (문서만, 폰 검증 필요)

- 재생 코어의 나머지 MED(다음 곡 준비 메인 파싱, HQ 병렬 디코드 직렬화, 가사 싱크 override,
  크로스페이드 IDLE ticker, 분석 헤드 폴링)는 이번에도 코드 경로를 바꾸지 않았다. 재생 검증이
  먼저 필요하다.
- `savedDownloads`/`savedCollections`와 `player` 상태는 여전히 루트가 읽는다. 다음 차수에서
  다운로드 릴리스 계산과 대기열 상태를 화면 안으로 더 내리면 루트 재구성이 추가로 줄어든다.
- 기기 실측(첫 프레임, 프레임 드랍, 메모리)은 이 러너에서 할 수 없다.
