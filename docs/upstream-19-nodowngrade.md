# v1.9 업그레이드 — "기능 다운그레이드 없음" 확인표

사용자 10-31: "잘 비교해서 1.9로 버전업그레이드, 기능적으로 다운그레이드 되는 일 없도록. DeepSeek 가 잘못할 것이니 Opus 가 감독관."
이 표의 모든 줄이 **확인됨**이 되기 전에는 시험판을 "완료"라고 하지 않는다.

## 감독 절차 (DeepSeek 결과마다)
1. `tools/upgrade/merge-guard.ps1 -Candidate <결과> -Base <기준>` — BLOCK(1.9 파일 삭제, 옛 파일로 되돌림)이면 버린다.
   - 실제로 잡은 사례(10-31): u19-shared-matcher 가 "빌드 성공"을 위해 1.9 파일 142개 삭제 + 178개 되돌림 → 폐기. u19-shared-repo 는 앱 파일을 옛 버전으로 되돌림 → shared/ 만 채택.
2. 각 결과에서 **지시한 범위 파일 + 새 `ko/` 파일만** 가져온다.
3. 잃은 테스트·설정 키·문자열 키 목록(merge-guard 3~5번)을 이 표와 맞춘다.
4. CI(`ko-wip-check`) → 에뮬레이터(`ui-tour` quick=false) → 사용자 로그 featurehealth.

## 기능별 확인표
판정: 1.9 = 1.9 구현 사용 / 우리 = 한국어 포크 구현 유지 / 1.9+우리 = 1.9 위에 우리 부분 얹기

| 기능 (featurehealth 키) | 판정 | 다운그레이드 확인 방법 | 상태 |
|---|---|---|---|
| 맞는 곡 재생: 노래 버전 판정·MV 대신 음원·커버/피아노 거르기 (D5-version, D2-swap) | 우리 | TrackMatcher/SongVersion 테스트 전부 통과 + 에뮬레이터 Dance Monkey 가 음원으로 재생 | shared 테스트 6개 실패 → DeepSeek u19-shared-tests 진행 중 |
| 재생 전 비디오→음원 자동 교체 (`musicOnlyTried`), 대체 버전 캐시 | 우리 | 설정 키 `prefer_music_only` 살아 있음 + 로그 `audio switch` 줄 | 잃음(merge-guard 4번) → app 단계에서 복원 |
| 비음악 구간·광고 구간 건너뛰기 설정 (`skip_non_music_parts`, `skip_sponsor_parts`) | 우리 | 설정 키 살아 있음 + SkipSegmentsTest | 잃음 → app 단계에서 복원 |
| 가사 싱크 보정·크로마 정렬·오프셋 화면 (D1-sync, D6-sync) | 우리 | LyricsTiming* 테스트 + 에뮬레이터 가사 화면 + `timingKeep` 복원 | sharedUi 가사 묶음 DeepSeek 진행 중 |
| 가사 표시·번역 (D1-lyrics, D6-lyrics) | 1.9+우리 | data.lyrics.* 테스트 + 에뮬레이터 가사 화면 | 대기 |
| 한국 차단 곡 대체·프록시 재생·다른 ATV 사본 (D8-unplayable) | 우리 | MusicGateFallback* 테스트 + 에뮬레이터(집 IP 경로) 재생 | shared 컴파일 통과, 테스트 대기 |
| 비디오 라디오 (D4-video, D4-modes) | 우리 | VideoRadio* 테스트 + `radioCache` 복원 | 대기 |
| 품질 업그레이드 (D1-hq) | 1.9 (Direct Source) | 1.9 `direct:` 로그를 featurehealth 가 세게 수정 → 사용자 로그 성공률이 우리 이전 수치 이상 | 대기. HqAudioVerify 는 증거 나올 때까지 미연결 |
| 자동 믹스·크로스페이드 (D1-mix, D7-*) | 1.9 | 지운 우리 테스트 2개를 1.9 플래너 기준으로 다시 돌려 비교; 떨어지는 항목만 얹기. featurehealth D7 패턴을 1.9 로그에 맞춤 | 대기 |
| 다음 곡 준비·첫 소리까지 시간 (D8-firstaudio, D1-time) | 1.9+우리 | NextPrep* 테스트 + 에뮬레이터 로그 | 대기 |
| 자동재생 (autoplay) | 1.9+우리 | Autoplay* 테스트 | 대기 |
| 다운로드·오프라인 재생목록 | 1.9 | 1.9 PlaylistSyncTest + 우리 Download* 테스트 중 1.9 에도 해당하는 것 | 대기 |
| 설정 화면 | 1.9+우리 | 한국 고유 설정 항목이 1.9 화면에 모두 있음(에뮬레이터 스크린샷) + 설정 검색 한국어 별칭 | 대기 |
| 노래 메뉴 (iOS 메뉴) | 1.9+우리 | 우리 메뉴 항목(버전 바꾸기 등)이 1.9 메뉴에 있음 | 대기 |
| 플레이어·미니플레이어·탭바 | 1.9 | 에뮬레이터 스크린샷을 이전 화면과 비교 | 대기 |
| 한국어 문구 (애플뮤직 합니다체) | 우리 | strings 키 누락 0(확인됨) + KoreanStringsTest + UiTranslationCoverageTest + 1.9 새 문구 번역 | 키 누락 0 확인됨 |
| 오디오 출력·파이프라인 한국어 라벨 | 우리 | AudioPipelineVerdictTest + 화면 | 잃음 → app 단계 |
| 진단 로그·충돌 기록·featurehealth 로그 줄 | 우리 | `installCrashHandler`·릴리스 `TrackLog.echo` 복원 + featurehealth 가 에뮬레이터 로그에서 FAIL 0 | 잃음 → app 단계 |
| 단어 단위 줄바꿈 (`withPhraseLineBreak`) | 우리 | 화면 | 잃음 → app 단계 |
| 큐 비면 플레이어 닫기, 분석 대기(폴링 없음) | 우리 | 테스트/코드 확인 | 잃음 → app 단계 |
| 1.9 신기능: bit-perfect/USB DAC, 팟캐스트, Top Picks, LoopMix, 뮤직비디오 플레이어 | 1.9 | merge-guard 1번(1.9 파일 삭제 0) + 1.9 테스트 CI 목록에 추가 | 삭제 0 확인(현재 HEAD) |

