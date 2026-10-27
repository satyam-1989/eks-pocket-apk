package dev.ekspocket.aws

import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.TreeMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class AwsCredentials(
    val accessKeyId: String,
    val secretAccessKey: String,
    val sessionToken: String? = null,
)

/** Minimal AWS Signature Version 4: header signing and presigned GET URLs. */
object SigV4 {
    private const val ALGORITHM = "AWS4-HMAC-SHA256"
    private val AMZ_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun hmac(key: ByteArray, message: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
    }

    /** RFC 3986 encoding as required by SigV4 (only unreserved characters left as-is). */
    fun uriEncode(value: String): String {
        val sb = StringBuilder()
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                ch == '-' || ch == '_' || ch == '.' || ch == '~'
            ) {
                sb.append(ch)
            } else {
                sb.append('%').append(String.format("%02X", c))
            }
        }
        return sb.toString()
    }

    fun canonicalQuery(params: Map<String, String>): String =
        params.entries
            .map { uriEncode(it.key) to uriEncode(it.value) }
            .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
            .joinToString("&") { "${it.first}=${it.second}" }

    private fun signature(
        secret: String,
        shortDate: String,
        region: String,
        service: String,
        stringToSign: String,
    ): String {
        val kDate = hmac("AWS4$secret".toByteArray(Charsets.UTF_8), shortDate)
        val kRegion = hmac(kDate, region)
        val kService = hmac(kRegion, service)
        val kSigning = hmac(kService, "aws4_request")
        return hmac(kSigning, stringToSign).toHex()
    }

    private fun stringToSign(amzDate: String, scope: String, canonicalRequest: String): String =
        "$ALGORITHM\n$amzDate\n$scope\n${sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8))}"

    /** Returns the headers to add to a request (X-Amz-Date, optional token, Authorization). */
    fun signHeaders(
        creds: AwsCredentials,
        method: String,
        host: String,
        path: String,
        query: Map<String, String>,
        region: String,
        service: String,
        payload: ByteArray = ByteArray(0),
        now: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC),
    ): Map<String, String> {
        val amzDate = AMZ_DATE.format(now)
        val shortDate = SHORT_DATE.format(now)
        val scope = "$shortDate/$region/$service/aws4_request"

        val headers = TreeMap<String, String>()
        headers["host"] = host
        headers["x-amz-date"] = amzDate
        creds.sessionToken?.let { headers["x-amz-security-token"] = it }

        val signedHeaders = headers.keys.joinToString(";")
        val canonicalHeaders = headers.entries.joinToString("") { "${it.key}:${it.value.trim()}\n" }
        val canonicalRequest = listOf(
            method, path, canonicalQuery(query), canonicalHeaders, signedHeaders, sha256Hex(payload),
        ).joinToString("\n")

        val sig = signature(
            creds.secretAccessKey, shortDate, region, service,
            stringToSign(amzDate, scope, canonicalRequest),
        )

        val out = LinkedHashMap<String, String>()
        out["X-Amz-Date"] = amzDate
        creds.sessionToken?.let { out["X-Amz-Security-Token"] = it }
        out["Authorization"] =
            "$ALGORITHM Credential=${creds.accessKeyId}/$scope, SignedHeaders=$signedHeaders, Signature=$sig"
        return out
    }

    /** Builds a presigned GET URL (used for the EKS STS token). */
    fun presignGetUrl(
        creds: AwsCredentials,
        host: String,
        path: String,
        query: Map<String, String>,
        region: String,
        service: String,
        signedHeaderValues: Map<String, String>,
        expiresSeconds: Int,
        now: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC),
    ): String {
        val amzDate = AMZ_DATE.format(now)
        val shortDate = SHORT_DATE.format(now)
        val scope = "$shortDate/$region/$service/aws4_request"

        val headers = TreeMap<String, String>()
        headers["host"] = host
        signedHeaderValues.forEach { (k, v) -> headers[k.lowercase()] = v }
        val signedHeaders = headers.keys.joinToString(";")
        val canonicalHeaders = headers.entries.joinToString("") { "${it.key}:${it.value.trim()}\n" }

        val q = LinkedHashMap(query)
        q["X-Amz-Algorithm"] = ALGORITHM
        q["X-Amz-Credential"] = "${creds.accessKeyId}/$scope"
        q["X-Amz-Date"] = amzDate
        q["X-Amz-Expires"] = expiresSeconds.toString()
        q["X-Amz-SignedHeaders"] = signedHeaders
        creds.sessionToken?.let { q["X-Amz-Security-Token"] = it }

        val cq = canonicalQuery(q)
        val canonicalRequest = listOf(
            "GET", path, cq, canonicalHeaders, signedHeaders, sha256Hex(ByteArray(0)),
        ).joinToString("\n")

        val sig = signature(
            creds.secretAccessKey, shortDate, region, service,
            stringToSign(amzDate, scope, canonicalRequest),
        )
        return "https://$host$path?$cq&X-Amz-Signature=$sig"
    }
}
