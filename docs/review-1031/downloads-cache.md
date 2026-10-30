# downloads-cache — 다운로드·캐시 심층 검토 (2026-10-30)

범위: `app/src/main/java/com/music/bitchord/download/*`, `playback/AudioCache.kt`,
`playback/DynamicLruCacheEvictor.kt`, `shared/.../data/{net,sources,lyrics,cover}` 중
다운로드·오프라인 재생에 닿는 순수 정책. 기준 커밋 `2ccab48` (task/fix-1030),
작업 브랜치 `dsq/deep-downloads-1031`. 코드 수정은 이 문서의 HIGH/MEDIUM 중
아래 “이번에 고침” 항목만 했다. 업스트림 v1.8.1 코드 삭제·분할은 하지 않았다.

표시: **HIGH**는 실제 사용에서 파일이 안 남거나 잘못 남는 결함, **MEDIUM**은
재시도·저장 공간·배터리처럼 반복 사용에서 쌓이는 결함. `파일:줄`은 이번 수정
후의 현재 줄이다.

---

## 1. 흔한 상황별 끝까지 동작하는가

| 상황 | 진입점 → 처리 → 결과 | 판정 |
|---|---|---|
| 일반 곡 다운로드 | `Downloads.enqueue` → `DownloadService` 4 worker → `prepare`(소스/YouTube) → `Downloader.fetch` 범위 GET → `DownloadStore.Pending` → 태그 → `commit` → `remember` | 정상. 단 저장 공간 선검사 없음(§4-3) |
| 커버 영상 다운로드 | `prepare`가 `songVersion`을 시도하지만 커버는 `mayInstallSongVersion`에서 거절 → 원본 커버 행 유지 → 파일명·메타데이터도 커버 | 정상(커버 보존) |
| 비디오 노래(MV) 다운로드 | `prepare`가 카탈로그 곡 버전으로 교체 → `remember`가 MV id와 곡 id를 같은 파일에 기록 | 정상 |
| MV 소리로 잘못 받은 파일 재다운로드 | `scanForMvSoundSuspects` → `MvSoundRedownload` → `mvSoundRepair` enqueue → `prepare`가 다른 업로드 실패 시 실패 유지 | 정상. 단 앱이 foreground일 때만 스케줄(§4-1) |
| 셔플 | 다운로드 큐는 재생 순서와 무관, `takeNext`가 ask 순서로 소비 | 정상 |
| 앱 재시작 | `pending`/`running`은 메모리만; `onStopped`가 running만 비움. 진행 중·대기 트랙은 복구되지 않음 | **결함(HIGH, 미수정)** §4-2 |
| 화면 꺼짐·백그라운드 | 이미 시작한 `DownloadService`는 dataSync FGS로 계속. 새 enqueue/자동 패스/MV 복구는 `AppForeground.isForeground`가 아니면 서비스를 시작하지 않고 대기 | **결함(HIGH, 미수정)** §4-1 |
| 프록시 켬 | 프록시가 Range를 무시하고 200+전체 파일을 주면 `contentLength`가 길이를 못 읽고, 중간 range 200은 파일 앞부분을 중복 기록 | **이번에 고침(HIGH)** §3-1 |
| 다운로드된 곡 재생 | `savedUri`/`verifiedSavedUri`가 파일 존재 확인 → `file://`/`content://`는 `FileDataSource`로 바로 재생 | 정상. `content://` 사라짐은 `forgetMissing`이 복구 |
| 느린/끊긴 망 | 2MB 범위 반복, 0바이트 range는 stall 실패, 403/404/410은 1회 재해결 | 정상. 전체 다운로드 timeout은 없음(HTTP client timeout에 의존) |
| Android 14+ | manifest `dataSync` + `FOREGROUND_SERVICE_DATA_SYNC` 존재, `startForegroundService` 실패는 잡아 실패 행으로 | 정상. 백그라운드 예외 상황 미활용(§4-1) |
| 반쪽 파일 | MediaStore는 `IS_PENDING`, 파일 경로는 `.part`→rename, 실패·취소는 `abort` | 정상. 프로세스 사망 `.part`는 이번에 24h 후 청소, MediaStore pending row는 미수정(§4-2) |
| 캐시 정리 | `DynamicLruCacheEvictor` LRU + head 보호, 설정 변경 즉시 `applyNow`, `dropForeignEntries` 인덱스 | 정상. 무제한+저장공간 고갈은 미검사(§4-3) |

