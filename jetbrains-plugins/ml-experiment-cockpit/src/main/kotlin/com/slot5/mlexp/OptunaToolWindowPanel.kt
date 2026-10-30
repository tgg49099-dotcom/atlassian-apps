package com.slot5.mlexp

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBFont
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.table.DefaultTableModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Tool window panel for the Optuna tab of the ML Experiment Cockpit v0.0.4.
 *
 * Layout (BorderLayout):
 *   NORTH  : [Refresh] [Status label]
 *   CENTER : JSplitPane
 *             - LEFT  : Tree of Studies (one node per study; expanding the
 *                       node is not implemented — selection drives the
 *                       right-pane render for v0.0.4).
 *             - RIGHT : sub-panel (BorderLayout)
 *                       NORTH  : summary label (study name, direction,
 *                                n_trials, n_complete, best value,
 *                                datetime_start) — HTML monospace.
 *                       CENTER : JSplitPane (vertical)
 *                                  - Top    : JTable of top 5 trials
 *                                             sorted by value
 *                                  - Bottom : HorizontalBarChart canvas
 *                                             showing the distribution of
 *                                             the most-chosen hyperparameter
 *                                             values across trials.
 *
 * Network I/O runs on worker threads ("mlexp-optuna-refresh", "mlexp-optuna-detail"),
 * then we marshal back to the EDT via `invokeLater` to mutate the tree and
 * detail panel.
 *
 * Stylistic note: same hand-rolled `Graphics2D` approach used by
 * [LineChartCanvas] (v0.0.3) — no JFreeChart, no 3rd-party chart library.
 */
