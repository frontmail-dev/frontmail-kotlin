package dev.frontmail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * Cloudflare Turnstile (CAPTCHA) helpers for a WebView. Pure functions – the WebView wiring lives
 * in your app (see the README for a complete Android snippet):
 *
 * 1. Resolve the widget: [sharedWidget] (Frontmail's shared mobile key, no setup) or
 *    [TurnstileWidget.own] (your own site key).
 * 2. Load [html] with `loadDataWithBaseURL(widget.baseUrl, html, "text/html", "utf-8", null)` and
 *    expose a bridge object named [ANDROID_BRIDGE_NAME] with a `postMessage(String)` method.
 * 3. Pass every bridge message to [parseMessage]; guard navigation with [navigationDecision].
 * 4. Send with `SendOptions(turnstileToken = token, turnstileKey = widget.turnstileKey)`.
 */
public object Turnstile {
    /** `source` field of every message the page posts. */
    public const val MESSAGE_SOURCE: String = "frontmail-turnstile"

    /** Name of the Android JavaScript bridge (`addJavascriptInterface(bridge, ANDROID_BRIDGE_NAME)`). */
    public const val ANDROID_BRIDGE_NAME: String = "FrontmailTurnstile"

    /** Name of the iOS `WKScriptMessageHandler` (`window.webkit.messageHandlers.frontmailTurnstile`). */
    public const val IOS_MESSAGE_HANDLER_NAME: String = "frontmailTurnstile"

    /** Origin of the Turnstile script and challenge iframe. */
    public const val TURNSTILE_ORIGIN: String = "https://challenges.cloudflare.com"

    /** Script for `evaluateJavascript` that resets the widget and requests a new (single-use) token. */
    public const val RESET_SCRIPT: String = "window.turnstile&&window.turnstile.reset(window.fmWidget);true;"

    /** Widget theme. */
    public enum class Theme(public val value: String) { AUTO("auto"), LIGHT("light"), DARK("dark") }

    /** Widget size; suggested WebView size: normal 300 × 70 dp, compact 150 × 140 dp, flexible full width × 70 dp. */
    public enum class Size(public val value: String) { NORMAL("normal"), COMPACT("compact"), FLEXIBLE("flexible") }

    /** What to do with a navigation request of the widget WebView. */
    public enum class NavigationDecision {
        /** Let the WebView load it (the inline page, the Cloudflare challenge, `about:` frames). */
        ALLOW,

        /** Cancel it and open the URL in the system browser (links in the widget: privacy, terms, help). */
        OPEN_EXTERNALLY,

        /** Cancel it. */
        BLOCK,
    }

    private val MESSAGE_TYPES = setOf("token", "expired", "error")
    private const val MAX_TOKEN_LENGTH = 4096
    private const val MAX_CODE_LENGTH = 64
    private val ORIGIN_RE = Regex("^([a-z][a-z0-9+.-]*://[^/?#\\\\]*)", RegexOption.IGNORE_CASE)
    private val HTTPS_RE = Regex("^https://", RegexOption.IGNORE_CASE)

    /**
     * HTML page that renders the widget and posts `{"source":"frontmail-turnstile","type":…}` JSON
     * strings to the native bridge (Android `window.FrontmailTurnstile.postMessage`, iOS
     * `window.webkit.messageHandlers.frontmailTurnstile.postMessage` – whichever exists).
     *
     * @param action Turnstile `action` (analytics label), `[a-z0-9_-]{0,32}`.
     * @param language widget language, e.g. `cs`; `null` = `auto`.
     */
    public fun html(
        siteKey: String,
        theme: Theme = Theme.AUTO,
        size: Size = Size.NORMAL,
        action: String? = null,
        language: String? = null,
    ): String {
        val options = buildJsonObject {
            put("sitekey", siteKey)
            put("theme", theme.value)
            put("size", size.value)
            put("language", language ?: "auto")
            if (!action.isNullOrEmpty()) put("action", action)
        }
        val source = js(JsonPrimitive(MESSAGE_SOURCE))
        val android = ANDROID_BRIDGE_NAME
        val ios = IOS_MESSAGE_HANDLER_NAME
        return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<style>html,body{margin:0;padding:0;background:transparent}#w{display:flex;justify-content:center}</style>
<script>
function fmPost(m){m.source=$source;var s=JSON.stringify(m),w=window;if(w.$android&&w.$android.postMessage)w.$android.postMessage(s);else if(w.webkit&&w.webkit.messageHandlers&&w.webkit.messageHandlers.$ios)w.webkit.messageHandlers.$ios.postMessage(s);}
function fmRender(){var o=${js(options)};
o.callback=function(t){fmPost({type:'token',token:t});};
o['error-callback']=function(c){fmPost({type:'error',code:String(c==null?'':c)});return true;};
o['expired-callback']=function(){fmPost({type:'expired'});};
window.fmWidget=turnstile.render('#w',o);}
</script>
<script src="https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit&onload=fmRender" async defer onerror="fmPost({type:'error',code:'script_load_failed'})"></script>
</head><body><div id="w"></div></body></html>"""
    }

    /** JSON for an inline `<script>`: `<`, U+2028 and U+2029 escaped so values can't close the script tag. */
    internal fun js(value: JsonElement): String =
        value.toString()
            .replace("<", "\\u003c")
            .replace(" ", "\\u2028")
            .replace(" ", "\\u2029")

    /**
     * Parses a bridge message. Returns `null` unless it has exactly the shape the inline page posts
     * (so a message from anything else is ignored).
     */
    public fun parseMessage(data: String?): TurnstileMessage? {
        if (data == null) return null
        val m = try {
            Json.parseToJsonElement(data)
        } catch (_: Exception) {
            return null
        } as? JsonObject ?: return null
        val source = m["source"].asString() ?: return null
        val type = m["type"].asString() ?: return null
        if (source != MESSAGE_SOURCE || type !in MESSAGE_TYPES) return null
        val token = m["token"]
        val code = m["code"]
        if (type == "token") {
            val t = token.asString()
            if (t.isNullOrEmpty() || t.length > MAX_TOKEN_LENGTH) return null
        }
        if (code != null) {
            val c = code.asString()
            if (c == null || c.length > MAX_CODE_LENGTH) return null
        }
        return when (type) {
            "token" -> TurnstileMessage.Token(token.asString()!!)
            "expired" -> TurnstileMessage.Expired
            else -> TurnstileMessage.Error(code.asString())
        }
    }

    private fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** `scheme://host[:port]` of an absolute URL, lower-cased; `null` for anything else. */
    public fun originOf(url: String?): String? =
        url?.let { ORIGIN_RE.find(it)?.groupValues?.get(1)?.lowercase(Locale.ROOT) }

    /**
     * Navigation guard for the widget WebView: the top frame must stay on the inline page
     * ([baseUrl]); links clicked inside the widget open in the system browser instead of turning
     * the WebView – which holds the bridge – into a general-purpose browser.
     *
     * @param isTopFrame `false` for sub-frames (the Turnstile challenge runs in an iframe). On
     *   Android pass `request.isForMainFrame`.
     */
    public fun navigationDecision(url: String?, baseUrl: String, isTopFrame: Boolean = true): NavigationDecision {
        val u = url ?: ""
        if (!isTopFrame) return NavigationDecision.ALLOW
        if (u == "about:blank" || u.startsWith("about:srcdoc")) return NavigationDecision.ALLOW
        val origin = originOf(u)
        if (origin != null && (origin == originOf(baseUrl) || origin == TURNSTILE_ORIGIN)) return NavigationDecision.ALLOW
        return if (HTTPS_RE.containsMatchIn(u)) NavigationDecision.OPEN_EXTERNALLY else NavigationDecision.BLOCK
    }

    /**
     * `true` when the WebView may load [url] (see [navigationDecision]); a `false` result for an
     * `https://` URL should be opened in the system browser.
     */
    public fun isAllowedNavigation(url: String?, baseUrl: String, isTopFrame: Boolean = true): Boolean =
        navigationDecision(url, baseUrl, isTopFrame) == NavigationDecision.ALLOW

    /**
     * Frontmail's shared mobile widget of an API (site key and page URL from
     * [Frontmail.publicConfig], cached). Tokens are sent with [TurnstileKey.FRONTMAIL].
     *
     * @param baseUrl overrides the page URL (normally leave `null`).
     * @throws FrontmailException `captcha_failed` (`details.reason = "not_configured"`) when the API
     *   has no shared key, or the error of the config request.
     */
    public suspend fun sharedWidget(apiUrl: String = Frontmail.DEFAULT_API_URL, baseUrl: String? = null): TurnstileWidget =
        sharedWidget(Frontmail.publicConfig(apiUrl), baseUrl)

    internal fun sharedWidget(config: PublicConfig, baseUrl: String?): TurnstileWidget {
        val key = config.turnstile.mobileSiteKey
        val url = baseUrl ?: config.turnstile.mobileBaseUrl
        if (key == null || url == null) {
            throw FrontmailException(
                "captcha_failed",
                "This API has no shared mobile Turnstile key – use your own site key (TurnstileWidget.own).",
                details = buildJsonObject { put("reason", "not_configured") },
            )
        }
        return TurnstileWidget(key, url, TurnstileKey.FRONTMAIL)
    }
}

/** A message posted by the Turnstile page, see [Turnstile.parseMessage]. */
public sealed class TurnstileMessage {
    /** A fresh token – single use, valid for about 5 minutes. */
    public class Token(public val token: String) : TurnstileMessage() {
        override fun toString(): String = "TurnstileMessage.Token(${token.length} chars)"
    }

    /** The token expired; the widget refreshes itself – drop any stored token. */
    public object Expired : TurnstileMessage() {
        override fun toString(): String = "TurnstileMessage.Expired"
    }

    /** The widget failed (Turnstile error code, or `script_load_failed`). */
    public class Error(public val code: String?) : TurnstileMessage() {
        override fun toString(): String = "TurnstileMessage.Error($code)"
    }
}

/**
 * A resolved Turnstile widget: which site key to render, the page URL to load it with, and the
 * `turnstileKey` to send its tokens with.
 */
public class TurnstileWidget internal constructor(
    public val siteKey: String,
    /** URL for `loadDataWithBaseURL`; nothing is loaded from it. Its hostname must be allowed by the widget. */
    public val baseUrl: String,
    /** Pass as [SendOptions.turnstileKey]. */
    public val turnstileKey: TurnstileKey,
) {
    /** [Turnstile.html] for this widget. */
    public fun html(
        theme: Turnstile.Theme = Turnstile.Theme.AUTO,
        size: Turnstile.Size = Turnstile.Size.NORMAL,
        action: String? = null,
        language: String? = null,
    ): String = Turnstile.html(siteKey, theme, size, action, language)

    /** [Turnstile.navigationDecision] against this widget's [baseUrl]. */
    public fun navigationDecision(url: String?, isTopFrame: Boolean = true): Turnstile.NavigationDecision =
        Turnstile.navigationDecision(url, baseUrl, isTopFrame)

    /** Origin (`scheme://host[:port]`) of [baseUrl]; only messages from it should reach the app. */
    public val origin: String? get() = Turnstile.originOf(baseUrl)

    override fun toString(): String = "TurnstileWidget(siteKey=$siteKey, baseUrl=$baseUrl, turnstileKey=$turnstileKey)"

    public companion object {
        /**
         * Your own Turnstile widget. [baseUrl] (e.g. `https://example.com`) must be one of the
         * widget's hostnames in the Cloudflare dashboard, otherwise Turnstile fails with 110200.
         * Tokens are sent with [TurnstileKey.ORG] and verified with the secret from the dashboard
         * (Security → Bot protection).
         */
        public fun own(siteKey: String, baseUrl: String): TurnstileWidget {
            require(siteKey.isNotEmpty()) { "siteKey must not be empty." }
            require(Turnstile.originOf(baseUrl) != null) { "baseUrl must be an absolute URL, e.g. https://example.com." }
            return TurnstileWidget(siteKey, baseUrl, TurnstileKey.ORG)
        }
    }
}
