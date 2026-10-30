# review-1031 — 백업·복원·설정 공유

범위: 앱 자체 백업/복원(`Backup`), 설정 공유(`SettingsTransfer`), 로컬 재생목록
복원(`LocalPlaylistStore`), 복원이 건드리는 설정·청취·평점 저장소. 기준은 현재
체크아웃이며, 아래 줄 번호는 이 작업 후 코드 기준입니다.

## 1. 진입점 → 처리 → 결과

| 진입점 | 처리 | 결과 / 실패 시 |
|---|---|---|
| 설정 › 데이터 내보내기 | `Backup.exportTo` → `AppSettings.exportPrefs` + `ListeningStats.exportAll` + `LocalPlaylistStore.exportAll` + `SearchHistory.export` + `TasteSignals.ratedVideoIds` | 선택한 문서에 JSON 저장. 열기/쓰기 실패는 `Result.failure`로 반환. |
| 설정 › 데이터 가져오기 | `Backup.importFrom` → `decode` + `validate` → 청취 파일 교체 → 설정 교체 → 검색 기록/재생목록/평점 복원 | 검증 실패는 첫 쓰기 전에 중단. 청취 파일은 임시 파일에 먼저 쓴 뒤 교체. |
| 설정 › 설정 보내기 | `SettingsTransfer.share` → 화이트리스트 설정 + 소스 + 프록시(비밀번호 포함) → 캐시 파일 → `FileProvider` 공유 | 공유 시트 열림. 실패는 화면의 보내기 줄에 표시. |
| 설정 › 설정 가져오기 | 파일 선택 또는 `ACTION_SEND`/`ACTION_VIEW` → `SettingsTransferInbox` → `importFrom` → 미리보기 → `apply` | 미리보기 전에는 아무것도 쓰지 않음. 적용 시 바뀐 섹션만 기록. |
| 소스 편집/삭제/설정 공유로 소스 교체 | `SourceRegistry.update`/`remove`/`replaceFromTransfer` → `HqVerificationStore` 무효화 | 이전 소스 주소/목록의 HQ 판정을 재사용하지 않음. |

## 2. 흔한 상황별 실제 동작

| 상황 | 백업 내보내기/복원 | 설정 공유 | 판정 |
|---|---|---|---|
| 커버 영상·비디오 노래 | `BackupSong`이 `isVideo`, `isVideoOrigin`, `localPath`, `sourceQuality`를 보존 | 설정 공유 대상 아님(재생목록·다운로드 기록은 이관하지 않음) | 통과. 단, 아주 옛 로컬 재생목록 행에 `isVideo`가 없으면 여전히 false로 읽힘(기존 한계). |
| 셔플 | 백업은 대기열/셔플 상태를 저장하지 않음. 설정 공유는 `shuffle_enabled`만 이관 | 값은 적용됨 | 통과(대기열 복원은 백업 기능 범위 밖). |
| 앱 재시작 후 | 내보내기는 `TasteSignals`의 저장된 평점까지 읽음. 복원은 그 평점을 다시 저장 | 설정은 live flow에서 읽으므로 재시작 여부와 무관 | 이번에 고침(아래 평점 결함). |
| 화면 꺼짐·백그라운드 | 파일 읽기/쓰기는 사용자가 누른 순간 `Dispatchers.IO`에서 1회 | 동일 | 주기 작업·타이머 없음. |
| 프록시 켬 | 앱 백업은 비밀번호를 의도적으로 제외. 설정 공유는 경고 후 프록시 비밀번호 포함 | `setProxyConfig`로 한 번에 적용 | 설계대로. 전체 백업에 프록시가 없다는 점은 사용자에게 명시돼 있지 않음(남은 LOW). |
| 다운로드된 곡 | `downloaded_*`는 `DEVICE_LOCAL`로 제외. 재생목록 행의 파일 경로/포맷은 보존 | 다운로드 기록 자체는 이관하지 않음 | 통과. 새 기기에서 파일을 다시 받아야 함. |
| 느리거나 끊긴 망 | 이 영역은 네트워크를 쓰지 않음 | 소스 교체 뒤 다음 요청부터 새 주소 사용 | 영향 없음. |
| Android 14+ | 문서 선택 URI 권한으로만 읽고 씀. 저장소 전체 권한 없음 | 공유 인텐트의 URI 읽기 권한 사용 | 통과. |
| 큰 파일 | 가져오기는 32 MiB에서 거부. 내보내기는 기존처럼 문자열을 메모리에 만든 뒤 씀 | 가져오기는 1 MB에서 거부 | 가져오기 OOM 경로는 이번에 막음. 내보내기 메모리는 남은 LOW. |
| 옛 형식(schema 1) | 재생목록/평점/검색 기록은 건드리지 않고 설정·청취·검색 기록만 복원 | 설정 공유는 schema 1만 허용 | 통과. 음수/0 버전은 이번에 거부. |
| 바꾸기/합치기 | 재생목록은 `REPLACE`/`MERGE`, 나머지는 교체 | 설정은 화이트리스트 병합 | 통과. |

