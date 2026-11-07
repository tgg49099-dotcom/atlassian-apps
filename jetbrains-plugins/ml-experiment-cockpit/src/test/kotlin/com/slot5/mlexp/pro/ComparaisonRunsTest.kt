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
 * Tests of the **comparison core** of Pro tier 1 ("Compare 2 runs").
 *
 * v0.0.22. These tests only depend on the JDK and JUnit 5: no MLflow server,
 * no network (the fake server is a `com.sun.net.httpserver.HttpServer`, as in
 * the existing tests).
 *
 * Each test bites on a **behaviour** of the plan
 * (`/work/cockpit-pro-n1-plan.md` section 4):
 *   4. two different runs -> the table shows the gaps;
 *   5. two identical runs -> 0 gap;
 *   6. a missing metric -> state "absent from B", never 0.0;
 *   7. two different servers -> reasoned refusal;
 *   8. 5 000 points -> less than 2 s;
 *   9. beyond 5 000 points -> the Pro paginates, the free tier stays capped;
 *  10. more than 2 runs -> explicit refusal;
 *  11. the Markdown copy contains the header and the expected rows.
 */
class ComparaisonRunsTest {

    // ------------------------------------------------------------------
    // Test data
    // ------------------------------------------------------------------

    private fun runA() = RunRef(
        id = "a1",
        nom = "adamw-lr0.01",
        statut = "FINISHED",
        startTimeMs = 1_700_000_000_000L,
        endTimeMs = 1_700_000_060_000L,
        origine = "http://mlflow:5000",
        params = mapOf("lr" to "0.01", "batch_size" to "32"),
        metriques = mapOf("acc" to 0.80, "loss" to 0.5)
    )

    private fun runB() = RunRef(
        id = "b2",
        nom = "adamw-lr0.05",
        statut = "FINISHED",
        startTimeMs = 1_700_000_000_000L,
        endTimeMs = 1_700_000_120_000L,
        origine = "http://mlflow:5000",
        params = mapOf("lr" to "0.05", "batch_size" to "32"),
        metriques = mapOf("acc" to 0.86, "loss" to 0.5)
    )

    private fun comparaison(a: RunRef, b: RunRef): ResultatComparaison.Comparaison {
        val r = ComparaisonRuns.calculer(listOf(a, b), ProState.PRO)
        assertTrue(r is ResultatComparaison.Comparaison, "the comparison must succeed: $r")
        return r as ResultatComparaison.Comparaison
    }

    // ------------------------------------------------------------------
    // Test 4 - two different runs
    // ------------------------------------------------------------------

    @Test
    @DisplayName("4 - two different runs: the table shows the gaps (lr and acc, +7,50 %)")
    fun deuxRunsDifferents_leTableauMontreLesEcarts() {
        val c = comparaison(runA(), runB())

        // 1 parameter gap (lr) + 1 metric gap (acc) = 2.
        assertEquals(2, c.nombreEcarts, "there must be exactly 2 gaps")
        assertEquals(1, c.ecartsParams.size)
        assertEquals(1, c.ecartsMetriques.size)

        val lr = c.params.first { it.cle == "lr" }
        assertEquals(EtatComparaison.DIFFERENT, lr.etat)
        assertEquals("0.01", lr.valeurA)
        assertEquals("0.05", lr.valeurB)

        val acc = c.metriques.first { it.cle == "acc" }
        assertEquals(EtatComparaison.DIFFERENT, acc.etat)
        assertEquals(0.06, acc.ecartAbsolu!!, 1e-9, "absolute gap = 0,86 - 0,80")
        assertEquals(7.50, acc.ecartRelatifPct!!, 1e-9, "relative gap = (0,86 - 0,80) / 0,80")
        assertEquals("+7,50 %", ComparaisonRuns.formatEcartRelatif(acc.ecartRelatifPct))

        // The identical parameter is still displayed, but it is not a gap.
        val batch = c.params.first { it.cle == "batch_size" }
        assertEquals(EtatComparaison.IDENTIQUE, batch.etat)
    }

    // ------------------------------------------------------------------
    // Test 5 - two identical runs
    // ------------------------------------------------------------------

    @Test
    @DisplayName("5 - two identical runs: 0 gap, state \"identical\" everywhere")
    fun deuxRunsIdentiques_leTableauEstVideDEcarts() {
        val a = runA()
        val b = runB().copy(params = a.params, metriques = a.metriques)
        val c = comparaison(a, b)

        assertEquals(0, c.nombreEcarts, "2 identical runs must produce no gap")
        assertTrue(c.ecartsParams.isEmpty())
        assertTrue(c.ecartsMetriques.isEmpty())
        assertTrue(c.params.all { it.etat == EtatComparaison.IDENTIQUE })
        assertTrue(c.metriques.all { it.etat == EtatComparaison.IDENTIQUE })
    }

