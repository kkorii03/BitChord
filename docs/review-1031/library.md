# 보관함(라이브러리) 심층 검토 — 2026-10-31

범위: 재생목록 만들기·편집·순서·삭제, 좋아요(영상 숨김 포함), 다운로드 목록,
고음질 보관함, 정렬·필터, 오프라인 표시, 계정 동기화와 로컬 사본 충돌.
기준: 이 브랜치(`dsq/deep-library-1031`)의 코드. 파일·줄은 수정 후 상태 기준.

배터리 원칙: 이번 수정은 모두 이벤트(사용자 탭·화면 전환·다운로드 완료)에서만
실행된다. 새 타이머·주기 폴링·`delay` 루프는 추가하지 않았다. 기존의
`HqLibraryUpgrade` WorkManager 지연 재시도는 서버가 지정한 1회성 지연이며
그대로 두었다.

---

## 1. 진입점 → 처리 → 결과

| 진입점 | 처리 | 결과(정상 경로) | 흔한 예외에서 실제 결과 |
|---|---|---|---|
| Library 타일 "새 플레이리스트" | `PlaylistPickerSheet` → `MainViewModel.createPlaylist` → `YtMusicRepository.createPlaylist` | 서버에 생성, `_playlists`/`editPlaylistShelf` 즉시 반영 | 로그인 안 됨: **아무 일도 없음**(시트만 닫힘) — 아래 M4 |
| 곡 메뉴 "플레이리스트에 추가" | `loadPlaylists` → `addToPlaylists` → `addOne`(중복 검사 후 추가) | 추가/이미 있음/실패를 한 줄로 보고 | 오프라인: 실패 1줄. 중복 검사는 열린 페이지 또는 `allSongs` |
| Spotify 가져오기 | `createPlaylistWithVideoIds` → 50곡 생성 + 나머지 50곡씩 추가 | 새 목록 열기 | 51곡째 이후 추가 실패는 **조용히 버려짐** — 아래 M3 |
| 플레이리스트 곡 삭제 | `removeFromPlaylist` → `removeFromPlaylist` API | 행 즉시 제거 | `setVideoId` 없으면 **로그 없이 return** — 아래 M5 |
| 이름 변경(원격) | `renamePlaylist` → `setPlaylistTitle`(목록/카드/열린 페이지) | 즉시 세 곳 반영 | 서버 거부 시 실패 안내 |
| 이름 변경/삭제(로컬) | `LocalPlaylistStore` | 카드/페이지/저장소 반영 | **이번에 연결함** — 아래 H1 |
| 순서 변경 | `loadPlaylistEntries` → `ReorderPlaylistPage` → `playlistMoves` → 일괄 move | 열린 페이지 즉시 새 순서 | 목록이 바뀌었으면 이동 0건(안전) |
| 삭제(원격) | `deletePlaylist` → 목록/카드/열린 페이지 제거 | 즉시 사라짐 | 서버 거부 시 실패 안내 |
| 좋아요/좋아요 취소 | `setLike` → `rate` → `LikeState` + `dropFromLikedLists` + `forgetFromLibrary` | 하트 즉시 반영, 목록에서 제거 | 오프라인: 롤백 |
| Liked Music 영상 숨김 | `musicOnlyLikedPage`/`YouTubeFallback.musicOnlyLiked` | 영상은 접힌 행 뒤로 | 첫 페이지·이어받기 모두 분리됨(폴백 포함) |
| 다운로드 목록 | `Downloads.getDownloadedSongs`(파일 존재 확인) → 폴더/앨범/아티스트 그룹 | 오프라인 재생 가능 | 파일이 밖에서 지워지면 열 때 정리(단, Library 카드는 다음 열람까지 남음) |
| 고음질 보관함 | `HqVerificationStore` → `HqLibraryPolicy.select` → `HqLibraryUpgrade` 1곡씩 | 검증된 상위 등급으로 교체/미리 저장 | 화면 꺼짐·백그라운드·재생 중에는 시작하지 않음 — 아래 H2 |
| 보관함 정렬 | `LibrarySort` | "Show all" 그리드에 적용 | 메인 카드 행에는 미적용(L1, 낮음) |
| 백업 가져오기 | `Backup.importFrom` | 설정/청취/플레이리스트/좋아요 복원 | 좋아요는 **합쳐짐**(문서는 교체라고 함) — 아래 M1 |

---

## 2. 발견 사항

