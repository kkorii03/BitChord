# v1.9 업그레이드 인수인계 (새 Claude 계정용) — 2026-11-01 06:00

새 계정의 Claude 는 이 파일부터 읽는다. 함께 읽을 것: `C:\Users\jang\Desktop\BitChord\CLAUDE.md`(운영 지침, 자동 로드), `ship\HANDOFF.md`(1.8 일상판, 원래 세션 담당), 이 폴더의 `docs/upstream-19-*.md`, `docs/ko-fork-layout.md`.
사용자 응답은 항상 한국어. 사용자는 코딩을 모른다 — 쉬운 말로.

## 0. 한 줄 현황
- 1.9 고침판 **ko-102-u19-2 (vc97)** 서명만 됨, 다운로드 페이지 **미게시**(사용자 폰 확인 전 금지). 사용자 폰에는 1.8 기반 되돌림판 **ko-102-test25r (vc96)**.
- 1.9 작업은 **정지 상태**(밤 방침): 업스트림 **v1.9.1 핫픽스**가 나오면 그때 같이 합친다.
- **11-01 07:30 갱신:** fix-1030 15커밋 합침 완료(39e43cf5, 018c841b 까지). 사용자 요청 '가사' 설정 묶기는 1.9 방식(`SettingsCategory.LYRICS` 분류 페이지, cf749036)으로 넣고 1.8 용 LyricsSettingsScreen 은 지움; 가사 페이지 스위치 iOS 식 통일(f8315bb2, CI 41273372130 결과 확인 필요). ui-tour 41270559428(cf749036) 성공: 화면 81, 앱 크래시 0(FATAL 2건은 uiautomator), '가사' 페이지 스크린샷 확인. 이 변경들은 vc97 이후라 **서명본 없음** — 다음 1.9 시험판은 vc99.
- 이 계정 주간 한도 89%에서 정지(사용자 지시: 99% 전 인수인계).
- 첫 할 일: fix-1030 에 새로 들어온 커밋이 있으면 합치기(`git log HEAD..origin/task/fix-1030`), 사용자 폰 로그 확인(§5-2).

## 1. 어디에 무엇이 있나
| 항목 | 값 |
|---|---|
| 저장소 | `kkorii03/BitChord` (포크). **gh 는 항상 `--repo kkorii03/BitChord`**, upstream(kushagrasinghx)에는 push/dispatch 금지 |
| 1.9 브랜치 | `task/upstream-19` (worktree `C:\Users\jang\Desktop\BitChord\BitChord-u19`), 최신 `c078d94e` |
| 1.8 일상 브랜치 | `task/fix-1030` (원래 세션 "BitChord 계속" 담당, 건드리지 말 것 — 여기서 1.9 로 **합쳐 오기만**) |
| 업스트림 기준 | 태그 `v1.9` = `04f64c9f` |
| 도구 준비 | 모든 git/gh 전에 `. C:\Users\jang\Desktop\BitChord\tools\env.ps1` |
| 서명본 | `ship\dl-u19\ko-102-u19-2.apk` vc97, 87,924,581 B, SHA256 `d4fbbc24b018529437f26776b1de508a2d0ce895cf702aeac07a852aa75f1222` (소스 c078d94e, APK run 41239819968). 첫 실패판 `ko-102-u19-1.apk` vc95 도 같은 폴더 |
| 다운로드 페이지 | https://claude.ai/artifact/82P1f4Bz5SamysVHGbfyfk — **이전 계정 소유**. 새 계정은 수정 못 할 수 있음 → 새 페이지를 만들고 사용자에게 링크를 알려야 함(`ship\dl-test4` 조각 방식, `ship_local.py` 가 조각 수 자동 결정) |
| 공유 메모 폴더 | `C:\Users\jang\AppData\Local\Claude-Data\claude_pzs8sxrjxfjjc\side-session-notes\local_15ed43a1-...\u19-status.md` (계정이 바뀌면 경로가 달라질 수 있음 — 이 파일이 정본) |

## 2. 버전 번호 규칙
- 1.9 시험판 = **홀수**(다음 99), 1.8(fix-1030) 시험판 = **짝수**(다음 98). 설치 후 낮은 번호로 되돌릴 수 없음.
- 서명: `task-apk.yml` 이 `apk/**` 브랜치 push 로 서명 안 된 APK 를 만든다 → `gh run download <run> -n BitChord-task-arm64` → 
  `BITCHORD_APK=<apk> BITCHORD_VERSION_CODE=<홀수> BITCHORD_DL=ship\dl-u19 tools\python\python.exe -I ship\ship_local.py ko-102-u19-N`. `ship\work` 를 1.8 쪽과 같이 쓰므로 동시에 돌리지 말 것. GitHub 릴리스를 만드는 `korean-build.yml` 은 쓰지 않는다(자동 배포 금지).

