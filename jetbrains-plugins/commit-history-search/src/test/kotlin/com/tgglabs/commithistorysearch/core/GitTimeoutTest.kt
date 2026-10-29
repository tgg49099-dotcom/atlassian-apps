package com.tgglabs.commithistorysearch.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Tests of the maximum delay of a git command.
 *
 * ⚠️ This file uses the `timeoutMs` argument of [GitCommand.run], which did **not exist** on
 * 29/10/2026 : against the code of that day, this test file does not even COMPILE
 * ("no value passed for parameter 'timeoutMs'"), which is the proof that the old code had
 * no maximum delay at all. The counter proof of the other defects is in
 * [GitProcessSafetyTest], which compiles against the old code and fails on it.
 */
class GitTimeoutTest {

    @Test(timeout = 12_000)
    fun `a git command that never ends is killed after the maximum delay`() {
        val directory = temporaryDirectory()
        val finished = File(directory, "finished.txt")
        val git = fakeGit(
            directory,
            "sleep 30\necho FINISHED > " + quoted(finished)
        )

        val start = System.currentTimeMillis()
        try {
            GitCommand.run(git, directory, listOf("log"), { false }, timeoutMs = 2_000)
            fail("the command should have timed out")
        } catch (e: GitTimeoutException) {
            assertTrue(
                "the error message must name the delay, it was '${e.message}'",
                e.message!!.contains("2000")
            )
        }
        val elapsed = System.currentTimeMillis() - start

        assertTrue("the timeout took $elapsed ms", elapsed < 15_000)
        assertFalse("the git process was not killed", finished.exists())
    }

    @Test(timeout = 12_000)
    fun `a timeout is a GitException that can be told apart from another failure`() {
        val directory = temporaryDirectory()
        val git = fakeGit(directory, "sleep 30")

        // GitTimeoutException is a GitException: it must be catchable as such, but it must
        // also be identifiable, so that the search does not fall back on the POSIX regexps
        // (which would pay the maximum delay a second time).
        val caught = try {
            GitCommand.run(git, directory, listOf("log"), { false }, timeoutMs = 1_000)
            null
        } catch (e: GitException) {
            e
        }

        assertTrue("the command should have timed out", caught != null)
        assertTrue(
            "a timeout must be identifiable, it was ${caught!!::class.java.name}",
            caught is GitTimeoutException
        )
    }

    @Test(timeout = 12_000)
    fun `the default maximum delay is documented and long enough for a big repository`() {
        // The pickaxe walks the whole history of the repository: the maximum delay must be
        // long enough (10 minutes), and it must be a documented constant.
        assertTrue(
            "the default maximum delay is ${GitCommand.DEFAULT_TIMEOUT_MS} ms",
            GitCommand.DEFAULT_TIMEOUT_MS >= 600_000L
        )
        assertTrue(
            "the poll interval is ${GitCommand.POLL_INTERVAL_MS} ms, it must be at most 200 ms",
            GitCommand.POLL_INTERVAL_MS <= 200L
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun temporaryDirectory(): File {
        val directory = File(
            System.getProperty("java.io.tmpdir"),
            "git-timeout-test-" + System.nanoTime()
        )
        directory.mkdirs()
        return directory
    }

    private fun fakeGit(directory: File, body: String): File {
        val file = File(directory, "fake-git.sh")
        file.writeText("#!/bin/sh\n" + body + "\n")
        file.setExecutable(true)
        return file
    }

    private fun quoted(file: File): String = "'" + file.absolutePath.replace("'", "'\\''") + "'"
}
