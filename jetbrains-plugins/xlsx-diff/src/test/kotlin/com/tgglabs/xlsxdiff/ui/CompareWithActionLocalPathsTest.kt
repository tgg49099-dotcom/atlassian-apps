package com.tgglabs.xlsxdiff.ui

import com.intellij.testFramework.LightVirtualFile
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rule of the action for a file that is not on the local disk: the
 * comparison is refused, with a message that says why.
 *
 * This is the pure side of the fix of the jury
 * (`20261027-1021-slot2-xlsx-diff-plugin-ebf7`): the same rule, tested
 * without the IDE.
 */
class CompareWithActionLocalPathsTest {

    @Test
    fun `a file outside the local disk has no path`() {
        val notOnDisk = LightVirtualFile("data.xlsx")
        assertNull(
            CompareWithAction().localPaths(listOf(notOnDisk, LightVirtualFile("other.xlsx"))),
            "a file outside the local disk must be refused, not crash the IDE",
        )
    }

    @Test
    fun `the message says what the user has to do`() {
        val message = CompareWithAction.NOT_ON_LOCAL_DISK
        assertTrue(
            message.contains("local disk"),
            "the message must say that the file has to be on the local disk: $message",
        )
    }
}
