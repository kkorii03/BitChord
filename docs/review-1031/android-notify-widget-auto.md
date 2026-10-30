# 알림·잠금화면·위젯·Android Auto/차량 엄격 검토 (2026-10-31)

기준 브랜치: `task/fix-1030` (로컬 `dsq/deep-notify-widget-auto-1031`)
검토 범위: `PlaybackService` 세션 메타데이터/커스텀 레이아웃/브라우즈, `MediaSessionArtwork`, `widget/*`, 매니페스트 리시버/프로바이더.

수정 파일:

- `app/src/main/java/com/music/bitchord/playback/MediaSessionArtwork.kt`
- `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt`
- `app/src/main/java/com/music/bitchord/playback/SessionFavoriteState.kt` (신규)
- `app/src/main/java/com/music/bitchord/widget/MediaWidgetSnapshot.kt`
- `app/src/test/java/com/music/bitchord/playback/MediaSessionArtworkReadersTest.kt` (신규)
- `app/src/test/java/com/music/bitchord/playback/SessionFavoriteStateTest.kt` (신규)
- `app/src/test/java/com/music/bitchord/widget/MediaWidgetSnapshotTest.kt` (신규)

---

## 1. 진입점 → 처리 → 결과

| 표면 | 진입점 | 처리 | 결과 / 상태 반영 |
|---|---|---|---|
| 알림 재생/일시정지 | Media3 알림 버튼 | `SessionPlayer` → `play/pause` | `onPlayWhenReadyChanged`가 `publishWidgetState(playing=…)` 호출, 위젯/워치 동기 |
| 알림 다음/이전 | Media3 알림 버튼 | `SessionPlayer.seekToNextMediaItem/seekToPreviousMediaItem` → `wrappedPlayer` | 재생 중이면 `onTrackBecameCurrent`가 위젯 갱신·다음곡 준비. 일시정지 중 스킵은 의도적으로 일시정지 유지 |
| 알림 좋아요 | `favoriteCommand` | `onCustomCommand` → `canLike` 가드 → `toggleFavoriteFromNotification` | `LikeState` 낙관 갱신 → `refreshCustomLayouts` + `publishWidgetState` |
| 알림 셔플 | `shuffleCommand` | `toggleShuffleFromSession` → `QueueShuffle`/bounded edit | `QueueShuffle.enabled` 수집기가 커스텀 레이아웃과 위젯을 갱신 |
| 잠금화면/System UI 표지 | 플랫폼 MediaSession 메타데이터 | `SessionPlayer.getMediaMetadata` → `MediaSessionArtwork.apply` | FileProvider `content://` + 읽기 권한 |
| Android Auto 표지(재생 중) | 플랫폼 MediaSession 메타데이터 | `apply` | 수정 전: `com.android.systemui`에만 권한 → Auto에서 `SecurityException` 가능 |
| Android Auto 표지(브라우즈 목록) | `toMediaItem` / `createGridPlaylistItem` | `MediaSessionArtwork.uriFor/uriForUrl` | 수정 전: `apply`를 거치지 않아 권한 자체가 없음 |
| 위젯 재생/다음/이전/좋아요/셔플 | `MediaWidgetActions` 브로드캐스트 | `MediaController` bind 후 세션 명령 | 큐가 있으면 알림과 같은 서비스 경로, 없으면 무음 return(아래 LOW) |
| 위젯 탭 → 앱 | `WidgetOpenIntent` | `AppSettings.widgetOpensPlayer`가 켜져 있으면 `EXTRA_OPEN_PLAYER` | `PlayerDeepLink`가 콜드/웜 양쪽에서 소비, 설정 OFF면 일반 복귀 |
| 위젯 상태 표시 | 저장된 `MediaWidgetSnapshot` | `publishWidgetState`가 재생/일시정지/전환/셔플/좋아요/종료 시 저장 | 프로세스 사망 후에도 마지막 상태로 그림 |
| 차량 버튼(라디오/원곡) | `carButtons` 커스텀 레이아웃 | `startStationFromSession` / `revertCurrentToOriginal` | 차량 컨트롤러 연결/해제 시 `refreshCustomLayouts` |

## 2. 흔한 상황별 실제 동작

