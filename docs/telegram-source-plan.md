# 텔레그램 소스 이식 검토서 (조사·계획만)

- 작성일: 2026-10-31
- 검토 대상: `Hexclyde/bitchord-morphe-patches` v1.0.20 (커밋 `a6a8e4a`, GPL-3.0-or-later, BitChord 1.8.1 전용 Morphe 패치)
- 검토 기준: 이 러너의 현재 체크아웃 `0874095` (`versionName 1.8.1`, `versionCode 29`). 요청에 적힌 `task/fix-1030` 브랜치는 이 러너에 없어 현재 HEAD를 기준으로 대응 지점을 적었다.
- 이번 작업은 문서만 추가했다. 앱 코드, `.github/`, `keys/`, 서명 설정은 건드리지 않았다.

## 결론 먼저

**기술적으로는 이식 가능하다.** 라이선스도 우리 앱(GPL-3.0)과 호환된다. 다만 패치는 1.8.1 APK의 **난독화된 smali 지점에 바이트코드를 끼워 넣는 방식**이라 그대로 복사할 수는 없고, 같은 역할을 우리 Kotlin 소스 구조에 다시 구현해야 한다.

권장안은 **패치 전체를 옮기지 않고, 텔레그램을 우리 앱 안의 첫 번째 소스(`SourceKind.TELEGRAM`)로 최소 이식**하는 것이다. 검색·인증·재생 코어만 옮기고, 패치의 부가 기능(채널 뷰, zip/cue, 이름 교정, 메인 채널 복사, 포맷 범례, 다운믹스, 부드러운 품질 전환)은 1차 범위에서 제외한다. 우리 포크에는 이미 `SourceResolver.upgradeFor`, `hq-verify`, 다음 곡 준비가 있으므로 텔레그램 결과를 일반 소스 후보로 넣기만 하면 기존 품질 업그레이드 경로를 그대로 탄다. 패치의 `SmoothUpgrade`·`VersionAlign`·`Downmix` 훅은 우리 코드와 충돌하고 안전 규칙(특히 폰 스피커 PCM16 상한)을 건드릴 수 있으므로 이식하지 않는다.

---

## ① 패치가 거는 지점과 우리 포크의 대응 지점

패치의 진입점은 `patches/src/main/kotlin/app/bitchord/patches/TelegramPatch.kt` 하나다. 실제 기능 코드는 `extensions/telegram/src/main/java/app/bitchord/telegram/`에 있고, 패치가 1.8.1 APK의 아래 클래스/메서드에 정적 호출을 삽입한다.