## 3. 고친 결함

| 심각도 | 파일:줄 | 증거 | 사용자 영향 | 얼마나 흔한가 | 수정 |
|---|---|---|---|---|---|
| HIGH | `app/src/main/java/com/music/bitchord/data/stats/ListeningStats.kt:751`, `app/src/main/java/com/music/bitchord/data/stats/Backup.kt:178` | 기존 `importAll`은 기존 월 파일을 전부 지운 뒤 하나씩 썼고, 파싱할 수 없는 달은 `return@forEach`로 조용히 건너뛰었다. `Backup`은 그 결과를 확인하지 않았다. | 손상된 백업이나 월 표기가 깨진 백업을 복원하면 기존 청취 기록이 먼저 사라지고 일부 달만 남는다. 화면에는 성공으로 보인다. | 손상 파일·옛 백업에서 재현. 정상 파일에서는 드묾. | `prepareImport`로 전부 직렬화하고, 임시 `.importing` 파일에 먼저 쓴 뒤 교체. 실패 시 `false`를 반환하고 `Backup`이 첫 쓰기 전에 중단. `validate`가 잘못된 달/중복 달을 먼저 거부. |
| MEDIUM | `app/src/main/java/com/music/bitchord/data/stats/Backup.kt:72`, `:163` | 기존 가져오기는 `readBytes()`로 파일 전체를 메모리에 올렸다. | 큰 `.json`을 가져오면 스키마 검사 전에 OOM으로 프로세스가 죽는다. | 큰 파일을 고른 사용자. 낮지만 한 번 나면 복원 불가. | `MAX_IMPORT_BYTES` 32 MiB와 `readTextBounded`를 추가. 초과 시 `백업 파일이 너무 큽니다`. |
| MEDIUM | `app/src/main/java/com/music/bitchord/data/stats/Backup.kt:253`, `:258` | 기존 `validate`는 `version <= 2`만 봤다. `version=0`/음수도 옛 형식처럼 통과했다. | 존재한 적 없는 스키마를 schema 1로 오인해 일부만 복원할 수 있다. | 손상/조작된 파일. 드묾. | `MIN_SCHEMA_VERSION=1` 검사 추가. |
| MEDIUM | `app/src/main/java/com/music/bitchord/data/stats/Backup.kt:312`, `:253` | 기존에는 청취 달을 검증하지 않아 `importAll`이 조용히 버렸다. | 백업에 잘못된 달이 하나 있으면 그 달 기록이 말없이 사라진다. | 손상 백업. 드묾. | `validate`에서 `YearMonth.parse` 실패와 같은 달 중복을 거부. |
| MEDIUM | `app/src/main/java/com/music/bitchord/data/taste/TasteSignals.kt:351`, `:366`, `app/src/main/java/com/music/bitchord/data/stats/Backup.kt:110`, `:193` | 기존 백업 평점은 `LikeState.overrides`만 읽었다. 이 값은 이번 프로세스에서 누른 것만 담고, 재시작하면 비어 있다. 저장된 평점은 `taste/signals.json`에 있었다. | 앱을 다시 켠 뒤 백업하면 좋아요/싫어요가 빠지고, 복원해도 재시작하면 평점이 사라진다. "좋아요 행을 백업한다"는 설명과 다르다. | 백업을 재시작 후 만드는 모든 사용자. 흔함. | `TasteSignals.ratedVideoIds`로 저장된 평점을 합치고, `restoreRatings`로 복원. 세션 값이 더 최신이므로 `mergeRatedIds`에서 세션 값이 이긴다. |
| MEDIUM | `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt:3003` | `ko_sync_safety_*` 세 키가 `KNOWN_PREFS`에 없어 복원 시 타입 검증을 받지 않았다. `readAll`은 `getBoolean`으로 읽는다. | 손상 백업이 이 키에 문자열을 넣으면 복원이 기존 설정을 지운 뒤 다음 reload에서 ClassCastException으로 죽는다. | 손상/조작 백업. 드묾. | 세 키를 `bool` 화이트리스트에 추가. |
| MEDIUM | `app/src/main/java/com/music/bitchord/data/sources/SourceRegistry.kt:325`, `:348`, `:427`, `app/src/main/java/com/music/bitchord/playback/smart/HqVerificationStore.kt:203`, `:218` | 기존 소스 편집/삭제/설정 공유 교체는 `SourceResolver` 캐시만 지우고 `HqVerificationStore`는 남겼다. 판정 키는 `sourceConfigId + trackId`이고 주소는 들어 있지 않다. | 같은 id의 애드온 주소를 바꾸거나 다른 기기 설정을 가져오면 자동 HQ 패스가 새 서버에 옛 trackId를 요청해 실패하거나 다른 녹음을 받는다. 사용자 성적표의 "다운로드·고음질 보관함 실패 140회"에 기여할 수 있는 경로다. | 소스 주소를 바꾸거나 설정 공유로 소스를 교체한 뒤 자동 HQ 패스를 켠 사용자. | 주소/종류가 바뀐 소스만 `invalidate`, 삭제 시 해당 소스만 무효화, 설정 공유 교체 시 전체 무효화. |
| MEDIUM | `app/src/main/java/com/music/bitchord/data/settings/SettingsTransfer.kt:314` | `apply`가 소스 목록이 같아도 항상 `replaceFromTransfer`를 호출했다. 위 HQ 무효화를 넣은 뒤로는 테마만 바꾸는 설정 가져오기도 모든 HQ 판정을 지울 수 있었다. | 불필요한 소스 재생성·HQ 재검증 비용. | 설정을 자주 가져오는 사용자. | `sourcesChanged`일 때만 교체하고, 프록시도 `proxyChanged`일 때만 적용. 실제 변경 결과는 그대로다(아래 진리표). |

