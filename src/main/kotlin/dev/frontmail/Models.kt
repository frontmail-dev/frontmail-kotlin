package dev.frontmail

import kotlinx.serialization.json.JsonObject

/**
 * Retry policy for network errors, timeouts, 5xx and 429 responses (4xx other than 429 are never
 * retried). Delays use exponential backoff with full jitter; a `Retry-After` header wins, and a
 * `Retry-After` above 60 seconds is not retried at all.
 */
public class RetryPolicy(
    /** Retries after the first attempt. `0` disables retries. */
    public val retries: Int = 3,
    /** Base delay of the exponential backoff. */
    public val baseDelayMillis: Long = 300,
    /** Upper bound of a single backoff delay. */
    public val maxDelayMillis: Long = 10_000,
) {
    init {
        require(retries >= 0) { "retries must be >= 0" }
        require(baseDelayMillis >= 0 && maxDelayMillis >= 0) { "delays must be >= 0" }
    }

    override fun toString(): String = "RetryPolicy(retries=$retries, baseDelayMillis=$baseDelayMillis, maxDelayMillis=$maxDelayMillis)"

    public companion object {
        /** No retries: every error is thrown after the first attempt. */
        public val NONE: RetryPolicy = RetryPolicy(retries = 0)
    }
}

/** Which Turnstile secret verifies the token (`turnstile_key`). */
public enum class TurnstileKey(public val value: String) {
    /** Your own site key configured in the dashboard (Security → Bot protection). */
    ORG("org"),

    /** Frontmail's shared mobile widget (the default for requests from apps). */
    FRONTMAIL("frontmail"),
}

/** A dynamic attachment of a send (the template must allow dynamic attachments). */
public sealed class Attachment {
    /** File content sent inline as Base64. */
    public class Inline(
        public val filename: String,
        public val contentType: String,
        public val contentBase64: String,
    ) : Attachment() {
        override fun toString(): String = "Attachment.Inline(filename=$filename, contentType=$contentType, ${contentBase64.length} base64 chars)"
    }

    /** A file uploaded beforehand through `POST /v1/uploads`. */
    public class Upload(public val uploadId: String) : Attachment() {
        override fun toString(): String = "Attachment.Upload(uploadId=$uploadId)"
    }

    public companion object {
        /** Inline attachment from raw bytes (Base64-encoded for you). */
        public fun fromBytes(filename: String, contentType: String, bytes: ByteArray): Inline =
            Inline(filename, contentType, Base64Encoder.encode(bytes))
    }
}

/** Per-send options. */
public class SendOptions(
    /** Explicit idempotency key; by default a UUID is generated per `send` call and reused across its retries. */
    public val idempotencyKey: String? = null,
    /** Cloudflare Turnstile token (single use) when the template requires a CAPTCHA. */
    public val turnstileToken: String? = null,
    /**
     * Which secret verifies [turnstileToken]: `null` / [TurnstileKey.FRONTMAIL] = Frontmail's shared
     * mobile widget, [TurnstileKey.ORG] = your own site key. [TurnstileWidget.turnstileKey] holds the
     * right value for a widget.
     */
    public val turnstileKey: TurnstileKey? = null,
    public val attachments: List<Attachment> = emptyList(),
    /**
     * Preferred template language as a BCP 47 tag (`"de"`, `"pt-BR"`). The API picks the exact
     * variant, then one with the same base language (`de-AT` → `de`), otherwise the template's
     * default language – an unknown language is not an error. `null` sends the default language.
     */
    public val locale: String? = null,
) {
    /** Binary compatibility with 0.1.x (callers compiled before [locale] existed). */
    @Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
    public constructor(
        idempotencyKey: String? = null,
        turnstileToken: String? = null,
        turnstileKey: TurnstileKey? = null,
        attachments: List<Attachment> = emptyList(),
    ) : this(idempotencyKey, turnstileToken, turnstileKey, attachments, null)
}

/** Status of an accepted message. */
public enum class AcceptedStatus(public val value: String) {
    /** Accepted for delivery. */
    QUEUED("queued"),

    /** Accepted and waiting for credits (delivered once the organization has credits again). */
    HELD("held"),

    /** A status this SDK version doesn't know yet. */
    UNKNOWN("unknown"),
    ;

    internal companion object {
        fun from(value: String): AcceptedStatus = entries.firstOrNull { it != UNKNOWN && it.value == value } ?: UNKNOWN
    }
}

/** Result of [Frontmail.send]. */
public class SendResult(
    public val messageId: String,
    public val status: AcceptedStatus,
    /** Lets the app read the message status via [Frontmail.getStatus]. Treat it like a password. */
    public val statusToken: String,
    /** Language the template was rendered in (the resolved [SendOptions.locale] or the default). */
    public val locale: String? = null,
) {
    /** Binary compatibility with 0.1.x (callers compiled before [locale] existed). */
    @Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
    public constructor(messageId: String, status: AcceptedStatus, statusToken: String) : this(messageId, status, statusToken, null)

    override fun toString(): String = "SendResult(messageId=$messageId, status=$status, locale=$locale)"
}

/** Delivery status of a message. */
public enum class MessageStatus(public val value: String) {
    QUEUED("queued"),
    SENDING("sending"),
    SENT("sent"),
    DELIVERED("delivered"),
    BOUNCED("bounced"),
    COMPLAINED("complained"),
    FAILED("failed"),
    HELD("held"),
    HELD_SERVICE_ERROR("held_service_error"),
    DISCARDED("discarded"),

    /** A status this SDK version doesn't know yet; the raw value is in [MessageStatusResponse.raw]. */
    UNKNOWN("unknown"),
    ;

    internal companion object {
        fun from(value: String): MessageStatus = entries.firstOrNull { it != UNKNOWN && it.value == value } ?: UNKNOWN
    }
}

/** One entry of a message's event log. */
public class MessageEvent(
    /** Event type, e.g. `accepted`, `sent`, `delivered`. */
    public val type: String,
    /** ISO-8601 timestamp. */
    public val at: String,
    /** The raw event object (snake_case keys), including fields this SDK doesn't model. */
    public val raw: JsonObject,
) {
    override fun toString(): String = "MessageEvent(type=$type, at=$at)"
}

/** Response of [Frontmail.getStatus]. */
public class MessageStatusResponse(
    public val messageId: String,
    public val status: MessageStatus,
    /** ISO-8601 timestamp. */
    public val createdAt: String,
    public val sentAt: String?,
    public val templateId: String?,
    public val serviceId: String?,
    public val events: List<MessageEvent>,
    /** The raw response body (snake_case keys). */
    public val raw: JsonObject,
) {
    override fun toString(): String = "MessageStatusResponse(messageId=$messageId, status=$status, events=${events.size})"
}

/** Response of `GET /v1/public-config`. */
public class PublicConfig(public val turnstile: TurnstileConfig) {
    /** Frontmail's shared Turnstile widget for native apps. */
    public class TurnstileConfig(
        /** Site key of the shared widget; `null` when the API has none configured. */
        public val mobileSiteKey: String?,
        /** Page URL the shared widget runs on (`https://mobile.<domain>`); `null` when not configured. */
        public val mobileBaseUrl: String?,
    ) {
        override fun toString(): String = "PublicConfig.TurnstileConfig(mobileSiteKey=$mobileSiteKey, mobileBaseUrl=$mobileBaseUrl)"
    }

    override fun toString(): String = "PublicConfig(turnstile=$turnstile)"
}