| 상황 | 알림 | 잠금화면 표지 | 위젯 | Android Auto |
|---|---|---|---|---|
| 커버 영상/비디오 노래 | 제목·표지 정상 | `artworkSelection`이 https still 사용 | 수정 후 썸네일 없으면 video still 사용 | 수정 후 FileProvider 권한 포함 |
| 다운로드된 곡 | 재생/좋아요 정상 | 수정 후 Auto/차량도 읽기 가능 | 로컬 sidecar `file://`를 앱 프로세스에서 직접 그림 | 수정 후 sidecar 표지 읽기 가능 |
| 셔플 | 아이콘이 `QueueShuffle.enabled` 추종 | 영향 없음 | 4×1 위젯 배경/아이콘 갱신 | 커스텀 버튼 갱신 |
| 앱 재시작 후 | 수정 후 저장된 좋아요로 별 표시, 첫 탭은 해제 | 세션 메타데이터 재구성 | 큐 복원 시 `publishWidgetState`, 큐가 없으면 EMPTY 저장 | `onPlaybackResumption` 1순위는 현재 큐 |
| 화면 꺼짐/백그라운드 | 이벤트 기반 갱신만 | 표지 URI 사전 권한 부여 | 주기 폴링 없음 | 재생 중 메타데이터 이벤트로 갱신 |
| 프록시 켬 | 알림은 세션 명령만 사용, 프록시 무관 | 원격 URL이면 그대로 | 원격 URL Coil 경유 | 원격 URL 그대로 |
| 느린/끊긴 망 | 재생 명령은 즉시 접수 | 원격 표지는 System UI가 재시도 | 8초 타임아웃 후 이전 표지 유지 | 브라우즈 목록 네트워크 타임아웃 시 빈 목록(아래 MEDIUM, 미수정) |
| Android 14+ | Media3 미디어 알림/FGS 경로 | FileProvider grant는 패키지별 | 위젯은 bind 사용(백그라운드 startService 회피) | 동일 |

---

## 3. 수정한 결함

### HIGH-1. Android Auto/차량에서 다운로드 표지가 보이지 않음

- 파일: `app/src/main/java/com/music/bitchord/playback/MediaSessionArtwork.kt:36-51, 65-92, 151-159`
- 증거: 기존 코드는 `grantRead`에서 `SYSTEM_UI` 한 패키지에만 `grantUriPermission`을 호출했다. 그런데 세션 메타데이터의 `content://` 표지는 잠금화면뿐 아니라 Android Auto(`com.google.android.projection.gearhead`)와 Automotive 미디어 앱이 각자의 프로세스에서 연다. 게다가 브라우즈 항목은 `uriFor`/`uriForUrl`로 `MediaItem`을 만들 뿐 `apply`를 거치지 않아 권한이 아예 없었다.
- 사용자 영향: 차량에서 다운로드 곡/다운로드 플레이리스트 표지가 빈칸, 일부 Auto 구현에서는 이미지 로드 실패 로그/예외.
- 얼마나 흔한지: 다운로드 곡을 차량에서 재생하는 모든 경우. 다운로드·고음질 보관함 사용자에게 흔함.
- 수정: 읽기 대상에 Auto/Automotive를 추가하고, `uriFor`/`uriForUrl`에서도 URI를 만들 때 권한을 부여. 권한 캐시 키를 `URI`에서 `리더|URI`로 바꿔 나중에 연결된 차량도 자기 권한을 받게 했다. 기존 “재생 중 메타데이터만 다른 프로세스에 간다”는 주석은 Android Auto 브라우즈에는 사실이 아니므로 수정했다.
- 테스트: `MediaSessionArtworkReadersTest`

### MEDIUM-1. 프로세스 재시작 후 알림 별이 실제 좋아요와 반대

