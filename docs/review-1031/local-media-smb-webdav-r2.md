# 로컬 음악 · SMB · WebDAV 재검토 (2026-10-31, 2차)

기준 브랜치: `dsq/deep-local-media-1031-r2` (1차 `local-media-smb-webdav.md` 위)
범위: 기기 스캔(MediaStore/SAF), SMB, WebDAV의 스캔·필터·재생·표지·가사·정렬·권한 거절·큰 보관함.
UI 문구, 업스트림 v1.8.1 코드 삭제, `.github/`, keys, 서명은 건드리지 않았다.

검증 명령:
- `./gradlew :app:compileProdReleaseKotlin -Pbitchord.arm64Only=true --console=plain -q` → 성공
- `./gradlew :app:testProdDebugUnitTest --tests 'com.music.bitchord.LocalMediaFilterTest' --tests 'com.music.bitchord.SmbTest' --tests 'com.music.bitchord.WebDavTest' --console=plain -q` → 성공

---

## 1. 1차 지적 3건 재검증 결과

| 1차 지적 | 재검증 | 이번 처리 |
|---|---|---|
| MEDIUM `LocalMediaRepository.kt`의 `wma`/`aif`/`aiff`는 Media3 1.11에 추출기가 없는데 로컬 음악으로 노출됨 | **타당.** Media3 1.11.0 `media3-extractor`의 추출기 목록을 jar에서 직접 확인했다: `amr, avi, avif, bmp, flac, flv, heif, jpeg, mkv(Matroska), mp3, mp4, ogg, png, ts(AC-3/AC-4/ADTS/PS/TS), wav, webp`. WMA/ASF와 AIFF 추출기는 없다. | **수정(H1)**. 로컬·SMB·WebDAV의 목록에서 제외. |
| MEDIUM `SmbConfig`/`WebDavConfig`의 `mp4`/`3gp`/`3gpp`가 원격 비디오를 곡으로 가져와 `maxFiles`를 소모함 | **타당.** 원격 목록에는 MediaStore의 Audio/Video 분리가 없고, `WebDavClient.Entry.contentType`은 받아만 두고 판정에 쓰지 않았다. | **수정(H2)**. SMB는 비디오 컨테이너를 오디오에서 제외하고, WebDAV는 `getcontenttype`이 `audio/…`일 때만 모호한 컨테이너를 곡으로 인정한다. |
| LOW `SmbClient.kt:60`, `WebDavClient.kt:93`에서 상한 도달 시 `break`가 같은 폴더의 이미지까지 건너뛰어 표지를 잃음 | **타당.** `break`가 엔트리 루프를 끝내므로 상한 뒤의 `cover.jpg`가 `images`에 들어가지 않았다. | **수정(H3)**. 오디오 추가만 상한으로 막고, 같은 디렉터리의 이미지는 끝까지 수집한다. |

---

## 2. 이번에 고친 결함

