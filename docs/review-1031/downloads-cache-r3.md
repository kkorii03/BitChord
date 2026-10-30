# downloads-cache r3 — 이전 지적 4건 재검증·수정 (2026-10-30)

범위: `app/src/main/java/com/music/bitchord/download/{Downloader,DownloadService,HqLibraryUpgrade}.kt`
와 그 단위 테스트. 기준 브랜치 `task/fix-1030`, 작업 브랜치
`dsq/deep-downloads-1031-r3`. r2에서 고친 경로는 되돌리지 않았고, 이번
검토 지적 MEDIUM 3건을 코드로 확정해 고쳤으며 LOW 1건(회귀 테스트 부재)도
닫았다. 이전 차수의 미해결 HIGH/MEDIUM은 §5에 “재검증 필요”로 남겼다.

표시: **HIGH** 실제 사용에서 파일이 안 남거나 잘못 남음, **MEDIUM** 재시도·
저장 공간·배터리처럼 반복 사용에서 쌓이는 결함, **LOW** 회귀 위험.
`파일:줄`은 이번 수정 후다.

---

## 1. 검토 지적 4건 처리 결과

### 1-1. MEDIUM — “목표 등급 이상”이 정확한 검증 쌍 확인을 대체해 잘못된 파일을 교체 완료로 정산

- 지적: `HqLibraryUpgrade.kt:625-630`.
- 확정: 맞다. 수정 전 `atLeastAsGood`은 `hasCopyAtLeastAsGood`가
  저장 파일의 등급만 보았고, `wrongRecording` 플래그와 출처(source/track)
  를 보지 않았다. `AudioCache.hasCompleteLosslessCopy`만 있는 캐시 사본도
  “이미 목표 등급”으로 인정했다. 그래서 사용자가 같은 id를 같은/더 높은
  등급으로 다시 받았지만 그 파일이 다른 녹음이거나 라이브러리에 저장된 적
  없는 캐시 사본이어도, 대상이 계획에서 제거됐다.
- 사용자 영향: 고음질 보관함 교체가 “완료”로 사라지고 잘못된 녹음이 그대로
  남는다. 성적표의 “고음질 보관함 미착지 128”에 직접 기여한다.
- 흔한 정도: 자동 교체 대상 곡을 사용자가 직접 다시 받는 경우(배치 다운로드·
  수동 다운로드와 자동 패스가 겹칠 때) 흔하다.
- 수정: `hqSavedCopyAnswersTarget`(`HqLibraryUpgrade.kt:84-95`)를 추가해
  대체 경로를 좁혔다. 저장 메타데이터가 ① `wrongRecording`이 아니고
  ② 목표의 `sourceConfigId`·`trackId`와 일치하며 ③ 저장 등급이 목표 이상일
  때만 “이미 목표 등급”으로 인정한다. 캐시 전용 사본은 메타데이터가 없으므로
  답하지 않는다. `onDownloadSettled`는 이 함수를 쓴다
  (`HqLibraryUpgrade.kt:648-651`). 자동 대상 적격성의
  `hasCopyAtLeastAsGood`도 `wrongRecording` 파일을 목표 등급으로 세지 않도록
  막았다(`HqLibraryUpgrade.kt:211-216`).
- 회귀 테스트: `HqReplacementLandedTest` 5개 추가
  - `the same verified recording on a higher rung answers the target`
  - `a wrong recording at the target rung does not answer it`
  - `an ordinary download without provenance does not answer the target`
  - `a different verified source does not answer the target`
  - `a cache only copy without saved metadata does not answer the target`

### 1-2. MEDIUM — 1바이트 range probe가 200+부분 Content-Length를 전체 길이로 믿어 잘린 파일 커밋

- 지적: `Downloader.kt:321`.
- 확정: 맞다. 수정 전에는 probe 응답이 200이고 `Content-Length > 1`이면
  그 값을 전체 길이로 인정했다. 캐시 프록시가 `bytes=0-0`에 200과 고정
  64KiB 슬라이스를 주면 `total=65536`이 되고, `fetch`는 그 길이에서 성공으로
  끝나 잘린 파일을 커밋했다.
