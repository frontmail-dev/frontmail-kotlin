package dev.frontmail

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The real HttpURLConnection transport against a local JDK HTTP server (loopback only). */
class UrlConnectionTransportTest {
    private lateinit var server: HttpServer
    private val seen = CopyOnWriteArrayList<Triple<String, Map<String, String>, String>>()
    private val release = CountDownLatch(1)
    private val url get() = "http://127.0.0.1:${server.address.port}"

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val headers = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() }
            seen += Triple("${ex.requestMethod} ${ex.requestURI.rawPath}", headers, body)
            val (status, response, extra) = when (ex.requestURI.rawPath) {
                "/v1/send" -> Triple(202, """{"message_id":"msg_1","status":"queued","status_token":"st"}""", emptyMap())
                "/v1/messages/limited" -> Triple(429, """{"error":{"code":"rate_limited","message":"slow down"}}""", mapOf("Retry-After" to "90"))
                "/v1/messages/hang" -> {
                    release.await(10, TimeUnit.SECONDS)
                    Triple(200, "{}", emptyMap())
                }
                else -> Triple(404, """{"error":{"code":"message_not_found","message":"nope"}}""", emptyMap())
            }
            extra.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            val bytes = response.toByteArray()
            runCatching {
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
        }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
    }

    @AfterTest
    fun stop() {
        release.countDown()
        server.stop(0)
    }

    @Test
    fun `sends body and headers and reads success and error responses`() = runBlocking {
        val c = Frontmail("pk_local", apiUrl = url, retry = RetryPolicy.NONE)
        val r = c.send("tpl_1", mapOf("name" to "Jan"))
        assertEquals("msg_1", r.messageId)
        val (line, headers, body) = seen.single()
        assertEquals("POST /v1/send", line)
        assertEquals("pk_local", headers["x-frontmail-public-key"])
        assertEquals("application/json", headers["content-type"])
        assertEquals(Frontmail.CLIENT_NAME, headers["x-frontmail-client"])
        assertTrue(headers["idempotency-key"]!!.isNotEmpty())
        assertTrue(body.contains("\"template_params\":{\"name\":\"Jan\"}"))

        val nf = assertFailsWith<FrontmailException> { c.getStatus("missing", "tok") }
        assertEquals("message_not_found", nf.code)
        assertEquals(404, nf.status)
        assertEquals("tok", seen.last().second["x-frontmail-status-token"])

        val rl = assertFailsWith<FrontmailException> { c.getStatus("limited", "tok") }
        assertEquals("rate_limited", rl.code)
        assertEquals(90, rl.retryAfter!!.inWholeSeconds)
    }

    @Test
    fun `connection failures are network errors`() = runBlocking {
        val port = server.address.port
        server.stop(0)
        val c = Frontmail("pk_local", apiUrl = "http://127.0.0.1:$port", retry = RetryPolicy.NONE)
        val e = assertFailsWith<FrontmailException> { c.send("tpl_1") }
        assertEquals("network_error", e.code)
    }

    @Test
    fun `timeouts and cancellation do not wait for the server`() = runBlocking {
        val c = Frontmail("pk_local", apiUrl = url, retry = RetryPolicy.NONE, timeoutMillis = 300)
        val start = System.nanoTime()
        val e = assertFailsWith<FrontmailException> { c.getStatus("hang", "tok") }
        assertEquals("timeout", e.code)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000)

        val slow = Frontmail("pk_local", apiUrl = url, retry = RetryPolicy.NONE)
        val job = async { slow.getStatus("hang", "tok") }
        delay(200)
        val t0 = System.nanoTime()
        job.cancel()
        assertFailsWith<CancellationException> { withTimeout(5_000) { job.await() } }
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
    }
}
