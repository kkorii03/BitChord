# downloads-cache r2 — 이전 지적 검증·수정 (2026-10-30)

범위: `app/src/main/java/com/music/bitchord/download/*`, 다운로드 진입점과
`DownloadService`, `Downloader`, `DownloadStore`, `HqLibraryUpgrade`.
기준 브랜치 `task/fix-1030`, 작업 브랜치 `dsq/deep-downloads-1031-r2`.
이번 차수는 이전 차수 문서(`downloads-cache.md`)의 결함을 되돌리지 않고,
검토 지적 5건을 코드로 재확인한 뒤 HIGH 1건·MEDIUM 3건을 고쳤다. LOW 1건과
이전 차수의 미해결 HIGH/MEDIUM은 “남긴 결함”에 이유와 필요한 작업을 적었다.

표시: **HIGH** 실제 사용에서 파일이 안 남거나 잘못 남음, **MEDIUM** 재시도·
저장 공간·배터리처럼 반복 사용에서 쌓이는 결함. `파일:줄`은 이번 수정 후다.

---

## 1. 흔한 상황별 끝까지 동작하는가

| 상황 | 진입점 → 처리 → 결과 | 판정 |
|---|---|---|
| 일반 곡 다운로드 | `Downloads.enqueue` → `DownloadService` 4 worker → `prepare` → `Downloader.fetch` 범위 GET → `DownloadStore.Pending` → 태그 → `commit` → `remember` | 정상. 저장 공간 선검사 없음(§4) |
| 커버 영상 | `prepare`가 `songVersion`을 거절 → 원본 커버 행 유지 → 파일명·태그도 커버 | 정상 |
| 비디오 노래(MV) | 카탈로그 곡 버전으로 교체, `remember`가 MV id와 곡 id를 같은 파일에 기록 | 정상 |
| MV 소리 재다운로드 | `scanForMvSoundSuspects` → `MvSoundRedownload` → `mvSoundRepair` → `prepare` 실패 시 기존 파일 유지 | 정상. foreground 이벤트에서만 스케줄(§4) |
| 셔플 | 큐는 재생 순서와 무관, `takeNext`가 ask 순서로 소비 | 정상 |
| 앱 재시작 | `pending`/`running`은 메모리만. 진행·대기 복구 없음 | **결함(HIGH, 이전 차수 미해결)** §4 |
| 화면 꺼짐·백그라운드 | 이미 시작한 FGS는 계속. 새 enqueue/자동 패스/MV 복구는 `AppForeground.isForeground`가 아니면 시작하지 않고 대기 | **결함(HIGH, 이전 차수 미해결)** §4 |
| 프록시 켬 | 200 전체 응답은 `position`만큼 skip 후 `total`까지만 기록. 측정 불가한 1바이트 200은 Range 없는 재측정 | **이번에 고침** §3-2, §3-3 |
| 다운로드된 곡 재생 | `savedUri`/`verifiedSavedUri`가 존재 확인 후 `file://`/`content://`를 직접 재생 | 정상 |
| 느린/끊긴 망 | 2MB 범위 반복, 0바이트 range는 stall 실패, 403/404/410은 1회 재해결 | 정상. 전체 timeout은 HTTP client 의존 |
| Android 14+ | manifest `dataSync` + `FOREGROUND_SERVICE_DATA_SYNC`, 시작 실패는 실패 행 | 정상. 백그라운드 예외 미활용(§4) |
| 반쪽 파일 | MediaStore `IS_PENDING`, 파일 경로 `.part`→rename, 실패·취소 `abort`; 24h 지난 `.part`만 begin에서 청소 | 정상. 활성 part 보호는 LOW(§4) |
| 캐시 정리 | `DynamicLruCacheEvictor` LRU + head 보호, 설정 변경 즉시 `applyNow` | 정상. 무제한+저장고갈 검사 없음(§4) |
| 정산 직후 새 enqueue | drain 종료 시 `stopSelf(startId)`와 최신 start id 비교, 늦은 요청은 새 drain으로 인계 | **이번에 고침** §3-4 |

