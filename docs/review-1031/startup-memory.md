# 시작 속도·메모리 전수 검토 (1031)

기준: `task/fix-1030` 계열, 현재 작업 트리. 범위는 `Application.onCreate`부터 Home 첫 줄이
그려질 때까지, 그리고 그 화면이 쓰는 캐시·정적 참조·큰 목록이다. 실제 기기 측정은 이
러너에서 할 수 없으므로 로그(`startup application.onCreate`, `startup first-home-content`,
`slow: home …`)와 코드 경로로 판단했다.

## 1. 이번에 고친 것

| # | 심각도 | 위치 | 결함 | 수정 |
|---|---|---|---|---|
| 1 | HIGH | `app/src/main/java/com/music/bitchord/ui/MainViewModel.kt:1407` | 시작 2초 뒤 타이머가 화면에 없는 Explore·Library·플레이리스트까지 네트워크로 읽었다. `MainActivity.kt:1371`의 “Explore is no longer fetched during init”과 정면으로 모순되며, Home 첫 요청과 같은 대역폭·DNS·파서를 두고 경쟁했다. | 시작 타이머에는 계정 identity와 하루 1회 업데이트 확인만 남겼다. Explore/Library/플레이리스트는 각 탭의 `onExploreShown`/`onLibraryShown` 이벤트에서만 읽는다. |
| 2 | HIGH | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/YtMusicRepository.kt:123` | 정상 Music Home은 `FEmusic_home` 응답을 다 받은 뒤에야 인기곡 차트를 찾았다. 차트는 Home 본문과 독립인데도 `홈 browse + 차트 browse(+ playlist browse)`가 직렬로 더해져 첫 줄이 늦어졌다. | `loadWithChartBefore`로 Home browse와 차트 lookup을 동시에 시작한다. fallback/실패/게이트 활성으로 차트를 쓸 수 없으면 즉시 취소한다. |
| 3 | MEDIUM | `shared/.../YtMusicRepository.kt:287` | 차트 캐시가 비어 있을 때 Home 본문과 두 supplement가 동시에 차트를 요청하면 같은 차트를 여러 번 받을 수 있었다. | `SharedLookups` 한 건으로 합쳤다. 먼저 시작한 요청의 답을 Home과 supplement가 함께 기다린다. |
| 4 | MEDIUM | `app/src/main/java/com/music/bitchord/data/remote/RemoteArtworkStore.kt:40`, `DownloadedArtworkResolver.kt:44` | 원격/다운로드 앨범 아트 해석 결과가 `ConcurrentHashMap`에 무제한으로 쌓였다. 세션 길이와 라이브러리 크기에 비례해 증가하는 문자열 캐시였다. | 512개 LRU `BoundedMemo`로 상한을 두었다. 디스크 캐시·실제 추출 경로는 그대로다. |

### 조건 변경 전후 truth table

`contentCountryCharts`를 `withContentCountryChart`로 분리하면서도 기존 선택 조건은 모두 보존했다.

| 조건 | 수정 전 | 수정 후 |
|---|---|---|
| Trending 제목 선반 없음 | 원본 shelves | 원본 shelves (동일) |
| Trending 있음 + 차트 성공 | 해당 선반 교체 | 해당 선반 교체 (동일) |
| Trending 있음 + 차트 없음 | 원본 shelves | 원본 shelves (동일) |
| 차트 캐시가 이미 유효 | 캐시 사용 | 캐시 사용 (동일) |
| Home이 fallback/실패 | 차트 lookup 없음 | 시작했던 차트를 취소, fallback/실패 결과 유지 |
| 게이트가 응답 뒤 활성 | 차트 lookup 없음 | 시작했던 차트를 취소, fallback 유지 |

## 2. 흔한 상황별 진입점 → 처리 → 결과

| 상황 | 진입점 | 처리 | 결과 |
|---|---|---|---|
| 앱 콜드 스타트, 로그인, 일반 Music | `BitChordApplication.onCreate` → `MainViewModel.init` → `loadHome` | 동기 store 초기화, `home` browse와 차트 lookup 병렬, Recents·보조 선반 병렬. 2초 뒤 계정/업데이트만 | Home 첫 콘텐츠는 core feed가 오면 표시. 차트가 늦어도 core를 막지 않음 |
| 앱 콜드 스타트, 비로그인 | 같은 경로 | `home()`이 게스트 browse, `homeRecentlyPlayed`는 로컬 기록 우선 | 로컬 기록이 없으면 Recents는 비고 나머지 선반 표시 |
| 한국/무프리미엄 게이트 | `home()`의 `YouTubeFallback.home` | `buildHome()`이 Recents·Quick picks·Listen again·Likes·YouTube home·Music channel을 순차 조립 | **남은 MEDIUM**: 첫 선반 전에 여러 네트워크와 `enrichCovers`가 끝나야 함 |
| 프록시 켬 | `AppSettings.initProxy` → 첫 요청 | 프록시 설정은 첫 YouTube 요청 전에 설치됨. 게이트 재검사 1회 | 경로 자체는 유지. 차트/홈 동시 요청도 같은 프록시 사용 |
| 앱 재시작(프로세스 사망) | `LastPlayed.init`, `PlaybackService` 복원 | 저장한 큐/위치 복원. UI는 Home 로드와 독립 | 시작 화면이 재생 복원을 기다리지 않음 |
| 화면 꺼짐·백그라운드 | `AppForeground`, `PowerState` | Home 로드는 Activity가 만든 ViewModel 범위에서 계속됨. 새 폴링 없음 | 화면 밖에서 시작한 탭 선반 요청이 사라져 배터리 낭비 감소 |
| 느리거나 끊긴 망 | `YtMusicRepository.call` | core Home 실패 시 Error, 보조 요청 실패는 해당 선반만 누락 | 첫 줄은 core 성공 여부로 결정. 차트 실패는 Home 전체 실패가 아님 |
| 다운로드 곡 재생 시작 | `Downloads.init` → `AudioCache` | 시작 시 다운로드 JSON은 startup thread에서 열고, 로컬 파일 재생은 네트워크 없음 | 시작 화면과 경쟁하지 않음 |
| Android 14+ 백그라운드 제약 | `AppForeground` 기반 다운로드/HQ 작업 | 포그라운드 이벤트에서만 서비스 시작 | 이번 변경으로 새 서비스/타이머를 만들지 않음 |
| 첫 업데이트 설치 후 | `consumeVersionUpdate` | 메모리 이미지 캐시는 즉시, 디스크 이미지 캐시 삭제는 IO 코루틴 | 첫 프레임에서 수천 파일 삭제를 기다리지 않음 |
| Explore/Library 첫 탭 | `onExploreShown`/`onLibraryShown` | 그 탭이 보일 때 한 번 로드, 진행 중이면 중복 없음 | 시작 타이머와 중복 요청 없음 |
| Home 큰 목록 스크롤 | `HomeScreen` LazyColumn | 선반과 행을 지연 구성, 보조 선반은 도착 순서대로 추가 | 이번 범위에서 새 전역 캐시 없음 |
| 회전/Activity 재생성 | ViewModel 유지 | Home StateFlow와 로드 job 유지, 시작 타이머 재등록 없음 | 콜드 스타트 비용을 반복하지 않음 |

## 3. 조용히 건너뛰는 경로 감사

| 위치 | 조건 | 판단 |
|---|---|---|
| `MainViewModel.onExploreShown` | 이미 Loading이거나 job 활성 | 의도된 중복 방지. 로그는 `loadExplore` 시작 시 남음 |
| `MainViewModel.onLibraryShown` | 이미 Loading/job 활성 | 의도된 중복 방지. `onLibraryShown` 자체가 이벤트 |
| `MainViewModel.loadHome` | 이전 generation 응답 | 세대가 다르면 결과 폐기. 새 로드가 이미 로그를 남김 |
| `YtMusicRepository.startContentCountryChart` | 유효 캐시 있음 | 네트워크 생략이 목적. `withContentCountryChart`가 캐시를 다시 적용 |
| `YtMusicRepository.loadWithChartBefore` | fallback/게이트 활성 | 사용하지 않을 차트 요청을 취소. 새 조용한 실패 아님 |
| `BitChordApplication` 초기화들 | store open 실패 | `StartupWork`가 `startup open failed: <name>`을 남김 |

## 4. 남은 HIGH/MEDIUM

### MEDIUM — 게이트 Home의 `buildHome` 직렬 조립과 커버 대기

- 위치: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/YouTubeFallback.kt:202`
  (`buildHome`), `:246` (`listenAgainShelf`), `:276` (`enrichCovers`), `:1094`.
