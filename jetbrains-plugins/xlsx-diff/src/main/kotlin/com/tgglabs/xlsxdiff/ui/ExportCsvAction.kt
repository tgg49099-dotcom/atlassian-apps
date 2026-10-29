package com.tgglabs.xlsxdiff.ui

import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.tgglabs.xlsxdiff.core.DiffExporter
import java.nio.file.Path

/**
 * "Export CSV…" — saves the last comparison as a CSV file.
 *
 * The file is written by [DiffExporter]: UTF-8 **with** a byte order mark,
 * the separator of the CSV standard, and an apostrophe in front of every
 * value that Excel would otherwise read as a formula.
 *
 * The action shows itself only when there is something to save: before the
 * first comparison there is no result, and offering to export it would lead
 * to a dead end.
 */
class ExportCsvAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val session = project?.let { it.service<DiffSession>() }
        e.presentation.isEnabledAndVisible = isVisibleFor(
            hasResult = session?.result != null,
            contextFile = e.getData(CommonDataKeys.VIRTUAL_FILE),
            report = session?.report,
            inProjectView = e.place == ActionPlaces.PROJECT_VIEW_POPUP,
        )
    }

    /**
     * The rule of the action, in one place, so that it can be tested.
     *
     *  - there must be something to export ([hasResult]);
     *  - in the editor, the file in the context must be the report the plugin
     *    opened;
     *  - in the Project view the context always holds the file that was
     *    right-clicked (never null), so the action is shown there whatever
     *    the file ([inProjectView]), as promised by the description.
     */
    internal fun isVisibleFor(
        hasResult: Boolean,
        contextFile: VirtualFile?,
        report: VirtualFile?,
        inProjectView: Boolean = false,
    ): Boolean {
        if (!hasResult) return false
        if (inProjectView) return true
        return contextFile == null || contextFile === report
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val session = project.service<DiffSession>()
        val result = session.result ?: return
        val target = chooseFile(project) ?: return
        try {
            DiffExporter.writeCsv(target, result)
        } catch (e: Exception) {
            Messages.showErrorDialog(
                project,
                "The diff could not be written: ${e.message ?: e.javaClass.simpleName}",
                TITLE,
            )
            return
        }
        val written = target.toAbsolutePath()
        Notifications.show(
            project,
            "Diff exported",
            "The differences were written to:\n$written",
        )
    }

    /**
     * Asks the user where the CSV must be written.
     *
     * The dialog is the standard one of the IDE (a `FileSaverDialog`, opened
     * by the `FileChooserFactory`): the user chooses the folder and the name
     * of the file in the window it knows.
     */
    private fun chooseFile(project: Project): Path? {
        // 27/10/2026 : le Plugin Verifier signale cette construction comme
        // "deprecated" sur IntelliJ 2026.2 (IC-262). C'est VOLONTAIRE : le seul
        // constructeur public de `FileSaverDescriptor` dans la version de
        // compilation du projet (IC-243, la plus ancienne que le plugin
        // supporte) est `FileSaverDescriptor(String, String, String...)`. Le
        // constructeur non deprecie de 2026.2 (`(String, String, String)`)
        // n'existe pas en 243 : l'utiliser rendrait le plugin incompatible
        // avec la version que nous declarons (`since-build 243`).
        // Mesure : IC-243 -> "Compatible" ; IC-262 -> "Compatible. 1 usage of
        // deprecated API" (l'API deprecated existe toujours et fonctionne).
        // A reviser quand la version minimale du plugin passera a 262.
        val descriptor = FileSaverDescriptor(
            "Export the diff as CSV",
            "Choose where to write the diff file",
            "csv",
        )
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val base = project.basePath?.let { Path.of(it) } ?: Path.of(System.getProperty("user.home"))
        val wrapper = dialog.save(base.resolve(DEFAULT_NAME), "csv") ?: return null
        // `VirtualFileWrapper.getFile()` returns a java.io.File.
        val chosen = wrapper.file.toPath()
        // The dialog can return a name without the extension.
        if (!chosen.fileName.toString().endsWith(".csv", ignoreCase = true)) {
            return chosen.resolveSibling("${chosen.fileName}.csv")
        }
        return chosen
    }

    companion object {
        /** The title of the error dialogs. */
        const val TITLE = "XLSX Diff"

        /** The identifier of the action, as declared in `plugin.xml`. */
        const val ID = "XlsxDiff.ExportCsv"

        /** The name proposed to the user. */
        const val DEFAULT_NAME = "xlsxdiff-diff.csv"
    }
}
