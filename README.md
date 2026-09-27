# Frontmail Kotlin SDK

Send emails from Android and Kotlin/JVM apps through your [Frontmail](https://frontmail.dev)
templates with the **public key** – no backend of your own needed.

- Coroutines (`suspend` API), no Android dependency: a pure Kotlin/JVM library (Java 11 bytecode)
  that works on Android and on the JVM.
- Automatic retries (network errors, timeouts, `5xx`, `429` with `Retry-After`) that reuse one
  idempotency key, so a retry never sends an email twice.
- Message status, typed errors, and Cloudflare Turnstile helpers for a `WebView`.
- Dependencies: `kotlinx-coroutines-core` and `kotlinx-serialization-json` only.

Documentation: <https://docs.frontmail.dev/sdks/android/>

## Install

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.frontmail:frontmail:0.1.0")
}
```

Requirements: Kotlin 2.2+ in the app, Java 11+ bytecode target; on Android the `INTERNET`
permission:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

## Allow mobile apps

Native apps don't send an `Origin` or `Referer` header, so the API rejects their requests with
`403 origin_not_allowed` until you turn on **Security → Mobile apps → Allow mobile apps** in the
dashboard and click **Save**. The switch is required even when your list of allowed websites is
empty; requests from websites are still checked against that list.

## Send

```kotlin
import dev.frontmail.Frontmail
import dev.frontmail.FrontmailException

val frontmail = Frontmail("pk_4f2a…") // create once, reuse (thread-safe)

lifecycleScope.launch {
    try {
        val result = frontmail.send(
            templateId = "tpl_contact",
            params = mapOf(
                "email" to email,
                "message" to message,
                "items" to listOf(mapOf("title" to "A", "qty" to 1)),
            ),
            serviceId = "svc_01J9…", // optional: default = the template's service
        )
        // result.status: QUEUED (accepted) or HELD (accepted, waiting for credits)
        showThanks()
    } catch (e: FrontmailException) {
        showError(e)
    }
}
```

Params may contain `String`, numbers, `Boolean`, `null`, nested `Map<String, *>`, `List`, arrays
and `JsonElement`s (there's also an overload that takes a `JsonObject`). Anything else throws
`IllegalArgumentException`.

Options (`SendOptions`): `turnstileToken`, `turnstileKey`, `idempotencyKey` (default: a UUID per
`send` call, reused by its retries), `locale` and `attachments`.

`SendOptions(locale = "de")` picks a language variant of the template (BCP 47 tag). The API falls back
to the same base language (`de-AT` → `de`), then to the template's default language – an unknown
language is not an error (`Locale.getDefault().toLanguageTag()` gives a valid tag).
`SendResult.locale` is the language the email was rendered in.

```kotlin
val result = frontmail.send("tpl_contact", mapOf("name" to name), options = SendOptions(locale = "de"))
println(result.locale) // "de"
```

Attachments:

```kotlin
import dev.frontmail.Attachment
import dev.frontmail.SendOptions