---

## 2. 성적표 숫자를 코드로 설명

- **다운로드 실패 140**: `Downloader.contentLength`가 `clen` 또는 `Content-Range`만
  읽어, 프록시/캐시가 Range를 무시하고 200을 주면 첫 바이트 전에
  `길이 정보 없음`으로 실패했다. 또 중간 range가 200이면 앞부분을 중복 기록해
  “성공했지만 재생 불가”가 됐다. 이번 수정으로 이 두 경로는 사라진다.
  남은 실패는 소스 탐색 85초 초과(`Downloads.kt:2620` 부근), 고음질 검증 거절,
  저장 공간 고갈이 주 원인이다.
- **고음질 보관함 미착지 128**: `HqLibraryUpgrade`는 한 번에 한 곡만 큐에 넣고,
  실패하면 `settle`이 계획에서 그 곡을 제거한다. `AutomaticRetryPolicy`는
  2회 실패 후 24시간 동안 자동 재시도를 막는다. 게다가 사용자가 같은 id를
  먼저 다운로드해 큐가 중복 요청을 거절하면, 그 사용자 다운로드의 성공이
  고음질 교체 성공으로 오인됐다(이번에 provenance 확인 추가). 검증 자체가
  드물게 성공하는 것도 대상 수를 작게 만든다.
- **서비스 시작 실패 9**: `Downloads.enqueue`는 `AppForeground.isForeground`일
  때만 `startForegroundService`를 부르고, 실패하면 `fail` 행을 만든다.
  foreground 플래그가 true인데 Android가 거절하는 전환 경계(액티비티가 막
  백그라운드로 간 순간)가 9회로 보인다. 반대로 화면 꺼짐·재생 중 백그라운드에서는
  아예 시도하지 않아 “실패”로도 집계되지 않고 대기만 한다(§4-1).
- **다음 곡 싱크 준비 29% / 무손실 확인 후 실제 무손실 재생 4% / 자동 재생 보충 24%**:
  이 영역에서 직접 원인은 다운로드된 곡이 로컬 파일로 재생되어 스트림 검증·교체
  사슬을 타지 않는 점, 그리고 자동 패스가 foreground 이벤트 없이는 시작하지 않는
  점이다. 다운로드 파일 자체는 무손실이어도 “확인 후 교체” 지표에는 잡히지 않는다.
  이번 작업은 그 사슬을 막는 다운로드 실패·교체 오인을 줄이는 데 집중했다.

---

## 3. 이번에 고친 결함

### 3-1. HIGH — Range를 무시하는 프록시에서 다운로드가 실패하거나 파일이 깨짐

- 근거(수정 전): `Downloader.contentLength`는 `clen`이 없으면
  `Content-Range`만 읽었다. `Downloader.fetch`는 응답 코드를 확인하지 않고
  `length`만큼만 읽은 뒤 다음 range를 요청했다. `fetchDirect`의 주석은 같은
  위험을 이미 설명하고 있었지만 `fetch`에는 적용되지 않았다.
- 증거: 프록시가 `Range: bytes=0-2097151`에 `200`+전체 파일을 주면 첫 2MB만
  기록하고, 다음 요청에서 다시 파일 앞부분을 기록해 길이는 맞지만 내용이
  뒤섞인다. `clen`이 없는 URL은 `Content-Length`가 있어도 `null`로 실패한다.
- 사용자 영향: 프록시 사용자·캐시 계층이 있는 망에서 다운로드 실패 또는
  “저장됨” 후 재생 불가. 흔한 편은 아니지만 프록시 설정 사용자에게는 전곡 단위.
- 수정: `Content-Length` fallback, 200 응답은 전체 파일로 처리(이미 쓴
  `position`만큼 skip), 206은 `Content-Range` 시작 위치를 검증.
