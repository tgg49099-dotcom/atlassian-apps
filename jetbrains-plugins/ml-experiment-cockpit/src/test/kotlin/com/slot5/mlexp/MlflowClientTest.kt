package com.slot5.mlexp

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests du client MLflow (`MlflowClient`) sur un faux serveur local.
 *
 * Meme methode que `WandbClientTest` : `com.sun.net.httpserver.HttpServer`
 * (du JDK, aucune dependance) repond sur le chemin du client, et le corps
 * envoye est **le corps qui declenche le bug**.
 *
 * v0.0.19 — le bug mesure : le parseur JSON acceptait un nombre mal forme
 * (« 1. »). Le nombre produit etait illisible et la lecture du champ
 * `start_time` levait une `NumberFormatException` **non maitrisee** : elle
 * traversait `MlflowClient.parse()` (qui n'attrape que `JsonException`) et
 * sortait de `listRuns()` sous la forme « For input string: "1." ».
 * L'utilisateur voyait « Unexpected error: For input string: "1." » — un
 * message qui accuse le programme alors que le serveur a repondu du JSON
 * invalide.
 *
 * Ce test est **ROUGE** sur le code d'avant la correction (l'exception levee
 * est une `NumberFormatException`, pas une `MlflowException`) et **VERT**
 * apres : le message devient « reponse non JSON du serveur ».
 */
class MlflowClientTest {

    /** Faux serveur MLflow qui repond `body` sur n'importe quel chemin. */
    private fun fakeMlflow(body: String): Pair<HttpServer, String> {
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

    @Test
    @DisplayName("un nombre mal forme donne « reponse non JSON du serveur », pas une erreur de conversion")
    fun malformedNumberIsReportedAsInvalidJson() {
        // `start_time` vaut « 1. » : ce n'est pas un nombre JSON valide.
        val body = """{"runs":[{"info":{"run_id":"abc123","status":"FINISHED","start_time":1.}}]}"""
        val (server, base) = fakeMlflow(body)
        try {
            val client = MlflowClient(base)
            val e = assertThrows(MlflowException::class.java) {
                client.listRuns("2")
            }
            val message = e.message ?: ""
            assertTrue(
                message.contains("reponse non JSON du serveur"),
                "le message doit dire que la reponse n'est pas du JSON, il dit : $message"
            )
            assertTrue(
                message.contains("nombre"),
                "le message doit nommer la cause (le nombre), il dit : $message"
            )
        } finally {
            server.stop(0)
        }
    }

    // ---------------------------------------------------------------------
    // Tests de Jules (revue croisee, tache 13614161511429318484) — recus par
    // le patch /work/jules/13614161511429318484.patch.
    //
    // Portage mecanique JUnit 4 -> JUnit 5 (30/10/2026, cycle 87) :
    // `org.junit.Assert.*` / `org.junit.Test` -> `org.junit.jupiter.api.*`.
    // Le projet ne declare `junit:junit:4.13.2` qu'en `testRuntimeOnly` et
    // n'a pas de `junit-vintage-engine` : sans portage, ces tests ne seraient
    // ni compiles ni EXECUTES (ils passeraient en silence).
    //
    // Ils ne testent pas le code de production : ils verifient leurs propres
    // expressions regulieres locales. Conserves tels quels.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Jules - nombre en notation scientifique dans un JSON compact")
    fun testRegexParsingScientificNotationAndCompactJson() {
        // MLflow emits compact JSON without spaces around colons/commas
        val json = """{"metrics":[{"key":"learning_rate","value":1e-05,"timestamp":1600000000000,"step":0},{"key":"loss","value":-1.5E+02,"timestamp":1600000001000,"step":1}]}"""

        val historyPointRegex = Regex(
            """\{[^}]*"value":(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)[^}]*"timestamp":(\d+)[^}]*"step":(\d+)"""
        )

        val matches = historyPointRegex.findAll(json).toList()
        assertEquals(2, matches.size)

        assertEquals("1e-05", matches[0].groupValues[1])
        assertEquals("1600000000000", matches[0].groupValues[2])
        assertEquals("0", matches[0].groupValues[3])

        assertEquals("-1.5E+02", matches[1].groupValues[1])
        assertEquals("1600000001000", matches[1].groupValues[2])
        assertEquals("1", matches[1].groupValues[3])
    }

    @Test
    @DisplayName("Jules - nom de modele dans un JSON compact")
    fun testModelVersionParsingCompactJson() {
        // Compact JSON emitted by MLflow 2.x
        val modelBlock = """{"name":"my_model","version":"1","creation_timestamp":1700000000000,"current_stage":"Production","status":"READY"}"""

        val regModelNameRegex = Regex(""""name":"([^"]+)"""")
        val match = regModelNameRegex.find(modelBlock)
        assertNotNull(match)
        assertEquals("my_model", match!!.groupValues[1])
    }
}
