# 기능 무게 평가와 경량화 (2/2: 무거운 기능)

작업 기준: `task/proxy-fallback` HEAD (ko-101 이후 모든 변경 포함, 이전 대기열 작업 결과 포함).
방법: 정적 코드 경로 읽기. 로컬 Gradle 빌드는 하지 않았고 컴파일·테스트는 CI가 수행한다.
기기 실측은 만들지 않았으며, 아래 무게는 같은 입력에서 실제로 도는 작업량을 코드로
따라간 추정치다. 사용자 기능·설정·저장 형식은 바꾸지 않았다(아래 3번은 사용자 지시로
충전 조건을 없앤 것이 유일한 동작 변경이다).

1/2 결과는 이 작업 직전 커밋이 `docs/cleanup/perf.md`에 기록했다(같은 05-features-weight
작업). 이 파일은 그 결과를 읽고 무거운 기능(2/2)을 이어서 평가·조치한 기록이다.

무게 표기: 낮음 / 중간 / 높음. "빈도"는 실제 실행 계층 기준이다.

## 1. 재생 중 상시 도는 기능

| 기능 | 위치 | 빈도 | CPU | 메모리 | 네트워크 | 배터리 | 중복·비용 메모 | 이번 조치 |
|---|---|---|---|---|---|---|---|---|
| 진행 샘플러(재생 초·통계·watchtime) | `PlaybackService.reportProgress` / `sampleWhilePlaying` | 재생 중 5초, 일시정지 시 0 | 낮음 | 낮음 | 낮음(30초 watchtime) | 낮음 | 재생 중에만 흐른다. 별도 타이머 없음 | 유지 |
| 출력 건강 확인 | `PlaybackService.checkOutputHealth` | 기존 5초 샘플 틱에 편승 | 낮음 | 낮음 | 없음 | 낮음 | 새 타이머를 만들지 않고 같은 틱 사용 | 유지 |
| 크로스페이드·자동 믹스 틱 | `CrossfadeController.tick` | IDLE 250ms, ARM 40ms, FADE 30ms | 중간 | 낮음 | 없음 | 중간 | 틱마다 `planTransition` 순수 계산. 분석 결과는 맵 조회 | 다음 단계(플랜 캐시) |
| 자동 믹스 분석 요청 | `CrossfadeController.requestAnalysisAround` | 틱마다 호출, 실제 분석은 곡당 1회 | 낮음(요청) | 낮음 | 없음 | 낮음 | `TrackAnalyzer.running`·`AnalysisStore`가 중복 차단 | 유지 |
| 가사 티커 | `PlaybackService.startLyricsTicker` | 줄 경계마다, 화면 꺼짐/백그라운드 중지 | 낮음 | 낮음 | 없음 | 낮음 | 화면·컨트롤러 조건으로 중지 | 유지 |
| 가사 프레임·스크롤 | `PlayerLyrics` | 표시 중 프레임 | 중간 | 중간 | 없음 | 중간(화면) | `derivedStateOf`, `remember(lines)`, 포그라운드 게이트 | 유지 |
| 메시 그라데이션·블러 | `MeshGradientBackground` 등 | 표시 중 애니메이션 | 중간 | 중간 | 없음 | 중간(화면) | `rememberIsForeground`·저전력 게이트 | 유지 |
| 캔버스 영상 재생 | `CanvasArtworkPlayer` | 보일 때만, 무음·오디오 트랙 비활성 | 높음(비디오 디코더) | 중간 | 캐시 우선 | 높음(화면) | `CanvasCache`, `pausedForTransition`, 저전력/포그라운드 게이트 | 유지 |
| 잠금화면 가사 시계 | `LockScreenLyricsActivity.Clock` | 화면 켜짐 1초 | 매우 낮음 | 낮음 | 없음 | 낮음 | 화면 꺼짐/백그라운드 중지 | 유지 |
| 파티 동기화 | `PartySync` | 파티 중 700ms | 중간 | 낮음 | 중간 | 중간 | 파티 정확도에 필요. 유휴 시 중지 | 유지 |
| 파티 ping/report/health | `ListenTogether` | 연결 중 15초/10초/10초 | 낮음 | 낮음 | 중간 | 낮음~중간 | 서버 관측·연결 유지에 필요 | 유지 |
| Presence | `Presence` | 포그라운드 5분 | 매우 낮음 | 낮음 | 낮음 | 낮음 | 이벤트 기반 | 유지 |
| 다운로드 진행 알림 | `DownloadService.reflectProgress` | 250ms(4/초) | 낮음 | 낮음 | 없음 | 낮음 | 알림 요구사항 | 유지 |
| 다운로드 작업 루프 | `DownloadService.work` | 100ms `takeNext` | 낮음 | 낮음 | 없음 | 낮음 | 큐가 비어도 깨어난다 | 다음 단계(이벤트화) |
| 스크로블·Discord·Last.fm | `PlaybackService` 관측 경로 | 재생·일시정지·곡 전환 이벤트 | 낮음 | 낮음 | 낮음 | 낮음 | 이벤트 기반 | 유지 |
| 위젯·잠금화면 상태 | `publishWidgetState` 등 | 이벤트·곡 전환 | 낮음 | 낮음 | 없음 | 낮음 | `updatePeriodMillis=0` | 유지 |

## 2. 곡마다 도는 기능

