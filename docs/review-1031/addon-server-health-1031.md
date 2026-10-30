# addon-server-health-1031 — 고음질 애드온 서버 신뢰성 검토

기준: `task/fix-1030` (= 이 작업의 시작점 `867ca072`). 범위는 고음질 애드온
(MOnOchrome/onrender 무료, soundfean/vercel 등)의 검색·스트림·검증 다운로드가
429·503(잠듦)·느린 응답을 만났을 때의 처리다. 2026-10-30~31 코드 정적 추적 +
JVM 단위 테스트. 실제 기기 로그는 첨부하지 않았고, 아래의 성적표 해석은 코드가
만들 수 있는 결과만 근거로 한다.

## 1. 이번에 고친 결함

### 1.1 HIGH — 애드온의 5xx/타임아웃이 서버 성적에 전혀 남지 않았다

- 파일: `app/src/main/java/com/music/bitchord/data/sources/addon/AddonClient.kt:334-424`
- 증거(수정 전): `body()` 는 성공 시 `HqHostTracker.noteSuccess` 만 호출했고,
  429/503 에서만 `noteRateLimited` 를 호출했다. `502/504`, 일반 `5xx`,
  `SocketTimeoutException`/연결 거부는 그대로 예외로 빠져나갔다. 그 예외는
  `AddonUnavailable` 이 아니므로 `AddonSource.search/stream` 의
  `reportSourceFailure` 도, `SourceCooldown` 도, 호스트 감점도 타지 않았다.
- 사용자 영향: 잠들었거나 느린 애드온이 “정상 서버”로 남아 매 곡 다시 선택된다.
  반대로 실제로 잘 되는 서버는 그 사실이 우선순위에 반영되지 않는다.
- 흔한 정도: 높음. Render/Vercel 무료 인스턴스의 콜드 스타트와 모바일 망
  타임아웃은 이 영역의 일상이다.
- 수정: 모든 응답을 `HqHostTracker.noteResponse(host, elapsedMs, ok)` 로 계측하고,
  전송 예외는 `noteResponse(ok=false)` + `rest(20s)` 후 `AddonUnavailable` 로
  바꿨다. 5xx 도 실패로 기록한다. 404 는 서버가 정상 응답한 “없음”이므로 성공으로
  기록한다.
- 조건 진리표(수정 전 → 수정 후, `body()` 의 분기):

| 응답/예외 | 수정 전 처리 | 수정 후 처리 |
|---|---|---|
| 2xx | success | noteResponse(ok=true), 성공 |
| 404 | AddonNotFound, 성적 없음 | noteResponse(ok=true), AddonNotFound |
| 429 | noteRateLimited, 재시도 가능 | (동일) + decideAddon |
| 503 | noteRateLimited, 3회 재시도 | noteRateLimited, **재시도 없음**, rest |
| 502/504 | AddonUnavailable, 성적 없음 | noteRateLimited, **재시도 없음**, rest |
| 500/기타 5xx | AddonUnavailable, 성적 없음 | noteResponse(ok=false)+failures |
| IOException/타임아웃 | IOException 그대로 | noteResponse(ok=false)+20s rest+AddonUnavailable |

### 1.2 HIGH — 서버별 성공률·응답시간·휴식이 앱 재시작과 함께 사라졌다

- 파일: `app/src/main/java/com/music/bitchord/playback/HqHostPolicy.kt:19-32,171-192,276-340,371-455`,
  `app/src/main/java/com/music/bitchord/BitChordApplication.kt:164`
- 증거(수정 전): `HqHostGate` 의 `Map<String, State>` 는 프로세스 메모리뿐이었고,
  `HqHostTracker` 를 초기화하는 코드도 없었다. 앱을 다시 켜면 어제 429/잠듦으로
  쉬고 있던 서버가 첫 곡의 첫 후보가 됐다.
- 사용자 영향: “앱 재시작 후” 첫 곡에서 무손실 준비가 다시 느린/잠든 서버를
  기다린다. 검증 결과는 `HqVerificationStore` 로 남지만, 그 검증을 수행할 서버
  선택은 매번 백지에서 시작했다.
- 흔한 정도: 높음. 사용자는 앱을 하루에도 여러 번 다시 켠다.
- 수정: 성공/실패 횟수, 응답시간 표본·합계, 최근 429, 휴식 만료시각을
  `hq_host_health` SharedPreferences 에 호스트별로 저장한다. 쓰기는 요청이
  끝나는 이벤트에서만 일어나며 주기 타이머는 없다. 7일 지난 기록과 최근 128개
  호스트만 유지한다. `BitChordApplication.onCreate()` 에서 한 번 읽는다.

### 1.3 MEDIUM — 잠든 서버를 한 번의 호출 안에서 3번까지 두드렸다

