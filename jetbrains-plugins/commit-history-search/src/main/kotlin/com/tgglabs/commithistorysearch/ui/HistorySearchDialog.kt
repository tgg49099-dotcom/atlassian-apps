package com.tgglabs.commithistorysearch.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.tgglabs.commithistorysearch.core.CommitHit
import com.tgglabs.commithistorysearch.core.FileHit
import com.tgglabs.commithistorysearch.core.GitCommand
import com.tgglabs.commithistorysearch.core.GitException
import com.tgglabs.commithistorysearch.core.GitHistorySearch
import com.tgglabs.commithistorysearch.core.GitLocator
import com.tgglabs.commithistorysearch.core.SearchCancelledException
import com.tgglabs.commithistorysearch.core.SearchSummary
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.JPanel
import javax.swing.table.AbstractTableModel

data class HistorySearchState(
    val caseSensitive: Boolean = false,
    val regexMode: Boolean = false,
    val query: String = ""
)

object HistorySearchSettings {
    const val KEY_CASE_SENSITIVE = "com.tgglabs.commithistorysearch.caseSensitive"
    const val KEY_REGEX_MODE = "com.tgglabs.commithistorysearch.regexMode"
    const val KEY_LAST_QUERY = "com.tgglabs.commithistorysearch.lastQuery"

    fun load(getBoolean: (String, Boolean) -> Boolean, getValue: (String, String) -> String): HistorySearchState {
        return HistorySearchState(
            caseSensitive = getBoolean(KEY_CASE_SENSITIVE, false),
            regexMode = getBoolean(KEY_REGEX_MODE, false),
            query = getValue(KEY_LAST_QUERY, "")
        )
    }

    fun save(setBoolean: (String, Boolean) -> Unit, setValue: (String, String) -> Unit, state: HistorySearchState) {
        setBoolean(KEY_CASE_SENSITIVE, state.caseSensitive)
        setBoolean(KEY_REGEX_MODE, state.regexMode)
        setValue(KEY_LAST_QUERY, state.query)
    }
}

/**
 * The "Search in Commit History" window: a text field, the options, and the list of the
 * commits whose diffs contain the searched text.
 */
class HistorySearchDialog(private val project: Project) : DialogWrapper(project) {

    private val queryField = JBTextField(28)
    private val caseSensitive = JBCheckBox("Case sensitive")
    private val regexMode = JBCheckBox("Regular expression")
    private val searchButton = JButton("Search")
    private val status = JBLabel("Type a text and press Search.")
    private val model = CommitTableModel()
    private val table = JBTable(model)

