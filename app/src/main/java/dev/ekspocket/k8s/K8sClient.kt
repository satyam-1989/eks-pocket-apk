package dev.ekspocket.k8s

import dev.ekspocket.aws.EksCluster
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.time.Instant
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class K8sException(val code: Int, message: String) : Exception(message)

class K8sClient(
    val cluster: EksCluster,
    private val tokenProvider: () -> String,
) {
    private val base = cluster.endpoint.trimEnd('/')
    private var token: String? = null
    private var tokenAt = 0L
    private val discovery = ConcurrentHashMap<String, JSONArray>()

    private val http: OkHttpClient
    private val streamHttp: OkHttpClient

    init {
        val tm = trustManagerFor(cluster.caData)
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        http = OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, tm)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer ${bearer()}")
                        .build()
                )
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        streamHttp = http.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    /** EKS tokens live ~15 minutes; regenerate every 10. */
    @Synchronized
    private fun bearer(): String {
        val now = System.currentTimeMillis()
        if (token == null || now - tokenAt > 10 * 60 * 1000) {
            token = tokenProvider()
            tokenAt = now
        }
        return token!!
    }

    private fun trustManagerFor(caData: String): X509TrustManager {
        val pem = Base64.getMimeDecoder().decode(caData.trim())
        val certs = CertificateFactory.getInstance("X.509")
            .generateCertificates(ByteArrayInputStream(pem))
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            certs.forEachIndexed { i, c -> setCertificateEntry("ca$i", c) }
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun statusMessage(code: Int, text: String): String {
        val msg = runCatching { JSONObject(text).optString("message") }.getOrNull()
            .orEmpty().ifBlank { text.take(300) }
        return when (code) {
            401 -> "The cluster rejected your credentials. Give this IAM identity an EKS access entry (or an aws-auth mapping) on the cluster."
            403 -> "Not allowed: $msg"
            else -> "Error $code: $msg"
        }
    }

    fun request(
        method: String,
        path: String,
        body: String? = null,
        contentType: String = "application/json",
    ): String {
        val rb = body?.toRequestBody(contentType.toMediaType())
        val req = Request.Builder()
            .url(base + path)
            .method(method, rb)
            .header("Accept", "application/json")
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw K8sException(resp.code, statusMessage(resp.code, text))
            return text
        }
    }

    fun getJson(path: String): JSONObject = JSONObject(request("GET", path))

    fun list(path: String): List<JSONObject> = getJson(path).optJSONArray("items").objects()

    private fun nsPath(ns: String?) = if (ns == null) "" else "/namespaces/$ns"

    fun namespaces(): List<String> = list("/api/v1/namespaces").map { it.meta("name") }.sorted()
    fun pods(ns: String?) = list("/api/v1${nsPath(ns)}/pods")
    fun deployments(ns: String?) = list("/apis/apps/v1${nsPath(ns)}/deployments")
    fun services(ns: String?) = list("/api/v1${nsPath(ns)}/services")
    fun nodes() = list("/api/v1/nodes")

    fun delete(path: String) {
        request("DELETE", path)
    }

    fun scale(ns: String, name: String, replicas: Int) {
        val body = JSONObject().put("spec", JSONObject().put("replicas", replicas))
        request("PATCH", "/apis/apps/v1/namespaces/$ns/deployments/$name/scale", body.toString(), MERGE)
    }

    fun restartDeployment(ns: String, name: String) {
        val annotations = JSONObject().put("kubectl.kubernetes.io/restartedAt", Instant.now().toString())
        val body = JSONObject().put(
            "spec", JSONObject().put(
                "template", JSONObject().put("metadata", JSONObject().put("annotations", annotations))
            )
        )
        request("PATCH", "/apis/apps/v1/namespaces/$ns/deployments/$name", body.toString(), MERGE)
    }

    /** Scale any workload that has a /scale subresource. */
    fun scaleAt(itemPath: String, replicas: Int) {
        val body = JSONObject().put("spec", JSONObject().put("replicas", replicas))
        request("PATCH", "$itemPath/scale", body.toString(), MERGE)
    }

    /** Rolling restart (what kubectl rollout restart does) for any pod-template workload. */
    fun restartAt(itemPath: String) {
        val annotations = JSONObject().put("kubectl.kubernetes.io/restartedAt", Instant.now().toString())
        val body = JSONObject().put(
            "spec", JSONObject().put(
                "template", JSONObject().put("metadata", JSONObject().put("annotations", annotations))
            )
        )
        request("PATCH", itemPath, body.toString(), MERGE)
    }

    fun setUnschedulable(node: String, value: Boolean) {
        val body = JSONObject().put("spec", JSONObject().put("unschedulable", value))
        request("PATCH", "/api/v1/nodes/$node", body.toString(), MERGE)
    }

    // ---- Discovery and YAML apply ----

    private fun resources(groupPath: String): JSONArray =
        discovery[groupPath] ?: getJson(groupPath).getJSONArray("resources").also { discovery[groupPath] = it }

    /** Every listable resource type the cluster serves, including CRDs (preferred versions only). */
    fun discoverKinds(): List<Kind> {
        val groupVersions = mutableListOf<String>()
        getJson("/api").optJSONArray("versions")?.let { a ->
            for (i in 0 until a.length()) groupVersions += a.getString(i)
        }
        getJson("/apis").optJSONArray("groups").objects().forEach { g ->
            g.optJSONObject("preferredVersion")?.optString("groupVersion")
                ?.takeIf { it.isNotBlank() }
                ?.let { groupVersions += it }
        }
        val pool = Executors.newFixedThreadPool(6)
        try {
            val futures = groupVersions.map { gv ->
                pool.submit(Callable { runCatching { kindsIn(gv) }.getOrDefault(emptyList()) })
            }
            return futures.flatMap { it.get() }
                .distinctBy { "${it.apiPath}/${it.plural}" }
                .sortedWith(compareBy<Kind> { it.group != "core" }.thenBy { it.group }.thenBy { it.kind })
        } finally {
            pool.shutdown()
        }
    }

    private fun kindsIn(groupVersion: String): List<Kind> {
        val apiPath = if (groupVersion.contains('/')) "/apis/$groupVersion" else "/api/$groupVersion"
        return resources(apiPath).objects()
            .filter { r ->
                val verbs = r.optJSONArray("verbs")
                !r.optString("name").contains('/') &&
                    verbs != null && (0 until verbs.length()).any { verbs.optString(it) == "list" }
            }
            .map { r ->
                Kind(
                    kind = r.optString("kind"),
                    plural = r.getString("name"),
                    apiPath = apiPath,
                    namespaced = r.optBoolean("namespaced"),
                )
            }
    }

    fun pathFor(apiVersion: String, kind: String, namespace: String?, name: String?): String {
        val groupPath = if (apiVersion.contains('/')) "/apis/$apiVersion" else "/api/$apiVersion"
        val res = resources(groupPath)
        var plural: String? = null
        var namespaced = false
        for (i in 0 until res.length()) {
            val r = res.getJSONObject(i)
            val n = r.getString("name")
            if (r.optString("kind") == kind && !n.contains('/')) {
                plural = n
                namespaced = r.optBoolean("namespaced")
                break
            }
        }
        if (plural == null) throw K8sException(404, "The cluster doesn't know kind $kind in $apiVersion")
        return buildString {
            append(groupPath)
            if (namespaced) append("/namespaces/").append(namespace ?: "default")
            append('/').append(plural)
            if (name != null) append('/').append(name)
        }
    }

    /** Server-side apply for every document in [yaml]. Creates or updates. */
    fun applyYaml(yaml: String, defaultNamespace: String): List<String> {
        val docs = YamlCodec.parseAll(yaml)
        if (docs.isEmpty()) throw IllegalArgumentException("No YAML documents found")
        return docs.map { obj ->
            val apiVersion = obj.optString("apiVersion")
            val kind = obj.optString("kind")
            if (apiVersion.isBlank() || kind.isBlank()) {
                throw IllegalArgumentException("Each document needs apiVersion and kind")
            }
            val meta = obj.optJSONObject("metadata")
                ?: throw IllegalArgumentException("$kind is missing metadata")
            val name = meta.optString("name")
            if (name.isBlank()) throw IllegalArgumentException("$kind is missing metadata.name")
            meta.remove("managedFields")
            val ns = meta.optString("namespace").ifBlank { defaultNamespace }
            val path = pathFor(apiVersion, kind, ns, name)
            request(
                "PATCH", "$path?fieldManager=ekspocket&force=true",
                obj.toString(), "application/apply-patch+yaml",
            )
            "$kind/$name applied"
        }
    }

    /** Full replace (like kubectl replace) of the object at [path]. */
    fun replace(path: String, yaml: String) {
        val obj = YamlCodec.parseAll(yaml).firstOrNull()
            ?: throw IllegalArgumentException("The YAML is empty")
        obj.optJSONObject("metadata")?.remove("managedFields")
        request("PUT", path, obj.toString())
    }

    // ---- Streaming ----

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun logCall(
        ns: String,
        pod: String,
        container: String?,
        tailLines: Int,
        follow: Boolean,
        previous: Boolean,
    ): Call {
        val q = buildString {
            append("tailLines=").append(tailLines)
            append("&follow=").append(follow)
            append("&previous=").append(previous)
            if (container != null) append("&container=").append(enc(container))
        }
        val req = Request.Builder().url("$base/api/v1/namespaces/$ns/pods/$pod/log?$q").build()
        return streamHttp.newCall(req)
    }

    /** Blocks, calling [onLine] for each log line until the stream ends or the call is cancelled. */
    fun streamLines(call: Call, onLine: (String) -> Unit) {
        call.execute().use { resp ->
            if (!resp.isSuccessful) {
                throw K8sException(resp.code, statusMessage(resp.code, resp.body?.string().orEmpty()))
            }
            val src = resp.body?.source() ?: return
            while (true) {
                val line = src.readUtf8Line() ?: break
                onLine(line)
            }
        }
    }

    /** Opens an interactive TTY shell (bash if present, else sh) over the v4 channel protocol. */
    fun exec(ns: String, pod: String, container: String?, listener: WebSocketListener): WebSocket {
        val command = listOf(
            "/bin/sh", "-c",
            "if command -v bash >/dev/null 2>&1; then exec bash; else exec sh; fi",
        )
        val q = buildString {
            command.forEach { append("command=").append(enc(it)).append('&') }
            append("stdin=true&stdout=true&stderr=true&tty=true")
            if (container != null) append("&container=").append(enc(container))
        }
        val req = Request.Builder()
            .url("$base/api/v1/namespaces/$ns/pods/$pod/exec?$q")
            .header("Sec-WebSocket-Protocol", "v4.channel.k8s.io")
            .build()
        return streamHttp.newWebSocket(req, listener)
    }

    companion object {
        const val MERGE = "application/merge-patch+json"
    }
}