- 사용자 영향: “저장됨” 후 곡이 중간에 끊기고 재생·무손실 검증이 실패한다.
- 흔한 정도: Range를 흉내 내는 캐시/프록시 계층에서 발생한다. 일반 회선에서는
  드물지만 프록시 사용자에게는 전곡 단위다.
- 수정: 200 probe는 어떤 `Content-Length`든 모호하므로 항상 Range 없는
  `measureWhole`로 다시 측정한다(`Downloader.kt:298-318`). 본문은 읽지 않고
  헤더만 닫는다. `PROBE_BYTES` 상수는 더 이상 쓰지 않아 제거했다.
- 회귀 테스트: `DownloaderRangeTest`
  - `a partial 200 probe is measured again without a range`
  - 기존 `a one byte 200 range probe is not mistaken for the whole track` 유지

### 1-3. MEDIUM — drain 종료 경계에서 새 start가 오면 foreground 알림 없이 새 drain이 남음

- 지적: `DownloadService.kt:149-157,214-223`.
- 확정: 맞다. 수정 전 `shutdown(stopWork=false, stopId)`는 `stopSelf(stopId)`
  보다 먼저 `notifier`를 취소하고 `drain`을 null로 만든 뒤
  `stopForeground(REMOVE)`를 호출했다. `stopIdAfter`가 id를 고른 직후 새
  start가 도착하면 `stopSelf(id)`는 정지하지 않지만, 이미 알림과 drain 참조는
  사라져 새 drain이 foreground 알림 없이 돌았다.
- 사용자 영향: Android 14+에서 dataSync FGS가 알림 없이 동작하거나, 다음
  start가 서비스를 다시 승격할 때까지 다운로드가 불안정해진다. “서비스 시작
  실패 9”와 경계 상황에서 겹친다.
- 흔한 정도: drain 종료와 enqueue가 겹치는 경계 race라 드물지만, 배치가
  끝나는 순간 추가하면 걸릴 수 있다.
- 수정: `StartIdLedger.stopConfirmed`(`DownloadService.kt:459-463`)가
  최신 id를 다시 확인하고 `stopSelfResult`의 반환값을 본다. 정지가 확인된
  경우에만 `notifier` 취소·`drain=null`·`stopForeground(REMOVE)`를 한다
  (`DownloadService.kt:214-247`). 거절되면 알림과 drain 참조를 유지하고
  `ensureDrain()`으로 새 start에 drain을 넘긴다.
- 회귀 테스트: `DownloadServiceStartIdTest` 3개 추가
  - `a stop with no newer start is confirmed before teardown`
  - `a start that arrives after the stop id is chosen cancels the stop`
  - `a refused stopSelfResult keeps the service alive`

### 1-4. LOW — 실제 HIGH 수정 경로(`retryActive`)에 회귀 테스트가 없음

- 지적: `HqLibraryUpgrade.kt:654-664`.
- 확정: 맞다. 기존 테스트는 순수 함수 `hqReplacementLanded`만 검증해
  `settle(success=false)`로 되돌아가도 통과했다.
- 수정: 상태 변경을 `releaseActiveKeepingPlan`(`HqLibraryUpgrade.kt:678-686`)로
  분리하고 `retryActive`가 이를 사용한다. 이 함수는 `activeVideoId`만 비우고
  계획·실패 횟수는 건드리지 않는다.
- 회귀 테스트: 새 `HqRetryActiveTest`
  - `releasing an unpaired download keeps the target and spends no retry`
    — 대상이 계획에 남고 `failures`가 비어 있음을 단언
  - `releasing a target that is not active changes nothing`

---

## 2. 흔한 상황별 끝까지 동작하는가 (이번 수정 반영)

