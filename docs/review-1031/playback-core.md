# 재생 핵심 엄격 검토 (1031)

- 기준: 현재 워크스페이스 HEAD (`dsq/deep-playback-core-1031`)
- 범위: 곡 해석(`StreamResolver`·`DirectClientResolver`·`InnerTubeXResolver`·NewPipe·프록시 경주), 재시도·실패 처리(`PlaybackService` 오류 경로·`UnplayableReason`·다음 곡 넘김), 큐 전환·건너뛰기, 서비스 생명주기(포그라운드·알림·오디오 포커스·이어폰·전화).
- 방법: 진입점에서 결과까지 코드를 따라갔고, 아래 표는 실제 분기와 로그/상태 전이를 기준으로 했다. 수정한 것은 HIGH/MEDIUM 중 단위 테스트로 고정할 수 있는 것부터다.
- 배터리 원칙: 이번 변경은 폴링·타이머·`delay` 루프를 새로 만들지 않았다. 모두 이벤트(첫 소리, 큐 변경, 재생 오류, 배치 도착)에서 한 번만 동작한다.

## 요약

- 수정 완료: HIGH 1, HIGH/MEDIUM 1, MEDIUM 2.
- 남긴 결함: MEDIUM 4 (이유와 필요한 작업을 아래에 적었다).
- 새 테스트: `SkipTargetPolicyTest`(6), `DownloadContainerPolicyTest`(3), `AutoplayQueuePolicyTest`(+3), `StreamChoiceTest`(+1).
- 검증: `:shared:jvmTest` 전체 통과, `:app:testProdReleaseUnitTest` 대상 테스트 통과, `:app:compileProdReleaseKotlin -Pbitchord.arm64Only=true` 통과.

## 1. 흔한 상황별 실제 동작

| 상황 | 진입점 | 처리 | 결과 / 남는 위험 |
|---|---|---|---|
| 커버 영상 | `PlaybackService` 해석기 → `StreamResolver.resolve` → `innerTubeXStreamAllowingUploads` | InnerTubeX 카탈로그 → 실패 시 direct IOS/ANDROID → 프록시 → NewPipe | 커버는 `UnplayableReason`이 아니라 재생 자체는 정상. 커버 판별은 오토플레이 필터(`Autoplay.kt`)에서 별도로 한다. |
| 비디오 노래(뮤직비디오) | `SongVersionController.resolveSongVersionBeforeAudio` / `NextTrackPreparation` | `preferMusicOnly`면 첫 소리 전에 곡 버전 조회, 실패하면 영상 그대로 재생 | 정상. 버전 조회가 1.5초를 넘기면 영상이 먼저 재생되고 늦게 온 답으로 가사만 재측정한다. |
| 셔플 | `QueueShuffle`·`PlaybackService.onMediaItemTransition` | 셔플 순서는 큐 타임라인에 반영되고, `nextMediaItemIndex`가 다음 행을 가리킨다. | 정상. 단, 반복 1곡에서 실패 건너뛰기는 아래 F1로 무한 루프였다. |
| 앱 재시작 후 | `BitChordApplication` → `LastPlayed`/`BoundedContextPersistence` → `PlaybackService` | 저장된 큐/위치 복원, 첫 재생에서 해석 | 정상. 다만 저장된 세션이 있으면 프로세스 시작 즉시 `ensureSessionScope()` 네트워크가 나간다(F8). |
| 화면 꺼짐 | `PlaybackService.updateWakeMode`·`PowerState`·`HeadphoneResume` | 재생 중 `WAKE_MODE_NETWORK`, 일시정지 시 `NONE`; 화면 이벤트 수신 | 정상. 크로스페이드 컨트롤러의 250ms/1s 틱은 이 영역 밖의 기존 배터리 부채다. |
| 백그라운드 | `onTaskRemoved`·미디어 세션·`AppForeground` | 스와이프 제거 설정이 꺼져 있으면 계속 재생, WorkManager는 포그라운드일 때만 실제 작업 | 정상. 프로세스 시작 시 세션 스코프 네트워크(F8)와 WorkManager 예약은 남은 비용. |
| 프록시 켬 | `StreamResolver.resolveUncached` → `raceForFirstSuccess` → `ProxyPlayback` | 기억된 곡은 프록시 먼저, 직접 거부 시 direct+proxy 경주, URL에 라우트 세대를 찍음 | 정상. 단, `StreamChoice`에 고정된 프록시 URL은 라우트 변경 후에도 반환되어 403이 났다(F4, 수정). |
| 다운로드된 곡 | `Song.toMediaItem` → 로컬 URI 재생, 실패 시 `restreamMissingLocalFile` | 파일이 있으면 로컬, 없으면 스트리밍으로 교체 | 정상. 다운로드 생성 경로의 컨테이너 버그는 F2(수정), 재해석 컨테이너 불일치는 F7(미수정). |
| 느린/끊긴 망 | `ChunkedDataSource`·`PermanentAwareLoadErrorPolicy`·`recoverFrom` | 범위 재시도, 429/503 상한, 실패 시 재해석/대체/건너뛰기 | 정상. 실패 전송을 성공으로 기록하는 F5(미수정)는 호스트 선택을 나쁘게 만든다. |
| Android 14+ 제약 | 매니페스트 `foregroundServiceType="mediaPlayback"`, `setForegroundServiceTimeoutMs` | 미디어 재생 FGS 타입과 타임아웃 조정 | 정상. 백그라운드에서 FGS 시작을 시도하지 않도록 `AppForeground` 가드가 있다. |
| 전화/오디오 포커스/이어폰 분리 | `buildPlayer`의 `setAudioAttributes(handleAudioFocus)`·`setHandleAudioBecomingNoisy` | ExoPlayer가 포커스 손실/이어폰 분리 시 일시정지, 복귀 시 재생 | 정상. 크로스페이드 중에는 `setSessionOwner`가 한 플레이어만 포커스를 갖게 한다. |

