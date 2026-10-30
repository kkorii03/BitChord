# integration-downloads-hq — 다운로드·캐시·고음질 영역 통합 검토

- 기준: `89f9e3b8..HEAD` (현재 통합 트리). 실제 커밋 범위: `git log --oneline 89f9e3b8..HEAD -- <영역 파일>`.
- 범위: `Downloads`, `DownloadService`, `DownloadStore`, `DownloadSession`, `HqLibraryUpgrade`,
  `HqAudioVerify`, `HqVerificationStore`, `AudioCache`, `MvSoundRedownload`, `Backup`,
  `DownloadHealth`, `OfflinePackage`.
- 방법: 오늘 각 작업이 같은 함수·상태를 어떻게 바꿨는지 커밋별 diff를 읽고, 병합 뒤에도
  남은 이중 경로·취소/재시도 경합·계획 유실을 코드로 확인했다. 기기 검증은 하지 않았고
  고친 항목은 컴파일과 단위 테스트로 확인했다.

## 1. 결과 표

| # | 심각도 | 파일 | 증거 | 사용자 영향 | 상태 |
|---:|---|---|---|---|---|
| 1 | MEDIUM | `download/HqLibraryUpgrade.kt:575-618`, `download/Downloads.kt:2012` | `Downloads.run`의 `finally`가 `completed=false`만 넘겨 취소도 일반 실패로 정산했다. `onDownloadSettled`는 그 답으로 `settle(success=false)`를 호출해 계획에서 대상을 제거하고 실패 횟수를 +1 했다. | 알림의 “전체 취소”나 서비스 종료로 자동 HQ 다운로드가 멈추면, 검증된 쌍이 멀쩡한데도 대상이 계획에서 사라지고 24시간 쿨다운이 시작된다. 두 번이면 자동 재시도가 끝난다. | **고침** |
| 2 | MEDIUM | `download/HqLibraryUpgrade.kt:285-337` | `onBackground`가 지연 재시도 WorkManager를 취소한다. 이후 `onForeground`의 `hasRetryableWork`는 쿨다운 중인 계획 항목을 “지금 실행 가능한 대상 없음”으로 보고 worker를 넣지 않았다. 그 worker만이 `nextParkedWaitLocked`로 쿨다운 만료를 다시 예약한다. | 429/일시 실패로 쉬던 곡이 앱을 백그라운드에 보냈다 오면, 쿨다운이 끝나도 다음 재생/포그라운드 전환 전까지(또는 계속 앱을 켜 두면 영원히) 재시도되지 않는다. | **고침** |
| 3 | LOW(병합 실수) | `data/stats/Backup.kt:251-260` | 리뷰-perf 작업의 `readBackupText(InputStream)`과 리뷰-data 작업의 `InputStream.readBackupText()`가 각각 전체를 읽는 구현으로 남아 있었다. 상한 상수도 `MAX_BACKUP_BYTES`와 `MAX_IMPORT_BYTES`로 두 벌이었다. | 지금은 두 구현이 같은 32MB지만, 한쪽 상한/문구만 바뀌면 가져오기와 내보내기가 서로 다른 파일을 허용한다. 같은 일을 두 번 하는 경로. | **고침(중복 제거)** |

## 2. 수정 내용

### 2.1 취소를 실패로 세지 않는다 — `HqLibraryUpgrade.kt`, `Downloads.kt`

- `Downloads.run`의 `finally`가 `cancelled`를 `HqLibraryUpgrade.onDownloadSettled`에 넘긴다.
- 취소면 `clearActiveTarget(videoId)`만 호출한다. 계획 항목, `done`/`total`, `failures`는
  그대로 두고 `activeVideoId`만 비운다. 다음 포그라운드/재생 이벤트가 계획을 다시 고른다.
- 계획을 지우지 않으므로 “전체 취소” 한 번이 검증된 쌍의 재시도 예산을 소모하지 않는다.

취소 정산 진리표(수정 전 → 수정 후):

| 다운로드 결과 | cancelled | 수정 전 계획 항목 | 수정 전 실패 횟수 | 수정 후 계획 항목 | 수정 후 실패 횟수 |
|---|---|---|---|---|---|
| 성공 + hqApplied | false | 제거 | 유지/초기화 | 제거 | 유지/초기화 |
| 성공 + hqApplied 아님 | false | 제거(일시 재시도) | 증가 | 제거(일시 재시도) | 증가 |
| 실패(영구) | false | 제거 | +1 | 제거 | +1 |
| 일시 실패(429/망) | false | 유지(대기) | 유지 | 유지(대기) | 유지 |
| 취소 | **true** | **제거(버그)** | **+1(버그)** | 유지 | 유지 |

