package com.slot5.mlexp

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Tiny read-only MLflow Tracking Server client shared by the "Connect" action
 * (v0.0.1) and the Tool Window (v0.0.2). No third-party deps — just the JDK 17
 * [HttpClient] and the hand-rolled [JsonParser].
 *
 * Why hand-roll instead of pulling `mlflow-client-java`: the published client
 * is heavy (~3 MB of transitive deps + Guava + Jackson), and the only methods
 * we currently need are `experiments/search`, `runs/search` and
 * `metrics/get-history`. Six ko of parsing wins on plugin size.
 *
 * v0.0.13 — two fixes, both proved against a real MLflow 3.17.2 server whose
 * response bytes are archived in `research/cycle41/reponses/`.
 *
 *  1. **Verbes HTTP.** `runs/get`, `registered-models/search`,
 *     `registered-models/get` and `model-versions/search` answer **HTTP 405**
 *     to a POST and **200** to a GET. They now use GET with the parameters in
 *     the query string. The other three keep their verb because it was
 *     *measured*, not assumed: `runs/search` answers **405 to a GET**, so
 *     switching it would break an endpoint that works.
 *
 *  2. **Zero regex on JSON.** The server emits *indented* JSON, so the 15
 *     `Regex` extractors that assumed compact output matched nothing at all —
 *     on MLflow 2.x or 3.x alike. Parsing is now done by [JsonParser].
 *
 * Threading: every method is blocking and synchronous. Callers (action / tool
 * window) are expected to invoke this on a background thread, never on the EDT.
 */
class MlflowClient(private val baseUri: String) {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    /**
     * Hit `<base>/api/2.0/mlflow/experiments/search` and return the parsed list.
     * Returns an empty list on HTTP 200 with `{"experiments": []}` (i.e. an
     * MLflow server with no experiments yet). Throws [MlflowException] on any
     * non-2xx response, network error, or malformed body.
     */
    fun listExperiments(): List<Experiment> {
        val body = post("/api/2.0/mlflow/experiments/search", """{"max_results":500}""")
        return parse(body).arr("experiments").mapNotNull { e ->
            val id = e.str("experiment_id")
            if (id.isEmpty()) null
            else Experiment(id = id, name = e.str("name"), lifecycleStage = e.str("lifecycle_stage"))
        }
    }

    /**
     * Hit `<base>/api/2.0/mlflow/runs/search` for a given experiment and return
     * the parsed runs.
     */
    fun listRuns(experimentId: String): List<Run> {
        val payload = """{"experiment_ids":["$experimentId"],"max_results":100}"""
        val body = post("/api/2.0/mlflow/runs/search", payload)
        return parse(body).arr("runs").mapNotNull { r ->
            val info = r.obj("info")
            // `run_id` is canonical; `run_uuid` is the older name, still emitted.
            val id = info.str("run_id").ifEmpty { info.str("run_uuid") }
            if (id.isEmpty()) null
            else Run(
                id = id,
                status = info.str("status"),
                startTimeMs = info.lng("start_time"),
                name = info.str("run_name")
            )
        }
    }

    /**
     * Returns the metrics stored on a single run as a `key -> last value` map.
     * Cheaper than fetching `metrics/get-history` for each metric key.
     */
    fun getRunMetrics(runId: String): Map<String, Double> {
        val data = runGet(runId)
        val out = LinkedHashMap<String, Double>()
        for (m in data.arr("metrics")) {
            val key = m.str("key")
            if (key.isEmpty()) continue
            // MLflow (protobuf JSON) writes NaN / Infinity as *strings*:
            // `m.dbl("value")` would silently turn them into 0.0.
            out[key] = m.num("value")?.asDouble ?: when (m.str("value")) {
                "NaN" -> Double.NaN
                "Infinity" -> Double.POSITIVE_INFINITY
                "-Infinity" -> Double.NEGATIVE_INFINITY
                else -> continue
            }
        }
        return out
    }

    /**
     * Returns the params stored on a single run as a `key -> string value` map.
     */
    fun getRunParams(runId: String): Map<String, String> {
        val data = runGet(runId)
        val out = LinkedHashMap<String, String>()
        for (p in data.arr("params")) {
            val key = p.str("key")
            if (key.isNotEmpty()) out[key] = p.str("value")
        }
        return out
    }

