# 아티스트·앨범·재생목록 상세 화면 — 2차 엄격 검토 (2026-10-31)

범위: 1차 문서(`artist-album-playlist-detail.md`)가 남긴 HIGH/MEDIUM 중 재검토에서 다시 지적된 3건.
기준 브랜치: `task/fix-1030` 계열. 이번 변경은 상세 화면의 빈 상태 판정, continuation 병합, 명시적 ARTIST 힌트 우선순위만 건드린다.

## 1. 이번 수정 요약

| # | 심각도 | 결함 | 수정 위치 | 새 테스트 |
|---|---|---|---|---|
| R1 | MEDIUM | `detailListingState`가 `sections`(Videos/앨범 셸프)를 몰라, 곡이 없고 셸프만 있는 아티스트 페이지가 Error가 될 수 있었다. | `shared/.../model/DetailTarget.kt:35-41`, `app/.../MainViewModel.kt:2859-2863` | `DetailTargetTest.an artist with only shelves is content, not the empty state` |
| R2 | MEDIUM | `fillIn`이 `songs is UiState.Success`인 페이지에만 병합해서, 첫 응답이 Error이고 continuation에만 Liked Music의 숨긴 영상이 있는 경우 받아 놓고 버렸다. | `app/.../MainViewModel.kt:3203-3214`, `:3861-3900` (`mergeDetailContinuation`) | `DetailContinuationMergeTest` (5건) |
| R3 | LOW | `openDetail`이 `browseTypeOf`를 다시 돌려, 카드가 ARTIST라고 알려 줘도 id가 `MPREb`/`VL`/`PL`로 시작하면 앨범/재생목록으로 강제됐다. | `app/.../MainViewModel.kt:2707-2708`, `:2769` (`detailTypeFor`) | `BrowseTypeTest.an explicit artist hint wins over a release prefix` |

## 2. 항목별 증거와 사용자 영향

### R1 — 셸프만 있는 페이지가 빈 상태가 되는 문제
- 증거(수정 전): 아티스트 분기는 `songs.isEmpty() && sections.isEmpty()`로 직접 판정했고(`MainViewModel.kt:2847`), 앨범/재생목록 분기의 `detailListingState`는 `songs`와 `hiddenSongs`만 봤다(`DetailTarget.kt:39`). 즉 "빈 목록" 판정이 두 곳에 흩어져 있었고, 한쪽만 셸프를 알았다. 파서의 Videos 셸프 보존(D1) 이후에도 다른 진입 경로가 `browseSongs`로 흘러 셸프 없는 Error를 만들면 그 위에 셸프가 남는 조합이 가능했다.
- 사용자 영향: MV만 있는 아티스트에서 "No tracks here"가 셸프 위에 뜨고, 페이지가 비어 보인다.
- 빈도: MV/비디오 전용 아티스트, 라이브러리 피드의 비-`UC` 아티스트 카드에서 재현 가능. 흔하지 않지만 한 번 걸리면 페이지 전체가 잘못 보인다.
- 수정: `detailListingState(..., hasShelves)`를 추가해 빈 상태 판정을 한 곳으로 모으고, 아티스트 분기도 이 함수를 쓰게 했다.

조건 진리표(기존 항을 모두 유지하고 `hasShelves`만 추가):

| songs | hiddenSongs | sections | 수정 전 | 수정 후 |
|---|---|---|---|---|
| empty | empty | empty | Error | Error |
| empty | empty | non-empty | (분기별로 달랐음) Error 가능 | Success(empty) |
| empty | non-empty | any | Success(empty) | Success(empty) |
| non-empty | any | any | Success(songs) | Success(songs) |

### R2 — continuation의 숨긴 영상이 버려지는 문제
- 증거(수정 전): `fillIn`의 병합 루프가 `(current.songs as? UiState.Success)?.data ?: return@map current`로 시작했다(`MainViewModel.kt:3190`). 첫 응답이 `songs=[]`, `hiddenSongs=[]`이면 `detailListingState`가 Error를 반환하고, `more`는 설정되지만 Error 페이지는 병합 대상에서 빠져 `progressed=false`로 종료됐다. 다음 페이지에 있던 숨긴 영상은 네트워크로 받고도 버려졌다.
- 사용자 영향: 영상만 좋아요한 계정에서 Liked Music의 "N videos hidden · Show" 행이 영원히 나타나지 않는다.
- 빈도: Liked Music을 쓰는 계정에서 첫 페이지가 음악 0곡일 때. 문서화된 L2 경로.
- 수정: 병합을 순수 함수 `mergeDetailContinuation`으로 분리하고, `UiState.Error`를 "아직 행이 없는 페이지"로 취급해 `existing = emptyList()`에서 시작하게 했다. 새 행이 하나라도 오면 `songs`를 `Success`로 승격한다. `Loading`은 계속 건드리지 않는다.

