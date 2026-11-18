package com.slot5.mlexp

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Contre-epreuve du cycle 235 : **l'URI MLflow mal saisie** (onglet principal).
 *
 * Le defaut, mesure :
 *   - l'onglet **Experiments** traite DEUX types d'exception :
 *       catch (e: MlflowException) -> « Failed to load experiments: … »
 *       catch (t: Throwable)        -> « Unexpected error: … » + bulle rouge
 *     Le type de l'exception levee est donc exactement ce qui decide du
 *     message affiche a l'utilisateur ;
 *   - or `MlflowClient.get()` et `MlflowClient.post()` construisaient la
 *     requete HTTP **HORS du `try`** (`URI.create(...)` puis
 *     `HttpRequest…build()`). Une URI mal saisie — le schema « http:// »
 *     oublie, c'est-a-dire « localhost:5000 » au lieu de
 *     « http://localhost:5000 » — leve donc une
 *     `IllegalArgumentException("invalid URI scheme localhost")` **brute**,
 *     qui n'est pas une `MlflowException` : l'utilisateur voit
 *     « Unexpected error: invalid URI scheme localhost » et une bulle
 *     « Project unexpected error », pour une simple faute de frappe ;
 *   - **ce defaut est deja corrige et teste pour le client Optuna**
 *     (v0.0.20, `Regression0020Test` defaut n° 3 : la requete a ete construite
 *     DANS le `try`). Meme defaut, meme correctif, meme test — mais le client
 *     **MLflow**, celui du tout premier onglet et celui que la fiche publique
 *     demande de configurer en premier, n'a jamais recu le correctif.
 *
 * Ces tests sont **ROUGE sur le code d'avant la correction** (l'exception
 * levee est une `IllegalArgumentException`, pas une `MlflowException`) et
 * **VERT apres**. Ils n'utilisent que le JDK et JUnit 5 : aucune dependance a
 * l'IDE, aucun serveur.
 */
class Regression0025Test {

    // ---------------------------------------------------------------------
    // 1 - POST (experiments/search) : le chemin le plus utilise
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("1 - une URI MLflow invalide donne « Failed to load experiments », pas « Unexpected error »")
    fun invalidMlflowUriIsReportedAsAFailedLoad() {
        // URI mal saisie : il manque le schema « http:// ». `URI.create()`
        // accepte la chaine (scheme = « localhost »), mais c'est
        // `HttpRequest.Builder.build()` qui leve
        // `IllegalArgumentException("invalid URI scheme localhost")`.
        val client = MlflowClient("localhost:5000")

        val e = assertThrows(MlflowException::class.java) {
            client.listExperiments()
        }
        val message = e.message ?: ""
        // v2026.1.5 — le message n'est plus celui de la JVM
        // (« invalid URI scheme localhost ») mais le message actionnable :
        // « Invalid MLflow URL: "localhost:5000" - the scheme is missing
        // (http:// or https://) » (voir Regression0026Test).
        assertTrue(
            message.contains("Invalid MLflow URL"),
            "le message doit dire quelle URI est refusee, il dit : $message"
        )
        assertTrue(
            message.contains("localhost:5000") || message.contains("localhost"),
            "le message doit nommer l'URI refusee, il dit : $message"
        )
    }

    // ---------------------------------------------------------------------
    // 2 - GET (registered-models/search) : meme client, autre methode
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("2 - le GET (registered-models/search) donne aussi une MlflowException")
    fun invalidMlflowUriIsReportedAsAFailedLoadOnGetToo() {
        val client = MlflowClient("localhost:5000")

        val e = assertThrows(MlflowException::class.java) {
            client.listRegisteredModels()
        }
        val message = e.message ?: ""
        assertTrue(
            message.contains("Invalid MLflow URL"),
            "le message doit dire quelle URI est refusee, il dit : $message"
        )
    }

    // ---------------------------------------------------------------------
    // 3 - une URI illisible (espace) : meme traitement
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("3 - une URI MLflow illisible donne aussi une MlflowException")
    fun unreadableMlflowUriIsReportedAsAFailedLoad() {
        // Un espace dans l'URI fait echouer `URI.create()` lui-meme.
        val client = MlflowClient("http://local host:5000")

        val e = assertThrows(MlflowException::class.java) {
            client.listExperiments()
        }
        val message = e.message ?: ""
        assertTrue(
            message.contains("local host") || message.contains("Illegal character"),
            "le message doit nommer l'URI refusee, il dit : $message"
        )
    }
}
