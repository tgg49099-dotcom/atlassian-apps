package com.tgglabs.xlsxdiff.ui

import com.intellij.testFramework.LightVirtualFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule of the "Export CSV…" action: it shows itself only when there is
 * a comparison to save.
 *
 * An action that is always visible leads to a dead end (there is nothing to
 * export before the first comparison), and an action that is never visible
 * makes the export impossible.
 */
class ExportCsvActionTest {

    @Test
    fun `there is a result - the action is visible`() {
        assertTrue(
            ExportCsvAction().isVisibleFor(hasResult = true, contextFile = null, report = null),
            "with a comparison to save, the action must be visible",
        )
    }

    @Test
    fun `there is no result - the action is hidden`() {
        assertFalse(
            ExportCsvAction().isVisibleFor(hasResult = false, contextFile = null, report = null),
            "before the first comparison there is nothing to export: the action must be hidden",
        )
    }

    @Test
    fun `in the editor, the action is visible only on the report of the plugin`() {
        val report = LightVirtualFile("XLSX diff - a.xlsx vs b.xlsx")
        val other = LightVirtualFile("budget.xlsx")

        assertTrue(
            ExportCsvAction().isVisibleFor(hasResult = true, contextFile = report, report = report),
            "on the tab of the report, the action must be visible",
        )
        assertFalse(
            ExportCsvAction().isVisibleFor(hasResult = true, contextFile = other, report = report),
            "on any other file, there is nothing to export: the action must be hidden",
        )
    }

    @Test
    fun `in the Project view, the action is visible on any right-clicked file`() {
        val clicked = LightVirtualFile("budget.xlsx")
        assertTrue(
            ExportCsvAction().isVisibleFor(hasResult = true, contextFile = clicked, report = null, inProjectView = true),
            "the Project view always puts the clicked file in the context: the action must stay visible",
        )
    }

    @Test
    fun `the file proposed to the user has the csv extension`() {
        assertTrue(
            ExportCsvAction.DEFAULT_NAME.endsWith(".csv"),
            "the proposed name must end with .csv : ${ExportCsvAction.DEFAULT_NAME}",
        )
    }
}