---

## 2. 성적표 숫자를 코드로 설명

- **다운로드 실패 140**: 이전 차수의 200 처리로 “프록시가 Range를 무시해
  첫 바이트 전에 길이 없음”과 “중간 range 200이 앞부분을 중복”은 줄었다.
  남은 주 원인은 ① 소스 탐색 상한 85초
  (`Downloads.kt:2694`, `Downloads.kt:2585`) ② 고음질 검증 거절
  ③ 저장 공간 고갈이다. 이번에 200 응답이 `total`보다 길 때 저장 공간을
  채우던 경로와 1바이트 200을 전체 길이로 오인해 잘린 파일을 커밋하던 경로를
  닫았다(§3-2, §3-3). 잘린 파일이 “성공”으로 남던 실패도 이제 실패한다.
- **고음질 보관함 미착지 128**: 이전 차수는 같은 id의 사용자 다운로드가
  검증 쌍을 갖지 않으면 `settle(success=false)`로 **대상을 계획에서 제거하고
  실패 횟수를 소모**했다. 그 결과 다음 자동 시도가 24시간 쿨다운에 걸리고
  교체는 영영 착지하지 못했다. 이번에 ① 이미 목표 등급 이상이면 성공으로
  정산 ② 아니면 대상을 계획에 남긴 채 같은 대상만 재시도(실패 예산 미소모)로
  바꿨다(§3-1). 자동 패스가 foreground에서만 돌고 한 번에 한 곡인 점,
  `AutomaticRetryPolicy.MAX_FAILURES=2`·24h 쿨다운은 여전히 대상 수를 줄인다.
- **서비스 시작 실패 9**: `Downloads.kt:459`의 `AppForeground.isForeground`
  조건과 `ContextCompat.startForegroundService` 거절(`Downloads.kt:526-530`)이
  원인이다. 액티비티가 막 백그라운드로 간 전환 경계에서 true로 보이다가
  Android가 거절하면 실패 행이 남는다. 이번 수정은 drain 종료와 새 enqueue
  사이의 race(`stopSelf`가 새 요청을 같이 죽임)만 닫았고(§3-4), 백그라운드
  시작 자체를 넓히지는 않았다(§4).
- **다음 곡 싱크 준비 29% / 무손실 확인 후 실제 무손실 재생 4% / 자동 재생
  보충 24% / 사용자 조작 없는 백그라운드 시작 11회**: 다운로드 파일은 로컬
  파일로 재생되어 “확인 후 교체” 사슬을 타지 않고, 자동 패스·MV 복구는
  foreground 이벤트 없이는 시작하지 않는다. 이번 수정은 교체가 실패로 오인되어
  사라지던 경로를 없애 “확인 후 실제 무손실 파일”이 남을 확률을 높이지만,
  백그라운드 시작 조건(§4)은 여전히 직접 원인이다.

---

## 3. 이번에 고친 결함

### 3-1. HIGH — 같은 id의 정상 사용자 다운로드를 영구 실패로 정산해 교체가 사라짐

- 지적: `HqLibraryUpgrade.kt:607-625`.
- 근거(수정 전): 대상이 계획에 남아 있는데 `hqApplied`가 false면 무조건
  `settle(success=false)`였다. 사용자가 같은 id를 목표 등급 이상으로 이미
  받았어도 검증 쌍만 없으면 실패로 처리해 대상을 제거하고
  `AutomaticRetryPolicy` 횟수를 소모했다.
- 사용자 영향: 고음질 보관함 교체가 한 번 어긋나면 그 곡은 자동으로 다시
  시도되지 않는다. 성적표의 “고음질 보관함 미착지 128”에 직접 기여한다.
