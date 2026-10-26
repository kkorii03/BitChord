# check-mv-paths — MV 소리 누수 경로 감사

- 범위: 곡이 재생 큐에 들어가 소리가 나기까지, `SongVersionPolicy`/`YtMusicRepository.songVersion`/`SongVersionController`의 “MV를 노래 버전으로 바꾸는 판단”을 거치지 않거나 건너뛰는 경로.
- 방법: `SongVersionPolicy`, `SongVersionController`, `NextTrackPreparation`, `PrefetchController`, `PlaybackService`, `PlayerConnection`, `PartySync`, `CrossfadeController`, `CastPlayback`, `WearBridge`/`Speaker`, `Downloads`, `LastPlayed`, `BoundedContextPersistence`, `LocalPlaylistStore`, `Backup`, `TrackMatcher`를 읽었다. 코드는 수정하지 않았고 이 문서만 추가했다.
- 표시: `근거`는 파일:줄, `MV가 새는 조건`은 실제로 MV 소리가 날 수 있는 조건, `고칠 방법`은 한 줄 수정 방향.

정상 판단 지점은 `YtMusicRepository.resolveQueueAhead`(큐 빌드) → `PlaybackService.onTrackBecameCurrent`(전이) → `SongVersionController.resolveSongVersionBeforeAudio`(첫 소리 전) → `scheduleUpcomingSongVersions`/`NextTrackPreparation`(다음 곡)이다. 아래는 이 사슬을 우회하거나, 사슬에 도달해도 판정이 실패하는 경로다.

---

## 1. 다운로드 목록/저장 파일 재생 — 행이 `video=n`으로 되살아난다

- **근거**: `app/src/main/java/com/music/bitchord/download/Downloads.kt:1172-1217` (`getDownloadedSongs`가 `Song`을 만들면서 `isVideo`/`typeUnknown`을 넘기지 않음), `Downloads.kt:483-495` (`savedSongFor`도 같음), `Downloads.kt:2002-2028` (`SavedSongMetadata`에 영상 표식 자체가 없음), `Downloads.kt:1001-1072` (영상 행과 곡 행을 같은 파일의 두 id로 기록), `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/net/SongVersionPolicy.kt:87-92` (`keepsLocalFile`), `app/src/main/java/com/music/bitchord/playback/SongVersionController.kt:314-327` (skip 이유), `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/YtMusicRepository.kt:1042-1049`.
- **MV가 새는 조건**: MV를 다운로드하면 `remember`가 `asked.videoId=MV`와 `fetched.videoId=곡`을 같은 URI로 기록한다. `getDownloadedSongs`는 URI 기준으로 한 행만 만들고 `meta.videoId`를 쓰므로, 먼저 읽힌 MV alias가 `videoId=MV`, `isVideo=false`, `localUri=파일`로 나온다. 그러면 `wantsSongVersion(false,false)=false` → `keepsLocalFile(true,false,false)=true` → `songVersion`은 즉시 null이 된다. 새 다운로드에 `soundSourceVideoId`가 남아 있으면 `SongVersionController.kt:291-312`의 local-file 블록이 `songIdForFile`로 곡을 스트리밍해 구제할 수 있지만, 예전 파일처럼 provenance가 null이거나 `songIdForFile`이 MV를 고르는 경우에는 `"not a video"`/`"local file"`로 건너뛰고 MV 파일이 그대로 난다. `id=MV`이므로 `AudioCache`/`StreamResolver`의 cache도 MV 바이트를 준다. `Downloads.suspectedSongs`(`Downloads.kt:663-680`)와 재다운로드도 같은 표식 손실을 반복한다.
- **고칠 방법**: `SavedSongMetadata`에 `isVideo`/`isVideoOrigin`/`typeUnknown`을 저장하고 `getDownloadedSongs`/`savedSongFor`/`suspectedSongs`에서 복원하며, 같은 URI의 alias 중에서는 곡 id(짧은 id)를 반환한다.
- **심각도**: HIGH