- 증거: `buildHome`은 `mergedHistory` → Quick picks → Listen again → Mixes → Likes →
  YouTube home → Music channel을 순서대로 기다린다. 마지막에 `enrichCovers`가
  `HOME_COVER_LIMIT = 20`개의 커버를 최대 4개 동시 요청으로 해석한 뒤에야 `HomeFeed`를
  돌려준다. 커버 한 건의 HTTP 상한은 8초(`YouTubeCoverResolver`의 client)다.
- 사용자 영향: 게이트(한국/무프리미엄) 사용자는 첫 Home이 core Home browse 하나가 아니라
  여러 직렬 요청과 커버 대기까지 기다린다. 로그의 Home 3.4초·Explore 3.7초를 설명할 수 있는
  가장 큰 남은 경로다.
- 얼마나 흔한가: 게이트가 활성인 계정에서는 매 콜드 스타트. 일반 Music 계정에서는 이 경로를
  타지 않는다.
- 수정안: `HomeFeed`를 먼저 publish하고, 커버/나머지 선반은 별도 업데이트 이벤트로 합치는
  채널이 필요하다. 또는 최소한 `enrichCovers`를 첫 화면 선반 몇 개로 제한하고 나머지는
  video still로 즉시 표시해야 한다. 이번에는 Home 표시 계약(선반 순서·커버 교체)을 바꾸지
  않으려고 손대지 않았다. 업스트림 파일의 큰 구조 변경이라 별도 작업이 안전하다.