class OptunaToolWindowPanel(
    private val project: Project,
    private val toolWindow: ToolWindow
) {

    /** The Swing component the IntelliJ Platform hosts. */
    val rootComponent: JPanel = JPanel(BorderLayout())

    private val refreshButton = JButton("Refresh")
    private val statusLabel = JBLabel("Idle. Click Refresh to load studies.")

    private val summaryLabel = JBLabel("Select a study in the tree to view its summary, top-5 trials, and best-value bar chart.")
    private val trialsColumns: Array<Any> = arrayOf<Any>("#", "value", "state", "started")
    private val trialsTableModel = NonEditableTableModel(trialsColumns)
    private val trialsTable = JTable(trialsTableModel).apply {
        setShowGrid(false)
        intercellSpacing = Dimension(0, 0)
        rowHeight = 20
        font = JBFont.regular()
    }
    private val barChart = HorizontalBarChart()

    private val treeRoot = DefaultMutableTreeNode("(no data)")
    private val treeModel = DefaultTreeModel(treeRoot)
    private val tree: Tree = Tree(treeModel).apply {
        showsRootHandles = true
        isRootVisible = false
    }

    /** Cached studies from the last successful refresh. v0.0.4 keeps a flat list. */
    private var studies: List<StudySummary> = emptyList()

    init {
        // ---- NORTH (refresh + status) ----
        val topBar = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(refreshButton)
            add(statusLabel)
        }
        rootComponent.add(topBar, BorderLayout.NORTH)

        // ---- LEFT (studies tree) ----
        val treeScroll = JBScrollPane(tree).apply {
            preferredSize = Dimension(220, 400)
        }

        // ---- RIGHT (summary / table / chart) ----
        summaryLabel.font = JBFont.regular()
        val summaryScroll = JBScrollPane(summaryLabel).apply {
            preferredSize = Dimension(420, 110)
        }
        val trialsScroll = JBScrollPane(trialsTable).apply {
            preferredSize = Dimension(420, 140)
        }
        val lowerSplit = JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            trialsScroll,
            barChart
        ).apply {
            dividerLocation = 140
        }
        val rightPanel = JPanel(BorderLayout()).apply {
            border = javax.swing.border.EmptyBorder(0, 4, 0, 0)
            add(summaryScroll, BorderLayout.NORTH)
            add(lowerSplit, BorderLayout.CENTER)
        }

        val split = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            treeScroll,
            rightPanel
        ).apply {
            dividerLocation = 220
        }
        rootComponent.add(split, BorderLayout.CENTER)

        refreshButton.addActionListener { refresh() }
        tree.addTreeSelectionListener { onTreeSelection() }
    }

    private fun refresh() {
        val rawUri = MlflowSettingsImpl.getInstance().getOptunaUri().trim()
        val base = rawUri.trimEnd('/').ifEmpty {
            showStatus("Configure the Optuna URI first (Settings → Tools → ML Experiment Cockpit).", true)
            return
        }

        refreshButton.isEnabled = false
        showStatus("Loading studies from $base ...", false)
        Thread({
            try {
                val client = OptunaClient(base)
                val loaded = client.listStudies()
                ApplicationManager.getApplication().invokeLater {
                    studies = loaded
                    populateTree(loaded)
                    showStatus("Loaded ${loaded.size} study(ies) from $base", false)
                    refreshButton.isEnabled = true
                }
            } catch (e: OptunaException) {
                ApplicationManager.getApplication().invokeLater {
                    showStatus("Failed to load studies: ${e.message}", true)
                    refreshButton.isEnabled = true
                    showBalloonError("Optuna failed to load studies: ${e.message}")
                }
            } catch (t: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    showStatus("Unexpected error: ${t.message ?: t.javaClass.simpleName}", true)
                    refreshButton.isEnabled = true
                    showBalloonError("Optuna unexpected error: ${t.message}")
                }
            }
        }, "mlexp-optuna-refresh").start()
    }

    private fun populateTree(rows: List<StudySummary>) {
        treeRoot.removeAllChildren()
        if (rows.isEmpty()) {
            treeRoot.add(DefaultMutableTreeNode("No studies found"))
        } else {
            for (s in rows.sortedBy { it.name }) {
                treeRoot.add(DefaultMutableTreeNode(StudyNode(s)))
            }
        }
        treeModel.reload()
        tree.expandRow(0)
    }

    private fun onTreeSelection() {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
            ?: return
        val userObj = node.userObject
        if (userObj is StudyNode) {
            loadStudyDetail(userObj.summary)
        } else {
            summaryLabel.text = "Select a study in the tree to view its summary."
            trialsTableModel.setDataVector(arrayOf<Array<Any>>(), trialsColumns)
            barChart.setData(emptyList(), "")
        }
    }

    private fun loadStudyDetail(summary: StudySummary) {
        val base = MlflowSettingsImpl.getInstance().getOptunaUri().trimEnd('/')
        summaryLabel.text = "Loading study ${summary.name} ..."
        trialsTableModel.setDataVector(arrayOf<Array<Any>>(), trialsColumns)
        barChart.setData(emptyList(), "")
        Thread({
            try {
                val client = OptunaClient(base)
                val study = client.getStudy(summary.id)
                val topTrials = pickTopTrials(study.trials, study.direction)
                val bar = pickBarChart(study.trials)
                ApplicationManager.getApplication().invokeLater {
                    summaryLabel.text = renderStudySummary(summary, study)
                    renderTopTrialsTable(topTrials)
                    barChart.setData(bar.buckets, bar.paramName)
                    showStatus("Loaded study ${study.name} (${study.nTrials} trial(s), ${study.nComplete} complete).", false)
                }
            } catch (e: OptunaException) {
                ApplicationManager.getApplication().invokeLater {
                    summaryLabel.text = "<html><body>Failed to load study: ${escape(e.message ?: "")}</body></html>"
                    showStatus("Failed to load study ${summary.name}: ${e.message}", true)
                }
            } catch (t: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    summaryLabel.text = "<html><body>Unexpected error: ${escape(t.message ?: t.javaClass.simpleName)}</body></html>"
                    showStatus("Unexpected error: ${t.message ?: t.javaClass.simpleName}", true)
                }
            }
        }, "mlexp-optuna-detail").start()
    }

    /**
     * Sort trials by value depending on the study direction:
     *   - minimize: ascending (best value first)
     *   - maximize: descending
     * Filters out trials with a `null` value (RUNNING / PRUNED / FAIL early),
     * keeps only the top 5.
     */
    private fun pickTopTrials(trials: List<Trial>, direction: String): List<Trial> {
        val valid = trials.filter { it.value != null }
        val sorted = if (direction.equals("maximize", ignoreCase = true))
            valid.sortedByDescending { it.value ?: Double.NEGATIVE_INFINITY }
        else
            valid.sortedBy { it.value ?: Double.POSITIVE_INFINITY }
        return sorted.take(5)
    }

    private fun renderStudySummary(summary: StudySummary, study: Study): String {
        val sb = StringBuilder()
        sb.append("<html><body style='font-family:monospace'>")
        sb.append("<b>Study</b><br>")
        sb.append("name&nbsp;&nbsp;&nbsp;&nbsp;: ${escape(study.name)}<br>")
        sb.append("id&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;: ${summary.id}<br>")
        sb.append("direction: ${escape(study.direction)}<br>")
        sb.append("n_trials&nbsp;: ${study.nTrials}<br>")
        sb.append("complete&nbsp;: ${study.nComplete}<br>")
        val bv = study.bestValue
        sb.append("best&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;: ${if (bv != null) "%.6g".format(bv) else "n/a"}<br>")
        val started = prettyDatetime(study.datetimeStart)
        sb.append("started&nbsp;&nbsp;: ${escape(started)}")
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun renderTopTrialsTable(trials: List<Trial>) {
        val data: Array<Array<Any>> = trials.map { t ->
            val valueStr = when (t.value) {
                null -> "—"
                else -> "%.6g".format(t.value)
            }
            arrayOf<Any>(
                t.number,
                valueStr,
                t.state,
                prettyDatetime(t.datetimeStart)
            )
        }.toTypedArray()
        trialsTableModel.setDataVector(data, trialsColumns)
        if (trialsTable.columnCount >= 1) trialsTable.columnModel.getColumn(0).preferredWidth = 40
        if (trialsTable.columnCount >= 2) trialsTable.columnModel.getColumn(1).preferredWidth = 110
        if (trialsTable.columnCount >= 3) trialsTable.columnModel.getColumn(2).preferredWidth = 90
    }

    private fun prettyDatetime(raw: String): String {
        if (raw.isBlank()) return "n/a"
        // The dashboard sends ISO-ish timestamps ("2024-09-12 03:14:15" or
        // "2024-09-12T03:14:15.123456"). Accept both shapes — try the first
        // two patterns and return the raw string if neither parses.
        val parsed = runCatching { java.time.LocalDateTime.parse(raw) }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(raw.replace("T", " ").take(19)) }.getOrNull()
        return parsed?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) ?: raw.take(19)
    }

    private fun showStatus(message: String, isError: Boolean) {
        statusLabel.text = message
        statusLabel.foreground = if (isError) Color(0xC0, 0x39, 0x2B) else statusLabel.foreground
    }

    private fun showBalloonError(content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(ConnectToMlflowAction.NOTIFICATION_GROUP_ID)
            .createNotification("ML Experiment Cockpit (Optuna)", content, NotificationType.ERROR)
            .notify(project)
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /**
     * Tree-node payload.
     *
     * v0.0.20 — **the tree of the Optuna tab had no cell renderer**, so Swing
     * printed the generated data-class `toString()`:
     * `StudyNode(summary=StudySummary(id=1, name=demo, direction=minimize))`.
     * `toString()` now returns the readable label, exactly like
     * `MlflowToolWindowPanel.ExperimentNode` / `RunNode` do since 0.0.15 —
     * one single way to display a tree payload across the whole plugin.
     */
    data class StudyNode(val summary: StudySummary) {
        /** e.g. `demo  (minimize)`. */
        val label: String
            get() {
                val shown = summary.name.ifEmpty { "study #${summary.id}" }
                val direction = summary.direction.ifEmpty { "minimize" }
                return "$shown  ($direction)"
            }

        override fun toString(): String = label
    }

    /** Bar-chart payload. */
    data class BarChartData(val buckets: List<BarBucket>, val paramName: String)
    data class BarBucket(val label: String, val count: Int)

    /**
     * Read-only table model so users can't accidentally edit cells. v0.0.4
     * doesn't need sorting or column reordering — keep it dead simple.
     */
    private class NonEditableTableModel(columns: Array<Any>) :
        DefaultTableModel(columns, 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }

    /**
     * Pure helpers of the Optuna tab, kept in a companion object so that the
     * unit tests can call them **without** instantiating a Swing panel.
     */
    companion object {
        /**
         * Pick the most "interesting" hyperparameter to show as a bar chart.
         * Heuristic (v0.0.4 MVP):
         *   1. Prefer the param that takes the most distinct values.
         *   2. Among ties, prefer numeric (int / float) over string.
         *   3. Empty result for studies with no params.
         *
         * v0.0.20 — **the two criteria were applied in the wrong order.**
         * The code sorted by `numeric` FIRST and by `distinct` second, i.e. it
         * did the opposite of what this comment (and the original spec) says: a
         * parameter with a single numeric value (`learning_rate = 0.01`) won over
         * a parameter that took ten different string values (`optimizer`).
         * The sort is now `distinct` first, `numeric` only as the tie-breaker.
         *
         * The function moved to the companion object (and became `internal`)
         * so that the unit test can call it **without** building a Swing panel:
         * it is a pure function of its argument and touches no instance state.
         */
        internal fun pickBarChart(trials: List<Trial>): BarChartData {
            val allParams: Map<String, List<String>> = trials
                .flatMap { it.params.entries }
                .groupBy({ it.key }, { it.value })
            if (allParams.isEmpty()) return BarChartData(emptyList(), "")

            data class Cand(val name: String, val distinct: Int, val numeric: Boolean)
            val candidates = allParams.map { (k, vs) ->
                Cand(
                    name = k,
                    distinct = vs.toSet().size,
                    numeric = vs.all { v -> v.toDoubleOrNull() != null }
                )
            }
            // v0.0.20 — « most distinct values » FIRST, « numeric » only as the
            // tie-breaker (before: the exact opposite).
            val best = candidates
                .sortedWith(compareByDescending<Cand> { it.distinct }.thenByDescending { it.numeric })
                .first()
            val counts: Map<String, Int> = trials
                .mapNotNull { it.params[best.name] }
                .groupingBy { it }
                .eachCount()
            // Keep top 8 to fit the chart height comfortably.
            val buckets = counts.entries
                .sortedByDescending { it.value }
                .take(8)
                .map { BarBucket(label = it.key, count = it.value) }
            return BarChartData(buckets, best.name)
        }
    }
}