- 흔한 정도: 고음질 자동 교체 대상이면서 사용자가 같은 곡을 직접 받은 경우.
  배치 다운로드와 자동 패스가 겹치면 흔하다.
- 수정: `hqReplacementLanded(success, applied, atLeastAsGood)`로
  `HqLibraryPolicy.hasCopyAtLeastAsGood` 결과를 함께 본다. 목표 등급 이상이면
  성공 정산. 아니면 `retryActive`가 `activeVideoId`만 비우고 **계획에는
  그대로 둔 채** 다음 worker를 요청한다. 실패 기록·재시도 예산은 건드리지 않는다.
- 회귀 테스트: `HqReplacementLandedTest`
  - `a successful download already at least as good as the target landed`
  - `a successful download below the target and without its pair did not land`

### 3-2. MEDIUM — 200 응답이 측정 길이보다 길면 저장 공간을 채운 뒤 실패

- 지적: `Downloader.kt:156-166`.
- 근거(수정 전): 200 분기가 EOF까지 읽어 `sink`에 모두 썼다. Content-Length가
  없거나 chunked인 프록시가 `total`보다 긴 본문(또는 끝나지 않는 응답)을 주면
  마지막 길이 검사 전에 저장 공간을 소진했다.
- 사용자 영향: 프록시/캐시 환경에서 저장 공간이 갑자기 줄고 다운로드가 실패.
- 흔한 정도: 프록시 사용자와 손상된 캐시 계층에서 드물지 않다.
- 수정: `total - position`만 남겨 그만큼만 쓰고, 다 쓴 뒤 한 바이트라도 더
  오면 즉시 오류. `Downloads`의 커밋 전 abort 경로가 반쪽 파일을 지운다.
- 회귀 테스트: `DownloaderRangeTest`
  - `a chunked 200 body longer than the measured total is not written past the end`

### 3-3. MEDIUM — 1바이트 200 range probe를 전체 길이로 믿어 잘린 파일 커밋

- 지적: `Downloader.kt:310-313`.
- 근거(수정 전): `contentLength`가 200이면 어떤 Content-Length든 전체 길이로
  받아들였다. 프록시가 `bytes=0-0`에 200+1바이트만 주면 `total=1`이 되고
  `fetch`가 1바이트를 성공으로 커밋했다.
- 사용자 영향: “저장됨” 후 즉시 끝나는 파일. 재생 불가·무손실 확인 실패.
- 흔한 정도: Range를 흉내 내는 프록시/캐시에서만. 일반 회선에서는 드물다.
- 수정: 200의 Content-Length가 probe 크기(1) 이하이면 모호하므로 Range 없이
  헤더만 다시 측정(`measureWhole`)한다. 본문은 읽지 않고 닫는다. 큰 200
  길이는 그대로 전체 길이로 인정한다. 200 분기의 상한 덕분에 남은 오측정도
  잘린 커밋이 아니라 실패가 된다.
- 회귀 테스트: `DownloaderRangeTest`
  - `a one byte 200 range probe is not mistaken for the whole track`

### 3-4. MEDIUM — drain 종료 직후 온 enqueue를 `stopSelf()`가 함께 정지

- 지적: `DownloadService.kt:137-142`.
- 근거(수정 전): `Downloads.busy()`가 false인 것을 보고 `stopSelf()`를 호출했다.
  그 사이 도착한 start 요청은 서비스와 함께 정지되어 큐에 남았다.
- 사용자 영향: 배치가 끝나는 순간 추가한 곡이 다음 foreground 이벤트까지
  멈춘다. 사용자는 “다운로드가 멈췄다”고 본다.
- 흔한 정도: 경계 race라 드물지만 한 번 걸리면 작업이 사라진다.
- 수정: `StartIdLedger`가 마지막 start id를 기록한다. drain이 시작된 id보다
  최신 요청이 있으면 `stopSelf`를 하지 않고 새 drain을 시작한다. 최신이면
  `stopSelf(latestStartId)`로 정지해 그 사이 도착한 요청을 죽이지 않는다.
