# 업스트림 v1.9 HQ(품질 업그레이드) 비교

작성일: 2026-10-31
대상: 현재 브랜치(v1.9 병합본) vs 업스트림 `kushagrasinghx/BitChord` 태그 `v1.9`(`04f64c9f`)

## 먼저 알아둘 점

- **v1.9에도 HQ 장치가 이미 있습니다.** `DirectSourceGate.kt`, `SourceResolver.directStream`, `QualityUpgrade.kt`, 오디션(무음 플레이어로 먼저 확인하기), `lookForBetterCopy`, `ManualUpgradeThrottle`은 v1.9 원본에 있습니다. 현재 트리의 `DirectSourceGate.kt`는 v1.9와 **완전히 같습니다**(diff 없음).
- 그래서 이 작업은 "포크 HQ를 통째로 지우고 새로 만든다"가 아니라, **포크가 v1.9 위에 덧붙인 수색·재시도·호스트 건강·다운로드 패스·지문 검증을 걷어내는 일**입니다.
- 판정 기준:
  - `USE-1.9` = v1.9 코드 그대로 쓴다.
  - `KEEP-AS-KO-HOOK` = **맞는 곡/맞는 녹음을 지키는 안전장치일 때만** `com.music.bitchord.ko`에 작은 훅으로 남긴다.
  - `DROP` = 포크 장치를 버린다.

## 포크 HQ 동작별 비교

