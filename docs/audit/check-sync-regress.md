# check-sync-regress — ko-101 이후 가사 싱크 퇴행 점검

범위: `git diff ko-101..HEAD` (ko-101 = 57400f7, HEAD = 6360409 tree). 가사 선택·싱크 계산·저장·적용 경로(`LyricsTiming`, `LyricsController`, `TranslationAhead`, `NextTrackPreparation`, `LyricsOverrideStore`, `CoverTitles`, `LyricsQueryVariants`, `DownloadedSyncCheck`, AI shift 저장)만 읽었다. 코드는 수정하지 않았다. 최대 12개, HIGH 먼저.

## 1. HIGH — NextPrep이 행 id로만 단계·가사를 캐시해 버전 교체 뒤 다른 녹음의 가사/싱크를 재사용한다

- 근거: `app/src/main/java/com/music/bitchord/playback/NextTrackPreparation.kt:70-80`(`attempted`/`lyricsByRow`가 rowId 키), `:137-145`(`attemptsFor(rowId)`, `steps` 고정), `:156-170`(VERSION이 `song`을 새 녹음으로 바꿔도 `steps`와 `lyricsByRow[snapshot.rowId]`는 그대로), `:182-208`(`plan`이 옛 `attempted`를 사용하고 LYRICS 결과도 rowId에 저장), `:211-218`(SYNC가 옛 `lyrics`로 실행), `:251-267`(옛 가사를 `NextPrepCache`에 저장). `shared/src/commonMain/kotlin/com/music/bitchord/playback/NextPrepPolicy.kt:18`의 순서(VERSION→LYRICS→HQ→SYNC)도 이 상태를 전제한다.
- 증상: MV 행이 VERSION 단계에서 song version으로 바뀐 뒤에도 이전 MV에서 시도된 LYRICS/SYNC가 `attempted`에 남아 새 녹음에서 건너뛰어진다. `lyricsByRow[rowId]`에 남은 MV 가사로 SYNC하거나 `NextPrepCache`에 저장하므로, 노래 버전 오디오에 MV 기준 가사/싱크가 적용된다.
- 고칠 방법: `attempted`/`lyricsByRow`를 `queueKey`(rowId+videoId)로 키잉하고, VERSION이 `song`을 바꾸면 `steps`와 `lyrics`를 새 키에서 다시 가져온다.

## 2. HIGH — NextPrep 첫 프레임/캐시가 override와 소스 키를 무시해 옛 파일 가사·옛 텍스트 키로 SYNC한다

- 근거: `app/src/main/java/com/music/bitchord/playback/TranslationAhead.kt:130-153`(`lyricsForPrep`은 override를 읽지 않고 `EmbeddedLyrics`만 읽음), `:184-194`(`prepare`는 override 우선), `app/src/main/java/com/music/bitchord/playback/NextTrackPreparation.kt:211-218`(옛 가사로 `verifySong`), `:251-267`(옛 가사를 `NextPrepCache`에 저장), `app/src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt:1178-1189`(옛 텍스트 키로 `songVerified`), `:1278-1293`(옛 키로 `aiTextKey` 덮어씀), `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:9464-9470`(`prepared.shown`을 첫 프레임에 무조건 게시), `app/src/main/java/com/music/bitchord/ui/LyricsController.kt:257-267`(`prepared.raw`를 먼저 게시), `app/src/main/java/com/music/bitchord/playback/NextTrackPreparation.kt:498-502`(`NextPrepCache`는 videoId만 검사).
- 증상: recheck가 새 override와 새 텍스트 키로 싱크를 저장해도, 그 곡이 다시 다음 곡이 되면 NextPrep이 파일의 옛 태그 가사로 AI 답을 옛 키에 덮어쓴다. 재생 시 새 override 가사에는 저장된 싱크가 없고, 첫 프레임에는 옛 가사/옛 shown이 먼저 표시된다.
- 고칠 방법: `lyricsForPrep`도 `LyricsOverrideStore.read`를 먼저 적용하고, `NextPrepCache` 키에 `audioSourceKey`/`isVideo`를 포함하며 override write 시 clear한다.

## 3. HIGH — NextPrep의 SYNC가 HQ 교체 뒤 실행되어 HQ 오디오 기준 AI 답을 저장하고, 첫 프레임에서 HQ 맵을 다시 더한다

