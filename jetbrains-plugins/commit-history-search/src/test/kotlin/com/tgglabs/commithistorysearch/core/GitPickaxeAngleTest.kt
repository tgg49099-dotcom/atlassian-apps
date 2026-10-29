package com.tgglabs.commithistorysearch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Tests of the angle of the plugin: the IDE natively searches the commits by MESSAGE
 * ("Search Everywhere > Commit by message", and the search field of the Log tab, "Filter by
 * Commit Message or Hashes"). The plugin searches the CONTENT OF THE DIFFS.
 *
 * These tests build real repositories and run the real git, so they measure the behaviour
 * that is actually shipped, not only the parser.
 */
class GitPickaxeAngleTest {

    @Test
    fun `a text that is only in a commit message is not a result`() {
        val directory = repository()
        write(directory, "a.txt", "hello\n")
        commit(directory, "the needle is only in the message")

        val hits = GitHistorySearch(directory, "needle").search()

        assertTrue(
            "the commit message must not be searched, only the diffs (hits: ${hits.map { it.subject }})",
            hits.isEmpty()
        )
    }

    @Test
    fun `a text that is only in a diff is a result even when no message mentions it`() {
        val directory = repository()
        write(directory, "a.txt", "hello\n")
        commit(directory, "first commit")
        write(directory, "a.txt", "hello\nthe needle is here\n")
        commit(directory, "no hint in this message")

        val hits = GitHistorySearch(directory, "needle").search()

        assertEquals(1, hits.size)
        assertEquals("no hint in this message", hits[0].subject)
        assertEquals("a.txt", hits[0].files[0].path)
        assertEquals(ChangeKind.ADDED, hits[0].files[0].change)
    }

    @Test
    fun `the repository root is found from a sub directory`() {
        val directory = repository()
        val deep = File(directory, "src/main/kotlin")
        deep.mkdirs()
        write(deep, "a.txt", "hello\n")
        commit(directory, "first commit")

        val root = GitLocator.findRepositoryRoot(deep)

        assertTrue("the repository root was not found from $deep", root != null)
        assertEquals(directory.canonicalPath, root!!.canonicalPath)
    }

    @Test
    fun `the search is case insensitive by default`() {
        val directory = repository()
        write(directory, "a.txt", "The Needle\n")
        commit(directory, "add the Needle")

        assertEquals(
            "the search must ignore the case by default",
            1,
            GitHistorySearch(directory, "needle").search().size
        )
        assertEquals(
            "the search must be case sensitive when asked",
            0,
            GitHistorySearch(directory, "needle", ignoreCase = false).search().size
        )
    }

    @Test
    fun `a renamed file is reported under its new path`() {
        val directory = repository()
        write(directory, "old.txt", "hello\nworld\n")
        commit(directory, "add the old file")
        git(directory, "mv", "old.txt", "new.txt")
        write(directory, "new.txt", "hello\nworld\nthe needle is here\n")
        commit(directory, "rename the file")

        val hits = GitHistorySearch(directory, "needle").search()

        assertEquals(1, hits.size)
        assertEquals("new.txt", hits[0].files[0].path)
    }

    @Test
    fun `a binary file is not reported`() {
        val directory = repository()
        write(directory, "a.txt", "hello\n")
        commit(directory, "first commit")
        File(directory, "blob.bin").writeBytes(byteArrayOf(0, 1, 2) + "needle".toByteArray() + byteArrayOf(0, 3))
        commit(directory, "add a binary file")

        val hits = GitHistorySearch(directory, "needle").search()

        assertTrue("a binary file is not a text diff, it must not be reported", hits.isEmpty())
    }

    // ------------------------------------------------------------------ helpers

    private fun repository(): File {
        val git = GitLocator.find()
        assumeTrue("git is not available", git != null)
        val directory = File(System.getProperty("java.io.tmpdir"), "pickaxe-angle-" + System.nanoTime())
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

    private fun write(directory: File, name: String, content: String) {
        val file = File(directory, name)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    private fun commit(directory: File, message: String) {
        git(directory, "add", "-A")
        git(directory, "commit", "-m", message)
    }
}