- 파일: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/net/HqRetryPolicy.kt:58-77`,
  `app/src/main/java/com/music/bitchord/data/sources/addon/AddonClient.kt:382-414`
- 증거(수정 전): 503 은 `isRateLimited` 이므로 `HqRetryPolicy.decide` 의
  1s/2s/4s 사다리를 그대로 탔다. 잠든 컨테이너가 깨어나는 동안 같은 서버에
  최대 3개의 요청이 도착했다.
- 사용자 영향: 콜드 스타트를 오히려 늘리고, 그 시간 동안 재생 준비가 붙잡힌다.
- 흔한 정도: 높음(onrender 무료, vercel 무료).
- 수정: `isSleepingGateway(502/503/504)` 와 `decideAddon` 을 추가해 게이트웨이
  응답은 “이번 호출에서는 1회, 이후 호스트 휴식”으로 처리한다. 429(깨어 있는
  서버의 간격 요청)는 기존 사다리를 유지한다.
- 조건 진리표(`decideAddon`, 수정 전 동작 → 수정 후):

| 응답 코드 | 수정 전 retry | 수정 후 retry |
|---|---|---|
| 429 | true(attempt<3) | true(attempt<3) |
| 502 | 해당 없음(일반 5xx 즉시 실패) | false |
| 503 | true(attempt<3) | false |
| 504 | 해당 없음(일반 5xx 즉시 실패) | false |
| 500 | false | false |

### 1.4 MEDIUM — 검증용 후보 다운로드도 잠든 서버를 두 번 깨웠다

- 파일: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/sources/HqVerifyPolicy.kt:106-126`,
  `app/src/main/java/com/music/bitchord/playback/smart/HqAudioVerify.kt:1090-1103`
- 증거(수정 전): `retryCandidateDownload` 는 429/503 을 구분하지 않고 한 번 더
  요청했다. `downloadPrefix` 는 503 을 `RateLimitedDownload` 로 분류하므로
  `HqAudioVerify` 가 20초 뒤 같은 잠든 서버를 다시 깨웠다.
- 사용자 영향: 검증 시작이 불필요하게 늦어지고 서버 콜드 스타트가 길어진다.
- 흔한 정도: 중간. 다음 곡 준비·다운로드 보관함이 겹칠 때.
- 수정: `retryCandidateDownload` 에 `code` 를 넘기고 게이트웨이 코드는 재시도하지
  않는다. 429 의 “한 번만” 규칙은 그대로다.
- 추가: 후보 다운로드가 IOException 으로 실패하면 해당 호스트에
  `noteFailure` 를 남긴다(`HqAudioVerify.kt:1065-1073`).

### 1.5 MEDIUM — 느린 서버의 응답시간이 순위에 쓰이지 않았다

- 파일: `app/src/main/java/com/music/bitchord/playback/HqHostPolicy.kt:171-192,252-267`
- 증거(수정 전): `penalty()` 는 429, 성공률, 바이트 처리량만 봤다. 애드온 JSON 은
  몇 KB 라서 처리량 기준으로는 항상 “느림”이 되거나, 아예 계측되지 않았다.
- 사용자 영향: 4초 소스 타임아웃 안에 못 들어오는 서버가 먼저 선택되어
  다음 곡 준비가 29%에서 끊긴다.
- 흔한 정도: 중간~높음.
- 수정: 응답시간 표본 3개 이상이고 평균 5초 이상이면 감점 1을 준다. 바이트
  처리량과 분리해 작은 JSON 이 감점을 왜곡하지 않게 했다.

### 1.6 MEDIUM — 잘린 다운로드 파일 머리를 영구 거부로 저장해 무손실 파일을 죽였다

- 파일: `app/src/main/java/com/music/bitchord/playback/smart/HqAudioVerify.kt:700-744`
- 증거(수정 전): 스트림 검증(`verifyOutcome`)은 `HqReferenceCache.completeEnough` 로
  기준/후보 완전도를 확인했지만, 다운로드된 파일 검증(`verifyDownloaded`)은
  확인 없이 `align` 후 `HqVerificationStore.reject` 했다. 중간 EOF·프록시 절단으로
  90초 창이 짧아지면 정상 FLAC 이 커버로 낙인찍혀 이후 재검증도 막힌다.
- 사용자 영향: 받아 둔 무손실 파일이 영구히 “다른 녹음”이 된다. 고음질 보관함의
  실패 140회와 재시도 불가로 이어질 수 있다.
- 흔한 정도: 느린/끊긴 망, 화면 꺼짐 직후.
- 수정: 기준이 충분한데 후보가 짧으면 `Unavailable`(측정 불가)로 돌리고,
  거부 저장은 양쪽이 충분할 때만 한다. 스트림 경로와 같은
  `HqVerifyPolicy.shouldPersistRejection` 을 쓴다.

