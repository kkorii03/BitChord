# audit-download-library — 다운로드·보관함·오프라인 재생·고음질 재다운로드·MV 보정 감사

조사일: 2026-10-26. 브랜치 `dsq/audit-download-library`.

비교 기준은 `ko-101` 태그다. 이 저장소는 shallow snapshot이라 태그를 origin에서
`git fetch --depth=1 origin refs/tags/ko-101:refs/tags/ko-101`로 받은 뒤
`git diff ko-101..HEAD -- app/src/main/java/com/music/bitchord/download
app/src/main/java/com/music/bitchord/ui/screens/LocalFolder.kt
app/src/main/java/com/music/bitchord/ui/components/DownloadManagerSheet.kt
app/src/main/java/com/music/bitchord/data/remote/DownloadedArtworkResolver.kt
shared/src`로 확인했다. 이번 작업은 **문서만** 바꿨고 코드는 수정하지 않았다.
따라서 아래 HIGH 항목도 모두 "미수정"이며, 각 항목에 정적 확인 방법을 적었다.

범위에서 `ko-101` 이후 실제로 바뀐 중심 파일은 `Downloads.kt`(+833),
`HqLibraryUpgrade.kt`(신규 748), `DownloadHealth.kt`(신규 237),
`DownloadedQuality.kt`(신규 121), `MvSoundRedownload.kt`(+15),
`LocalFolder.kt`(+5), `DownloadManagerSheet.kt`(+85),
`DownloadedArtworkResolver.kt`(신규 161), `DownloadedMvPolicy.kt`(신규 208),
`HqLibraryPolicy.kt`(신규 195), `DownloadHealthPolicy.kt`(신규 198)이다.
`DownloadStore.kt`와 `OfflineHls.kt`/`OfflineDash.kt`는 이번 diff에 없지만,
새 재다운로드 경로가 그 위에서 실행되므로 함께 감사했다.

---

## 요약 표

