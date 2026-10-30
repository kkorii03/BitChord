# addon-server-health-1031-r2 — 고음질 애드온 서버 신뢰성 2차 검토

기준: 2차 리뷰 `VERDICT: ISSUES 6` (이전 차수 `docs/review-1031/addon-server-health-1031.md`
위에 쌓인 결과). 범위는 고음질 애드온의 검색·스트림·검증 다운로드가 429·잠듦(502/503/504)·
본문 절단·느린 응답을 만났을 때다. 이번 차수는 지적된 6건 중 MEDIUM 3건과 LOW 3건을
모두 고쳤다. 새 HIGH/MEDIUM은 없다.

## 1. 지적 번호별 처리

### 1.1 MEDIUM (지적 1) — 응답 본문 읽기 실패가 전송 실패 경로를 벗어났다

- 파일: `app/src/main/java/com/music/bitchord/data/sources/addon/AddonClient.kt:369-448`
- 증거(수정 전): `client.newCall(request).execute()`만 `try/catch` 안에 있었고,
  상태줄을 받은 뒤의 `response.body?.string()`(`:374`)은 그 밖이었다. 본문을 읽는
  도중 소켓 타임아웃·연결 절단이 나면 `IOException`이 그대로 `fetch()`까지 올라가
  `noteResponse`도, `rest`도, `AddonUnavailable`도 타지 않았다.
- 사용자 영향: 서버가 응답을 시작한 뒤 끊긴 경우가 “서버 장애”로 집계되지 않아
  호스트 순위가 그대로였고, 다음 곡이 같은 서버를 다시 첫 후보로 골랐다.
- 흔한 정도: 중간~높음. 무료 Render/Vercel 인스턴스가 깨어나는 도중, 또는 모바일
  망이 끊길 때 실제로 본문이 잘린다.
- 수정: `call.use { ... }` 전체를 `try`로 감싸고, `CancellationException`은 그대로
  통과시키되 `IOException`은 전송 실패와 같은 경로로 보낸다. `noteResponse(ok=false)`,
  `rest(TRANSPORT_REST_MS)`, 로그 후 `AddonUnavailable`로 변환한다. `AddonNotFound`,
  `AddonUnavailable`, `AddonException`은 각자의 분기를 유지한다.

| 본문 읽기 결과 | 수정 전 | 수정 후 |
|---|---|---|
| 2xx + 정상 JSON | noteResponse(ok=true), 반환 | 동일 |
| 2xx + 빈 본문 | AddonException | 동일 |
| 2xx + 본문 중간 IOException | bare IOException | noteResponse(false) + rest + AddonUnavailable |
| 404 | noteResponse(true), AddonNotFound | 동일 |
| 429 | noteRateLimited + decideAddon | 동일 |
| 502/503/504 | noteRateLimited + decideAddon(재시도 없음) | 동일 |
| 500/기타 5xx | noteResponse(false), AddonUnavailable | 동일 |

### 1.2 MEDIUM (지적 2) — 실제 재생 스트림은 잠든 503을 3번 두드렸다

- 파일: `app/src/main/java/com/music/bitchord/playback/ChunkedDataSource.kt:442-499`
- 증거(수정 전): `openWithRateLimitRetry`가 `HqRetryPolicy.isRateLimited`만 검사해
  503을 429와 같이 취급했다. `decide`의 1s/2s/4s 사다리로 최대 3회 재시도했고,
  502/504는 아예 이 분기에 들어오지 못해 호스트 휴식도 없었다.
- 사용자 영향: 잠든 무료 인스턴스에 같은 콜드 스타트 요청을 연속으로 보내
  다음 곡 준비가 늦어지고, 재생 실패 후 다른 후보로 넘어갈 때도 그 서버가
  건강한 것처럼 남았다.
- 흔한 정도: 높음(onrender 무료, vercel 무료, 503을 내는 모든 무료 티어).
- 수정: `isSleepingGateway(502/503/504)`도 진입 조건에 넣고 `decideAddon`으로
  결정한다. 429는 기존 재시도 사다리를 유지하고, 게이트웨이는 이번 호출에서
  재시도하지 않으며 `noteRateLimited`가 남기는 휴식(최소 60초, 서버 Retry-After가
  더 길면 그 값)을 그대로 쓴다. 로그에도 “한 번의 wake 요청”과 휴식 초를 남긴다.

