package com.slot5.mlexp

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests du client W&B (`WandbClient`) sur des réponses **reelles** rejouées.
 *
 * Pourquoi un faux serveur local : le client ne prend pas de `HttpClient`
 * injectable, et un test qui n'appellerait pas le vrai chemin
 * `POST <base>/graphql` ne testerait rien. Le serveur ci-dessous est un
 * `com.sun.net.httpserver.HttpServer` du **JDK** (aucune dependance), il repond
 * sur `/graphql` exactement comme le fait `api.wandb.ai`, et les corps
 * utilises sont **les reponses reelles mesurees** (journal
 * `/work/research/cycle54/`, 24/10/2026).
 *
 * Les deux tests `A` et `B` figent les **deux bugs corriges le 24/10/2026**
 * (jury `6868`) :
 *  - `A` : projet inexistant → W&B repond **HTTP 200** avec
 *    `{"data":{"project":null}}` et **sans** tableau `"errors"` → le client
 *    rendait une liste vide (« Loaded 0 sweep(s) »), un mensonge silencieux ;
 *    il doit maintenant lever « project not found » ;
 *  - `B` : une erreur GraphQL doit montrer **le message du serveur**
 *    (« Cannot query field ... »), pas un « GraphQL error » nu — le code
 *    lisait `first.obj("message").str("message")` alors que W&B envoie une
 *    **chaine**.
 *
 * Ces deux tests sont **ROUGES** sur le JAR 0.0.16 (code d'avant la
 * correction) et **VERTS** sur le code courant : c'est la preuve demandee,
 * journal `/work/research/cycle58/tests_kotlin_red_0.0.16.log` puis
 * `/work/research/cycle58/tests_kotlin_green.log`.
 */
class WandbClientTest {

    /**
     * Demarre un faux serveur W&B qui repond `body` avec le code HTTP `status`
     * sur `POST /graphql`, et rend l'URL de base a utiliser.
     */
    private fun fakeWandb(body: String, status: Int = 200): Pair<HttpServer, String> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/graphql") { exchange ->
            // On lit la requete pour que le client n'ait pas de "broken pipe".
            exchange.requestBody.use { it.readBytes() }
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server to "http://127.0.0.1:${server.address.port}"
    }

    @Test
    @DisplayName("A - projet inexistant : « project not found », pas une liste vide")
    fun unknownProjectIsReportedAsNotFound() {
        // Reponse REELLE de api.wandb.ai pour un projet inexistant :
        // HTTP 200, {"data":{"project":null}}, AUCUN tableau "errors".
        val (server, base) = fakeWandb("""{"data":{"project":null}}""")
        try {
            val client = WandbClient(base, "fake-key")
            val e = assertThrows(WandbException::class.java) {
                client.listSweeps("wandb", "project-qui-nexiste-pas")
            }
            val message = e.message ?: ""
            assertTrue(
                message.contains("project not found"),
                "le message doit dire « project not found », il dit : $message"
            )
            assertTrue(
                message.contains("project-qui-nexiste-pas"),
                "le message doit nommer le projet, il dit : $message"
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    @DisplayName("B - erreur GraphQL : le message du serveur est montre")
    fun graphQlErrorShowsTheServerMessage() {
        // Reponse REELLE de api.wandb.ai quand le schema refuse un champ.
        val body = """{"errors":[{"message":"Cannot query field \"bestRun\" on type \"Sweep\"."}]}"""
        val (server, base) = fakeWandb(body, status = 400)
        try {
            val client = WandbClient(base, "fake-key")
            val e = assertThrows(WandbException::class.java) {
                client.listSweeps("wandb", "un-projet")
            }
            val message = e.message ?: ""
            assertTrue(
                message.contains("Cannot query field"),
                "le message du serveur doit etre montre, il dit : $message"
            )
            assertTrue(
                !message.contains("GraphQL error:"),
                "le message ne doit pas etre un « GraphQL error » nu, il dit : $message"
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    @DisplayName("C - un sweep reel est lu (garde-fou de non-regression)")
    fun aRealSweepIsParsed() {
        val body = """
            {"data":{"project":{"sweeps":{"edges":[
              {"node":{"id":"sweep-1","name":"demo","state":"RUNNING",
                       "createdAt":"2026-10-24T10:00:00Z","updatedAt":"2026-10-24T11:00:00Z",
                       "runs":{"totalCount":12}}}
            ]}}}}
        """.trimIndent()
        val (server, base) = fakeWandb(body)
        try {
            val client = WandbClient(base, "fake-key")
            val sweeps = client.listSweeps("wandb", "sweep-demo")
            assertEquals(1, sweeps.size)
            assertEquals("sweep-1", sweeps[0].id)
            assertEquals("demo", sweeps[0].name)
            // W&B repond en MAJUSCULES, l'arbre regroupe sur la forme minuscule.
            assertEquals("running", sweeps[0].state)
        } finally {
            server.stop(0)
        }
    }
}