| 기능 | 위치 | 빈도 | CPU | 메모리 | 네트워크 | 배터리 | 중복·비용 메모 | 이번 조치 |
|---|---|---|---|---|---|---|---|---|
| 자동 믹스 분석(전체/헤드) | `TrackAnalyzer.request` → `analyze`/`analyzeHead` | 곡당 1회(헤드→전체 승격) | 높음(디코드+모델 2회) | 중간 | 헤드 1회 | 높음(1회성) | `running`, `AnalysisStore`, `requestAnalysisHead` 1회, 결과 디스크 캐시 | 유지 |
| HQ 기준 프로필 디코드 | `HqAudioVerify.referenceFeatures` | 새 후보 쌍마다 1회, 기준은 곡당 공유 | 높음(90초 디코드) | 중간(프로필) | 캐시된 YouTube 앞부분 | 높음(1회성) | 메모리 LRU·기준 락·후보/기준 병렬 디코드(1/2) | **디스크 LRU 추가** |
| HQ 후보 프로필 디코드 | `HqAudioVerify.candidateFeatures` | 후보마다 1회 | 높음(90초 디코드) | 중간 | 후보 URL 90초 | 높음(1회성) | 승산 없는 후보는 검증 생략(1/2), 쌍 판정 캐시 | 유지 |
| HQ 판정 저장 | `HqVerificationStore` | 검증 1건마다 | 낮음~중간(전체 맵 직렬화) | 낮음 | 없음 | 낮음 | 2,000개 맵 재작성 | 다음 단계(배치) |
| 가사 조회 | `LyricsRepository.lyrics` | 곡당 1회 + 화면/서비스 동시 호출 | 중간(파싱) | 낮음 | 제공처별 | 중간 | `inFlight` 합치기, 메모·디스크 캐시 | 유지 |
| 가사 타이밍 측정 | `LyricsTiming.measure` | 곡당 1회(부분→전체 재측정 가능) | 높음(디코드+정렬) | 중간 | 원곡·비디오 앞부분 | 높음(1회성) | `measuring` 락, `store`, 원곡 프로필 디스크 캐시 | 유지 |
| 노래 검증(Deepgram Whisper) | `LyricsTiming.verifySong` | 곡당 1회(가능한 경우) | 중간 | 중간(WAV) | 업로드 1회 | 중간 | `songInFlight`, 429 게이트·재시도, `heavyWorkAllowed` | 유지 |
| 가사 번역 미리 받기 | `TranslationAhead.prepare` | 곡당 1회, 다음 곡/현재 곡 | 중간 | 낮음 | AI 1회 | 중간 | `prepared`·`timingJobs`·`currentJob` 중복 차단, 번역 캐시 | 유지 |
| 버전 검색 | `YtMusicRepository.songVersion` | 곡당 1회(큐 앞뒤 준비 포함) | 낮음 | 낮음 | 검색 1회 | 중간 | `SharedLookups`, 성공/없음 캐시 | 유지 |
| 다음 곡 버전 준비 | `PlaybackService.scheduleUpcomingSongVersions` | 첫 소리 후 앞뒤 최대 4행 | 낮음 | 낮음 | 행마다 검색 | 중간 | 첫 소리 전에는 안 함, 같은 곡 패스 중복 차단 | 유지 |
| 미리 받기·read-ahead | `AudioCache.prefetchQueue` | 다음 곡 1개, 첫 소리 후 10초 유예 | 중간 | 중간 | 다음 곡 바이트 | 중간 | 큐 동일 시 무시, 미터드에서는 URL만 | 유지 |
| 소스 경쟁·대체본 | `SourceResolver.bestAcross` / `prefetchSubstitute` | 다음 곡 준비·업그레이드 | 중간 | 낮음 | 소스별 검색 | 중간 | 10분 검색 캐시, 30분 기억, 승산 없는 후보 검증 생략(1/2) | 유지 |
| 제목·가수 정규화 | `TrackMatcher.parseTitle`/`queries`/`artistVariants`/`artistNames` | 버전 검색·가사·커버·매칭에서 반복 | 중간(정규식) | 낮음 | 없음 | 낮음~중간 | 같은 입력을 여러 기능이 각자 계산 | **BoundedMemo 추가** |
| 커버 읽기·가사 쿼리 | `CoverTitles.read` / `lyricsQuery` | 가사·캔버스·스크로블·매칭 | 낮음~중간 | 낮음 | 없음 | 낮음 | 같은 제목의 클립 태그 정리를 반복 | **`tidyMemo` 추가** |
| 캔버스 조회 | `CanvasRepository.canvasFor` | 곡당 1회, 캐시 미스만 | 중간 | 낮음 | 제공처 순차 | 중간 | 64개 LRU(미스 포함), 직렬화, 첫 소리+포그라운드 게이트 | 유지 |
| Song Info | `SongInfoRepository.load` | 화면 열 때 | 중간(파싱) | 낮음 | player/next/링크 | 중간 | 5분·64개 LRU(1/2) | 유지 |
| 스크로블·감상 기록 | `ScrobbleManager` / `ListeningStats` | 곡당 1회·2분 flush | 매우 낮음 | 낮음 | 낮음 | 낮음 | 이벤트 기반 | 유지 |

## 3. 앱 시작 시 도는 기능

| 기능 | 위치 | 빈도 | CPU | 메모리 | 네트워크 | 배터리 | 중복·비용 메모 | 이번 조치 |
|---|---|---|---|---|---|---|---|---|
| 진단 로그 초기화 | `BitChordApplication.onCreate` → `TrackLog.initPersistence` | 프로세스 시작 1회 | 낮음 | 낮음 | 없음 | 낮음 | writer 1개, flush 정책(1/2) | 유지 |
| 설정·프록시 초기화 | `AppSettings.initProxy` / `ProxyPlaybackStore` / `CounterpartStore` | 시작 1회 | 낮음 | 낮음 | 없음(읽기만) | 낮음 | 작은 prefs 읽기 | 유지 |
| 암호화 저장소 열기 | `SourceRegistry.init` → `StartupWork` | 시작 1회, 단일 스레드 | 중간 | 낮음 | 없음 | 낮음 | 메인 스레드 조인 없음 | 유지 |
| 세션 스코프 조회 | `Innertube.ensureSessionScope` | 로그인 상태에서 시작 1회 | 낮음 | 낮음 | 작은 요청 1회 | 낮음 | 요청 시에도 지연 보장. 시작 선조회는 유지 | 유지 |
| 캐시·저장소 열기 | `AudioCache.init`, `Downloads.init`, `SearchHistory.init` 등 | 시작 1회 | 중간 | 중간 | 없음 | 낮음 | 필요한 시점 자기-open 폴백 | 유지 |
| 이미지 캐시 정리 | `consumeVersionUpdate` | 업데이트 첫 실행만 | 낮음~중간 | 낮음 | 없음 | 낮음 | 디스크 삭제는 IO로 이동 | 유지 |
| 업데이트·재다운로드 예약 | `AppUpdateChecker`, `MvSoundRedownload` | 시작 1회 | 낮음 | 낮음 | 조건부 | 낮음 | WorkManager, 주기 폴링 없음 | 유지 |
| 홈 첫 로드 | `MainViewModel.loadHome` | 첫 화면 1회 | 중간 | 중간 | core+최근재생+보조 | 중간 | 로드/갱신 합치기(1/2), 보조 탭 지연 | 유지 |
| 저전력·배터리 방송 | `LowPowerDevice.start` | 이벤트 | 매우 낮음 | 낮음 | 없음 | 낮음 | 주기 폴링 없음 | 유지 |