## 2. 흔한 상황별 추적표 (이 영역)

| 상황 | 진입점 → 처리 | 실제 결과 / 근거 |
|---|---|---|
| 커버 영상 | `HqAudioVerify.verifyOutcome` 배열 검사 → `reject` | 저장된 거부. 이후 같은 쌍은 재검증 없이 거부(`HqVerificationStore`) |
| 비디오 노래 | `NextTrackPreparation` 의 `isVideo` 제외 | 다음 곡 고음질 준비 대상 아님. 재생 중 수동 업그레이드만 |
| 셔플/빠른 스킵 | `NextTrackPreparation.schedule`, `AudioCache.prefetchQueue` | 다음 행이 바뀌면 준비 취소. 준비 완료 전에 넘기면 29% 쪽 실패 |
| 앱 재시작 후 | `HqHostTracker.init`(신규) + `HqVerificationStore` | 서버 성적·휴식은 이제 유지. 검증 결과도 유지되지만 재생용 스트림 핀(`StreamChoice`)은 메모리라 첫 곡 준비가 다시 필요 |
| 화면 꺼짐 | `VideoAudio.songWav` 가 `PowerState.screenOn=false` 면 null | `NoReference(DECODE_FAILED)` 가 이번 재생 동안 고정된다. 아래 3.2 참고(미수정) |
| 백그라운드 | 다운로드/보관함은 WorkManager 이벤트 | 서버 휴식은 `restUntilMs` 비교라 백그라운드 타이머 없음 |
| 프록시 켬 | `ProxyPlayback.mediaClientFor`, `StreamResolver` | 애드온 JSON 은 프록시를 타지 않는다(직접 URL). 스트림/검증 바이트만 프록시 경로 |
| 다운로드된 곡 | `verifyDownloaded` → 저장된 검증은 디코드 없이 반환 | 이번 1.6 으로 잘린 머리가 정상 파일을 거부하지 못하게 됨 |
| 느린/끊긴 망 | `AddonClient` 20초 call timeout, `SourceResolver` 4초 소스 타임아웃 | 타임아웃은 이제 `AddonUnavailable`+호스트 실패/20초 휴식. 소스 단위 30분 쿨다운은 3.1 |
| Android 14+ | WorkManager 포그라운드 제약 | 이 영역의 서버 상태는 메모리/SharedPreferences 만 쓰고 포그라운드 서비스 시작을 요구하지 않음 |

## 3. 고치지 못한 HIGH/MEDIUM

### 3.1 MEDIUM — 소스 단위 30분 쿨다운이 호스트 단위 휴식을 덮는다

- 파일: `app/src/main/java/com/music/bitchord/data/sources/SourceResolver.kt:55-87,1026,1423`
- 증거: 애드온이 잠들어 `AddonUnavailable` 을 두 번 내면 `SourceCooldown` 이
  그 config 전체를 30분 쉰다. 새 호스트 휴식(60초~10분)은 그보다 짧아서,
  서버가 1분 만에 깨어나도 사용자는 최대 30분 동안 그 애드온을 못 쓴다.
- 사용자 영향: 잠든 서버가 깨어난 뒤에도 고음질 후보에서 사라진다.
- 남긴 이유: 소스 전체 쿨다운은 “죽은 애드온이 모든 곡의 4초를 잡아먹지 않게”
  하려는 기존 안전 규칙이다. 호스트별로 바꾸려면 `MusicSource` 실패에 호스트/코드를
  실어 보내야 하고, 모듈 소스까지 같은 규칙을 적용하는 제품 결정이 필요하다.
- 필요한 작업: `AddonUnavailable` 에 휴식 만료/게이트웨이 여부를 실어
  `SourceCooldown.failed(configId, untilMs)` 로 호스트 휴식과 같은 시각을 쓰게 한다.

### 3.2 HIGH(관련 영역) — 화면 꺼짐 중의 기준 없음이 재생 단위로 고정된다

- 파일: `app/src/main/java/com/music/bitchord/playback/smart/HqAudioVerify.kt:940-955`,
  `app/src/main/java/com/music/bitchord/playback/smart/VideoAudio.kt:99`
- 증거: `VideoAudio.songWav` 가 화면 꺼짐이면 즉시 null 을 돌려주고,
  `referenceFeaturesUncached` 는 그 null 을 `NoReference(DECODE_FAILED)` 로
  `noReference` 맵에 저장한다. 이 맵은 다음 재생 시작에서만 지워진다.
- 사용자 영향: 화면을 다시 켜도 그 재생 동안 모든 고음질 후보가 “기준 없음”으로
  건너뛰어진다. 무손실 확인 후 실제 재생 4%의 한 원인이다.
