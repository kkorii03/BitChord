# DunkBlock Benchmark

덩크시티 다이너스티 자동 블록 시스템의 **사전 검증용 측정 앱**입니다. 자동 블록 프로그램이 아닙니다.
Galaxy S24 Ultra에서 화면 인식 방식이 블록 타이밍을 맞힐 만큼 빠른지, 그리고 수동 이동과 자동 입력이
양립하는지를 **측정값으로** 확인하는 것이 목적입니다.

- 기술 검토와 판정(가능/불가능/미확인): [docs/FEASIBILITY.md](docs/FEASIBILITY.md)
- 설치·측정 절차·결과 해석: [docs/MEASUREMENT_GUIDE.md](docs/MEASUREMENT_GUIDE.md)

## 측정 기능

| # | 시험 | 결과 | 성격 |
|---|---|---|---|
| ① | 게임 캡처 세션 | 실효 캡처 FPS, 프레임 간격(평균·p95), 중복/백로그, 검은 화면 비율, 단계별 처리시간, 움직임 이벤트 + 증거 이미지, 1초 단위 열/CPU | 실측 (누락 판정은 근사) |
| ② | 지연·FPS 패턴 | 화면 카운터로 표시 프레임 대비 정확한 고유/중복/누락 수, 표시→수신 지연, 색상 전환 지연, 타임베이스 점검 | 계수는 실측, 지연은 추정(예상 표시시각 기준) + 외부 촬영 검증 지원 |
| ③ | 합성 모션 검출 | 정답 시작 시각 대비 검출 지연, 점프 검출률, 페이크 반응률 | 합성 장면 한정 실측 |
| ④ | 조이스틱 + 자동 탭 충돌 | 기존 터치 취소 여부, MOVE 끊김, 주입 탭 도착/지연 | 실측 (앱 화면) |
| ⑤ | 인게임 자동 탭 | 탭 전송/완료 기록 (이동 끊김은 사용자 관찰) | 관찰 |

측정 중에는 오버레이를 띄우지 않습니다. 모든 기록은 미리 할당한 메모리 배열에 쌓고, 세션 종료 후에 CSV로 씁니다.

## 구조

```
dunkblock/
├─ core/   순수 Kotlin(JVM) — 기기 없이 단위 테스트
│   ├─ Stats, FrameLog, DisplayLog            통계 / 사전 할당 로그
│   ├─ PatternCodec                           화면 프레임 카운터 인코딩·디코딩
│   ├─ Motion (KotlinMotion, gridFeatures)     축소·그레이·차분·격자/ROI 특징 (무의존 백엔드)
│   ├─ AttackEventDetector                    임계·확정·중복 병합·전체화면 모션 억제
│   ├─ CaptureAnalysis, TimingReport          ①②③ 보고서 (서로 다른 시계는 빼지 않음)
│   ├─ TruthMatcher, DisplayReport            합성 정답 대비 지연 / 표시 측 끊김
│   ├─ BlockGeometry                          캐릭터별 블록 각도(180°/측면 포함/360°) 판정
│   └─ Csv, SessionReport                     원시 CSV · 요약
├─ app/    Android (Kotlin, AndroidX 미사용, OpenCV 4.12.0 Maven)
│   ├─ CaptureService       MediaProjection + VirtualDisplay + ImageReader, 전용 고우선순위 스레드
│   ├─ FramePipeline        프레임별 단계 계측, 백엔드 실행, 이벤트 증거 이미지
│   ├─ OpenCvMotion         Image 버퍼를 무복사로 Mat 래핑 → resize → gray → absdiff → threshold
│   ├─ PatternActivity      ② 카운터/색상 전환/ACK, ③ 합성 점프/페이크
│   ├─ TouchProbeActivity   ④ 충돌 시험
│   ├─ InGameTapTest        ⑤
│   ├─ BlockAccessibilityService  dispatchGesture 전용 (화면 내용 읽지 않음)
│   └─ SystemSampler, Exporter, DeviceInfo, MainActivity
└─ tools/analyze.py   표준 라이브러리만 사용, CSV에서 모든 수치 재계산 + 라벨 조인 + 세션 비교
```

## 빌드

- GitHub Actions: `.github/workflows/dunkblock-apk.yml` — core 테스트 → 분석기 자체 테스트 → release APK 빌드(디버그 불가, 디버그 키 서명) → 아티팩트 `DunkBlockBench-apk`.
- 로컬(Android SDK 있음): `cd dunkblock && ./gradlew :app:assembleRelease`
- 로컬(SDK 없음, core만): `cd dunkblock && DUNKBLOCK_CORE_ONLY=1 ./gradlew :core:test`

## 출력 파일 (`Download/DunkBlock/<세션>/`)

| 파일 | 내용 |
|---|---|
| `summary.txt` | 사람이 읽는 요약 (각 수치에 측정/추정 구분) |
| `frames.csv` | 프레임별: 도착 시각(nanoTime), Image.timestamp, 백로그, 패턴 카운터, 샘플 해시/중복, 단계별 처리시간(µs), 모션 값 |
| `display.csv` | (②③) 테스트 화면이 그린 프레임: vsync 시각, 예상 표시 시각, 색상 전환 |
| `events.csv` | 움직임 이벤트: 시작/확정/종료, 확정 지연, 지속, 정점, 상승량, 묘사 라벨 |
| `truth.csv` | (③) 합성 시행 정답과 매칭 결과 |
| `seconds.csv` | 1초 단위 FPS, 분석 시간, 앱 CPU, 열 상태/헤드룸, 배터리 온도 |
| `evidence/event_XXXX.png` | 이벤트 전후 32프레임 (수동 라벨용) |
| `touch_probe*.csv/txt` | (④) 충돌 시험 |

## 정직성 규칙 (코드에 반영된 것)

- 프레임 수신 간격을 캡처 지연으로 보고하지 않습니다.
- `Image.timestamp`와 `System.nanoTime()`은 시계 동일성 점검을 통과할 때만 서로 뺍니다.
- 표시 시각은 Choreographer의 **예상** 표시 시각이므로 관련 지연은 항상 "추정"으로 표기합니다.
- 움직임 이벤트의 `UPWARD_BURST` 등은 움직임 모양의 묘사일 뿐, 슛/덩크/레이업/페이크 분류가 아닙니다.
- 시스템 전체 CPU, 게임 자체 FPS처럼 일반 앱이 읽을 수 없는 값은 측정한다고 주장하지 않습니다.