- 회귀 테스트: `app/src/test/java/com/music/bitchord/download/DownloaderRangeTest.kt`
  - `a 200 response with content length is a whole track, not an unknown length`
  - `a later range answered with 200 continues instead of duplicating the opening`

### 3-2. HIGH — 마지막 drain이 끝나는 순간의 시작 요청이 버려짐

- 근거(수정 전): `DownloadService.onStartCommand`가 `if (drain == null)`일 때만
  drain을 시작했다. 마지막 worker가 끝나 `drainQueue()`가 반환됐지만
  `shutdown`이 아직 `drain = null`로 만들기 전에 새 enqueue의
  `startForegroundService`가 들어오면, `drain`은 완료된 Job이라 non-null →
  새 drain을 만들지 않고 곧 서비스가 stopSelf했다.
- 사용자 영향: 배치가 끝나는 순간 추가한 곡이 “대기” 상태로 남고, 다음
  foreground 이벤트가 없으면 영원히 시작되지 않는다. 드물지만 한 번 걸리면
  사용자는 다운로드가 멈춘 것으로 본다.
- 수정: `DrainGate`로 활성 drain을 판정하고, drain 완료 시 `Downloads.busy()`를
  다시 확인해 큐가 있으면 새 drain을 시작. `drainGeneration`으로 늦게 시작한
  새 drain을 옛 drain이 stopSelf로 죽이지 못하게 했다.
- 회귀 테스트: `app/src/test/java/com/music/bitchord/download/DownloadDrainGateTest.kt`
  - `a second start while draining does not launch a second drain`
  - `a finishing drain restarts when work is still queued`
  - `a finishing drain stops when the queue is empty`

### 3-3. MEDIUM — 유휴 worker 4개가 100ms마다 폴링

- 근거(수정 전): `DownloadService.work()`가 `delay(IDLE_POLL_MS=100)`로 최대
  2초간 worker마다 20회, 4 worker면 80회 깨어났다. `docs/audit/audit-battery.md`에도
  같은 MEDIUM이 남아 있었다.
- 수정: `Downloads`에 `workVersion` StateFlow와 `awaitWorkChange`를 추가.
  `enqueue`/`onIdle`/`onStopped`가 버전을 올리고, worker는 enqueue·완료 이벤트로
  즉시 깨어난다. timeout은 배치 종료 유예 2초에만 쓰인다.
- 배터리: 유휴 중 주기 wake-up 없음. 알림 progress 갱신은 상태 emission 기반
  throttle(250ms)만 유지.
- 회귀 테스트: `app/src/test/java/com/music/bitchord/download/DownloadsWorkSignalTest.kt`
  - `one signal wakes every worker waiting on the same generation`
  - `a wait with no change returns null after its timeout`

### 3-4. MEDIUM — 프로세스 사망이 남긴 `.part` 파일이 영구히 쌓임

- 근거: `DownloadStore.begin`은 같은 이름의 `.part`만 지웠다. 다시 받지 않는
  곡의 반쪽 파일은 앱을 재시작해도 남아 저장 공간을 차지했다.
- 수정: 다운로드 시작 시 `staleParts(folder, now)`가 24시간 넘은 `.part`만
  지운다. 타이머가 아니라 begin 이벤트에서만 실행하고, 4 worker가 동시에
  쓰는 최신 part는 나이 조건으로 보호한다.
- 회귀 테스트: `app/src/test/java/com/music/bitchord/download/DownloadStoreStalePartTest.kt`
  - `only parts older than the live-transfer window are swept`

### 3-5. MEDIUM — MediaStore 행이 사라졌는데 다운로드 성공으로 기록

- 근거(수정 전): `DownloadStore.Pending.commit`이
  `contentResolver.update(IS_PENDING=0)`의 반환값을 무시했다. 사용자가 폴더를
  비우거나 store가 행을 회수하면 update는 0을 반환하지만, 그대로 uri를
  반환해 `remember`가 없는 파일을 기록했다.
