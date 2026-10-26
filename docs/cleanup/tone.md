# 한국어 말투 정리 (UI/UX 3차)

`app/src/main/res/values-ko/strings.xml`와 `sharedUi/src/commonMain/composeResources/values-ko/strings.xml`를 애플뮤직 한국어 톤으로 맞췄다. 플레이스홀더(`%1$s`, `%1$d`, `\n`)와 저장 데이터 키는 그대로 두었다.

## 규칙

- 안내·설명은 짧은 해요체(`~해요`, `~돼요`, `~하세요`)로 통일. `~하십시오`, `~합니다`, `~했습니다`, `~됩니다`는 남기지 않았다.
- 메뉴·동작 이름은 명사형(`보관함에 추가`, `다음에 재생`, `대기열에 추가`)으로 통일.
- 대기열은 `대기열`, 다음 재생 동작은 `다음에 재생`으로 고정. `나중에 재생`은 없앴다.
- 재생목록으로 통일. `플레이리스트` 혼용을 없앴다.
- 느낌표·이모지는 사용하지 않는다. `저장되었습니다!` → `저장됨`.
- 오탈자·용어: `앤범` → `앨범`, `컧오프` → `컷오프`, `내려받기` → `다운로드`.

## 주요 전후

| 키 | 이전 | 이후 |
|---|---|---|
| queue | 다음 재생 | 대기열 |
| add_to_queue | 나중에 재생 | 대기열에 추가 |
| song_added_to_queue | 나중에 재생에 추가됨 | 대기열에 추가됨 |
| playlist_action_failed | 플레이리스트를 변경하지 못했습니다 | 재생목록을 변경하지 못했어요 |
| saved_bang | 저장되었습니다! | 저장됨 |
| album_folder | 앤범 | 앨범 |
| pipeline_dsd_full_band | DSD → PCM, 컧오프 아래 전 대역 | DSD → PCM, 컷오프 아래 전 대역 |
| no_downloaded_tracks | 내려받은 곡이 없습니다 | 다운로드한 곡이 없어요 |
| auto_login_subtitle | …로그인하십시오. | …로그인하세요. |
| auto_empty_recents_subtitle | …표시됩니다. | …표시돼요. |
| search_empty | …수백만 곡의 노래를 검색하십시오. | …수백만 곡을 검색하세요. |
| storage_required_save | …필요합니다. | …필요해요. |
| channel_session_unavailable | …없습니다. 먼저 …여십시오. | …없어요. 먼저 …여세요. |
| channel_switch_instructions | …탭하십시오. | …탭하세요. |
| import_data_subtitle | …대체합니다. | …대체해요. |
| app_language_description | …선택하십시오. | …선택하세요. |
| automix_performance_subtitle | …설정합니다. | …설정해요. |
| prefer_music_only_subtitle | …표시합니다. | …표시해요. |
| equalizer_footer | …조절합니다. | …조절해요. |
| equalizer_bands_footer | …커브가 됩니다. | …커브가 돼요. |
| equalizer_balance_footer | …치우치게 합니다. | …치우치게 해요. |
| equalizer_bitstream_footer | …적용됩니다. …없습니다. | …적용돼요. …없어요. |
| system_equalizer_footer | …적용됩니다. 둘 다 …적용됩니다. | …적용돼요. 둘 다 …적용돼요. |
| show_nerd_stats_subtitle | …표시합니다. | …표시해요. |
| reduce_animation_subtitle | …고정됩니다. | …고정돼요. |
| liquid_glass_subtitle | …적용합니다. …사용합니다. | …적용해요. …사용해요. |
| synced_lyrics_subtitle | …강조 표시됩니다. | …강조돼요. |
| stop_music_on_close_subtitle | …중단합니다. | …중단해요. |
| hide_volume_bar_subtitle | …제거합니다. | …없애요. |
| animated_cover_art_subtitle | …재생합니다. | …재생해요. |
| high_performance_subtitle | …요청합니다. | …요청해요. |
| high_performance_battery_warning | …설정하십시오. …수 있습니다. | …설정하세요. …수 있어요. |
| activity_dialog_message | …동사입니다. | …동사예요. |
| activity_name_message | …내용입니다. …사용됩니다. | …내용이에요. …사용해요. |
| add_addon_detail | …연결합니다. | …연결해요. |
| translation_language_description | …번역됩니다. | …번역돼요. |
| connect_account_first | …연결하십시오. | …연결하세요. |
| discord_token_description | …붙여 넣으십시오. …전송됩니다. | …붙여 넣으세요. …전송돼요. |
| discord_token_warning | …사용합니다. …위배됩니다. …사용하십시오. | …사용해요. …어긋나요. …사용하세요. |
| enter_token_to_enable | …입력하십시오. | …입력하세요. |
| cast_searching_hint | …있어야 합니다. | …있어야 해요. |
| wifi_only_download_refusal | …있습니다. …끄십시오. | …있어요. …끄세요. |
| library_sign_in_description | …로그인하십시오. | …로그인하세요. |
| history_sign_in_required | …로그인하십시오. | …로그인하세요. |
| youtube_request_rejected | …거부했습니다. …보십시오. | …거부했어요. …보세요. |
| something_went_wrong | 문제가 발생했습니다. | 문제가 발생했어요. |
| lastfm_login_description | …로그인하십시오. | …로그인하세요. |
| lastfm_now_playing_subtitle | …업데이트합니다. | …업데이트해요. |
| scrobble_primary_artist_only_subtitle | …사용합니다. | …사용해요. |
| lead_with_song_subtitle | …표시합니다. | …표시해요. |
| listenbrainz_token_description | …붙여 넣으십시오. | …붙여 넣으세요. |
| lyrics_sources_order | …시도됩니다. …변경하십시오. …사용됩니다. | …시도해요. …바꾸세요. …사용해요. |
| addon_url_description | …붙여 넣으십시오. …요청합니다. | …붙여 넣으세요. …요청해요. |
| no_playlists_yet | …없습니다. …생성하십시오. | …없어요. …만들어 보세요. |
| play_to_preview | …표시됩니다. | …표시돼요. |
| rich_presence_footer | …표시합니다. | …표시해요. |
| scrobble_delay_subtitle | …설정합니다. | …설정해요. |
| scrobbling_footer | …스크로블합니다. | …스크로블해요. |
| scrobbling_paused_footer | …예정입니다. …있습니다. | …예정이에요. …있어요. |
| sources_no_lossless | …없습니다. …추가하십시오. | …없어요. …추가하세요. |
| sources_order_footer | …시도합니다. …수 있습니다. | …시도해요. …수 있어요. |
| sources_quality_capped | …트랜스코딩됩니다. …않습니다. | …트랜스코딩돼요. …않아요. |
| enable_jiosaavn | JioSaavn을 켜겠습니까? | JioSaavn을 켤까요? |
| upgrade_length_slack_footer | …엄격해집니다. …3초입니다. | …엄격해져요. …3초예요. |
| spotify_canvas_setup_description | …입력하십시오. | …입력하세요. |
| spotify_canvas_setup_steps | …로그인합니다. …이동합니다. …복사합니다. …붙여 넣습니다. | …로그인하세요. …이동하세요. …복사하세요. …붙여 넣으세요. |
| prioritize_spotify_canvas_subtitle | …시도합니다. | …시도해요. |
| status_dialog_message | …상태입니다. | …상태예요. |
| genres_pending | …표시됩니다. | …표시돼요. |
| not_enough_listening | 청취 기록이 부족합니다 | 청취 기록이 부족해요 |
| replay_share_card_description | …카드입니다. | …카드예요. |
| replay_share_summary_description | …이미지입니다. | …이미지예요. |
| tap_share_for_picture | …생성하십시오. | …만들어 보세요. |
| use_all_audio_folders_subtitle | …검색합니다. | …검색해요. |
| webdav_not_configured | …연결하십시오. | …연결하세요. |
| webdav_empty | …없습니다. …확인하십시오. | …없어요. …확인하세요. |
| webdav_description | …연결합니다. | …연결해요. |
| webdav_conflict_message | …유지하시겠습니까? | …유지할까요? |
| smb_not_configured | …연결하십시오. | …연결하세요. |
| smb_empty | …없습니다. …확인하십시오. | …없어요. …확인하세요. |
| smb_description | …연결합니다. | …연결해요. |
| delete_playlist_confirmation | …삭제하겠습니까? …탭하십시오. | …삭제할까요? …탭하세요. |
| delete_download_confirmation | …제거하겠습니까? …탭하십시오. | …제거할까요? …탭하세요. |
| automix_enabled_subtitle | …조절합니다. …끄십시오. | …조절해요. …끄세요. |
| automix_disabled_subtitle | …조절합니다. …있습니다. | …조절해요. …있어요. |
| advanced_automix_subtitle | …믹스합니다. …끊습니다. …없습니다. | …믹스해요. …끊어요. …없어요. |
| song_cache_large_subtitle | …저장합니다. …있습니다. | …저장해요. …있어요. |
| song_cache_limit_subtitle | …빠르게 합니다. | …빠르게 해요. |
| replay_genres_enabled_subtitle | …요청합니다. …전송됩니다. | …요청해요. …전송돼요. |
| replay_genres_disabled_subtitle | …않습니다. | …않아요. |
| swipe_plays_next | …재생됩니다. | …재생돼요. |
| swipe_adds_to_queue | …추가됩니다. | …추가돼요. |
| import_backup_warning | …대체됩니다. …내보내십시오. | …대체돼요. …내보내세요. |
| listen_together_create_sheet_subtitle | …선택하십시오. | …선택하세요. |
| listen_together_join_question | …참여하겠습니까? | …참여할까요? |
| listen_together_preview_footer | …표시됩니다. | …표시돼요. |
| listen_together_custom_server_dialog_message | …호스팅되고 있습니다. …참여하겠습니까? | …호스팅되고 있어요. …참여할까요? |
| listen_together_official_server_dialog_message | …호스팅되고 있습니다. …참여하겠습니까? | …호스팅되고 있어요. …참여할까요? |
| listen_together_switch_failed | …없습니다: | …없어요: |
| listen_together_invalid_server_url | …URL입니다. …입력하십시오. | …URL이에요. …입력하세요. |
| listen_together_err_invalid_port | …하여야 합니다. | …해야 해요. |
| miscellaneous_footer | …중단됩니다. | …중단돼요. |
| loudness_normalization_subtitle | …재생합니다. | …재생해요. |
| loudness_target_subtitle | …적합합니다. | …맞아요. |
| source_needs_lossless_output | …사용하십시오. | …사용하세요. |
| source_needs_lossless_bt_codec | …사용 중입니다. …사용하십시오. | …사용 중이에요. …사용하세요. |
| lossless_output_permission_denied_detail | …허용하십시오. …없습니다. | …허용하세요. …없어요. |
| cached_songs_empty | …없습니다. …표시됩니다. | …없어요. …표시돼요. |
| show_cache_folder_subtitle | …추가합니다. | …추가해요. |
| clear_song_cache_title | …지우겠습니까? | …지울까요? |
| clear_song_cache_warning | …비워집니다. …스트리밍됩니다. | …비워져요. …스트리밍돼요. |
| spotify_import_description | …붙여 넣으십시오. …매칭됩니다. | …붙여 넣으세요. …매칭돼요. |
| spotify_import_local_fallback | …저장했습니다. | …저장했어요. |
| spotify_import_unmatched | …못했습니다. | …못했어요. |
| gemini_translation_subtitle_ready | …번역합니다. …사용합니다. | …번역해요. …사용해요. |
| gemini_api_key_description | …붙여 넣으십시오. …전송됩니다. …사용합니다. | …붙여 넣으세요. …전송돼요. …사용해요. |
| gemini_translation_subtitle_rejected | …사용 중입니다. …확인하십시오. | …사용 중이에요. …확인하세요. |
| prefetch_translation_subtitle | …표시됩니다. | …표시돼요. |
| auto_download_liked_subtitle | …다운로드합니다. | …다운로드해요. |
| smart_playlist_sign_in | …로그인하십시오. | …로그인하세요. |
| lock_screen_lyrics_subtitle | …표시합니다. | …표시해요. |
| lock_screen_lyrics_needs_permission | …필요합니다. …허용하십시오. | …필요해요. …허용하세요. |
| auto_translate_lyrics_subtitle | …표시됩니다. …누르십시오. | …표시돼요. …누르세요. |
| content_country_description | …표시합니다. …않습니다. | …표시해요. …않아요. |
| resume_on_headphones_subtitle | …재생합니다. | …재생해요. |
| cover_channel_set_done | …표시됩니다. | …표시돼요. |
| cover_channel_unset_done | …해제했습니다. | …해제했어요. |
| upstream_new_version_detail | …해야 합니다. | …해야 해요. |
| account_channels_hint | …사용됩니다. | …사용해요. |
| auto_lyrics_timing_subtitle | …보정합니다. …맞춥니다. | …보정해요. …맞춰요. |
| deepgram_api_key_description | …사용합니다. …맞춥니다. …있습니다. | …사용해요. …맞춰요. …있어요. |
| deepgram_subtitle_missing | …사용합니다 | …사용해요 |
| deepgram_subtitle_rejected | …거부했습니다. …입력하십시오. | …거부했어요. …입력하세요. |
| deepgram_key_bad | …거부했습니다. …확인하십시오. | …거부했어요. …확인하세요. |
| deepgram_key_unverified | …못했습니다 | …못했어요 |
| proxy_server_not_set | …입력하십시오 | …입력하세요 |
| proxy_scope_music_subtitle | …사용합니다. …사용하며 …마찬가지입니다. | …사용해요. …사용하고 …마찬가지예요. |
| proxy_scope_all_subtitle | …사용합니다. | …사용해요. |
| proxy_connection_test_subtitle | …확인합니다. | …확인해요. |
| proxy_health_home_notice | …동작합니다. | …동작해요. |
| proxy_error_host_missing | …입력하십시오 | …입력하세요 |
| proxy_error_port_invalid | …하여야 합니다 | …해야 해요 |
| artist_signatures_subtitle | …표시합니다 | …표시해요 |
| artist_signatures_test_failed | …없습니다 | …없어요 |
| removed_from_cache | …삭제했습니다 | …삭제했어요 |
| show_queue_history_subtitle | …표시합니다 | …표시해요 |
| song_info_error | …못했습니다. | …못했어요. |
| wear_os_failed | 실패했습니다 | 실패했어요 |
| wear_os_installed | 설치했습니다. …됩니다. | 설치했어요. …돼요. |
| wear_os_send_failed | …못했습니다 | …못했어요 |
| wear_os_unknown | …못했습니다 | …못했어요 |
| wear_os_unsupported | …이어야 합니다. | …이어야 해요. |
| no_local_albums | …없습니다. …표시됩니다. | …없어요. …표시돼요. |
| music_gate_fallback | …동작합니다 | …동작해요 |
| switch_profile_hint | …사용하십시오. | …사용하세요. |
| listen_together_sign_in_footer | …필요합니다. …있습니다. | …필요해요. …있어요. |
| listen_together_custom_server_footer | …비워 두십시오. …해야 합니다. | …비워 두세요. …해야 해요. |
| listen_together_create_footer | …발급됩니다. …있습니다. | …발급돼요. …있어요. |
| listen_together_code_footer | …전송하십시오. …0입니다. | …전송하세요. …0이에요. |
| listen_together_landing_subtitle | …좋습니다. …추가하십시오. | …좋아요. …추가하세요. |
| widget_*_description | …위젯입니다. | …위젯이에요. |
| dolby_atmos_subtitle | …재생합니다. | …재생해요. |
| dolby_atmos_unavailable | …재생됩니다. | …재생돼요. |
| spatial_audio_atmos_subtitle | …공간 음향입니다. | …공간 음향이에요. |
| import_backup_title | …가져오겠습니까? | …가져올까요? |
| enable_high_performance_title | …켜겠습니까? | …켤까요? |
| sharedUi next_from / next_in_queue / up_next | 다음 재생 | 다음에 재생 |
| sharedUi queue / remove_from_queue | 다음 재생 / 다음 재생에서 제거 | 대기열 / 대기열에서 제거 |
| sharedUi lyrics_already_in_language | …가사입니다. | …가사예요. |
| sharedUi lyrics_offset_description | …표시됩니다. | …표시돼요. |
| sharedUi song_meaning_footer | …해석입니다. 틀릴 수 있습니다. | …해석이에요. 틀릴 수 있어요. |
| sharedUi search_addons_empty | …검색합니다. …재생됩니다. | …검색해요. …재생돼요. |
| sharedUi lyrics_timing_untouched / video_own | …그대로입니다. | …그대로예요. |