| 패치가 거는 1.8.1 지점 | 패치가 하는 일 | 우리 포크의 대응 지점 (파일:줄) | 이식 방향 |
|---|---|---|---|
| `BitChordApplication.onCreate()` | 앱 시작 때 `TelegramBridge.init(context)` 호출, TDLib 네이티브 로드·클라이언트 생성 | `app/src/main/java/com/music/bitchord/BitChordApplication.kt:90` (`SourceRegistry.init`은 `:167`) | 앱 시작 시 초기화하지 않는다. 설정 화면을 열거나 첫 텔레그램 검색/재생 때 지연 초기화한다(배터리 원칙). |
| `PlaybackService`의 `smoothSwapCurrentTrackVersion`, `spare`, `versionSwapJob/Generation`, `prepareVersionAlignment` | 품질 업그레이드를 기존 2-플레이어 전환에 태움 | `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:728`(`spare`), `:2789-2790`(job/generation), `:2798`(`smoothSwapCurrentTrackVersion`), `:3163`(`prepareVersionAlignment`) | **이식하지 않음.** 우리 `SourceResolver.upgradeFor`가 이미 텔레그램 스트림을 후보로 고를 수 있다. |
| `PrecisionAudioSink.configure/handleBuffer/getCurrentPositionUs` | 5.1/7.1 → 스테레오 다운믹스, 위치 보정 | `app/src/main/java/com/music/bitchord/playback/audio/PrecisionAudioSink.kt:163`, `:292`, `:539` | **이식하지 않음.** 다운믹스는 기존 안전 규칙(폰 스피커 PCM16 상한, `AudioOutputPolicy.kt:20-23`, `OutputNegotiator.kt:111-112`)을 건드릴 수 있다. |
| `SongTextKt.ExplicitSongTitle` | 제목 옆 포맷 범례 | `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/SongText.kt:44` | 1차 제외. 나중에 별도 UI 작업으로. |
| `MainViewModel$SearchRequest.<init>` | 메인 검색어를 텔레그램에도 보내 범례 매칭 | `app/src/main/java/com/music/bitchord/ui/MainViewModel.kt:2520` | 1차 제외. 메인 검색마다 텔레그램 검색을 보내면 배터리·rate limit에 불리하다. |
| `TrackMatcher.best/ranked` | 텔레그램 행에 더 엄격한 제목 검사를 앞단에 추가 | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/sources/TrackMatcher.kt:253`, `:915` | 우리 `TrackMatcher`를 그대로 쓴다. 텔레그램 행이 일반 `Song`으로 들어오면 기존 검사가 적용된다. |
| `Downloads$run$2`의 Addon IO 코루틴 | `https://telegram.bitchord.invalid/...` 가짜 애드온 주소를 가로채 JSON 응답 | `app/src/main/java/com/music/bitchord/data/sources/addon/AddonClient.kt:347`(`fetch`), `:373`(`body`) | 애드온 경로를 재사용할 경우 이 지점에 내부 응답 분기를 둔다. 전용 소스로 갈 경우 불필요. |
| `ResolvingDataSource` 생성자 | 텔레그램 내부 URI를 `TelegramDataSource`로 라우팅 | `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:2019`(resolver), `:2322-2353`(factory 체인) | `ResolvingDataSource`/`DefaultDataSource` 체인에 내부 URI 라우터를 추가한다. |
| `WearBridge$sendToWatch`의 직접 다운로드 | 텔레그램 파일을 다운로드 경로에서 직접 받음 | `app/src/main/java/com/music/bitchord/download/Downloader.kt:261`(`fetchDirect`), `app/src/main/java/com/music/bitchord/download/Downloads.kt:2655` | 1차 제외(다운로드는 재생 안정화 후). |
| `SettingsSheetKt.SettingsRow` / `SettingsGroup` | 설정 화면에 텔레그램 행/섹션 추가 | `app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt:3027`(`SettingsRow`), `:2931`(`SettingsGroup`) | `AccountAndScrobblingScreen`에 전용 행을 추가한다. |
| `DiscordScreenKt.AccountAndScrobblingScreen` | 계정 설정 안에 삽입 | `app/src/main/java/com/music/bitchord/ui/screens/AccountAndScrobblingScreen.kt:50` | 같은 위치에 “Unofficial Telegram” 설정 진입점을 둔다. 한국어 문구는 기존 `보관함/플레이리스트/다음 항목 재생/나중에 재생/셔플` 표기와 합니다체를 따른다. |
| `SourcesScreenKt$$ExternalSyntheticLambda32` | 소스 행 탭을 텔레그램 설정으로 우회 | `app/src/main/java/com/music/bitchord/ui/screens/SourcesScreen.kt:236`, `:252`, `:750`(`SourceRow`) | 전용 `SourceKind.TELEGRAM`이면 일반 소스 행/편집기 경로를 그대로 쓸 수 있다. |
| `PlaybackService$playbackListener$1.onIsPlayingChanged` | 실제 재생 시작 시에만 텔레그램 항목 확인 | `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:1253` | 필요 시 재생 이벤트에서만 후처리한다. 폴링 없음. |
| `PlaybackService.onTrackBecameCurrent` | 현재 곡 전환 시 텔레그램 상태 갱신 | `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:4576` | 선택. 1차에서는 불필요. |
| `PlaybackService.access$publishNerdStats`, `NerdStats$Snapshot`, `PlayerControls.SleeveNerdStats` | 통계 화면에 실제 소스/비트레이트 표시 | `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:9088`, `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/NerdStats.kt:43`, `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerControls.kt:417` | 우리 `NerdStats`/`StreamFormat`에 맞춰 매핑되면 자동으로 표시된다. 별도 훅 불필요. |

### 우리 소스 등록·검색·재생 구조에서의 위치

