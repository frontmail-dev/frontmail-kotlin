# Changelog

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
