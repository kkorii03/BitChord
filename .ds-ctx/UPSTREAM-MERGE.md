# 업스트림 합치기 절차 (2주마다)

대상: 한국 포크 `kkorii03/BitChord`에 업스트림 `kushagrasinghx/BitChord`의 새
릴리스 태그를 합친다. 이 문서는 절차만 정리하며, 이 작업 자체는 커밋/push/브랜치
변경을 하지 않는다.

## 0. 절대 규칙

- 업스트림에는 절대 push하지 않는다. `git push upstream ...` 금지.
- 업스트림에는 절대 workflow dispatch하지 않는다.
  `gh workflow run --repo kushagrasinghx/BitChord ...` 금지.
- `gh` 명령은 항상 `--repo kkorii03/BitChord`를 붙인다.
- `.github/`, `keys/`, `*.jks`, 서명 설정은 이 절차에서 수정하지 않는다. 충돌이
  나면 포크 쪽을 유지하고, 변경은 별도 승인 작업으로 분리한다.
- 한 번에 한 태그만 합친다. 여러 릴리스를 한 번에 합치면 충돌 원인을 찾기 어렵다.
- 합치기 전 작업 트리는 깨끗해야 한다. `git status --short`가 비어 있지 않으면
  먼저 보관하거나 버린다.

빌드 규칙:
- 이 포크의 기본 규칙은 로컬 Gradle 빌드 금지다. 빌드/테스트는 CI에서 한다.
- 단, 클라우드 러너(현재 작업 환경)에서는 아래 컴파일/테스트 명령을 허용한다.
  로컬 PC에서는 이 명령을 실행하지 않는다.

## 1. 준비 (5분)

```bash
cd /path/to/BitChord
git status --short
git branch --show-current
git remote -v
git tag --sort=-v:refname | head -20
```

확인할 것:
- 현재 브랜치가 `main` 또는 합치기용 새 브랜치인지.
- `origin`이 `https://github.com/kkorii03/BitChord`인지.
- `upstream`이 없다면 아래처럼 추가한다. push URL은 막아 둔다.

```bash
git remote add upstream https://github.com/kushagrasinghx/BitChord
git remote set-url --push upstream DISABLED
git remote -v
```

`upstream`의 push URL이 `DISABLED`면 실수로 push해도 실패한다. 그래도
`git push upstream`을 직접 입력하지 않는다.

## 2. 새 태그 확인

```bash
git fetch upstream --tags
git tag --sort=-v:refname | head -20
git ls-remote --tags https://github.com/kushagrasinghx/BitChord | tail -20
```

- 최신 안정 태그를 고른다. 예: `v1.8.1` 다음이 `v1.8.2`면 `v1.8.2`.
- pre-release/rc 태그는 피한다.
- 새 태그가 없으면 합치기를 건너뛰고 이 문서의 "다음 주기"만 기록한다.
- 합칠 태그를 `TARGET=v1.8.2`처럼 변수로 정해 둔다.

```bash
TARGET=v1.8.2
git show --stat --oneline "$TARGET" | head -40
```

## 3. 작업 브랜치 생성

합치기는 `main`에서 새 브랜치를 만들어 한다. 기존 `task/*` 브랜치에 바로
합치지 않는다.

```bash
git switch main
git pull --ff-only origin main
git switch -c "merge/${TARGET}"
```

- `main`이 오래됐다면 먼저 `origin/main`과 맞춘다.
- 이미 같은 이름의 브랜치가 있으면 `merge/${TARGET}-2`처럼 새로 만든다.
- 브랜치를 만든 뒤 `git status --short`가 비어 있는지 다시 확인한다.

## 4. 합치기

```bash
git merge --no-ff "$TARGET"
```

- `--no-ff`로 merge commit을 남겨 어느 태그를 합쳤는지 기록한다.
- 충돌이 나면 `git status`로 충돌 파일을 확인한다.
- 충돌 해결 전에는 `git commit`하지 않는다.
- 중간에 그만두려면 `git merge --abort`.

## 5. 충돌 해결 순서

### 5.1 먼저 전체 그림

```bash
git status --short
git diff --name-only --diff-filter=U
```

충돌 파일을 기능별로 나눈다.

1. 업스트림만 바뀐 파일: 업스트림을 그대로 받는다.
2. 포크 전용 파일: 포크를 그대로 유지한다.
3. 양쪽이 모두 바뀐 업스트림 파일: 아래 5.2의 상위 10개를 먼저 본다.
4. `.github/`, `keys/`, `*.jks`, 서명 설정: 이 절차에서 수정하지 않는다.
   포크 쪽을 유지하고 별도 작업으로 넘긴다.

### 5.2 충돌 위험 상위 10개 파일

자세한 내용과 현재 줄 번호는 `.ds-ctx/KOREAN-FEATURES.md`의 2번 절을 본다.

