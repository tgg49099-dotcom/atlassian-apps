package com.slot5.mlexp

import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Component
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer

/**
 * Renders the experiment / run tree of the ML Experiment Cockpit panel.
 *
 * Added in 0.0.15.
 *
 * Before this class existed, Swing fell back to its default behaviour and
 * printed the *raw* `toString()` of the payload carried by each tree node:
 *
 *     ExperimentNode(exp=Experiment(id=2, name=nlp-finetune, artifact_locat…
 *     RunNode(run=Run(id=734fda59fc41475686ea1ab6cb15c766, status=FAILED, …))
 *
 * which is unreadable and, in a 150 px-wide column, truncated exactly where
 * the information starts. The renderer now prints the readable label carried
 * by the payload (id / name / status) and colours the run status.
 *
 * No icon is drawn: the tree has only two kinds of rows and the text alone
 * identifies them, so the default leaf icons were pure noise.
 *
 * Nothing here changes what the panel *does* — it only changes what it shows.
 */
class NodeTreeCellRenderer : DefaultTreeCellRenderer() {

    // Instance fields on purpose: a companion object or a top-level property
    // would add an extra .class file to the JAR (the pre-flight counts them).
    private val colorFailed = JBColor(Color(0xC0, 0x39, 0x2B), Color(0xE8, 0x6A, 0x5A))
    private val colorFinished = JBColor(Color(0x1E, 0x7A, 0x3C), Color(0x5A, 0xC8, 0x7A))
    private val colorRunning = JBColor(Color(0x1E, 0x6F, 0xB8), Color(0x6F, 0xB0, 0xE8))

    override fun getTreeCellRendererComponent(
        tree: JTree?,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ): Component {
        super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)

        val payload = (value as? DefaultMutableTreeNode)?.userObject

        when (payload) {
            is MlflowToolWindowPanel.ExperimentNode -> {
                text = payload.label
                icon = null
                toolTipText = "Experiment #${payload.id} — ${payload.name} (${payload.runs.size} run(s))"
                // The experiment row keeps the theme's own colour.
            }

            is MlflowToolWindowPanel.RunNode -> {
                text = payload.label
                icon = null
                toolTipText = "Run ${payload.run.id} — ${payload.run.status}"
                // Only recolour when the row is not selected: the selection
                // colour must win, otherwise the text can become unreadable.
                if (!selected) {
                    statusColor(payload.run.status)?.let { foreground = it }
                }
            }

            else -> {
                // "(no data)" / "No experiments found" — plain strings.
                icon = null
            }
        }
        return this
    }

    /** Maps an MLflow run status to a colour; unknown statuses keep the theme colour. */
    private fun statusColor(status: String): Color? = when (status.uppercase()) {
        "FAILED", "KILLED" -> colorFailed
        "FINISHED" -> colorFinished
        "RUNNING" -> colorRunning
        else -> null
    }
}