## 2. 앱 재시작 후 큐 복원 — 리스너가 붙기 전에 현재 항목이 준비된다

- **근거**: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:2155-2177` (복원 `setMediaItems` 후에야 `playbackListener` 추가), `PlaybackService.kt:7711-7714` (`restoreLastQueue`), `PlaybackService.kt:7801` (`restoreBoundedQueue`), `PlaybackService.kt:4235` (판단은 `onTrackBecameCurrent`에서만).
- **MV가 새는 조건**: 복원된 현재 행이 MV(또는 1번처럼 `video=false`로 저장된 MV)이고, 사용자가 앱을 열어 재생/복원하거나 위젯·헤드폰·`LinkRequest.Resume`(`app/src/main/java/com/music/bitchord/MainActivity.kt:2320`)이 `play()`를 호출하면, 이미 current인 항목에는 `onMediaItemTransition`이 다시 오지 않는다. 따라서 `resolveSongVersionBeforeAudio`가 한 번도 호출되지 않고 복원된 MV가 그대로 난다. `restoreLastQueue`의 KDoc도 “preparing or resolving 없이”라고 명시한다.
- **고칠 방법**: 복원 직후(리스너 추가 전) 현재 항목에 `resolveSongVersionBeforeAudio(exo, item, alreadyAudible=false)`를 적용하고 그 hold가 끝난 뒤 prepare/play 하거나, 첫 `play()`에서 “아직 소리 없음 + 영상 행”이면 같은 판단을 먼저 수행한다.
- **심각도**: HIGH

## 3. 위젯·알림·헤드폰·잠금화면 재생 — 복원된 현재 항목에는 전이가 없다

- **근거**: `app/src/main/java/com/music/bitchord/widget/MediaWidgetActions.kt:106-117` (`prepareIfIdle`/`play`만 보냄), `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:10060-10076` (`SessionPlayer.play`), `PlaybackService.kt:10060`의 play는 `onUserIntent`/party/cast 분기만 하고 노래 버전 판단을 하지 않음, 복원은 2번 경로.
- **MV가 새는 조건**: 2번처럼 복원된 현재 행이 MV일 때 위젯/알림/헤드폰/잠금화면의 재생 버튼은 `play()`만 호출한다. `next`/`previous`는 전이를 만들어 `4235`를 타지만, “재생”은 타지 않는다. 알림·헤드폰·Android Auto도 같은 `SessionPlayer`를 지나므로 같은 조건이다.
- **고칠 방법**: `SessionPlayer.play()`에서 현재 항목이 아직 audible이 아니고 `wantsSongVersion`이면, prepare 전에 공통 pre-audio 판단을 await 하게 한다(2번 수정과 같은 헬퍼).
- **심각도**: MEDIUM

## 4. Android Auto 재개/폴백 — 현재 큐와 다운로드 목록을 그대로 돌려준다

- **근거**: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:10780-10813` (`onPlaybackResumption`이 현재 player items, `Downloads.getDownloadedSongs`, 로컬 음악을 그대로 반환), `PlaybackService.kt:10879-10901` (`onSetMediaItems`/`onAddMediaItems`), `PlaybackService.kt:10903-10997` (`resolveMediaItems`는 `searchQuery`가 있을 때만 검색하고, 아니면 `resolved.add(item)`로 원본 유지), `PlaybackService.kt:10617-10632` (브라우즈 자식도 `toMediaItem()` 그대로).
- **MV가 새는 조건**: Auto가 세션을 재개하면 기존 현재 항목을 그대로 돌려주므로 2번과 같은 “전이 없는 재생”이 된다. 다운로드 폴백은 1번의 `video=n` 행을 그대로 큐에 넣는다. 브라우즈에서 새로 탭한 항목은 `setMediaItems` 전이를 거쳐 서비스가 판단하므로, 이 경로 자체는 party/restore/crossfade가 아니면 안전하다.
- **고칠 방법**: `onPlaybackResumption`에서 현재 항목(특히 다운로드 폴백)을 반환하기 전에 `songVersion`을 적용하거나, 복원 경로와 같은 pre-audio 판단을 거친 뒤 반환한다.
- **심각도**: HIGH