### H1 (HIGH, 수정함) — 기기에만 있는 재생목록은 이름 변경·삭제가 아예 불가능
- 증거: 수정 전 `LocalPlaylistStore.renamePlaylist`/`deletePlaylist`를 호출하는
  곳이 저장소 정의 밖에 없었다. `BrowseActionsSheet`가 받는 `playlist`는
  `MainViewModel.playlistOwned`가 `true`인 원격 목록만이라(수정 전
  `MainActivity.kt:541?`), Spotify 가져오기나 로그아웃 상태에서 만든
  `local:playlist:sp_local_…` 카드는 이름 변경/삭제 행이 없었다.
- 사용자 영향: 가져온 목록이 기기에 영구히 쌓이고, 잘못 가져온 목록을 지울 수
  없다. "보관함"에서 사용자가 자기 데이터를 통제하지 못하는 상태.
- 빈도: Spotify 가져오기 사용자에게 항상. 로그인 실패 폴백에서도.
- 수정: `MainViewModel.renameLocalPlaylist`/`deleteLocalPlaylist` 추가,
  `MainActivity`의 카드 메뉴가 로컬 목록을 `UserPlaylist`로 표현해 같은 시트를
  쓰도록 연결. 순서 변경은 서버 `setVideoId`가 없으므로 원격 목록에만 남겼다.
- 회귀 방지: `LocalPlaylistStoreTest`의 browse-id 삭제/이름 변경 테스트.

### H2 (HIGH, 미수정) — 고음질 보관함은 화면이 켜져 있고 재생이 멈춘 동안에만 돈다
- 증거: `HqLibraryUpgrade.kt:237,241,590,595,981` — `!AppForeground`, `PlaybackActivity.active`,
  포그라운드 서비스 시작 가능 여부에서 모두 return. 다운로드 파일의 검증은
  `DownloadHealth.onPlayback`(`DownloadHealth.kt:108`)이 재생 전환 때만 수행하고,
  `HqAudioVerify.verifyDownloaded`가 `Unavailable`이면(`DownloadHealth.kt:116-119`)
  판정을 저장하지 않아 다음 재생에서 다시 시도한다.
- 사용자 영향: 화면을 끄고 듣는 사용자(대부분)는 보관함 전체가 "검증 대기"로
  남고, 실제 사용 성적표의 "무손실 확인 후 실제 무손실 재생 4%"와
  "다운로드·고음질 보관함 실패 140회"에 직접 기여한다. 백그라운드에서 새로
  시작된 재생은 11회뿐이라는 수치도 같은 게이트 때문이다.
- 빈도: 화면 꺼짐/백그라운드 재생은 일상적.
- 수정안(큰 작업): (1) `DownloadHealth`의 검증을 화면 상태와 분리해 이미
  다운로드된 파일을 디코드할 수 있는 포그라운드 서비스 경로를 만든다.
  (2) `HqLibraryUpgrade`의 `foreground` 요구를 "서비스 시작 가능"과 분리하고,
  WorkManager 포그라운드 작업으로 1곡씩 처리한다. (3) `Unavailable` 판정을
  영구 실패로 저장하지 않는 현재 규칙은 유지한다. 오디오 디코드 비용 때문에
  재생 중 동시 실행은 계속 금지해야 한다.

### M1 (MEDIUM, 미수정) — 백업 복원이 좋아요/싫어요를 "교체"가 아니라 "병합"
- 증거: `Backup.kt:144-145` — `file.likedVideoIds.forEach { LikeState.set(...) }`,
  `dislikedVideoIds`도 동일. 기존 `LikeState`를 지우지 않는다. 같은 파일의
  문서(`Backup.importFrom` 주석)는 "replaces ... liked rows"라고 명시한다.
- 영향: 다른 기기 백업을 복원해도 이 기기의 옛 좋아요가 남는다. 백업에서
  빠진 곡을 좋아요 취소한 뒤 복원해도 하트가 다시 살아나며, 세션 동안
  `likeStatuses`의 override가 라이브러리보다 우선하므로 화면이 계속 틀린다.
- 빈도: 기기 이전·복원 시. 데이터 충돌 영역의 핵심.
- 수정안: `LikeState.replaceAll(map)`을 추가해 백업의 두 목록으로 한 번에
  교체한다. 단, `LikeState`에는 계정 라이브러리에서 seed된 좋아요도 섞여
  있으므로, 곧바로 전면 교체하면 첫 페이지 밖 좋아요가 일시적으로 사라질 수
  있다. 백업에서 온 id 집합을 별도로 기록해 그 집합만 교체하는 방식이 안전하다.
  (이번에 전면 교체를 넣지 않은 이유: 계정 seed와 사용자 override를 구분할
  저장소가 없어 회귀 위험이 있음.)

