package com.slot5.mlexp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests du parseur JSON écrit à la main (`JsonParser`, `Json.kt`).
 *
 * Ils existent parce que le parseur a été introduit en 0.0.13 pour réparer un
 * bug **mesuré** : les 15 `Regex(` de `MlflowClient` cherchaient
 * `"experiment_id":"` sans espace, alors que **MLflow 3.x renvoie du JSON
 * indenté** → 7 parsing sur 7 en échec. Un test écrit sur des fixtures
 * *compactes* n'aurait pas vu le défaut ; celui-ci teste donc explicitement
 * les deux écritures (compacte et indentée) et exige qu'elles donnent le même
 * résultat.
 *
 * Le test doit être **ROUGE** si le parseur redevient naïf : c'est vérifié par
 * mutation (le `skipWs()` est neutralisé dans une copie de travail, le test
 * échoue, la copie est jetée) — journal
 * `/work/research/cycle58/tests_kotlin_red_mutation.log`.
 */
class JsonParserTest {

    @Test
    @DisplayName("un corps INDENTÉ (MLflow 3.x) donne le même résultat qu'un corps compact")
    fun indentedBodyIsParsedLikeCompactOne() {
        val compact = """{"experiment":{"experiment_id":"2","name":"nlp-finetune","lifecycle_stage":"active"}}"""
        val indented = """
            {
              "experiment": {
                "experiment_id": "2",
                "name": "nlp-finetune",
                "lifecycle_stage": "active"
              }
            }
        """.trimIndent()

        val a = JsonParser.parse(compact).obj("experiment")
        val b = JsonParser.parse(indented).obj("experiment")

        assertEquals(a.str("experiment_id"), b.str("experiment_id"))
        assertEquals(a.str("name"), b.str("name"))
        assertEquals(a.str("lifecycle_stage"), b.str("lifecycle_stage"))
        assertEquals("2", b.str("experiment_id"))
        assertEquals("nlp-finetune", b.str("name"))
    }

    @Test
    @DisplayName("un champ null est lu comme null, pas comme une chaîne vide")
    fun nullFieldIsJsonNull() {
        // Réponse réelle de W&B pour un projet inexistant (mesurée le 24/10).
        val root = JsonParser.parse("""{"data":{"project":null}}""")
        val data = root.obj("data")
        assertTrue(data.has("project"), "data.project doit etre present")
        assertNull(data.get("project") as? Json.Obj, "data.project ne doit PAS etre un objet")
        assertEquals(Json.Null, data.get("project"))
    }

    @Test
    @DisplayName("les echappements de chaine sont decodes")
    fun escapesAreDecoded() {
        val root = JsonParser.parse("""{"a":"quote:\" backslash:\\ slash:\/ tab:\t newline:\n u:\u00e9"}""")
        assertEquals("quote:\" backslash:\\ slash:/ tab:\t newline:\n u:é", root.str("a"))
    }

    @Test
    @DisplayName("les nombres sont lus a l'identique, y compris en notation scientifique")
    fun numbersAreReadExactly() {
        val root = JsonParser.parse("""{"i":42,"f":0.5,"e":1e-05,"neg":-3}""")
        assertEquals(42L, root.lng("i"))
        assertEquals(0.5, root.dbl("f"))
        // 1e-05 doit rester 1e-05 : le texte d'origine est conserve.
        assertEquals("1e-05", ((root as Json.Obj).get("e") as Json.Num).text)
        assertEquals(-3L, root.lng("neg"))
    }

    @Test
    @DisplayName("un corps tronque est refuse (pas de resultat silencieux faux)")
    fun truncatedBodyIsRejected() {
        try {
            JsonParser.parse("""{"a":1""")
            fail("un JSON tronque doit lever JsonException")
        } catch (e: JsonException) {
            assertTrue(e.message!!.contains("tronque"), "message inattendu : ${e.message}")
        }
    }

    @Test
    @DisplayName("un corps qui n'est pas du JSON est refuse")
    fun htmlBodyIsRejected() {
        // Un serveur qui repond 200 avec une page HTML ne doit pas produire
        // une liste vide : la panneau doit dire ce qui s'est passe.
        try {
            JsonParser.parse("<!DOCTYPE html><html><body>502</body></html>")
            fail("une page HTML doit lever JsonException")
        } catch (e: JsonException) {
            assertTrue(e.message!!.contains("caractere inattendu"), "message inattendu : ${e.message}")
        }
    }

    @Test
    @DisplayName("un tableau et un objet imbrique sont lus")
    fun arraysAndNestingWork() {
        val root = JsonParser.parse("""{"edges":[{"node":{"id":"a"}},{"node":{"id":"b"}}]}""")
        val edges = root.arr("edges")
        assertEquals(2, edges.size)
        assertEquals("a", edges[0].obj("node").str("id"))
        assertEquals("b", edges[1].obj("node").str("id"))
        // Un champ absent ne doit pas faire planter la lecture.
        assertEquals("", root.obj("inexistant").str("id"))
    }
}
