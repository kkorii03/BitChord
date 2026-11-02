package com.music.bitchord

import java.io.File

internal object TestFiles {
    fun repositoryRoot(): File {
        var directory = File(System.getProperty("user.dir")).absoluteFile
        while (!File(directory, "settings.gradle.kts").isFile) {
            directory = directory.parentFile
                ?: error("settings.gradle.kts not found above ${System.getProperty("user.dir")}")
        }
        return directory
    }

    fun file(relative: String): File = File(repositoryRoot(), relative)
}