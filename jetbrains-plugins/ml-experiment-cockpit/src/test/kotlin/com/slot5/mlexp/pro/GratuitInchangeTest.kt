package com.slot5.mlexp.pro

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Proof that **the free tier has not moved** - Pro tier 1 (v0.0.22).
 *
 * This is the strong constraint of the brief: "the free tier must stay
 * strictly identical: 4 tabs, all the announced limits (500 experiments,
 * 100 runs/experiment, 500 models, 500 versions, 5 000 points/run), 0 feature
 * lost. A test proves it."
 *
 * This file proves the 4 points, on 3 levels:
 *   1. **the 4 free tabs still exist** (read of the factory);
 *   2. **the announced limits are still there** (read of the descriptor);
 *   3. **the free path never queries the Pro gate** (read of the sources of
 *      the 4 free tabs: neither `ProGate`, nor `LicenceNatif`, nor
 *      `LicensingFacade`, nor the product code);
 *   4. **the requests of the free client still carry the same limits**
 *      (measure: a fake HTTP server records what is really sent).
 *
 * Point 4 is the strongest: it proves the behaviour, not the text.
 */
class GratuitInchangeTest {

    /**
     * The files that implement the **free path**. The factory
     * (`MlflowToolWindowFactory.kt`) is deliberately excluded: it is the one
     * that wires the Compare tab and installs the licence reader - that is
     * wiring, not the free path.
     */
    private val fichiersGratuits = listOf(
        "src/main/kotlin/com/slot5/mlexp/ConnectToMlflowAction.kt",
        "src/main/kotlin/com/slot5/mlexp/Json.kt",
        "src/main/kotlin/com/slot5/mlexp/MetricsChartPanel.kt",
        "src/main/kotlin/com/slot5/mlexp/MlflowClient.kt",
        "src/main/kotlin/com/slot5/mlexp/MlflowSettings.kt",
        "src/main/kotlin/com/slot5/mlexp/MlflowSettingsConfigurable.kt",
        "src/main/kotlin/com/slot5/mlexp/MlflowToolWindowPanel.kt",
        "src/main/kotlin/com/slot5/mlexp/ModelRegistryToolWindowPanel.kt",
        "src/main/kotlin/com/slot5/mlexp/NodeTreeCellRenderer.kt",
        "src/main/kotlin/com/slot5/mlexp/OptunaClient.kt",
        "src/main/kotlin/com/slot5/mlexp/OptunaToolWindowPanel.kt",
        "src/main/kotlin/com/slot5/mlexp/SweepToolWindowPanel.kt",
        "src/main/kotlin/com/slot5/mlexp/WandbClient.kt"
    )

    // ------------------------------------------------------------------
    // 1. The 4 free tabs
    // ------------------------------------------------------------------

    @Test
    @DisplayName("1 - the 4 free tabs (Experiments, Optuna, Models, Sweeps) still exist")
    fun les4OngletsGratuitsExistentToujours() {
        val factory = source("src/main/kotlin/com/slot5/mlexp/MlflowToolWindowFactory.kt")
        for (titre in listOf("Experiments", "Optuna", "Models", "Sweeps")) {
            assertTrue(
                factory.contains("\"$titre\""),
                "the tab $titre must still be created by the factory"
            )
        }
        // The 5th tab is Pro tier 1, and it replaces nothing.
        assertTrue(factory.contains("\"Compare\""), "the Compare tab (Pro tier 1) must exist")
    }

    // ------------------------------------------------------------------
    // 2. The announced limits
    // ------------------------------------------------------------------

    @Test
    @DisplayName("2 - the announced limits are still in the descriptor")
    fun lesLimitesAnnonceesSontToujoursLa() {
        val descripteur = source("src/main/resources/META-INF/plugin.xml")
        for (limite in listOf(
            "500 experiments",
            "100 runs per experiment",
            "500 registered models",
            "500 model versions",
            "5 000 metric points per run"
        )) {
            assertTrue(
                descripteur.contains(limite),
                "the announced limit \"$limite\" has disappeared from the descriptor"
            )
        }
    }

