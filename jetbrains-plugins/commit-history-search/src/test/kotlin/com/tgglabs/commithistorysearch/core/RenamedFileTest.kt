package com.tgglabs.commithistorysearch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The "renamed file" defect, reported on 27/10, on 28/10 and again by the review of
 * 30/10/2026 (point 2) : the BEFORE side of the diff of a RENAMED file was empty.
 *
 * The window reads the file before the commit with `git show <hash>^:<path>`. With the
 * CURRENT path of the file, git fails (the new path does not exist in the parent commit)
 * and the "before" side of the diff opens EMPTY. The OLD path has to be used.
 *
 * The test builds a REAL repository and runs the REAL git binary : it is not a mock, and
 * it reads the content of the file with git itself.
 *
 * ⚠️ This test COMPILES against the code of 30/10/2026 08:20 : the getter of the path
 * of the file in the parent commit is looked up by reflection. It therefore FAILS on
 * that code with a real assertion message, and not with a compilation error : a test that
 * does not compile proves less than a test that fails.
 */
class RenamedFileTest {

    @Test
    fun `the before side of the diff of a renamed file is not empty`() {
        val directory = repository()
        // Ten lines: git only detects a rename when the file stays similar enough (a one
        // line file that changes is seen as a deletion and an addition, not as a rename).
        val original = (1..10).joinToString("\n") { "line $it" } + "\n"
        write(directory, "old.txt", original)
        commit(directory, "A: add old.txt")
        git(directory, "mv", "old.txt", "renamed.txt")
        write(directory, "renamed.txt", original + "the needle is here\n")
        commit(directory, "B: rename the file and add the needle")

        val hits = GitHistorySearch(directory, "needle").search()

        assertEquals(
            "the commit that renames the file and edits it must be found " +
                "(hits: ${hits.map { it.subject }})",
            1,
            hits.size
        )
        val hit = hits[0]
        val file = hit.files[0]
        assertEquals("the path of the file is the NEW one", "renamed.txt", file.path)

        // The path of the file in the PARENT commit: the OLD one, because it was renamed.
        val beforePath = beforePathOf(file)
        assertEquals(
            "the model must give the path of the file in the parent commit",
            "old.txt",
            beforePath
        )

        // What the window does to open the diff: the content of the file BEFORE the commit.
        val before = git(directory, "show", "${hit.hash}^:$beforePath")
        assertTrue(
            "the before side of the diff must contain the original content, it was '$before'",
            before.contains("line 1") && before.contains("line 10")
        )
        assertTrue(
            "the before side of the diff must NOT contain the line added by the commit",
            !before.contains("the needle is here")
        )

        // ... and with the NEW path (the defect of 27/10), git fails and the side is empty.
        val withTheNewPath = gitOrNull(directory, "show", "${hit.hash}^:${file.path}")
        assertTrue(
            "the test does not test what it thinks: the new path must NOT exist in the " +
                "parent commit, but git returned '$withTheNewPath'",
            withTheNewPath == null || withTheNewPath.isEmpty()
        )
    }

    @Test
    fun `a file that was not renamed keeps its path before the commit`() {
        val directory = repository()
        write(directory, "a.txt", "hello\n")
        commit(directory, "A: add a.txt")
        write(directory, "a.txt", "hello\nthe needle is here\n")
        commit(directory, "B: add the needle")

        val hits = GitHistorySearch(directory, "needle").search()

        assertEquals(1, hits.size)
        val file = hits[0].files[0]
        assertEquals("a.txt", file.path)
        assertEquals(
            "a file that was not renamed is read with its own path",
            "a.txt",
            beforePathOf(file)
        )
        assertNull("a file that was not renamed has no old path", oldPathOf(file))
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The path of the file in the parent commit, as the model gives it.
     *
     * Looked up by reflection so that this test compiles against the code that did not
     * have it yet (see the note at the top of the file).
     */
    private fun beforePathOf(file: FileHit): String {
        val getter = try {
            FileHit::class.java.getMethod("getBeforePath")
        } catch (e: NoSuchMethodException) {
            null
        }
        if (getter == null) {
            throw AssertionError(
                "the model does not tell the path of the file in the parent commit " +
                    "(FileHit has no getBeforePath): the before side of the diff of a RENAMED " +
                    "file opens EMPTY, because 'git show <hash>^:<new path>' fails."
            )
        }
        return getter.invoke(file) as String
    }

    /** The old path of the file, or null when it was not renamed. */
    private fun oldPathOf(file: FileHit): String? {
        val getter = try {
            FileHit::class.java.getMethod("getOldPath")
        } catch (e: NoSuchMethodException) {
            null
        } ?: return null
        return getter.invoke(file) as String?
    }

    private fun repository(): File {
        val git = GitLocator.find()
        assumeTrue("git is not available", git != null)
        val directory = File(System.getProperty("java.io.tmpdir"), "renamed-file-" + System.nanoTime())
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

    private fun gitOrNull(directory: File, vararg args: String): String? {
        val git = GitLocator.find()!!
        return GitCommand.runOrNull(git, directory, args.toList())
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