| 심각도 | 파일:줄 | 무엇이 고장 | 재현 경로 | 수정안 |
|---|---|---|---|---|
| HIGH | `app/src/main/java/com/music/bitchord/download/DownloadStore.kt:231-243,266-304`, `app/src/main/java/com/music/bitchord/download/Downloads.kt:1897-1916` | 같은 `아티스트 - 제목`을 가진 **다른 곡**이 오면, 내보내기/레거시 경로는 기존 파일을 새 파일로 덮어쓴다. 기존 곡의 기록은 같은 경로를 계속 가리켜 다른 곡의 바이트를 재생한다. | A(같은 아티스트·제목, videoId A)를 먼저 받는다. B(videoId B, 같은 아티스트·제목)를 받는다. `exportDownloads=false` 또는 API 28 이하에서 B의 `.part`가 A의 최종 파일로 `renameTo`되어 A 파일이 사라진다. | 충돌 시 고유 이름을 만든다(예: 충돌한 경우에만 `(videoId)` 접미사). 또는 기존 파일을 지우지 않고 새 이름으로 쓰고, 새 파일 commit 후에만 기록을 바꾼다. |
| HIGH | `app/src/main/java/com/music/bitchord/download/OfflineHls.kt:31,76`, `app/src/main/java/com/music/bitchord/download/OfflineDash.kt:81,114`, `app/src/main/java/com/music/bitchord/download/Downloads.kt:1918-1959`, `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt:56-58` | HLS/DASH 오프라인 패키지는 새 패키지를 받기 **전에** `root.deleteRecursively()`로 기존 패키지를 지운다. 중간 실패 시 기존 다운로드가 사라지고 기록만 남는다. "이전 파일은 새 파일이 commit된 뒤에만 지운다"는 `HqLibraryUpgrade`의 안전 규칙과 정면으로 어긋난다. | HLS/DASH로 받은 곡을 고음질 재다운로드하거나 다시 받는다. 세그먼트 fetch 중 네트워크를 끊는다. 기존 `offline-hls/<hash>/playlist.m3u8`은 이미 삭제되고 새 패키지는 실패해 없다. Downloads 행은 이전 URI를 계속 가리킨다. | 새 패키지를 `<hash>.new` 같은 임시 디렉터리에 완성한 뒤 디렉터리 rename으로 교체한다. 실패 시 임시 디렉터리만 지우고 기존 `<hash>`는 그대로 둔다. `remember`가 새 URI를 기록한 뒤에만 이전 패키지를 지운다. |
| HIGH | `app/src/main/java/com/music/bitchord/download/Downloads.kt:920-938,1715-1727,1343-1399` | MV 소리 보정이 원곡을 찾지 못하면 **같은 MV 스트림을 다시 받고** `remember`가 suspect를 지운다. 새 파일은 여전히 MV 소리인데 경고가 사라지거나, 레거시 기록에서는 다시 스캔되어 같은 재다운로드를 반복한다. | MV 소리로 저장된 파일(원곡 버전 없음)에서 배너를 누르거나 `MvSoundRedownloadWorker`를 실행한다. `songVersion()`이 null을 반환하면 `track`은 같은 id가 되고, `soundSourceVideoId in ids`라서 `clearMvSoundSuspects`가 호출된다. 경고는 사라지고 파일은 그대로다. | 보정 다운로드에서 `fetched.videoId == asked.videoId`이면 실패로 끝내고 suspect를 유지한다. `remember`에서 suspect를 지우는 조건에 "다른 id의 원곡을 실제로 받았음"을 추가한다. 같은 id를 다시 받는 경로는 아예 만들지 않는다. |
| HIGH | `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt:502-535,553-605,133-140`, `app/src/main/java/com/music/bitchord/download/DownloadHealth.kt:81-97,150-160`, `app/src/main/java/com/music/bitchord/download/MvSoundRedownload.kt:31-46,60-66`, `app/src/main/java/com/music/bitchord/download/Downloads.kt:869` | 고음질 교체와 MV 보정 실패가 **영구 재시도**된다. 실패한 HQ target은 plan에서 빠지지만 다음 `buildPlan`이 같은 검증 쌍을 다시 넣고, 앱 시작·재생마다 `scheduleIfEnabled`가 다시 큐에 넣는다. MV suspect도 실패하면 지워지지 않아 앱 시작·재생·Downloads 화면 열기마다 `scheduleIfEnabled`가 다시 큐에 넣는다. `DownloadHealth`는 `wrongRecording`이면 체크 여부와 무관하게 매 재생마다 `requestUpgrade`를 호출한다. | 소스가 사라졌거나 항상 낮은 등급을 주는 검증 쌍을 만든다. 또는 네트워크를 끊은 채 MV 보정을 실행한다. 한 번 실패한 뒤 앱을 재시작하거나 곡을 재생할 때마다 같은 다운로드/검증이 다시 시작된다. | target별 시도 횟수와 마지막 실패 시각을 저장하고, 실패 후에는 긴 cooldown을 둔다(또는 수동 재시도만 허용). MV suspect도 실패 횟수를 저장해 cooldown 전에는 worker를 다시 걸지 않는다. `DownloadHealth`의 wrongRecording 분기도 저장된 repair 시각/시그니처를 확인해 cooldown 전에는 요청하지 않는다. |
| MEDIUM | `app/src/main/java/com/music/bitchord/download/Downloads.kt:969-1000,1477-1481`, `app/src/main/java/com/music/bitchord/MainActivity.kt:3433-3447` | 한 파일이 video id와 song id 두 기록으로 존재할 때, 한 행만 삭제하면 `forget(videoId)`가 그 id만 지운다. 다른 alias는 삭제된 파일을 계속 "다운로드됨"으로 주장한다. | MV를 다운로드해 두 id로 기록된 상태에서 Downloads 목록의 한 행을 선택 삭제한다. 나머지 alias가 `saved`/`metadata`에 남아 메뉴는 다운로드됨으로 보이고, 재생은 사라진 파일을 연다. | `delete`에서 같은 URI를 가리키는 모든 id를 찾아 `forget`한다(`getDownloadedSongs`의 alias 정리와 같은 규칙). 삭제 후 collection 기록도 함께 정리한다. |
| MEDIUM | `app/src/main/java/com/music/bitchord/download/Downloads.kt:818-819` | 파일 길이를 읽지 못하면 `localDurationMs ?: durations.maxOf`로 **가장 긴** 기록 길이를 파일 길이로 대신 쓴다. 정상 파일이 MV 소리로 오판될 수 있다. | 메타데이터를 읽을 수 없는 정상 파일과 더 긴 alias 행이 있을 때 Downloads 화면을 연다. `file - catalog >= 2500ms`가 만들어져 불필요한 보정 다운로드가 걸린다. | `localDurationMs`가 null이면 그 파일은 건너뛴다. 기록된 길이만으로 판정하지 않는다. (기존 `SongVersionPolicy`의 "null은 증거가 아니다" 원칙과 같게.) |
| MEDIUM | `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt:432-464,379-383,502-528`, `app/src/main/java/com/music/bitchord/download/Downloads.kt:343-344` | 같은 id의 일반 다운로드가 이미 Queued/Running이면 `Downloads.enqueue`가 조용히 반환한다. 그래도 pass는 `activeVideoId`를 그 id로 두고, 그 일반 다운로드가 성공하면 `onDownloadSettled(success=true)`가 **고음질 교체가 landing한 것으로** target을 제거한다. | 곡의 일반 다운로드를 시작한 직후 고음질 pass를 트리거한다(앱 시작/재생/`onVerified`). 일반 다운로드가 끝나면 고음질 target이 done으로 사라지고 `hqApplied`는 false다. | pass가 enqueue하기 전에 그 id가 이미 다른 요청으로 active인지 확인하고, 그렇다면 이번 run에서 target을 건드리지 않는다. `settle(success=true)`에서도 `Downloads.hqApplied(id, hq)`를 확인해 아니면 target을 남긴다. |
| MEDIUM | `app/src/main/java/com/music/bitchord/download/MediaTagger.kt:135-150`, `app/src/main/java/com/music/bitchord/download/DownloadStore.kt:271,301` | 다운로드 삭제가 오디오 파일만 지우고 `files/download-artwork` 사이드카와 `.part` 파일은 남긴다. 프로세스가 중간에 죽으면 `.part`/MediaStore pending 행이 정리되지 않는다. | 앨범을 받은 뒤 트랙을 모두 삭제해도 `files/download-artwork`의 파일이 남는다. 레거시/비공개 다운로드 중 앱을 강제 종료하면 `.name.part`가 남는다. | 마지막 참조가 사라진 sidecar를 삭제하고, 앱 시작 시(이벤트 기반) 소유한 `.part`와 오래된 pending 행을 정리한다. 타이머를 새로 만들지 않는다. |
| MEDIUM | `app/src/main/java/com/music/bitchord/download/DownloadService.kt:116-139,142-148` | 다운로드 큐가 비었는지 확인하려고 100ms `delay` 루프를 돌고, 진행률도 250ms 고정 `delay` 루프로 갱신한다. 배터리 우선 원칙(주기 폴링·타이머 금지)에 어긋난다. | 다운로드가 없는 유휴 시간에도 worker들이 2초 동안 100ms마다 `takeNext`를 호출한다. 다운로드 중에는 `active` 수집기가 250ms마다 깨어난다. | 큐 입출을 `Channel`/`StateFlow` 신호로 바꾸고, 진행률 갱신은 상태 변화에 conflate/throttle로 반응하게 한다. 고정 주기 `delay` 루프를 없앤다. |
| MEDIUM | `app/src/main/java/com/music/bitchord/download/MediaTagger.kt:223,439-445` | FLAC만 스트리밍 태깅을 하고, M4A/WebM은 `readAll`로 파일 전체를 메모리에 올린 뒤 tagger가 다시 2~3배 복사한다. 큰 ALAC/MP4에서 OOM이 나면 태그·커버·가사가 조용히 빠진다. | 큰 M4A/ALAC를 다운로드한다. `readAll` + tagger 복사본이 heap 한계를 넘으면 `runCatching(Throwable)`이 OOM을 삼키고 파일은 태그 없이 저장된다. | MP4/WebM도 임시 파일 기반으로 스트리밍하거나, 최소한 파일 크기 상한을 두고 초과 시 태깅을 건너뛴다. 전체 파일을 `ByteArray`로 읽지 않는다. |
| LOW | `app/src/main/java/com/music/bitchord/download/OfflineHls.kt:30`, `app/src/main/java/com/music/bitchord/download/OfflineDash.kt:80` | 오프라인 패키지 디렉터리를 `id.hashCode().toUInt().toString(16)`로만 구분한다. 서로 다른 videoId의 hashCode가 충돌하면 한 곡의 다운로드가 다른 곡의 패키지를 지운다. | hashCode가 같은 두 videoId를 HLS/DASH로 받는다. 두 번째 `save`의 `deleteRecursively`가 첫 번째 패키지를 지운다. | 디렉터리 이름에 collision-resistant id(예: SHA-256 앞부분)를 쓰거나, 기존 디렉터리가 다른 id의 것인지 기록으로 확인한다. |