| 상황 | 진입점 → 처리 → 결과 | 판정 |
|---|---|---|
| 일반 곡 다운로드 | `Downloads.enqueue` → `DownloadService` 4 worker → `prepare` → `Downloader.fetch` 범위 GET → `DownloadStore.Pending` → 태그 → `commit` → `remember` | 정상 |
| 커버 영상 | `prepare`가 `songVersion`을 거절 → 원본 커버 행·파일명·태그 유지 | 정상 |
| 비디오 노래(MV) | 카탈로그 곡 버전으로 교체, `remember`가 MV id와 곡 id를 같은 파일에 기록 | 정상 |
| MV 소리 재다운로드 | `scanForMvSoundSuspects` → `MvSoundRedownload` → `mvSoundRepair` → `prepare` 실패 시 기존 파일 유지 | 정상, foreground 이벤트에서만 스케줄 |
| 셔플 | 큐는 재생 순서와 무관, `takeNext`가 ask 순서로 소비 | 정상 |
| 앱 재시작 | `pending`/`running`은 메모리만, pending MediaStore row 복구 없음 | **결함(HIGH, 이전 차수 미해결)** §5-2 |
| 화면 꺼짐·백그라운드 | 이미 시작한 FGS는 계속. 새 enqueue/자동 패스/MV 복구는 `AppForeground.isForeground`가 아니면 대기 | **결함(HIGH, 이전 차수 미해결)** §5-1 |
| 프록시 켬 | 전체 200은 `position`만큼 skip 후 `total`까지만 기록. 200 probe는 Range 없이 재측정. 부분 200 fragment를 전체 길이로 믿지 않음 | **이번에 고침** §1-2 |
| 다운로드된 곡 재생 | `savedUri`/`verifiedSavedUri`가 존재 확인 후 `file://`/`content://` 직접 재생 | 정상 |
| 느린/끊긴 망 | 2MB 범위 반복, 0바이트 range는 stall 실패, 403/404/410은 1회 재해결 | 정상, 전체 timeout은 HTTP client 의존 |
| Android 14+ | manifest `dataSync` + `FOREGROUND_SERVICE_DATA_SYNC`; 시작 실패는 실패 행 | 정상, 백그라운드 예외 미활용 §5-1 |
| 반쪽 파일 | MediaStore `IS_PENDING`, 파일 경로 `.part`→rename, 실패·취소 `abort`; 24h 지난 `.part`만 begin에서 청소 | 정상 |
| 캐시 정리 | `DynamicLruCacheEvictor` LRU + head 보호, 설정 변경 즉시 `applyNow` | 정상, 저장고갈 검사 없음 §5-3 |
| 고음질 교체 정산 | 목표 쌍이 없으면 목표 등급 이상이라도 출처·`wrongRecording`을 확인. 아니면 계획 유지·재시도 예산 미소모 | **이번에 고침** §1-1 |
| drain 종료 직후 새 enqueue | `stopSelfResult(id)`가 정지 확인을 반환할 때만 알림·drain 해체, 거절 시 새 drain에 인계 | **이번에 고침** §1-3 |

조용히 건너뛰는 경로: `HqLibraryUpgrade.runOne`의
`if (!AppForeground.isForeground) return`(§5-1)와
`DownloadService.work`의 idle 반환은 의도된 대기이며 로그가 있다. 새 수정
경로에는 조건 거짓으로 조용히 return하는 곳이 없다(`retryActive`는 로그 후
재시도).

---

## 3. 성적표 숫자를 코드로 설명

- **다운로드 실패 140**: 200 응답을 전체 파일로 처리하는 경로는 r2에서
  닫혔지만, 200 probe의 부분 `Content-Length`를 전체 길이로 믿는 경로가 남아
  “잘린 파일을 성공으로 커밋”했다. 이번에 그 길이 측정을 Range 없는 재측정으로
  바꿔 잘린 성공을 실패/정상 중 하나로 확정한다. 남은 주 원인은 소스 탐색
  상한 85초, 고음질 검증 거절, 저장 공간 고갈이다.
