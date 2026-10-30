package dev.ekspocket.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ekspocket.aws.AwsCredentials
import dev.ekspocket.aws.EksClient
import dev.ekspocket.cloud.Cluster
import dev.ekspocket.aws.EksToken
import dev.ekspocket.cloud.GkeClient
import dev.ekspocket.cloud.GoogleAccountTokenSource
import dev.ekspocket.cloud.ServiceAccountTokenSource
import dev.ekspocket.cloud.TokenSource
import dev.ekspocket.k8s.K8sClient
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class ProfileType(val label: String) {
    AWS("AWS"),
    GCP_KEY("GCP service account"),
    GCP_ACCOUNT("Google account"),
}

data class Profile(
    val id: String,
    val name: String,
    val type: ProfileType = ProfileType.AWS,
    // AWS
    val accessKeyId: String = "",
    val secretAccessKey: String = "",
    val sessionToken: String? = null,
    val region: String = "us-east-1",
    // GCP
    val projectId: String = "",
    val gcpKeyJson: String? = null,
    val googleEmail: String? = null,
) {
    val isAws: Boolean get() = type == ProfileType.AWS

    /** Who this profile acts as, for display. */
    val identity: String
        get() = when (type) {
            ProfileType.AWS -> maskedKey
            ProfileType.GCP_KEY -> gcpKeyJson?.let { runCatching { JSONObject(it).optString("client_email") }.getOrNull() }.orEmpty()
            ProfileType.GCP_ACCOUNT -> googleEmail.orEmpty()
        }

    fun credentials() = AwsCredentials(
        accessKeyId, secretAccessKey, sessionToken?.takeIf { it.isNotBlank() },
    )

    val maskedKey: String
        get() = if (accessKeyId.length > 8) "${accessKeyId.take(4)}…${accessKeyId.takeLast(4)}" else accessKeyId

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}

data class ProfileState(
    val profiles: List<Profile> = emptyList(),
    val activeId: String? = null,
)

/** Stores all profiles encrypted with an AES-GCM key that never leaves the Android Keystore. */
class CredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("ekspocket", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun save(state: ProfileState) {
        if (state.profiles.isEmpty()) {
            clear()
            return
        }
        val arr = JSONArray()
        state.profiles.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("type", p.type.name)
                    .put("ak", p.accessKeyId)
                    .put("sk", p.secretAccessKey)
                    .put("st", p.sessionToken ?: "")
                    .put("region", p.region)
                    .put("project", p.projectId)
                    .put("gcpKey", p.gcpKeyJson ?: "")
                    .put("gEmail", p.googleEmail ?: "")
            )
        }
        val json = JSONObject()
            .put("version", 2)
            .put("active", state.activeId ?: "")
            .put("profiles", arr)
            .toString()
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("ct", Base64.encodeToString(ct, Base64.NO_WRAP))
            .apply()
    }

    fun load(): ProfileState {
        return try {
            val iv = prefs.getString("iv", null) ?: return ProfileState()
            val ct = prefs.getString("ct", null) ?: return ProfileState()
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(
                Cipher.DECRYPT_MODE, key(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            val plain = cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP))
            val o = JSONObject(String(plain, Charsets.UTF_8))
            if (o.has("profiles")) parseV2(o) else migrateV1(o)
        } catch (e: Exception) {
            ProfileState()
        }
    }

    private fun parseV2(o: JSONObject): ProfileState {
        val arr = o.getJSONArray("profiles")
        val profiles = (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            Profile(
                id = p.getString("id"),
                name = p.getString("name"),
                type = ProfileType.entries.firstOrNull { it.name == p.optString("type") } ?: ProfileType.AWS,
                accessKeyId = p.optString("ak"),
                secretAccessKey = p.optString("sk"),
                sessionToken = p.optString("st").ifBlank { null },
                region = p.optString("region", "us-east-1"),
                projectId = p.optString("project"),
                gcpKeyJson = p.optString("gcpKey").ifBlank { null },
                googleEmail = p.optString("gEmail").ifBlank { null },
            )
        }
        return ProfileState(profiles, o.optString("active").ifBlank { null })
    }

    /** Version 1 stored a single key set; it becomes a profile named "default". */
    private fun migrateV1(o: JSONObject): ProfileState {
        val p = Profile(
            id = Profile.newId(),
            name = "default",
            accessKeyId = o.getString("ak"),
            secretAccessKey = o.getString("sk"),
            sessionToken = o.optString("st").ifBlank { null },
            region = o.optString("region", "us-east-1"),
        )
        val state = ProfileState(listOf(p), p.id)
        save(state)
        return state
    }

    fun clear() {
        prefs.edit().clear().apply()
        runCatching {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)
        }
    }

    private companion object {
        const val ALIAS = "ekspocket_credentials"
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}

