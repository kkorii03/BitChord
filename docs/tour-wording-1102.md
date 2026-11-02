# Tour 화면 문구 감사 (Apple Music Korea 기준)

범위: `.tmp_verify/tour/screen-texts.md`의 65개 화면에 실제로 보이는 텍스트. 선행 감사(`ko-wording-audit.md`)와 겹치는 항목은 다시 적지 않았다. 아래는 그 감사에 없던 화면 노출 문구, 또는 화면에서만 드러나는 문제다.
기준: 라벨·버튼·탭·섹션 제목은 짧은 명사구, 문장은 `합니다`체, 개념당 한 용어, 영어·개발자 용어·이모지 없음, 라벨에 마침표 없음, 접근성 라벨도 한국어.
소스는 `app/src/main/res/values-ko/strings.xml`, `sharedUi/src/commonMain/composeResources/values-ko/strings.xml`, 또는 하드코딩된 Kotlin 파일:줄이다.

## 1. 문장·어조

| 화면 | 현재 텍스트 | 제안 (Apple 스타일) | 이유 | 수정할 소스 |
|---|---|---|---|---|
| 62-lyrics-offset | `원곡을 찾지 못함 (검색 2.6초)` | `원곡을 찾지 못했습니다` | 사용자에게 보이는 문장이 명사형(`못함`)으로 끝난다. 검색 소요 시간은 진단 정보라 화면 문장에 둘 필요가 없다. | `app/src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt:1229` (`note = "원곡을 찾지 못함 ($times)"`) |
| 35-settings-account | `이 지역에서는 YouTube Music 서비스가 제한되어 YouTube 기반으로 동작합니다` | `이 지역에서는 YouTube Music을 사용할 수 없어 YouTube를 기반으로 재생합니다.` | 서비스가 '제한되어 동작한다'는 번역투다. 완결 문장인데 마침표도 없다. | `app/src/main/res/values-ko/strings.xml:929` (`music_gate_fallback`) |
| 41-settings-sources | `이 소스를 사용하려면 텔레그램에 로그인합니다.` | `이 소스를 사용하려면 텔레그램에 로그인하십시오.` | 안내 문장이 평서문이라 '로그인하라'는 지시가 되지 않는다. 다른 안내는 모두 `-십시오`체다. | `app/src/main/res/values-ko/strings.xml:1486` (`telegram_source_login_required`) |
| 35-settings-account | `현재 쓰는 서버` | `현재 서버` | `쓰는`은 구어체다. Apple 설정 행은 `현재 서버`처럼 명사구로 끝낸다. | `app/src/main/res/values-ko/strings.xml:1248` (`proxy_active_server`) |
| 37-settings-account-scrolled-2 | `켜려면 토큰을 입력하십시오.` | `ListenBrainz를 켜려면 토큰을 입력하십시오.` | 목적어가 없어 무엇을 켜는지 알 수 없다. 바로 위 항목이 `ListenBrainz`다. | `app/src/main/res/values-ko/strings.xml:465` (`enter_token_to_enable`) |

## 2. 용어 불일치 (한 개념 = 한 단어)

