# 체감 속도·효율 (05-features-weight / 08 성능)

작업 기준: `task/proxy-fallback` HEAD (ko-101 이후 모든 변경 포함, 대기열 작업 결과 포함).
로컬 Gradle 빌드는 하지 않았고 컴파일·테스트는 CI(`ko-wip-check`)가 수행한다.
사용자 기능·설정·저장 형식은 바꾸지 않았고, 아래는 같은 입력에 같은 결과를 내는
경로만 손봤다. ko-101..HEAD에서 새로 들어온 HQ 검증·Song Info·가사 타이밍·
크로스페이드/자동 믹스·다운로드 메뉴 코드를 먼저 읽고, 그 위에 있던 기존
캐시·경쟁 경로와 함께 확인했다.

## 요약

| # | 문제 | 위치 | 조치 | 기대 효과 |
|---|---|---|---|---|
| 1 | HQ 검증이 기준/후보 90초 디코드를 **순차**로 수행 | `HqAudioVerify.kt:147-190` | 두 디코드를 `async`로 동시에 시작, 기준이 없으면 후보 취소 | 콜드 검증 시간 ≈ `기준 + 후보` → `max(기준, 후보)` |
| 2 | 업그레이드 오디션 전 헤더 워밍이 64KB 락 프로브를 먼저 보냄 | `AudioCache.kt:1638-1664`, `:2458` / `PlaybackService.kt:6848-6855` | 오디션 워밍만 `probeLock = false` | 범위 요청 4회(0·65536·1048576·seek) → 3회(프로브 제거), 필수 범위는 헤더·seek |
| 3 | 소스 경쟁에서 **이미 진 후보**도 90초 검증을 수행하고 그 검증이 끝날 때까지 대기 | `SourceResolver.kt:1035-1052` | `HqQuality.couldWin`으로 승산 없는 후보는 `accept` 호출 생략 | 불필요한 검증·대기 제거, 선택 결과는 동일 |
| 4 | Song Info를 열 때마다 player/next/링크/헤더 파싱을 반복 | `SongInfoRepository.kt:190-224` | 성공한 답을 5분·64개 LRU로 재사용(실패는 캐시 안 함) | 같은 곡 재열기에서 네트워크·JSON 파싱 0회 |
| 5 | 기록 화면의 큰 목록이 키 없는 `itemsIndexed` | `HistoryScreen.kt:95-99` | `index + videoId` 키 추가 | 스크롤·항목 재사용 안정화 |
| 6 | 첫 페이지 로드와 탭 갱신이 같은 홈 엔드포인트를 동시에 요청 | `MainViewModel.kt:2616`, `:2444` | `homeLoadJob`이 살아 있으면 갱신이 그 로드에 합류 | core feed·최근재생 중복 요청 제거 |
| 7 | (확인) 표지·가사·애드온의 중복 요청 | 아래 "이미 되어 있던 것" | 코드 변경 없음 | 회귀 없음 확인 |

### 전후 (코드 경로 기준)

기기 실측은 로컬 빌드 금지로 새로 만들지 않았다. 아래는 같은 입력에서
없어지거나 겹치게 된 작업량이다.

| 구간 | 전 | 후 |
|---|---|---|
| HQ 기준+후보 디코드 | `reference + candidate` (순차) | `max(reference, candidate)` (병렬) |
| 같은 곡 후보 여러 개 | 후보마다 기준 90초 디코드 | 기준은 `referenceLocks`로 1회, 나머지는 대기 |
| 승산 없는 후보 | 90초 검증 후 탈락 | 검증 생략, 최종 선택은 동일 |
| 오디션 네트워크 범위 | 4회 (프로브·헤더·경계·seek) | 3회 (프로브 제거) |
| 홈 첫 로드+탭 갱신 | core feed·최근재생 2회씩 | 1회씩 (갱신이 로드에 합류) |
| Song Info 재열기(5분 내) | player·next·링크·헤더 재요청 | 0회 (LRU) |


