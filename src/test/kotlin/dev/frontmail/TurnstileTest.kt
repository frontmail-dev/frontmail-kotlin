package dev.frontmail

import dev.frontmail.FakeTransport.Companion.ok
import dev.frontmail.Turnstile.NavigationDecision.ALLOW
import dev.frontmail.Turnstile.NavigationDecision.BLOCK
import dev.frontmail.Turnstile.NavigationDecision.OPEN_EXTERNALLY
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TurnstileTest {
    @Test
    fun `html matches the React Native page with native bridges`() {
        val expected = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<style>html,body{margin:0;padding:0;background:transparent}#w{display:flex;justify-content:center}</style>
<script>
function fmPost(m){m.source="frontmail-turnstile";var s=JSON.stringify(m),w=window;if(w.FrontmailTurnstile&&w.FrontmailTurnstile.postMessage)w.FrontmailTurnstile.postMessage(s);else if(w.webkit&&w.webkit.messageHandlers&&w.webkit.messageHandlers.frontmailTurnstile)w.webkit.messageHandlers.frontmailTurnstile.postMessage(s);}
function fmRender(){var o={"sitekey":"0x4AAA","theme":"auto","size":"normal","language":"auto"};
o.callback=function(t){fmPost({type:'token',token:t});};
o['error-callback']=function(c){fmPost({type:'error',code:String(c==null?'':c)});return true;};
o['expired-callback']=function(){fmPost({type:'expired'});};
window.fmWidget=turnstile.render('#w',o);}
</script>
<script src="https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit&onload=fmRender" async defer onerror="fmPost({type:'error',code:'script_load_failed'})"></script>
</head><body><div id="w"></div></body></html>"""
        assertEquals(expected, Turnstile.html("0x4AAA"))
    }

    @Test
    fun `html escapes values exactly like the React Native js helper`() {
        val html = Turnstile.html(
            "k\"\\</script>\u2028\u2029\n\t\u0001é😀",
            Turnstile.Theme.DARK,
            Turnstile.Size.COMPACT,
            action = "contact",
            language = "cs",
        )
        // Output of the RN `js()` helper (JSON.stringify + escapes) for the same options, generated with Node.
        val fromNode = """{"sitekey":"k\"\\\u003c/script>\u2028\u2029\n\t\u0001é😀","theme":"dark","size":"compact","language":"cs","action":"contact"}"""
        assertTrue(html.contains("var o=$fromNode;"), html)
        assertEquals(2, Regex("</script>").findAll(html).count())
        assertFalse(html.contains('\u2028') || html.contains('\u2029'))
    }

    @Test
    fun `js escapes angle brackets and line separators`() {
        assertEquals("\"\\u003c/script>\\u2028\\u2029\"", Turnstile.js(JsonPrimitive("</script>\u2028\u2029")))
    }

    @Test
    fun `html omits an empty action`() {
        assertFalse(Turnstile.html("k", action = "").contains("\"action\""))
    }

    // --- parseMessage -----------------------------------------------------------------------

    private fun msg(body: String) = Turnstile.parseMessage(body)

    @Test
    fun `parses valid messages`() {
        val token = assertIs<TurnstileMessage.Token>(msg("""{"source":"frontmail-turnstile","type":"token","token":"abc"}"""))
        assertEquals("abc", token.token)
        assertSame(TurnstileMessage.Expired, msg("""{"source":"frontmail-turnstile","type":"expired"}"""))
        assertEquals("110200", assertIs<TurnstileMessage.Error>(msg("""{"source":"frontmail-turnstile","type":"error","code":"110200"}""")).code)
        assertNull(assertIs<TurnstileMessage.Error>(msg("""{"source":"frontmail-turnstile","type":"error"}""")).code)
        assertEquals("", assertIs<TurnstileMessage.Error>(msg("""{"source":"frontmail-turnstile","type":"error","code":""}""")).code)
        // Extra fields are ignored, like in RN.
        assertIs<TurnstileMessage.Expired>(msg("""{"source":"frontmail-turnstile","type":"expired","token":5,"x":1}"""))
        val max = "a".repeat(4096)
        assertEquals(max, assertIs<TurnstileMessage.Token>(msg("""{"source":"frontmail-turnstile","type":"token","token":"$max"}""")).token)
    }

    @Test
    fun `rejects anything that is not exactly the page's message`() {
        val bad = listOf(
            null,
            "",
            "not json",
            "\"string\"",
            "[]",
            "null",
            "42",
            """{"type":"token","token":"abc"}""",
            """{"source":"other","type":"token","token":"abc"}""",
            """{"source":["frontmail-turnstile"],"type":"token","token":"abc"}""",
            """{"source":"frontmail-turnstile","type":"hello"}""",
            """{"source":"frontmail-turnstile","type":1}""",
            """{"source":"frontmail-turnstile","type":"token"}""",
            """{"source":"frontmail-turnstile","type":"token","token":""}""",
            """{"source":"frontmail-turnstile","type":"token","token":123}""",
            """{"source":"frontmail-turnstile","type":"token","token":"${"a".repeat(4097)}"}""",
            """{"source":"frontmail-turnstile","type":"error","code":5}""",
            """{"source":"frontmail-turnstile","type":"error","code":null}""",
            """{"source":"frontmail-turnstile","type":"error","code":"${"c".repeat(65)}"}""",
            """{"source":"frontmail-turnstile","type":"token","token":"abc","code":{}}""",
        )
        for (b in bad) assertNull(Turnstile.parseMessage(b), b)
    }

    // --- navigation guard -------------------------------------------------------------------

    @Test
    fun `navigation guard mirrors React Native`() {
        val base = "https://mobile.frontmail.dev"
        assertEquals(ALLOW, Turnstile.navigationDecision("https://mobile.frontmail.dev/", base))
        assertEquals(ALLOW, Turnstile.navigationDecision("HTTPS://Mobile.Frontmail.dev/x?y", base))
        assertEquals(ALLOW, Turnstile.navigationDecision("https://challenges.cloudflare.com/cdn-cgi/challenge-platform/h/b", base))
        assertEquals(ALLOW, Turnstile.navigationDecision("about:blank", base))
        assertEquals(ALLOW, Turnstile.navigationDecision("about:srcdoc", base))
        assertEquals(ALLOW, Turnstile.navigationDecision("https://evil.example", base, isTopFrame = false))
        assertEquals(OPEN_EXTERNALLY, Turnstile.navigationDecision("https://www.cloudflare.com/privacypolicy/", base))
        assertEquals(OPEN_EXTERNALLY, Turnstile.navigationDecision("https://mobile.frontmail.dev.evil.example/", base))
        assertEquals(OPEN_EXTERNALLY, Turnstile.navigationDecision("https://mobile.frontmail.dev:8443/", base))
        assertEquals(BLOCK, Turnstile.navigationDecision("http://mobile.frontmail.dev/", base))
        assertEquals(BLOCK, Turnstile.navigationDecision("javascript:alert(1)", base))
        assertEquals(BLOCK, Turnstile.navigationDecision("intent://scan/#Intent;end", base))
        assertEquals(BLOCK, Turnstile.navigationDecision("file:///etc/passwd", base))
        assertEquals(BLOCK, Turnstile.navigationDecision(null, base))
        assertEquals(BLOCK, Turnstile.navigationDecision("about:config", base))
        // A backslash ends the authority, so this is not the base origin.
        assertEquals(OPEN_EXTERNALLY, Turnstile.navigationDecision("https://evil.example\\@mobile.frontmail.dev", base))
        assertTrue(Turnstile.isAllowedNavigation("https://mobile.frontmail.dev", base))
        assertFalse(Turnstile.isAllowedNavigation("https://example.com", base))
    }

    @Test
    fun `originOf extracts a lower-cased origin`() {
        assertEquals("https://example.com:8443", Turnstile.originOf("HTTPS://Example.com:8443/path?q#f"))
        assertEquals("https://example.com", Turnstile.originOf("https://example.com"))
        assertNull(Turnstile.originOf("about:blank"))
        assertNull(Turnstile.originOf("/relative"))
        assertNull(Turnstile.originOf(null))
    }

    // --- widgets ----------------------------------------------------------------------------

    @Test
    fun `own widget uses the org secret`() {
        val w = TurnstileWidget.own("0xOWN", "https://example.com")
        assertEquals(TurnstileKey.ORG, w.turnstileKey)
        assertEquals("https://example.com", w.origin)
        assertEquals(Turnstile.html("0xOWN", language = "cs"), w.html(language = "cs"))
        assertEquals(OPEN_EXTERNALLY, w.navigationDecision("https://mobile.frontmail.dev/"))
        assertFailsWith<IllegalArgumentException> { TurnstileWidget.own("0xOWN", "example.com") }
        assertFailsWith<IllegalArgumentException> { TurnstileWidget.own("", "https://example.com") }
    }

    @Test
    fun `shared widget comes from the public config`() = runTest {
        Frontmail.resetPublicConfigCache()
        val t = FakeTransport(ok("""{"turnstile":{"mobileSiteKey":"0xSHARED","mobileBaseUrl":"https://mobile.frontmail.dev"}}""", 200))
        val w = Turnstile.sharedWidget(Frontmail.publicConfig("https://d.example", t), null)
        assertEquals("0xSHARED", w.siteKey)
        assertEquals("https://mobile.frontmail.dev", w.baseUrl)
        assertEquals(TurnstileKey.FRONTMAIL, w.turnstileKey)
        Frontmail.resetPublicConfigCache()

        val none = PublicConfig(PublicConfig.TurnstileConfig(null, null))
        val e = assertFailsWith<FrontmailException> { Turnstile.sharedWidget(none, null) }
        assertEquals("captcha_failed", e.code)
        assertEquals("not_configured", e.reason)
        val custom = Turnstile.sharedWidget(PublicConfig(PublicConfig.TurnstileConfig("0xS", null)), "https://m.example")
        assertEquals("https://m.example", custom.baseUrl)
    }
}
