package com.slot5.mlexp

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.util.NlsContexts
import com.intellij.ui.components.JBLabel
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * Settings panel for the ML Experiment Cockpit plugin (5 fields as of v0.0.8;
 * the W&B key is now in PasswordSafe since v0.0.9 but still entered here).
 *
 * v0.0.8 removed the three Replicate / deploy-related fields that the v0.0.5
 * deploy action used (`deployToken`, `deployProvider`, `defaultModelName`).
 * The deploy action itself was removed in the same cycle because the only
 * thing it did was send a hard-coded model + version payload (no per-run
 * substitution) — shipping it would have invited a reviewer rejection.
 *
 * v0.0.9 moved the W&B API key out of the settings XML and into the IDE's
 * PasswordSafe credential store (via [com.intellij.ide.passwordSafe.PasswordSafe]).
 * The text field is still here so the user has somewhere to type the key.
 *
 * Field history (newest first):
 *   - v0.0.9 : `wandbApiKey` is now persisted in PasswordSafe
 *   - v0.0.7 : `wandbApiKey`, `wandbEntity`, `wandbProject`
 *   - v0.0.4 : `optunaUri` (2nd field)
 *   - v0.0.1 : `trackingUri` (1st field)
 *
 * Wired in `plugin.xml` under `<applicationConfigurable>` with
 * `displayName="ML Experiment Cockpit"` (Settings → Tools → ML Experiment Cockpit).
 *
 * Each field is a plain text input. Empty is allowed (clears the value);
 * persistence is handled by [MlflowSettingsImpl].
 */
class MlflowSettingsConfigurable : Configurable {

    private val panel: JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }

    private val mlflowField: JTextField = JTextField().apply {
        toolTipText = "Base URL of the MLflow Tracking Server (e.g. http://localhost:5000). Empty = not configured."
    }
    private val optunaField: JTextField = JTextField().apply {
        toolTipText = "Base URL of the Optuna Dashboard (e.g. http://localhost:8080). Empty = not configured."
    }
    // v0.0.7: W&B Sweeps settings. v0.0.9: stored in PasswordSafe (not in the XML).
    private val wandbApiKeyField: JTextField = JTextField().apply {
        toolTipText = "W&B API key (paste from https://www.wandb.ai/settings → Danger Zone → API keys). Empty = not configured. Stored in the IDE's PasswordSafe credential store (not in the settings XML) since v0.0.9."
    }
    private val wandbEntityField: JTextField = JTextField().apply {
        toolTipText = "Default W&B entity (user or team name) to pre-fill the Sweeps tab. You can override it per-session in the Sweeps tab."
    }
    private val wandbProjectField: JTextField = JTextField().apply {
        toolTipText = "Default W&B project name to pre-fill the Sweeps tab. You can override it per-session in the Sweeps tab."
    }

    /** Snapshots taken at panel display time, for `isModified()`. */
    private var mlflowSnapshot: String = ""
    private var optunaSnapshot: String = ""
    private var wandbApiKeySnapshot: String = ""
    private var wandbEntitySnapshot: String = ""
    private var wandbProjectSnapshot: String = ""

    override fun getDisplayName(): @NlsContexts.ConfigurableName String =
        "ML Experiment Cockpit"

    override fun createComponent(): JComponent {
        panel.add(JBLabel("MLflow tracking URI:"))
        panel.add(mlflowField)
        panel.add(JBLabel("Optuna dashboard URI:"))
        panel.add(optunaField)
        panel.add(JBLabel("W&B API key (Bearer):"))
        panel.add(wandbApiKeyField)
        panel.add(JBLabel("W&B entity (default for Sweeps tab):"))
        panel.add(wandbEntityField)
        panel.add(JBLabel("W&B project (default for Sweeps tab):"))
        panel.add(wandbProjectField)
        return panel
    }

    override fun isModified(): Boolean {
        return mlflowField.text.trim() != mlflowSnapshot ||
                optunaField.text.trim() != optunaSnapshot ||
                wandbApiKeyField.text.trim() != wandbApiKeySnapshot ||
                wandbEntityField.text.trim() != wandbEntitySnapshot ||
                wandbProjectField.text.trim() != wandbProjectSnapshot
    }

    override fun apply() {
        val s = MlflowSettingsImpl.getInstance()
        s.setTrackingUri(mlflowField.text)
        s.setOptunaUri(optunaField.text)
        s.setWandbApiKey(wandbApiKeyField.text)
        s.setWandbEntity(wandbEntityField.text)
        s.setWandbProject(wandbProjectField.text)
        mlflowSnapshot = mlflowField.text.trim()
        optunaSnapshot = optunaField.text.trim()
        wandbApiKeySnapshot = wandbApiKeyField.text.trim()
        wandbEntitySnapshot = wandbEntityField.text.trim()
        wandbProjectSnapshot = wandbProjectField.text.trim()
    }

    override fun reset() {
        val s = MlflowSettingsImpl.getInstance()
        mlflowField.text = s.getTrackingUri()
        optunaField.text = s.getOptunaUri()
        wandbApiKeyField.text = s.getWandbApiKey()
        wandbEntityField.text = s.getWandbEntity()
        wandbProjectField.text = s.getWandbProject()
        mlflowSnapshot = s.getTrackingUri()
        optunaSnapshot = s.getOptunaUri()
        wandbApiKeySnapshot = s.getWandbApiKey()
        wandbEntitySnapshot = s.getWandbEntity()
        wandbProjectSnapshot = s.getWandbProject()
    }

    override fun disposeUIResources() {
        // Nothing to dispose — Swing components are GC'd with the panel.
    }
}
