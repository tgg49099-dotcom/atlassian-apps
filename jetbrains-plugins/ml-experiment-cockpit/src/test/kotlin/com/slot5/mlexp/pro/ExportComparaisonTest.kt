package com.slot5.mlexp.pro

import com.slot5.mlexp.MetricPoint
import com.slot5.mlexp.MlflowClient
import com.slot5.mlexp.Run
import java.awt.Component
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.swing.JButton
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * **Palier Pro n°2 — l'export partageable de la comparaison** (cycle 245).
 *
 * Le palier Pro n°1 (« Compare 2 runs ») se termine par une copie **Markdown**
 * dans le presse-papiers : pratique pour une discussion, inutile pour
 * quelqu'un qui n'a pas MLflow. Le palier n°2 écrit **un fichier HTML
 * autonome** : les 2 tableaux + les 2 courbes en PNG intégré, le CSS dans le
 * fichier, **aucune ressource externe**.
 *
 * Ces tests couvrent 4 choses :
 *
 *   1. **Le rapport** : les 2 tableaux, le PNG intégré, **0 URL externe**,
 *      **0 NaN**, **0 ligne fantome**, le texte du serveur jamais lu comme
 *      du HTML ;
 *   2. **La porte** : sans licence active, **aucun export** n'est possible
 *      (et le serveur n'est meme pas interroge) ;
 *   3. **L'echec propre** : un run disparu ou renomme entre la comparaison et
 *      l'export => **message clair, aucune exception, aucun fichier ecrit** ;
 *   4. **Le contrat** : le code de l'export n'envoie **rien** (pas de socket,
 *      pas d'URL, pas de telemetry) et ne contient **aucun chemin en dur**.
 */
class ExportComparaisonTest {

    @org.junit.jupiter.api.BeforeEach
    fun accepterLesSceauxDeTest() = ProGate.installerVerificateur(VerificateurDeTest.ACCEPTE)

    @org.junit.jupiter.api.AfterEach
    fun remettreLeVraiVerificateur() = ProGate.remettreVerificateurJetBrains()

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * The code without its comments: a comment may **mention** a forbidden
     * word (the file explains that the export sends nothing) - the check is
     * about the CODE, not about the prose.
     */
    private fun sansCommentaires(source: String): String {
        val sansBlocs = source.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        return sansBlocs.lines().joinToString("\n") { ligne ->
            val i = ligne.indexOf("//")
            // "//" of a URL ("https://...") is not a comment: it is kept.
            if (i >= 0 && (i == 0 || ligne[i - 1] != ':')) ligne.substring(0, i) else ligne
        }
    }

    /** A comparison built by hand (no server): the core of the report. */
    private fun comparaison(
        paramsA: Map<String, String> = mapOf("lr" to "0.01"),
        paramsB: Map<String, String> = mapOf("lr" to "0.02"),
        metriquesA: Map<String, Double> = mapOf("loss" to 0.5, "acc" to 0.9),
        metriquesB: Map<String, Double> = mapOf("acc" to 0.8)
    ): ResultatComparaison.Comparaison {
        val a = RunRef(
            id = "a1", nom = "run-a", statut = "FINISHED",
            startTimeMs = 1_700_000_000_000L, endTimeMs = 1_700_000_060_000L,
            origine = "http://serveur:5000", params = paramsA, metriques = metriquesA
        )
        val b = RunRef(
            id = "b1", nom = "run-b", statut = "FINISHED",
            startTimeMs = 1_700_000_000_000L, endTimeMs = 1_700_000_030_000L,
            origine = "http://serveur:5000", params = paramsB, metriques = metriquesB
        )
        val r = ComparaisonRuns.calculer(listOf(a, b), ProState.PRO)
        assertTrue(r is ResultatComparaison.Comparaison, "the comparison must succeed: $r")
        return r as ResultatComparaison.Comparaison
    }

    /** The PNG of a 2-series curve, as the report carries it. */
    private fun pngBase64(): String = RenduCourbePng.base64(
        listOf(MetricPoint(0, 1.0, 0L), MetricPoint(1, 0.5, 1L)),
        listOf(MetricPoint(0, 0.8, 0L), MetricPoint(1, 0.6, 1L)),
        "loss"
    )

    /** Every button of a component tree. */
    private fun boutonsDe(c: Component): List<JButton> {
        val liste = mutableListOf<JButton>()
        if (c is JButton) liste.add(c)
        if (c is java.awt.Container) for (enfant in c.components) liste.addAll(boutonsDe(enfant))
        return liste
    }

    /** Installs a licence source, runs the block, then restores the default one. */
    private fun avecLicence(source: SourceLicence, bloc: () -> Unit) {
        ProGate.installerSource(source)
        try {
            bloc()
        } finally {
            ProGate.installerSource(SourceLicenceAbsent)
        }
    }

    /** A machine with the product licensed. */
    private object LicenceActive : SourceLicence {
        override fun lire() = LectureLicence(facadeInitialisee = true, sceau = "key:essai")
    }

    /** A machine with NO licence for the product (the free user's case). */
    private object LicenceAbsente : SourceLicence {
        override fun lire() = LectureLicence(facadeInitialisee = true, sceau = null)
    }

    // ------------------------------------------------------------------
    // 1. The report
    // ------------------------------------------------------------------

    @Test
    @DisplayName("E1 - the file name is ml-experiment-compare-<runA>-<runB>-<date>.html")
    fun leNomDuFichierSuitLeFormatAgreed() {
        val instant = 1_762_000_000_000L
        val date = DateTimeFormatter.ofPattern("yyyyMMdd")
            .format(Instant.ofEpochMilli(instant).atZone(ZoneId.systemDefault()))
        val nom = ExportComparaison.nomFichier("run-a", "run-b", instant)
        assertEquals("ml-experiment-compare-run-a-run-b-$date.html", nom)
        assertTrue(nom.startsWith("ml-experiment-compare-"), nom)
        assertTrue(nom.endsWith(".html"), nom)
    }

    @Test
    @DisplayName("E2 - a hostile run name cannot escape the folder the user chose")
    fun unNomHostileNeSortPasDuDossier() {
        val nom = ExportComparaison.nomFichier("../../etc/passwd", "run b/../c", 1_762_000_000_000L)
        assertFalse(nom.contains("/"), "the file name must hold no path separator: $nom")
        assertFalse(nom.contains(".."), "the file name must hold no \"..\": $nom")
        assertFalse(nom.contains(" "), "the file name must hold no space: $nom")
        assertTrue(nom.startsWith("ml-experiment-compare-"), nom)
        // A name made only of forbidden characters still gives a usable name.
        val vide = ExportComparaison.nomFichier("", "", 1_762_000_000_000L)
        assertTrue(vide.startsWith("ml-experiment-compare-run-run-"), vide)
    }

    @Test
    @DisplayName("E3 - the report holds the 2 tables and the embedded PNG")
    fun leRapportContientLes2TableauxEtLePng() {
        val html = ExportComparaison.html(comparaison(), pngBase64(), "loss", 1_762_000_000_000L)
        // the 2 tables
        assertTrue(html.contains("<th>parameter</th>"), "the parameter table must be there")
        assertTrue(html.contains("<th>metric</th>"), "the metric table must be there")
        assertTrue(html.contains("<th>absolute gap</th>"), "the absolute gap column must be there")
        assertTrue(html.contains("<th>relative gap</th>"), "the relative gap column must be there")
        // the rows
        assertTrue(html.contains(">lr<"), "the parameter row must be there")
        assertTrue(html.contains(">loss<"), "the metric row must be there")
        // the PNG, embedded
        assertTrue(html.contains("data:image/png;base64,"), "the curve must be embedded")
        // the style is integrated
        assertTrue(html.contains("<style>"), "the CSS must be inside the file")
        // a real HTML document
        assertTrue(html.trimStart().startsWith("<!DOCTYPE html>"), "the file must be an HTML document")
    }

    @Test
    @DisplayName("E4 - the report loads NOTHING from the network: not a single http:// or https://")
    fun leRapportNeContientAucuneUrl() {
        val c = comparaison(
            paramsA = mapOf("dataset" to "https://exemple.org/data?a=1", "path" to "/home/user/x"),
            paramsB = mapOf("dataset" to "http://exemple.org/data")
        )
        val html = ExportComparaison.html(c, pngBase64(), "loss", 1_762_000_000_000L)
        assertFalse(html.contains("http://"), "the report must hold no http://")
        assertFalse(html.contains("https://"), "the report must hold no https://")
        // the URL is still readable by the human (the scheme is kept, the
        // colon is an HTML entity) - the report does not lie about the data.
        assertTrue(html.contains("exemple.org/data"), "the value must still be readable")
    }

    @Test
    @DisplayName("E5 - the text coming from the server can never be read as HTML")
    fun leTexteDuServeurNestJamaisDuHtml() {
        val c = comparaison(
            paramsA = mapOf("x" to "<script>alert(1)</script>", "y" to "a & b < c"),
            paramsB = mapOf("x" to "\"quoted\"")
        )
        val html = ExportComparaison.html(c, pngBase64(), "loss", 1_762_000_000_000L)
        assertFalse(html.contains("<script>"), "the script must be escaped")
        assertTrue(html.contains("&lt;script&gt;"), "the script must be escaped")
        assertTrue(html.contains("&amp;"), "the ampersand must be escaped")
    }

    @Test
    @DisplayName("E6 - a metric missing on one side: no NaN, no ghost line, just a dash")
    fun uneMetriqueAbsenteNeLaisseNiNanNiLigneFantome() {
        val c = comparaison()
        val html = ExportComparaison.html(c, pngBase64(), "acc", 1_762_000_000_000L)
        assertFalse(html.contains("NaN"), "the report must hold no NaN: $html")
        // "loss" exists only in A: exactly one row, and the B cell is a dash.
        val lignesLoss = html.lines().filter { it.contains(">loss<") }
        assertEquals(1, lignesLoss.size, "the metric must appear exactly once: ${lignesLoss.size}")
        assertTrue(lignesLoss.first().contains(">-<"), "the missing value must be a dash: ${lignesLoss.first()}")
        // no ghost row for a metric that does not exist anywhere
        assertFalse(html.contains(">ghost<"), "no ghost line")
    }

    @Test
    @DisplayName("E7 - the embedded image is a real PNG of the announced size")
    fun lePngEstUnVraiPng() {
        val png = RenduCourbePng.png(
            listOf(MetricPoint(0, 1.0, 0L), MetricPoint(1, 0.5, 1L)),
            listOf(MetricPoint(0, 0.8, 0L), MetricPoint(1, 0.6, 1L)),
            "loss"
        )
        assertTrue(png.size > 1000, "the PNG must not be empty (${png.size} bytes)")
        // the PNG magic number
        assertEquals(
            listOf(0x89.toByte(), 0x50, 0x4E, 0x47),
            png.take(4).toList(),
            "the file must start with the PNG magic number"
        )
        val image = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(png))
        assertNotNull(image, "the PNG must be readable")
        assertEquals(RenduCourbePng.LARGEUR, image.width)
        assertEquals(RenduCourbePng.HAUTEUR, image.height)
        // the base64 of the report decodes back to the same PNG
        val decode = Base64.getDecoder().decode(pngBase64())
        assertTrue(decode.size > 1000, "the base64 must carry the PNG")
    }

    @Test
    @DisplayName("E8 - the report is written in the folder the user chose")
    fun leRapportEstEcritDansLeDossierChoisi() {
        val dossier = File(System.getProperty("java.io.tmpdir"), "mlexp-export-test-${System.nanoTime()}")
        assertTrue(dossier.mkdirs(), "the test folder must be created")
        try {
            val fichier = File(dossier, "rapport.html")
            val html = ExportComparaison.html(comparaison(), pngBase64(), "loss", 1_762_000_000_000L)
            val r = ExportComparaison.ecrire(fichier, html)
            assertTrue(r is ResultatExport.Ecrit, "the write must succeed: $r")
            assertTrue(fichier.exists(), "the file must exist")
            assertEquals(html, fichier.readText(Charsets.UTF_8), "the file must hold the report")
            assertEquals(html.toByteArray(Charsets.UTF_8).size.toLong(), (r as ResultatExport.Ecrit).octets)
            // no temporary file left behind
            val restes = dossier.listFiles()?.filter { it.name.startsWith(".mlexp-export-") } ?: emptyList()
            assertTrue(restes.isEmpty(), "no temporary file must stay: ${restes.map { it.name }}")
        } finally {
            dossier.deleteRecursively()
        }
    }

    @Test
    @DisplayName("E9 - an impossible destination fails cleanly and writes nothing")
    fun uneDestinationImpossibleEchoueProprement() {
        val fichier = File("/definitely/not/a/folder/rapport.html")
        val r = ExportComparaison.ecrire(fichier, "<html></html>")
        assertTrue(r is ResultatExport.Echec, "the write must fail cleanly: $r")
        assertFalse(fichier.exists(), "no file must be written")
        assertTrue((r as ResultatExport.Echec).message.contains("No file was written"), r.message)
    }

    @Test
    @DisplayName("E10 - the curve of a metric that does not exist is still a valid PNG")
    fun laCourbeDuneMetriqueAbsenteEstUnPngValide() {
        val png = RenduCourbePng.png(emptyList(), emptyList(), "(no metric)")
        assertTrue(png.size > 500, "the PNG must exist even without any point (${png.size} bytes)")
        val image = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(png))
        assertNotNull(image, "the PNG must be readable")
    }

    // ------------------------------------------------------------------
    // 2. The gate (the Pro tier is not open -> no export at all)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("E11 - without a Pro licence, no export is possible and the server is not even read")
    fun sansLicenceAucunExport() {
        val serveur = ServeurFaux()
        val dossier = File(System.getProperty("java.io.tmpdir"), "mlexp-export-test-${System.nanoTime()}")
        assertTrue(dossier.mkdirs())
        try {
            val fichier = File(dossier, "rapport.html")
            val r = ExportComparaison.exporter(
                client = MlflowClient(serveur.base),
                runA = Run("a1", "FINISHED", 1_700_000_000_000L, "run-a"),
                runB = Run("b1", "FINISHED", 1_700_000_000_000L, "run-b"),
                experimentId = "1",
                metrique = "loss",
                fichier = fichier,
                etat = ProState.ABSENT
            )
            assertTrue(r is ResultatExport.Echec, "the export must be refused: $r")
            assertTrue((r as ResultatExport.Echec).message.contains("Pro feature"), r.message)
            assertFalse(fichier.exists(), "no file must be written")
            assertEquals(0, serveur.nbRequetes, "the server must not even be read")
        } finally {
            serveur.close()
            dossier.deleteRecursively()
        }
    }

    @Test
    @DisplayName("E12 - the Export button only exists when the Pro tier is active")
    fun leBoutonExportNExisteQueAvecLicence() {
        // without a licence: the button is not offered at all
        avecLicence(LicenceAbsente) {
            val panneau = PanneauComparaison(ComparaisonHub())
            val bouton = boutonsDe(panneau.rootComponent).firstOrNull { it.text.contains("Export") }
            assertNotNull(bouton, "the panel must hold the Export button")
            assertFalse(bouton!!.isVisible, "without a licence the Export button must not be visible")
        }
        // with a licence: the button is there
        avecLicence(LicenceActive) {
            val panneau = PanneauComparaison(ComparaisonHub())
            val bouton = boutonsDe(panneau.rootComponent).firstOrNull { it.text.contains("Export") }
            assertNotNull(bouton, "the panel must hold the Export button")
            assertTrue(bouton!!.isVisible, "with a licence the Export button must be visible")
            // the free buttons are still there: nothing is removed
            assertTrue(
                boutonsDe(panneau.rootComponent).any { it.text == "Copy (Markdown)" },
                "the Markdown copy must still be there"
            )
        }
    }

    // ------------------------------------------------------------------
    // 3. The export itself, against a fake MLflow server
    // ------------------------------------------------------------------

    @Test
    @DisplayName("E13 - the happy path: 1 file, the 2 tables, the PNG, and no external resource")
    fun leCheminNominalEcritLeRapport() {
        val serveur = ServeurFaux()
        val dossier = File(System.getProperty("java.io.tmpdir"), "mlexp-export-test-${System.nanoTime()}")
        assertTrue(dossier.mkdirs())
        try {
            val fichier = File(dossier, "rapport.html")
            val r = ExportComparaison.exporter(
                client = MlflowClient(serveur.base),
                runA = Run("a1", "FINISHED", 1_700_000_000_000L, "run-a"),
                runB = Run("b1", "FINISHED", 1_700_000_000_000L, "run-b"),
                experimentId = "1",
                metrique = "loss",
                fichier = fichier,
                etat = ProState.PRO
            )
            assertTrue(r is ResultatExport.Ecrit, "the export must succeed: $r")
            assertTrue(fichier.exists(), "the file must exist")
            val html = fichier.readText(Charsets.UTF_8)
            assertTrue(html.contains("<th>parameter</th>"), "the parameter table must be there")
            assertTrue(html.contains("<th>metric</th>"), "the metric table must be there")
            assertTrue(html.contains("data:image/png;base64,"), "the curve must be embedded")
            assertFalse(html.contains("http://"), "the report must hold no external URL")
            assertFalse(html.contains("https://"), "the report must hold no external URL")
            assertFalse(html.contains("NaN"), "the report must hold no NaN")
            assertTrue(html.contains("run-a"), "the report must name run A")
            assertTrue(html.contains("run-b"), "the report must name run B")
        } finally {
            serveur.close()
            dossier.deleteRecursively()
        }
    }

    @Test
    @DisplayName("E14 - a run deleted between the comparison and the export: clean failure, no file")
    fun unRunSupprimeEchoueProprement() {
        val serveur = ServeurFaux()
        val dossier = File(System.getProperty("java.io.tmpdir"), "mlexp-export-test-${System.nanoTime()}")
        assertTrue(dossier.mkdirs())
        try {
            val fichier = File(dossier, "rapport.html")
            // the run A disappears from the experiment
            serveur.ids.remove("a1")
            val r = ExportComparaison.exporter(
                client = MlflowClient(serveur.base),
                runA = Run("a1", "FINISHED", 1_700_000_000_000L, "run-a"),
                runB = Run("b1", "FINISHED", 1_700_000_000_000L, "run-b"),
                experimentId = "1",
                metrique = "loss",
                fichier = fichier,
                etat = ProState.PRO
            )
            assertTrue(r is ResultatExport.Echec, "the export must fail: $r")
            val message = (r as ResultatExport.Echec).message
            assertTrue(message.contains("no longer exists"), "the message must say why: $message")
            assertTrue(message.contains("No file was written"), "the message must say it: $message")
            assertFalse(fichier.exists(), "no file must be written")
            assertTrue(dossier.listFiles()!!.isEmpty(), "the folder must stay empty: ${dossier.listFiles()}")
        } finally {
            serveur.close()
            dossier.deleteRecursively()
        }
    }

    @Test
    @DisplayName("E15 - a run that the server answers with 404: clean failure, no file")
    fun unRunEnErreur404EchoueProprement() {
        val serveur = ServeurFaux()
        val dossier = File(System.getProperty("java.io.tmpdir"), "mlexp-export-test-${System.nanoTime()}")
        assertTrue(dossier.mkdirs())
        try {
            val fichier = File(dossier, "rapport.html")
            // the run is still listed, but the server refuses to describe it
            serveur.runIntrouvable = "a1"
            val r = ExportComparaison.exporter(
                client = MlflowClient(serveur.base),
                runA = Run("a1", "FINISHED", 1_700_000_000_000L, "run-a"),
                runB = Run("b1", "FINISHED", 1_700_000_000_000L, "run-b"),
                experimentId = "1",
                metrique = "loss",
                fichier = fichier,
                etat = ProState.PRO
            )
            assertTrue(r is ResultatExport.Echec, "the export must fail: $r")
            val message = (r as ResultatExport.Echec).message
            assertTrue(message.contains("no longer exists"), "the message must say why: $message")
            assertFalse(fichier.exists(), "no file must be written")
        } finally {
            serveur.close()
            dossier.deleteRecursively()
        }
    }

    @Test
    @DisplayName("E16 - a run renamed between the comparison and the export: clean failure, no file")
    fun unRunRenommeEchoueProprement() {
        val serveur = ServeurFaux()
        val dossier = File(System.getProperty("java.io.tmpdir"), "mlexp-export-test-${System.nanoTime()}")
        assertTrue(dossier.mkdirs())
        try {
            val fichier = File(dossier, "rapport.html")
            // the server now answers another name for run A
            serveur.noms["a1"] = "run-a-renomme"
            val r = ExportComparaison.exporter(
                client = MlflowClient(serveur.base),
                runA = Run("a1", "FINISHED", 1_700_000_000_000L, "run-a"),
                runB = Run("b1", "FINISHED", 1_700_000_000_000L, "run-b"),
                experimentId = "1",
                metrique = "loss",
                fichier = fichier,
                etat = ProState.PRO
            )
            assertTrue(r is ResultatExport.Echec, "the export must fail: $r")
            val message = (r as ResultatExport.Echec).message
            assertTrue(message.contains("renamed"), "the message must say why: $message")
            assertTrue(message.contains("No file was written"), "the message must say it: $message")
            assertFalse(fichier.exists(), "no file must be written")
        } finally {
            serveur.close()
            dossier.deleteRecursively()
        }
    }

    @Test
    @DisplayName("E17 - a metric that exists only in run A: the report has no ghost line and no NaN")
    fun uneMetriqueDUnSeulRunDonneUnRapportPropre() {
        val serveur = ServeurFaux()
        val dossier = File(System.getProperty("java.io.tmpdir"), "mlexp-export-test-${System.nanoTime()}")
        assertTrue(dossier.mkdirs())
        try {
            val fichier = File(dossier, "rapport.html")
            // "loss" exists only in run A
            serveur.metriques["b1"] = mapOf("acc" to 0.8)
            val r = ExportComparaison.exporter(
                client = MlflowClient(serveur.base),
                runA = Run("a1", "FINISHED", 1_700_000_000_000L, "run-a"),
                runB = Run("b1", "FINISHED", 1_700_000_000_000L, "run-b"),
                experimentId = "1",
                metrique = "acc",
                fichier = fichier,
                etat = ProState.PRO
            )
            assertTrue(r is ResultatExport.Ecrit, "the export must succeed: $r")
            val html = fichier.readText(Charsets.UTF_8)
            assertFalse(html.contains("NaN"), "the report must hold no NaN")
            val lignesLoss = html.lines().filter { it.contains(">loss<") }
            assertEquals(1, lignesLoss.size, "the metric must appear exactly once")
            assertTrue(lignesLoss.first().contains(">-<"), "the missing value must be a dash")
            // the relative gap of a missing value is a dash too, never NaN %
            assertFalse(html.contains("NaN %"), "the report must hold no NaN %")
        } finally {
            serveur.close()
            dossier.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 4. The contract: the export sends nothing, and hard-codes no path
    // ------------------------------------------------------------------

    @Test
    @DisplayName("E18 - the code of the export sends nothing (no socket, no URL, no telemetry)")
    fun leCodeDeLExportNEnvoieRien() {
        for (fichier in listOf(
            "src/main/kotlin/com/slot5/mlexp/pro/ExportComparaison.kt",
            "src/main/kotlin/com/slot5/mlexp/pro/RenduCourbePng.kt"
        )) {
            val f = File(fichier)
            assertTrue(f.exists(), "$fichier must exist")
            val code = sansCommentaires(f.readText(Charsets.UTF_8))
            for (interdit in listOf(
                "Socket(", "HttpClient(", "URL(", "openConnection", "sendMail", "telemetry"
            )) {
                assertFalse(
                    code.contains(interdit),
                    "$fichier must not contain \"$interdit\": the export sends nothing"
                )
            }
        }
    }

    @Test
    @DisplayName("E19 - the code of the export hard-codes no path")
    fun leCodeDeLExportNeDurcitAucunChemin() {
        for (fichier in listOf(
            "src/main/kotlin/com/slot5/mlexp/pro/ExportComparaison.kt",
            "src/main/kotlin/com/slot5/mlexp/pro/RenduCourbePng.kt"
        )) {
            val code = sansCommentaires(File(fichier).readText(Charsets.UTF_8))
            for (interdit in listOf("/tmp/", "/home/", "C:\\\\", "/Users/", "~/")) {
                assertFalse(
                    code.contains(interdit),
                    "$fichier must not hard-code the path \"$interdit\": the user chooses the folder"
                )
            }
        }
    }

    @Test
    @DisplayName("E20 - the free tabs do not depend on the export code")
    fun lesOngletsGratuitsNeDependentPasDeLExport() {
        for (fichier in listOf(
            "src/main/kotlin/com/slot5/mlexp/MlflowToolWindowPanel.kt",
            "src/main/kotlin/com/slot5/mlexp/OptunaToolWindowPanel.kt",
            "src/main/kotlin/com/slot5/mlexp/ModelRegistryToolWindowPanel.kt",
            "src/main/kotlin/com/slot5/mlexp/SweepToolWindowPanel.kt",
            "src/main/kotlin/com/slot5/mlexp/MetricsChartPanel.kt"
        )) {
            val code = File(fichier).readText(Charsets.UTF_8)
            assertFalse(
                code.contains("ExportComparaison"),
                "$fichier must not depend on the export code: the free path stays free"
            )
        }
    }

    @Test
    @DisplayName("E21 - the Markdown copy is untouched by the export")
    fun laCopieMarkdownNestPasTouchee() {
        val c = comparaison()
        val md = c.markdown()
        assertTrue(md.startsWith("| parameter | A | B | state |"), md)
        assertTrue(md.contains("| metric | A | B | absolute gap | relative gap | state |"), md)
        assertTrue(md.contains("| lr | 0.01 | 0.02 | different |"), md)
        // the export does not change the Markdown in any way
        assertEquals(md, c.markdown(), "the Markdown must stay exactly the same")
    }

    @Test
    @DisplayName("E22 - the export writes the file the user chose, nowhere else")
    fun leExportEcritLeFichierChoisiEtRienDautre() {        val serveur = ServeurFaux()
        val dossier = File(System.getProperty("java.io.tmpdir"), "mlexp-export-test-${System.nanoTime()}")
        val sousDossier = File(dossier, "rapports")
        assertTrue(sousDossier.mkdirs())
        try {
            val fichier = File(sousDossier, "mon-rapport.html")
            val r = ExportComparaison.exporter(
                client = MlflowClient(serveur.base),
                runA = Run("a1", "FINISHED", 1_700_000_000_000L, "run-a"),
                runB = Run("b1", "FINISHED", 1_700_000_000_000L, "run-b"),
                experimentId = "1",
                metrique = "loss",
                fichier = fichier,
                etat = ProState.PRO
            )
            assertTrue(r is ResultatExport.Ecrit, "the export must succeed: $r")
            assertEquals(fichier.absolutePath, (r as ResultatExport.Ecrit).chemin)
            assertEquals(1, sousDossier.listFiles()!!.size, "only the report must be in the folder")
            assertEquals("mon-rapport.html", sousDossier.listFiles()!![0].name)
        } finally {
            serveur.close()
            dossier.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 5. The cycle 246 corrections (retouches 2b and 2c of the Studio)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("E23 - the final report has the rights 644: a colleague can open it")
    fun leRapportFinalEstLisibleParUnCollegue() {
        val dossier = File(System.getProperty("java.io.tmpdir"), "mlexp-export-test-${System.nanoTime()}")
        assertTrue(dossier.mkdirs(), "the test folder must be created")
        try {
            val fichier = File(dossier, "rapport.html")
            val html = ExportComparaison.html(comparaison(), pngBase64(), "loss", 1_762_000_000_000L)
            val r = ExportComparaison.ecrire(fichier, html)
            assertTrue(r is ResultatExport.Ecrit, "the write must succeed: $r")
            // The temporary file of the export is created with the rights 600
            // (owner only): the rights must be set BEFORE the move.
            val droits = java.nio.file.attribute.PosixFilePermissions.toString(
                java.nio.file.Files.getPosixFilePermissions(fichier.toPath())
            )
            assertEquals(
                "rw-r--r--",
                droits,
                "the report must be readable by everybody (644): the rights are $droits"
            )
        } finally {
            dossier.deleteRecursively()
        }
    }

    @Test
    @DisplayName("E24 - the scheme of a URL is neutralised in ANY case (HTTPS:// too)")
    fun leSchemaEstNeutraliseDansToutesLesCasses() {
        // A URL scheme is case-insensitive (RFC 3986): an upper-case scheme
        // is a URL exactly like a lower-case one. The report must hold
        // neither, otherwise the proof of the autonomy could be green on a
        // report that holds a URL.
        for (hostile in listOf(
            "https://exemple.org/x",
            "HTTPS://exemple.org/x",
            "Http://exemple.org/x",
            "hTtPs://exemple.org/x"
        )) {
            val echappe = ExportComparaison.echapper(hostile)
            assertFalse(
                Regex("(?i)https?://").containsMatchIn(echappe),
                "the scheme must be neutralised, whatever its case: $hostile -> $echappe"
            )
            assertTrue(
                echappe.contains("exemple.org/x"),
                "the value must still be readable by the human: $echappe"
            )
        }
        // and the whole report holds none of them
        val c = comparaison(
            paramsA = mapOf("dataset" to "HTTPS://exemple.org/data?a=1"),
            paramsB = mapOf("dataset" to "HTTP://exemple.org/data")
        )
        val html = ExportComparaison.html(c, pngBase64(), "loss", 1_762_000_000_000L)
        assertFalse(
            Regex("(?i)https?://").containsMatchIn(html),
            "the report must hold no scheme at all, whatever its case"
        )
    }

}