- 근거: `shared/src/commonMain/kotlin/com/music/bitchord/playback/NextPrepPolicy.kt:18`(HQ가 SYNC보다 먼저), `app/src/main/java/com/music/bitchord/playback/NextTrackPreparation.kt:193-218`(HQ pin 뒤 SYNC 실행), `:432-452`(`timed`가 `baseAllowed = timeable`로 substitute 여부를 보지 않음), `app/src/main/java/com/music/bitchord/playback/smart/VideoAudio.kt:114-132`(songWav가 디스크의 현재 rendition을 읽음), `app/src/main/java/com/music/bitchord/playback/smart/VersionAudioAligner.kt:328-340`(`#alt` 대체본도 선택 가능), `app/src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt:181-190`(base와 hq를 함께 계산), `:264-278`(base 적용 뒤 hq를 다시 적용), `app/src/main/java/com/music/bitchord/ui/LyricsController.kt:630-637`(UI는 substitute면 base를 끔).
- 증상: HQ substitute가 pin된 뒤 SYNC가 그 오디오를 듣고 fitted shift를 `ai`로 저장한다. 이 `ai`는 이미 HQ 클럭인데 `timed`가 `base`로 적용한 뒤 같은 `hq` 맵을 한 번 더 적용해 shift가 두 번 더해진다. 이 double-shifted `shown`이 첫 프레임에 쓰인다.
- 고칠 방법: NextPrep SYNC도 substitute면 건너뛰고, `timed`의 `baseAllowed`에 `!StreamChoice.isSubstitute(videoId)`를 포함한다. 파일 자신을 기준으로 측정한 `base.byAi`에는 hq를 적용하지 않는다.

## 4. HIGH — 다운로드 HQ 파일의 저장된 offset/rate를 읽지 않고 지난 스트림의 StreamChoice에 의존한다

- 근거: `app/src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt:288-308`(`hqAdjustment`가 `StreamChoice`+`HqVerificationStore`만 읽음), `app/src/main/java/com/music/bitchord/download/Downloads.kt:2035-2041`(`hqOffsetMs`/`hqRate`가 "가사 클럭에 필요"하다고 저장됨), `:1056-1059`(저장), `:471-475`(`savedMetadataFor` 존재), `app/src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt:181-190`, `:264-278`(hq를 base 위에 적용).
- 증상: 재시작·오프라인·StreamChoice 만료 뒤에는 HQ 교체 파일의 검증된 shift가 사라져 가사가 원래 YouTube 클럭으로 표시된다. 반대로 같은 id를 최근 스트림에서 substitute로 재생해 StreamChoice가 남아 있으면, 파일 자신을 기준으로 측정한 AI 답(`base.byAi`) 위에 HQ 맵이 또 적용될 수 있다.
- 고칠 방법: 로컬 파일이면 `Downloads.savedMetadataFor(videoId)`의 `hqOffsetMs`/`hqRate`로 맵을 만들고, `localFile && base.byAi`면 hq를 null로 한다. StreamChoice는 스트림 재생에만 사용한다.

## 5. HIGH — resetSongCheck가 syncCheckVersion과 RECHECK override를 남겨 교체된 파일이 재검증되지 않는다

- 근거: `app/src/main/java/com/music/bitchord/download/Downloads.kt:1090-1107`(파일 교체 시 `resetSongCheck` 호출), `app/src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt:1501-1520`(`syncCheckVersion` 미초기화, override 미제거), `:1600-1612`(version이 1이면 `Recheck.NONE`), `:1656-1673`(RECHECK override 저장), `app/src/main/java/com/music/bitchord/ui/LyricsController.kt:274`, `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:9493`(override를 파일보다 먼저 읽음).
- 증상: HQ 교체/재다운로드로 같은 videoId의 파일 바이트가 바뀌어도 `syncCheckVersion=1`이 남아 `recheckDownloaded`가 즉시 `NONE`을 반환한다. 옛 RECHECK override는 새 파일에 계속 적용되고, `resetSongCheck`가 지운 AI 답 때문에 새 파일의 싱크는 사라진다.
- 고칠 방법: `resetSongCheck`에서 `syncCheckVersion = 0`으로 되돌리고 RECHECK override를 제거한다(USER override는 보존).

## 6. HIGH — HQ 검증의 reference가 YouTube song version이 아니라 디스크의 임의 rendition/MV/대체본일 수 있다