| # | 포크 HQ 동작 (파일:함수) | 하는 일 | v1.9 대응 | 이 동작을 커버하는 테스트 | 잃을 때 위험 | 판정 |
|---|---|---|---|---|---|---|
| 1 | `SourceResolver.kt`의 `SourceCooldown` | 소스가 2번 실패하면 30분 쉬게 하고, 네트워크 실패와 인증 실패를 구분합니다. | 없음. v1.9 `bestAcross`는 매번 모든 소스에 질문합니다. | `HqNetworkFailureTest`, `AddonAuthFailureTest`, `AddonAuthReviewTest`, `HqSearchCacheTest` | 품질/맞는 곡 문제는 아닙니다. 죽은 소스 때문에 매 곡 타임아웃이 생겨 **배터리·데이터·시작 지연**이 늘 수 있습니다. | DROP |
| 2 | `SourceResolver.kt`의 `searchAnswers` 검색 메모 | 같은 소스·같은 질문의 결과를 10분간 저장하고, 실패가 섞인 "없음"은 저장하지 않습니다. | 없음. v1.9는 같은 질문도 다시 검색합니다. | `HqSearchCacheTest`, `HqNetworkFailureTest` | 품질/맞는 곡 문제는 아닙니다. **배터리·데이터**를 더 씁니다. 반대로 오래된 결과를 재사용하는 위험도 포크 쪽에 있습니다. | DROP |
| 3 | `SourceResolver.kt`의 `SearchOutcome`/`SearchReport`/`hq-search` 로그, `authSourceNames` | 소스별로 "왜 못 찾았는지"(제목/버전/가수/길이/인증/타임아웃)를 모아 한 줄로 남깁니다. | 없음. v1.9는 일반 `TrackLog` 줄만 있습니다. | `AddonAuthFailureTest`, `AddonAuthReviewTest`, `HqNetworkFailureTest`, `HqSearchCacheTest`, `UpgradeArtist*` | 소리에는 영향이 없습니다. **진단/featurehealth 로그**만 잃습니다. 필요하면 v1.9 코드에 별도 로그로 다시 넣을 수 있습니다. | DROP |
| 4 | `SourceResolver.kt`의 `strictOriginal` + `TrackMatcher.isOriginalRecording` (원곡/커버/라이브/리마스터/테이크 거르기) | 다른 소스의 후보가 **같은 녹음인지**를 제목·테이크·길이·커버 표식으로 먼저 거르고, 통과한 것만 재생·다운로드·교체합니다. | v1.9는 `TrackMatcher.score`만 씁니다. 다른 가수 크레딧은 `CREDITS_DISAGREE` + `CREDIT_OVERRIDE_SEC = 2`(길이가 2초 이내면 허용)로 통과시킵니다. `directStream`에는 `strictOriginal`이 없습니다. | `SourcesTest`(커버 거부), `TrackMatcherJudgementTest`, `TrackMatcherJudgementEdgeTest`, `TrackMatcherOstReviewTest`, `TrackMatcherArtistScriptTest` 일부 | **맞는 곡 위험**: 길이가 비슷한 커버·라이브·리마스터·다른 테이크가 원곡 자리에 재생될 수 있습니다. 특히 v1.9의 2초 크레딧 예외는 이 위험을 막지 못합니다. | **KEEP-AS-KO-HOOK** |
| 5 | `TrackMatcher.isArtistUnverifiedOriginal` / `allowArtistUnverified` (다른 문자권 가수명) | 가수명이 전혀 다른 문자(예: `マイリー・サイラス` vs `Miley Cyrus`)여도 제목·테이크·길이(3초 이내)·커버 아님 조건이 맞으면 후보로 남기고, 소리 비교에 넘깁니다. | v1.9는 같은 문자권 이름 비교만 하고, 다른 크레딧은 `CREDIT_OVERRIDE_SEC = 2`로만 예외입니다. 문자 변환/별칭 표는 없습니다. | `TrackMatcherArtistScriptTest`, `UpgradeArtistScriptSearchTest`, `UpgradeArtistUnverifiedFallbackTest`, `UpgradeArtistUnverifiedRankingTest` | 현재 **실제 재생 경로에 연결되어 있지 않습니다.** 위 테스트가 쓰는 `bestAcross(allowArtistUnverified=...)` 매개변수는 있으나, 일반 업그레이드 호출자는 이 값을 넘기지 않아 지금 상태로는 동작이 없습니다. 연결하면 곡 후보가 넓어지므로 소리 비교 없이는 **맞는 곡 위험**이 생깁니다. | DROP (증거가 생기면 `HqAudioVerify`와 함께 훅으로 재검토) |
| 6 | `SourceResolver.rememberOriginal` / `prefetchSubstitute`의 "검증된 원곡만 대체재로 사용" | 이전에 검증된 원곡이 있을 때만 다른 소스를 씁니다. 없으면 YouTube로 시작합니다. | v1.9 `substituteForYouTube`는 그 자리에서 `bestAcross`로 경쟁시키고, `prefetchSubstitute`는 소리 검증 없이 핀을 심습니다. | 직접 커버하는 테스트 없음 | **첫 소리 품질/지연**이 달라집니다. 맞는 곡 보호는 4번(`strictOriginal`)과 `HqAudioVerify`로 따로 할 수 있습니다. | DROP |
| 7 | `SourceResolver.blockedStartStream` + `PlaybackService`의 막힌 시작 고품질 구제·`verifyBlockedStartCandidate` | YouTube가 막힌 곡에서 첫 소리 전에 다른 소스를 짧게 찾아 재생하고, 재생 후 지문으로 확인합니다. | 없음. v1.9는 막힌 곡을 YouTube 대체/건너뛰기로 처리합니다. | `BlockedStartPolicyTest`(shared), `UnplayableRecoveryTest` 일부 | 재생 구제/품질 문제입니다. 지문 확인이 없으면 **맞는 곡 위험**이 있지만, v1.9에는 이 기능 자체가 없습니다. | DROP |
| 8 | `shared`의 `HqQuality`/`HqTier` 계단(하이레스·일반 로스리스·Atmos·가짜 FLAC·비트레이트 비교) | 품질을 여러 단계로 나눠 더 좋은 후보를 고릅니다. | v1.9는 `StreamFormat.isLossless`/`isDolbyAtmos`와 `SourceResolver.isBetter`/`beatsYouTubeAac`로 충분히 비교합니다. | `HqQualityBoundaryTest`, `HqTierTest`, `PlayingAudioFormatTest`, `QualityUpgradeAdviceTest` | 품질 선택 문제입니다. 맞는 곡/배터리와 직접 관계는 없습니다. | DROP |
| 9 | `QualityUpgrade.kt`의 `retryAfter`/불확정 재시도(60초) | 소스 실패·타임아웃으로 결론이 안 난 "없음"을 영구 결론으로 저장하지 않고, 다음 재생 이벤트에서 다시 묻습니다. | 없음. v1.9는 `asked`에 넣어 이번 재생에서는 끝냅니다. | 직접 커버하는 테스트 없음 | 품질만 잃습니다(일시 장애 때 업그레이드 포기). 맞는 곡 위험은 없습니다. | DROP |
| 10 | `QualityUpgrade.kt`의 `failedCandidate`/`retryNext`/`failedSources`/`failedHosts` | 실패한 후보의 소스·호스트를 다음 시도에서 제외하고, 같은 질문을 다음 소스로 넘깁니다. | 없음. | 직접 커버하는 테스트 없음 | **현재 호출자가 없습니다(죽은 코드).** 품질/재시도 문제이며 맞는 곡 위험은 없습니다. | DROP |
| 11 | `QualityUpgrade.kt`의 수동 업그레이드 확장: `manual` Pending, 12초 수동 타임아웃, 메모/휴식 무시, `adoptByHand`, `Refusal`, `defersAutomaticAnswerToManual` | 사용자가 직접 "품질 올리기"를 눌렀을 때 검색 규칙을 바꾸고, 거절 이유를 나눠 보여줍니다. | v1.9에도 `askByHand`, `upgradeQualityNow`, `ManualUpgradeThrottle`이 있지만 수동 전용 검색 규칙·거절 enum은 없습니다. | `QualityUpgradeRefusalTest`, `QualityUpgradeScopeTest`(수동 2개), `AddonAuthFailureTest`(수동 12초), `AddonAuthFollowupTest` | 수동 UX/품질 문제입니다. 맞는 곡 위험은 없습니다. | DROP |
| 12 | `QualityUpgrade.kt`의 `forgetUrl`/`forgetReverted` | 429가 난 URL과 그 URL을 가리키던 핀·선택을 함께 지웁니다. | v1.9에는 `forget`만 있고 URL 단위·되돌리기 단위 정리는 없습니다. | `StreamChoiceTest`(되돌리기 1개) | 품질/안정성 문제입니다(죽은 URL 재사용). 맞는 곡 위험은 아닙니다. | DROP |
| 13 | `QualityUpgrade.kt`의 `upgradeGeneration`/`hifi-2` 렌디션 키 | 업그레이드 세대마다 캐시 키를 분리해, 두 스트림이 같은 파일에 섞이지 않게 합니다. | v1.9는 `upgradedUri`에 `q=hifi` 표식은 있지만 세대(`hifi-2`) 구분은 없습니다. | `AutomixAnalysisSourceTest`(hifi-2), `QualityUpgradeScopeTest` 일부 | **맞는 소리 위험**: 서로 다른 스트림이 같은 캐시 키에 이어 붙어 잘리거나 다른 오디오가 재생될 수 있습니다. 실제 실패를 설명하는 안전 규칙입니다. | **KEEP-AS-KO-HOOK** |
| 14 | `playback/smart/HqAudioVerify.kt` + `HqVerificationStore` + `HqMatchPolicy` + `HqReferenceCache` + `HqVerifyPolicy` | 90초 오디오 지문(크로마)을 비교해 커버·라이브·다른 마스터를 거부합니다. | v1.9의 오디션은 길이와 디코더 MIME만 확인합니다. 같은 길이의 커버/다른 마스터는 구분하지 못합니다. | `HqAudioVerifyCancellationTest`, `HqAudioVerifyPendingRetryTest`, `HqVerificationSourceIdentityTest`, `HqVerificationStoreFormatTest`, `HqVerificationStoreInvalidationTest`, `HqMatchPolicyTest`, `HqReferenceCacheTest`, `HqVerifyPolicyTest` | **맞는 곡 위험**: 길이·MIME이 같은 커버/라이브/리마스터가 통과할 수 있습니다. 단, 현재 일반 업그레이드 경로가 아니라 막힌 시작·429 재개·다운로드에서만 쓰입니다. | **KEEP-AS-KO-HOOK** (일반 업그레이드 전체에 붙이지 말고, v1.9가 증명 못 하는 곳에만) |
| 15 | `playback/HqHostPolicy.kt`의 `HqHostTracker`/`HqHostGate` | 호스트별 429 `Retry-After`, 휴식, 동시 다운로드 직렬화, 성공/실패/속도 점수를 기록합니다. | 없음. v1.9 `ChunkedDataSource`에는 호스트 건강·휴식 개념이 없습니다. | `HqHostPolicyTest`, `HqRateLimitRecoveryTest`, `ChunkedDataSourceRecoveryHostTest`, `ChunkedDataSourceSleepingGatewayTest` | 품질/안정성/배터리/데이터 문제입니다. 맞는 곡 위험은 아닙니다. | DROP |
| 16 | `playback/HqSwapPolicy.kt`의 스페어 플레이어 크로스페이드 교체(`swapLosslessMidSong`, `smoothSwapToLossless`) | 곡 중간에 다른 플레이어로 손실 음원을 열고 250ms 페이드로 바꿉니다. | v1.9는 `swapIn`에서 `replaceMediaItem` + 오디션 + `watchUpgrade`로 바꿉니다. | `HqSwapPolicyTest`, `HqSwapPolicyEdgeTest` | 오디오 끊김/배터리 문제입니다. **현재 호출자가 없습니다(죽은 코드).** | DROP |
| 17 | `playback/HqSwapPolicy.kt`의 보호 판정(`stillOwnsItem`, `provesDuration`, `versionSwapMayStart`, `transitionOwnsPlayer`, `toYoutubeMs`) | 교체 중 다른 곡/다른 위치로 바뀌지 않았는지, 길이가 맞는지, 버전 교체와 손실 교체가 동시에 플레이어를 차지하지 않는지 확인합니다. | v1.9에도 `watchUpgrade`, `swapPointFor`, 핀 확인이 있지만 같은 이름·같은 범위의 보호는 없습니다. | `HqSwapPolicyTest`, `HqSwapPolicyEdgeTest` | **맞는 곡/맞는 위치 위험**: 스왑 도중 다른 트랙이나 다른 지점으로 튈 수 있습니다. 단, 16번의 죽은 교체 경로를 버리면 일부 판정도 함께 사라집니다. | **KEEP-AS-KO-HOOK** (살아 있는 버전 교체/전환 보호만) |
| 18 | `download/HqLibraryUpgrade.kt`의 보관함 자동 교체·미리 저장 패스 | 검증된 고품질 짝을 보관함 파일에 자동으로 덮어쓰거나, 좋아요/자주 듣는 곡을 미리 받아 둡니다. | 없음. v1.9에는 이 자동 패스가 없습니다. | `HqLibraryMeteredPolicyTest`, `HqLibraryUpgradeBackgroundPruneTest`, `HqLibraryUpgradeCancellationTest`, `HqLibraryUpgradeSelectionTest`, `HqLibraryUpgradeSettleGuardTest`, `HqReplacementLandedTest`, `HqRetryActiveTest`, shared `HqLibrary*` | **배터리/데이터**를 많이 쓰고, 자동 교체가 틀리면 파일 품질/녹음이 바뀔 수 있습니다. v1.9 기본값과 맞지 않습니다. | DROP |
| 19 | `download/MvSoundRedownload.kt`의 MV 사운드 재다운로드 | 다운로드 파일이 뮤직비디오 사운드로 판명되면, 같은 곡의 음원 버전을 다시 받습니다. | 없음. v1.9에는 이 자동 수리가 없습니다. | `MvSoundRedownloadPolicyTest`, `MvSoundRedownloadScheduleTest` | **맞는 곡 위험**: 다운로드에 MV 사운드/다른 버전이 남습니다. 다만 자동 작업이므로 배터리/데이터도 함께 고려해야 합니다. | **KEEP-AS-KO-HOOK** (한 번짜리 WorkManager, 폴링 없이) |
| 20 | HQ 다운로드 출처·검증 짝(`Downloads`의 `routeForVerified`, `hqSourceConfigId`, `hqTrackId`, `wrongRecording`, `HqLibraryPolicy`) | 어떤 소스·트랙·품질로 검증된 파일인지 기록하고, 그 짝이 맞을 때만 기존 파일을 대체합니다. | 없음. v1.9 다운로드는 일반 파일 경로만 압니다. | `DownloadedQualityTest`, `DownloadedQualityStoredTierTest`, `DownloadsFailClearsHqOverrideTest`, `HqReplacementLandedTest`, `DownloadHealthRetryPolicyTest`, `DownloadHealthPolicyTest` | **맞는 곡 위험**: 검증되지 않은 파일이 곡 이름으로 저장되어 나중에 다른 녹음이 재생될 수 있습니다. | **KEEP-AS-KO-HOOK** (자동 교체 패스가 아니라 "검증된 짝" 기록만) |
| 21 | `PlaybackService`의 수동 업그레이드 대기자(`manualUpgradeFor`, `manualJoinedRunningUpgrade`, `reportManualUpgrade`, `adoptByHand`) | 수동 요청이 자동 검색과 겹칠 때 답을 미루고, 사용자에게 거절 이유를 알려줍니다. | v1.9에도 `upgradeQualityNow`/`ManualUpgradeThrottle`이 있지만 이 대기자·거절 메시지 분기는 없습니다. | `QualityUpgradeRefusalTest`, `QualityUpgradeScopeTest`(수동 2개) | 수동 UX/품질 문제입니다. 맞는 곡 위험은 없습니다. | DROP |
| 22 | `playback/NextTrackPreparation`의 HQ 라벨/캐시 선택(`HqQuality.tierOf`, `isLosslessTier`) | 다음 곡 준비에서 품질 라벨과 손실 캐시 선택을 결정합니다. | v1.9는 `StreamFormat`과 기존 캐시 선택을 씁니다. | `NextPrepHqLabelTest`, `NextPrepCacheKeyTest` | 표시/품질 문제입니다. 맞는 곡 위험은 없습니다. | DROP |

