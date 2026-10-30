# 생명주기 심층 검토 (review-1031)

- 기준: `task/fix-1030` 계열 현재 트리 (`dsq/deep-lifecycle-1031`, HEAD `2ccab48`).
- 범위: 앱 시작(`BitChordApplication.onCreate`, 첫 화면), 백그라운드 진입/복귀, 사용자 조작 없는 프로세스 시작(WorkManager·브로드캐스트·미디어 재개), 프로세스 재시작 후 큐·위치·설정 복원, 알림·위젯·잠금화면 버튼.
- 방법: 매니페스트의 모든 진입점(Activity/Service/Receiver/Provider)에서 코드를 따라갔고, `LastPlayed`·`BoundedContextPersistence`·`PartyPersonalQueueStash` 복원 경로와 `AppForeground` 이벤트를 대조했다. 기기 동작에 의존하는 부분은 `추측`으로 표시했다.
- 이번 수정은 HIGH 2건, MEDIUM 1건이다. 각 수정에는 먼저 실패하도록 작성한 단위 테스트가 있다.

---

## 1. [HIGH, 수정] 프로세스 재시작 뒤 공유 링크가 다시 재생·파티 재참가·가져오기를 실행한다

- 파일: `app/src/main/java/com/music/bitchord/MainActivity.kt:449-486`, `app/src/main/java/com/music/bitchord/playback/MusicLink.kt:65-81`, `app/src/main/java/com/music/bitchord/data/listentogether/JamInviteLink.kt:30-42`, `app/src/main/java/com/music/bitchord/data/settings/SettingsTransfer.kt:510-522`
- 증거: 세 릴레이는 각자 `intent.putExtra(EXTRA_CONSUMED, true)`로 한 번만 처리한다고 표시한다. 그런데 이 extra는 클라이언트 프로세스의 `Intent` 객체에만 쓰이고, 프로세스가 죽으면 시스템이 태스크의 **원본** 인텐트를 다시 내려준다. 그래서 `savedInstanceState != null`로 재생성될 때 `EXTRA_CONSUMED`가 사라진 원본이 다시 소비된다. 바로 옆의 `PlayerDeepLink`는 이미 `recreating`/`launchedFromHistory`로 이 재생을 막고 있었지만, 링크 릴레이에는 그 방어가 없었다.
- 사용자 영향: 최근에 공유한 유튜브 링크가 앱을 다시 열기만 해도 재생되기 시작한다(`MusicLink.Resume`는 큐가 있으면 `session.play()`까지 한다). 파티 초대 링크는 사용자가 누르지 않았는데 참가 화면/참가를 다시 연다. 설정 파일 공유는 가져오기 미리보기를 다시 연다.
- 얼마나 흔한지: 링크로 앱을 연 뒤 백그라운드에서 프로세스가 회수되는 경우마다. 삼성/픽셀 모두 태스크를 Recents에 남긴 채 프로세스만 죽이는 일이 흔하므로 재현성이 높다.
- 수정: `startup/LaunchReplay.kt`를 추가해 `launchedFromHistory`와 저장된 인텐트 지문(fingerprint)을 비교한다. `MainActivity`는 `onSaveInstanceState`에 처리한 지문을 남기고, 같은 지문이 재생되면 세 릴레이를 호출하지 않는다. 새 인텐트가 재생성된 액티비티에 **다른 지문**으로 도착하면 정상 처리한다.
- 테스트: `app/src/test/java/com/music/bitchord/startup/LaunchReplayTest.kt`

## 2. [HIGH, 수정] MV 소리 복구 WorkManager가 백그라운드에서 프로세스를 다시 깨운다

- 파일: `app/src/main/java/com/music/bitchord/BitChordApplication.kt:234-241`, `app/src/main/java/com/music/bitchord/download/MvSoundRedownload.kt:113-190`
- 증거: `HqLibraryUpgrade`는 `AppForeground.onBackground`에서 고유 작업을 취소했지만, `MvSoundRedownload`는 그 이벤트에 등록되어 있지 않았다. 그래서 포그라운드에서 예약된 네트워크 제약 원샷 작업이 앱이 백그라운드로 간 뒤에도 남고, WorkManager가 프로세스를 깨운다. 워커는 창이 없으면 `Result.success()`로 조용히 끝났다(수정 전 `:149-154`). 이 작업은 아무 일도 하지 않으려고 프로세스를 띄우는 것이다.
- 사용자 영향: 사용자 조작 없는 백그라운드 프로세스 시작. 배터리·콜드 스타트 비용. 성적표의 "사용자 조작 없는 백그라운드 시작 11회"에 직접 기여한다.
- 얼마나 흔한지: `autoRedownloadMvSound`(기본 켜짐)가 켜져 있고 MV 소리 의심 파일이 하나라도 있으면, 앱을 백그라운드로 보낼 때마다.
- 수정: `shouldSchedule(enabled, foreground, hasSuspects)`로 조건을 한 곳에 모으고(기존 세 조건을 그대로 유지, `hasSuspects`는 앞 조건이 거짓이면 평가하지 않음), `onBackground`에서 고유 작업을 취소한다. `BitChordApplication`의 `onBackground`에 등록했다. 워커의 조용한 스킵 세 곳에 로그를 넣었다.
- 테스트: `app/src/test/java/com/music/bitchord/download/MvSoundRedownloadScheduleTest.kt`

