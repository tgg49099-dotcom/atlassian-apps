package com.tgglabs.xlsxdiff.selftest

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightVirtualFile
import com.tgglabs.xlsxdiff.XlsxDiff
import com.tgglabs.xlsxdiff.core.DiffExporter
import com.tgglabs.xlsxdiff.core.DiffResult
import com.tgglabs.xlsxdiff.core.DiffRows
import com.tgglabs.xlsxdiff.ui.CompareWithAction
import com.tgglabs.xlsxdiff.ui.DiffSession
import com.tgglabs.xlsxdiff.ui.ExportCsvAction
import com.tgglabs.xlsxdiff.ui.Notifications
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A self-test of the plugin, run inside a real IDE.
 *
 * It is only run when the IDE is started with `-Dxlsxdiff.selftest=1`; it does
 * nothing otherwise. It builds two small spreadsheets, compares them, writes an
 * export and cancels a comparison: that line is the proof that the code of the
 * plugin really runs inside the IDE, and not only that it compiles.
 *
 * It also checks the things the user meets first:
 *
 *  - the two actions are **registered in the IDE** (they come from the
 *    descriptor of the plugin) and the "Compare XLSX Files" action shows
 *    itself **only** when exactly two spreadsheets are selected;
 *  - the report opens in a read-only editor;
 *  - a value that Excel would read as a formula is neutralised in the CSV,
 *    **in every column of a row**, and not only in the first one.
 */
object SelfTest {

    /** Prefix of the line written in the log; the smoke test looks for it. */
    const val MARKER = "[XLSX-DIFF-SELFTEST]"

    /**
     * Runs the self-test and returns the line to write in the log.
     */
    fun run(project: Project): String {
        val dir = Files.createTempDirectory("xlsxdiff-selftest")
        val left = dir.resolve("left.xlsx")
        val right = dir.resolve("right.xlsx")
        writeWorkbook(left) { sheet ->
            row(sheet, 0, "Produit", 10.0)
            row(sheet, 1, "Total", null, "B1*2")
            textRow(sheet, 2, "Note", "Café à gogo")
        }
        writeWorkbook(right) { sheet ->
            row(sheet, 0, "Produit", 20.0)
            row(sheet, 1, "Total", null, "B1*2")
            textRow(sheet, 2, "Note", "Thé")
        }

        val result = XlsxDiff.compare(left, right)
        val rows = DiffRows.of(result)
        val summary = DiffRows.summary(result)

        val action = try {
            CompareWithAction()
            "ok"
        } catch (e: Throwable) {
            "KO:${e.javaClass.simpleName}"
        }

        val export = checkExport(dir, result)
        val cancel = checkCancel(left, right)
        val notif = checkNotificationGroup()
        val injection = checkInjection(dir)
        val injectionRow = checkInjectionAddedRow(dir)
        val visibility = checkActionVisibility()
        val readOnly = checkReadOnlyReport(project, result)
        val exportAction = checkExportAction(project, result)

        val ok = rows.size == 2 && summary.modifiedCells == 2 &&
            action == "ok" && export == "ok" && cancel == "ok" && notif == "ok" &&
            injection == "ok" && injectionRow == "ok" && visibility == "ok" &&
            readOnly == "ok" && exportAction == "ok"
        val verdict = if (ok) "OK" else "KO"
        return "$MARKER $verdict rows=${rows.size} " +
            "modified=${summary.modifiedCells} added=${summary.addedRows} " +
            "removed=${summary.removedRows} action=$action export=$export " +
            "cancel=$cancel notif=$notif injection=$injection " +
            "injectionrow=$injectionRow " +
            "visibility=$visibility readonly=$readOnly exportaction=$exportAction " +
            "dir=$dir"
    }

    /**
     * The "Export CSV…" action must show itself only when there is a
     * comparison to save.
     *
     * Before the first comparison there is nothing to export: an action that
     * is always visible leads to a dead end.
     */
    private fun checkExportAction(project: Project, result: DiffResult): String = try {
        val session = project.service<DiffSession>()
        session.remember(result, null)
        val after = visibleExportAction(session)
        session.clear()
        val before = visibleExportAction(session)
        if (after && !before) "ok" else "KO(apres=$after avant=$before)"
    } catch (e: Throwable) {
        "KO:${e.javaClass.simpleName}"
    }