## 1. HQ 검증 디코드 병렬화 — `HqAudioVerify.kt:147-190`

기존에는 `referenceFeatures()`가 끝난 뒤 `candidateFeatures()`를 호출했다. 둘은
서로의 결과를 쓰지 않는다(기준은 캐시된 YouTube 오프닝, 후보는 자기 URL).
`coroutineScope` 안에서 후보를 `async`로 먼저 띄우고 기준을 기다린다. 기준이
없으면 후보를 취소하고 기존과 같은 "no reference audio" 경로로 빠진다.

결과는 그대로다. 같은 90초 창, 같은 `ChromaAlignment.align`, 같은
`HqMatchPolicy.verdict`를 쓴다. 달라지는 것은 두 읽기가 겹치는 시간뿐이다.
후보 쪽 디코더에는 `abort`를 넘겨, 기준이 없거나 호출이 취소되면 남은 90초를
끝까지 읽지 않고 바로 멈춘다(`HqAudioVerify.kt:331-357`). 같은 YouTube id의
기준 프로필은 `referenceLocks`로 한 번에 하나만 디코드한다. 경쟁 중인 후보들이
모두 캐시 미스로 시작해도 두 번째부터는 첫 번째의 프로필을 기다리므로, 같은
기준 90초를 여러 번 읽지 않는다(`HqAudioVerify.kt:104`, `:247-261`). 취소로
끝난 기준 읽기는 "기준 없음"으로 기록하지 않아 다음 재생이 다시 시도할 수 있다.

- 전(코드 경로): `referenceDecode + candidateDecode + align`
- 후(코드 경로): `max(referenceDecode, candidateDecode) + align`
- 사용자 보고 로그의 `upgrade to FLAC proved in 2218~8424ms`는 오디션 구간이고,
  그 앞의 검증도 같은 순차 구조였다. 두 디코드가 비슷한 크기라면 검증 구간이
  대략 절반으로 줄어든다. 기기 실측은 로컬 빌드 금지로 새로 만들지 않았다.

## 2. 오디션 범위 요청 줄이기 — `AudioCache.kt`, `PlaybackService.kt`

로그의 `auditioning upgraded … at 0 / 65536 / 1048576 / 4514742`는 각각
헤더 워밍(0), `fetch`의 락 프로브(65536 = `LOCK_PROBE_BYTES`), 헤더 워밍의
나머지(1048576 = `UPGRADE_HEADER_BYTES`), 오디션 플레이어의 seek 지점이었다.

`fetch`의 64KB 프로브는 "다른 writer가 엔트리를 잡고 있나"를 싸게 확인하는
장치다. 오디션은 그 직전에 엔트리를 버렸거나(또는 이 URL의 바이트임을 알고)
있고, 마커가 붙은 `#hifi` 엔트리를 아직 아무 플레이어도 읽지 않으므로 프로브가
필요 없다. `warmRange(..., probeLock = false)`로 그 한 번을 없앴다.

- 전: 프로브 64KB → 헤더 1MB → (캐시 경계 연속 읽기) → seek (4회)
- 후: 헤더 1MB → (캐시 경계 연속 읽기) → seek (3회)
- 프로브가 사라져 필수 범위인 "앞부분(헤더) 1회 + 끝부분(seek) 1회"에 가까워졌다.
  캐시 경계(1048576)에서 한 번 더 읽는 것은 컨테이너 헤더/메타데이터가 그 지점을
  넘는 경우이며, 헤더 크기를 늘려 없애면 데이터를 더 받아야 해 배터리 우선
  원칙에 맞지 않아 그대로 두었다.

`probeLock` 기본값은 `true`라 다른 호출부(분석 헤드, 스왑 후 분석 워밍)는
기존 동작 그대로다. 헤더를 파싱해야 seek 지점을 읽을 수 있으므로 이 두 범위는
본질적으로 순차다. 병렬화한 것은 서로 독립인 기준/후보 90초 디코드(1번)이고,
여기서는 요청 수를 줄였다.

