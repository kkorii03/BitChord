package com.music.bitchord.playback

/**
 * Whether the item the player is on reads from the device or from the network.
 *
 * The item's own URI is the authority. A saved download under the same id is
 * not evidence that this item is using it: a song version whose file is the
 * video's sound streams from a source while the old file is still recorded, and
 * treating that record as local removed the network wake lock the stream needs.
 *
 * Kept free of Media3 so the rule is testable on the JVM; the caller passes the
 * schemes and the download record's answer.
 */
internal object LocalPlaybackPolicy {

    fun isLocal(
        mediaId: String,
        localConfigurationUri: String?,
        requestMetadataUri: String?,
        extraLocalUri: String?,
        extraLocalPath: String?,
        savedUri: String?,
    ): Boolean {
        if (mediaId.startsWith("content://") ||
            mediaId.startsWith("file://") ||
            mediaId.startsWith("/")
        ) {
            return true
        }
        if (isFileUri(localConfigurationUri)) return true
        if (isFileUri(requestMetadataUri)) return true
        // The item states a source to open and it is not a file: the player is
        // reading that source, not the saved file that happens to share the id.
        // The extras are only evidence when there is no source at all, or when
        // the source is itself a file. A file deleted from under a saved row
        // leaves its old EXTRA_LOCAL_PATH behind while the rebuilt item streams
        // from `bitchord://`, and trusting it removed the network wake lock.
        if (hasScheme(localConfigurationUri)) return false
        if (hasScheme(requestMetadataUri)) return false
        if (isFileUri(extraLocalUri)) return true
        if (!extraLocalPath.isNullOrBlank()) return true
        return savedUri != null
    }

    private fun isFileUri(uri: String?): Boolean {
        val scheme = schemeOf(uri) ?: return false
        return scheme == "file" || scheme == "content"
    }

    private fun hasScheme(uri: String?): Boolean = schemeOf(uri) != null

    private fun schemeOf(uri: String?): String? {
        if (uri.isNullOrBlank()) return null
        val colon = uri.indexOf(':')
        if (colon <= 0) return null
        return uri.substring(0, colon).lowercase()
    }
}
