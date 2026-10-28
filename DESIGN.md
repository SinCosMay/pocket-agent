# Pocket Agent design system

## Android fidelity contract
The approved `preview/index.html`, `preview/style.css`, `preview/mark.svg` and
connection forms are the shared interface source. Android packages these assets
locally and runs them without a web server. `native-app.js` binds the same layout
and components to Android's existing stores and services. Do not replace primary
screens with native AlertDialogs or approximate the settings groups with cards.
Only Android permission/system flows and explicitly labelled advanced controls
use platform dialogs. The top area has one menu bubble, with no overflow button.
Use the same 220ms drawer reveal/push motion, grouped settings rows, serif section
headings, profile avatar, field geometry and theme tokens as the web preview.
The chat textarea shows the normal text caret without an inner focus rectangle;
the neutral composer boundary supplies focus context. Sidebar titles occupy one
line with ellipsis. Generate 3–6 word topic titles with the selected model, capped
at 42 characters, and persist them per conversation. Until naming is available,
show a short bounded excerpt. Title requests have no phone tools and yield to
foreground chat; they never append messages to the actual conversation.
Sidebar gestures use horizontal touch intent after 10px and a 1.3:1 horizontal
to vertical ratio. The drawer follows the finger; release commits after 28% of
its width, or a 24px flick above 0.45px/ms. Rightward swipes on chat open it;
leftward swipes on the open drawer close it. Vertical scrolling, text selection,
form inputs and settings retain their normal gestures. Cancelled gestures return
to their starting state. Settling uses the existing 220ms motion (zero for
reduced motion). Mechanism reference: beUI drawer and draggable bottom-sheet.

## 1. Atmosphere and identity
A personal workspace, with the calm of a well-made notebook. Warm charcoal,
paper-colored type, and a small mint action create a deliberate alternative to
purple AI dashboards. The signature is an open pocket mark and an editorial
greeting. Home now contains only a centered Pocket mark, a short question, and
one supporting line explaining how to begin. No suggested prompts or task strip on home.
Conversation is primary; background activity is available from the drawer.
This is a redesign of the existing native column/row/text/button primitives.
The restrained palette and simple chat controls keep conversation primary.

## 2. Color
| Semantic role | Dark | Light |
|---|---|---|
| Canvas | #171918 | #F6F5F0 |
| Surface | #1E211F | #FFFFFF |
| Elevated/hover | #292D29 | #EBEDE7 |
| Primary ink | #EEEFE8 | #252923 |
| Secondary ink | #A6ADA3 | #61695E |
| Divider | #343A33 | #D5DACF |
| Accent | #B7D9A4 | #C4DDB5 |
| On accent | #202C1B | #202C1B |
| Error | #F2AAA0 | #9D3028 |
Accent backgrounds identify the send action; selection uses neutral washes.
Native resources carry the same semantic roles. Theme attributes supply native
dialog/form colors. Keep the user's original colors.xml intact.

## 3. Typography
Native: sans-serif, with sans-serif-medium for controls. Browser: system sans,
Georgia for the 48px desktop / 36px mobile greeting. Body 16px/1.6, secondary
14px/1.5, metadata 12px/1.5, app title 20px medium. Native greeting uses the
platform serif at 30sp. Body and transcript remain selectable and scalable.