- 회귀 테스트: `DownloadServiceStartIdTest`
  - `a start that arrives after the drain began prevents the stop`
  - `a drain with no later start stops with the latest id`
  - `a start that arrives during the same drain is not ignored`

---

## 4. 남긴 결함 (이유와 필요한 작업)

### 4-1. LOW — `staleParts`가 활성 전송의 `.part`도 24시간 뒤 삭제할 수 있음

- 지적: `DownloadStore.kt:164-169`. 현재 `staleParts(folder, now)`는 나이만 본다.
- 영향: 아주 느린 회선에서 24시간 넘게 이어지는 전송의 part가 다른 worker의
  `begin` 청소에 지워져 커밋이 실패한다.
- 이유: 24시간은 실제 전송보다 훨씬 길게 잡은 안전 여유이고, 활성 part 집합을
  서비스·스토어에 걸쳐 관리하려면 수명 주기 변경이 필요하다. 이번 HIGH/MEDIUM
  수정 범위를 넘는다.
- 필요한 작업: 활성 part 경로 집합(worker 등록/해제)을 두고 `staleParts`에서
  제외하거나, 전송이 하나도 없을 때만 sweep.

### 4-2. HIGH(이전 차수 미해결) — 재생 중/화면 꺼짐 백그라운드에서 서비스 시작 안 함

- 근거: `Downloads.kt:459`, `Downloads.kt:492`, `MvSoundRedownload.kt:117,154`,
  `HqLibraryUpgrade.kt:249,635,1026`의 `AppForeground.isForeground`.
- 영향: 재생 FGS가 살아 있어도 창이 없으면 시작하지 않아 자동 패스·MV 복구·
  좋아요 자동 다운로드가 앱을 다시 열 때까지 멈춘다. “사용자 조작 없는
  백그라운드 시작 11회”의 직접 원인.
- 이유: `PlaybackService`의 실제 FGS/타임아웃 상태를 노출해야 하고 업스트림
  재생 코드 수정이 필요하다. 잘못 넓히면 Android 12+ 예외로 앱이 죽는다.
- 필요한 작업: PlaybackService가 `hasDataSyncExemptForegroundService`를
  `AppForeground`에 알리고, 시작 조건을 `isForeground || exemption`으로 넓힌다.
  실패는 기존처럼 실패 행으로 남긴다.

### 4-3. HIGH(이전 차수 미해결) — 앱 재시작 시 대기/진행 다운로드·pending row 복구 없음

- 근거: `Downloads.kt`의 `pending`/`running`은 메모리 필드, `DownloadSession`도
  비영속. `DownloadStore.begin`이 만든 `IS_PENDING=1` 행은 프로세스 사망 후
  아무도 정리하지 않는다.
- 영향: OS가 다운로드 중 프로세스를 죽이면 대기 목록이 사라지고, 보이지 않는
  pending MediaStore 행이 저장 공간을 차지한다.
- 이유: 큐 스냅샷·복구·pending row sweep은 별도 영속 설계와 동시성 검증이
  필요하다.
- 필요한 작업: 큐 스냅샷을 디스크에 저장하고 foreground 이벤트에서 재개,
  시작 시 `Music/BitChord`의 `IS_PENDING=1` 행 정리.

### 4-4. MEDIUM(이전 차수 미해결) — 저장 공간 선검사·ENOSPC 문구 없음

- 근거: `DownloadStore.begin`과 `Downloader.fetch`에 `usableSpace` 검사가 없다.
  `FriendlyError`도 ENOSPC를 구분하지 않는다.
- 영향: 공간 부족이 “문제가 발생했습니다”로만 보이고, insert/openStream/write
  중간에 실패한다.
