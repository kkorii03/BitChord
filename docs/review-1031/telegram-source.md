# 텔레그램 소스 1단계(엔진·로그인) 검토 지적 처리 — 2026-11-01

이번 회차는 이전 작업 결과(`dsq/tg-stage1-1101-r2`)를 유지한 채 아래 5건만 판정·수정했다.
기존 수명 정책(시작 시 자동 연결 없음, 이벤트 기반, 폴링 없음)과 Keystore·no-backup 저장 방식은
되돌리지 않았다.

## 지적별 처리

### MEDIUM — TelegramDatabaseKeyStore.kt:21-22 — 복호화 실패 시 무단 키 교체
- **판정: 지적 타당. 고침.**
- `loadOrCreate()`는 이제 키 파일이 있으면 복호화 실패를 `TelegramDatabaseKeyException`으로
  올린다. 키가 없어도 TDLib `db`/`files`가 이미 있으면 새 키를 만들지 않고 예외를 올린다.
  새 키는 세션이 전혀 없을 때만 만든다. 이전 세션을 조용히 영구 불능으로 만들지 않는다.
- 테스트: `TelegramDatabaseKeyStoreTest` (신규). 손상 키가 덮어써지지 않는지, 세션만 있고 키가
  없을 때 키 파일이 생기지 않는지 확인한다. 수정 전 코드에서는 두 경우 모두 새 키를 썼으므로 실패한다.

### HIGH — TelegramClientManager.kt:378 — 키 로딩 예외가 워커 밖으로 탈출
- **판정: 지적 타당. 고침.**
- `configure()`에서 `databaseKeyStore.loadOrCreate()`(Keystore·디스크 포함)를 try/catch로 감싸
  `TelegramError.GENERIC`으로 표시하고 반환한다. 워커 스레드에서 예외가 프로세스를 죽이지 않는다.
- 같은 실패는 위 `TelegramDatabaseKeyStoreTest`가 예외 계약을 고정한다.

### MEDIUM — TelegramClientManager.kt:267-269, :148-153 — Close 확인 전 새 클라이언트/삭제
- **판정: 지적 타당. 고침.**
- `closeNow()`는 TDLib의 `AuthorizationStateClosed`(future 완료)를 확인했을 때만 핸들을 버린다.
  5초 안에 확인되지 않으면 `client`와 세션 파일을 그대로 두고 `GENERIC`을 표시하며, 새 클라이언트를
  시작하지 않는다. 이후 Closed 업데이트가 오면 워커에서 정리하고 게이트를 다시 적용한다.
- `logOut()`도 20초 안에 Closed가 확인된 경우에만 `db`/`files`를 지운다. 확인되지 않으면
  `logoutRequested`를 유지한 채 기다리고, Closed 업데이트에서만 삭제한다.
- **이번 검증 필요:** Android/TDLib 런타임 경로라 호스트 단위 테스트로는 확인할 수 없다. 기기에서
  Close 지연/타임아웃 시 재시작·삭제가 일어나지 않는지 확인이 필요하다.

### MEDIUM — TelegramClientGate.kt:26 — 재생만으로는 열 수 없음
- **판정: 지적 타당. 고침.**
- `shouldBeOpen = playbackActive || (holders.isNotEmpty() && appForeground)`로 바꿔 재생을
  독립적인 유지 사유로 만들었다. 백그라운드여도 재생 중이면 유지되고, 재생이 끝나면 닫힌다.
- 테스트: `TelegramClientGateTest.playbackAloneOpensAndKeepsTheClientOpen` 추가. 수정 전에는
  holder 없이 재생만 켜면 false여서 실패한다. 기존 백그라운드/복귀 테스트는 그대로 통과한다.

### LOW — TelegramClientManager.kt:482-483 — StateFlow read-modify-write 및 비-volatile 필드
- **판정: 지적 타당. 고침.**
- TDLib 콜백은 이제 `work`로 넘겨 워커에서만 `onUpdate()`를 실행하고, 워커 안에서 세대(generation)를
  다시 확인한다. `auth`·`logoutRequested`·`closing`은 워커 전용이 되어 경합하지 않는다.
- `update()`는 `MutableStateFlow.update {}`로 바꿔 콜백/워커가 겹쳐도 stage/error를 덮어쓰지 않는다.
- **이번 검증 필요:** 콜백 스레드 경합은 단위 테스트 대신 코드 경로로 확인했다. 실제 TDLib 세션에서
  로그아웃·백그라운드 전환이 겹칠 때 상태가 유지되는지 기기 검증이 필요하다.

## 요약
HIGH·MEDIUM 4건 모두 코드로 고쳤고, LOW 1건도 워커 직렬화로 처리했다. 새 테스트는
`TelegramDatabaseKeyStoreTest`(신규)와 `TelegramClientGateTest`(재생 단독 사유 테스트 추가)다.
