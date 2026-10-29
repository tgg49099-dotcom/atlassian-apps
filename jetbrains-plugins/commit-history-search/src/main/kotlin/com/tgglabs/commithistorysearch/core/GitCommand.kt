package com.tgglabs.commithistorysearch.core

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** A git command that returned a non-zero exit code. */
open class GitException(message: String) : IOException(message)

/**
 * A git command that did not finish within the maximum allowed duration.
 *
 * It is a [GitException], but it is caught on its own: a timeout must NOT trigger the
 * fallback on the POSIX regular expressions, or the maximum delay would be paid twice.
 */
class GitTimeoutException(message: String) : GitException(message)

/** The user asked to stop the search. */
class SearchCancelledException : RuntimeException("search cancelled")

/**
 * Runs the git binary. Pure JDK on purpose: the core of the plugin is testable without the IDE.
 */
object GitCommand {

    /**
     * How often the cancellation flag is checked while git runs, in milliseconds.
     *
     * The reader thread wakes up at least every 200 ms, so the "Cancel" button of the IDE
     * reacts even when git has not produced a single line yet (a `git log -S` on a big
     * repository can stay silent for minutes).
     */
    const val POLL_INTERVAL_MS = 200L

    /**
     * Maximum duration of ONE git command, in milliseconds: 10 minutes.
     *
     * Why 10 minutes: the pickaxe walks the whole history of the repository, and on a big
     * repository it takes "tens of seconds" (measured, see the mission). 10 minutes leaves
     * a very wide margin, and a git command that goes past it (network access, a credential
     * prompt, a stuck index.lock) is killed instead of blocking the IDE forever.
     */
    const val DEFAULT_TIMEOUT_MS = 600_000L

    fun run(
        git: File,
        workingDirectory: File,
        args: List<String>,
        cancelled: () -> Boolean = { false },
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): String {
        val timeout = if (timeoutMs > 0) timeoutMs else DEFAULT_TIMEOUT_MS
        val builder = ProcessBuilder(withNoPager(listOf(git.absolutePath) + args))
        builder.directory(workingDirectory)
        builder.redirectError(ProcessBuilder.Redirect.INHERIT)
        val process = builder.start()

        // The child must never read the IDE's standard input. ProcessBuilder gives it a
        // PIPE by default, and that pipe stays OPEN as long as the parent keeps its writing
        // end: a pager (less) would then wait for a keyboard input that never comes, and
        // the plugin would freeze. Closing it right away gives the child an immediate EOF.
        closeQuietly(process.outputStream)

        val output = StringBuilder()
        val monitor = Object()
        var finished = false

        // The output is read by a separate thread: the main loop must keep checking the
        // cancellation flag even while git is silent, which a blocking readLine() forbids.
        val reader = Thread({
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { source ->
                    while (true) {
                        val line = source.readLine() ?: break
                        synchronized(monitor) {
                            output.append(line).append('\n')
                            monitor.notifyAll()
                        }
                    }
                }
            } catch (_: IOException) {
                // The process was killed while reading: the lines already read are kept.
            } finally {
                synchronized(monitor) {
                    finished = true
                    monitor.notifyAll()
                }
            }
        }, "commit-history-search-git-reader")
        reader.isDaemon = true
        reader.start()

        val deadline = System.currentTimeMillis() + timeout
        try {
            while (true) {
                if (cancelled()) throw SearchCancelledException()
                var done: Boolean
                synchronized(monitor) {
                    if (!finished) monitor.wait(POLL_INTERVAL_MS)
                    done = finished
                }
                if (done) break
                if (cancelled()) throw SearchCancelledException()
                if (System.currentTimeMillis() >= deadline) {
                    throw GitTimeoutException(
                        "git ${args.joinToString(" ")} did not finish within $timeout ms"
                    )
                }
            }
            // The output is complete: wait for the exit code, within the same deadline.
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L || !process.waitFor(remaining, TimeUnit.MILLISECONDS)) {
                throw GitTimeoutException(
                    "git ${args.joinToString(" ")} did not finish within $timeout ms"
                )
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SearchCancelledException()
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                try {
                    process.waitFor(2, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        val exitCode = process.exitValue()
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
        cancelled: () -> Boolean = { false },
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): String? = try {
        run(git, workingDirectory, args, cancelled, timeoutMs)
    } catch (e: GitException) {
        null
    }

    /**
     * Adds `--no-pager` to the git command line if it is not there yet.
     *
     * `git log` starts a pager (less) when it can. In the IDE there is no terminal to
     * interact with: the pager waits for a keyboard input that never comes (the plugin
     * freezes) and it pollutes the captured output with its escape codes. `--no-pager`
     * is applied to EVERY git command, not only to `git log`.
     */
    private fun withNoPager(command: List<String>): List<String> {
        if (command.any { it == "--no-pager" }) return command
        return listOf(command[0], "--no-pager") + command.drop(1)
    }

    private fun closeQuietly(stream: OutputStream) {
        try {
            stream.close()
        } catch (_: IOException) {
            // Nothing to do: the stream is closed on a best effort basis.
        }
    }
}