- 이유: `total`은 `Downloader` 안에서, 볼륨은 `DownloadStore`에서 알 수 있어
  두 계층 사이에 길이를 전달하는 설계가 먼저 필요하다. 잘못된 볼륨 검사는
  여유가 있는데도 거절할 수 있다.
- 필요한 작업: 측정한 `total`을 `transfer`에 넘겨 대상 볼륨 `usableSpace`와
  비교하고, 부족하면 formal 한국어 문구로 실패 행 생성. 캐시 무제한 설정도
  같은 검사를 공유.

### 4-5. MEDIUM(이전 차수 미해결) — MediaStore 교체 시 같은 이름이 `(1)`로 개명

- 근거: `Downloads.transfer`의 교체 이름 선택(`Downloads.kt:2220`)과
  `DownloadStore.begin`의 같은 DISPLAY_NAME insert(`DownloadStore.kt:356`).
  MediaStore가 중복 이름을 `… (1).ext`로 바꾸고 `remember`가 옛 행을 지운다.
- 영향: 고음질 교체마다 파일명이 오가고 레코드 uri가 새 이름을 가리켜 다음
  adoption/검색이 이름으로 찾지 못한다.
- 이유: “옛 파일을 먼저 지우지 않는다”는 안전 규칙과 충돌하지 않게 임시 이름→
  커밋→옛 행 삭제→최종 이름 update 순서를 설계해야 한다.
- 필요한 작업: 교체용 새 행은 임시 고유 이름으로 만들고, 커밋 후 옛 행을 지운
  뒤 DISPLAY_NAME을 최종 이름으로 update.

### 4-6. MEDIUM(이전 차수 미해결) — 재시도가 업그레이드 의도를 잃음

- 근거: `DownloadSession.Item`은 `videoId/song/progress/from/sequence`만 보관
  (`DownloadSession.kt:62`), 재시도는 `Downloads.enqueue(context, item.song,
  item.from)`(`ui/components/DownloadManagerSheet.kt:296`). 실패한 고음질 교체의
  `forceReplace`, `minimumTier`, `quality`, `hq`가 사라진다.
- 영향: 교체 실패 후 재시도가 기존 저음질 파일을 채택해 “성공”으로 보이거나
  검증되지 않은 검색을 다시 한다.
- 이유: 세션 모델과 UI 재시도 경로를 함께 바꿔야 하고, 자동 패스 실패는
  세션에 없어 영향이 작다.
- 필요한 작업: `DownloadSession.Item`에 재시도 계획을 담거나 실패 작업의
  원래 진입점 콜백을 보관.

### 4-7. MEDIUM(이전 차수 미해결) — 배치마다 곡 수만큼 `startForegroundService`

- 근거: `Downloads.enqueue`는 중복 여부와 무관하게 곡마다 `startService`를
  호출한다(`Downloads.kt:459-461`).
- 영향: 372곡 배치면 372개의 start intent가 들어와 `promote()`와 알림 빌드를
  반복한다. 기능 실패는 아니지만 저사양 기기에서 jank·배터리 낭비.
- 이유: 서비스 생존 상태(`serviceRequested`)와 종료 시 자체 재시작 안전망을
  함께 넣어야 하고, 이번 §3-4의 drain 재시작과 맞물려 검증이 필요하다.
- 필요한 작업: 서비스가 살아 있으면 enqueue가 start를 생략하고, 서비스 종료
  시 pending이 있으면 스스로 재시작.

---

## 5. 조건 변경 전후 진리표

### 5-1. 고음질 정산 (`hqReplacementLanded` + `onDownloadSettled`)

| 다운로드 성공 | 검증 쌍 일치 | 목표 등급 이상 | 기존 | 새 |
|---|---|---|---|---|
| 예 | 예 | 예/아니오 | 성공 정산 | 성공 정산 |
| 예 | 아니오 | 예 | **실패 정산 → 대상 제거** | 성공 정산 |
| 예 | 아니오 | 아니오 | **실패 정산 → 대상 제거·예산 소모** | **대상 유지, 같은 대상 재시도** |
| 아니오 | 예/아니오 | 예/아니오 | 실패 정산 | 실패 정산 |

