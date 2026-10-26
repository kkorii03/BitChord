# tests-net — shared data/net·data/innertube 판정 로직 보강

작업 범위: `shared/src/jvmSharedMain/.../data/net`, `.../data/innertube`의
순수 판정 로직. 운영 코드는 수정하지 않았고 테스트 파일과 이 문서만 추가했다.

## 추가한 테스트 (205개, 모두 통과)

### 프록시 경로 정책

- `data/net/ProxyRoutePolicyEdgeTest.kt` (7)
  - backup 유무 × 현재 route × 경과 시간의 모든 분기.
  - 15분 재시도 창의 경계(`-1`, `0`, `window`, `window+1`, `Long.MAX_VALUE`).
  - backup이 없을 때 BACKUP 성공도 PRIMARY로 돌아가는 기존 규칙 유지.
- `data/net/ProxyScopeMatcherEdgeTest.kt` (10)
  - host 공백/대소문자/포트 포함/널 path, music-only의 player·get_watch 제외.
  - ALL_YOUTUBE의 apex·서브도메인, `notgooglevideo.com`·`youtube.com.evil` 같은
    lookalike 거절.
- `data/net/ProxyPlaybackPolicyEdgeTest.kt` (8)
  - `cachedUrlUsable`의 direct URL/같은 generation/다른 generation/프록시 불가 조합.
  - remembered·refused·canProxy 8조합과 scope/mode/host별 `canProxyPlayback`.
- `data/net/ProxyPlaybackStateTest.kt` (7)
  - `markResolved` 최초 성공 1회 보고, URL canonical화, 같은 id의 새 URL이
    이전 URL을 잊는지, 프록시 OFF/route 전환 시 generation 무효화.

### 설정·URL 파싱·로그

- `data/net/ProxyConfigEdgeTest.kt` (12)
  - OFF 상태의 필드 무시, host 공백/백슬래시/@/단일 콜론/IPv6, backup의 선택성.
  - normalize의 bracket/공백/비밀번호 보존, toString 자격증명 마스킹.
- `data/net/ProxyUrlParserEdgeTest.kt` (23)
  - scheme 대소문자, `socks5h`, 기본 포트, trailing slash, path/query/fragment 거절.
  - IPv6 bracket/unbracket, 포트 경계와 `+80`(현재 허용), userinfo의 마지막 `@`,
    비밀번호 콜론, `%40`/`%3A`/`+`/잘못된 percent, 빈 host.
- `data/net/LogUrlEdgeTest.kt` (9)
  - scheme/host 정규화, 포트·userinfo·query·fragment 제거, encoded path 보존,
    서명 파라미터 다량 제거, 파싱 불가 표기.
- `data/net/SessionScopedCacheEdgeTest.kt` (7)
  - TTL 경계(정확히 TTL은 miss), 0/음수 TTL, 다른 세션, 미래 시각, 빈 키.

### 재시도·Retry-After

- `data/net/HqRetryPolicyEdgeTest.kt` (17)
  - 429/503만 rate limit, 공백/음수/소수/과거 날짜/잘못된 날짜 header.
  - backoff 사다리 clamp, 3회 예산, 0초/음수/정확히 cap/초과 cap, rest 계산.
  - header 대소문자·다중 값·빈 리스트.
- `data/net/ProxyFailureEdgeTest.kt` (9)
  - NoRouteToHost, 깊은 cause chain, cause cycle, null message, 대소문자,
    `407`/`unable to tunnel`, 무관한 HTTP 상태.
- `data/net/SharedLookupsEdgeTest.kt` (5)
  - 다른 key 독립 실행, 같은 Deferred 공유, isRunning/runningCount,
    실패가 모든 대기자에게 전달되고 재시도 가능, 완료 후 재실행.
- `data/net/ResolutionRaceEdgeTest.kt` (5)
  - direct/proxy 예외, 첫 실패가 proxy인 경우, 승자가 패자를 취소.

### 게이트 폴백

