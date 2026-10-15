# Personal memory (Android)

Pocket Agent uses one app-private `personal-memory.db` store in the Android process. It does not run a local language model, vector daemon, or background service, and does not depend on hosted Supermemory, Honcho, or OpenClaw deployments.

## Design

- **Always-available personal profile:** concise confirmed and inferred preferences, people, active projects, goals, and repeatable procedures are injected into the real Pi system transcript before a user turn. The same mechanism applies to resumed workers and newly spawned workers.
- **Relevant episodic recall:** Android SQLite FTS4 searches localized indexed text; only bounded, relevant results are included in context, and each carries confirmation status, source and update time. No embedding model is needed for normal recall.
- **Explicit writes and corrections:** the model has `memory_save`, `memory_search`, `memory_profile` and `memory_forget` tools. Stable keys update previous facts rather than accumulating duplicates. The model is instructed to save stable facts when directly asked, and to resolve corrections. The original user transcript is retained separately.
- **Automatic observation:** when three successful main user turns accumulate, a separate bounded observer/reflector session reuses the configured inference provider. It gets **only user messages**, never worker tool output or app-controlled content. It extracts at most five non-sensitive facts and stores them as inferred (`confirmed=false`). If inference fails, queued input is retained in the existing runtime checkpoint for a future attempt. Background observation does not block normal user turns or add another local model process.
- **Thread scope:** only main can write or delete shared global memories. Workers can read global memories and their own task-private notes, but cannot see sibling notes or promote their own claims into global facts. The read-only researcher has no memory mutation tool. No worker can bypass inherited tool grants.
- **Provenance:** confirmed memories cannot be automatically overwritten by observer output. Returned source and timestamps are visible for retrieval and corrections. Explicit agent calls are distinguished from automatically inferred records; confidence is not the same as verified completion.
- **Limits:** 5,000 records total, 800 characters each, FTS indexing on Android, 16 profile hits plus search, and 5.5 KB maximum injected recall. No additional SQLite extensions or native libraries.

## Security and limitations

No secrets, medical data, private identifiers, political or other sensitive information should be automatically remembered. A model instruction alone is not a complete privacy filter, so a shipping-grade consent/delete-all UI and stricter sensitive-data validation remain necessary. Existing Android app-private filesystem protections apply; this memory file is not separately Keystore-encrypted. Memory data is treated as untrusted context, not fresh operational evidence or a standing authorization to send messages, mutate apps or share data.

The background observer is a **single model-call observer and reflector**, not a verbatim port of Mastra Observational Memory or Supermemory. Semantic embeddings, remote hosted memory engines, and guaranteed cross-lingual paraphrase recall are not included. Full end-to-end quality requires multi-session correction, stale-fact and multilingual datasets; fast FTS lookup is not a quality benchmark.

## Testing

`cd runtime && npm ci && npm test && npm run build` runs real Pi runtime tests. Android instrumentation `dev.pocket.agent.MemoryDeviceTest` checks SQLite persistence, update/delete index synchronization, permission scopes, rejection of untrusted updates to confirmed facts, four concurrent readers and process memory measurement. Run the test against **the exact CI-signed release/test APKs** on `emulator-5580`:

```sh
adb -s emulator-5580 install -r Pocket-Agent-x86_64.apk
adb -s emulator-5580 install -r Pocket-Agent-tests.apk
adb -s emulator-5580 shell am instrument -w -e class dev.pocket.agent.MemoryDeviceTest dev.pocket.agent.test/androidx.test.runner.AndroidJUnitRunner
```

This test creates/deletes an isolated `memory-instrumentation.db` and does not modify existing user memory. Also run `dev.pocket.agent.MemoryLoopDeviceTest`: this invokes the real scripted Pi tool loop to save a synthetic preference, verifies the native SQLite entry, and recalls it on the next main turn. The synthetic record is deleted after the test.

`MemoryStore` uses a separate DB file so no schema changes to `StateStore` or operation receipts are necessary.
