# 배터리 최적화 — 재생·화면 꺼짐·백그라운드 전수 조사

작업 기준: `task/proxy-fallback` HEAD (ko-101 이후 변경 포함). 로컬 Gradle 빌드는
하지 않았고, 컴파일·테스트는 CI(`ko-wip-check`)가 수행한다. 아래 빈도·영향은
기기 프로파일링이 아니라 코드 경로와 상수에서 읽은 추정치다. 이번 변경은
충전 여부로 기능을 막거나 미루는 조건을 새로 만들지 않았다.

## 요약 표

| 항목 | 위치 | 빈도 | 추정 영향 | 조치 |
|---|---|---|---|---|
| 재생 진행 샘플러 | `PlaybackService.reportProgress` / `sampleWhilePlaying` | 재생 중 5초, 일시정지 시 0 | 낮음 | 이미 이벤트 기반(`samplerPlaying`). 유지 |
| 출력 건강 확인 | `PlaybackService.checkOutputHealth` | 위 샘플러와 동일(5초) | 낮음 | 새 타이머 없이 기존 틱에 편입. 유지 |
| 크로스페이드 틱 | `CrossfadeController.start` | 재생 중 250ms, ARM 40ms, FADE 30ms, 일시정지·무음 0 | 중간 | 일시정지/무음은 이미 이벤트 대기. 재생 중 플랜 계산은 제안 |
| AutoMix 분석 | `TrackAnalyzer.request` | 곡당 1회(헤드 1회 + 완전 1회), 결과 디스크 캐시 | 높음(1회성) | 곡당 1회·캐시 유지. 저전력 지연은 제안 |
| HQ 기준 오디오 디코드 | `HqAudioVerify.referenceFeatures` | 후보 검증마다 90초 디코드 | 높음 | **고침**: YouTube 기준 프로파일 LRU 캐시 |
| HQ 후보 디코드 | `HqAudioVerify.candidateFeatures` | (곡, 소스, 트랙)당 1회, 승인/거절 캐시 | 높음(1회성) | 결과 캐시 유지 |
| HQ 판정 저장 | `HqVerificationStore.save` | 검증/거절마다 prefs 전체 재작성 | 낮음~중간 | 제안: 디바운스 저장 |
| 가사 자막 틱 | `PlaybackService.startLyricsTicker` | 줄 경계마다 1회, 화면 꺼짐 시 중지 | 낮음 | 이미 화면 꺼짐·컨트롤러 조건으로 중지 |
| 가사 프레임 클럭 | `PlayerLyrics.rememberLyricClock` | 표시 중·재생 중 프레임마다 | 높음(표시 중) | 이미 `rememberIsForeground`로 게이트 |
| 메시 그라데이션 | `MeshGradientBackground` | 포그라운드·애니메이션 중 프레임마다 | 높음(표시 중) | 이미 포그라운드 게이트 + 정지 후 스냅 |
| 캔버스 영상 재생 | `CanvasArtworkPlayer` | 보이는 동안 비디오 디코드 | 높음(표시 중) | 이미 포그라운드·전환 중 일시정지 |
| 캔버스 검색 | `rememberCanvasArtwork` | 곡당 1회, 앨범 도착 대기 600ms | 중간(네트워크) | **고침**: 백그라운드에서는 검색하지 않음 |
| 잠금화면 시계 | `LockScreenLyricsActivity.Clock` | 화면 켜짐 1분 | 매우 낮음 | **고침**: 화면 꺼짐/백그라운드에서 중지 |
| 잠금화면 가사 패널 | `StandaloneLyricsPanel` | 표시 중 프레임 | 높음(표시 중) | 가사 클럭과 같은 포그라운드 게이트 사용 |
| 다운로드 대기 폴링 | `DownloadService.work` | 큐가 빈 동안 100ms, 최대 2초 | 낮음 | 제안: `Channel`/이벤트 기반 인계 |
| 다운로드 진행 알림 | `DownloadService.reflectProgress` | 250ms(4/초) | 낮음 | 알림 요구사항. 유지 |
| 파티 동기화 틱 | `PartySync` | 파티 중 700ms | 중간 | 동기화 정확도에 필요. 유지/제안 |
| 파티 ping | `ListenTogether.pingLoop` | 연결 중 15초 | 낮음~중간 | 연결 유지에 필요. 유지 |
| 파티 report | `ListenTogether.reportLoop` | 연결 중 10초 | 낮음~중간 | 서버 로그/드리프트 관측용. 유지 |
| 파티 health poll | `ListenTogether.startHealthMonitor` | 화면 열림+소켓 없음일 때 10초 | 낮음 | 이미 `shouldSkipIdleProbe`로 게이트 |
| Presence | `Presence` | 앱 열림 중 5분 | 매우 낮음 | 유지 |
| YouTube watchtime ping | `PlaybackTracker.onProgress` | 새 재생 30초마다 1회 | 낮음 | 이미 오디오 진행량 기준. 유지 |
| 가사 번역 선행 | `TranslationAhead` / `LyricsTiming` | 곡당 1회, 비미터드+heavy 허용 시 | 중간(1회성) | 이미 정책 게이트. 유지 |
| Discord/Last.fm/ListenBrainz | `PlaybackService` 관측 경로 | 재생·일시정지·곡 전환 이벤트 | 낮음 | 이벤트 기반. 유지 |
| 취향/아티스트 저장 | `TasteSignals` / `ArtistFacts` | 디바운스된 쓰기 | 낮음 | 이미 디바운스. 유지 |
| 캐시 정리·라우드니스 재시도 | `scheduleForeignCachePurge` / `scheduleLoudnessRetry` | 곡 전환 후 6~30초 1회 | 낮음 | 원샷 지연. 유지 |
| 업데이트 확인 | `AppUpdateChecker` | 사용자/앱 시작 시 | 낮음 | 주기 작업 없음. 유지 |
| WorkManager | `MvSoundRedownload` | 원샷 1회, 주기 작업 없음 | 낮음 | 유지 |
| WakeLock/WifiLock | 앱 전체 | 없음 | 없음 | 유지 |
| 센서·위치 | 앱 전체 | 없음 | 없음 | 유지 |
| 네트워크 재시도 | `Innertube.withRetry`, `AddonClient.body`, `StreamRefusalRecovery` | 실패 시 2~3회, 백오프 | 낮음 | 이미 백오프·상한. 폭주 없음 |
| 진단 로그 디스크 | `TrackLogPersistence` | 기존 버스트당 flush(초당 다수 가능) → 최대 3초에 1회/64KB, 유휴 시 0 | 낮음~중간 | **고침**: 1.5초 유휴/3초 상한/64KB로 묶어 쓰기 |
| 청취 기록 저장 | `ListeningStats.flush` | 재생 중 최대 2분에 1회, 일시정지·정지 시 강제 | 낮음 | 이미 스로틀·강제 flush |
| 재생 상태 저장 | `PlaybackService.playbackStateThrottle` | 재생 중 30초 | 낮음 | 유지(재개 지점 보존) |
| 소스 애드온 검색 | `SourceResolver.bestAcross` / `matchAndStream` | 소스·쿼리 변형별 1회, 10분 메모 | 중간(네트워크) | 다음 곡 준비와 본 재생이 메모 공유. 유지 |
| 곡 버전 미리 찾기 | `PlaybackService.scheduleUpcomingSongVersions` | 곡당 앞뒤 최대 4행 | 중간(네트워크) | 기능상 필요. 저전력/미터드 게이트는 제안 |
| 다음 곡 전체 선반입 | `AudioCache.fetchWhole` | 비미터드에서 다음 곡당 1회 | 중간~높음(네트워크/디스크) | 재생 연속성 기능. 정책 조정은 제안 |
| 자동 믹스 플랜 | `TransitionPlanner.planTransition` | 크로스페이드 틱마다 | 중간(CPU) | 제안: (곡 쌍, 분석) 단위 플랜 캐시 |
| Compose 재구성 | `SmartAnalysis` / `TransitionWindow` StateFlow | 틱마다 값 설정 | 낮음 | data class 동등성으로 같은 값은 방출 안 함. 유지 |
| 로딩/셔머/블링크 애니메이션 | `Skeletons`, `Common`, `PlayerControls`, `PlayerLyrics`, `ListenTogetherScreen` | 표시 중 프레임 | 낮음~중간 | 제안: 공통 포그라운드 게이트(현재는 화면 꺼짐 시 Compose 프레임 클럭에 의존) |
| 앨범 아트 요청 | `PlayerArtwork.request` | 곡/시도 변경 시 1회 | 낮음 | 이미 `derivedStateOf`로 재사용 |
| 스크롭 타이머 | `ScrobbleManager` | 곡당 1회 지연 | 매우 낮음 | 유지 |
| 수면 타이머 | `SleepTimer` / `watchSleepTimer` | 설정 시 1회 | 매우 낮음 | 유지 |
| 헤드폰 재생 | `HeadphoneResume` | 연결 이벤트 1회 | 매우 낮음 | 유지 |
| 블루투스 추적 | `BluetoothAudioTracker` | 장치 변경 이벤트 | 낮음 | 유지 |
| 위젯 갱신 | `widget_media_*.xml` | `updatePeriodMillis=0`, 이벤트만 | 낮음 | 유지 |
| 배터리/절전 방송 | `LowPowerDevice` | 시스템 방송 시 | 매우 낮음 | 이벤트 기반. 유지 |
| 화면 상태 방송 | `PlaybackService.screenStateReceiver` | 화면 on/off 시 | 매우 낮음 | 이벤트 기반. 유지 |