### 조건 변경 진리표 — `SettingsTransfer.apply`

| sources 비어 있음 | sourcesChanged | 이전 교체 | 이후 교체 | 이전/이후 summary.sources |
|---|---|---|---|---|
| true | false | 안 함 | 안 함 | 0 / 0 |
| false | false | **함(동일 목록)** | 안 함 | 0 / 0 |
| false | true | 함 | 함 | 결과 / 결과 |

| proxy null | proxyChanged | 이전 적용 | 이후 적용 | summary.proxy |
|---|---|---|---|---|
| true | false | 안 함 | 안 함 | false |
| false | false | **함(동일 값)** | 안 함 | false |
| false | true | 함 | 함 | true |

즉 실제로 바뀌는 경우의 결과는 모두 유지하고, 바뀌지 않는 경우의 불필요한 쓰기만 제거했다.

## 4. 고치지 못한 결함

| 심각도 | 파일:줄 | 내용 | 남긴 이유 / 필요한 작업 |
|---|---|---|---|
| MEDIUM | `app/src/main/java/com/music/bitchord/data/stats/Backup.kt:186`, `app/src/main/java/com/music/bitchord/data/spotify/LocalPlaylistStore.kt:154` | `LocalPlaylistStore.importAll`은 `_playlists`를 바꾼 뒤 `persistAsync`로 쓰고 반환한다. 가져오기 성공 표시가 디스크 기록보다 먼저 날 수 있고, commit 실패도 성공으로 보인다. | 저장을 동기 `commit`으로 바꾸고 반환값을 `Boolean`으로 올리면 `Backup`이 실패를 알 수 있다. 다만 설정·청취·평점은 이미 적용된 뒤라 전체 원자성까지 보장하려면 모든 저장소의 2단계 커밋이 필요하다. 이번 범위에서는 청취 기록(HIGH)과 평점 왕복을 우선했다. |
| MEDIUM | `app/src/main/java/com/music/bitchord/data/settings/SettingsTransfer.kt:307` | 설정 공유 적용은 설정 → 소스 → 프록시 순서라 뒤 단계가 실패하면 앞 단계가 남는다. | 현재 각 단계는 검증된 값만 받고 예외 가능성이 낮지만, 완전한 원자 적용은 이전 값 스냅샷과 롤백이 필요하다. 별도 작업. |
| LOW | `app/src/main/java/com/music/bitchord/data/stats/Backup.kt:133` | 내보내기는 최종 JSON 문자열을 메모리에 만든 뒤 `toByteArray()`로 한 번 더 복사한다. 내보내기 큰 파일 OOM 가능성. | 가져오기 OOM은 이번에 막았다. 스트리밍 인코더로 바꾸려면 kotlinx.serialization 출력 경로와 `ContentResolver` 쓰기 방식을 함께 바꿔야 한다. |
| LOW | `app/src/main/java/com/music/bitchord/data/stats/Backup.kt:182` | schema 1 복원은 검색 기록을 설정 키로만 복원하고, `TasteSignals` 평점은 schema 2부터다. | 옛 파일에는 평점 섹션이 없으므로 복원할 데이터가 없다. 의도된 하위 호환. |

