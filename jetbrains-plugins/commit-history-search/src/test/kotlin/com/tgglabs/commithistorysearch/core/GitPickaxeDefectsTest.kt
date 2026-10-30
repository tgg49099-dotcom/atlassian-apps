package com.tgglabs.commithistorysearch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The defects found by the hostile review of 30/10/2026. Each test here FAILS on
 * the code of 29/10 (or of 30/10 08:20 for the last one) and PASSES on the fixed code.
 *
 * A. "Case sensitive" mode was asking git for `git log -S`, whose semantics are "the
 *    NUMBER of occurrences changed": a line that is MODIFIED without changing that
 *    number was missed, while the public text promises the commits where the text was
 *    "added, removed or modified".
 * B. A file whose name is not ASCII: git escapes the path in octal ("caf\303\251.txt")
 *    and the path was never decoded, so the diff of the file opened EMPTY.
 * C. git compiles the pattern of `-G` with POSIX ERE: `\d` is not a class there, and
 *    `--perl-regexp` does not change that. The Java filter of the plugin understands
 *    `\d`, so the user saw "it matches" and got NOTHING at all.
 *
 * These tests build real repositories and run the real git: they measure the behaviour
 * that is actually shipped, not only the parser.
 */
class GitPickaxeDefectsTest {

    /**
     * "caf\u00e9.txt", written with an escape so that this source file stays pure ASCII:
     * the build must not depend on the encoding of the sources.
     */
    private val accentedName = "caf\u00e9.txt"

    @Test
    fun `a modified line is found when the search is case sensitive`() {
        val directory = repository()
        write(directory, "a.txt", "hello foo\n")
        commit(directory, "A: add foo")
        write(directory, "a.txt", "hello foo!\n")
        commit(directory, "B: modify foo")

        // "foo" is there once before and once after the change: the NUMBER of occurrences
        // did not change, so `git log -Sfoo` skips the commit B. The public text promises
        // "added, removed or modified": the commit B must be found.
        val hits = GitHistorySearch(directory, "foo", ignoreCase = false).search()

        assertEquals(
            "the commit that modified the line must be found (hits: ${hits.map { it.subject }})",
            2,
            hits.size
        )
        val modified = hits.first { it.subject == "B: modify foo" }
        assertEquals(
            "the modified line must be found as an added line",
            1,
            modified.files[0].addedMatches
        )
        assertEquals("hello foo!", modified.files[0].sample)
    }

    @Test
    fun `the diff of a file whose name is not ASCII is not empty`() {
        val directory = repository()
        write(directory, accentedName, "hello\n")
        commit(directory, "add the accented file")
        write(directory, accentedName, "hello\nthe needle is here\n")
        commit(directory, "add the needle")

        val hits = GitHistorySearch(directory, "needle").search()

        assertEquals(1, hits.size)
        val path = hits[0].files[0].path
        assertEquals("the path of the file must be decoded", accentedName, path)

        // What the window does to open the diff of the file: the content must be there.
        val content = git(directory, "show", "${hits[0].hash}:$path")
        assertTrue(
            "the diff of the accented file must not be empty (content: $content)",
            content.contains("the needle is here")
        )
    }

    @Test
    fun `a regular expression finds the digits that POSIX ERE does not know`() {
        val directory = repository()
        write(directory, "a.txt", "val = 42\n")
        commit(directory, "add a value")

        // git compiles the pattern of -G with POSIX EXTENDED regular expressions, and
        // `--perl-regexp` does NOT change that (measured on 30/10/2026 on git 2.39.5 and
        // on git 2.43 : `-G'\d+'` finds NOTHING, with or without --perl-regexp).
        // The Java filter of the plugin understands `\d`, so the user who typed `\d+` saw
        // the filter say "it matches" and got no result at all, without any message.
        val hits = GitHistorySearch(directory, "\\d+", regex = true).search()

        assertEquals(
            "the commit that adds 42 must be found by the pattern \\d+ " +
                "(hits: ${hits.map { it.subject }})",
            1,
            hits.size
        )
        assertEquals("add a value", hits[0].subject)
    }

    // ------------------------------------------------------------------ helpers

    private fun repository(): File {
        val git = GitLocator.find()
        assumeTrue("git is not available", git != null)
        val directory = File(System.getProperty("java.io.tmpdir"), "pickaxe-defect-" + System.nanoTime())
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
