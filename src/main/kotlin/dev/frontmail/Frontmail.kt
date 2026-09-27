package dev.frontmail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.SocketTimeoutException
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/**
 * Frontmail client for Android and Kotlin/JVM apps. Sends emails through your templates with the
 * **public** key (`pk_…`) – never ship a private key in an app.
 *
 * Requests from apps carry no `Origin` header, so turn on **Security → Mobile apps → Allow mobile
 * apps** in the dashboard, otherwise the API answers `403 origin_not_allowed`.
 *
 * The client is thread-safe and cheap; create one per public key and reuse it.
 *
 * ```kotlin
 * val frontmail = Frontmail("pk_…")
 * val result = frontmail.send("tpl_contact", mapOf("email" to email, "message" to message))
 * ```
 */
public class Frontmail internal constructor(
    publicKey: String,
    apiUrl: String,
    /** Retry policy for network errors, timeouts, 5xx and 429. */
    public val retry: RetryPolicy,
    /** Per-attempt timeout in milliseconds. */
    public val timeoutMillis: Long,
    private val transport: HttpTransport,
    private val random: () -> Double,
) {
    /**
     * @param publicKey the public key (`pk_…`) from the dashboard. A private key (`sk_…`) throws
     *   [FrontmailException] with code `private_key_in_browser`.
     * @param apiUrl API base URL; trailing slashes are removed.
     * @param retry retry policy, [RetryPolicy.NONE] disables retries.
     * @param timeoutMillis per-attempt timeout.
     */
    public constructor(
        publicKey: String,
        apiUrl: String = DEFAULT_API_URL,
        retry: RetryPolicy = RetryPolicy(),
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ) : this(publicKey, apiUrl, retry, timeoutMillis, UrlConnectionTransport, { Random.nextDouble() })

    /** The public key used for all requests. */
    public val publicKey: String = publicKey

    /** API base URL without trailing slashes. */
    public val apiUrl: String = normalizeApiUrl(apiUrl)

    init {
        if (publicKey.startsWith("sk_")) {
            throw FrontmailException(
                FrontmailException.PRIVATE_KEY_IN_BROWSER,
                "Private keys (sk_…) must never be used in a mobile app – anything in an app can be extracted. " +
                    "Use the public key (pk_…) and enable Security → Mobile apps in the dashboard.",
            )
        }
        require(publicKey.isNotBlank()) { "publicKey must not be blank." }
        require(timeoutMillis > 0) { "timeoutMillis must be > 0." }
        requireHttpUrl(this.apiUrl)
    }

    /**
     * Sends an email through a template (`POST /v1/send`).
     *
     * Params may contain `String`, numbers, `Boolean`, `null`, nested `Map<String, *>`, `List`,
     * arrays and [JsonElement]s; anything else throws [IllegalArgumentException].
     *
     * All retries of one call reuse the same `Idempotency-Key`, so a retry never sends twice.
     *
     * @param templateId template id (`tpl_…`).
     * @param serviceId email service; `null` = the template's service, else the organization default.
     * @throws FrontmailException on API, network and timeout errors.
     */
    public suspend fun send(
        templateId: String,
        params: Map<String, Any?> = emptyMap(),
        serviceId: String? = null,
        options: SendOptions = SendOptions(),
    ): SendResult = send(templateId, toJsonElement(params) as JsonObject, serviceId, options)

    /** [send] with params that are already JSON. */
    public suspend fun send(
        templateId: String,
        params: JsonObject,
        serviceId: String? = null,
        options: SendOptions = SendOptions(),
    ): SendResult {
        val body = buildJsonObject {
            if (!serviceId.isNullOrEmpty()) put("service_id", serviceId)
            put("template_id", templateId)
            put("user_id", publicKey)
            put("template_params", params)
            options.turnstileToken?.let { put("turnstile_token", it) }
            options.turnstileKey?.let { put("turnstile_key", it.value) }
            options.locale?.takeIf { it.isNotEmpty() }?.let { put("locale", it) }
            if (options.attachments.isNotEmpty()) {
                put(
                    "attachments",
                    JsonArray(
                        options.attachments.map { a ->
                            when (a) {
                                is Attachment.Inline -> buildJsonObject {
                                    put("filename", a.filename)
                                    put("content_type", a.contentType)
                                    put("content_base64", a.contentBase64)
                                }
                                is Attachment.Upload -> buildJsonObject { put("upload_id", a.uploadId) }
                            }
                        },
                    ),
                )
            }
        }
        val idempotencyKey = options.idempotencyKey?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
        val data = request("POST", "/v1/send", body, mapOf("Idempotency-Key" to idempotencyKey)) as? JsonObject
        val messageId = data?.string("message_id")
        if (data == null || messageId.isNullOrEmpty()) throw invalidResponse()
        return SendResult(
            messageId = messageId,
            status = AcceptedStatus.from(data.string("status") ?: ""),
            statusToken = data.string("status_token") ?: "",
            locale = data.string("locale")?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Reads the delivery status of a message (`GET /v1/messages/:id`). The token travels in the
     * `X-Frontmail-Status-Token` header, never in the URL.
     */
    public suspend fun getStatus(messageId: String, statusToken: String): MessageStatusResponse {
        require(messageId.isNotEmpty()) { "messageId must not be empty." }
        val data = request(
            "GET",
            "/v1/messages/" + encodeUriComponent(messageId),
            null,
            mapOf(STATUS_TOKEN_HEADER to statusToken),
        ) as? JsonObject ?: throw invalidResponse()
        return parseStatus(data)
    }

    /** Status of a message returned by [send]. */
    public suspend fun getStatus(result: SendResult): MessageStatusResponse = getStatus(result.messageId, result.statusToken)

    private suspend fun request(
        method: String,
        path: String,
        body: JsonElement?,
        extraHeaders: Map<String, String>,
    ): JsonElement? {
        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = "application/json"
        headers["X-Frontmail-Client"] = CLIENT_NAME
        headers["X-Frontmail-Public-Key"] = publicKey
        if (body != null) headers["Content-Type"] = "application/json"
        headers.putAll(extraHeaders)
        val req = HttpRequest(method, apiUrl + path, headers, body?.toString(), timeoutMillis)

        var attempt = 0
        while (true) {
            val (res, networkError) = attempt(req, transport)
            var wait: Long? = null
            val err: FrontmailException
            if (res != null) {
                val data = parseJson(res.body)
                if (res.status in 200..299) return data
                val retryAfter = parseRetryAfter(res.header("Retry-After"))
                err = errorFromResponse(res.status, data, retryAfter)
                if (res.status < 500 && res.status != 429) throw err
                if (retryAfter != null) {
                    if (retryAfter > MAX_RETRY_AFTER_SECONDS) throw err
                    wait = (retryAfter * 1000).toLong()
                }
            } else {
                err = networkError!!
            }
            if (attempt >= retry.retries) throw err
            delay(wait ?: backoffDelay(attempt, retry.baseDelayMillis, retry.maxDelayMillis, random()))
            attempt++
        }
    }

    public companion object {
        /** Default API URL. */
        public const val DEFAULT_API_URL: String = "https://api.frontmail.dev"

        /** Default per-attempt timeout. */
        public const val DEFAULT_TIMEOUT_MILLIS: Long = 15_000

        /** Header carrying the `statusToken` when reading a message status. */
        public const val STATUS_TOKEN_HEADER: String = "X-Frontmail-Status-Token"

        /** Value of the `X-Frontmail-Client` header. */
        public const val CLIENT_NAME: String = "frontmail-kotlin/" + FrontmailVersion.CURRENT

        /** 429/5xx responses asking to wait longer than this are not retried. */
        private const val MAX_RETRY_AFTER_SECONDS = 60.0

        private val configCache = HashMap<String, PublicConfig>()
        private val configMutex = Mutex()

        /**
         * Public configuration of a Frontmail API (`GET <apiUrl>/v1/public-config`, no key needed),
         * e.g. Frontmail's shared Turnstile site key for apps. Cached in memory per API URL for the
         * lifetime of the process; failures are not cached, so the next call tries again.
         */
        public suspend fun publicConfig(apiUrl: String = DEFAULT_API_URL): PublicConfig = publicConfig(apiUrl, UrlConnectionTransport)

        internal suspend fun publicConfig(apiUrl: String, transport: HttpTransport): PublicConfig {
            val base = normalizeApiUrl(apiUrl)
            requireHttpUrl(base)
            return configMutex.withLock {
                configCache[base] ?: loadPublicConfig(base, transport).also { configCache[base] = it }
            }
        }

        internal suspend fun resetPublicConfigCache() {
            configMutex.withLock { configCache.clear() }
        }

        private suspend fun loadPublicConfig(base: String, transport: HttpTransport): PublicConfig {
            val req = HttpRequest(
                "GET",
                "$base/v1/public-config",
                mapOf("Accept" to "application/json", "X-Frontmail-Client" to CLIENT_NAME),
                null,
                DEFAULT_TIMEOUT_MILLIS,
            )
            val (res, networkError) = attempt(req, transport)
            if (res == null) throw networkError!!
            val data = parseJson(res.body)
            if (res.status !in 200..299) throw errorFromResponse(res.status, data, parseRetryAfter(res.header("Retry-After")))
            val t = (data as? JsonObject)?.get("turnstile") as? JsonObject ?: throw invalidResponse()
            return PublicConfig(
                PublicConfig.TurnstileConfig(
                    mobileSiteKey = t.string("mobileSiteKey")?.ifEmpty { null },
                    mobileBaseUrl = t.string("mobileBaseUrl")?.ifEmpty { null },
                ),
            )
        }

        /** One attempt: the response, or the network/timeout error. Cancellation propagates. */
        private suspend fun attempt(req: HttpRequest, transport: HttpTransport): Pair<HttpResponse?, FrontmailException?> =
            try {
                val res = withTimeoutOrNull(req.timeoutMillis) { transport.execute(req) }
                if (res != null) {
                    res to null
                } else {
                    null to FrontmailException(FrontmailException.TIMEOUT, "Request timed out.")
                }
            } catch (e: CancellationException) {
                throw abortedCancellation(e)
            } catch (e: SocketTimeoutException) {
                null to FrontmailException(FrontmailException.TIMEOUT, "Request timed out.", cause = e)
            } catch (e: Exception) {
                null to FrontmailException(FrontmailException.NETWORK_ERROR, "Network request failed.", cause = e)
            }

        private val json = Json { ignoreUnknownKeys = true }

        private fun parseJson(body: String): JsonElement? =
            if (body.isBlank()) null else runCatching { json.parseToJsonElement(body) }.getOrNull()

        private fun invalidResponse() = FrontmailException(FrontmailException.INVALID_RESPONSE, "Unexpected API response.")

        private fun requireHttpUrl(url: String) {
            require(url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)) {
                "apiUrl must be an http(s) URL."
            }
        }

        /** Maps an HTTP error response to a [FrontmailException] (mirrors sdk-core's `errorFromResponse`). */
        internal fun errorFromResponse(status: Int, body: JsonElement?, retryAfterSeconds: Double?): FrontmailException {
            val e = (body as? JsonObject)?.get("error") as? JsonObject
            val code = e?.string("code") ?: if (status >= 500) "internal_error" else "bad_request"
            return FrontmailException(
                code = code,
                message = e?.string("message") ?: "Request failed with status $status",
                status = status,
                docsUrl = e?.string("docs_url") ?: FrontmailException.docsUrlFor(code),
                details = e?.get("details")?.takeIf { it !is JsonNull },
                retryAfter = retryAfterSeconds?.let { (it * 1000).toLong().milliseconds },
            )
        }

        private fun parseStatus(o: JsonObject): MessageStatusResponse {
            val messageId = o.string("message_id")
            val status = o.string("status")
            if (messageId.isNullOrEmpty() || status == null) throw invalidResponse()
            val events = (o["events"] as? JsonArray).orEmpty().mapNotNull { ev ->
                val eo = ev as? JsonObject ?: return@mapNotNull null
                MessageEvent(type = eo.string("type") ?: "", at = eo.string("at") ?: "", raw = eo)
            }
            return MessageStatusResponse(
                messageId = messageId,
                status = MessageStatus.from(status),
                createdAt = o.string("created_at") ?: "",
                sentAt = o.string("sent_at"),
                templateId = o.string("template_id"),
                serviceId = o.string("service_id"),
                events = events,
                raw = o,
            )
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