| 응답 코드 | 수정 전 동작 | 수정 후 동작 |
|---|---|---|
| 429 | 최대 3회 재시도 | 최대 3회 재시도(동일) |
| 502 | 일반 예외, 휴식 없음 | 1회 요청 + 호스트 휴식 |
| 503 | 최대 3회 재시도 | 1회 요청 + 호스트 휴식 |
| 504 | 일반 예외, 휴식 없음 | 1회 요청 + 호스트 휴식 |
| 500 | 일반 예외 | 일반 예외(동일) |

### 1.3 MEDIUM (지적 3) — 검증 다운로드의 502/504가 잠듦 분기에 닿지 않았다

- 파일: `app/src/main/java/com/music/bitchord/playback/smart/HqAudioVerify.kt:1220-1237`,
  소비 지점 `:1119-1130`
- 증거(수정 전): `downloadPrefix`는 `isRateLimited`(429/503)만
  `RateLimitedDownload`로 분류했다. 502/504는 `IOException("HTTP …")`가 되어
  `downloadCandidate`의 일반 실패 경로로 빠졌고, `HqVerifyPolicy.retryCandidateDownload`의
  게이트웨이 분기(`retry=false`)에는 도달할 수 없었다.
- 사용자 영향: 검증 시도가 잠든 서버를 깨우고도 그 호스트를 쉬게 하지 않아,
  다음 후보·다음 곡이 같은 콜드 컨테이너를 다시 골랐다. 다운로드·보관함 실패가
  반복되는 한 원인이다.
- 흔한 정도: 중간~높음. 검증은 다음 곡 준비와 겹쳐 같은 무료 인스턴스에 몰린다.
- 수정: `isRateLimited(code) || isSleepingGateway(code)`이면
  `RateLimitedDownload(code, retryAfter, headers)`로 올린다. 그러면 기존
  `retryCandidateDownload`가 게이트웨이를 “재시도 없음”으로 판정하고,
  `noteRateLimited`가 호스트를 휴식시킨 뒤 `RateLimited`로 끝난다.

| 응답 코드 | 수정 전 | 수정 후 |
|---|---|---|
| 429 | RateLimitedDownload → 1회 대기 후 재시도 | 동일 |
| 502 | IOException → 일반 실패, 휴식 없음 | RateLimitedDownload → 재시도 없음 + 휴식 |
| 503 | RateLimitedDownload → 재시도 없음 + 휴식 | 동일 |
| 504 | IOException → 일반 실패, 휴식 없음 | RateLimitedDownload → 재시도 없음 + 휴식 |

### 1.4 LOW (지적 4) — 호스트 성적 복원이 메인 스레드에서 디스크를 열었다

- 파일: `app/src/main/java/com/music/bitchord/BitChordApplication.kt:161-169`
- 증거: `HqHostTracker.init(this)`가 `Application.onCreate`에서 동기로
  `getSharedPreferences(...).getString(...)`을 호출했다. 첫 open은 디스크 파싱으로
  대기할 수 있다.
- 사용자 영향: 앱 콜드 스타트 첫 프레임이 늦어질 수 있다.
- 흔한 정도: 낮음(파일이 작고 보통 캐시됨), 그러나 저사양 기기·첫 실행에서 발생.
- 수정: `StartupWork.background("hq-host-health")`로 옮겼다. 복원 전에 요청이
  들어와도 게이트는 빈 상태로 동작하고, `restore()`는 이미 살아 있는 호스트 기록을
  덮지 않으므로 진행 중 요청을 지우지 않는다.

### 1.5 LOW (지적 5) — 성적 저장 스냅샷이 다른 스냅샷을 덮을 수 있었다

- 파일: `app/src/main/java/com/music/bitchord/playback/HqHostPolicy.kt:367-371,450-461`
- 증거: `persist()`가 락 없이 `gate.snapshot()` 후 `apply()`했다. `noteTransfer`와
  `noteResponse`가 여러 스레드에서 호출되면, 오래된 스냅샷을 든 스레드가 나중에
  apply해 더 새로운 카운트를 덮을 수 있었다.
- 사용자 영향: 재시작 후 서버 순위가 실제보다 오래된 성적을 따를 수 있다.
- 흔한 정도: 낮음(경합 창이 짧지만 재생·검증·다운로드가 동시에 돌면 가능).
- 수정: `persistLock`으로 snapshot+apply를 직렬화해 apply 순서가 스냅샷 순서와
  같아지게 했다. 주기 타이머는 추가하지 않았다.

### 1.6 LOW (지적 6) — 7일 만료 테스트가 다른 분기를 시험했다