## 4. 이번에 바꾼 것

### 4.1 HQ 기준 프로필을 세션 너머로 캐시 — `HqAudioVerify.kt`

기준 프로필은 같은 YouTube 녹음의 앞 90초라서 실행마다 달라지지 않는다. 기존에는
메모리 LRU(`HqReferenceCache`, 8개)만 있어 프로세스가 끝나면 같은 90초를 다시
디코드했다. 이제 완성도 기준(90초의 90% 이상)을 통과한 프로필만
`filesDir/hq_reference`에 곡당 1파일(약 30KB)로 남기고, 다음 실행에서는 파일을 먼저
읽는다. 임시 파일에 쓴 뒤 rename하고, 64개를 넘으면 오래된 파일부터 지운다. 부분
프로필은 메모리와 같은 이유로 저장하지 않는다. 검증 결과 자체는 이미
`HqVerificationStore`가 기억하므로, 이 캐시는 "새 후보가 처음 보는 쌍"일 때만 쓰인다.
사용자에게 보이는 판정·오프셋·매치 퍼센트는 그대로다.

- 전: 새 세션 + 새 후보 → 기준 90초 디코드
- 후: 새 세션 + 새 후보 → 디스크 읽기(약 30KB), 디코드 0회
- 파일이 깨졌거나 형식이 바뀌면 읽기에서 버리고 다시 잰다(`Features.fromBytes`의
  FORMAT 검사).

### 4.2 제목·가수 정규화 메모 — `TrackMatcher.kt`, `CoverTitles.kt`, `BoundedMemo.kt`

버전 검색은 소스마다, 검색 결과마다 같은 `queries`/`parseTitle`을 다시 계산하고,
가사·커버·캔버스·매칭도 같은 행의 제목과 크레딧을 각자 정규화한다. 순수 계산이라
입력이 같으면 결과도 같은데, 지금까지는 호출마다 정규식과 문자열 분해를 다시 했다.
`BoundedMemo`(256개 LRU)로 다음 네 가지를 한 번만 계산한다.

- `parseTitle(raw, artist)`
- `artistVariants(artist)`
- `artistNames(artist)`
- `queries(target)` — title/artist만 읽는 순수 부분

커버 제목의 클립 태그 정리(`CoverTitles.normalize`)도 같은 방식으로 256개 LRU에
보관한다. 정규화 결과(`Tidy`)만 캐시하고, 채널·가수·원곡 정보로 실제 커버를 읽는
단계는 매번 현재 상태로 수행하므로 설정 변경이 즉시 반영된다.

`ArtistNameIndex`처럼 앱이 학습하며 변하는 상태를 읽는 경로는 캐시하지 않았다. 검색
결과·매칭 결과·표시 문자열은 그대로이고, 같은 입력의 반복 계산만 사라진다.

### 4.3 충전 조건 제거 — `AppSettings.heavyWorkAllowed`

`heavyWorkAllowed`는 `!lowPower || charging`이었다. 즉 저전력 상태에서도 충전 중이면
자동 AI 타이밍·백그라운드 분석을 허용했고, 충전기가 빠지면 같은 작업을 미뤘다. 사용자
지시("충전 여부로 기능을 막거나 미루는 조건 금지")에 따라 충전기를 조건에서 뺐다.
이제 `heavyWorkAllowed` 자체는 충전기를 읽지 않는다. 저전력 상태(사용자 선택·시스템
세이버·저배터리)이면 충전 여부와 무관하게 무거운 자동 작업을 미루고, 저전력이 아니면
충전 여부와 무관하게 실행한다. 수동 요청은 전과 같이 이 값을 읽지 않는다.

### 4.4 이미 되어 있던 중복 제거(회귀 확인)

- HQ 기준/후보 90초 디코드 병렬화, 기준 락, 승산 없는 후보 검증 생략(1/2).
- `LyricsRepository`의 `inFlight` 합치기·메모·디스크 캐시.
- `YtMusicRepository.songVersion`의 `SharedLookups`와 성공/없음 캐시.
- `CanvasRepository`의 미스 포함 LRU·직렬화·첫 소리/포그라운드 게이트.
- `SongInfoRepository` 5분·64개 LRU(1/2).
- 홈 첫 로드와 탭 갱신 합치기(1/2).
- `TranslationAhead`의 `prepared`·타이밍 작업 중복 차단.
- `LyricsTiming`의 `measuring` 락·원곡 프로필 디스크 캐시.
- `TrackAnalyzer`의 `running`·`AnalysisStore`·헤드 요청 1회.
- 기록 목록 키 안정화(1/2), 검색 입력 디바운스, 애드온 `SharedCalls`.

## 5. 테스트

- `shared/src/jvmTest/.../data/sources/BoundedMemoTest.kt` (신규)
  - 같은 키는 한 번만 계산
  - 한도 초과 시 가장 오래 안 쓴 항목 제거
  - `clear`/`size`
  - 계산이 예외로 끝나면 캐시하지 않음
