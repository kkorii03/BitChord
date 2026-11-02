# 한국어 포크 코드 배치 규칙 (업스트림 업그레이드를 쉽게)

목표: 다음 업스트림 버전이 나오면 `git merge` 충돌이 거의 없게 한다.
우리(한국어 포크)가 만든 기능은 **우리 파일**에 두고, 업스트림 파일에는 **한 줄짜리 연결 지점(hook)** 만 남긴다.

## 1. 어디에 두나
| 종류 | 위치 |
|---|---|
| shared 모듈 우리 코드 | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/ko/...` (공통 타입이면 `commonMain/.../ko/`) |
| app 모듈 우리 코드 | `app/src/main/java/com/music/bitchord/ko/...` |
| sharedUi 우리 화면 | `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ko/ui/...` |
| 우리 테스트 | 같은 모듈의 test 폴더, 패키지 `com.music.bitchord.ko...` |
| 우리만 쓰는 문자열 키 | `res/values/strings_ko_fork.xml`, `res/values-ko/strings_ko_fork.xml` (키 이름 앞에 `ko_`) |
| 업스트림 문자열의 한국어 번역 | `res/values-ko/strings.xml` (업스트림 파일; 충돌 시 `tools/strings_union.py` 로 키 합집합) |

이미 우리 파일로 존재하는 것(예: `data/videoradio/*`, `data/cover/*`, `playback/smart/LyricsTiming.kt`, `HqAudioVerify.kt` 등 v1.9 에 없는 파일)은 **옮기지 않는다** — 업스트림에 없는 파일은 충돌하지 않으므로 그대로 둔다. 옮기는 대상은 **업스트림 파일 안에 섞여 들어간 우리 코드**뿐이다.

## 2. 업스트림 파일 안에서의 규칙
- 업스트림 함수 본문에는 우리 로직을 쓰지 않는다. `KoXxx.someHook(args)` 같은 **호출 한 줄**만 둔다.
- 그 줄(또는 2~3줄 블록) 끝/앞에 반드시 `// [KO] <기능 이름>` 표시를 단다. 예:
  ```kotlin
  val url = KoKoreaGate.rewriteIfBlocked(url, ctx) // [KO] korea-gate
  ```
- 업스트림 클래스에 필드가 꼭 필요하면 한 줄로 추가하고 `// [KO]` 표시. 가능하면 우리 쪽 `object`/`WeakHashMap` 등으로 상태를 밖에 둔다.
- 업스트림 코드는 쪼개거나 지우지 않는다(사용자 규칙). 업스트림 private 멤버가 필요하면 그 멤버를 `internal` 로 바꾸는 한 단어 수정 + `// [KO]` 만 허용.

## 3. 업그레이드 절차 (다음 버전)
1. `git merge -X theirs <새 태그>` — 업스트림 파일은 업스트림 쪽이 이긴다. `ko/` 폴더와 `strings_ko_fork.xml` 은 충돌하지 않는다.
2. `tools/ko-hooks.ps1 diff <이전 병합 커밋>` 으로 사라진 `// [KO]` 줄 목록을 뽑는다.
3. 사라진 hook 줄만 새 업스트림 코드의 맞는 위치에 다시 넣는다(대개 수십 줄).
4. 컴파일·CI·에뮬레이터 확인.

## 4. 현황 기록
`docs/ko-hooks.md` 는 `tools/ko-hooks.ps1 list` 가 만든다(업스트림 파일별 `[KO]` 줄 목록). 손으로 고치지 않는다.