| # | 심각도 | 파일:줄 | 결함 | 사용자 영향 | 흔한가 | 테스트 |
|---|---|---|---|---|---|---|
| H1 | MEDIUM | `app/src/main/java/com/music/bitchord/data/LocalMediaRepository.kt:30-40`; `data/smb/SmbConfig.kt:21-36`; `data/webdav/WebDavConfig.kt:17-23` | 재생 불가능한 WMA/ASF, AIFF를 음악 목록에 넣었다. 목록에는 보이지만 첫 재생에서 `ParserException`/무음이 된다. | NAS의 예전 WMA 앨범, AIFF 마스터가 곡처럼 보이고 눌러도 재생되지 않는다. | 중간. WMA는 오래된 NAS/Windows 보관함에 흔하고, AIFF는 매킨토시 작업 폴더에 흔하다. | `LocalMediaFilterTest.containers media3 has no extractor for stay out`, `SmbTest.detectsMediaFiles`, `WebDavTest.detectsAudioFiles` |
| H2 | MEDIUM | `data/smb/SmbConfig.kt:21-36,71-74`; `data/webdav/WebDavConfig.kt:17-31,69-75`; `data/webdav/WebDavClient.kt:96-101` | 원격 공유의 `.mp4`/`.webm`/`.3gp`/`.3gpp` 영화·뮤직비디오를 전부 곡으로 가져왔다. 열거 상한(`maxFiles = 10_000`)을 영상이 먼저 채우면 실제 음악이 잘린다. WebDAV가 이미 받은 `getcontenttype`도 쓰지 않았다. | 음악 폴더 옆 영상 폴더를 가리킨 사용자는 보관함이 영상으로 가득 차고, 정작 곡이 상한 밖으로 밀린다. | 중간. NAS 공유에 영상과 음악을 함께 두는 구성이 흔하다. | `SmbTest.detectsMediaFiles`, `WebDavTest.content type decides ambiguous containers`, `WebDavTest.listLibrary leaves video files out of the audio rows` |
| H3 | LOW | `data/smb/SmbClient.kt:56-72`; `data/webdav/WebDavClient.kt:88-103` | 상한에 닿는 순간 엔트리 루프를 `break`해, 같은 폴더에서 아직 읽지 않은 `cover.jpg`/`folder.jpg`를 버렸다. | 한 폴더에 곡이 상한보다 많으면 이미 수집한 곡들의 폴더 표지가 사라진다. | 낮음~중간. 한 폴더에 수천 곡을 넣는 사용자. | `WebDavTest.listLibrary enforces maxFiles inside one large directory` (상한 뒤 `cover.jpg` 검증) |
| H4 | MEDIUM | `data/smb/SmbConfig.kt:46-64` | 브래킷 없는 IPv6 호스트(`fe80::1`)를 `host="fe80"`, `port=1`로 잘라냈다. 호스트의 마지막 콜론 뒤를 포트로 오인했다. | IPv6 NAS를 주소로 직접 넣은 사용자는 연결 자체가 되지 않는다. 브래킷 표기만 동작했다. | 낮음~중간. IPv6 전용/링크로컬 NAS. | `SmbTest.splitsHostPort` (`fe80::1`, `smb://FE80::1/Share`) |

### 2.1 조건 변경 전후 (기존 항은 유지, 항만 추가/제외)

`SmbConfig.isAudioFile(name)`:

| 입력 | 이전 | 이후 |
|---|---|---|
| `song.flac`, `take.WAV`, `master.dsf/dff` | true | true |
| `old.wma`, `mix.aif`, `master.aiff` | true | **false(신규 제외)** |
| `movie.mp4`, `clip.webm`, `clip.3gp/3gpp` | true | **false(신규 제외)** |
| `cover.jpg`, `notes.txt` | false | false |

`WebDavConfig.isAudioFile(name, contentType = null)`:

| 입력 | 이전 | 이후 |
|---|---|---|
| 오디오 전용 확장자 + contentType 없음/audio | true | true |
| `movie.mp4` + `video/mp4` | true | **false(신규 제외)** |
| `album.mp4` + `audio/mp4` | true | **true(신규 명시)** |
| `song.mp3` + `video/mp4` | true | **false(신규 제외)** |
| `old.wma`, `mix.aif`, `master.aiff` | true | **false(신규 제외)** |

`SmbConfig.splitHostPort(raw)`:

| 입력 | 이전 | 이후 |
|---|---|---|
| `nas.local:4445` | `nas.local to 4445` | 같음 |
| `[::1]:1445` | `::1 to 1445` | 같음 |
| `fe80::1` | **`fe80 to 1`** | **`fe80::1 to 445`** |
| `smb://FE80::1/Share` | **`fe80 to 1`** | **`fe80::1 to 445`** |

`listLibrary` 상한 도달 시:

| 상황 | 이전 | 이후 |
|---|---|---|
| 오디오가 상한에 도달 | 엔트리 루프 즉시 종료 | 오디오 추가만 중단 |
| 같은 폴더의 뒤쪽 이미지 | **수집 안 됨** | **수집됨** |
| 이후 폴더 순회 | 중단 | 중단(변경 없음) |

---

## 3. 흔한 상황별 코드 추적표 (변경 반영)

