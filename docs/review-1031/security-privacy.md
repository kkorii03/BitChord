# 보안·개인정보 검토 (2026-10-30)

기준: `task/fix-1030` 계열 작업 트리, 현재 브랜치 `dsq/review-privacy-1031`.
범위: APK에 들어가는 자격 증명, 로그·진단 내보내기, 로그인 쿠키 저장, 외부 서버로 나가는 데이터, 시크릿 모드, http 요청, 설정/백업 내보내기.
코드 수정은 HIGH/MED 중 원인이 분명하고 1~2개 파일 안에서 고칠 수 있는 것만 했다. 재생 핵심 경로(곡 해석·버전·가사 싱크·크로스페이드)는 건드리지 않았다.

## 1. 이전 감사 문서 확인

| 이전 항목 | 출처 | 현재 상태 | 근거 |
|---|---|---|---|
| 앱 자체 Backup이 Deepgram 키를 내보냄 | `docs/cleanup/features.md` 4.1 | 고쳐짐 | `AppSettings.SECRETS`에 `deepgram_api_key` 포함. `BackupSecretsAndPrefsTest` 통과 |
| 앱 자체 Backup이 세션·API 키를 제외 | `docs/audit/check-data.md` "확인했지만 문제 없음" | 고쳐짐 | `AppSettings.exportPrefs()`가 `SECRETS`를 필터. 백업 테스트 통과 |
| Android 자동 백업이 `bitchord_settings.xml`의 API 키를 포함할 수 있음 | `docs/audit/check-data.md` #7 주석 | **이번에 고침** | 비밀 키를 `AuthStore`의 암호화 저장소로 이전하고, 그 파일은 양쪽 백업 규칙에서 제외 |
| 설정 공유가 프록시 비밀번호를 포함 | `docs/audit/check-data.md` "확인했지만 문제 없음" | 해당 없음(의도) | `SettingsTransfer.ProxyTransfer`가 비밀번호를 포함하고 공유 전 경고를 띄움 (`strings.xml:241`) |
| YouTube 로그인 쿠키 폴링 | `docs/audit/audit-battery.md:124` | 고쳐짐 | `YtMusicLoginScreen`은 `onPageFinished`/`doUpdateVisitedHistory` 페이지 이벤트만 사용. 타이머 없음 |
| 로그인 WebView를 닫을 때 destroy | `docs/audit/audit-battery.md:122` | 고쳐짐 | `onRelease`에서 `stopLoading()`·`destroy()` 호출 (`YtMusicLoginScreen.kt:151-159`) |
| 진단 로그에 프록시 주소·비밀번호·쿠키가 남지 않아야 함 | `docs/upstream/UPSTREAM-MERGE.md:206` | 부분 고침 | 내보내기는 `LogRedactor`로 쿠키·인증·키·이메일·URL 쿼리를 제거. 프록시 로그는 라우트만 남김. 이번에 원본 로그 파일을 자동 백업에서 제외 |
| 익명 검색·추천에 쿠키가 실리지 않음 | `docs/AUDIT_FEATURES.md:223` | 확인 | `InnertubeSearchPrivacyTest` 통과 |

## 2. 새로 찾은 문제

값은 적지 않았다. 위치와 코드 근거만 적는다.