    init {
        title = "Search in Commit History"
        setOKButtonText("Close")
        loadSettings()
        searchButton.addActionListener { startSearch() }
        queryField.addActionListener { startSearch() }
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) openSelectedDiff()
            }
        })
        // Enter on a selected row opens the diff, like a double click.
        table.registerKeyboardAction(
            { openSelectedDiff() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT
        )
        init()
    }

    private fun loadSettings() {
        val props = PropertiesComponent.getInstance(project)
        val state = HistorySearchSettings.load(
            getBoolean = { key, def -> props.getBoolean(key, def) },
            getValue = { key, def -> props.getValue(key, def) }
        )
        caseSensitive.isSelected = state.caseSensitive
        regexMode.isSelected = state.regexMode
        queryField.text = state.query
    }

    private fun saveSettings() {
        val props = PropertiesComponent.getInstance(project)
        val state = HistorySearchState(
            caseSensitive = caseSensitive.isSelected,
            regexMode = regexMode.isSelected,
            query = queryField.text
        )
        HistorySearchSettings.save(
            setBoolean = { key, value -> props.setValue(key, value) },
            setValue = { key, value -> props.setValue(key, value) },
            state = state
        )
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(8, 8))
        val input = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        input.add(JBLabel("Find:"))
        input.add(queryField)
        input.add(caseSensitive)
        input.add(regexMode)
        input.add(searchButton)
        panel.add(input, BorderLayout.NORTH)
        panel.add(JBScrollPane(table), BorderLayout.CENTER)
        panel.add(status, BorderLayout.SOUTH)
        panel.preferredSize = Dimension(860, 440)
        return panel
    }

    private fun startSearch() {
        val query = queryField.text.trim()
        if (query.isEmpty()) {
            status.text = "Type the text to search for."
            return
        }
        val root = repositoryRoot()
        if (root == null) {
            status.text = "No Git repository found in this project."
            return
        }
        saveSettings()
        setInputEnabled(false)
        status.text = "Searching the commit history of ${root.name}..."
        val regex = regexMode.isSelected
        val ignoreCase = !caseSensitive.isSelected
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Search in commit history", true) {
            override fun run(indicator: ProgressIndicator) {
                var hits: List<CommitHit> = emptyList()
                var failure: String? = null
                try {
                    hits = GitHistorySearch(root, query, regex, ignoreCase).search { indicator.isCanceled }
                } catch (e: SearchCancelledException) {
                    failure = "Search cancelled."
                } catch (e: GitException) {
                    failure = e.message ?: "git failed."
                } catch (e: Exception) {
                    failure = e.message ?: e.toString()
                }
                val found = hits
                val error = failure
                ApplicationManager.getApplication().invokeLater({
                    setInputEnabled(true)
                    if (error != null) {
                        status.text = error
                        return@invokeLater
                    }
                    val rows = found.flatMap { commit -> commit.files.map { Row(commit, it) } }
                    model.setRows(rows)
                    status.text = SearchSummary.describe(rows.size, found.size, query)
                }, ModalityState.stateForComponent(table))
            }
        })
    }

    private fun openSelectedDiff() {
        val row = model.rowAt(table.selectedRow) ?: return
        val path = row.file.path
        if (path.isEmpty()) {
            status.text = "No file diff available for this commit entry."
            return
        }
        val root = repositoryRoot() ?: return
        val git = GitLocator.find()
        if (git == null) {
            status.text = "git executable not found."
            return
        }
        status.text = "Loading the diff of $path..."
        val hash = row.commit.hash
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Load diff", false) {
            override fun run(indicator: ProgressIndicator) {
                val before = GitCommand.runOrNull(git, root, listOf("show", "$hash^:$path")) ?: ""
                val after = GitCommand.runOrNull(git, root, listOf("show", "$hash:$path")) ?: ""
                val oldText = before
                val newText = after
                ApplicationManager.getApplication().invokeLater({
                    if (oldText.contains('\u0000') || newText.contains('\u0000')) {
                        status.text = "Binary file: no text diff available."
                        return@invokeLater
                    }
                    DiffOpener.show(
                        project,
                        "${row.commit.shortHash} - $path",
                        oldText,
                        newText
                    )
                    status.text = "Diff of $path opened."
                }, ModalityState.stateForComponent(table))
            }
        })
    }

    private fun setInputEnabled(enabled: Boolean) {
        queryField.isEnabled = enabled
        caseSensitive.isEnabled = enabled
        regexMode.isEnabled = enabled
        searchButton.isEnabled = enabled
    }

    /** The root of the Git repository the project belongs to (not the first content root). */
    private fun repositoryRoot(): File? = ProjectRepository.root(project)

    /** One line of the result table: a commit and one of its matching files. */
    data class Row(val commit: CommitHit, val file: FileHit)

    private class CommitTableModel : AbstractTableModel() {

        private val columns = arrayOf("Commit", "Author", "Date", "Change", "File", "Match", "Message")
        private var rows: List<Row> = emptyList()

        fun setRows(newRows: List<Row>) {
            rows = newRows
            fireTableDataChanged()
        }

        fun rowAt(index: Int): Row? = rows.getOrNull(index)

        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = columns.size

        override fun getColumnName(column: Int): String = columns[column]

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val row = rows[rowIndex]
            return when (columnIndex) {
                0 -> row.commit.shortHash
                1 -> row.commit.author
                2 -> row.commit.date
                3 -> row.file.change.label
                4 -> row.file.path
                5 -> row.file.sample ?: ""
                6 -> row.commit.subject
                else -> ""
            }
        }
    }
}
