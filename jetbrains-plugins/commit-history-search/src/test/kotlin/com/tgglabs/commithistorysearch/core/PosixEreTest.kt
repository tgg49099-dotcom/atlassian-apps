package com.tgglabs.commithistorysearch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The POSIX ERE of git, and the warnings of the window.
 *
 * git compiles the pattern of `-G` with POSIX **extended** regular expressions, and
 * `--perl-regexp` does not change that (measured on 30/10/2026 on git 2.39.5 and on
 * git 2.43 : `-G'\d+'` finds NOTHING, with or without `--perl-regexp`).
 *
 * The plugin must therefore give git a POSIX ERE, and it must warn the user when the
 * pattern uses something POSIX ERE cannot do (a lookahead, a backreference...).
 */
class PosixEreTest {

    // ---------------------------------------------------------------- the conversion

    @Test
    fun `the Java classes are converted to their POSIX equivalent`() {
        assertEquals("[0-9]", PosixEre.toEre("\\d"))
        assertEquals("[0-9]+", PosixEre.toEre("\\d+"))
        assertEquals("[^0-9]", PosixEre.toEre("\\D"))
        assertEquals("[_[:alnum:]]", PosixEre.toEre("\\w"))
        assertEquals("[^_[:alnum:]]", PosixEre.toEre("\\W"))
        assertEquals("[[:space:]]", PosixEre.toEre("\\s"))
        assertEquals("[^[:space:]]", PosixEre.toEre("\\S"))
        assertEquals("val = [0-9]+", PosixEre.toEre("val = \\d+"))
    }

    @Test
    fun `a pattern that POSIX ERE understands is not changed`() {
        assertEquals("value = 4[0-9]", PosixEre.toEre("value = 4[0-9]"))
        assertEquals("(foo|bar)+", PosixEre.toEre("(foo|bar)+"))
        assertEquals("a{2,3}", PosixEre.toEre("a{2,3}"))
        assertEquals("[[:digit:]]+", PosixEre.toEre("[[:digit:]]+"))
        assertEquals("", PosixEre.toEre(""))
    }

    @Test
    fun `a class inside a bracket expression is not converted`() {
        // Inside [...], POSIX reads a backslash as a literal backslash: converting there
        // would change the meaning of the set. Such a pattern is warned about instead.
        assertEquals("[\\d]", PosixEre.toEre("[\\d]"))
        assertEquals("[a-z\\d]", PosixEre.toEre("[a-z\\d]"))
        assertEquals("[0-9]+", PosixEre.toEre("[0-9]+"))
    }

    // ---------------------------------------------------------------- the command line

    @Test
    fun `git is given the POSIX ERE and never the perl option`() {
        val arguments = GitHistorySearch(File("."), "\\d+", regex = true).buildArguments()

        assertFalse(
            "the command line must not contain --perl-regexp: git compiles -G with POSIX " +
                "ERE whatever this option says (arguments: $arguments)",
            arguments.contains("--perl-regexp")
        )
        assertTrue(
            "the command line must contain --extended-regexp (arguments: $arguments)",
            arguments.contains("--extended-regexp")
        )
        val pattern = arguments.first { it.startsWith("-G") }
        assertEquals(
            "git must be given the POSIX ERE, not the Java pattern",
            "-G[0-9]+",
            pattern
        )
    }

    @Test
    fun `a literal search is not changed by the conversion`() {
        // The literal is escaped by literalToRegex, which is already a valid POSIX ERE.
        val arguments = GitHistorySearch(File("."), "a.b", ignoreCase = false).buildArguments()

        assertEquals("-Ga\\.b", arguments.first { it.startsWith("-G") })
    }

    // ---------------------------------------------------------------- the warnings

    @Test
    fun `a lookahead is reported`() {
        val warnings = PosixEre.warnings("foo(?=bar)")

        assertEquals("the lookahead must be reported", 1, warnings.size)
        assertTrue(
            "the warning must say that it is a lookahead (it was '$warnings')",
            warnings[0].contains("lookahead")
        )
    }

    @Test
    fun `a negative lookahead is reported`() {
        assertTrue(
            "the negative lookahead must be reported (${PosixEre.warnings("foo(?!bar)")})",
            PosixEre.warnings("foo(?!bar)").isNotEmpty()
        )
    }

    @Test
    fun `a backreference is reported`() {
        val warnings = PosixEre.warnings("(a)\\1")

        assertEquals("the backreference must be reported", 1, warnings.size)
        assertTrue(
            "the warning must say that it is a backreference (it was '$warnings')",
            warnings[0].contains("backreference")
        )
    }

    @Test
    fun `an inline flag is reported`() {
        // git answers "fatal: invalid regex" on "(?i)VAL" (measured on 30/10/2026).
        val warnings = PosixEre.warnings("(?i)VAL")

        assertTrue(
            "the inline flag must be reported (${PosixEre.warnings("(?i)VAL")})",
            warnings.isNotEmpty()
        )
    }

    @Test
    fun `a class inside a bracket expression is reported`() {
        val warnings = PosixEre.warnings("[\\d]")

        assertTrue(
            "the class inside the bracket expression must be reported ($warnings)",
            warnings.isNotEmpty()
        )
    }

    @Test
    fun `a pattern that POSIX ERE understands is not reported`() {
        assertTrue(
            "a POSIX pattern must not be reported (${PosixEre.warnings("value = 4[0-9]")})",
            PosixEre.warnings("value = 4[0-9]").isEmpty()
        )
        assertTrue(
            "a POSIX pattern must not be reported (${PosixEre.warnings("(foo|bar)+")})",
            PosixEre.warnings("(foo|bar)+").isEmpty()
        )
    }

    // ---------------------------------------------------------------- the label

    @Test
    fun `the label of the checkbox says that the pattern is a POSIX ERE`() {
        assertTrue(
            "the label must say POSIX ERE (it was '${PosixEre.REGEX_LABEL}')",
            PosixEre.REGEX_LABEL.contains("POSIX ERE")
        )
        assertTrue(
            "the label must say that git -G is the reference (it was '${PosixEre.REGEX_LABEL}')",
            PosixEre.REGEX_LABEL.contains("git -G")
        )
        assertTrue(
            "the label must tell the user to write [0-9] (it was '${PosixEre.REGEX_LABEL}')",
            PosixEre.REGEX_LABEL.contains("[0-9]")
        )
        assertTrue(
            "the label must show the Java form that does not work (it was '${PosixEre.REGEX_LABEL}')",
            PosixEre.REGEX_LABEL.contains("\\d")
        )
    }
}