- 기존 `TitleNormalizationTest`, `HqReferenceCacheTest`, `HqMatchPolicyTest`,
  `SubstituteOriginalTest`, `SongInfoParserTest`, `SongInfoDisplayTest`는 그대로
  통과해야 한다(공개 동작 불변).
- CI 필터: `com.music.bitchord.data.sources.*` 포함.

## 6. 다음 단계에 남긴 것

- `HqVerificationStore.save`가 검증 1건마다 2,000개 맵 전체를 다시 문자열로 만든다.
  1~2초 배치 또는 키별 저장으로 바꾸면 검증이 몰릴 때 CPU/IO가 줄어든다.
- `CrossfadeController`가 250ms마다 `planTransition`을 다시 계산한다. 분석 쌍이 같은
  동안 정적 부분을 캐시하고 시간 의존 필드만 갱신하는 방법이 남아 있다.
- `DownloadService`의 100ms `takeNext` 루프를 `Downloads` 채널/이벤트 wake-up으로
  바꾸는 것.
- `AudioCache.fetchWhole`의 미터드/비미터드 정책 세분화.
- `HqVerificationStore.bestTier`가 호출마다 전체 검증 맵을 훑는다. 곡별 인덱스를 두면
  다음 곡 준비에서 스캔을 없앨 수 있다.
- 기기 실측은 로컬 빌드 금지로 새로 만들지 못했다. 위 수치는 코드 경로 기준이다.

---

# 기능 연결 전수 검사 (1/2: 목록·연결)

작업 기준: 현재 스냅샷 HEAD. 이 저장소에는 `ko-101` 태그가 없어 커밋 비교 대신
`docs/cleanup/`의 이전 기록(가사 검증·제목 정규화·크로스페이드/자동 믹스·싱크
채점·다운로드 메뉴·HQ/다음 곡 준비·설정 공유·커버 가수 이동·충전 조건 제거)과
현재 코드의 선언/호출/설정 소비처를 대조했다. `.ds-ctx/`는 수정 금지 지시가 있어
만들지 않았고, 목록은 이 파일에 남긴다.

방법:
- `AppSettings.kt`의 `KEY_*` 152개와 공개 속성 167개를 모두 뽑아, 설정 화면
  (`SettingsSheet.kt`, `ProxySettingsSection.kt`, `AccountAndScrobblingScreen.kt`,
  `DiscordScreen.kt`, `EqualizerScreen.kt`, `SourcesScreen.kt`)과
  `app`·`shared`·`sharedUi`의 소비처를 대조했다.
- 곡 메뉴(`SongActionsSheet.kt`), 재생 화면(`sharedUi/.../PlayerControls.kt`,
  `AndroidPlayerHost.kt`), 보관함/홈(`MainActivity.kt`, `MainViewModel.kt`),
  위젯(`MediaWidget*`), 알림 버튼의 `onClick`/세션 명령 연결을 따라갔다.
- `catch { }`, `onClick = {}`, `TODO`, 항상 거짓인 플래그, 같은 일을 하는 두
  경로를 검색했다.
- 컴파일은 `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true`로,
  공유 테스트는 `./gradlew :shared:jvmTest --tests ...`로 확인했다.

상태 범례: **정상** / **죽은 스위치** / **연결 끊김** / **충돌** /
**조용한 실패 위험**(이번에 로그 추가) / **판단 필요** / **확인 불가**.

## 1. 설정 키 전수 (AppSettings 152키)