우리 포크는 패치의 `SourceRegistry` 리플렉션(`NativeSource.java`) 대신 정식 `SourceKind`/`MusicSource` 구조를 쓴다.

- 소스 종류: `app/src/main/java/com/music/bitchord/data/sources/SourceKind.kt:16`
- 레지스트리·인스턴스 생성: `app/src/main/java/com/music/bitchord/data/sources/SourceRegistry.kt:107`(`configs`), `:277`(`activeForPlayback`), `:586`(`build`)
- 소스 인터페이스: `app/src/main/java/com/music/bitchord/data/sources/MusicSource.kt:123`(`MusicSource`), `:14`(`SourceStream`)
- 애드온 검색 탭: `app/src/main/java/com/music/bitchord/ui/MainViewModel.kt:122`(`SearchSource.ADDONS`), `:2407-2418`(활성 애드온 검색)
- 텔레그램 결과가 품질 업그레이드 후보가 되는 경로:
  - `SourceResolver.upgradeFor` — `app/src/main/java/com/music/bitchord/data/sources/SourceResolver.kt:731`부터 `SourceRegistry.activeForPlayback()`의 모든 소스를 `bestAcross`로 경쟁시킨다.
  - 다음 곡 준비 — `app/src/main/java/com/music/bitchord/playback/NextTrackPreparation.kt:756`(`prepareHq`)가 `AudioCache.awaitHqHead`를 기다리고, `AudioCache.kt:1851-1909`의 `SourceResolver.prefetchSubstitute`가 다음 곡 후보를 찾아 `HqAudioVerify.verify`로 검증한 뒤 핀한다.
  - 따라서 텔레그램이 `MusicSource`로 등록되고 `Song`/`SourceStream`의 형식·길이·`sourceConfigId`를 정확히 채우면, **`SourceResolver`/`hq-verify`/다음 곡 준비를 새로 고치지 않아도 기존 후보 경로에 들어간다.**
  - 단, 패치의 `TelegramMatch`/`FormatLegend.searching` 같은 APK 훅은 필요 없다. 우리 `TrackMatcher`와 `HqAudioVerify`가 같은 역할을 이미 한다.

---

## ② TDLib 네이티브 라이브러리

### 출처와 빌드 방식

- 패치는 Gradle 의존성이 아니라 **미리 빌드된 `.so`를 APK에 직접 넣는 방식**이다.
- 출처: `TGX-Android/tdlib` 커밋 `7ec241d026ee3563e615c25a700117c2abbaea19`의 `30.0.16248370/android-24/<abi>/` 아래 파일.
- TDLib 소스 커밋: `ea3f69ce11aef458bfbb09869b809ede4447486f`, OpenSSL 3.5.9.
- 네이티브 빌드: NDK `30.0.16248370`, `android-24`, ABI 3종(arm64-v8a, armeabi-v7a, x86_64).
- Java 바인딩: `vendor/tdlib-api/TdApi.java`(약 161k줄, 5.2MB), `vendor/tdlib-api/Client.java`.
- 우리 쪽 적용 위치는 `app/src/main/jniLibs/<abi>/`가 자연스럽다. 현재 이 디렉터리에는 `libffmpegJNI.so`만 있고 `libc++_shared.so`는 없다. 따라서 TDLib가 요구하는 `libc++_shared.so`도 함께 추가해야 한다.

### ABI별 크기(원본 파일, 비압축)

| 파일 | arm64-v8a | armeabi-v7a | x86_64 |
|---|---:|---:|---:|
| `libtdjni.so` | 14,600,992 | 13,733,896 | 18,209,632 |
| `libsslx.so` | 690,512 | 695,656 | 669,032 |
| `libcryptox.so` | 3,556,736 | 3,292,012 | 4,261,616 |
| `libc++_shared.so` (NDK r30) | 9,485,032 | 7,567,040 | 9,091,400 |
| **합계** | **28,333,272 (약 27.0 MiB)** | **25,288,604 (약 24.1 MiB)** | **32,231,680 (약 30.7 MiB)** |

### arm64만 넣을 때 APK 증가량

