# audit-settings-ui — 설정·설정 공유·곡 메뉴 감사

작업 기준: 현재 HEAD(`dsq/audit-settings-ui`). 이 체크아웃에는 `ko-101` 태그가 없고
커밋도 하나뿐이라 `git diff ko-101..HEAD`를 만들 수 없었다. 그래서 범위 안의 현재 코드를
직접 읽고, 확인된 것만 고쳤다. 검사 범위는 설정 화면, 설정 내보내기/가져오기,
고급·실험 기능 스위치, 곡 메뉴(하단 시트), 문제 표시, 노래 정보, 한국어 문구다.

표시: HIGH = 사용자 데이터·안전·프로세스에 직접 영향, MEDIUM = 기능이 조용히 무시되거나
잘못된 값을 보여 줌, LOW = 문구·일관성. `고침`은 이번 작업에서 코드를 바꾼 항목,
`문서만`은 확인했지만 이번 범위에서 바꾸지 않은 항목이다.

## 요약 표

| 심각도 | 파일:줄 | 무엇이 고장 | 재현 경로 | 수정? |
|---|---|---|---|---|
| HIGH | `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt:3235-3237`, `:3364-3366` | 설정 공유가 HQ 자동 교체·자동 미리받기·미리받기 상한을 나르지 않는다. 받는 기기는 기본값(둘 다 켬, 500곡)을 유지한다. | A에서 다운로드 › HQ 자동 교체/미리받기를 끔 → 데이터 › 설정 보내기 → B에서 가져오기 → B의 두 스위치가 다시 켜져 있고 최대 500곡을 자동으로 받을 수 있다. | **고침** |
| HIGH | `app/src/main/java/com/music/bitchord/download/HqLibraryUpgrade.kt:406-410`, `:456` | HQ 자동 패스가 `allowMetered=true`로 다운로드 큐를 불러, 사용자가 켠 "Wi-Fi에서만 다운로드"를 무시한다. | Wi-Fi 전용 다운로드를 켬 → 모바일 데이터에서 재생/앱 시작 → 자동 HQ 교체·미리받기가 셀룰러로 내려받는다. | **고침** |
| MEDIUM | `app/src/main/java/com/music/bitchord/data/settings/SettingsTransfer.kt:278-304` | 공유받은 설정 파일을 크기 제한 없이 `readBytes()`로 통째로 읽는다. 큰 `application/json` 파일을 공유하면 가져오기에서 OOM으로 프로세스가 죽을 수 있다. | 외부 앱에서 수백 MB JSON을 BitChord로 공유 → 설정 › 설정 가져오기 → 프로세스 종료. | **고침** |
| MEDIUM | `app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt:416,450,472,490,510,543` | 내보내기/가져오기 실패 줄에 `it.message`를 그대로 넣어 `Couldn't open that file for writing` 같은 영어 기술 문구가 한국어 화면에 나온다. | 쓰기 권한이 사라진 문서에 내보내기 → 실패 줄이 영어로 표시. | **고침** |
| MEDIUM | `app/src/main/java/com/music/bitchord/ui/components/SongActionsSheet.kt:929-936` | 곡 메뉴의 "기본값으로 재설정"이 피치만 0으로 되돌리고 재생 속도는 1×로 되돌리지 않는다. 속도만 바꾼 경우 행 자체가 비활성이라 누를 수도 없다. | 플레이어 곡 메뉴 › 재생 속도 › 1.5× 선택 → 뒤로 → 기본값으로 재설정(비활성) 또는 피치를 바꾼 뒤 눌러도 속도는 1.5×로 남음. | **고침** |
| MEDIUM | `app/src/main/java/com/music/bitchord/data/stats/Backup.kt:132-134` | 백업 가져오기도 `readBytes()`로 파일 전체를 메모리에 올린다. 설정 공유와 같은 OOM 경로다. | 큰 `.json` 백업을 골라 가져오기 → 프로세스 종료. | 문서만 |
| LOW | `app/src/main/res/values-ko/strings.xml:649` | 수면 타이머를 "잠자기 타이머"라고 부른다. Apple Music 한국어 용어는 "취침 타이머"다. | 플레이어 곡 메뉴 › 수면 타이머. | **고침** |
| LOW | `sharedUi/src/commonMain/composeResources/values-ko/strings.xml:105-106,131` | 같은 동작을 앱은 "다음 항목 재생", 플레이어 대기열은 "다음 재생"이라고 부른다. | 곡 메뉴의 "다음 항목 재생"과 대기열 머리글의 "다음 재생"을 나란히 비교. | 문서만 |
| LOW | `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt:2915-3000` | `ko_sync_safety_*` 세 키가 `KNOWN_PREFS`에 없어 백업 가져오기에서 타입 검증을 받지 않는다. 정상 백업은 Boolean이라 문제없고, 손상된 파일만 위험하다. | `ko_sync_safety_next_prep`를 문자열로 넣은 백업 가져오기 → `getBoolean`에서 예외. | 문서만 |

