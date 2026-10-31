# 업스트림 v1.9 합치기 계획 (2026-10-31)

- 업스트림: kushagrasinghx/BitChord 태그 `v1.9` = `04f64c9f` (2026-10-30)
- 우리 기준: `origin/task/fix-1030` (vc91 test22 이후)
- 작업 브랜치: `task/upstream-19` (worktree `BitChord-u19`)
- 사용자 방침: **최대한 1.9 를 반영.** 같은 일을 하는 우리 기능은 1.9 것으로 바꾸고, 1.9 에 없는 한국어 고유 기능만 1.9 구조 위에 다시 연결한다.
- 추가 방침(10-31 사용자): 다음 업그레이드가 쉽도록 우리 기능을 따로 떼어 둔다 → `docs/ko-fork-layout.md`.

## 1. 병합 방식과 규모
- `git merge --no-ff -X theirs v1.9`: 충돌 **96파일**(코드 95 + 문자열), 충돌 덩어리는 전부 1.9 쪽 선택, 충돌 안 난 우리 변경은 유지. 병합 커밋 `0fce7db4`.
- 1.9 가 지운 `playback/audio/usb/DirectAudioOutput.kt` 삭제(1.9 `bitperfect/usb/*` 가 대체).
- `strings.xml` 39개: 키 합집합(`tools/upgrade/strings_union.py`). values-ko = 우리 번역 우선 + 1.9 새 키, 나머지 언어 = 1.9 우선 + 우리 키. 검사 결과 우리 키 누락 0.
- 충돌이 컸던 파일: SettingsSheet(39), MainActivity(28), PlaybackService(24), SongActionsMenu(16), values-ko strings(15), SourceResolver(12), TransitionPlanner(12), InnertubeParser(10), YtMusicRepository·MainViewModel·CrossfadeController(9).
- 첫 CI(41184531411): shared 모듈에서 컴파일 오류 약 380개(18파일). app 모듈 오류는 shared 가 통과해야 보인다.

## 2. 기능별 결정
| 기능 | 결정 | 내용 |
|---|---|---|
| 품질 업그레이드(hq-swap, HQ prefetch, Qobuz/FLAC 교체, HqAudioVerify) | **1.9 채택** (Direct Source Stream) | 우리 hq-swap/prefetch 호출부는 지운다. Qobuz 가수명 일본어 표기 탈락 문제 여부는 §4 |
| Automix / 크로스페이드 | **1.9 채택** | `TransitionPlanner`·`TransitionPolicy` 는 1.9 원본 그대로(커밋 3번째). 우리 automix 테스트 2개 삭제. 배터리용 `AutomixWorkPolicy`(분석 생략 판단)는 1.9 Automix 에 붙일 수 있으면 유지, 안 맞으면 버림 |
| 다운로드·오프라인 재생목록 | **1.9 채택** (`PlaylistSync`, `OfflinePlaylists`) | 우리 다운로드 수정은 1.9 와 겹치면 버림 |
| 설정 화면 | **1.9 채택** (재작성된 SettingsSheet) | 한국어 고유 설정 항목(가사 싱크, 한국 차단 대체/프록시, 비디오 라디오, 진단 로그 내보내기)만 1.9 화면에 다시 추가 |
| 노래 메뉴 / iOS 메뉴 | **1.9 채택** (`IosMenu`, `SongActionsMenu`) | 우리 메뉴 항목 중 한국 고유 기능만 다시 추가 |
| 비디오 재생 | **1.9 채택** (`MusicVideoPlayer`, `MusicVideoLayout`) | 단, "MV 대신 음원" 판정은 우리 것 유지(아래) |
| 탭바·미니플레이어·플레이어 화면 | **1.9 채택** | 가사 화면의 싱크 보정 UI(LyricsOffsetSheet)만 유지 |
| bit-perfect / USB DAC | **1.9 채택** (새 기능) | 우리 `DirectAudioOutput` 삭제 |
| 팟캐스트·Top Picks·Apple 카탈로그 | **1.9 채택** (새 기능) | |
| 가사 싱크 보정(LyricsTiming, 크로마 정렬, 오프셋) | **유지(한국어)** | 우리 파일 그대로, 1.9 플레이어에 hook 으로 연결 |
| 노래 버전 판정·MV 금지·커버/피아노 거르기 | **유지(한국어)** | `TrackMatcher`/`SourceResolver` 안의 우리 코드 → `ko/` 로 분리 예정 |
| 한국 차단 곡 대체·프록시 재생·다른 ATV 사본 | **유지(한국어)** | `StreamResolver`/`Innertube` 안의 우리 코드 → `ko/` 로 분리 예정 |
| 비디오 라디오 | **유지(한국어)** | `data/videoradio/*` (우리 파일) |
| 한국어 문자열(애플뮤직 한국어 합니다체) | **유지(한국어)** | values-ko 우리 번역 우선; 1.9 새 키 중 영어로 남은 것은 번역 추가 |
| 진단 로그·featurehealth 로그 줄 | **유지(한국어)** | 1.9 코드 경로에 같은 로그 줄을 hook 으로 다시 넣는다(featurehealth 측정이 끊기지 않게) |

(세부 항목별 잃은 코드 목록: `docs/upstream-19-lost.md` — DeepSeek 조사 후 추가)

## 3. 진행 순서
1. **컴파일 초록** — shared 오류를 파일 묶음 3개로 나눠 클라우드 DeepSeek 병렬(innertube / repo / matcher). 잃은 우리 선언은 업스트림 파일이 아니라 `ko/` 새 파일에 복원. 그다음 app 모듈 오류를 같은 방식으로.
2. **CI 테스트 목록 정리** — 버린 기능 테스트(automix 품질, hq-swap 등) 빼고, 1.9 새 테스트(bitperfect, PlaylistSync, LoopMix 등) 넣기. `.github` 는 Opus 가 직접.
3. **한국 기능 재연결 확인** — 가사 싱크, MV 금지, 한국 차단 대체, 비디오 라디오, featurehealth 로그 줄이 실제 동작하는지 에뮬레이터(`ui-tour.yml` quick=false, smoke)로 확인.
4. **분리 작업(업그레이드 쉽게)** — `tools/upgrade/ko-hooks.ps1 mixed` 로 업스트림 파일 안 우리 코드 줄 수를 재고(병합 직후 PlaybackService 5130, AppSettings 1837, Downloads 1584, StreamResolver 1489, YtMusicRepository 1385, TrackMatcher 1302 …), 큰 파일부터 하나씩 `ko/` 로 옮기고 `// [KO]` hook 한 줄만 남긴다. 파일 1개 = DeepSeek 작업 1건 = CI 1회.
5. 시험판 APK(다음 versionCode, 기존 서명 흐름) → 다운로드 페이지는 원래 세션이 게시.

## 4. Direct Source Stream 의 가수명 매칭
(조사 중)
