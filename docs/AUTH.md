# Model connections

Pi, Pi9 scheduling and every phone tool run inside the APK. Providers supply
inference only; they do not run a desktop agent or phone tools.

## Codex account

Select **Codex account** in Settings. There is no API key, custom endpoint, PC
relay, ngrok or MCP connection to configure. Choose a model available to your
account; emulator acceptance uses `gpt-6-luna`.

- **Sign in** uses the Codex device-code flow. Open the official verification
  page in the system browser and approve the displayed code with your existing
  ChatGPT account. Device-code login may need enabling in ChatGPT security or
  workspace settings. Cancellation and expiration leave the active account alone.
- **Import Codex auth.json** uses Android's document picker to read an existing
  Codex login cache. The file contains account credentials: transfer it privately,
  select it, and remove the source copy afterward. No endpoint or token pasting is
  required. A cache containing only an API key is not an account login.
- **Sign out** removes the app's saved account credentials. It does not revoke
  other Codex installations or erase your retained conversation history.

The import accepts the normal `~/.codex/auth.json` token object. Android validates
its shape, expiry and account-ID consistency before storing allowlisted fields
in an AES-GCM envelope protected by Android Keystore. Import does not constitute
independent signature verification of an arbitrary JWT; OpenAI validates the
credential on inference/refresh. Never share an auth cache publicly or commit it.
Imported caches share the original session's refresh-token lineage; a separate
device-code login is preferable for ongoing use on multiple devices. If another
installation rotates/revokes that shared token, sign in again rather than
replaying a failed phone action.

Direct inference uses the pinned Pi Codex Responses adapter and the fixed HTTPS
Codex backend. Refresh uses the fixed HTTPS OpenAI token endpoint. Main and workers
share a single refresh operation; replacement tokens must be durably encrypted
before any request uses them. Native compare-and-swap prevents a stale refresh
from overwriting a newly imported account or sign-out. Failed refresh requires a
new sign-in; inference and tool mutations are not automatically replayed.

Credentials are excluded from app backup/device transfer, public runtime state,
transcripts and diagnostic events. Account mode never forwards tokens to a
user-configured endpoint. Provider failures are bounded and sanitized. This is a
Codex-compatible account connection using Pi's existing protocol, not an OpenAI
endorsement or a separate registered Sign in with ChatGPT application.

The [Codex authentication documentation](https://learn.chatgpt.com/docs/auth)
describes device-code login and securely transferring an existing auth cache.
The newer [ChatGPT plan usage flow](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)
uses separately issued dynamic-client credentials and public Responses inference;
those credentials are different from an existing Codex auth cache.

## API key

Choose **API key (HTTPS)**, then enter an HTTPS Responses base URL, a model and its
API key. The default base URL is `https://api.openai.com/v1`. A configured provider
receives the supplied key and conversation, so use a trusted endpoint. API billing
is separate from a ChatGPT subscription. Keys receive the same encrypted storage
and transcript redaction as account tokens.

Both inference adapters support SSE, structured function calls/results, images
and cancellation. Requests have a 120-second timeout and no automatic retries.
Screen leases, mutation receipts and unknown outcomes remain enforced locally.

## Legacy emulator relay

**Codex emulator relay (development)** remains available for older test setups.
It requires `python3 scripts/codex_relay.py --model gpt-6-luna` on the emulator host,
Python `aiohttp`, and the random bearer in
`~/.local/state/pocket-agent/relay.token` (mode `0600`). Its endpoint is
`http://10.0.2.2:8787/v1`; native settings reject this mode on physical devices.
The server binds only to `127.0.0.1`. Do not publish or tunnel it. Direct account
mode above does not require this relay.

## Validation

Run `npm test` in `runtime` for account refresh concurrency, durable rotation,
origin restrictions, token redaction, stream cancellation and tool-schema checks.
Relay-specific synthetic checks remain available through
`python3 -m unittest discover -s scripts -p test_codex_relay.py -v`.

After downloading exact signed Actions APKs, test direct account inference on the
explicit emulator with:

```sh
python3 scripts/emulator_test.py --serial emulator-5580 --artifacts artifacts/BUILD --fresh --codex-account
```

This opt-in setup requires the rooted test AVD; the installed app itself does not
require root. It privately reads the local Codex cache, provisions a transient
app-scoped file, imports encrypted credentials and deletes that file. It never
refreshes or rewrites the host cache. The live case requires a real Luna background
worker, a concurrent main answer, and a native observation of
`STATUS_CLICKED:LiveCodex COUNT:1`. Cleanup stops work and removes emulator account
credentials. Device-code browser approval is a separate interactive check; import
acceptance does not prove a user completed that sign-in flow.