| 상황 | 진입점 → 처리 → 결과 (코드) | 실제 결과 |
|---|---|---|
| 기기 Local Music, 권한 허용 | `MainViewModel.loadLibrarySongs`/`localPageState("local:all")` → `LocalMediaRepository.getLocalMusic` (`:206`) → MediaStore 쿼리 → `isEligibleLocalMusic`/`hasLocalMusicExtension` → `Song` | 동작. WMA/AIFF는 이제 목록에 없다. |
| 권한 거절 (Android 13+ `READ_MEDIA_AUDIO`) | `hasStoragePermission` (`:54`) false → `getLocalMusic` 로그 후 빈 목록 (`:207-210`) | `storage_required_read` 표시. 백그라운드 복원은 빈 후보 + 로그. |
| 필터 켬 + WAV/3GP | SQL `IS_MUSIC`/길이 → `isEligibleLocalMusic` (`:393-403`) → `nonMusicExtensions` 거절 | 숨김(설정 문구와 일치). |
| 필터 끔 + WAV/3GP | SQL 선택 없음 → `hasLocalMusicExtension` (`:413`) 통과 → 목록 | 표시. |
| 다운로드 곡 재생 | `Downloads.getDownloadedSongs` → `Song.toMediaItem` → `localUri` → `AudioCache.playbackFactory` 로컬 우회 → ExoPlayer | 동작. |
| 다운로드 폴더 수동 복사 | `LocalMediaRepository.getDownloadedSongs` → MediaStore `RELATIVE_PATH LIKE %BitChord%` → `isAudioFileName` (`:434`) | WAV/3GP/MP4/DSD 표시. WMA/AIFF는 재생 불가라 제외. |
| 비디오 노래/커버 영상 다운로드 | 다운로드 레코드 `isVideo`/`soundSourceVideoId` → 로컬 파일 재생, 가사는 원곡 기준 조회 | 재생 동작. 로컬 `.mp4`는 MediaStore Audio 행일 때만 목록에 들어온다. |
| SMB 영상 파일 | `SmbClient.listLibrary` → `SmbConfig.isAudioFile` (`SmbConfig.kt:71`) | **목록에서 제외(H2)**. 오디오 전용 `.m4a`/`.flac`/`.opus`는 유지. |
| WebDAV 영상 파일 | `WebDavClient.listLibrary` → `WebDavConfig.isAudioFile(name, contentType)` (`WebDavConfig.kt:69`) | `video/*`는 제외, `audio/mp4`는 유지(H2). |
| 셔플/다음 곡 | `LocalFolder` → `play(songs, index)` → `QueueShuffle` → `toMediaItem` | 동작. 다음 곡 준비는 `NextTrackPreparation.schedule`. |
| 앱 재시작 후 큐 복원 | `PlaybackService` `restoreLastQueue`/`onPlaybackResumption` → 현재 큐 → `Downloads.getDownloadedSongs` → `getLocalMusic` | 큐가 남아 있으면 그대로. 없으면 다운로드 → 로컬 → 홈 순. |
| 화면 꺼짐/백그라운드 | 같은 MediaSession/ExoPlayer. 로컬 `WAKE_MODE_LOCAL`. SMB는 `SmbDataSource`가 open마다 핸들 재사용/재연결 | 로컬 정상. SMB는 30초 read timeout 후 IOException 1회 재연결. |
| 프록시 켬 | `ProxyAwareDataSourceFactory`는 프록시로 기록된 URL만 프록시. 로컬/SMB는 직접, WebDAV는 `Http.client` 직접 | 로컬/SMB 영향 없음. WebDAV 프록시 미적용은 아래 미수정. |
| 느린/끊긴 망 + SMB | `SmbClient.listLibrary` `withShare` → 목록 실패 시 `getOrThrow` → `RemoteListing.state` 오류 문구 | 오류 노출. 하위 폴더 하나의 403은 전체 실패(아래 미수정). |
| 느린/끊긴 망 + WebDAV | `WebDavClient.listLibrary` → PROPFIND 207 파싱 → 실패는 예외 | 오류 노출. |
| Android 14+ 스코프드 스토리지 | `getLocalMusic`은 MediaStore만 읽고 선택 폴더는 `DATA` 경로 문자열 비교 (`:357-362`) | DATA가 null인 공급자면 선택 폴더가 빈 목록(아래 미수정). |
| 큰 보관함(한 폴더 1만+) | `maxFiles` 상한이 디렉터리 안에서 지켜짐. 이미지도 상한 뒤 같은 폴더에서 계속 수집(H3) | 오디오 상한에서 멈춤, 표지는 보존. |
| 로컬 표지 | MediaStore `albumart`만 사용 (`:324`) | MediaStore 아트가 없고 파일 내장만 있으면 빈 표지(아래 미수정). |
| SMB/WebDAV 표지 | 목록의 같은 폴더 이미지 → `RemoteArtwork.pick` → Coil/HTTP → 없으면 `RemoteArtworkStore` 범위 읽기 | 폴더 커버 동작. H3로 상한 이후에도 커버 유지. |
| 로컬 가사 | `PlaybackService` → `EmbeddedLyrics.withSource` → 없으면 `LyricsRepository` | 파일 내장/사이드카 우선, 온라인 폴백. |
| SMB/WebDAV 가사 | `localUri`가 원격 → `EmbeddedLyrics` 로컬만 열어 실패 → 제목/아티스트 온라인 조회 | 원격 파일 내장 가사는 안 읽음(아래 미수정). |
| 정렬 | `LocalGroups.sortedForLibrary` (`:185`), 앨범 `sortedForAlbum` (`:206`) | 제목/추가/수정 정렬, 앨범 트랙번호. 멀티디스크는 디스크 번호를 버리고 트랙 번호만 쓴다(아래 미수정). |

