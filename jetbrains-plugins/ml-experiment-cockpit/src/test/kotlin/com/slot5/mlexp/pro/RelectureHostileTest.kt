package com.slot5.mlexp.pro

import com.slot5.mlexp.MlflowClient
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * **Relecture hostile** du chemin PAYANT (palier Pro n°1) — cycle 158.
 *
 * Ces tests ne testent pas une fonctionnalité : ils **cherchent la panne**.
 * Chaque cas est un cas limite demandé explicitement par la relecture
 * hostile (10/11/2026) :
 *
 *   - H1  deux runs dont les métriques **ne se recouvrent pas** ;
 *   - H2  un run **sans paramètres** ;
 *   - H3  une métrique **absente** de l'un des deux runs (dans les 2 sens) ;
 *   - H4  **100 000 points** par métrique (le plafond Pro) ;
 *   - H5  une valeur de référence **nulle** (division par zéro) ;
 *   - H6  deux runs **sans aucune métrique** ;
 *   - H7  un `run_id` **vide** ;
 *   - H8  une durée **absente, négative, < 1 s, < 1 min, >= 1 h** ;
 *   - H9  des nombres **extrêmes** (NaN, infini, 1e-7, 1e12) ;
 *   - H10 un Markdown **vide** (2 en-têtes, 0 ligne).
 *
 * Aucun test de ce fichier ne doit échouer : le but est de **prouver** que
 * le chemin payant ne plante pas sur ces cas, pas d'ajouter une fonction.
 */
class RelectureHostileTest {

    private fun comparaison(a: RunRef, b: RunRef): ResultatComparaison.Comparaison {
        val r = ComparaisonRuns.calculer(listOf(a, b), ProState.PRO)
        assertTrue(r is ResultatComparaison.Comparaison, "the comparison must succeed: $r")
        return r as ResultatComparaison.Comparaison
    }

    private fun run(id: String, metriques: Map<String, Double>, params: Map<String, String> = emptyMap()) =
        RunRef(
            id = id,
            nom = "run-$id",
            statut = "FINISHED",
            startTimeMs = 1_700_000_000_000L,
            endTimeMs = 1_700_000_060_000L,
            origine = "http://mlflow:5000",
            params = params,
            metriques = metriques
        )

    // ------------------------------------------------------------------
    // H1 - two runs whose metrics do not overlap
    // ------------------------------------------------------------------

    @Test
    @DisplayName("H1 - two runs whose metrics do not overlap: no common metric, no crash, no gap invented")
    fun deuxRunsSansMetriqueCommune() {
        val a = run("a1", mapOf("loss" to 0.5))
        val b = run("b1", mapOf("acc" to 0.8))
        val c = comparaison(a, b)

        assertEquals(2, c.metriques.size, "the 2 metric rows must be displayed")
        assertTrue(c.metriquesCommunes.isEmpty(), "no metric is common: the selector must stay empty")
        val loss = c.metriques.first { it.cle == "loss" }
        val acc = c.metriques.first { it.cle == "acc" }
        assertEquals(EtatComparaison.ABSENT_B, loss.etat)
        assertEquals(EtatComparaison.ABSENT_A, acc.etat)
        assertNull(loss.ecartAbsolu, "no gap can be computed on a missing value")
        assertNull(acc.ecartRelatifPct, "no relative gap can be computed on a missing value")
        assertEquals(2, c.nombreEcarts, "the 2 absent metrics are 2 gaps")
        // The Markdown export must not invent a value either.
        val md = c.markdown()
        assertTrue(md.contains("| loss | 0,5 | - | - | - | absent from B |"), md)
        assertTrue(md.contains("| acc | - | 0,8 | - | - | absent from A |"), md)
    }

    // ------------------------------------------------------------------
    // H2 - a run without parameters
    // ------------------------------------------------------------------

    @Test
    @DisplayName("H2 - a run without parameters: the other run's parameters are marked \"absent from A\"")
    fun unRunSansParametres() {
        val a = run("a1", mapOf("acc" to 0.8), params = emptyMap())
        val b = run("b1", mapOf("acc" to 0.8), params = mapOf("lr" to "0.01"))
        val c = comparaison(a, b)

        assertEquals(1, c.params.size, "the union of the parameter keys is 1")
        val lr = c.params.first()
        assertEquals("lr", lr.cle)
        assertEquals(EtatComparaison.ABSENT_A, lr.etat)
        assertNull(lr.valeurA, "the missing parameter must not become an empty string")
        assertEquals("0.01", lr.valeurB)
        assertEquals(1, c.nombreEcarts, "a parameter missing on one side is a gap")
        assertTrue(c.metriques.first().etat == EtatComparaison.IDENTIQUE)
    }

