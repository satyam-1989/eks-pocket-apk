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
import dev.ekspocket.aws.EksCluster
import dev.ekspocket.aws.EksToken
import dev.ekspocket.k8s.K8sClient
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class StoredConfig(
    val accessKeyId: String,
    val secretAccessKey: String,
    val sessionToken: String?,
    val region: String,
) {
    fun credentials() = AwsCredentials(
        accessKeyId, secretAccessKey, sessionToken?.takeIf { it.isNotBlank() },
    )
}

/** Stores keys encrypted with an AES-GCM key that never leaves the Android Keystore. */
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

    fun save(cfg: StoredConfig) {
        val json = JSONObject()
            .put("ak", cfg.accessKeyId)
            .put("sk", cfg.secretAccessKey)
            .put("st", cfg.sessionToken ?: "")
            .put("region", cfg.region)
            .toString()
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("ct", Base64.encodeToString(ct, Base64.NO_WRAP))
            .apply()
    }

    fun load(): StoredConfig? {
        return try {
            val iv = prefs.getString("iv", null) ?: return null
            val ct = prefs.getString("ct", null) ?: return null
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(
                Cipher.DECRYPT_MODE, key(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            val plain = cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP))
            val o = JSONObject(String(plain, Charsets.UTF_8))
            StoredConfig(
                accessKeyId = o.getString("ak"),
                secretAccessKey = o.getString("sk"),
                sessionToken = o.optString("st").ifBlank { null },
                region = o.optString("region", "us-east-1"),
            )
        } catch (e: Exception) {
            null
        }
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

class Session(private val store: CredentialStore) {
    var config by mutableStateOf(store.load())
        private set

    var k8s: K8sClient? = null
        private set

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val region: String get() = config?.region ?: "us-east-1"

    fun save(cfg: StoredConfig) {
        store.save(cfg)
        config = cfg
    }

    fun setRegion(region: String) {
        config?.let { save(it.copy(region = region)) }
    }

    fun signOut() {
        store.clear()
        config = null
        k8s = null
    }

    fun eks(): EksClient {
        val c = config ?: error("No AWS keys saved")
        return EksClient(c.credentials(), c.region, http)
    }

    fun connect(cluster: EksCluster): K8sClient {
        val c = config ?: error("No AWS keys saved")
        val creds = c.credentials()
        val region = c.region
        return K8sClient(cluster) { EksToken.generate(creds, region, cluster.name) }
            .also { k8s = it }
    }
}

val REGIONS = listOf(
    "us-east-1", "us-east-2", "us-west-1", "us-west-2", "ca-central-1", "sa-east-1",
    "eu-west-1", "eu-west-2", "eu-west-3", "eu-central-1", "eu-central-2", "eu-north-1",
    "eu-south-1", "ap-south-1", "ap-south-2", "ap-southeast-1", "ap-southeast-2",
    "ap-southeast-3", "ap-northeast-1", "ap-northeast-2", "ap-northeast-3", "ap-east-1",
    "me-south-1", "me-central-1", "af-south-1", "il-central-1",
)