## 2. 수정한 결함

### F1 — HIGH: 반복 1곡에서 재생 불가 트랙을 "건너뛰면" 자기 자신으로 돌아와 무한 루프

- 파일: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:5386` (수정 후), `shared/src/commonMain/kotlin/com/music/bitchord/playback/SkipTargetPolicy.kt:21`
- 증거: 기존 `skipPastUnplayable`은 `hasNextMediaItem()`이 true면 `seekToNextMediaItem()`을 호출했다. Media3에서 `REPEAT_MODE_ONE`일 때 `nextMediaItemIndex`는 현재 인덱스이고 `hasNextMediaItem()`도 true다. 따라서 해석기가 이미 "재생 불가"로 판정한 곡을 `prepare()`하면 같은 곡이 다시 실패하고, 다시 같은 곡으로 "건너뛰기"를 반복한다.
- 사용자 영향: 반복 1곡 + 연령/지역/저작권 차단 곡에서 재생이 영원히 준비→실패를 반복한다. 다음 곡으로 넘어가지 않고 배터리·네트워크를 계속 쓴다.
- 얼마나 흔한지: 반복 1곡은 흔한 설정이고, 차단 곡은 한국 비프리미엄 환경에서 드물지 않다. HIGH.
- 수정안(적용): `SkipTargetPolicy.targetAfterFailure`가 (a) 진짜 다른 다음 행, (b) 반복 1곡이면 현재+1(모듈로), (c) 한 곡뿐이면 `NO_TARGET`을 돌려준다. 서비스는 `NO_TARGET`이면 건너뛰지 않고 오류를 남긴다.
- 테스트: `shared/src/jvmTest/kotlin/com/music/bitchord/playback/SkipTargetPolicyTest.kt` — 반복 1곡, 마지막 행 랩, 한 곡, 잘못된 인덱스.

### F2 — HIGH/MEDIUM: 비보내기 다운로드에서 MP4 응답을 버리고 Opus를 다시 찾다가 실패

- 파일: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/StreamResolver.kt:1405` (새 정책), `:1439`·`:1525` (사용처)
- 증거: 기존 `downloadStream`은 `requireM4a == false`일 때 `it.downloadExtension == "webm"`만 받아들였다. 그런데 `resolveForDownload`의 허용 집합은 `setOf("m4a", "webm")`이었다. InnerTubeX가 AAC-in-MP4를 돌려주면 `found`를 버리고, 추출 실패 시 `error("이 트랙에는 다운로드 가능한 오디오가 없습니다")`로 끝났다. 프록시 다운로드 경로도 `wantedExtension = "webm"`으로 같은 실수를 했다.
- 사용자 영향: 실제로는 재생 가능한 AAC 사본이 있는데 다운로드가 실패한다. 성적표 D9의 `download failed` 140건과 직접 연결되는 경로다.
- 얼마나 흔한지: InnerTubeX가 MP4를 고르는 곡(Opus가 없거나 AAC 상위 래더만 있는 곡), 프록시로 기억된 곡에서 흔하다. HIGH/MEDIUM.
- 수정안(적용): `downloadContainerAllowed(extension, requireM4a)` 하나로 통일했다. 내보내기(`requireM4a=true`)만 MP4를 강제하고, 앱 전용 다운로드는 두 컨테이너를 모두 받는다. 실패 로그도 실제 요구 컨테이너를 말한다.
- 테스트: `shared/src/jvmTest/kotlin/com/music/bitchord/data/innertube/DownloadContainerPolicyTest.kt`.

