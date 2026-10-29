package com.tgglabs.commithistorysearch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Tests of the SCOPE of the search: how far back the scan goes, and how many results it lists.
 *
 * They exist because the public description of the plugin used to say "the scan covers the 500
 * most recent commits", which is FALSE. Measured on 29/10/2026 with git 2.39.5: git's
 * `--max-count` limits the number of commits DISPLAYED, not the number of commits SCANNED.
 * On a repository of 1200 commits, `git log -S needle --max-count=500` still finds a needle
 * that only exists in the very first commit, and on a repository of 5000 commits git walks
 * all of them (0.65 s) even when the needle is nowhere to be found.
 *
 * The listing now says the opposite ("the scan covers the whole history, the list shows at
 * most the 500 most recent matching commits"). These tests make sure the code keeps doing
 * what the text says: they would fail the day someone makes the scan stop at the 500 most
 * recent commits without changing the text.
 */
class GitHistoryScopeTest {

    @Test
    fun `the search covers the whole history and not only the 500 most recent commits`() {
        val repo = buildRepository()
        assumeTrue("git is not available", repo != null)
        val directory = repo!!

        // 501 commits: the needle is added by the first one, the 500 next ones are empty.
        write(directory, "a.txt", "the needle is here\n")
        commit(directory, "c1 adds the needle")
        repeat(500) { index -> commitEmpty(directory, "c${index + 2}") }

        val hits = GitHistorySearch(directory, "needle").search()

        assertEquals(
            "the needle is in the very first commit of a history of 501 commits: the scan must " +
                "not stop at the 500 most recent commits",
            1,
            hits.size
        )
        assertEquals("c1 adds the needle", hits[0].subject)

        // Counter-proof, with the real git binary: the same search, restricted to the 500 most
        // recent commits, finds NOTHING. That is what the plugin would answer if the scan
        // really stopped there. The difference between the two answers is what makes the
        // public description worth writing down.
        val recent = git(directory, "rev-list", "--max-count=500", "HEAD").trim().lines()
        val restricted = git(directory, "log", "-S", "needle", "--max-count=500", recent.last() + "..HEAD")
        assertTrue(
            "the counter-proof is broken: a search restricted to the 500 most recent commits " +
                "must find nothing, it found: $restricted",
            restricted.isEmpty()
        )
    }

    @Test
    fun `the result list shows at most the 500 most recent matching commits`() {
        val repo = buildRepository()
        assumeTrue("git is not available", repo != null)
        val directory = repo!!

        // 501 commits, each one adding a line that contains the needle: 501 matches.
        repeat(501) { index ->
            write(directory, "a.txt", "needle ${index + 1}\n", append = true)
            commit(directory, "c${index + 1}")
        }

        val hits = GitHistorySearch(directory, "needle").search()

        assertEquals("the list must be capped at 500 results", 500, hits.size)
        assertEquals("c501", hits.first().subject)
        assertFalse(
            "the oldest match must be the one that is dropped, not the newest",
            hits.any { it.subject == "c1" }
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun buildRepository(): File? {
        val git = GitLocator.find() ?: return null
        val directory = File(System.getProperty("java.io.tmpdir"), "pickaxe-scope-" + System.nanoTime())
        if (!directory.mkdirs()) return null
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

    private fun write(directory: File, name: String, content: String, append: Boolean = false) {
        val file = File(directory, name)
        if (append) file.appendText(content) else file.writeText(content)
    }

    private fun commit(directory: File, message: String) {
        git(directory, "add", "-A")
        git(directory, "commit", "-m", message)
    }

    /** An empty commit: it is part of the history but it changes no file. */
    private fun commitEmpty(directory: File, message: String) {
        git(directory, "commit", "--allow-empty", "-m", message)
    }
}
