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
