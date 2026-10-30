# review-dead-r4 — 존재하지만 작동하지 않는 기능 (4차)

- 점검일: 2026-10-31 (러너 기준 2026-10-30)
- 기준: 현재 워크스페이스 HEAD. 얕은 클론이라 `7d956f21..HEAD` 이력은 없고, 업스트림/포크 구분은 기존 감사 문서와 주석으로만 판단했다.
- 이전 문서: `docs/review-1031/review-dead.md` (r3). 이번 라운드는 그 문서가 `문서에만/미해결`로 남긴 HIGH·MED 중 재생 핵심 경로가 아닌 것을 우선 처리했다.
- 범위: next-prep, HQ 검증, HQ 자동 다운로드, 스트림 전송 집계, 설정 UI. 재생 핵심 경로(곡 해석·노래 버전·가사 싱크 계산·크로스페이드)는 수정하지 않았다.

---

## 1. r3 문서의 HIGH·MED 처리 결과

| r3 항목 | r4 상태 | 근거 |
|---|---|---|
| next-prep `sync=skipped`에 이유가 없음 | **고침** | `NextTrackPreparation.kt:267,326`가 `SyncSafetyPolicy.nextPrepSyncSkipReason`으로 `next-prep-lyrics-off`, `lyrics-off`, `video`, `low-power`, `timing-ahead-off`, `deepgram-unavailable`, `no-lyrics`, `no-timed-lines`, `not-applied` 중 하나를 남긴다. |
| `ChunkedDataSource.close()`가 실패한 전송도 `ok=true` | **고침** | `ChunkedDataSource.kt:76,458-482`가 open/read/truncated 실패를 `transferFailed`로 추적하고, 실패는 `noteFailure`로 한 번만 기록한다. |
| `HqVerifyPolicy.shouldRetryMissingReference` 호출부 0 | **고침** | `HqAudioVerify.kt:895`가 `manual` 대신 이 정책 함수를 쓴다. 반환값이 `manual`과 같아 동작은 그대로다(아래 진리표). |
| `HqVerifyPolicy.cooldownRemaining` 호출부 0 | 남음 · 실 검증 필요 | `HqVerifyPolicy.kt:63`는 여전히 테스트만 참조한다. 현재 앱에는 “마지막 수락 시각” 저장소가 없어(`HqVerificationStore`는 시각을 저장하지 않는다) 그대로 연결하면 새 TTL 정책을 만드는 셈이다. HQ 경로라 기기 검증 후 결정. |
| `HqAutoDownloadPolicy.shouldStartNow` 호출부 0 | 남음 | `HqAutoDownloadPolicy.kt:85`. 자동 패스는 `HqLibraryUpgrade.runOne`의 인라인 `PlaybackActivity.active`/`runAllowed` 검사로 실제로 막히고 있어 지금 동작에는 구멍이 없다. 함수만 dead다. |
| `SyncSafety` 3스위치 기본 off | 남음 · 실 검증 필요 | `AppSettings.kt:526-528,1314-1316`. 가사·싱크 계산 경로라 이번에도 기본값을 바꾸지 않았다. |
| 다운로드 재검증/override 기본 off | 남음 · 실 검증 필요 | `AppSettings.kt:526-528`, `LyricsTiming`의 `SyncSafetyPolicy.recheckAllowed`/`overrideReadAllowed`. 가사 싱크 경로. |
| `hqAdjustmentAllowed` 기본 off + `hqOffsetMs`/`hqRate` 미독 | 남음 · 실 검증 필요 | `AppSettings.kt:528`, `Downloads.kt`의 HQ 메타 저장만 있고 로컬 재생에서 읽는 곳이 없다. 가사 싱크 경로. |
| `preferMusicOnly` 기본 off | 남음 · 실 검증 필요 | `AppSettings.kt:596`; `NextTrackPreparation.versionWanted`, `SongVersionController`, `PrefetchController`가 모두 즉시 반환. 노래 버전 핵심 경로. |
| NextPrep이 `rowId`만 키로 씀 | 남음 · 실 검증 필요 | `NextTrackPreparation.kt:71,140,149`; VERSION이 같은 행의 videoId를 바꿔도 이전 시도 기록이 남는다. 가사 싱크 핵심 경로. |
| Replay 장르 차트 탭 | 이전 고침 유지 | `ReplayScreen.kt`, `MainActivity.kt`의 `onOpenGenre`. |
| AutoPlay 커버 시드 0곡 | 이전 고침 유지 | `Autoplay.kt`의 `isKaraokeOrTributeArrangement` 폴백. |
| 서비스 파괴 후 수면 타이머 | 이전 고침 유지 | `PlaybackService.onDestroy`의 `SleepTimer.cancel()`. |