## 3. [MEDIUM, 수정] 큐가 바뀐 직후 프로세스가 죽으면 새 큐가 옛 재생 위치로 복원된다

- 파일: `app/src/main/java/com/music/bitchord/playback/LastPlayed.kt:49-74`, `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:949-953`
- 증거: `LastPlayed.saveQueue`는 큐와 인덱스만 `apply()`하고 `KEY_POSITION`은 쓰지 않았다. `savePlaybackState`가 곧이어 위치를 쓰지만 별도 스레드 작업으로 큐 snapshot 뒤에 줄을 서므로, 그 사이 프로세스가 죽으면 새 큐 + **이전 곡의 위치**가 디스크에 남는다. 재생 위치가 곡 길이를 넘으면 끝에서 시작하거나 엉뚱한 지점에서 시작한다.
- 사용자 영향: 재생 중 큐를 편집(추가/삭제/이동)한 뒤 앱이 백그라운드에서 회수되면, 복원된 큐가 이전 곡의 재생 위치에서 시작한다.
- 얼마나 흔한지: 큐 편집 자체는 흔하지만, 그 직후 프로세스 사망 창은 좁다. 다만 창이 좁을 뿐 결과는 "위치가 완전히 틀림"이라 MEDIUM으로 둔다.
- 수정: `saveQueue`가 `positionMs`를 같은 `SharedPreferences.Editor` 트랜잭션에 쓴다. 호출부(`PlaybackService`의 `QueueSnapshot`)는 이미 위치를 들고 있었다.
- 테스트: `app/src/test/java/com/music/bitchord/playback/LastPlayedRestoreTest.kt`

## 4. [LOW, 수정] HQ 자동 패스의 조용한 스킵

- 파일: `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt:586-601`, `:1140-1145`
- 증거: 워커가 창 없음/설정 꺼짐/재생 중 때문에 일을 건너뛸 때 로그가 없었다. 규칙 ②(조건 거짓 → 로그 없이 return은 결함)에 해당한다.
- 수정: 각 스킵에 이유를 남겼다. 동작은 바꾸지 않았다.

---

## 공통 상황별 추적표

| 상황 | 진입점 → 처리 → 결과 | 판정 |
|---|---|---|
| 커버 영상 | `Song.toMediaItem` → `CoverTitles.applyTo` → `bitchord://watch` URI 유지, 표시만 원곡명 | 정상. 재시작 snapshot도 `StoredTrack`에 original/cover 필드를 보존 |
| 비디오 노래 | `isVideo`/`isVideoOrigin`/`videoRadio`를 `LastPlayed.StoredTrack`과 `PartyPersonalQueueStash`가 보존 | 정상 |
| 셔플 | `QueueShuffle.startingOrder`가 `original` 저장 → `LastPlayed.shuffleOrder` → `restorePersisted` | 정상. bounded 큐는 canonical context로 복원 |
| 앱 재시작 후 | `PlaybackService.onCreate` → stash → bounded → LastPlayed 순서로 복원, prepare/play는 하지 않음 | 정상(의도적으로 일시정지). 위치는 이번 수정으로 큐 변경과 원자화 |
| 화면 꺼짐 | 서비스는 계속 재생, UI 폴링은 `rememberIsForeground`로 중지, wake mode는 `WAKE_MODE_NETWORK/LOCAL` | 정상. 잠금화면 가사는 화면 이벤트로만 시작 |
| 백그라운드 | `AppForeground.onBackground` → HQ/MV Work 취소, 재생은 유지 | MV 누락을 이번에 수정 |
| 프록시 켬 | `AppSettings.initProxy`가 `Application.onCreate`에서 가장 먼저 라우트를 심음 | 정상 |
| 다운로드된 곡 | `Song.toMediaItem`이 `Downloads.verifiedSavedUri`를 확인 후 `file://`/`content://` 사용 | 정상 |
| 느린/끊긴 망 | 해석 상한 120초, 복구/스킵 경로 존재. 생명주기 영역 밖 | 이번 범위 아님 |
| Android 14+ | `FOREGROUND_SERVICE_MEDIA_PLAYBACK`/`DATA_SYNC` 선언, 백그라운드 FGS 시작은 `AppForeground`로 회피 | 정상. HQ 자동 패스는 포그라운드에서만 진행(아래 잔여 항목) |
| 링크/공유로 시작 | `MainActivity.onCreate` → `LaunchReplay` → 세 릴레이 | 프로세스 재생 시 재실행되던 결함을 이번에 수정 |
| 위젯 버튼 | `MediaWidgetActions`가 bind로 서비스에 연결, 큐 복원 후 토글 | 정상. 큐가 비어 있으면 조용히 no-op(잔여) |
| 알림/잠금화면 | Media3 세션과 `LockScreenLyricsActivity`가 같은 `PlaybackService`를 봄 | 정상. 서비스 사망 시 잠금화면 액티비티 정리 없음(잔여) |

