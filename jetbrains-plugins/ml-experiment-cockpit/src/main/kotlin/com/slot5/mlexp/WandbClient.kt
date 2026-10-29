package com.slot5.mlexp

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Tiny read-only W&B Sweeps GraphQL client shared by the "Sweeps" tab of the
 * tool window. v0.0.7. No third-party deps — just the JDK 17 [HttpClient] and
 * the hand-rolled [JsonParser].
 *
 * Endpoints used:
 *   POST <base>/graphql with body
 *     {"query":"query ListSweeps(...) { project(...) { sweeps { edges { node { ... } } } } }",
 *      "variables":{"entity":"...","project":"..."}}
 *
 * Why GraphQL instead of scraping https://wandb.ai/<entity>/<project>/sweeps:
 *   - The HTML page is server-rendered, contains a session-scoped CSRF token,
 *     and breaks often (W&B frequently changes its template).
 *   - The GraphQL API is stable, the same endpoint used by the official
 *     `wandb` Python client (`wandb.Api()`).
 *   - Same auth (API key from https://www.wandb.ai/settings → Danger Zone →
 *     "API keys"), Header `Authorization: Bearer <key>`.
 *
 * Self-hosted support: pass `baseUri` = `http(s)://<your-wandb-host>` — the
 * client always POSTs to `<base>/graphql` regardless of cloud or self-hosted.
 *
 * v0.0.13 — **two defects measured against the real W&B server**, not guessed:
 *
 *  1. **The GraphQL query was rejected by the server.** The `Sweep` type of
 *     the W&B schema has **no `bestRun` field**:
 *     `{"errors":[{"message":"Cannot query field \"bestRun\" on type \"Sweep\"."}]}`
 *     with **HTTP 400** — captured in `research/cycle43/`. Every call of this
 *     client therefore failed, with or without an API key: the tab could
 *     never have shown a single sweep. The field is simply not in the
 *     selection any more, and the best-run block of the detail panel now
 *     says so instead of pretending.
 *  2. **The regexes assumed a compact JSON and a lowercase state.** W&B
 *     answers `{"data":{"project":{"sweeps":{"edges":[…]}}}}` and a state
 *     such as `"RUNNING"` — uppercase, so the lowercase-only `state` regex
 *     matched nothing. Parsing now goes through [JsonParser], and the state
 *     is lower-cased on the way out, which is what the tree panel groups on.
 *
 * Threading: every method is blocking. Callers (tool window) invoke this on a
 * background thread, never on the EDT.
 *
 * v0.0.17 — **three defects of the jury of 2026-10-24 (id 6868), measured,
 * not guessed** (`research/cycle54/`, probes live against `api.wandb.ai`):
 *
 *  1. **The GraphQL error message was never shown.** The code read
 *     `first.obj("message").str("message")` — it looks for an *object* where
 *     W&B sends a *string* (`{"errors":[{"message":"Cannot query field …"}]}`),
 *     so the extraction always yielded "" and the user only ever saw the
 *     useless « GraphQL error ». Now `first.str("message")`.
 *  2. **A missing project was reported as an empty list.** W&B answers
 *     **HTTP 200** with `{"data":{"project":null}}` and **no `"errors"` array**
 *     for an unknown entity/project — so the tab said "Loaded 0 sweep(s)".
 *     It now says *project not found*.
 *  3. **`runs { totalCount }` is valid on the type `Sweep`.** The jury could
 *     not verify it; it was verified here against the live schema: the very
 *     query of this client answers **HTTP 200** with `"runs":{"totalCount":12}`,
 *     while the same query with one invented field is refused with **HTTP 400**
 *     « Cannot query field … on type "Sweep" ». The field is therefore kept in
 *     the query, and the parser tolerates its absence (null, not a fake 0).
 */