- 남긴 이유: 이 작업의 범위는 애드온 서버 상태이고, 이 수정은 화면 켜짐/꺼짐
  이벤트를 `PowerState` 에 연결해야 한다. 화면이 꺼진 채로 후보를 계속 디코드하는
  배터리 비용도 함께 결정해야 한다.
- 필요한 작업: `!PowerState.screenOn` 일 때는 `NoReference` 를 저장하지 않고,
  화면 켜짐 이벤트에서 `noReference` 를 비운 뒤 다음 검증 요청에서 재시도한다.

### 3.3 LOW/MEDIUM — 후보 정렬은 휴식만 보고 성적 감점은 나중에 본다

- 파일: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/sources/HqVerifyPolicy.kt:145-158`,
  `app/src/main/java/com/music/bitchord/data/sources/SourceResolver.kt:1087-1120`
- 증거: `candidateOrder` 는 `isResting` 만으로 건강한 호스트를 앞에 두고,
  `penalty` 는 이후 `best` 비교에서만 쓰인다. 같은 렌디션의 여러 후보가 동시에
  도착하면 원래 소스 순서가 먼저 남을 수 있다.
- 남긴 이유: 소스 순서는 사용자가 정한 우선순위다. 감점을 정렬 키로 쓰면 품질이
  같은 두 소스의 순서를 앱이 뒤집게 되어 제품 의미가 바뀐다.
- 필요한 작업: `candidateOrder(hosts, isResting, penalty)` 로 확장하되, 품질이
  같은 경우에만 감점을 tie-break 로 쓰는 규칙을 먼저 정한다.

## 4. 성적표 해석 (vc85, 362곡)

- 다음 곡 싱크 준비 29%: 준비는 `NextTrackPreparation` 이 `bestAcross` 를
  `SOURCE_LOOKUP_TIMEOUT_MS=4s` 로 감싸고, 검증용 후보 헤드 다운로드가 그 밖에서
  최대 20초까지 걸린다. 잠든/느린 애드온이 첫 후보이면 준비가 끝나기 전에 다음
  곡으로 넘어간다. 이번 수정으로 그 서버는 실패/느림으로 감점되고 20초 휴식에
  들어가 다음 곡 준비에서 뒤로 밀린다.
- 무손실 확인 후 실제 무손실 재생 4%: 검증 결과는 저장되지만, 화면 꺼짐 기준
  없음이 재생 단위로 고정되고(3.2), 소스 30분 쿨다운(3.1)이 깨어난 서버를 계속
  숨긴다. 또한 잘린 다운로드 머리가 정상 파일을 영구 거부할 수 있었다(1.6).
- 자동 재생 보충 성공 24%: 보충은 서버가 429/잠듦일 때 같은 소스를 다시 부르기
  쉬웠다. 이제 5xx/타임아웃도 호스트 실패로 남고, 잠든 게이트웨이는 한 번만
  깨운다(1.1, 1.3).
- 사용자 조작 없는 백그라운드 시작 11회: 이 영역은 백그라운드 시작 정책을 바꾸지
  않았다. 다운로드/보관함 WorkManager 제약은 그대로다.
- 다운로드·고음질 보관함 실패 140회: 보관함은 검증된 `(YouTube id, 소스, 트랙)`
  쌍을 재사용하지만, 그 검증 자체가 잘린 후보 머리로 거부되면 영구히 막혔다
  (1.6). 서버가 잠들어 검증 다운로드가 503 이면 이제 재시도 대신 휴식하고 다음
  이벤트에서 다시 시도한다(1.4).

## 5. 테스트

- `shared/src/jvmTest/.../data/net/HqRetryPolicyTest.kt`
  - `a sleeping gateway is woken once and never retried in the same call`
  - `a rate limit on an awake server still gets the first retry`
- `shared/src/jvmTest/.../data/sources/HqVerifyPolicyTest.kt`
  - `a sleeping candidate gateway gets one wake request, not an in-call retry`
- `app/src/test/java/com/music/bitchord/AddonClientHealthTest.kt` (신규)
  - `a sleeping gateway is asked exactly once`
  - `a successful addon answer records its response time`
  - `a server fault counts against the host`
- `app/src/test/java/com/music/bitchord/HqHostPolicyTest.kt`
  - `a slow but working addon sorts behind a fast one`
  - `host health and rest survive a restart`
  - `a health record older than a week is not restored`

실행 결과: `:shared:jvmTest`(HqRetryPolicy*, HqVerifyPolicyTest), `:app:testProdDebugUnitTest`
(AddonClientHealthTest, HqHostPolicyTest, HqRateLimitRecoveryTest, SourcesTest,
HqAudioVerify*, HqVerification*) 통과. `:app:compileProdReleaseKotlin
-Pbitchord.arm64Only=true` 통과.
