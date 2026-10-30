package com.slot5.mlexp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests **de Jules** (revue croisee, tache 13614161511429318484) sur le
 * client Optuna. Recus par le patch
 * `/work/jules/13614161511429318484.patch` (fichier `src/test/.../OptunaClientTest.kt`).
 *
 * **Portage mecanique JUnit 4 -> JUnit 5** (30/10/2026, cycle 87) :
 * `org.junit.Assert.*` / `org.junit.Test` -> `org.junit.jupiter.api.Assertions.*` /
 * `org.junit.jupiter.api.Test`. Aucune autre modification : les assertions, les
 * corps de test et les commentaires sont ceux de Jules.
 * *Pourquoi :* le projet ne declare que `org.junit.jupiter:junit-jupiter:5.10.2`
 * en dependance de **compilation** de test (`build.gradle.kts`) ; `junit:junit:4.13.2`
 * n'est qu'en `testRuntimeOnly` (pour le `TestRule` de l'initialiseur
 * d'environnement d'IntelliJ) et il n'y a **pas** de `junit-vintage-engine` :
 * un test JUnit 4 ne serait ni compile ni **execute** (il serait passe sous silence).
 * Portage = aucune dependance ajoutee, et les tests sont reellement joues.
 *
 * **Ce que ces tests ne font PAS** (constat de la revue du cycle 87, a lire) :
 * ils ne testent **pas** le code de production. Ils redefinissent leurs propres
 * expressions regulieres **en local** et verifient ces regex-la, jamais le
 * parseur du plugin (`JsonParser`, `OptunaClient.firstDouble`). Ils passent donc
 * que le plugin soit correct ou non — ils ne peuvent pas servir de contre-epreuve.
 * Le test « testPickBarChartInversionLogic » est le plus instructif : il
 * verifie que l'ancien et le nouvel ordre de tri donnent **la meme** reponse
 * (« lr ») sur ses propres donnees, donc il ne distingue pas les deux
 * algorithmes. La contre-epreuve reelle de ce defaut est dans
 * `Regression0020Test` (cas ou les deux criteres divergent).
 *
 * Ils sont conserves tels quels : c'est un travail de revue utile, et les
 * garder evite de perdre une trace de la revue croisee.
 */
class OptunaClientTest {

    @Test
    @DisplayName("Jules - valeurs Optuna : regex sur inf / -inf / nan / nombre")
    fun testOptunaValuesArrayRegexInfsAndNans() {
        val valuesRegex = Regex("""\[(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|nan|-?inf)""")

        val jsonInf = """{"values":[inf]}"""
        val jsonNegInf = """{"values":[-inf]}"""
        val jsonNan = """{"values":[nan]}"""
        val jsonNum = """{"values":[3.14159]}"""

        val m1 = valuesRegex.find(jsonInf)
        assertNotNull(m1)
        assertEquals("inf", m1!!.groupValues[1])

        val m2 = valuesRegex.find(jsonNegInf)
        assertNotNull(m2)
        assertEquals("-inf", m2!!.groupValues[1])

        val m3 = valuesRegex.find(jsonNan)
        assertNotNull(m3)
        assertEquals("nan", m3!!.groupValues[1])

        val m4 = valuesRegex.find(jsonNum)
        assertNotNull(m4)
        assertEquals("3.14159", m4!!.groupValues[1])
    }

    @Test
    @DisplayName("Jules - pickBarChart : l'ancien et le nouvel ordre de tri (meme reponse sur ces donnees)")
    fun testPickBarChartInversionLogic() {
        val trial1 = Trial(
            number = 1,
            value = 0.5,
            params = mapOf("lr" to "0.001", "optimizer" to "adam"),
            state = "Complete",
            datetimeStart = "",
            datetimeComplete = ""
        )
        val trial2 = Trial(
            number = 2,
            value = 0.6,
            params = mapOf("lr" to "0.01", "optimizer" to "adam"),
            state = "Complete",
            datetimeStart = "",
            datetimeComplete = ""
        )
        val trial3 = Trial(
            number = 3,
            value = 0.7,
            params = mapOf("lr" to "0.1", "optimizer" to "adam"),
            state = "Complete",
            datetimeStart = "",
            datetimeComplete = ""
        )

        val trials = listOf(trial1, trial2, trial3)

        // Optimizer has 1 distinct value ("adam"), non-numeric
        // lr has 3 distinct values ("0.001", "0.01", "0.1"), numeric
        val allParams = trials.flatMap { it.params.entries }.groupBy({ it.key }, { it.value })

        data class Cand(val name: String, val distinct: Int, val numeric: Boolean)
        val candidates = allParams.map { (k, vs) ->
            Cand(
                name = k,
                distinct = vs.toSet().size,
                numeric = vs.all { v -> v.toDoubleOrNull() != null }
            )
        }

        // Buggy 0.0.19 logic: compares numeric first, then distinct
        val buggyBest = candidates
            .sortedWith(compareByDescending<Cand> { it.numeric }.thenByDescending { it.distinct })
            .first()

        // Intended logic: compares distinct first, then numeric
        val intendedBest = candidates
            .sortedWith(compareByDescending<Cand> { it.distinct }.thenByDescending { it.numeric })
            .first()

        assertEquals("lr", buggyBest.name)
        assertEquals("lr", intendedBest.name)
    }
}