- 파일: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:2403-2419, 3865-3871`; 신규 `SessionFavoriteState.kt`
- 증거: 알림은 `LikeState.overrides`만 읽었다. 이 맵은 현재 프로세스에서 본/바꾼 평가만 담는다. 서비스가 위젯/차량/프로세스 사망 후 시작하면 비어 있으므로 좋아요한 곡도 빈 별로 보였고, 첫 탭은 `previous = INDIFFERENT`라 **해제가 아니라 다시 좋아요**를 전송했다. 같은 서비스의 위젯 상태는 이미 저장된 스냅샷을 폴백으로 쓰고 있어 두 표면이 서로 달랐다.
- 사용자 영향: 알림/잠금화면/Auto에서 별이 실제와 다르고 첫 탭이 반대로 동작.
- 얼마나 흔한지: 앱을 종료했다가 알림·위젯·차량으로 재생을 시작하는 경우.
- 수정: `SessionFavoriteState`가 (1) 이번 세션 override, (2) 같은 트랙의 저장된 위젯 스냅샷 좋아요 순으로 상태를 정하고, 별 표시와 다음 탭의 목표를 모두 같은 함수로 계산.
- 조건 변경 진리표:

| override | 같은 트랙 snapshot.liked | 이전 표시 | 이전 첫 탭 | 새 표시 | 새 첫 탭 |
|---|---|---|---|---|---|
| 없음 | false/다른 트랙 | 빈 별 | LIKE | 빈 별 | LIKE |
| 없음 | true | 빈 별 | LIKE (잘못) | 채운 별 | INDIFFERENT (해제) |
| LIKE | any | 채운 별 | INDIFFERENT | 채운 별 | INDIFFERENT |
| INDIFFERENT | any | 빈 별 | LIKE | 빈 별 | LIKE |

- 테스트: `SessionFavoriteStateTest`

### MEDIUM-2. 썸네일이 없는 곡/복원된 큐에서 위젯 표지가 비어 있음

- 파일: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:8512-8527`; `app/src/main/java/com/music/bitchord/widget/MediaWidgetSnapshot.kt:75-113`
- 증거: 위젯은 `song.thumbnailUrl`을 그대로 저장했다. 복원된 큐, 좋아요 목록의 일부 행, 오래된 스냅샷은 `thumbnailUrl = null`인데 `videoId`는 유효하다. 알림/잠금화면은 `artworkSelection`이 `videoThumbnailUrl`로 폴백하지만 위젯은 `MediaWidgetArt.loadArtwork`가 빈 URL에서 즉시 null을 반환해 플레이스홀더를 그렸다.
- 사용자 영향: 같은 곡이 알림에는 표지가 있고 위젯에는 없음.
- 얼마나 흔한지: 앱 재시작 후 큐 복원, 썸네일이 생략된 목록 행.
- 수정: `MediaWidgetSnapshot.artworkUrlFor`가 기록된 썸네일을 우선 쓰고, 없으면 로컬 파일이 아닌 경우 YouTube still로 폴백.
- 테스트: `MediaWidgetSnapshotTest`의 `a restored row with no thumbnail uses the video still`, `a local file with no YouTube identity has no invented cover`

### MEDIUM-3. 큐를 비워도 위젯이 마지막 곡을 계속 표시

- 파일: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:8508-8535`; `app/src/main/java/com/music/bitchord/widget/MediaWidgetSnapshot.kt:67-93`
- 증거: 기존 `publishWidgetState`는 현재 미디어가 없으면 `?: return`으로 빠져나왔다. 위젯은 저장된 스냅샷을 읽으므로 큐가 비워져도 이전 곡 제목/표지/일시정지 글리프가 남았다.
- 사용자 영향: 재생할 것이 없는데 위젯이 재생 중인 것처럼 보이고, 버튼은 큐가 없어 무음 no-op.
- 얼마나 흔한지: 앱에서 큐 전체 삭제/마지막 항목 제거 후 홈으로 이동.
- 수정: `MediaWidgetSnapshot.fromPlayback(song = null)`이 `EMPTY`를 돌려주고, `publishWidgetState`는 여전히 저장/새로고침한다.
- 조건 변경 진리표:

| player | current song | 이전 결과 | 새 결과 |
|---|---|---|---|
| null | any | return | return |
| 있음 | null | return, 위젯 유지 | EMPTY 저장 + 위젯/워치 갱신 |
| 있음 | 있음 | 스냅샷 저장 | 스냅샷 저장 (동일) |

- 테스트: `MediaWidgetSnapshotTest`의 `an empty queue publishes the empty snapshot instead of the last track`

---

## 4. 고치지 못한 HIGH/MEDIUM

### MEDIUM-A. Android Auto 브라우즈가 느린 망에서 조용히 빈 목록

- 파일: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:11655-11710` (`resolveMediaItems`의 `playlist:`/`browse:` 분기)
- 증거: `withTimeoutOrNull(2500~3000)` 결과가 null/빈 목록이면 로그 없이 `resolved`에 아무것도 추가하지 않는다. Auto에서 플레이리스트를 눌렀을 때 로딩 실패가 “빈 폴더”로 보인다.
- 이유: 이번에 고치려면 Auto용 오류/재시도 항목의 문구·동작을 정해야 하고, `onGetChildren`과 같은 캐시 경로까지 함께 손봐야 한다. 최소 수정은 `TrackLog.w` + 오류 항목이지만 사용자 노출 문구 결정이 필요해 남겼다.
- 필요한 작업: 실패 시 `createInfoItem` 재시도 안내 + 로그, 캐시 히트 우선.

### MEDIUM-B. 차량 연결 시 `onPlaybackResumption`이 임의의 다운로드/로컬 곡을 반환