    // ------------------------------------------------------------------
    // Test 6 - a metric missing from one of the 2 runs
    // ------------------------------------------------------------------

    @Test
    @DisplayName("6 - a metric missing from B is marked \"absent from B\", never 0.0")
    fun metriquesManquantes_leTableauMarqueAbsent() {
        val b = runB().copy(metriques = mapOf("acc" to 0.86))
        val c = comparaison(runA(), b)

        val loss = c.metriques.first { it.cle == "loss" }
        assertEquals(EtatComparaison.ABSENT_B, loss.etat)
        assertEquals(0.5, loss.valeurA!!, 1e-9)
        assertNull(loss.valeurB, "the missing value must not become 0.0")
        assertNull(loss.ecartAbsolu, "no gap when a value is missing")
        assertNull(loss.ecartRelatifPct, "no relative gap when a value is missing")
        assertEquals("-", ComparaisonRuns.formatNombre(loss.valeurB) ?: "-")
    }

    // ------------------------------------------------------------------
    // Test 7 - two different servers
    // ------------------------------------------------------------------

    @Test
    @DisplayName("7 - two runs from 2 different servers: the comparison is refused")
    fun deuxServeursDifferents_laComparaisonEstRefusee() {
        val r = ComparaisonRuns.calculer(
            listOf(runA(), runB().copy(origine = "http://autre:5000")),
            ProState.PRO
        )
        assertTrue(r is ResultatComparaison.Refus, "the comparison must be refused: $r")
        val refus = r as ResultatComparaison.Refus
        assertEquals(CodeRefus.ORIGINES_DIFFERENTES, refus.code)
        assertTrue(
            refus.message.contains("http://mlflow:5000") && refus.message.contains("http://autre:5000"),
            "the message must name the 2 URIs, it says: ${refus.message}"
        )
    }

    // ------------------------------------------------------------------
    // Test 8 - performance on 5 000 points
    // ------------------------------------------------------------------

    @Test
    @DisplayName("8 - 5 000 points of history are read in less than 2 seconds")
    fun performance_5000Points_enMoinsDe2s() {
        val (server, base) = fauxHistorique(total = 5000, page = 5000)
        try {
            val client = MlflowClient(base)
            val debut = System.currentTimeMillis()
            val points = client.getMetricHistory("run1", "loss")
            val duree = System.currentTimeMillis() - debut
            assertEquals(5000, points.size)
            assertTrue(duree < 2000, "5 000 points must be read in less than 2 s (measured: ${duree} ms)")
        } finally {
            server.stop(0)
        }
    }

    // ------------------------------------------------------------------
    // Test 9 - the pagination (beyond 5 000 points)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("9 - beyond 5 000 points: the Pro paginates, the free tier stays capped at 5 000")
    fun auDelaDe5000Points_leProPagine() {
        val (server, base) = fauxHistorique(total = 12000, page = 5000)
        try {
            val client = MlflowClient(base)

            // The free tier: 5 000 points, it does not follow the page token.
            val gratuit = client.getMetricHistory("run1", "loss")
            assertEquals(5000, gratuit.size, "the free tier must stay capped at 5 000 points")

            // The Pro: it follows the pages up to the ceiling of 100 000.
            val pro = client.getMetricHistoryAll("run1", "loss", 100000)
            assertEquals(12000, pro.points.size, "the Pro must fetch the 12 000 points")
            assertTrue(!pro.tronque, "12 000 points < 100 000: the series is not truncated")
            // The points are sorted by step.
            val pas = pro.points.map { it.step }
            assertEquals(pas.sorted(), pas, "the points must be sorted by step")
        } finally {
            server.stop(0)
        }
    }

    @Test
    @DisplayName("9 bis - at the ceiling of 100 000 points, the series is declared truncated")
    fun auPlafond_laSerieEstDeclareeTronquee() {
        val (server, base) = fauxHistorique(total = 12000, page = 5000)
        try {
            val client = MlflowClient(base)
            val pro = client.getMetricHistoryAll("run1", "loss", 3000)
            assertEquals(3000, pro.points.size)
            assertTrue(pro.tronque, "the ceiling being reached must be reported, not hidden")
        } finally {
            server.stop(0)
        }
    }

    // ------------------------------------------------------------------
    // Test 10 - more than 2 runs
    // ------------------------------------------------------------------

    @Test
    @DisplayName("10 - 3 runs requested: the comparison is explicitly refused")
    fun plusDeDeuxRuns_estRefuse() {
        val r = ComparaisonRuns.calculer(listOf(runA(), runB(), runB().copy(id = "c3")), ProState.PRO)
        assertTrue(r is ResultatComparaison.Refus, "3 runs must be refused: $r")
        val refus = r as ResultatComparaison.Refus
        assertEquals(CodeRefus.TROIS_RUNS_NON_SUPPORTES, refus.code)
        assertTrue(refus.message.contains("2 runs"), "the message must recall the scope: ${refus.message}")
    }