- **비압축 저장 기준 약 27.0 MiB**. 우리 앱은 `minSdk 26`이고 AGP 8.10.1 기본 패키징을 쓰므로 `.so`가 APK 안에 무압축·정렬 저장될 가능성이 높다. 이 경우 APK 증가량은 원본 합계와 거의 같다.
- 같은 4개 파일을 deflate로 압축하면 **약 11.1 MiB**(측정값 `11,638,710`바이트)다. 패치처럼 `android:extractNativeLibs="true"`로 두고 압축 저장하면 이 값에 가까워진다.
- 어느 쪽이든 **arm64 전용 APK 기준 대략 11~27 MiB 증가**로 계획하고, 실제 빌드에서 `apkanalyzer`/`unzip -l`로 확정한다. 3 ABI를 모두 넣으면 약 80MiB(비압축 합계)까지 늘어나므로 arm64 전용 빌드를 유지해야 한다.

### 주의

- TDLib `.so`는 16KB page size 정렬 등 Android 15+ 요구사항을 확인해야 한다.
- R8/난독화는 Java 바인딩과 JNI 콜백 이름을 건드리면 안 된다. `TdApi`/`Client`는 보존 규칙이 필요하다.
- 라이선스 고지(TDLib Boost, OpenSSL Apache, libc++ LLVM, NDK notice)를 `THIRD_PARTY_NOTICES`에 추가해야 한다.

---

## ③ 배터리·네트워크: 우리 원칙과 충돌하는 부분

패치는 “주기 폴링 금지, 이벤트 기반, 화면 꺼짐 배터리 최소” 원칙과 **여러 곳에서 충돌한다.** 그대로 옮기면 안 되고, 아래처럼 바꿔야 한다.

### 패치의 현재 동작

1. **앱 시작과 동시에 TDLib 초기화**
   - `BitChordApplication.onCreate`에서 `TelegramBridge.init`을 호출하고, 생성자에서 TDLib 클라이언트와 3개의 백그라운드 스레드(색인·가져오기·파일 검사)를 시작한다.
   - 사용자가 텔레그램을 한 번도 열지 않아도 네이티브 로드와 스레드가 생긴다.

2. **TDLib 연결 유지**
   - `TelegramLifecycle`은 앱이 백그라운드로 가면 `SetOption("online", false)`만 보내고 **클라이언트를 닫지 않는다**(`TelegramLifecycle.java`, `TelegramBridge.setForeground`).
   - 따라서 프로세스가 살아 있는 동안 Telegram DC와의 연결/업데이트 수신이 계속된다. TDLib 자체의 keepalive·업데이트 트래픽이 남는다.

3. **색인 스레드의 연속 루프**
   - 기본값 `searchIndex=true`. `runIndex()`는 `while(true)`로 돌며 페이지당 `PACE_MILLIS=2000`(최소 700ms, 최대 8000ms) 간격으로 채팅을 훑는다.
   - 다 훑은 뒤에도 60초마다 깨어나고, `REFRESH_MILLIS=6시간`마다 새 글을 보충하며, `REBUILD_MILLIS=30일`마다 전체 재구축한다.
   - 큰 채널은 최초 색인에 1시간 이상, 약 150MB 저장공간을 쓸 수 있다.

4. **그 밖의 주기/백그라운드 작업**
   - `runChecks()`는 파일 검사 스레드를 계속 돌린다(기본 `checkLossless=true`; 백그라운드 전체 검사는 기본 off).
   - `suggestTrackNames`가 켜져 있으면 다른 소스 곡이 재생될 때마다 텔레그램 검색을 보내고, 최소 20초 간격을 둔다.
   - `useSharedIndexes`가 켜져 있으면 채팅별 색인 파일을 찾아 내려받는다.
   - 포맷 범례가 켜져 있으면 메인 검색어마다 텔레그램 검색을 한 번 더 보낸다.

### 우리 원칙에 맞춘 이식 규칙