    // ------------------------------------------------------------------
    // 3. The free path never queries the Pro gate
    // ------------------------------------------------------------------

    @Test
    @DisplayName("3 - no file of the free path queries the Pro gate")
    fun leCheminGratuitNeConsulteJamaisLaPortePro() {
        for (chemin in fichiersGratuits) {
            val texte = source(chemin)
            for (interdit in listOf("ProGate", "LicenceNatif", "LicensingFacade", "getConfirmationStamp", "PPCOCKPITPRO")) {
                assertTrue(
                    !texte.contains(interdit),
                    "$chemin must not contain \"$interdit\": the free tier never queries the Pro gate"
                )
            }
        }
    }

    @Test
    @DisplayName("3 bis - the native licence reader exists only in the pro package")
    fun leLecteurDeLicenceEstUnique() {
        val racine = File("src/main/kotlin/com/slot5/mlexp")
        val porteurs = racine.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("LicensingFacade") }
            .toList()
        assertEquals(
            1,
            porteurs.size,
            "only LicenceNatif.kt may talk to LicensingFacade, found in: " +
                porteurs.joinToString { it.name }
        )
        assertTrue(porteurs[0].name == "LicenceNatif.kt")
    }

    // ------------------------------------------------------------------
    // 4. The requests of the free client keep their limits
    // ------------------------------------------------------------------

    @Test
    @DisplayName("4 - the requests of the free client still carry the same limits")
    fun lesRequetesDuClientGardentLeursLimites() {
        val faux = FauxServeur()
        try {
            val base = faux.demarrer()
            val client = com.slot5.mlexp.MlflowClient(base)

            client.listExperiments()
            faux.dernierCorps()!!.let { corps ->
                assertTrue(
                    corps.contains("\"max_results\":500"),
                    "listExperiments must still ask for 500 experiments, it asked for: $corps"
                )
            }

            client.listRuns("2")
            faux.dernierCorps()!!.let { corps ->
                assertTrue(
                    corps.contains("\"max_results\":100"),
                    "listRuns must still ask for 100 runs, it asked for: $corps"
                )
            }

            client.listRegisteredModels()
            faux.derniereUri()!!.let { uri ->
                assertTrue(
                    uri.contains("max_results=500"),
                    "listRegisteredModels must still ask for 500 models, it asked for: $uri"
                )
            }

            client.listModelVersions("mon-modele")
            faux.derniereUri()!!.let { uri ->
                assertTrue(
                    uri.contains("max_results=500"),
                    "listModelVersions must still ask for 500 versions, it asked for: $uri"
                )
            }

            client.getMetricHistory("run1", "loss")
            faux.derniereUri()!!.let { uri ->
                assertTrue(
                    uri.contains("max_results=5000"),
                    "getMetricHistory must still ask for 5 000 points, it asked for: $uri"
                )
            }
        } finally {
            faux.arreter()
        }
    }

    // ------------------------------------------------------------------
    // 5. Without a licence: strictly identical free behaviour
    // ------------------------------------------------------------------

    @Test
    @DisplayName("5 - when no licence is installed, the free tier stays strictly identical")
    fun sansLicenceLeGratuitResteStrictementIdentique() {
        val sansLicence = LectureLicence(facadeInitialisee = true, sceau = null)
        val etat = ProGate.etat(sansLicence)
        assertEquals(ProState.ABSENT, etat, "without a licence, the state must be ABSENT")
        assertTrue(!etat.autorise(), "without a licence, no Pro feature is authorised")

        // 1. The 4 free tabs must be present
        val factory = source("src/main/kotlin/com/slot5/mlexp/MlflowToolWindowFactory.kt")
        for (onglet in listOf("Experiments", "Optuna", "Models", "Sweeps")) {
            assertTrue(
                factory.contains("\"$onglet\""),
                "without a licence, the tab $onglet must exist at all costs"
            )
        }

        // 2. The announced limits must stay strictly intact
        val descripteur = source("src/main/resources/META-INF/plugin.xml")
        val limites = listOf(
            "500 experiments",
            "100 runs per experiment",
            "500 registered models",
            "500 model versions",
            "5 000 metric points per run"
        )
        for (limite in limites) {
            assertTrue(
                descripteur.contains(limite),
                "without a licence, the limit \"$limite\" must stay strictly identical"
            )
        }

        // 3. The free code path queries no Pro gate
        for (chemin in fichiersGratuits) {
            val code = source(chemin)
            for (interdit in listOf("ProGate", "LicenceNatif", "LicensingFacade", "getConfirmationStamp", "PPCOCKPITPRO")) {
                assertTrue(
                    !code.contains(interdit),
                    "$chemin must not depend on $interdit: the free tier stays strictly isolated"
                )
            }
        }

        // 4. The free client calls keep their strict network limits
        val faux = FauxServeur()
        try {
            val base = faux.demarrer()
            val client = com.slot5.mlexp.MlflowClient(base)

            client.listExperiments()
            assertTrue(
                faux.dernierCorps()!!.contains("\"max_results\":500"),
                "without a licence, listExperiments must ask for 500 experiments"
            )

            client.listRuns("exp1")
            assertTrue(
                faux.dernierCorps()!!.contains("\"max_results\":100"),
                "without a licence, listRuns must ask for 100 runs"
            )

            client.listRegisteredModels()
            assertTrue(
                faux.derniereUri()!!.contains("max_results=500"),
                "without a licence, listRegisteredModels must ask for 500 models"
            )

            client.listModelVersions("modele1")
            assertTrue(
                faux.derniereUri()!!.contains("max_results=500"),
                "without a licence, listModelVersions must ask for 500 versions"
            )

            client.getMetricHistory("run1", "loss")
            assertTrue(
                faux.derniereUri()!!.contains("max_results=5000"),
                "without a licence, getMetricHistory must ask for 5000 points"
            )
        } finally {
            faux.arreter()
        }

        // 5. Without a licence, the Pro gate blocks the comparison
        val runs = listOf(
            RunRef(id = "r1", origine = "http://localhost:5000"),
            RunRef(id = "r2", origine = "http://localhost:5000")
        )
        val resultat = ComparaisonRuns.calculer(runs, etat)
        assertTrue(resultat is ResultatComparaison.Refus, "without a licence, the comparison must be refused")
        assertEquals(CodeRefus.BLOQUE_SANS_LICENCE, (resultat as ResultatComparaison.Refus).code)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Reads a file of the project (the working directory of the test = the project root). */
    private fun source(chemin: String): String {
        var d: File? = File(".").absoluteFile
        while (d != null) {
            val f = File(d, chemin)
            if (f.exists()) return f.readText(Charsets.UTF_8)
            d = d.parentFile
        }
        throw AssertionError("source not found: $chemin")
    }

    /** Fake MLflow server: records what is asked, answers `{}`. */
    private class FauxServeur {
        private lateinit var server: HttpServer
        private val corps = ArrayList<String>()
        private val uris = ArrayList<String>()

        fun demarrer(): String {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.use { it.readBytes().toString(StandardCharsets.UTF_8) }
                corps.add(body)
                uris.add(exchange.requestURI.toString())
                val reponse = "{}".toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, reponse.size.toLong())
                exchange.responseBody.use { it.write(reponse) }
            }
            server.start()
            return "http://127.0.0.1:${server.address.port}"
        }

        fun dernierCorps(): String? = if (corps.isEmpty()) null else corps[corps.size - 1]
        fun derniereUri(): String? = if (uris.isEmpty()) null else uris[uris.size - 1]
        fun arreter() = server.stop(0)
    }
}