조건 진리표(모든 기존 항 유지):

| current.songs | 새 행 있음 | 수정 전 | 수정 후 |
|---|---|---|---|
| Loading | any | 건너뜀 | 건너뜀 |
| Error | 없음 | 건너뜀 | 건너뜀(null) |
| Error | 있음 | 건너뜀(행 유실) | Success로 승격/병합 |
| Success | 없음 | 건너뜀(null) | 건너뜀(null) |
| Success | 있음 | 병합 | 병합 |

### R3 — 명시적 ARTIST 힌트가 prefix 추론에 지는 문제
- 증거(수정 전): 카드가 `detailBrowseType()`로 ARTIST를 넘겨도 `openDetail`이 `browseTypeOf(browseId, type)`를 다시 호출해 `MPREb`/`VL`/`PL` prefix가 ARTIST 힌트를 덮어썼다(`MainViewModel.kt:2769`, `MainActivity.kt:3725`).
- 사용자 영향: 비-`UC` 아티스트 카드가 앨범/재생목록 로더로 열려 빈 페이지 또는 잘못된 트랙 목록이 된다.
- 빈도: 라이브러리 아티스트 피드의 비-`UC` id 중 앨범/재생목록 prefix와 겹치는 경우. D3의 나머지 절반.
- 수정: `detailTypeFor(browseId, hint)`를 추가해 `hint == ARTIST`면 prefix와 무관하게 ARTIST를 쓴다. 그 외에는 기존 `browseTypeOf`와 동일하다.

진리표(기존 분기 전부 유지):

| hint | id prefix | 수정 전 | 수정 후 |
|---|---|---|---|
| ARTIST | UC | ARTIST | ARTIST |
| ARTIST | MPREb/VL/PL | ALBUM/PLAYLIST | ARTIST |
| ARTIST | unknown | ARTIST | ARTIST |
| non-ARTIST | UC | ARTIST | ARTIST |
| non-ARTIST | MPREb/VL/PL | ALBUM/PLAYLIST | ALBUM/PLAYLIST |
| non-ARTIST | unknown | fallback | fallback |

## 3. 실사용 성적표와의 연결 (변경 없음, 재확인)

- **다음 곡 싱크 준비 29% / 자동 보충 24%**: R2는 Liked Music의 숨긴 영상 행을 살리는 수정이라 큐 구성 자체를 늘리지 않는다. 다운로드 사본에서 시작한 재생의 continuation 미인계(L1, HIGH)는 재생 계층 변경이 필요해 여전히 남아 있으며, 이 수치의 상세 화면 쪽 주원인이다.
- **무손실 확인 후 실제 재생 4%**: 상세 화면은 `sourceQuality`를 건드리지 않는다. 이 영역의 회귀는 아니다.
- **백그라운드 시작 11회**: 이번 변경은 타이머/폴링을 추가하지 않는다. `mergeDetailContinuation`은 continuation 응답이 있을 때만 실행되는 이벤트 기반 병합이다.
- **다운로드·고음질 보관함 실패 140회**: 이번 변경은 다운로드/HQ 파이프라인을 건드리지 않는다.

## 4. 남은 결함

- L1(HIGH, 1차 문서): 다운로드 사본에서 시작한 재생이 온라인 continuation을 끝까지 붙이지 못할 수 있음. 재생 서비스 registry 갱신이 필요해 이번 범위 밖. `다음 검증 필요`.
- L3(MEDIUM, 1차 문서): `openByName`의 앨범 첫 검색 결과 무검증. 이번 3건에 포함되지 않아 남김.
- L4/L5(LOW): 시그니처 API settled miss 재시도, 빈 browseId 가드. 이번 범위 밖.

## 5. 검증

- `./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.model.DetailTargetTest' --console=plain -q` 통과
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.BrowseTypeTest' --tests 'com.music.bitchord.ui.DetailContinuationMergeTest' --console=plain -q` 통과
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` 통과