    /**
     * Fetch the full time-series of a single metric for a single run.
     *
     * MLflow endpoint: `GET /api/2.0/mlflow/metrics/get-history?run_id=...&metric_key=...&max_results=...`
     * Each point is a [MetricPoint] (step, value, timestamp).
     */
    fun getMetricHistory(runId: String, metricKey: String, maxResults: Int = 5000): List<MetricPoint> {
        val query = "run_id=" + enc(runId) +
                "&metric_key=" + enc(metricKey) +
                "&max_results=$maxResults"
        val body = get("/api/2.0/mlflow/metrics/get-history?$query")
        return parse(body).arr("metrics").mapNotNull { m ->
            val value = m.num("value")?.asDouble ?: return@mapNotNull null
            MetricPoint(step = m.lng("step"), value = value, timestampMs = m.lng("timestamp"))
        }
    }

    /**
     * Hit `<base>/api/2.0/mlflow/registered-models/search` and return the
     * parsed list. Model Registry browser (4th Tool Window tab).
     *
     * MLflow 2.0+ exposes this as a GA endpoint (no `/preview/` prefix).
     * Each registered model carries `latest_versions`, so we get a flat
     * version list without an extra round-trip.
     *
     * Returns an empty list on HTTP 200 with `{"registered_models": []}`.
     * Throws [MlflowException] on any non-2xx response or network error.
     */
    fun listRegisteredModels(): List<RegisteredModel> {
        val body = get("/api/2.0/mlflow/registered-models/search?max_results=500")
        return parse(body).arr("registered_models").map { m ->
            RegisteredModel(
                name = m.str("name"),
                description = m.str("description"),
                creationTimestampMs = m.lng("creation_timestamp"),
                latestVersions = m.arr("latest_versions").mapNotNull { parseVersion(it, m.str("name")) }
            )
        }
    }

    /**
     * Hit `<base>/api/2.0/mlflow/registered-models/get` and return a single
     * [RegisteredModel] with its `latest_versions`.
     */
    fun getRegisteredModel(name: String): RegisteredModel {
        val body = get("/api/2.0/mlflow/registered-models/get?name=" + enc(name))
        val rm = parse(body).obj("registered_model")
        val rmName = rm.str("name").ifEmpty { name }
        return RegisteredModel(
            name = rmName,
            description = rm.str("description"),
            creationTimestampMs = rm.lng("creation_timestamp"),
            latestVersions = rm.arr("latest_versions").mapNotNull { parseVersion(it, rmName) }
        )
    }

    /**
     * Hit `<base>/api/2.0/mlflow/model-versions/search` and return all
     * versions of a given registered model.
     *
     * Pass empty `name` to return all versions across all models.
     */
    fun listModelVersions(name: String): List<ModelVersion> {
        // The MLflow filter syntax is `name="<name>"`, percent-encoded here.
        val filter = if (name.isBlank()) "" else "filter=name%3D%22${enc(name)}%22&"
        val body = get("/api/2.0/mlflow/model-versions/search?${filter}max_results=500")
        return parse(body).arr("model_versions").mapNotNull { parseVersion(it, null) }
    }

    /**
     * `runs/get` as a GET (the POST is refused with HTTP 405), parameters in
     * the query string, then the `run.data` block. Shared by
     * [getRunMetrics] and [getRunParams].
     */
    private fun runGet(runId: String): Json {
        val body = get("/api/2.0/mlflow/runs/get?run_id=" + enc(runId))
        return parse(body).obj("run").obj("data")
    }

    /**
     * Converts a `model_version` object into a [ModelVersion]. Returns null if
     * the block has no `version` field — i.e. if it is not a version at all.
     * `defaultName` is used when the block omits `name`.
     */
    private fun parseVersion(v: Json, defaultName: String?): ModelVersion? {
        val version = v.str("version")
        if (version.isEmpty()) return null
        return ModelVersion(
            name = v.str("name").ifEmpty { defaultName ?: "" },
            version = version,
            currentStage = v.str("current_stage"),
            runId = v.str("run_id"),
            status = v.str("status"),
            creationTimestampMs = v.lng("creation_timestamp")
        )
    }

