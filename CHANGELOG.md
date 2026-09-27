# Changelog

## 0.2.0

- `SendOptions(locale = "de")` picks a language variant of the template (BCP 47 tag; the API falls
  back to the same base language, then to the template's default language). Sent as `locale`.
- `SendResult.locale`: the language the email was rendered in (`null` if the API didn't return one).
- Binary compatible with 0.1.x: the previous `SendOptions` and `SendResult` constructors remain in
  the bytecode (hidden from Kotlin source).

## 0.1.0

Initial release.

- `Frontmail(publicKey)` client with `suspend` `send` (params as `Map<String, Any?>` or `JsonObject`,
  Turnstile token, dynamic attachments) and `getStatus` (status token in `X-Frontmail-Status-Token`).
- Private keys (`sk_…`) are refused with `private_key_in_browser`.
- Retries for network errors, timeouts, `5xx` and `429` (`Retry-After` honored, > 60 s not retried),
  exponential backoff with full jitter, one `Idempotency-Key` per send reused across retries,
  per-attempt timeout, immediate cancellation.
- `FrontmailException` with `code`, `status`, `docsUrl`, `details`, `retryAfter` and category checks.
- `Frontmail.publicConfig()` (cached per API URL).
- Turnstile helpers for a `WebView`: `Turnstile.html`, `parseMessage`, `navigationDecision` /
  `isAllowedNavigation`, `Turnstile.sharedWidget()` and `TurnstileWidget.own()`.
