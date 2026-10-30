# 비디오 노래 이어 듣기 — 채널 찾기 엄격 검토 (1031)

범위: `Song.videoRadio` 시드의 업로더 채널을 찾아 다음 영상 곡을 보충하는 경로.
기준 커밋: `867ca07` (task/fix-1030). 변경 파일은 마지막 절 참고.

## 1. 결함 표

| 심각도 | 파일:줄 | 증거 | 사용자 영향 | 얼마나 흔한지 | 수정 |
|---|---|---|---|---|---|
| HIGH | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/videoradio/VideoRadioSources.kt:64` (수정 전 `channelSongs`/`collect`) | 수정 전에는 `YouTubeArtistResolver.resolve(name)`의 검색 결과로 채널을 열었다. 이름은 정체성이 아니므로 동명 채널이 먼저 잡히면 그 채널의 영상이 큐에 들어간다. | 커버/라이브 영상의 “같은 가수 더 듣기”가 전혀 다른 사람의 채널로 이어진다. 채널 행이 0~2개로 얇아져 3곡 미만이면 일반 라디오로 대체되고, 그 경로는 커버/편곡을 걸러내므로 자동 보충이 자주 빈다. | 업로더 이름이 흔한 이름(예: “오화요”, “IU”, “Live”)이거나 동명 채널이 있는 경우 매우 흔함. | **수정.** `Song.channelId`를 추가하고 재생/시청 응답의 `videoDetails.channelId`(또는 `externalChannelId`)와 watch queue byline의 browse id를 저장한다. `collect`는 이 id를 `channelSongs(name, id)`로 넘기고, id가 있으면 이름 검색을 절대 하지 않는다. |
| HIGH | `shared/src/commonMain/kotlin/com/music/bitchord/data/model/Models.kt:45`, `app/src/main/java/com/music/bitchord/playback/PlayerConnection.kt:232,467,564,825`, `LastPlayed.kt:185,222,252`, `BoundedContextPersistence.kt:447,515`, `PartyPersonalQueueStash.kt:134,187`, `data/stats/Backup.kt:280,320` | 수정 전 `Song`에는 업로더 채널 id가 없었고, 큐가 MediaItem/저장소를 거치면 이름만 남았다. | 화면 꺼짐/백그라운드/앱 재시작 후 첫 곡은 다시 이름 검색으로 떨어져 동명 채널 위험이 재발한다. | 큐 복원·화면 회전·서비스 재생성마다 발생. | **수정.** MediaItem extras, LastPlayed, BoundedContextPersistence, PartyPersonalQueueStash, Backup에 `channelId`를 왕복 저장한다. 업그레이드 전 저장된 큐는 null이며, 이때만 플레이어 응답에서 새로 얻는다. |
| MEDIUM | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/videoradio/VideoRadioSources.kt:37,53,64,81` | 수정 전 RD 믹스가 실패/빈 응답이면 조용히 `emptyList()`였고, 채널 캐시 키가 이름이라 같은 이름의 다른 시드가 10분 동안 잘못된 채널 행을 재사용할 수 있었다. | RD 믹스 실패가 로그에 드러나지 않아 “왜 자동 재생이 안 붙는지” 추적할 수 없었고, 이름 캐시는 동명 채널 오염을 10분간 고정했다. | RD 믹스는 지역/프록시/세션에 따라 자주 비며, 이름 캐시는 같은 이름 시드가 연속될 때 발생. | **수정.** 믹스 실패와 빈 응답을 `video-radio mix failed/empty`로 남긴다. 채널 캐시 키를 `id:<UC…>`로 바꾸고, 이름 검색 폴백은 로그를 남긴다. |
| MEDIUM | `app/src/main/java/com/music/bitchord/playback/Autoplay.kt:165` | 수정 전에는 `seedSong`의 이름만 넘겼다. 소스(addon/모듈) 곡은 `youtubeSeedFor`가 다른 YouTube id로 시드하므로 그 곡의 채널 id를 그대로 쓰면 다른 업로드의 채널이 된다. | addon/모듈 곡에서 엉뚱한 채널을 열 수 있었다. | 소스 연동 곡에서 발생. | **수정.** `channelId = seedSong.channelId.takeIf { seedSong.videoId == seed }`. id가 시드와 다르면 플레이어 응답에서 새로 얻는다. |