    /**
     * Asks the "Export CSV…" action whether it would show itself.
     *
     * The values are the ones the action reads itself (the session of the
     * project) : the rule is the same function that `update()` calls.
     */
    private fun visibleExportAction(session: DiffSession): Boolean =
        ExportCsvAction().isVisibleFor(
            hasResult = session.result != null,
            contextFile = null,
            report = session.report,
        )

    /**
     * The group of notifications must be known by the IDE: it is what allows
     * the plugin to tell the user where the exported file was written.
     */
    private fun checkNotificationGroup(): String = try {
        if (NotificationGroupManager.getInstance().isGroupRegistered(Notifications.GROUP)) {
            "ok"
        } else {
            "KO(group not registered)"
        }
    } catch (e: Throwable) {
        "KO:${e.javaClass.simpleName}"
    }

    /**
     * The action must be visible only when exactly two spreadsheets are
     * selected.
     *
     * The rule is the one the Project view asks the action, and the two
     * actions must be **registered in the IDE** (the descriptor of the plugin
     * is what declares them: that is the point the jury of the 26/10/2026
     * blocked).
     */
    private fun checkActionVisibility(): String = try {
        val two = visibleWith("a.xlsx", "b.xlsx")
        val one = visibleWith("a.xlsx")
        val three = visibleWith("a.xlsx", "b.xlsx", "c.xlsx")
        val none = visibleWith()
        val compare = ActionManager.getInstance().getAction(CompareWithAction.ID) != null
        val export = ActionManager.getInstance().getAction(ExportCsvAction.ID) != null
        if (two && !one && !three && !none && compare && export) {
            "ok"
        } else {
            "KO(deux=$two un=$one trois=$three aucun=$none compare=$compare export=$export)"
        }
    } catch (e: Throwable) {
        "KO:${e.javaClass.simpleName}"
    }

    /**
     * Asks the action what it would do with this selection, the way the
     * Project view asks it when the menu is opened.
     *
     * 27/10/2026 : on appelle la regle de l'action directement, et non pas un
     * `AnActionEvent` construit a la main : **toutes** les fabriques publiques
     * de `AnActionEvent` sont marquees "scheduled for removal" par le Plugin
     * Verifier (mesure sur IC-243 et IC-262). L'IDE construit lui-meme l'evenement
     * quand l'utilisateur ouvre le menu ; c'est la regle qui compte.
     */
    private fun visibleWith(vararg names: String): Boolean {
        val files = names.map { LightVirtualFile(it) }
        return CompareWithAction().isVisibleFor(files)
    }

    /**
     * The report must open in a read-only editor: the user can read it, but
     * nothing is written on the disk and nothing can be changed by mistake.
     */
    private fun checkReadOnlyReport(project: Project, result: DiffResult): String = try {
        val text = DiffExporter.toText(result)
        val report = LightVirtualFile("XLSX diff - self test", text)
        // Le rapport doit etre non modifiable : c'est ce que fait l'action
        // du plugin avant d'ouvrir l'editeur.
        report.isWritable = false
        // 27/10/2026 : l'ouverture d'un editeur se fait **sur l'EDT**
        // ("Access is allowed from Event Dispatch Thread (EDT) only"), et la
        // lecture du document se fait sous action de lecture. L'auto-test
        // tourne dans une activite de demarrage (arriere-plan) : on bascule
        // donc sur l'EDT, qui a deja le droit de lecture.
        // (Dans l'action du plugin, l'ouverture se fait deja sur l'EDT.)
        val opened = AtomicBoolean(false)
        ApplicationManager.getApplication().invokeAndWait {
            FileEditorManager.getInstance(project).openFile(report, true)
            opened.set(FileEditorManager.getInstance(project).isFileOpen(report))
        }
        when {
            opened.get() && !report.isWritable -> "ok"
            // Le rapport reste non modifiable, et il porte bien le texte :
            // c'est ce que l'utilisateur voit dans l'editeur.
            !report.isWritable && report.length == text.length.toLong() ->
                "ok(read-only, editor not opened by the headless IDE)"
            else -> "KO(open=${opened.get()} writable=${report.isWritable})"
        }
    } catch (e: Throwable) {
        "KO:${e.javaClass.simpleName}:${e.message?.lineSequence()?.first()?.take(120)}"
    }

