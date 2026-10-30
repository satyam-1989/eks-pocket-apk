package dev.ekspocket.aws

import dev.ekspocket.cloud.Cluster
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Base64

class AwsException(message: String) : Exception(message)

class EksClient(
    private val creds: AwsCredentials,
    val region: String,
    private val http: OkHttpClient,
) {
    private val host = "eks.$region.amazonaws.com"

    private fun get(path: String, query: Map<String, String> = emptyMap()): JSONObject {
        val headers = SigV4.signHeaders(creds, "GET", host, path, query, region, "eks")
        val qs = SigV4.canonicalQuery(query)
        val url = "https://$host$path" + if (qs.isEmpty()) "" else "?$qs"
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(body).optString("message") }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: body.take(300)
                throw AwsException("EKS returned ${resp.code}: $msg")
            }
            return JSONObject(body)
        }
    }

    fun listClusters(): List<String> {
        val out = mutableListOf<String>()
        var token: String? = null
        do {
            val query = buildMap {
                put("maxResults", "100")
                token?.let { put("nextToken", it) }
            }
            val r = get("/clusters", query)
            r.optJSONArray("clusters")?.let { arr ->
                for (i in 0 until arr.length()) out += arr.getString(i)
            }
            token = r.optString("nextToken").takeIf { it.isNotBlank() && it != "null" }
        } while (token != null)
        return out
    }

    fun describeCluster(name: String): Cluster {
        val c = get("/clusters/$name").getJSONObject("cluster")
        val vpc = c.optJSONObject("resourcesVpcConfig")
        return Cluster(
            id = c.optString("arn").ifBlank { "eks/$region/$name" },
            name = c.getString("name"),
            provider = "EKS",
            location = region,
            endpoint = c.optString("endpoint"),
            caData = c.optJSONObject("certificateAuthority")?.optString("data").orEmpty(),
            status = c.optString("status"),
            version = c.optString("version"),
            publicAccess = vpc?.optBoolean("endpointPublicAccess", true) ?: true,
            privateAccess = vpc?.optBoolean("endpointPrivateAccess", false) ?: false,
        )
    }
}

/** Same token `aws eks get-token` produces: a presigned STS GetCallerIdentity URL. */
object EksToken {
    fun generate(creds: AwsCredentials, region: String, clusterName: String): String {
        val url = SigV4.presignGetUrl(
            creds = creds,
            host = "sts.$region.amazonaws.com",
            path = "/",
            query = mapOf("Action" to "GetCallerIdentity", "Version" to "2011-06-15"),
            region = region,
            service = "sts",
            signedHeaderValues = mapOf("x-k8s-aws-id" to clusterName),
            expiresSeconds = 60,
        )
        return "k8s-aws-v1." +
            Base64.getUrlEncoder().withoutPadding().encodeToString(url.toByteArray(Charsets.UTF_8))
    }
}