---

## 2. 새로 확인한 문제

| 심각도 | 파일:줄 | 증거 | 사용자 영향 | 수정안 |
|---|---|---|---|---|
| MED | `shared/.../data/sources/HqVerifyPolicy.kt:63` | 앱 코드에서 `cooldownRemaining` 호출 0. `HqVerificationStore`에는 마지막 수락 시각 필드가 없다(`Verified`는 offset/rate/score/tier만 저장). | “수동 고음질 다시 받기”가 자동 경로의 쿨다운을 우회한다는 정책이 코드로는 존재하지 않는다. 지금은 쿨다운 자체가 없어 우회할 것도 없지만, 정책 함수와 실제 동작이 다르다. | TTL을 실제로 도입할 때 `Verified`에 시각을 추가하고 이 함수를 자동 경로에 연결한다. 그전에는 문서에만 둔다. |
| MED | `shared/.../data/net/HqAutoDownloadPolicy.kt:85` | `shouldStartNow` 호출 0. 실제 차단은 `HqLibraryUpgrade.kt:595`의 `PlaybackActivity.active`와 `:598`의 `runAllowed`가 한다. | 자동 다운로드가 “재생 중·호스트 사용 중·동시 1개”를 한 함수로 보장한다는 설명과 달리, 호스트 사용 중/동시 1개 조건은 이 함수를 거치지 않는다. 지금은 worker가 1개만 돌아 실제 피해는 제한적이다. | `runOne`에서 host를 알 수 있는 지점(타깃 선택 후)에 연결하거나, 중복 정책이므로 함수를 제거한다. |
| LOW | `app/.../ui/screens/SettingsSheet.kt:597` | 설정 검색창 `onSubmit = {}`. 입력 중 필터는 이미 적용된다. | 엔터를 눌러도 키보드가 닫히거나 첫 결과로 이동하지 않는다. 기능 손실은 없다. | 키보드 닫기 정도의 최소 동작을 준다. |
| LOW | `app/.../data/settings/AppSettings.kt:961`; `app/.../ui/screens/AccountAndScrobblingScreen.kt:382-404` | `scrobblingAvailable = true` 상수라 else(중지 안내) 분기가 도달 불가. | 죽은 UI 분기가 남아 있다. 사용자에게는 보이지 않는다. | 실제 기능 플래그로 만들거나 else 분기를 삭제한다. |

새 HIGH는 찾지 못했다. 위 MED 2건은 r3에서 이미 식별된 것이고, 이번에 새로 생긴 것은 아니다.

---

## 3. 기능별 “실행 조건”과 조용히 건너뛰는 상황 (r4 확인분)

| 기능 | 실행 조건(코드) | 거짓이 되는 흔한 상황 | 건너뛸 때 로그 |
|---|---|---|---|
| next-prep 싱크 | `SyncSafetyPolicy.nextPrepSyncSkipReason(...) == null` | 기본값 off, 비디오 행, 저전력, Deepgram 키 없음, 가사 없음/무시간 | **이번 수정으로 사유 토큰이 남는다**(`sync=skipped:<reason>`). |
| next-prep 버전 | `preferMusicOnly && wantsSongVersion && !videoRadio && !(localUri && offline)` (`NextTrackPreparation.kt:319-326`) | 새 설치 기본값 off, 오프라인 다운로드, video radio 행 | 기존 최종 로그의 `version=`만. 별도 사유 없음. |
| HQ 후보 검증 | `SourceResolver.canSubstituteForYouTube()` + 후보 존재 | YouTube 전용, 다운로드 행, 애드온 미설정 | `hq=` 값만. 실패 시 `next-prep hq failed`. |
| 자동 HQ 다운로드 | `runAllowed && AppForeground.isForeground && !PlaybackActivity.active && !metered` (`HqLibraryUpgrade.kt:590-606`) | 백그라운드, 재생 중, 모바일 데이터 | 일부 로그 있음(`waiting for an unmetered connection`). 재생 중/백그라운드는 무로그. |
| AutoPlay 커버 시드 | `isKaraokeOrTributeArrangement` 폴백 | 카라오케/트리뷰트/인스트루멘탈만 있는 방송국 | 제거 개수 로그. 0곡 사유는 여전히 별도 없음. |

---

## 4. 호출부 0인 코드 (삭제하지 않음)

이번에도 삭제하지 않았다. 업스트림 v1.8.1과 포크를 이력 없이 확정할 수 없고, 정책 함수를 지우면 정책 자체가 사라지기 때문이다.