## v1.9에 이미 있어서 다시 만들 필요가 없는 것

아래는 포크가 아니라 v1.9 원본에도 있는 기능입니다. 이번 교체에서 새로 만들지 말고 **v1.9 것을 그대로** 씁니다.

- Direct Source Stream: `DirectSourceGate` (`isActive`, `awaitOpen`, `lookup`, `startedOnYouTube`, `isStreamReady`)
- 첫 소리 전 애드온 직접 재생: `SourceResolver.directStream`
- 기본 품질 업그레이드: `QualityUpgrade.settledForLess`, `lookAgain`, `swapIn`, `shelve`, `forgetLastSession`
- 무음 오디션과 길이/MIME 확인: `auditionUpgrade`, `auditionVerdict`, `watchUpgrade`
- 수동 업그레이드 기본 동작: `upgradeQualityNow`, `ManualUpgradeThrottle`, `askByHand`
- 품질 비교: `SourceResolver.worthSwapping`, `sameRecordingAs`, `isBetter`, `beatsYouTubeAac`
- v1.9 `TrackMatcher.score`의 다른 크레딧 예외: `CREDITS_DISAGREE`, `CREDIT_OVERRIDE_SEC = 2`

## 교체 순서

1. **현재 상태를 기준선으로 저장합니다.** `:app:compileProdReleaseKotlin`은 통과하고, `UpgradeArtist*` 3개 파일이 쓰는 `allowArtistUnverified` 매개변수도 `bestAcross`에 있어 테스트 컴파일은 통과합니다. 다만 일반 업그레이드 호출자는 이 값을 넘기지 않습니다.
2. **v1.9 파일을 그대로 가져옵니다.** `SourceResolver.kt`, `QualityUpgrade.kt`, `PlaybackService.kt`, `TrackMatcher.kt`, `StreamFormat.kt`를 v1.9 것으로 되돌립니다. `DirectSourceGate.kt`는 이미 v1.9와 같으므로 건드리지 않습니다.
3. **포크 전용 HQ 파일을 지웁니다.** `playback/HqHostPolicy.kt`, `playback/HqSwapPolicy.kt`, `download/HqLibraryUpgrade.kt`, `playback/smart/HqAudioVerify.kt`(아래 4번으로 옮길 최소 부분 제외), `shared`의 `HqLibraryPolicy`, `HqLibraryAutoRetryPolicy`, `HqTier`, `HqQuality`, `HqMatchPolicy`(훅에 필요한 부분 제외), `HqReferenceCache`(훅에 필요한 부분 제외), `HqVerifyPolicy`(훅에 필요한 부분 제외).
4. **KEEP 항목만 `com.music.bitchord.ko`에 작은 훅으로 옮깁니다.**
   - `ko/OriginalRecordingKo.kt`: v1.9 `directStream`/`upgradeFor`/`bestAcross` 결과에 원곡 필터(`TrackMatcher.isOriginalRecording`)를 한 번 적용합니다. v1.9의 2초 크레딧 예외로 커버가 들어오지 않게 하는 것이 목적입니다.
   - `ko/HqVerifyKo.kt`: `HqAudioVerify`의 최소 부분(지문 비교 + `HqMatchPolicy` + `HqVerificationStore`)만 옮기고, 일반 업그레이드 전체가 아니라 **막힌 시작·429 재개·다운로드 검증**에만 겁니다. 이때도 한 번의 이벤트로만 실행하고 타이머/폴링을 만들지 않습니다.
   - `ko/MvSoundKo.kt`: `MvSoundRedownload` 정책과 한 번짜리 WorkManager 예약만 남기고, v1.9의 앱 시작/재생 이벤트에 연결합니다.
   - `ko/DownloadProvenanceKo.kt`: `hqSourceConfigId`/`hqTrackId`/`wrongRecording` 같은 "검증된 짝" 기록만 남깁니다. 보관함 전체 자동 교체 패스는 넣지 않습니다.
   - `ko/RenditionKeyKo.kt`: `hifi-2` 세대 키와 `forgetReverted`에 해당하는 캐시 분리만 v1.9 `QualityUpgrade.upgradedUri` 옆에 붙입니다.
   - `ko/SwapGuardKo.kt`: `versionSwapMayStart`, `transitionOwnsPlayer`, `stillOwnsItem`, `provesDuration` 중 v1.9에 없는 보호만 남깁니다. 스페어 플레이어 페이드 교체(`smoothSwapToLossless`)는 가져오지 않습니다.
