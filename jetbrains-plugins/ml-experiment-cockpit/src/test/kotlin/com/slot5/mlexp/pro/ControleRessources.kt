package com.slot5.mlexp.pro

/**
 * **THE CONTROL OF THE RESOURCES OF THE EXPORTED REPORT** — proof 08
 * (cycle 245), corrected in cycle 246 (consigne of the mother, 19/11/2026).
 *
 * The report must open **offline**: it must load **nothing** from the
 * network. This control is what makes that property **checkable**:
 *
 *   1. it counts the **schemes** of the report (`http://`, `https://`) —
 *      **without regard to the case**. A URL scheme is case-insensitive
 *      (RFC 3986): `HTTPS://` is a URL exactly like `https://`. The first
 *      version of the control counted the **lower case only**, and the
 *      escape of the text neutralised the **lower case only**: an
 *      upper-case scheme therefore passed **both** — a **false green**.
 *   2. it lists the `src` and the `href` of the report and requires
 *      **every one of them to start with `data:`** — the only resource a
 *      self-contained report may hold.
 *
 * A control that is never shown to **fail** is not a control: the hostile
 * texts that it must catch are in `ControleRessourcesTest`.
 */
object ControleRessources {

    /** The scheme of a URL, in **any** case. */
    private val SCHEME = Regex("""(?i)\bhttps?://""")

    /** The `src` and the `href` of the report (double or simple quotes). */
    private val ATTRIBUT = Regex("""(?i)\b(?:src|href)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

    /** The schemes found in the report: **0** is the expected result. */
    fun schemesExternes(html: String): List<String> =
        SCHEME.findAll(html).map { it.value }.toList()

    /** The `src` and the `href` of the report. */
    fun ressources(html: String): List<String> =
        ATTRIBUT.findAll(html).map {
            if (it.groupValues[1].isNotEmpty()) it.groupValues[1] else it.groupValues[2]
        }.toList()

    /**
     * The resources that are **not** a `data:` URI: they would make the
     * report load something from the network. **0** is the expected result.
     */
    fun ressourcesNonData(html: String): List<String> =
        ressources(html).filter { !it.startsWith("data:", ignoreCase = true) }

    /** The report is self-contained: no scheme, and every resource is a `data:`. */
    fun estAutonome(html: String): Boolean =
        schemesExternes(html).isEmpty() && ressourcesNonData(html).isEmpty()
}