| 파일:줄 | 함수 | 참조 |
|---|---|---|
| `shared/.../data/sources/HqVerifyPolicy.kt:63` | `cooldownRemaining` | 테스트만 |
| `shared/.../data/net/HqAutoDownloadPolicy.kt:85` | `shouldStartNow` | 테스트만 |
| `shared/.../playback/HandoffQueuePolicy.kt:58` | `tailAfterIncoming` | 테스트만 |
| `shared/.../videoradio/VideoRadioPolicy.kt:457` | `videoRadioFallbackStage` | 테스트만 |
| `app/.../playback/QueueHistory.kt:17,27` | `skippedByQueueJump`, `queueStartingAt` | 테스트만 |
| `shared/.../data/model/SongOrigins.kt:54` | `showsAccountActions` | 테스트만 |
| `shared/.../data/sources/PlayingAudioFormat.kt:40` | `isLosslessOrAtmos` | 테스트만 |
| `shared/.../data/sources/HqTier.kt:108` | `isFakeHighQuality` | 테스트만 |

`shouldRetryMissingReference`는 이번에 프로덕션 호출부가 생겨 목록에서 빠졌다. 삭제한 코드는 없다.

---

## 5. 조건 진리표 (변경 전/후)

`HqAudioVerify.referenceFeaturesUncached`의 수동 분기만 정책 함수로 바꿨다.

| manual | 변경 전 | 변경 후 (`HqVerifyPolicy.shouldRetryMissingReference(manual)`) |
|---|---|---|
| true | `noReference.remove(youtubeId)` | `noReference.remove(youtubeId)` |
| false | `noReference[youtubeId]`가 있으면 `Reference.Missing` 반환 | 같음 |

`shouldRetryMissingReference(manual) = manual`이므로 두 열은 완전히 동일하다. next-prep 로그는 계산을 바꾸지 않고 문자열만 추가했다.

`ChunkedDataSource`의 전송 집계는 다음처럼 바뀌었다.

| 상황 | 변경 전 `close()` | 변경 후 `close()` |
|---|---|---|
| 정상 EOF | `noteTransfer(ok=true)` | 같음 |
| open 403/타임아웃 | `noteTransfer(ok=true)` + 실패 지점 `noteFailure` | 실패 지점 `noteFailure` 1회, `close()`는 성공 집계 안 함 |
| 범위 read 잘림/예외 | `noteTransfer(ok=true)` | `noteFailure` 1회, 성공 집계 안 함 |
| 중간 재시도 성공 | 성공 집계 | 성공 집계 |

---

## 6. 이번에 바꾼 파일과 테스트

- `shared/src/commonMain/kotlin/com/music/bitchord/playback/SyncSafetyPolicy.kt` — `nextPrepSyncSkipReason` 추가(순수 로그 사유 계산).
- `app/src/main/java/com/music/bitchord/playback/NextTrackPreparation.kt` — 최종 next-prep 로그에 `sync=skipped:<reason>` 추가. 단계 선택·계산은 그대로.
- `app/src/main/java/com/music/bitchord/playback/ChunkedDataSource.kt` — `transferFailed`/`failureNoted`로 실패 전송을 성공으로 집계하지 않음.
- `app/src/main/java/com/music/bitchord/playback/smart/HqAudioVerify.kt` — `shouldRetryMissingReference` 실제 호출.
- `shared/src/jvmTest/kotlin/com/music/bitchord/playback/SyncSafetyPolicyTest.kt` — 새 테스트 1건: `nextPrepSkipReasonNamesTheFirstBlockingCondition`.

검증:
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` — 통과.
- `./gradlew :shared:jvmTest --tests 'com.music.bitchord.playback.SyncSafetyPolicyTest' --console=plain -q` — 통과.

---

## 7. 남은 문제 (수정하지 않음)

1. `SyncSafety` 3스위치 기본 off와 `preferMusicOnly` 기본 off는 재생 핵심 경로라 그대로 뒀다. 실기기에서 기본값을 켰을 때의 가사/버전 동작을 확인해야 한다.
2. NextPrep `rowId` 키 캐시, `bestTier` 메인 조회, `DownloadedSyncCheck` 완료 기록, `lyricsForPrep` override 무시, HQ offset/rate 미독은 가사 싱크 핵심 경로라 문서에만 남긴다.
3. `HqVerifyPolicy.cooldownRemaining`과 `HqAutoDownloadPolicy.shouldStartNow`는 여전히 dead다. 연결하려면 TTL 저장소/호스트 상태를 먼저 정해야 한다.
4. `SettingsSheet` 검색 `onSubmit`, `scrobblingAvailable` 상수는 LOW로 남긴다.