- **고음질 보관함 미착지 128**: r2에서 “검증 쌍 없는 성공”을 계획 유지로
  바꿨지만, “목표 등급 이상” 예외가 `wrongRecording`·캐시 사본까지 통과시켜
  대상을 제거했다. 이번에 그 예외를 같은 검증 출처·비-wrong 파일로 좁혀,
  잘못된 파일이 교체 완료로 사라지는 경로를 닫았다. 자동 패스가 foreground
  에서만 돌고 한 번에 한 곡인 점, 24h 쿨다운은 여전히 대상 수를 줄인다(§5).
- **서비스 시작 실패 9**: `Downloads.kt`의 `AppForeground.isForeground` 조건과
  `startForegroundService` 거절이 주 원인이다(§5-1). 이번 수정은 drain 종료
  경계에서 새 start의 FGS 알림이 사라지던 경로를 닫았지만(§1-3), 백그라운드
  에서 시작 자체를 넓히지는 않았다.
- **다음 곡 싱크 준비 29% / 무손실 확인 후 실제 무손실 재생 4% / 자동 재생
  보충 24% / 사용자 조작 없는 백그라운드 시작 11회**: 다운로드 파일은 로컬
  파일로 재생되어 “확인 후 교체” 사슬을 타지 않고, 자동 패스·MV 복구는
  foreground 이벤트 없이는 시작하지 않는다. 이번 수정은 잘못된 파일이 교체
  완료로 사라지던 경로와 drain 종료 시 알림이 사라지던 경로를 줄여, 실제
  무손실 파일이 남을 확률을 높인다. 백그라운드 시작 조건(§5-1)은 여전히
  직접 원인이다.

---

## 4. 조건 변경 전후 진리표

### 4-1. 고음질 정산의 “목표 등급 이상” 예외

| 다운로드 성공 | 검증 쌍 일치 | 목표 등급 이상 | 출처 일치 | wrongRecording | 기존 | 새 |
|---|---|---|---|---|---|---|
| 예 | 예 | — | — | — | 성공 정산 | 성공 정산 |
| 예 | 아니오 | 예 | 예 | 아니오 | 성공 정산 | 성공 정산 |
| 예 | 아니오 | 예 | 아니오(일반 다운로드) | 아니오 | **성공 정산 → 대상 제거** | 대상 유지·재시도 |
| 예 | 아니오 | 예 | 예 | 예 | **성공 정산 → 대상 제거** | 대상 유지·재시도 |
| 예 | 아니오 | 예 | 메타 없음(캐시만) | — | **성공 정산 → 대상 제거** | 대상 유지·재시도 |
| 예 | 아니오 | 아니오 | 예/아니오 | 예/아니오 | 대상 유지·재시도 | 대상 유지·재시도 |
| 아니오 | — | — | — | — | 실패 정산 | 실패 정산 |

기존의 모든 항(성공, 검증 쌍, 목표 등급)을 유지하고, “검증 쌍 없음 +
목표 등급 이상”에 출처 일치·비-wrong 조건을 추가했다.

### 4-2. 200 probe 길이 측정

| probe 응답 | Content-Length | 기존 | 새 |
|---|---|---|---|
| 206 + Content-Range | — | Content-Range의 전체 길이 | 같음 |
| 200 | 1(probe만) | Range 없이 재측정 | Range 없이 재측정 |
| 200 | 64KiB(고정 슬라이스) | **64KiB를 전체로 인정 → 잘림** | Range 없이 재측정 |
| 200 | 전체 파일 길이 | 그 값 인정 | Range 없이 재측정(헤더만) |
| 200 아님 | — | null | null |

### 4-3. drain 종료

| stop id 선택 후 새 start | stopSelfResult | 기존 | 새 |
|---|---|---|---|
| 없음 | true(정지) | 알림 해체 후 정지 | 정지 확인 후 알림 해체 |
| 있음 | false(정지 거절) | **알림·drain 먼저 해체 → 새 drain 알림 없음** | 알림·drain 유지, 새 drain 인계 |
| finish 시 이미 새 start | — | 새 drain 시작 | 새 drain 시작 |

기존 항(활성 drain, 큐 존재, 최신 start id)을 유지하고 “정지 확인”만 추가했다.

---

## 5. 남긴 HIGH/MEDIUM (이유와 필요한 작업)

