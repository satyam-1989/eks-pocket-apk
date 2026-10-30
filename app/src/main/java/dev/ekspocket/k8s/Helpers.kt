package dev.ekspocket.k8s

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.common.FlowStyle
import java.math.BigInteger
import java.time.Duration
import java.time.Instant

// ---------- JSON helpers ----------

fun JSONObject.meta(key: String): String = optJSONObject("metadata")?.optString(key).orEmpty()

fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

fun age(timestamp: String?): String {
    if (timestamp.isNullOrBlank()) return "-"
    val s = runCatching { Duration.between(Instant.parse(timestamp), Instant.now()).seconds }
        .getOrNull() ?: return "-"
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m"
        s < 86400 -> "${s / 3600}h"
        else -> "${s / 86400}d"
    }
}

data class PodSummary(
    val phase: String,
    val ready: String,
    val restarts: Int,
    /** e.g. "12m ago, OOMKilled", from the most recently terminated container. */
    val lastRestart: String? = null,
)

fun podSummary(p: JSONObject): PodSummary {
    val status = p.optJSONObject("status") ?: JSONObject()
    var phase = status.optString("phase", "Unknown")
    val total = p.optJSONObject("spec")?.optJSONArray("containers")?.length() ?: 0
    var ready = 0
    var restarts = 0
    var lastAt: Instant? = null
    var lastReason: String? = null
    for (c in status.optJSONArray("containerStatuses").objects()) {
        val prev = c.optJSONObject("lastState")?.optJSONObject("terminated")
        val finished = prev?.optString("finishedAt")?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (finished != null && (lastAt == null || finished.isAfter(lastAt))) {
            lastAt = finished
            lastReason = prev?.optString("reason")?.ifBlank { null } ?: "exit ${prev?.optInt("exitCode")}"
        }
        if (c.optBoolean("ready")) ready++
        restarts += c.optInt("restartCount")
        val state = c.optJSONObject("state")
        val waiting = state?.optJSONObject("waiting")?.optString("reason")
        if (!waiting.isNullOrBlank()) phase = waiting
        val terminated = state?.optJSONObject("terminated")?.optString("reason")
        if (!terminated.isNullOrBlank() && phase == "Running") phase = terminated
    }
    if (p.optJSONObject("metadata")?.has("deletionTimestamp") == true) phase = "Terminating"
    val last = lastAt?.let { "${age(it.toString())} ago, $lastReason" }
    return PodSummary(phase, "$ready/$total", restarts, last)
}

fun containerState(cs: JSONObject?): String {
    val st = cs?.optJSONObject("state") ?: return "Not started"
    st.optJSONObject("running")?.let { return "Running for ${age(it.optString("startedAt"))}" }
    st.optJSONObject("waiting")?.let { return "Waiting: ${it.optString("reason")}" }
    st.optJSONObject("terminated")?.let {
        return "Terminated: ${it.optString("reason")} (exit ${it.optInt("exitCode")})"
    }
    return "Unknown"
}

// ---------- YAML ----------

object YamlCodec {
    fun parseAll(text: String): List<JSONObject> {
        val load = Load(LoadSettings.builder().build())
        return load.loadAllFromString(text).filterNotNull().map {
            toJson(it) as? JSONObject
                ?: throw IllegalArgumentException("Each YAML document must be a mapping")
        }
    }

    private fun toJson(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, x) -> o.put(k.toString(), toJson(x)) } }
        is List<*> -> JSONArray().also { a -> v.forEach { a.put(toJson(it)) } }
        is BigInteger -> v.toLong()
        else -> v
    }

    private fun fromJson(v: Any?): Any? = when {
        v == null || v == JSONObject.NULL -> null
        v is JSONObject -> LinkedHashMap<String, Any?>().also { m ->
            v.keys().forEach { k -> m[k] = fromJson(v.get(k)) }
        }
        v is JSONArray -> (0 until v.length()).map { fromJson(v.get(it)) }
        else -> v
    }

    fun dump(obj: JSONObject): String {
        val settings = DumpSettings.builder()
            .setDefaultFlowStyle(FlowStyle.BLOCK)
            .setIndent(2)
            .setSplitLines(false)
            .build()
        return Dump(settings).dumpToString(fromJson(obj))
    }
}

// ---------- Terminal ----------

object Ansi {
    private val osc = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")
    private val csi = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
    private val other = Regex("\u001B[()][A-Za-z0-9]|\u001B[=>78MDEHc]")
    private val complete = Regex(
        "^(?:\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|\u001B\\[[0-?]*[ -/]*[@-~]|\u001B[()][A-Za-z0-9]|\u001B[=>78MDEHc])"
    )
    private val clearScreen = Regex("\u001B\\[[23]J")

    fun isComplete(s: String) = complete.containsMatchIn(s)

    /** Returns the index just after the last clear-screen sequence, or -1. */
    fun lastClear(s: String): Int = clearScreen.findAll(s).lastOrNull()?.range?.last?.plus(1) ?: -1

    fun strip(s: String): String =
        other.replace(csi.replace(osc.replace(s, ""), ""), "").replace("\u001B", "")
}

/** A simple line-oriented terminal: strips escape codes, handles CR, backspace and clear. */
class TerminalBuffer(private val maxChars: Int = 80_000) {
    private val sb = StringBuilder()
    private var pending = ""

    var text by mutableStateOf("")
        private set

    @Synchronized
    fun feed(chunk: String) {
        var s = pending + chunk
        pending = ""
        val lastEsc = s.lastIndexOf('\u001B')
        if (lastEsc >= 0 && s.length - lastEsc < 64 && !Ansi.isComplete(s.substring(lastEsc))) {
            pending = s.substring(lastEsc)
            s = s.substring(0, lastEsc)
        }
        val clearAt = Ansi.lastClear(s)
        if (clearAt >= 0) {
            sb.setLength(0)
            s = s.substring(clearAt)
        }
        val clean = Ansi.strip(s)
        var i = 0
        while (i < clean.length) {
            when (val c = clean[i]) {
                '\r' -> {
                    val crlf = i + 1 < clean.length && clean[i + 1] == '\n'
                    if (!crlf) {
                        // Carriage return: the line is about to be redrawn, so drop it.
                        sb.setLength(sb.lastIndexOf("\n") + 1)
                    }
                }
                '\b' -> if (sb.isNotEmpty() && sb[sb.length - 1] != '\n') sb.setLength(sb.length - 1)
                '\u0007', '\u0000' -> Unit
                else -> sb.append(c)
            }
            i++
        }
        if (sb.length > maxChars) sb.delete(0, sb.length - maxChars)
        text = sb.toString()
    }

    @Synchronized
    fun clear() {
        sb.setLength(0)
        text = ""
    }
}
