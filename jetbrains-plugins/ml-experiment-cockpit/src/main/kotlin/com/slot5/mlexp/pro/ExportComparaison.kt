package com.slot5.mlexp.pro

import com.slot5.mlexp.MlflowClient
import com.slot5.mlexp.MlflowException
import com.slot5.mlexp.Run
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * **Standalone HTML export of the comparison** — Pro tier 2.
 *
 * 2026.1.8 (consigne of the mother, 19/11/2026). Tier 1 ("Compare 2 runs")
 * ends with a **Markdown** copied to the clipboard: useful for a chat, useless
 * for a colleague who has no MLflow. Tier 2 produces **one self-contained
 * HTML file** that can be sent to anyone:
 *
 *   - the **2 comparison tables** (parameters, latest metric values with the
 *     absolute and the relative gaps);
 *   - the **2 metric curves** (A and B) as an **embedded PNG** (base64 inside
 *     the HTML — no external image to download);
 *   - **CSS integrated** in the file: the report opens offline, with no
 *     external resource of any kind.
 *
 * **The file never leaves the machine by itself**: the plugin writes it where
 * the user chose (a file chooser, no hard-coded path), and nothing is sent
 * anywhere — no request, no e-mail, no telemetry.
 *
 * **The export is a fresh reading.** The 2 runs are read again on the server
 * at the moment of the export: if a run has disappeared or has been renamed
 * since the comparison was computed, the export **fails cleanly** (a clear
 * message, no exception) and **no file is written** — a report that does not
 * match the server is worse than no report.
 *
 * This file is **pure** (no IntelliJ class): it is testable in a bare JVM.
 */
object ExportComparaison {

    /** Prefix of the file name: `ml-experiment-compare-<runA>-<runB>-<date>.html`. */
    const val PREFIXE_FICHIER = "ml-experiment-compare-"

    /** Extension of the report. */
    const val SUFFIXE_FICHIER = ".html"

    /** Date part of the file name (local time). */
    private val DATE_FICHIER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    /** Date and time shown in the report (local time). */
    private val DATE_RAPPORT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    // ------------------------------------------------------------------
    // The file name
    // ------------------------------------------------------------------

    /**
     * The default name of the report: `ml-experiment-compare-<runA>-<runB>-<date>.html`.
     *
     * The 2 run names are **sanitised**: only the ASCII letters, digits, `.`,
     * `_` and `-` survive; everything else becomes a `-`. A run name is free
     * text (it can hold `/`, `..`, spaces, accents): the name of the file
     * must never escape the folder the user chose.
     */
    fun nomFichier(runA: String, runB: String, dateMs: Long): String {
        val date = DATE_FICHIER.format(Instant.ofEpochMilli(dateMs).atZone(ZoneId.systemDefault()))
        return PREFIXE_FICHIER + assainir(runA) + "-" + assainir(runB) + "-" + date + SUFFIXE_FICHIER
    }

    /** Turns any free text into a safe file-name part (never empty, never `..`). */
    fun assainir(s: String): String {
        val sb = StringBuilder()
        for (c in s.trim()) {
            val ascii = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'
            sb.append(if (ascii || c == '.' || c == '_' || c == '-') c else '-')
        }
        var t = sb.toString()
            .replace(Regex("\\.{2,}"), ".")
            .replace(Regex("-{2,}"), "-")
            .trim('-', '_', '.')
        if (t.length > 40) t = t.substring(0, 40).trim('-', '_', '.')
        return if (t.isEmpty()) "run" else t
    }

    // ------------------------------------------------------------------
    // The export itself
    // ------------------------------------------------------------------

