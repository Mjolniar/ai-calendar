# Security

> [!WARNING]
> Fully AI-coded. No independent security review.

## Reporting

Use **Security > Report a vulnerability** on this repository (private reporting). Do not open a public issue.

## Model

- **One owner per deployment.** One pairing code protects devices, the AI client consent page and the Outlook
  connect page, plus tokens derived from it. Use a long random code (20+ characters). Wrong guesses are rate-limited
  per address and instance; length is the real protection.
- **Devices:** random 256-bit token at pairing; the server stores only its SHA-256 hash.
- **AI clients:** OAuth 2.1, PKCE (S256 only), dynamic registration with HTTPS (or loopback) redirect URIs, exact
  redirect matching, 5-minute codes, 1-hour access tokens, rotating 90-day refresh tokens. The consent page shows the
  redirect target; check the host before entering the pairing code.
- **Static API token** (`AI_CALENDAR_API_TOKEN`): full MCP access. Local scripts only.
- **Firestore:** all client access denied; only the functions read and write.
- **Responses:** `Cache-Control: no-store`, HSTS, `X-Frame-Options: DENY`, `nosniff`. Request bodies (may contain codes
  and tokens) are never logged.
- **Phone:** HTTPS only, system CAs only, no cloud backup, device token encrypted with an Android Keystore key and never
  moved to another device.
- **Windows:** HTTPS only (or loopback); device token DPAPI-protected for the current user.
- **Mail:** Outlook tools only move mail; delete, forward and redirect are refused, including in Inbox rules. Mail
  content is data; tool descriptions tell the AI client not to follow instructions in it. `outlook_send_mail` can send
  as you: grant it only if wanted.

## Deployment checklist

1. Long random pairing code and API token (`openssl rand -base64 32`).
2. Secrets only in Firebase Secret Manager and git-ignored local files; never in the repository, chat or logs. Keep the
   project out of cloud-synced folders, or accept that local copies sync.
3. Billing budget alert (`docs/SETUP-FIREBASE.md`, Running costs). `api` is capped at 10 instances.
4. Keep dependencies current: `cd bridge && npm audit --omit=dev`. Dependabot is enabled here.
5. Turn off USB debugging on the phone when not developing: a debug build lets any ADB-authorized computer read app
   data.
6. Lost device: rotate the pairing code (`desktop/pair-desktop.ps1`) and delete its document in Firestore `devices`.
   Revoke AI client access by deleting its documents in `oauthTokens`.
