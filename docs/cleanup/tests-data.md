# tests-data — 저장·설정 테스트 보강

작업 기준: 현재 HEAD. 운영 코드는 한 줄도 바꾸지 않았고, 앱 테스트
(`app/src/test`)와 이 문서만 추가했다. shared 테스트는 추가하지 않았다.

## 추가한 테스트

앱 단위 테스트 5개 클래스, 총 51개. 그중 48개 통과, 3개는 아래 발견된
버그를 고정하기 위해 `@Ignore`로 남겼다.

- `data/settings/AppSettingsDefaultsTest.kt` (7)
  - `AudioQuality.permits` 전체 진리표: LOSSLESS는 모든 소스, HIGH는
    lossless 불가 소스만, MEDIUM/LOW는 YouTube만.
  - YouTube fallback 비트레이트 사다리(LOW 64, 나머지 무제한), 다운로드
    등급의 `keepsLossless`, 캐시·가사 오프셋·업그레이드 slack·핀 한도·
    기본 국가 상수.
  - `normalizeSignatureApiUrl`의 trim, 끝 슬래시 제거, 500자 상한.
- `data/settings/SettingsTransferEdgeCasesTest.kt` (17, 2 ignored)
  - 다른 앱 태그·깨진 JSON 거부.
  - `TransferSpec.normalize` 경계: BOOLEAN/INT/LONG/FLOAT의 캐리어 타입,
    enum 문자열 대소문자, STRING_LIST의 trim·빈 값·전부 불명 거부,
    STRING_SET 필터, FLOAT_LIST의 비숫자 거부.
  - 잘못된 설정 한 줄만 건너뛰고 나머지 적용, 소스 trim·중복 제거·빈 id
    UUID 보충·MODULE/불완전 소스 제외, 프록시 정규화와 잘못된 mode/scope/
    port 제외.
  - 미리보기의 숫자 값 비교(Int vs Double), 소스 순서 변경 감지, 빈 소스
    가져오기는 변경 아님, 프록시 동일/변경, skipped 합산.
  - `buildFile`이 화이트리스트·지원 타입만 쓰고 메타데이터를 기록하며
    비밀 키를 파일에 넣지 않는지, 다시 읽었을 때 값이 보존되는지.
- `data/stats/BackupSecretsAndPrefsTest.kt` (11, 1 ignored)
  - `AppSettings.exportPrefs`가 8개 자격 증명과 6개 기기 로컬 키를 모두
    제외하는지.
  - 그 결과로 만든 백업이 JSON 왕복 후에도 비밀/기기 로컬 키 없이 같은
    설정을 복원하는지.
  - `PrefValue`가 Boolean/Int/Long/Float/String/StringSet을 정확한 타입으로
    왕복하고, 숫자를 문자열로 기록하며, 알 수 없는 값·깨진 숫자는 null인지.
  - schema 1의 옛 JSON과 알 수 없는 필드 읽기, 깨진 JSON 거부, schema 2의
    빈 재생목록 id/곡 videoId 검증, 최신 schema 거부.
- `playback/smart/HqVerificationStoreFormatTest.kt` (8)
  - 검증 쌍의 `key\toffset\trate\tmatch\ttier` 한 줄 형식과 재로딩.
  - 옛 줄은 읽고, 필드 수 부족·숫자/등급 파싱 실패 줄은 개별로 건너뛰는지.
  - 키의 개행/탭이 공백으로 정규화되는지, source/track id가 빈 항목은
    `entries`에서 제외되는지.
  - 한 영상의 여러 쌍 중 최고 등급 선택, 거절이 검증을 대체하고 새 검증이
    거절을 지우는지, 거절 줄의 재로딩.
- `playback/smart/LyricsTimingSavedEntryTest.kt` (8)
  - ko-21/22 시절 필드(`autoChecked`, `autoShiftMs`, `aiTextKey`,
    `aiShiftMs`, `aiStarts`)만 있는 항목이 로드되고, 옛 `aiShiftMs`가
    `timing`에 그대로 적용되는지.
  - 알 수 없는 필드 무시, `manualMs` 없음=0, 깨진 JSON/잘못된 shape=빈
    저장소, 다른 id에는 항목이 새지 않는지.
  - `setManual`이 실제 파일에 `manualMs`를 쓰고 새 Store에서 다시 읽히는지,
    ±`MAX_MANUAL_MS`로 clamp되는지.

테스트 보조 파일: `app/src/test/java/com/music/bitchord/testutil/FakeAndroid.kt`
(in-memory `SharedPreferences`, 생성자를 우회한 `Context`). 테스트 클래스가
아니므로 CI 목록에는 넣지 않아도 된다.

## 발견된 버그

운영 코드는 고치지 않았고, 각각의 테스트에 이유를 적어 `@Ignore`했다.

1. **`Backup`이 app 태그 없는 JSON을 백업으로 받아들인다.**
   `BackupFile.app`의 `@Serializable` 기본값이 `"bitchord"`라 `{}`도
   `decode`·`validate`를 통과한다. `importFrom`은 이를 schema 1 빈 백업으로
   보고 `AppSettings.importPrefs(emptyMap())`을 호출하므로, 잘못 고른 JSON
   한 개가 설정을 지울 수 있다.
   `BackupSecretsAndPrefsTest.a document without the app tag is not a backup`.
2. **`SettingsTransfer`도 app 태그 없는 JSON을 설정 파일로 받아들인다.**
   같은 기본값 때문에 `{}`가 `decode`를 통과한다. 이쪽은 빈 가져오기라
   파괴적이진 않지만 "BitChord 설정 파일이 아닙니다" 계약과 어긋난다.
   `SettingsTransferEdgeCasesTest.a document without the app tag is not a settings file`.
3. **`SettingsTransfer.ProxyTransfer.toConfig`가 사용할 수 없는 프록시를
   통과시킨다.** 포트를 `0..65535`로만 확인해 HTTP/SOCKS5 모드에서 포트 0과
   빈 호스트를 허용한다. 미리보기는 변경으로 표시하고, 적용하면
   `ProxyValidation`이 unusable로 판정하는 설정이 저장된다.
   `SettingsTransferEdgeCasesTest.an enabled proxy without a host or a real port is not a configuration`.

## 실행

```bash
./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q
./gradlew :app:testProdReleaseUnitTest \
  --tests 'com.music.bitchord.data.settings.AppSettingsDefaultsTest' \
  --tests 'com.music.bitchord.data.settings.SettingsTransferEdgeCasesTest' \
  --tests 'com.music.bitchord.data.stats.BackupSecretsAndPrefsTest' \
  --tests 'com.music.bitchord.playback.smart.HqVerificationStoreFormatTest' \
  --tests 'com.music.bitchord.playback.smart.LyricsTimingSavedEntryTest' \
  -Pbitchord.arm64Only=true --console=plain -q
```

결과: 51개 중 48 통과, 3 skipped(`@Ignore`), 실패 0.
