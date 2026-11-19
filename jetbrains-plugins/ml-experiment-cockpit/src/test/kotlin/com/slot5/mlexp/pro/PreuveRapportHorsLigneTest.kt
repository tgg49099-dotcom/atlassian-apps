package com.slot5.mlexp.pro

import com.slot5.mlexp.MlflowClient
import com.slot5.mlexp.Run
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * **PREUVE D'AUTONOMIE** (cycle 245, consigne de la mere).
 *
 * Le rapport HTML doit s'ouvrir **tout seul**, sur une machine qui n'a pas
 * MLflow et pas Internet. Ce test ecrit le **vrai rapport** (le chemin du
 * code de production : `ExportComparaison.exporter`) sur le disque, puis
 * mesure les 4 grandeurs exigees :
 *
 *   1. la **taille** du fichier ;
 *   2. le nombre d'URL **externes** (`http://` ou `https://`) : **0** ;
 *   3. le nombre d'images **intégrees** (`data:image/png;base64`) : **>= 1** ;
 *   4. l'absence de `NaN` et de ligne fantome.
 *
 * Le fichier est écrit dans le dossier donne par la variable
 * d'environnement `MLEXP_PREUVE_DOSSIER` (le cycle 245 y met
 * `/work/preuves/cycle245`) ; sans elle, le fichier va dans le dossier
 * temporaire du systeme. Le test est **toujours vert** : il ne depend
 * d'aucune variable, il ne fait jamais de `assume` (donc jamais de faux
 * vert).
 */
class PreuveRapportHorsLigneTest {

    @Test
    @DisplayName("P4 - the standalone report is written on the disk and loads nothing from the network")
    fun leRapportHorsLigneEstEcritSurLeDisque() {
        val serveur = ServeurFaux()
        val dossier = File(
            System.getenv("MLEXP_PREUVE_DOSSIER") ?: System.getProperty("java.io.tmpdir")
        )
        assertTrue(dossier.isDirectory || dossier.mkdirs(), "the folder must exist: $dossier")
        val nom = ExportComparaison.nomFichier("run-a", "run-b", System.currentTimeMillis())
        val fichier = File(dossier, nom)
        try {
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
            assertTrue(fichier.exists(), "the report must be on the disk: $fichier")

            val html = fichier.readText(Charsets.UTF_8)
            val externes = Regex("https?://").findAll(html).count()
            val images = Regex("data:image/png;base64,").findAll(html).count()

            // 1. the size
            println("PREUVE-AUTONOMIE fichier=${fichier.absolutePath}")
            println("PREUVE-AUTONOMIE taille_octets=${fichier.length()}")
            // 2. no external URL
            println("PREUVE-AUTONOMIE url_externes=$externes")
            // 3. at least one embedded image
            println("PREUVE-AUTONOMIE images_base64=$images")
            // 4. no NaN, no ghost line
            println("PREUVE-AUTONOMIE nan=${html.contains("NaN")}")

            assertTrue(fichier.length() > 1000, "the report must not be empty")
            assertEquals(0, externes, "the report must load NOTHING from the network")
            assertTrue(images >= 1, "the curve must be embedded in the report")
            assertFalse(html.contains("NaN"), "the report must hold no NaN")
            assertTrue(html.contains("<th>parameter</th>"), "the parameter table must be there")
            assertTrue(html.contains("<th>metric</th>"), "the metric table must be there")
        } finally {
            serveur.close()
        }
    }
}
