# 작은 낭비 묶음 2차 — PoToken·visitor 저장 무결성

기준: `dsq/fix-small-perf-1031-r2`, 이전 작업 `e92edc5` 위. 2026-10-30.
범위는 이전 문서 `docs/review-1031/small-waste.md`(FIXLIST 96~99)의 결과에 대한
검증 지적 4건 처리와, 그 과정에서 드러난 같은 영역의 저장 무결성 결함이다.
주기 폴링·타이머·delay 루프는 추가하지 않았다.

## 검증 지적별 처리

| # | 심각도 | 지적 | 처리 | 증거 |
|---|---|---|---|---|
| 1 | HIGH | PoToken 캐시가 SharedPreferences 5개 키를 잠금 없이 따로 써서, 동시 민팅·중간 kill 시 세션/비디오와 토큰이 다른 민팅에서 섞인다. | **고침.** 저장소를 `read()/write(record)` 한 레코드로 바꾸고, 단일 키에 `edit().putString().apply()` 한 번으로 쓴다. `get`/`put`은 인스턴스 락으로 감싼다. | `shared/.../potoken/PoTokenGenerator.kt:274-372`, `app/.../AndroidStreamHooks.kt:66,83-90` |
| 2 | MEDIUM | `persistVisitor`가 쿠키에 묶인 visitor id를 계정 수준 `sessionKey()`(`u:dataSyncId`)로 저장해, 같은 계정의 새 쿠키가 옛 쿠키에서 민팅한 visitor/PoToken을 재사용한다. | **고침.** visitor 저장 슬롯을 쿠키 해시(`visitorPersistenceKey(cookie)`)로 바꿨다. 쿠키가 바뀌면 슬롯이 달라져 옛 visitor를 복원하지 않는다. `sessionKey()` 자체의 계정 단위 의미(게이트 캐시 등)는 유지한다. | `shared/.../Innertube.kt:292-312,2245-2264` |
| 3 | LOW | `CoverFilterKey`가 `seedSong.albumName`을 빼서, 같은 videoId/title/artist에 앨범 메타데이터만 바뀐 시드가 옛 거르기 결과를 재사용한다. | **고침.** 키에 `seedAlbum`을 넣었다. | `app/.../Autoplay.kt:346-372` |
| 4 | LOW | `last_player_screen`이 백그라운드/dispose에서만 저장돼 강제 종료 시 최신 패널을 잃는다. | **반박 + 문서화.** 패널 전이마다 저장하면 FIXLIST 96이 없애려던 대기열 열기/닫기 1분 8회 쓰기가 그대로 돌아온다. 동일값 가드는 `MAIN→QUEUE→MAIN` 교대를 막지 못한다. 정상 백그라운드·화면 꺼짐·dispose 경로는 모두 저장하므로, 남는 손실은 비정상 kill 직전의 패널 하나뿐이다. | `sharedUi/.../NowPlayingScreen.kt:1044-1052`, `app/.../AppSettings.kt:2347-2351` |

## 1) PoToken 레코드 원자성

### 진입점 → 처리 → 결과

| 상황 | 처리 | 결과 |
|---|---|---|
| 플레이어가 곡 해석 | `getWebClientPoToken` → `tokenCache.get` | 한 레코드를 한 번 읽어 세션·비디오·TTL을 함께 검사 |
| 같은 곡 프리페치/다운로드 동시 | `tokenCache.put` 두 스레드 | 락 안에서 레코드 하나를 통째로 씀. 다른 곡 토큰과 섞이지 않음 |
| 민팅 중 프로세스 kill | 단일 키 `apply()` | 이전 레코드 또는 새 레코드 중 하나. 반쪽 레코드 없음 |
| 30분 경과 | `mintedAt` 검사 | 캐시 miss, 정상 재민팅 |
| 프리웜 | 기존처럼 캐시 조회 생략 | 페이지 준비 목적 유지 |
| 프록시 켬 | 같은 토큰 제공자 경유 | 별도 분기 없음, 영향 없음 |
| 다운로드/오프라인 | 스트림 해석이 없으면 호출 없음 | 영향 없음 |
| Android 14+ | SharedPreferences 수명 동일 | 영향 없음 |

기존/새 저장 동작:

| 동작 | 기존 | 새 |
|---|---|---|
| `put` 1회의 디스크 쓰기 | 5회(서로 다른 키, 각 `apply`) | 1회(단일 키, 단일 editor) |
| 동시 `put` | 마지막 키 단위로 섞일 수 있음 | 락 + 단일 레코드 |
| 중간 kill | 세션/비디오는 새 값, 토큰은 옛 값 가능 | 이전/새 레코드 중 하나 |

## 2) visitor 저장 키

기존/새 진리표(같은 계정, 쿠키 회전):