---

## 조건 변경 전/후 진리표

### MV 복구 예약 조건

수정 전 (세 개의 조기 반환):

```
if (!enabled) return
if (!foreground) return
if (!hasSuspects) return
schedule()
```

수정 후:

```
if (!(enabled && foreground && hasSuspects())) return
schedule()
```

| enabled | foreground | hasSuspects | 수정 전 | 수정 후 |
|---|---|---|---|---|
| T | T | T | 예약 | 예약 |
| T | T | F | 안 함 | 안 함 |
| T | F | T/F | 안 함(스캔 안 함) | 안 함(스캔 안 함) |
| F | T/F | T/F | 안 함(스캔 안 함) | 안 함(스캔 안 함) |

기존 조건 세 개를 모두 유지했다. 차이는 `hasSuspects()`가 앞 조건이 거짓일 때 평가되지 않는다는 것뿐이다.

### 링크 인텐트 소비 조건

수정 전: `intent != null && !EXTRA_CONSUMED && parse 성공` → 소비.

수정 후: 위 조건 **그리고** `!isReplay` → 소비.

`isReplay = launchedFromHistory || (recreating && (savedFingerprint == null || savedFingerprint == currentFingerprint))`

| 재생성 | history | 저장 지문 | 현재 지문 | 수정 전 | 수정 후 |
|---|---|---|---|---|---|
| F | F | 없음 | A | 소비 | 소비 |
| F | T | 없음 | A | 소비(재실행) | 소비 안 함 |
| T | F | A | A | 소비(재실행) | 소비 안 함 |
| T | F | A | B | 소비 | 소비(새 인텐트) |
| T | F | 없음 | A | 소비(재실행) | 소비 안 함(안전 우선) |

---

## 성적표 숫자와 생명주기의 관계

- **사용자 조작 없는 백그라운드 시작 11회**: `MvSoundRedownload`의 WorkManager 작업이 백그라운드에서 남아 프로세스를 깨우고 창이 없어 바로 끝나는 경로가 직접 원인 중 하나였다. 이번에 `onBackground` 취소로 제거했다. 위젯 `APPWIDGET_UPDATE`(부팅/런처 갱신)와 `MediaButtonReceiver`(헤드셋/차량 버튼)는 사용자·시스템 이벤트라 남는다.
- **다음 곡 싱크 준비 29%**: 생명주기 쪽에서는 프로세스가 재시작되면 `NextTrackPreparation`의 진행 상태가 사라지고 다음 재생 이벤트에서 처음부터 다시 준비된다. 다만 대부분은 소스 해석·정렬 비용이라 이번 영역만으로 설명되지 않는다.
- **무손실 확인 후 실제 무손실 재생 4%**: `HqVerificationStore`는 디스크에 남지만, 자동 HQ 패스(`HqLibraryUpgrade`)는 `AppForeground.isForeground`일 때만 예약되고 워커도 창이 없으면 즉시 반환한다. 즉 백그라운드 청취 위주 사용자는 검증은 되어도 실제 교체 패스가 거의 돌지 않는다. Android 12+의 백그라운드 FGS 제한 때문에 단순히 가드를 풀 수는 없고, WorkManager foreground worker 또는 사용자가 화면을 켠 동안의 명시적 배치가 필요하다(잔여).
- **자동 재생 보충 성공 24%**: 프로세스 재시작 시 `loadAutoplayForCurrentTrack`가 복원 직후 다시 호출되지만, 백그라운드에서 프로세스가 회수되면 보충 자체가 멈춘다. 소스/추천 응답 실패와 겹치는 부분이 커서 생명주기 단독 원인으로 보지 않는다.
- **다운로드·고음질 보관함 실패 140회**: `HqLibraryUpgrade.onBackground`가 자동 패스를 취소하고, 백그라운드에서는 `Downloads.enqueue`가 FGS를 시작할 수 없어 대기 목록이 메모리에만 남는다. 그 상태에서 프로세스가 죽으면 대기 목록이 사라지고 다음 포그라운드에서 다시 계획을 세운다. 자동 패스가 짧은 포그라운드 창마다 중단·재시도되는 구조가 실패 누적의 생명주기 쪽 원인이다.