## 5. 함께 듣기(Party) — 서비스가 의도적으로 판단을 건너뛴다

- **근거**: `app/src/main/java/com/music/bitchord/playback/SongVersionController.kt:114-116` (`scheduleUpcomingSongVersions`가 `inParty`면 return), `SongVersionController.kt:315-317` (`skipReason = "listen together"`), `app/src/main/java/com/music/bitchord/playback/PartySync.kt:677-716` (파티 큐를 `toSong().toMediaItem()`로 만들어 `setMediaItems`/`prepare`/`play`), `PartySync.kt:972`/`998` (AHEAD 추가·교체도 원본 행).
- **MV가 새는 조건**: 파티 큐에 MV id가 들어 있으면(호스트가 MV를 재생 중이거나 파티 큐를 raw 행으로 publish) 각 기기의 `load`가 MV를 그대로 재생한다. `inParty` 가드 때문에 서비스의 pre-audio 판단과 look-ahead가 모두 꺼져 있어, 곡 버전으로 바꿀 기회가 없다. 이는 “함께 듣기에서는 각자 버전을 바꾸지 않는다”는 의도와 충돌하는 지점이다.
- **고칠 방법**: 파티 큐를 publish하기 전에 호스트가 현재+다음 행을 곡 버전으로 바꾸거나, 수신 기기가 `PartySync.load` 전에 `songVersion`을 적용하고 파티에서는 “이미 재생 중인 곡의 mid-song 교체”만 금지한다.
- **심각도**: HIGH

## 6. 크로스페이드/자동믹스 대기 플레이어 — 이미 소리가 난 뒤라 교체 금지

- **근거**: `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt:1513-1526` (다음 항목을 standby에 `setMediaItems` 후 `prepare`), `CrossfadeController.kt:1784-1815` (handoff), `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:3913-3943` (`adoptPlayer`가 `onTrackBecameCurrent(..., alreadyAudible=true)` 호출), `SongVersionController.kt:314-316`/`628-632` (already audible/첫 소리 가드로 교체 거부).
- **MV가 새는 조건**: 크로스페이드가 arm되는 시점에 다음 행이 아직 MV이면(버전 lookup이 늦거나 timeout, 로컬 영상, `video=n` 행) standby가 MV를 버퍼링하고 fade가 시작된다. handoff는 `alreadyAudible=true`로 기록하므로 서비스는 그 행을 절대 교체하지 않는다. `scheduleUpcomingSongVersions`/`NextTrackPreparation`이 나중에 active 큐를 바꾸면 크로스페이드는 playlist change로 bail하지만, 이미 fade가 시작된 뒤에는 늦다.
- **고칠 방법**: crossfade arm 전에 다음 행이 “곡 버전 확정/버전 없음 확정/로컬 곡”인지 확인하고, 미확정 영상 행이면 arm을 보류하거나 standby `prepare` 전에 버전을 적용한다.
- **심각도**: HIGH

## 7. 워치 스피커 — 폰 큐의 다음 항목을 그대로 워치에 보낸다

- **근거**: `app/src/main/java/com/music/bitchord/wear/WearBridge.kt:1049-1095` (`WatchSpeaker.load`/`tracksFrom`이 현재+`AHEAD` 행의 URI를 그대로 전송), `wearos/src/main/java/com/music/bitchord/wear/playback/Speaker.kt:145-159` (`setMediaItems`/`prepare`/`playWhenReady`), `Speaker.kt:130-139` (AHEAD 추가).
- **MV가 새는 조건**: 폰 큐의 현재 또는 `AHEAD` 행이 아직 MV이면 워치가 그 스트림을 직접 받아 재생한다. 폰 플레이어는 `start()`에서 pause/stop되어 있고, 워치가 다음 곡으로 넘어가도 그 소리는 이미 워치에서 난 뒤다. `tracksFrom`은 `isVideo`/`typeUnknown`을 보지 않는다.
- **고칠 방법**: `tracksFrom` 전에 현재+`AHEAD` 행을 `songVersion`으로 해결해 곡 id/URI만 보내고, 미확정 영상 행은 워치 큐에서 제외하거나 hold한다.
- **심각도**: HIGH

