package com.music.bitchord.data.cover

import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult

/**
 * Retries a failed YouTube still at the size every video has.
 *
 * Rows rewrite a legacy still to `hq720` for a square, bar-free crop, but the
 * smaller cover uploads have no `hq720` (and often no `maxresdefault`) at all:
 * that request 404s and the player, the mini player and every list row drew an
 * empty tile over a video that does have a thumbnail. When any YouTube still
 * size fails, this asks for the same video's `hqdefault` instead. Other hosts
 * and other failures are left exactly as they were.
 */
class YouTubeArtworkFallback : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val result = chain.proceed()
        if (result !is ErrorResult) return result
        val failed = chain.request.data as? String ?: return result
        val fallback = youtubeStillFallback(failed) ?: return result
        val request = chain.request.newBuilder().data(fallback).build()
        return chain.withRequest(request).proceed()
    }
}