## 4. Spacing and layout
4/8/12/16/24/32/48px rhythm; 20dp native insets. Main conversation owns scrolling;
composer remains visible. Phone-only composition: product bar, compact history
actions, scrolling conversation, background-task strip, bottom composer. Browser
preview stays 430px wide on larger monitors; it fills the viewport on phones.
There is no desktop workspace. A menu button opens a phone-sized left drawer
with new chat, search, conversation history, and bottom utility actions. It
overlays the conversation rather than squeezing it. Main uses min-width:0/min-height:0.
Browser height is 100dvh, with 24px outer framing only on larger monitors.
Spatial source: [StyleGallery scroll-body-shell](https://github.com/changeroa/StyleGallery/blob/main/patterns/viewport-shell/scroll-body-shell.md).
The central conversation, rather than the document, owns the scroll, so the
header and composer stay available through long messages.

## 5. Components and states
- Preview top area: no divider or separate bar surface; menu button is a 48px
  circular surface bubble with a neutral hover state. Product identity, New chat, Copy
  conversation, Activity, and Settings live in the drawer. Copy is disabled
  until there are messages. Search uses one rounded container with a visible
  neutral border on focus, without an extra rectangular input outline.
- Quiet button: neutral fill, 10px radius, 48dp native / 44px web minimum target.
- Primary button: mint fill and dark label, same minimum target.
- Composer provider picker: Pocket Agent when unconfigured; Nebius, OpenCode,
  or Codex after selection. Opens AI & model settings. API key, Codex relay,
  and Scripted options are removed from the preview; stale selections fall back
  to unconfigured. Persisted supported selections restore their composer label.
- Composer: elevated surface, 16px radius, multiline input, send at trailing edge.
- Native chat tasks: compact worker cards inside the conversation scroll. Each
  contains title, status, run number, real display lease status, and Open worker.
  A schedule link appears only when there are saved schedules. No invented work.
- Dialog: native modal / semantic HTML dialog; labeled title and close control.
- Conversation: native rich text role labels; web semantic message articles.
- Chat drawer: at most 320px and always leaves 48px of the phone visible. Native
  focus containment, Escape/backdrop dismissal, and focus restoration. Drawer
  and scrim live inside the clipped phone frame, not the browser top layer.
  Both open and close use a 220ms horizontal transition from the phone's left
  edge. The home screen moves aside by the same drawer width, leaving a visible
  strip to tap to close. Reduced motion switches immediately.
  History owns its scroll; selected chat uses a neutral wash and aria-current.
  Search filters saved preview chat text. Empty and no-results states differ.
  Interaction reference: beui.dev drawer source; adopt focus, dismissal, and
  reduced-motion principles with platform dialog behavior, not its React code.
- Settings preview: an in-frame phone screen with a fixed back/title row and
  scrolling content. Overview contains a local profile card, grouped navigation
  rows, and appearance selection. Subscreens cover provider/model/reasoning,
  personal instructions, speech recognition/wake word, phone permissions,
  HTTPS MCP connection drafts, and background work. Native-only actions remain
  visibly unavailable. Profile/preferences stay in browser storage. Provider and
  voice secrets use the local server's encrypted vault, never browser storage.
  Codex has device sign-in, import, cancellation and sign-out; key providers have
  provider-specific key forms and model discovery.
  Fields have visible labels, 16px input text, 48px targets, and adjacent status.
- Voice: system speech shows no cloud key form; Groq Whisper and Deepgram show
  only their own credential form. Mic input fills a draft for review, independently
  of AI-provider setup. Missing keys open Voice settings. Groq protocol follows
  SinCosMay/phone-control, using whisper-large-v3-turbo.
  Native Voice starts the existing VoiceListeningService; wake words and live
  hybrid/Flux recognition remain Android capabilities.
- Activity: full-screen native view, grouped schedules, approvals, and compact
  run results. Raw output is behind View details. nextAt is formatted in local
  time; absent next runs read No upcoming run. Browser Activity has Schedules,
  Subagents, and Displays tabs that clearly state phone activity is unavailable.
All web controls have hover, pressed, disabled, and keyboard-focus states. Empty,
history search/no-results, preview reply and storage error states are explicit.

## 6. Motion
Chat uses a right-aligned neutral user bubble and a left-aligned assistant
reply, with accessible role labels instead of visible provider names. The
composer is a single 56px pill at rest with dictation and a 48px voice/send
action; focus expands the multiline field. Provider selection stays in Settings.
Message nodes, worker drafts, and scroll containers survive live updates.
New content follows only when already near the end. Displays owns the three
private-screen slots and their read-only previews; threads show no display IDs.
Preview capture runs only while viewing.
Composer expansion follows real Android IME visibility, not retained DOM focus.
Keyboard dismissal collapses an empty draft; nonempty drafts grow to their
content up to 160px and then scroll internally. Downward touch intent in the
conversation dismisses the keyboard with passive listeners, preserving native
scrolling and horizontal drawer gestures. The drawer brand starts a new chat.
Keyboard motion follows WindowInsetsAnimation rather than a final-inset jump.
Only 160ms color and opacity transitions. No entrance loops, typing simulation,
glows, or particles. Reduced motion disables transitions. File changes reload
the preview through server-sent events without a framework or build tool.

## 7. Responsive and accessibility
48px web / 48dp native minimum controls, visible focus, named icon controls,
semantic landmarks, dialog Escape dismissal, safe-area composer padding. Mint
always has dark ink. Secondary text must reach 4.5:1 on its surface. Prompt text
wraps; long messages use overflow-wrap:anywhere. Browser preview supports light
and dark with a persisted theme. Native follows system night mode.

## 8. Scope and verification
The browser is a localhost-only text-chat companion, using the repository's
pinned provider adapters and Codex refresh/catalog code. Device sign-in follows
CodexAccountAuth.java; credentials are AES-GCM encrypted under a protected local
state directory. It does not bridge phone tools, schedules, subagents, or virtual
displays into the browser. Android retains those capabilities, and now publishes
real display lease states for its inline worker cards. Secret-bearing APIs enforce
local Host, same-origin and custom-header checks, with bounded requests.
Verification includes source-matched protocol fixtures, browser interaction and
visual checks, runtime tests, bundle build, and native Java compilation. Real
account authorization and cloud speech need user credentials. No phone install
or native device visual verification is claimed.