## 5. 사용자 성적표와 이 영역의 관계

- **다음 곡 싱크 준비 29% / 무손실 확인 후 실제 무손실 재생 4% / 자동 재생 보충 성공 24% /
  사용자 조작 없는 백그라운드 시작 11회**: 이 수치는 큐·스트림 해석·백그라운드 재생
  경로가 만든다. 백업/복원/설정 공유는 재생을 시작하거나 다음 곡을 고르지 않으므로 이
  숫자를 직접 설명하지 못한다. 이번 작업에서 억지로 건드리지 않았다.
- **다운로드·고음질 보관함 실패 140회**: 설정 공유로 소스 목록을 바꾼 뒤에도
  `HqVerificationStore`가 `sourceConfigId + trackId` 판정을 그대로 들고 있어, 새 서버에
  옛 카탈로그 행을 요청하던 경로가 있었다. 이번에 소스 주소 변경·삭제·교체 시 판정을
  무효화했다. 또한 설정 가져오기가 바뀌지 않은 소스까지 교체해 판정을 지우던 동작을
  막았다. 다만 실패 140회의 나머지 원인(다운로드 큐·소스 응답)은 이 영역 밖이다.
- 배터리: 백업/복원/설정 공유는 사용자가 누를 때만 IO를 1회 수행한다. 주기 폴링,
  `delay` 루프, 타이머를 추가하지 않았다.

## 6. 검증

- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` 통과.
- `./gradlew :app:testProdDebugUnitTest --tests 'com.music.bitchord.data.settings.*' --tests 'com.music.bitchord.data.stats.*' --tests 'com.music.bitchord.data.spotify.LocalPlaylist*' --tests 'com.music.bitchord.data.taste.TasteSignalsRatingBackupTest' --tests 'com.music.bitchord.playback.smart.HqVerificationStoreInvalidationTest' --console=plain -q` 통과.

새 테스트:

- `app/src/test/java/com/music/bitchord/data/stats/BackupImportValidationTest.kt`
  - `an oversized backup is refused instead of being read whole`
  - `a backup exactly at the cap is still read`
  - `an unknown older schema is refused before any write`
  - `a listening bucket whose month cannot be parsed is refused`
  - `two listening buckets for the same month are refused rather than silently merged`
  - `a valid listening month still validates`
  - `import preparation never silently drops a bad month`
  - `import preparation refuses duplicate months`
- `app/src/test/java/com/music/bitchord/data/taste/TasteSignalsRatingBackupTest.kt`
  - `ratings restored from a backup are readable as the same ratings`
  - `a later restored rating for the same id wins`
  - `export combines persisted ratings with this session's overrides`
- `app/src/test/java/com/music/bitchord/playback/smart/HqVerificationStoreInvalidationTest.kt`
  - `invalidating one source keeps the other source's verdicts`
  - `clearing forgets every accepted and rejected pair`
- `AppSettingsImportValidationTest`
  - `the advanced sync safety switches are validated as booleans too`

## 7. 2차 검증 — 외부 리뷰 지적 반영

기준선은 위 1차 작업 결과다. 아래는 2차 리뷰가 지적한 5건과 이번에 고친 내용이다.
줄 번호는 2차 작업 후 코드 기준이다.

