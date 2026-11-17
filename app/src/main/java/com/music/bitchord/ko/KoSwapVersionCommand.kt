package com.music.bitchord.ko

/**
 * [KO] review V-4: whether a version-swap command still applies when it reaches
 * the service. The sender checks "still current" on its MediaController, one IPC
 * hop behind the service, so a skip, a notification or Bluetooth key, or the song
 * ending can move the service on to the next row before the command lands. That
 * next row must not be replaced with the previous song's version.
 *
 * [expectedFromId] is the media id the sender swapped from; null (an older sender
 * that did not say) keeps the old behaviour. [currentId] is the service's row now.
 */
internal fun koSwapFromStillCurrent(expectedFromId: String?, currentId: String?): Boolean =
    expectedFromId == null || expectedFromId == currentId