## 이번에 고친 것

### 1. 진단 로그 묶어 쓰기 (`TrackLogPersistence`)

- 기존: 큐에 쌓인 로그를 버스트 단위로 모두 쓴 뒤 `flush()`했다. 로그가 초당
  한 줄씩 드문드문 들어오면 사실상 줄마다 한 번씩 write syscall이 나갔다.
- 변경: `TrackLogFlushPolicy`(순수 함수)로 flush 시점을 정한다.
  - 마지막 줄 후 1.5초 조용하면 flush
  - 계속 들어와도 3초마다 flush
  - 대기 문자가 64KB를 넘으면 즉시 flush
  - 버퍼가 비면 writer는 `queue.take()`로 무기한 대기한다(유휴 중 주기
    깨우기 없음).
- `TrackLog.flush()`, 크래시(`appendSync`), 백그라운드 전환, trim memory,
  프로세스 종료 경로는 그대로 즉시 flush한다. 로그 내용·순서·로테이션은
  바뀌지 않는다.
- `flush()`는 큐가 비어 보여도 `FlushSignal`을 넣도록 바꿨다. writer가 마지막
  줄을 꺼낸 뒤 아직 쓰기 전인 경우 예전에는 flush가 그 줄을 놓칠 수 있었다.

### 2. HQ 기준 오디오 프로파일 캐시 (`HqReferenceCache`)