| 심각도 | 파일:줄 | 증거 | 사용자 영향 | 수정안 / 상태 |
|---|---|---|---|---|
| HIGH | `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt:3560`, `app/src/main/java/com/music/bitchord/auth/AuthStore.kt:154` | Gemini·Deepgram·PaxSenix·Last.fm 세션/API·ListenBrainz·Spotify `sp_dc` 키가 평문 `bitchord_settings`에 있었고, `backup_rules.xml`/`data_extraction_rules.xml`은 이 파일을 제외하지 않았다. | Android 자동 백업·기기 이전으로 API 키와 세션 키가 클라우드 사본에 들어간다. | **고침**: 비밀 키를 `AuthStore`의 암호화 저장소로 이전(`migrateSecretsToAuthStore`), 읽기/쓰기 경로를 그쪽으로 변경. `bitchord_auth`는 이미 양쪽 백업에서 제외된다. |
| HIGH | `app/src/main/java/com/music/bitchord/BitChordApplication.kt:90`, `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/TrackLog.kt:199` | 진단 로그는 `filesDir/logs`에 원본 그대로 기록된다. 내보내기에서만 `LogRedactor`가 적용되고, 백업 규칙에는 `file` 도메인 제외가 없었다. | 로그에 남은 서명된 미디어 URL·쿼리·곡 제목 등이 자동 백업으로 기기 밖에 복사될 수 있다. | **고침**: `backup_rules.xml`과 `data_extraction_rules.xml`의 cloud/device-transfer 양쪽에서 `domain="file" path="logs"` 제외. |
| MED | `app/src/main/java/com/music/bitchord/data/listentogether/ListenTogether.kt:730,901,1787` | 파티 멤버십 토큰(`party_token`)이 평문 `bitchord_listen_together` SharedPreferences에 저장되고, 이 파일도 백업 제외 목록에 없었다. | 파티 세션을 조작할 수 있는 토큰이 클라우드 백업/기기 이전에 포함된다. | **고침**: `bitchord_listen_together.xml`을 양쪽 백업 규칙에서 제외. (토큰 자체를 암호화 저장소로 옮기는 일은 후속 작업) |
| MED | `app/src/main/java/com/music/bitchord/data/listentogether/ListenTogether.kt:1554` | 파티 표시 이름이 `nickname → profile.name → account.name → email.substringBefore('@')` 순서였다. 마지막 폴백이 Google 계정 이메일의 로컬 파트를 외부 파티 서버로 보냈다. | 프로필/계정 이름이 비어 있는 계정에서 이메일 앞부분이 파티 멤버에게/서버에 노출된다. | **고침**: `partyDisplayName` 순수 함수로 분리. 폴백은 `nickname → profile.name → account.name → 공개 handle → "Listener"`. 이메일 폴백 제거. |
| MED | `app/src/main/java/com/music/bitchord/data/settings/SettingsTransfer.kt:255,348`, `app/src/main/java/com/music/bitchord/data/sources/SourceRegistry.kt:34,389`, `app/src/main/res/values/strings.xml:241` | 설정 공유 파일은 `SourceRegistry.transferSnapshot()`의 `SourceConfig.baseUrl`을 그대로 담는다. 애드온 URL은 경로에 사용자 토큰이 들어갈 수 있다(`AddonClient.kt:486` 주석). 공유 경고 문구는 프록시 비밀번호만 언급한다. | 사용자가 설정 파일을 공유하면 프록시 비밀번호뿐 아니라 토큰이 박힌 애드온 URL도 함께 나갈 수 있는데, 경고는 그 사실을 말하지 않는다. | **고침(2차)**: 소스 목록은 기능상 유지하되, 공유 경고에 프록시 비밀번호와 소스 주소의 개인 토큰을 함께 명시했다(`strings.xml:241`, `values-ko/strings.xml:1367`). |
| LOW | `app/src/main/java/com/music/bitchord/data/DiagnosticsExport.kt:127`, `app/src/main/java/com/music/bitchord/data/DiagnosticsState.kt:45` | 진단 내보내기 헤더에 `private listening` 상태와 현재 곡 id/제목이 들어간다. 시크릿 모드에서도 내보내면 현재 재생 곡이 파일에 남는다. | 사용자가 진단 로그를 공유할 때 시크릿 모드 중 들은 곡이 함께 나갈 수 있다. | **문서만**: 사용자가 직접 누르는 내보내기이고 진단에 필요하다. 시크릿 모드에서는 현재 곡 줄을 빼는 선택지를 후속 검토. |
| LOW | `app/src/main/java/com/music/bitchord/data/innertube/potoken/PoTokenWebView.kt:445`, `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/Innertube.kt:132`, `app/src/main/java/com/music/bitchord/data/canvas/TidalCanvas.kt:29`, `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/Musixmatch.kt:35`, `app/build.gradle.kts:103` | APK에는 Google 웹 API 키, Tidal 임베드 토큰, Musixmatch 폴백 서명 키가 상수로 들어 있고, CI 빌드는 Last.fm API 키/시크릿을 `BuildConfig`로 넣는다. Gemini/Deepgram 기본 키 리소스는 저장소에서 빈 문자열이다. | 위 키들은 서드파티 공개 클라이언트 키이거나 앱 자체 키라 사용자 계정 키는 아니다. 다만 Last.fm 시크릿은 APK에서 추출 가능하다. | **문서만**: 사용자별 Gemini/Deepgram/프록시 계정은 APK에 박혀 있지 않다. Last.fm 키는 서버 측 제한/회전 정책으로 관리해야 한다. |
| LOW | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/presence/Presence.kt:89` | Presence는 `id`(설치 UUID), `platform`, `open`만 보낸다. 계정·곡 정보는 없다. 다만 300초 주기 ping이다. | 개인정보 노출은 없음. 배터리 관점에서는 주기 전송이 남아 있다. | **문서만**: 이번 보안 작업에서는 변경하지 않음. |

## 3. 이번 수정 상세

### 3.1 비밀 키를 암호화 저장소로 이동

- `AuthStore`에 `secret(name)`/`setSecret(name, value)` 추가. 기존 `bitchord_auth` 암호화 파일을 그대로 쓴다.
- `AppSettings.init()`에서 `migrateSecretsToAuthStore()`를 먼저 실행한다. 이전 빌드가 `bitchord_settings`에 남긴 키를 암호화 저장소로 옮기고 평문 파일에서 제거한다. 여러 번 실행해도 안전하다.
- `AppSettings.readAll()`과 setter는 `authStore.secret(...)`/`putSecret(...)`을 쓴다. `SECRETS` 집합은 이전 백업 파일의 가져오기를 막는 용도로 유지했다.
- `exportPrefs()`/`importPrefs()`의 기존 제외 규칙은 그대로다. 기존 `BackupSecretsAndPrefsTest`도 통과한다.

### 3.2 자동 백업에서 로그·파티 토큰 제외

- `backup_rules.xml`: `bitchord_listen_together.xml`, `domain="file" path="logs"` 추가.
- `data_extraction_rules.xml`: cloud-backup과 device-transfer 양쪽에 같은 두 줄 추가.
- 기존 auth/sources/queue 제외 규칙은 삭제하거나 약화하지 않았다.

### 3.3 파티 표시 이름에서 이메일 제거

기존 조건과 새 조건의 진리표:

| nickname | profile.name | account.name | email local part | 공개 handle | 기존 결과 | 새 결과 |
|---|---|---|---|---|---|---|
| 있음 | any | any | any | any | nickname | nickname |
| 없음 | 있음 | any | any | any | profile.name | profile.name |
| 없음 | 없음 | 있음 | any | any | account.name | account.name |
| 없음 | 없음 | 없음 | 있음 | any | **email local part** | 공개 handle |
| 없음 | 없음 | 없음 | 없음 | 있음 | email local part(빈 값이면 null) | 공개 handle |
| 없음 | 없음 | 없음 | 없음 | 없음 | null(파티 참여 실패) | `"Listener"` |

즉 기존의 `nickname`, `profile.name`, `account.name` 항은 모두 유지했고, 이메일 폴백만 공개 handle과 중립 라벨로 바꿨다.

### 3.4 로그 URL 정리 보강

`LogRedactor`가 URL의 쿼리뿐 아니라 fragment와 `user:pass@` 형태의 userinfo도 제거하도록 보강했다. 프록시 자격 증명이 URL 형태로 로그에 들어와도 내보내기 파일에는 남지 않는다.

## 4. 확인했지만 문제 없음 / 의도된 동작

- **로그인 쿠키 저장**: YouTube 쿠키·Discord 토큰·WebDAV/SMB/프록시 비밀번호는 `AuthStore`의 `EncryptedSharedPreferences`에 있다. 평문 폴백 파일도 백업에서 제외된다 (`AuthStore.kt:24`, `backup_rules.xml`).
- **앱 자체 Backup**: `AppSettings.exportPrefs()`가 `SECRETS`와 `DEVICE_LOCAL`을 제외한다. Deepgram 키도 포함되어 있다.
- **설정 공유**: 프록시 비밀번호를 포함하는 것은 의도된 동작이며 공유 전 경고가 있다. 다만 애드온 URL 토큰 경고는 위 MED로 남겼다.
- **http 요청**: `AndroidManifest.xml:66`의 `usesCleartextTraffic="false"`로 평문 HTTP는 기본 차단된다. 사용자 입력 WebDAV/애드온/파티 URL에 `http://`를 허용하는 파서는 있으나 실제 전송은 cleartext 정책을 따른다.
- **외부 서버 데이터 범위**: 애드온 검색은 곡 제목/아티스트 질의만 보낸다(`AddonClient.kt:147`). Gemini는 가사 텍스트(`LyricsTranslation.kt:518-538`), Deepgram은 오디오와 언어(`Deepgram.kt:96-97`)만 보낸다. Presence는 설치 UUID·플랫폼만 보낸다. 계정 쿠키나 이메일이 이 요청들에 섞이지 않는다.
- **시크릿 모드**: YouTube 시청 기록, Last.fm/ListenBrainz 스크로블, Discord presence는 `PrivateListening`으로 차단된다. 로컬 감상 통계·취향·검색 기록은 "기기 밖으로 나가지 않는 기록"이라 의도적으로 유지된다. 파티는 사용자가 직접 참여한 실시간 공유라 별도 게이트가 없으며, 이번에는 문서에만 적었다.
- **진단 내보내기**: 쿠키는 이름만, 값은 기록하지 않는다(`DiagnosticsExport.kt:164,292`). URL 쿼리는 내보내기에서 제거된다.