---

## HIGH 상세

### H1. 같은 이름의 다른 곡이 기존 다운로드 파일을 덮어쓴다

- 근거: `DownloadStore.fileNameFor`는 `Artist - Title.ext`만 만든다
  (`app/src/main/java/com/music/bitchord/download/DownloadStore.kt:87-96`).
  `begin`은 같은 이름의 `target`과 `.part`를 그대로 잡고
  (`:266-304`), `commit`은 `part.renameTo(target)`로 끝낸다(`:231-243`).
  `Downloads`는 `alreadyThere`가 이 곡의 검증된 기록이 아니면 그냥 진행한다
  (`app/src/main/java/com/music/bitchord/download/Downloads.kt:1897-1916`).
- 왜 위험한가: MediaStore 경로는 중복 이름을 `(1)`로 바꾸지만, 비공개
  (`exportDownloads=false`)와 API 28 이하 레거시 경로는 같은 최종 경로로
  rename한다. 기존 곡의 기록은 그 경로를 계속 가리키므로 **다른 곡의 오디오와
  커버가 기존 곡 이름으로 재생**된다.
- 재현: A와 B가 같은 `artist`/`title`이고 videoId만 다르게 만든다. A를 받고
  B를 받는다. `exportDownloads=false`에서 A의 파일이 B의 바이트로 바뀌는지
  확인한다. `Downloads.saved[A]`는 같은 경로다.