    /**
     * Exports the comparison of [runA] and [runB] as a standalone HTML file.
     *
     * Order of the checks (deliberate): **licence first** (nothing is read
     * and nothing is written without a Pro licence), then the existence and
     * the name of the 2 runs, then the fresh reading, then the file.
     *
     * @param client the MLflow client of the server being read.
     * @param runA the 1st run (as the comparison knows it).
     * @param runB the 2nd run (as the comparison knows it).
     * @param experimentId the experiment both runs belong to.
     * @param metrique the metric whose curve is drawn ("" = no metric).
     * @param fichier the file to write — **the folder is the user's choice**.
     * @param etat the state of the Pro gate, read at the moment of the export.
     * @param origine the URI of the server (same for the 2 runs).
     * @param maintenantMs the date written in the report.
     * @return [ResultatExport.Ecrit] or [ResultatExport.Echec] — **never an
     *         exception**: a failure is a message, and no file is left behind.
     */
    fun exporter(
        client: MlflowClient,
        runA: Run,
        runB: Run,
        experimentId: String,
        metrique: String,
        fichier: File,
        etat: ProState,
        origine: String = "",
        maintenantMs: Long = System.currentTimeMillis()
    ): ResultatExport {
        // 1. The gate comes first: without a Pro licence, nothing is read
        //    from the server and nothing is written on the disk.
        if (!etat.autorise()) {
            return ResultatExport.Echec(
                "Export is a Pro feature: an ML Experiment Cockpit Pro licence is required. " +
                    "No file was written."
            )
        }
        if (runA.id.isBlank() || runB.id.isBlank()) {
            return ResultatExport.Echec("One of the 2 runs has no id: no file was written.")
        }
        try {
            // 2. The 2 runs must still exist, under the same names.
            val runs = client.listRuns(experimentId)
            val aFrais = runs.firstOrNull { it.id == runA.id }
                ?: return ResultatExport.Echec(
                    "Run A (${runA.id}) no longer exists in the experiment: " +
                        "compare again. No file was written."
                )
            if (aFrais.name != runA.name) {
                return ResultatExport.Echec(
                    "Run A was renamed since the comparison was computed " +
                        "(\"${runA.name}\" -> \"${aFrais.name}\"): compare again. No file was written."
                )
            }
            val bFrais = runs.firstOrNull { it.id == runB.id }
                ?: return ResultatExport.Echec(
                    "Run B (${runB.id}) no longer exists in the experiment: " +
                        "compare again. No file was written."
                )
            if (bFrais.name != runB.name) {
                return ResultatExport.Echec(
                    "Run B was renamed since the comparison was computed " +
                        "(\"${runB.name}\" -> \"${bFrais.name}\"): compare again. No file was written."
                )
            }

            // 3. Fresh reading of the 2 runs (parameters, metrics, times).
            val detailsA = client.getRunDetails(runA.id)
            val detailsB = client.getRunDetails(runB.id)
            val refA = RunRef(
                id = detailsA.id,
                nom = runA.name,
                statut = detailsA.statut,
                startTimeMs = detailsA.startTimeMs,
                endTimeMs = detailsA.endTimeMs,
                origine = origine,
                params = detailsA.params,
                metriques = detailsA.metriques
            )
            val refB = RunRef(
                id = detailsB.id,
                nom = runB.name,
                statut = detailsB.statut,
                startTimeMs = detailsB.startTimeMs,
                endTimeMs = detailsB.endTimeMs,
                origine = origine,
                params = detailsB.params,
                metriques = detailsB.metriques
            )
            val resultat = ComparaisonRuns.calculer(listOf(refA, refB), etat)
            if (resultat is ResultatComparaison.Refus) {
                return ResultatExport.Echec(
                    "Export failed: ${resultat.message} No file was written."
                )
            }
            val comparaison = resultat as ResultatComparaison.Comparaison

            // 4. The curve of the chosen metric (fresh reading, same ceiling
            //    as the Compare tab).
            val pointsA = if (metrique.isNotBlank()) {
                client.getMetricHistoryAll(runA.id, metrique, PanneauComparaison.PLAFOND_POINTS_PRO).points
            } else {
                emptyList()
            }
            val pointsB = if (metrique.isNotBlank()) {
                client.getMetricHistoryAll(runB.id, metrique, PanneauComparaison.PLAFOND_POINTS_PRO).points
            } else {
                emptyList()
            }

            // 5. The report: 1 file, CSS integrated, PNG embedded.
            val png = RenduCourbePng.base64(pointsA, pointsB, titreCourbe(metrique))
            val html = html(comparaison, png, metrique, maintenantMs)

            // 6. The file, in the folder the user chose.
            return ecrire(fichier, html)
        } catch (e: MlflowException) {
            val raison = if (e.status == 404) {
                "the run no longer exists on the server"
            } else {
                e.message ?: "the server refused the request"
            }
            return ResultatExport.Echec("Export failed: $raison. No file was written.")
        } catch (t: Throwable) {
            return ResultatExport.Echec(
                "Export failed: ${t.message ?: t.javaClass.simpleName}. No file was written."
            )
        }
    }

    /** The title of the curve: the metric, or a clear "no metric" marker. */
    fun titreCourbe(metrique: String): String =
        if (metrique.isBlank()) "(no metric)" else metrique

    // ------------------------------------------------------------------
    // Writing the file
    // ------------------------------------------------------------------