- `HqAudioVerify`는 후보마다 YouTube 기준 90초를 다시 디코드했다. 같은 곡의
  후보가 여러 소스에서 동시에 올라오면 기준 디코드가 후보 수만큼 중복됐다.
- 90초 창의 90% 이상을 덮는 프로파일만 최대 8곡 LRU로 메모리에 보관한다.
  부분 프로파일은 캐시하지 않아, 나중에 더 많은 바이트가 도착하면 예전처럼
  다시 읽는다. 판정 결과·거절 캐시(`HqVerificationStore`)는 그대로다.

### 3. 보이지 않는 캔버스 검색 중지 (`rememberCanvasArtwork`)

- 재생은 백그라운드에서 계속되지만 캔버스는 장식이다. 앱이 화면에 없을 때
  캔버스 검색(및 앨범 대기)을 시작하지 않는다. 캐시된 클립은 그대로 읽어
  복귀 즉시 보여 준다. 포그라운드 복귀 시 effect가 다시 시작된다.

### 4. 잠금화면 시계 중지 (`LockScreenLyricsActivity.Clock`)

- 화면이 꺼진 뒤에도 composition이 살아 있으면 1분마다 깨어났다. 이제
  `rememberIsForeground`가 false면 `produceState` 루프를 시작하지 않는다.
  보이는 동안의 1분 갱신은 그대로다.