    /** Parses a JSON body into a [Json] tree, or throws [MlflowException]
     *  when the server returned anything else (an HTML error page served with
     *  HTTP 200, a truncated body…). Silently returning an empty list would
     *  show the user "Loaded 0 experiment(s)" for a server that answered. */
    private fun parse(body: String): Json = try {
        JsonParser.parse(body)
    } catch (e: JsonException) {
        throw MlflowException(
            "reponse non JSON du serveur : ${e.message}",
            status = 0,
            body = body.take(200),
            cause = e
        )
    }

    /** RFC 3986 percent-encoder: only « unreserved » characters pass through.
     *  `run_id` is hex and metric keys stay alphanumeric, but a registered
     *  model name can contain a slash or a space — hence a strict encoder
     *  rather than the previous hand-picked subset. */
    private fun enc(s: String): String {
        val sb = StringBuilder(s.length)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt().toChar()
            sb.append(
                when {
                    c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' -> c
                    c == '-' || c == '_' || c == '.' || c == '~' -> c
                    else -> "%%%02X".format(b.toInt() and 0xFF)
                }
            )
        }
        return sb.toString()
    }

    private fun get(pathWithQuery: String): String {
        val endpoint = baseUri.trimEnd('/') + pathWithQuery
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
                throw MlflowException(
                    "HTTP $status from $endpoint",
                    status = status,
                    body = body.take(200)
                )
            }
            body
        } catch (e: MlflowException) {
            throw e
        } catch (t: Throwable) {
            throw MlflowException(
                t.message ?: t.javaClass.simpleName,
                status = 0,
                body = "",
                cause = t
            )
        }
    }

    private fun post(path: String, jsonBody: String): String {
        val endpoint = baseUri.trimEnd('/') + path
        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build()
        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            val status = response.statusCode()
            val body = response.body().orEmpty()
            if (status !in 200..299) {
                throw MlflowException(
                    "HTTP $status from $endpoint",
                    status = status,
                    body = body.take(200)
                )
            }
            body
        } catch (e: MlflowException) {
            throw e
        } catch (t: Throwable) {
            throw MlflowException(
                t.message ?: t.javaClass.simpleName,
                status = 0,
                body = "",
                cause = t
            )
        }
    }
}

/** Plain data carriers — no plugin deps, no IntelliJ types. */
/**
 * One row of `experiments/search`.
 *
 * `lifecycleStage` ("active" / "deleted") and the run's `run_name` are read
 * since 0.0.15, purely so the tree can print a readable label. Both have a
 * default value, so every existing construction site keeps compiling and a
 * server that does not send them behaves exactly as before (empty string).
 */
data class Experiment(
    val id: String,
    val name: String,
    val lifecycleStage: String = ""
)
data class Run(
    val id: String,
    val status: String,
    val startTimeMs: Long,
    /** MLflow's own run name ("adamw-lr0.01-s7"); empty when not sent. */
    val name: String = ""
)

/**
 * One row of `registered-models/search`.
 *
 * `latestVersions` carries the (typically 1-3) versions the server surfaces as
 * the "latest in each stage". For a complete version list use
 * [MlflowClient.listModelVersions].
 */
data class RegisteredModel(
    val name: String,
    val description: String,
    val creationTimestampMs: Long,
    val latestVersions: List<ModelVersion>
)

/**
 * One row of `model-versions/search`.
 *
 * `currentStage` is typically `None`, `Staging`, `Production` or `Archived`.
 * `status` is typically `READY`, `PENDING_REGISTRATION` or
 * `FAILED_REGISTRATION`.
 */
data class ModelVersion(
    val name: String,
    val version: String,
    val currentStage: String,
    val runId: String,
    val status: String,
    val creationTimestampMs: Long
)

/** A single time-series point in a metric's history. */
data class MetricPoint(val step: Long, val value: Double, val timestampMs: Long)

/** Thrown by [MlflowClient] on any non-success outcome. */
class MlflowException(
    message: String,
    val status: Int,
    val body: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)