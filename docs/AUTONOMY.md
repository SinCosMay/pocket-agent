# Scheduled and autonomous work

The assistant can save responsibilities and run them without another chat message.
The first implementation runs entirely on the Android device, with the configured
provider connection. It does not run while the phone is powered off or offline.
Android may delay timers for Doze, battery policy, quota or OEM restrictions. Force
stop prevents background delivery until Android allows the app to run again.

## Agent tools

- `task`: create, list, inspect, checkpoint, update, pause, resume, cancel, delete,
  complete or manually run a durable responsibility. Save the objective, progress
  and next step. Default budget: 20 runs; explicit range: 1–100.
- `schedule`: save one-time ISO timestamps, intervals of at least 15 minutes, or
  local `HH:mm` schedules with an IANA timezone and weekdays (Sunday = 0).
  Includes list/get/update/pause/resume/delete/run_now/history. Returns the saved
  next occurrence. Missed recurring occurrences coalesce rather than replaying a
  backlog. DST gaps skip that local occurrence; repeated local times run once.
- `wakeup`: set, list, inspect or cancel a one-time continuation for a task. Save
  a checkpoint first, set an ISO timestamp with an explicit timezone, then end the
  turn. Waiting does not keep Node, a worker slot or a display alive.
- `watch`: filtered phone-notification subscriptions for one package, an optional
  text match and a cooldown. Notification access must be enabled by the user.
  Calendar change feeds and external webhooks are not implemented.
- `notify_user`: save an in-app result and attempt an Android notification. Records
  survive denied notification permission. Alerts are silent from 22:00–08:00 in
  the phone's timezone. Posting does not prove delivery or that a user read it.
- `request_decision`: persist a question or request exact phone-tool approval for
  a task. Responses come only from the native Activity screen, never from a model
  tool. Approval expires after one hour and is consumed by the next run.
- `runtime_status`: enabled state, network/notification readiness, timing and
  execution limits. Background runs do not have remote MCP tools.
- `skill`: create/list/get/update/delete versioned instructions. Include inputs,
  steps, validation and failure handling. Read the skill and copy its instructions
  into a saved task prompt; scheduling by skill title alone is unsupported.

Examples: “Check my calendar every weekday at 8 AM Asia/Kolkata”; “Save a task to
research this, check again in an hour, and tell me only if something changes.”

## Execution and recovery

`AutonomyEngine` owns the job, task, wake-up, watch, decision, result and run state.
`AutonomyStore` stores it in a separate SQLite database with revision checks.
External notification events and user decisions use a durable, deduplicated inbox.
An event is acknowledged only after its resulting state is saved. The record count
and UTF-8 byte budget are bounded; exhausted limits return errors, never silently
truncate pending work. Delete old schedules, tasks or skills when their limits
are reached. Completed run outputs retain the latest 100 records.

WorkManager owns the timer. A short timer worker queues a separate execution
worker, so rearming a timer does not cancel ongoing execution. Execution leases
the same `RuntimeHost` as `AgentService`; a replacement waits for the old isolate
to close. There is no background `startForegroundService` bypass and no exact-alarm
permission. Each WorkManager invocation is bounded to eight minutes. Android 16
job quotas and OEM restrictions still apply. Native startup/dispatch failures back off
across process restarts; three consecutive failures pause automation until the
user reviews the issue and enables it again in Activity.

Each run uses a dedicated `autonomy-worker` conversation, without a main-chat
model turn or nested delegation. Its context comes from the saved task checkpoint
and self-contained trigger instructions. Default phone tools are read-only.
Unattended workers cannot create recurring triggers, edit skills, use remote MCP,
or broaden their grants. The next run can include specific phone tools approved
in Activity. The runtime rechecks task state, automation pause and grant expiry
before native phone calls. Existing operation receipts still prohibit blindly
repeating a mutation with an unknown outcome.

Dispatch is saved before inference. Worker reservations and terminal outcomes are
recorded independently. A crashed dispatch or missing/interrupted worker is marked
interrupted instead of replayed. The user or assistant must inspect receipts and
explicitly restart it. Task checkpoint continuity does not replay an old tool
call. Terminal automated worker conversations are collected and removed to free
retained-worker slots; their bounded result is kept in run history.

Activity shows pending decisions, schedules, recent runs and results, plus the
master enable switch. Stop all pauses future automation and stops current work.
Resume automation explicitly in Activity. Pausing a schedule stops future runs;
cancel the associated task to stop its current worker as well.

## Core and personal instructions

Core main/worker instructions live in `runtime/src/prompts.ts`, which developers
can edit locally and rebuild. The app exposes only **Personal instructions**.
These preferences supplement the source-owned core; they cannot replace it.
The core and default prompt fields are absent from public state and copied debug
history. Legacy `set_system_prompt` and `configure.systemPrompt` are rejected.
Legacy saved custom text migrates to preferences; the former built-in default
migrates to empty preferences. Clearing preferences retains all core rules.

This separation prevents accidental replacement through Settings. It is not a
claim that prompt text is a security sandbox or cannot be extracted from an APK;
tool grants and native permission checks enforce execution boundaries.

## Validation

Runtime tests cover restart/deduplication, persistence failure, self-wake
checkpoints, missed intervals, timezone/DST rules, decision races, one-run approval,
notification filters, skills, task completion and core-prompt migration/protection.
`AutonomyDeviceTest` exercises real SQLite revision/event handling and shared
runtime ownership. The APK workflow runs that test alongside the existing
instruction/editor lifecycle instrumentation. APK compilation and signing run
only in GitHub Actions. Physical-device Doze/OEM battery behavior and live scheduled
provider runs remain separate device validation, not guarantees from unit tests.