## HIGH 1 — 설정 공유가 HQ 자동 다운로드 스위치를 빠뜨림

- **고친 곳**
  - `AppSettings.TRANSFERABLE_KEYS`에 `auto_replace_hq_downloads`, `auto_prefetch_hq`,
    `hq_prefetch_limit`을 추가했다. 상한은 기존 설정기와 같은 `0..10_000`으로 검증한다.
  - `transferableValue()`에도 세 키를 추가해, 아직 한 번도 바꾸지 않아 환경설정 파일에
    없는 기본값도 파일에 실리게 했다.
- **왜 HIGH인가**: 설정 공유의 목적은 "이 기기의 선택을 저 기기로"다. 자동 HQ 패스는
  기본값이 켬이고 최대 500곡을 미리 받으므로, 끈 사람의 선택이 사라지면 받는 기기에서
  모바일 데이터와 배터리를 자동으로 쓴다.
- **회귀 테스트**: `app/src/test/java/com/music/bitchord/data/settings/SettingsTransferEdgeCasesTest.kt`
  - `hq automation switches and the prefetch cap travel with the settings`
  - 결과: 통과 (`:app:testProdReleaseUnitTest --tests 'com.music.bitchord.data.settings.*'`).

## HIGH 2 — HQ 자동 패스가 Wi-Fi 전용 다운로드를 우회함

- **고친 곳**
  - `HqLibraryUpgrade.runOne`의 `allowMetered = true`를 `hqPassAllowsMeteredData(manualPass)`로
    바꿨다. 버튼으로 시작한 패스(`manualPass=true`)만 모바일 데이터를 쓸 수 있다.
  - 대기 상태에서 자동 패스가 시작되면 `finishRun()`으로 계획을 비우고 Wi-Fi를 기다린다.
    계획을 실패 행으로 하나씩 소모하지 않는다.
- **조건 진리표**

  | manualPass | Wi-Fi 전용 | 현재 네트워크 | 이전 | 이후 |
  |---|---|---|---|---|
  | false | 꺼짐 | 셀룰러 | 다운로드 | 다운로드 |
  | false | 켜짐 | 셀룰러 | **다운로드(버그)** | 시작하지 않음, 계획 유지 |
  | false | 켜짐 | Wi-Fi | 다운로드 | 다운로드 |
  | false | 켜짐 | 네트워크 없음 | 다운로드 시도 | 다운로드 시도(기존 `downloadsAllowedNow` 규칙 유지) |
  | true | 켜짐 | 셀룰러 | 다운로드 | 다운로드(사용자가 버튼으로 요청) |

  기존 `Downloads.enqueue`의 Wi-Fi 검사와 `downloadsAllowedNow`의 "네트워크 없음은
  통과" 규칙은 그대로 두었다. 안전 규칙을 약화한 것이 아니라 자동 패스에만 적용한다.
- **회귀 테스트**: `app/src/test/java/com/music/bitchord/download/HqLibraryMeteredPolicyTest.kt`
  - `the background pass does not override the wi-fi-only rule`
  - `a pass the listener started may use the connection in hand`
  - 결과: 통과.