## 3. 승산 없는 후보는 검증하지 않는다 — `SourceResolver.kt:1035-1052`

`bestAcross`는 `waitForAll` 경로(다음 곡 준비·업그레이드)에서 모든 소스의
답을 모은다. 그동안 `accept`(= `HqAudioVerify.verify`)가 **모든** 후보에 대해
실행됐다. 이미 320kbps가 찾아진 뒤 128kbps 후보를 90초 디코드하는 것은
결과에 영향이 없고, 그 검증이 끝날 때까지 경쟁을 붙잡기만 했다.

선택 규칙 자체를 `HqQuality.couldWin`(`HqTier.kt:155-165`)으로 옮기고,
승산이 없으면 `accept`를 부르지 않는다. 규칙은 기존과 같다.

- 더 좋은 rendition이면 승리
- 같으면 사용자 소스 순서가 앞선 쪽이 승리
- 더 나쁘면 소스 순서와 무관하게 패배

따라서 최종 `best`는 동일하고, 느린 후보의 검증이 다른 후보의 선택을
막지 않는다. 지금 검증하지 않은 후보는 나중에 그 소스가 실제로 선택될 때
`HqAudioVerify.verify`가 한 번 검증하고 결과를 기존 키
`(YouTube id, 소스 config id, 소스 트랙 id)`로 기억한다. 새 순수 함수는
`HqTierTest`에서 경계를 검증한다.

## 4. Song Info 재요청·재파싱 제거 — `SongInfoRepository.kt:190-224`

`SongInfoDialog`와 `SongInfoPage`가 같은 곡에 대해 각각 `load()`를 부르고,
닫았다 다시 열 때도 player/next/링크/헤더를 다시 읽었다. 성공한 `SongInfo`를
`videoId` 기준 5분·64개 LRU에 보관한다. `failed`는 캐시하지 않아 Retry와
다음 열기가 다시 요청할 수 있다. 표시 필드·문구·레이아웃은 그대로다.

## 5. 기록 목록 키 — `HistoryScreen.kt:95-99`

`itemsIndexed(songs)`에 `key = { index, song -> "${index}_${song.videoId}" }`를
넣었다. 기존 주석의 의도(videoId 단독은 중복 가능, index로 총체성 확보)를
그대로 지키면서 LazyColumn이 항목 상태를 안정적으로 재사용한다.

## 6. 홈 로드·갱신 합치기 — `MainViewModel.kt:2616`, `:2444`

`refresh`는 다른 *갱신*끼리만 합쳤고(`refreshJobs`), 첫 페이지 *로드*와 갱신은
서로를 몰랐다. 실행 직후 탭이 다시 보이면 `loadHome`의 core feed와
`homeRecentlyPlayed`, 그리고 `refreshHome`의 같은 두 요청이 동시에 나갔다.
이제 `homeLoadJob`이 살아 있으면 홈 갱신은 새 요청을 만들지 않고 그 로드에
합류한다. 갱신을 시작하지 않으므로 `homeLoadGeneration`이 그대로 남아 로드의
답이 화면에 남는다. 강제 갱신(당겨서 새로고침, 프록시 경로 변경)은 예외로 그대로
실행된다. 계정 전환처럼 새 로드가 필요한 경우에는 `loadHome`이 새 job으로
`homeLoadJob`을 교체하므로 갱신 가드는 새 로드를 따른다.

## 이미 되어 있던 것 (변경 없음, 회귀 확인)

- **홈 갱신 중복(갱신끼리)**: `refreshJobs[feed]`가 같은 피드의 동시 갱신을
  합치고(`MainViewModel.kt:2429-2457`), 탭 재진입은 `homeStale` 플래그로 한 번만
  갱신한다(`:2329-2336`). 첫 로드는 core+최근재생+보충을 병렬로 띄우고, 보조
  탭은 `STARTUP_DEFER_MS` 뒤로 미룬다(`:2218-2226`).