frontmail.send(
    "tpl_job_application",
    mapOf("name" to name),
    options = SendOptions(
        attachments = listOf(
            Attachment.fromBytes("cv.pdf", "application/pdf", bytes), // sent as Base64
            Attachment.Upload("upl_…"),                                 // uploaded beforehand
        ),
    ),
)
```

Client options:

```kotlin
Frontmail(
    publicKey = "pk_…",
    apiUrl = Frontmail.DEFAULT_API_URL,        // https://api.frontmail.dev
    retry = RetryPolicy(retries = 3, baseDelayMillis = 300, maxDelayMillis = 10_000), // or RetryPolicy.NONE
    timeoutMillis = 15_000,                     // per attempt
)
```

Every request carries `X-Frontmail-Client: frontmail-kotlin/<version>`.

## Message status

```kotlin
val status = frontmail.getStatus(result)                        // or getStatus(messageId, statusToken)
status.status   // MessageStatus.SENT, DELIVERED, BOUNCED, … (UNKNOWN for values this version doesn't know)
status.events   // [MessageEvent(type = "accepted", at = "…"), …]
```

The status token travels in the `X-Frontmail-Status-Token` header, never in the URL. Treat it like
a password.

## Errors

Every failure is a `FrontmailException`:

| Property | Meaning |
|---|---|
| `code` | API error code (`captcha_required`, `origin_not_allowed`, …) or client code: `network_error`, `timeout`, `invalid_response`, `private_key_in_browser` |
| `message` | human-readable message |
| `status` | HTTP status, `null` for client errors |
| `docsUrl` | documentation of the code |
| `details` | extra data from the API as `JsonElement` (`reason` reads `details.reason`) |
| `retryAfter` | `Duration` from `Retry-After`, when present |
| `isAuth`, `isValidation`, `isRateLimit`, `isInsufficientCredits`, `isNetwork` | categories |

```kotlin
catch (e: FrontmailException) {
    when {
        e.isNetwork -> showOffline()                         // retried already
        e.code == "origin_not_allowed" -> log("Enable Security → Mobile apps")
        e.code == "captcha_required" || e.code == "captcha_failed" -> resetTurnstile()
        e.isRateLimit -> showTryLater(e.retryAfter)
        e.isValidation -> showInvalid(e.details)
        else -> showGenericError()
    }
}
```

Cancelling the coroutine stops the request immediately (the connection is closed, no further
retries). The cancellation propagates as a `CancellationException` – when the SDK rethrows it, its
`cause` is a `FrontmailException` with code `aborted`.

## Retries and idempotency

Network errors, timeouts, `5xx` and `429` are retried (default 3 retries, exponential backoff with
full jitter from 300 ms, at most 10 s per delay). A `Retry-After` header wins; above 60 seconds the
error is thrown instead. Other `4xx` errors are never retried. All attempts of one `send` share the
same `Idempotency-Key`, so the API sends the email once even when a response got lost.

## Turnstile (CAPTCHA)

Templates with a CAPTCHA need a Cloudflare Turnstile token. The SDK gives you the pieces for a
`WebView`; no extra dependency is needed.

**Frontmail's shared key (no setup):** `Turnstile.sharedWidget()` reads the shared mobile site key
from `GET /v1/public-config` (cached) and renders the widget as an inline page with the URL
`https://mobile.frontmail.dev` – nothing is loaded from that address. Tokens are verified with
Frontmail's shared mobile secret, so you don't need Turnstile keys of your own.

**Your own site key:** `TurnstileWidget.own(siteKey, baseUrl = "https://example.com")`. The
hostname of `baseUrl` must be one of your widget's hostnames in the Cloudflare dashboard (otherwise
Turnstile fails with `110200`), and the secret key must be set in **Security → Bot protection**.
Tokens are then sent with `turnstileKey = TurnstileKey.ORG`.

Complete Android example:

```kotlin
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.frontmail.Turnstile
import dev.frontmail.TurnstileMessage
import dev.frontmail.TurnstileWidget

/** Renders the widget into this WebView (300 × 70 dp for the normal size). */
@SuppressLint("SetJavaScriptEnabled")
fun WebView.showTurnstile(
    widget: TurnstileWidget,
    onToken: (String) -> Unit,
    onExpire: () -> Unit = {},
    onError: (String?) -> Unit = {},
) {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.javaScriptCanOpenWindowsAutomatically = false
    settings.setSupportMultipleWindows(false)
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    setBackgroundColor(Color.TRANSPARENT)

    val main = Handler(Looper.getMainLooper())
    addJavascriptInterface(
        object {
            // Called on a background thread – hop to the main thread. `String?`: any frame can call it
            // with `null`, and a non-null Kotlin parameter would throw on the bridge thread.
            @JavascriptInterface
            fun postMessage(data: String?) {
                main.post {
                    when (val m = Turnstile.parseMessage(data)) {
                        is TurnstileMessage.Token -> onToken(m.token)
                        TurnstileMessage.Expired -> onExpire()
                        is TurnstileMessage.Error -> onError(m.code)
                        null -> Unit // not from the widget page – ignore
                    }
                }
            }
        },
        Turnstile.ANDROID_BRIDGE_NAME, // "FrontmailTurnstile"
    )

    // Keep the WebView on the widget: links inside it open in the browser.
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            when (widget.navigationDecision(request.url.toString(), request.isForMainFrame)) {
                Turnstile.NavigationDecision.ALLOW -> false
                Turnstile.NavigationDecision.OPEN_EXTERNALLY -> {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                    true
                }
                Turnstile.NavigationDecision.BLOCK -> true
            }
    }

    loadDataWithBaseURL(widget.baseUrl, widget.html(), "text/html", "utf-8", null)
}

/** Requests a new token (tokens are single use). */
fun WebView.resetTurnstile() = evaluateJavascript(Turnstile.RESET_SCRIPT, null)
```

