package com.slot5.mlexp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import java.awt.BorderLayout
import java.awt.Color
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
 * Tool window panel for the "Models" tab of the ML Experiment Cockpit v0.0.6.
 *
 * Surfaces the MLflow Model Registry (registered models + their versions)
 * inside the IDE. Reuses the same base URI as the Experiments tab — no new
 * settings field, no new auth.
 *
 * Layout (BorderLayout):
 *   NORTH  : [Refresh] [Status label]
 *   CENTER : JSplitPane
 *             - LEFT  : Tree of registered models (1 node per model; expanding
 *                       the model node reveals its latest versions).
 *             - RIGHT : A detail panel showing model description, creation
 *                       timestamp, latest stages, and (on version selection)
 *                       the version's run id + status + creation timestamp.
 *
 * Network I/O runs on worker threads ("mlexp-mreg-refresh",
 * "mlexp-mreg-version-detail") then marshals back to the EDT via
 * `invokeLater`.
 *
 * Cross-version: same Swing primitives used by the Optuna (v0.0.4) and
 * Deploy (v0.0.5) tabs — works on PyCharm 2024.3 + 2025.1.
 */
class ModelRegistryToolWindowPanel(
    private val project: Project,
    private val toolWindow: ToolWindow
) {

    /** The Swing component the IntelliJ Platform hosts. */
    val rootComponent: JPanel = JPanel(BorderLayout())

    private val refreshButton = JButton("Refresh")
    private val statusLabel = JBLabel("Idle. Click Refresh to list registered models.")

    private val detailArea = JBLabel("Select a model or a version in the tree to view details.")

    private val treeRoot = DefaultMutableTreeNode("(no data)")
    private val treeModel = DefaultTreeModel(treeRoot)
    private val tree: Tree = Tree(treeModel).apply {
        showsRootHandles = true
        isRootVisible = false
    }

    /** Cached registered models from the last successful refresh. */
    private var models: List<RegisteredModel> = emptyList()

    init {
        // ---- NORTH (refresh + status) ----
        val topBar = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(refreshButton)
            add(statusLabel)
        }
        rootComponent.add(topBar, BorderLayout.NORTH)

        // ---- LEFT (models tree) ----
        val treeScroll = JBScrollPane(tree).apply {
            preferredSize = Dimension(300, 400)
        }

        // ---- RIGHT (detail) ----
        val detailScroll = JBScrollPane(detailArea).apply {
            preferredSize = Dimension(420, 360)
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
            dividerLocation = 300
        }
        rootComponent.add(split, BorderLayout.CENTER)

        refreshButton.addActionListener { refresh() }
        tree.addTreeSelectionListener { onTreeSelection() }
    }

    private fun refresh() {
        val rawUri = MlflowSettingsImpl.getInstance().getTrackingUri().trim()
        val base = rawUri.trimEnd('/').ifEmpty {
            showStatus(
                "Configure the tracking URI first (Settings → Tools → ML Experiment Cockpit).",
                true
            )
            return
        }

        refreshButton.isEnabled = false
        showStatus("Loading registered models from $base ...", false)
        Thread({
            try {
                val client = MlflowClient(base)
                val loaded = client.listRegisteredModels()
                ApplicationManager.getApplication().invokeLater {
                    models = loaded
                    populateTree(loaded)
                    showStatus(
                        "Loaded ${loaded.size} registered model(s) from $base.",
                        false
                    )
                    refreshButton.isEnabled = true
                }
            } catch (e: MlflowException) {
                ApplicationManager.getApplication().invokeLater {
                    showStatus(
                        "Failed to load registered models: ${e.message} " +
                                "(HTTP ${e.status}). Note: Model Registry is shipped " +
                                "with MLflow 2.0+ only — older servers don't expose it.",
                        true
                    )
                    refreshButton.isEnabled = true
                }
            } catch (t: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    showStatus(
                        "Unexpected error: ${t.message ?: t.javaClass.simpleName}",
                        true
                    )
                    refreshButton.isEnabled = true
                }
            }
        }, "mlexp-mreg-refresh").start()
    }

    private fun populateTree(rows: List<RegisteredModel>) {
        treeRoot.removeAllChildren()
        if (rows.isEmpty()) {
            treeRoot.add(DefaultMutableTreeNode("No registered models found"))
        } else {
            for (rm in rows.sortedBy { it.name }) {
                val label = "${rm.name} (${rm.latestVersions.size} version(s))"
                val modelNode = DefaultMutableTreeNode(RegisteredModelNode(rm, label))
                for (v in rm.latestVersions) {
                    val vLabel = "v${v.version} [${v.currentStage.ifBlank { "None" }}]"
                    modelNode.add(DefaultMutableTreeNode(ModelVersionNode(v, vLabel)))
                }
                treeRoot.add(modelNode)
            }
        }
        treeModel.reload()
        tree.expandRow(0)
    }

    private fun onTreeSelection() {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        when (val userObj = node.userObject) {
            is RegisteredModelNode -> showModelDetail(userObj.model)
            is ModelVersionNode -> showVersionDetail(userObj.version)
            else -> detailArea.text = ""
        }
    }

    private fun showModelDetail(model: RegisteredModel) {
        detailArea.text = "<html><body style='font-family:monospace'>" +
                "<b>Registered model</b><br>" +
                "name&nbsp;&nbsp;&nbsp;&nbsp;: ${escape(model.name)}<br>" +
                "created&nbsp; : ${formatTs(model.creationTimestampMs)}<br>" +
                "versions : ${model.latestVersions.size}<br>" +
                "<br><b>Description</b><br>" +
                "&nbsp;&nbsp;${escape(model.description.ifBlank { "(no description)" })}<br>" +
                "<br><b>Latest versions</b><br>" +
                if (model.latestVersions.isEmpty()) {
                    "&nbsp;&nbsp;(none)<br>"
                } else {
                    model.latestVersions
                        .sortedBy { it.version.toIntOrNull() ?: 0 }
                        .joinToString("") {
                            "&nbsp;&nbsp;v${escape(it.version)} &nbsp; stage=${escape(it.currentStage.ifBlank { "None" })}" +
                                    " &nbsp; status=${escape(it.status.ifBlank { "?" })}<br>"
                        }
                } +
                "</body></html>"
    }

    private fun showVersionDetail(v: ModelVersion) {
        detailArea.text = "<html><body style='font-family:monospace'>" +
                "<b>Model version</b><br>" +
                "name&nbsp;&nbsp;&nbsp;: ${escape(v.name)}<br>" +
                "version&nbsp; : ${escape(v.version)}<br>" +
                "stage&nbsp;&nbsp; : ${escape(v.currentStage.ifBlank { "None" })}<br>" +
                "status&nbsp; : ${escape(v.status.ifBlank { "?" })}<br>" +
                "run_id&nbsp;&nbsp; : ${escape(v.runId)}<br>" +
                "created&nbsp; : ${formatTs(v.creationTimestampMs)}<br>" +
                "</body></html>"
    }

    private fun formatTs(ms: Long): String {
        if (ms <= 0) return "n/a"
        return ISO_FMT.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
    }

    private fun showStatus(message: String, isError: Boolean) {
        statusLabel.text = message
        statusLabel.foreground = if (isError) Color(0xC0, 0x39, 0x2B) else statusLabel.foreground
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /**
     * Tree-node payload types. Carried as DefaultMutableTreeNode.userObject.
     *
     * v0.0.20 — **the tree of the Models tab had no cell renderer**, so Swing
     * printed the generated data-class `toString()`:
     * `RegisteredModelNode(model=RegisteredModel(name=…, …), label=…)`.
     * The label was computed and stored in the node but never displayed.
     * `toString()` now returns that label, exactly like
     * `MlflowToolWindowPanel.ExperimentNode` / `RunNode` do since 0.0.15 —
     * one single way to display a tree payload across the whole plugin.
     */
    data class RegisteredModelNode(val model: RegisteredModel, val label: String) {
        override fun toString(): String = label
    }

    data class ModelVersionNode(val version: ModelVersion, val label: String) {
        override fun toString(): String = label
    }

    companion object {
        private val ISO_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}