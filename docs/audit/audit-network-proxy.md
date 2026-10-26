# audit-network-proxy — 프록시 재생·스트림 해석·429/IPv6 실패 경로

- 범위: `ProxyPlayback`, `YouTubeProxy`, `ProxyHealth`/`ProxyFailure`, `ProxyConfig`, `Innertube`의 프록시 재시도, `InnerTubeXResolver` 호출부, `StreamResolver`, `ChunkedDataSource`의 403/429/503 처리.
- 방법: `git grep`/`grep`으로 좁힌 뒤 해당 함수만 읽었다. 이 체크아웃에는 `ko-101` 태그가 없어 현재 HEAD를 기준으로 했다.
- 원칙: 코드에서 확인한 사실만 적었다. 기기 동작 추정은 `추측`으로 표시했다.
- 검증: `./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.net.*' --tests 'com.music.bitchord.data.innertube.DirectClientResolver*' --tests 'com.music.bitchord.data.innertube.ProxyWalkFailurePropagationTest' --console=plain -q` 통과. `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` 통과.

## 결과 표

| 심각도 | 파일:줄 | 무엇이 고장 | 재현 경로 | 수정? |
|---|---|---|---|---|
| HIGH | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/DirectClientResolver.kt:120`, `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/StreamResolver.kt:861,894,978,1598` | 프록시 플레이어 요청의 연결 실패를 각 단계가 `null`(후보 없음)로 삼켰다. `resolveViaProxy`가 `ProxyFailure.isProxyDown`을 볼 기회가 없어 백업 프록시로 넘어가지 않고 곡이 실패한다. | Set primary proxy to a closed port and a valid backup; play a track that needs the proxy. No request reaches the backup. Unit repro: `ProxyWalkFailurePropagationTest.a proxy connect failure is propagated instead of looking like no candidate` returned `[]` before the fix. | 예 |
| HIGH | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/net/ProxyHealth.kt:39-47` | IPv6 프록시 주소에 경로가 없을 때 JVM/Android는 `NoRouteToHostException`이 아니라 평범한 `SocketException("Network is unreachable")`를 던진다. 기존 분류는 이를 프록시 다운으로 보지 않아 백업 전환과 5분 우회가 모두 발동하지 않는다. | Set the proxy host to an unroutable IPv6 literal such as `2001:db8::1` with a backup configured; play/refresh. The backup is never tried. Unit repro: `ProxyWalkFailurePropagationTest.an IPv6 route failure is classified as the proxy being down` failed before the fix. | 예 |
| MEDIUM | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/StreamResolver.kt:1258-1261`, `app/src/main/java/com/music/bitchord/playback/ChunkedDataSource.kt:473-475` | 429/503에서 `Retry-After`가 8초를 넘거나 재시도 3회를 다 쓰면 데이터 소스는 실패하지만 `StreamResolver.recent`에는 같은 URL이 남는다. `PlaybackService.recoverFrom`이 약 350ms 뒤 같은 URL로 다시 열어 복구 예산을 같은 호스트에 쓴다. | Make a googlevideo range answer `429` with `Retry-After: 60`; the same URL is reopened on each recovery until the track is skipped. | 부분 (URL 캐시만 무효화; 클라이언트/호스트 제외는 아직 없음) |
| MEDIUM | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/Innertube.kt:1828-1858` | 백업이 없을 때도 프록시 실패를 direct 재시도 전에 `UNREACHABLE`로 기록했다. direct도 실패하면(기기 전체 오프라인) 프록시는 잘못된 채로 5분 동안 우회된다. | Configure a proxy, enable airplane mode, refresh Home (proxy and direct both fail), then restore the network. For the next 5 minutes Music requests bypass the working proxy. | 예 |
| MEDIUM | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/InnerTubeXResolver.kt:362-363,381`, `app/src/main/java/com/music/bitchord/download/Downloader.kt:208-214` | `minted`가 64개에 도달하면 전부 `clear()`된다. 이미 발급된 URL의 정확한 헤더도 사라져, 진행 중인 다운로드가 다음 range를 열 때 `PlayerClient.forStreamUrl`의 대체 헤더를 쓴다. | Start a long download, then mint 64+ other stream URLs (queue/browse) before its next range. `headersFor(url)` is null and `Downloader.open` falls back to client-derived headers. | 아니오 |
| LOW | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/net/ProxyConfig.kt:90-92,190-198`, `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/net/YouTubeProxy.kt:213` | 호스트 칸에 `[2001:db8::1]:8080`처럼 authority 전체를 붙여 넣어도 검증을 통과한다. 정규화는 대괄호만 벗기려다 문자열이 `]`로 끝나지 않아 그대로 두고, `InetSocketAddress`가 대괄호·포트가 섞인 호스트명을 해석하려다 실패한다. | Settings > Network proxy: host `[2001:db8::1]:8080`, port `8080`; save and run the connection test. It fails with an unknown host rather than a validation error. | 아니오 |
| LOW | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/InnerTubeXResolver.kt:297,413-418` | `excluded`는 조회한 `videoId`의 만료 항목만 지운다. 거부된 다른 트랙의 항목은 프로세스가 끝날 때까지 남는다. | Play many region-blocked tracks in one session; the per-video exclusion maps accumulate with no global trim. | 아니오 |

## HIGH 수정 고지

### HIGH 1 — 프록시 연결 실패가 백업 전환 신호를 잃던 문제

- 고친 것: `viaProxy` 경로에서 `ProxyFailure.isProxyDown`인 예외를 `DirectClientResolver.candidates`, `StreamResolver.innerTubeXStream`, `askAsUpload`, `probe`가 더 이상 `null`로 바꾸지 않고 그대로 던진다. `resolveViaProxy`의 기존 catch가 이 예외를 받아 `recordUnreachable` → 백업 재시도로 이어진다.
- 재현 테스트: `shared/src/jvmTest/kotlin/com/music/bitchord/data/innertube/ProxyWalkFailurePropagationTest.kt:26`
  - 수정 전: `Expected an exception of class java.io.IOException ... completed successfully with the result: <[]>` (실패)
  - 수정 후: `IOException` 전파 (통과)

### HIGH 2 — IPv6 `Network is unreachable`을 프록시 다운으로 분류하지 않던 문제

- 고친 것: `ProxyFailure.isProxyDown`이 `SocketException`의 `Network is unreachable`, `No route to host`, `Address family not supported` 메시지를 프록시 다운으로 분류한다. `NoRouteToHostException`은 기존 분기를 그대로 유지했다.
- 재현 테스트: `shared/src/jvmTest/kotlin/com/music/bitchord/data/innertube/ProxyWalkFailurePropagationTest.kt:42`
  - 수정 전: `assertTrue(ProxyFailure.isProxyDown(SocketException("Network is unreachable")))` 실패
  - 수정 후: 통과

## 참고

- MEDIUM 429 항목은 `StreamResolver.forgetCachedUrl`과 `ChunkedDataSource.openWithRateLimitRetry`의 호출로 URL 재사용만 막았다. 같은 클라이언트를 다시 고르면 같은 호스트를 만날 수 있으므로 완전한 해결은 아니다. 재현 테스트 `ProxyWalkFailurePropagationTest.kt:47`는 캐시 무효화만 검증한다.
- MEDIUM `Innertube` 항목은 백업이 설정된 경우에만 direct 재시도 전에 기록하도록 바꿨다. 백업이 없으면 direct가 성공했을 때만 `recordUnreachable`을 호출한다.