## 제안만 남긴 것 (동작이 달라질 수 있어 손대지 않음)

1. **자동 믹스 플랜 캐시**: `CrossfadeController`가 재생 중 250ms마다
   `planTransition`을 다시 계산한다. 플랜은 분석·길이·곡 쌍에만 의존하고
   시간은 arm 시점 판단에만 쓰이므로, 쌍/분석 버전별로 캐시하고 시간 조건만
   다시 평가하면 CPU를 줄일 수 있다.
2. **HQ 판정 저장 디바운스**: `HqVerificationStore.save`가 검증마다 최대
   2,000개 항목을 다시 문자열로 만든다. `StoredVerdictCache`처럼 1~2초
   디바운스하면 같은 곡의 여러 후보 검증에서 쓰기를 합칠 수 있다.
3. **다운로드 대기 이벤트화**: `DownloadService`의 100ms `takeNext` 폴링을
   `Downloads`의 채널/플로우 wake-up으로 바꾸면 큐 시작·종료 시의 빈 폴링이
   사라진다.
4. **다음 곡 전체 선반입 정책**: `AudioCache.fetchWhole`은 비미터드에서 다음
   곡 전체를 받는다. 저전력에서는 헤드/일부 구간만 받고 재생 시 이어 받는
   정책이 배터리에 유리하다. 데이터는 무제한이라는 전제와 충돌하지 않는다.
5. **곡 버전 미리 찾기 게이트**: `scheduleUpcomingSongVersions`가 곡당 최대
   4행을 미리 조회한다. 저전력·미터드에서 범위를 1행으로 줄이는 선택지.
6. **저전력 분석 지연 복원**: `TrackAnalyzer.request`에서 최근 제거된
   `heavyWorkAllowed` 조기 반환을, 충전 조건 없이 `AppSettings.lowPower`만으로
   되살리면 저전력에서 무거운 디코드/추론을 미룰 수 있다. AutoMix 분석이
   늦어지는 대신 배터리를 아낀다.
7. **파티 주기**: `PartySync` 700ms, ping 15초, report 10초는 파티 동기화
   정확도와 연결 유지에 필요하다. 배터리 세이버에서만 간격을 늘리는 정책은
   별도 결정이 필요하다.

## 추가 조사 결과

### 진단 로그(TrackLog) 디스크 쓰기

- 쓰기 자체는 단일 `bitchord-trail` 스레드가 맡고, 호출 스레드는 큐에 넣기만
  한다. 재생 중 로그는 상태 변화·전환·검증·오류 때 발생하며, 초당 고정
  주기의 로그는 없다. 다만 네트워크/가사 경로가 몰리면 짧은 버스트가 생길 수
  있어 이번에 flush를 묶었다.
- 파일은 `trail-0..2.txt`, 각 1.7MB(합 ~5MB)로 로테이션된다. 크기는 그대로다.
- 크래시 직전 마지막 줄은 `appendSync`로 동기 flush된다. 일반 로그는 최대
  1.5초(유휴)~3초(연속) 늦게 디스크에 닿을 수 있다.

### 네트워크 재시도 폭주

- `Innertube.withRetry`: 최대 3회, 500ms→1s 백오프. 타임아웃은 재시도하지
  않는다.
- `AddonClient`: 429만 `Retry-After`/지수 백오프(500ms~8s, 최대 2회)로
  기다린다. 4xx는 즉시 실패, 5xx는 다음 곡으로 미룬다.