---

## 4. 사용 성적표(vc85, 362곡)와 이 영역의 연결

| 지표 | 코드상 원인 | 이번 수정 |
|---|---|---|
| 다음 곡 싱크 준비 29% | `NextTrackPreparation.run`은 `switches.nextPrepLyrics`가 기본 false면 `LYRICS`/`SYNC`를 계획에서 뺀다(`SyncSafetyPolicy.nextPrepSteps`). 이 스위치는 실제 실패를 설명하는 기존 안전 규칙이라 유지했다. 즉 29%는 로컬/SMB/WebDAV 문제가 아니라 "다음 곡 준비에서 싱크 스위치가 켜진 비율"이다. | 변경 없음(안전 규칙 유지). |
| 무손실 확인 후 실제 무손실 재생 4% | `NextTrackPreparation.hqWanted`가 `song.localUri != null`을 제외(`:410-412`)하므로 로컬/다운로드 파일은 HQ 검증·교체 대상이 아니다. WMA/AIFF가 목록에 섞여 "확인된 무손실" 표본을 오염시키던 문제는 H1로 제거. HQ 검증 자체는 온라인 소스 영역이라 미수정. | H1로 로컬 목록 정합성 개선. |
| 자동 재생 보충 성공 24% | 1차에서 `Autoplay.youtubeSeedFor`가 기기 `content://`/`smb:`/`webdav:` id를 YouTube radio seed로 보내던 경로를 검색 경로로 고쳤다. 이번 코드에도 그 수정이 유지되어 있다. | 변경 없음(1차 수정 유지 확인). |
| 사용자 조작 없는 백그라운드 시작 11회 | `onPlaybackResumption`은 현재 큐 → 다운로드 → Local Music 순으로 후보를 찾고, 후보가 없으면 다음으로 넘어간다. 타이머/폴링 없이 미디어 버튼·세션 이벤트로만 시작한다. | 변경 없음. 배터리 규칙 위반 없음. |
| 다운로드·고음질 보관함 실패 140회 | 이 영역에서 직접 집계하지 않는다. 원격 영상이 상한을 채워 실제 곡이 잘리던 H2와, 재생 불가 WMA/AIFF가 목록에 남던 H1이 "보관함에 안 보임/재생 실패"로 보이는 부분을 줄였다. HQ 교체 실패 원인은 `docs/audit/check-hq-chain.md` 참고. | H1/H2. |

---

## 5. 수정 전 실패 테스트

새 테스트는 수정 전 코드에서 실패하는 것을 확인한 뒤 위 수정을 적용했다.