- 수정 여부: 미수정(문서만).
- 확인 방법:
  `grep -n "fun fileNameFor\|renameTo(target)\|val alreadyThere" app/src/main/java/com/music/bitchord/download/DownloadStore.kt app/src/main/java/com/music/bitchord/download/Downloads.kt`
  로 이름 충돌 분기가 없음을 확인한다. 순수 로직 테스트를 추가한다면
  "같은 이름, 다른 videoId"에서 `begin` 이름이 달라져야 한다는 테스트가 필요하다.

### H2. HLS/DASH 재다운로드가 기존 패키지를 먼저 지운다

- 근거: `OfflineHls.save`는 시작하자마자 `root.deleteRecursively()`를 호출하고
  (`app/src/main/java/com/music/bitchord/download/OfflineHls.kt:31`), 실패
  catch에서도 다시 지운다(`:76`). `OfflineDash`도 같다
  (`app/src/main/java/com/music/bitchord/download/OfflineDash.kt:81,114`).
  `Downloads.transfer`는 이 경로를 `DownloadStore.Pending` 없이 직접 호출한다
  (`app/src/main/java/com/music/bitchord/download/Downloads.kt:1918-1959`).
- 왜 위험한가: 같은 id의 재다운로드/고음질 교체는 같은 `<hash>` 디렉터리를
  쓰므로, 새 패키지가 완성되기 전에 기존 패키지가 사라진다. `HqLibraryUpgrade`의
  문서는 "이전 파일은 새 파일이 commit된 뒤에만 지운다"고 보장하지만
  (`app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt:56-58`),
  HLS/DASH에서는 지켜지지 않는다.