- `StreamRefusalRecovery`: mid-play 거절은 direct→proxy→같은 URL 재시도
  순서로 제한되고, 같은 URL 재시도 횟수와 전체 단계 수에 상한이 있다.
- `ProxyHealth`: 실패 창(5분)과 경로 전환 유예(30초)가 있어 실패 직후 모든
  요청이 동시에 재시도하지 않는다.
- 결론: 즉시 반복 요청 폭주는 발견하지 못했다. 추가 변경 없음.

### 화면에 안 보이는 이미지·캔버스 로딩

- 캔버스 **재생**은 이미 `rememberIsForeground`와 `pausedForTransition`으로
  막혀 있었다. 캔버스 **검색**은 백그라운드에서도 나갈 수 있어 이번에 막았다.
- 메시 그라데이션은 포그라운드가 아니면 `snapTo`로 멈추고, 캔버스 프레임
  캡처 루프도 `shouldRefreshFrames`가 포그라운드/렌더 여부를 본다.
- 앨범 아트는 Coil 요청이 곡/재시도 단위로 재사용되어 재구성마다 새 요청을
  만들지 않는다.

### 소스 애드온 검색이 곡마다 여러 번 도는지

- `SourceResolver.matchAndStream`은 `TrackMatcher.queries`의 각 변형을 순서대로
  시도하고, `searchAnswers`에 (소스, 대상, 요청, 쿼리) 단위로 10분간 메모한다.
  다음 곡 준비와 본 재생이 같은 질문을 하면 메모가 답한다.
- `AddonClient.search`도 (쿼리, 파라미터) 단위 10분 캐시를 별도로 둔다.
- 따라서 같은 곡을 반복 재생하거나 준비→재생으로 넘어갈 때 실제 HTTP 검색이
  다시 나가지는 않는다. 다만 제목 정규화로 쿼리 변형 수가 늘었고, 변형마다
  첫 시도는 네트워크다. 실패한 변형도 빈 결과로 메모된다.

## 그 외 ko-101 이후 변경

노래 검증, 가사 제목 정규화, 크로스페이드·자동 믹스, 싱크 채점 모드, 다운로드
곡 메뉴·사진, 고음질 소스·등급·다음 곡 준비, 설정 공유, 커버 가수 이동,
충전 조건 제거는 모두 확인했다. 이 중 재생 중 주기 타이머를 새로 만드는
변경은 없었고, 배터리와 직접 관련된 경로(자동 믹스 분석/플랜, HQ 검증,
다음 곡 선반입, 가사 검색 변형, 캔버스, 진단 로그)는 위 표에 포함했다.
설정 공유·커버 가수 이동·다운로드 메뉴/사진은 사용자 조작 시 1회성 작업이다.

## 테스트

- `shared/src/jvmTest/.../data/TrackLogPersistenceTest.kt`
  - 큐에 넣은 100줄이 flush 후 전부 디스크에 남는지
  - `TrackLogFlushPolicy`의 3초 상한·64KB 상한 경계
- `shared/src/jvmTest/.../data/sources/HqReferenceCacheTest.kt`
  - LRU 상한·최근 사용 순서·clear
  - 90초 창의 90% 이상만 캐시 대상으로 인정하는지
- CI 필터(`com.music.bitchord.data.sources.*`, `TrackLogPersistenceTest`)에
  포함된다.

## 남은 위험

- 로그 flush가 최대 3초 늦어질 수 있으므로, 크래시가 아닌 강제 종료 직전
  마지막 1~3초 로그는 유실될 수 있다. 크래시 핸들러 경로는 영향 없다.
- HQ 기준 프로파일 캐시는 세션 메모리만 쓴다. 프로세스가 재시작되면 첫 후보
  검증에서 한 번 다시 디코드한다.
- 캔버스 검색을 포그라운드로 제한했으므로, 백그라운드에서 곡이 바뀐 뒤 앱으로
  돌아오면 클립 검색이 그때 시작된다. 캐시에 있던 클립은 즉시 보인다.
