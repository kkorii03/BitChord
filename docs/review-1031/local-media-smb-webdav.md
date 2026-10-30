# 로컬 음악 · SMB · WebDAV 심층 검토 (2026-10-31)

기준 브랜치: `task/fix-1030` (검토 브랜치 `dsq/deep-local-media-1031`)
범위: 기기 스캔(MediaStore/SAF), 다운로드 폴더, SMB, WebDAV의 스캔·필터(WAV/3GP)·재생·
표지·가사·정렬·권한 거절·큰 보관함 성능. UI 문구는 바꾸지 않았다.

검증 명령:
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` → 성공
- `./gradlew :app:testProdDebugUnitTest --tests '...' --console=plain -q` → 아래 신규/기존 테스트 성공
- 전체 `:app:testProdDebugUnitTest`는 1691개 중 22개 실패, 1개 skip. 실패 5개 클래스
  (`ProviderLyricsTest`, `ArtistNameMatchTest`, `LocaleStringsTest`, `SourceFormatsTest`,
  `BluetoothAudioTrackerTest`)는 이번 변경 전부터 있던 로케일/문자열/환경 문제이고
  이 영역의 테스트가 아니다(문서 끝 "전체 테스트" 참고).

---

## 1. 이번에 고친 결함

| # | 심각도 | 파일:줄 | 결함 | 사용자 영향 | 흔한가 | 테스트 |
|---|---|---|---|---|---|---|
| F1 | HIGH | `app/src/main/java/com/music/bitchord/data/LocalMediaRepository.kt:33-49,406-411` | WAV/3GP가 재생 가능한데도 `localMusicExtensions`에 없어 **필터 켬/끔 모두** Local Music에서 사라졌다. 필터 끔은 "재생 가능한 컨테이너만" 통과시켜야 하는데 WAV/3GP는 그 전에 탈락했다. | WAV 무손실 앨범, 3GP 음성/클립이 기기 보관함에 안 보인다. 설정 문구는 "WAV 파일을 가립니다"라고 약속하는데 필터를 꺼도 안 보였다. | 높음. WAV는 다운로드/HQ 경로가 실제로 만들고, 3GP는 통화/영상 클립에 흔하다. | `LocalMediaFilterTest` 신규 3개 |
| F2 | HIGH | `app/src/main/java/com/music/bitchord/data/webdav/WebDavRepository.kt:25-29` | `listLibrary(...).getOrNull() ?: emptyList()` — 401/403/500/연결 실패가 "빈 보관함"으로 바뀌었다. SMB는 `getOrThrow()`라서 두 원격 라이브러리의 오류 표시가 서로 달랐다. | 서버 주소·비밀번호·권한이 틀렸을 때 "곡이 없습니다"만 보이고 원인은 안 보인다. 사용자는 폴더를 뒤지거나 설정을 다시 만진다. | 높음. 첫 설정/비밀번호 변경/서버 재시작마다. | `WebDavTest.repository surfaces a listing failure...` |
| F3 | HIGH | `app/src/main/java/com/music/bitchord/playback/Autoplay.kt:104-125` | `youtubeSeedFor`가 모듈 키만 검색하고, 기기 파일의 `content://…`/`smb:…`/`webdav:…` id를 그대로 YouTube radio seed로 보냈다. YouTube는 그 id로 방송국을 만들 수 없다. | 로컬/SMB/WebDAV 곡 뒤 AutoPlay가 첫 곡 이후 채워지지 않는다. 사용자 로그의 "자동 재생 보충 성공 24%"와 직접 연결된다. | 높음. 로컬 보관함을 재생하면 매번. | `AutoplaySeedTest` 신규 2개 |
| F4 | MEDIUM(보안) | `app/src/main/java/com/music/bitchord/data/webdav/WebDavClient.kt:232-258` | PROPFIND XML 파싱 실패를 조용히 `emptyList()`로 바꿨고, `DocumentBuilderFactory`가 DOCTYPE/외부 엔티티를 허용했다. | 악의적/깨진 WebDAV 응답이 "빈 보관함"으로 보이거나, XXE로 로컬 파일/내부 URL을 읽으려는 입력을 파서가 처리할 수 있었다. | 낮음(정상 서버) ~ 중간(자가 호스팅·프록시·깨진 서버). | `WebDavTest` 신규 2개 |
| F5 | MEDIUM | `app/src/main/java/com/music/bitchord/data/webdav/WebDavClient.kt:91-97`, `app/src/main/java/com/music/bitchord/data/smb/SmbClient.kt:56-61` | `maxFiles` 상한이 디렉터리 경계에서만 검사돼, 한 폴더에 상한보다 많은 곡이 있으면 전부 메모리에 쌓였다. 이름은 상한인데 실제 상한이 아니었다. | 큰 보관함(수만 곡이 한 폴더)에서 목록이 끝없이 커져 느려지고 OOM 위험이 있다. | 중간. NAS/WebDAV에서 한 폴더에 대량 파일을 두는 사용자. | `WebDavTest.listLibrary enforces maxFiles...` |
| F6 | MEDIUM | `app/src/main/java/com/music/bitchord/data/smb/SmbConfig.kt:80-89` | SMB 경로에서 `?`/`#`를 잘라냈다. SMB 파일명에는 둘 다 합법이다. | `Track #2.flac` 같은 파일이 열리지 않거나 다른 파일/프리픽스가 열린다. | 중간. 앨범명에 `#`, `?`를 쓰는 파일이 흔하다. | `SmbTest.relativePath...` 확장 |
| F7 | MEDIUM | `app/src/main/java/com/music/bitchord/data/LocalMediaRepository.kt:33-38,450` | 다운로드 폴더의 수동 파일 판정(`isAudioFileName`)이 Local Music과 다른 목록이었다: WAV/3GP는 있고 MP4/DSD는 없었다. | 파일 관리자로 `Music/BitChord`에 넣은 DSF/DFF/오디오 MP4가 Downloads에 안 보였다. | 중간. DSD/MP4를 직접 넣는 사용자. | `LocalMediaFilterTest.download folder extras...` |
| F8 | LOW(진단) | `app/src/main/java/com/music/bitchord/data/LocalMediaRepository.kt:218-256` | 권한 거절·선택 폴더 URI 해석 실패가 로그 없이 빈 목록이 됐다. | Android Auto/백그라운드 복원에는 권한을 물을 UI가 없어 "빈 보관함"처럼 보인다. 원인 추적이 안 됐다. | 중간. 권한을 나중에 철회하거나 SAF URI가 깨졌을 때. | 로그만 추가(단위 테스트 대상 아님) |