## 3. 지금까지 한 일 (10-31 ~ 11-01)
1. v1.9 를 `git merge -X theirs` 로 합침(충돌 96파일), strings 는 키 합집합(`tools/upgrade/strings_union.py`). 컴파일 오류 약 1,300개를 클라우드 DeepSeek 여러 줄 + Opus 검토로 해결.
2. 우리 한국어 기능은 `com.music.bitchord.ko` 패키지 + 업스트림 파일엔 한 줄 `// [KO] <이름>` 훅(`docs/ko-fork-layout.md`). 섞인 줄 측정: `tools/upgrade/ko-hooks.ps1 mixed`.
3. 감독 도구 `tools/upgrade/merge-guard.ps1 -Candidate <브랜치> -Base <기준> -Fork 0fce7db4^1` — DeepSeek 결과가 1.9 파일을 지우거나 옛 포크 파일로 되돌리면 BLOCK.
4. `task/fix-1030` 을 네 번 더 합침(마지막 70f7443a, 그 뒤 15커밋 미반영 — §5).
5. **맞는 곡(1순위)**: 1.9 `TrackMatcher.score` 의 "가수가 달라도 길이 2초 이내면 채택"을 끔(`ko/KoTitleRules.creditsOverrideAllowed=false`), 트리뷰트/Originally Performed by/In the Style of/Made Famous by 를 커버 표식으로. 함정 시험 `TrackMatcherTrapCasesTest` 14개.
6. **품질 업그레이드 1.9 교체**: 1단계(459794aa) 검색 중심부 1.9 모양 + 애드온 건강 관리 `ko/SourceSearchKo.kt`(401/403·온라인 실패 30분 휴식, 오프라인은 휴식 안 셈, 실패 섞인 '없음' 캐시 금지, hq-search 로그 한 줄, 수동 12초). 2단계(bbf453be) 가타카나 등 다른 글자 가수 사본은 소리 대조(HqAudioVerify) 통과 때만. 결정 근거 `docs/upstream-19-hq-compare.md` 맨 위 "감독 확정 판정".
7. 다운그레이드 복구: float 출력 무음 건너뛰기(DeepSeek 가 거짓 주석 달고 지웠던 것), 1.9 새 문구 합니다체/플레이리스트, 자동 믹스 상태 줄 한국어(`ko/AutomixStatusKo.kt`).
8. **첫 시험판 vc95 폰 실패 → 고침**(사용자 '최악'): 
   - 첫 소리 9초 → a9ae4776: 재생기가 automix 분석 쓰기에서 캐시 항목을 빼앗음, YouTube resolve 를 애드온 조회와 동시에, 애드온 전부 휴식이면 대기 안 함.
   - 믹스가 곡 초반 시작 → 4bda2036: 병합 때 꼬리 분석 시각을 곡 시간으로 안 옮겼음(1.9 원본 버그 아님), "끝-페이드-30초 전 arm 금지" 가드.
   - ANR → a9cd79f7: 다운로드 기록 JSON 을 composition 에서 읽던 것 IO 로, `ko/ExitReasonsKo.kt` 가 다음 시작 때 ANR 스택을 진단 로그에 기록(`exit:`/`exit-anr:` 줄).
   - 한국어 기기 공유 링크 MV 가 노래로 판정돼 MV 그대로 재생 → c078d94e: `musicVideoType`(OMV/UGC=비디오, ATV=노래) 우선.
9. 검증: CI 41239098093 초록. 에뮬(사용자 설정 applied=25): smoke 41239817705 6/7(품질 업그레이드 FAIL = 애드온 장애, 1.8 판도 동일), ui-tour 41227347865 성공(화면 92, 앱 크래시 0). **자동 믹스는 에뮬에서 미확인**(smoke 가 곡을 건너뜀) — 단위 시험만.