## 5. 테스트

실행:

```bash
./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q
./gradlew :app:testProdReleaseUnitTest \
  --tests 'com.music.bitchord.data.LogRedactorTest' \
  --tests 'com.music.bitchord.data.settings.AppSettingsImportValidationTest' \
  --tests 'com.music.bitchord.ListenTogetherIdleHealthTest' \
  --console=plain -q
./gradlew :app:testProdReleaseUnitTest \
  --tests 'com.music.bitchord.data.stats.BackupSecretsAndPrefsTest' \
  --tests 'com.music.bitchord.data.settings.SettingsTransferEdgeCasesTest' \
  --tests 'com.music.bitchord.data.settings.SettingsTransferTest' \
  --console=plain -q
./gradlew :shared:jvmTest \
  --tests 'com.music.bitchord.data.innertube.InnertubeSearchPrivacyTest' \
  --console=plain -q
```

새/보강 테스트:
- `LogRedactorTest.proxyCredentialsInUrlAreRedactedAndFragmentIsDropped`
- `AppSettingsImportValidationTest.legacy secrets move from the plain settings file into the encrypted store`
- `AppSettingsImportValidationTest.settings setters keep credentials out of the plain settings file`
- `ListenTogetherIdleHealthTest.party name never falls back to the account email`

## 6. 남은 일 (1차 시점)

