package com.tgglabs.commithistorysearch.core

import java.io.File

/**
 * Finds the git executable without depending on the Git4Idea plugin, so that the plugin also
 * works in IDEs where the bundled Git integration is disabled.
 */
object GitLocator {

    private val knownLocations = listOf(
        "/usr/bin/git",
        "/usr/local/bin/git",
        "/bin/git",
        "/opt/homebrew/bin/git",
        "/usr/local/opt/git/bin/git",
        "C:\\Program Files\\Git\\cmd\\git.exe",
        "C:\\Program Files\\Git\\bin\\git.exe",
        "C:\\Program Files (x86)\\Git\\cmd\\git.exe"
    )

    fun find(): File? = findInPath() ?: knownLocations.map { File(it) }.firstOrNull { it.isFile && it.canExecute() }

    /**
     * Walks up from [start] until it finds the directory that holds the Git metadata.
     *
     * The project opened in the IDE is often not the root of the repository (a module, a
     * sub-package...), and the search has to run at the root of the repository to see the
     * whole history. A ".git" entry can be a directory (normal clone) or a file (worktree,
     * submodule), so its mere presence is the test.
     */
    fun findRepositoryRoot(start: File): File? {
        var directory: File? = start.absoluteFile
        while (directory != null) {
            if (File(directory, ".git").exists()) return directory
            directory = directory.parentFile
        }
        return null
    }

    private fun findInPath(): File? {
        val executable = if (isWindows()) "git.exe" else "git"
        val path = System.getenv("PATH") ?: return null
        for (directory in path.split(File.pathSeparator)) {
            if (directory.isBlank()) continue
            val candidate = File(directory, executable)
            if (candidate.isFile && candidate.canExecute()) return candidate
        }
        return null
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")
}