5. **죽은 포크 진입점을 지웁니다.** `swapLosslessMidSong`, `prefetchLosslessForNextPlay`, `QualityUpgrade.failedCandidate`, `QualityUpgrade.retryNext`, `TrackMatcher.allowArtistUnverified`, `blockedStartStream`(7번을 DROP하므로), `HqLibraryUpgrade` 예약 호출을 제거합니다.
6. **KEEP 훅을 연결합니다.** 연결 지점은 다음 네 곳만 둡니다: ① Direct Source 결과 수락 직전, ② 429 재개/막힌 시작의 대체 스트림 수락 직전, ③ 다운로드 파일 저장 직전, ④ MV 사운드 재다운로드 예약. 그 외의 검색·재시도·휴식·호스트 건강·보관함 자동 교체는 넣지 않습니다.
7. **배터리 규칙을 확인합니다.** 새 훅에 `delay` 루프, 주기적 타이머, `while(true)` 폴링을 만들지 않습니다. 재생 이벤트·전환 이벤트·WorkManager 한 번짜리 작업만 사용합니다.
8. **테스트를 정리합니다.** 아래 목록대로 지우거나 다시 씁니다. 마지막에 `:app:compileProdReleaseKotlin`과 남긴 훅의 테스트를 다시 돌립니다.