### F3 — MEDIUM: AutoPlay가 큐 끝에 곡을 채운 뒤 재생을 시작하지 않아 침묵

- 파일: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:3679` (수정 후), `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/net/AutoplayQueuePolicy.kt:47`
- 증거: 큐가 `STATE_ENDED`가 되면 `onPlaybackStateChanged`가 `refreshAutoplayIfQueueEmpty()`를 호출하고, 비동기 배치가 도착하면 기존 코드는 `latestPlayer.addMediaItems(...)`만 했다. ExoPlayer는 마지막 항목 재생 후 항목을 추가해도 `STATE_ENDED`에 머물며 새 행으로 seek하지 않는다. 그래서 라디오는 성공(`+radio n`)했는데 소리는 나지 않았다.
- 사용자 영향: 자동 재생이 켜져 있어도 큐가 바닥나면 사용자가 직접 재생을 눌러야 한다. "사용자 조작 없는 백그라운드 시작"이 적은 이유의 하나다.
- 얼마나 흔한지: AutoPlay가 켜진 긴 청취에서 큐 끝은 반드시 온다. MEDIUM.
- 수정안(적용): 추가 전 `STATE_ENDED`·`playWhenReady`·현재 행이 마지막 행인지 확인하고, 조건이 맞으면 첫 새 행으로 `seekTo` 후 `prepare()`/`play()` 한다. 일시정지 상태나 중간 행에 추가된 배치는 건드리지 않는다.
- 테스트: `shared/src/jvmTest/kotlin/com/music/bitchord/data/net/AutoplayQueuePolicyTest.kt`의 `shouldResumeAfterTopUp` 3건.

### F4 — MEDIUM: 프록시 라우트가 바뀐 뒤 `StreamChoice`가 옛 프록시 URL을 계속 반환해 403

- 파일: `app/src/main/java/com/music/bitchord/playback/StreamChoice.kt:76` (수정 후), `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/net/ProxyPlayback.kt:225`
- 증거: `StreamChoice.of`는 TTL만 확인하고 URL이 어느 라우트에서 발급됐는지 보지 않았다. `StreamResolver.resolve`는 `ProxyPlaybackPolicy.cachedUrlUsable`로 세대를 확인하지만, 해석기 앞에서 `StreamChoice`가 먼저 반환되면 그 검사를 우회한다. 프록시를 끄거나 기본↔백업으로 바꾸면 `ProxyAwareDataSource`는 `usesProxyFor=false`로 직접 요청하고, googlevideo는 403을 준다.
- 사용자 영향: 프록시 설정 변경 직후 재생 중인 곡/다음 곡이 한 번 끊기고 복구된다. 반복되면 로딩이 길어진다.
- 얼마나 흔한지: 프록시 사용자가 설정을 바꾸거나 백업으로 전환할 때. MEDIUM.
- 수정안(적용): `ProxyPlayback.isStaleProxiedUrl(url)`을 추가하고 `StreamChoice.of`가 stale이면 선택을 지우고 null을 반환해 새로 해석하게 했다. 직접 URL은 영향 없다.
- 테스트: `app/src/test/java/com/music/bitchord/StreamChoiceTest.kt`의 새 테스트. **수정 전 실행에서 실제로 1건 실패**했고, 수정 후 통과했다.

## 3. 고치지 못한 HIGH/MEDIUM

### F5 — MEDIUM: 실패한 전송도 호스트 성공으로 기록된다

- 파일: `app/src/main/java/com/music/bitchord/playback/ChunkedDataSource.kt:424`
- 증거: `close()`가 `HqHostTracker.noteTransfer(..., ok = true)`를 무조건 호출한다. `HqHostGate.penalty`는 `successes`/`failures`와 처리량으로 호스트 순위를 정하므로, 403/타임아웃으로 중단된 호스트도 성공·바이트를 얻어 다음 후보에서 계속 앞선다.
- 사용자 영향: 느리거나 거부하는 호스트가 계속 선택되어 끊김/재시도가 반복된다. D9·음질 선택에도 악영향.
- 얼마나 흔한지: 곡 중간 전송 실패가 있는 모든 재생. MEDIUM.
- 수정안: `ChunkedDataSource`에 `transferFailed`를 두고 `openChunk`/`read`의 실패 경로에서 세운 뒤 `close()`에서 `ok = !transferFailed`, 실패 시 `HqHostTracker.noteFailure`도 호출한다.
- 남긴 이유: `DataSource`/`DataSpec`이 `android.net.Uri`에 의존해 현재 JVM 단위 테스트에서 가짜 소스로 실행할 수 없다. Robolectric 또는 계측 테스트, 혹은 `ChunkedDataSource`에서 실패 판정만 떼어낸 순수 함수가 먼저 필요하다.

### F6 — MEDIUM: 재생 불가 판정보다 URL 캐시가 먼저라 죽은 URL이 다시 나간다

- 파일: `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/StreamResolver.kt:278`
- 증거: `resolve()`는 `recent[videoId]`를 먼저 반환하고, 그 아래에서야 `unplayableReason(videoId)`를 확인한다. 주석은 "판정이 있으면 요청 없이 알려준다"고 하지만, 재생 실패로 `markUnplayable`된 뒤에도 20분 TTL의 캐시 URL이 남아 있으면 그 URL이 그대로 반환된다.
- 사용자 영향: 이미 모두가 거부한 곡을 다시 재생/읽기 전용으로 시도해 한 번 더 로딩·실패한다.
- 얼마나 흔한지: 재생 불가 곡을 사용자가 다시 누르거나, 읽기 전용이 같은 곡을 다시 해석할 때. MEDIUM.
- 수정안: `unplayableReason` 검사를 캐시 조회보다 앞으로 옮기거나, `markUnplayable`에서 `recent.remove(videoId)`를 함께 호출한다.
- 남긴 이유: `resolve`는 네트워크/캐시 상태를 함께 보므로 순수 단위 테스트로 고정하려면 캐시 주입 seam이 필요하다. 다음 작업에서 `StreamResolver`의 캐시를 작은 인터페이스로 분리한 뒤 테스트와 함께 고치는 것이 안전하다.

### F7 — MEDIUM: 다운로드 중 재해석이 컨테이너가 달라도 길이만 같으면 이어 쓴다

- 파일: `app/src/main/java/com/music/bitchord/download/Downloader.kt:115`
- 증거: 403 후 `resolveForDownload`로 새 URL을 받지만 `contentLength(url) != total`만 확인한다. 새 URL이 MP4인데 기존 목적지가 WebM이면, 같은 길이일 때 다른 컨테이너 바이트를 한 파일에 이어 쓴다.
- 사용자 영향: 파일이 생성되지만 재생 불가/중간 깨짐. "다운로드 파일을 제자리에서 다시 쓰지 않는다"는 안전 규칙과도 충돌한다.
- 얼마나 흔한지: 곡 중간 403 + 컨테이너가 바뀌는 드문 경우. MEDIUM.
- 수정안: 재해석 결과의 `downloadExtension`이 원래와 다르면 즉시 실패하고, 같을 때만 `url`을 갱신한다. `Downloader.fetch`의 테스트 seam(가짜 resolver)이 필요하다.
- 남긴 이유: 실제 HTTP/리졸버를 대체하는 테스트 seam이 없어, 지금 고치면 회귀 테스트 없이 위험한 경로를 바꾸게 된다.

### F8 — MEDIUM(배터리/D10): 백그라운드 프로세스 시작에서도 세션 스코프 네트워크가 나간다

- 파일: `app/src/main/java/com/music/bitchord/BitChordApplication.kt:193`
- 증거: 복원된 세션이 있으면 `onCreate`에서 `CoroutineScope(Dispatchers.IO).launch { Innertube.ensureSessionScope() }`가 즉시 실행된다. 이 호출은 music.youtube.com 셸을 가져오는 네트워크 요청이고, 포그라운드/재생 여부를 보지 않는다. `StreamResolver.ensureWarm()`도 첫 재생에서 같은 호출을 하므로 백그라운드 시작에서 굳이 지불할 필요가 없다.
- 사용자 영향: 알림/서비스 때문에 프로세스가 백그라운드로 깨어난 경우에도 네트워크·배터리를 쓴다. 성적표 D10의 "사용자 조작 없는 백그라운드 시작 11회"와 같은 방향.
- 얼마나 흔한지: 로그인 상태에서 백그라운드 프로세스 시작이 있는 모든 경우. MEDIUM.
- 수정안: 앱 시작의 즉시 호출을 `AppForeground.onForeground` 또는 첫 재생(`ensureWarm`)으로 미룬다. 계정 귀속이 필요한 통계/스크로블 경로는 첫 재생 전에 이미 `ensureWarm`이 보장한다.
- 남긴 이유: 시작 순서가 계정 귀속·통계에 영향을 줄 수 있어 기기 검증이 필요하고, 시작 하네스 없이 단위 테스트로 고정하기 어렵다.

## 4. 성적표(vc85, 362곡) 해석

- **다음 곡 싱크 준비 29% (D1-sync)**: `AppSettings.syncSafetyNextPrep` 기본값이 `false`이고(`app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt:526`), `SyncSafetyPolicy.nextPrepSteps`가 이 스위치가 꺼져 있으면 `LYRICS`/`SYNC`를 계획에서 뺀다(`shared/.../playback/SyncSafetyPolicy.kt:35`). 켜져 있어도 `syncWanted`가 `heavyWorkAllowed`·`aiTimingAhead`·`Deepgram.usable`을 모두 요구한다(`NextTrackPreparation.kt:460`). 즉 29%는 "다음 곡 오디오를 아직 분석하지 못한 경우"가 대부분이며, 안전 스위치를 임의로 켜서 올리는 것은 이번 작업에서 하지 않았다. 개선하려면 다음 곡의 오디오를 첫 소리 전에 준비해 `LyricsTiming`이 측정할 수 있게 하는 별도 작업이 필요하다.
- **무손실 확인 후 실제 무손실 재생 4% (D2)**: `HqSwapPolicy.decide`가 20초 미만 남음·크로스페이드·핀·위치 범위 밖을 거부하고(`HqSwapPolicy.kt:63`), 서비스가 대체 재생 중 현재 시계를 모르면 `current-clock-unknown`으로 거부한다(`PlaybackService.kt:5692`). 거부된 검증본은 `prefetchLosslessForNextPlay`로 다음 재생에 넘긴다. 성적표는 "실제 FLAC 재생"만 세므로 다음 재생으로 넘긴 정상 동작이 4%로 보인다. 가드를 약화하지 않고 올리려면 검증/다운로드를 곡 시작 직후로 앞당기는 작업이 필요하다.
- **자동 재생 보충 성공 24% (D3)**: 보충 자체는 `autoplayTopUpNeeded`가 5곡 미만일 때만 요청하고, `loadNormalAutoplayTracks`가 커버/메타데이터/세션 중복을 거른 뒤 0곡이 될 수 있다(`Autoplay.kt:355`). 여기에 F3(성공해도 재생 시작 안 함)가 겹쳤다. F3은 이번에 고쳤고, 필터로 0곡이 되는 경로는 의도된 중복 방지라 유지했다.
- **사용자 조작 없는 백그라운드 시작 11회 (D10)**: F8의 시작 시 네트워크와 WorkManager 예약이 원인 후보. F8은 남겼다.
- **다운로드·고음질 보관함 실패 140회 (D9)**: F2의 컨테이너 버그가 직접 원인 중 하나로 고쳐졌다. 나머지는 자동 고음질 패스의 실패/쿨다운(`HqLibraryUpgrade.kt:853`)과 429/호스트 휴식(`Downloads.kt:1882`)이 섞여 있어, 다음 작업에서 `download failed`의 예외 종류를 집계해야 한다.

## 5. 변경 파일

- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/StreamResolver.kt`
- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/net/ProxyPlayback.kt`
- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/net/AutoplayQueuePolicy.kt`
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/SkipTargetPolicy.kt` (신규)
- `app/src/main/java/com/music/bitchord/playback/StreamChoice.kt`
- `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt`
- `shared/src/jvmTest/kotlin/com/music/bitchord/playback/SkipTargetPolicyTest.kt` (신규)
- `shared/src/jvmTest/kotlin/com/music/bitchord/data/innertube/DownloadContainerPolicyTest.kt` (신규)
- `shared/src/jvmTest/kotlin/com/music/bitchord/data/net/AutoplayQueuePolicyTest.kt`
- `app/src/test/java/com/music/bitchord/StreamChoiceTest.kt`