1. ~~설정 공유 파일의 애드온 URL 토큰 경고/선택 UI (MED, 문서만).~~ 2차에서 경고 문구를 보강했다(7절).
2. 파티 토큰도 `AuthStore` 암호화 저장소로 옮기는 후속 정리 (현재는 백업 제외로 완화).
3. 시크릿 모드에서 진단 내보내기의 현재 곡 줄을 뺄지 결정 (LOW).
4. Last.fm API 시크릿의 APK 추출 가능성은 서버 측 키 제한/회전 정책으로 관리 (LOW).

## 7. 2차 검토 (2026-10-31)

이전 문서에서 `문서에만`으로 남긴 MED와, 2차에서 새로 확인한 경로를 다룬다. 값은 적지 않았고 위치와 코드 근거만 적는다.

### 7.1 이전 감사 항목 재확인

| 이전 항목 | 출처 | 현재 상태 | 근거 |
|---|---|---|---|
| Android 자동 백업이 `bitchord_settings.xml`의 API 키를 포함할 수 있음 | `docs/audit/check-data.md` #7 주석 | 고침 | 비밀 키를 `AuthStore`의 암호화 저장소로 이전(`AppSettings.kt:2847`), `SECRETS`(`AppSettings.kt:3560`), 두 백업 규칙에서 auth/sources/party/logs 제외 |
| 앱 자체 Backup이 세션·API 키를 제외 | `docs/audit/check-data.md` "확인했지만 문제 없음" | 고침 | `exportPrefs()`가 `SECRETS`와 `DEVICE_LOCAL`을 필터(`AppSettings.kt:2880`). `BackupSecretsAndPrefsTest` 통과 |
| YouTube 로그인 쿠키 폴링 | `docs/audit/audit-battery.md:124` | 고침 | `YtMusicLoginScreen`은 `onPageFinished`/`doUpdateVisitedHistory` 페이지 이벤트만 사용(`YtMusicLoginScreen.kt:117,123`). 타이머 없음 |
| 로그인 WebView를 닫을 때 destroy | `docs/audit/audit-battery.md:122` | 고침 | `onRelease`에서 `stopLoading()`·`destroy()` 호출(`YtMusicLoginScreen.kt:152-159`) |
| 진단 로그에 프록시 주소·비밀번호·쿠키가 남지 않아야 함 | `docs/upstream/UPSTREAM-MERGE.md:206` | 부분 고침 → 2차 보강 | 내보내기는 `LogRedactor` 적용, 원본 로그 파일은 자동 백업에서 제외. 2차에서 클립보드 복사 경로도 `LogRedactor`를 거치게 했다(`MainActivity.kt:5192`) |
| 익명 검색·추천에 쿠키가 실리지 않음 | `docs/AUDIT_FEATURES.md:223` | 확인 | `InnertubeSearchPrivacyTest` 통과 |
| 설정 공유가 프록시 비밀번호를 포함 | `docs/audit/check-data.md` "확인했지만 문제 없음" | 해당 없음(의도) | `SettingsTransfer.ProxyTransfer`가 비밀번호를 포함하고 공유 전 경고를 띄운다. 2차에서 소스 URL 토큰까지 경고에 명시 |
| 설정 공유 경고가 애드온 URL 토큰을 말하지 않음 | 이 문서 2절 MED | 고침(2차) | `settings_transfer_send_warning`에 프록시 비밀번호와 소스 주소의 개인 토큰을 함께 명시(`strings.xml:241`, `values-ko/strings.xml:1367`) |

