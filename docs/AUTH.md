# Model connections

The Pi agent loop, subagent scheduler and native tool execution run inside Android.
The provider adapter only sends model requests and receives text/function calls.
Neither model path runs a desktop agent or desktop phone tools.

## Direct API key (independent operation)

Select `openai` authentication and set an HTTPS Responses base URL, model ID and API
key. The default base URL is `https://api.openai.com/v1`; the model is configurable.
This connection works without a PC once configured. An API key and a model available
to that API account are required; a ChatGPT subscription is not an API key.

Native code stores credentials in the app's protected credential store and passes
only the active credentials to the runtime. They must not be put in transcript
persistence, APK assets, source control or diagnostic logs. Clearing app data clears
these credentials. A configured custom HTTPS endpoint receives the supplied key and
conversation, so use only a trusted provider.

`runtime/src/provider.ts` selects only Pi's OpenAI Responses implementation. It
retains SSE streaming, structured function calls and function results, images and
`AbortSignal` cancellation. Requests have a 120-second timeout and zero automatic
retries. Tool mutation receipts and unknown outcomes are handled by the Android
host, never by replaying model requests blindly. The configured context/output
limits are conservative host defaults, not a claim about a provider's full limits.

## Codex account: emulator development relay

The development relay uses this machine's existing `~/.codex/auth.json` only for
inference. Start it in a separate terminal:

```sh
python3 scripts/codex_relay.py --port 8787 --model gpt-6-sol
```

Python `aiohttp` is required. The relay prints the private bearer **file path**, not
its value. Its default file is `~/.local/state/pocket-agent/relay.token`, created
with mode `0600`. Provision that random development bearer into the emulator's
protected provider configuration through the local test setup; never paste it
into a checked-in file, APK asset, shell command log or public URL. Configure:

```json
{"authMode":"codex","endpoint":"http://10.0.2.2:8787/v1","model":"gpt-6-sol","apiKey":"<private development bearer>"}
```

The example placeholder is not a working credential. `10.0.2.2` is the Android
emulator's host alias. The server bind is hardcoded to `127.0.0.1`. The adapter
rejects other non-loopback relay hosts. Do not tunnel, publish or expose this
service; it is a local test aid, not the standalone production architecture.
A physical phone cannot use this emulator-only configuration.

The relay replaces the local bearer with the cached Codex access token and account
ID only on an HTTPS request to the fixed Codex inference endpoint. The account
access/refresh tokens never go to Android. It rereads the auth file each request
and does not refresh or rewrite Codex login state. If the login expires, sign in
with the normal host Codex login flow and restart/retry the user request after
checking any unknown tool outcomes. The relay allows one configured model, rejects
server-executed tools, does not follow redirects, bounds request/output size,
redacts upstream failure bodies, and cancels inference when its client disconnects.
Astra models are disabled by its CLI.

This path preserves the on-phone harness and tool execution, but it depends on the
PC for inference authentication. It is explicitly a development test, not a
completed independent ChatGPT-account connection.

## Native ChatGPT-account login: remaining blocker

Direct API-key inference is implemented. Native account sign-in is **not** yet
implemented. Do not paste a Codex access token into the phone's API-key field or
invent an Android OAuth redirect.

The inspected Pi Codex OAuth module explicitly supports CLI Node environments,
uses a localhost callback server, a specific Codex OAuth client ID and CLI/device
login flows. Reusing that client registration as a new Android application's
approved sign-in path has not been established. Before shipping native account
login, establish a supported client registration and mobile redirect/device flow,
secure refresh-token storage, refresh and revocation handling, and validate it on
a device. [Codex authentication documentation](https://learn.chatgpt.com/docs/auth)
describes Codex account/API-key login; it does not establish an Android OAuth
registration for this app. [App-server documentation](https://developers.openai.com/codex/app-server/)
is a separate host protocol, not proof of independent mobile login.

Protocol evidence: Pi `@earendil-works/pi-ai` 1.2.0,
`api/openai-codex-responses.ts` and `auth/oauth/openai-codex.ts`, inspected from Pi
commit `dc939913f6142adce7c09da5707fdd6451b37fb0`. The dev relay follows its
`/backend-api/codex/responses`, account-ID header, `store:false`, and streaming
Responses format; no browser cookies or account tokens are exported.

## Validation

Run local protocol/security tests without network access or account credentials:

```sh
python3 -m unittest discover -s scripts -p test_codex_relay.py -v
```

These cover bearer/origin checks, private token-file permissions, model and
server-tool rejection, streaming, structured function history preservation, safe
bounded failures without retries, and cancellation before the first upstream
response byte. A real Codex model tool-call test is separate from these synthetic
tests; phone tool execution must still be demonstrated on the exact APK artifact.