- **앱 시작 초기화 금지.** 설정 화면 진입, 사용자의 명시적 검색, 재생/다운로드 시작 시에만 TDLib를 연다.
- **백그라운드에서 클라이언트 닫기.** 텔레그램 재생·다운로드가 없고 앱이 백그라운드로 간 뒤 유예 시간이 지나면 `TdApi.Close`로 닫는다. 세션은 디스크에 남아 다음에 다시 연다. 재생 중에는 유지한다.
- **색인은 기본 off, 수동/이벤트 기반.** “지금 색인” 버튼 또는 사용자가 고른 채팅에 한해, 화면이 켜져 있고 Wi‑Fi/충전 조건을 만족할 때만 페이지를 진행한다. 6시간·30일 타이머는 두지 않는다. 필요하면 “새 글만 확인”도 사용자가 명시적으로 누를 때만.
- **메인 검색어를 자동으로 텔레그램에 보내지 않는다.** 포맷 범례용 `searching()` 훅은 1차에서 제외한다.
- **다른 소스 재생 중 이름 제안 검색 기본 off.** 켜더라도 재생 이벤트 1회, 세션당 곡별 1회, 최소 간격을 두고 화면이 켜져 있을 때만.
- **체크/다운로드/공유 색인 기본 off.** 재생 중인 파일 검증은 기존 `hq-verify`가 필요한 만큼만 한다.
- **주기 타이머 대신 이벤트.** TDLib 업데이트 콜백, 재생 시작/전환, 사용자의 검색·다운로드 요청을 신호로 쓴다. `while(true) + sleep` 루프는 두지 않는다.

---

## ④ 사용자가 해야 할 설정과 개인정보

### 설정 절차

1. **my.telegram.org 가입/로그인**: 텔레그램 계정으로 로그인하고 텔레그램 앱으로 온 코드를 입력한다.
2. **API 개발 도구**: `my.telegram.org/apps`에서 앱을 만들고 **App api_id**(숫자)와 **App api_hash**(32자리 16진수)를 받는다.
3. **앱에 자격 증명 입력**: `Settings → Account & integrations → Unofficial Telegram`에서 API ID/Hash를 저장한다. 패치 옵션에 넣지 말고 설치 후 앱 안에서 입력한다(APK에서 추출 가능해지기 때문).
4. **전화번호 로그인**: 국가번호를 포함한 전화번호 입력 → 텔레그램이 보낸 인증 코드 입력 → 필요하면 **2단계 비밀번호**, 이메일 인증, 다른 기기 확인까지 진행한다.
5. **채팅 선택**: `Music chats → Choose`에서 전체 채팅/폴더/보관함 중 하나를 고르고 음악 채널을 선택한다. 목록에 없으면 `@username` 또는 숫자 chat ID를 직접 추가한다. 초대 링크는 지원하지 않는다.
6. **검색/재생**: `Search → Addons`에서 제목·아티스트·파일명으로 검색하고 재생한다. 한 번에 최대 200곡.
7. **세션 해제**: 앱의 `Session → Sign out` 또는 텔레그램 공식 앱의 `Settings → Devices`에서 세션을 끊는다.

### 개인정보·저장 위치

- **API ID/Hash**: 앱 전용 저장소의 `application.sealed.json`에 **Android Keystore의 AES-GCM 키**로 암호화해 저장한다(`CredentialStore.java`, `CredentialCipher.java`). 백업·색인 파일에는 넣지 않는다.
- **텔레그램 세션/데이터베이스**: `context.getNoBackupFilesDir()/telegram-native/db`, `.../files`에 TDLib가 저장한다. 데이터베이스 키도 Android Keystore로 보호한다(`TelegramBridge.databaseKey()`).
- **선택 채팅·색인·교정 이름**: 앱 전용 저장소. 로그아웃하면 채팅 선택·색인·설정이 지워진다.
- **스크린샷 차단**: 설정 화면은 `FLAG_SECURE`로 캡처를 막는다.
- **권한 범위**: “음악만” 권한이 아니라 **일반 텔레그램 클라이언트 세션 전체 권한**이다. 채팅 선택은 검색 범위를 줄일 뿐 계정 권한을 줄이지 않는다.
- **텔레그램이 보는 것**: 로그인, 선택 채팅 검색, 미디어 요청. 기능을 켜면 앨범 커버·파일 헤더(64KB)·AcoustID 지문·공유 색인 다운로드 등이 추가로 나간다.
- **금지**: API hash, 로그인 코드, 비밀번호, 세션 파일, 비공개 채널 링크를 로그/이슈/백업에 남기지 않는다.