## 8. Cast receiver — 큐의 현재/다음 항목을 그대로 receiver에 로드

- **근거**: `app/src/main/java/com/music/bitchord/playback/cast/CastPlayback.kt:401-535` (`load`/`loadNow`/`buildQueueItem`/`reconcileOnce`가 `item`의 URI를 resolve), `CastPlayback.kt:496-527` (receiver AHEAD를 폰 큐의 다음 행으로 채움), `CastPlayback.kt:294-314` (`onLocalTransition`도 큐 항목을 그대로 jump/load).
- **MV가 새는 조건**: cast 시작 시 현재 행이 MV이거나, receiver가 스스로 다음 곡으로 넘어갈 때 폰 큐의 다음 행이 아직 MV이면 receiver가 MV 스트림을 재생한다. 폰의 버전 교체는 큐를 바꾸지만 receiver가 이미 그 항목을 열었거나 자체적으로 진행한 뒤면 소리는 늦다.
- **고칠 방법**: `buildQueueItem` 전에 현재+`AHEAD` 행의 곡 버전을 확정하고, 미확정 영상 행은 receiver 큐에 넣지 않는다.
- **심각도**: HIGH

## 9. 공식 재생목록 판정 탈락 — ‘Youngblood (Official Video)’가 검색 결과를 받고도 MV로 남는다