| 심각도 | 파일:줄 | 증거 | 사용자 영향 | 얼마나 흔한가 | 수정 |
|---|---|---|---|---|---|
| HIGH | `app/src/main/java/com/music/bitchord/data/stats/ListeningStats.kt:783`, `:896` | 1차 `importAll`은 기존 `.json`을 전부 지운 뒤 staged 파일을 rename했다. 두 번째 rename이 실패하거나 그 사이 프로세스가 죽으면 옛 달은 이미 사라지고 일부 달만 남았다. | 복원이 실패 또는 중단되면 기존 청취 기록이 유실된다. 화면에는 실패로 보이지만 되돌릴 방법이 없다. | 손상 파일·저장 공간 부족·강제 종료에서 재현. 정상 파일에서는 드묾. | 기존 달을 `.json.bak`으로 rename하고, 모든 staged rename이 끝난 뒤에만 `.bak`을 지운다. 실패하면 `.bak`을 원위치로 되돌린다. `import.journal`을 커밋 전에 쓰고 커밋 시점에 지우며, `init`에서 남은 journal을 보면 전부 rollback한다. |
| MEDIUM | `ListeningStats.kt:468`, `:783` | 1차 가져오기는 `lock`만 잡고 디렉터리를 바꿨고, 재생 기록 writer는 `writeLock`으로 썼다. 가져오기 직전에 큐에 들어간 flush가 복원 뒤에 실행되어 옛 달을 되살릴 수 있었다. | 복원 직후 재생 중이던 곡의 옛 달 파일이 다시 나타나 방금 복원한 달을 덮어쓴다. | 복원을 재생 중에 하는 사용자. 드물지만 데이터가 조용히 섞인다. | `importAll`을 suspend로 바꾸고 `importing` 플래그를 세운 뒤 `lastWrite`를 join하고 `writeLock` 안에서만 폴더를 바꾼다. `pending`/`write`는 `importing` 동안 새 쓰기를 만들지 않는다. |
| MEDIUM | `app/src/main/java/com/music/bitchord/data/stats/Backup.kt:156`, `:180` | 1차 `importFrom`은 청취·설정을 먼저 바꾼 뒤 재생목록/검색/평점을 적용했다. 뒤 단계가 실패하면 앞 단계가 남는 반쪽 복원이었다. | 설정은 새 기기, 재생목록은 옛 기기인 상태가 되어 사용자가 무엇이 복원됐는지 알 수 없다. | 저장 실패·프로세스 중단에서 드묾. | 설정·재생목록·검색·평점은 스냅샷을 뜬 뒤 적용하고, 청취 폴더는 마지막에 쓴다. 어느 단계든 실패하면 이전 스냅샷으로 되돌린 뒤 실패를 반환한다. `LocalPlaylistStore.importAll`과 `AppSettings.importPrefs`, `SearchHistory.replace`는 동기 commit 결과를 반환하도록 바꿔 실패를 감지한다. |
| MEDIUM | `app/src/main/java/com/music/bitchord/data/taste/TasteSignals.kt:367`, `:486` | 1차 `restoreRatings`는 파일에 있는 행만 추가/덮어쓰고, 파일에 없는 기존 평점은 지우지 않았다. 또 새 행의 `lastPlayedAt=0`이라 `MAX_TRACKS` 정리에서 가장 먼저 잘렸다. | 복원이 합치기로 동작해 옛 기기의 평점이 남고, 큰 보관함에서는 복원한 평점이 사라진다. | 평점을 쓰던 사용자가 복원할 때. 흔함. | 파일에 없는 기존 평점은 `RATING_NONE`으로 지우고, 복원 행에는 현재 시각을 준다. `pruneLocked`는 평점이 있는 행을 크기 제한에서 제외한다. `flushAndAwait`로 복원 직후 디스크까지 확인한다. |
| LOW | `Backup.kt:211` | 1차 가져오기는 `LikeState` 세션 override를 지우지 않고 복원 평점을 덮어썼다. | 가져오기 전에 누른 좋아요/싫어요가 복원된 평점을 UI에서 가린다. | 가져오기 직전에 평점을 누른 사용자. | `LikeState.clear()` 뒤 복원 목록을 다시 넣는다. rollback에서도 이전 override를 복원한다. |
| MEDIUM | `app/src/main/java/com/music/bitchord/data/settings/SettingsTransfer.kt:307` | 1차 설정 공유 적용은 설정 → 소스 → 프록시 순서라 뒤 단계가 실패하면 앞 단계가 남았다. (1차 문서 4절에 미해결로 남긴 항목) | 테마만 새 기기 값이고 소스/프록시는 옛 값인 반쪽 적용. | 저장 실패 시 드묾. | 적용 전 설정·소스·프록시 스냅샷을 뜨고, 예외 시 역순으로 되돌린다. |
| MEDIUM | `app/src/main/java/com/music/bitchord/data/spotify/LocalPlaylistStore.kt:161` | 1차 `importAll`은 `persistAsync`로 쓰고 즉시 성공을 반환했다. (1차 문서 4절에 미해결로 남긴 항목) | 복원 성공 표시가 디스크 기록보다 먼저 나고, commit 실패도 성공으로 보인다. | 저장 실패 시 드묾. | 가져오기만 동기 `commit`으로 바꾸고, 성공한 뒤에만 메모리 목록을 바꾼다. 실패는 `-1`로 반환해 `Backup`이 rollback한다. |

