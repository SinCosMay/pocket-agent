# Virtual display cleanup isolation

## Phone investigation, 22 October 2026

The USB development MCP on phone port 8767 identified itself as
`pocket-agent-development`, version `0.1.7-stt-selector-test.1-dev`, on Android 14.
The affected code is the app's Shizuku `ShellDisplayService`, reached by
`phone_screen release`, worker completion, and service shutdown.

Two failures were observed:

1. The fallback task parser carried the last `Display #N (activities from top to
   bottom)` header into the later `ActivityTaskSupervisor state` section. That
   section contains task references and another hierarchy for **all** displays.
   Those references were incorrectly attributed to N. When `am stack list`
   returned no tasks for a worker display, cleanup used this fallback and ran
   `am stack remove` on physical-screen tasks too. A captured dump attributed
   physical home/root tasks to an almost empty worker display. A release probe
   reproduced deletion of unrelated physical app tasks from Recents.
2. `NEW_TASK | MULTIPLE_TASK | EXCLUDE_FROM_RECENTS` does not guarantee a separate
   instance for `singleTask`/`singleInstance` apps. A disposable Settings task
   moved from display zero to the worker display with the same task ID. The old
   cleanup assumed every task on that display was owned by the worker. Its
   historical task ID set also remained eligible after a task moved elsewhere.

The reproduction itself cleared existing unrelated Recents entries. No app-data
clearing commands were used; prior activity stacks and unsaved state cannot be
reconstructed from the task listing. Further old-code release probes were stopped.
The final probe's shell display process was terminated directly, bypassing its
faulty application cleanup. No test display was left running.

## Changes

- Parse only explicit `RootTask`/`Stack` records with their display IDs, or the
  top-level task records inside the initial activity display sections. Ignore
  nested child tasks and later diagnostic references. An empty target display
  in a valid stack listing is authoritative and does not trigger a fallback.
- Select deletion candidates from fresh display membership and check membership
  again immediately before each removal. Never delete historical task IDs that
  have moved to another display.
- Record pre-existing tasks and their origin displays before creating a worker
  display and before launching another app. Restore borrowed tasks instead of
  deleting them. Refuse display destruction if restoration or membership checks
  fail, and retain the coordinator's lease when cleanup fails.
- Propagate cleanup failures to the runtime and tool caller. Retain the runtime
  pool reservation until native cleanup succeeds, so a waiting worker cannot
  consume capacity that is still occupied. Bump the Shizuku service version to
  avoid attaching the updated app to an old display-service implementation.
- Reject resolved `singleTask`/`singleInstance` launches when that package already
  has a task on another display. A post-launch migration check also restores
  reused tasks and rejects the launch. This is task isolation, not separate app
  storage: browser tabs and app data can still be shared across app windows.

## Validation and rollout

`python3 -m unittest discover -s scripts -p 'test_display_tasks.py' -v` compiles
the production parser/policy with Java 17 compatibility and runs a regression
harness. It covers the captured dump shape, empty worker displays, references in
later sections, nested tasks, other workers, borrowed tasks, moved tasks, and
unrecognized input. The normal signed-APK workflow runs this check too.

This source change does not update the installed phone APK. Build/sign through
GitHub Actions per the repository policy. Before rollout, exercise the updated
development APK using disposable fixtures: preserve a physical task and a second
worker task, release an empty worker display, repeat release, move a worker task
to display zero before release, and attempt a singleton app launch that already
has a physical task. Verify original task IDs remain and worker tasks disappear.
Do not repeat cleanup testing with personal apps on the vulnerable installed APK.

Host validation in this change: 122 runtime tests passed, all 13 Python/JVM
checks passed, and the runtime bundle built successfully. Android APK compilation
and updated-device execution have not been run for this patch.
