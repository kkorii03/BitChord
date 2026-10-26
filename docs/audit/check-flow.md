# 재생 흐름 상호작용 감사 (check-flow)

- 범위: `NextTrackPreparation`, `CrossfadeController`/`AutomixWorkPolicy`, 노래 버전 교체, `LyricsTiming.recheckDownloaded`, `HqLibraryUpgrade`, `setWakeMode`, 잘린 스트림 재시도.
- 방법: `git diff 0a5a1d1f..HEAD`에서 관련 파일을 좁힌 뒤 실제 코드만 읽었다. 코드는 수정하지 않았고 이 문서만 추가했다.
- 표시: `추측`은 코드에서 확인한 사실이 아니라 기기 동작 추정이다.

---

## 1. [HIGH] 자동 믹스가 준비한 대기 플레이어가 다음 행의 버전 교체를 따라가지 못한다

- 근거: `app/src/main/java/com/music/bitchord/playback/NextTrackPreparation.kt:350-361`(다음 행을 `replaceMediaItem`), `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt:1481-1513`(arm 시 큐 전체 복사), `CrossfadeController.kt:1842-1847`(handoff에서 추가된 항목만 복사), `CrossfadeController.kt:704-715`(현재 항목 전환만 bail). `SongVersionController.kt:158-167`도 같은 다음 행을 교체한다.
- 증상: arm/fade 중 다음 행의 videoId가 바뀌면 active 플레이어의 큐만 바뀌고 standby의 복사본은 옛 버전 그대로다. handoff 뒤 standby가 세션이 되므로 새 버전 교체는 버려지고 옛 녹음이 재생된다.
- 고칠 방법: 다음 행이 바뀌면 `CrossfadeController`에 알려 standby 항목을 다시 복사/교체하거나 전환을 bail하게 한다.

## 2. [HIGH] 번들이 실행 중일 때 같은 행의 queueKey 변화를 무시해 다음 곡 준비가 끝난 채 재시작되지 않는다

- 근거: `app/src/main/java/com/music/bitchord/playback/NextTrackPreparation.kt:100-107`(같은 rowId이고 job이 active면 `shouldCancel`보다 먼저 return), `:134`(queueKey = rowId|videoId), `:147-154`(stillCurrent가 옛 videoId와 다르면 종료), `:137-145`(attempted는 rowId만 키).
- 증상: 준비 중에 `SongVersionController` 등이 다음 행의 버전을 바꾸면 번들은 조용히 끝나고, timeline 이벤트의 재스케줄도 active job 때문에 return한다. 새 녹음에 대한 가사/HQ/믹스 준비가 다시 시작되지 않는다.
- 고칠 방법: 같은 행이라도 `queueKey`가 다르면 먼저 취소·재시작하고, 새 녹음 기준으로 attempted 상태를 다시 잡는다.

## 3. [HIGH] 행 단위로 저장한 가사가 버전 교체 뒤 새 videoId에 그대로 캐시된다

- 근거: `app/src/main/java/com/music/bitchord/playback/NextTrackPreparation.kt:70-80`(`attempted`/`lyricsByRow` 키가 rowId), `:168-170`(행 키로 옛 가사 조회), `:196`(step을 시도 전에 기록), `:206-208`(행 키로 저장), `:256-266`(현재 `song.videoId`로 `NextPrepCache`에 저장), `app/src/main/java/com/music/bitchord/ui/LyricsController.kt:257-267`(그 캐시를 즉시 표시).
- 증상: 다음 행의 녹음이 바뀐 뒤 새 번들이 LYRICS를 attempted로 건너뛰면 옛 녹음의 가사가 새 videoId의 `NextPrepCache`에 들어가 화면에 잘못 표시된다.
- 고칠 방법: `attempted`와 `lyricsByRow`를 `queueKey`(rowId|videoId)로 키잉하거나, 녹음이 바뀔 때 둘 다 지운다.

## 4. [HIGH] 다음 곡 버전 교체가 다운로드 파일의 출처·길이 안전 규칙을 우회한다

- 근거: `app/src/main/java/com/music/bitchord/playback/NextTrackPreparation.kt:350-351`(`resolved.toMediaItem()` 기본값 `useDownloadedFile=true`), `app/src/main/java/com/music/bitchord/playback/PlayerConnection.kt:688,717-719`(저장된 파일 URI를 자동으로 붙임), `app/src/main/java/com/music/bitchord/playback/SongVersionController.kt:290-312`(`downloadedFileUse`/`streamLongDownloadedSong` 검사), `:314-315`(`alreadyAudible`이면 그 검사를 건너뜀).
- 증상: 다음 행이 영상에서 카탈로그 곡으로 바뀔 때 그 곡 id에 저장된 파일이 영상 사운드이거나 legacy 길이 의심 파일이어도 그대로 큐에 실린다. 크로스페이드로 시작하면 `alreadyAudible=true`라 보정 경로도 타지 않아 잘못된 녹음이 재생된다.
- 고칠 방법: `resolveVersion`에서도 `SongVersionPolicy.downloadedFileUse`/`mvSoundDeltaMs`를 적용해 `STREAM_SONG`이면 `useDownloadedFile=false`로 만든다.

## 5. [HIGH] 스트리밍 중인 곡을 다운로드 파일 존재만으로 로컬 재생으로 분류해 wake mode를 낮춘다