## 테스트 파일 정리

### 지울 파일 (포크 HQ 장치 전용)

- `app/src/test/java/com/music/bitchord/AddonAuthFailureTest.kt`
- `app/src/test/java/com/music/bitchord/AddonAuthFollowupTest.kt`
- `app/src/test/java/com/music/bitchord/AddonAuthReviewTest.kt`
- `app/src/test/java/com/music/bitchord/AddonClientHealthTest.kt`
- `app/src/test/java/com/music/bitchord/HqHostPolicyTest.kt`
- `app/src/test/java/com/music/bitchord/HqNetworkFailureTest.kt`
- `app/src/test/java/com/music/bitchord/HqRateLimitRecoveryTest.kt`
- `app/src/test/java/com/music/bitchord/HqSearchCacheTest.kt`
- `app/src/test/java/com/music/bitchord/UpgradeArtistScriptSearchTest.kt`
- `app/src/test/java/com/music/bitchord/UpgradeArtistUnverifiedFallbackTest.kt`
- `app/src/test/java/com/music/bitchord/UpgradeArtistUnverifiedRankingTest.kt`
- `app/src/test/java/com/music/bitchord/QualityUpgradeRefusalTest.kt`
- `app/src/test/java/com/music/bitchord/playback/HqSwapPolicyTest.kt`
- `app/src/test/java/com/music/bitchord/playback/HqSwapPolicyEdgeTest.kt`
- `app/src/test/java/com/music/bitchord/playback/NextPrepHqLabelTest.kt`
- `app/src/test/java/com/music/bitchord/playback/ChunkedDataSourceRecoveryHostTest.kt`
- `app/src/test/java/com/music/bitchord/playback/ChunkedDataSourceSleepingGatewayTest.kt`
- `app/src/test/java/com/music/bitchord/download/DownloadHealthRetryPolicyTest.kt`
- `app/src/test/java/com/music/bitchord/download/DownloadedQualityTest.kt`
- `app/src/test/java/com/music/bitchord/download/DownloadedQualityStoredTierTest.kt`
- `app/src/test/java/com/music/bitchord/download/DownloadsFailClearsHqOverrideTest.kt`
- `app/src/test/java/com/music/bitchord/download/HqLibraryMeteredPolicyTest.kt`
- `app/src/test/java/com/music/bitchord/download/HqLibraryUpgradeBackgroundPruneTest.kt`
- `app/src/test/java/com/music/bitchord/download/HqLibraryUpgradeCancellationTest.kt`
- `app/src/test/java/com/music/bitchord/download/HqLibraryUpgradeSelectionTest.kt`
- `app/src/test/java/com/music/bitchord/download/HqLibraryUpgradeSettleGuardTest.kt`
- `app/src/test/java/com/music/bitchord/download/HqReplacementLandedTest.kt`
- `app/src/test/java/com/music/bitchord/download/HqRetryActiveTest.kt`
- `shared/src/jvmTest/kotlin/com/music/bitchord/data/net/HqAutoDownloadPolicyTest.kt`
- `shared/src/jvmTest/kotlin/com/music/bitchord/data/sources/HqLibraryAutoRetryPolicyTest.kt`
- `shared/src/jvmTest/kotlin/com/music/bitchord/data/sources/HqLibraryPolicyBoundaryTest.kt`
- `shared/src/jvmTest/kotlin/com/music/bitchord/data/sources/HqLibraryPolicyTest.kt`
- `shared/src/jvmTest/kotlin/com/music/bitchord/data/sources/HqQualityBoundaryTest.kt`
- `shared/src/jvmTest/kotlin/com/music/bitchord/data/sources/HqTierTest.kt`
- `shared/src/jvmTest/kotlin/com/music/bitchord/data/sources/QualityUpgradeAdviceTest.kt`
- `shared/src/jvmTest/kotlin/com/music/bitchord/data/sources/PlayingAudioFormatTest.kt`
- `shared/src/jvmTest/kotlin/com/music/bitchord/data/sources/TrackMatcherArtistScriptTest.kt` (5번을 DROP하므로)

