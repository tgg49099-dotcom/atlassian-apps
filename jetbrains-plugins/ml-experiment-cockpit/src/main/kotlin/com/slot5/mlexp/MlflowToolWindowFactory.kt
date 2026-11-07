package com.slot5.mlexp

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.slot5.mlexp.pro.ComparaisonHub
import com.slot5.mlexp.pro.LicenceNatif
import com.slot5.mlexp.pro.PanneauComparaison
import com.slot5.mlexp.pro.ProGate

/**
 * Registers the "ML Experiment Cockpit" tool window with the IntelliJ Platform.
 *
 * The factory owns FIVE `Content` tabs inside the same tool window (v0.0.8
 * after the v0.0.7-v0.0.5 Replicate deploy tab was removed because the only
 * action behind it was a placeholder that always sent the same hard-coded
 * model + version — a reviewer would have caught it on first try):
 *   - "Experiments" — the MLflow Experiments/Run tab. Wraps an
 *                     [MlflowToolWindowPanel]. Added in v0.0.2.
 *   - "Optuna"      — the Optuna tab. Wraps an [OptunaToolWindowPanel].
 *                     Added in v0.0.4.
 *   - "Models"      — the MLflow Model Registry tab. Wraps a
 *                     [ModelRegistryToolWindowPanel]. Added in v0.0.6.
 *                     No new settings — reuses the MLflow tracking URI.
 *   - "Sweeps"      — the W&B Sweeps tab. Wraps a [SweepToolWindowPanel].
 *                     Added in v0.0.7. Bearer auth against the public
 *                     W&B GraphQL API; new `wandbApiKey`, `wandbEntity`,
 *                     `wandbProject` settings fields.
 *   - "Compare"     — the **Pro tier n°1** tab (v0.0.22): compare two MLflow
 *                     runs side by side. Visible to everybody, **locked**
 *                     without a licence. It does not replace anything: the
 *                     four free tabs keep working exactly as before.
 *
 * Each tab has its own JBLabel + tree + detail panel. Selecting the tab
 * does not throw the MLflow panel away; users can alternate between
 * MLflow, Optuna, Models and Sweeps inside the same tool window.
 *
 * v0.0.22 — the "Compare" tab is connected to the "Experiments" tab through
 * a [ComparaisonHub]: the free tree only *asks* for a comparison, it never
 * knows whether the Pro gate is open. The native licence reader is
 * installed here, once, when the tool window is created.
 *
 * Cross-version: PyCharm 2024.3+ supports ToolWindow unconditionally.
 */
class MlflowToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val cf = ContentFactory.getInstance()

        // v0.0.22 — the native licence reader (JetBrains platform). Until it
        // is installed the Pro gate stays closed (fail-closed).
        ProGate.installerSource(LicenceNatif())

        // The relay between the free "Experiments" tree and the Pro "Compare"
        // tab. Neither panel knows the other.
        val comparaisonHub = ComparaisonHub()

        // ---- Tab 1: MLflow experiments (since v0.0.2, unchanged since v0.0.8) ----
        val mlflowPanel = MlflowToolWindowPanel(project, toolWindow, comparaisonHub)
        val mlflowContent = cf.createContent(
            mlflowPanel.rootComponent,
            "Experiments",
            /* isLockable = */ false
        )
        toolWindow.contentManager.addContent(mlflowContent)

        // ---- Tab 2: Optuna studies (since v0.0.4, unchanged since v0.0.8) ----
        val optunaPanel = OptunaToolWindowPanel(project, toolWindow)
        val optunaContent = cf.createContent(
            optunaPanel.rootComponent,
            "Optuna",
            /* isLockable = */ false
        )
        toolWindow.contentManager.addContent(optunaContent)

        // ---- Tab 3: MLflow Model Registry (since v0.0.6, unchanged since v0.0.8) ----
        val modelRegPanel = ModelRegistryToolWindowPanel(project, toolWindow)
        val modelRegContent = cf.createContent(
            modelRegPanel.rootComponent,
            "Models",
            /* isLockable = */ false
        )
        toolWindow.contentManager.addContent(modelRegContent)

        // ---- Tab 4: W&B Sweeps (since v0.0.7, unchanged since v0.0.8) ----
        val sweepsPanel = SweepToolWindowPanel(project, toolWindow)
        val sweepsContent = cf.createContent(
            sweepsPanel.rootComponent,
            "Sweeps",
            /* isLockable = */ false
        )
        toolWindow.contentManager.addContent(sweepsContent)

        // ---- Tab 5: Compare 2 runs — Pro tier n°1 (v0.0.22) ----
        // Visible to everybody; the content is locked without a licence.
        val comparePanel = PanneauComparaison(comparaisonHub)
        val compareContent = cf.createContent(
            comparePanel.rootComponent,
            "Compare",
            /* isLockable = */ false
        )
        toolWindow.contentManager.addContent(compareContent)

        // « Compare with… » dans l'onglet Experiments affiche l'onglet Compare.
        comparaisonHub.selectionner {
            toolWindow.contentManager.setSelectedContent(compareContent)
        }
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}