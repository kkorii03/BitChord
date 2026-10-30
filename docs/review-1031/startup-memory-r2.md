# 시작 속도·메모리 2차 검토 — 이전 차수 지적 반영 (1031-r2)

대상: `YtMusicRepository.kt` Home 경로. 이전 차수 변경(`00047a67`)이 남긴 HIGH 1건,
MEDIUM 1건, LOW 1건을 코드로 재확인하고 HIGH·MEDIUM을 고쳤다. 새 폴링·타이머·delay 루프는
추가하지 않았다.

## 1. 지적별 처리

### HIGH — 게이트 재확인 후에도 Music 피드가 그대로 그려짐 (고침)

- 위치: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/YtMusicRepository.kt:177-196`
- 증거: 이전 코드는 `shouldTryMusic(key)`가 참일 때 browse를 다시 한 뒤
  `if (!MusicGateFallback.isActive(key)) return MusicHome;` 로 검사하고, 여전히 GATED면
  아래로 흘러 `YouTubeFallback.home(force)`를 돌려줬다. 차트 겹치기 리팩터가 이 검사를
  없애고 `keepChart`에서 차트만 취소했다. 그래서 게이트가 유지된 응답이 Premium 업셀
  빈 피드로 렌더링됐다.
- 사용자 영향: 한국/무프리미엄(또는 강제 게이트) 계정에서 하루 1회(프록시 사용 시 10분)
  재확인이 실패하면 Home이 정상 youtube.com 보관함 대신 빈 화면이 된다.
- 얼마나 흔한가: 게이트가 활성인 모든 계정에서 재확인 주기마다. 일반 OPEN 계정은 영향 없음.
- 수정: `shouldRenderMusicHome(gateActive) = !gateActive`를 추가하고, `MusicHome` 분기에서
  게이트가 활성이면 `YouTubeFallback.home(force)`로 라우팅한다. 차트 유지 조건도 같은
  함수를 쓴다. `MusicHome`이 파싱된 선반을 들고 다니므로 파싱은 한 번만 한다.

### MEDIUM — 공유 차트 lookup을 join한 Home이 취소해 다른 대기자가 실패 (고침)

- 위치: `YtMusicRepository.kt:232-253`, `shared/.../net/SharedLookups.kt:13-31`
- 증거: `SharedLookups`는 "기다리기만 하는 호출자는 공유 작업을 취소하면 안 된다"는 계약이다.
  그런데 `loadWithChartBefore`가 `keepChart == false` 또는 load 실패 시 `chart.result.cancel()`을
  무조건 호출했다. Home이 supplement가 시작한 차트를 join한 경우 그 취소가 소유자와
  다른 Home/supplement의 `await()`에 `CancellationException`을 전파했다.
- 사용자 영향: Home과 Explore/보조 선반이 같은 차트를 기다릴 때 간헐적으로 차트가 사라지고
  호출부에 취소 예외가 올라간다.
- 얼마나 흔한가: 차트 캐시가 빈 콜드 스타트에서 Home과 supplement가 겹칠 때. 이전 차수가
  공유를 도입하면서 생긴 경로라 시작 시점에 흔하다.
- 수정: `cancelIfNotShared()`는 `joined == false`일 때만 취소한다. 소유자는 그대로 취소한다.

### LOW — 트렌딩 선반이 없어도 차트를 await (지연은 고침, 요청은 유지)

- 위치: `YtMusicRepository.kt:170-174`
- 증거: 이전 `contentCountryCharts`는 파싱 후 트렌딩 선반이 있을 때만 차트를 읽었다. 새 경로는
  Home browse와 겹치려고 차트를 먼저 시작하고, 선반 유무와 무관하게 `await()`했다.
- 사용자 영향: 트렌딩 선반이 없는 Home도 차트 응답 시간만큼 첫 줄이 늦어질 수 있었다.
- 얼마나 흔한가: 모든 Music Home 콜드 스타트(차트 캐시가 빈 경우).
- 수정: `keepChart`가 (게이트 열림 + `MusicHome` + 트렌딩 선반 존재)일 때만 참이다. 조건이
  거짓이면 대기하지 않고 소유한 lookup은 취소한다. **남은 점**: 선반 유무는 피드를 파싱해야
  알 수 있어 요청 시작 자체는 유지했다. 시작까지 없애려면 겹치기(이전 HIGH 개선)를 포기하고
  파싱 후에 시작해야 하므로, 지연 제거와 대역폭 낭비 취소까지만 반영했다. 이 항목은 LOW이며
  이전 차수의 HIGH(차트/홈 직렬화)를 되돌리지 않는다.

## 2. 조건 변경 전후 truth table

### 게이트 라우팅 (`home()`의 Music browse 결과)

| 조건 | 이전 차수 이전 | 이번 수정 전 | 이번 수정 후 |
|---|---|---|---|
| 게이트 GATED, browse 성공 | youtube.com fallback | **MusicHome(빈 업셀)** | youtube.com fallback |
| 게이트 OPEN, browse 성공 | MusicHome | MusicHome | MusicHome |
| 게이트 GATED, browse 실패 + fallback 허용 | youtube.com fallback | youtube.com fallback | youtube.com fallback |
| 게이트 GATED, browse 실패 + fallback 불가 | 오류 | 오류 | 오류 |
| 게이트 UNKNOWN/신규, browse 성공 | MusicHome | MusicHome | MusicHome |

### 차트 시작/대기/취소

| 조건 | 이전 차수 이전 | 이번 수정 전 | 이번 수정 후 |
|---|---|---|---|
| 트렌딩 선반 없음 | 요청 없음, 대기 없음 | 요청 시작, await | 요청 시작, 대기 없이 취소(소유 시) |
| 트렌딩 있음 + 캐시 유효 | 캐시 사용 | 캐시 사용 | 캐시 사용 |
| 트렌딩 있음 + 캐시 없음 | 파싱 후 시작/대기 | browse와 동시 시작/대기 | browse와 동시 시작/대기 |
| fallback/오류 | 요청 없음 | 시작한 요청 취소 | 소유한 요청만 취소 |
| 다른 호출자가 시작한 lookup에 join | 해당 없음 | **취소(대기자 실패)** | 취소하지 않음(소유자 완료 대기) |

기존 조건의 모든 항(트렌딩 유무, 캐시 유무, fallback/오류, 게이트 활성)을 유지했고,
join한 lookup의 취소 금지 규칙만 추가했다.

## 3. 흔한 상황별 진입점 → 처리 → 결과 (변경 경로)

| 상황 | 진입점 | 처리 | 결과 |
|---|---|---|---|
| 게이트 유지된 재확인 | `home()` → `shouldTryMusic` → browse 성공 | `shouldRenderMusicHome(true)` → `YouTubeFallback.home` | 빈 업셀 대신 정상 Home |
| 프록시 켬 + 게이트 | `home()` → `shouldUseYouTubeFallback` | 실패 시에만 fallback, 성공 시 게이트 재판정 | 프록시 정상 경로 유지 |
| Home + Explore 동시 차트 | `home()`/`homeSupplement` → `SharedLookups` | 먼저 시작한 요청을 공유, Home은 join | Home 취소가 supplement를 죽이지 않음 |
| 트렌딩 선반 없는 Home | `home()` → `keepChart` | 차트 대기 안 함, 소유 요청 취소 | 첫 줄이 차트를 기다리지 않음 |
| 차트 캐시 있음 | `startContentCountryChart` | lookup 시작 생략 | 네트워크 없음 |
| 앱 재시작 후 | `MusicGateFallback.init` → `home()` | 저장된 GATED는 프록시 유무에 따라 재확인 | 이전과 동일 |
| 느린/끊긴 망 | `load()` 실패 | `cancelIfNotShared` 후 예외 유지 | Home 오류 화면, 유령 요청 없음 |
| 화면 꺼짐/백그라운드 | `home()` 호출자 | 새 타이머/폴링 없음 | 이벤트 기반 유지 |

## 4. 조용히 건너뛰는 경로 감사 (변경 경로)

| 위치 | 조건 | 판단 |
|---|---|---|
| `startContentCountryChart` | 콘텐츠 국가 없음/빈 문자열, 유효 캐시 | 의도된 생략. 캐시가 있으면 `attempt.second`가 캐시를 다시 적용 |
| `cancelIfNotShared` | `joined == true` | 소유자 작업 보호를 위한 의도된 생략. Home은 차트를 쓰지 않으므로 로그 없이 넘어가도 결과에 영향 없음 |
| `keepChart` | 게이트 활성/트렌딩 없음 | 차트 미사용이 목적. HIGH 경로는 별도 분기에서 fallback 로그를 남김 |

## 5. 남긴 결함

- LOW — 트렌딩 선반이 없어도 차트 요청은 시작된다. 위 1절 LOW 참조. 요청 시작까지 없애려면
  차트 겹치기를 포기해야 하므로 이번에는 지연·대기만 제거했다.
- 이전 문서(`startup-memory.md`)의 MEDIUM — 게이트 Home `buildHome`의 직렬 조립과
  `enrichCovers` 대기. 이번 차수는 Home 라우팅/차트 경로만 수정했고, 해당 파일은 건드리지
  않았다. 이번 검토에서도 여전히 남은 항목이다.
- 이전 문서의 LOW 2건(marker 동기 I/O, Coil 20%)도 그대로다.

## 6. 사용 성적표와 이 영역의 관계

`vc85, 362곡`: 다음 곡 싱크 준비 29%, 실제 무손실 재생 4%, 자동 재생 보충 24%, 백그라운드
시작 11회, 다운로드·고음질 보관함 실패 140회는 재생 파이프라인 지표다. 이번 수정은 Home
라우팅과 차트 요청 경합만 바꾸므로 그 숫자를 직접 개선한다고 주장하지 않는다. 관련 있는
부분은 콜드 스타트 경합 하나다. Home이 게이트인데도 Music 업셀을 받아 빈 화면을 그리면
사용자는 곡을 고르지 못해 재생 파이프라인이 시작되지 않는다. HIGH 수정은 그 진입 자체를
복구한다. 나머지 지표의 주원인은 여전히 `NextTrackPreparation`, `HqAudioVerify`, `Autoplay`,
`Downloads`/`HqLibraryUpgrade`이며 별도 영역 검토가 필요하다.

## 7. 검증

- 새 테스트
  - `shared/src/jvmTest/kotlin/com/music/bitchord/data/HomeGateRouteTest.kt`
    (`a music browse that returns while the gate is active is not rendered`)
  - `shared/src/jvmTest/kotlin/com/music/bitchord/data/HomeChartOverlapTest.kt`에 추가:
    `a fallback does not cancel a chart another caller started`,
    `a failed load does not cancel a chart another caller started`
- 실패→통과 확인: HIGH 수정 전 `shouldRenderMusicHome`을 참으로 두고
  `HomeGateRouteTest`를 실행해 1건 실패를 확인한 뒤 수정해 통과.
- 실행 결과
  - `./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.HomeGateRouteTest' --tests 'com.music.bitchord.data.HomeChartOverlapTest' --console=plain -q` 통과
  - `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` 통과
- 안전장치(전화 스피커 PCM16 상한, 다운로드 원본 무결성, 미디어 전체 메모리 로드 금지,
  인증/서명, 업스트림 구조)는 건드리지 않았다. 배터리 규칙(주기 폴링·타이머) 위반 없음.

## 8. 변경 파일

- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/YtMusicRepository.kt`
- `shared/src/jvmTest/kotlin/com/music/bitchord/data/HomeChartOverlapTest.kt`
- `shared/src/jvmTest/kotlin/com/music/bitchord/data/HomeGateRouteTest.kt`
- `docs/review-1031/startup-memory-r2.md`
