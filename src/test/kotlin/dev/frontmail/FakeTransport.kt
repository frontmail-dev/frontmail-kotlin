package dev.frontmail

import kotlinx.coroutines.delay

/** Scripted transport: each call pops the next step and records the request. */
internal class FakeTransport(vararg steps: Step) : HttpTransport {
    sealed class Step {
        class Respond(val status: Int, val body: String, val headers: Map<String, String> = emptyMap()) : Step()

        class Fail(val error: Exception) : Step()

        /** Suspends (virtual time) before responding – used for timeouts and cancellation. */
        class Hang(val millis: Long, val then: Step = Respond(200, "{}")) : Step()
    }

    private val queue = ArrayDeque(steps.toList())
    val requests = mutableListOf<HttpRequest>()

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        return run(queue.removeFirstOrNull() ?: error("No more scripted responses (request ${requests.size})"))
    }

    private suspend fun run(step: Step): HttpResponse =
        when (step) {
            is Step.Respond -> HttpResponse(step.status, step.headers, step.body)
            is Step.Fail -> throw step.error
            is Step.Hang -> {
                delay(step.millis)
                run(step.then)
            }
        }

    companion object {
        fun ok(body: String, status: Int = 202) = Step.Respond(status, body)

        fun error(status: Int, code: String, headers: Map<String, String> = emptyMap(), extra: String = "") =
            Step.Respond(status, """{"error":{"code":"$code","message":"msg $code"$extra}}""", headers)

        const val ACCEPTED = """{"message_id":"msg_1","status":"queued","status_token":"st_1"}"""
    }
}

internal fun client(
    transport: HttpTransport,
    retry: RetryPolicy = RetryPolicy(),
    publicKey: String = "pk_test",
    apiUrl: String = "https://api.example.com/",
    timeoutMillis: Long = 15_000,
    random: () -> Double = { 0.5 },
) = Frontmail(publicKey, apiUrl, retry, timeoutMillis, transport, random)
