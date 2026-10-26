# check-lyrics — 가사·싱크 합류 점검

범위: `git diff 0a5a1d1..HEAD`, 가사·싱크 흐름(NextPrepCache, 다운로드 재검증, LyricsOverrideStore, CoverTitles, AI shift 저장). 코드는 수정하지 않았다.

## 1. HIGH — 교체된 다운로드 파일이 재검증되지 않고 옛 override가 남는다

- 근거: `app/src/main/java/com/music/bitchord/download/Downloads.kt:1093-1106`(파일 교체 시 `resetSongCheck`), `app/src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt:1501-1519`(`syncCheckVersion` 미초기화), `LyricsTiming.kt:1600-1605`(현재 version이면 `NONE`), `LyricsTiming.kt:1666-1673`(RECHECK override 저장), `app/src/main/java/com/music/bitchord/ui/LyricsController.kt:274`, `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:9426`(override 우선).
- 증상: 자동 HQ 교체/재다운로드로 같은 videoId의 파일 바이트가 바뀌어도 `syncCheckVersion`이 1로 남아 `recheckDownloaded`가 즉시 `NONE`을 반환한다. RECHECK override도 지워지지 않아 새 파일 대신 옛 가사가 계속 표시되고, `resetSongCheck`가 지운 AI 답 때문에 새 파일의 싱크는 사라진다.
- 고칠 방법: `resetSongCheck`에서 `syncCheckVersion = 0`으로 되돌리고 RECHECK override를 제거한다(USER override는 보존). 또는 override를 파일 URI/소스 키로 저장하고 교체 시 무효화한다.

## 2. HIGH — NextPrep이 override를 무시해 옛 가사로 SYNC하고 새 키의 AI 싱크를 덮어쓴다

- 근거: `app/src/main/java/com/music/bitchord/playback/TranslationAhead.kt:130-153`(`lyricsForPrep`은 `EmbeddedLyrics`만 읽음), `TranslationAhead.kt:184-194`(`prepare`는 override 우선), `app/src/main/java/com/music/bitchord/playback/NextTrackPreparation.kt:200-217`(LYRICS/SYNC), `LyricsTiming.kt:1182-1188`(옛 텍스트 키로 `songVerified`), `LyricsTiming.kt:1278-1293`(옛 키로 store 갱신), `NextTrackPreparation.kt:256-267`(옛 가사를 캐시).
- 증상: recheck가 새 override와 새 텍스트 키로 AI 싱크를 저장한 뒤 그 곡이 다시 '다음 곡'이 되면, NextPrep은 파일의 옛 태그 가사로 `verifySong`을 돌려 store의 `aiTextKey`를 옛 키로 덮어쓴다. 재생 시 새 override 가사에는 저장된 싱크가 없어 밀리고, `NextPrepCache`도 옛 가사를 먼저 게시한다.
- 고칠 방법: `lyricsForPrep`도 `LyricsOverrideStore.read`를 먼저 해서 override의 lines/source를 `fromFile = true`로 반환하고, override가 바뀌면 `NextPrepCache.clear()`.

## 3. HIGH — 다운로드 override의 저장된 싱크가 잠금화면·알림·차량에서 적용되지 않는다

- 근거: `PlaybackService.kt:9426-9430`(override여도 `fromFile = localUri != null`), `PlaybackService.kt:9466-9486`(`timeable=false` → `baseAllowed=false`), `LyricsController.kt:292-303`(RECHECK override면 `adjustable=true`).
- 증상: recheck가 fresh sheet에 맞춰 저장한 AI/측정 싱크는 가사 화면에는 적용되지만, 화면이 꺼졌을 때 보는 잠금화면·알림·차량 자막은 제공처의 원시 시각으로 표시된다. recheck의 목적이 사라진다.
- 고칠 방법: override에서 온 라인은 `fromFile=false`로 두거나, RECHECK override면 `baseAllowed=true`로 `LyricsTiming.timing`을 호출해 UI와 같은 키·규칙을 쓴다.

## 4. MEDIUM — override가 videoId만 키로 하여 스트림·다른 파일에 그대로 적용된다

