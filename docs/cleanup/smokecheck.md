# 시험판 자동 판정 — `scripts/smokecheck.py`

에뮬레이터 UI 투어(`.github/workflows/ui-tour.yml`)가 남긴 logcat 을 읽어, 사용자가
직접 써 보기 전에 시험판의 핵심 실패를 표로 판정한다. 표준 라이브러리만 쓰며
앱 코드·워크플로는 건드리지 않는다.

## 사용법

```bash
# logcat 파일 하나를 판정 (실패가 하나라도 있으면 종료 코드 1)
python3 scripts/smokecheck.py logcat.txt

# 마크다운 요약도 함께 쓰기
python3 scripts/smokecheck.py logcat.txt --report smoke.md

# 표준 입력으로 받기
adb logcat -d | python3 scripts/smokecheck.py -
```

- 종료 코드: 모든 항목 PASS → `0`, 하나라도 FAIL → `1`, 로그/보고서를 읽거나
  쓸 수 없음 → `2`.
- 출력: `# / 항목 / 판정 / 상세` 표와 전체 결과. `--report` 는 같은 내용을
  마크다운 표와 항목별 상세로 쓴다.
- 로그 줄은 `adb logcat -d` 의 접두사(`10-26 12:00:00.000  1234  5678 I BitChord: ...`)
  가 붙은 형태를 그대로 받는다. 문자열은 줄 어디에 있든 찾는다.

## 판정 항목

로그 형식은 앱 코드에서 확인한 그대로 맞춘다.

| # | 항목 | PASS 조건 | FAIL 조건 |
|---|---|---|---|
| 1 | MV 재생 | `track-summary ... video=y` 또는 제목에 `(Official Video)`·`Official Music Video`·`M/V`·독립 단어 `MV` 가 있는 곡이 모두 `song version: <mv> -> <song> via ...` 교체 로그를 가짐. 단, MV 제목 표식이 없고 `audio switch: no official song match` 로 카탈로그 곡을 못 찾았거나 `song version: <id> -> none via none (cover)` 인 커버·노래 영상은 그대로 재생돼도 통과 | MV 제목 표식이 있는 곡에 교체 로그가 없음 (MV 소리가 그대로 재생됨) |
| 2 | 노래 버전 판정 | `audio switch: no official song match` 없음 | 한 건 이상. 곡명·가수와 건수를 표시 |
| 3 | 가사 싱크 | `lyrics-sync`/`song-sync` 의 `abs(shift) <= 3000ms` 이고 `refused-shift` 없음 | `abs(shift) > 3000ms` 또는 `song-sync ... result=refused-shift` (MV 소리 의심) |
| 4 | 품질 업그레이드 | `hq-verify ... -> accepted` 가 1건 이상, 또는 후보 자체가 없음 | 후보(`accepted`+`rejected`+`decode: hq-verify failed`+`no reference`)가 있는데 `accepted` 가 0 |
| 5 | 튕김/ANR | `FATAL EXCEPTION`·`ANR in` 없음 | 한 건 이상 |
| 6 | 다음 곡 준비 | `next-prep ... version=<id>` 의 `<id>` 가 확인된 MV id 가 아님 | MV id 로 남음 (`song version ... kept (none)`/교체 실패 등) |

### 세부 규칙

- **MV id 판별**: `track-summary` 의 `video=y` 또는 MV 제목 표식,
  `lyrics-sync ... video=y`, 그리고 `song version: <id> ...` 의 원본 id
  (`kept` 포함)를 MV 후보로 본다. `song version: <mv> -> <song>` 의 도착 id 는
  이미 노래 버전이므로 MV 로 세지 않는다.
- **1번의 교체 인정**: `track-summary` 의 id 가 교체 로그의 원본이거나 도착
  id 이면 통과. `-> none`(공식 곡 없음)은 교체가 아니므로 실패다. 다만 MV 제목
  표식이 없는 커버·노래 영상은 예외다. 같은 제목의 `audio switch: no official
  song match` 가 있거나, `kept (none)`/`-> none via none (cover)` 로 카탈로그
  곡이 없음이 확인되면 그대로 재생돼도 통과한다. 제목에 MV 표식이 있으면 이
  예외는 적용되지 않는다.
- **3번은 경고가 아니라 FAIL**: `|shift| > 3000ms` 는 MV 소리 의심 신호이고,
  이 도구의 목적이 그 신호를 자동으로 잡는 것이므로 종료 코드에 반영한다.
  상세 줄에는 `WARN` 접두사를 붙여 목록으로 보여 준다.
- **4번의 후보**: `hq-verify` 판정 줄, `decode: hq-verify failed`, 참조가 없어
  검증을 못 한 `no reference` 줄을 모두 후보 시도로 센다. 후보가 아예 없으면
  (업그레이드할 곡이 없었으면) PASS.
- **중복**: 같은 MV id·같은 싱크 경고·같은 `next-prep` 행은 상세 목록에서
  한 번만 보여 준다. `audio switch` 실패는 곡명·가수별 건수를 센다.

## 단위 테스트

작은 가짜 로그로 각 항목의 PASS/FAIL 과 보고서·종료 코드를 확인한다.

```bash
python3 -m unittest scripts/test_smokecheck.py -v
```

테스트는 MV 교체 유무, MV 제목 표식, 커버·노래 영상 예외, `no official song match`, 3000ms 경계,
`refused-shift`, HQ accepted/rejected/decode 실패/no-reference, FATAL/ANR,
`next-prep` 의 MV id 잔존, 마크다운 보고서와 종료 코드를 다룬다.

## 로그를 남기는 쪽

- 판정에 쓰는 줄: `track-summary:`, `song version:`, `audio switch:`,
  `lyrics-sync:`, `song-sync:`, `hq-verify:`, `decode:`, `next-prep:`,
  `FATAL EXCEPTION`, `ANR in`.
- `TrackLog` 는 디버그 빌드에서만 logcat 으로 내보낸다. UI 투어가 쓰는
  `devDebug` 빌드는 해당된다.
- 워크플로 연결(`.github/`)은 이 작업에서 수정하지 않았다. 아티팩트
  `ui-tour` 에 logcat 을 넣는 일은 별도 작업이 한다.
