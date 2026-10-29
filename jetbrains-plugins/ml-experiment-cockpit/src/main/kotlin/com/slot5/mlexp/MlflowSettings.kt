package com.slot5.mlexp

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Public, read-only view of the plugin settings.
 *
 * Accessed via `ApplicationManager.getApplication().getService(MlflowSettings::class.java)`.
 * The implementation ([MlflowSettingsImpl]) stores the MLflow tracking URI
 * and the Optuna dashboard URI in the IDE's XML settings file, and the
 * W&B Sweeps key in the IDE's [PasswordSafe] credential store.
 *
 * v0.0.8 removed the Replicate / deploy provider / default deployment name
 * fields that the v0.0.5 deploy action used — the action itself was
 * removed in the same cycle because it always sent a single hard-coded
 * model + version payload (no per-run substitution at all).
 *
 * v0.0.9 moved the W&B API key out of the XML settings file and into
 * [PasswordSafe] (so the key is no longer world-readable in the IDE
 * config directory). Existing v0.0.7–v0.0.8 users keep their key —
 * `loadState()` migrates it on the next settings read.
 *
 * The component name and interface are preserved across versions so existing
 * users don't lose their stored URIs / tokens after upgrading.
 */
interface MlflowSettings {
    /**
     * Base URL of the MLflow Tracking Server, e.g. `http://localhost:5000`.
     * Empty string means "not configured yet".
     */
    fun getTrackingUri(): String
    fun setTrackingUri(uri: String)

    /**
     * Base URL of the Optuna Dashboard, e.g. `http://localhost:8080`. v0.0.4.
     * Empty string means "not configured yet".
     */
    fun getOptunaUri(): String
    fun setOptunaUri(uri: String)

    /**
     * Bearer token for the W&B GraphQL API (`https://api.wandb.ai/graphql`).
     * Empty string means "not configured yet". v0.0.7.
     *
     * Stored in [PasswordSafe] since v0.0.9 (was plain text in the settings
     * XML in v0.0.7–v0.0.8 — migrated transparently on first load).
     *
     * Get one at https://www.wandb.ai/settings → Danger Zone → "API keys".
     */
    fun getWandbApiKey(): String
    fun setWandbApiKey(key: String)

    /**
     * Default W&B entity (user or team name) to pre-fill the Sweeps tab.
     * Empty string means "not configured yet — user types it in the panel".
     * v0.0.7.
     */
    fun getWandbEntity(): String
    fun setWandbEntity(entity: String)

    /**
     * Default W&B project name to pre-fill the Sweeps tab.
     * Empty string means "not configured yet — user types it in the panel".
     * v0.0.7.
     */
    fun getWandbProject(): String
    fun setWandbProject(project: String)
}

/**
 * Persistent implementation of [MlflowSettings].
 *
 * Public fields are stored in `~/.config/JetBrains/<IDE>/options/mlExperimentCockpit.xml`:
 *
 * ```xml
 * <application>
 *   <component name="MlflowSettings">
 *     <trackingUri>http://localhost:5000</trackingUri>
 *     <optunaUri>http://localhost:8080</optunaUri>
 *     <wandbApiKey></wandbApiKey>      <!-- deprecated since v0.0.9, see PasswordSafe -->
 *     <wandbEntity>my-team</wandbEntity>
 *     <wandbProject>my-project</wandbProject>
 *   </component>
 * </application>
 * ```
 *
 * The W&B API key is stored in [PasswordSafe] under the
 * `ML Experiment Cockpit / W&B API key` [CredentialAttributes] pair, not in
 * the XML. v0.0.7–v0.0.8 users are migrated transparently in [loadState].
 *
 * `name = "MlflowSettings"` is the component key — never change it after
 * publishing or existing users lose their stored values.
 */
@State(
    name = "MlflowSettings",
    // The W&B API key lives in PasswordSafe, not in this file. We keep the
    // XML off the JetBrains cloud anyway: it could still contain a
    // pre-v0.0.9 key during the one-time migration window.
    storages = [Storage("mlExperimentCockpit.xml", roamingType = RoamingType.DISABLED)]
)
class MlflowSettingsImpl : PersistentStateComponent<MlflowSettingsImpl.State>, MlflowSettings {

    private var state: State = State()

    /**
     * Where the W&B API key lives in the IDE's [PasswordSafe] since v0.0.9.
     * `serviceName` is the plugin name, `userName` is the field — both are
     * arbitrary identifiers, the IDE never displays them.
     */
    private val wandbKeyAttributes = CredentialAttributes(
        "ML Experiment Cockpit",
        "W&B API key"
    )

    override fun getTrackingUri(): String = state.trackingUri
    override fun setTrackingUri(uri: String) { state.trackingUri = uri.trim() }

    override fun getOptunaUri(): String = state.optunaUri
    override fun setOptunaUri(uri: String) { state.optunaUri = uri.trim() }

    override fun getWandbApiKey(): String =
        // PasswordSafe.instance.get(...) is documented as safe to call from
        // any thread — the Sweeps refresh worker thread uses it too.
        PasswordSafe.instance.get(wandbKeyAttributes)?.getPasswordAsString().orEmpty()

    override fun setWandbApiKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            // Pass `null` to remove the credential from the store entirely.
            PasswordSafe.instance.set(wandbKeyAttributes, null)
        } else {
            PasswordSafe.instance.set(wandbKeyAttributes, Credentials(USER_NAME, trimmed))
        }
    }

    override fun getWandbEntity(): String = state.wandbEntity
    override fun setWandbEntity(entity: String) { state.wandbEntity = entity.trim() }

    override fun getWandbProject(): String = state.wandbProject
    override fun setWandbProject(project: String) { state.wandbProject = project.trim() }

    override fun getState(): State = state

    override fun loadState(state: State) {
        // Capture the legacy plain-text key (if any) BEFORE copying the rest
        // of the bean — the key is migrated to PasswordSafe in v0.0.9 and
        // then cleared from the state so the next XML write does not
        // re-introduce a plain-text field.
        val legacyKey = state.wandbApiKey

        XmlSerializerUtil.copyBean(state, this.state)

        if (legacyKey.isNotEmpty()) {
            // One-time migration from v0.0.7–v0.0.8 plain-text storage to
            // the PasswordSafe credential store.
            setWandbApiKey(legacyKey)
            this.state.wandbApiKey = ""
        }
    }

    /**
     * The bean XmlSerializer actually serializes. Keep field names stable.
     */
    class State {
        var trackingUri: String = ""
        var optunaUri: String = ""
        // v0.0.7 — W&B Sweeps. Deprecated in v0.0.9: the key now lives in
        // PasswordSafe. The field is still here (no @Transient) so the
        // XmlSerializer can read it from v0.0.7–v0.0.8 XML files during
        // the migration window, and `loadState()` clears it after moving
        // the value to PasswordSafe.
        var wandbApiKey: String = ""
        var wandbEntity: String = ""
        var wandbProject: String = ""
    }

    companion object {
        /** Arbitrary identifier passed to [PasswordSafe]. Not user-visible. */
        private const val USER_NAME: String = "slot5-mlexp"

        fun getInstance(): MlflowSettings =
            ApplicationManager.getApplication().getService(MlflowSettings::class.java)
    }
}
