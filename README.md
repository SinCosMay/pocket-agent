# Pocket Agent

A standalone Android text agent. Pi runs locally in one app-owned Node runtime; adapted `@pi9/subagent` workers use direct Android tools. Only inference leaves the phone. No ngrok, Termux, desktop agent host, or MCP loopback is required for normal API-key operation.

Development is on `feat/android-agent`. The project is being validated; a build alone does not establish runtime readiness. See `docs/IMPLEMENTATION_HANDOFF.md` for requirements and later validation reports for measured results.

Build APKs with GitHub Actions. Run runtime tests on the Fedora emulator using explicit serials. Do not install over Pocket MCP: this app is `dev.pocket.agent`.
