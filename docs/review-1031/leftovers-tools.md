# leftovers — tools (2026-10-31)

기준: `dsq/leftovers-tools-1031` (통합 기준 `task/fix-1030` 계열). 영역 `tools` 지적 10건
([422]·[390]은 같은 D3 보충 이중 계산 문제라 한 행으로 합침). `.github/`는 보호
대상이라 수정하지 않았고, 필요한 정확한 추가 줄을 이 문서와 최종 요약에 남긴다.

| 지적 | 요약 | 판정 | 근거 파일:줄 | 처리 |
|---|---|---|---|---|
| [318] HIGH | D9가 90% 비율이라 9성공/1실패를 통과시키고 실패 0건 가드를 없앴다. | 이미 고쳐짐 | `scripts/featurehealth.py:1600-1638` | 현재 D9는 비율이 아니라 `download failed 0건`·`hq-library did not land 0건`이며, 실패/미착지가 하나라도 있으면 `passed=False`, 없으면 `None`이다. 별도 90% 상수도 없다. |
| [269] MED | D9 attempts가 로그 줄 수라 같은 id 재실패가 부풀고, 성공을 id 기준으로 세지 않는다. | 고침 | `scripts/featurehealth.py:1603-1638`, `scripts/test_featurehealth.py:538-552` | 실패 attempts를 고유 id 수(`len(failed_ids)`, `len(missing_ids)`)로 바꿨다. 현재 로그 형식에는 다운로드 성공/done/landed 줄이 없어 성공 id 지표는 만들 수 없고, 기준은 그대로 실패 0건이다. 같은 id 2회 실패가 attempts=1인 테스트를 추가했다. |
| [321] MED | `hq-verify`에 id가 있으면 같은 제목의 다른 녹음 FLAC도 검증 재생으로 인정한다. | 고침 | `scripts/featurehealth.py:493-500`, `:929-956`, `scripts/test_featurehealth.py:217-232` | `hq-verify`의 선택적 `id=`를 파싱하고, 이벤트 id가 있으면 증거 id가 같아야 성공으로 본다. id가 없을 때만 기존 제목/제목→id 폴백을 쓴다. 같은 제목·다른 id 증거를 실패로 만드는 테스트를 먼저 추가했다. |
| [326] MED | seed 없는 drop이 120초 내 아무 seedless `+radio`에 귀속된다. | 지적이 틀림 | `scripts/featurehealth.py:1028-1035` | 현재 `zero_refills` 상관은 seed를 쓰지 않고, drop 직후 1.0초 안의 `+radio`만 본다. 120초 seed wildcard 코드/테스트는 현재 트리에 없다. |
| [316] MED | D1 skip 사유 분포가 `d1_with_lyrics`에서만 만들어져 `lyrics=none` 행이 빠진다. | 이미 고쳐짐 | `scripts/featurehealth.py:759-767`, `scripts/test_featurehealth.py:168-194` | 분포는 전체 `next_preps`의 미적용 sync에서 만들고, D1-sync 분모만 가사 있는 곡으로 유지한다. `no-lyrics` 포함 테스트가 이미 있다. |
| [422]·[390] MED | D3가 `fallback=`을 무시하고, `fallback=normal` 보충을 `+radio`와 append 양쪽에서 세어 한 번을 두 번으로 센다. | 고침 | `scripts/featurehealth.py:1037-1121`, `scripts/test_featurehealth.py:331-374` | `empty`=실패, `normal`=보충 경로로 위임, `none/same-mode/partial`=비디오 성공 분류는 이미 있었다. 추가로 `+radio`와 뒤따르는 `autoplay-append`(30초 이내)를 한 보충으로 묶어 attempts/success를 한 번만 센다. 3보충이 4시도로 세지던 테스트를 3/2로 바꾸고, `+radio`+append 한 보충=1시도 테스트를 추가했다. |
| [400] MED | `SleeveNerdStats`가 `PlayerSettings.showNerdStats`를 읽지 않아 “Show stats for nerds” 토글이 죽어 있다. | 고침 | `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerControls.kt:371-390`, `sharedUi/src/jvmTest/kotlin/com/music/bitchord/ui/player/NerdStatsVisibilityTest.kt` | 스위치가 켜져 있으면 탭 없이도 항상 표시하고, 꺼져 있으면 기존처럼 탭 후 4초만 표시한다. 순수 판정 함수와 JVM 테스트를 추가했다. |
| [440] HIGH | 재생 중 Settings 화면을 투어가 캡처하지 않는다. | 남김 | `.github/ui-tour/tour.sh:557-606` | `.github/`는 수정 금지라 아래 정확한 줄을 남긴다. |
| [332] HIGH | `scripts/ui-tour-settings.sh`가 워크플로/투어에서 호출되지 않아 설정 행 시트가 캡처되지 않는다. | 남김 | `.github/workflows/ui-tour.yml:58`, `.github/ui-tour/tour.sh:418-502` | `.github/`는 수정 금지라 아래 정확한 줄을 남긴다. |

## `.github`에 적용할 정확한 줄 (보호 대상이라 미적용)

[440] `section_now_playing()`의 `if tap "오디오 출력" ... fi` 뒤, 마지막 `back` 앞:

```bash
  # 재생 중 Settings 시트: Library의 계정 메뉴로 들어가고 미니 플레이어는 유지한다.
  tap_tab library 2 "보관함|Library" "tab_library|nav_library|library_tab|library"
  scroll_top
  if tap "계정 전환" first 3 || tap "Switch account" first 3; then
    sleep 2
    if tap "설정" first 5 || tap "Settings" first 3; then
      shot settings-while-playing "Settings while playing"
      back
    fi
  fi
```

[332] 투어의 `section_settings` 실행 직후(현재 `.github/ui-tour/tour.sh:690`):

```bash
  run_section section_settings settings
  # 행에서 열리는 설정 시트(품질/다운로드/재생)는 별도 탭 순서가 필요하다.
  if [ -x scripts/ui-tour-settings.sh ]; then
    OUT="$OUT" PKG="$PKG" bash scripts/ui-tour-settings.sh || true
  fi
```

## 조건 변경 전후 진리표

D2 (hq-verify 재생 인정):

| 이벤트 id | 증거 | 이전 | 이후 |
|---|---|---|---|
| 있음 | 같은 id | 통과(제목 또는 id) | 통과 |
| 있음 | 다른 id, 같은 제목 | 통과(제목) | 실패 |
| 없음 | 같은 제목 또는 같은 제목의 id | 통과 | 통과(기존과 동일) |

D3 (한 보충의 시도/성공):

| `+radio` 합 | append n | 이전 시도/성공 | 이후 시도/성공 |
|---|---|---|---|
| >0 | >0 | 2 / 2 | 1 / 1 (짝) |
| 0 | >0 | 2 / 1 | 1 / 0 (짝 실패) |
| >0 | 0 | 2 / 1 | 1 / 0 (짝 실패) |
| >0 | append 없음 | 1 / 1 | 1 / 1 (기존) |
| append만 | >0 | 1 / 1 | 1 / 1 (기존) |

## 검증

- `python3 -m unittest test_featurehealth -q` → 30개 통과.
- `./gradlew :sharedUi:jvmTest --tests 'com.music.bitchord.ui.player.NerdStatsVisibilityTest' --console=plain -q` → 통과.
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` → 통과.