---

## ⑤ 옮기는 방법 두 가지 비교와 추천

### (a) 코드를 우리 앱의 새 소스로 이식

**방식**: `SourceKind.TELEGRAM` + `TelegramSource : MusicSource`를 추가하고, TDLib 브리지·인증·검색·스트리밍 DataSource를 앱 안에 둔다. 애드온 HTTP 계층을 재사용하지 않고 내부 URI(`bitchord://telegram/...`)를 `PlaybackService`의 DataSource 체인에서 직접 라우팅한다.

- 장점
  - 로컬 HTTP 서버가 없다. `.invalid` 가짜 주소나 localhost cleartext 예외가 필요 없다.
  - 한 프로세스에서 재생·캐시·다음 곡 준비와 직접 연결된다. Range 요청, 취소, 세션 변경 검사를 DataSource 안에서 정확히 할 수 있다.
  - 기존 `SourceResolver`/`hq-verify`/`NextTrackPreparation` 후보 경로에 자연스럽게 들어간다.
  - 세션·자격 증명을 앱의 Keystore/EncryptedPrefs 체계에 맞출 수 있다.
- 단점
  - 앱 코어(`SourceKind`, `SourceRegistry`, `PlaybackService` DataSource 체인, 설정 UI)를 수정해야 한다.
  - TDLib 네이티브·바인딩으로 APK가 커지고, TDLib 스레드/세션 수명을 우리 배터리 원칙에 맞춰 직접 관리해야 한다.
  - 1.8.1 패치의 smali 훅을 그대로 쓸 수 없어 Java 코드를 Kotlin 구조에 맞게 다시 배선해야 한다.

**세부 변형**: 처음부터 전용 `SourceKind`를 만들지 않고, 패치처럼 `AddonSource`/`AddonClient` 뒤에 내부 응답 분기를 넣어 “가짜 애드온”으로 시작할 수도 있다. 이 경우 코어 수정은 적지만, 소스 편집기·URL 노출·`allowDownloads`/`checkValidLossless` 정책이 어색해지고, 내부 URI를 결국 DataSource에서 따로 라우팅해야 한다. **장기적으로는 전용 소스가 낫다.**

### (b) 사용자 폰에서 별도 애드온 서버를 띄움

**방식**: TDLib를 가진 별도 앱/프로세스가 `localhost` HTTP 애드온(`/manifest.json`, `/search`, `/stream/{id}`)을 제공하고, 우리 앱에는 그 주소를 애드온으로 등록한다.

- 장점
  - 우리 앱의 소스/재생 코어를 거의 바꾸지 않는다. 기존 `AddonSource`가 그대로 쓴다.
  - 텔레그램 기능을 앱과 분리해 장애·권한 범위를 나눌 수 있다.
  - 다른 플레이어나 디버깅 도구에서도 같은 서버를 쓸 수 있다.
- 단점
  - **별도 앱/프로세스 + 포그라운드 서비스 + 로컬 서버**가 필요해 배터리·유지보수 부담이 더 크다. 재생 중이 아닐 때도 서버/서비스 수명을 관리해야 한다.
  - 우리 앱은 `usesCleartextTraffic="false"`라서 localhost HTTP를 쓰려면 네트워크 보안 예외가 필요하고, HTTPS로 하면 자체 서명 인증서 문제가 생긴다.
  - 로컬 HTTP를 한 번 더 거치므로 Range/취소/에러 전달이 한 겹 늘고, 큰 파일 스트리밍 시 복사·버퍼링 비용이 생긴다.
  - TDLib 네이티브 크기(27MiB)는 어차피 별도 앱에 들어가고, 사용자는 앱을 두 개 설치·설정해야 한다.
  - 텔레그램 세션·API 자격 증명이 별도 앱에 생겨 개인정보 관리 지점이 늘어난다.

### 추천안

**(a) 전용 `SourceKind.TELEGRAM` 최소 이식**을 추천한다. 단, 아래 순서로 나누고 각 단계의 완료 조건을 통과한 뒤 다음 단계로 간다. 패치의 부가 기능은 1차에서 제외한다.

