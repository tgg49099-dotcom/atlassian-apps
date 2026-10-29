package com.slot5.mlexp

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Tiny read-only Optuna Dashboard client shared by the "Optuna" tab of the
 * tool window. v0.0.4. No third-party deps — just the JDK 17 [HttpClient] and
 * the hand-rolled [JsonParser].
 *
 * Endpoints used (the exact shape of the dashboard HTTP API; deviates
 * slightly from the cycle-8 brief which expected study_name in the URL —
 * in the real Optuna Dashboard the path uses the integer study_id, see
 * https://github.com/optuna/optuna-dashboard/blob/main/optuna_dashboard/_app.py):
 *
 *   GET /api/studies                                   -> {"study_summaries":[{study_id, study_name, directions, ...}]}
 *   GET /api/studies/{study_id}                        -> {name, directions, trials[], best_trials[], ...}
 *
 * For [listTrials] we use the detail endpoint and extract the `"trials":[...]`
 * array — the dashboard does not expose a separate paginated trials endpoint.
 * The MVP only ever needs the most recent ~50 trials of a study; that fits
 * in the detail response trivially.
 *
 * v0.0.13 — **the 9 regex extractors are gone.** They assumed a *compact* JSON
 * (`"study_id":1`) with a *fixed key order* (`"number":N,"state":"X"`). The
 * real Optuna Dashboard 5.0 answers with `json.dumps` defaults, i.e. a space
 * after the colon (`"study_id": 1`), and the key order is the dataclass order
 * (`trial_id`, `study_id`, `number`, `state`, `params`, …). Measured on real
 * bytes (`research/cycle41/reponses/optuna_api_studies*.json`, 13 218 o):
 * **8 of the 9 regexes matched nothing at all.** The tab showed "0 study".
 *
 * The parsing now goes through [JsonParser], the same strict parser as
 * [MlflowClient]: order-independent, whitespace-independent, and a non-JSON
 * body raises instead of silently producing an empty list.
 *
 * Threading: every method is blocking. Callers (tool window) invoke this on a
 * background thread, never on the EDT.
 */
class OptunaClient(private val baseUri: String) {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    /**
     * Hit `<base>/api/studies` and return the parsed summaries.
     *
     * Returns an empty list when the server has no studies. Throws
     * [OptunaException] on any non-2xx response, network error, or
     * malformed body.
     */
    fun listStudies(): List<StudySummary> {
        val root = parse(get("/api/studies"))
        return root.arr("study_summaries").mapNotNull { e ->
            val id = e.lng("study_id", -1L)
            if (id < 0) null
            else StudySummary(
                id = id,
                name = e.str("study_name"),
                direction = firstString(e, "directions").ifEmpty { "minimize" }
            )
        }
    }

    /**
     * Hit `<base>/api/studies/{study_id}` and return the parsed study +
     * its trials.
     *
     * For single-objective studies (the v0.0.4 MVP), `direction` is the first
     * entry of the `directions` array and `bestValue` is the first entry of
     * `best_trials[0].values`. Multi-objective studies are partially supported
     * — only the first objective is shown.
     */
    fun getStudy(studyId: Long): Study {
        val root = parse(get("/api/studies/$studyId"))
        val trials = parseTrials(root)
        val completeCount = trials.count { it.state.equals("Complete", ignoreCase = true) }
        return Study(
            name = root.str("name"),
            direction = firstString(root, "directions").ifEmpty { "minimize" },
            nTrials = trials.size,
            nComplete = completeCount,
            // The dashboard puts the study-level timestamps at the root of the
            // detail response when they exist; the old code hunted for the
            // LAST `"datetime_start"` after the `trials` array, which picked
            // up a *trial's* timestamp and showed it as the study's.
            datetimeStart = root.str("datetime_start"),
            datetimeComplete = root.str("datetime_complete"),
            bestValue = firstBestValue(root),
            trials = trials
        )
    }

    /**
     * List all trials for a single study. Convenience wrapper around
     * [getStudy] that returns just the `trials` field. Keeps the API surface
     * symmetric with the user's brief ("GET /api/studies/{...}/trials").
     */
    fun listTrials(studyId: Long): List<Trial> = getStudy(studyId).trials

    // ---- helpers ----------------------------------------------------------

    /**
     * Parse the `"trials":[...]` array into typed [Trial] objects.
     *
     * Each trial carries `number`, `state`, `values`, `params` (a list of
     * `{"name":…, "param_external_value":…}`), `datetime_start` and
     * `datetime_complete`. The value is the first entry of `values`; a trial
     * that has not produced one has an empty array.
     */
    private fun parseTrials(root: Json): List<Trial> =
        root.arr("trials").mapNotNull { t ->
            val number = t.lng("number", -1L)
            if (number < 0) null
            else Trial(
                number = number.toInt(),
                value = firstDouble(t, "values"),
                params = t.arr("params").mapNotNull { p ->
                    val n = p.str("name")
                    if (n.isEmpty()) null else n to p.str("param_external_value")
                }.toMap(),
                state = t.str("state"),
                datetimeStart = t.str("datetime_start"),
                datetimeComplete = t.str("datetime_complete")
            )
        }

    /**
     * Best value: the first entry of `best_trials[0].values`
     * (single-objective studies only).
     */
    private fun firstBestValue(root: Json): Double? =
        root.arr("best_trials").firstOrNull()?.let { firstDouble(it, "values") }

    /** First element of the array [key], when it is a string. */
    private fun firstString(j: Json, key: String): String =
        j.arr(key).firstOrNull()?.let { (it as? Json.Str)?.value } ?: ""

    /** First element of the array [key], when it is a number. */
    private fun firstDouble(j: Json, key: String): Double? =
        j.arr(key).firstOrNull()?.let { (it as? Json.Num)?.asDouble }

    /**
     * Parses a JSON body into a [Json] tree, or throws [OptunaException] when
     * the server returned anything else (an HTML error page served with
     * HTTP 200, a truncated body…). Silently returning an empty list would
     * show the user "0 study" for a server that answered.
     */
    private fun parse(body: String): Json = try {
        JsonParser.parse(body)
    } catch (e: JsonException) {
        throw OptunaException(
            "reponse non JSON du serveur : ${e.message}",
            status = 0,
            body = body.take(200),
            cause = e
        )
    }

    private fun get(path: String): String {
        val endpoint = baseUri.trimEnd('/') + path
        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json")
            .GET()
            .build()
        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            val status = response.statusCode()
            val body = response.body().orEmpty()
            if (status !in 200..299) {
                throw OptunaException(
                    "HTTP $status from $endpoint",
                    status = status,
                    body = body.take(200)
                )
            }
            body
        } catch (e: OptunaException) {
            throw e
        } catch (t: Throwable) {
            throw OptunaException(
                t.message ?: t.javaClass.simpleName,
                status = 0,
                body = "",
                cause = t
            )
        }
    }
}

/** A single study in the [OptunaClient.listStudies] response (v0.0.4). */
data class StudySummary(val id: Long, val name: String, val direction: String)

/** Full study record returned by [OptunaClient.getStudy] (v0.0.4). */
data class Study(
    val name: String,
    val direction: String,
    val nTrials: Int,
    val nComplete: Int,
    val datetimeStart: String,
    val datetimeComplete: String,
    val bestValue: Double?,
    val trials: List<Trial>
)

/** A single trial of a study. v0.0.4 single-objective MVP. */
data class Trial(
    val number: Int,
    val value: Double?,
    val params: Map<String, String>,
    val state: String,
    val datetimeStart: String,
    val datetimeComplete: String
)

/** Thrown by [OptunaClient] on any non-success outcome. */
class OptunaException(
    message: String,
    val status: Int,
    val body: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