기존의 모든 항(성공 여부, 검증 쌍 일치, 대상이 계획에 있는지)을 유지하고,
“성공 + 검증 쌍 없음”의 두 경우만 목표 등급 기준으로 나눴다.

### 5-2. drain 종료 (`DownloadService`)

| drain 시작 후 새 start | 큐에 작업 있음 | 기존 | 새 |
|---|---|---|---|
| 없음 | 아니오 | `stopSelf()` | `stopSelf(최신 id)` |
| 없음 | 예 | 새 drain | 새 drain |
| 있음 | 예/아니오 | **`stopSelf()`가 새 요청까지 정지 가능** | `stopSelf` 보류, 새 drain 시작 |

기존 항(활성 drain, 큐 존재)을 유지하고 “새 start 도착”만 추가로 판정한다.

### 5-3. 200 응답 길이·본문

| 200 Content-Length | 본문 길이 | 기존 | 새 |
|---|---|---|---|
| `total`과 같음 | `total` | 성공 | 성공 |
| `total`과 같음 | `> total` | `total` 초과분 기록 후 실패 | `total`에서 즉시 실패 |
| 없음(chunked) | `> total` | EOF까지 기록 후 실패 | `total`에서 즉시 실패 |
| probe=1 | 1바이트만 | **total=1로 성공(잘림)** | Range 없이 재측정 |
| probe > 1 | 전체 | total로 인정 | total로 인정 |

### 5-4. `.part` 청소

| 이름 `.part` | 나이 ≥ 24h | 활성 전송 | 기존/새 |
|---|---|---|---|
| 예 | 예 | 아니오 | 삭제 |
| 예 | 예 | 예 | **삭제(위험, LOW)** |
| 예 | 아니오 | 예/아니오 | 남음 |
| 아니오 | — | — | 남음 |

---

## 6. 테스트

새 테스트(모두 app JVM 단위 테스트):
- `com.music.bitchord.download.DownloaderRangeTest` — 2개 추가(총 4개)
- `com.music.bitchord.download.DownloadServiceStartIdTest` — 3개
- `com.music.bitchord.download.HqReplacementLandedTest` — 2개 추가(총 5개)

수정 전 실패 확인:
- chunked 200 초과 본문: 수정 전 `sink`에 96바이트가 기록되어 `out.size()==64`
  단언이 실패한다.
- 1바이트 200 probe: 수정 전 `written==1`이라 `written==4096` 단언이 실패한다.
- 정산: 수정 전 `hqReplacementLanded(success=true, applied=false,
  atLeastAsGood=true)`가 false다.
- start id: 수정 전 `StartIdLedger`/`stopIdAfter`가 없어 컴파일되지 않는다.

실행 결과(2026-10-30):
```
JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 ./gradlew :app:testProdReleaseUnitTest \
  --tests 'com.music.bitchord.download.*' --console=plain -q
# EXIT:0  (download 패키지 전체 통과)
./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q
# 통과
```
JDK 17 기본값에서는 InnerTubeX 의존성이 class file 65(Java 21)라 테스트가
초기화에 실패하므로 JDK 21로 실행했다.

---

## 7. 수정 파일

- `app/src/main/java/com/music/bitchord/download/Downloader.kt`
- `app/src/main/java/com/music/bitchord/download/DownloadService.kt`
- `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt`
- `app/src/test/java/com/music/bitchord/download/DownloaderRangeTest.kt`
- `app/src/test/java/com/music/bitchord/download/DownloadServiceStartIdTest.kt`
- `app/src/test/java/com/music/bitchord/download/HqReplacementLandedTest.kt`
- `docs/review-1031/downloads-cache-r2.md`