/**
 * Horizontal bar chart drawn with `Graphics2D`. v0.0.4.
 *
 * Buckets are sorted by the data source (descending count). The chart
 * auto-scales bar widths against the maximum bucket count. Labels are
 * rendered left of each bar; values at the right edge.
 */
class HorizontalBarChart : JComponent() {

    private var buckets: List<OptunaToolWindowPanel.BarBucket> = emptyList()
    private var title: String = ""
    private val titleFont = Font(Font.SANS_SERIF, Font.BOLD, 12)
    private val labelFont = Font(Font.MONOSPACED, Font.PLAIN, 11)
    private val barColor = Color(0x1E, 0x88, 0xE5)
    private val placeholderColor = Color(0x99, 0x99, 0x99)
    private val labelColor = Color(0x44, 0x44, 0x44)

    fun setData(buckets: List<OptunaToolWindowPanel.BarBucket>, title: String) {
        this.buckets = buckets
        this.title = title
        repaint()
    }

    override fun getPreferredSize(): Dimension = Dimension(380, 180)

    override fun paintComponent(g: java.awt.Graphics) {
        super.paintComponent(g)
        val g2 = g as java.awt.Graphics2D
        g2.setRenderingHint(
            java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
            java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON
        )
        val w = width.toFloat()
        val h = height.toFloat()
        g2.color = Color.WHITE
        g2.fillRect(0, 0, width, height)

        // Title
        g2.font = titleFont
        g2.color = labelColor
        val caption = if (title.isEmpty()) "(no hyperparameters)" else "top values for $title"
        g2.drawString(caption, 12f, 18f)

        if (buckets.isEmpty()) {
            g2.font = labelFont
            g2.color = placeholderColor
            val msg = "no hyperparameters on trials"
            val fm = g2.fontMetrics
            val tw = fm.stringWidth(msg)
            g2.drawString(msg, (width - tw) / 2f, height / 2f)
            return
        }

        val leftPad = 130f
        val rightPad = 38f
        val topPad = 28f
        val bottomPad = 8f
        val plotX0 = leftPad
        val plotW = w - leftPad - rightPad
        val plotY0 = topPad
        val plotH = h - topPad - bottomPad

        val n = buckets.size
        val rowH = plotH / n
        val barInset = rowH * 0.18f
        val maxCount = buckets.maxOf { it.count }

        g2.font = labelFont
        for ((i, b) in buckets.withIndex()) {
            val y0 = plotY0 + i * rowH
            // Label
            g2.color = labelColor
            g2.drawString(b.label.take(16), 8f, y0 + rowH / 2 + 4f)
            // Bar
            val fraction = if (maxCount == 0) 0f else b.count.toFloat() / maxCount
            g2.color = barColor
            g2.fillRect(plotX0.toInt(), (y0 + barInset).toInt(),
                (plotW * fraction).toInt(), (rowH - 2 * barInset).toInt())
            // Value at the right of the bar.
            g2.color = labelColor
            g2.drawString(b.count.toString(),
                (plotX0 + plotW * fraction + 6f).coerceAtMost(w - rightPad + 4f),
                y0 + rowH / 2 + 4f)
        }
    }
}
