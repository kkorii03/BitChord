# 작은 낭비 묶음 (FIXLIST 96~98 + 취소된 콜드 작업)

기준: `task/fix-1030` 계열 작업 트리, 2026-10-30. 영역은 플레이어 화면 상태 저장,
AutoPlay 커버/노래방 거르기, 셔플 로그, 그리고 백그라운드/프로세스 회수 뒤의
visitor id·PoToken 재사용이다. 주기 폴링·타이머는 추가하지 않았다.

## 결론

| # | 심각도 | 위치 | 문제 | 조치 |
|---|---|---|---|---|
| 96 | MEDIUM | `sharedUi/.../NowPlayingScreen.kt:1036-1053`, `AppSettings.kt:2347` | 대기열/가사를 열고 닫을 때마다 `last_player_screen`을 `apply()`로 다시 썼다. 1분에 8회(열기/닫기 2회 × 4회)가 실제 사용 로그와 맞는다. | 화면 상태는 메모리에만 두고, 포그라운드를 떠날 때와 플레이어 컴포지션이 폐기될 때만 저장한다. 저장 경로 자체는 기존 동일값 가드를 유지한다. |
| 97 | MEDIUM | `app/.../Autoplay.kt:335-370` | 설정 토글 등으로 같은 시드의 보충이 다시 고려될 때 캐시된 같은 라디오 목록을 37행씩 다시 거르고 `dropped 37 cover/karaoke rows`를 매번 다시 썼다. | 시드 + 라디오 목록 단위 LRU(8)로 거른 결과를 재사용하고, 실제 스캔에서만 로그를 남긴다. |
| 98 | LOW | `app/.../QueueShuffle.kt:43-54` | 서비스 시작 시 주 플레이어와 대기 플레이어 모두 `applySettings`를 타서 같은 `mode: shuffle=on/off`가 두 줄씩 남았다. 상태가 같아도 항상 로그를 썼다. | 상태는 두 플레이어 모두에 계속 대입하되, 실제로 값이 바뀐 전이에서만 로그를 쓴다. |
| 99 | MEDIUM | `shared/.../Innertube.kt:228-315`, `shared/.../potoken/PoTokenGenerator.kt:49-96,290-338`, `AndroidStreamHooks.kt:78-91` | 프로세스가 회수되면 visitor id를 다시 `/sw.js_data`에서 민팅하고, 이미 민팅한 PoToken도 새 WebView에서 다시 만들었다. 다음 시작이 같은 일을 처음부터 했다. | visitor id를 세션 키별로 디스크에 보관해 같은 쿠키/계정이면 재사용하고, PoToken 결과는 visitor+video 키로 30분 TTL 동안 재사용한다. BotGuard 페이지 자체는 WebView 수명에 묶여 저장하지 않는다. |

## 진입점 → 처리 → 결과

### 96. `last_player_screen`

| 진입점 | 처리 | 결과 |
|---|---|---|
| 대기열 열기 | `queueOpen=true` → `lastPlayerScreenOf`가 QUEUE | 포그라운드 동안 디스크 쓰기 없음 |
| 대기열 닫기 | `queueOpen=false` → MAIN | 포그라운드 동안 디스크 쓰기 없음 |
| 가사/대기열 동시 | 가사 우선(LYRICS) | 기존과 같은 우선순위 |
| 앱이 뒤로 감/화면 꺼짐 | `rememberIsForeground()`가 false로 전이 | 그 시점의 화면을 1회 저장 |
| 플레이어 화면 폐기 | `DisposableEffect.onDispose` | 그 시점의 화면을 1회 저장(같은 값이면 기존 가드가 무시) |
| 프로세스 재시작 | `AppSettings` 초기화가 저장값 복원 | 마지막 화면 유지 |

기존/새 동작 진리표(대기열 열고 닫기 1회):

| 상태 | 기존 저장 횟수 | 새 저장 횟수 |
|---|---|---|
| 열기(QUEUE) | 1 | 0 (포그라운드) |
| 닫기(MAIN) | 1 | 0 (포그라운드) |
| 백그라운드 전이 | 0 | 1 |
| 플레이어 폐기 | 0 | 1 |

### 97. AutoPlay 커버/노래방 거르기

| 상황 | 처리 | 결과 |
|---|---|---|
| 커버 영상 시드, 보통 라디오 | `loadNormalAutoplayTracks` → `coverFilteredRadio` | 첫 스캔만 수행, 같은 목록 재진입은 캐시 |
| 비디오 라디오가 얇아 일반 경로로 대체 | 같은 함수로 합류 | 대체 경로도 캐시 공유 |
| 설정 토글 뒤 같은 시드 재보충 | 같은 `YtMusicRepository.radio` 목록 | `TrackMatcher.isCoverArrangement` 재계산 0회, `dropped 37` 재로그 0회 |
| 라디오 목록이 실제로 바뀜 | 시드+목록 키가 달라짐 | 새로 거르고 한 번 로그 |
| 오프라인 다운로드 비디오 라디오 | `loadOfflineVideoRadio`/`pickVideoRadio` | 이 캐시를 쓰지 않음. 기존 동작 유지 |
| 앱 재시작 | 프로세스 캐시라 초기화 | 다음 시작 첫 스캔은 정상 비용 |

### 98. 셔플 로그

