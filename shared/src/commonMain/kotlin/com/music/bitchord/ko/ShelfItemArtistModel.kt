package com.music.bitchord.ko

import com.music.bitchord.data.model.ShelfItem

/**
 * Korean-fork card flag that the v1.9 merge dropped from `ShelfItem`.
 *
 * Kept as an extension so the upstream model file stays untouched. When the
 * merged model carries the real `isArtist` field again, a member shadows this
 * and the value comes from the parser as before.
 */
val ShelfItem.isArtist: Boolean
    get() = browseId?.startsWith("UC") == true