- 파일: `app/src/test/java/com/music/bitchord/HqHostPolicyTest.kt:307-321`
- 증거(수정 전): `lastActivityMs = 0L`이라 `restore()`의 “활동 기록 없음” 분기에서
  먼저 버려졌다. 7일 비교가 깨져도 테스트는 통과했다.
- 수정: `lastActivityMs = now - 8일`(0 아님)로 바꿔 실제 7일 비교를 통과해야만
  테스트가 성공하게 했다.

## 2. 흔한 상황별 추적표 (이번 수정이 닿는 경로)

| 상황 | 진입점 → 처리 | 결과 / 근거 |
|---|---|---|
| 앱 재시작 후 | `StartupWork`의 `HqHostTracker.init` → `restore` | 호스트 성적·휴식이 첫 곡 전에 복원된다. 복원 전 요청도 빈 게이트로 안전 |
| 커버 영상 | `HqAudioVerify` 후보 다운로드 → 502/504 분류 | 잠든 서버면 한 번만 깨우고 휴식, 이후 후보 정렬에서 뒤로 |
| 비디오 노래 | 스트림 오픈 `ChunkedDataSource.open` → `openWithRateLimitRetry` | 502/503/504는 1회 요청 + 휴식, 429만 재시도 사다리 |
| 셔플/빠른 스킵 | 다음 곡 준비의 검증 다운로드 | `samePlaybackAfter`가 재생 변경 시 대기를 끊고, 이번 수정은 첫 요청만 남긴다 |
| 화면 꺼짐 | 검증 다운로드 transport 실패 | 본문 절단도 `AddonUnavailable`로 남아 호스트가 쉰다. 기준 없음 고정은 아래 3.1 |
| 백그라운드 | 이벤트 기반 `note*`/`persist` | 주기 폴링·타이머 없음. WorkManager 제약도 변경 없음 |
| 프록시 켬 | 애드온 JSON은 직접, 스트림/검증 바이트는 프록시 | 양쪽 다 같은 `HqRetryPolicy` 분기를 공유 |
| 다운로드된 곡 | 다운로드 검증의 502/504 | 이제 RateLimited로 분류돼 재검증 폭주 없이 호스트만 휴식 |
| 느린/끊긴 망 | 애드온 응답 본문 절단, 스트림 5xx | 본문 절단은 20초 휴식 + AddonUnavailable, 스트림은 위 표 |
| Android 14+ | 포그라운드 서비스 제약 | 이 영역은 상태 저장만 하고 서비스 시작을 요구하지 않는다 |

## 3. 이번에 고치지 못한 HIGH/MEDIUM

이번 2차 리뷰의 HIGH/MEDIUM은 모두 고쳤다. 아래는 이전 차수 문서에서 이미
“문서에만 남김”으로 표시된 항목으로, 이 영역(서버 전송) 밖의 제품 결정이 필요해
그대로 둔다. **재검증 필요** 표시를 유지한다.

### 3.1 HIGH(관련 영역, 재검증 필요) — 화면 꺼짐 중 “기준 없음”이 재생 단위로 고정

- 파일: `app/src/main/java/com/music/bitchord/playback/smart/HqAudioVerify.kt:940-955`,
  `app/src/main/java/com/music/bitchord/playback/smart/VideoAudio.kt:99`
- 증거: 화면이 꺼져 있으면 `VideoAudio.songWav`가 즉시 null을 반환하고, 그 null이
  `NoReference(DECODE_FAILED)`로 `noReference` 맵에 저장된다. 이 맵은 다음 재생
  시작에서만 비워진다.
- 사용자 영향: 화면을 다시 켜도 그 재생 동안 모든 고음질 후보가 “기준 없음”으로
  건너뛰어져 “무손실 확인 후 실제 무손실 재생 4%”에 기여한다.
- 남긴 이유: 이 작업의 범위는 애드온 서버 상태이고, 이 수정은 `PowerState`의
  화면 이벤트에 `noReference`를 연결해야 하며, 화면이 꺼진 채 후보를 디코드하는
  배터리 비용을 함께 정해야 한다. 배터리 우선 원칙상 별도 결정이 필요하다.
- 필요한 작업: `!PowerState.screenOn`일 때는 `NoReference`를 저장하지 않고,
  화면 켜짐 이벤트에서 `noReference`를 비운 뒤 다음 검증에서 재시도한다.

