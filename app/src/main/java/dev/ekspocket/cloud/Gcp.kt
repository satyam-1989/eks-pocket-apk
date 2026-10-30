package dev.ekspocket.cloud

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit

class GcpException(message: String) : Exception(message)

/** Something that hands out a valid Google OAuth access token. */
interface TokenSource {
    fun token(): String
}

private const val CLOUD_PLATFORM = "https://www.googleapis.com/auth/cloud-platform"
private const val USERINFO_EMAIL = "https://www.googleapis.com/auth/userinfo.email"

private fun b64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

/** Exchanges a signed JWT from a service account key for short-lived access tokens. */
class ServiceAccountTokenSource(keyJson: String, private val http: OkHttpClient) : TokenSource {
    private val key = JSONObject(keyJson)
    private val clientEmail = key.getString("client_email")
    private val privateKey: PrivateKey = parsePrivateKey(key.getString("private_key"))
    private val tokenUri = key.optString("token_uri").ifBlank { "https://oauth2.googleapis.com/token" }
    private var cached: String? = null
    private var expiresAt = 0L

    @Synchronized
    override fun token(): String {
        val now = System.currentTimeMillis()
        cached?.let { if (now < expiresAt) return it }

        val iat = now / 1000
        val header = b64url("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val claims = b64url(
            JSONObject()
                .put("iss", clientEmail)
                .put("scope", CLOUD_PLATFORM)
                .put("aud", tokenUri)
                .put("iat", iat)
                .put("exp", iat + 3600)
                .toString()
                .toByteArray()
        )
        val signingInput = "$header.$claims"
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(signingInput.toByteArray())
            sign()
        }
        val body = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
            .add("assertion", "$signingInput.${b64url(sig)}")
            .build()
        http.newCall(Request.Builder().url(tokenUri).post(body).build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(text).optString("error_description") }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: text.take(200)
                throw GcpException("Google rejected the service account key: $msg")
            }
            val o = JSONObject(text)
            val token = o.getString("access_token")
            cached = token
            expiresAt = now + (o.optLong("expires_in", 3600) - 300) * 1000
            return token
        }
    }

    companion object {
        fun parsePrivateKey(pem: String): PrivateKey {
            val der = Base64.getDecoder().decode(
                pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replace(Regex("\\s"), "")
            )
            return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
        }

        /** Checks a key file and returns (client email, project id). Throws with a readable message. */
        fun inspect(keyJson: String): Pair<String, String> {
            val k = try {
                JSONObject(keyJson)
            } catch (e: Exception) {
                throw GcpException("This isn't valid JSON")
            }
            if (k.optString("type") != "service_account") throw GcpException("This isn't a service account key file")
            val email = k.optString("client_email")
            if (email.isBlank() || k.optString("private_key").isBlank()) throw GcpException("The key file is missing fields")
            try {
                parsePrivateKey(k.getString("private_key"))
            } catch (e: Exception) {
                throw GcpException("The private key in this file can't be read")
            }
            return email to k.optString("project_id")
        }
    }
}

/** Access tokens for a Google account signed in on this phone, via Google Play services. */
object GoogleAuth {
    private val scopes = listOf(Scope(CLOUD_PLATFORM), Scope(USERINFO_EMAIL))

    fun client(context: Context): AuthorizationClient = Identity.getAuthorizationClient(context)

    fun request(email: String?): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(scopes)
            .apply { if (!email.isNullOrBlank()) setAccount(Account(email, "com.google")) }
            .build()

    /** Looks up which Google account a token belongs to. */
    fun emailFor(token: String, http: OkHttpClient): String {
        val req = Request.Builder()
            .url("https://openidconnect.googleapis.com/v1/userinfo")
            .header("Authorization", "Bearer $token")
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw GcpException("Couldn't read your Google account (${resp.code})")
            return JSONObject(text).optString("email").ifBlank { throw GcpException("Google didn't return an email") }
        }
    }
}

class GoogleAccountTokenSource(private val context: Context, private val email: String?) : TokenSource {
    private var cached: String? = null
    private var fetchedAt = 0L

    @Synchronized
    override fun token(): String {
        val now = System.currentTimeMillis()
        cached?.let { if (now - fetchedAt < 5 * 60_000) return it }
        val result = try {
            Tasks.await(GoogleAuth.client(context).authorize(GoogleAuth.request(email)), 30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            throw GcpException("Google sign-in failed: ${e.cause?.message ?: e.message}")
        }
        if (result.hasResolution()) {
            throw GcpException("Google needs you to confirm access again. Edit this profile and tap Sign in with Google.")
        }
        val token = result.accessToken ?: throw GcpException("Google didn't return an access token")
        cached = token
        fetchedAt = now
        return token
    }
}

/** Lists GKE clusters in one project, across all regions and zones. */
class GkeClient(
    private val tokens: TokenSource,
    private val projectId: String,
    private val http: OkHttpClient,
) {
    fun listClusters(): List<Cluster> {
        val req = Request.Builder()
            .url("https://container.googleapis.com/v1/projects/$projectId/locations/-/clusters")
            .header("Authorization", "Bearer ${tokens.token()}")
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: text.take(300)
                throw GcpException("GKE returned ${resp.code}: $msg")
            }
            val arr = JSONObject(text).optJSONArray("clusters") ?: return emptyList()
            return (0 until arr.length()).map { parse(arr.getJSONObject(it)) }.sortedBy { it.name }
        }
    }

    private fun parse(c: JSONObject): Cluster {
        val name = c.getString("name")
        val location = c.optString("location")
        val ca = c.optJSONObject("masterAuth")?.optString("clusterCaCertificate").orEmpty()
        val privateCfg = c.optJSONObject("privateClusterConfig")
        val endpoints = c.optJSONObject("controlPlaneEndpointsConfig")
        val dns = endpoints?.optJSONObject("dnsEndpointConfig")
        val ipCfg = endpoints?.optJSONObject("ipEndpointsConfig")

        val ipEnabled = ipCfg?.optBoolean("enabled", true) ?: true
        val publicIp = when {
            ipCfg != null && ipCfg.has("enablePublicEndpoint") -> ipCfg.optBoolean("enablePublicEndpoint")
            privateCfg?.optBoolean("enablePrivateEndpoint") == true -> false
            else -> true
        }
        val publicEndpoint = c.optString("endpoint")
        val dnsEndpoint = dns?.optString("endpoint").orEmpty()
        val dnsExternal = dns?.optBoolean("allowExternalTraffic") == true && dnsEndpoint.isNotBlank()
        val privateEndpoint = privateCfg?.optString("privateEndpoint").orEmpty()

        data class Route(val url: String, val ca: String, val public: Boolean, val note: String?)
        val route = when {
            ipEnabled && publicIp && publicEndpoint.isNotBlank() -> Route("https://$publicEndpoint", ca, true, null)
            // The DNS endpoint uses a Google-trusted certificate, so no cluster CA is needed.
            dnsExternal -> Route("https://$dnsEndpoint", "", true, "DNS endpoint")
            else -> Route(
                "https://" + privateEndpoint.ifBlank { publicEndpoint }, ca, false,
                "Private endpoint only. Connect a VPN into the VPC, or enable the DNS endpoint.",
            )
        }

        return Cluster(
            id = c.optString("selfLink").ifBlank { "gke/$projectId/$location/$name" },
            name = name,
            provider = "GKE",
            location = location,
            endpoint = route.url,
            caData = route.ca,
            status = c.optString("status"),
            version = c.optString("currentMasterVersion"),
            publicAccess = route.public,
            privateAccess = privateEndpoint.isNotBlank(),
            accessNote = route.note,
        )
    }
}