## MEDIUM — 설정 파일 무제한 읽기

- `SettingsTransfer.importFrom`이 `openInputStream(...).use { it.readBytes() }`로 파일 전체를
  읽었다. 공유 인텐트는 확장자 또는 `application/json`/`application/octet-stream`만 보므로
  다른 앱이 임의로 큰 파일을 넘길 수 있다.
- `readTextBounded()`를 추가해 1 MB(`MAX_IMPORT_BYTES`)까지만 읽고, 넘으면
  `설정 파일이 너무 큽니다`로 거부한다. 기존 UTF-8 디코딩과 오류 문구는 유지했다.
- 회귀 테스트: `SettingsTransferEdgeCasesTest`
  - `an oversized settings file is refused instead of being read whole`
  - `a settings file exactly at the cap is still read`
  - 결과: 통과.

## MEDIUM — 실패 문구의 영어 예외 노출

- 설정 내보내기/가져오기/공유/적용 실패가 `it.message`를 그대로 사용자에게 보여 주었다.
  `FriendlyError.message(context, error)`로 바꿔, 이미 한국어인 우리 메시지는 그대로 두고
  영어 기술 예외만 한국어 요약으로 바꾼다. Last.fm 로그인 오류는 자격 증명 안내가 필요해
  이번에 건드리지 않았다.

## MEDIUM — 곡 메뉴 "기본값으로 재설정"이 속도를 남김

- `PlaybackSpeedPicker`의 재설정 행이 `setPitchSemitones(0)`만 호출했다.
- `setPlaybackSpeed(1f)`와 `setPitchSemitones(0)`을 함께 호출하고, 둘 중 하나라도
  기본값이 아니면 활성화한다.

## LOW — 한국어 용어

- 수면 타이머를 Apple Music 한국어와 같은 "취침 타이머"로 바꿨다.
- 플레이어 대기열의 "다음 재생"과 앱의 "다음 항목 재생"은 같은 동작을 다르게 부른다.
  이번에는 문구를 더 건드리지 않고 목록만 남겼다. Apple Music 용어로 통일할 때
  `next_in_queue`, `up_next`, `next_from`을 함께 정리해야 한다.

## 확인했지만 문제 없음

- 고급·실험 기능의 세 스위치(`syncSafetyNextPrep`, `syncSafetyDownloadRecheck`,
  `syncSafetyHqSync`)는 각각 `SyncSafetyPolicy`의 `nextPrepLyrics`, `downloadedRecheck`,
  `hqReapply`로 실제 소비된다. 기본값이 꺼짐인 것도 의도된 안전 기본값이다.
- 설정 화면의 스위치·세그먼트·슬라이더는 모두 해당 `AppSettings` setter에 연결되어 있고,
  `AppSettings`의 StateFlow를 읽는 소비처가 있다. 표시만 하고 아무 데도 닿지 않는
  스위치는 찾지 못했다.
- 곡 메뉴의 "문제 표시"는 재생 중인 곡의 `mediaId == song.videoId`일 때만 노출되고,
  `TrackDiagnostics.mark`로 진단 로그에 남는다. 곡 정보는 `SongInfoDisplay`의 순수 규칙과
  `SongInfoRepository`의 5분 LRU 캐시를 그대로 쓴다.
- 수면 타이머의 1초 카운트다운은 플레이어 메뉴가 열려 있을 때만 도는 표시용 갱신이며,
  백그라운드 폴링이나 별도 알람은 없다.

## 검증

- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` → 통과.
- `./gradlew :app:testProdReleaseUnitTest --tests 'com.music.bitchord.data.settings.*' --tests 'com.music.bitchord.download.HqLibraryMeteredPolicyTest' --console=plain -q` → 통과.
- `./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.sources.HqLibraryPolicyTest' --tests 'com.music.bitchord.data.SongInfo*' --tests 'com.music.bitchord.playback.SyncSafetyPolicyTest' --console=plain -q` → 통과.