    // ------------------------------------------------------------------
    // H3 - a metric absent from one of the two runs (both directions)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("H3 - a metric absent from one run: \"absent from A\" and \"absent from B\", never 0.0")
    fun metriqueAbsenteDansLesDeuxSens() {
        val a = run("a1", mapOf("loss" to 0.5, "acc" to 0.8))
        val b = run("b1", mapOf("acc" to 0.9))
        val c = comparaison(a, b)

        val loss = c.metriques.first { it.cle == "loss" }
        assertEquals(EtatComparaison.ABSENT_B, loss.etat)
        assertNull(loss.valeurB)
        assertNull(loss.ecartAbsolu)
        assertNull(loss.ecartRelatifPct)
        assertEquals(1, c.metriquesCommunes.size, "only \"acc\" is common")
        assertEquals("acc", c.metriquesCommunes.first())
    }

    // ------------------------------------------------------------------
    // H4 - 100 000 points per metric (the Pro ceiling)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("H4 - 100 000 points per metric: the Pro ceiling is reached exactly, without truncation")
    fun centMillePoints() {
        val (server, base) = fauxHistorique(total = 100000, page = 10000)
        try {
            val client = MlflowClient(base)
            val debut = System.currentTimeMillis()
            val pro = client.getMetricHistoryAll("run1", "loss", 100000)
            val duree = System.currentTimeMillis() - debut
            assertEquals(100000, pro.points.size, "the 100 000 points must be read")
            assertTrue(!pro.tronque, "the series is complete: it must not be declared truncated")
            assertEquals(99999L, pro.points.last().step, "the last step must be the last one")
            val pas = pro.points.map { it.step }
            assertEquals(pas.sorted(), pas, "the points must be sorted by step")
            assertTrue(duree < 30000, "100 000 points must be read in less than 30 s (measured: ${duree} ms)")
        } finally {
            server.stop(0)
        }
    }

    @Test
    @DisplayName("H4 bis - 100 001 points: the ceiling is reached and the series is declared truncated")
    fun centMilleUnPoints_lePlafondEstDeclare() {
        val (server, base) = fauxHistorique(total = 100001, page = 10000)
        try {
            val client = MlflowClient(base)
            val pro = client.getMetricHistoryAll("run1", "loss", 100000)
            assertEquals(100000, pro.points.size, "the ceiling is 100 000 points")
            assertTrue(pro.tronque, "points are missing: the truncation must be declared, not hidden")
        } finally {
            server.stop(0)
        }
    }

    // ------------------------------------------------------------------
    // H5 - a null reference value (division by zero)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("H5 - a null reference value: the relative gap is undefined, not Infinity")
    fun valeurDeReferenceNulle() {
        val a = run("a1", mapOf("acc" to 0.0))
        val b = run("b1", mapOf("acc" to 0.8))
        val c = comparaison(a, b)
        val acc = c.metriques.first()

        assertEquals(0.8, acc.ecartAbsolu!!, 1e-9, "the absolute gap is still computed")
        assertNull(acc.ecartRelatifPct, "0 % of 0 is undefined: it must be null, never Infinity")
        assertEquals("-", ComparaisonRuns.formatEcartRelatif(acc.ecartRelatifPct) ?: "-")
    }

    // ------------------------------------------------------------------
    // H6 - two runs without any metric
    // ------------------------------------------------------------------

    @Test
    @DisplayName("H6 - two runs without any metric: an empty comparison, no crash")
    fun deuxRunsSansAucuneMetrique() {
        val c = comparaison(run("a1", emptyMap()), run("b1", emptyMap()))
        assertTrue(c.metriques.isEmpty())
        assertTrue(c.metriquesCommunes.isEmpty())
        assertEquals(0, c.nombreEcarts)
        assertTrue(c.markdown().contains("| metric | A | B | absolute gap | relative gap | state |"))
    }

    // ------------------------------------------------------------------
    // H7 - a blank run id
    // ------------------------------------------------------------------

    @Test
    @DisplayName("H7 - a blank run id: the comparison is refused, not computed on a ghost run")
    fun identifiantDeRunVide() {
        val r = ComparaisonRuns.calculer(
            listOf(run("", mapOf("acc" to 0.8)), run("b1", mapOf("acc" to 0.8))),
            ProState.PRO
        )
        assertTrue(r is ResultatComparaison.Refus, "a blank run id must be refused: $r")
        assertEquals(CodeRefus.RUN_INTROUVABLE, (r as ResultatComparaison.Refus).code)
    }

