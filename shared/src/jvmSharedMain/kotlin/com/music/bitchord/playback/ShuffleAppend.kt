package com.music.bitchord.playback

import kotlin.random.Random

object ShuffleAppend {

    fun <T> mergeRemaining(
        remaining: List<T>,
        added: List<T>,
        key: (T) -> String,
        random: Random = Random.Default,
    ): List<T> {
        if (added.isEmpty()) return remaining
        val seen = remaining.mapTo(HashSet()) { key(it) }
        val fresh = added.filter { seen.add(key(it)) }
        if (fresh.isEmpty()) return remaining
        val result = remaining.toMutableList()
        fresh.shuffled(random).forEach { item ->
            result.add(random.nextInt(result.size + 1), item)
        }
        return result
    }
}
