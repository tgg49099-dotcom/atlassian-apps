package com.slot5.mlexp

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Tool window panel for the ML Experiment Cockpit v0.0.3.
 *
 * Layout (BorderLayout):
 *   NORTH  : [Refresh] [Status label]
 *   CENTER : JSplitPane
 *             - LEFT  : Tree of Experiments / Runs
 *             - RIGHT : A sub-panel (BorderLayout) with the run detail on
 *                       top and a metrics time-series chart on the bottom.
 *
 * The tree is a 2-level structure:
 *   root
 *   ├── Experiment 1 (id)
 *   │   ├── Run 1 (status)
 *   │   └── Run 2 (status)
 *   └── Experiment 2 (id)
 *       └── ...
 *
 * Network I/O runs on worker threads, then we marshal back to the EDT via
 * `invokeLater` to mutate the tree.
 */
class MlflowToolWindowPanel(
    private val project: Project,
    private val toolWindow: ToolWindow
) {

    /** The Swing component the IntelliJ Platform hosts. */
    val rootComponent: JPanel = JPanel(BorderLayout())

    private val refreshButton = JButton("Refresh")
    private val statusLabel = JBLabel("Idle. Click Refresh to load experiments.")
    private val detailArea = JBLabel("Select a run in the tree to view params and metrics.")

    private val treeRoot = DefaultMutableTreeNode("(no data)")
    private val treeModel = DefaultTreeModel(treeRoot)
    private val tree: Tree = Tree(treeModel).apply {
        showsRootHandles = true
        isRootVisible = false
        // 0.0.15: without a renderer, Swing printed the raw `toString()` of the
        // payload object ("ExperimentNode(exp=Experiment(id=2, …))"), which is
        // both unreadable and truncated by the column width.
        setCellRenderer(NodeTreeCellRenderer())
    }

    /**
     * The right side of the split. Its top is the run detail (params +
     * latest metric values), its bottom is the time-series chart for the
     * currently selected metric. We rebuild the chart child each time the
     * user picks a different run, because it owns the runId and the list of
     * metrics for that run.
     */
    private val rightPanel = JPanel(BorderLayout()).apply {
        border = javax.swing.border.EmptyBorder(0, 4, 0, 0)
    }

    init {
        // ---- NORTH (refresh + status) ----
        val topBar = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(refreshButton)
            add(statusLabel)
        }
        rootComponent.add(topBar, BorderLayout.NORTH)

        // ---- CENTER (tree | right) ----
        val treeScroll = JBScrollPane(tree).apply {
            preferredSize = Dimension(300, 400)
        }
        val detailScroll = JBScrollPane(detailArea).apply {
            preferredSize = Dimension(380, 160)
        }
        rightPanel.add(detailScroll, BorderLayout.NORTH)
        // Bottom slot starts empty — populated when a run is selected.
        val chartHolder = JPanel(BorderLayout())
        rightPanel.add(chartHolder, BorderLayout.CENTER)
        rightPanel.putClientProperty(CLIENT_CHART_HOLDER, chartHolder)

        val split = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            treeScroll,
            rightPanel
        ).apply {
            dividerLocation = 300
        }
        rootComponent.add(split, BorderLayout.CENTER)

        refreshButton.addActionListener { refresh() }
        tree.addTreeSelectionListener { onTreeSelection() }
    }

    /**
     * Re-fetch the experiments and runs from the configured MLflow tracking
     * server on a worker thread, then marshal the rebuild back to the EDT.
     */
    private fun refresh() {
        val rawUri = MlflowSettingsImpl.getInstance().getTrackingUri().trim()
        val base = rawUri.trimEnd('/').ifEmpty {
            showStatus("Configure the tracking URI first (Settings → Tools → ML Experiment Cockpit).", true)
            return
        }

        refreshButton.isEnabled = false
        showStatus("Loading experiments from $base ...", false)

        Thread({
            try {
                val client = MlflowClient(base)
                val experiments = client.listExperiments()
                // Build a flat list of (experiment, runs) so we can populate
                // the tree in a single EDT dispatch.
                val rows = experiments.map { exp ->
                    val runs = try {
                        client.listRuns(exp.id)
                    } catch (e: MlflowException) {
                        // Listing runs can fail per-experiment even if listing
                        // experiments succeeded (e.g. permission denied). Surface
                        // the failure in the node label rather than crashing.
                        emptyList()
                    }
                    ExperimentNode(exp, runs)
                }
                ApplicationManager.getApplication().invokeLater {
                    populateTree(rows)
                    showStatus("Loaded ${rows.size} experiment(s) from $base", false)
                    refreshButton.isEnabled = true
                }
            } catch (e: MlflowException) {
                ApplicationManager.getApplication().invokeLater {
                    showStatus("Failed to load experiments: ${e.message}", true)
                    refreshButton.isEnabled = true
                    showBalloonError("Project failed to load experiments: ${e.message}")
                }
            } catch (t: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    showStatus("Unexpected error: ${t.message ?: t.javaClass.simpleName}", true)
                    refreshButton.isEnabled = true
                    showBalloonError("Project unexpected error: ${t.message}")
                }
            }
        }, "mlexp-tw-refresh").start()
    }

    private fun populateTree(rows: List<ExperimentNode>) {
        treeRoot.removeAllChildren()
        if (rows.isEmpty()) {
            treeRoot.add(DefaultMutableTreeNode("No experiments found"))
        } else {
            for (row in rows) {
                val expNode = DefaultMutableTreeNode(row)
                for (run in row.runs) {
                    expNode.add(DefaultMutableTreeNode(RunNode(run)))
                }
                treeRoot.add(expNode)
            }
        }
        treeModel.reload()
        tree.expandRow(0)
    }

    private fun onTreeSelection() {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
            ?: return
        val userObj = node.userObject
        when (userObj) {
            is ExperimentNode -> {
                showExperimentDetail(userObj)
                clearChart()
            }
            is RunNode -> {
                loadRunDetail(userObj.run)
            }
            else -> {
                detailArea.text = ""
                clearChart()
            }
        }
    }

    private fun showExperimentDetail(node: ExperimentNode) {
        detailArea.text = "<html><body style='font-family:monospace'>" +
                "<b>Experiment</b><br>" +
                "name : ${escape(node.name)}<br>" +
                "id&nbsp;&nbsp; : ${escape(node.id)}<br>" +
                "runs : ${node.runs.size}" +
                "</body></html>"
    }

    private fun loadRunDetail(run: Run) {
        detailArea.text = "Loading run ${run.id.take(8)} ..."
        clearChart()
        Thread({
            try {
                val client = MlflowClient(MlflowSettingsImpl.getInstance().getTrackingUri().trimEnd('/'))
                val params = client.getRunParams(run.id)
                val metrics = client.getRunMetrics(run.id)
                ApplicationManager.getApplication().invokeLater {
                    detailArea.text = renderRunDetail(run, params, metrics)
                    // Build the chart panel now that we know the metric names.
                    installChart(run, metrics.keys.toList())
                }
            } catch (e: MlflowException) {
                ApplicationManager.getApplication().invokeLater {
                    detailArea.text = "<html><body>Failed to load run: ${escape(e.message ?: "")}</body></html>"
                }
            } catch (t: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    detailArea.text = "<html><body>Unexpected error: ${escape(t.message ?: t.javaClass.simpleName)}</body></html>"
                }
            }
        }, "mlexp-tw-rundetail").start()
    }

    /**
     * Build a new [MetricsChartPanel] for the given run and drop it into the
     * bottom slot of the right pane. Safe to call repeatedly; old panels are
     * simply replaced.
     */
    private fun installChart(run: Run, metricNames: List<String>) {
        val holder = rightPanel.getClientProperty(CLIENT_CHART_HOLDER) as? JPanel ?: return
        holder.removeAll()
        val baseUriProvider: () -> String = {
            MlflowSettingsImpl.getInstance().getTrackingUri()
        }
        val onStatus: (String) -> Unit = { msg -> showStatus(msg, false) }
        val panel = MetricsChartPanel(
            runId = run.id,
            getBaseUri = baseUriProvider,
            onStatus = onStatus
        )
        holder.add(panel.rootComponent, BorderLayout.CENTER)
        holder.revalidate()
        holder.repaint()
        // Populate the combo with this run's metrics; the panel will fetch
        // history for the first one automatically.
        panel.setAvailableMetrics(metricNames)
    }

    /** Remove any chart from the right pane. */
    private fun clearChart() {
        val holder = rightPanel.getClientProperty(CLIENT_CHART_HOLDER) as? JPanel ?: return
        holder.removeAll()
        holder.revalidate()
        holder.repaint()
    }

    private fun renderRunDetail(run: Run, params: Map<String, String>, metrics: Map<String, Double>): String {
        val ts = if (run.startTimeMs > 0)
            ISO_FMT.format(Instant.ofEpochMilli(run.startTimeMs).atZone(ZoneId.systemDefault()))
        else "n/a"
        val sb = StringBuilder()
        sb.append("<html><body style='font-family:monospace'>")
        sb.append("<b>Run</b><br>")
        sb.append("id&nbsp;&nbsp;&nbsp;&nbsp;: ${escape(run.id)}<br>")
        sb.append("status : ${escape(run.status)}<br>")
        sb.append("start&nbsp; : $ts<br>")
        sb.append("<br><b>Params (${params.size})</b><br>")
        if (params.isEmpty()) {
            sb.append("&nbsp;&nbsp;(none)<br>")
        } else {
            for ((k, v) in params.entries.sortedBy { it.key }) {
                sb.append("&nbsp;&nbsp;${escape(k)} = ${escape(v)}<br>")
            }
        }
        sb.append("<br><b>Metrics (${metrics.size})</b><br>")
        if (metrics.isEmpty()) {
            sb.append("&nbsp;&nbsp;(none)<br>")
        } else {
            for ((k, v) in metrics.entries.sortedBy { it.key }) {
                sb.append("&nbsp;&nbsp;${escape(k)} = ${"%.6g".format(v)}<br>")
            }
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun showStatus(message: String, isError: Boolean) {
        statusLabel.text = message
        statusLabel.foreground = if (isError)
            java.awt.Color(0xC0, 0x39, 0x2B) else statusLabel.foreground
    }

    private fun showBalloonError(content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(ConnectToMlflowAction.NOTIFICATION_GROUP_ID)
            .createNotification("ML Experiment Cockpit", content, NotificationType.ERROR)
            .notify(project)
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    companion object {
        private val ISO_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        /** Key for the right-pane slot that hosts the chart. */
        private const val CLIENT_CHART_HOLDER = "mlexp.chartHolder"
    }

    /**
     * Tree-node payload types. Carried as DefaultMutableTreeNode.userObject.
     *
     * 0.0.15: both payloads now print a readable label (id / name / status)
     * instead of the generated data-class `toString()`, which used to dump the
     * whole object graph ("ExperimentNode(exp=Experiment(id=2, name=…, …))")
     * into the tree. The renderer reads [label]; [toString] returns the same
     * string so that any other consumer (tooltips, copy-to-clipboard) also
     * sees something a human can read.
     */
    data class ExperimentNode(val exp: Experiment, val runs: List<Run>) {
        val id: String get() = exp.id
        val name: String get() = exp.name

        /** e.g. `#2  nlp-finetune  (active)`. */
        val label: String
            get() {
                val stage = exp.lifecycleStage
                return if (stage.isEmpty()) "#$id  $name" else "#$id  $name  ($stage)"
            }

        override fun toString(): String = label
    }

    data class RunNode(val run: Run) {
        /** e.g. `adamw-lr0.01-s7  FAILED`. */
        val label: String
            get() {
                val shown = run.name.ifEmpty { run.id.take(8) }
                val status = run.status
                return if (status.isEmpty()) shown else "$shown  $status"
            }

        override fun toString(): String = label
    }
}