| 기능(키) | UI 위치 | 실제 소비처(근거) | 상태 |
|---|---|---|---|
| 음질 상한 Wi‑Fi/셀룰러 (`audio_quality_wifi`, `audio_quality_cellular`) | Settings > Audio quality, Sources | `AppSettings.effectiveAudioQuality` → `SourceResolver`/`AudioCache` | 정상 |
| 구버전 음질 마이그레이션 (`audio_quality`) | 없음(마이그레이션) | `AppSettings.migrateSingleQuality` | 정상(내부) |
| 다운로드 음질 (`audio_quality_download`) | Settings > Downloads | `Downloads.enqueue`/`Downloader` | 정상 |
| 업그레이드 길이 허용 (`upgrade_length_slack_seconds`) | Sources | `PlaybackService` 업그레이드 길이 판정 | 정상 |
| Wi‑Fi에서만 다운로드 (`wifi_only_downloads`) | Settings > Downloads | `AppSettings.downloadsAllowedNow`, `LikedAutoDownload` | 정상 |
| MV 사운드 자동 재다운로드 (`auto_redownload_mv_sound`) | Settings > Downloads | `MvSoundRedownload.scheduleIfEnabled` | 정상 |
| Music/BitChord로 내보내기 (`export_downloads`) | Settings > Downloads | `Downloads`/`Downloader` | 정상 |
| 크로스페이드 초 (`crossfade_seconds`) | Settings > Playback | `CrossfadeController`가 틱마다 읽음 | 정상 |
| 자동 믹스 (`smart_fade_enabled`) | Settings > Playback | `CrossfadeController.tick`, `requestAnalysisAround` | 정상 |
| 자동 믹스 비디오 (`automix_videos`) | Settings > Playback | `CrossfadeController` | 정상 |
| 자동 믹스 CPU (`automix_performance_mode`) | Settings > Playback | `TrackAnalyzer` 스레드/우선순위 | 정상 |
| 무음 건너뛰기 (`skip_silence`) | Settings > Playback | `PlaybackService.observeSettings` → `skipSilenceEnabled` | 정상 |
| 시크릿 모드 (`private_listening`, `private_listening_since`) | Settings > Playback, 곡 메뉴 | `ListeningRecorder`, `PlaybackService` 스크로블/Discord, `checkPrivateListeningExpiry` | 정상 |
| 출력 PCM (`output_pcm_mode`) | Settings > Audio | `PlaybackService` 출력 재구성, `AudioOutputPolicy` | 정상 |
| USB DAC 우선 (`prefer_usb_dac`) | Settings > Audio | `PlaybackService.observeSettings` → 출력 재구성 | 정상 |
| 라우드니스 정규화/스피커 예외 (`loudness_normalization`, `loudness_off_on_speaker`) | Settings > Audio | `PlaybackService.setupLoudnessEnhancer` | 정상 |
| Dolby Atmos (`dolby_atmos`) | Settings > Audio | `AddonClient`, `ModuleSource`, `SourceResolver`, `AudioCache` | 정상 |
| 공간 오디오 (`spatial_audio`) | Settings > Audio | `PlaybackService.applySpatialAudioEnabled` | 정상 |
| 이퀄라이저 (`equalizer_enabled`, `equalizer_mode`, `equalizer_tone_x`, `equalizer_tone_y`, `equalizer_focused`, `equalizer_balance`, `equalizer_bands`) | Equalizer | `PlaybackService` EQ 프로세서, `EqualizerCurve` | 정상 |
| 재생 속도/피치 (`playback_speed`, `playback_pitch_semitones`) | 곡 메뉴, 재생 화면 | `PlaybackService.observeSettings` → `applyPlaybackSpeed` | 정상 |
| 테마 (`theme_mode`) | Settings > Appearance | `MainActivity`/`Theme` | 정상 |
| 자동 재생/개인화 (`autoplay`, `personalised_autoplay`) | Settings > Playback | `PlaybackService` AutoPlay, `Autoplay`/`AutoplayOrder` | 정상 |
| 셔플/반복 (`shuffle_enabled`, `repeat_mode`) | 재생 화면, 알림 | `QueueShuffle`, `PlaybackService` 재시작 복원 | 정상 |
| 알림 권한 요청 기록 (`notification_permission_asked`) | 없음(1회 요청) | `MainActivity` 권한 요청 | 정상(내부) |
| 너드 통계 (`show_nerd_stats`) | Settings > Playback | 재생 화면/진단 | 정상 |
| 오디오 캐시 한도 (`audio_cache_limit_bytes`) | Settings > Storage | `AudioCache` LRU/정리 | 정상 |
| 애니메이션 줄이기 (`reduce_animation`) | Settings > Appearance | 재생 화면/백드롭 | 정상 |
| 저전력 (`low_power_mode`) | Settings > Performance | `LowPowerDevice`, `AppSettings.lowPower`, `heavyWorkAllowed` | 정상 |
| 고성능/주사율 (`high_performance_mode`, `performance_refresh_rate`) | Settings > Performance | `MainActivity.applyPerformanceMode` | 정상 |
| 앱 닫을 때 정지 (`stop_on_task_removed`) | Settings > Playback | `PlaybackService` | 정상 |
| 볼륨/상태바/곡 상태 숨김 (`hide_volume_bar`, `hide_status_bar_in_player`, `hide_song_status`) | Settings > Playback | `AndroidPlayerHost`/재생 화면 | 정상 |
| 위젯 탭 동작 (`widget_opens_player`) | Settings > Playback | `WidgetOpenIntent` | 정상 |
| 대기열 기록 (`show_queue_history`) | Settings > Playback | `AndroidPlayerHost`/재생 화면 | 정상 |
| 스와이프 다음 재생 (`swipe_to_play_next`) | Settings > Playback | 보관함/홈 행 제스처 | 정상 |
| 추천 중복 제외 (`dont_repeat_suggestions`) | Settings > Playback | `PlaybackService` 세션 추천 목록 | 정상 |
| 음악만 재생 (`prefer_music_only`) | Settings > Playback | `SongVersionController`, `PrefetchController`, `Autoplay`, `MainActivity` | 정상 |
| 비음악 구간 건너뛰기 (`skip_non_music_parts`) | Settings > Playback | `PlaybackService.loadSkipSegmentsForCurrentTrack`/`checkSkipSegments` | 정상(이번에 즉시 반영) |
| 스폰서 구간까지 (`skip_sponsor_parts`) | Settings > Playback | `PlaybackService.skipCategories`/`scheduleSkipSegmentMessages` | 정상(이번에 즉시 반영) |
| 버전 자동 정렬 (`smart_version_alignment`) | Settings > Playback | `PlaybackService` 버전 스왑, `MainActivity` | 정상 |
| 동적 블러/리퀴드 글래스 (`reduce_dynamic_blur`, `liquid_glass`) | Settings > Appearance | `Backdrop`/플레이어 UI | 정상 |
| 가사 블러/오프셋 (`lyrics_blur`, `lyrics_offset_ms`) | Settings > Appearance, 곡 메뉴 | `PlayerLyrics`, `LyricsController` | 정상 |
| 번역 언어 (`translation_language`) | Settings > Lyrics | `LyricsTranslation`, `TranslationAhead` | 정상 |
| 캔버스 (`animated_canvas`, `canvas_over_cellular`, `spotify_canvas_auto_hide`, `prioritize_spotify_canvas`, `ko_video_canvas`) | Settings > Appearance | `CanvasRepository`, `AndroidPlayerHost`, `DetailScreen` | 정상 |
| 아티스트 서명 API (`signature_api_enabled`, `signature_api_url`) | Settings > Appearance | `DetailScreen` → `ArtistSignatureRepository` | 정상 |
| 전체 화면 커버/구형 메시 (`full_bleed_artwork`, `legacy_mesh_gradient`) | Settings > Appearance | 재생 화면 백드롭 | 정상 |
| 마지막 플레이어 화면 (`last_player_screen`) | 없음(재생 화면이 기록) | `AndroidPlayerHost` | 정상(내부) |
| 가사/소스 (`synced_lyrics`, `lyrics_sources`, `lyrics_source_order`, `lyrics_sources_seen`, `prioritize_syllable_sync`) | Settings > Lyrics | `LyricsRepository`, `LyricsController` | 정상 |
| 가사 API 키 (`paxsenix_api_key`, `gemini_api_key`, `deepgram_api_key`) | Settings > Lyrics | `PaxSenix`, `LyricsTranslation`, `Deepgram` | 정상. **Deepgram 키는 이번에 백업 제외로 고침** |
| Gemini 번역 (`gemini_translation`) | Settings > Lyrics | `AppSettings.init/setGeminiTranslation` → `LyricsTranslation.geminiEnabled` | 정상 |
| 번역 미리/다운로드 유지 (`prefetch_translation`, `keep_download_translation`) | Settings > Lyrics | `TranslationAhead` | 정상 |
| 좋아요 자동 다운로드 (`auto_download_liked`) | Settings > Downloads | `LikedAutoDownload` | 정상 |
| 자동 번역/자동 타이밍/AI (`auto_translate_lyrics`, `auto_lyrics_timing`, `ko_ai_lyrics`, `ko_ai_timing_ahead`) | Settings > Lyrics | `LyricsController`, `TranslationAhead`, `AiLyrics` | 정상 |
| 가사 기본값 마이그레이션 (`ko_syllable_default_migrated`) | 없음(1회) | `AppSettings.readAll` | 정상(내부) |
| 잠금화면 가사 (`lock_screen_lyrics`) | Settings > Lyrics | `LockScreenLyrics`, `PlaybackService` | 정상 |
| 콘텐츠/차트 국가 (`ko_content_country`, `ko_charts_country`) | Settings > Content, 차트 | `YtMusicRepository`, `MainViewModel.loadCharts` | 정상 |
| 커버 채널/가수 (`ko_cover_channels`, `ko_cover_singers`) | 곡 메뉴, 자동 학습 | `CoverTitles.designatedChannels/knownSingers`, `SongActionsSheet` | 정상 |
| 숨긴 Explore (`ko_hidden_explore_categories`, `hidden_explore_seeded_v1`) | Settings > Appearance | `ExploreCategoryFilter`, `MainViewModel` | 정상 |
| 헤드폰 재생 (`ko_resume_on_headphones`) | Settings > Playback | `HeadphoneResume` | 정상 |
| Replay 장르 (`replay_genres`) | Settings > Replay | `ArtistFacts` | 정상 |
| 비음악 오디오 필터 (`filter_non_music_audio`) | Settings > Local music | `MainActivity` 로컬 스캔 | 정상 |
| 캐시 폴더 표시 (`show_cache_folder`) | Settings > Storage | `LibraryPhoneParts` | 정상 |
| 정렬/보기 (`local_music_sort`, `downloaded_music_sort`, `library_sort`, `detail_song_sorts`, `local_music_view_type`, `downloaded_music_view_type`, `home_recents_view_type`) | 보관함/홈 메뉴 | `MainActivity`, `AndroidAppUiHost` | 정상 |
| 로컬 음악 폴더 (`local_music_folder_uri`) | Settings > Local music | `LocalMediaRepository` | 정상 |
| WebDAV (`webdav_url`, `webdav_username`) | Sources | `WebDavConfig`, `WebDavRepository` | 정상 |
| SMB (`smb_host`, `smb_share`, `smb_base_path`, `smb_username`) | Sources | `SmbConfig`, `SmbRepository` | 정상 |
| 프록시 (`proxy_mode`, `proxy_host`, `proxy_port`, `proxy_backup_host`, `proxy_backup_port`, `proxy_username`, `proxy_scope`) | Sources > Proxy | `AppSettings.publishProxy` → `YouTubeProxy`, `MusicGateFallback` | 정상 |
| 고정 플레이리스트 (`pinned_playlists`) | 보관함 길게 누르기 | `MainActivity`, `AndroidAppUiHost` | 정상 |
| Last.fm (`lastfm_enabled`, `lastfm_username`, `lastfm_session_key`, `lastfm_api_key`, `lastfm_secret`, `lastfm_scrobble_enabled`, `lastfm_now_playing`, `lastfm_primary_artist_only`, `scrobble_min_duration`, `scrobble_delay_percent`, `scrobble_delay_seconds`) | Account & scrobbling | `ScrobbleManager`, `LastFM`, `PlaybackService` | 정상 |
| Last.fm 엔드포인트 (`lastfm_endpoint`) | 없음 | `BitChordApplication`, `PlaybackService` | **판단 필요**: UI/설정자가 없고 백업·빌드 값으로만 읽힌다. 기능 자체는 동작 |
| ListenBrainz (`listenbrainz_enabled`, `listenbrainz_token`, `listenbrainz_primary_artist_only`) | Account & scrobbling | `ListenBrainzManager`, `PlaybackService` | 정상 |
| Spotify 토큰 (`spotify_spdc_token`) | Spotify Canvas 로그인 | `SpotifyCanvas`, `SpotifyToken` | 정상 |
| Discord (`discord_username`, `discord_name`, `discord_avatar`, `discord_rpc_enabled`, `discord_use_details`, `discord_show_audio_quality`, `discord_advanced_mode`, `discord_status`, `discord_activity_type`, `discord_activity_name`, `discord_button_1_text`, `discord_button_1_visible`, `discord_button_2_text`, `discord_button_2_visible`, `discord_info_dismissed`) | Settings > Discord | `PlaybackService` presence, `DiscordScreen` | 정상 |
| 온보딩/버전 (`onboarding_complete`, `last_version_code`) | 온보딩/업데이트 | `MainActivity`, `consumeVersionUpdate` | 정상(내부) |