### 5-1. HIGH — 재생 중/화면 꺼짐 백그라운드에서 서비스 시작 안 함 **(재검증 필요)**

- 근거: `Downloads.kt`의 `AppForeground.isForeground` 조건,
  `MvSoundRedownload.kt`, `HqLibraryUpgrade.kt`의 `runOne`/`enqueue`.
- 영향: 재생 FGS가 살아 있어도 창이 없으면 자동 패스·MV 복구·좋아요 자동
  다운로드가 앱을 다시 열 때까지 멈춘다. “사용자 조작 없는 백그라운드 시작
  11회”의 직접 원인이다.
- 이유: `PlaybackService`의 실제 FGS/타임아웃 상태를 노출해야 하고 업스트림
  재생 코드 수정이 필요하다. 잘못 넓히면 Android 12+ 예외로 앱이 죽는다.
- 필요한 작업: `PlaybackService`가 dataSync 예외 FGS 생존을 `AppForeground`에
  알리고, 시작 조건을 `isForeground || exemption`으로 넓힌다. 실패는 기존처럼
  실패 행으로 남긴다.

### 5-2. HIGH — 앱 재시작 시 대기/진행 다운로드·pending row 복구 없음 **(재검증 필요)**

- 근거: `Downloads`의 `pending`/`running`은 메모리 필드, `DownloadSession`도
  비영속. `DownloadStore.begin`이 만든 `IS_PENDING=1` 행은 프로세스 사망 후
  아무도 정리하지 않는다.
- 영향: OS가 다운로드 중 프로세스를 죽이면 대기 목록이 사라지고, 보이지 않는
  pending MediaStore 행이 저장 공간을 차지한다.
- 이유: 큐 스냅샷·복구·pending row sweep은 별도 영속 설계와 동시성 검증이
  필요하다.
- 필요한 작업: 큐 스냅샷을 디스크에 저장하고 foreground 이벤트에서 재개,
  시작 시 `Music/BitChord`의 `IS_PENDING=1` 행 정리.

### 5-3. MEDIUM — 저장 공간 선검사·ENOSPC 문구 없음 **(재검증 필요)**

- 근거: `DownloadStore.begin`과 `Downloader.fetch`에 `usableSpace` 검사가
  없고 `FriendlyError`도 ENOSPC를 구분하지 않는다.
- 영향: 공간 부족이 “문제가 발생했습니다”로만 보이고, insert/openStream/write
  중간에 실패한다.
- 이유: `total`은 `Downloader`, 볼륨은 `DownloadStore`에서 알아 두 계층 사이
  길이 전달 설계가 먼저 필요하다. 잘못된 볼륨 검사는 여유가 있는데도 거절한다.
- 필요한 작업: 측정한 `total`을 `transfer`에 넘겨 대상 볼륨 `usableSpace`와
  비교하고, 부족하면 formal 한국어 문구로 실패 행 생성.

### 5-4. MEDIUM — MediaStore 교체 시 같은 이름이 `(1)`로 개명 **(재검증 필요)**

- 근거: `Downloads.transfer`의 교체 이름 선택과 `DownloadStore.begin`의 같은
  DISPLAY_NAME insert. MediaStore가 중복 이름을 `… (1).ext`로 바꾸고
  `remember`가 옛 행을 지운다.
- 영향: 고음질 교체마다 파일명이 오가고 레코드 uri가 새 이름을 가리켜 다음
  adoption/검색이 이름으로 찾지 못한다.
- 이유: “옛 파일을 먼저 지우지 않는다”는 안전 규칙과 충돌하지 않게 임시
  이름→커밋→옛 행 삭제→최종 이름 update 순서를 설계해야 한다.
- 필요한 작업: 교체용 새 행은 임시 고유 이름으로 만들고, 커밋 후 옛 행을 지운
  뒤 DISPLAY_NAME을 최종 이름으로 update.

### 5-5. MEDIUM — 재시도가 업그레이드 의도를 잃음 **(재검증 필요)**

