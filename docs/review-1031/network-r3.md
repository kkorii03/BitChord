# network 3차 — 프록시 URL 스탬프·조인·바이패스·PoToken 백스톱

기준: `task/fix-1030` 계열, 작업 브랜치 `dsq/deep-network-1031-r3`
이번 차수는 2차 문서(`docs/review-1031/network.md`)의 VERDICT가 지목한 5건을
실제 코드로 다시 따라가 처리했다. 지적 번호는 그 VERDICT 순서를 그대로 쓴다.

## 처리 요약

| # | 심각도 | 위치 | 판정 |
|---|---|---|---|
| 1 | HIGH | `shared/.../innertube/StreamResolver.kt:2366` | 수정 |
| 2 | MEDIUM | `shared/.../innertube/StreamResolver.kt:317,524-556` | 수정 |
| 3 | MEDIUM | `shared/.../innertube/StreamResolver.kt:535-553`, `shared/.../net/ProxyPlayback.kt:149` | 수정 |
| 4 | LOW | `app/.../playback/StreamChoice.kt:151`, `app/.../playback/QualityUpgrade.kt:737` | 수정 |
| 5 | LOW | `shared/.../innertube/potoken/PoTokenGenerator.kt:154` | 수정 |

---

## HIGH 1 — 재시도된 ALL_YOUTUBE URL이 스탬프를 잃고 직접 fetch로 403

