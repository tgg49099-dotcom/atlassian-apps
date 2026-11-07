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
 * Preuve que **le gratuit n'a pas bougé** — palier Pro n°1 (v0.0.22).
 *
 * C'est la contrainte forte de la consigne : « le gratuit doit rester
 * strictement identique : 4 onglets, toutes les limites annoncées
 * (500 experiments, 100 runs/expo, 500 modèles, 500 versions, 5 000
 * points/run), 0 fonction perdue. Un test le prouve. »
 *
 * Ce fichier prouve les 4 points, sur 3 niveaux :
 *   1. **les 4 onglets gratuits existent toujours** (lecture de la factory) ;
 *   2. **les limites annoncées sont toujours là** (lecture du descripteur) ;
 *   3. **le chemin gratuit ne consulte jamais la porte Pro** (lecture des
 *      sources des 4 onglets : ni `ProGate`, ni `LicenceNatif`, ni
 *      `LicensingFacade`, ni le code produit) ;
 *   4. **les requêtes du client libre portent toujours les mêmes limites**
 *      (mesure : un faux serveur HTTP enregistre ce qui est réellement
 *      envoyé).
 *
 * Le point 4 est le plus fort : il prouve le comportement, pas le texte.
 */
class GratuitInchangeTest {

    /**
     * Les fichiers qui implémentent le **chemin gratuit**. La factory
     * (`MlflowToolWindowFactory.kt`) est volontairement exclue : c'est elle
     * qui branche l'onglet Compare et qui installe le lecteur de licence —
     * c'est du câblage, pas du chemin gratuit.
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
    // 1. Les 4 onglets gratuits
    // ------------------------------------------------------------------

    @Test
    @DisplayName("1 - les 4 onglets gratuits (Experiments, Optuna, Models, Sweeps) existent toujours")
    fun les4OngletsGratuitsExistentToujours() {
        val factory = source("src/main/kotlin/com/slot5/mlexp/MlflowToolWindowFactory.kt")
        for (titre in listOf("Experiments", "Optuna", "Models", "Sweeps")) {
            assertTrue(
                factory.contains("\"$titre\""),
                "l'onglet $titre doit toujours être créé par la factory"
            )
        }
        // Le 5ᵉ onglet est bien le palier Pro n°1, et il ne remplace rien.
        assertTrue(factory.contains("\"Compare\""), "l'onglet Compare (palier Pro n°1) doit exister")
    }

    // ------------------------------------------------------------------
    // 2. Les limites annoncées
    // ------------------------------------------------------------------

    @Test
    @DisplayName("2 - les limites annoncées sont toujours dans le descripteur")
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
                "la limite annoncée « $limite » a disparu du descripteur"
            )
        }
    }

    // ------------------------------------------------------------------
    // 3. Le chemin gratuit ne consulte jamais la porte Pro
    // ------------------------------------------------------------------

    @Test
    @DisplayName("3 - aucun fichier du chemin gratuit ne consulte la porte Pro")
    fun leCheminGratuitNeConsulteJamaisLaPortePro() {
        for (chemin in fichiersGratuits) {
            val texte = source(chemin)
            for (interdit in listOf("ProGate", "LicenceNatif", "LicensingFacade", "getConfirmationStamp", "PPCOCKPITPRO")) {
                assertTrue(
                    !texte.contains(interdit),
                    "$chemin ne doit pas contenir « $interdit » : le gratuit ne consulte jamais la porte Pro"
                )
            }
        }
    }

    @Test
    @DisplayName("3 bis - le lecteur de licence natif n'existe que dans le paquet pro")
    fun leLecteurDeLicenceEstUnique() {
        val racine = File("src/main/kotlin/com/slot5/mlexp")
        val porteurs = racine.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("LicensingFacade") }
            .toList()
        assertEquals(
            1,
            porteurs.size,
            "seul LicenceNativ.kt doit parler à LicensingFacade, trouvé dans : " +
                porteurs.joinToString { it.name }
        )
        assertTrue(porteurs[0].name == "LicenceNatif.kt")
    }

    // ------------------------------------------------------------------
    // 4. Les requêtes du client libre gardent leurs limites
    // ------------------------------------------------------------------

    @Test
    @DisplayName("4 - les requêtes du client libre portent toujours les mêmes limites")
    fun lesRequetesDuClientGardentLeursLimites() {
        val faux = FauxServeur()
        try {
            val base = faux.demarrer()
            val client = com.slot5.mlexp.MlflowClient(base)

            client.listExperiments()
            faux.dernierCorps()!!.let { corps ->
                assertTrue(
                    corps.contains("\"max_results\":500"),
                    "listExperiments doit toujours demander 500 experiments, il a demandé : $corps"
                )
            }

            client.listRuns("2")
            faux.dernierCorps()!!.let { corps ->
                assertTrue(
                    corps.contains("\"max_results\":100"),
                    "listRuns doit toujours demander 100 runs, il a demandé : $corps"
                )
            }

            client.listRegisteredModels()
            faux.derniereUri()!!.let { uri ->
                assertTrue(
                    uri.contains("max_results=500"),
                    "listRegisteredModels doit toujours demander 500 modeles, il a demandé : $uri"
                )
            }

            client.listModelVersions("mon-modele")
            faux.derniereUri()!!.let { uri ->
                assertTrue(
                    uri.contains("max_results=500"),
                    "listModelVersions doit toujours demander 500 versions, il a demandé : $uri"
                )
            }

            client.getMetricHistory("run1", "loss")
            faux.derniereUri()!!.let { uri ->
                assertTrue(
                    uri.contains("max_results=5000"),
                    "getMetricHistory doit toujours demander 5 000 points, il a demandé : $uri"
                )
            }
        } finally {
            faux.arreter()
        }
    }

    // ------------------------------------------------------------------
    // 5. Sans licence : comportement gratuit strictement identique
    // ------------------------------------------------------------------

    @Test
    @DisplayName("5 - quand aucune licence n'est installée, le gratuit reste strictement identique")
    fun sansLicenceLeGratuitResteStrictementIdentique() {
        val sansLicence = LectureLicence(facadeInitialisee = true, sceau = null)
        val etat = ProGate.etat(sansLicence)
        assertEquals(ProState.ABSENT, etat, "sans licence, l'état doit être ABSENT")
        assertTrue(!etat.autorise(), "sans licence, aucune fonction Pro n'est autorisée")

        // 1. Les 4 onglets gratuits doivent être présents
        val factory = source("src/main/kotlin/com/slot5/mlexp/MlflowToolWindowFactory.kt")
        for (onglet in listOf("Experiments", "Optuna", "Models", "Sweeps")) {
            assertTrue(
                factory.contains("\"$onglet\""),
                "sans licence, l'onglet $onglet doit impérativement exister"
            )
        }

        // 2. Les limites annoncées doivent rester strictement intactes
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
                "sans licence, la limite « $limite » doit rester strictement identique"
            )
        }

        // 3. Le chemin de code gratuit ne consulte aucune porte Pro
        for (chemin in fichiersGratuits) {
            val code = source(chemin)
            for (interdit in listOf("ProGate", "LicenceNatif", "LicensingFacade", "getConfirmationStamp", "PPCOCKPITPRO")) {
                assertTrue(
                    !code.contains(interdit),
                    "$chemin ne doit pas dépendre de $interdit : le gratuit reste strictement isolé"
                )
            }
        }

        // 4. Les appels client gratuits gardent strictement leurs limites réseau
        val faux = FauxServeur()
        try {
            val base = faux.demarrer()
            val client = com.slot5.mlexp.MlflowClient(base)

            client.listExperiments()
            assertTrue(
                faux.dernierCorps()!!.contains("\"max_results\":500"),
                "sans licence, listExperiments doit demander 500 experiments"
            )

            client.listRuns("exp1")
            assertTrue(
                faux.dernierCorps()!!.contains("\"max_results\":100"),
                "sans licence, listRuns doit demander 100 runs"
            )

            client.listRegisteredModels()
            assertTrue(
                faux.derniereUri()!!.contains("max_results=500"),
                "sans licence, listRegisteredModels doit demander 500 modeles"
            )

            client.listModelVersions("modele1")
            assertTrue(
                faux.derniereUri()!!.contains("max_results=500"),
                "sans licence, listModelVersions doit demander 500 versions"
            )

            client.getMetricHistory("run1", "loss")
            assertTrue(
                faux.derniereUri()!!.contains("max_results=5000"),
                "sans licence, getMetricHistory doit demander 5000 points"
            )
        } finally {
            faux.arreter()
        }

        // 5. Sans licence, la porte Pro bloque la comparaison
        val runs = listOf(
            RunRef(id = "r1", origine = "http://localhost:5000"),
            RunRef(id = "r2", origine = "http://localhost:5000")
        )
        val resultat = ComparaisonRuns.calculer(runs, etat)
        assertTrue(resultat is ResultatComparaison.Refus, "sans licence, la comparaison doit être refusée")
        assertEquals(CodeRefus.BLOQUE_SANS_LICENCE, (resultat as ResultatComparaison.Refus).code)
    }

    // ------------------------------------------------------------------
    // Outils
    // ------------------------------------------------------------------

    /** Lit un fichier du projet (le répertoire de travail du test = la racine du projet). */
    private fun source(chemin: String): String {
        var d: File? = File(".").absoluteFile
        while (d != null) {
            val f = File(d, chemin)
            if (f.exists()) return f.readText(Charsets.UTF_8)
            d = d.parentFile
        }
        throw AssertionError("source introuvable : $chemin")
    }

    /** Faux serveur MLflow : enregistre ce qui est demandé, répond `{}`. */
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
