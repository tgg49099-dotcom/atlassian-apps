package com.slot5.mlexp

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Contre-epreuves de la version **0.0.20** : un test par defaut corrige.
 *
 * Chaque test de ce fichier est **ROUGE sur le code d'avant la correction** et
 * **VERT sur le code d'apres**. Ce sont les quatre defauts que la revue a
 * mesures sur la 0.0.19 en ligne (plugin 35416) :
 *
 *  1. l'arbre des onglets **Optuna** et **Models** affichait le dump Kotlin
 *     brut (`StudyNode(summary=...)`, `RegisteredModelNode(model=...)`) au lieu
 *     du libelle ;
 *  2. `pickBarChart` appliquait l'**inverse** de son propre algorithme (le
 *     critere « numerique » primait sur « le plus de valeurs distinctes ») ;
 *  3. `OptunaClient.get()` construisait la requete **hors du `try`** : une URI
 *     mal saisie remontait en `IllegalArgumentException` et l'onglet affichait
 *     « Unexpected error » au lieu de « Failed to load studies » ;
 *  4. les valeurs `inf` / `-inf` d'Optuna (envoyees **en chaine** par le
 *     dashboard) etaient lues `null` : le trial affichait « — » et sortait du
 *     classement.
 *
 * Aucun de ces tests ne depend de l'IDE : ils n'utilisent que le JDK
 * (`com.sun.net.httpserver.HttpServer`) et JUnit 5.
 */
class Regression0020Test {

    // ---------------------------------------------------------------------
    // Defaut 1 — l'arbre affiche le libelle, pas le dump Kotlin
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("1 - les noeuds de l'arbre Optuna/Models affichent leur libelle, pas « StudyNode(… »")
    fun treeNodesShowTheirLabelNotTheKotlinDump() {
        // --- onglet Optuna ---
        val studyNode = OptunaToolWindowPanel.StudyNode(
            StudySummary(id = 7L, name = "demo", direction = "minimize")
        )
        val studyText = studyNode.toString()
        assertFalse(
            studyText.contains("StudyNode("),
            "l'arbre Optuna affiche encore le dump Kotlin : $studyText"
        )
        assertFalse(
            studyText.contains("summary="),
            "l'arbre Optuna affiche encore le dump Kotlin : $studyText"
        )
        assertTrue(
            studyText.contains("demo"),
            "l'libelle doit contenir le nom de l'etude : $studyText"
        )

        // --- onglet Models ---
        val version = ModelVersion(
            name = "bert",
            version = "2",
            currentStage = "Production",
            runId = "abc123",
            status = "READY",
            creationTimestampMs = 1_700_000_000_000L
        )
        val model = RegisteredModel(
            name = "bert",
            description = "un modele",
            creationTimestampMs = 1_700_000_000_000L,
            latestVersions = listOf(version)
        )
        val modelNode = ModelRegistryToolWindowPanel.RegisteredModelNode(model, "bert (1 version(s))")
        val modelText = modelNode.toString()
        assertFalse(
            modelText.contains("RegisteredModelNode("),
            "l'arbre Models affiche encore le dump Kotlin : $modelText"
        )
        assertEquals("bert (1 version(s))", modelText)

        val versionNode = ModelRegistryToolWindowPanel.ModelVersionNode(version, "v2 [Production]")
        val versionText = versionNode.toString()
        assertFalse(
            versionText.contains("ModelVersionNode("),
            "l'arbre Models affiche encore le dump Kotlin : $versionText"
        )
        assertEquals("v2 [Production]", versionText)
    }

    // ---------------------------------------------------------------------
    // Defaut 2 — l'ordre des criteres de pickBarChart
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("2 - pickBarChart prefere « le plus de valeurs distinctes » a « numerique »")
    fun pickBarChartPrefersDistinctValuesOverNumeric() {
        // Cas ou les DEUX criteres divergent :
        //   learning_rate : 1 seule valeur, mais numerique  -> numeric = true,  distinct = 1
        //   optimizer     : 3 valeurs, mais des chaines      -> numeric = false, distinct = 3
        // L'algorithme annonce (et la spec d'origine) dit : « le plus de
        // valeurs distinctes » d'abord, « numerique » seulement en cas
        // d'egalite. L'ancien code faisait l'inverse et choisissait
        // `learning_rate`.
        val trials = listOf(
            trial(0, mapOf("learning_rate" to "0.01", "optimizer" to "adam")),
            trial(1, mapOf("learning_rate" to "0.01", "optimizer" to "sgd")),
            trial(2, mapOf("learning_rate" to "0.01", "optimizer" to "rmsprop"))
        )
        val bar = OptunaToolWindowPanel.pickBarChart(trials)
        assertEquals(
            "optimizer",
            bar.paramName,
            "pickBarChart doit choisir le parametre qui prend le plus de valeurs distinctes"
        )

        // Cas d'egalite : le tie-breaker « numerique » tranche (regle 2).
        val tied = listOf(
            trial(0, mapOf("alpha" to "0.1", "beta" to "adam")),
            trial(1, mapOf("alpha" to "0.2", "beta" to "adam"))
        )
        assertEquals(
            "alpha",
            OptunaToolWindowPanel.pickBarChart(tied).paramName,
            "a nombre de valeurs distinctes egal, le parametre numerique doit gagner"
        )
    }