- 근거: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:4006-4013`(`isLocalPlayback`이면 `WAKE_MODE_LOCAL`), `:7206-7229`(마지막에 `Downloads.verifiedSavedUri(mediaId) != null`이면 로컬), `app/src/main/java/com/music/bitchord/playback/SongVersionController.kt:562-575`(영상 사운드 파일을 스트리밍할 때 mediaId를 `Downloads.songIdForFile`로 바꾸고 `localUri`를 null로 둠), `app/src/main/java/com/music/bitchord/download/Downloads.kt:561-577`(그 id는 같은 파일로 기록된 id).
- 증상: 실제 URI는 https 스트림인데 같은 id의 저장 파일이 남아 있어 `WAKE_MODE_LOCAL`이 된다. 화면이 꺼지면 네트워크 wake lock이 없어 버퍼가 마르고 재생이 끊길 수 있다. 소스 표시도 "로컬 저장소"로 잘못 나온다.
- 고칠 방법: 항목의 실제 URI(`localConfiguration`/`requestMetadata`)를 먼저 보고, 저장 기록은 그 URI가 기록된 파일과 같을 때만 로컬로 판정한다.

## 6. [MEDIUM] 저장된 검증 결과가 사라진 HQ 대상에서 패스가 영원히 멈춘다

- 근거: `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt:211-226`(`verified == null`이면 activeVideoId를 설정하기 전에 `settle` 호출), `:296-305`(`activeVideoId != videoId`이면 settle이 아무것도 하지 않고 return).
- 증상: plan의 첫 대상이 이 상태이면 `settle`이 대상을 제거하지 못하고 activeVideoId도 null이라, 다음 실행에서도 같은 대상을 다시 골라 같은 로그만 남긴다. HQ 교체 패스 전체가 진행되지 않는다.
- 고칠 방법: 검증 결과가 없을 때는 active 가드 없이 plan에서 해당 대상만 제거하는 경로를 따로 둔다.

## 7. [MEDIUM] 같은 id의 일반 다운로드가 끝나도 HQ 교체 성공으로 정산한다

- 근거: `app/src/main/java/com/music/bitchord/download/Downloads.kt:311-314`(pending/running이면 `hqOverrides`를 저장하기 전에 return), `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt:228-258`(active만 세우고 enqueue한 뒤 Queued/Running이면 대기), `Downloads.kt:1380-1401`(모든 다운로드의 finally가 `onDownloadSettled(id, completed)` 호출), `HqLibraryUpgrade.kt:175-178`(id만 일치하면 settle).
- 증상: HQ 요청이 중복으로 거절되어 `hq` 메타데이터 없이 일반 다운로드가 진행되고, 그것이 끝나면 HQ 대상이 성공으로 제거된다. 실제 파일은 검증된 고음질이 아닌데 진행률은 완료로 보인다.
- 고칠 방법: 성공 정산 전에 `Downloads.hqApplied(id, 대상 source/track/tier)`를 확인하고, enqueue가 중복으로 거절되면 대상을 성공 처리하지 않는다.

## 8. [MEDIUM] 파일 교체 뒤 늦게 끝난 recheckDownloaded가 옛 파일 기준 sync를 다시 쓴다

- 근거: `app/src/main/java/com/music/bitchord/download/Downloads.kt:1090-1107`(파일 교체 후 `LyricsTiming.resetSongCheck` 호출), `app/src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt:1501-1519`(`resetSongCheck`는 `songSyncGate`를 잡지 않음), `:1607-1722`(`recheckDownloaded`는 gate 안에서 옛 파일을 읽고 store에 기록).
- 증상: HQ 교체가 reset을 수행하는 순간 이미 진행 중이던 recheck가 있으면, recheck가 나중에 옛 파일 기준 sync/override를 store에 다시 써서 새 파일이 옛 타이밍을 갖게 된다.
- 고칠 방법: `resetSongCheck`도 `songSyncGate`를 잡게 하거나, 파일 세대 토큰을 두고 recheck가 기록 직전에 같은 세대인지 확인한다.

## 9. [MEDIUM] HQ 교체가 큐에 이미 들어간 MediaItem의 옛 파일 URI를 지울 수 있다

- 근거: `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt:48-51`(이전 파일은 교체 후 제거), `app/src/main/java/com/music/bitchord/download/Downloads.kt:1093-1139`(새 URI가 다르면 이전 URI 파일 삭제), `app/src/main/java/com/music/bitchord/playback/PlayerConnection.kt:717-719`(MediaItem을 만들 때 저장 파일 URI를 붙임). 다운로드 교체 시 플레이어 큐 항목을 다시 만드는 경로는 없다.
- 증상: 확장자가 바뀌거나 MediaStore가 새 행을 만드는 교체에서, 이미 큐에 있던 다음/현재 곡 항목이 삭제된 URI를 들고 있게 된다. 재생 시작·seek 시 파일 없음 오류가 나고 스트림으로 되돌아가거나 전환이 실패할 수 있다.
- 고칠 방법: 이전 파일을 지우기 전에 그 URI를 쓰는 플레이어 항목을 새 URI로 갱신하거나, 큐에서 사라질 때까지 이전 파일 삭제를 미룬다.

---

## 요약
- HIGH 5개
- 1) 자동 믹스 standby가 다음 행 버전 교체를 따라가지 못함
- 2) 실행 중 같은 행의 queueKey 변화를 무시해 다음 곡 준비가 재시작되지 않음
- 3) 행 단위 가사 캐시가 버전 교체 뒤 새 videoId에 그대로 적용됨
- 4) 다음 곡 버전 교체가 다운로드 파일 출처·길이 안전 규칙을 우회함
- 5) 스트리밍 곡을 로컬로 오판해 wake mode를 낮춤