### 7.2 2차에서 새로 찾은 문제

| 심각도 | 파일:줄 | 증거 | 사용자 영향 | 수정안 / 상태 |
|---|---|---|---|---|
| MED | `app/src/main/java/com/music/bitchord/MainActivity.kt:5192` | 곡 메뉴의 "로그 복사"가 `TrackLog.forTrack(song, ...)` 결과를 그대로 클립보드에 넣었다. 이 문자열에는 원본 로그가 들어 있어 `JioSaavnSource.kt:88`·`ModuleManager.kt:318` 같은 줄의 서명된 스트림 URL과 쿼리가 남는다. | 사용자가 로그를 복사해 채팅·이슈에 붙이면 서명 URL(일시적 재생 권한)이 기기 밖으로 나간다. | **고침**: `LogRedactor.redact(...)`를 거친 뒤 복사하도록 변경. 내보내기와 같은 규칙을 쓴다. |
| MED | `app/src/main/java/com/music/bitchord/data/DiagnosticsExport.kt:41` | 기존 `AUTHORIZATION` 정규식은 `Bearer`만 스킴으로 인식하고 나머지는 `[^\s,;]+`로 첫 단어만 지웠다. `Authorization: Token <key>`는 "Token"만, `Proxy-Authorization: Basic <base64>`는 "Basic"만 지우고 실제 자격 증명을 남긴다. Deepgram·ListenBrainz가 `Token` 스킴을, WebDAV가 `Basic`을 쓴다(`Deepgram.kt:96`, `ListenBrainzManager.kt:45`, `WebDavConfig.kt:45-50`). | 자격 증명이 진단 내보내기/복사 로그에 남을 수 있다. | **고침**: 임의의 `[A-Za-z][A-Za-z0-9_-]*` 스킴을 함께 지우도록 변경. `LogRedactorTest.tokenAndBasicAuthorizationSchemesAreFullyRedacted` 추가. |
| MED | `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:9975` | `onDestroy`의 마지막 ListenBrainz 제출은 `listenBrainzSong`/`listenBrainzStartMs`만 확인하고 `PrivateListening`을 보지 않았다. 같은 파일의 `submitListenBrainzFinished`(`:9869`)는 게이트가 있지만 이 경로는 `ListenBrainzManager.submitFinished`를 직접 호출한다. 상태 변경 핸들러가 private 여부와 무관하게 `listenBrainzSong`을 채우므로(`:1257`), 시크릿 모드 재생을 스와이프로 종료하면 마지막 곡이 제출될 수 있었다. | 시크릿 모드에서 들은 곡이 ListenBrainz 기록으로 나간다. | **고침**: `PrivateListening.allowsLastChanceScrobble` 게이트를 `onDestroy`와 상태 변경 양쪽에 적용. `PrivateListeningTest.last chance scrobble stays silent in private listening` 추가. |
| LOW | `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:1257` | 상태 변경 핸들러가 private 여부를 보지 않고 `listenBrainzSong`/시작 시각을 기록했다. onDestroy 게이트로 제출은 막히지만, 필드 자체가 private 재생을 기억했다. | 메모리에만 남고 전송은 막히지만, 향후 다른 제출 경로가 추가되면 위험하다. | **고침**: 위 MED 수정에서 같은 헬퍼로 기록 자체를 막았다. |
| LOW | `app/src/main/java/com/music/bitchord/data/DiagnosticsExport.kt:49` | 기존 URL 정규식은 `http(s)`만 처리해 `wss://host/ws?token=...`의 쿼리가 남을 수 있었다. | 파티 소켓 주소가 복사/내보내기 로그에 남을 수 있다. | **고침**: `wss?`까지 같은 규칙 적용. `LogRedactorTest.websocketQueryIsDroppedToo` 추가. |
| LOW | `app/src/main/java/com/music/bitchord/data/DiagnosticsExport.kt:161`, `app/src/main/java/com/music/bitchord/data/DiagnosticsState.kt:42` | 내보내기 헤더의 `current:` 줄에 현재 곡 id/제목이 들어가고, 시크릿 모드에서도 빠지지 않는다. | 사용자가 진단 로그를 공유할 때 시크릿 모드 중 들은 곡이 함께 나갈 수 있다. | **문서만**: 사용자가 직접 누르는 내보내기이고 진단에 필요하다. 시크릿 모드에서는 현재 곡 줄을 빼는 선택지를 후속 검토. |
| LOW | `app/src/main/java/com/music/bitchord/data/webdav/WebDavConfig.kt:21`, `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt:2600` | `normalizeUrl`는 `user:pass@host` 형태의 userinfo를 그대로 통과시키고 `setWebDavUrl`은 그 문자열을 평문 `bitchord_settings`에 저장한다. `exportPrefs`/자동 백업에도 포함된다. | 사용자가 WebDAV 주소 칸에 자격 증명이 포함된 URL을 붙여 넣으면 비밀번호가 평문 설정·백업에 남는다. | **문서만**: `setWebDavUrl`/`normalizeUrl`에서 userinfo를 거부하거나 분리해 암호화 저장소로 보내는 후속 정리. 별도 아이디/비밀번호 입력칸이 이미 있다. |
| LOW | `app/src/main/java/com/music/bitchord/data/listentogether/ListenTogether.kt:901` | `party_token`이 평문 `bitchord_listen_together` SharedPreferences에 남는다. 두 백업 규칙에서 이 파일을 제외해 클라우드 사본은 막았다. | 루팅된 기기·앱 데이터 접근이 가능한 환경에서는 파티 세션 토큰이 평문으로 보인다. | **문서만/후속**: `AuthStore` 암호화 저장소로 이전. 이번에는 기존 백업 제외를 유지했다. |
| LOW | `app/src/main/java/com/music/bitchord/data/innertube/potoken/PoTokenWebView.kt:445`, `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/Innertube.kt:132`, `app/src/main/java/com/music/bitchord/data/canvas/TidalCanvas.kt:29`, `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/Musixmatch.kt:35`, `app/build.gradle.kts:103` | APK에 Google 웹 API 키, Tidal 임베드 토큰, Musixmatch 폴백 서명 키가 상수로 들어 있고, CI 빌드는 Last.fm API 키/시크릿을 `BuildConfig`로 넣는다. Gemini/Deepgram 기본 키 리소스는 빈 문자열이다(`strings.xml:1350-1351`). | 서드파티 공개 클라이언트 키이거나 앱 자체 키라 사용자 계정 키는 아니다. Last.fm 시크릿은 APK에서 추출 가능하다. | **문서만**: 사용자별 Gemini/Deepgram/프록시 계정은 APK에 박혀 있지 않다. Last.fm 키는 서버 측 제한/회전 정책으로 관리. |

