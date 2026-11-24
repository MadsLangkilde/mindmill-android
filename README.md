# Mindmill for Android

The phone companion to [Mindmill](https://github.com/MadsLangkilde/mindmill-macos) on the Mac. The Mac is the server; the phone talks to it over one TLS WebSocket on
the same network or over Tailscale. The contract is [`docs/remote-api.md`](https://github.com/MadsLangkilde/mindmill-macos/blob/main/docs/remote-api.md) in the [Mindmill repo](https://github.com/MadsLangkilde/mindmill-macos).

## Build

Needs JDK 21 and the Android SDK (`local.properties` with `sdk.dir`, not committed).

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # JVM unit tests
```

Kotlin, Jetpack Compose and Material 3 in one `app` module (`dk.langkilde.mindmill`), minSdk 29, targetSdk 36,
compileSdk 37 (the current AndroidX and OkHttp releases need it).

## Pairing

1. On the Mac: Settings → Remote → turn it on → Show Pairing Code.
2. On the phone: Scan the QR code (or paste the `kb://pair?…` invite under "Paste the invite instead").
3. Check the 6-digit code and tap Pair. An expired or used code says so, with Scan again.

The phone can be paired with several Macs and talks to one at a time. For each it keeps the hosts, port, certificate
fingerprint, device token and the phone's own scope there, encrypted with an Android Keystore key
(`store/PairingStore.kt`; a phone paired before this moves its one Mac over by itself). It trusts only the certificate
whose SHA-256 matches that Mac's pinned fingerprint; pairing the same Mac again replaces its entry. Pair another Mac
from Connection → Pair another Mac or the Mac row in the drawer; the others stay. Switching (the Mac row's menu,
once there are two, or Connection) disconnects, connects to the other Mac and starts every screen over, so nothing
of the last Mac stays on screen; not while the phone records a meeting. Removing a Mac asks first. If a Mac removes
the phone, the app goes back to pairing and offers the other Macs. The phone never asks for, receives or stores an
API key.

## Connection

`remote/ConnectionMachine.kt` decides what the phone shows (tested without a network): a quiet "Connecting to
<Mac>…" on the first try, "Retrying in Ns" with Retry now after a failure (backoff 1, 2, 4, 8, 15, 30 s), and after
four failed tries "Can't reach <Mac>" with the reason and what to check (same Wi-Fi or Tailscale, Mindmill open, Remote
on). It starts over and tries at once when the network changes or the app comes to the front. Screens wait for the
connection instead of failing; a request made while it can't reach the Mac waits a few seconds, then fails with a
sentence and Retry.

## Permissions

| Permission | For |
|---|---|
| Camera | Scanning the pairing code |
| Microphone, notifications | Voice and live meetings, kept alive with the screen off (foreground service) |
| Nearby devices (Bluetooth) | Talking through a headset or the car |
| Screen capture (asked each meeting) | Sending screenshots to the meeting notes |
| Install unknown apps | Installing test builds; the Test screen walks you through allowing it |

## Navigation

Everything is reached from the **drawer**, opened with the menu button at the top left (on a wide window, 840 dp
and more, it stays open beside the content). From the top:

1. **Plan | Build**, a compact switch with an icon each, showing how many wait for you in the other mode.
2. The mode's places, as the Mac's sidebar has them (`app.structure`, in the user's order, under the Mac's
   headings, with counts and "needs you" badges): Plan's Home, Today, Planning and Work rows; Build's Overview,
   Factories, Testing. Then **More** (Ask Mindmill, Voice, All actions, Strategies, Knowledge, Tags, People,
   Projects, Connection, and every area of the Mac).
3. **New session**, then the mode's sessions in the current project: every one that needs you first, then the most
   recent; **All** opens the Sessions screen with search, Older and Archived.
4. **Project**: the project and scope picker (below), and the **Mac** with its connection state; its menu switches
   Macs and leads to Connection.

The top bar has the menu, Back when there is something to go back to, the screen's headline, a session's own menu
(⋮) in a session, and **Ask Mindmill** (the sparkle) at the far right on every screen. Connection problems and
notices show under the top bar, in the layout.

`ui/Navigation.kt` holds where the user is as a pure, tested model (`NavState`), drawn by Navigation 3
(`ui/NavHost.kt`) and saved across rotation and process death. Each place picked in the drawer keeps its own
stack; Plan and Build each keep theirs. Anything opened from a screen (More, Home cards, search, a notice, a
notification) goes on top of the current place, never in place of it; a session or New session picked in the drawer
takes the place of the session on screen. System back and the predictive back gesture close the drawer first, then:

1. pop the current place (detail, then the list, then the place it was opened from, such as More);
2. from another place's root, go to the mode's start (Home in Plan, Overview in Build) as it was left;
3. right after a Plan/Build switch, from the start, go back to the mode switched from, once;
4. otherwise close the app.

Picking the place you're in takes it back to its root. On a wide window a list keeps its detail beside it; back
closes the detail first. Search boxes clear before back leaves the screen; dialogs and menus close themselves.
`DrawerModel` (in `ui/Drawer.kt`) picks the drawer's places and sessions and is unit tested.

## Look

Navy and warm cream, from the app icon, with a navy-night dark theme; coral only marks what needs you, teal what
runs, green what's done (`ui/Theme.kt`, contrast checked in `ThemeContrastTest`). Connection → Look switches to the
wallpaper's colours on Android 12 and later.

## Screens

The **project** (in the drawer): the Mac's scopes by project (the project-wide scope first), the phone's own choice
kept per Mac and independent of the Mac's unless **Switch the Mac too** is ticked (or "Make … current on the Mac");
Manage projects… opens Projects.

- **New session** (Ask Mindmill, the drawer's New session, Home's quick action): what it can do and things to try.
  Nothing is made until you write or say something; then `session.new` starts a session in the mode's space and the
  phone's project with it as the first message (in Plan an assistant session, which can do anything Mindmill can),
  and the session opens in place of this screen.
- **A session**: the transcript, with the steps Claude takes (commands, files read, searches) folded into one
  "Working… · n steps" row between messages, showing the latest step; tap it to see every step and command. A
  reply that asks ends with a ```` ```choices ```` block: its options are buttons that wrap (the recommended one
  filled), active only on the latest reply while the session waits for you; a tap sends that option (`choices multi`:
  tick several, then send). Older replies' options show as plain text. When Claude was refused a tool, a card says
  what and offers **Allow for this session** (`session.allow`). The session's ⋮ menu archives it or shows it on the
  Mac; in Build it also has **Test**: one "Deliver …" per target of the session's project (`test.status`, then
  `test.deliver` with the session), or what a target still needs and the Test screen. The delivery arrives as usual
  and installs from Test. Grouping and choices are `remote/ChatRows.kt` and `remote/ReplyParts.kt`, both tested.
- **The message box** (every session and New session): type and the button sends; empty, the button starts **live
  chat**. Live chat listens, sends what you said (`spoken: true`, so the Mac asks Claude for a short spoken answer;
  older Macs ignore it), says a short acknowledgement at once (varied, never the same twice in a row, in English or
  Danish after `voice.config`'s language), reads the reply aloud as it arrives (no Markdown or code; options read out
  briefly), and listens again. Talk over it or tap Stop to interrupt; End or the button stops it. From New session the
  first thing said starts the session. It keeps going with the screen off (the microphone notification has End). The
  turn-taking is `voice/LiveChatMachine.kt`, tested without a phone.
- **Sessions** (Plan): every Plan conversation, however it was started (Sessions, Research, Ideas, a discussion, a
  follow-up, a meeting, a goal, an entry, Ask), grouped by kind as on the Mac, with "needs you" and running state;
  "Older · n" and "Archived · n" folded at the bottom. Archive or restore with a swipe, a long-press, or the
  session's ⋮ menu; Undo shows in the bar at the bottom. **Sessions** (Build): by project, helpers under their lead.
  New session opens the New session screen.
- **Home**: a dashboard: the day and how many things need you, what needs you as tiles (the Mac's `overview`), Ask
  Mindmill, quick actions (talk, meeting on the Mac or the phone, new session, all actions), the meetings still to
  come today with Start on Mac, then sessions that wait for you or are working.
- **Backlog**: your or everyone's work items; open one to change state, assign, comment. New work item.
- **Voice** (More, or Home's Talk): a hands-free conversation with the brain (`voice.start`), which reaches every
  action; action cards show what was done and its data.
- **More**:
  - **All actions**: every action of `actions.list`, by area and searchable, each with a form made from its JSON
    schema; risky ones ask first. "Every area of the Mac" lists the areas, so each is one tap away.
  - **Facts**, **Knowledge** (sections, search everything, new entries), **Discussions**, **Tags**, **People**.
  - **Follow-ups**: on you, waiting on others, or all; done, drop, note, and new ones.
  - **Meetings**: start one on the Mac (from the calendar or now) and follow it live: notes, transcription, stop,
    file. Or record on the phone. Notes and summaries by day.
  - **Factories**: status, progress, questions; start, pause, resume, stop; new, edit, duplicate and delete with
    the full editor (`factory.config.*`).
  - **Pull requests**, **Today**, **Reminders**, **Goals**, **Strategies**: from the Mac's actions, with the
    area's other actions a tap away.
  - **Test**: builds to install, links and previews to open, changes to review, and the Mac's test set-up.
  - **Projects**: the Mac's projects with their locations and scopes (`projects.config.*`). New project or edit
    one: title (and id when new), locations picked by browsing the Mac's folders (`folders.list`) and named, scopes
    with the locations each works on, the scopes it also shows, the ones it reads knowledge from, and which is
    project-wide. Removing a scope deletes it on save (its entries move to the project-wide one), after asking; the
    Mac's own objections show as it words them. Parsing and checks are in `remote/ProjectsConfig.kt`.
  - **Connection**: your Macs (connected, switch, remove, pair another), voice options, wallpaper colours.

Lists pull to refresh, load with placeholders, say what to do when empty, and offer Retry when a request fails.
Finished actions say so at the bottom of the screen (in the layout, not over it); deleting asks first. If the Mac's
Mindmill is older than the phone's, screens say "Update Mindmill on the Mac".