- `data/innertube/MusicGateFallbackDetectionTest.kt` (14)
  - 빈/채워진 shelf, 중첩 배열, mood의 navigation button, Premium upsell의
    영어/한국어/오탐, home/moods/player의 gated 판정, `shouldUseYouTubeFallback`
    16조합 truth table.
- `data/innertube/MusicGateFallbackStateTest.kt` (21)
  - UNKNOWN/OPEN/GATED, 일일/프록시 10분 재확인 경계, 신선한 verdict 미갱신,
    stale verdict 갱신, touch backoff, forced/debug override, resetAll,
    malformed/알 수 없는 persisted state, restart, browse/player/premium signal.

### 클라이언트 선택

- `data/innertube/DirectClientResolverEdgeTest.kt` (16)
  - streamingData 없음/빈 배열/비디오 전용, mime·url 없는 행, mime parameter 제거,
    ceiling 0/음수/1/151/129/MAX, bitrate 없음, refusal callback, status 형태,
    OK+Premium reason, adaptiveFormats 비배열, 동률 선택.
- `data/innertube/ResolutionOrderEdgeTest.kt` (18)
  - `itx:` round-trip/공백/대소문자, NewPipe 미학습, 비-winner refusal,
    winner 2회 miss, 최근 refusal 8개, direct 6-track window와 30분 bench,
    winner 24시간 경계, 저장된 winner load/만료/빈 path, attach, persist.
- `app/.../data/innertube/PlayerClientSelectionTest.kt` (17)
  - `c`/`cver`별 IOS/IOS_RECENT/ANDROID/ANDROID_MUSIC/ANDROID_VR/TV/WEB/MWEB/
    알 수 없는 client의 IOS fallback, media header의 Origin/Referer, range 1MiB/512KiB.

## 새 app 테스트 클래스

- `app/src/test/java/com/music/bitchord/data/innertube/PlayerClientSelectionTest.kt`

## 발견된 버그 (고침)

1. **`HqRetryPolicy.retryAfterMs`의 초 단위 곱셈 오버플로**
   - `seconds * 1000L`에 overflow 보호가 없었다. `Retry-After: 10000000000000000`
     또는 `Long.MAX_VALUE`는 음수로 넘쳐 `coerceAtLeast(0L)`이 0ms로 만들고,
     `decide()`가 즉시 재시도했다. 서버가 아주 긴 대기를 요구한 경우와 정반대다.
   - → 고침: 음수는 0으로, `Long.MAX_VALUE / 1000`을 넘는 초는 표현 불가로
     null을 반환한다. `decide`는 원래 초 값을 따로 확인해 이런 값도
     `retryAfterTooLong`으로 처리한다.
   - `HqRetryPolicyEdgeTest.an absurdly large retry-after never becomes an immediate retry`.

2. **`PlayerClient.rangeBytesFor`의 host suffix 경계 누락**
   - `host.endsWith("googlevideo.com")`이라 `notgooglevideo.com`도 googlevideo로
     오인해 1MiB range cap을 적용했다. 같은 파일의 `ProxyScopeMatcher.isYouTubeHost`는
     apex 또는 `.googlevideo.com`만 허용해 규칙이 서로 달랐다.
   - → 고침: apex이거나 `.googlevideo.com`으로 끝나는 host만 range cap 대상으로
     삼는다.
   - `PlayerClientSelectionTest.a lookalike host is not range-capped`.

그 외 새 테스트는 모두 통과한다. `+80` 포트가 80으로 파싱되는 것과 direct 5회
거절 뒤 성공 1회가 그 자체로는 bench하지 않는 것은 현재 의도된 동작으로
테스트에 고정했다.

## 실행

```bash
./gradlew :shared:jvmTest --console=plain -q
./gradlew :app:testProdReleaseUnitTest \
  --tests 'com.music.bitchord.data.innertube.PlayerClientSelectionTest' --console=plain -q
./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q
```

- shared 전체 jvmTest 통과 (신규 188개 포함).
- app 신규 테스트 17개 모두 통과.
- `:app:compileProdReleaseKotlin` 통과.