결과: 선언만 있고 어디서도 읽히지 않는 **죽은 스위치 0건**. 값이 바뀌어도
동작이 바뀌지 않는 후보는 `skip_non_music_parts`/`skip_sponsor_parts`(재생 중
다음 이벤트까지 지연)와 `lastfm_endpoint`(UI 없음)였고, 앞의 둘은 이번에 고쳤다.

## 2. 버튼·메뉴 연결

| 화면/버튼 | 연결(근거) | 상태 |
|---|---|---|
| 곡 메뉴: 재생 다음/대기열/라디오 | `MainActivity.kt:4944` `SongActionsSheet` 콜백 → `playNext`/`addToQueue`/`startRadio` | 정상 |
| 곡 메뉴: 다운로드/캐시에서 다운로드 | `SongActionsSheet.DownloadRow`, `MainActivity.downloadSong/downloadFromCache` | 정상 |
| 곡 메뉴: WebDAV 업로드 | `onUploadToWebDav`(로컬 파일+설정 시에만) → `uploadToWebDav` | 정상 |
| 곡 메뉴: 좋아요/싫어요/보관함 | `viewModel.toggleLike/toggleDislike`, `LikeState` | 정상 |
| 곡 메뉴: 재생목록 추가/제거 | `PlaylistPickerSheet`, `removeFromPlaylist` | 정상 |
| 곡 메뉴: 캐시에서 제거 | `AudioCache.holds` 확인 후 `removeFromCache` | 정상 |
| 곡 메뉴: 원곡으로 되돌리기/음질 업그레이드 | `controller.revertToOriginal/upgradeQuality` | 정상 |
| 곡 메뉴: 오디오/비디오 버전 전환 | `onToggleAudioVersion` → `switchToVideo/switchToMusicOnly` | 정상 |
| 곡 메뉴: 커버 원제목 복사/커버 채널 지정 | `SongActionsSheet` 클립보드, `AppSettings.setCoverChannel` | 정상 |
| 곡 메뉴: 가수/앨범 열기 | `coverSingerChannelId`/`CoverTitles.creditedArtist` → `openPage` | 정상(커버 가수 이동) |
| 곡 메뉴: Song Info/가사 오프셋/문제 신고/로그 복사 | `SongInfoDialog`, `showLyricsOffset`, `TrackDiagnostics.mark`, `TrackLog.forTrack` | 정상 |
| 곡 메뉴: 수면 타이머/재생 속도/시크릿 | `SleepTimer`, `setPlaybackSpeed`, `setPrivateListening` | 정상 |
| 재생 화면: 재생/일시정지/이전/다음/셔플/반복/좋아요/대기열/가사/출력/볼륨 | `PlayerControls.kt`, `PlaybackService` 세션 명령 | 정상 |
| 홈/보관함: 검색/정렬/보기/탭/새로고침 | `MainActivity`/`MainViewModel` | 정상 |
| 설정: 데이터 내보내기/가져오기 | `SettingsSheet.kt:1639` → `Backup.exportTo/importFrom` | 정상(내보내기에서 Deepgram 키 제외로 고침) |
| 설정: 캐시 비우기/폴더 선택/WebDAV·SMB/프록시/소스 편집 | `SettingsSheet`, `SourcesScreen`, `ProxySettingsSection` | 정상 |
| 설정: 이퀄라이저/가사 소스/번역 언어/콘텐츠 국가/숨긴 카테고리 | `EqualizerScreen`, `LyricsSourcesDialog`, `TranslationLanguageDialog`, `ContentCountryDialog` | 정상 |
| 설정: Gemini/Deepgram 키/서명 API/Discord/Last.fm/ListenBrainz/Wear OS | `MainActivity` 알림/화면 라우팅 | 정상 |
| 위젯: 재생/다음/이전/좋아요/셔플/열기 | `MediaWidgetActions.kt:35`, `WidgetOpenIntent` | 정상 |
| 알림: 좋아요/셔플 | `PlaybackService` 세션 명령 | 정상 |
| 빈 람다/비활성 자리 | `DetailScreen.kt:1792`(구독 정보 없음), `MainActivity.kt:6311`(메뉴 내부 클릭 차단), `SongActionsSheet.kt:814`(업로드 진행 중) | 의도된 비활성, 정상 |