    /**
     * Writes the report in [fichier].
     *
     * The file is written **in a temporary file of the same folder**, then
     * moved: a failure therefore leaves **no half-written report** behind.
     *
     * The final file has the rights **644** (readable by everybody, writable
     * by its owner): the report is meant to be **shared**, and a colleague
     * who has no write access on the folder must still be able to open it.
     */
    fun ecrire(fichier: File, html: String): ResultatExport {
        val absolut = fichier.absoluteFile
        val dossier = absolut.parentFile
        if (dossier == null || !dossier.isDirectory) {
            return ResultatExport.Echec(
                "The folder does not exist (${dossier?.path ?: absolut.path}). No file was written."
            )
        }
        var tmp: java.nio.file.Path? = null
        return try {
            tmp = Files.createTempFile(dossier.toPath(), ".mlexp-export-", ".tmp")
            Files.write(tmp, html.toByteArray(StandardCharsets.UTF_8))
            appliquerDroits644(tmp)
            Files.move(tmp, absolut.toPath(), StandardCopyOption.REPLACE_EXISTING)
            tmp = null
            ResultatExport.Ecrit(absolut.path, absolut.length())
        } catch (t: Throwable) {
            ResultatExport.Echec(
                "Could not write the file ${absolut.path}: " +
                    "${t.message ?: t.javaClass.simpleName}. No file was written."
            )
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp)
                } catch (t: Throwable) {
                    // The temporary file stays: the message of the failure above
                    // is more useful than a second one.
                }
            }
        }
    }

    /**
     * Gives the file the rights **644** (`rw-r--r--`).
     *
     * `Files.createTempFile` creates the temporary file with the rights
     * **600** (owner only) on a POSIX file system, and a move keeps them:
     * the report would then be unreadable by a colleague who shares the
     * folder. The rights are therefore set **before** the move, on the
     * temporary file itself.
     *
     * On a file system that has no POSIX rights (Windows), the call is
     * simply skipped: the report is still written, with the rights the
     * system gives it.
     */
    private fun appliquerDroits644(fichier: java.nio.file.Path) {
        try {
            Files.setPosixFilePermissions(
                fichier,
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--")
            )
        } catch (t: Throwable) {
            // A file system without POSIX rights (Windows): nothing to change.
        }
    }

    // ------------------------------------------------------------------
    // The report
    // ------------------------------------------------------------------

    /**
     * The standalone report: **1 file, no external resource**.
     *
     * The HTML holds the 2 tables and the PNG of the curve. The CSS is
     * integrated in a `<style>` block, and the image is a `data:` URI: the
     * file opens offline, in any browser, on any machine.
     */
    fun html(
        c: ResultatComparaison.Comparaison,
        pngBase64: String?,
        metrique: String,
        genereLeMs: Long
    ): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html>\n")
        sb.append("<html lang=\"en\">\n<head>\n")
        sb.append("<meta charset=\"utf-8\">\n")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        sb.append("<title>ML Experiment Cockpit - comparison of 2 runs</title>\n")
        sb.append("<style>\n").append(CSS).append("\n</style>\n")
        sb.append("</head>\n<body>\n")

        sb.append("<h1>Comparison of 2 runs</h1>\n")
        sb.append("<p class=\"meta\">ML Experiment Cockpit Pro - generated on ")
            .append(echapper(formatInstant(genereLeMs))).append(" (local time).</p>\n")

        // ---- The 2 runs ----
        sb.append("<table class=\"runs\">\n")
        sb.append("<tr><th>Run</th><th>A</th><th>B</th></tr>\n")
        sb.append(ligneRuns("name", c.a.libelle(), c.b.libelle()))
        sb.append(ligneRuns("run id", c.a.id, c.b.id))
        sb.append(ligneRuns("status", c.a.statut, c.b.statut))
        sb.append(ligneRuns("start", formatInstant(c.a.startTimeMs), formatInstant(c.b.startTimeMs)))
        sb.append(ligneRuns("duration", c.dureeA, c.dureeB))
        sb.append("</table>\n")

        // ---- The parameters ----
        sb.append("<h2>Parameters</h2>\n")
        sb.append("<table class=\"params\">\n")
        sb.append("<tr><th>parameter</th><th>A</th><th>B</th><th>state</th></tr>\n")
        for (l in c.params) {
            sb.append("<tr><td>").append(echapper(l.cle)).append("</td>")
                .append("<td class=\"num\">").append(echapper(l.valeurA ?: "-")).append("</td>")
                .append("<td class=\"num\">").append(echapper(l.valeurB ?: "-")).append("</td>")
                .append("<td>").append(echapper(l.etat.libelle)).append("</td></tr>\n")
        }
        sb.append("</table>\n")

        // ---- The metrics ----
        sb.append("<h2>Metrics</h2>\n")
        sb.append("<table class=\"metrics\">\n")
        sb.append(
            "<tr><th>metric</th><th>A</th><th>B</th>" +
                "<th>absolute gap</th><th>relative gap</th><th>state</th></tr>\n"
        )
        for (l in c.metriques) {
            sb.append("<tr><td>").append(echapper(l.cle)).append("</td>")
                .append("<td class=\"num\">").append(echapper(nombre(l.valeurA))).append("</td>")
                .append("<td class=\"num\">").append(echapper(nombre(l.valeurB))).append("</td>")
                .append("<td class=\"num\">").append(echapper(nombre(l.ecartAbsolu))).append("</td>")
                .append("<td class=\"num\">").append(echapper(ecartRelatif(l.ecartRelatifPct))).append("</td>")
                .append("<td>").append(echapper(l.etat.libelle)).append("</td></tr>\n")
        }
        sb.append("</table>\n")

        // ---- The curve ----
        sb.append("<h2>Metric curve</h2>\n")
        if (pngBase64.isNullOrEmpty()) {
            sb.append("<p class=\"note\">The curve could not be drawn.</p>\n")
        } else {
            sb.append("<p class=\"note\">")
                .append(echapper(if (metrique.isBlank()) "No metric selected." else "Metric: $metrique"))
                .append(" - A in blue, B in orange, both on the same scale.</p>\n")
            sb.append("<img alt=\"Metric curve of the 2 runs\" src=\"data:image/png;base64,")
                .append(pngBase64).append("\">\n")
        }

        sb.append("<p class=\"note\">This report is self-contained: it loads no external resource " +
            "and can be read offline. It was generated locally by ML Experiment Cockpit Pro " +
            "from the MLflow server the user configured; nothing was sent anywhere.</p>\n")
        sb.append("</body>\n</html>\n")
        return sb.toString()
    }

    /** One row of the "runs" table. */
    private fun ligneRuns(libelle: String, a: String, b: String): String =
        "<tr><td>" + echapper(libelle) + "</td>" +
            "<td class=\"num\">" + echapper(a) + "</td>" +
            "<td class=\"num\">" + echapper(b) + "</td></tr>\n"

    /**
     * A number for the report: `-` when the value is missing **or not a
     * number**. A `NaN` in the report would be a lie (the value is not a
     * number, it is an absent value).
     */
    fun nombre(v: Double?): String {
        if (v == null || v.isNaN()) return "-"
        return ComparaisonRuns.formatNombre(v) ?: "-"
    }

    /** A relative gap for the report: `-` when it is missing or not a number. */
    fun ecartRelatif(v: Double?): String {
        if (v == null || v.isNaN()) return "-"
        return ComparaisonRuns.formatEcartRelatif(v) ?: "-"
    }

    /** An instant in the local time, or `-` when it is unknown. */
    fun formatInstant(ms: Long): String {
        if (ms <= 0L) return "-"
        return DATE_RAPPORT.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
    }

    /**
     * Escapes the text that comes from the server (a parameter value, a run
     * name...), so that it can never be read as HTML.
     *
     * The scheme of a URL is **neutralised** (`https&#58;//`): the file then
     * contains **no `http://` and no `https://` at all**, which is what makes
     * the "the report loads nothing from the network" property **checkable**
     * with a simple `grep`. The browser still displays the text as it is.
     *
     * The scheme is neutralised **whatever its case**: a URL scheme is
     * case-insensitive (RFC 3986), so `HTTPS://` is a URL exactly like
     * `https://`. The proof of the report (proof 08) counts the schemes
     * **without regard to the case**: if only the lower case were
     * neutralised, an upper-case scheme would survive the escape **and**
     * escape the count — a false green.
     */
    fun echapper(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(c)
            }
        }
        return SCHEME_URL.replace(sb.toString()) { m -> m.value.replace("://", "&#58;//") }
    }

    /**
     * The scheme of a URL, **in any case** (`HTTP://`, `Https://`...).
     */
    private val SCHEME_URL = Regex("https?://", RegexOption.IGNORE_CASE)

    /** The CSS of the report: integrated in the file, nothing external. */
    private val CSS = """
        body { font-family: -apple-system, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
               margin: 24px; color: #1f2328; background: #ffffff; }
        h1 { font-size: 20px; margin: 0 0 4px 0; }
        h2 { font-size: 15px; margin: 24px 0 8px 0; }
        p.meta { color: #57606a; font-size: 12px; margin: 0 0 18px 0; }
        p.note { color: #57606a; font-size: 12px; margin: 6px 0 18px 0; }
        table { border-collapse: collapse; font-size: 13px; width: 100%; margin-bottom: 10px; }
        th, td { border: 1px solid #d0d7de; padding: 5px 8px; text-align: left; vertical-align: top; }
        th { background: #f6f8fa; font-weight: 600; }
        td.num { text-align: right; font-variant-numeric: tabular-nums; }
        tr:nth-child(even) td { background: #fbfcfd; }
        img { max-width: 100%; height: auto; border: 1px solid #d0d7de; }
    """.trimIndent()
}

/** The outcome of an export: the file written, or the precise reason why not. */
sealed class ResultatExport {

    /** The report was written. */
    data class Ecrit(val chemin: String, val octets: Long) : ResultatExport()

    /** The export failed: [message] says why, and **no file was written**. */
    data class Echec(val message: String) : ResultatExport()
}