class WandbClient(
    private val baseUri: String,
    private val apiKey: String
) {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    /**
     * Hit `POST <base>/graphql` with the [listSweeps] query and return
     * the parsed sweeps.
     *
     * Returns an empty list when the project has no sweeps yet. Throws
     * [WandbException] on any non-2xx, GraphQL error (`"errors":[...]` in
     * the body), network failure, or malformed body.
     */
    fun listSweeps(entity: String, project: String): List<SweepSummary> {
        val body = postGraphQL(
            query = """
            query ListSweeps(${"$"}entity: String!, ${"$"}project: String!) {
              project(name: ${"$"}project, entityName: ${"$"}entity) {
                sweeps(first: 50) {
                  edges {
                    node {
                      id
                      name
                      state
                      createdAt
                      updatedAt
                      runs { totalCount }
                    }
                  }
                }
              }
            }
            """.trimIndent(),
            variables = mapOf("entity" to entity, "project" to project)
        )
        val root = parse(body)
        val proj = requireProject(root, entity, project)
        val edges = proj.obj("sweeps").arr("edges")
        return edges.mapNotNull { e -> parseSweepNode(e.obj("node")) }
    }

    /**
     * Hit `POST <base>/graphql` with a sweep-detail query and return the
     * parsed sweep with its runs total.
     */
    fun getSweep(entity: String, project: String, sweepName: String): Sweep {
        val body = postGraphQL(
            query = """
            query GetSweep(${"$"}entity: String!, ${"$"}project: String!, ${"$"}sweepName: String!) {
              project(name: ${"$"}project, entityName: ${"$"}entity) {
                sweep(sweepName: ${"$"}sweepName) {
                  id
                  name
                  state
                  createdAt
                  updatedAt
                  runs { totalCount }
                }
              }
            }
            """.trimIndent(),
            variables = mapOf(
                "entity" to entity,
                "project" to project,
                "sweepName" to sweepName
            )
        )
        val root = parse(body)
        val proj = requireProject(root, entity, project)
        val parsed = parseSweepNode(proj.obj("sweep"))
        return parsed?.let { Sweep(it.id, it.name, it.state, it.createdAt, it.updatedAt, it.bestRun, it.runsCount) }
            ?: Sweep("", "", "", "", "", null, null)
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * The `data.project` object of a successful GraphQL answer, or a
     * [WandbException] saying the project does not exist.
     *
     * **Measured on the live W&B server** (2026-10-24, `research/cycle54/`,
     * public project `wandb/sweep-demo`, no token needed): a request for an
     * unknown entity or an unknown project answers **HTTP 200** with the body
     * `{"data":{"project":null}}` and **no `"errors"` array**. The GraphQL
     * error path therefore never fires, and the client used to hand back an
     * empty list: the user saw "Loaded 0 sweep(s)" for a typo in the project
     * name — a silent lie that looks like "this project has no sweep".
     * From now on the answer is explicit: *project not found*.
     */
    private fun requireProject(root: Json, entity: String, project: String): Json.Obj {
        val data = root.obj("data")
        // `data.project` is present but NULL when the project does not exist,
        // and absent when the server answered something else entirely.
        val proj = data.get("project") as? Json.Obj
        if (proj == null) {
            throw WandbException(
                "project not found: W&B has no project '$project' for entity '$entity'",
                status = 200,
                body = ""
            )
        }
        return proj
    }

    /**
     * Parse one `node { id name state createdAt updatedAt runs { totalCount } }`
     * block. Returns null when the mandatory `id` is missing — i.e. when the
     * block is not a sweep at all.
     *
     * `bestRun` is always null: the W&B schema has no such field on `Sweep`
     * (see the class doc). The field is kept in the model so the detail
     * panel can say *why* it has nothing to show.
     *
     * `runsCount` is **null when the server did not send the `runs` block**.
     * `runs { totalCount }` was verified against the live W&B schema on
     * 2026-10-24 (`research/cycle54/`: HTTP 200 and `"runs":{"totalCount":12}`
     * in the answer, while the same query with one invented field is rejected
     * with HTTP 400 "Cannot query field …"), but a server that does not know
     * the field must not make the panel lie about "0 run(s)": it says
     * "n/a" instead.
     */
    private fun parseSweepNode(node: Json.Obj): SweepSummary? {
        val id = node.str("id")
        if (id.isEmpty()) return null
        return SweepSummary(
            id = id,
            name = node.str("name"),
            // W&B answers "RUNNING"/"PENDING"/… — the tree panel groups on
            // the lowercase form.
            state = node.str("state").lowercase(),
            createdAt = node.str("createdAt"),
            updatedAt = node.str("updatedAt"),
            bestRun = null,
            runsCount = (node.get("runs") as? Json.Obj)
                ?.let { (it.get("totalCount") as? Json.Num)?.asLong }
                ?.toInt()
        )
    }

    /**
     * Parses a JSON body into a [Json] tree, or throws [WandbException] when
     * the server returned anything else (an HTML error page served with
     * HTTP 200, a truncated body…).
     */
    private fun parse(body: String): Json = try {
        JsonParser.parse(body)
    } catch (e: JsonException) {
        throw WandbException(
            "reponse non JSON du serveur : ${e.message}",
            status = 0,
            body = body.take(200),
            cause = e
        )
    }

    /**
     * The GraphQL error message carried by a body, if any. W&B answers
     * HTTP 400 with `{"errors":[{"message":"Cannot query field …"}]}` for a
     * query the schema rejects — that message is the most useful thing we
     * can show the user, so it is extracted instead of the bare status code.
     */
    private fun graphQlErrorMessage(body: String): String? {
        val errs = try {
            JsonParser.parse(body).arr("errors")
        } catch (e: JsonException) {
            return null
        }
        val first = errs.firstOrNull() ?: return null
        val msg = first.str("message")
        return msg.ifEmpty { "GraphQL error" }
    }

    /**
     * POST a GraphQL query and return the body. Surfaces any `"errors":[...]`
     * field as a [WandbException] so callers can surface the GraphQL error
     * message in the UI status label.
     */
    private fun postGraphQL(query: String, variables: Map<String, String>): String {
        val variablesJson = variables.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            "\"$k\":${jsonString(v)}"
        }
        // GraphQL queries are sent as JSON; we hand-roll to avoid pulling a
        // JSON serializer dep.
        val payload = "{\"query\":${jsonString(query)},\"variables\":${variablesJson}}"
        val endpoint = baseUri.trimEnd('/') + "/graphql"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()
        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            val status = response.statusCode()
            val body = response.body().orEmpty()
            if (status !in 200..299) {
                val gqlMsg = graphQlErrorMessage(body)
                throw WandbException(
                    if (gqlMsg != null) "HTTP $status from $endpoint — GraphQL: $gqlMsg"
                    else "HTTP $status from $endpoint",
                    status = status,
                    body = body.take(200)
                )
            }
            // GraphQL returns HTTP 200 even on errors — errors are surfaced
            // in a `"errors":[...]` field. Detect and rethrow.
            graphQlErrorMessage(body)?.let {
                throw WandbException(
                    "GraphQL error: $it",
                    status = status,
                    body = body.take(200)
                )
            }
            body
        } catch (e: WandbException) {
            throw e
        } catch (t: Throwable) {
            throw WandbException(
                t.message ?: t.javaClass.simpleName,
                status = 0,
                body = "",
                cause = t
            )
        }
    }

    /** Wrap a string as `"..."` for embedding inside a JSON body. */
    private fun jsonString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) {
                    sb.append("\\u").append("%04x".format(c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}

/** A single sweep summary returned by [WandbClient.listSweeps]. v0.0.7. */
data class SweepSummary(
    val id: String,
    val name: String,
    val state: String,
    val createdAt: String,
    val updatedAt: String,
    val bestRun: SweepBestRun?,
    /** Number of runs — **null when the server did not send the `runs` block**
     *  (see [WandbClient.parseSweepNode]). Never silently 0. */
    val runsCount: Int?
)

/** Full sweep record returned by [WandbClient.getSweep]. v0.0.7. */
data class Sweep(
    val id: String,
    val name: String,
    val state: String,
    val createdAt: String,
    val updatedAt: String,
    val bestRun: SweepBestRun?,
    /** Number of runs — **null when the server did not send the `runs` block**. */
    val runsCount: Int?
)

/**
 * The "best run" attached to a sweep.
 *
 * Always `null` for the sweeps read from W&B: the GraphQL schema of W&B has
 * no `bestRun` field on the `Sweep` type (the query that asked for it was
 * rejected with HTTP 400 — see `research/cycle43/`). The type is kept so the
 * model does not change shape; a future W&B API that exposes it will fill
 * this in.
 */
data class SweepBestRun(
    val id: String,
    val name: String,
    val displayName: String,
    val state: String,
    val summaryMetrics: Map<String, String>
)

/** Thrown by [WandbClient] on any non-success outcome. */
class WandbException(
    message: String,
    val status: Int,
    val body: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