## 3. 백그라운드 기능

| 기능 | 위치 | 연결 | 상태 |
|---|---|---|---|
| 크로스페이드/자동 믹스 | `CrossfadeController.kt:110`, `:777`, `:988` | 설정 읽기, 틱, 분석 요청. 컨트롤러 1개만 생성 | 정상 |
| 노래 버전 전환 | `SongVersionController.kt:113`, `PlaybackService.kt:2338` | `SharedLookups` 중복 차단, 크로스페이드/버전 스왑 상호 배제 | 정상 |
| 다음 곡 선반입 | `PrefetchController.kt:72`, `AudioCache.prefetchQueue` | 한 경로만 사용, 같은 큐 요청 무시 | 정상 |
| 프록시/일본 경유 | `AppSettings.publishProxy:2590` | `YouTubeProxy.update`, `MusicGateFallback.resetAll` | 정상 |
| 가사 싱크/번역 | `LyricsController`, `LyricsTiming`, `TranslationAhead` | 소스·타이밍·번역 캐시/중복 차단 | 정상 |
| 노래 검증(Whisper) | `LyricsTiming.verifySong:1167` | `LyricsController`가 AI 타이밍/저전력/키 조건에서 호출 | 정상 |
| 싱크 채점 모드 | `SyncTestMode.kt:34,49,81` | `sync-test.flag`가 있을 때만 WAV/덤프 | 확인 불가(외부 플래그, 의도) |
| 진단 로그 | `TrackLog`, `TrackLogPersistence` | 이벤트/버스트 flush, 내보내기 | 정상 |
| 시크릿 모드 | `AppSettings.privateListening`, `ListeningRecorder`, `PlaybackService` | 기록/스크로블/Discord 차단 | 정상 |
| 최근 재생/감상 기록 | `ListeningStats`, `RecentShelf`, `LocalPlayHistory` | 재생 이벤트 기록 | 정상 |
| 다운로드 | `DownloadService.kt:116`, `Downloads`, `LikedAutoDownload`, `MvSoundRedownload` | 큐/진행/자동 다운로드 | 정상(유휴 100ms 폴링은 다음 단계) |
| 스크로블 | `ScrobbleManager`, `LastFM`, `ListenBrainzManager` | 재생/일시정지/곡 전환 이벤트 | 정상 |
| 함께 듣기 | `PartySync.kt:225`, `ListenTogether.kt:1304,1332` | 파티 중에만 틱/ping/report | 정상(파티 동기화에 필요) |
| 위젯 | `MediaWidgetActions`, `MediaWidgetSnapshot` | 세션 명령/스냅샷 | 정상 |
| SponsorBlock | `SkipSegments.kt`, `PlaybackService.kt:9498` | 설정 변경 즉시 반영, HTTP 실패 로그 추가 | 정상(이번에 고침) |
| ListenBrainz 추천 | `SimilarCandidates.kt:97,164` | 토큰 없는 유사곡 API → AutoPlay | 정상 |
| HQ 소스/등급/다음 곡 준비 | `HqAudioVerify.kt:129`, `HqVerificationStore`, `SourceResolver.bestAcross` | 검증·캐시·다음 곡 준비 | 정상 |
| 설정 공유(백업) | `Backup.kt:71,115`, `AppSettings.exportPrefs/importPrefs` | 설정/기록/재생목록 내보내기·가져오기 | 정상(비밀키 제외 보강) |
| 커버 가수 이동 | `CoverTitles.creditedArtist:301`, `ArtistCredits.coverSingerChannelId:116` | 곡 메뉴/크레딧/스크로블 | 정상 |
| 충전 조건 제거 | `AppSettings.heavyWorkAllowed:489` | `!lowPower`만 사용, 충전 여부 미사용 | 정상 |