| 상태 | 기존 키 | 새 키 | 기존 결과 | 새 결과 |
|---|---|---|---|---|
| 로그인, dataSyncId 있음, 쿠키 A | `u:<dataSyncId>` | `c:<hash(A)>` | A의 visitor 저장 | A의 visitor 저장 |
| 같은 계정, 쿠키 B로 회전 | `u:<dataSyncId>` (같음) | `c:<hash(B)>` (다름) | **A의 visitor 재사용** | B 슬롯 miss → 새로 민팅 |
| 비로그인 | `anonymous` | `anonymous` | 공유 익명 슬롯 | 공유 익명 슬롯 |
| 채널 오버라이드 중 | `u:<채널>` | `c:<hash(cookie)>` | 계정 슬롯 | 쿠키 슬롯 |

`sessionKey()`는 게이트/홈 캐시처럼 "계정 단위"가 맞는 곳에서 계속 `u:<id>`를
반환한다. visitor 저장만 쿠키 단위로 분리했다.

## 3) 커버 거르기 캐시 키

| 상황 | 처리 | 결과 |
|---|---|---|
| 같은 시드·같은 라디오 재진입 | 키 동일 | 재스캔 없음, 로그 없음 |
| 시드 앨범만 바뀜 | `seedAlbum`이 키에 포함 | 새로 거르고 필요 시 한 번 로그 |
| 라디오 목록 바뀜 | `radio` 리스트가 키에 포함 | 새로 거르기 |
| 앱 재시작 | 프로세스 캐시 초기화 | 첫 스캔은 정상 비용 |

## 4) `last_player_screen` (고치지 않음)

| 상황 | 처리 | 결과 |
|---|---|---|
| 대기열 열기/닫기 | 메모리만 갱신 | 디스크 쓰기 0회(1분 8회 → 0회) |
| 앱 뒤로 감/화면 꺼짐 | `foreground=false` 전이 | 그 시점 화면 1회 저장 |
| 플레이어 폐기 | `onDispose` | 그 시점 화면 1회 저장(동일값 가드) |
| 강제 종료/크래시 | 저장 이벤트 없음 | 직전 패널 하나를 잃을 수 있음 |
| 프로세스 재시작 | 저장값 복원 | 마지막으로 저장된 화면 |

패널 전이마다 저장하는 수정은 `MAIN↔QUEUE`가 매번 값이 달라 동일값 가드로
억제되지 않으므로 채택하지 않았다.

## 사용 성적표와의 관계

- `자동 재생 보충 성공 24%`: 이번 커버 키 수정은 같은 시드의 앨범 메타데이터가
  갱신됐을 때 옛 거르기 결과를 재사용해 잘못된 행을 넣거나 빼는 문제를 막는다.
  성공률 자체를 좌우하는 빈 라디오 응답 재시도·`autoplaySeed` 래치는 이 영역 밖이라
  바꾸지 않았다.
- `다운로드·고음질 보관함 실패 140회`: visitor/PoToken이 매 시작마다 새로 만들어지던
  경로 중 (a) 쿠키 회전 시 옛 visitor 재사용(403 유발)과 (b) 5키 부분 쓰기로 인한
  다른 곡 토큰 혼입을 이번에 제거했다. HQ 검증·소스 경쟁 자체는
  `HqLibraryUpgrade`/`HqAudioVerify` 영역이라 손대지 않았다.
- `다음 곡 싱크 준비 29%`, `무손실 확인 후 실제 무손실 재생 4%`: 이 영역의 코드
  경로가 아니므로 원인을 단정하지 않는다.

## 남긴 결함

- LOW: `last_player_screen`의 강제 종료 직전 패널 손실. 위 4)의 이유로 의도적으로
  남겼다. 해결하려면 패널 전이 저장과 쓰기 억제를 함께 만족하는 별도 정책
  (예: Activity `onStop` + 주기 없는 체크포인트)이 필요하다.
- LOW: BotGuard WebView 페이지 자체는 프로세스 간 재사용 불가. 토큰 결과만 30분 TTL로
  재사용한다.
- 기존 문서에서 남긴 AutoPlay 빈 응답 재시도/공급자 선거 정책은 이번에도 범위 밖.

## 테스트

새/보강 테스트:
- `com.music.bitchord.data.innertube.potoken.PoTokenResultCacheTest`
  - `a minted pair is persisted in one atomic write`
  - `an interrupted write never pairs one video's tokens with another`
- `com.music.bitchord.data.innertube.VisitorDataPersistenceTest`
  - `a rotated cookie for the same account gets a different visitor slot`
- `com.music.bitchord.playback.AutoplayCoverFilterCacheTest`
  - `a seed album change is not served from the previous filter result`

수정 전(`e92edc5`)에는 각각 5회 쓰기, video-b가 video-a 토큰을 읽음, 계정 키
재사용, 앨범 무시 캐시 재사용으로 실패한다.

실행 결과:
- `./gradlew :shared:jvmTest --tests '...PoTokenResultCacheTest' --tests '...VisitorDataPersistenceTest'` → 통과
- `./gradlew :app:testProdReleaseUnitTest --tests '...AutoplayCoverFilterCacheTest'` → 통과
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true` → 통과