- 재현: HLS/DASH 다운로드를 만든 뒤 다시 받는다. 첫 세그먼트 요청 전후로
  네트워크를 끊는다. 기존 `offline-hls/<hash>/playlist.m3u8`이 없고 기록은
  그 URI를 가리키는 상태가 된다.
- 수정 여부: 미수정(문서만).
- 확인 방법:
  `grep -n "deleteRecursively" app/src/main/java/com/music/bitchord/download/OfflineHls.kt app/src/main/java/com/music/bitchord/download/OfflineDash.kt`
  로 시작 시 삭제를 확인한다. 테스트는 임시 디렉터리에 기존 패키지를 두고
  `save` 실패를 주입해 기존 `playlist.m3u8`이 남는지 검사하면 된다.

### H3. MV 소리 보정이 같은 MV 스트림을 다시 받고 경고를 지운다

- 근거: `redownloadSuspected`는 suspect를 `typeUnknown=true`로 큐에 넣는다
  (`app/src/main/java/com/music/bitchord/download/Downloads.kt:920-938`).
  `prepare`는 `songVersion()`이 null이면 `?: versionSong`으로 같은 id를 쓴다
  (`:1715-1727`). `remember`는 `soundSourceVideoId in ids`이면 suspect를
  지운다(`:1343-1399`).
- 왜 위험한가: 원곡 버전이 없는 파일은 보정이 "성공"한 것처럼 보이지만 같은
  MV 소리를 다시 저장한다. 새 기록은 `soundSourceVideoId == 자기 id`가 되어
  `DownloadedMvPolicy.isRecordedSongVersion`이 true가 되고, 이후 스캔은 그
  파일을 원곡으로 믿는다. 레거시 기록에서는 suspect가 다시 잡혀 같은
  재다운로드가 반복될 수 있다.
- 재현: MV 소리로 판정된 파일 중 `YtMusicRepository.songVersion()`이 null인
  곡을 준비한다. Downloads 배너를 누르거나 `MvSoundRedownloadWorker`를
  실행한다. 배너가 사라지고 파일 길이/소리는 그대로인지 확인한다.
- 수정 여부: 미수정(문서만).
- 확인 방법:
  `grep -n "songs.forEach { enqueue\|?: versionSong\|soundSourceVideoId in ids\|clearMvSoundSuspects(previousSuspects" app/src/main/java/com/music/bitchord/download/Downloads.kt`
  로 세 지점을 확인한다. 테스트는 `DownloadedMvPolicy`에 "같은 id로 끝난
  보정은 suspect를 지우지 않는다"는 순수 판정을 추가해 검증할 수 있다.

### H4. 실패한 고음질 교체와 MV 보정이 이벤트마다 무한 재시도된다

- 근거: `HqLibraryUpgrade.settle`은 실패해도 target을 plan에서 제거하고
  "다음에 다시 시도"만 로그한다(`app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt:502-535`).
  `buildPlan`은 `HqVerificationStore.entries`와 현재 파일 등급만 보고 같은
  target을 다시 만든다(`:553-605`). `scheduleIfEnabled`는 앱 시작과 재생에서
  호출된다(`:133-140`). `DownloadHealth`는 `wrongRecording`이면 저장된 체크
  시그니처를 보지 않고 매 재생마다 `requestUpgrade`를 호출한다
  (`app/src/main/java/com/music/bitchord/download/DownloadHealth.kt:81-97,150-160`).
  MV 쪽도 `scanForMvSoundSuspects`가 suspect를 지우지 못하면
  `MvSoundRedownload.scheduleIfEnabled`를 다시 호출하고
  (`app/src/main/java/com/music/bitchord/download/Downloads.kt:869`), worker는
  실패해도 `Result.success()`로 끝나 다음 이벤트에 또 걸린다
  (`app/src/main/java/com/music/bitchord/download/MvSoundRedownload.kt:31-46,60-66`).