- 근거: `DownloadSession.Item`은 `videoId/song/progress/from/sequence`만 보관,
  재시도는 `Downloads.enqueue(context, item.song, item.from)`.
  `forceReplace`, `minimumTier`, `quality`, `hq`가 사라진다.
- 영향: 교체 실패 후 재시도가 기존 저음질 파일을 채택해 “성공”으로 보이거나
  검증되지 않은 검색을 다시 한다.
- 이유: 세션 모델과 UI 재시도 경로를 함께 바꿔야 하고, 자동 패스 실패는
  세션에 없어 영향이 작다.
- 필요한 작업: `DownloadSession.Item`에 재시도 계획을 담거나 실패 작업의
  원래 진입점 콜백을 보관.

### 5-6. MEDIUM — 배치마다 곡 수만큼 `startForegroundService` **(재검증 필요)**

- 근거: `Downloads.enqueue`는 중복 여부와 무관하게 곡마다 start를 호출한다.
  372곡 배치면 372개의 start intent가 들어와 `promote()`와 알림 빌드를 반복한다.
- 영향: 기능 실패는 아니지만 저사양 기기에서 jank·배터리 낭비.
- 이유: 서비스 생존 상태(`serviceRequested`)와 종료 시 자체 재시작 안전망을
  함께 넣어야 하고, 이번 §1-3의 drain 재시작과 맞물려 검증이 필요하다.
- 필요한 작업: 서비스가 살아 있으면 enqueue가 start를 생략하고, 서비스 종료
  시 pending이 있으면 스스로 재시작.

### 5-7. LOW(이전 차수) — `staleParts`가 활성 전송의 `.part`도 24시간 뒤 삭제 가능 **(재검증 필요)**

- 24시간은 실제 전송보다 훨씬 긴 안전 여유이고, 활성 part 집합을 서비스·스토어에
  걸쳐 관리하려면 수명 주기 변경이 필요하다. 이번 범위를 넘는다.

---

## 6. 테스트

새/확장 테스트(모두 app JVM 단위 테스트):
- `com.music.bitchord.download.DownloaderRangeTest` — 1개 추가(총 5개)
- `com.music.bitchord.download.DownloadServiceStartIdTest` — 3개 추가(총 6개)
- `com.music.bitchord.download.HqReplacementLandedTest` — 5개 추가(총 10개)
- `com.music.bitchord.download.HqRetryActiveTest` — 신규 2개

수정 전 실패 확인:
- 부분 200 probe: 수정 전 `total=65536`으로 `written==65536`이 되어
  `body.size(70536)` 단언이 실패한다.
- 잘못된/캐시 사본 정산: 수정 전 `hqSavedCopyAnswersTarget`에 해당하는
  판정이 `wrongRecording`·출처를 보지 않아 true가 된다.
- stop 확인: 수정 전 `stopConfirmed`가 없어 컴파일되지 않는다.
- retryActive: 수정 전 계획 유지·실패 미기록을 단언할 테스트가 없었다.

실행 결과(2026-10-30, JDK 21):
```
JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 ./gradlew :app:testProdReleaseUnitTest \
  --tests 'com.music.bitchord.download.*' --console=plain -q
# EXIT:0 (download 패키지 전체 통과)
./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q
# 통과
```
기본 JDK 17에서는 InnerTubeX 의존성이 class file 65(Java 21)라 테스트가
초기화에 실패하므로 JDK 21로 실행했다.

---

## 7. 수정 파일

- `app/src/main/java/com/music/bitchord/download/Downloader.kt`
- `app/src/main/java/com/music/bitchord/download/DownloadService.kt`
- `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt`
- `app/src/test/java/com/music/bitchord/download/DownloaderRangeTest.kt`
- `app/src/test/java/com/music/bitchord/download/DownloadServiceStartIdTest.kt`
- `app/src/test/java/com/music/bitchord/download/HqReplacementLandedTest.kt`
- `app/src/test/java/com/music/bitchord/download/HqRetryActiveTest.kt` (신규)
- `docs/review-1031/downloads-cache-r3.md`
