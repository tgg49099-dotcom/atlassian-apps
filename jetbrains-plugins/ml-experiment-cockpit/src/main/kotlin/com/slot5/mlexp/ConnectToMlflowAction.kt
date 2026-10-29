package com.slot5.mlexp

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProcessCanceledException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * "Tools → MLflow → Connect to Tracking Server" action — the v0.0.1 MVP feature.
 *
 * Triggered from the menu (action id = "Slot5.Mlexp.Connect"). Behaviour:
 *
 * 1. Read the tracking URI from [MlflowSettings] (Settings → Tools → ML Experiment Cockpit).
 * 2. If empty / malformed → notification balloon: "Configure the tracking URI first."
 * 3. Else, send **one** HTTP POST to `<uri>/api/2.0/mlflow/experiments/search`
 *    with `{"max_results": 1}` to validate connectivity. MLflow responds with
 *    200 + JSON if reachable, 4xx/5xx if misconfigured.
 * 4. Notification balloon with the outcome (success + roundtrip, or error + reason).
 *
 * Why a single `experiments/search` call: it is the cheapest endpoint that
 * confirms the server is up AND speaks the v2.0 MLflow protocol. The run-list
 * endpoint requires at least one experiment_id and would fail with 400 even on
 * a healthy server.
 *
 * Threading: network I/O is synchronous but bounded to a 5 s read timeout.
 * For a v0.0.1 we accept this on the EDT to keep the code simple; v0.0.2 will
 * wrap the call in `Task.Backgroundable`.
 */
class ConnectToMlflowAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val rawUri = MlflowSettingsImpl.getInstance().getTrackingUri()

        // Validate URI early — anything from "not set" to "garbage" gets the same
        // friendly message. We intentionally do not echo the raw input back to
        // the balloon to avoid leaking typos into notification history.
        val base = parseBaseUri(rawUri)
        if (base == null) {
            showNotification(
                project,
                NotificationType.WARNING,
                title = "ML Experiment Cockpit",
                content = "Configure the MLflow tracking URI first " +
                        "(Settings → Tools → ML Experiment Cockpit). " +
                        "Expected format: http://localhost:5000"
            )
            return
        }

        // Run the probe on a worker, not the EDT. The action's updateThread is
        // BGT, but `actionPerformed` still runs on EDT by default — so we use
        // a small inline Runnable via `Thread` to keep v0.0.1 minimal. v0.0.2
        // will switch to `Task.Backgroundable` for proper progress UI.
        Thread({ probeAndNotify(project, base) }, "mlexp-probe").start()
    }

    override fun getActionUpdateThread(): ActionUpdateThread =
        ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        // The action is always enabled — even with no URI configured, clicking
        // it surfaces the "configure first" message. That's better UX than a
        // silently-greyed-out menu item.
        e.presentation.isEnabled = true
        e.presentation.isVisible = true
    }

    /**
     * Probe the tracking server and show the outcome. Extracted so the
     * background thread has a single, easy-to-test entry point.
     */
    private fun probeAndNotify(project: com.intellij.openapi.project.Project?, base: String) {
        val endpoint = "$base/api/2.0/mlflow/experiments/search"
        val started = System.currentTimeMillis()

        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build()

        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            // MLflow expects a JSON body. {max_results: 1} is the minimum valid
            // payload — it returns {"experiments": [...]} or an empty list.
            .POST(HttpRequest.BodyPublishers.ofString("{\"max_results\":1}"))
            .build()

        val result = try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            val elapsed = System.currentTimeMillis() - started
            val status = response.statusCode()
            val body = response.body().orEmpty()

            when {
                status in 200..299 -> {
                    val count = countTopLevelKeys(body, "experiment_id")
                    Outcome.Success(
                        status = status,
                        elapsedMs = elapsed,
                        experimentsFound = count
                    )
                }
                else -> Outcome.HttpError(
                    status = status,
                    elapsedMs = elapsed,
                    detail = body.take(200)
                )
            }
        } catch (pce: ProcessCanceledException) {
            throw pce
        } catch (t: Throwable) {
            // Catch-all because the network throws a different exception type
            // depending on the failure mode (ConnectException, SocketTimeout,
            // HttpTimeoutException, IllegalArgumentException for a malformed
            // URL after parseBaseUri accepted it, …).
            Outcome.NetworkError(
                elapsedMs = System.currentTimeMillis() - started,
                detail = (t.message ?: t.javaClass.simpleName).take(200)
            )
        }

        // Marshal back to EDT for the notification.
        showOutcome(project, result)
    }

    /**
     * Display the probe result. Always runs on EDT (called from the
     * background thread via `invokeLater`).
     */
    private fun showOutcome(
        project: com.intellij.openapi.project.Project?,
        outcome: Outcome
    ) {
        val (type, content) = when (outcome) {
            is Outcome.Success -> NotificationType.INFORMATION to
                    "Connected to MLflow in ${outcome.elapsedMs} ms (HTTP ${outcome.status}, " +
                    "${outcome.experimentsFound} experiment(s) visible to the API call)."
            is Outcome.HttpError -> NotificationType.ERROR to
                    "MLflow returned HTTP ${outcome.status} in ${outcome.elapsedMs} ms. " +
                    "Body preview: ${outcome.detail}"
            is Outcome.NetworkError -> NotificationType.ERROR to
                    "Could not reach the MLflow server (${outcome.elapsedMs} ms): " +
                    "${outcome.detail}"
        }
        showNotification(project, type, "ML Experiment Cockpit", content)
    }

    private fun showNotification(
        project: com.intellij.openapi.project.Project?,
        type: NotificationType,
        title: String,
        content: String
    ) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(title, content, type)
                .notify(project)
        }
    }

    /**
     * Parse the user-entered base URL. Returns null on:
     * - empty / blank string
     * - non-http(s) scheme (file:, ftp:, javascript:, …)
     * - malformed URI (no host, etc.)
     *
     * Strips trailing slashes so callers can safely append "/api/2.0/...".
     */
    private fun parseBaseUri(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        return try {
            val uri = URI.create(trimmed)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            if (uri.host.isNullOrBlank()) return null
            trimmed
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * Naive JSON counter used only for the success balloon — we don't import a
     * JSON library to keep dependencies tight. Counts occurrences of `"experiment_id":`
     * in the top-level experiments array (MLflow always emits this key).
     */
    private fun countTopLevelKeys(body: String, key: String): Int {
        // Cheap heuristic: count occurrences of "<key>": in the body. False
        // positives are theoretically possible but harmless — the balloon text
        // is informational only. Good enough for v0.0.1.
        return body.split("\"$key\":").size - 1
    }

    companion object {
        /** Must match the `id` of the `notificationGroup` extension in plugin.xml. */
        const val NOTIFICATION_GROUP_ID: String = "ML Experiment Cockpit Notifications"
    }

    /** Tagged-union of probe outcomes. Kept private to this file. */
    private sealed class Outcome {
        data class Success(val status: Int, val elapsedMs: Long, val experimentsFound: Int) : Outcome()
        data class HttpError(val status: Int, val elapsedMs: Long, val detail: String) : Outcome()
        data class NetworkError(val elapsedMs: Long, val detail: String) : Outcome()
    }
}