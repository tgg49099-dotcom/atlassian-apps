package com.slot5.mlexp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Contre-epreuve du cycle 236 (v2026.1.5) : **le message d'erreur quand
 * l'adresse du serveur est mal saisie**, et le **dernier client** qui construisait
 * sa requete HTTP **hors du `try`**.
 *
 * 1. **LE DEFAUT TROUVE PAR LA MERE, VERIFIE DANS LE CODE.**
 *    Dans `WandbClient.postGraphQL`, la requete etait construite **AVANT** le
 *    `try` : `.uri(URI.create(endpoint))` a la ligne 258, le `try` ne commence
 *    qu'a la ligne 265. Une adresse W&B mal saisie (« `wandb.ai` » au lieu de
 *    « `https://wandb.ai` ») levait donc une `IllegalArgumentException`
 *    **brute**, hors du `catch (t: Throwable)` : l'onglet Sweeps affichait
 *    « **Unexpected error** » + une bulle rouge, pour une simple faute de
 *    frappe. C'est exactement le defaut deja corrige (et teste) pour
 *    `OptunaClient` en v0.0.20 et pour `MlflowClient` en v2026.1.5.
 *
 * 2. **LE MESSAGE ACTIONNABLE.** L'utilisateur lisait « invalid URI scheme
 *    localhost », le message **brut de la JVM** : il ne sait pas quoi corriger.
 *    Un seul message est desormais employe par les **trois** clients (MLflow,
 *    Optuna, W&B), en anglais (l'interface est en anglais) :
 *
 *        Invalid <CLIENT> URL: "<adresse>" - the scheme is missing (http:// or https://)
 *
 *    L'adresse fautive est nommee, et les deux formes acceptees sont rappelees.
 *
 * Ces tests sont **ROUGE sur le code d'avant la correction** et **VERT apres** :
 *   - test 1 : l'exception levee n'est pas une `WandbException` mais une
 *     `IllegalArgumentException` brute ;
 *   - tests 2, 3, 4 : le message n'est pas celui qui est attendu.
 *
 * Aucun test ne depend de l'IDE : ils n'utilisent que le JDK et JUnit 5.
 */
class Regression0026Test {

    // ---------------------------------------------------------------------
    // 1 - W&B : le defaut de la mere (requete construite hors du `try`)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("1 - une URL W&B sans schema donne une WandbException, pas une IllegalArgumentException brute")
    fun invalidWandbUriIsReportedAsAWandbException() {
        // Adresse mal saisie : il manque le schema « https:// ».
        // Avant la correction, `URI.create(endpoint)` puis
        // `HttpRequest…build()` etaient **hors du `try`** : l'exception brute
        // `IllegalArgumentException` remontait jusqu'a l'onglet, qui treat
        // `catch (t: Throwable)` -> « Unexpected error ».
        val client = WandbClient("wandb.ai", "fake-api-key")

        val e = assertThrows(WandbException::class.java) {
            client.listSweeps("my-entity", "my-project")
        }
        val message = e.message ?: ""
        assertTrue(
            message.contains("Invalid W&B URL"),
            "le message doit dire quelle URL est refusee, il dit : $message"
        )
        assertTrue(
            message.contains("wandb.ai"),
            "le message doit nommer l'URL refusee, il dit : $message"
        )
    }

    // ---------------------------------------------------------------------
    // 2 - MLflow : le message actionnable
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("2 - MLflow : « Invalid MLflow URL: … - the scheme is missing (http:// or https://) »")
    fun mlflowUrlMessageIsActionable() {
        val client = MlflowClient("localhost:5000")

        val e = assertThrows(MlflowException::class.java) {
            client.listExperiments()
        }
        assertEquals(
            "Invalid MLflow URL: \"localhost:5000\" - the scheme is missing (http:// or https://)",
            e.message
        )
    }

    // ---------------------------------------------------------------------
    // 3 - Optuna : le meme message, pour le client Optuna
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("3 - Optuna : « Invalid Optuna URL: … - the scheme is missing (http:// or https://) »")
    fun optunaUrlMessageIsActionable() {
        val client = OptunaClient("localhost:8080")

        val e = assertThrows(OptunaException::class.java) {
            client.listStudies()
        }
        assertEquals(
            "Invalid Optuna URL: \"localhost:8080\" - the scheme is missing (http:// or https://)",
            e.message
        )
    }

    // ---------------------------------------------------------------------
    // 4 - W&B : le meme message, pour le client W&B
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("4 - W&B : « Invalid W&B URL: … - the scheme is missing (http:// or https://) »")
    fun wandbUrlMessageIsActionable() {
        val client = WandbClient("wandb.ai", "fake-api-key")

        val e = assertThrows(WandbException::class.java) {
            client.listSweeps("my-entity", "my-project")
        }
        assertEquals(
            "Invalid W&B URL: \"wandb.ai\" - the scheme is missing (http:// or https://)",
            e.message
        )
    }
}