### 고쳐서 남길 파일 (KEEP 훅 검증)

- `app/src/test/java/com/music/bitchord/download/MvSoundRedownloadPolicyTest.kt`
- `app/src/test/java/com/music/bitchord/download/MvSoundRedownloadScheduleTest.kt`
- `app/src/test/java/com/music/bitchord/playback/smart/HqAudioVerifyCancellationTest.kt` (훅으로 옮긴 뒤 경로만 수정)
- `app/src/test/java/com/music/bitchord/playback/smart/HqAudioVerifyPendingRetryTest.kt`
- `app/src/test/java/com/music/bitchord/playback/smart/HqVerificationSourceIdentityTest.kt`
- `app/src/test/java/com/music/bitchord/playback/smart/HqVerificationStoreFormatTest.kt`
- `app/src/test/java/com/music/bitchord/playback/smart/HqVerificationStoreInvalidationTest.kt`
- `shared/src/jvmTest/kotlin/com/music/bitchord/data/sources/HqMatchPolicyTest.kt`
- `shared/src/jvmTest/kotlin/com/music/bitchord/data/sources/HqReferenceCacheTest.kt`
- `shared/src/jvmTest/kotlin/com/music/bitchord/data/sources/HqVerifyPolicyTest.kt`
- `shared/src/jvmTest/kotlin/com/music/bitchord/data/sources/DownloadHealthPolicyTest.kt` (검증된 짝 기록만 남기는 범위로 축소)
- `app/src/test/java/com/music/bitchord/playback/CacheRenditionChoiceTest.kt` (렌디션 키 훅 범위로 축소)
- `app/src/test/java/com/music/bitchord/AutomixAnalysisSourceTest.kt` (`hifi-2` 세대 키를 훅으로 남길 때만 유지)
- `app/src/test/java/com/music/bitchord/StreamChoiceTest.kt` (`forgetReverted`를 훅으로 남길 때만 유지)