- 수정: update 결과가 0이면 실패로 처리해 `abort` 경로로 보낸다.
- 회귀 테스트: `app/src/test/java/com/music/bitchord/download/DownloadStorePublishAppliedTest.kt`
  - `a row that was published is a success`
  - `a row that no longer exists is not a success`

### 3-6. MEDIUM — 사용자 다운로드 성공을 고음질 교체 성공으로 오인

- 근거(수정 전): `HqLibraryUpgrade.onDownloadSettled`는 `success`만 보고
  `settle(success=true)`했다. 같은 id가 이미 큐에 있으면 `enqueue`가 중복을
  거절하므로, 사용자의 일반 다운로드가 끝나도 고음질 대상이 “완료”로 제거됐다.
- 수정: 대상이 아직 계획에 있으면 `Downloads.hqApplied`로 검증된
  source/track/tier가 실제 기록에 있는지 확인하고, 없으면 실패로 정산한다.
- 회귀 테스트: `app/src/test/java/com/music/bitchord/download/HqReplacementLandedTest.kt`
  - `a successful download with the verified pair landed`
  - `a successful download without the verified pair did not land`
  - `a failed download never landed`

---

## 4. 고치지 못한 HIGH/MEDIUM (이유와 필요한 작업)

### 4-1. HIGH — 재생 중/화면 꺼짐 백그라운드에서 다운로드 서비스를 아예 시작하지 않음

- 근거: `Downloads.kt:455` `if (AppForeground.isForeground)`,
  `Downloads.kt:488` `onForeground`도 같은 조건, `MvSoundRedownload.kt:117,154`,
  `HqLibraryUpgrade.kt:249,635,1026`. `PlaybackActivity.active`는 재생/버퍼링
  여부만 알리고 FGS 생존 여부는 노출하지 않는다.
- 영향: 음악을 재생하며 화면을 끄면 Android 12+에서 “앱이 foreground service를
  실행 중” 예외로 다운로드 서비스를 시작할 수 있는데도, 이 앱은 창이 없다는
  이유로 시작하지 않는다. MV 소리 복구·좋아요 자동 다운로드·고음질 패스가
  사용자가 앱을 다시 열 때까지 멈춘다. 성적표의 “사용자 조작 없는 백그라운드
  시작 11회”가 낮은 직접 원인이다.
- 필요한 작업: `PlaybackService`가 실제 FGS 상태를 `AppForeground`(또는 별도
  객체)에 알리고, `isForeground || hasDataSyncExemptForegroundService`로
  시작 조건을 넓힌다. 일시정지 후 10분 FGS 타임아웃처럼 실제로 foreground가
  아닌 상태를 구분해야 하며, 실패는 기존처럼 실패 행으로 남겨야 한다.
  업스트림 `PlaybackService` 수정을 최소화해야 하므로 별도 작업이 안전하다.

### 4-2. HIGH — 앱 재시작 시 대기/진행 다운로드와 MediaStore pending row 복구 없음

- 근거: `Downloads`의 `pending`/`running`은 메모리 필드(`Downloads.kt:198` 부근),
  `takeNext`/`onStopped`(`Downloads.kt:1787,1821`)만 있고 디스크 복구가 없다.
  `DownloadSession`도 의도적으로 비영속. `DownloadStore.begin`이 만든
  `IS_PENDING=1` 행은 프로세스 사망 후 아무도 `IS_PENDING=0`이나 delete를
  하지 않는다.
- 영향: OS가 다운로드 중 프로세스를 죽이면 대기 300곡이 사라지고, 완료된
  파일만 남는다. pending MediaStore 행은 보이지 않지만 저장 공간을 차지한다.
- 필요한 작업: 큐 스냅샷을 디스크에 저장하고 시작 시 복구(자동 재개는 foreground
  이벤트에서), 그리고 시작 시 `Music/BitChord`의 `IS_PENDING=1` 행을 정리.
  후자는 다른 프로세스가 동시에 쓰지 않는다는 전제가 필요해 별도 테스트가 필요.

### 4-3. MEDIUM — 저장 공간 선검사 없음 / ENOSPC가 일반 오류 문구로 표시

