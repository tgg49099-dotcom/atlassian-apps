package com.slot5.mlexp

/**
 * v2026.1.5 — **un seul message actionnable** pour les trois clients
 * (MLflow, Optuna, W&B) quand l'adresse du serveur est mal saisie.
 *
 * Le defaut mesure : l'utilisateur lisait « **invalid URI scheme localhost** »,
 * le message **brut de la JVM** (`HttpRequest.Builder.build()`). Ce message dit
 * ce que la JVM a refuse, pas ce qu'il faut corriger ; l'utilisateur ne sait
 * pas qu'il a oublie « http:// ».
 *
 * Le message desormais affiche nomme **l'adresse fautive** et rappelle les deux
 * formes acceptees :
 *
 *     Invalid <CLIENT> URL: "<adresse>" - the scheme is missing (http:// or https://)
 *
 * Il est en **anglais**, comme toute l'interface du plugin, et il est **le meme
 * pour les trois clients** : seul le nom du client change (MLflow, Optuna,
 * W&B). Le test qui le verifie est `Regression0026Test` (un test par client).
 */
object BaseUrl {

    /**
     * The message shown when the configured address cannot be used as an HTTP
     * URL. The most common cause is a forgotten scheme (« localhost:5000 »
     * instead of « http://localhost:5000 »), which is what the message says;
     * an address whose scheme is neither http nor https (for example
     * « ftp://… ») gets the same message, which also names the two accepted
     * forms.
     */
    fun invalidUrlMessage(client: String, url: String): String =
        "Invalid $client URL: \"$url\" - the scheme is missing (http:// or https://)"

    /**
     * True when [url] cannot be used as an HTTP(S) URL, i.e. when it does not
     * start with « http:// » or « https:// ». An empty or blank address is
     * invalid too.
     */
    fun isNotHttpUrl(url: String): Boolean {
        val u = url.trim()
        return !(u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true))
    }
}