| 순위 | 파일 | 해결 원칙 |
|---:|---|---|
| 1 | `app/.../playback/PlaybackService.kt` | 업스트림 재생 콜백/상태 흐름을 먼저 살린다. 포크 호출(`songVersionController`, `prefetchController`, `PrivateListening.allows`, `TrackDiagnostics`, `LyricsTiming`, `HqAudioVerify`, `LockScreenLyrics`)을 lifecycle 지점에 다시 꽂는다. 포크 함수 본문을 통째로 붙여 넣지 말고 호출만 남긴다 |
| 2 | `app/.../ui/MainViewModel.kt` | 업스트림 상태 필드와 `loadHome`/`refreshHome` 구조를 유지한다. `LyricsController` 위임, `LocalPlayHistory` 수집, `refreshRecentsFromLocal`, 국가/차트, 타입어헤드 캐시를 이벤트 지점에 다시 넣는다 |
| 3 | `app/.../MainActivity.kt` | UI는 업스트림 우선. `switchToMusicOnly`, 디버그 스위치, 프록시 상태 알림, 시크릿 토글, `PlayerExpandGuard`, 온보딩만 함수/호출 단위로 다시 연결한다. `MainActivityHelpers/Layout/SortMenu`는 이미 분리된 파일을 유지한다 |
| 4 | `shared/.../data/YtMusicRepository.kt` | 각 공개 메서드 입구의 `ensureGateKnown()`/`YouTubeFallback` 분기를 빠뜨리지 않는다. `counterpartAudio`, `songVersion`, `RecentShelf`/`LocalPlayHistory` 병합, 국가 차트를 다시 넣는다. 업스트림의 새 API/continuation은 지우지 않는다 |
| 5 | `shared/.../data/innertube/Innertube.kt` | 업스트림 엔드포인트/세션 스코프를 유지하고, youtube.com 메서드와 프록시 전용 browse를 그 위에 다시 얹는다. `postMusic`/`postYouTube` 시그니처 변경을 확인한다. 익명 검색 규칙을 깨지 않는다 |
| 6 | `shared/.../data/lyrics/LyricsTranslation.kt` | 업스트림 번역 파이프라인을 유지하고 Gemini는 fallback으로만 붙인다. `nativeName`, `placeLinesInVideo`, `transcribeVideo`, 캐시 키/슬롯 마커를 다시 확인한다 |
| 7 | `app/.../data/settings/AppSettings.kt` | 키 이름 충돌을 먼저 확인한다. 프록시, 시크릿, 가사/AI/번역, 자동재생 개인화, 저전력 설정을 다시 넣고, 백업의 `SECRETS`/`DEVICE_LOCAL` 목록에 포크 키를 계속 포함한다 |
| 8 | `shared/.../data/innertube/StreamResolver.kt` | 가장 위험. 업스트림의 클라이언트 순서/새 resolver를 먼저 보존하고, 프록시는 "거절 시 한 번" 규칙으로 감싼다. `resolveViaProxy`, `ResolutionOrder`, `refuseMidPlay`/`reResolve`, `proxyDownloadStream`을 다시 연결한다 |
| 9 | `app/.../playback/AudioCache.kt` | 업스트림 SimpleCache 단일 writer/잠금 규칙을 유지한다. 프리페치/warm range, 분석 retain, HQ 검증, `timingKeep`/`playerHoldsKey`를 다시 넣는다 |
| 10 | `app/.../ui/screens/SettingsSheet.kt` | 업스트림 행을 유지하고 포크 행은 `ProxySettingsSection`, `ForkAlerts`, `ContentCountryDialog`, `SettingsSegmentedControl` 호출로 붙인다. row 키/순서 중복을 확인한다 |

다음 순위 감시: `LyricsRepository.kt`, `TrackMatcher.kt`,
`SongInfoRepository.kt`, `CoverTitles.kt`, `PlayerLyrics.kt`,
`LyricsOffsetSheet.kt`, `NowPlayingScreen.kt`.

### 5.3 해결 후 확인

```bash
git diff --check
git grep -n '^<<<<<<<\|^=======$\|^>>>>>>>' -- . ':!.github' || true
git status --short
```

- 충돌 마커가 남아 있으면 안 된다.
- `.github/`는 이 절차에서 수정하지 않았는지 확인한다.
- 포크 기능이 사라진 것 같으면 `KOREAN-FEATURES.md`의 기능별 파일 목록과
  `git diff "$TARGET"...HEAD --stat`를 대조한다.

## 6. 컴파일 / 테스트

### 6.1 클라우드 러너에서 허용되는 명령

```bash
./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q
./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.net.*' --console=plain -q
./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.innertube.*' --console=plain -q
./gradlew :shared:jvmTest --tests 'com.music.bitchord.data.lyrics.*' --console=plain -q
```

- 먼저 `:app:compileProdReleaseKotlin`으로 컴파일 오류를 모두 잡는다.
- 그다음 프록시/게이트, 가사, 곡 버전, 자동재생 테스트를 기능별로 돌린다.
- 실패한 테스트가 이번 합치기와 무관한 기존 실패인지, 합치기로 생긴 실패인지
  `git diff "$TARGET"...HEAD -- <관련 파일>`로 구분한다.