### v1.9 원본으로 되돌릴 파일

- `app/src/test/java/com/music/bitchord/QualityUpgradeScopeTest.kt` (현재 포크가 수동 테스트 2개를 추가했습니다. v1.9 4개로 되돌립니다.)
- `app/src/test/java/com/music/bitchord/SourcesTest.kt` (v1.9 원본 + KEEP 원곡 필터 테스트만 추가)
- `app/src/test/java/com/music/bitchord/PlayerQualityMenuTest.kt` (v1.9 원본 유지)
- `app/src/test/java/com/music/bitchord/TrackIdentityMismatchTest.kt` (v1.9 원본 유지)
- `shared/src/jvmTest/kotlin/com/music/bitchord/data/sources/TrackMatcherJudgementTest.kt`, `TrackMatcherJudgementEdgeTest.kt`, `TrackMatcherOstReviewTest.kt` (v1.9 원본 유지, 원곡 필터 테스트만 추가)

## 확인된 사실과 남은 질문

- 확인: v1.9 `DirectSourceGate.kt`는 현재 트리와 diff가 없습니다. v1.9에도 `QualityUpgrade`와 오디션이 있습니다.
- 확인: 포크의 일반 업그레이드는 v1.9 `QualityUpgrade` + 오디션을 그대로 쓰고, `HqAudioVerify`는 일반 업그레이드에 연결되어 있지 않습니다.
- 확인: `swapLosslessMidSong`/`prefetchLosslessForNextPlay`, `QualityUpgrade.failedCandidate`/`retryNext`는 호출자가 없는 죽은 코드입니다.
- 확인: `allowArtistUnverified`는 `bestAcross` 매개변수로만 있고 일반 업그레이드 호출자는 넘기지 않아, 실제 재생 경로에서는 쓰이지 않습니다. `UpgradeArtist*` 테스트 3개는 이 매개변수로 통과합니다.
- 남은 질문: v1.9 `CREDIT_OVERRIDE_SEC = 2`만으로 실제 사용자 로그에서 커버/리마스터 오재생이 있었는지 증거가 필요합니다. 증거가 없으면 5번은 DROP이 맞습니다.
- 남은 질문: `HqAudioVerify` 훅을 막힌 시작·429 재개·다운로드 중 어디까지 남길지, 그리고 그 비용(90초 비교)을 배터리 예산 안에서 허용할지 결정해야 합니다.