## 4. 반드시 지킬 것 (이번에 배운 함정)
- **DeepSeek "compiled" 결과를 믿지 말 것.** 실제로 1.9 파일 142개 삭제·178개 되돌림, 기능 삭제 후 "v1.9 leaves this to Media3" 같은 **거짓 주석**, 범위 밖 파일 수정이 여러 번 있었다. 지시서에 `[NO-FIX-LOOP]`(클라우드 자동수정 루프 끔), 결과는 **지시한 파일만** `git checkout <cloud/브랜치> -- <파일>` 로 가져온다. 핵심 로직(곡 판정·싱크·캐시·재생)은 diff 를 직접 읽는다.
- 클라우드 DeepSeek: `powershell -NoProfile -File tools\cloud-ds.ps1 <지시서 파일 경로> <이름> -Base task/upstream-19 -Repo auto` (지시서는 **파일 경로**로 넘김; 결과는 `cloud/<이름>` 브랜치). 지시서는 `ship\` 이나 스크래치에 두고 rg 대신 grep 쓰라고 적는다.
- 새 테스트는 `.github/workflows/ko-wip-check.yml` 목록에 넣어야 돈다(DeepSeek 는 workflows 수정 금지 — Opus 가 넣음).
- 커밋 메시지에 큰따옴표가 있으면 PowerShell 이 깨짐 → `git commit -F <파일>`.
- GitHub API 가 가끔 끊김/404 → 재시도. CI 가 앱 테스트에서 80분 멈춘 적 1회(취소 후 재실행으로 통과).
- `SMOKE_SETTINGS_B64` 비밀값 쓰기는 이 계정 권한에서 막힘 — 사용자에게 명령을 드리거나 원래 세션 몫.
- 에뮬 smoke 의 '노래 버전 판정' FAIL 은 시험 경로가 한국 IP 게이트에 걸린 것일 수 있음(앱 결함 아님, 원래 세션 판단).
- 폰 검증은 사용자 폰 확인 후에만 "완료". 1.9 시험판은 사용자 폰 확인 전 기본 다운로드판 금지.

## 5. 남은 일 (순서)
1. ~~fix-1030 미반영 15커밋 합치기~~ **완료(39e43cf5)**. 합칠 때 발견: 앞선 병합이 `YtMusicRepository.moreSongs` 의 한국 차단 대체(YouTubeFallback)·로그아웃·좋아요 음원만 이어 불러오기를 빠뜨렸었음 → 복원. 아래는 당시 메모(70f7443a 이후, 마지막 018c841b): 가사 설정을 '가사' 한 줄+하위 화면으로(사용자 요청), 재생목록 표지 대체, gapless 자동 넘김 판정, 홈/둘러보기 IO(1.9 의 a9cd79f7 와 같은 수정), ui-tour 가사 행 탭. **충돌 예상 5파일**: `ko-wip-check.yml`(테스트 목록 합집합), `MainActivity.kt`(downloadedReleases — 1.9 쪽 produceState 유지), `PlaybackService.kt`, `SettingsSheet.kt`(1.9 설정 화면 구조에 '가사' 하위 화면을 새로 얹어야 함 — 포크 화면을 그대로 가져오지 말 것), `YtMusicRepository.kt`. 합친 뒤 CI → merge-guard.
2. 사용자가 vc97 을 폰에서 써 보면 진단 로그로 확인: `TIMING first audio`, `crossfade: arm/start`(곡 끝 근처인지), `crossfade: refused early plan`, `exit:`/`exit-anr:` 줄, `song version: ... ->`(MV 교체), track-summary.
3. **v1.9.1 나오면 합치기**: 같은 절차(merge -X theirs → strings 합집합 → `ko-hooks.ps1 diff` 로 [KO] 훅 재삽입 → merge-guard → CI → 에뮬). 겹침 주의: 1.9.1 "오디오 버전 기본 재생" vs 우리 musicOnly 교체(비교해서 더 나은 쪽, 우리 쪽에 커버 방지 장치 있음), bit-perfect 배터리 수정(그 전까지 bit-perfect 직접 출력은 끄라고 안내함), 홈 렉(liquid glass).
4. `docs/upstream-19-nodowngrade.md` 확인표의 '대기' 줄 마무리(에뮬/폰 근거로).
5. 우리 코드 `ko/` 분리 계속(큰 파일부터, 파일 하나 = DeepSeek 작업 하나 = CI 하나, `ko-hooks.ps1 mixed` 숫자가 줄어야 함). 업스트림 파일 쪼개기·삭제 금지.
6. HQ 교체 나머지(서버별 휴식 HqHostTracker, 죽은 코드)는 사용자 로그 증거가 나올 때만.

## 5-1. ANR(응답 없음) 원인 조사와 수정 — 11-01 07:00~
- 도구: `tools/upgrade/stalls.py <logcat.txt>...` (logcat 의 `Choreographer: Skipped N frames`·`Davey! duration=` 를 직전 `nav:` 화면별로 합산). ui-tour 에 두 기능 추가: 입력 `liquid_glass_off`(사용자 설정에서 Liquid Glass 만 끔, `scripts/smoke/set_setting.py`), 시작 직후 메인 스레드 표본 12개(`debuggerd -j`, 산출물 `main-stacks.txt`, 지금은 40줄에서 잘림 → 필요하면 늘릴 것).
- 결과: 멈춤은 **홈**이 압도적(1.8 판도 같음: 1.8 Home 1155 skipped/0.7초 이상 5번, 1.9 1658/23번). **Liquid Glass 는 원인 아님**(quick 끔 41277154595: Home 1336/31번/max 3.9s, 켬 41277161386: 1216/28번/max 5.2s).
- 메인 스레드 표본(41278808885): `Application.onCreate` 안에서 **EncryptedPrefs(Tink 암호 초기화, AuthStore)** 와 **ArtistFacts.init**(BitChordApplication.kt:381) 가 메인에서 돈다(에뮬 onCreate 3.7초). 홈 로딩 중에는 메인이 RenderThread 그리기를 기다림.
- **1.8 줄(task/telegram, 읽기만)도 같은 원인**: onCreate 에서 AuthStore 147행·ArtistFacts.init 350행이 메인. 1.9 수정이 확인되면 같은 방식으로 1.8 에도(그쪽은 원래 세션 담당, 직접 push 금지).
- **11-01 07:45 1.9 수정 a6d8249a**: AuthStore(EncryptedPrefs/Tink)를 시작 직후 백그라운드에서 미리 열기(EncryptedPrefs 는 @Synchronized+캐시라 안전, 로그인 데이터·파일 이름 변경 없음), 홈 폴더 카드가 홈이 안 보일 때(LocalArtworkMotion) 블러 애니메이션 멈춤(재생 중 매 프레임 다시 블러하던 것). ArtistFacts.init 은 아직 메인(다음 할 일). 검증: CI 41285079189, quick ui-tour 41285081536 — 비교 기준 quick 41277161386(Home 0.7초 이상 28번, max 5.2s).
- **측정 결과(11-01 08:10, 41285081536, a6d8249a): 개선 확인 안 됨.** Home 0.7초 이상 28번(수정 전 28~31번), skipped 2578(전 1216~1336), max 9.3s; onCreate 4.2~7.3초(전 3.7초). CI 41285079189 는 통과. 에뮬레이터(소프트웨어 그리기, 공용 러너)는 같은 코드로도 값이 크게 흔들려서 이 정도 변경은 구분이 안 됨 — **에뮬 수치로 판단하지 말 것.** 수정 자체는 해롭지 않아 남겨 둠(로그인 데이터 변경 없음).
- 다음 방법: ① 메인 스레드 표본을 홈 진입 후 5~20초 구간에서, 40줄 제한 없이 여러 번 찍어 앱 코드(com.music.bitchord) 가 몇 번 나오는지 센다 ② ArtistFacts.init 을 IO 로 ③ 사용자 폰에서 vc99 이상의 `exit-anr:` 스택이 가장 확실한 근거.
- 주의: PowerShell 로 이 파일을 고칠 때 `Get-Content` 에 `-Encoding UTF8` 을 빼면 한글이 깨진다(11-01 515b519f 에서 실제로 깨져 복구함). Edit 도구나 `-Encoding UTF8` 사용.

## 6. 사용자 지시 원문 요약 (우선순위)
- "최대한 1.9 반영" → 정정: "우리 기능이 1.9보다 좋으면 우리 것, 1.9에 없고 우리에게 있으면 사용" → "우리 기능은 문제가 많다, 잘 비교해서" → 기본 1.9, 우리 것은 증거 있을 때만. 단 **맞는 곡 판정은 우리 것**(1.9 가 현악 커버를 채택하고 가타카나 가수를 놓침 — 증거 있음).
- "기능적으로 다운그레이드 없도록, DeepSeek 가 잘못할 것이니 Opus 가 감독관."
- "다음 버전 나오면 쉽게 업그레이드하게 우리 기능을 분리" → ko/ 패키지.
- "진행 중인 1.8.1 수정은 모두 1.9 에도 적용."
- 우선순위: 맞는 곡 > 가사 싱크 > 안정성 > 배터리 > 속도 > 고음질 > UI. UI 는 애플뮤직 한국어(합니다체, 플레이리스트/보관함).
