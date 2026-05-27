package com.example.claudevoice.api

import com.example.claudevoice.Config
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 火山引擎 HTTP 请求签名（HMAC-SHA256）。
 *
 * 参考文档：https://www.volcengine.com/docs/6369/67269
 *
 * 签名流程：
 *   1. 构造 CanonicalRequest
 *   2. 构造 StringToSign
 *   3. 派生 SigningKey
 *   4. 计算 Signature
 *   5. 生成 Authorization Header
 */
object VolcengineAuth {

    private const val ALGORITHM = "HMAC-SHA256"

    /**
     * 为 POST JSON 请求生成签名 Headers。
     *
     * @param url         完整 URL（如 https://ark.cn-beijing.volces.com/api/v3/chat/completions）
     * @param body        请求体 JSON 字符串
     * @param service     服务名（如 "ark"）
     * @param region      地域（如 "cn-beijing"）
     * @return 包含 X-Date 和 Authorization 的 Headers Map
     */
    fun signHeaders(
        url: String,
        body: String,
        service: String = Config.ARK_SERVICE,
        region: String  = Config.ARK_REGION
    ): Map<String, String> {
        val parsedUrl  = URL(url)
        val host       = parsedUrl.host
        val path       = parsedUrl.path.ifEmpty { "/" }
        val query      = parsedUrl.query ?: ""

        val now        = Date()
        val xDate      = formatXDate(now)
        val shortDate  = xDate.substring(0, 8)

        val signedHeaders = "content-type;host;x-date"
        val canonicalHeaders =
            "content-type:application/json\n" +
            "host:$host\n" +
            "x-date:$xDate\n"

        val bodyHash = sha256Hex(body)

        val canonicalRequest = listOf(
            "POST",
            path,
            query,
            canonicalHeaders,
            signedHeaders,
            bodyHash
        ).joinToString("\n")

        val credentialScope = "$shortDate/$region/$service/request"
        val stringToSign = listOf(
            ALGORITHM,
            xDate,
            credentialScope,
            sha256Hex(canonicalRequest)
        ).joinToString("\n")

        val signingKey = buildSigningKey(Config.VOLCENGINE_SK, shortDate, region, service)
        val signature  = hmacSha256Hex(signingKey, stringToSign)

        val authorization = "$ALGORITHM Credential=${Config.VOLCENGINE_AK}/$credentialScope, " +
                "SignedHeaders=$signedHeaders, Signature=$signature"

        return mapOf(
            "X-Date"        to xDate,
            "Authorization" to authorization,
            "Host"          to host,
            "Content-Type"  to "application/json"
        )
    }

    // ─── 内部工具 ─────────────────────────────────────────────────

    private fun formatXDate(date: Date): String {
        val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(date)
    }

    private fun buildSigningKey(sk: String, date: String, region: String, service: String): ByteArray {
        var key = hmacSha256(sk.toByteArray(StandardCharsets.UTF_8), date)
        key = hmacSha256(key, region)
        key = hmacSha256(key, service)
        key = hmacSha256(key, "request")
        return key
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
    }

    private fun hmacSha256Hex(key: ByteArray, data: String): String =
        hmacSha256(key, data).toHex()

    private fun sha256Hex(data: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data.toByteArray(StandardCharsets.UTF_8)).toHex()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