    // ---------------------------------------------------------------------
    // Defaut 3 — une URI mal saisie doit donner « Failed to load studies »
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("3 - une URI Optuna invalide donne « Failed to load studies », pas « Unexpected error »")
    fun invalidOptunaUriIsReportedAsAFailedLoad() {
        // URI mal saisie : il manque le schema « http:// ». `URI.create()`
        // accepte la chaine (scheme = « localhost »), mais c'est
        // `HttpRequest.Builder.build()` qui leve
        // `IllegalArgumentException("invalid URI scheme localhost")`.
        val client = OptunaClient("localhost:8080")

        // L'onglet Optuna traite DEUX types d'exception :
        //   catch (e: OptunaException) -> « Failed to load studies: … »
        //   catch (t: Throwable)        -> « Unexpected error: … »
        // Le type de l'exception est donc exactement ce qui decide du message
        // affiche a l'utilisateur : on exige une `OptunaException`.
        val e = assertThrows(OptunaException::class.java) {
            client.listStudies()
        }
        val message = e.message ?: ""
        assertTrue(
            message.contains("invalid URI scheme"),
            "le message doit dire quelle URI est refusee, il dit : $message"
        )
        assertTrue(
            message.contains("localhost:8080") || message.contains("localhost"),
            "le message doit nommer l'URI refusee, il dit : $message"
        )
    }

    // ---------------------------------------------------------------------
    // Defaut 4 — les valeurs inf / -inf envoyees en chaine
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("4 - une valeur « inf » / « -inf » envoyee en chaine vaut ±Infinity")
    fun infinitySentAsAStringIsReadAsInfinity() {
        // Corps REEL du dashboard Optuna : Python serialise `float('inf')` en
        // la chaine « inf » (et `float('-inf')` en « -inf »), donc la valeur
        // arrive dans le JSON sous forme de chaine, pas de nombre.
        val body = """
            {
              "name": "demo",
              "directions": ["minimize"],
              "datetime_start": "2026-10-01 00:00:00",
              "trials": [
                {"number": 0, "state": "Complete", "values": ["inf"],
                 "params": [{"name": "lr", "param_external_value": "0.01"}],
                 "datetime_start": "2026-10-01 00:00:00"},
                {"number": 1, "state": "Complete", "values": ["-inf"],
                 "params": [{"name": "lr", "param_external_value": "0.02"}],
                 "datetime_start": "2026-10-01 00:01:00"},
                {"number": 2, "state": "Complete", "values": [0.5],
                 "params": [{"name": "lr", "param_external_value": "0.03"}],
                 "datetime_start": "2026-10-01 00:02:00"}
              ]
            }
        """.trimIndent()
        val (server, base) = fakeOptuna(body)
        try {
            val study = OptunaClient(base).getStudy(1L)
            assertEquals(3, study.trials.size)

            val positive = study.trials[0].value
            assertNotNull(positive, "la valeur « inf » ne doit pas etre nulle")
            assertEquals(Double.POSITIVE_INFINITY, positive!!, "« inf » doit valoir +Infinity")

            val negative = study.trials[1].value
            assertNotNull(negative, "la valeur « -inf » ne doit pas etre nulle")
            assertEquals(Double.NEGATIVE_INFINITY, negative!!, "« -inf » doit valoir -Infinity")

            // Une vraie valeur numerique n'est pas affectee.
            assertEquals(0.5, study.trials[2].value!!, 0.0)
        } finally {
            server.stop(0)
        }
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    /** Un trial Optuna minimal. */
    private fun trial(number: Int, params: Map<String, String>): Trial = Trial(
        number = number,
        value = 0.5,
        params = params,
        state = "Complete",
        datetimeStart = "2026-10-01 00:00:00",
        datetimeComplete = "2026-10-01 00:00:01"
    )

    /** Faux dashboard Optuna qui repond `body` sur n'importe quel chemin. */
    private fun fakeOptuna(body: String): Pair<HttpServer, String> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server to "http://127.0.0.1:${server.address.port}"
    }
}