    // ------------------------------------------------------------------
    // H8 - the duration
    // ------------------------------------------------------------------

    @Test
    @DisplayName("H8 - the duration: unknown, negative, under 1 s, under 1 min, over 1 h")
    fun laDuree() {
        assertEquals(
            "-",
            ComparaisonRuns.formatDuree(run("a", emptyMap()).copy(startTimeMs = 0L, endTimeMs = 0L)),
            "an unknown duration is displayed as \"-\""
        )
        assertEquals(
            "-",
            ComparaisonRuns.formatDuree(run("a", emptyMap()).copy(startTimeMs = 2000L, endTimeMs = 1000L)),
            "a duration cannot be negative"
        )
        assertEquals(
            "500 ms",
            ComparaisonRuns.formatDuree(run("a", emptyMap()).copy(startTimeMs = 1000L, endTimeMs = 1500L))
        )
        assertEquals(
            "1,5 s",
            ComparaisonRuns.formatDuree(run("a", emptyMap()).copy(startTimeMs = 1000L, endTimeMs = 2500L))
        )
        assertEquals(
            "1 min 00 s",
            ComparaisonRuns.formatDuree(run("a", emptyMap()).copy(startTimeMs = 1000L, endTimeMs = 61_000L))
        )
        assertEquals(
            "1 h 02 min 00 s",
            ComparaisonRuns.formatDuree(
                run("a", emptyMap()).copy(startTimeMs = 1000L, endTimeMs = 1000L + 3_720_000L)
            )
        )
    }

    // ------------------------------------------------------------------
    // H9 - the extreme numbers
    // ------------------------------------------------------------------

    @Test
    @DisplayName("H9 - the extreme numbers: NaN, infinity, 1e-7, 1e12 are displayed, never lost")
    fun lesNombresExtremes() {
        assertEquals("NaN", ComparaisonRuns.formatNombre(Double.NaN))
        assertEquals("Infinity", ComparaisonRuns.formatNombre(Double.POSITIVE_INFINITY))
        assertEquals("-Infinity", ComparaisonRuns.formatNombre(Double.NEGATIVE_INFINITY))
        assertNull(ComparaisonRuns.formatNombre(null), "a missing value stays missing")

        val petit = ComparaisonRuns.formatNombre(1e-7)
        assertTrue(petit != null && petit.contains("e"), "1e-7 must not become 0, it becomes: $petit")
        val grand = ComparaisonRuns.formatNombre(1e12)
        assertTrue(grand != null && grand.contains("e"), "1e12 must not be truncated, it becomes: $grand")
        assertEquals("100000000000", ComparaisonRuns.formatNombre(1e11))
        assertEquals("0,000001", ComparaisonRuns.formatNombre(1e-6))
        assertEquals("0", ComparaisonRuns.formatNombre(0.0))
    }

    // ------------------------------------------------------------------
    // H10 - the Markdown export of an empty comparison
    // ------------------------------------------------------------------

    @Test
    @DisplayName("H10 - the Markdown export of an empty comparison: the 2 headers, 0 line, no crash")
    fun markdownVide() {
        val c = comparaison(run("a1", emptyMap()), run("b1", emptyMap()))
        val md = c.markdown()
        assertTrue(md.contains("| parameter | A | B | state |"), md)
        assertTrue(md.contains("| metric | A | B | absolute gap | relative gap | state |"), md)
        assertEquals(2, md.lines().count { it.startsWith("|---") }, md)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Fake server that serves a paginated metric history (like the real MLflow). */
    private fun fauxHistorique(total: Int, page: Int): Pair<HttpServer, String> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val query = exchange.requestURI.query ?: ""
            val params = query.split("&").associate {
                val kv = it.split("=", limit = 2)
                kv[0] to java.net.URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8")
            }
            val max = params["max_results"]?.toIntOrNull() ?: total
            val debut = params["page_token"]?.toIntOrNull() ?: 0
            val taille = minOf(max, page, total - debut)
            val points = (debut until debut + taille).joinToString(",") { i ->
                """{"key":"loss","value":${i.toDouble()},"timestamp":${1700000000000L + i},"step":$i}"""
            }
            val suivant = if (debut + taille < total) """"next_page_token":"${debut + taille}",""" else ""
            val body = """{$suivant"metrics":[$points]}""".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        return server to "http://127.0.0.1:${server.address.port}"
    }
}