## 2. 진입점 → 처리 → 결과 (흔한 상황)

| 상황 | 진입점 | 처리 | 결과 | 판정 |
|---|---|---|---|---|
| 커버 영상 시드 | 검색/큐 행 → `parseResponsiveListItem`/`parseWatchQueue`가 `channelId` 기록 → `Autoplay.loadAutoplayTracks` | `collect`가 id를 그대로 사용, 없으면 `Innertube.playerResponse`에서 획득. 이름 검색은 마지막 폴백만. | 올바른 업로더 채널의 영상 탭 행을 받는다. | 통과 |
| 비디오 노래(채널에서 이어짐) | `YouTubeWebParser.songOfPanel`/`lockupSong`이 `channelId` 기록 → 다음 보충 | 다음 시드도 id를 들고 있어 이름 검색 없음. | 같은 채널/믹스 세계가 유지된다. | 통과 |
| 셔플 | `QueueShuffle` → `Song.toMediaItem` | `channelId`가 extras로 왕복. | 셔플 후에도 id 유지. | 통과 |
| 앱 재시작 | `LastPlayed`/`BoundedContextPersistence`/`PartyPersonalQueueStash` | 필드가 저장·복원됨. 업그레이드 전 항목은 null. | 복원된 큐는 id 사용, 구 항목만 플레이어 응답 1회. | 통과 |
| 화면 꺼짐 | PlaybackService의 기존 큐 top-up | 주기 타이머 없음. 큐가 줄 때 한 번만 네트워크. | id 기반 채널 조회. | 통과 |
| 백그라운드 시작 | PlaybackService → `loadAutoplayTracks` | 동일 경로. 실패 시 safe가 로그 후 일반 라디오. | 새 타이머/폴링 없음. | 통과 |
| 프록시 켬 | `Innertube.playerResponse`/`browseYouTube` | 기존 proxy/gate 경로를 그대로 사용. | id 조회와 채널 페이지가 같은 규칙을 따른다. | 통과 |
| 다운로드된 곡 | 오프라인: `loadOfflineVideoRadio`; 온라인: 다운로드 시드 | 오프라인은 네트워크 없음. 온라인은 `SavedSongMetadata`에 id가 없으므로 플레이어 응답으로 1회 보충. | 오프라인은 다운로드 영상만, 온라인은 올바른 채널. | 통과(온라인 1회 조회) |
| 느린/끊긴 망 | `safe()` | 믹스와 채널이 병렬. 한쪽 실패가 다른 쪽을 막지 않고, 둘 다 실패하면 일반 라디오로 대체. | 로그: `video-radio mix failed`, `channel failed`, `channel id missing`. | 통과 |
| Android 14+ 제약 | 기존 포그라운드 서비스 | 새 alarm/timer/polling 없음. | 추가 제약 없음. | 통과 |

## 3. 실제 성적표(vc85, 362곡)와 이 영역의 인과

