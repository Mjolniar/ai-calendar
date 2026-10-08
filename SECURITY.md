# Security

This project is fully AI-coded and has not had an independent security review. Treat it
accordingly.

## Reporting a vulnerability

Use **Security > Report a vulnerability** on this repository (private vulnerability reporting).
Please do not open a public issue for a security problem.

## Security model, in short

- **One owner per deployment.** Everything is protected by one pairing code (devices, the AI
  client consent page, the Outlook connect page) and by tokens derived from it. Make the code long
  and random (20+ characters); the server rate-limits wrong guesses per address and instance, but
  the code's length is what really protects it.
- **Devices** get a random 256-bit token at pairing; the server keeps only its SHA-256 hash.
- **AI clients** use OAuth 2.1 with PKCE (S256 only), dynamic registration limited to HTTPS (or
  loopback) redirect URIs, exact redirect matching, 5-minute codes, 1-hour access tokens and
  rotating 90-day refresh tokens. The consent page shows where it will send you; check the host
  before entering the pairing code.
- **The static API token** (`AI_CALENDAR_API_TOKEN`) grants full MCP access. Keep it for local
  scripts only.
- **Firestore** denies all client access; only the functions read and write.
- **Responses** carry `Cache-Control: no-store`, HSTS, `X-Frame-Options: DENY` and `nosniff`.
  Request bodies (which can hold codes and tokens) are never logged.
- **Phone:** HTTPS only, system certificate authorities only, no cloud backup, the device token is
  encrypted with an Android Keystore key and never moves to another device.
- **Windows:** HTTPS only (or loopback), the device token is DPAPI-protected for the current user.
- **Mail:** the Outlook tools only move mail; delete, forward and redirect are refused, also in
  Inbox rules. Mail content is data; tool descriptions tell the AI client not to follow
  instructions found in it. `outlook_send_mail` can still send mail as you: grant your AI client
  that tool only if you want it to.

## Checklist for your deployment

1. A long random pairing code and API token (`openssl rand -base64 32`).
2. Secrets only in Firebase Secret Manager and in git-ignored local files; never in the repository,
   chat or logs. Keep the project folder out of cloud-synced folders, or accept that the local
   copies sync with it.
3. A billing budget alert (see `docs/SETUP-FIREBASE.md`, Running costs). `api` is capped at 10
   instances.
4. Keep dependencies current: `cd bridge && npm audit --omit=dev`. Dependabot is enabled on this
   repository.
5. Turn off USB debugging on the phone when you are not developing: a debug build lets any
   computer you have authorized for ADB read the app's data.
6. If a device is lost: rotate the pairing code (`desktop/pair-desktop.ps1`) so it cannot pair
   again, and delete its document in Firestore `devices` to revoke its token. Revoke AI client
   access by deleting its documents in `oauthTokens`.