## 4. 이번에 고친 것

### 4.1 백업이 Deepgram API 키를 내보내던 문제 — `AppSettings.kt:2742`

`SECRETS`에 `KEY_DEEPGRAM_API_KEY`가 빠져 있었다. Gemini·PaxSenix·Last.fm·
ListenBrainz 키는 제외하면서 Deepgram만 일반 설정처럼 `exportPrefs()`에 실렸고,
백업 파일을 공유하면 계정 키가 함께 나갔다. `SECRETS`에 추가해 내보내기와
가져오기 모두에서 제외했다. 기존 백업 파일을 가져올 때도 이 키는 덮어쓰지 않고
기기의 키를 유지한다. 설정 화면·동작·백업 스키마는 그대로다.

### 4.2 SponsorBlock 스위치가 재생 중 다음 이벤트까지 무시되던 문제 — `PlaybackService.kt:8492,9498`

`skip_non_music_parts`/`skip_sponsor_parts`는 `checkSkipSegments()`가 불릴 때만
읽혔다. 그 함수는 재생/일시정지 변경이나 이미 예약된 구간 메시지에서만 불려서,
재생 중에 스위치를 켜면 다음 곡/일시정지까지 적용되지 않았다. 이제 두 설정의
변화를 `observeSettings()`에서 직접 받아 `refreshSkipSegmentsForSettings()`로
현재 곡에 적용한다. 끄면 예약 메시지를 취소하고, 켜면 이미 받은 구간을 재사용해
카테고리만 다시 고른다. 새 타이머·폴링은 없다.

### 4.3 조용한 실패에 TrackLog 경고 — `PlaybackService.kt:430,464,513,544,571,649`, `SkipSegments.kt:266,274`

Android Auto용 최근재생/빠른선택/좋아요/홈/재생목록/폴더 갱신이 예외를
`emptyList()`/`null`로 삼키고 있었다. 이제 실패 종류만(예외 클래스 이름)
`TrackLog.w`로 남긴다. SponsorBlock은 HTTP 비성공 응답과 네트워크 실패를
`TrackLog.w`로 남긴다. 키·검색어·계정 정보는 쓰지 않는다.

### 4.4 확인만 한 것

- 같은 일을 두 곳에서 하는 경로: 버전 스왑과 크로스페이드가 서로를 배제하고
  (`PlaybackService.kt:2338`), 선반입은 `PrefetchController` 한 곳이며,
  좋아요 자동 다운로드는 두 진입점이 있어도 `alreadyHandled`로 막는다.
- 항상 꺼진 플래그: `AppUpdateChecker.CHECK_UPSTREAM_RELEASES=false`는 포크
  서명상 업스트림 APK를 설치할 수 없어 의도적으로 끈 것이고, 대신
  `noticeUpstream`이 하루 1회 알림을 띄운다. 도달 불가 기능 아님.
- `.ds-ctx/`는 지시대로 수정하지 않았다.

## 5. 남긴 것(판단 필요/다음 단계)

- `lastfm_endpoint`: 설정 UI가 없어 일반 사용자는 바꿀 수 없다. 의도된 숨김인지
  확인 필요(기능 자체는 읽힘).
- Android Auto의 나머지 `catch (_: Exception) { emptyList() }`(재생목록/검색/홈
  폴백 등 `PlaybackService.kt:10400~10830`)는 같은 패턴이 남아 있다. 이번에는
  캐시 경로만 로그를 넣었다.
- `DownloadService.work`의 유휴 100ms 폴링(`DownloadService.kt:125`)은 배터리
  우선 원칙의 다음 후보. 이벤트 기반 wake-up으로 바꾸는 것은 별도 작업.
- `CanvasArtworkPlayer`의 3초 프레임 readback(`CanvasArtworkPlayer.kt:351`)은
  화면·전체화면 조건으로 게이트되어 있으나, 배터리 실측 후 주기 조정 여지가 있다.
- `AppSettings.exportPrefs`는 여전히 `KEY_LAST_VERSION_CODE` 외 기기 로컬 키를
  `DEVICE_LOCAL`로 관리한다. 새 기기 로컬 키가 추가되면 여기에 등록해야 한다.

## 6. 테스트

- 신규 `shared/src/jvmTest/.../data/cover/CoverCreditTest.kt`
  - 커버 byline에서 가수/원곡자 분리
  - 파싱된 커버 필드가 byline보다 우선
  - 일반 곡은 artist 그대로, 원곡자 null
- `./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.cover.CoverCreditTest' --console=plain -q` 통과.
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` 통과.
- 기존 `SkipSegmentsTest`, `BackupLibraryRoundTripTest`, `TitleNormalizationTest`,
  `HqMatchPolicyTest`, `HqTierTest`, `DownloadedArtworkCandidatesTest`는 동작
  불변이므로 그대로 통과해야 한다.