### F1 조건 변경 전후(기존 항 모두 유지, 항만 추가)

`isEligibleLocalMusic(duration, name, path)` (필터 켬):

| 조건 | 이전 | 이후 |
|---|---|---|
| `duration in 1..29999` | false | false |
| 확장자 ∉ playable | false | false |
| 확장자 ∈ {wav, 3gp, 3gpp} | **true(계속 진행)** | **false(신규)** |
| 경로에 `/alarms/`, `/recordings/` 등 포함 | false | false |
| 그 외 | true | true |

`hasLocalMusicExtension` (필터 끔): 기존 통과 항목은 그대로 두고 `wav,wma,aif,aiff,3gp,3gpp`를 추가했다.
`isAudioFileName`은 이제 `hasLocalMusicExtension`과 같은 집합을 쓴다(기존 WAV/3GP는 유지, MP4/DSD/DFF 추가).

### F3 조건 변경 전후

`youtubeSeedFor`가 곡 id를 그대로 쓸지:

| 곡 | 이전 | 이후 |
|---|---|---|
| 일반 YouTube 11자 id | 그대로 사용 | 그대로 사용 |
| 다운로드된 YouTube 곡(localUri 있어도 id 유지) | 그대로 사용 | 그대로 사용 |
| 모듈 키 `src:…` | 검색 | 검색 |
| 기기 `content://…` / `file://…` | **그대로 사용(잘못)** | **검색(신규)** |
| `smb:…` / `webdav:…` | **그대로 사용(잘못)** | **검색(신규)** |