- `LocalMediaFilterTest.containers media3 has no extractor for stay out`
- `SmbTest.detectsMediaFiles` (확장: 3gp/mp4/webm/wma/aif/aiff 거절)
- `SmbTest.splitsHostPort` (확장: 브래킷 없는 IPv6)
- `WebDavTest.detectsAudioFiles` (확장: 모호한 컨테이너 거절)
- `WebDavTest.content type decides ambiguous containers`
- `WebDavTest.listLibrary leaves video files out of the audio rows`
- `WebDavTest.listLibrary enforces maxFiles inside one large directory` (확장: 상한 뒤 커버 수집)

---

## 6. 고치지 못한 HIGH/MEDIUM (재검증 필요)

| 심각도 | 파일:줄 | 문제 | 이유 / 필요한 작업 |
|---|---|---|---|
| MEDIUM | `data/LocalMediaRepository.kt:357-362,293` | 선택 폴더를 MediaStore `DATA` 문자열로만 비교한다. Android 14 공급자/가상 경로에서 DATA가 null이면 선택 폴더의 곡이 전부 조용히 빠진다. | `RELATIVE_PATH`+`VOLUME_NAME` 폴백이 필요하다. `DocumentsContract`가 JVM 단위 테스트에서 동작하지 않아 이번에는 실기기 검증이 필요하다. **재검증 필요** |
| MEDIUM | `data/webdav/WebDavClient.kt:91`, `data/smb/SmbClient.kt:59` | 하위 폴더 하나의 403/타임아웃이 전체 목록을 실패시킨다. | 디렉터리별 실패를 **로그와 함께** 건너뛰는 정책 결정이 필요하다(조용한 스킵 금지). **재검증 필요** |
| MEDIUM | `data/webdav/WebDavClient.kt:88-105`, `data/smb/SmbClient.kt:56-74` | 이미지 목록에는 `maxFiles` 상한이 없다. 앨범아트가 많은 공유에서 이미지가 계속 쌓인다. | 이미지 상한/디렉터리별 상한 정책 필요. **재검증 필요** |
| MEDIUM | `data/LocalMediaRepository.kt:300-349` | 필터/폴더/확장자로 빠지는 곡 수를 로그로 남기지 않는다(개별 `continue`). | 스캔 1회당 요약 카운터를 남기면 된다. 배터리 영향 없는 로그. **재검증 필요** |
| MEDIUM | `playback/PlaybackService.kt` WebDAV 소스 표시 | WebDAV 재생의 소스 표시가 `Unknown`으로 떨어질 수 있다(SMB는 DataSource가 `NerdStats`에 기록). | `Song.playbackSource`를 extra에서 읽어 표시. **재검증 필요** |
| MEDIUM | `data/lyrics/EmbeddedLyrics.kt:100-120` | SMB/WebDAV 파일 내장 가사를 읽지 않는다(로컬 file/content만). 온라인 폴백으로 무음은 아니다. | 범위 읽기로 MP4/FLAC 태그를 읽는 경로가 필요하다. **재검증 필요** |
| MEDIUM | `data/webdav/WebDavClient.kt:35` | WebDAV는 프록시 설정을 타지 않는다(`Http.client` 직접). | 사설망 WebDAV는 직접이 맞을 수 있어 정책 결정이 먼저다. **재검증 필요** |
| MEDIUM | `data/LocalGroups.kt:206-211`, `LocalMediaRepository.kt:491-495` | 멀티디스크 앨범에서 MediaStore의 `disc*1000+track`을 트랙 번호만 남겨 디스크 순서가 사라진다. | `Song`에 디스크 번호를 추가하고 정렬 키를 (disc, track)으로 바꿔야 한다. 모델 변경 범위가 커서 이번에는 남긴다. **재검증 필요** |

---

## 7. 전체 테스트

- 대상 테스트 성공: `LocalMediaFilterTest`, `SmbTest`, `WebDavTest`.
- `:app:compileProdReleaseKotlin` 성공.
- 이번 diff는 app 모듈에만 있고 shared 코드는 바꾸지 않았다.