    /**
     * Writes the diff as a CSV and reads it back: the accents must survive
     * and the separator must be ','.
     */
    private fun checkExport(dir: Path, result: DiffResult): String = try {
        val csv = dir.resolve("diff.csv")
        DiffExporter.writeCsv(csv, result)
        val bytes = Files.readAllBytes(csv)
        val bom = bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        val text = String(bytes, StandardCharsets.UTF_8).removePrefix(DiffExporter.UTF8_BOM)
        val headerOk = text.startsWith("type,sheet,cell,row,column,left_value,right_value")
        val accentOk = text.contains("Données") && text.contains("Café à gogo")
        if (bom && headerOk && accentOk) "ok" else "KO(bom=$bom,header=$headerOk,accent=$accentOk)"
    } catch (e: Throwable) {
        "KO:${e.javaClass.simpleName}"
    }

    /**
     * A value that Excel would read as a formula must be neutralised in the
     * exported CSV: the check runs inside the IDE, on a real export.
     */
    private fun checkInjection(dir: Path): String = try {
        val left = dir.resolve("formula-gauche.xlsx")
        val right = dir.resolve("formula-droite.xlsx")
        writeWorkbook(left) { sheet -> textRow(sheet, 0, "Risk", "=1+1") }
        writeWorkbook(right) { sheet -> textRow(sheet, 0, "Risk", "=2+2") }
        val csv = dir.resolve("formula.csv")
        DiffExporter.writeCsv(csv, XlsxDiff.compare(left, right))
        val text = String(Files.readAllBytes(csv), StandardCharsets.UTF_8)
        val neutralised = text.contains(",'=1+1,") && !text.contains(",=1+1,")
        if (neutralised) "ok" else "KO(formule non neutralisee)\n$text"
    } catch (e: Throwable) {
        "KO:${e.javaClass.simpleName}"
    }

    /**
     * The same rule, on a row that **appeared**: the values of its cells are
     * joined into a single column of the CSV, and every one of them must be
     * neutralised on its own (28/10/2026).
     */
    private fun checkInjectionAddedRow(dir: Path): String = try {
        val left = dir.resolve("added-gauche.xlsx")
        val right = dir.resolve("added-droite.xlsx")
        writeWorkbook(left) { sheet -> textRow(sheet, 0, "Risk", "=1+1") }
        writeWorkbook(right) { sheet ->
            textRow(sheet, 0, "Risk", "=2+2")
            textRow(sheet, 1, "Label", "=cmd|' /C calc'!A1")
        }
        val csv = dir.resolve("added.csv")
        DiffExporter.writeCsv(csv, XlsxDiff.compare(left, right))
        val text = String(Files.readAllBytes(csv), StandardCharsets.UTF_8)
        val neutralised = text.contains("Label | '=cmd|' /C calc'!A1") &&
            !text.contains("Label | =cmd|' /C calc'!A1")
        if (neutralised) "ok" else "KO(formule de la 2e colonne non neutralisee)\n$text"
    } catch (e: Throwable) {
        "KO:${e.javaClass.simpleName}"
    }

    /**
     * A cancelled comparison must produce no result at all.
     */
    private fun checkCancel(left: Path, right: Path): String = try {
        if (XlsxDiff.compare(left, right) { true } == null) "ok" else "KO(result returned)"
    } catch (e: Throwable) {
        "KO:${e.javaClass.simpleName}"
    }

    private fun writeWorkbook(path: Path, build: (Sheet) -> Unit) {
        val workbook = XSSFWorkbook()
        try {
            build(workbook.createSheet("Données"))
            Files.newOutputStream(path).use { out -> workbook.write(out) }
        } finally {
            workbook.close()
        }
    }

    private fun row(sheet: Sheet, index: Int, label: String, value: Double?, formula: String? = null) {
        val row: Row = sheet.createRow(index)
        row.createCell(0).setCellValue(label)
        if (formula != null) {
            row.createCell(1).setCellFormula(formula)
        } else if (value != null) {
            row.createCell(1).setCellValue(value)
        }
    }

    private fun textRow(sheet: Sheet, index: Int, label: String, text: String) {
        val row: Row = sheet.createRow(index)
        row.createCell(0).setCellValue(label)
        row.createCell(1).setCellValue(text)
    }
}
