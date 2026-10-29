package com.slot5.mlexp

/**
 * Mini parseur JSON — analyse descendante récursive, sans dépendance.
 *
 * v0.0.13. Remplace les 15 `Regex(` de `MlflowClient`, qui ne fonctionnaient
 * sur aucune version de MLflow (le serveur renvoie un JSON **indenté**, pas
 * compact) — voir `NOTES_CYCLE41.md` §1 et le test de rejeu.
 *
 * Pourquoi écrit à la main plutôt qu'une librairie ? Mesure faite sur la
 * distribution PyCharm 2026.2 livrée dans le conteneur : **aucun** `gson`,
 * **aucun** `jackson` n'y est présent (1356 jar vérifiés). Ajouter Jackson
 * coûterait ~1,3 Mo au plugin pour lire 4 champs. Ce parseur fait 6 Ko, n'a
 * aucune dépendance et n'entre pas en conflit avec le reste du plugin.
 *
 * Ce qu'il gère : objet, tableau, chaîne (avec `\" \\ \/ \b \f \n \r \t \uXXXX`),
 * nombre (entier ou flottant, y compris `1e-05`), `true`, `false`, `null`.
 * Ce qu'il refuse : tout le reste, y compris le JSON tronqué — un corps
 * d'erreur HTML d'un serveur qui renvoie 200 est rejeté au lieu de produire
 * silencieusement une liste vide.
 */
sealed class Json {

    /** Objet JSON. L'ordre des clés d'origine est conservé. */
    class Obj(val members: Map<String, Json>) : Json() {
        operator fun get(key: String): Json? = members[key]
        fun has(key: String): Boolean = members.containsKey(key)
        val keys: Set<String> get() = members.keys
    }

    /** Tableau JSON. */
    class Arr(val items: List<Json>) : Json()

    /** Chaîne JSON, déjà déséchappée. */
    class Str(val value: String) : Json()

    /** Nombre JSON. On garde le texte d'origine : `1e-05` doit rester exact. */
    class Num(val text: String) : Json() {
        val asDouble: Double get() = text.toDouble()
        val asLong: Long get() = text.toLongOrNull() ?: text.toDouble().toLong()
    }

    class Bool(val value: Boolean) : Json()
    object Null : Json()

    // --- raccourcis de lecture, tous sûrs (pas d'exception, valeur par défaut) ---

    /** Élément d'un [Obj] lu comme chaîne ; `""` si absent ou pas une chaîne. */
    fun str(key: String, def: String = ""): String =
        (this as? Obj)?.get(key)?.let { it as? Str }?.value ?: def

    /** Élément d'un [Obj] lu comme double ; `0.0` si absent ou non numérique. */
    fun dbl(key: String, def: Double = 0.0): Double =
        (this as? Obj)?.get(key)?.let { it as? Num }?.asDouble ?: def

    /** Élément d'un [Obj] lu comme long ; `0L` si absent ou non numérique. */
    fun lng(key: String, def: Long = 0L): Long =
        (this as? Obj)?.get(key)?.let { it as? Num }?.asLong ?: def

    /** Élément d'un [Obj] lu comme [Num] ; `null` si absent ou d'un autre type.
     *  À utiliser quand un défaut silencieux masquerait une donnée absente. */
    fun num(key: String): Num? = (this as? Obj)?.get(key) as? Num

    /** Tableau de l'objet courant ; liste vide si absent ou pas un tableau. */
    fun arr(key: String): List<Json> =
        (this as? Obj)?.get(key)?.let { it as? Arr }?.items ?: emptyList()

    /** Sous-objet ; `Obj` vide si absent. */
    fun obj(key: String): Obj =
        (this as? Obj)?.get(key)?.let { it as? Obj } ?: Obj(emptyMap())
}

/** Levée quand le corps reçu n'est pas du JSON valide. */
class JsonException(message: String, val offset: Int) :
    RuntimeException("$message (position $offset)")

