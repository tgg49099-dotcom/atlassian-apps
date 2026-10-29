package com.tgglabs.commithistorysearch.core

import java.io.File
import java.io.IOException

/** A git command that returned a non-zero exit code. */
class GitException(message: String) : IOException(message)

/** The user asked to stop the search. */
class SearchCancelledException : RuntimeException("search cancelled")

/**
 * Runs the git binary. Pure JDK on purpose: the core of the plugin is testable without the IDE.
 */
object GitCommand {

    fun run(
        git: File,
        workingDirectory: File,
        args: List<String>,
        cancelled: () -> Boolean = { false }
    ): String {
        val builder = ProcessBuilder(listOf(git.absolutePath) + args)
        builder.directory(workingDirectory)
        builder.redirectError(ProcessBuilder.Redirect.INHERIT)
        val process = builder.start()
        val output = StringBuilder()
        try {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    if (cancelled()) {
                        process.destroyForcibly()
                        throw SearchCancelledException()
                    }
                    val line = reader.readLine() ?: break
                    output.append(line).append('\n')
                }
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GitException("git ${args.joinToString(" ")} failed (exit code $exitCode)")
        }
        return output.toString()
    }

    /** Same as [run] but returns null instead of throwing when git fails. */
    fun runOrNull(
        git: File,
        workingDirectory: File,
        args: List<String>,
        cancelled: () -> Boolean = { false }
    ): String? = try {
        run(git, workingDirectory, args, cancelled)
    } catch (e: GitException) {
        null
    }
}