- **자동 재생 보충 성공 24%**: 비디오 시드는 이름 검색으로 채널을 찾았다. 동명 채널이 잡히면 채널 행이 0~2개가 되고, `MIN_VIDEO_RADIO_TRACKS = 3` 미만이면 일반 라디오로 대체된다. 일반 라디오는 `loadNormalAutoplayTracks`에서 커버/편곡을 다시 걸러내므로 비디오 시드에서는 보충이 더 자주 빈다. id 기반 채널 조회는 이 실패의 첫 단계를 없앤다.
- **사용자 조작 없는 백그라운드 시작 11회**: 백그라운드 top-up은 같은 `loadAutoplayTracks`를 탄다. 이름 검색 실패·RD 믹스 실패가 겹치면 다음 행이 없어 사용자 조작 없이 시작할 곡이 사라진다. 이제 RD 실패와 채널 실패가 각각 로그로 남고, id가 있으면 채널 다리 하나는 살아남는다.
- **다음 곡 싱크 준비 29%**: 이 영역은 다음 곡 자체를 공급하는 경로다. 보충이 비면 `NextTrackPreparation`이 준비할 다음 행도 없어진다. 채널 id 저장은 간접적으로 이 수치를 개선하지만, 싱크 준비 자체의 실패 원인은 별도 영역이다.
- **무손실 확인 후 실제 무손실 재생 4%, 다운로드·고음질 보관함 실패 140회**: 이번 영역(비디오 라디오 채널 찾기)과 직접 인과가 없다. 손대지 않았고, 별도 감사가 필요하다.

## 4. 이번에 추가한 테스트

- `shared/src/jvmTest/kotlin/com/music/bitchord/data/videoradio/VideoRadioChannelSourceTest.kt`
  - `an explicit channel id is used without searching the uploader name`
  - `the player response supplies the channel id when the song does not`
  - `the name is searched only when neither the song nor the player names a channel`
  - `a failed RD mix still returns the channel rows`
- `shared/src/jvmTest/kotlin/com/music/bitchord/data/innertube/VideoRadioChannelParseTest.kt`
  - `a watch queue row keeps the uploader channel id`
  - `the player response names the video's own channel`
  - `the player microformat is the fallback channel id`

검증:
- `./gradlew :shared:jvmTest --console=plain -q` 통과
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` 통과
- `./gradlew :app:compileProdDebugUnitTestKotlin --console=plain -q` 통과

## 5. 남긴 결함 / 후속 작업

- **LOW (남김)**: `PartySync`의 `PartyTrack`에는 `channelId`가 없다. 파티 중에는 `videoWanted = ... && !ListenTogether.state.value.inParty`로 비디오 라디오를 아예 시작하지 않으므로 이번 영역의 실제 영향은 없다. 파티에서 비디오 라디오를 허용할 때 함께 옮겨야 한다.
- **LOW (남김)**: 플레이어 응답에서 얻은 시드별 채널 id는 Song에 다시 쓰지 않고 그 top-up에만 쓴다. 같은 구 큐 행이 반복 시드되면 플레이어 응답을 다시 부를 수 있다. 주기 폴링은 아니지만, 장기적으로는 id 캐시를 두는 편이 낫다.
- **LOW (남김)**: 채널 영상 탭은 첫 페이지만 읽는다. 3곡 미만이면 다음 페이지가 아니라 일반 라디오로 대체된다. 이는 의도된 비용 절감이며, 더 많은 행이 필요하면 continuation을 붙여야 한다.

## 6. 변경 파일

- `shared/src/commonMain/kotlin/com/music/bitchord/data/model/Models.kt`
- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/InnertubeParser.kt`
- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/YouTubeWebParser.kt`
- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/videoradio/VideoRadioSources.kt`
- `app/src/main/java/com/music/bitchord/playback/Autoplay.kt`
- `app/src/main/java/com/music/bitchord/playback/PlayerConnection.kt`
- `app/src/main/java/com/music/bitchord/playback/LastPlayed.kt`
- `app/src/main/java/com/music/bitchord/playback/BoundedContextPersistence.kt`
- `app/src/main/java/com/music/bitchord/playback/PartyPersonalQueueStash.kt`
- `app/src/main/java/com/music/bitchord/MainActivity.kt`
- `app/src/main/java/com/music/bitchord/data/stats/Backup.kt`
- `shared/src/jvmTest/kotlin/com/music/bitchord/data/videoradio/VideoRadioChannelSourceTest.kt`
- `shared/src/jvmTest/kotlin/com/music/bitchord/data/innertube/VideoRadioChannelParseTest.kt`