- 근거: `app/src/main/java/com/music/bitchord/playback/smart/HqAudioVerify.kt:451-488`(`VideoAudio.songWav`를 `Song(videoId=youtubeId, isVideo=false)`로 호출), `app/src/main/java/com/music/bitchord/playback/smart/VideoAudio.kt:114-132`(`openSongSource`가 `VersionAudioAligner.openDataSource`를 씀), `app/src/main/java/com/music/bitchord/playback/smart/VersionAudioAligner.kt:328-340`(로컬 파일 또는 `#alt` 대체본까지 선택), `app/src/main/java/com/music/bitchord/playback/smart/HqAudioVerify.kt:254-279`(그 offset/rate를 저장), `app/src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt:288-308`(그 값을 YouTube→HQ 맵으로 사용).
- 증상: MV나 대체본을 reference로 잰 offset/rate가 "YouTube song version→HQ" 맵으로 저장된다. 이 맵이 노래 버전 클럭의 가사에 적용되면 MV/대체본과 노래 버전의 차이만큼 초 단위로 어긋난다.
- 고칠 방법: HQ 검증 reference는 `AutomixAnalysisSource.opusUri(youtubeId)`처럼 YouTube 원본을 직접 열어 디코드하고, 로컬 파일/`#alt` rendition을 reference로 쓰지 않는다.

## 7. HIGH — 다운로드 override의 저장 싱크가 잠금화면·알림·차량 자막에는 적용되지 않는다

- 근거: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:9493-9497`(override여도 `fromFile = localUri != null`), `:9533-9544`(`timeable = timed && (!fromFile || coverQuery.isVideo)` → 다운로드 곡은 `baseAllowed=false`), `app/src/main/java/com/music/bitchord/ui/LyricsController.kt:289-303`(RECHECK override면 `adjustable=true`로 화면에는 적용).
- 증상: recheck가 fresh sheet에 맞춰 저장한 AI/측정 싱크는 가사 화면에는 적용되지만, 화면이 꺼졌을 때 읽는 잠금화면·알림·차량 자막은 제공처의 원시 시각으로 표시된다. 같은 파일의 싱크가 화면과 다른 표면에서 달라진다.
- 고칠 방법: override에서 온 라인은 `fromFile=false`로 두거나, RECHECK override면 `baseAllowed=true`로 UI와 같은 키·규칙을 쓴다.

## 8. MEDIUM — DownloadedSyncCheck가 답 없는 검사를 version=1로 기록해 재시도와 새 결과를 막는다

- 근거: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/DownloadedSyncCheck.kt:105-123`(`decide`가 교체가 없으면 항상 `recordVersion=true`; `ownNoAnswer` 개념 없음), `app/src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt:1618-1655`(own match null + fresh null이면 decide 호출), `:1679-1705`(version 기록), `:1600-1605`(다음 재생은 `NONE`).
- 증상: Deepgram HTTP 실패/`no-answer`처럼 실제 답이 없는 경우에도 "검사 완료"로 저장되어 다음 재생에서 다시 검사하지 않는다. 옛 잘못된 싱크가 그대로 남는다.
- 고칠 방법: `decide`에 `ownNoAnswer`를 넘겨 답이 없을 때는 `recordVersion=false`로 하고, 다음 재생에서 재시도한다.

## 9. MEDIUM — HQ 검증이 부분 디코드 profile도 받아 offset/rate를 저장한다

- 근거: `app/src/main/java/com/music/bitchord/playback/smart/HqAudioVerify.kt:235-279`(candidate completeness 검사 없이 align·저장), `:526-560`(`featuresOf`가 `decoded != null`이면 partial profile 반환), `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/sources/HqReferenceCache.kt:50-54`(`completeEnough`는 reference에만 사용), `app/src/main/java/com/music/bitchord/playback/smart/HqAudioVerify.kt:480-486`(partial reference는 비교에는 쓰임).
- 증상: 느린/끊긴 후보 스트림의 짧은 profile로 match≥75가 나오면 잘못된 offset/rate가 저장된다. 그 값이 hq 맵으로 가사에 적용되어 후반부로 갈수록 오차가 커진다.
- 고칠 방법: reference와 candidate 모두 `COMPARE_SECONDS`의 90% 이상을 덮을 때만 verdict를 저장하고, partial이면 `Unavailable`/재시도로 둔다.

## 10. MEDIUM — LyricsOverrideFile이 videoId만 키로 하여 파일용 override가 스트림/다른 파일에 적용된다

