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
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTextField
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel

/**
 * Tool window panel for the "Sweeps" tab of the ML Experiment Cockpit v0.0.7.
 *
 * Surfaces W&B Sweeps (Hyperparameter sweeps — analogous to Optuna studies)
 * inside the IDE. Authenticates with a W&B API key (Bearer token) configured
 * in the plugin settings.
 *
 * Layout (BorderLayout):
 *   NORTH  : [entity textfield] [project textfield] [Refresh] [Status label]
 *   LEFT   : Tree of Sweeps (grouped by state — running / finished / failed).
 *   RIGHT  : Detail panel showing: sweep name, state, runs count, created
 *            and updated timestamps, best run (id + name + summary metrics),
 *            and a top-N hyperparameter view if present in summary metrics.
 *
 * Network I/O runs on worker threads ("mlexp-wandb-refresh",
 * "mlexp-wandb-detail") then marshals back to the EDT via `invokeLater`.
 *
 * Cross-version: same Swing primitives used by the Optuna (v0.0.4),
 * Deploy (v0.0.5) and Model Registry (v0.0.6) tabs.
 */
class SweepToolWindowPanel(
    private val project: Project,
    private val toolWindow: ToolWindow
) {

    /** The Swing component the IntelliJ Platform hosts. */
    val rootComponent: JPanel = JPanel(BorderLayout())

    private val entityField = JTextField().apply {
        toolTipText = "W&B entity (user or team name). Set it in Settings → Tools → ML Experiment Cockpit OR type it here for this session only."
        columns = 14
    }
    private val projectField = JTextField().apply {
        toolTipText = "W&B project name. Same as above — settings or this field."
        columns = 14
    }
    private val refreshButton = JButton("Refresh")
    private val statusLabel = JBLabel("Idle. Configure entity + project, then click Refresh.")

    private val detailArea = JBLabel(
        "Select a sweep in the tree to view its details (state, timestamps, runs total, best run + summary metrics)."
    )

    private val treeRoot = DefaultMutableTreeNode("(no data)")
    private val treeModel = DefaultTreeModel(treeRoot)
    private val tree: Tree = Tree(treeModel).apply {
        showsRootHandles = true
        isRootVisible = false
        cellRenderer = SweepTreeCellRenderer()
    }

    /** Cached sweeps from the last successful refresh. v0.0.7 keeps a flat list. */
    private var sweeps: List<SweepSummary> = emptyList()

    init {
        // ---- NORTH (entity + project + refresh + status) ----
        val entityLabel = JLabel("entity:")
        val projectLabel = JLabel("project:")
        val topBar = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(entityLabel)
            add(entityField)
            add(projectLabel)
            add(projectField)
            add(refreshButton)
            add(statusLabel)
        }
        rootComponent.add(topBar, BorderLayout.NORTH)

        // Pre-populate the entity + project fields from the persisted settings.
        // (v0.0.7 stores the W&B API key in settings; entity + project stay
        // session-local so users can quickly switch projects without touching
        // settings.)
        val s = MlflowSettingsImpl.getInstance()
        if (s.getWandbEntity().isNotEmpty()) entityField.text = s.getWandbEntity()
        if (s.getWandbProject().isNotEmpty()) projectField.text = s.getWandbProject()

        // ---- LEFT (sweeps tree) ----
        val treeScroll = JBScrollPane(tree).apply {
            preferredSize = Dimension(260, 400)
        }

        // ---- RIGHT (detail) ----
        detailArea.font = JBFont.regular()
        val detailScroll = JBScrollPane(detailArea).apply {
            preferredSize = Dimension(460, 380)
        }
        val rightPanel = JPanel(BorderLayout()).apply {
            border = javax.swing.border.EmptyBorder(0, 4, 0, 0)
            add(detailScroll, BorderLayout.CENTER)
        }

        val split = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            treeScroll,
            rightPanel
        ).apply {
            dividerLocation = 260
        }
        rootComponent.add(split, BorderLayout.CENTER)

        refreshButton.addActionListener { refresh() }
        tree.addTreeSelectionListener { onTreeSelection() }
    }

    private fun refresh() {
        val rawKey = MlflowSettingsImpl.getInstance().getWandbApiKey().trim()
        if (rawKey.isEmpty()) {
            showStatus(
                "Configure the W&B API token first (Settings → Tools → ML Experiment Cockpit).",
                true
            )
            return
        }
        val entity = entityField.text.trim()
        val project = projectField.text.trim()
        if (entity.isEmpty() || project.isEmpty()) {
            showStatus("Both entity and project are required.", true)
            return
        }

        refreshButton.isEnabled = false
        showStatus("Loading sweeps from W&B ($entity/$project) ...", false)
        Thread({
            try {
                val client = WandbClient(
                    baseUri = "https://api.wandb.ai",
                    apiKey = rawKey
                )
                val loaded = client.listSweeps(entity, project)
                ApplicationManager.getApplication().invokeLater {
                    sweeps = loaded
                    populateTree(loaded)
                    showStatus(
                        "Loaded ${loaded.size} sweep(s) from $entity/$project.",
                        false
                    )
                    refreshButton.isEnabled = true
                }
            } catch (e: WandbException) {
                ApplicationManager.getApplication().invokeLater {
                    showStatus("Failed to load sweeps: ${e.message}", true)
                    refreshButton.isEnabled = true
                    showBalloonError("W&B sweeps failed to load: ${e.message}")
                }
            } catch (t: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    showStatus("Unexpected error: ${t.message ?: t.javaClass.simpleName}", true)
                    refreshButton.isEnabled = true
                    showBalloonError("W&B sweeps unexpected error: ${t.message}")
                }
            }
        }, "mlexp-wandb-refresh").start()
    }

    private fun populateTree(rows: List<SweepSummary>) {
        treeRoot.removeAllChildren()
        if (rows.isEmpty()) {
            treeRoot.add(DefaultMutableTreeNode("No sweeps found"))
        } else {
            // Group by state — running first, then finished, then failed.
            // Other states (crashed, pending, etc.) go in their own bucket.
            val order = listOf("running", "finished", "failed", "crashed", "pending", "stopped")
            val grouped: Map<String, List<SweepSummary>> = rows
                .groupBy { it.state.ifBlank { "other" } }
            for (state in order) {
                val bucket = grouped[state].orEmpty()
                if (bucket.isNotEmpty()) {
                    val groupNode = DefaultMutableTreeNode(
                        SweepGroupNode(state, bucket.size)
                    )
                    for (sweep in bucket.sortedBy { it.name }) {
                        groupNode.add(DefaultMutableTreeNode(SweepNode(sweep)))
                    }
                    treeRoot.add(groupNode)
                }
            }
            // Catch-all bucket for unknown states.
            val known = order.toSet()
            val other = grouped.filterKeys { it !in known }
            for ((state, bucket) in other) {
                val groupNode = DefaultMutableTreeNode(
                    SweepGroupNode(state, bucket.size)
                )
                for (sweep in bucket.sortedBy { it.name }) {
                    groupNode.add(DefaultMutableTreeNode(SweepNode(sweep)))
                }
                treeRoot.add(groupNode)
            }
        }
        treeModel.reload()
        // Expand the first group if any.
        if (tree.rowCount > 0) tree.expandRow(0)
    }

    private fun onTreeSelection() {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
            ?: return
        when (val userObj = node.userObject) {
            is SweepNode -> showSweepDetail(userObj.sweep)
            is SweepGroupNode -> detailArea.text =
                "<html><body>${userObj.count} sweep(s) with state <b>${escape(userObj.state)}</b>.</body></html>"
            else -> detailArea.text = ""
        }
    }

    private fun showSweepDetail(s: SweepSummary) {
        val sb = StringBuilder()
        sb.append("<html><body style='font-family:monospace'>")
        sb.append("<b>Sweep</b><br>")
        sb.append("name&nbsp;&nbsp;&nbsp;&nbsp;: ${escape(s.name)}<br>")
        sb.append("id&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;: ${escape(s.id)}<br>")
        sb.append("state&nbsp;&nbsp;&nbsp;: ${escape(s.state.ifBlank { "?" })}<br>")
        sb.append("runs&nbsp;&nbsp;&nbsp;&nbsp;: ${s.runsCount?.toString() ?: "n/a (not reported by the W&amp;B API)"}<br>")
        sb.append("created&nbsp; : ${escape(s.createdAt.ifBlank { "n/a" })}<br>")
        sb.append("updated&nbsp; : ${escape(s.updatedAt.ifBlank { "n/a" })}<br>")
        sb.append("<br><b>Best run</b><br>")
        val best = s.bestRun
        if (best == null) {
            // Pas « no completed runs yet » : le sweep peut en avoir 12. C'est
            // l'API W&B qui n'expose pas de « best run » sur un sweep
            // (champ `bestRun` absent du type `Sweep`), voir WandbClient.
            sb.append("&nbsp;&nbsp;(not exposed by the W&amp;B API for sweeps)<br>")
        } else {
            sb.append("&nbsp;&nbsp;name&nbsp;&nbsp;: ${escape(best.displayName)}<br>")
            sb.append("&nbsp;&nbsp;id&nbsp;&nbsp;&nbsp;&nbsp;: ${escape(best.id)}<br>")
            sb.append("&nbsp;&nbsp;state&nbsp;: ${escape(best.state.ifBlank { "?" })}<br>")
            if (best.summaryMetrics.isEmpty()) {
                sb.append("&nbsp;&nbsp;summary: (none reported)<br>")
            } else {
                sb.append("&nbsp;&nbsp;summary:<br>")
                // Show top-8 metrics, sorted by key for stability.
                best.summaryMetrics.entries.sortedBy { it.key }.take(8).forEach { (k, v) ->
                    sb.append("&nbsp;&nbsp;&nbsp;&nbsp;<b>${escape(k)}</b> = ${escape(v)}<br>")
                }
            }
        }
        sb.append("</body></html>")
        detailArea.text = sb.toString()
    }

    private fun showStatus(message: String, isError: Boolean) {
        statusLabel.text = message
        statusLabel.foreground = if (isError) Color(0xC0, 0x39, 0x2B) else statusLabel.foreground
    }

    private fun showBalloonError(content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(ConnectToMlflowAction.NOTIFICATION_GROUP_ID)
            .createNotification("ML Experiment Cockpit (W&B Sweeps)", content, NotificationType.ERROR)
            .notify(project)
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /** Tree-node payload types. Carried as DefaultMutableTreeNode.userObject. */
    data class SweepGroupNode(val state: String, val count: Int)
    data class SweepNode(val sweep: SweepSummary)

    /**
     * Cell renderer that colours a sweep node by its state — running
     * shows in blue, finished in green, failed/crashed in red. Helps the
     * user scan a tree of dozens of sweeps quickly.
     */
    private class SweepTreeCellRenderer : DefaultTreeCellRenderer() {
        override fun getTreeCellRendererComponent(
            tree: JTree,
            value: Any?,
            sel: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ): java.awt.Component {
            val c = super.getTreeCellRendererComponent(
                tree, value, sel, expanded, leaf, row, hasFocus
            )
            val node = (value as? DefaultMutableTreeNode)?.userObject
            when (node) {
                is SweepGroupNode -> {
                    textNonSelectionColor = Color(0x44, 0x44, 0x44)
                    textSelectionColor = Color.WHITE
                    font = Font(Font.SANS_SERIF, Font.BOLD, 12)
                    if (!sel) {
                        when (node.state.lowercase()) {
                            "running" -> textNonSelectionColor = Color(0x1E, 0x88, 0xE5)
                            "finished" -> textNonSelectionColor = Color(0x2E, 0x7D, 0x32)
                            "failed", "crashed" -> textNonSelectionColor = Color(0xC0, 0x39, 0x2B)
                        }
                    }
                }
                is SweepNode -> {
                    font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
                    // `runsCount` is null when the server did not send the
                    // `runs` block: say "n/a" rather than a fake "0 run(s)".
                    val runs = node.sweep.runsCount?.let { "$it run(s)" } ?: "runs n/a"
                    val best = node.sweep.bestRun?.summaryMetrics?.entries?.firstOrNull()
                    val bestStr = if (best != null) {
                        " — ${best.key}=${best.value}"
                    } else ""
                    text = "${node.sweep.name} (${node.sweep.state.ifBlank { "?" }}, $runs)$bestStr"
                    textNonSelectionColor = Color(0x33, 0x33, 0x33)
                    textSelectionColor = Color.WHITE
                }
            }
            return c
        }
    }

    companion object {
        /**
         * The W&B API base URL. v0.0.7 hard-codes the public cloud endpoint.
         * Future versions may surface a self-hosted base URI in settings.
         */
        const val WANDB_PUBLIC_BASE: String = "https://api.wandb.ai"
    }
}