### 가져오기 순서 변경 진리표 — `Backup.importFrom`

| 단계 | 1차 순서 | 2차 순서 | 실패 시 1차 | 실패 시 2차 |
|---|---|---|---|---|
| 청취 폴더 | 1 | 마지막 | 옛 기록 이미 삭제, 이후 단계 미적용 | 청취 자체 rollback, 나머지 스냅샷 rollback |
| 설정 | 2 | 1 | 앞 단계(청취)만 남음 | 스냅샷 rollback |
| 검색 | 3 | 2 | 앞 단계 유지 | 스냅샷 rollback |
| 재생목록 | 4 | 3 | 앞 단계 유지 | 성공한 뒤에만 메모리 반영, 실패 시 rollback |
| 평점 | 5 | 4 | 앞 단계 유지 | 스냅샷 rollback |

즉 성공 경로의 최종 상태는 1차와 같고, 실패 경로만 전부 이전 상태로 돌아간다.

### 이번에 추가한 테스트

- `app/src/test/java/com/music/bitchord/data/stats/ListeningStatsImportAtomicityTest.kt`
  - `a failed commit restores the month that was already on disk`
  - `a write queued before the import cannot overwrite the restored month`
  - `an interrupted import is rolled back on the next open`
- `app/src/test/java/com/music/bitchord/data/taste/TasteSignalsRatingBackupTest.kt`
  - `a restore clears ratings the file does not name`
  - `a restored rating survives the size cap even in a full library`
- `app/src/test/java/com/music/bitchord/data/spotify/LocalPlaylistImportPersistenceTest.kt`
  - `a failed commit is reported instead of being called a restore`
- `app/src/test/java/com/music/bitchord/data/stats/BackupImportRollbackTest.kt`
  - `a listening failure rolls the already-applied settings back`
- `app/src/test/java/com/music/bitchord/data/settings/SettingsTransferRollbackTest.kt`
  - `a source failure leaves the settings untouched`

### 남긴 결함

| 심각도 | 파일:줄 | 내용 | 이유 |
|---|---|---|---|
| LOW | `app/src/main/java/com/music/bitchord/data/stats/Backup.kt:141` | 내보내기는 최종 JSON 문자열과 byte 배열을 메모리에 함께 만든다. | 가져오기 OOM은 1차에서 막았다. 내보내기 스트리밍은 kotlinx.serialization 출력 경로와 `ContentResolver` 쓰기를 함께 바꾸는 별도 작업이라 이번 범위에서 제외했다. |
| LOW | `Backup.kt:182` | schema 1 복원은 평점 섹션을 복원하지 않는다. | 옛 파일에 그 데이터가 없으므로 의도된 하위 호환이다. |
| LOW | `ListeningStats.kt:896` | journal rollback은 프로세스가 커밋 도중 죽은 경우를 복구하지만, 파일 시스템이 rename을 부분적으로만 반영하는 극단적 손상까지 보증하지는 않는다. | 정상 POSIX/Android 파일 시스템의 rename 원자성을 전제로 한다. |

## 8. 3차 검증 — 외부 리뷰 지적 반영

기준선은 위 2차 작업 결과다. 아래 여섯 건을 이번에 모두 고쳤다. 줄 번호는 이
작업 후 코드 기준이다.

