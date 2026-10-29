package com.tgglabs.commithistorysearch.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The update() of the action looks for the root of the repository ON DISK, so it must not
 * run on the EDT: IntelliJ's own rule is that an action doing a disk access declares
 * `getActionUpdateThread() = ActionUpdateThread.BGT`. Without it the action stays on the
 * OLD_EDT (the default of `AnAction`) and the IDE freezes while the repository is scanned.
 *
 * The counter-proof of the 27/10 replaced this override by the default value: the test
 * below then fails, which proves it is not a test that cannot fail.
 */
class SearchInHistoryActionUpdateThreadTest {

    @Test
    fun `the action updates in the background`() {
        val method = try {
            SearchInHistoryAction::class.java.getDeclaredMethod("getActionUpdateThread")
        } catch (error: NoSuchMethodException) {
            throw AssertionError(
                "SearchInHistoryAction does not override getActionUpdateThread(): the action " +
                    "stays on the EDT and its disk access freezes the IDE", error
            )
        }
        assertTrue(
            "getActionUpdateThread() must be declared by SearchInHistoryAction itself",
            method.declaringClass == SearchInHistoryAction::class.java
        )
        val action = SearchInHistoryAction()
        assertEquals(
            "the update of the action does a disk access: it must run in the background",
            ActionUpdateThread.BGT,
            method.invoke(action)
        )
    }
}