- 근거: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsOverrideFile.kt:62-70,73-90`(videoId 키), `app/src/main/java/com/music/bitchord/data/lyrics/LyricsOverrideStore.kt:19-31`, `LyricsController.kt:274`, `PlaybackService.kt:9426`(`localUri`와 무관하게 읽음), `LyricsTiming.kt:1941-1948`(파일/스트림은 다른 소스 키).
- 증상: 다운로드 파일용 RECHECK override가 파일 삭제 후 스트림 재생이나 다른 파일 재생에도 우선 적용된다. 싱크는 파일 키로 저장되어 스트림 키에서는 적용되지 않으므로 엉뚱한 가사 + 원시 시각이 될 수 있다.
- 고칠 방법: override 키에 audioSource(file/stream 또는 URI)를 포함하고 RECHECK은 해당 파일 재생 때만 사용하며, 삭제/교체 시 제거한다.

## 5. MEDIUM — NextPrepCache가 videoId만 키로 하고 소스·종류를 무시한다

- 근거: `NextTrackPreparation.kt:134`(`queueKey = rowId|videoId`, localUri 없음), `NextTrackPreparation.kt:483-502`(`isVideo`/`fromFile` 필드는 있으나 `get(videoId)`만), `LyricsController.kt:257`, `PlaybackService.kt:9397`.
- 증상: 다음 곡 준비 후 그 행의 다운로드가 완료되거나 파일↔스트림이 바뀌어도 queueKey가 같아 취소·clear되지 않고, 시작 시 다른 소스용 가사와 `shown`을 먼저 게시한다. `isVideo`/`fromFile`을 소비처가 검사하지 않아 조회가 실패하면 잘못된 가사가 남는다.
- 고칠 방법: queueKey와 `Ready` 키에 `audioSourceKey(videoId, localUri != null)`와 `isVideo`를 포함하고 `get(videoId, audioSource, isVideo)`로 검사한다. override write 시 clear.

## 6. MEDIUM — HQ 교체 메타의 hqOffsetMs/hqRate를 가사 클럭이 읽지 않는다

- 근거: `Downloads.kt:1056-1060,1073-1077`(저장), `Downloads.kt:2035-2042`(주석: 가사 클럭에 필요), `LyricsTiming.kt:288-305`(`StreamChoice`+`HqVerificationStore`만 읽음). `hqOffsetMs`/`hqRate`를 읽는 곳은 저장소 전체에서 없다.
- 증상: 자동 HQ 교체 파일은 검증된 offset/rate가 메타에 저장되지만, 재시작·오프라인 등으로 `StreamChoice`/`HqVerificationStore`가 없으면 가사가 YouTube 클럭 그대로 표시되어 초 단위로 어긋난다.
- 고칠 방법: `hqAdjustment`에서 두 스토어가 없을 때 `Downloads` 메타의 `hqOffsetMs`/`hqRate`로 폴백한다(추측: 현재는 두 스토어가 살아있을 때만 맞음).

## 7. MEDIUM — CoverTitles.isVideo와 Song.isVideo가 달라 UI와 서비스가 다른 싱크 키를 쓴다

- 근거: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/cover/CoverTitles.kt:324-330`(커버면 `isVideo=true`), `app/src/main/java/com/music/bitchord/MainActivity.kt:1279-1295`(UI는 `query.isVideo` 전달), `LyricsController.kt:193-196,866-877`(isVideo면 audioSource null), `NextTrackPreparation.kt:425-429,447-452`, `PlaybackService.kt:9466-9484`(`song.isVideo` 기준).
- 증상: 비디오 플래그를 잃은 커버 행(주석이 말하는 Recents·저장 대기열)에서 UI는 words-only 키로 AI 답을 저장·조회하고, NextPrep/잠금화면은 file/stream 키를 써서 같은 곡의 싱크가 화면과 잠금화면에서 다르게 나온다.
- 고칠 방법: 한쪽으로 통일한다. 서비스·NextPrep도 `CoverTitles.lyricsQuery(song).isVideo`를 쓰거나, UI도 `song.isVideo`를 키에 쓴다.

## 8. LOW — AI 재요청 게이트가 audioSource 없는 키를 쓴다

- 근거: `LyricsTiming.kt:940-945,1887-1893`(`textKey(lines)`), `LyricsTiming.kt:1207-1210`(저장은 audioSource 포함), `LyricsTiming.kt:1941-1948`.
- 증상: 파일용으로 저장된 AI 답·실패를 `hasAi`/`aiAlreadyFailed`/`wantsAi`가 못 보고 같은 엔진을 다시 지불하거나 재요청을 막을 수 있다. 현재 `wantsAi` 호출부는 영상뿐이라 잠재(추측: 다운로드 곡 자동 경로로 확장하면 즉시 발생).
- 고칠 방법: 세 함수에 audioSource 인자를 추가해 저장 키와 동일하게 계산한다.

## 확인한 지점

- 수동 오프셋: `setManual`은 videoId 엔트리에 저장되고, `resetSongCheck`·recheck·`verifySong`의 `copy`는 `manualMs`를 보존한다. 오늘 추가된 경로에서 `manualMs`를 지우는 곳은 없었다. 기존 `clearAi`와 수동 `placeWithAi`는 AI 해제/요청 시 지우지만 오늘 변경은 아니다.
- `./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.lyrics.*' --tests 'com.music.bitchord.data.cover.*'` 통과(EXIT 0).
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true` 통과(EXIT 0).
- 추가 확인: `NextPrepPolicy`, `LyricsOverrideFile` 왕복, `DownloadedSyncCheck.decide`, CoverTitles 제목 형식 테스트.

## 요약

HIGH 3 — ① 교체된 다운로드 파일의 재검증 상태·override 잔존 ② NextPrep이 override를 무시해 옛 키 AI 싱크 덮어쓰기 ③ 다운로드 override 싱크가 잠금화면·알림·차량에 미적용.
MEDIUM 4 — override videoId 단일 키, NextPrepCache 소스 미구분, HQ offset/rate 미독, CoverTitles/Song isVideo 키 불일치.
LOW 1 — AI 재요청 게이트의 audioSource 키 불일치(잠재).