- 근거: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsOverrideFile.kt:62-90`(read/write 모두 videoId 키), `app/src/main/java/com/music/bitchord/ui/LyricsController.kt:274`, `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:9493`(localUri와 무관하게 읽음), `app/src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt:1947`(파일/스트림은 다른 audioSource 키).
- 증상: 다운로드 파일용 RECHECK override가 파일 삭제 후 스트림 재생이나 다른 파일 재생에도 우선 적용된다. 싱크는 파일 키로 저장되어 스트림 키에서는 적용되지 않으므로 엉뚱한 가사 + 원시 시각이 될 수 있다.
- 고칠 방법: override 키에 audioSource(file/stream 또는 URI)를 포함하고 RECHECK은 해당 파일 재생 때만 사용하며 삭제/교체 시 제거한다.

## 11. MEDIUM — CoverTitles/Song의 isVideo 불일치로 UI와 NextPrep/서비스가 다른 싱크 키를 쓴다

- 근거: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/cover/CoverTitles.kt:324-330`(커버면 `isVideo=true`), `app/src/main/java/com/music/bitchord/MainActivity.kt:1292`(UI는 `query.isVideo` 전달), `app/src/main/java/com/music/bitchord/playback/NextTrackPreparation.kt:264`, `:447`(NextPrep은 `song.isVideo`), `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:9515`, `:9545`(조회는 `coverQuery.isVideo`, 타이밍 키는 `song.isVideo`), `app/src/main/java/com/music/bitchord/ui/LyricsController.kt:193-196`, `:866-877`(`isVideo`면 `audioSource=null`).
- 증상: 비디오 플래그를 잃은 커버 행(Recents·저장 대기열)에서 UI는 words-only 키로 AI 답을 저장·조회하고, NextPrep/잠금화면은 file/stream 키를 써서 같은 곡의 싱크가 화면과 첫 프레임/잠금화면에서 다르게 나온다.
- 고칠 방법: 한쪽으로 통일한다. 서비스·NextPrep도 `CoverTitles.lyricsQuery(song).isVideo`를 키에 쓰거나 UI도 `song.isVideo`를 쓴다.

## 12. LOW — LyricsQueryVariants가 artist 없는 변형을 대안보다 먼저 시도해 다른 가수/녹음의 timed 가사를 채택할 수 있다

- 근거: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsQueryVariants.kt:48-53`(`primary to ""`, `altTitles to ""`를 alternatives보다 먼저 추가), `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsRepository.kt:599-615`(첫 timed 변형을 alternatives/원어명보다 먼저 반환).
- 증상: 같은 제목의 다른 가수·라이브·커버 timed sheet가 먼저 반환될 수 있다. 이후 measurement/song check가 없거나 실패하면 그 시각이 현재 오디오에 그대로 적용되어 다른 녹음 기준 싱크가 된다.
- 고칠 방법: artist 없는 변형은 alternatives/원어명 뒤로 미루고, 채택한 variant가 원래 artist와 다르면 measurement/song check를 필수로 한다.

## 확인한 지점

- `SongCheckWindow.kt`는 `git diff ko-101..HEAD`에서 변경이 없다(창 계산 자체는 퇴행 원인이 아님). `CoverTitles`의 제목 정규화 변경은 직접적인 시간 계산이 아니라 11번의 `isVideo` 키 불일치와 12번의 query 선택을 통해 간접적으로만 영향을 준다.
- 수동 보정(`manualMs`)은 `resetSongCheck`·recheck·`verifySong`의 `copy`에서 보존된다. 새 경로가 `manualMs`를 지우는 곳은 없었다.

## 요약

1. HIGH 7 — ① NextPrep rowId 캐시로 버전 교체 뒤 다른 녹음 가사/싱크 재사용 ② NextPrep override 무시로 옛 키 AI 싱크 덮어쓰기·첫 프레임 옛 가사.
2. HIGH ③ HQ pin 뒤 SYNC + hq 재적용으로 shift 두 번 ④ 다운로드 HQ 메타 offset/rate 미독 및 StreamChoice 오염 ⑤ resetSongCheck가 version/override를 남겨 교체 파일 미재검증.
3. HIGH ⑥ HQ reference가 YouTube 원본이 아닐 수 있음 ⑦ 다운로드 override 싱크가 잠금화면·알림·차량에 미적용.
4. MEDIUM 4 — ⑧ 답 없는 재검사를 version=1로 기록 ⑨ partial profile로 offset/rate 저장 ⑩ override videoId 단일 키 ⑪ CoverTitles/Song isVideo 키 불일치.
5. LOW 1 — ⑫ LyricsQueryVariants의 artist 없는 변형이 다른 녹음 timed 가사를 먼저 채택할 수 있음.
6. 우선 수정 순서는 ②→③→①→⑤→④이며, 모두 ko-101 이후 새로 들어온 NextPrep/HQ/override/recheck 경로에서 발생한다.
