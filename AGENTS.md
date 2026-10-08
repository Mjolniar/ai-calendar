# Instructions for coding agents

You are working on AI Calendar: an Android app (`app/`), a Windows app (`desktop/`) and a
Firebase Functions backend (`bridge/`) that share one cloud calendar. AI clients write to it
through the bridge's MCP server. Read this file first, then `docs/ARCHITECTURE.md` before
changing behavior.

## If you are asked to set the project up from scratch

Work through these in order, and stop to ask the human whenever a step needs their account, a
payment plan, a credential or a choice. You cannot create accounts, sign in for them, or type
their secrets.

1. **Prerequisites** (check, do not install silently): Node 22+, JDK 17+ (Android Studio's JBR
   works), Android SDK, .NET 8 SDK (Windows app only), Python 3 (icons; Pillow only for picture
   sheets), Firebase CLI (`bridge/node_modules/.bin/firebase` after `npm ci`).
2. **Firebase:** follow `docs/SETUP-FIREBASE.md`. The human creates the project, picks the Blaze
   plan and runs `firebase login`. You can then select the project, deploy rules, create secrets
   from files the human prepared (never echo their values), write `bridge/.env.<project-id>` from
   the Android app registration values, and deploy.
3. **Microsoft (optional):** follow `docs/SETUP-AZURE.md`. The human registers the app and creates
   the client secret in the Entra admin center; you store the values as secrets and redeploy.
4. **Verify:** `GET <base>/health`, then `tools/mcp-call.ps1 list_series` with the API token.
5. **Apps:** change `applicationId` and `namespace` in `app/build.gradle.kts` if the human wants
   their own package (the Firebase Android registration must match), build and install the phone
   app, install the Windows app, and let the human pair both with the pairing code.
6. **AI client:** the human adds `<base>/mcp` as a custom MCP connector and enters the pairing
   code on the consent page.

## Commands

| What | Command (repository root) |
|---|---|
| Bridge typecheck and tests | `cd bridge && npm ci && npx tsc -p tsconfig.json --noEmit && npm test` |
| Bridge deploy | `node bridge/node_modules/firebase-tools/lib/bin/firebase.js deploy --only functions` (or `scripts/deploy-bridge.ps1`) |
| Phone unit tests, lint, debug APK | `scripts/build-app.ps1 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` (or `./gradlew` with `--no-daemon`) |
| Phone screens and UI flows, no device | `ui-harness/run.ps1` (pictures: `ui-harness/build/shots`, `shots-dark`) |
| Windows tests | `scripts/test-desktop.ps1` (or `dotnet test desktop/tests`) |
| Windows install | `scripts/install-desktop.ps1` |
| Icons | `python tools/xp-icons/generate.py` after editing `tools/xp-icons/icons.py` |

Windows traps that already cost time:

- Keep the repository in a path without `&`; npm and firebase shims cannot run from one. Spaces and
  apostrophes in `%TEMP%` can break the JVM; the scripts point `TEMP` at `build/tmp`.
- Robolectric (the harness) cannot read its runtime jar from a folder with a space in the path
  (for example a user folder named "First Last"). `ui-harness/run.ps1` copies the jar to
  `build/robolectric-deps` and the harness uses it.
- A Gradle call whose output is piped can wait on a newly started daemon for hours. Use the
  scripts (they redirect to a log file) or `--no-daemon`.
- Some agent shells set `NoDefaultCurrentDirectoryInExePath=1`; then `cmd /c gradlew.bat` is "not
  recognized". Clear the variable for the call.

## Rules

1. **Secrets** stay out of the repository and out of logs and chat: Firebase secrets, `secrets/`,
   `bridge/.env.*` (except `.env.example`), the Windows app's `settings.json` (DPAPI-protected
   device token). Scripts read them by path.
2. **Two apps, one set of rules.** Grouping, filing, alert timing, the night rule, deliveries,
   colors and wording are implemented in both `app/` and `desktop/`. Change both, with tests on
   both sides. The table "Rules implemented twice" in `docs/ARCHITECTURE.md` lists the pairs.
3. **The look is Windows XP.** Screens use only the controls and the two palettes (Luna, Noir) in
   `app/.../xp/` and `desktop/Xp/`. A new color is a palette entry in both. All artwork is
   original; icons are generated, never hand-edited.
4. **Deliveries never alert,** on any device, whatever lead times they carry. A reminder is a
   delivery only when it says so (`domain: "delivery"`); never infer it from wording.
5. **MCP compatibility.** AI clients read the tool list when a session starts. Add optional
   fields and new tools; never rename or remove. The `instructions` string in
   `bridge/src/mcp.ts` steers every client: treat a change to it as a behavior change.
6. **Real data.** On a live deployment, test with reminders titled `TEST ...` and delete them.
   Mail tools move mail and never delete it; keep it that way.
7. **Windows tests** never show a window on screen, take the focus or start the real `App`
   (`desktop/tests/UiFixture.cs` is the pattern).
8. **Phone installs:** `adb install -r` only. Uninstalling or clearing the app erases its local
   reminders, its outbox of unsent changes and its pairing.
9. **Versions:** bump `versionCode`/`versionName` in `app/build.gradle.kts` and `Version` in
   `desktop/AI.Calendar.Desktop.csproj` with every shipped change, and keep the READMEs current.
10. **Deploy order:** bridge first when the apps depend on it, then check `/health` and one MCP
    call, then the apps.
