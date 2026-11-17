package com.music.bitchord.ko

/**
 * [KO] mv-video
 *
 * Whether a music-video row's picture should be streamed and decoded at all.
 * The "뮤직비디오 영상 재생" switch ([KoAppSettings.mvVideo]) is the first
 * condition: with it off, no surface — the full player, the mini player or the
 * glass bar — resolves the video stream or starts the second decoder.
 */
internal object KoMusicVideoPicture {
    fun pictureWanted(
        isVideo: Boolean,
        pictureless: Boolean,
        showsPicture: Boolean,
        mvVideoOn: Boolean,
    ): Boolean = mvVideoOn && isVideo && !pictureless && showsPicture
}
