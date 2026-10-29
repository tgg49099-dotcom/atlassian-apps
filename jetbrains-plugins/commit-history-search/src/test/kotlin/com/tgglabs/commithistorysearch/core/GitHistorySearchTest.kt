package com.tgglabs.commithistorysearch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Tests of the pickaxe search.
 *
 * The end-to-end tests build a real Git repository and run the real `git log -S` / `git log -G`:
 * they prove the whole chain (command line, parsing, classification), not only the parser.
 */
class GitHistorySearchTest {

    // ---------------------------------------------------------------- parser only

    @Test
    fun `parses commit header and matching files`() {
        val output = buildString {
            append('\u001e')
            append("1111111111111111111111111111111111111111\u001f")
            append("1111111\u001f")
            append("Alice\u001f")
            append("alice@example.org\u001f")
            append("2026-10-26T10:30:00+01:00\u001f")
            append("Add the needle\u001f")
            append('\n')
            append("diff --git a/src/main.kt b/src/main.kt\n")
            append("index 1234567..89abcde 100644\n")
            append("--- a/src/main.kt\n")
            append("+++ b/src/main.kt\n")
            append("@@ -0,0 +1,2 @@\n")
            append("+val needle = 1\n")
            append("+val other = 2\n")
        }

        val hits = GitLogParser.parse(output) { it.contains("needle") }

        assertEquals(1, hits.size)
        val commit = hits[0]
        assertEquals("1111111111111111111111111111111111111111", commit.hash)
        assertEquals("1111111", commit.shortHash)
        assertEquals("Alice", commit.author)
        assertEquals("alice@example.org", commit.email)
        assertEquals("2026-10-26T10:30:00+01:00", commit.date)
        assertEquals("Add the needle", commit.subject)
        assertEquals(1, commit.files.size)
        val file = commit.files[0]
        assertEquals("src/main.kt", file.path)
        assertEquals(1, file.addedMatches)
        assertEquals(0, file.removedMatches)
        assertEquals(ChangeKind.ADDED, file.change)
    }

    @Test
    fun `the diff header line is never counted as a match`() {
        val output = "\u001e" + "a".repeat(40) + "\u001f" + "aaaaaaa\u001f" + "Bob\u001f" +
            "bob@example.org\u001f" + "2026-10-26T10:30:00+01:00\u001f" + "msg\u001f" +
            "\ndiff --git a/needle.txt b/needle.txt\n" +
            "--- a/needle.txt\n" +
            "+++ b/needle.txt\n" +
            "@@ -1 +1 @@\n" +
            "+a needle line\n"

        val hits = GitLogParser.parse(output) { it.contains("needle") }

        assertEquals(1, hits.size)
        // "+++ b/needle.txt" contains "needle" but is a header, not a content line.
        assertEquals(1, hits[0].files[0].addedMatches)
    }

    @Test
    fun `a line removed and a line added is reported as modified`() {
        val output = "\u001e" + "b".repeat(40) + "\u001f" + "bbbbbbb\u001f" + "Bob\u001f" +
            "bob@example.org\u001f" + "2026-10-26T10:30:00+01:00\u001f" + "msg\u001f" +
            "\ndiff --git a/f.txt b/f.txt\n" +
            "--- a/f.txt\n" +
            "+++ b/f.txt\n" +
            "@@ -1 +1 @@\n" +
            "-old needle\n" +
            "+new needle\n"

        val hits = GitLogParser.parse(output) { it.contains("needle") }

        assertEquals(1, hits[0].files[0].addedMatches)
        assertEquals(1, hits[0].files[0].removedMatches)
        assertEquals(ChangeKind.MODIFIED, hits[0].files[0].change)
    }

    // ---------------------------------------------------------------- end to end, real git

    @Test
    fun `finds in a real repository the commit that added the text`() {
        val repo = buildRepository()
        assumeTrue("git is not available", repo != null)
        val directory = repo!!

        write(directory, "a.txt", "hello\n")
        commit(directory, "first commit")
        write(directory, "a.txt", "hello\nthe needle is here\n")
        commit(directory, "add the needle")

        val hits = GitHistorySearch(directory, "needle").search()

        assertEquals(1, hits.size)
        assertEquals("add the needle", hits[0].subject)
        assertEquals(1, hits[0].files.size)
        assertEquals("a.txt", hits[0].files[0].path)
        assertEquals(ChangeKind.ADDED, hits[0].files[0].change)
    }