### 3.2 MEDIUM(관련 영역, 재검증 필요) — 소스 단위 30분 쿨다운이 호스트 휴식을 덮는다

- 파일: `app/src/main/java/com/music/bitchord/data/sources/SourceResolver.kt:55-87,124-126`,
  `app/src/main/java/com/music/bitchord/data/sources/AddonSource.kt:198,262`
- 증거: `AddonUnavailable`이 두 번 나면 `SourceCooldown.failed(configId)`가 그
  애드온 config 전체를 30분 쉰다. 새 호스트 휴식(60초~10분)은 그보다 짧아서,
  서버가 1분 만에 깨어나도 최대 30분 동안 그 애드온이 후보에서 사라진다.
- 사용자 영향: 잠든 서버가 깨어난 뒤에도 고음질 후보·자동 보충에서 제외된다.
- 남긴 이유: “죽은 애드온이 모든 곡의 4초를 잡아먹지 않게” 하려는 기존 안전
  규칙이다. 호스트별로 바꾸려면 `MusicSource` 실패에 호스트/코드를 실어 보내야
  하고, 모듈 소스까지 같은 규칙을 적용하는 제품 결정이 필요하다.
- 필요한 작업: `AddonUnavailable`에 휴식 만료/게이트웨이 여부를 실어
  `SourceCooldown.failed(configId, untilMs)`가 호스트 휴식과 같은 시각을 쓰게 한다.

## 4. 성적표(vc85, 362곡)와 코드 연결

- 다음 곡 싱크 준비 29%: 준비 창(4초) 밖에서 검증 다운로드가 최대 20초 대기했다.
  502/504가 RateLimited로 분류되지 않아 호스트가 쉬지 못하고 같은 서버가 다시
  선택되던 경로를 지적 3에서 고쳤다. 스트림 로더도 503을 3번 두드리던 지적 2를
  고쳐 콜드 스타트 동안 로더를 붙잡지 않는다.
- 무손실 확인 후 실제 무손실 재생 4%: 검증 다운로드가 잠든 서버에서 실패해도
  휴식이 남지 않던 문제(지적 3)와, 재시작 후 서버 성적이 사라지던 문제(지적 4)를
  고쳤다. 화면 꺼짐 기준 없음 고정(3.1)은 여전히 남아 있다.
- 자동 재생 보충 성공 24%: 보충도 같은 `AddonClient`/`ChunkedDataSource` 경로를
  쓰므로, 잠든 서버를 한 번만 깨우고 호스트를 쉬게 하는 지적 2·3이 직접 영향을
  준다. 본문 절단이 성적에 안 남던 지적 1도 보충 성공률을 왜곡하던 원인이다.
- 사용자 조작 없는 백그라운드 시작 11회: 이번 차수는 백그라운드 시작 정책을 바꾸지
  않았다. 다운로드/보관함 WorkManager 제약은 그대로다.
- 다운로드·고음질 보관함 실패 140회: 검증 다운로드의 502/504가 일반 IOException으로
  빠져 호스트 휴식 없이 반복되던 경로(지적 3)와, 재시작 후 성적 소실(지적 4)을
  고쳤다. 소스 30분 쿨다운(3.2)은 여전히 남아 있다.

## 5. 테스트

새 테스트:
- `app/src/test/java/com/music/bitchord/AddonClientHealthTest.kt`
  - `a body cut off mid-response is a server fault and rests the host`
- `app/src/test/java/com/music/bitchord/playback/ChunkedDataSourceSleepingGatewayTest.kt`
  - `a sleeping stream gateway is opened once and rested`
- `app/src/test/java/android/net/FakeUri.kt` (JVM 테스트용 최소 Uri)
- `app/src/test/java/com/music/bitchord/playback/smart/HqAudioVerifyCancellationTest.kt`
  - `a sleeping candidate gateway is woken once and rested`
- `app/src/test/java/com/music/bitchord/HqHostPolicyTest.kt`
  - `a health record older than a week is not restored`(7일 비교를 실제로 타도록 수정)

실행 결과:
- `:app:compileProdReleaseKotlin -Pbitchord.arm64Only=true` 통과.
- `:app:testProdDebugUnitTest`(위 새 테스트 + `AddonClientHealthTest`,
  `HqAudioVerifyCancellationTest`, `HqHostPolicyTest`, `HqRateLimitRecoveryTest`) 통과.
- `:shared:jvmTest`(`HqRetryPolicyTest`, `HqRetryPolicyEdgeTest`,
  `HqVerifyPolicyTest`) 통과.