Use it from an activity or fragment:

```kotlin
private var token: String? = null
private lateinit var widget: TurnstileWidget

lifecycleScope.launch {
    widget = Turnstile.sharedWidget() // or TurnstileWidget.own("0x4AAA…", "https://example.com")
    binding.turnstile.showTurnstile(
        widget,
        onToken = { token = it; binding.send.isEnabled = true },
        onExpire = { token = null; binding.send.isEnabled = false },
        onError = { code -> Log.w("Turnstile", "error $code") },
    )
}

binding.send.setOnClickListener {
    lifecycleScope.launch {
        try {
            frontmail.send(
                "tpl_contact",
                mapOf("email" to email, "message" to message),
                options = SendOptions(turnstileToken = token, turnstileKey = widget.turnstileKey),
            )
        } catch (e: FrontmailException) {
            showError(e)
        } finally {
            // Tokens are single use – get a fresh one for the next attempt.
            token = null
            binding.send.isEnabled = false
            binding.turnstile.resetTurnstile()
        }
    }
}
```

In Jetpack Compose, create the `WebView` in an `AndroidView(factory = { WebView(it).apply { showTurnstile(…) } })`.

Notes:

- `addJavascriptInterface` exposes the bridge to every frame of the WebView. The navigation guard
  keeps the WebView on your page and Cloudflare's challenge, and `parseMessage` accepts only the
  exact messages the page posts. With `androidx.webkit` you can scope the bridge to the page origin
  instead: `WebViewCompat.addWebMessageListener(webView, Turnstile.ANDROID_BRIDGE_NAME, setOf(widget.origin!!)) { _, message, _, _, _ -> … Turnstile.parseMessage(message.data) … }`
  (when `WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)`); the page works
  with both.
- R8 keeps `@JavascriptInterface` methods with the default Android ProGuard files. The SDK itself
  uses no reflection and needs no rules.
- The page also posts to `window.webkit.messageHandlers.frontmailTurnstile` (iOS), so the same
  HTML works in a `WKWebView`.

## Dynamic recipients (content lock)

If a template's To/CC/BCC use params (e.g. an auto-reply to the address from the form), a
public-key send is **content-locked**: the template must require a CAPTCHA, From / From name /
Reply-To must be fixed, params can't carry HTML, strings are limited to 500 characters (URLs 2048),
links must point to allowed domains and each recipient gets at most 5 emails per day by default.
Violations fail with `403 dynamic_recipient_requires_captcha`, `403 dynamic_field_not_allowed`,
`403 link_not_allowed`, `422 invalid_template_params` (`details.reason = "content_locked"`) or
`429 rate_limited`. See <https://docs.frontmail.dev/security/link-allowlist/>.

## Security

- Only ever put the **public** key (`pk_…`) into an app. Anything in an app can be extracted, so
  the SDK refuses private keys (`sk_…`) with `private_key_in_browser`.
- With **Allow mobile apps** on, anyone who knows your public key can send requests without an
  `Origin`. Protect the templates the app uses with Turnstile, keep the per-IP rate limit on and
  use the block list.

## Development

```sh
./gradlew build                 # compile, tests, binary-compatibility check (apiCheck)
./gradlew apiDump               # after an intentional public API change
./gradlew publishToMavenLocal   # unsigned local publish (signing runs only with a key)
```

The version lives in `gradle.properties` (`VERSION_NAME`) and is compiled into
`FrontmailVersion.CURRENT`. Releases are published to Maven Central with
`./gradlew publishToMavenCentral` and the `ORG_GRADLE_PROJECT_mavenCentralUsername`,
`ORG_GRADLE_PROJECT_mavenCentralPassword`, `ORG_GRADLE_PROJECT_signingInMemoryKey`
(+ `…KeyId`, `…KeyPassword`) environment variables.

## License

MIT – see [LICENSE](LICENSE).
