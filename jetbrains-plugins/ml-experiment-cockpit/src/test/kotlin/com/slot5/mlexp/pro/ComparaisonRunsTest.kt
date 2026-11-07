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
 * Tests du **cœur de comparaison** du palier Pro n°1 (« Compare 2 runs »).
 *
 * v0.0.22. Ces tests ne dépendent que du JDK et de JUnit 5 : aucun serveur
 * MLflow, aucun réseau (le faux serveur est un
 * `com.sun.net.httpserver.HttpServer`, comme dans les tests existants).
 *
 * Chaque test mord sur un **comportement** du plan
 * (`/work/cockpit-pro-n1-plan.md` § 4) :
 *   4. deux runs différents → le tableau montre les écarts ;
 *   5. deux runs identiques → 0 écart ;
 *   6. une métrique absente → état « absent de B », jamais 0.0 ;
 *   7. deux serveurs différents → refus motivé ;
 *   8. 5 000 points → moins de 2 s ;
 *   9. au-delà de 5 000 points → le Pro pagine, le gratuit reste plafonné ;
 *  10. plus de 2 runs → refus explicite ;
 *  11. la copie Markdown contient l'en-tête et les lignes attendues.
 */
class ComparaisonRunsTest {

    // ------------------------------------------------------------------
    // Données de test
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
        assertTrue(r is ResultatComparaison.Comparaison, "la comparaison doit aboutir : $r")
        return r as ResultatComparaison.Comparaison
    }

    // ------------------------------------------------------------------
    // Test 4 — deux runs différents
    // ------------------------------------------------------------------

    @Test
    @DisplayName("4 - deux runs différents : le tableau montre les écarts (lr et acc, +7,50 %)")
    fun deuxRunsDifferents_leTableauMontreLesEcarts() {
        val c = comparaison(runA(), runB())

        // 1 écart de paramètre (lr) + 1 écart de métrique (acc) = 2.
        assertEquals(2, c.nombreEcarts, "il doit y avoir exactement 2 écarts")
        assertEquals(1, c.ecartsParams.size)
        assertEquals(1, c.ecartsMetriques.size)

        val lr = c.params.first { it.cle == "lr" }
        assertEquals(EtatComparaison.DIFFERENT, lr.etat)
        assertEquals("0.01", lr.valeurA)
        assertEquals("0.05", lr.valeurB)

        val acc = c.metriques.first { it.cle == "acc" }
        assertEquals(EtatComparaison.DIFFERENT, acc.etat)
        assertEquals(0.06, acc.ecartAbsolu!!, 1e-9, "écart absolu = 0,86 − 0,80")
        assertEquals(7.50, acc.ecartRelatifPct!!, 1e-9, "écart relatif = (0,86 − 0,80) / 0,80")
        assertEquals("+7,50 %", ComparaisonRuns.formatEcartRelatif(acc.ecartRelatifPct))

        // Le paramètre identique reste affiché, mais il n'est pas un écart.
        val batch = c.params.first { it.cle == "batch_size" }
        assertEquals(EtatComparaison.IDENTIQUE, batch.etat)
    }

    // ------------------------------------------------------------------
    // Test 5 — deux runs identiques
    // ------------------------------------------------------------------

    @Test
    @DisplayName("5 - deux runs identiques : 0 écart, état « identique » partout")
    fun deuxRunsIdentiques_leTableauEstVideDEcarts() {
        val a = runA()
        val b = runB().copy(params = a.params, metriques = a.metriques)
        val c = comparaison(a, b)

        assertEquals(0, c.nombreEcarts, "2 runs identiques ne doivent produire aucun écart")
        assertTrue(c.ecartsParams.isEmpty())
        assertTrue(c.ecartsMetriques.isEmpty())
        assertTrue(c.params.all { it.etat == EtatComparaison.IDENTIQUE })
        assertTrue(c.metriques.all { it.etat == EtatComparaison.IDENTIQUE })
    }

    // ------------------------------------------------------------------
    // Test 6 — une métrique absente d'un des 2 runs
    // ------------------------------------------------------------------

    @Test
    @DisplayName("6 - une métrique absente de B est marquée « absent de B », jamais 0.0")
    fun metriquesManquantes_leTableauMarqueAbsent() {
        val b = runB().copy(metriques = mapOf("acc" to 0.86))
        val c = comparaison(runA(), b)

        val loss = c.metriques.first { it.cle == "loss" }
        assertEquals(EtatComparaison.ABSENT_B, loss.etat)
        assertEquals(0.5, loss.valeurA!!, 1e-9)
        assertNull(loss.valeurB, "la valeur absente ne doit pas devenir 0.0")
        assertNull(loss.ecartAbsolu, "pas d'écart si une valeur manque")
        assertNull(loss.ecartRelatifPct, "pas d'écart relatif si une valeur manque")
        assertEquals("—", ComparaisonRuns.formatNombre(loss.valeurB) ?: "—")
    }

    // ------------------------------------------------------------------
    // Test 7 — deux serveurs différents
    // ------------------------------------------------------------------

    @Test
    @DisplayName("7 - deux runs de 2 serveurs différents : la comparaison est refusée")
    fun deuxServeursDifferents_laComparaisonEstRefusee() {
        val r = ComparaisonRuns.calculer(
            listOf(runA(), runB().copy(origine = "http://autre:5000")),
            ProState.PRO
        )
        assertTrue(r is ResultatComparaison.Refus, "la comparaison doit être refusée : $r")
        val refus = r as ResultatComparaison.Refus
        assertEquals(CodeRefus.ORIGINES_DIFFERENTES, refus.code)
        assertTrue(
            refus.message.contains("http://mlflow:5000") && refus.message.contains("http://autre:5000"),
            "le message doit nommer les 2 URI, il dit : ${refus.message}"
        )
    }

    // ------------------------------------------------------------------
    // Test 8 — performance sur 5 000 points
    // ------------------------------------------------------------------

    @Test
    @DisplayName("8 - 5 000 points d'historique sont lus en moins de 2 secondes")
    fun performance_5000Points_enMoinsDe2s() {
        val (server, base) = fauxHistorique(total = 5000, page = 5000)
        try {
            val client = MlflowClient(base)
            val debut = System.currentTimeMillis()
            val points = client.getMetricHistory("run1", "loss")
            val duree = System.currentTimeMillis() - debut
            assertEquals(5000, points.size)
            assertTrue(duree < 2000, "5 000 points doivent être lus en moins de 2 s (mesuré : ${duree} ms)")
        } finally {
            server.stop(0)
        }
    }

    // ------------------------------------------------------------------
    // Test 9 — la pagination (au-delà de 5 000 points)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("9 - au-delà de 5 000 points : le Pro pagine, le gratuit reste plafonné à 5 000")
    fun auDelaDe5000Points_leProPagine() {
        val (server, base) = fauxHistorique(total = 12000, page = 5000)
        try {
            val client = MlflowClient(base)

            // Le gratuit : 5 000 points, il ne suit pas le jeton de page.
            val gratuit = client.getMetricHistory("run1", "loss")
            assertEquals(5000, gratuit.size, "le gratuit doit rester plafonné à 5 000 points")

            // Le Pro : il suit les pages jusqu'au plafond de 100 000.
            val pro = client.getMetricHistoryAll("run1", "loss", 100000)
            assertEquals(12000, pro.points.size, "le Pro doit récupérer les 12 000 points")
            assertTrue(!pro.tronque, "12 000 points < 100 000 : la série n'est pas tronquée")
            // Les points sont triés par pas.
            val pas = pro.points.map { it.step }
            assertEquals(pas.sorted(), pas, "les points doivent être triés par pas")
        } finally {
            server.stop(0)
        }
    }

    @Test
    @DisplayName("9 bis - au plafond de 100 000 points, la série est déclarée tronquée")
    fun auPlafond_laSerieEstDeclareeTronquee() {
        val (server, base) = fauxHistorique(total = 12000, page = 5000)
        try {
            val client = MlflowClient(base)
            val pro = client.getMetricHistoryAll("run1", "loss", 3000)
            assertEquals(3000, pro.points.size)
            assertTrue(pro.tronque, "le plafond atteint doit être signalé, pas caché")
        } finally {
            server.stop(0)
        }
    }

    // ------------------------------------------------------------------
    // Test 10 — plus de 2 runs
    // ------------------------------------------------------------------

    @Test
    @DisplayName("10 - 3 runs demandés : la comparaison est refusée explicitement")
    fun plusDeDeuxRuns_estRefuse() {
        val r = ComparaisonRuns.calculer(listOf(runA(), runB(), runB().copy(id = "c3")), ProState.PRO)
        assertTrue(r is ResultatComparaison.Refus, "3 runs doivent être refusés : $r")
        val refus = r as ResultatComparaison.Refus
        assertEquals(CodeRefus.TROIS_RUNS_NON_SUPPORTES, refus.code)
        assertTrue(refus.message.contains("2 runs"), "le message doit rappeler le périmètre : ${refus.message}")
    }

    // ------------------------------------------------------------------
    // Test 11 — la copie Markdown
    // ------------------------------------------------------------------

    @Test
    @DisplayName("11 - la copie Markdown contient l'en-tête et les 2 lignes attendues")
    fun copierLeTableau_donneLeMarkdownAttendu() {
        val c = comparaison(runA(), runB())
        val md = c.markdown()

        assertTrue(
            md.contains("| paramètre | A | B | état |"),
            "l'en-tête des paramètres doit être présent :\n$md"
        )
        assertTrue(
            md.contains("| métrique | A | B | écart absolu | écart relatif | état |"),
            "l'en-tête des métriques doit être présent :\n$md"
        )
        assertTrue(
            md.contains("| lr | 0.01 | 0.05 | différent |"),
            "la ligne du paramètre lr doit être présente :\n$md"
        )
        assertTrue(
            md.contains("| acc | 0,8 | 0,86 | 0,06 | +7,50 % | différent |"),
            "la ligne de la métrique acc doit être présente :\n$md"
        )
    }

    // ------------------------------------------------------------------
    // Test 13 — intégrité : ce qui est affiché vient bien des 2 runs choisis
    // ------------------------------------------------------------------

    /**
     * Échoue si la comparaison affiche des paramètres ou des métriques qui
     * ne sont pas ceux des 2 runs choisis.
     *
     * 4 contrôles, du plus faible au plus fort :
     *   1. les **clés** affichées sont exactement l'union des clés des 2 runs
     *      choisis (ni une de plus, ni une de moins) ;
     *   2. chaque **valeur** affichée est bien celle du bon côté (A à gauche,
     *      B à droite) ;
     *   3. la comparaison **n'inverse pas** A et B (et le dit dans son
     *      résultat : `c.a` est le run choisi comme A) ;
     *   4. une comparaison **ne laisse pas fuir** les données d'un autre
     *      couple de runs : comparer (D, E) après (A, B) ne doit pas ressusciter
     *      les paramètres ni les métriques propres à A et B.
     */
    @Test
    @DisplayName("13 - la comparaison n'affiche que les paramètres et métriques des 2 runs choisis")
    fun laComparaisonNExhibeQueLes2RunsChoisis() {
        val a = runA()
        val b = runB()
        val c = comparaison(a, b)

        // (1) les clés affichées sont EXACTEMENT celles des 2 runs choisis
        assertEquals(
            (a.params.keys + b.params.keys).sorted(),
            c.params.map { it.cle },
            "les paramètres affichés doivent être exactement ceux des 2 runs choisis"
        )
        assertEquals(
            (a.metriques.keys + b.metriques.keys).sorted(),
            c.metriques.map { it.cle },
            "les métriques affichées doivent être exactement celles des 2 runs choisis"
        )

        // (2) chaque valeur affichée est celle du bon côté
        for (l in c.params) {
            assertEquals(a.params[l.cle], l.valeurA, "valeur A du paramètre ${l.cle}")
            assertEquals(b.params[l.cle], l.valeurB, "valeur B du paramètre ${l.cle}")
        }
        for (l in c.metriques) {
            assertEquals(a.metriques[l.cle], l.valeurA, "valeur A de la métrique ${l.cle}")
            assertEquals(b.metriques[l.cle], l.valeurB, "valeur B de la métrique ${l.cle}")
        }

        // (3) A et B ne sont pas inversés
        assertEquals("a1", c.a.id, "le résultat doit dire qu'il compare bien le run A")
        assertEquals("b2", c.b.id, "le résultat doit dire qu'il compare bien le run B")
        val lr = c.params.first { it.cle == "lr" }
        assertEquals("0.01", lr.valeurA, "A ne doit pas être inversé avec B")
        assertEquals("0.05", lr.valeurB, "A ne doit pas être inversé avec B")

        // (4) une comparaison ne doit pas laisser fuir un autre couple de runs
        val d = RunRef(id = "d1", params = mapOf("lr" to "0.11"), metriques = mapOf("acc" to 0.70))
        val e = RunRef(id = "e1", params = mapOf("lr" to "0.12"), metriques = mapOf("acc" to 0.72))
        val c2 = comparaison(d, e)
        assertEquals(listOf("lr"), c2.params.map { it.cle }, "la 2ᵉ comparaison ne doit afficher que D et E")
        assertEquals(listOf("acc"), c2.metriques.map { it.cle }, "la 2ᵉ comparaison ne doit afficher que D et E")
        assertTrue(
            c2.params.none { it.cle == "batch_size" },
            "« batch_size » n'appartient qu'à A et B : il ne doit pas rester dans la comparaison (D, E)"
        )
        assertTrue(
            c2.metriques.none { it.cle == "loss" },
            "« loss » n'appartient qu'à A et B : il ne doit pas rester dans la comparaison (D, E)"
        )
    }

    // ------------------------------------------------------------------
    // Outils
    // ------------------------------------------------------------------

    /**
     * Faux serveur qui sert un historique de métrique paginé : chaque page
     * porte un `next_page_token`, comme le vrai MLflow
     * (`metrics/get-history`).
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
