package com.tgglabs.xlsxdiff.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.tgglabs.xlsxdiff.XlsxDiff
import com.tgglabs.xlsxdiff.core.DiffExporter
import com.tgglabs.xlsxdiff.core.DiffResult
import com.tgglabs.xlsxdiff.core.XlsxDiffException
import java.nio.file.Path

/**
 * "Compare XLSX Files" — the entry point of the plugin.
 *
 * The user selects **two** `.xlsx` files in the Project view and right-clicks
 * one of them: the action compares them cell by cell. This is the same gesture
 * as the "Compare With…" of the IDE itself, which also asks for two files.
 *
 * The action is **only** visible when exactly two spreadsheets are selected:
 * with one file there is nothing to compare, and with three the plugin would
 * have to guess which two the user means.
 */
class CompareWithAction : AnAction() {

    /**
     * The rule of the action is read in a background thread: it only looks at
     * the names of the selected files, and it must never slow the opening of
     * the menu.
     */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = isVisibleFor(selectedFiles(e))
    }

    /**
     * The rule of the action, in one place, so that it can be tested.
     *
     * Exactly two `.xlsx` files, and nothing else.
     */
    internal fun isVisibleFor(files: List<VirtualFile>): Boolean =
        files.size == 2 && files.all { isSpreadsheet(it) }

    /**
     * The files selected in the Project view.
     *
     * The array of the selected files is the same one the IDE's own
     * "Compare With…" uses. When the menu is opened on a single file, the
     * array holds that file only.
     */
    private fun selectedFiles(e: AnActionEvent): List<VirtualFile> {
        val array = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        if (array != null) return array.toList()
        val single = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return emptyList()
        return listOf(single)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = selectedFiles(e)
        if (!isVisibleFor(files)) {
            Messages.showErrorDialog(
                project,
                "Select exactly two .xlsx files to compare them.",
                TITLE,
            )
            return
        }
        // toNioPath() throws for a file that is not on the local disk (inside a
        // JAR/ZIP, remote file system...): say so instead of crashing the IDE.
        val paths = localPaths(files)
        if (paths == null) {
            Messages.showErrorDialog(project, NOT_ON_LOCAL_DISK, TITLE)
            return
        }
        compare(project, paths[0], paths[1], files[0].name, files[1].name)
    }

    /**
     * The files as paths on the local disk, or null when one of them is not
     * on the local disk (inside a JAR or a ZIP, on a remote file system...).
     *
     * The rule is in one place, so that it can be tested: `toNioPath()` throws
     * an `UnsupportedOperationException` for such a file, and that exception
     * must never reach the IDE.
     */
    internal fun localPaths(files: List<VirtualFile>): List<Path>? {
        val paths = ArrayList<Path>(files.size)
        for (file in files) {
            val path = try {
                file.toNioPath()
            } catch (ex: UnsupportedOperationException) {
                return null
            }
            paths.add(path)
        }
        return paths
    }

    /**
     * Runs the comparison off the UI thread and shows the result.
     *
     * The task is cancellable: the progress bar shows a "Cancel" button and
     * the comparison stops at once when it is pressed. In that case **nothing**
     * is shown: no report, no table, no error dialog.
     */
    private fun compare(
        project: Project,
        left: Path,
        right: Path,
        leftName: String,
        rightName: String,
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Comparing spreadsheets…", true) {
            override fun run(indicator: ProgressIndicator) {
                val result = try {
                    // The core asks the indicator from time to time whether it
                    // should stop, and returns null when it must.
                    XlsxDiff.compare(left, right) { indicator.isCanceled() }
                } catch (e: XlsxDiffException) {
                    showError(project, e.message ?: "The two files could not be compared.")
                    return
                } catch (e: ProcessCanceledException) {
                    // The platform handles this one: it is not an error.
                    throw e
                } catch (e: Throwable) {
                    showError(project, "Unexpected error while comparing the files: ${e.message ?: e.javaClass.simpleName}")
                    return
                }
                // The user pressed "Cancel": there is no result to show.
                if (result == null) return
                ApplicationManager.getApplication().invokeLater {
                    showResult(project, result, leftName, rightName)
                }
            }
        })
    }

    /**
     * Shows the differences of [result].
     *
     * The report is opened in a **read-only editor** (a virtual file: it is
     * never written on the disk, and it cannot be edited). The result is also
     * kept in the [DiffSession] of the project, so that the "Export CSV…"
     * action can save it without comparing the two files a second time.
     */
    private fun showResult(
        project: Project,
        result: DiffResult,
        leftName: String,
        rightName: String,
    ) {
        val report = openReadOnlyReport(project, result, leftName, rightName)
        project.service<DiffSession>().remember(result, report)
    }

    /**
     * Opens the report in a read-only editor.
     *
     * The report is a virtual file: the user can read it, search in it and
     * copy from it, but nothing is written on the disk and nothing can be
     * changed by mistake.
     *
     * @return the file opened in the editor, or null when it could not be
     *         opened.
     */
    private fun openReadOnlyReport(
        project: Project,
        result: DiffResult,
        leftName: String,
        rightName: String,
    ): VirtualFile? {
        return try {
            val report = LightVirtualFile("XLSX diff - $leftName vs $rightName", DiffExporter.toText(result))
            // 27/10/2026 : un LightVirtualFile est modifiable par defaut. Sans
            // cette ligne, l'utilisateur pourrait modifier le rapport dans
            // l'editeur et l'enregistrer -> le rapport ne serait plus
            // "ouvert en lecture seule". `setWritable(false)` rend le fichier
            // non modifiable : l'editeur refuse toute ecriture.
            report.isWritable = false
            FileEditorManager.getInstance(project).openFile(report, true)
            report
        } catch (e: Throwable) {
            // The differences are still in the result: the "Export CSV…"
            // action can save them. A failure to open the editor must not
            // hide them.
            null
        }
    }

    private fun showError(project: Project, message: String) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showErrorDialog(project, message, TITLE)
        }
    }

    companion object {
        private const val TITLE = "XLSX Diff"

        /**
         * The message shown when one of the two files is not on the local
         * disk: the plugin can only read a file that has a path on the disk.
         */
        const val NOT_ON_LOCAL_DISK = "Only .xlsx files on the local disk can be compared."

        /** The identifier of the action, as declared in `plugin.xml`. */
        const val ID = "XlsxDiff.CompareWith"

        /** True when the file is a spreadsheet this plugin can read. */
        fun isSpreadsheet(file: VirtualFile): Boolean =
            !file.isDirectory && file.extension.equals("xlsx", ignoreCase = true)
    }
}