/**
 * Analyseur. Usage : `Json.parse(body)` → [Json].
 *
 * Volontairement strict : le code de production préfère une exception
 * explicite à un résultat silencieux faux, parce qu'un Tool Window qui affiche
 * « Loaded 0 experiment(s) » quand le serveur a renvoyé du HTML ne sert à rien
 * à l'utilisateur.
 */
object JsonParser {

    fun parse(text: String): Json {
        val p = P(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (!p.eof()) p.fail("texte inattendu apres la valeur JSON")
        return v
    }

    private class P(val s: String) {
        var i = 0

        fun eof() = i >= s.length
        fun fail(msg: String): Nothing = throw JsonException(msg, i)

        fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun value(): Json {
            if (eof()) fail("corps vide")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> array()
                '"' -> Json.Str(string())
                't' -> { lit("true"); Json.Bool(true) }
                'f' -> { lit("false"); Json.Bool(false) }
                'n' -> { lit("null"); Json.Null }
                else ->
                    if (c == '-' || c in '0'..'9') number()
                    else fail("caractere inattendu '$c'")
            }
        }

        fun lit(mot: String) {
            if (!s.startsWith(mot, i)) fail("litteral attendu : $mot")
            i += mot.length
        }

        fun obj(): Json {
            i++ // '{'
            val m = LinkedHashMap<String, Json>()
            skipWs()
            if (!eof() && s[i] == '}') { i++; return Json.Obj(m) }
            while (true) {
                skipWs()
                if (eof() || s[i] != '"') fail("nom de champ attendu")
                val k = string()
                skipWs()
                if (eof() || s[i] != ':') fail("':' attendu apres le nom de champ")
                i++
                skipWs()
                m[k] = value()
                skipWs()
                if (eof()) fail("objet JSON tronque")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return Json.Obj(m) }
                    else -> fail("',' ou '}' attendu")
                }
            }
        }

        fun array(): Json {
            i++ // '['
            val items = ArrayList<Json>()
            skipWs()
            if (!eof() && s[i] == ']') { i++; return Json.Arr(items) }
            while (true) {
                skipWs()
                items.add(value())
                skipWs()
                if (eof()) fail("tableau JSON tronque")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return Json.Arr(items) }
                    else -> fail("',' ou ']' attendu")
                }
            }
        }

        fun string(): String {
            i++ // '"' d'ouverture
            val sb = StringBuilder()
            while (true) {
                if (eof()) fail("chaine non terminee")
                when (val c = s[i]) {
                    '"' -> { i++; return sb.toString() }
                    '\\' -> {
                        i++
                        if (eof()) fail("echappement incomplet")
                        when (val e = s[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 >= s.length) fail("sequence \\u incomplete")
                                val hex = s.substring(i + 1, i + 5)
                                val cp = hex.toIntOrNull(16) ?: fail("sequence \\u invalide : $hex")
                                sb.append(cp.toChar())
                                i += 4
                            }
                            else -> fail("echappement inconnu '\\$e'")
                        }
                        i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
        }

        fun number(): Json {
            val start = i
            if (!eof() && s[i] == '-') i++
            while (!eof() && s[i] in '0'..'9') i++
            if (!eof() && s[i] == '.') {
                i++
                while (!eof() && s[i] in '0'..'9') i++
            }
            if (!eof() && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (!eof() && (s[i] == '+' || s[i] == '-')) i++
                while (!eof() && s[i] in '0'..'9') i++
            }
            if (i == start) fail("nombre attendu")
            val text = s.substring(start, i)
            // `toDouble` accepte « 1d », « 0x1p3 » et l'infini : on refuse tout
            // ce qui n'est pas strictement un nombre JSON.
            if (text.any { it !in "0123456789+-.eE" }) fail("nombre invalide : $text")
            return Json.Num(text)
        }
    }
}