---

## 2. 흔한 상황별 코드 추적표

| 상황 | 진입점 → 처리 → 결과 (코드) | 실제 결과 |
|---|---|---|
| 기기 Local Music, 권한 허용 | `MainViewModel.loadLibrarySongs`/`localPageState("local:all")` → `LocalMediaRepository.getLocalMusic` (`:217`) → MediaStore 쿼리 → `isEligibleLocalMusic`/`hasLocalMusicExtension` → `Song` | 동작. WAV/3GP는 필터에 따라 이제 정상 노출(F1). |
| 권한 거절 (Android 13+ `READ_MEDIA_AUDIO`, 이하 `READ_EXTERNAL_STORAGE`) | `hasStoragePermission` (`:65`) false → `getLocalMusic` 즉시 빈 목록(`:218`) | 앱 UI는 `storage_required_read`. Auto/백그라운드 복원은 빈 목록 + 이제 로그(F8). |
| 필터 켬 + WAV/3GP | SQL `IS_MUSIC`/길이 → `isEligibleLocalMusic` (`:406`) → `nonMusicExtensions` 거절 | 숨김(설정 문구와 일치). |
| 필터 끔 + WAV/3GP | SQL 선택 없음 → `hasLocalMusicExtension` (`:426`) 통과 → 목록 | 표시(F1). |
| 다운로드 곡 재생 | `Downloads.getDownloadedSongs` → `Song.toMediaItem` (`PlayerConnection.kt:691`) → `localUri`(file/content) → `AudioCache.playbackFactory`가 로컬은 캐시 우회(`AudioCache.kt:1341`) → ExoPlayer | 동작. 파일이 없거나 wrong recording이면 `verifiedSavedUri`로 스트림 폴백. |
| 다운로드 폴더에 수동 복사한 곡 | `getDownloadedSongs` → MediaStore `RELATIVE_PATH LIKE %BitChord%` → `isAudioFileName` (`:450`) | WAV/3GP/MP4/DSD 포함(F7). |
| 비디오 노래/커버 영상 다운로드 | 다운로드 레코드의 `isVideo`/`soundSourceVideoId` → `toMediaItem` → 로컬 파일 재생, 가사는 `CoverTitles.lyricsQuery`로 원곡 기준 조회(`PlaybackService.kt:10204`) | 재생 동작. 곡 중 HQ 버전 교체는 `hqWanted`가 localUri를 제외(`NextTrackPreparation.kt:410`)해 일어나지 않음(의도: 파일 보존). |
| 셔플/다음 곡 | `LocalFolder` → `play(songs, index)` → `QueueShuffle` → 큐 아이템은 `toMediaItem` | 동작. 다음 곡 준비는 `NextTrackPreparation.schedule`. |
| 앱 재시작 후 큐 복원 | `restoreLastQueue`/`onPlaybackResumption` (`PlaybackService.kt:11508`) → 현재 큐 → `Downloads.getDownloadedSongs` (`:11526`) → `getLocalMusic` (`:11537`) | 큐가 남아 있으면 그대로. 없으면 다운로드 → 로컬 → 홈 순. 권한/파일이 없으면 조용히 다음 후보. |
| 화면 꺼짐/백그라운드 | 같은 MediaSession/ExoPlayer. 로컬은 `WAKE_MODE_LOCAL`(`:4024`). SMB는 `SmbDataSource`가 매 open마다 핸들 재사용/재연결 | 로컬 정상. SMB는 30초 read timeout 후 `SmbConnection.withShare`가 IOException 1회 재연결(`SmbConnection.kt:34`). |
| 프록시 켬 | `ProxyAwareDataSourceFactory`는 **프록시로 기록된 URL만** 프록시(`ProxyPlayback.usesProxyFor`). 로컬 file/content는 캐시도 프록시도 우회. SMB는 `SmbDataSource.RoutingFactory`가 `smb`를 직접 처리(`PlaybackService.kt:2120`) | 로컬/SMB는 프록시 영향 없음. WebDAV는 `Http.client` 직접(프록시 미적용), 사용자가 "모두 프록시"를 기대하면 불일치(미수정, 아래). |
| 느린/끊긴 망 + SMB | `SmbClient.listLibrary` `withShare` → 목록 실패 시 `getOrThrow` → `RemoteListing.state`가 오류 문구 | 오류 노출. 단, 하위 폴더 하나가 403이면 전체 목록 실패(아래 미수정). |
| 느린/끊긴 망 + WebDAV | `WebDavClient.listLibrary` → PROPFIND 207 파싱 → 실패는 이제 예외(F2/F4) | 오류 노출. |
| Android 14+ 스코프드 스토리지 | `getLocalMusic`은 MediaStore만 읽고, 선택 폴더는 `localMusicFolderUri`의 DATA 경로와 문자열 비교(`:328,370`) | 권한 있으면 동작. DATA가 null인 공급자면 선택 폴더에서 전부 탈락(아래 미수정). SAF 트리 자체를 열람하지 않는다. |
| 큰 보관함(한 폴더 1만+) | `maxFiles` 상한이 디렉터리 안에서 지켜짐(F5) | 상한에서 멈춤. 이미지 목록은 별도 상한이 없음(아래 미수정). |
| 로컬 표지 | `getLocalMusic`이 `content://media/.../albumart`만 넣음(`:316`) → 행/앨범 카드는 `rememberRemoteArtworkUrl` (`RemoteArtwork.kt:29`) | MediaStore 앨범아트가 있으면 표시. 파일 안에만 있는 커버는 로컬 곡에서 안 읽힘(아래 미수정). |
| SMB/WebDAV 표지 | 목록에서 같은 폴더 이미지 선택(`RemoteArtwork.pick`) → Coil Fetcher/HTTP → 없으면 `RemoteArtworkStore`가 파일 안 커버 범위 읽기 | 폴더 커버는 동작. 파일 내장 커버는 SMB/HTTP 범위 읽기로 동작. |
| 로컬 가사 | `PlaybackService` (`:10218`) → `EmbeddedLyrics.withSource` → 없으면 `LyricsRepository` | 파일 내장/사이드카 우선, 온라인 폴백. |
| SMB/WebDAV 가사 | `localUri`가 원격 URL → `EmbeddedLyrics`는 로컬만 열므로 실패 → 제목/아티스트로 온라인 조회 | 원격 파일 내장 가사는 안 읽음(아래 미수정). |
| 정렬 | `LocalGroups.sortedForLibrary` (`:185`), 앨범은 `sortedForAlbum` (`:206`) | 제목/추가/수정 정렬, 앨범 트랙번호. 동작. |