- **근거**: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/YtMusicRepository.kt:758-810` (`resolveAudioUncached`가 `bestSameArtist` → `bestOfficialAudioForVideo` → `CatalogSongMapper` 순으로 시도), `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/sources/TrackMatcher.kt:229-245` (`bestSameArtist`는 `namesTheSameArtist` 또는 `sameArtistIdentity`를 요구), `TrackMatcher.kt:565-600` (`bestOfficialAudioForVideo`는 정확한 title/version + same-artist identity 요구), `YtMusicRepository.kt:1103-1125` (후보가 null이면 `songVersionMissing`에 고정).
- **MV가 새는 조건**: 공식 재생목록(RDCLAK)의 영상 행은 `isVideo=true`로 큐에 들어오고 검색은 후보를 받지만, 후보의 표시 크레딧이 채널 alias/“- Topic”/다른 표기이거나, 제목의 version 표식이 대상과 다르면 두 matcher가 모두 null을 낸다. 그러면 `songVersionMissing`이 이 세션 동안 고정되어 큐 빌드와 서비스의 재시도 모두 “버전 없음”으로 끝나고 MV가 재생된다. 이 경로는 판단을 건너뛰는 것이 아니라 “판단이 도달했지만 탈락”하는 누수다.
- **고칠 방법**: 공식 ATV 후보에 한해 `sharesArtistChannel`/`sharesRelease`/`videoRuntimeAllows`를 우선하는 완화 게이트를 두고, 부분/모호한 답은 missing으로 확정하지 않으며, Youngblood 회귀 테스트를 추가한다.
- **심각도**: HIGH

## 10. `video=n`/`typeUnknown`으로 잘못 저장된 메타데이터 복원

- **근거**: `app/src/main/java/com/music/bitchord/data/spotify/LocalPlaylistStore.kt:286-300` (`isVideo` 키가 없으면 false), `app/src/main/java/com/music/bitchord/data/stats/Backup.kt:276-300` (`BackupSong.isVideo` 기본 false), `app/src/main/java/com/music/bitchord/playback/LastPlayed.kt:160-210` (`StoredTrack`에 `typeUnknown` 없음), `app/src/main/java/com/music/bitchord/playback/BoundedContextPersistence.kt:442-520` (`StoredSong`에 `typeUnknown` 없음), `app/src/main/java/com/music/bitchord/playback/PartyPersonalQueueStash.kt:130-170` (`typeUnknown` 없음).
- **MV가 새는 조건**: 예전 로컬 플레이리스트/백업이 영상 행을 `isVideo=false`로 저장했거나, 미리보기 `typeUnknown=true` 행이 스냅샷을 거치며 `false`가 되면, 복원된 행은 `wantsSongVersion`을 통과하지 못한다. `LastPlayed`/`BoundedContextPersistence`의 schema version은 예전 `isVideo=false` 스냅샷을 거부하지만, `typeUnknown`은 지금도 보존되지 않는다.
- **고칠 방법**: 모든 큐/라이브러리 스냅샷에 `isVideo`/`isVideoOrigin`/`typeUnknown`을 저장하고, 구버전은 마이그레이션하거나 거부하며, YouTube id + 영상 표식이 있는데 flag가 없으면 `typeUnknown=true`로 승격한다.
- **심각도**: HIGH

## 11. 자동 재생/라디오 보충 — lookahead 밖은 전이에 의존하고, 현재 곡은 유지된다

- **근거**: `app/src/main/java/com/music/bitchord/playback/Autoplay.kt:192-201` (`resolveQueueAhead(rows, 0, ahead = 4)`), `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:3635` (AutoPlay가 `fresh.map { it.toMediaItem() }`로 추가), `PlaybackService.kt:2489` (station 관련 곡 raw 추가), `app/src/main/java/com/music/bitchord/MainActivity.kt:2000`/`2012-2031` (station 현재 곡 유지/시드만 해결), `SongVersionController.kt:129-165` (다음 3개만 look-ahead).
- **MV가 새는 조건**: AutoPlay/station이 붙인 5번째 이후 행이나 현재 곡 유지 분기(`currentItem?.mediaId == song.videoId`)는 곡 버전 확정 없이 큐에 남는다. 이후 전이가 정상적으로 `4235`를 타면 서비스가 구제하지만, 5·6·7·8번 경로(party/crossfade/restore/cast/watch)나 1.5초 timeout과 겹치면 MV가 난다.
- **고칠 방법**: 추가하는 모든 행을 곡 버전으로 해결하거나, 최소한 “미확정 영상 행” 표식을 남겨 전이 시 hold하게 하고, station에서 현재 MV를 유지하는 분기는 곡 버전으로 바꾼 뒤 유지한다.
- **심각도**: MEDIUM

## 12. 다음 곡 준비(NextTrackPreparation) — 비동기 준비가 전이를 붙잡지 않는다

- **근거**: `app/src/main/java/com/music/bitchord/playback/NextTrackPreparation.kt:123-146` (`schedule`가 job을 띄우기만 함), `NextTrackPreparation.kt:159-185` (`run`), `NextTrackPreparation.kt:306-350` (`versionWanted`/`resolveVersion`), `SongVersionController.kt:129-165` (별도 look-ahead).
- **MV가 새는 조건**: next-prep의 버전 단계가 끝나기 전에 gapless advance나 crossfade가 일어나면 다음 행은 MV로 시작한다. `versionWanted`는 `localUri != null && AudioCache.isOffline()`일 때 false이고, 1번처럼 `video=false`가 된 다운로드 영상도 false다. `resolveVersion`은 다음 항목을 교체하지만 전이를 hold하지 않는다.
- **고칠 방법**: 다음 행에 “버전 미확정” 상태를 남기고 전이(특히 crossfade arm)가 그 단계를 최대 1.5초 await하게 하거나, next-prep 완료 전에는 다음 곡 첫 소리를 hold한다.
- **심각도**: MEDIUM

---

## 3. 판단을 거치는 경로(누수 아님, 조건부)

아래는 정상적으로 `resolveQueueAhead`/`resolveSongVersionBeforeAudio`/전이를 타는 경로다. 다만 위 5~8번(party/crossfade/restore/cast/watch)이나 9번(matcher 탈락)과 겹치면 같은 누수가 된다.

- 검색 결과 바로 재생: `MainActivity.kt:1900-1933` (`playRadio`가 `songVersion` 1.5초 시도), 이후 서비스 `4235`.
- 공유 링크 Track/Search·어시스턴트: `MainActivity.kt:2287`/`2306` (`playRadio` 경유). `LinkRequest.Resume`만 `2320`에서 판단 없이 `session.play()`.
- 재생목록/앨범 첫 곡: `MainActivity.kt:1724-1752` (`resolveQueueAhead(ahead=1)`), 서비스 `4235`.
- 큐에서 직접 누르기·next/previous: `PlaybackService.kt:1348-1405` 전이 → `4235`.
- 셔플: `QueueShuffle.kt:253-299`는 순서만 바꾸고, 다음 전이에서 `4235`를 탄다.
- 다운로드 준비: `Downloads.kt:1426-1440` (`downloadIsSongVersion`으로 영상 행을 먼저 곡 버전으로 해결). 단 1번의 기존 파일 재생은 이 경로를 타지 않는다.
- AutoPlay 첫 4곡: `Autoplay.kt:192-201`; 이후 행은 11번.

---

## 4. “MV인가”를 판단하는 함수 목록과 서로 다른 점

| 함수/필드 | 정의 | 차이·위험 |
|---|---|---|
| `Song.isVideo` | 파서가 정한 영상 행 표식 (`shared/src/commonMain/kotlin/com/music/bitchord/data/model/Models.kt:59-69`) | 파서마다 채우는 신호가 다르고, 다운로드/스냅샷에서 유실되면 false가 된다. |
| `Song.isVideoOrigin` | 영상에서 곡으로 바뀐 뒤에도 출처를 기억 (`Models.kt:69-74`) | AutoMix/표시용. `isVideo` 대신 쓰면 이미 곡인 행을 영상으로 오판한다. |
| `Song.typeUnknown` | 미리보기라 아직 영상 여부 미확정 (`Models.kt:59-69`) | `wantsSongVersion`은 true로 보지만 `resolveAudio`는 무시한다(아래). |
| `SongVersionPolicy.wantsSongVersion(isVideo,typeUnknown)` | `isVideo || typeUnknown` (`SongVersionPolicy.kt:71-72`) | 중앙 정책. 모든 경로가 이 함수를 써야 하는데, `Downloads`/`MediaItem.toSong`/`resolveAudio`는 다른 기준을 쓴다. |
| `SongVersionPolicy.keepsLocalFile(localFile,isVideo,typeUnknown)` | `localFile && !wantsSongVersion` (`SongVersionPolicy.kt:87-92`) | 다운로드 영상이 `video=false`로 오면 로컬 파일을 그대로 재생하게 만드는 직접 원인. |
| `SongVersionPolicy.downloadIsSongVersion` | `wantsSongVersion && !knownMissing` (`SongVersionPolicy.kt:100-105`) | 다운로드 준비에서만 사용. 기존 파일 재생 경로는 이 함수를 거치지 않는다. |
| `YtMusicRepository.resolveAudio` | `if (!song.isVideo) return song` (`YtMusicRepository.kt:728-730`) | `wantsSongVersion`보다 좁다. `typeUnknown=true` 행은 검색을 하지 않고, `resolveSongVersion`이 이를 “검색 성공/버전 없음”으로 오인한다(`YtMusicRepository.kt:1103-1125`). |
| `InnertubeParser.parseResponsiveListItem`/`parseCardShelfSong` | `musicVideoType`이 있으면 ATV 여부, 없으면 `rowType=="video"` 또는 정사각 아트 여부 (`InnertubeParser.kt:814-823`, `864-869`) | 정사각 아트 MV는 `isVideo=false`가 될 수 있다. |
| `InnertubeParser.parsePlaylistPanelRow` | `musicVideoType`이 있으면 ATV 여부, 없으면 byline의 `views`/`조회수` (`InnertubeParser.kt:1322-1343`) | 같은 업로드가 responsive row와 playlist panel row에서 다르게 분류될 수 있다(정사각 아트 vs 조회수). |
| `YouTubeWebParser.isVideoRow` | 와이드 썸네일 또는 “Music Video” 배지 (`YouTubeWebParser.kt:309-320`) | 웹 fallback에는 `musicVideoType`이 없어 정사각 MV를 곡으로 단정한다. `typeUnknown`을 만들지 않는다. |
| `MediaItem.isVideoRow` | `EXTRA_IS_VIDEO`만 봄 (`PlayerConnection.kt:607-608`) | Watch/Cast가 만드는 MediaItem은 extras가 없어 항상 false. `MediaItem.isVideoOrigin`(`602-605`)은 `EXTRA_VIDEO_ORIGIN`도 봐서 다르다. |
| `MediaItem.toSong()` | extras가 없으면 `isVideo=false`, `typeUnknown=false` (`PlayerConnection.kt:457-472`) | 세션을 한 번 거친 행이 표식을 잃는 지점. |
| `Downloads.getDownloadedSongs`/`savedSongFor`/`suspectedSongs` | `Song` 생성 시 영상/미확정 표식을 넘기지 않음 (`Downloads.kt:483-495`, `663-680`, `1204-1215`) | 다운로드 행을 항상 곡으로 취급한다. |
| `TrackMatcher.bestSameArtist` | 점수 + `namesTheSameArtist`/`sameArtistIdentity` + cover 거부 (`TrackMatcher.kt:229-245`) | 같은 곡이어도 표시 크레딧 alias만 다르면 탈락. 9번. |
| `TrackMatcher.bestOfficialAudioForVideo` | 정확한 title/version + same-artist identity, 영상 길이 드리프트 허용 (`TrackMatcher.kt:565-600`) | 영상 길이 차이는 허용하지만 크레딧/버전이 다르면 탈락. 9번. |
| `SongVersionController.streamLongDownloadedSong` | `!isVideo && !typeUnknown && localUri != null`일 때만 파일 길이로 MV 소리 의심 (`SongVersionController.kt:225-253`) | `video=false`로 잘못 저장된 MV 행에는 작동하지 않는다. |
| `CatalogSongMapper.map` | `localUri != null`이면 즉시 null (`CatalogSongMapper.kt:152-154`) | 다운로드 영상 행이 검색 실패 시 앨범 폴백을 못 쓴다(9번의 보조 원인). |

핵심 차이: `wantsSongVersion`은 `typeUnknown`을 포함하지만 `resolveAudio`는 포함하지 않고, 파서·`MediaItem`·`Downloads`는 각자 다른 신호로 `isVideo`를 만들며, `MediaItem`/`Downloads` 경로는 표식을 잃으면 “곡”으로 단정한다. 따라서 “MV인가”를 한 곳에서 결정하지 못하는 것이 이 감사의 근본 원인이다.

---

## 5. 요약

- 누수 경로: **12개** (HIGH 9, MEDIUM 3).
- HIGH: 1 다운로드 목록/저장 파일 재생, 2 앱 재시작 큐 복원, 4 Android Auto 재개/폴백, 5 함께 듣기, 6 크로스페이드 handoff, 7 워치 스피커, 8 Cast receiver, 9 공식 재생목록 matcher 탈락(Youngblood), 10 `video=n`/`typeUnknown` 메타데이터 복원.
- MEDIUM: 3 위젯·알림·헤드폰 재생, 11 자동 재생/라디오 보충, 12 NextTrackPreparation 비동기.
- 공통 수정 우선순위: (a) 다운로드/스냅샷에 `isVideo`/`isVideoOrigin`/`typeUnknown` 보존, (b) 복원·Auto 재개·watch/cast 로드 전에 공통 pre-audio 판단 적용, (c) party/crossfade에서 미확정 영상 행 hold, (d) `resolveAudio`의 `!isVideo` 조기 반환을 `wantsSongVersion`으로 교체.