## 품질 업그레이드(HQ) 교체 단계 — 컴파일 초록 뒤 별도로
- 현황(10-31 밤): u19-app-sources 가 컴파일을 맞추려고 `SourceResolver.bestAcross` 를 1.9 원본 대신 우리 HQ 장치(SourceCooldown, HqHostTracker, HqQuality.couldWin 90초 소리 비교, SearchReport, strictOriginal, manual)로 바꿨다. 우리 HQ 코드가 PlaybackService·QualityUpgrade·HqLibraryUpgrade·MvSoundRedownload·HqAudioVerify 에 퍼져 있어서 한 번에 떼면 컴파일이 깨지므로 일단 채택.
- 10-31: fix-1030 의 "가수명이 다른 글자(일본어 등)로 적힌 고음질 사본 허용"(`allowArtistUnverified`, 테스트 UpgradeArtistScriptSearch/UnverifiedFallback/UnverifiedRanking 3개)은 우리 HQ 장치 깊숙이 붙어 있어 옮기지 않고 테스트만 뺐다. 1.9 는 같은 문제를 `TrackMatcher.score` 의 CREDIT_OVERRIDE_SEC(길이 2초 이내면 허용)로 푼다. HQ 교체 때 이 3개 테스트(`git show origin/task/fix-1030:app/src/test/java/com/music/bitchord/<이름>.kt`)를 1.9 경로 기준으로 다시 써서 같은 곡이 통과하는지 확인할 것.
- 사용자 방침(1.9 기본, 우리 품질 업그레이드는 문제 많음)에 따라 컴파일 초록 뒤 한 작업으로 교체: ① 우리 HQ 기능 vs 1.9 QualityUpgrade/DirectSourceGate 기능 비교표 작성(DeepSeek), ② 1.9 에 없는 것 중 **맞는 곡**에 관한 것(strictOriginal = 커버·리마스터로 바꾸지 않기, MV 소리 재다운로드)만 ko/ hook 으로 유지, ③ 나머지 HQ 장치는 1.9 로 교체, ④ HQ 테스트·featurehealth D1-hq 로 전후 비교.

## 병합 뒤 task/fix-1030 에 더 들어간 수정 (10-31, 원래 세션)
병합 기준 `724fd5ef` 이후 15커밋(가사 싱크 엔진 실패 표시, 늦게 온 표지 다시 그리기, 다음 곡 비디오 싱크 429 대기, 품질 업그레이드 가수명/네트워크 실패 표시). 컴파일이 초록이 된 뒤 `origin/task/fix-1030` 을 한 번 더 합친다. 품질 업그레이드 관련 커밋은 1.9 Direct Source 로 대체되므로 테스트만 비교 자료로 쓴다.