- 파일: `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:11522-11587`
- 증거: 현재 큐가 없으면 2) 다운로드 전체, 3) 로컬 음악 전체, 5) 홈 추천을 반환한다. 차량이 재개를 요청하면 사용자가 마지막으로 듣던 곡이 아니라 목록 첫 곡이 시작될 수 있다. “사용자 조작 없는 백그라운드 시작 11회”의 한 원인 후보.
- 이유: 이 폴백은 “오프라인에서도 차량이 뭔가 재생되게” 하려는 의도가 주석에 명시돼 있어, 제거는 제품 결정이다. 마지막 재생 기록(`LastPlayed`)이 있으면 1)에서 이미 복원된다.
- 필요한 작업: 큐가 없을 때 빈 목록을 반환할지, 다운로드 폴더를 명시적으로 선택했을 때만 폴백할지 결정.

### LOW. 위젯 명령의 무음 스킵

- 파일: `app/src/main/java/com/music/bitchord/widget/MediaWidgetActions.kt:77`
- 증거: `mediaItemCount == 0`이면 로그 없이 return. 앱 재시작 후 복원이 실패한 큐에서 버튼이 “눌렀는데 아무 일도 없음”이 된다.
- 이유: 사용자에게 보여줄 대체 동작(앱 열기 등)은 백그라운드 브로드캐스트에서 Activity 시작 제약이 있어 이번 범위에서는 로그/문서만 남긴다.

---

## 5. 실제 사용 성적표와 이 영역의 관계

- **다음 곡 싱크 준비 29%**: 알림/Auto의 스킵은 `SessionPlayer.seekToNextMediaItem`으로 들어와 `onUserAdvance`/`crossfade.onSkipRequested`를 거친다. `onTrackBecameCurrent`는 `isPlaying || alreadyAudible`일 때만 `scheduleNextPrep`을 호출하므로, 일시정지 상태에서 알림으로 다음 곡을 고르면 다음 곡 준비가 시작되지 않는다. 이는 “일시정지 중 스킵은 일시정지 유지”라는 기존 규칙의 부작용이고, 배터리 규칙상 일시정지 중 선행 준비를 켜는 것은 이번에 하지 않았다. 재생 중 스킵 경로 자체는 정상적으로 준비를 건다.
- **무손실 확인 후 실제 무손실 재생 4%**: 이 영역의 표면(알림/위젯/Auto)은 품질 전환이나 `HqAudioVerify`를 트리거하지 않는다. 낮은 수치는 `playback/smart`·`download` 영역의 검증/재생 경로 문제이지 알림/위젯 때문이 아니다. 이번 수정은 표지·좋아요 상태에만 영향을 준다.
- **자동 재생 보충 성공 24%**: 큐가 끝났을 때 알림/Auto의 “다음”은 다음 항목이 없으면 `seekToNextMediaItem`이 이동하지 않고, `loadAutoplayForCurrentTrack`은 트랜지션에서만 호출된다. 따라서 끝에서의 보충은 이 표면이 아니라 서비스의 전이/보충 스케줄러가 담당한다. 위젯은 `hasNext=false`를 흐리게 표시해 잘못된 기대를 막는다.
- **사용자 조작 없는 백그라운드 시작 11회**: 위젯은 `startService` 대신 bind를 써서 백그라운드 시작 제한을 피한다. 다만 Auto의 `onPlaybackResumption` 폴백(MEDIUM-B)은 차량 연결만으로 임의 재생을 시작할 수 있어 이 숫자에 기여할 수 있다.
- **다운로드·고음질 보관함 실패 140회**: 이 영역은 다운로드 자체를 수행하지 않는다. 다만 HIGH-1 때문에 다운로드된 곡의 차량 표지가 실패로 보였을 수 있고, 수정으로 그 표시 실패는 줄어든다. 실제 다운로드 실패는 `download/HqLibraryUpgrade` 영역 과제다.

---

## 6. 검증

- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` 통과.
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.widget.MediaWidgetSnapshotTest' --tests 'com.music.bitchord.playback.SessionFavoriteStateTest' --tests 'com.music.bitchord.playback.MediaSessionArtworkReadersTest' -Pbitchord.arm64Only=true --console=plain -q` 통과.
- 신규 테스트: `MediaWidgetSnapshotTest`, `SessionFavoriteStateTest`, `MediaSessionArtworkReadersTest`.
- 배터리: 새 주기 폴링/타이머/delay 루프 없음. 모든 갱신은 기존 재생·전이·셔플·좋아요 이벤트에서만 발생.
