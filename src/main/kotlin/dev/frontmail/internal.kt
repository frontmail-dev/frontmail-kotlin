package dev.frontmail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Minimal HTTP abstraction so tests can run without a network. */
internal fun interface HttpTransport {
    /** Performs one attempt. Throws on network failures; HTTP error statuses are returned normally. */
    suspend fun execute(request: HttpRequest): HttpResponse
}

internal class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String?,
    val timeoutMillis: Long,
)

internal class HttpResponse(
    val status: Int,
    /** Header names are matched case-insensitively by [header]. */
    val headers: Map<String, String>,
    val body: String,
) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/** [HttpURLConnection]-based transport (available on every Android version and JVM). */
internal object UrlConnectionTransport : HttpTransport {
    private const val MAX_BODY_BYTES = 1 shl 20

    override suspend fun execute(request: HttpRequest): HttpResponse =
        suspendCancellableCoroutine { cont ->
            val conn = URL(request.url).openConnection() as HttpURLConnection
            // Disconnecting unblocks a pending read, so cancellation is immediate.
            cont.invokeOnCancellation { runCatching { conn.disconnect() } }
            Dispatchers.IO.asExecutor().execute {
                cont.resumeWith(runCatching { perform(conn, request) })
            }
        }

    private fun perform(conn: HttpURLConnection, request: HttpRequest): HttpResponse {
        try {
            val timeout = request.timeoutMillis.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            conn.connectTimeout = timeout
            conn.readTimeout = timeout
            conn.requestMethod = request.method
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            request.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (request.body != null) {
                val bytes = request.body.toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else runCatching { conn.inputStream }.getOrNull()
            val body = stream?.use { readLimited(it) } ?: ""
            val headers = conn.headerFields.entries
                .filter { it.key != null && !it.value.isNullOrEmpty() }
                .associate { it.key to it.value.first() }
            return HttpResponse(status, headers, body)
        } finally {
            conn.disconnect()
        }
    }

    private fun readLimited(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BODY_BYTES) break
        }
        return out.toString("UTF-8")
    }
}

/** `CancellationException` carrying an `aborted` [FrontmailException] as its cause. */
internal fun abortedCancellation(cause: Throwable): CancellationException =
    CancellationException("Request aborted.").apply {
        initCause(FrontmailException(FrontmailException.ABORTED, "Request aborted.", cause = cause))
    }

/** Trims trailing slashes like sdk-core (`/\/+$/`). */
internal fun normalizeApiUrl(apiUrl: String): String = apiUrl.ifEmpty { Frontmail.DEFAULT_API_URL }.trimEnd('/')

/** JavaScript's `encodeURIComponent`. */
internal fun encodeUriComponent(value: String): String {
    val sb = StringBuilder()
    for (b in value.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt() and 0xff
        if (c < 0x80 && (c.toChar().isLetterOrDigit() || c.toChar() in "-_.!~*'()")) {
            sb.append(c.toChar())
        } else {
            sb.append('%').append(HEX[c shr 4]).append(HEX[c and 0xf])
        }
    }
    return sb.toString()
}

private const val HEX = "0123456789ABCDEF"

/** Parses `Retry-After` (seconds or HTTP date) into seconds, never negative. */
internal fun parseRetryAfter(header: String?, nowMillis: Long = System.currentTimeMillis()): Double? {
    val h = header?.trim()
    if (h.isNullOrEmpty()) return null
    h.toDoubleOrNull()?.let { return if (it.isNaN()) null else maxOf(0.0, it) }
    val date = runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.parse(h)
    }.getOrNull() ?: return null
    return maxOf(0.0, (date.time - nowMillis) / 1000.0)
}

/** Full-jitter exponential backoff: random in [0, min(max, base * 2^attempt)). */
internal fun backoffDelay(attempt: Int, baseMillis: Long, maxMillis: Long, random: Double): Long {
    val cap = minOf(maxMillis.toDouble(), baseMillis.toDouble() * Math.pow(2.0, attempt.toDouble()))
    return Math.floor(random * cap).toLong()
}

/** Converts `Map<String, Any?>` template params into JSON. */
internal fun toJsonElement(value: Any?, path: String = "params"): JsonElement =
    when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is CharSequence, is Char -> JsonPrimitive(value.toString())
        is Boolean -> JsonPrimitive(value)
        is Int, is Long, is Short, is Byte, is BigInteger -> JsonPrimitive(value as Number)
        is Double, is Float -> {
            val d = (value as Number).toDouble()
            require(!d.isNaN() && !d.isInfinite()) { "$path: NaN and Infinity can't be sent as JSON." }
            JsonPrimitive(value)
        }
        is BigDecimal -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value.toDouble().also { require(!it.isNaN() && !it.isInfinite()) { "$path: not a finite number." } })
        is Map<*, *> -> JsonObject(
            value.entries.associate { (k, v) ->
                require(k is String) { "$path: map keys must be strings." }
                k to toJsonElement(v, "$path.$k")
            },
        )
        is Iterable<*> -> JsonArray(value.mapIndexed { i, v -> toJsonElement(v, "$path[$i]") })
        is Array<*> -> JsonArray(value.mapIndexed { i, v -> toJsonElement(v, "$path[$i]") })
        is IntArray -> JsonArray(value.map { JsonPrimitive(it) })
        is LongArray -> JsonArray(value.map { JsonPrimitive(it) })
        is DoubleArray -> JsonArray(value.mapIndexed { i, v -> toJsonElement(v, "$path[$i]") })
        is BooleanArray -> JsonArray(value.map { JsonPrimitive(it) })
        is Enum<*> -> JsonPrimitive(value.name)
        else -> throw IllegalArgumentException(
            "$path: unsupported value of type ${value::class.java.name}. Use String, Number, Boolean, null, Map, List, Array or JsonElement.",
        )
    }

/** Base64 (RFC 4648, with padding) without `java.util.Base64`, which needs Android API 26. */
internal object Base64Encoder {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xff
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else -1
            sb.append(ALPHABET[b0 shr 2])
            sb.append(ALPHABET[((b0 and 0x03) shl 4) or (if (b1 < 0) 0 else b1 shr 4)])
            sb.append(if (b1 < 0) '=' else ALPHABET[((b1 and 0x0f) shl 2) or (if (b2 < 0) 0 else b2 shr 6)])
            sb.append(if (b2 < 0) '=' else ALPHABET[b2 and 0x3f])
            i += 3
        }
        return sb.toString()
    }
}