- **표지 `kind=cover` 검색**: `YouTubeCoverResolver.matchFor`가 정착된 답을
  영구 캐시하고, 같은 곡의 동시 호출은 `inFlight`로 합친다
  (`YouTubeCoverResolver.kt:67`, `:139-171`). iTunes→Deezer는 앞의 답이
  성공하면 뒤를 부르지 않는 순차 구조를 유지했다(배터리 우선).
- **가사 제공처 검색**: `LyricsRepository`가 같은 요청을 `inFlight`로 합치고
  (`LyricsRepository.kt:263`), 최근 답을 `recent` LRU에 보관하며
  (`:708`), 디스크 캐시를 먼저 읽는다. 가사 화면은 `derivedStateOf`와
  `remember(lines)`로 프레임마다 목록을 다시 만들지 않는다
  (`PlayerLyrics.kt:603`, `:1876`, `:1895`).
- **애드온 manifest/search**: `AddonClient`의 `SharedCalls`가 TTL·동시 호출
  합치기를 담당한다(`AddonClient.kt:50-51`, `:82`, `:133`).
- **소스/버전 검색**: `SourceResolver.searchAnswers`(10분,
  `SourceResolver.kt:103`, `:1105`)와 `remembered`(30분, `:111`), 그리고
  `YtMusicRepository`의 audio/video version in-flight 맵
  (`YtMusicRepository.kt:99-100`)이 같은 검색을 재사용한다.
- **검색 입력**: 타자 중에는 `debounce(TYPEAHEAD_DEBOUNCE_MS)` +
  `collectLatest`로 두 파이프라인만 돈다(`MainViewModel.kt:3301`, `:3334`).
  제출 검색은 사용자의 명시적 행동이라 디바운스를 두지 않는다(기존 의도 유지).
- **큰 목록 키**: 재생목록/앨범 행은 이미 키가 있다
  (`DetailScreen.kt:856-858`), 재생 대기열도 키가 있다
  (`PlayerQueue.kt:316`, `:408`). 홈·보관함의 가로 선반은 항목 수가 작아
  키 추가 이득이 없어 건드리지 않았다.

## 테스트

- `shared/src/jvmTest/.../data/sources/HqTierTest.kt`
  - `a candidate that cannot win is not worth proving`: 찾은 답 없음/후보 없음/
    더 좋음/더 나쁨/동률+앞선 순서/동률+뒤진 순서의 경계.
- 기존 `HqMatchPolicyTest`, `SubstituteOriginalTest`, `SongInfoParserTest`,
  `SongInfoDisplayTest`는 그대로 통과해야 한다(공개 동작 불변).
- CI 필터: `com.music.bitchord.data.sources.*` 포함.

## 남은 것 / 위험

- 기기 실측 전후 시간은 로컬 빌드 금지로 새로 측정하지 못했다. 위 수치는
  코드 경로와 사용자 로그(2218~8424ms, 0/65536/1048576/4514742)에서 도출한
  기대값이다.
- `prefetchSubstitute`는 여전히 `waitForAll = true`다. 느린 소스의 *검색*까지
  기다리지만, 이제 승산 없는 후보의 *검증*은 기다리지 않는다. 검색 자체를
  기다리지 않게 하려면 선택 결과가 달라질 수 있어 이번 범위에서 제외했다.
- `HqVerificationStore.save`는 검증 1건마다 prefs 전체를 다시 쓴다. 검증
  빈도가 낮아 이번에는 손대지 않았다(배터리 문서의 기존 제안과 동일).
- Song Info 5분 캐시는 조회수·좋아요가 최대 5분 늦게 보일 수 있다. 앨범·
  발매일·크레딧은 변하지 않는다.