#### 단계별 작업 목록과 완료 조건

| 단계 | 작업 | 완료 조건 |
|---|---|---|
| 0. 법적/기술 스파이크 | GPL 고지·THIRD_PARTY_NOTICES 추가, TGX TDLib `.so`와 바인딩을 arm64만 `jniLibs`에 넣고 로드 테스트 | arm64 APK가 빌드되고 앱 시작 없이 `System.loadLibrary` 스파이크에서 4개 `.so`가 로드된다. APK 증가량이 측정값(11~27MiB) 범위에 있다. |
| 1. TDLib 브리지·인증 | `TelegramBridge` 축소판: API ID/Hash 저장(Keystore), 전화번호/코드/2단계 비밀번호 로그인, 세션 경로, 로그아웃. 앱 시작 초기화 없음 | 앱 재시작 후에도 로그인 세션이 유지된다. 로그아웃 시 로컬 세션·선택 채팅이 지워지고 공식 앱에서 세션이 끊긴다. 백그라운드 전환 시 클라이언트가 닫힌다. |
| 2. 채팅 선택 저장 | 채팅/폴더/보관함 목록, 선택 저장(EncryptedPrefs), `@username`/숫자 ID 추가 | 선택 채팅이 재시작 후 유지되고, 선택 해제 시 검색·재생에서 즉시 빠진다. |
| 3. 검색 → `MusicSource` | `TelegramSource.search()`: TDLib `SearchChatMessages`(audio/document), 행을 `Song`으로 매핑, `SourceRegistry.trackKey` 부여. 색인 없이 서버 검색만 | `Search → Addons`에서 텔레그램 행이 나오고, 제목·아티스트·길이·코덱이 표시된다. 검색을 누르지 않으면 네트워크 요청이 없다. |
| 4. 스트리밍 DataSource | `TelegramDataSource`: Range 읽기, 취소, 세션/채팅 변경 검사. `PlaybackService`의 `ResolvingDataSource`/`DefaultDataSource` 체인에 내부 URI 라우터 추가. 전체 파일 메모리 로드·원본 파일 덮어쓰기 금지 | FLAC/WAV/ALAC 재생과 seek가 동작한다. 재생 중 채팅 선택을 바꾸면 안전하게 중단된다. 파일 전체를 메모리에 올리지 않는다. |
| 5. 품질 업그레이드·다음 곡 연결 | `SourceKind.TELEGRAM`을 `SourceRegistry.build`에 추가, `AddonSource`와 동일한 `StreamFormat`(무손실/비트/샘플레이트) 매핑. `upgradeFor`/`hq-verify`/`prefetchSubstitute`가 후보로 보게 함 | YouTube로 시작한 곡이 텔레그램 FLAC으로 업그레이드되고 `hq-verify`를 통과한다. 다음 곡 준비에서 텔레그램 후보가 핀된다. 재생 중 추가 폴링이 없다. |
| 6. 선택적 색인 | 기본 off. 사용자가 “색인”을 누를 때만, 화면 켜짐·Wi‑Fi/충전 조건에서 페이지 진행. 6시간/30일 자동 타이머 없음 | 색인을 켜지 않으면 백그라운드 깨우기가 0회다. 켠 경우에도 화면 꺼짐/조건 불충족 시 즉시 멈춘다. |
| 7. 부가 기능(선택) | 앨범 커버, 포맷 범례, zip/cue, 채널 뷰, 이름 교정, 메인 채널 복사, 다운로드 | 각 기능을 개별 플래그로 추가하고, 기본 off. 추가할 때마다 배터리·rate limit 회귀 테스트를 둔다. |
| 8. 한국어 UI·문서 | 설정 화면 문구, 오류 문구, 개인정보 안내. Apple Music식 명사 라벨/합니다체/보관함·플레이리스트·다음 항목 재생·나중에 재생·셔플 유지 | 문자열 리소스 검토와 `assemble`/테스트 통과. 사용자에게 보이는 기존 문구 스타일 변경 없음. |

#### 예상 규모