| 화면 | 현재 텍스트 | 제안 (Apple 스타일) | 이유 | 수정할 소스 |
|---|---|---|---|---|
| 01/02/03-home, 58-now-playing-mini | `회원님을 위한 추천`, `회원님을 위한 분위기`, `회원님을 위한 맞춤` + 같은 화면의 `내 스테이션`, `내 분위기 찾기`, `나를 위한 추천` | `나를 위한 추천`, `나를 위한 분위기`, `나를 위한 맞춤` | 같은 화면에서 `회원님`과 `나/내`가 섞인다. 앱의 기존 `shelf_for_you`도 `나를 위한 추천`이다. | `sharedUi/src/commonMain/composeResources/values-ko/strings.xml:271-273` (`top_picks_for_you`, `top_pick_made_for_you`, `top_pick_mood_for_you`) |
| 01/58 vs 05/06/07 | `에너지` vs `에너지 충전` | `에너지` | 같은 무드가 홈에서는 `에너지`, 둘러보기에서는 `에너지 충전`이다. | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/ExploreCategories.kt:52`; `app/.../strings.xml:1078` (`shelf_energy_boosters`); `sharedUi/.../strings.xml:280` (`mood_energy`) |
| 05/06 vs 설정 | `잠잘 때` vs `수면` | `수면` | 같은 무드/설정 개념이 두 단어로 갈린다. | `ExploreCategories.kt:53`; `app/.../strings.xml:1084` (`shelf_sleep`); `sharedUi/.../strings.xml:321` (`mood_sleep`) |
| 06/07 vs 58 | `집중할 때` vs `집중` | `집중` | 같은 무드가 `-할 때`형과 명사형으로 갈린다. | `ExploreCategories.kt:54`; `app/.../strings.xml:1082` (`shelf_focus`); `sharedUi/.../strings.xml:288` (`mood_focus`) |
| 06-explore-scrolled-1 | `출퇴근 & 등하교` (다른 곳 `출퇴근길`, `출퇴근`) | `출퇴근` | 한 개념에 세 표기. `&`는 Apple이 쓰지 않는다. | `ExploreCategories.kt:55`; `app/.../strings.xml:1080` (`shelf_commute`); `sharedUi/.../strings.xml:301` (`mood_commuting`) |
| 05/06 | `편안한 분위기` (다른 곳 `휴식`) | `휴식` | 같은 무드가 형용사구와 명사로 갈린다. | `ExploreCategories.kt:56`; `app/.../strings.xml:1083` (`shelf_relax`); `sharedUi/.../strings.xml:282` (`mood_relax`) |
| 05/06 | `행복한 기분` (다른 곳 `기분 좋은`) | `기분 좋음` | 같은 무드의 두 표기. Apple 무드 타일은 명사구다. | `ExploreCategories.kt:57`; `app/.../strings.xml:1079` (`shelf_feel_good`); `sharedUi/.../strings.xml:286` (`mood_feel_good`) |
| 06/07 vs 58 | `슬플 때` vs `우울할 때` (다른 곳 `슬픔`) | `슬픔` | 같은 무드에 세 표기. | `ExploreCategories.kt:60`; `sharedUi/.../strings.xml:284` (`mood_feeling_blue`); `app/.../strings.xml:1087` (`shelf_sad`) |
| 35-settings-account vs 38-settings-discord | `로그인 안 됨` vs `연결 안 됨` | 계정 연동은 `로그인 안 됨`으로 통일 | Discord 화면의 버튼은 `Discord로 로그인`인데 상태만 `연결 안 됨`이라 같은 동작을 두 단어로 부른다. | `app/.../strings.xml:38` (`not_signed_in`) vs `:637` (`not_connected`) |
| 35-settings-account, 32-listen-together | `예비 서버` vs `대체 서버` | `대체 서버` | 같은 fallback 서버를 프록시는 `예비`, 함께 듣기는 `대체`라고 부른다. | `app/.../strings.xml:1244` (`proxy_backup_server`) vs `:1032` (`listen_together_top_bar_fallback`) |
| 15~31-settings | `이 기기는 Atmos를 재생할 수 없어…` (같은 화면 행 제목은 `Dolby Atmos`) | `이 기기에서는 Dolby Atmos를 재생할 수 없어 일반 음원으로 재생됩니다.` | 행 제목은 `Dolby Atmos`, 설명은 `Atmos`로 줄여 쓴다. | `app/.../strings.xml:216` (`dolby_atmos_unavailable`) |
| 41/43-settings-sources | `소스 매칭`, `잘못 매칭될 수 있습니다` | `소스 일치`, `잘못 일치될 수 있습니다` | `매칭`은 영어 차용 개발 용어다. Apple은 `일치`를 쓴다. | `app/.../strings.xml:699` (`jiosaavn_mismatch_warning`), `:703` (`source_matching`), `:1126` (`spotify_import_description`), `:1133` (`spotify_import_matching`) |
| 61-song-actions | `캐시에서 삭제` (앱의 같은 개념은 `오프라인 임시 저장`) | `오프라인 임시 저장에서 삭제` | 캐시는 개발 용어이고, 앱은 이미 사용자에게 `오프라인 임시 저장`이라고 보여 준다. | `app/.../strings.xml:1316` (`remove_from_cache`), 비교 `:1114` (`cached_songs`) |
| 17~31-settings | `로컬 음악` | `이 기기의 음악` | `로컬`은 개발 용어다. 같은 파일에서 `이 기기`, `기기의 오디오 파일`을 이미 쓴다. | `app/.../strings.xml:29` (`local_music`) |

## 3. 영어·개발자 용어

| 화면 | 현재 텍스트 | 제안 (Apple 스타일) | 이유 | 수정할 소스 |
|---|---|---|---|---|
| 18/19/29/30/31/45/46-settings 하단 | `bitchord 1.9  GitHub  개발자  Discord  웹사이트` / `~YouTube Music & Listen Together Backend` | `BitChord 1.9 · GitHub · 개발자 · Discord · 웹사이트` / `YouTube Music 및 함께 듣기 서비스` | 브랜드가 소문자이고, 항목을 공백 두 칸과 `~`로 구분하며, `Backend`가 사용자에게 그대로 노출된다. | `app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt:1582-1604` |
| 59-now-playing | `Opus · 48.0 kHz · 135 kbps · 스테레오` | `48 kHz · 스테레오` (코덱·비트레이트는 '고급 정보' 안에서만) | 코덱명과 비트레이트는 일반 사용자에게 의미 없는 개발 정보이고, `48.0` 표기도 다른 화면과 다르다. | `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerControls.kt:2079-2103` |
| 59-now-playing | `FullHD` | `1080p` | 붙여 쓴 영어 화질명. Apple은 해상도(`1080p`)로 표시한다. | `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerHost.kt:198` |
| 39/40-settings-discord | `Listen on YouTube Music` / `Visit BitChord` | `YouTube Music에서 듣기` / `BitChord 방문` | 미리보기 카드의 버튼이 영어 문장 그대로다. | `app/src/main/java/com/music/bitchord/data/discord/DiscordRPC.kt:154-155` (`DEFAULT_BUTTON_1`, `DEFAULT_BUTTON_2`) |
| 37/38-settings-account | `스크로블`, `계정 및 스크로블` | `청취 기록 전송`, `계정 및 청취 기록` | `스크로블(scrobble)`은 개발자·서비스 용어다. Apple은 `청취 기록`으로 말한다. | `app/.../strings.xml:120-122` (`account_scrobbling`, `scrobbling`), `:679` (`scrobbling_footer`) |
| 37/38-settings-account | `리치 프레즌스` | `현재 재생 중 표시` | Discord 내부 기능명을 음차했다. 사용자는 '무엇이 표시되는지'가 필요하다. | `app/.../strings.xml:121` (`rich_presence`), `:646` (`presence`), `:669` (`rich_presence_footer`) |
| 35/36-settings-account | `프록시를 통해 music.youtube.com 홈을 확인합니다.` | `프록시로 YouTube Music 홈에 연결되는지 확인합니다.` | 사용자 안내에 도메인을 그대로 노출한다. | `app/.../strings.xml:1256` (`proxy_connection_test_subtitle`) |
| 35-settings-account | `한국 제한 우회 중(대체 경로)` | `대체 경로 사용 중` | `우회`는 정책 회피를 전면에 내세우는 표현이다. Apple은 동작만 설명한다. | `app/.../strings.xml:1259` (`proxy_gate_fallback`) |

## 4. 라벨·지시문

| 화면 | 현재 텍스트 | 제안 (Apple 스타일) | 이유 | 수정할 소스 |
|---|---|---|---|---|
| 35/36/37/38-settings-account | `탭하여 Google로 로그인`, `탭하여 연결`, `탭하여 로그인`, `탭하여 Discord로 로그인` | `Google로 로그인`, `Spotify 연결`, `Last.fm 로그인`, `Discord로 로그인` | 같은 상태 행이 네 가지 지시문으로 갈린다. Apple은 행 자체를 동작 이름으로 두고 '탭하여'를 붙이지 않는다. | `app/.../strings.xml:725-727` (`tap_to_connect`, `tap_to_sign_in`, `tap_to_sign_in_discord`), `:895` (`tap_to_sign_in_google`) |

## 5. 띄어쓰기·문장부호·표기

| 화면 | 현재 텍스트 | 제안 (Apple 스타일) | 이유 | 수정할 소스 |
|---|---|---|---|---|
| 62-lyrics-offset | `+0.0 s` | `+0.0초` | 숫자와 단위 사이를 띄고 라틴 `s`를 쓴다. 같은 화면의 다른 값은 `2.6초`, `3초`다. | `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LyricsOffsetSheet.kt:609-613` |
| 12/13/14/48/49-search | `검색 "쇼"` | `검색 ‘쇼’` | 검색어를 ASCII 따옴표로 감싼다. 최근 검색의 `‘쇼미더머니’ 편집`은 둥근 따옴표라 같은 화면에서 혼용된다. | `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/SearchScreen.kt:832` |
| 36/37-settings-account, 42/43-settings-sources | `SPOTIFY`, `WEBDAV` (바로 아래 행은 `Spotify`, `WebDAV`) | `Spotify`, `WebDAV`, `SMB` | 섹션 헤더를 코드에서 무조건 대문자로 바꿔(`uppercase`), 같은 이름이 두 표기로 겹친다. | `app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt:2317-2320` |
| 32/33-settings-listen-together | `1519ms` | `연결 지연 1.5초` (상세 값은 진단에 유지) | 단위 없는 원시 밀리초 값이 사용자 화면에 노출된다. | `app/.../strings.xml:1019` (`listen_together_ping`), `:939` (`listen_together_server_online`); `MainActivity.kt:4354` |
| 59-now-playing | `자동 믹스 · 비디오 노래: 단순 크로스페이드` | `자동 믹스 · 비디오 노래 크로스페이드` | 콜론으로 끊은 설명이 디버그 메모처럼 읽힌다. Apple은 `·`로 이어 쓴다. | `sharedUi/.../strings.xml:10` (`automix_not_supported_video`) |

## 6. 내용·동작 불일치

| 화면 | 현재 텍스트 | 제안 (Apple 스타일) | 이유 | 수정할 소스 |
|---|---|---|---|---|
| 42/43-settings-sources | 선택값 `2초`, 안내문 `기본값은 3초입니다.` | 화면 값과 안내문을 같은 값으로 (예: 둘 다 `2초`) | 같은 화면에서 현재 값과 기본값이 서로 다르다. 사용자는 어느 쪽이 기본인지 알 수 없다. 실제 기본 상수를 확인해 한쪽을 고쳐야 한다. | `app/.../strings.xml:704` (`upgrade_length_slack`, 값은 코드가 표시), `:706` (`upgrade_length_slack_footer`) |

## 7. 접근성 라벨

| 화면 | 현재 텍스트 | 제안 (Apple 스타일) | 이유 | 수정할 소스 |
|---|---|---|---|---|
| 58/59/60-now-playing | `[desc] 다음` (대기열 머리글은 `다음 항목`) | `[desc] 다음 항목` | 같은 'next' 동작을 버튼은 `다음`, 머리글은 `다음 항목`으로 읽어 준다. TalkBack에서 같은 동작이 다르게 들린다. | `sharedUi/.../strings.xml:138` (`widget_next`) vs `:134` (`up_next`) |

## 8. 화면 텍스트만으로 보이는 UX 문제

- **무드 이름이 화면마다 다르다.** `에너지`/`에너지 충전`, `잠잘 때`/`수면`, `집중할 때`/`집중`, `출퇴근 & 등하교`/`출퇴근길`/`출퇴근`, `편안한 분위기`/`휴식`, `행복한 기분`/`기분 좋은`, `슬플 때`/`우울할 때`/`슬픔`이 홈·둘러보기·플레이어에 섞여 나온다. 무드 하나가 화면에 따라 다른 이름으로 보인다.
- **존칭이 섞인다.** 홈은 `회원님을 위한 …`인데 같은 화면에 `내 스테이션`, `내 분위기 찾기`, `나를 위한 추천`이 있다.
- **같은 상태를 `로그인 안 됨`과 `연결 안 됨`으로 부른다.** 계정 화면과 Discord 화면이 서로 다르다.
- **한 화면에서 같은 동작을 두 이름으로 부른다.** 32-listen-together의 제목 `파티 시작하기`와 버튼 `파티 만들기`가 같은 행동을 가리킨다(선행 감사의 개별 라벨 제안과 별개로, 같은 화면 중복이 문제).
- **섹션 헤더와 행 제목이 중복된다.** `SPOTIFY` 바로 아래 `Spotify`, `WEBDAV` 바로 아래 `WebDAV`가 같은 카드에 두 번 나온다.
- **같은 화면에서 현재 값과 기본값이 모순된다.** 소스 설정은 `2초`를 보여 주면서 안내문은 `기본값은 3초`라고 한다.
- **따옴표 체계가 다르다.** 검색어는 `"쇼"`, 최근 검색 편집은 `‘쇼미더머니’`다.
- **다운로드와 임시 저장의 구분이 흐리다.** 61-song-actions에 `다운로드`와 `캐시에서 삭제`가 나란히 있어, 사용자는 무엇이 지워지는지 알기 어렵다.
- **개발자용 정보가 일반 화면에 있다.** 플레이어에 `Opus · 48.0 kHz · 135 kbps`, `FullHD`, `1519ms`, `원곡을 찾지 못함 (검색 2.6초)`가 그대로 보인다.
- **11-library와 47-search-empty의 텍스트가 완전히 같다.** 보관함 화면에 보관함 항목(`플레이리스트`, `노래`, `앨범`, `다운로드`) 없이 `YouTube`/`애드온`/`둘러보기`와 무드 타일만 보인다. 실제 화면인지 캡처 중첩인지 확인이 필요하다.

## 9. 요약

- 문장·어조: 5
- 용어 불일치: 14
- 영어·개발자 용어: 8
- 라벨·지시문: 1
- 띄어쓰기·문장부호·표기: 5
- 내용·동작 불일치: 1
- 접근성 라벨: 1
- 표에 든 문제: 35건 (UX 관찰 10건 별도)

가장 눈에 띄는 10가지:

1. 설정 하단 `bitchord 1.9  GitHub  개발자  Discord  웹사이트` / `~YouTube Music & Listen Together Backend`
2. 모든 설정 화면의 `이 기기는 Atmos를 재생할 수 없어…` (행 제목은 `Dolby Atmos`)
3. 홈·둘러보기·플레이어에서 갈리는 무드 이름 (`에너지`/`에너지 충전`, `잠잘 때`/`수면`, `슬플 때`/`우울할 때`)
4. `회원님을 위한 추천`과 같은 화면의 `내 스테이션`/`나를 위한 추천`
5. `스크로블`, `리치 프레즌스` 섹션 이름
6. 플레이어의 `FullHD`, `Opus · 48.0 kHz · 135 kbps`
7. 소스 설정의 `2초` vs `기본값은 3초입니다.`
8. `SPOTIFY`/`WEBDAV` 대문자 헤더와 바로 아래 `Spotify`/`WebDAV`
9. Discord 미리보기 버튼 `Listen on YouTube Music` / `Visit BitChord`
10. 가사 싱크 화면의 `원곡을 찾지 못함 (검색 2.6초)`와 `+0.0 s`