    @Test
    fun `finds in a real repository the commit that removed the text`() {
        val repo = buildRepository()
        assumeTrue("git is not available", repo != null)
        val directory = repo!!

        write(directory, "a.txt", "the needle is here\n")
        commit(directory, "add the needle")
        write(directory, "a.txt", "nothing here\n")
        commit(directory, "remove the needle")

        val hits = GitHistorySearch(directory, "needle").search()

        assertEquals(2, hits.size)
        val removal = hits.first { it.subject == "remove the needle" }
        assertEquals(ChangeKind.REMOVED, removal.files[0].change)
    }

    @Test
    fun `is case sensitive when asked`() {
        val repo = buildRepository()
        assumeTrue("git is not available", repo != null)
        val directory = repo!!

        write(directory, "a.txt", "The Needle\n")
        commit(directory, "add the Needle")

        // "The Needle" in the file, "needle" in the query.
        assertEquals(1, GitHistorySearch(directory, "needle").search().size)
        assertEquals(0, GitHistorySearch(directory, "needle", ignoreCase = false).search().size)
    }

    @Test
    fun `regular expression mode matches with -G`() {
        val repo = buildRepository()
        assumeTrue("git is not available", repo != null)
        val directory = repo!!

        write(directory, "a.txt", "value = 42\n")
        commit(directory, "add a value")
        write(directory, "a.txt", "value = 43\n")
        commit(directory, "change the value")

        val hits = GitHistorySearch(directory, "value = 4[0-9]", regex = true).search()

        assertEquals(2, hits.size)
        assertEquals("value = 43", hits[0].files[0].sample)
    }

    @Test
    fun `regular expression mode ignores the case when asked`() {
        val repo = buildRepository()
        assumeTrue("git is not available", repo != null)
        val directory = repo!!

        write(directory, "a.txt", "The Needle\n")
        commit(directory, "add the Needle")

        // The Java filter is case insensitive, git's pickaxe (-G) is not: without
        // --regexp-ignore-case the commit that adds "Needle" is missed.
        val hits = GitHistorySearch(directory, "needle", regex = true).search()

        assertEquals(1, hits.size)
        assertEquals("add the Needle", hits[0].subject)
        assertEquals(ChangeKind.ADDED, hits[0].files[0].change)

        // ... but only when the case is meant to be ignored.
        assertEquals(
            0,
            GitHistorySearch(directory, "needle", regex = true, ignoreCase = false).search().size
        )
    }

    @Test
    fun `a text that never appears returns no commit`() {
        val repo = buildRepository()
        assumeTrue("git is not available", repo != null)
        val directory = repo!!

        write(directory, "a.txt", "hello\n")
        commit(directory, "first commit")

        assertTrue(GitHistorySearch(directory, "no-such-text-anywhere").search().isEmpty())
    }

    @Test
    fun `a repository without any commit does not crash the search`() {
        val repo = buildRepository()
        assumeTrue("git is not available", repo != null)
        val directory = repo!!

        // A fresh project: the repository exists but has no commit yet.
        // `git log` then fails (exit code 128, "your current branch does not have any
        // commits yet"): the search must report "nothing found", not blow up in the IDE.
        write(directory, "a.txt", "hello\n")

        val hits = GitHistorySearch(directory, "needle").search()

        assertTrue(
            "a repository without any commit has no diff to search, the result must be empty",
            hits.isEmpty()
        )
    }

    @Test
    fun `the search can be cancelled`() {
        val repo = buildRepository()
        assumeTrue("git is not available", repo != null)
        val directory = repo!!

        write(directory, "a.txt", "the needle is here\n")
        commit(directory, "add the needle")

        var calls = 0
        try {
            GitHistorySearch(directory, "needle").search {
                calls++
                true
            }
            throw AssertionError("the search should have been cancelled")
        } catch (e: SearchCancelledException) {
            assertTrue(calls > 0)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun buildRepository(): File? {
        val git = GitLocator.find() ?: return null
        val directory = File(System.getProperty("java.io.tmpdir"), "pickaxe-test-" + System.nanoTime())
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

    private fun write(directory: File, name: String, content: String) {
        File(directory, name).writeText(content)
    }

    private fun commit(directory: File, message: String) {
        git(directory, "add", "-A")
        git(directory, "commit", "-m", message)
    }
}
