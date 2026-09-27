package dev.frontmail

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration

/**
 * The only exception type thrown by the SDK (besides [IllegalArgumentException] for programming
 * errors such as unsupported param values).
 *
 * [code] is either an API error code (`invalid_template_params`, `origin_not_allowed`, …) or one of
 * the client codes in the companion ([NETWORK_ERROR], [TIMEOUT], [ABORTED], [INVALID_RESPONSE],
 * [PRIVATE_KEY_IN_BROWSER]). Use the category properties ([isAuth], [isValidation], …) instead of
 * matching HTTP statuses yourself.
 */
public class FrontmailException(
    /** API error code or client code, e.g. `captcha_required` or `network_error`. */
    public val code: String,
    message: String,
    /** HTTP status of the error response; `null` for client-side errors (network, timeout, …). */
    public val status: Int? = null,
    /** Documentation page for [code]. */
    public val docsUrl: String = docsUrlFor(code),
    /** Extra error data from the API (`error.details`), e.g. `{"reason":"no_origin"}`. */
    public val details: JsonElement? = null,
    /** Server hint from the `Retry-After` header (429 / 503), when present. */
    public val retryAfter: Duration? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** HTTP 401 and key/origin related 403 responses (`forbidden`, `origin_not_allowed`, …). */
    public val isAuth: Boolean
        get() = status == 401 || (status == 403 && code in AUTH_403)

    /** HTTP 400 / 422 – invalid body or template params; see [details]. */
    public val isValidation: Boolean
        get() = status == 400 || status == 422

    /** HTTP 429 – see [retryAfter]. */
    public val isRateLimit: Boolean
        get() = status == 429

    /** HTTP 402 – the organization has no credits left (reject mode or a full hold queue). */
    public val isInsufficientCredits: Boolean
        get() = status == 402

    /** The request never produced an HTTP response (offline, DNS, TLS, timeout). */
    public val isNetwork: Boolean
        get() = status == null && (code == NETWORK_ERROR || code == TIMEOUT)

    /** `details.reason` when the API sent one (e.g. `no_origin`, `content_locked`). */
    public val reason: String?
        get() = ((details as? JsonObject)?.get("reason") as? JsonPrimitive)?.takeIf { it.isString }?.content

    override fun toString(): String = "FrontmailException(code=$code, status=$status, message=$message)"

    public companion object {
        /** No HTTP response (offline, DNS, TLS, connection reset). */
        public const val NETWORK_ERROR: String = "network_error"

        /** An attempt exceeded the per-attempt timeout. */
        public const val TIMEOUT: String = "timeout"

        /**
         * The coroutine was cancelled. Cancellation is propagated as a
         * [kotlinx.coroutines.CancellationException] whose `cause` is a [FrontmailException] with
         * this code, so structured concurrency keeps working.
         */
        public const val ABORTED: String = "aborted"

        /** The API answered with something the SDK could not understand. */
        public const val INVALID_RESPONSE: String = "invalid_response"

        /** A private key (`sk_…`) was passed to the app SDK. Mobile apps must only ship the public key. */
        public const val PRIVATE_KEY_IN_BROWSER: String = "private_key_in_browser"

        /** Base URL of the documentation. */
        public const val DOCS_BASE_URL: String = "https://docs.frontmail.dev"

        /** Documentation URL of an error code, e.g. `…/reference/errors/#captcha-required`. */
        public fun docsUrlFor(code: String): String = "$DOCS_BASE_URL/reference/errors/#" + code.replace('_', '-')

        private val AUTH_403 = setOf("forbidden", "origin_not_allowed", "private_key_required", "private_key_in_browser")
    }
}
