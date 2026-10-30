package com.tgglabs.commithistorysearch.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The wiring of the window: what the dialog does with the model.
 *
 * The dialog cannot be instantiated in a unit test (it needs the IDE), so these checks
 * read its source. They are the cheapest way to prove that the window
 *  - reads the before side of a diff with the path of the file in the parent commit,
 *  - uses the query as it is (a text that starts with spaces must be findable),
 *  - SHOWS the warning about the POSIX ERE to the user.
 */
class DialogWiringTest {

    @Test
    fun `the window reads the before side with the path of the file in the parent commit`() {
        val source = dialogSource().readText()

        assertTrue(
            "the window must read the before side with row.file.beforePath: the old path of " +
                "a renamed file (otherwise the before side of the diff opens empty)",
            source.contains("row.file.beforePath")
        )
    }

    @Test
    fun `the window does not trim the query`() {
        val source = dialogSource().readText()

        assertTrue(
            "the query must be used as it is: a text that starts with spaces (an " +
                "indentation) must be findable",
            source.contains("val query = queryField.text")
        )
        assertFalse(
            "the query must not be trimmed: 'val query = queryField.text.trim()' is still there",
            source.contains("val query = queryField.text.trim()")
        )
    }

    @Test
    fun `the window shows the warning about the POSIX ERE`() {
        val source = dialogSource().readText()

        assertTrue(
            "the window must compute the warning with PosixEre.warnings",
            source.contains("PosixEre.warnings")
        )
        assertTrue(
            "the warning must be a label of the window",
            source.contains("regexWarning")
        )
        assertTrue(
            "the warning label must be added to the panel of the window",
            source.contains("top.add(regexWarning")
        )
    }

    @Test
    fun `the checkbox of the regular expression mode says POSIX ERE`() {
        val source = dialogSource().readText()

        assertTrue(
            "the checkbox must use the label that says POSIX ERE and [0-9] and not \\d",
            source.contains("PosixEre.REGEX_LABEL")
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun dialogSource(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidate = directory.resolve(
                "src/main/kotlin/com/tgglabs/commithistorysearch/ui/HistorySearchDialog.kt"
            )
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        throw AssertionError("HistorySearchDialog.kt not found")
    }
}