    // ------------------------------------------------------------------
    // Test 11 - the Markdown copy
    // ------------------------------------------------------------------

    @Test
    @DisplayName("11 - the Markdown copy contains the header and the 2 expected rows")
    fun copierLeTableau_donneLeMarkdownAttendu() {
        val c = comparaison(runA(), runB())
        val md = c.markdown()

        assertTrue(
            md.contains("| parameter | A | B | state |"),
            "the parameter header must be present:\n$md"
        )
        assertTrue(
            md.contains("| metric | A | B | absolute gap | relative gap | state |"),
            "the metric header must be present:\n$md"
        )
        assertTrue(
            md.contains("| lr | 0.01 | 0.05 | different |"),
            "the lr parameter row must be present:\n$md"
        )
        assertTrue(
            md.contains("| acc | 0,8 | 0,86 | 0,06 | +7,50 % | different |"),
            "the acc metric row must be present:\n$md"
        )
    }

    // ------------------------------------------------------------------
    // Test 13 - integrity: what is displayed really comes from the 2 chosen runs
    // ------------------------------------------------------------------

    /**
     * Fails if the comparison displays parameters or metrics that are not the
     * ones of the 2 chosen runs.
     *
     * 4 checks, from the weakest to the strongest:
     *   1. the displayed **keys** are exactly the union of the keys of the 2
     *      chosen runs (not one more, not one less);
     *   2. each displayed **value** is the one of the right side (A on the
     *      left, B on the right);
     *   3. the comparison does **not** swap A and B (and says so in its
     *      result: `c.a` is the run chosen as A);
     *   4. a comparison does **not** leak** the data of another pair of
     *      runs: comparing (D, E) after (A, B) must not bring back the
     *      parameters nor the metrics specific to A and B.
     */
    @Test
    @DisplayName("13 - the comparison only displays the parameters and metrics of the 2 chosen runs")
    fun laComparaisonNExhibeQueLes2RunsChoisis() {
        val a = runA()
        val b = runB()
        val c = comparaison(a, b)

        // (1) the displayed keys are EXACTLY those of the 2 chosen runs
        assertEquals(
            (a.params.keys + b.params.keys).sorted(),
            c.params.map { it.cle },
            "the displayed parameters must be exactly those of the 2 chosen runs"
        )
        assertEquals(
            (a.metriques.keys + b.metriques.keys).sorted(),
            c.metriques.map { it.cle },
            "the displayed metrics must be exactly those of the 2 chosen runs"
        )

        // (2) each displayed value is the one of the right side
        for (l in c.params) {
            assertEquals(a.params[l.cle], l.valeurA, "value A of the parameter ${l.cle}")
            assertEquals(b.params[l.cle], l.valeurB, "value B of the parameter ${l.cle}")
        }
        for (l in c.metriques) {
            assertEquals(a.metriques[l.cle], l.valeurA, "value A of the metric ${l.cle}")
            assertEquals(b.metriques[l.cle], l.valeurB, "value B of the metric ${l.cle}")
        }

        // (3) A and B are not swapped
        assertEquals("a1", c.a.id, "the result must say it really compares run A")
        assertEquals("b2", c.b.id, "the result must say it really compares run B")
        val lr = c.params.first { it.cle == "lr" }
        assertEquals("0.01", lr.valeurA, "A must not be swapped with B")
        assertEquals("0.05", lr.valeurB, "A must not be swapped with B")

        // (4) a comparison must not leak another pair of runs
        val d = RunRef(id = "d1", params = mapOf("lr" to "0.11"), metriques = mapOf("acc" to 0.70))
        val e = RunRef(id = "e1", params = mapOf("lr" to "0.12"), metriques = mapOf("acc" to 0.72))
        val c2 = comparaison(d, e)
        assertEquals(listOf("lr"), c2.params.map { it.cle }, "the 2nd comparison must only display D and E")
        assertEquals(listOf("acc"), c2.metriques.map { it.cle }, "the 2nd comparison must only display D and E")
        assertTrue(
            c2.params.none { it.cle == "batch_size" },
            "\"batch_size\" only belongs to A and B: it must not remain in the comparison (D, E)"
        )
        assertTrue(
            c2.metriques.none { it.cle == "loss" },
            "\"loss\" only belongs to A and B: it must not remain in the comparison (D, E)"
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Fake server that serves a paginated metric history: each page carries a
     * `next_page_token`, like the real MLflow (`metrics/get-history`).
     */
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
