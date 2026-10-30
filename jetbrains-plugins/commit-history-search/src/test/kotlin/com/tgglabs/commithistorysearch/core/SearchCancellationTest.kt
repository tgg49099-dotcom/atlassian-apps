package com.tgglabs.commithistorysearch.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The PROOF that the cancellation reacts, even when git writes NOTHING.
 *
 * The review of 30/10/2026 (point 3) said that the cancellation was only checked between
 * two lines of output, and that the "Cancel" button would not react while git was silent.
 * That was true of the code of 28/10 (the version the reviewer read) : the output was read
 * by a blocking readLine() in the main thread. It is NOT true of the code of the cycle 19
 * : the output is read by its own thread and the flag is checked at least every 200 ms.
 *
 * This test does not take the word of anybody for it: it launches a REAL git command
 * that stays silent (a git alias that sleeps), asks for the cancellation 300 ms later,
 * and measures the time it takes to raise [SearchCancelledException]. It must be LESS
 * THAN 2 SECONDS.
 *
 * ⚠️ Against the code of 28/10 this test FAILS: the cancellation was only seen when the
 * silent command ended (10 s later), which is exactly the defect the reviewer reported.
 */
class SearchCancellationTest {

    @Test(timeout = 30_000)
    fun `a silent git command is cancelled in less than two seconds`() {
        val directory = repository()
        // A REAL git command that writes nothing for ten seconds: exactly the behaviour of
        // `git log -S` on a big repository, which can stay silent for minutes.
        git(directory, "config", "alias.tggsleep", "!sleep 10")

        val cancelAt = System.currentTimeMillis() + 300
        val cancelledAt = AtomicLong(0)
        val thrownAt = AtomicLong(0)
        val failure = AtomicReference<Throwable>()

        // The search runs in the background, like the task of the IDE.
        val worker = Thread({
            try {
                GitCommand.run(
                    GitLocator.find()!!,
                    directory,
                    listOf("tggsleep"),
                    cancelled = {
                        val now = System.currentTimeMillis()
                        if (now >= cancelAt) {
                            cancelledAt.compareAndSet(0, now)
                            true
                        } else {
                            false
                        }
                    }
                )
            } catch (e: Throwable) {
                thrownAt.set(System.currentTimeMillis())
                failure.set(e)
            }
        }, "search-cancellation-test")
        worker.isDaemon = true
        worker.start()
        worker.join(20_000)

        assertFalse("the search did not stop", worker.isAlive)
        val thrown = failure.get()
        assertTrue(
            "the silent git command must be stopped with a SearchCancelledException, " +
                "it was $thrown",
            thrown is SearchCancelledException
        )
        val delay = thrownAt.get() - cancelledAt.get()
        assertTrue(
            "the cancellation took $delay ms after the flag was raised: it must be less " +
                "than 2000 ms",
            delay < 2_000
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun repository(): File {
        val git = GitLocator.find()
        assumeTrue("git is not available", git != null)
        val directory = File(System.getProperty("java.io.tmpdir"), "cancellation-" + System.nanoTime())
        assertTrue(directory.mkdirs())
        git(directory, "init")
        git(directory, "config", "user.email", "test@example.org")
        git(directory, "config", "user.name", "Test")
        git(directory, "config", "commit.gpgsign", "false")
        return directory
    }

    private fun git(directory: File, vararg args: String): String {
        val git = GitLocator.find()!!
        return GitCommand.run(git, directory, args.toList())
    }
}