- **권장 최소 코어(단계 0~5)**: 신규 12~18개 파일, 직접 작성 4,000~6,500줄 + 생성 바인딩 2개 파일(약 161k줄, 5.2MB). 수정 파일은 `SourceKind.kt`, `SourceRegistry.kt`, `PlaybackService.kt`, `AccountAndScrobblingScreen.kt`, `SettingsSheet.kt`/새 설정 화면, `MainViewModel.kt`(검색 탭 연결), `app/build.gradle.kts`, `strings.xml` 등 **10~15개**.
- **패치 전체를 그대로 옮길 경우**: Java 63개 파일 **39,550줄**(그중 `Translations.java` 21,853줄 제외 시 **17,697줄**), 패치 Kotlin 1,671줄, TDLib 바인딩 161k줄. 부가 기능까지 포함하면 이 규모에 가까워진다.
- **별도 애드온 서버(b)**: 패치 코어에 HTTP 서버·포그라운드 서비스·서비스 수명 관리가 추가로 500~1,000줄, 앱 2개 배포·설정 부담.

#### 주요 위험

1. **계정 위험**: 텔레그램 약관·계정 제한/밴 가능성. “일반 클라이언트 세션”임을 사용자에게 명확히 알려야 한다.
2. **API 자격 증명**: 사용자마다 my.telegram.org에서 발급해야 하며, 등록 폼이 거절될 수 있다. 앱에 하드코딩하지 않는다.
3. **배터리 회귀**: TDLib 연결 유지와 색인 루프가 가장 큰 위험. 1차에서 색인·자동 검색·백그라운드 검사를 모두 끈다.
4. **안전 규칙 훼손**: `PrecisionAudioSink` 다운믹스, 폰 스피커 PCM16 상한, `hq-verify`/길이 검사, 큰 파일 메모리 로드 금지, 다운로드 파일 원본 덮어쓰기 금지. 패치의 `SmoothUpgrade`/`Downmix`/`FormatLegend` 훅은 이식하지 않거나, 이식하더라도 기존 가드를 그대로 유지한다.
5. **빌드/난독화**: R8이 TDLib Java 바인딩·JNI 콜백을 제거하지 않도록 keep 규칙 필요. 16KB page size/ABI 정렬 확인.
6. **개인정보**: 세션 DB·API hash·색인 파일의 저장 위치와 삭제 정책을 문서화하고, 로그에 남기지 않는다.
7. **품질 매칭 오탐**: 텔레그램 파일의 태그가 잘못되어 있으면 `TrackMatcher`가 다른 녹음으로 판단할 수 있다. 기존 `hq-verify`를 반드시 통과시킨다.

---

## 요약(10줄 이내)

1. **연결 지점**: `SourceKind.TELEGRAM` → `SourceRegistry.build`/`activeForPlayback`, `SourceResolver.upgradeFor`(SourceResolver.kt:731), `AudioCache.prefetchSubstitute`/`NextTrackPreparation.prepareHq`, `PlaybackService` DataSource 체인(PlaybackService.kt:2019, 2322-2353), 설정은 `AccountAndScrobblingScreen.kt:50`.
2. **APK 증가**: arm64 4개 `.so` 합계 27.0MiB(비압축), 압축 시 약 11.1MiB. arm64 전용 빌드 유지.
3. **배터리**: 패치는 앱 시작 TDLib 초기화, 백그라운드 연결 유지, 기본 색인 루프(2초 간격·6시간 갱신·30일 재구축)로 우리 원칙과 충돌. 색인 기본 off·수동/이벤트 기반, 백그라운드 시 클라이언트 종료로 바꿔야 한다.
4. **사용자 설정**: my.telegram.org에서 api_id/api_hash 발급 → 앱 설정에서 입력 → 전화번호/코드/2단계 비밀번호 로그인 → 채널 선택. API hash는 Keystore 암호화, 세션은 no-backup 저장소.
5. **추천안**: (a) 전용 `SourceKind.TELEGRAM` 최소 이식. (b) 로컬 애드온 서버는 배터리·cleartext·2앱 부담으로 비추천.
6. **예상 규모**: 코어 12~18개 신규 파일·4,000~6,500줄 + TDLib 바인딩 161k줄, 수정 10~15개 파일. 패치 전체는 63개 파일·39,550줄(번역 제외 17,697줄).