### M2 (MEDIUM, 수정함) — 같은 밀리초에 만든 로컬 플레이리스트가 서로를 덮어씀
- 증거: 수정 전 `LocalPlaylistStore.savePlaylist`가
  `"sp_local_" + System.currentTimeMillis()`를 id로 사용하고
  `filterNot { it.id == id }`로 기존 행을 지웠다. 두 번의 저장이 같은 ms에
  끝나면 두 번째만 남고 첫 번째는 저장소에서 사라진다.
- 영향: 가져오기 폴백/연속 생성에서 목록 하나가 조용히 유실.
- 빈도: 낮지만 발생하면 복구 불가(백업 전까지).
- 수정: `newLocalPlaylistId(now, existing)`가 비어 있는 ms까지 전진.
- 회귀 방지: 같은 ms 두 번 저장 테스트.

### M3 (MEDIUM, 미수정) — Spotify 가져오기 51곡 이후 추가 실패가 성공으로 보고됨
- 증거: `MainViewModel.kt:1024-1027` — `videoIds.drop(50).chunked(50).forEach {
  YtMusicRepository.addToPlaylist(playlistId, chunk) }`의 `Result`를 버린다.
  이후 `onResult(..., false)`로 성공을 알리고 화면은 새 목록을 연다.
- 영향: 362곡 가져오기에서 한 청크가 429/끊김으로 실패하면 최대 50곡이
  조용히 빠진 목록이 "완료"로 표시된다. 사용자는 곡이 없는 것을 한참 뒤에
  발견한다.
- 빈도: 50곡 초과 가져오기에서 네트워크가 느리거나 끊길 때.
- 수정안: 청크별 `Result`를 모아 실패 수를 콜백에 전달하고, 부분 실패 문구를
  한 번 보여 준다. 이미 만든 목록은 유지하되 "n곡을 추가하지 못했습니다"를
  표시하고, 실패 청크만 재시도할 수 있는 경로를 둔다.

### M4 (MEDIUM, 미수정) — 로그아웃 상태의 "새 플레이리스트"가 조용히 무시됨
- 증거: `LibraryScreen.kt:164-175`가 로그인하지 않아도 `PlaylistShelf`와
  `onNewPlaylist`를 그린다. `MainViewModel.createPlaylist`는
  `if (!requireSignIn()) return`(`requireSignIn` = `MainActivity`의
  `MainViewModel.kt:1364`, 단순 bool)으로 아무 안내 없이 끝난다. 시트는
  `MainActivity.kt:5344`에서 이미 닫힌다.
- 영향: 버튼이 죽은 것처럼 보인다. 반면 같은 시트의 Spotify 가져오기는
  로그아웃 상태에서 로컬 저장으로 폴백하므로 동작이 불일치한다.
- 빈도: 로그인 전 첫 사용.
- 수정안: (a) 로그아웃 상태에서 "새 플레이리스트" 타일을 숨기거나,
  (b) `createPlaylist`도 `createPlaylistWithVideoIds`처럼 로컬 저장으로
  폴백하고 폼의 안내 문구를 "이 기기에 저장"으로 바꾼다. (b)는 사용자 문구
  변경이 필요해 이번 범위에서는 보류.

### M5 (MEDIUM, 미수정) — `setVideoId` 없는 행의 "이 플레이리스트에서 삭제"가 무반응
- 증거: `MainViewModel.kt:1074` — `val setVideoId = song.setVideoId ?: return`.
  로그도, `onFailure`도 없다.
- 영향: 다운로드 사본/로컬 행 등 set-video-id가 없는 행에서 삭제를 눌러도
  아무 표시가 없다. 조용한 건너뛰기 금지 규칙 위반.
- 빈도: 낮음(원격 목록 행은 대개 id가 있음). 다만 백업/가져오기 행에서 발생.
- 수정안: `DebugLog.w`로 남기고 `onFailure()`를 호출해 기존 실패 안내를
  띄운다. 또는 그런 행에는 삭제 행 자체를 숨긴다.

### L1 (LOW, 미수정) — 보관함 정렬이 "Show all" 그리드에만 적용됨
- 증거: `LibraryScreen.kt:431-437`의 `sortedForLibrary`는 `LibraryGridPage`에서만
  쓰이고, 메인 Library의 `LibraryGridShelf`(같은 파일 250-310)는 정렬을 읽지
  않는다. 정렬 버튼도 `AppRoute.LibraryShowAll`에서만 보인다
  (`MainActivity.kt:4358-4387`).
- 영향: 사용자가 정렬을 바꾼 뒤 메인 보관함으로 돌아오면 카드 순서가 그대로라
  설정이 무시된 것처럼 보인다.