### 2.2 백그라운드에서 취소된 쿨다운 기상을 포그라운드에서 다시 건다 — `HqLibraryUpgrade.kt`

- `hasRetryableWork`가 실행 가능한 대상이 없어도 **계획이 비어 있지 않으면 true**를 돌려준다.
  `shouldResumePlan(planIsNotEmpty, hasRunnableTarget)`로 규칙을 분리했다.
- worker는 실행할 대상이 없으면 `nextParkedWaitLocked`로 가장 이른 쿨다운 만료를 WorkManager에
  맡기고 끝난다. 폴링이나 sleep은 추가하지 않는다.

계획 재개 진리표(수정 전 → 수정 후):

| 계획 존재 | 지금 실행 가능한 대상 | 수정 전 worker 예약 | 수정 후 worker 예약 |
|---|---|---|---|
| 아니오 | 아니오 | 아니오 | 아니오 |
| 아니오 | 예 | 예(판정 저장소에서) | 예 |
| 예(전부 쿨다운 중) | 아니오 | **아니오(기상 유실, 버그)** | 예(지연 기상 재예약) |
| 예 | 예 | 예 | 예 |

### 2.3 백업 읽기 경로 하나로 합치기 — `Backup.kt`

- `InputStream.readBackupText(maxBytes)`가 직접 읽던 루프를 지우고
  `readBackupText(input, maxBytes)`에 위임한다. 상한은 `MAX_BACKUP_BYTES` 하나만 두고
  `MAX_IMPORT_BYTES`는 같은 값의 호환 별칭으로 남긴다.
- 가져오기(`importFrom`)와 테스트가 쓰는 두 호출 형태가 같은 구현·같은 오류 문구를 쓴다.

백업 읽기 경로(수정 전 → 수정 후):

| 호출 | 수정 전 구현 | 수정 후 구현 |
|---|---|---|
| `Backup.readBackupText(input, max)` | SettingsTransfer 기반 루프 | 동일 |
| `with(Backup) { input.readBackupText(max) }` | 별도 로컬 루프 | 같은 함수에 위임 |
| 상한 상수 | 32MB 두 벌 | 32MB 한 벌(+별칭) |

## 3. 테스트

새 테스트: `app/src/test/java/com/music/bitchord/download/HqLibraryUpgradeCancellationTest.kt`

- `cancelling the active target keeps the plan and its retry budget`
  — 실제 `onDownloadSettled(..., cancelled = true)` 경로로 계획 유지·실패 미기록·active 해제를 확인.
  수정 전에는 `success=false, failure=null`로 정산되어 계획이 제거되고 실패가 +1 되므로 실패한다.
- `a late cancellation for another id does not clear this target`
- `a parked plan still resumes a worker for its cooldown wake`
  — `shouldResumePlan`의 진리표.

실행 결과:

```bash
./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q
# 성공

./gradlew :app:testProdReleaseUnitTest \
  --tests 'com.music.bitchord.download.*' \
  --tests 'com.music.bitchord.playback.smart.Hq*' \
  --tests 'com.music.bitchord.data.stats.Backup*' --console=plain -q
# 성공
```

전체 `:app:testProdReleaseUnitTest`는 이 영역과 무관한 기존 실패 22건
(`LocaleStringsTest` 16, `SourceFormatsTest` 3, `BluetoothAudioTrackerTest`, `ProviderLyricsTest`,
`ArtistNameMatchTest` 각 1)이 남아 있다. 이번 변경은 위 클래스들을 건드리지 않았다.
`shared:jvmTest`는 통과했다.

## 4. 남긴 문제

| 심각도 | 파일:줄 | 내용 | 이유 |
|---|---|---|---|
| LOW | `download/HqLibraryUpgrade.kt` `publishLocked` | `waiting` 표시가 계획의 첫 항목만 본다. 첫 항목이 실행 가능하고 뒤 항목이 쿨다운이면 “대기 중”이 아니다. | 화면 표시만의 문제이고 이번 상태 경합 수정과 별개. |
| LOW | `playback/AudioCache.kt` `hqHeadReady` | 곡마다 완료 객체를 넣고 지우지 않는 기존 누수. | 재생 핵심 경로이고 이번 병합 상호작용과 무관해 손대지 않았다. |
| MED | `download/DownloadService.kt` 종료 경합 | 마지막 worker가 빈 큐를 보고 반환하는 순간 enqueue가 들어오면 서비스가 내려가며 pending이 남을 수 있다. | Service 생명주기 재현이 필요해 코드 추적만 했다. 다음 이벤트가 다시 시작한다. |