| 심각도 | 파일:줄 | 증거 | 사용자 영향 | 얼마나 흔한가 | 수정 |
|---|---|---|---|---|---|
| MEDIUM | `app/src/main/java/com/music/bitchord/data/stats/ListeningStats.kt:481`, `:465`, `:511` | `importAll`은 `lastWrite?.join()`을 한 번 읽은 뒤 `writeLock`을 잡는다. 그 사이 `flush`가 스냅샷을 떠서, 또는 `bucketFor`가 월 롤오버 스냅샷을 떠서, 임포트가 `importing`을 내린 뒤에야 writer 작업이 시작될 수 있었다. 그러면 복원한 달을 임포트 전 스냅샷이 덮어쓴다. | 복원 중 재생이 이어지거나 달이 바뀌면 방금 복원한 청취 기록이 옛 기록으로 조용히 바뀐다. | 복원 중 재생·월 롤오버. 드물지만 데이터가 섞인다. | 스냅샷을 뜰 때 `importGeneration`을 함께 캡처하고, `write`가 `writeLock` 안에서 `importing`과 세대를 함께 검사한다. 임포트 커밋에서만 세대를 올린다. |
| MEDIUM | `ListeningStats.kt:868`, `:896`, `:929`, `:973`, `:992` | 실패 롤백이 `backup.renameTo(original)` 결과를 무시하고 journal을 지웠다. 다음 `init`은 journal이 없으니 남은 `.bak`을 “커밋 후 찌꺼기”로 보고 지운다. `recoverInterruptedImport` 자신도 rename 실패를 무시하고 journal을 지웠다. | `.bak` 되돌리기 한 번이 실패하면 그 달 기록이 영구히 사라진다. 저장 공간 부족·파일 잠금에서 재현. | 실패 복원 + rename 실패. 드물지만 한 번 나면 복구 불가. | `restoreBackups`가 모든 되돌리기를 시도하고 성공 여부를 반환한다. 하나라도 실패하면 journal을 남겨 다음 `init`이 재시도한다. 복구 경로도 `rolledBack`을 추적해 완전히 끝난 뒤에만 journal을 지운다. |
| MEDIUM | `app/src/main/java/com/music/bitchord/data/spotify/LocalPlaylistStore.kt:162`, `:170` | 가져오기는 writer를 건너뛰고 직접 `persist()`했다. 그 전에 큐에 들어간 `persistAsync`가 나중에 실행되면 복원한 재생목록을 옛 목록으로 덮었다. | 재생목록 저장 직후 가져오기를 하면 성공 표시 뒤에 옛 재생목록이 디스크에 남는다. | 빠르게 저장→가져오기. 드물지만 조용한 유실. | `importAll`을 suspend로 바꾸고 `writer.async { persist(next) }.await()`로 같은 단일 writer에 줄을 세운 뒤 성공했을 때만 메모리 목록을 바꾼다. |
| LOW | `app/src/main/java/com/music/bitchord/data/settings/SearchHistory.kt:139`, `:150` | `replace()`는 직접 `commit()`하고 `save()`는 writer에 큐를 넣었다. 가져오기 직전의 저장이 나중에 실행되면 복원한 검색 기록을 덮었다. | 가져오기 직후 검색 기록이 옛 기기 목록으로 돌아간다. | 빠른 검색→가져오기. 드물다. | `replace()`도 같은 writer의 `submit().get()`으로 직렬화하고 결과를 기다린다. |
| LOW | `app/src/main/java/com/music/bitchord/data/taste/TasteSignals.kt:100`, `:431`, `:455`, `:460` | 디바운스된 `flush()`가 `dirty`를 먼저 가져가 실패하면, 뒤이은 `flushAndAwait()`는 빈 스냅샷을 보고 `true`를 반환했다. | 백업이 “평점을 저장했다”고 표시했지만 실제로는 디스크에 없을 수 있다. | 쓰기 실패와 디바운스가 겹칠 때. 드물다. | 마지막 쓰기 결과(`lastWriteOk`)를 기억하고, 빈 스냅샷이면 그 값을 반환한다. 실패하면 `dirty`를 되살려 다음 호출이 재시도한다. |
| LOW | `app/src/main/java/com/music/bitchord/data/settings/SettingsTransfer.kt:317`, `:347` | `settingsApplied`/`sourcesApplied`는 호출이 반환된 뒤에만 세워졌다. 저장소가 상태를 바꾼 뒤 던지면 그 저장소의 롤백을 건너뛰었다. | 테마만 새 기기 값이고 나머지는 옛 값인 반쪽 적용이 남을 수 있다. | 저장소가 쓰기 후 예외를 던질 때. 드물다. | 플래그를 없애고 설정·소스·프록시 스냅샷을 무조건 되돌린다. 바뀌지 않은 스냅샷을 다시 쓰는 것은 무해하다. |