- 근거: `DownloadStore.begin`(`:331`)과 `Downloader.fetch`(`:77`) 어디에도
  `usableSpace` 검사가 없다. `FriendlyError`는 ENOSPC를 별도 문구로 매핑하지
  않는다(`ui/FriendlyError.kt`).
- 영향: 공간이 없으면 insert/openStream/write 중간에 실패하고, 사용자는
  “문제가 발생했습니다”만 본다. 반쪽 파일은 abort되지만 원인을 알 수 없다.
- 필요한 작업: `total`을 안 뒤 대상 볼륨의 `usableSpace`와 비교하고, 부족하면
  한국어 formal 문구(예: 저장 공간이 부족합니다)로 실패 행을 만든다. 캐시
  무제한 설정도 같은 검사를 공유해야 한다.

### 4-4. MEDIUM — MediaStore 교체 시 같은 이름 행이 `(1)`로 자동 개명

- 근거: `Downloads.transfer`는 교체(`plan.hq != null || forceReplace`)에서
  `downloadFileName(..., replacing=true)`로 기존과 같은 이름을 고르고
  (`Downloads.kt:2216`), `DownloadStore.begin`은 같은 DISPLAY_NAME으로 새 행을
  insert한다(`DownloadStore.kt:356`). MediaStore는 중복 이름을 `… (1).ext`로
  바꾸고, `remember`가 나중에 옛 행을 지운다.
- 영향: 고음질 교체 때마다 파일명이 `(1)`과 원래 이름을 오가고, 레코드의
  `uri`는 새 이름을 가리키므로 다음 adoption/검색이 이름으로 찾지 못한다.
- 필요한 작업: 교체용 새 행은 임시 고유 이름으로 만들고, 커밋 후 옛 행을
  지운 뒤 DISPLAY_NAME을 최종 이름으로 update한다. “옛 파일을 먼저 지우지
  않는다”는 안전 규칙은 유지해야 한다.

### 4-5. MEDIUM — 다운로드 관리자 “재시도”가 업그레이드 의도를 잃음

- 근거: `DownloadSession.Item`은 `videoId/song/progress/from/sequence`만
  보관(`DownloadSession.kt:62`), 재시도는 `Downloads.enqueue(context, item.song,
  item.from)`(`DownloadManagerSheet.kt:296`). `HqLibraryUpgrade.requestUpgrade`가
  실패하면 `forceReplace`, `minimumTier`, `quality`, `hq`가 사라진다.
- 영향: 사용자가 고음질 교체 실패 후 재시도하면 기존 저음질 파일을 그대로
  채택해 “성공”으로 보이거나, 검증된 쌍이 아닌 검색을 다시 한다.
- 필요한 작업: `DownloadSession.Item`에 재시도 계획(quality/hq/forceReplace/
  minimumTier/mvSoundRepair)을 담거나, 실패한 작업의 원래 진입점을 콜백으로
  보관한다. 자동 패스 실패는 세션에 없으므로 영향 없음.

### 4-6. MEDIUM — 배치마다 곡 수만큼 `startForegroundService` 호출

- 근거: `Downloads.enqueue`는 중복 여부와 무관하게 곡마다 `startService`를
  호출한다(`Downloads.kt:455` 부근). 372곡 배치면 372개의 start intent가
  서비스로 들어와 `promote()`와 알림 빌드를 반복한다.
- 영향: 배치 시작 순간 main thread·notification 서비스 부하. 기능 실패는
  아니지만 저사양 기기에서 jank와 배터리 낭비.
- 필요한 작업: 서비스가 살아 있으면 enqueue가 start를 생략하도록
  `serviceRequested` 상태를 두고, 서비스 종료 시 pending이 있으면 스스로
  재시작하는 안전망을 함께 넣는다(§3-2의 drain 재시작과 함께).

---

## 5. 조건 변경 전후 진리표

### 5-1. `DownloadService` drain 시작/종료