---

## 이번에 고치지 못한 HIGH/MEDIUM

1. **[MEDIUM] 다운로드 대기 목록이 프로세스 메모리에만 있다** — `Downloads.pending`(`Downloads.kt:205`)은 `LinkedHashMap`이고 디스크에 없다. 백그라운드에서 `enqueue`된 항목은 `onForeground`를 기다리는데, 그 전에 프로세스가 죽으면 사라진다. 필요한 작업: `pending` 큐를 작은 JSON/라인 포맷으로 저장하고 `Downloads.init`에서 복원, `onForeground`가 이어서 drain. 파일 범위가 커서 이번에는 문서화만 했다.
2. **[MEDIUM] 잠금화면 가사 액티비티가 서비스 사망을 따라 닫히지 않는다** — `LockScreenLyricsActivity`는 `controller`/`song`이 null이 될 때만 1.5초 뒤 닫는다. 서비스가 `onDestroy`되어 MediaController가 끊겨도 `song`이 즉시 null이 되지 않을 수 있다. 필요한 작업: `LockScreenLyrics.detach`에서 명시적 종료 이벤트(브로드캐스트 또는 `NowPlayingLyrics`의 종료 상태)를 보내고 액티비티가 그것을 수신.
3. **[MEDIUM] 프로세스 재시작 뒤 헤드폰 연결 자동 재개가 동작하지 않는다** — `PlaybackService.stoppedPlayingAt`은 서비스 인스턴스 필드라 재시작하면 0이 된다. 복원된 일시정지 큐는 "최근 일시정지"로 간주되지 않아 `HeadphoneResume`이 재개하지 않는다. 필요한 작업: 일시정지 시각을 `LastPlayed`에 저장하고 복원 시 신선도 창(30분)과 함께 읽기.
4. **[LOW] 수면 타이머가 서비스 파괴 뒤 남는다** — `PlaybackService.onDestroy`에 `SleepTimer.cancel()`이 없다. 프로세스가 유지된 채 서비스만 재시작되면 새 서비스가 지난 deadline을 보고 즉시 일시정지할 수 있다. 한 줄 수정이지만 서비스 생명주기 테스트 seam이 없어 이번 테스트 우선 원칙에서 제외했다. 필요한 작업: `onDestroy`에서 취소 + 서비스 teardown을 단위 테스트할 수 있는 작은 seam.

---

## 수정 파일

- `app/src/main/java/com/music/bitchord/MainActivity.kt`
- `app/src/main/java/com/music/bitchord/startup/LaunchReplay.kt` (신규)
- `app/src/main/java/com/music/bitchord/BitChordApplication.kt`
- `app/src/main/java/com/music/bitchord/download/MvSoundRedownload.kt`
- `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt` (로그만)
- `app/src/main/java/com/music/bitchord/playback/LastPlayed.kt`
- `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` (호출부 1줄)
- `app/src/test/java/com/music/bitchord/startup/LaunchReplayTest.kt` (신규)
- `app/src/test/java/com/music/bitchord/download/MvSoundRedownloadScheduleTest.kt` (신규)
- `app/src/test/java/com/music/bitchord/playback/LastPlayedRestoreTest.kt` (신규)

## 테스트

- `:app:compileProdReleaseKotlin -Pbitchord.arm64Only=true` 통과.
- `:app:testProdReleaseUnitTest --tests 'com.music.bitchord.startup.LaunchReplayTest' --tests 'com.music.bitchord.download.MvSoundRedownloadScheduleTest' --tests 'com.music.bitchord.playback.LastPlayedRestoreTest'` 통과.
- 전체 `:app:testProdReleaseUnitTest`는 1694개 중 22개가 실패한다. 모두 이번 변경과 무관한 기존 실패다(번역 키 누락 `LocaleStringsTest`, 러시아어 접기 `ArtistNameMatchTest`, provider 개수 `ProviderLyricsTest`, 소스 포맷 오류 메시지 `SourceFormatsTest`, Bluetooth 표시 문자열 `BluetoothAudioTrackerTest`).
