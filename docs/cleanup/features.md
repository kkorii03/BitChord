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