class Session(private val store: CredentialStore, private val appContext: Context) {
    var state by mutableStateOf(store.load())
        private set

    var k8s: K8sClient? = null
        private set

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val active: Profile?
        get() = state.profiles.firstOrNull { it.id == state.activeId } ?: state.profiles.firstOrNull()

    val region: String get() = active?.region ?: "us-east-1"

    fun profile(id: String?): Profile? = state.profiles.firstOrNull { it.id == id }

    fun nameTaken(name: String, exceptId: String?): Boolean =
        state.profiles.any { it.id != exceptId && it.name.equals(name.trim(), ignoreCase = true) }

    private fun persist(next: ProfileState) {
        val previousActive = active?.id
        store.save(next)
        state = next
        if (active?.id != previousActive) k8s = null
    }

    /** Adds or updates [profile] and makes it the active one. */
    fun upsert(profile: Profile) {
        val exists = state.profiles.any { it.id == profile.id }
        val list = if (exists) {
            state.profiles.map { if (it.id == profile.id) profile else it }
        } else {
            state.profiles + profile
        }
        persist(ProfileState(list, profile.id))
        k8s = null
    }

    fun switchTo(id: String) {
        if (active?.id != id) persist(state.copy(activeId = id))
    }

    fun delete(id: String) {
        val list = state.profiles.filterNot { it.id == id }
        val nextActive = if (active?.id == id) list.firstOrNull()?.id else active?.id
        persist(ProfileState(list, nextActive))
    }

    fun setRegion(region: String) {
        val p = active ?: return
        if (p.region != region) persist(state.copy(profiles = state.profiles.map {
            if (it.id == p.id) it.copy(region = region) else it
        }))
    }

    private val tokenSources = HashMap<Profile, TokenSource>()

    /** One token source per profile version, so cached Google tokens are reused. */
    private fun tokenSource(p: Profile): TokenSource = synchronized(tokenSources) {
        tokenSources.getOrPut(p) {
            when (p.type) {
                ProfileType.GCP_KEY -> ServiceAccountTokenSource(
                    p.gcpKeyJson ?: error("This profile has no service account key"), http,
                )
                ProfileType.GCP_ACCOUNT -> GoogleAccountTokenSource(appContext, p.googleEmail)
                ProfileType.AWS -> error("AWS profiles don't use Google tokens")
            }
        }
    }

    fun listClusters(): List<Cluster> {
        val p = active ?: error("No profile selected")
        return when (p.type) {
            ProfileType.AWS -> {
                val eks = EksClient(p.credentials(), p.region, http)
                eks.listClusters().map { eks.describeCluster(it) }
            }
            ProfileType.GCP_KEY, ProfileType.GCP_ACCOUNT -> {
                if (p.projectId.isBlank()) error("Set a GCP project ID on this profile")
                GkeClient(tokenSource(p), p.projectId, http).listClusters()
            }
        }
    }

    fun connect(cluster: Cluster): K8sClient {
        val p = active ?: error("No profile selected")
        val provider = if (p.isAws) awsTokenProvider(p, cluster.name) else googleTokenProvider(p)
        return K8sClient(cluster, provider).also { k8s = it }
    }

    private fun awsTokenProvider(p: Profile, clusterName: String): () -> String {
        val creds = p.credentials()
        val region = p.region
        return { EksToken.generate(creds, region, clusterName) }
    }

    private fun googleTokenProvider(p: Profile): () -> String {
        val source = tokenSource(p)
        return { source.token() }
    }
}

val REGIONS = listOf(
    "us-east-1", "us-east-2", "us-west-1", "us-west-2", "ca-central-1", "sa-east-1",
    "eu-west-1", "eu-west-2", "eu-west-3", "eu-central-1", "eu-central-2", "eu-north-1",
    "eu-south-1", "ap-south-1", "ap-south-2", "ap-southeast-1", "ap-southeast-2",
    "ap-southeast-3", "ap-northeast-1", "ap-northeast-2", "ap-northeast-3", "ap-east-1",
    "me-south-1", "me-central-1", "af-south-1", "il-central-1",
)