- 왜 위험한가: 소스가 사라졌거나 항상 낮은 등급을 주는 결정적 실패에서
  앱 시작/재생마다 같은 네트워크 검색·검증·다운로드가 반복된다. 배터리와
  데이터를 계속 쓰고, 사용자는 진행률이 계속 다시 도는 것을 본다. MV 보정은
  H3와 겹치면 같은 MV 스트림을 무한히 다시 받을 수도 있다.
- 재현: 검증된 고음질 쌍을 만든 뒤 그 소스를 비활성화하거나 낮은 등급만
  응답하게 한다. 실패 후 앱을 재시작하거나 곡을 재생할 때마다 같은 target이
  다시 큐에 들어가는지 로그(`hq-library: ... did not land`)로 확인한다.
  MV는 네트워크를 끊고 배너/worker를 실행한 뒤 앱을 재시작해 본다.
- 수정 여부: 미수정(문서만).
- 확인 방법:
  `grep -n "if (!success)\|enqueue(context, ExistingWorkPolicy.APPEND_OR_REPLACE)\|if (Downloads.isWrongRecording\|scheduleIfEnabled" app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt app/src/main/java/com/music/bitchord/download/DownloadHealth.kt app/src/main/java/com/music/bitchord/download/MvSoundRedownload.kt`
  로 재시도 상한/쿨다운이 없음을 확인한다. 순수 정책 테스트로
  "실패 후 cooldown 동안 target을 다시 만들지 않는다"를 추가한다.

---

## MEDIUM/LOW 메모

- M1(삭제 alias)은 `getDownloadedSongs`가 이미 같은 파일의 모든 id를
  `forget`하는 규칙(`Downloads.kt:1477-1481`)을 `delete`에도 적용하면 된다.
- M2(길이 추정)는 기존 감사 `docs/audit/audit-song.md`의 5번과 같은 지점이다.
  그때 제안한 "null이면 건너뛴다"가 아직 반영되지 않았다.
- M3(HQ pass의 무관한 다운로드)은 `settle(success=true)`에서
  `Downloads.hqApplied`를 확인하는 것만으로도 재발을 막을 수 있다.
- M4(저장공간)는 `download-artwork`가 `filesDir`에 있어 시스템 캐시 정리도
  닿지 않는다. 삭제 경로와 시작 시 정리가 필요하다.
- M5(배터리)는 `DownloadService`의 100ms/250ms `delay` 루프다. 큐를
  `Channel`로 바꾸면 `IDLE_GRACE_MS` 동안의 폴링도 없앨 수 있다.
- M6(전체 파일 메모리)은 FLAC에는 이미 스트리밍 경로가 있다. M4A/WebM도
  같은 원칙으로 옮기는 것이 맞다.
- L1(hash 충돌)은 드물지만, 지워지는 대상이 다른 곡의 오프라인 패키지라
  결과가 크다. 디렉터리 이름을 해시 전체/긴 해시로 바꾸는 편이 안전하다.

---

## 검증

- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` — 통과.
- `./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.net.DownloadedMvPolicyTest' --tests 'com.music.bitchord.data.sources.HqLibraryPolicyTest' --tests 'com.music.bitchord.data.sources.HqLibraryPolicyBoundaryTest' --tests 'com.music.bitchord.data.sources.DownloadHealthPolicyTest' --console=plain -q` — 통과.
- 코드 변경이 없으므로 위 테스트는 회귀 확인용이고, HIGH 항목을 고쳤다는
  증거가 아니다. 각 HIGH의 정적 확인 명령은 해당 절에 적었다.
