# Local conversation history: USB phone validation

Validated on 2026-10-25 on the connected Xiaomi `22041216I`, Android 14,
ARM64. The real Android SQLite and embedded Javet/Node/Pi runtime executed
the checks. The model was the deterministic offline scripted provider;
no cloud model or external memory server was used.

## Exact build

APK compilation and debug signing ran in [GitHub Actions run 40167714329](https://github.com/ru-aish/pocket-agent/actions/runs/40167714329).
The tested source commit is `f969afedd6b9296caed94da1235b3bb18a63b6c1`.
Downloaded APK hashes matched the artifact's `SHA256SUMS.txt`:

| Artifact | SHA-256 |
| --- | --- |
| `Pocket-Agent-history-arm64.apk` | `2b9cf69e6a702515e95a14c9ebb0571a4580d8969811b7a7254ce3613cc4ced4` |
| `Pocket-Agent-history-tests.apk` | `37cbae7b5ed10e19cdc2e0095d87141475b32f635d36413d2eb03967896bf4b9` |

The opt-in `isolatedDeviceValidation` Gradle property selects app ID
`dev.pocket.agent.historyvalidation` and debug instrumentation. The installed
`dev.pocket.agent` development build was neither upgraded nor cleared. Its
version and installation timestamps matched before and after validation.
The temporary validation app and instrumentation APK were removed afterward.

## Results

All four physical-device instrumentation cases passed:

| Case | Verified behavior |
| --- | --- |
| `mainAndWorkerHistorySurviveResetByteCheckpointAndReopen` | Main and worker text persists across checkpoint replacement and database reopen; duplicate checkpoints do not duplicate messages; workers cannot search or read another worker's private messages. |
| `versionOneMigrationPreservesAndArchivesExistingCheckpoint` | A real version-1 database upgrades to version 2, preserves its checkpoint, rendered snapshot and events, and archives legacy messages on the next save. |
| `failedCheckpointRollsBackArchiveAndSearchIndex` | A synthetic SQLite trigger rejects checkpoint persistence; both archive and FTS inserts roll back, and the previous checkpoint remains intact. Retrying after removing the failure succeeds. |
| `realPiSearchAndReadRecoverOldThreadAfterRuntimeRestart` | A real main turn is archived before reset; Pi calls `history_search` and `history_read` through the native bridge; the original message remains retrievable after closing and recreating the Node runtime. |

The embedded compatibility gate also passed: Node `26.10.0`, V8
`14.6.202.34-node.34`, WebCrypto, UUID generation, two streaming reads,
and cancellation after streaming starts.

After instrumentation exited, the validation app was launched separately.
The **Search saved conversations** dialog found the synthetic archived phrase
with message ID, timestamp, owner and role. **Read full message**, given that
ID, displayed the exact original text. UI hierarchy assertions and screenshots
were retained in the local `artifacts/history-device-f969afe/` directory.

The Actions build passed all 40 focused memory/runtime tests. The
[machine-readable phone record](validation/2026-10-25-history-phone.json)
contains the device results, APK hashes, and runtime gate output. Test wall
times include instrumentation startup and are not query-latency benchmarks.

## Reproduce

Build only in Actions, then execute locally against an explicit USB serial:

```sh
gh workflow run apk.yml --ref t3/implement-task-7e583006 -f isolated_history=true
gh run download RUN_ID --name pocket-agent-history-device --dir artifacts/history-device
(cd artifacts/history-device && sha256sum --check SHA256SUMS.txt)
adb -s USB_SERIAL install -r artifacts/history-device/Pocket-Agent-history-arm64.apk
adb -s USB_SERIAL install -r artifacts/history-device/Pocket-Agent-history-tests.apk
adb -s USB_SERIAL shell am instrument -w -r -e class \
  dev.pocket.agent.ConversationHistoryDeviceTest,dev.pocket.agent.ConversationHistoryLoopDeviceTest \
  dev.pocket.agent.historyvalidation.test/androidx.test.runner.AndroidJUnitRunner
```

Accept the phone's USB-install confirmation. On this Xiaomi phone, accidentally
remembering a rejection caused immediate `INSTALL_FAILED_USER_RESTRICTED`
failures until the validation app's saved denial was disabled in Developer
options → the **Install via USB** list headed **Denied installation of the
following apps via USB**. Other apps' deny settings were preserved.

## Limits

This validates the history feature in an isolated ARM64 debug build. It does
not validate a minified signed release APK, live model inference, semantic
embeddings/Mem0, large-history latency or storage growth, or long-duration
battery/OEM behavior. Search remains lexical.
