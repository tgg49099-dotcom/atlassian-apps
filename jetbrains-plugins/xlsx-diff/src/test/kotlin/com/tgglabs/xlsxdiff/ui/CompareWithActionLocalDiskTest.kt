package com.tgglabs.xlsxdiff.ui

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionPopupMenu
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.TimerListener
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ActionCallback
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import java.awt.Component
import java.awt.event.InputEvent
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A `.xlsx` that is **not on the local disk** (inside a JAR or a ZIP, on a
 * remote file system...) must not crash the IDE: the plugin has to say that it
 * cannot compare it.
 *
 * The test of this file is the RED→GREEN proof of the fix of the jury
 * (`20261027-1021-slot2-xlsx-diff-plugin-ebf7`): **the very same file** fails
 * on the code before the fix (the `UnsupportedOperationException` of
 * `toNioPath()` escapes the action) and passes after it.
 */
class CompareWithActionLocalDiskTest {

    @Test
    fun `a spreadsheet outside the local disk does not crash the IDE`() {
        val notOnDisk = LightVirtualFile("data.xlsx")
        val other = LightVirtualFile("other.xlsx")

        // The fixture must be a file that is not on the local disk, otherwise
        // the test would prove nothing.
        assertTrue(
            runCatching { notOnDisk.toNioPath() }.exceptionOrNull() is UnsupportedOperationException,
            "the fixture must be a file outside the local disk",
        )

        val action = CompareWithAction()
        val event = eventOf(notOnDisk, other)
        var thrown: Throwable? = null
        val thread = Thread { thrown = runCatching { action.actionPerformed(event) }.exceptionOrNull() }
        thread.start()
        thread.join(20_000)

        assertFalse(thread.isAlive, "the action must return, not block")
        assertFalse(
            thrown is UnsupportedOperationException,
            "the plugin must not crash on a .xlsx that is not on the local disk: it must say so instead",
        )
    }

    /**
     * An event that holds the two selected files and a project.
     *
     * The event is built by hand: the factories of the platform ask the IDE
     * for its services, and there is no IDE in a unit test.
     */
    private fun eventOf(vararg files: VirtualFile): AnActionEvent {
        val dataContext = MapDataContext(
            mapOf(
                CommonDataKeys.PROJECT.name to stubProject(),
                CommonDataKeys.VIRTUAL_FILE_ARRAY.name to files,
            ),
        )
        return AnActionEvent(
            dataContext,
            Presentation(),
            ActionPlaces.PROJECT_VIEW_POPUP,
            ActionUiKind.NONE,
            null,
            0,
            NoActionManager,
        )
    }

    /**
     * A context that only holds what the action reads.
     *
     * The one of the platform (`SimpleDataContext`) asks the IDE for its
     * services, and there is no IDE in a unit test.
     */
    private class MapDataContext(private val values: Map<String, Any?>) : DataContext {
        override fun getData(dataId: String): Any? = values[dataId]
    }

    /** A project that does nothing: the action only needs it to be there. */
    private fun stubProject(): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "isDisposed" -> false
            "toString" -> "stub-project"
            "hashCode" -> 0
            "equals" -> false
            else -> null
        }
    } as Project

    /**
     * The action manager of the platform, replaced by a stub: the action
     * never asks it anything, but the event of the platform insists on
     * having one.
     */
    private object NoActionManager : ActionManager() {
        private fun unused(): Nothing = throw UnsupportedOperationException("not used in a unit test")

        override fun createActionPopupMenu(place: String, group: ActionGroup): ActionPopupMenu = unused()
        override fun createActionToolbar(place: String, group: ActionGroup, horizontal: Boolean): ActionToolbar = unused()
        override fun getAction(id: String): AnAction = unused()
        override fun getId(action: AnAction): String = unused()
        override fun registerAction(id: String, action: AnAction) = Unit
        override fun registerAction(id: String, action: AnAction, pluginId: PluginId?) = Unit
        override fun unregisterAction(id: String) = Unit
        override fun replaceAction(id: String, action: AnAction) = Unit
        override fun getActionIds(text: String): Array<String> = unused()
        override fun getActionIdList(text: String): MutableList<String> = unused()
        override fun isGroup(id: String): Boolean = unused()
        override fun getActionOrStub(id: String): AnAction = unused()
        override fun addTimerListener(listener: TimerListener) = Unit
        override fun removeTimerListener(listener: TimerListener) = Unit
        override fun tryToExecute(
            action: AnAction,
            event: InputEvent?,
            component: Component?,
            place: String?,
            now: Boolean,
        ): ActionCallback = unused()

        override fun addAnActionListener(listener: AnActionListener) = Unit
        override fun getKeyboardShortcut(actionId: String): KeyboardShortcut = unused()
    }
}