### 조건 변경 진리표 — `ListeningStats.write`

| 스냅샷 시점 | 임포트 커밋과의 관계 | 이전 | 이후 |
|---|---|---|---|
| 임포트 전 | 커밋 전에 실행 | 씀(임포트가 백업 후 교체) | 씀(같음) |
| 임포트 전 | 커밋 후에 실행 | **씀 → 복원 덮어씀** | 건너뜀(세대 불일치) |
| 임포트 중 | 언제나 | 건너뜀(`importing`) | 건너뜀(`importing`) |
| 임포트 후 | 언제나 | 씀 | 씀(같은 세대) |

즉 기존에 막던 “임포트 중 쓰기”는 그대로 막고, 새로 “임포트 전 스냅샷이 커밋
후에 도착하는 경우”만 추가로 막았다.

### 조건 변경 진리표 — `SettingsTransfer.apply` 롤백

| 설정 적용 | 소스 적용 | 프록시 적용 | 이전 롤백 | 이후 롤백 |
|---|---|---|---|---|
| 성공 | 이후 실패 | 미시도 | 설정만 | 설정·소스(무해한 재적용) |
| 성공 | 성공 | 이후 실패 | 설정·소스만 | 설정·소스·프록시 |
| 쓰기 후 예외 | 미시도 | 미시도 | **아무것도 안 함** | 설정 |
| 실패 | 미시도 | 미시도 | 아무것도 안 함 | 설정(같은 값) |

이전에 롤백하던 경우는 모두 그대로 롤백하고, 플래그가 서지 않아 빠지던 경우를
추가로 되돌린다.

### 3차 흔한 상황 확인

| 상황 | 확인 내용 | 판정 |
|---|---|---|
| 복원 중 재생·월 롤오버 | `importGeneration`이 임포트 전 스냅샷을 커밋 후 차단 | 통과 |
| 복원 실패 후 앱 재시작 | journal을 남겨 `.bak`을 다음 `init`이 재시도 | 통과 |
| 재생목록 저장 직후 가져오기 | 같은 writer에서 저장 → 가져오기 순서 보장 | 통과 |
| 검색 직후 가져오기 | 같은 writer에서 저장 → 대체 순서 보장 | 통과 |
| 평점 저장 실패 | `flushAndAwait`가 `false`, `dirty` 복구 | 통과 |
| 설정 쓰기 후 예외 | 설정·소스·프록시를 무조건 스냅샷으로 복구 | 통과 |
| 큰 파일·옛 형식·비밀값 제외·바꾸기/합치기 | 1·2차 동작 변경 없음 | 통과 |

### 이번에 추가한 테스트

- `ListeningStatsImportAtomicityTest`
  - `a snapshot captured before an import cannot land after it`
  - `a rollback that cannot restore a month keeps the journal for the next open`
- `LocalPlaylistImportOrderingTest`
  - `a save queued before an import cannot replace the restored playlists`
- `SearchHistoryImportOrderingTest`
  - `a save queued before a replace cannot overwrite the restored history`
- `TasteSignalsRatingBackupTest`
  - `a failed write is reported by the next await instead of looking saved`
- `SettingsTransferRollbackTest`
  - `a store that mutates and then throws is still rolled back`

### 검증

- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` 통과.
- 위 테스트 묶음 `:app:testProdDebugUnitTest` 67건 통과.

### 남긴 결함

| 심각도 | 파일:줄 | 내용 | 이유 |
|---|---|---|---|
| LOW | `app/src/main/java/com/music/bitchord/data/stats/Backup.kt:141` | 내보내기는 최종 JSON 문자열과 byte 배열을 메모리에 함께 만든다. | 1차 문서와 같은 이유. 스트리밍 인코더와 `ContentResolver` 쓰기를 함께 바꾸는 별도 작업이다. |
| LOW | `Backup.kt:182` | schema 1 복원은 평점 섹션을 복원하지 않는다. | 옛 파일에 그 데이터가 없으므로 의도된 하위 호환이다. |
