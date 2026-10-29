package com.tgglabs.commithistorysearch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Tests of the safety of the git process: the pager, the standard input of the child, and the
 * cancellation.
 *
 * These tests run a FAKE git (a shell script), not the real git: they must be able to
 * reproduce the hostile configurations (a pager waiting for a keyboard input, a silent
 * process) without any dependency on the machine.
 *
 * Every test here FAILS on the code of 29/10/2026 (the counter proof of the review of
 * 30/10): before the fix, the child kept an open standard input (a `cat` on it never
 * returned), the command line had no `--no-pager`, and the cancellation was not checked
 * while git was silent.
 *
 * The tests of the maximum delay are in [GitTimeoutTest]: they use an argument that did
 * not exist before the fix, so this file compiles against the code of 29/10 (which is what
 * makes the counter proof possible).
 */
class GitProcessSafetyTest {

    // ---------------------------------------------------------------- the pager

    @Test
    fun `the command line of git always contains --no-pager`() {
        val directory = temporaryDirectory()
        val git = fakeGit(directory, "printf '%s\\n' \"\$@\" > " + quoted(File(directory, "argv.txt")))

        GitCommand.run(git, directory, listOf("log", "--oneline"))

        val arguments = File(directory, "argv.txt").readLines()
        assertTrue(
            "the git command line was $arguments, it must contain --no-pager",
            arguments.contains("--no-pager")
        )
    }

    @Test
    fun `the arguments of the git log command contain --no-pager`() {
        val arguments = GitHistorySearch(File("."), "needle").buildArguments()

        assertTrue(
            "the arguments of git log were $arguments, they must contain --no-pager",
            arguments.contains("--no-pager")
        )
        assertEquals("log", arguments[arguments.indexOf("--no-pager") + 1])
    }

    @Test
    fun `the pager is disabled even for a command built by hand`() {
        val directory = temporaryDirectory()
        val git = fakeGit(directory, "printf '%s\\n' \"\$@\" > " + quoted(File(directory, "argv.txt")))

        // A command that does not come from buildArguments (for instance `git show`) must
        // be protected too: the option is added at the single place where git is started.
        GitCommand.run(git, directory, listOf("show", "HEAD"))

        val arguments = File(directory, "argv.txt").readLines()
        assertTrue(
            "the git command line was $arguments, it must contain --no-pager",
            arguments.contains("--no-pager")
        )
    }

    // ---------------------------------------------------------------- the standard input

    @Test(timeout = 12_000)
    fun `the standard input of the child git is closed`() {
        val directory = temporaryDirectory()
        val capture = File(directory, "stdin.txt")
        // `cat` returns immediately when its input is at EOF. If the standard input of the
        // child stays open (the bug of 29/10), this `cat` blocks forever and the whole
        // command blocks: the test then fails on its timeout.
        val git = fakeGit(
            directory,
            "cat > " + quoted(capture) + "\necho AFTER_CAT"
        )

        val output = GitCommand.run(git, directory, listOf("log"))

        assertTrue("the child did not reach the end of the script, output was '$output'", output.contains("AFTER_CAT"))
        assertTrue("the child did not read anything from its standard input", capture.exists())
        assertEquals("the child read something from its standard input", "", capture.readText())
    }

    // ---------------------------------------------------------------- the cancellation

    @Test(timeout = 12_000)
    fun `a silent git is killed when the search is cancelled`() {
        val directory = temporaryDirectory()
        val finished = File(directory, "finished.txt")
        // The script prints nothing and waits: the old code only checked the cancellation
        // flag BETWEEN two lines, so it stayed blocked until the script ended.
        val git = fakeGit(
            directory,
            "sleep 30\necho FINISHED > " + quoted(finished)
        )

        val start = System.currentTimeMillis()
        try {
            GitCommand.run(git, directory, listOf("log"), cancelled = {
                System.currentTimeMillis() - start > 300
            })
            fail("the search should have been cancelled")
        } catch (e: SearchCancelledException) {
            // expected
        }
        val elapsed = System.currentTimeMillis() - start

        assertTrue("the cancellation took $elapsed ms, it must be immediate", elapsed < 10_000)
        assertFalse(
            "the git process was not killed: it ran until the end",
            finished.exists()
        )
    }

    @Test(timeout = 12_000)
    fun `the cancellation is checked at least every 200 ms while git is silent`() {
        val directory = temporaryDirectory()
        val git = fakeGit(directory, "sleep 30")

        val start = System.currentTimeMillis()
        var checks = 0
        try {
            GitCommand.run(git, directory, listOf("log"), cancelled = {
                checks++
                System.currentTimeMillis() - start > 1_500
            })
            fail("the search should have been cancelled")
        } catch (e: SearchCancelledException) {
            // expected
        }
        val elapsed = System.currentTimeMillis() - start

        assertTrue("the cancellation took $elapsed ms", elapsed < 10_000)
        // 1500 ms of silence, checked every 200 ms: at least a handful of checks.
        assertTrue("the cancellation flag was only tested $checks times", checks >= 3)
    }

    // ---------------------------------------------------------------- helpers

    internal fun temporaryDirectory(): File {
        val directory = File(
            System.getProperty("java.io.tmpdir"),
            "git-safety-test-" + System.nanoTime()
        )
        directory.mkdirs()
        return directory
    }

    /** Writes a fake git (a shell script) and returns it. */
    internal fun fakeGit(directory: File, body: String): File {
        val file = File(directory, "fake-git.sh")
        file.writeText("#!/bin/sh\n" + body + "\n")
        file.setExecutable(true)
        return file
    }

    /** Quotes a path for the shell. */
    internal fun quoted(file: File): String = "'" + file.absolutePath.replace("'", "'\\''") + "'"
}