- 빈도: 정렬을 쓰는 사용자.
- 수정안: 메인 `LibraryGridShelf`/`PlaylistShelf`에도 `AppUi.host.librarySort`를
  적용하고, 핀 고정과의 우선순위(핀 < 명시 정렬)를 그리드와 동일하게 맞춘다.

### L2 (LOW, 미수정) — 밖에서 지운 파일이 Library "On device" 카드에 잠시 남음
- 증거: `Downloads.savedReleases`는 `_saved` 기록만 보고 각 파일을 stat하지
  않는다(`Downloads.kt:1324`). 실제 파일 검사는 페이지를 열 때
  `getDownloadedSongs`/`getCollectionSongs`에서 한다.
- 영향: 파일 관리자로 지운 뒤 보관함 카드가 남아 있다가 열면 사라진다.
- 빈도: 외부 삭제 시.
- 수정안: 저비용으로는 그대로 두고, 카드 열기 실패 시 기록을 정리하는 현재
  동작을 유지. 정확성이 필요하면 폴더 열람 시 결과로 `_saved`를 프룬.

---

## 3. 실제 사용 성적표와 코드상 원인

- **다음 곡 싱크 준비 29% / 자동 재생 보충 24%**: 보관함에서 시작한 재생도
  다음 곡 준비가 `NextTrackPreparation`의 화면/포그라운드 조건과 겹친다.
  이번 영역에서는 직접 수정하지 않았다.
- **무손실 확인 후 실제 무손실 재생 4%**: H2. 검증이 재생 전환에서만 돌고,
  `verifyDownloaded`가 `Unavailable`이면 verdict 없이 끝나며, 보관함 패스는
  포그라운드·비재생 상태에서만 시작한다. 검증되지 않은 파일은 계속 현재
  파일로 재생된다.
- **사용자 조작 없는 백그라운드 시작 11회**: H2의 포그라운드 게이트가
  백그라운드 시작을 막는다. 반대로 게이트를 풀면 배터리/Android 14 제약이
  문제가 되므로, WorkManager 포그라운드 작업으로 명시적 1곡 처리만 허용하는
  설계가 필요하다.
- **다운로드·고음질 보관함 실패 140회**: H2의 구조적 원인 + M3의 가져오기
  부분 실패 + 원격 검증 불가(매니페스트/레퍼런스 없음)가 겹친다. 이번에는
  M2/H1을 고쳐 로컬 목록 유실/관리 불가를 제거했다.

---

## 4. 이번에 바꾼 조건의 진리표

`LocalPlaylistStore.deletePlaylist` / `renamePlaylist`의 id 정규화:

| 입력 id | 기존 삭제 | 새 삭제 | 기존 이름변경 | 새 이름변경 |
|---|---|---|---|---|
| `sp_local_1` | 삭제 | 삭제 | 변경 | 변경 |
| `local:playlist:sp_local_1` | 삭제 | 삭제 | 변경 | 변경 |
| `VLsp_local_1` | 삭제 안 됨 | 삭제 | 변경 | 변경 |
| 다른 id | 유지 | 유지 | 유지 | 유지 |

새 삭제/이름변경은 기존의 모든 항(정확한 id, browseId, `local:playlist:` 제거)을
그대로 유지하고 `VL` 접두 제거만 추가했다. 즉 새 조건은 기존 조건의 상위집합이며,
기존에 지워지던 행이 지워지지 않게 되는 경우는 없다.

`savePlaylist` id: 기존 `now` 고정 → 새로 `now, now+1, …` 중 기존 id와 겹치지 않는
첫 값. 기존에 생성되던 id(`sp_local_<now>`)는 그대로 유지된다.

---

## 5. 테스트

새/확장 테스트 (`app/src/test/java/com/music/bitchord/data/spotify/LocalPlaylistStoreTest.kt`):
- `two saves in the same millisecond get different ids`
- `deleting by browse id removes exactly the named local playlist`
- `renaming by browse id keeps position and changes only that title`

실행:
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.data.spotify.LocalPlaylistStoreTest' -Pbitchord.arm64Only=true --console=plain -q` → 성공
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` → 성공

---

## 6. 수정 파일

- `app/src/main/java/com/music/bitchord/data/spotify/LocalPlaylistStore.kt`
- `app/src/main/java/com/music/bitchord/ui/MainViewModel.kt`
- `app/src/main/java/com/music/bitchord/MainActivity.kt`
- `app/src/test/java/com/music/bitchord/data/spotify/LocalPlaylistStoreTest.kt`
- `docs/review-1031/library.md`

업스트림 v1.8.1 코드는 삭제·분할하지 않았다. 기존 안전 규칙(다운로드 파일
비교 조건, HQ 매니페스트 금지, PCM16 상한 등)은 그대로 두었다.