| 시작 요청 | drain 활성 | 큐에 작업 있음 | 기존 | 새 |
|---|---|---|---|---|
| 있음 | 아니오 | 아니오 | 새 drain 시작 | 새 drain 시작 |
| 있음 | 예 | 예/아니오 | 무시 | 무시(worker가 pending을 직접 소비) |
| 있음 | 완료 직후 | 예 | **무시 → 서비스 종료, 작업 멈춤** | 완료 coroutine이 큐 확인 후 새 drain |
| 종료 시점 | — | 아니오 | stopSelf | stopSelf |
| 종료 시점 | — | 예 | stopSelf | 새 drain |

기존 조건의 모든 항(활성 여부, 큐 존재)을 유지하고 “완료 직후 + 큐 있음”만
새로 살렸다.

### 5-2. 고음질 정산

| 다운로드 성공 | 계획에 대상 있음 | provenance 일치 | 기존 | 새 |
|---|---|---|---|---|
| 예 | 예 | 예 | 성공 | 성공 |
| 예 | 예 | 아니오 | **성공(오인)** | 실패 |
| 예 | 아니오 | — | 성공 | 성공 |
| 아니오 | 예/아니오 | — | 실패 | 실패 |

### 5-3. 길이 측정

| `clen` | `Content-Range` | `Content-Length` | 기존 | 새 |
|---|---|---|---|---|
| >0 | — | — | 그 값 | 그 값 |
| 없음 | >0 | — | 그 값 | 그 값 |
| 없음 | 없음 | >0 | **null → 실패** | 그 값 |
| 없음 | 없음 | 없음 | null → 실패 | null → 실패 |

### 5-4. `.part` 청소

| 이름 `.part` | 나이 ≥ 24h | 기존 | 새 |
|---|---|---|---|
| 예 | 예 | 남음 | 삭제 |
| 예 | 아니오 | 남음(활성 가능) | 남음 |
| 아니오 | — | 남음 | 남음 |

---

## 6. 테스트

새 테스트(모두 app JVM 단위 테스트):
- `com.music.bitchord.download.DownloaderRangeTest` — 2개
- `com.music.bitchord.download.DownloadDrainGateTest` — 3개
- `com.music.bitchord.download.DownloadsWorkSignalTest` — 2개
- `com.music.bitchord.download.DownloadStoreStalePartTest` — 1개
- `com.music.bitchord.download.DownloadStorePublishAppliedTest` — 2개
- `com.music.bitchord.download.HqReplacementLandedTest` — 3개

실행 명령:
```
JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 ./gradlew :app:testProdReleaseUnitTest \
  --tests 'com.music.bitchord.download.DownloaderRangeTest' \
  --tests 'com.music.bitchord.download.DownloadDrainGateTest' \
  --tests 'com.music.bitchord.download.DownloadsWorkSignalTest' \
  --tests 'com.music.bitchord.download.DownloadStoreStalePartTest' \
  --tests 'com.music.bitchord.download.DownloadStorePublishAppliedTest' \
  --tests 'com.music.bitchord.download.HqReplacementLandedTest' --console=plain -q
JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 ./gradlew :app:compileProdReleaseKotlin \
  -Pbitchord.arm64Only=true --console=plain -q
```

결과(2026-10-30): 위 13개 테스트 전부 통과, `:app:compileProdReleaseKotlin`
통과. 이 러너의 기본 JDK 17에서는 InnerTubeX 의존성이 class file 65(Java 21)라
테스트가 초기화에 실패하므로 JDK 21로 실행했다. Maven Central이 HTTP 429를
반환할 때는 Google Maven Central 미러(`maven-central.storage-download.googleapis.com`)
init script를 사용했고, 저장소 파일은 수정하지 않았다.

---

## 7. 수정 파일

- `app/src/main/java/com/music/bitchord/download/Downloader.kt`
- `app/src/main/java/com/music/bitchord/download/DownloadService.kt`
- `app/src/main/java/com/music/bitchord/download/Downloads.kt`
- `app/src/main/java/com/music/bitchord/download/DownloadStore.kt`
- `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt`
- 새 테스트 6개 (`app/src/test/java/com/music/bitchord/download/`)
- 이 문서