- 파일: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/StreamResolver.kt:2366`
  (`remember`), 호출부 `rememberServed`(1497) · `ChunkedDataSource` · `PlaybackService`
- 증거(수정 전):
  ```kotlin
  val registered =
      if (ProxyPlayback.isProxiedUrl(url)) ProxyPlayback.generationFor(url) else null
  ```
  `markResolved`를 거치지 않는 ALL_YOUTUBE URL은 `isProxiedUrl`가 항상 false라
  `registered == null`이었다. `rememberServed`는 캡처를 넘기지 않으므로
  `stampFor(null, null, null, currentAllScope=true)` → `(null, false)`.
  즉 미드플레이 재시도가 성공한 ALL URL이 "직접 발급"으로 다시 기록됐다.
- 사용자 영향: 다음 open에서 `allScopeNow && !mintedOnAllScope`라 캐시가 버려져
  불필요한 재해석이 발생하고, 그 사이 프록시가 꺼지거나 bypass/스코프가 바뀌면
  프록시가 발급한 URL을 직접 열어 googlevideo 403.
- 흔한 정도: ALL 사용자가 재생 중 403/재시도 후 다음 open을 할 때.
  (2차 문서 §7이 "live read를 보존한다"고 적은 부분과 코드가 어긋나 있었다.)
- 수정:
  ```kotlin
  val registered = ProxyPlayback.generationFor(url)
  ```
  `generationFor`의 ALL 분기는 현재 route 세대를, Music-only 분기는
  `markResolved`의 등록 세대를 돌려준다. 캡처가 없는 `rememberServed`도
  ALL URL의 스탬프를 유지한다.
- 진리표(수정 전 → 수정 후):
  - old: ALL URL → `isProxiedUrl=false` → `registered=null` → `(null,false)`
  - new: ALL URL → `generationFor=G` → `(G,true)` (ALL이 실제 유효할 때)
  - Music-only 등록 URL: old/new 모두 `(등록 세대,false)`
  - 직접 URL: old/new 모두 `(null,false)`
- 테스트: `StreamResolverAllScopeStampTest.a retried all-scope url keeps its route stamp`
  (`rememberServed` → `cachedProxyStampForTest`; 수정 전에는 generation null/flag false로 실패).

## MEDIUM 2 — 조인한 호출자가 완료 후 자기 시작 상태로 스탬프를 덮어씀

- 파일: `shared/.../innertube/StreamResolver.kt:317`(수정 전 캡처), `:524-556`(walk)
- 증거(수정 전): `resolve()`가 walk 시작 전에 `mintedGeneration`/`mintedOnAllScope`를
  캡처하고, `coalescedResolve()`가 기존 walk에 조인해도 반환 후 자기 캡처로
  `remember()`를 호출했다. walk가 시작된 뒤 route/scope가 바뀌면, 먼저 mint된 URL이
  나중 상태의 세대로 기록되어 다음 open이 새 route로 옛 URL을 요청(403).
- 사용자 영향: ALL + 느린 walk + 프록시 서버 전환/스코프 변경 시 403 또는 재해석.
- 흔한 정도: 서버 장애 전환·설정 편집 직후 재생/탐색. 백업 전환 시 재현.
- 수정: 캡처와 `remember` 호출을 walk 안/밖으로 분리.
  - `coalescedResolve`가 `ResolvedWalk(stream, generation, onAllScope)`를 반환.
  - walk의 async 블록이 시작 시 `routeGeneration`을 한 번만 캡처한다.
  - 조인한 호출자는 `Deferred`가 돌려준 같은 스탬프를 쓴다.
- 진리표:
  - old: 호출자별 `await` 완료 시점 자체 캡처
  - new: walk 시작 시 1회 캡처, 모든 호출자 공유
- 테스트: `StreamResolverAllScopeStampTest`의 walk 경계 케이스 +
  `ProxyRouteGenerationTest.an all-scope resolve is stamped with the route it started on`.

## MEDIUM 3 — ALL이 walk 중 bypass로 들어가 직접 발급한 URL이 proxied로 저장

- 파일: `shared/.../innertube/StreamResolver.kt:535-553`,
  `shared/.../net/ProxyPlayback.kt:149`(`mintedOnAllScopeAfterWalk`)
- 증거(수정 전): `mintedOnAllScope`를 walk 진입 시점에만 캡처했다. ALL이 walk 중
  프록시 장애 bypass로 들어가면 failsafe/직접 경로가 URL을 발급하는데, 완료 후
  그 URL이 `mintedOnAllScope=true`로 저장됐다. bypass가 풀리면 프록시로 fetch → 403.
  반대 방향(직접 mint 후 ALL 진입)도 같은 오류였다.
- 사용자 영향: ALL 프록시 장애 중 재생/탐색이 403, 또는 bypass 해제 후 첫 open 실패.
- 흔한 정도: ALL + 프록시 장애/백업 전환 시. 느린 망에서 walk가 길수록.
- 수정: walk 시작과 종료 **양쪽** 모두 ALL이 실제 유효할 때만 proxied로 본다.
  ```kotlin
  fun mintedOnAllScopeAfterWalk(allScopeAtStart: Boolean, allScopeAtEnd: Boolean) =
      allScopeAtStart && allScopeAtEnd
  ```
  `allScopeInForce()`는 `ProxyPlaybackPolicy.allScopeNow`로 usable/scope/bypass를
  모두 반영한다(기존 항 삭제 없음).
- 진리표(수정 전 → 수정 후):
  - old: `allScopeAtStart`
  - new: `allScopeAtStart && allScopeAtEnd`
  - true/true → proxied(유지), true/false → direct(수정), false/true → direct(수정)
- 테스트: `StreamResolverAllScopeStampTest`의
  `a walk that enters the bypass mid-flight...`, `...switches into all-scope...`,
  `...stays on all-scope...`.

## LOW 4 — URL 불일치 시 조용히 return하던 forget 헬퍼

- 파일: `app/.../playback/StreamChoice.kt:151`, `app/.../playback/QualityUpgrade.kt:737`
- 증거(수정 전): `firstOrNull { it.value.url == url } ?: return` — 정확히 같은 문자열이
  아니면 로그 없이 끝났다. `Uri`/OkHttp 재인코딩 차이로 pin이 남아도 알 수 없었다.
- 사용자 영향: rate-limited URL이 `StreamChoice`/`QualityUpgrade` pin에 남아 다음 open에
  다시 서빙 → 429/503 반복. 원인 추적 불가(조용한 skip).
- 흔한 정도: 모듈/강제 업그레이드 URL이 재인코딩되는 경우 전부.
- 수정: 양쪽 모두 `canonicalUrl`(OkHttp `toHttpUrlOrNull()?.toString()`)로 비교하고,
  불일치 시 `TrackLog.w(TAG, "... rate-limited URL: ${LogUrl.of(url)}")`로 남긴다.
- 진리표:
  - old: `url == stored`(문자열) → 불일치면 무로그 return
  - new: `canonical(url) == canonical(stored)` → 불일치면 경고 로그 후 return

## LOW 5 — PoToken outer backstop이 재생성 재시도를 덮지 못함

- 파일: `shared/.../innertube/potoken/PoTokenGenerator.kt:154`
- 증거(수정 전): `POTOKEN_TIMEOUT_MS = 75_000`. 주석은 재생성 경로를 처리한다고 했지만
  실제 재시도는 `45s init + 15s mint`를 한 번 더 수행한다(최대 120s). 75s는 두 번째
  시도 중간에 바깥에서 취소해, 느린 콜드 페이지가 영원히 token을 못 만든다.
- 사용자 영향: 느린 망/콜드 스타트에서 BotGuard 클라이언트가 빠져 재생·다운로드 성공률 저하.
- 흔한 정도: 콜드 스타트 + 느린 망에서 첫 mint가 실패해 재생성되는 경우.
- 수정: `135_000L`(2 × 60s + 여유). outer는 자체 timeout을 무시하는 페이지용 backstop이며
  살아 있는 페이지의 mint는 계속 내부 15s가 담당한다. 타이머/폴링 추가 없음.
- 진리표: old `75s < 120s` → new `135s > 120s`
- 테스트: `PoTokenGeneratorTimeoutBudgetTest.the outer budget covers the recreation retry`
  (수정 전 75s에서 실패).

---

## 실제 성적표와의 연결 (이번 수정분)

- 다운로드·고음질 보관함 실패 140회: HIGH 1·MEDIUM 2·3이 만드는 403/재해석이
  재시도 예산을 소모하던 경로를 줄인다. (게이트/프록시 미시도는 2차 수정분이 담당.)
- 다음 곡 싱크 준비 29% / 자동 재생 보충 24%: 백그라운드 read-ahead가 같은 walk에
  조인할 때 스탬프가 어긋나 다음 open이 403 나던 경로를 제거.
- 무손실 확인 후 실제 무손실 재생 4%: HQ 검증이 쓰는 pin이 rate-limit 후 남지 않도록
  LOW 4가 canonical 비교 + 로그로 보강.
- 사용자 조작 없는 백그라운드 시작 11회: 새 타이머·폴링 없음. PoToken outer는
  on-demand backstop만 늘렸다.

## 남긴 결함

- 이번 지적 5건 중 HIGH/MEDIUM 미수정은 없다.
- 잔여 경계: walk 중 route 세대가 바뀌면(백업 전환) 시작 세대로 스탬프되어
  `cachedUrlUsable`가 false가 되고 재해석된다. 이는 403이 아니라 안전한 재해석이며,
  요청 단위 route를 정확히 기록하려면 HTTP 계층까지 세대를 전달해야 한다(현재 범위 밖).

## 검증

- `./gradlew :shared:jvmTest` — 전체 통과.
- `./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.innertube.StreamResolverAllScopeStampTest'`
  `--tests 'com.music.bitchord.data.innertube.potoken.PoTokenGeneratorTimeoutBudgetTest'`
  `--tests 'com.music.bitchord.data.net.ProxyRouteGenerationTest'` — 통과.
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true` — 통과.
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.StreamChoiceTest'`
  `--tests 'com.music.bitchord.playback.QualityUpgrade*'` — 통과.

## 새/확장 테스트 이름

- `shared/src/jvmTest/.../innertube/StreamResolverAllScopeStampTest.kt`
  - `a retried all-scope url keeps its route stamp` (HIGH 1)
  - `a walk that enters the bypass mid-flight is not stamped as all-scope` (MEDIUM 3)
  - `a walk that switches into all-scope mid-flight is not stamped as all-scope` (MEDIUM 3)
  - `a walk that stays on all-scope is stamped as all-scope`
- `shared/src/jvmTest/.../innertube/potoken/PoTokenGeneratorTimeoutBudgetTest.kt`
  - `the outer budget covers the recreation retry` (LOW 5)
