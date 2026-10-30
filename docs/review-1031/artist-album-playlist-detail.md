# 아티스트·앨범·재생목록 상세 화면 — 엄격 검토 (2026-10-31)

범위: `DetailScreen`과 그 진입점(`MainActivity`의 `openDetail`/`openByName`/`playDetailSongs`/`shuffleDetailSongs`), `MainViewModel.openDetail`/`fillIn`, 아티스트 페이지 파서(`InnertubeParser.parseArtistPage`/`parseTwoRowItem`).
방법: 진입점 → 로딩 → 목록 구성 → 탭/셔플/뒤로 가기 → 재생 인계를 코드로 추적했다. 실제 기기 대신 정적 추적 + 단위 테스트(`shared:jvmTest`, `app:testProdReleaseUnitTest`)로 확인했고, 이번에 고친 항목은 수정 전 동작을 재현하는 테스트를 먼저 두었다.

## 1. 이번 수정 요약

| # | 심각도 | 결함 | 수정 위치 | 테스트 |
|---|---|---|---|---|
| D1 | HIGH | 아티스트의 "Videos" 캐러셀을 읽기 전에 버려서, MV만 있는 아티스트 페이지가 "No tracks here" 빈 화면이 됨. MV 표시 자체가 사라짐. | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/InnertubeParser.kt:444-446`, `:1563`, `:1586`, `:1611` | `ArtistPageVideoShelfTest` |
| D2 | MEDIUM | 상세 화면 캐러셀의 트랙 카드(비디오/곡, `browseId == null`)는 탭해도 아무 일도 없었음. 길게 눌러도 메뉴가 없었음. | `app/src/main/java/com/music/bitchord/MainActivity.kt:3697-3730`, `shared/.../model/DetailTarget.kt:24`, `MainActivity.kt:2383-2394` | `DetailTargetTest` |
| D3 | MEDIUM | 아티스트 카드가 `UC…`가 아닌 id를 가질 때(라이브러리 아티스트 피드에서 실제로 발생) 앨범으로 열려 빈/잘못된 페이지가 됨. | `shared/.../model/DetailTarget.kt:12`, `MainActivity.kt:3723` | `DetailTargetTest` |
| D4 | MEDIUM | Liked Music처럼 곡은 없고 숨긴 영상만 있는 페이지가 "No tracks here"로 바뀌어 영상 공개 행까지 사라짐. | `app/src/main/java/com/music/bitchord/ui/MainViewModel.kt:2882-2894`, `shared/.../model/DetailTarget.kt:34` | `DetailTargetTest` |
| D5 | LOW | 아티스트 인기곡 줄이 검색어/정렬을 무시하고 원본 첫 20곡을 그대로 그렸음(현재 아티스트 상단에는 검색 버튼이 없어 잠재 결함). | `DetailScreen.kt:828`, `:1491` | `DetailScreenQueueTest` |
| D6 | LOW | 상세 페이지 continuation 실패가 로그 없이 조용히 끝나 목록이 잘린 이유를 알 수 없었음. | `MainViewModel.kt:3170-3176` | 컴파일/기존 테스트 |

고친 결함: **HIGH 1, MEDIUM 3, LOW 2**. 새 테스트: `ArtistPageVideoShelfTest`, `DetailTargetTest`, `DetailScreenQueueTest`의 `artist top songs follow the open filter and keep the page cap`.

## 2. 진입점 → 처리 → 결과

| 진입점 | 처리 | 결과 |
|---|---|---|
| 홈/검색/라이브러리 카드 | `openDetail(browseId, title, …)` → `browseTypeOf` → artist/browse/로컬 분기 | 로딩 페이지 즉시 push, 결과를 같은 browseId에만 반영 |
| 트랙 행의 아티스트 탭 | `MainActivity.openByName(id, name)` → id 있으면 바로, 없으면 `findArtistPageId` | id가 있는데 빈 문자열이면 빈 id 페이지를 열 수 있음(아래 L5) |
| 아티스트 카드(상세 내 캐러셀) | `onSectionItemClick` → `detailBrowseType()` | `isArtist`면 비-UC id도 ARTIST로 열림(D3) |
| 상세 내 트랙/비디오 카드 | `isTrackCard` → `playRadio(Song(..., isVideo=item.isVideo))` | 이제 탭이 재생으로 이어짐(D2). 길게 누르면 `onShelfLongPress`가 곡 메뉴를 엶 |
| 앨범/재생목록 행 탭 | `onSongClick(queue, position)` → `playDetailSongs` | 필터된 큐의 선택 곡을 최신 페이지 스냅샷에서 다시 찾아 재생 |
| 전체 재생 | `ReleaseHeader.onPlay`/`ActionRow.onPlay` → `playDetailSongs(songs, 0)` | 필터와 무관하게 페이지 전체를 첫 곡부터 재생 |
| 셔플 | `shuffleDetailSongs` → `QueueShuffle.enableForNextQueue` → `playFrom` | 첫 페이지 즉시 재생, 앨범/재생목록이면 continuation을 서비스가 이어받음 |
| 뒤로 가기 | `MainActivity` 전역 `goBack` → `AppNavigator.pop` → `closeDetail` | 검색/폴더 선택/셸프가 열려 있으면 그것부터 닫히고, 그다음 페이지가 닫힘 |
| 셸프 "모두 보기" | `detailActiveShelf` → `ArtistShelfGridPage` | BackHandler가 셸프를 먼저 닫음 |

## 3. 흔한 상황별 실제 동작

| 상황 | 코드 경로 | 결과 / 판정 |
|---|---|---|
| 커버 영상이 앨범/플레이리스트에 섞임 | 파서 `musicVideoType`, `CoverTitles`/`SongVersionPolicy` | 상세 화면은 행을 숨기지 않고 그대로 보여 줌. 재생 시 버전 전환은 재생 계층이 담당(이 영역 밖). 표시 자체는 정상. |
| MV(비디오 노래) | `parseResponsiveListItem`, `ShelfItem.isVideo` | 앨범/재생목록 행은 `isVideo`를 유지. 아티스트 "Videos" 셸프는 이제 표시되고, 카드의 `isVideo`가 재생 Song으로 전달됨(D1/D2). |
| MV만 있는 아티스트 | `parseArtistPage` → `sections` 비어 있지 않음 → `MainViewModel` 성공 | 이전: `No tracks here`. 지금: "Videos" 셸프 표시(D1). |
| 셔플 | `shuffleDetailSongs` | 첫 페이지로 즉시 시작. `continuation != null`이고 원격이며 Cast/파티가 아니면 서비스가 이어서 섞음. 아티스트는 top songs만 대상. |
| 전체 재생 | `playDetailSongs` | 최신 스냅샷이 있으면 그 목록, 없으면 화면 목록. 앨범/재생목록은 bounded context+continuation 인계. |
| 앱 재시작 후 복귀 | 상세 스택은 유지되지 않음. 재생 큐 복원은 `PlaybackService` 소관 | 상세 화면 자체는 재시작 후 복원 대상이 아님(의도). 큐 복원 시 현재 행 버전 판정은 별도 감사(`docs/audit/check-mv-paths.md`) 대상. |
| 화면 꺼짐 | UI 이벤트 없음, 타이머 없음 | `DetailScreen`은 폴링/타이머를 만들지 않음. 화면이 꺼져도 상세 페이지가 추가 요청을 주기적으로 내지 않음(배터리 우선). |
| 백그라운드 | `canvasVisible = !(showNowPlaying && playerSong != null)` | 플레이어 시트가 올라오면 히어로 영상 디코딩을 중단. 페이지가 백그라운드로 가도 `fillIn`은 페이지가 스택에 있는 동안만 수행. |
| 프록시 켬 | `YtMusicRepository`의 `call`/fallback | 상세 로딩은 동일 경로. 프록시 실패 시 `UiState.Error`로 표시되고 조용한 빈 화면은 아님. |
| 다운로드된 곡/앨범 | `onDevice`/`localPageState` → `DetailPage.songsArePreview` | 다운로드 사본을 먼저 보여 주고 온라인 목록이 오면 교체. 단, 사본에서 바로 재생하면 continuation이 없어 이후 곡이 붙지 않을 수 있음(L2). |
| 느린/끊긴 망 | `openDetail` 실패 → `UiState.Error`, continuation 실패 → 로그 후 중단(D6) | 첫 화면은 이미 그려져 있고, continuation 실패는 이제 로그가 남음. |
| Android 14+ | 별도 권한 분기 없음(상세 화면 한정). 로컬 폴더는 기존 권한 경로 유지 | 상세 화면 자체에는 Android 14 전용 제약이 없음. |
| 동명 아티스트 | `findArtistPageId` → `bestArtistCandidate`(정확/괄호 alias, audience 순) | id가 없을 때 다른 동명 채널을 열지 않음. 기존 테스트 `ArtistCandidateTest`/`ArtistNameMatchTest`가 지키고 있음. |
| 아티스트 카드 id가 비-UC | `detailBrowseType` | 이제 ARTIST로 열림(D3). 이전에는 ALBUM으로 열려 빈 페이지가 될 수 있었음. |
| 상세 셸프의 트랙 카드 | `isTrackCard` | 이전에는 탭/롱프레스 모두 무반응. 지금은 재생/곡 메뉴(D2). |
| 표지 이미지 재시도 | `ArtistHeader`의 `LOGO_RETRIES=2`, 800ms 간격. `rememberRemoteArtworkUrl`은 키별 1회 해석 | 상세 화면에서 무한 재시도/주기 재시도는 발견하지 못함. 시그니처 API의 0/5/15/30초 backoff는 이벤트 기반 1회성이며, settled miss도 재시도하는 비용은 L4. |
| 뒤로 가기 | 전역 handler → 셸프 → 검색/폴더 선택 순 | 우선순위는 최신 등록 handler가 먼저. 상세 검색이 열린 상태에서 페이지를 닫지 않음. |

## 4. 남은 HIGH/MEDIUM (이번에 고치지 못한 것)

### L1 (HIGH) 다운로드 사본에서 시작한 재생은 온라인 continuation을 끝까지 못 붙일 수 있음
- 근거: `MainActivity.kt:3554-3617`(`playDetailSongs`), `MainViewModel.kt:3315-3351`(`takePlaybackContext`/`awaitDetailListing`).
- 증거: `hasImmediate`는 `songsArePreview=true`여도 true다. 그래서 온라인 목록을 기다리지 않고 `continuationToken`이 null인 사본으로 bounded context를 시작한다. 서비스는 토큰이 없으므로 더 가져올 페이지가 없다(`PlaybackService.kt:3160-3270`).
- 영향: 다운로드된 앨범/재생목록에서 재생을 시작하면, 온라인 목록이 나중에 도착해도 큐가 사본 곡에서 끝난다. 다음 곡 싱크/자동 보충 지표에 직접 악영향.
- 왜 못 고쳤나: 재생 즉시성(사본 즉시 재생)과 완전한 continuation 인계를 동시에 만족하려면 `playBoundedContext` 시작 후 페이지 listing이 도착했을 때 서비스 레지스트리에 토큰을 갱신하는 새 경로가 필요하다. 재생 계층 변경이라 이번 영역(상세 UI) 범위를 넘는다.
- 수정안: (a) preview 재생 시에도 `awaitDetailListing`을 짧게(예: 800ms) 기다려 토큰을 얻거나, (b) 사본 재생 후 실제 listing 도착 시 `BoundedContextRequests`/registry에 continuation을 추가하는 명령을 보낸다.

### L2 (MEDIUM) Liked Music의 숨긴 영상이 continuation에서만 오는 경우
- 근거: `MainViewModel.kt:2882-2894`, `DetailScreen.kt:681-703`.
- 증거: 첫 응답이 `songs=[]`, `hiddenSongs=[]`이고 숨긴 영상이 continuation에만 있으면 `detailListingState`가 Error를 반환하고, `fillIn`은 `(current.songs as? UiState.Success) ?: return@map`에서 hidden을 붙이지 못한다.
- 영향: 영상만 좋아요한 계정에서 영상 공개 행이 영원히 안 보일 수 있음.
- 수정안: `fillIn`의 시작 조건을 `UiState.Success`뿐 아니라 "Liked Music의 알려진 빈 상태"까지 허용하고, hidden/suggested만 추가될 때도 상태를 승격.

### L3 (MEDIUM) 이름만으로 앨범 열기(`openByName`)는 첫 앨범 검색 결과를 검증 없이 연다
- 근거: `MainActivity.kt:2189-2207`.
- 증거: 아티스트는 `findArtistPageId`로 정확/alias/audience 규칙을 적용하지만, 앨범은 `firstOrNull()`이다. 동명 앨범/커버 앨범이면 다른 릴리스가 열린다.
- 빈도: 드물지만 아티스트 상세의 크레딧처럼 앨범 id가 없는 행에서 발생.
- 수정안: 제목 정확 일치 + 아티스트 동일성(`ArtistNameMatch`/`TrackMatcher`)으로 후보를 거르고, 없으면 열지 않고 안내.

### L4 (LOW) 시그니처 API의 settled miss에도 backoff 재시도
- 근거: `DetailScreen.kt:442-460`, `ArtistSignatures.kt:83-101`.
- 증거: `signatureFor`는 확정 실패(`Entry(null)`)도 캐시하지만, 상세 화면은 `cached ?: signatureFor` 결과가 null이면 0/5/15/30초를 다시 돈다. 확정 miss인지 알 수 있는 API가 없다.
- 영향: 배터리/대기 시간 소폭. "재시도 폭주"는 아니고 1회성 50초 backoff.
- 수정안: `ArtistSignatureRepository`에 `isSettledMiss(name, base)`를 추가해 확정 miss면 즉시 루프를 끝낸다.

### L5 (LOW) 빈 browseId 가드
- 근거: `MainActivity.kt:2159`.
- 증거: `browseId != null`만 확인하고 `""`는 걸러내지 않는다. 파서가 빈 문자열을 낼 가능성은 낮지만, 나오면 빈 상세 페이지가 push된다.
- 수정안: `browseId?.takeIf { it.isNotBlank() }`.

## 5. 실사용 성적표(vc85, 362곡)와 이 영역의 관계

- **다음 곡 싱크 준비 29%**: 상세 페이지 재생은 첫 페이지만 확보한 뒤 서비스에 continuation을 넘긴다. 다운로드 사본/첫 응답 직후 탭에서는 토큰이 null인 채 bounded context가 시작되어(L1) 다음 곡의 메타데이터·버전 후보가 부족하다. 아티스트 페이지는 top 20곡만 큐로 넘기고 continuation을 넘기지 않는다(`DetailScreen.kt:828`, `MainActivity.kt:3554-3617`). 이 두 경로가 "다음 곡 준비" 실패의 상세 화면 쪽 원인이다.
- **무손실 확인 후 실제 무손실 재생 4%**: 상세 화면은 행의 `sourceQuality`/`isVideo`를 `withArtwork`에서 건드리지 않는다(`MainViewModel.kt:3357-3361`). 따라서 이 영역이 표식을 지우는 원인은 아니다. 다만 다운로드 사본에서 시작한 큐는 온라인 목록을 못 받아(L1) 고음질 후보 탐색 자체가 줄어든다.
- **자동 재생 보충 성공 24%**: 앨범/재생목록을 새로 시작할 때 서비스는 이전 큐의 AutoPlay를 의도적으로 버린다(`PlaybackService.kt`, `existingAutoplay = emptyList()`). 첫 페이지가 짧고 continuation이 늦으면 보충할 후보가 없는 구간이 생긴다. 상세 화면은 continuation을 최대한 일찍 넘기는 것이 중요하며, L1이 이 수치를 악화시킨다.
- **사용자 조작 없는 백그라운드 시작 11회**: 상세 화면에는 주기 타이머/폴링이 없다. 이 11회 자체는 상세 화면의 결함이 아니라 재생 서비스/복원 경로의 이벤트다. 배터리 규칙 위반은 발견하지 못했다.
- **다운로드·고음질 보관함 실패 140회**: 상세 화면의 "전체 다운로드"와 다운로드 폴더의 HQ 행은 탭 이벤트로만 시작된다(`DetailScreen.kt:651-665`, `:1278-1286`). 상세 화면이 재시도 루프를 만들어 실패를 늘리는 코드는 없다. 실패 원인은 `Downloads`/`HqLibraryUpgrade` 파이프라인 쪽이며 이번 영역 밖이다.

## 6. 검증

- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` 통과
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.DetailScreenQueueTest' --console=plain -q` 통과
- `./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.model.DetailTargetTest' --tests 'com.music.bitchord.data.innertube.ArtistPageVideoShelfTest' --console=plain -q` 통과

참고: `:app:testProdReleaseUnitTest` 전체 실행은 이번 변경과 무관한 기존 실패 22건(로케일 문자열 누락 16건, 한국어 로케일 기대값, `ArtistNameMatchTest`의 `normalizeForMatch` 기대값 등)으로 실패한다. 이번에 추가/수정한 `DetailScreenQueueTest`, `DetailTargetTest`, `ArtistPageVideoShelfTest`는 모두 통과했다.