### 7.3 테스트

```bash
./gradlew :shared:jvmTest \
  --tests 'com.music.bitchord.data.settings.PrivateListeningTest' \
  --console=plain -q
./gradlew :app:testProdReleaseUnitTest \
  --tests 'com.music.bitchord.data.LogRedactorTest' \
  --console=plain -q
./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q
```

모두 통과. 새/보강 테스트:
- `LogRedactorTest.tokenAndBasicAuthorizationSchemesAreFullyRedacted`
- `LogRedactorTest.websocketQueryIsDroppedToo`
- `PrivateListeningTest.last chance scrobble stays silent in private listening`

### 7.4 남은 일

1. 파티 토큰을 `AuthStore` 암호화 저장소로 옮기는 후속 정리 (현재는 백업 제외로 완화, MED).
2. 시크릿 모드에서 진단 내보내기의 현재 곡 줄을 뺄지 결정 (LOW).
3. 원본 로그 파일은 여전히 기기 안에서만 평문이지만 자동 백업에서 제외되고, 복사·내보내기는 `LogRedactor`를 거친다. 원본 로그 자체를 쓸 때 마스킹할지는 후속 (LOW).
4. Last.fm API 시크릿의 APK 추출 가능성은 서버 측 키 제한/회전 정책으로 관리 (LOW).
5. WebDAV 주소에 userinfo가 들어온 경우 평문 설정·백업에 남지 않도록 `setWebDavUrl`/`normalizeUrl`에서 분리·거부하는 후속 정리 (LOW).
6. `Presence`의 300초 주기 ping은 개인정보 노출은 없지만 배터리 관점에서 이벤트 기반으로 바꿀 후속 검토 (LOW).
