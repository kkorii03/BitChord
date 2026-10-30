# 비디오 노래 이어 듣기 — 채널 id 경로 2차 엄격 검토 (1031-r2)

범위: `Song.videoRadio` 시드의 업로더 채널을 찾아 다음 영상 곡을 보충하는 경로.
기준: `task/fix-1030` 위의 1차 수정(`docs/review-1031/video-radio-channel.md`) 이후.
결론: 이전 지적 MEDIUM 2건, LOW 2건을 모두 코드로 확인하고 수정했다. 새로 발견한
조용한 건너뛰기 2건도 로그를 붙였다.

## 1. 이전 지적 처리

| 이전 심각도 | 위치 | 판정 | 이번 처리 |
|---|---|---|---|
| MEDIUM | `YouTubeWebParser.lockupSong`의 byline | **타당.** `lockupViewModel`은 `longBylineText`/`shortBylineText`/`ownerText`가 없고 `metadata.contentMetadataViewModel.metadataRows[].metadataParts[].text.commandRuns[]`에 링크가 있다. 기존 helper는 이 경로를 읽지 않아 항상 null이었다. | `lockupMetadataChannelId()`를 추가해 `commandRuns[].onTap...browseEndpoint.browseId` 중 `UC…`만 채널 id로 읽는다. 테스트 `a lockup row reads the uploader channel id from metadata command runs`. |
| MEDIUM | `YouTubeWebParser.playlistVideoSong` | **타당.** `playlistVideoRenderer` 행은 `shortBylineText`에 채널 링크가 있는데도 `channelId`를 만들지 않았다. | `channelId = video.bylineChannelId()`를 추가했다. 테스트 `a playlist video row keeps the uploader channel id`. |
| LOW | `VideoRadioSources.resolveChannelId` | **타당.** 시드 행에 id가 없으면 채널 캐시가 채워진 뒤에도 매 top-up마다 플레이어 응답을 다시 읽었다. | `seedVideoId -> channelId` 캐시를 추가하고, 명시 id도 캐시에 쓴다. 테스트 `the player channel lookup is cached for the seed`. |
| LOW | `SongVersionController.downloadedSongVersionFor` | **타당.** 다운로드 파일의 다른 업로드 버전 `Song`을 만들면서 `channelId`를 빠뜨려, 다운로드 시드가 다시 이름 검색/플레이어 조회로 떨어졌다. | `channelId = song.channelId`를 복사한다. |

### 새로 발견해 수정한 조용한 경로

| 심각도 | 위치 | 증거 | 처리 |
|---|---|---|---|
| LOW | `VideoRadioSources.mix` | 빈 시드는 로그 없이 `emptyList()`였다. | `video-radio mix skipped: blank seed` 로그. |
| LOW | `VideoRadioSources.channelSongs` | id도 이름도 없으면 로그 없이 `emptyList()`였다. | `video-radio channel skipped: no id and no uploader name` 로그. |

## 2. 진입점 → 처리 → 결과

| 흔한 상황 | 진입점 | 처리 | 결과 | 판정 |
|---|---|---|---|---|
| 커버 영상 시드 | 검색/큐 행 → `YouTubeWebParser`/`InnertubeParser`가 `channelId` 기록 → `Autoplay.loadAutoplayTracks` | `collect`가 저장된 id를 그대로 `channelSongs`에 넘긴다. id가 없을 때만 플레이어 응답, 그다음 이름 검색. | 같은 업로더 채널의 영상 탭 행. 이름이 같은 다른 채널을 열지 않는다. | 통과 |
| 비디오 노래(채널에서 이어짐) | 채널 페이지 `lockupViewModel`/`playlistVideoRenderer` → 다음 top-up | 이번 수정으로 두 레이아웃 모두 `channelId`를 들고 다음 시드가 된다. | 채널 id가 다음 곡에도 유지된다. | 통과 |
| 셔플 | `QueueShuffle` → `Song.toMediaItem` | `channelId`가 MediaItem extras로 왕복한다. | 셔플 후에도 id 유지. | 통과 |
| 앱 재시작 | `LastPlayed`/`BoundedContextPersistence`/`PartyPersonalQueueStash` | `uploaderChannelId`로 저장·복원한다. 업그레이드 전 행만 null. | 복원 행은 id 사용, 구 행만 플레이어 응답 1회(이번 캐시로 반복 없음). | 통과 |
| 화면 꺼짐 | PlaybackService의 기존 큐 top-up | 큐가 줄 때 한 번만 네트워크. 주기 타이머 없음. | id 기반 채널 조회. | 통과 |
| 백그라운드 시작 | PlaybackService → `loadAutoplayTracks` | 동일 경로. RD 실패와 채널 실패가 각각 로그로 남고, 한쪽이 다른 쪽을 막지 않는다. | 새 폴링/타이머 없음. | 통과 |
| 프록시 켬 | `Innertube.playerResponse`/`browseYouTube` | 기존 proxy/gate 경로를 그대로 쓴다. | id 조회와 채널 페이지가 같은 규칙을 따른다. | 통과 |
| 다운로드된 곡 | 오프라인 `loadOfflineVideoRadio`; 온라인 다운로드 시드 | 오프라인은 네트워크 없음. 온라인은 이번 수정으로 `Song.channelId`가 다운로드 버전 교체 뒤에도 남는다. | 오프라인은 다운로드 영상만, 온라인은 id 우선. 다운로드 기록 자체에 id가 없는 구 행은 플레이어 응답 1회. | 통과(구 기록 1회) |
| 느린/끊긴 망 | `safe()` + `async` | RD 믹스와 채널이 병렬. 실패는 `video-radio mix failed/empty`, `video-radio channel ...`로 남고, 둘 다 얇으면 일반 라디오로 대체. | 조용한 빈 결과 없음. | 통과 |
| Android 14+ 제약 | 기존 포그라운드 서비스 | 새 alarm/timer/polling 없음. | 추가 제약 없음. | 통과 |