---

## 3. 수정 전/후 실패 테스트

새 테스트는 모두 **수정 전 실패**를 확인한 뒤 코드를 고쳤다.

- `LocalMediaFilterTest`: WAV/3GP 필터 켬/끔, 다운로드 폴더 확장자.
- `SmbTest`: WAV/3GP/DSD/DFF 인식, `?`/`#` 경로.
- `WebDavTest`: WAV/3GP/DSD/DFF 인식, 잘못된 XML, DOCTYPE/XXE, 500 응답 전파, 한 폴더 maxFiles.
- `AutoplaySeedTest`: 기기/SMB/WebDAV id는 검색, 다운로드 YouTube id는 그대로.

---

## 4. 사용 성적표(vc85, 362곡)와 이 영역의 연결

| 지표 | 코드상 원인 | 이번 수정 |
|---|---|---|
| 다음 곡 싱크 준비 29% | `SyncSafetyPolicy.nextPrepSteps`가 `nextPrepLyrics` 기본 **false**면 `LYRICS`/`SYNC`를 통째로 뺀다(`SyncSafetyPolicy.kt:35`, `:20`). 이 스위치는 기존 안전 규칙이라 건드리지 않았다(주석이 실제 실패를 설명). 즉 29%는 "다음 곡 준비에서 싱크를 시도한 비율"이 아니라 "스위치가 켜져 있거나 재생 후 경로가 처리한 비율"이다. | 변경 없음(안전 규칙 유지). 문서화만. |
| 무손실 확인 후 실제 무손실 재생 4% | 로컬 파일은 `hqWanted`가 `localUri != null`을 제외(`NextTrackPreparation.kt:410`)해 HQ 검증/교체 대상이 아니다. WAV/DSD는 이번 전까지 Local Music 목록에 아예 없어 "확인된 무손실" 표본에도 못 들어갔다. 다운로드 배지가 ALAC/WAV 등급을 잃는 문제는 `docs/audit/check-hq-chain.md` HIGH 7에 기존 기록. | F1로 WAV/DSD 노출. HQ 검증 자체는 온라인 소스 영역이라 미수정. |
| 자동 재생 보충 성공 24% | F3: 로컬/SMB/WebDAV 곡 id를 YouTube radio seed로 보내 실패. 362곡 로컬 보관함을 재생하면 매 곡에서 재현. | **수정(F3)**. 곡명/아티스트로 YouTube id를 먼저 찾는다. |
| 사용자 조작 없는 백그라운드 시작 11회 | `onPlaybackResumption`은 현재 큐 → 다운로드 → Local Music 순인데, 권한 거절/선택 폴더 불일치/빈 필터 결과면 조용히 다음 후보로 넘어간다(`PlaybackService.kt:11508-11545`). WAV/3GP 누락도 후보를 줄였다. | F1/F8로 후보와 로그 개선. 시작 자체는 미디어 버튼/세션 이벤트 기반이라 타이머 없음. |
| 다운로드·고음질 보관함 실패 140회 | 이 영역에서 직접 집계하지 않는다. 다만 다운로드 폴더의 수동 DSD/MP4 누락(F7)과, 파일이 사라진 레코드를 `getDownloadedSongs`가 prune하는 경로가 "보관함에 안 보임"으로 실패처럼 보일 수 있다. HQ 교체 실패 원인은 `docs/audit/check-hq-chain.md` 참고. | F7만 수정. |

