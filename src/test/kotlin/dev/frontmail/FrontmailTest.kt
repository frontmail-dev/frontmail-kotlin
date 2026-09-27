@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.frontmail

import dev.frontmail.FakeTransport.Companion.ACCEPTED
import dev.frontmail.FakeTransport.Companion.error
import dev.frontmail.FakeTransport.Companion.ok
import dev.frontmail.FakeTransport.Step
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class FrontmailTest {
    private fun HttpRequest.json(): JsonObject = Json.parseToJsonElement(body!!).jsonObject

    // --- construction -----------------------------------------------------------------------

    @Test
    fun `rejects private keys immediately`() {
        val e = assertFailsWith<FrontmailException> { Frontmail("sk_live_123") }
        assertEquals("private_key_in_browser", e.code)
        assertNull(e.status)
        assertTrue(e.message!!.contains("pk_"))
        assertEquals("https://docs.frontmail.dev/reference/errors/#private-key-in-browser", e.docsUrl)
    }

    @Test
    fun `validates constructor arguments`() {
        assertFailsWith<IllegalArgumentException> { Frontmail(" ") }
        assertFailsWith<IllegalArgumentException> { Frontmail("pk_1", apiUrl = "ftp://x") }
        assertFailsWith<IllegalArgumentException> { Frontmail("pk_1", timeoutMillis = 0) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(retries = -1) }
    }

    @Test
    fun `trims trailing slashes and defaults the API URL`() {
        assertEquals("https://api.frontmail.dev", Frontmail("pk_1").apiUrl)
        assertEquals("http://10.0.2.2:3000", Frontmail("pk_1", apiUrl = "http://10.0.2.2:3000///").apiUrl)
    }

    @Test
    fun `version comes from gradle properties`() {
        assertEquals(System.getProperty("frontmail.version"), FrontmailVersion.CURRENT)
        assertEquals("frontmail-kotlin/" + FrontmailVersion.CURRENT, Frontmail.CLIENT_NAME)
    }

    // --- send -------------------------------------------------------------------------------

    @Test
    fun `send posts the documented body and headers`() = runTest {
        val t = FakeTransport(ok(ACCEPTED))
        val result = client(t).send(
            templateId = "tpl_contact",
            params = mapOf("name" to "Jan", "qty" to 2),
            serviceId = "svc_1",
            options = SendOptions(
                turnstileToken = "tok",
                turnstileKey = TurnstileKey.ORG,
                attachments = listOf(
                    Attachment.fromBytes("a.txt", "text/plain", "hi".toByteArray()),
                    Attachment.Upload("upl_1"),
                ),
            ),
        )
        assertEquals("msg_1", result.messageId)
        assertEquals(AcceptedStatus.QUEUED, result.status)
        assertEquals("st_1", result.statusToken)

        val req = t.requests.single()
        assertEquals("POST", req.method)
        assertEquals("https://api.example.com/v1/send", req.url)
        assertEquals("pk_test", req.headers["X-Frontmail-Public-Key"])
        assertEquals("application/json", req.headers["Content-Type"])
        assertEquals("application/json", req.headers["Accept"])
        assertEquals("frontmail-kotlin/${FrontmailVersion.CURRENT}", req.headers["X-Frontmail-Client"])
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(req.headers["Idempotency-Key"]!!))
        assertFalse(req.headers.containsKey("Authorization"))
        assertEquals(
            Json.parseToJsonElement(
                """{"service_id":"svc_1","template_id":"tpl_contact","user_id":"pk_test",
                   "template_params":{"name":"Jan","qty":2},"turnstile_token":"tok","turnstile_key":"org",
                   "attachments":[{"filename":"a.txt","content_type":"text/plain","content_base64":"aGk="},{"upload_id":"upl_1"}]}""",
            ),
            req.json(),
        )
    }

    @Test
    fun `send omits null and empty optional fields`() = runTest {
        val t = FakeTransport(ok(ACCEPTED), ok(ACCEPTED))
        val c = client(t)
        c.send("tpl_1")
        c.send("tpl_1", serviceId = "")
        for (req in t.requests) {
            assertEquals(setOf("template_id", "user_id", "template_params"), req.json().keys)
            assertEquals(JsonObject(emptyMap()), req.json()["template_params"])
        }
    }

    @Test
    fun `send passes locale and returns the resolved locale`() = runTest {
        val t = FakeTransport(
            ok("""{"message_id":"msg_1","status":"queued","status_token":"st_1","locale":"de"}"""),
            ok(ACCEPTED),
        )
        val c = client(t)
        val a = c.send("tpl_1", options = SendOptions(locale = "de-AT"))
        val b = c.send("tpl_1", options = SendOptions(locale = ""))
        assertEquals(JsonPrimitive("de-AT"), t.requests[0].json()["locale"])
        assertFalse(t.requests[1].json().containsKey("locale"))
        assertEquals("de", a.locale)
        assertNull(b.locale)
    }

    @Test
    fun `SendOptions and SendResult keep their 0_1 constructors`() {
        // Hidden from Kotlin callers, but still in the bytecode for code compiled against 0.1.x.
        val ctors = SendOptions::class.java.constructors.map { it.parameterTypes.toList() }
        assertTrue(ctors.contains(listOf(String::class.java, String::class.java, TurnstileKey::class.java, List::class.java)))
        // …including the synthetic defaults constructor used by `SendOptions(turnstileToken = …)`.
        val names = SendOptions::class.java.declaredConstructors.map { c -> c.parameterTypes.joinToString(",") { it.simpleName } }
        assertTrue(names.contains("String,String,TurnstileKey,List,int,DefaultConstructorMarker"), names.toString())
        val results = SendResult::class.java.constructors.map { it.parameterTypes.toList() }
        assertTrue(results.contains(listOf(String::class.java, AcceptedStatus::class.java, String::class.java)))
        assertNull(SendOptions(turnstileToken = "t").locale)
    }

    @Test
    fun `converts nested params to JSON`() = runTest {
        val t = FakeTransport(ok(ACCEPTED))
        client(t).send(
            "tpl_1",
            mapOf(
                "s" to "x",
                "i" to 1,
                "l" to 2L,
                "d" to 1.5,
                "b" to true,
                "n" to null,
                "list" to listOf(mapOf("a" to 1), "b"),
                "arr" to arrayOf<Any>(1, "two"),
                "ints" to intArrayOf(3, 4),
                "json" to buildJsonObject { put("k", "v") },
                "big" to java.math.BigDecimal("12.50"),
            ),
        )
        assertEquals(
            Json.parseToJsonElement(
                """{"s":"x","i":1,"l":2,"d":1.5,"b":true,"n":null,"list":[{"a":1},"b"],"arr":[1,"two"],"ints":[3,4],"json":{"k":"v"},"big":12.50}""",
            ),
            t.requests.single().json()["template_params"],
        )
    }

    @Test
    fun `rejects unsupported param values`() = runTest {
        val c = client(FakeTransport())
        val e = assertFailsWith<IllegalArgumentException> { c.send("tpl_1", mapOf("x" to mapOf("y" to Any()))) }
        assertTrue(e.message!!.contains("params.x.y"))
        assertFailsWith<IllegalArgumentException> { c.send("tpl_1", mapOf("x" to Double.NaN)) }
        assertFailsWith<IllegalArgumentException> { c.send("tpl_1", mapOf("x" to mapOf(1 to "a"))) }
    }

    @Test
    fun `accepts a JsonObject`() = runTest {
        val t = FakeTransport(ok("""{"message_id":"msg_2","status":"held","status_token":"st"}"""))
        val params = buildJsonObject { put("name", "Jan") }
        val r = client(t).send("tpl_1", params)
        assertEquals(AcceptedStatus.HELD, r.status)
        assertEquals(params, t.requests.single().json()["template_params"])
    }

    @Test
    fun `unknown accepted status falls back to UNKNOWN`() = runTest {
        val r = client(FakeTransport(ok("""{"message_id":"m","status":"later","status_token":"t"}"""))).send("tpl_1")
        assertEquals(AcceptedStatus.UNKNOWN, r.status)
    }

    @Test
    fun `invalid success bodies are invalid_response`() = runTest {
        for (body in listOf("not json", "", "[]", """{"status":"queued"}""")) {
            val e = assertFailsWith<FrontmailException> { client(FakeTransport(ok(body))).send("tpl_1") }
            assertEquals("invalid_response", e.code)
        }
    }

    // --- idempotency & retries --------------------------------------------------------------

    @Test
    fun `reuses the idempotency key across retries and generates a new one per send`() = runTest {
        val t = FakeTransport(error(503, "service_unavailable"), Step.Fail(IOException("reset")), ok(ACCEPTED), ok(ACCEPTED))
        val c = client(t)
        c.send("tpl_1")
        c.send("tpl_1")
        val keys = t.requests.map { it.headers["Idempotency-Key"] }
        assertEquals(4, keys.size)
        assertEquals(1, keys.take(3).toSet().size)
        assertNotEquals(keys[0], keys[3])
        // The body is identical on every attempt.
        assertEquals(1, t.requests.take(3).map { it.body }.toSet().size)
    }

    @Test
    fun `uses an explicit idempotency key`() = runTest {
        val t = FakeTransport(ok(ACCEPTED))
        client(t).send("tpl_1", options = SendOptions(idempotencyKey = "order-42"))
        assertEquals("order-42", t.requests.single().headers["Idempotency-Key"])
    }

    @Test
    fun `retries 5xx with full-jitter exponential backoff`() = runTest {
        val t = FakeTransport(error(500, "internal_error"), error(502, "provider_error"), error(503, "service_unavailable"), ok(ACCEPTED))
        val start = currentTime
        client(t, random = { 0.5 }).send("tpl_1")
        assertEquals(4, t.requests.size)
        // 0.5 * 300 + 0.5 * 600 + 0.5 * 1200
        assertEquals(1050, currentTime - start)
    }

    @Test
    fun `backoff is capped by maxDelayMillis`() {
        assertEquals(0, backoffDelay(0, 300, 10_000, 0.0))
        assertEquals(299, backoffDelay(0, 300, 10_000, 0.999))
        assertEquals(9_990, backoffDelay(10, 300, 10_000, 0.999))
        assertEquals(9_990, backoffDelay(60, 300, 10_000, 0.999))
    }

    @Test
    fun `throws the last error when retries are exhausted`() = runTest {
        val t = FakeTransport(*Array(4) { error(503, "service_unavailable") })
        val e = assertFailsWith<FrontmailException> { client(t).send("tpl_1") }
        assertEquals("service_unavailable", e.code)
        assertEquals(503, e.status)
        assertEquals(4, t.requests.size)
    }

    @Test
    fun `RetryPolicy NONE disables retries`() = runTest {
        val t = FakeTransport(error(503, "service_unavailable"))
        assertFailsWith<FrontmailException> { client(t, retry = RetryPolicy.NONE).send("tpl_1") }
        assertEquals(1, t.requests.size)
    }

    @Test
    fun `never retries 4xx other than 429`() = runTest {
        for (status in listOf(400, 401, 402, 403, 404, 409, 413, 422, 451)) {
            val t = FakeTransport(error(status, "some_error"))
            val e = assertFailsWith<FrontmailException> { client(t).send("tpl_1") }
            assertEquals(status, e.status)
            assertEquals(1, t.requests.size, "status $status")
        }
    }

    @Test
    fun `honors Retry-After seconds on 429`() = runTest {
        val t = FakeTransport(error(429, "rate_limited", mapOf("retry-after" to "7")), ok(ACCEPTED))
        val start = currentTime
        client(t).send("tpl_1")
        assertEquals(7_000, currentTime - start)
        assertEquals(2, t.requests.size)
    }

    @Test
    fun `429 without Retry-After uses backoff`() = runTest {
        val t = FakeTransport(error(429, "rate_limited"), ok(ACCEPTED))
        val start = currentTime
        client(t, random = { 0.5 }).send("tpl_1")
        assertEquals(150, currentTime - start)
    }

    @Test
    fun `does not retry when Retry-After is above 60 seconds`() = runTest {
        val t = FakeTransport(error(429, "rate_limited", mapOf("Retry-After" to "120")))
        val e = assertFailsWith<FrontmailException> { client(t).send("tpl_1") }
        assertEquals(1, t.requests.size)
        assertTrue(e.isRateLimit)
        assertEquals(120.seconds, e.retryAfter)
    }

    @Test
    fun `parses Retry-After HTTP dates`() {
        val now = 1_800_000_000_000L
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
        assertEquals(30.0, parseRetryAfter(fmt.format(Date(now + 30_000)), now))
        assertEquals(0.0, parseRetryAfter(fmt.format(Date(now - 30_000)), now))
        assertEquals(1.5, parseRetryAfter("1.5", now))
        assertEquals(0.0, parseRetryAfter("-3", now))
        assertNull(parseRetryAfter("soon", now))
        assertNull(parseRetryAfter(null, now))
    }

    @Test
    fun `network errors are retried and reported as network_error`() = runTest {
        val t = FakeTransport(*Array(4) { Step.Fail(IOException("offline")) })
        val e = assertFailsWith<FrontmailException> { client(t).send("tpl_1") }
        assertEquals("network_error", e.code)
        assertTrue(e.isNetwork)
        assertNull(e.status)
        assertIs<IOException>(e.cause)
        assertEquals(4, t.requests.size)
    }

    @Test
    fun `socket timeouts are reported as timeout`() = runTest {
        val t = FakeTransport(Step.Fail(SocketTimeoutException("read timed out")))
        val e = assertFailsWith<FrontmailException> { client(t, retry = RetryPolicy.NONE).send("tpl_1") }
        assertEquals("timeout", e.code)
        assertTrue(e.isNetwork)
    }

    @Test
    fun `applies the per-attempt timeout and retries`() = runTest {
        val t = FakeTransport(Step.Hang(5_000), ok(ACCEPTED))
        val start = currentTime
        client(t, timeoutMillis = 1_000, random = { 0.0 }).send("tpl_1")
        assertEquals(2, t.requests.size)
        assertEquals(1_000, currentTime - start)
        assertEquals(1_000, t.requests[0].timeoutMillis)

        val t2 = FakeTransport(Step.Hang(5_000))
        val e = assertFailsWith<FrontmailException> { client(t2, retry = RetryPolicy.NONE, timeoutMillis = 1_000).send("tpl_1") }
        assertEquals("timeout", e.code)
    }

    @Test
    fun `cancellation stops immediately with aborted`() = runTest {
        val t = FakeTransport(Step.Hang(10_000), ok(ACCEPTED))
        val job = async { client(t).send("tpl_1") }
        advanceTimeBy(500)
        job.cancel()
        val e = assertFailsWith<CancellationException> { job.await() }
        assertEquals(1, t.requests.size)
        assertTrue(job.isCancelled)
        // The cancellation cause chain carries the aborted error (when the SDK was the one to rethrow).
        val chain = generateSequence<Throwable>(e) { it.cause }.toList()
        assertTrue(chain.any { it is CancellationException })
    }

    @Test
    fun `cancellation during a retry delay stops retrying`() = runTest {
        val t = FakeTransport(error(503, "service_unavailable"), ok(ACCEPTED))
        val job = async { client(t, random = { 0.99 }).send("tpl_1") }
        advanceTimeBy(100)
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
        assertEquals(1, t.requests.size)
    }

    @Test
    fun `transport cancellation is rethrown as a CancellationException carrying aborted`() = runTest {
        val t = FakeTransport(Step.Fail(CancellationException("cancelled")))
        val e = assertFailsWith<CancellationException> { client(t).send("tpl_1") }
        val cause = assertIs<FrontmailException>(e.cause)
        assertEquals("aborted", cause.code)
    }

    // --- errors -----------------------------------------------------------------------------

    @Test
    fun `parses API error bodies`() = runTest {
        val t = FakeTransport(
            error(
                422,
                "invalid_template_params",
                extra = ""","docs_url":"https://docs.example/x","details":{"reason":"content_locked","fields":["a"]}""",
            ),
        )
        val e = assertFailsWith<FrontmailException> { client(t).send("tpl_1") }
        assertEquals("invalid_template_params", e.code)
        assertEquals("msg invalid_template_params", e.message)
        assertEquals(422, e.status)
        assertEquals("https://docs.example/x", e.docsUrl)
        assertEquals("content_locked", e.reason)
        assertEquals(JsonPrimitive("a"), (e.details as JsonObject)["fields"]!!.let { (it as kotlinx.serialization.json.JsonArray)[0] })
        assertTrue(e.isValidation)
        assertFalse(e.isAuth || e.isRateLimit || e.isNetwork || e.isInsufficientCredits)
    }

    @Test
    fun `falls back to generic codes and docs URLs`() {
        val e = Frontmail.errorFromResponse(404, null, null)
        assertEquals("bad_request", e.code)
        assertEquals("Request failed with status 404", e.message)
        assertEquals("https://docs.frontmail.dev/reference/errors/#bad-request", e.docsUrl)
        assertEquals("internal_error", Frontmail.errorFromResponse(502, Json.parseToJsonElement("[]"), null).code)
        val nullDetails = Frontmail.errorFromResponse(400, Json.parseToJsonElement("""{"error":{"code":"x","details":null}}"""), null)
        assertNull(nullDetails.details)
        assertNull(nullDetails.reason)
    }

    @Test
    fun `classifies errors like sdk-core`() {
        fun e(status: Int, code: String) = Frontmail.errorFromResponse(status, Json.parseToJsonElement("""{"error":{"code":"$code"}}"""), null)
        assertTrue(e(401, "invalid_public_key").isAuth)
        for (code in listOf("forbidden", "origin_not_allowed", "private_key_required", "private_key_in_browser")) assertTrue(e(403, code).isAuth, code)
        assertFalse(e(403, "captcha_required").isAuth)
        assertTrue(e(400, "invalid_body").isValidation)
        assertTrue(e(422, "invalid_recipient").isValidation)
        assertTrue(e(429, "rate_limited").isRateLimit)
        assertTrue(e(402, "insufficient_credits").isInsufficientCredits)
        assertFalse(e(500, "internal_error").isNetwork)
        assertEquals(
            "origin_not_allowed",
            Frontmail.errorFromResponse(403, Json.parseToJsonElement("""{"error":{"code":"origin_not_allowed","details":{"reason":"no_origin"}}}"""), null)
                .also { assertEquals("no_origin", it.reason) }.code,
        )
    }

    // --- getStatus --------------------------------------------------------------------------

    @Test
    fun `getStatus sends the token in a header and parses the response`() = runTest {
        val t = FakeTransport(
            ok(
                """{"message_id":"msg/1 x","status":"delivered","created_at":"2026-09-01T10:00:00Z","sent_at":null,
                   "template_id":"tpl_1","service_id":"svc_1","events":[{"type":"accepted","at":"2026-09-01T10:00:00Z","extra":1},"junk"]}""",
                status = 200,
            ),
        )
        val s = client(t).getStatus("msg/1 x?é", "st_secret")
        val req = t.requests.single()
        assertEquals("GET", req.method)
        assertEquals("https://api.example.com/v1/messages/msg%2F1%20x%3F%C3%A9", req.url)
        assertFalse(req.url.contains("st_secret"))
        assertEquals("st_secret", req.headers["X-Frontmail-Status-Token"])
        assertEquals("pk_test", req.headers["X-Frontmail-Public-Key"])
        assertNull(req.body)
        assertFalse(req.headers.containsKey("Content-Type"))

        assertEquals("msg/1 x", s.messageId)
        assertEquals(MessageStatus.DELIVERED, s.status)
        assertEquals("2026-09-01T10:00:00Z", s.createdAt)
        assertNull(s.sentAt)
        assertEquals("tpl_1", s.templateId)
        assertEquals("svc_1", s.serviceId)
        assertEquals(1, s.events.size)
        assertEquals("accepted", s.events[0].type)
        assertEquals(1, s.events[0].raw["extra"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `getStatus maps unknown statuses and validates the body`() = runTest {
        val s = client(FakeTransport(ok("""{"message_id":"m","status":"teleported","created_at":"x"}""", 200))).getStatus("m", "t")
        assertEquals(MessageStatus.UNKNOWN, s.status)
        assertEquals("teleported", s.raw["status"]!!.jsonPrimitive.content)
        assertEquals(emptyList(), s.events)
        for (s2 in MessageStatus.entries.filter { it != MessageStatus.UNKNOWN }) assertEquals(s2, MessageStatus.from(s2.value))

        val e = assertFailsWith<FrontmailException> { client(FakeTransport(ok("""{"status":"sent"}""", 200))).getStatus("m", "t") }
        assertEquals("invalid_response", e.code)
        val nf = assertFailsWith<FrontmailException> { client(FakeTransport(error(404, "message_not_found"))).getStatus("m", "t") }
        assertEquals("message_not_found", nf.code)
    }

    @Test
    fun `getStatus accepts a SendResult`() = runTest {
        val t = FakeTransport(ok(ACCEPTED), ok("""{"message_id":"msg_1","status":"sent","created_at":"x"}""", 200))
        val c = client(t)
        val s = c.getStatus(c.send("tpl_1"))
        assertEquals(MessageStatus.SENT, s.status)
        assertEquals("https://api.example.com/v1/messages/msg_1", t.requests[1].url)
        assertEquals("st_1", t.requests[1].headers["X-Frontmail-Status-Token"])
    }

    // --- public config ----------------------------------------------------------------------

    @Test
    fun `publicConfig is cached per API URL and failures are not cached`() = runTest {
        Frontmail.resetPublicConfigCache()
        val body = """{"turnstile":{"mobileSiteKey":"0x4AAA","mobileBaseUrl":"https://mobile.frontmail.dev"}}"""
        val t = FakeTransport(
            Step.Fail(IOException("offline")),
            ok(body, 200),
            ok("""{"turnstile":{"mobileSiteKey":null,"mobileBaseUrl":""}}""", 200),
        )
        val e = assertFailsWith<FrontmailException> { Frontmail.publicConfig("https://a.example", t) }
        assertEquals("network_error", e.code)

        val c1 = Frontmail.publicConfig("https://a.example", t)
        val c2 = Frontmail.publicConfig("https://a.example//", t)
        assertEquals("0x4AAA", c1.turnstile.mobileSiteKey)
        assertEquals("https://mobile.frontmail.dev", c1.turnstile.mobileBaseUrl)
        assertTrue(c1 === c2)

        val other = Frontmail.publicConfig("https://b.example", t)
        assertNull(other.turnstile.mobileSiteKey)
        assertNull(other.turnstile.mobileBaseUrl)

        assertEquals(listOf("https://a.example/v1/public-config", "https://a.example/v1/public-config", "https://b.example/v1/public-config"), t.requests.map { it.url })
        assertEquals("GET", t.requests[0].method)
        assertFalse(t.requests[0].headers.containsKey("X-Frontmail-Public-Key"))
        Frontmail.resetPublicConfigCache()
    }

    @Test
    fun `publicConfig validates the response`() = runTest {
        Frontmail.resetPublicConfigCache()
        val t = FakeTransport(ok("{}", 200), error(500, "internal_error"))
        assertEquals("invalid_response", assertFailsWith<FrontmailException> { Frontmail.publicConfig("https://c.example", t) }.code)
        assertEquals("internal_error", assertFailsWith<FrontmailException> { Frontmail.publicConfig("https://c.example", t) }.code)
        Frontmail.resetPublicConfigCache()
    }

    // --- helpers ----------------------------------------------------------------------------

    @Test
    fun `encodes like encodeURIComponent`() {
        assertEquals("aZ09-_.!~*'()", encodeUriComponent("aZ09-_.!~*'()"))
        assertEquals("%20%2F%3F%23%26%3D%2B%25%C3%A9%F0%9F%98%80", encodeUriComponent(" /?#&=+%é😀"))
    }

    @Test
    fun `base64 matches java util Base64`() {
        val rnd = kotlin.random.Random(1)
        for (n in 0..64) {
            val bytes = rnd.nextBytes(n)
            assertEquals(java.util.Base64.getEncoder().encodeToString(bytes), Base64Encoder.encode(bytes))
        }
    }

    @Test
    fun `null JSON values are preserved`() {
        assertEquals(JsonNull, toJsonElement(null))
    }
}