## 3. 실제 성적표(vc85, 362곡)와 이 영역의 인과

- **자동 재생 보충 성공 24%**: 1차 수정 전에는 이름 검색이 채널을 정했고, 이번 2차 검토 전에는 채널 페이지의 `lockupViewModel`/`playlistVideoRenderer` 행이 id를 잃었다. 그 행이 다음 시드가 되면 다시 이름/플레이어 경로로 떨어지고, 채널 행이 3곡 미만이면 `MIN_VIDEO_RADIO_TRACKS = 3` 미만으로 일반 라디오 대체 → `loadNormalAutoplayTracks`의 커버/편곡 필터에서 빈 결과가 잦다. 두 파서를 고쳐 이 연쇄의 시작점을 없앴다.
- **다음 곡 싱크 준비 29%**: 비디오 라디오가 비면 `NextTrackPreparation`이 준비할 다음 행 자체가 없다. 이 영역은 다음 행 공급원이므로 보충 성공률과 직접 연결된다. 다만 싱크 준비의 나머지 원인은 별도 영역이다.
- **사용자 조작 없는 백그라운드 시작 11회**: 백그라운드 top-up도 같은 `loadAutoplayTracks`를 탄다. 이전에는 RD 실패가 조용했고 채널 id가 없으면 이름 검색이 다른 채널을 열었다. 이제 실패 로그와 id 우선 규칙이 있어 원인 추적이 가능하고, id 캐시로 재시작 후 반복 플레이어 호출도 줄었다.
- **무손실 확인 후 실제 무손실 재생 4%, 다운로드·고음질 보관함 실패 140회**: 이번 영역(비디오 라디오 채널 찾기)과 직접 인과가 없다. `SongVersionController`에서 채널 id 한 필드만 보존했고, 손실/다운로드 파이프라인은 건드리지 않았다. 별도 감사가 필요하다.

## 4. 이번에 추가한 테스트

- `shared/src/jvmTest/kotlin/com/music/bitchord/data/innertube/VideoRadioChannelParseTest.kt`
  - `a lockup row reads the uploader channel id from metadata command runs`
  - `a playlist video row keeps the uploader channel id`
- `shared/src/jvmTest/kotlin/com/music/bitchord/data/videoradio/VideoRadioChannelSourceTest.kt`
  - `the player channel lookup is cached for the seed`

수정 전 위 3개는 실패했고(lockup id `null`, playlist id `null`, 조회 2회), 수정 후 통과했다.
`downloadedSongVersionFor`는 Android 다운로드 저장소와 결합된 private 경로라 단위 테스트를
새로 만들지 않았고, `:app:compileProdReleaseKotlin`으로 타입/호출부를 확인했다.

검증:
- `./gradlew :shared:jvmTest --console=plain -q` 통과
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` 통과

## 5. 남긴 결함 / 후속 작업

- **LOW (남김)**: `SavedSongMetadata`에는 업로더 채널 id가 없다. 다운로드 보관함에서 직접
  재생을 시작한 구 행은 플레이어 응답을 1회 더 읽는다. 이번 수정은 큐/다운로드 버전 교체
  경로의 id 보존만 다룬다. 다운로드 메타데이터 스키마에 id를 추가하려면 `Downloads`,
  `DownloadHealth`, `HqLibraryUpgrade`, `LocalMediaRepository`를 함께 봐야 한다.
- **LOW (남김)**: `PartySync.PartyTrack`에는 `channelId`가 없다. 파티 중에는
  `videoWanted`가 `!ListenTogether.state.value.inParty`로 막혀 있어 이 영역의 실제 영향은
  없다. 파티에서 비디오 라디오를 허용할 때 함께 옮겨야 한다.
- **LOW (남김)**: 채널 영상 탭은 첫 페이지만 읽는다. 3곡 미만이면 continuation 대신 일반
  라디오로 대체한다. 의도된 비용 절감이며, 더 많은 행이 필요하면 continuation을 붙여야 한다.
- **LOW (남김)**: RD 믹스의 빈/실패 결과는 캐시하지 않는다. 다음 top-up에서 재시도하는 것이
  의도이며, 주기 폴링이 아니라 큐 이벤트에서만 발생한다.

## 6. 변경 파일

- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/YouTubeWebParser.kt`
- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/videoradio/VideoRadioSources.kt`
- `app/src/main/java/com/music/bitchord/playback/SongVersionController.kt`
- `shared/src/jvmTest/kotlin/com/music/bitchord/data/innertube/VideoRadioChannelParseTest.kt`
- `shared/src/jvmTest/kotlin/com/music/bitchord/data/videoradio/VideoRadioChannelSourceTest.kt`
- `docs/review-1031/video-radio-channel-r2.md`
