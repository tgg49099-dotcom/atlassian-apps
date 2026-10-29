package com.tgglabs.xlsxdiff.ui

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule of the "Compare XLSX Files" action:
 * it is **only** visible when exactly two `.xlsx` files are selected.
 *
 * This is the test that proves the action is usable: an action that is always
 * visible, or that is visible with one file only, sends the user to a dead
 * end (there is nothing to compare, or the IDE asks for a second file that
 * cannot be chosen).
 */
class CompareWithActionVisibilityTest {

    @Test
    fun `two spreadsheets are selected - the action is visible`() {
        assertTrue(
            visible("budget-2026.xlsx", "budget-2027.xlsx"),
            "with two .xlsx files selected, the action must be visible",
        )
    }

    @Test
    fun `one spreadsheet is selected - the action is hidden`() {
        assertFalse(
            visible("budget-2026.xlsx"),
            "with one file only, there is nothing to compare: the action must be hidden",
        )
    }

    @Test
    fun `three spreadsheets are selected - the action is hidden`() {
        assertFalse(
            visible("a.xlsx", "b.xlsx", "c.xlsx"),
            "the plugin compares two files: with three of them the action must be hidden",
        )
    }

    @Test
    fun `two files but one is not a spreadsheet - the action is hidden`() {
        assertFalse(
            visible("a.xlsx", "notes.txt"),
            "only two .xlsx files can be compared",
        )
    }

    @Test
    fun `nothing is selected - the action is hidden`() {
        assertFalse(visible(), "without a selection the action must be hidden")
    }

    /**
     * Asks the action what it would do with this selection, exactly as the
     * Project view asks it when the menu is opened.
     */
    private fun visible(vararg names: String): Boolean {
        val files: List<VirtualFile> = names.map { LightVirtualFile(it) }
        return CompareWithAction().isVisibleFor(files)
    }
}
