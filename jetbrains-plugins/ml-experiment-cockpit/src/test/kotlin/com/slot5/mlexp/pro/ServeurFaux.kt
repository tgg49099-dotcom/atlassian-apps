package com.slot5.mlexp.pro

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * A fake MLflow server: it answers the 3 endpoints the export reads
 * (`runs/search`, `runs/get`, `metrics/get-history`) with **real bytes**.
 *
 * Its state can be changed between the comparison and the export: a run
 * can disappear ([ids]), be renamed ([noms]) or answer 404
 * ([runIntrouvable]).
 *
 * **Top level** (cycle 245): shared by [ExportComparaisonTest] and by
 * [PreuveRapportHorsLigneTest], which writes the real report on the disk.
 */
class ServeurFaux : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    /** The ids the server still knows. */
    val ids: MutableSet<String> = mutableSetOf("a1", "b1")

    /** The name the server answers for each run. */
    val noms: MutableMap<String, String> = mutableMapOf("a1" to "run-a", "b1" to "run-b")

    /** The metrics of each run. */
    val metriques: MutableMap<String, Map<String, Double>> = mutableMapOf(
        "a1" to mapOf("loss" to 0.5, "acc" to 0.9),
        "b1" to mapOf("acc" to 0.8)
    )

    /** The parameters of each run. */
    val params: MutableMap<String, Map<String, String>> = mutableMapOf(
        "a1" to mapOf("lr" to "0.01"),
        "b1" to mapOf("lr" to "0.02")
    )

    /** When set, `runs/get` answers 404 for this run. */
    var runIntrouvable: String? = null

    /** The number of requests received (the export must not read at all without a licence). */
    var nbRequetes: Int = 0
        private set

    val base: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            nbRequetes++
            val path = exchange.requestURI.path
            val query = exchange.requestURI.query ?: ""
            val args = query.split("&").associate {
                val kv = it.split("=", limit = 2)
                kv[0] to java.net.URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8")
            }
            var code = 200
            var body: String
            when {
                path.endsWith("/runs/search") -> {
                    val runs = ids.joinToString(",") { id ->
                        """{"info":{"run_id":"$id","status":"FINISHED","start_time":1700000000000,"end_time":1700000060000,"run_name":"${noms[id] ?: id}"}}"""
                    }
                    body = """{"runs":[$runs]}"""
                }
                path.endsWith("/runs/get") -> {
                    val id = args["run_id"] ?: ""
                    if (runIntrouvable == id) {
                        code = 404
                        body = """{"error_code":"RESOURCE_DOES_NOT_EXIST","message":"Run $id not found"}"""
                    } else {
                        val ps = params[id].orEmpty().entries.joinToString(",") {
                            """{"key":"${it.key}","value":"${it.value}"}"""
                        }
                        val ms = metriques[id].orEmpty().entries.joinToString(",") {
                            """{"key":"${it.key}","value":${it.value}}"""
                        }
                        body = """{"run":{"info":{"run_id":"$id","status":"FINISHED",""" +
                            """"start_time":1700000000000,"end_time":1700000060000},""" +
                            """"data":{"params":[$ps],"metrics":[$ms]}}}"""
                    }
                }
                path.endsWith("/metrics/get-history") -> {
                    val id = args["run_id"] ?: ""
                    val cle = args["metric_key"] ?: ""
                    val points = (0 until 5).joinToString(",") { i ->
                        val v = metriques[id]?.get(cle) ?: 0.0
                        """{"key":"$cle","value":${v + i / 100.0},"timestamp":${1700000000000L + i},"step":$i}"""
                    }
                    body = """{"metrics":[$points]}"""
                }
                else -> {
                    code = 404
                    body = """{"error_code":"ENDPOINT_NOT_FOUND"}"""
                }
            }
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    override fun close() {
        server.stop(0)
    }
}