### LOW — 진단 로그 process marker의 메인 스레드 read/write

- 위치: `app/src/main/java/com/music/bitchord/BitChordApplication.kt:408`.
- 증거: `marker.readLines()`와 `writeText()`가 `Application.onCreate`에서 동기 실행된다.
  파일은 작고 예외는 삼켜지지만, 콜드 스타트 메인 스레드 디스크 I/O 한 번이다.
- 사용자 영향: 낮음. 크래시 진단의 warm/cold 표시를 위해 남겨 둔 의도가 있다.
- 수정안: crash evidence를 잃지 않도록 첫 로그를 큐에 넣고 marker는 startup thread로
  옮기는 별도 변경. 이번에는 건드리지 않았다.

### LOW — Coil 메모리 캐시 20%

- 위치: `app/src/main/java/com/music/bitchord/BitChordApplication.kt:371`.
- 증거: 앱 메모리의 20%를 이미지 캐시 상한으로 잡는다. 디스크는 100MB로 명시돼 있다.
- 사용자 영향: 저메모리 기기에서 사진이 많은 Home을 오래 스크롤할 때 압박이 될 수 있다.
  다만 bitmap 상한 자체는 있어 무제한 누수는 아니다.
- 수정안: 기기 메모리 class별 상한(예: 48~96MB clamp)으로 바꾸는 정책. 실기기 프로파일이
  필요해 이번에는 유지했다.

## 5. 사용 성적표와 이 영역의 관계

제공된 `vc85, 362곡` 성적표의 다음 곡 싱크 준비 29%, 실제 무손실 재생 4%, 자동 재생 보충
24%, 사용자 조작 없는 백그라운드 시작 11회, 다운로드·고음질 보관함 실패 140회는 **재생
파이프라인 지표**다. 이 영역의 코드가 그 값을 직접 만들지는 않는다. 따라서 이번 수정으로
그 숫자가 좋아졌다고 주장하지 않는다.

이 영역에서 연결되는 부분은 하나다. 콜드 스타트가 Home·차트·숨은 Explore/Library·계정
요청을 동시에 내보내면 첫 재생의 다음 곡 준비와 소스 탐색이 같은 네트워크/디스크를 나눠
쓴다. 이번에 숨은 두 피드 요청을 없애고 차트를 Home과 겹쳐 보냈으므로, 시작 직후 재생이
겪는 경쟁은 줄었다. 그러나 다음 곡 싱크·무손실 검증·자동 보충·다운로드 실패의 주원인은
각각 `NextTrackPreparation`, `HqAudioVerify`, `Autoplay`, `Downloads`/`HqLibraryUpgrade`
경로이며, 이번 시작 속도 작업만으로 그 실패율을 고칠 수는 없다. 별도 영역 검토가 필요하다.

## 6. 검증

- 새 테스트
  - `app/src/test/java/com/music/bitchord/StartupDeferredWorkTest.kt`
  - `shared/src/jvmTest/kotlin/com/music/bitchord/data/HomeChartOverlapTest.kt`
- 실행 결과
  - `./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.HomeChartOverlapTest' --console=plain -q` 통과
  - `./gradlew :app:testProdDebugUnitTest --tests 'com.music.bitchord.StartupDeferredWorkTest' --console=plain -q` 통과
  - `./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.YouTubeFallbackHomeCacheTest' --tests 'com.music.bitchord.data.model.HomeShelfMergeTest' --tests 'com.music.bitchord.data.RecentShelfTest' --console=plain -q` 통과
  - `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` 통과
- 기존 안전장치(전화 스피커 PCM16 상한, 다운로드 원본 무결성, 미디어 전체 메모리 로드 금지,
  인증/서명 설정)는 건드리지 않았다.

## 7. 변경 파일

- `app/src/main/java/com/music/bitchord/ui/MainViewModel.kt`
- `app/src/main/java/com/music/bitchord/data/remote/RemoteArtworkStore.kt`
- `app/src/main/java/com/music/bitchord/data/remote/DownloadedArtworkResolver.kt`
- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/YtMusicRepository.kt`
- `app/src/test/java/com/music/bitchord/StartupDeferredWorkTest.kt`
- `shared/src/jvmTest/kotlin/com/music/bitchord/data/HomeChartOverlapTest.kt`
- `docs/review-1031/startup-memory.md`