| 진입점 | 처리 | 결과 |
|---|---|---|
| 서비스 생성 `applySettings(exoPlayer)` | 상태 대입 | 값이 바뀔 때만 로그 |
| 서비스 생성 `applySettings(sparePlayer)` | 같은 값 대입 | 로그 없음 |
| 범위 큐 편집 `QueueShuffle.setEnabled` | 상태 대입 | 실제 전이에서만 로그 |
| UI 토글/복원 | 별도 `from ui`/`from restore` 로그 유지 | 사용자 조작 1회당 1줄 |

### 99. visitor id / PoToken

| 상황 | 처리 | 결과 |
|---|---|---|
| 같은 계정으로 앱 재시작 | `sessionKey()` 키로 저장된 visitor 복원 | 민팅 네트워크 왕복 생략 |
| 계정/쿠키 전환 | 저장 키가 달라짐, 쿠키 setter가 메모리 값도 비움 | 이전 계정 visitor를 쓰지 않음 |
| 비로그인 | `anonymous` 키 | 익명 visitor만 재사용 |
| 같은 곡 재해석(플레이어+프리페치/다운로드) | `PoTokenResultCache` hit | 새 WebView 민팅 생략 |
| 프로세스 회수 뒤 같은 곡(30분 내) | 디스크의 PoToken 결과 재사용 | WebView 페이지 생성 없이 같은 토큰 사용 |
| 다른 곡/다른 visitor | 키 불일치 | 정상적으로 새로 민팅 |
| 30분 경과 | TTL 만료 | 정상적으로 새로 민팅 |
| 프리웜 | 캐시 조회를 건너뜀 | 페이지를 실제로 준비하는 기존 목적 유지 |
| 프록시 | 같은 토큰 제공자 경유 | 별도 분기 없음 |
| 다운로드/오프라인 재생 | 스트림 해석이 없으면 호출되지 않음 | 영향 없음 |
| Android 14+ | SharedPreferences 수명은 동일 | 영향 없음 |

## 사용 성적표와의 관계

- `자동 재생 보충 성공 24%`: 이 영역에서는 같은 시드의 보충이 설정 토글/큐 편집 뒤
  다시 들어올 때마다 같은 라디오 37행을 다시 거르고 같은 로그를 다시 남겼다. 이번
  수정은 그 반복 비용과 로그 중복을 없앤다. 다만 성공률 자체를 결정하는 나머지
  경로(라디오 응답이 비었을 때의 재시도, `autoplaySeed` 래치, 파티 공급자 선거)는
  이 낭비 묶음의 범위 밖이라 바꾸지 않았다. 실패/빈 응답은 기존처럼 로그를 남긴다.
- `다운로드·고음질 보관함 실패 140회`: visitor id와 PoToken을 매 시작마다 새로
  만들던 비용은 스트림 해석 첫 단계의 지연·실패와 맞닿아 있다. 이번 수정은 같은
  세션에서 그 왕복을 재사용하지만, 실패의 나머지 원인(HQ 검증·소스 경쟁)은
  `HqLibraryUpgrade`/`HqAudioVerify` 영역이라 손대지 않았다.
- `다음 곡 싱크 준비 29%`, `무손실 확인 후 실제 무손실 재생 4%`: 이 영역의 코드
  경로(NextTrackPreparation, HqAudioVerify)가 아니므로 이번 문서에서는 원인을
  단정하지 않는다. 잘못된 연결을 만들지 않기 위해 수정 범위에서 제외했다.

## 남긴 결함

- MEDIUM: AutoPlay 보충 성공률을 낮추는 `autoplaySeed` 래치/빈 응답 재시도 정책은
  이번 낭비 묶음에서 고치지 않았다. 성공률을 올리려면 빈 응답 뒤 시드 해제 시점과
  파티 공급자 재선거를 별도 영역에서 다뤄야 한다.
- LOW: BotGuard WebView 페이지 자체는 프로세스 간 재사용이 불가능하다. 토큰 결과만
  재사용하며, 30분 TTL은 integrity token 수명보다 보수적으로 잡은 값이다.
- LOW: `last_player_screen`은 이제 포그라운드 동안 저장되지 않는다. 비정상 종료
  (프로세스 강제 kill) 직전 화면은 잃을 수 있으며, 정상 백그라운드/화면 꺼짐/플레이어
  폐기 경로는 모두 저장한다.

## 테스트

- `com.music.bitchord.ui.player.LastPlayerScreenPolicyTest`
- `com.music.bitchord.playback.AutoplayCoverFilterCacheTest`
- `com.music.bitchord.playback.QueueShuffleLogTest`
- `com.music.bitchord.data.innertube.VisitorDataPersistenceTest`
- `com.music.bitchord.data.innertube.potoken.PoTokenResultCacheTest`

실행:
- `./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.innertube.*'`
- `./gradlew :sharedUi:jvmTest --tests 'com.music.bitchord.ui.player.LastPlayerScreenPolicyTest'`
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.playback.AutoplayCoverFilterCacheTest' --tests 'com.music.bitchord.playback.QueueShuffleLogTest'`
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true`

참고: 위 대상 테스트는 통과했다. 같은 패키지 전체(`com.music.bitchord.playback.*`)를 넓게 돌리면
이번 변경과 무관한 `BluetoothAudioTrackerTest`가 시스템 로케일 때문에 한 건 실패한다
(`System Managed` 기대, 한국어 `시스템 관리` 반환).
