package com.tgglabs.commithistorysearch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests of the sentence shown under the result table.
 *
 * They exist because the plugin caps the result list at 500 matching commits (git's
 * `--max-count` limits the commits DISPLAYED, not the depth of the scan), and a truncated
 * list that does not say it is truncated is a lie the user cannot see. The public description
 * of the plugin promises "at most the 500 most recent matching commits": the window has to
 * keep that promise visible.
 */
class SearchSummaryTest {

    @Test
    fun `the summary says that nothing was found when there is no row`() {
        assertEquals(
            "No commit diff contains \"needle\".",
            SearchSummary.describe(0, 0, "needle")
        )
    }

    @Test
    fun `the summary counts the rows and the commits`() {
        assertEquals(
            "3 match(es) in 2 commit(s).",
            SearchSummary.describe(3, 2, "needle")
        )
    }

    @Test
    fun `the summary warns that the list is capped when the maximum is reached`() {
        val text = SearchSummary.describe(500, 500, "needle")
        assertTrue(
            "the cap must be named: $text",
            text.contains("Only the 500 most recent matching commits are listed")
        )
        assertTrue(
            "the user must be told that older matches are missing: $text",
            text.contains("older matches, if any, are not shown")
        )
    }

    @Test
    fun `the summary does not warn below the maximum`() {
        val text = SearchSummary.describe(499, 499, "needle")
        assertFalse(
            "the list is not truncated, there is nothing to warn about: $text",
            text.contains("not shown")
        )
    }
}