### 6.2 CI

포크에만 push하고 CI를 본다. 업스트림에는 push하지 않는다.

```bash
git push origin "merge/${TARGET}"
gh run list --repo kkorii03/BitChord --branch "merge/${TARGET}" --limit 20
gh run watch --repo kkorii03/BitChord <run-id>
```

확인할 workflow:
- `korean-build.yml` — arm64 개인 빌드/릴리스
- `android.yml` — 일반 Android 빌드
- `check.yml` — 공유 테스트/정적 검사
- `test-apk.yml` / `task-apk.yml` — APK 산출
- `lint.yml` — lint
- 필요 시 `timing-lab.yml`, `ui-tour.yml`

실패하면 로그에서 첫 컴파일 오류 또는 첫 테스트 실패를 고친다. CI를 우회하려고
workflow를 수정하지 않는다.

## 7. APK / 에뮬레이터 / 실제 폰 확인 순서

CI가 초록이면 APK를 내려받아 아래 순서로 확인한다. 각 단계는 "이전 단계가
통과했을 때"만 진행한다.

### 7.1 에뮬레이터 (네트워크 정상)

1. 앱 설치 후 첫 실행: 온보딩, 한국어 문자열, 로그인 화면이 뜨는지.
2. 로그인 후 홈: 최근 재생, 국가/차트, 자동재생 개인화 행이 보이는지.
3. 일반 재생: 곡 재생/일시정지/다음/이전, 백그라운드, 알림.
4. 가사: 제공처 목록, 싱크, 번역 토글, AI 타이밍 버튼, 잠금화면 가사.
5. 노래 버전: 뮤직비디오를 곡 버전으로 바꾸기, 되돌리기, 다운로드 파일 유지.
6. 시크릿 모드: 켠 뒤 스크로블/디스코드/최근 재생이 막히는지, 6시간 만료.
7. 진단: 설정에서 로그 내보내기, 파일이 생성되는지, 비밀번호/쿠키가 없는지.
8. 프록시: 설정에서 프록시를 켜고/끄고, 연결 테스트, 백업 서버 전환.

### 7.2 실제 폰 (한국 네트워크, Premium 없음)

1. 프록시 없이: 게이트 감지, `www.youtube.com` 폴백 홈/검색/재생.
2. 프록시 켜기(일본 노드): 첫 소리까지 시간, 재생 유지, 다음 곡, 다운로드.
3. 프록시 끄기: 직접 경로 복귀, 이미 프록시로 받은 URL을 직접 경로로 쓰지 않는지.
4. 노래 버전/가사/진단/시크릿을 7.1과 같은 순서로 한 번 더.
5. 배터리: 화면 끄고 10분 재생했을 때 비정상 웨이크업/발열이 없는지.
6. 앱 재시작: 프록시 기억, 최근 재생, 큐 복원, 시크릿 만료.

실제 폰에서만 재현되는 문제는 `adb logcat` 대신 앱의 진단 로그를 먼저 확인한다.
로그에는 프록시 주소/비밀번호/쿠키가 남지 않아야 한다.

## 8. 마무리

1. `git status`와 `git diff --stat "$TARGET"...HEAD`를 확인한다.
2. `KOREAN-FEATURES.md`의 줄 번호/파일 목록을 새 HEAD에 맞게 갱신한다.
3. PR은 포크에만 만든다.

```bash
gh pr create --repo kkorii03/BitChord --base main --head "merge/${TARGET}" \
  --title "merge: upstream ${TARGET}" \
  --body "업스트림 ${TARGET} 합치기. 충돌 해결은 .ds-ctx/KOREAN-FEATURES.md 기준."
```

4. 리뷰 후 `main`에 merge한다. 업스트림에는 어떤 것도 보내지 않는다.
5. 다음 합치기 날짜를 2주 뒤로 잡고, 새 태그가 나왔는지 확인한다.

## 9. 롤백

- merge commit 전: `git merge --abort`
- merge commit 후 push 전: `git reset --hard ORIG_HEAD`
- push 후: 새 revert commit을 만든다. 이미 공유된 브랜치를 force-push하지 않는다.
- 사고가 났을 때도 `git push upstream`으로 되돌리지 않는다.

## 10. 2주 체크리스트

- [ ] `git fetch upstream --tags`로 새 태그 확인
- [ ] `TARGET` 태그 결정
- [ ] `origin/main` 최신화
- [ ] `merge/${TARGET}` 브랜치 생성
- [ ] `git merge --no-ff "$TARGET"`
- [ ] 상위 10개 파일 충돌 해결
- [ ] `.github/`, `keys/`, 서명 설정 미변경 확인
- [ ] `:app:compileProdReleaseKotlin` 통과
- [ ] `shared:jvmTest` 기능별 통과
- [ ] 포크 CI 초록
- [ ] 에뮬레이터 8단계 통과
- [ ] 실제 폰 6단계 통과
- [ ] `KOREAN-FEATURES.md` 줄 번호 갱신
- [ ] 포크에만 PR 생성, 업스트림 push/dispatch 없음