---

## 5. 고치지 못한 HIGH/MEDIUM

| 심각도 | 파일:줄 | 문제 | 이유 / 필요한 작업 |
|---|---|---|---|
| MEDIUM | `app/src/main/java/com/music/bitchord/ui/components/RemoteArtwork.kt:38-55` | 로컬 기기 파일에 MediaStore 앨범아트가 없고 파일 안에만 커버가 있으면 행/앨범 카드가 빈 표지. SMB/WebDAV만 `RemoteArtworkStore`를 탄다. | Compose 훅이라 JVM 단위 테스트가 없다. `SongOrigins.isLocalFile`이면 `DownloadedArtworkResolver`(이미 파일 내장 커버 추출 지원)로 보내면 된다. |
| MEDIUM | `app/src/main/java/com/music/bitchord/data/webdav/WebDavClient.kt:74-104`, `app/src/main/java/com/music/bitchord/data/smb/SmbClient.kt:47-85` | 한 하위 폴더의 403/타임아웃이 전체 목록을 실패시킨다. 큰 서버에서 권한 없는 폴더 하나가 보관함 전체를 막는다. | 디렉터리별 실패를 로그와 함께 건너뛰는 정책 결정이 필요(조용한 스킵 금지). 현재는 오류를 숨기지 않는 쪽을 택했다. |
| MEDIUM | `app/src/main/java/com/music/bitchord/data/webdav/WebDavClient.kt:84-103`, `SmbClient.kt:47-85` | 이미지 목록에는 `maxFiles` 상한이 없다. 앨범아트가 많은 공유에서 오디오 상한과 무관하게 이미지가 계속 쌓인다. | 이미지 상한/디렉터리별 상한 정책 필요. |
| MEDIUM | `app/src/main/java/com/music/bitchord/data/LocalMediaRepository.kt:328,370` | 선택 폴더를 MediaStore `DATA` 경로 문자열로만 비교한다. Android 14 공급자/가상 경로에서 DATA가 null이면 선택 폴더의 모든 곡이 조용히 빠진다. | `RELATIVE_PATH`/`VOLUME_NAME` 기반 비교가 필요. 테스트에는 실제 MediaStore가 필요해 이번 범위에서 제외. |
| MEDIUM | `app/src/main/java/com/music/bitchord/data/LocalMediaRepository.kt:292-340` | 필터/폴더/확장자로 빠지는 곡 수를 로그로 남기지 않는다(개별 `continue`). | 스캔 1회당 요약 카운터를 남기면 된다. 성능/배터리 영향 없는 로그. |
| MEDIUM | `app/src/main/java/com/music/bitchord/data/smb/SmbConfig.kt:36-50` | 비정상/대문자/언브래킷 IPv6 호스트 파싱 경계가 문서화돼 있지 않다. | 별도 호스트 파서 테스트 보강 필요. |
| MEDIUM | `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt:7952-8005` | WebDAV 재생의 소스 표시가 `Unknown`으로 떨어질 수 있다(SMB는 DataSource가 `NerdStats`에 기록). | `Song.playbackSource`를 extra에서 읽어 표시. UI 문구/소스 표기 정책 확인 필요. |
| MEDIUM | `app/src/main/java/com/music/bitchord/data/lyrics/EmbeddedLyrics.kt:100-120` | SMB/WebDAV 파일 내장 가사를 읽지 않는다(로컬 file/content만). | 범위 읽기로 MP4/FLAC 태그를 읽는 경로가 필요. 현재는 온라인 조회로 폴백해 무음은 아니다. |
| MEDIUM | `app/src/main/java/com/music/bitchord/data/webdav/WebDavClient.kt:35` | WebDAV는 프록시 설정을 타지 않는다(`Http.client` 직접). "모두 프록시" 사용자는 원격 보관함이 프록시를 우회한다. | 프록시 정책과 원격 파일 라이브러리의 관계를 먼저 정해야 한다(사설망 WebDAV는 직접이 맞을 수 있음). |

---

## 6. 전체 테스트

- 대상 테스트 성공: `LocalMediaFilterTest`, `SmbTest`, `WebDavTest`, `AutoplaySeedTest`,
  `AutoplayFallbackTest`, `LocalGroupsTest`, `LocalAlbumTrackNumberTest`.
- `:app:compileProdReleaseKotlin` 성공.
- 전체 `:app:testProdDebugUnitTest`: 1691 tests, 22 failed, 1 skipped. 실패는 아래 5개 클래스에
  한정되며 이번 diff와 무관한 기존 로케일/문자열/환경 문제다:
  - `LocaleStringsTest` — 번역 파일에 기존 241개 키 누락.
  - `BluetoothAudioTrackerTest` — 기대 문자열이 영문인데 로케일이 한국어.
  - `ProviderLyricsTest` — 제공자 수 21 vs 20.
  - `ArtistNameMatchTest` — 키릴 문자 폴딩 기대 불일치.
  - `SourceFormatsTest` — 한국어 오류 메시지 vs 영문 기대.
