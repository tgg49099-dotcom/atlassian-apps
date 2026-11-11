package com.slot5.mlexp

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * **Les fils de la plateforme** — jury 11/11 (amelioration 5).
 *
 * Le jury demande d'utiliser les pools de fils de la plateforme
 * (`executeOnPooledThread`) au lieu de `Thread(...)` brut, comme le
 * recommande JetBrains.
 *
 * Ces tests ne testent pas une fonctionnalite : ils **comptent les fils**.
 * Ils lisent les SOURCES du plugin (le code livre) et verifient :
 *
 *   - F1  plus aucun `Thread(...)` brut dans `src/main/kotlin` ;
 *   - F2  plus aucun demarrage manuel de thread (`).start()`) ;
 *   - F3  les 7 fichiers qui lancaient un fil utilisent tous
 *        `executeOnPooledThread` ;
 *   - F4  le nombre de fils de plateforme est exactement 11 (le compte
 *        mesure : 3 dans le panneau Compare, 2 dans l'onglet Experiments,
 *        2 dans l'onglet Optuna, 1 dans Models, 1 dans Sweeps, 1 dans la
 *        courbe, 1 dans l'action « Connect »).
 *
 * **Contre-epreuve** : ce fichier est ROUGE sur le code d'avant la
 * conversion (10 `Thread(...)` bruts, 0 `executeOnPooledThread`) et VERT sur
 * le code d'apres. Preuves : /work/preuves/cycle167/.
 */
class FilsPlateformeTest {

    /** The 7 files that used to start a raw thread. */
    private val fichiersAvecFil = listOf(
        "src/main/kotlin/com/slot5/mlexp/pro/PanneauComparaison.kt",
        "src/main/kotlin/com/slot5/mlexp/SweepToolWindowPanel.kt",
        "src/main/kotlin/com/slot5/mlexp/ConnectToMlflowAction.kt",
        "src/main/kotlin/com/slot5/mlexp/MlflowToolWindowPanel.kt",
        "src/main/kotlin/com/slot5/mlexp/MetricsChartPanel.kt",
        "src/main/kotlin/com/slot5/mlexp/ModelRegistryToolWindowPanel.kt",
        "src/main/kotlin/com/slot5/mlexp/OptunaToolWindowPanel.kt"
    )

    /** `Thread(` but NOT `executeOnPooledThread(` / `getActionUpdateThread(`. */
    private val threadBrut = Regex("(?<![A-Za-z0-9_])Thread\\s*\\(")

    /** The sources of the plugin. */
    private fun sourcesKotlin(): List<File> =
        File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .toList()

    /**
     * The code without the comments: a comment is allowed to mention
     * `Thread(...)` (it explains what was replaced).
     */
    private fun sansCommentaires(source: String): String =
        source.lines().joinToString("\n") { ligne ->
            val i = ligne.indexOf("//")
            // "//" of a URL ("https://...") is not a comment: it is kept.
            if (i >= 0 && (i == 0 || ligne[i - 1] != ':')) ligne.substring(0, i) else ligne
        }

    @Test
    @DisplayName("F1 - no raw Thread(...) left in src/main/kotlin")
    fun aucunThreadBrut() {
        val coupables = mutableListOf<String>()
        for (f in sourcesKotlin()) {
            val code = sansCommentaires(f.readText(Charsets.UTF_8))
            for (m in threadBrut.findAll(code)) {
                val ligne = code.substring(0, m.range.first).count { it == '\n' } + 1
                coupables.add("${f.name}:$ligne")
            }
        }
        assertTrue(
            coupables.isEmpty(),
            "the plugin must not create raw threads any more (use executeOnPooledThread): $coupables"
        )
    }

    @Test
    @DisplayName("F2 - no manual thread start (...).start() left in src/main/kotlin")
    fun aucunDemarrageManuel() {
        val coupables = mutableListOf<String>()
        for (f in sourcesKotlin()) {
            val code = sansCommentaires(f.readText(Charsets.UTF_8))
            if (code.contains(").start()")) {
                coupables.add(f.name)
            }
        }
        assertTrue(
            coupables.isEmpty(),
            "no thread must be started by hand any more: $coupables"
        )
    }

    @Test
    @DisplayName("F3 - every file that used to start a thread now uses the platform's pool")
    fun lesSeptFichiersUtilisentLePool() {
        for (chemin in fichiersAvecFil) {
            val f = File(chemin)
            assertTrue(f.exists(), "the file $chemin must exist")
            val code = sansCommentaires(f.readText(Charsets.UTF_8))
            assertTrue(
                code.contains("executeOnPooledThread"),
                "$chemin must use executeOnPooledThread (the platform's pooled threads)"
            )
        }
    }

    @Test
    @DisplayName("F4 - the plugin starts exactly 11 background jobs, all on the platform's pool")
    fun onzeFilsDePlateforme() {
        var total = 0
        for (f in sourcesKotlin()) {
            val code = sansCommentaires(f.readText(Charsets.UTF_8))
            total += Regex("executeOnPooledThread\\s*\\{").findAll(code).count()
        }
        assertEquals(
            11, total,
            "the measured number of background jobs is 11 (3 Compare, 2 Experiments, 2 Optuna, 1 Models, 1 Sweeps, 1 chart, 1 action)"
        )
    }
}
