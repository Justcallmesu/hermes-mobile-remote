# Changelog

Releases are git tags; the version in the app comes from the tag. `git log v0.9.0..vX.Y.Z` is the
authoritative history — this file only summarises what is worth knowing before upgrading.

## Unreleased

- Keep connected in the background (Settings, on by default): after you swipe the app away, the
  phone stays connected while Hermes works on the PC: a running turn, a subagent, a background
  process, or an active `/loop` or `/heartbeat`. It keeps going for 5 minutes after the last of
  them ends (1 or 15 minutes can be chosen). Turn it off to stop the connection as soon as the
  reply is done.
- A dropped connection (Wi-Fi change, airplane mode, a sleeping screen) reconnects at once and
  resumes every running or waiting chat, not only the open one.
- Bridge: when the phone drops, the bridge keeps its connection to Hermes open for the device for
  `park_seconds` (default 300; 0 turns this off), so a quick return picks the same session back
  up. Needs the updated bridge.
- Bridge: the phone may call the read-only `session.control.read`, which shows `/loop` and
  `/heartbeat` state. Needs the updated bridge.
- A PC that has both a bridge and a gateway sign-in can be linked into one entry tagged
  "Gateway + Bridge". The app uses the bridge and falls back to the gateway when the bridge cannot
  be reached, then returns to the bridge when it answers again. The connection row says "via
  Bridge" or "via Gateway". After a gateway sign-in the app offers to pair the PC's bridge too;
  the host menu has *Link with...* and *Unlink*.
- Chats resumed from the phone are now labelled `desktop` in Hermes' live session, like new ones
  already were (they were labelled `tui` before). The stored chat row is not changed.

## v1.25.1

- Fixed a crash (app force closing) while a chat was streaming: a new run could reuse an
  item id already on screen.
- The Scheduled (clock) icon in the chat list now shows how many scheduled jobs are active.
  Paused jobs are not counted.

## v1.25.0

- Pictures returned by tools (vision_analyze, screenshots, browser captures) now show as images
  in the tool row instead of a wall of base64 text. Tap to open full screen.
- While the agent works, a *Steer / Queue* switch sits beside Send. Steer folds your message into
  the running reply (as before); Queue sends it as the next turn, run once the current reply ends.
  The choice is remembered.

## v1.24.0

- Background processes: a chat's background jobs (dev servers, builds, watchers the agent started
  in the background) now show under the transcript, like the subagent panel: the command, how long
  it has run, exit code once it ends. Tap one to read its latest output; *Stop* kills it on the PC.
  Updates every few seconds. Needs the updated bridge.

## v1.23.1

- *Show more* adds the number set in Settings > Chats per group (3, 5 or 10) instead of a fixed
  amount; v1.23.0 wrongly forced 5.

## v1.23.0

- Drawer groups: *Show more* now adds 5 chats at a time (was 10), and once a group is expanded a
  *Show less* button folds it back to its first page.
- Bridge doctor no longer reports "no firewall" when the PC runs inside a container (e.g.
  systemd-nspawn): the host's firewall can't be seen from there, so it now warns and says which
  port to allow on the host.

## v1.22.0

- New project: a project saved without a folder used to vanish (it was created, but the app hid
  it). It now shows in the project list as *No folder yet*; tap it to add one.
- Folder picker: the folder icon in the project's path field browses the PC's folders (Up, Home,
  New folder, git repos marked), so you no longer type a path from memory. An empty name takes
  the folder's name. Needs the updated bridge.
- System Monitor: every chart can be tapped or dragged to read the exact time and values. New
  Network and Disk I/O charts.
- Internet speed test of the PC (download, upload, ping, jitter, against Cloudflare) with a
  history chart. Needs the updated bridge.
- Claude usage popup shows more: rate tier, % left with the exact reset time, warning colours,
  lock reason, extra-usage credits, when it was last checked, a 24h / 7d / 30d history chart and
  each context slice's share of the window.

## v1.21.0

- Tap the Claude usage badge to open a usage popup instead of jumping to the System Monitor:
  session (5 hours) and weekly limits with reset times, this chat's context window as a stacked
  bar by category (system prompt, tools, memory, conversation...), and *Compact now* with an
  optional focus topic. The badge now shows Claude's mark with the session % beside it. The
  System Monitor is still one tap away from the popup. Context needs the updated bridge.
- System Monitor range and sort chips use the theme accent instead of Material purple.
- Move to project works once the bridge is restarted on the PC (it was still running the old
  allowlist).

## v1.20.0

- Move to project: long-press a chat and choose *Move to project* to file it under any project
  that has a folder, like the desktop's move. Needs the updated bridge.
- The Group by / Sort by / Status / Project chips now highlight in the theme's accent colour
  instead of Material's default purple.
- PC glance shows up reliably after pairing or a cold start. The app remembers which features the
  bridge offers and retries the bridge's hello a few times, so a slow network wakeup no longer
  hides the PC page until the next restart.
- Claude usage ring beside the connection badge: the outer ring is the 5-hour session, the inner
  ring is the week, and the number is the session %. It turns amber at 70% and red at 90%. Tap it
  to open the PC page. Shown only when the bridge has the PC monitor.
- On a tablet or in landscape, Enter sends the message and Shift+Enter adds a new line. On a phone
  in portrait, Enter still adds a new line.

## v1.19.0

- Switching between paired PCs no longer loses the profile dropdown. A reply from the PC you just
  left (its profile list, chats, scheduled jobs, settings or vault) could land on the one you
  switched to and replace its data; every reply is now checked against the PC and profile it was
  asked for.
- Subagents. When the agent delegates work, a card under the chat shows each subagent: its task,
  current tool, tool count, model and running time, then how it ended. *Stop* ends one subagent
  without stopping the chat. Opening a chat mid-run catches up on the ones already running.
- Bridge: the phone may call `subagent.list` and `subagent.interrupt` (needs the updated bridge).
- Sign in to a gateway: the phone can now connect straight to a gated Hermes gateway (`hermes serve`
  behind its own login), with no bridge and nothing installed on the PC. In Add host, choose
  "Sign in to a gateway" and type its address. The gateway's login page opens in a browser tab; you
  type the password there, never in the app. Tokens are kept in the Android Keystore and refreshed
  automatically. Plain `http` is accepted only for Tailscale or LAN addresses. Pairing a bridge is unchanged.
- Gateway hosts hide the features that need the bridge: the project
  description (IDEA.md), chat file download, the bridge status card, and the default-model and
  approval settings. Notifications while the app is closed do not work for a gateway host yet.
- Updates can be installed before pairing: the pairing screen shows "Version X is available" when a
  newer app is out; tap it for what's new and Update now. Before, the update was only offered in the
  drawer and Settings, which an unpaired phone never reaches.
- Chat: each message shows when it was sent, under it (the time today, the date for older ones).
- PC switcher: a gateway host no longer offers Re-pair, which only applies to a paired bridge.

## v1.18.0

- PC Glance is now part of the app. The drawer header has a PC page (live CPU/RAM/temperature,
  disk, network, per-core bars, history from 1 hour to 1 year, alerts), a process list where you
  can stop or force-stop a process, and Claude plan usage (session and weekly limits with reset
  times and history). Two home-screen widgets: *PC stats* and *Claude usage*. Update the bridge
  on the PC for these; the separate PC Glance app and agent can be uninstalled.
- Terminal: a shell on your PC from the phone (the `>_` icon), with colours and a key bar (Esc,
  Tab, Ctrl, ^C, arrows). Turn it off with `terminal = false` in the bridge's config.toml.
- Re-pair: when a pairing breaks, Settings > Re-pair this PC (or the PC's ⋮ in the PC switcher)
  takes a fresh code and keeps the PC's chats, drafts and name; no more delete and pair again.
  When the PC refuses the phone's token, the app now says so and offers Re-pair instead of
  "can't reach your PC". On the PC: tray > Devices > (phone) > Re-pair, or
  `hermes-remote-bridge pair <name> --replace`, which issues a new code and revokes the old one.
- Tray (Linux and Windows): *Run doctor...* shows every check with its verdict.
- Typing `/` and picking a skill or command from the suggestions no longer drops the slash (the
  skill ran as plain text before).
- Chat top bar: removed the New chat and Pin buttons; they are in the drawer and the chat's ⋮ menu.
- Bridge: no longer crash-loops on start when Tailscale is installed but stopped; it listens on
  the remaining addresses instead of failing with "Cannot assign requested address".
- Bridge on Windows: a project description saved from the phone keeps its line endings as written.

## v1.17.0

- Projects can be managed from the phone, like the desktop's project menu: rename, change the
  folder, edit the description, or delete. Tap the pencil next to a project in the project picker,
  or long-press a project group in the drawer (grouped by project) and choose Edit project.
  Deleting removes the saved project from Hermes only; its chats, files and folder stay on the PC.
- New project: the folder is now optional, and you can write a description. Like the desktop's
  New project, the description is saved as `IDEA.md` in the project folder (Markdown is fine).
- Bridge: allows the phone `projects.get/update/delete/add_folder/remove_folder/set_primary`, and
  adds `GET/PUT /v1/projects/{id}/idea`, which only ever reads or writes `IDEA.md` in that
  project's own folder. Update the bridge on the PC for these.

## v1.16.0

- Chat files: files Hermes hands over in a reply (`MEDIA:` reports, spreadsheets, PDFs) and files
  sent with a message now show as cards. Tap a card to open it in a phone app (Sheets, a PDF
  viewer, ...); the arrow saves it to Downloads/Hermes Remote. Before, they were plain text, or
  "📎 name" with nothing to tap.
- Messages with a file no longer show Hermes' "--- Context Warnings ---" block, and files sent
  from the desktop (``@file:`path` ``) show up correctly.
- Pictures saved outside Hermes' image folders (e.g. a chart in a project) now load in the chat.
- Bridge: new `GET /v1/sessions/{id}/file`. It serves only files that chat's own messages name.
  Update the PC bridge (`git pull`, then restart it) to use this.

## v1.15.1

- Drawer: long-press a project folder (grouped by Project) → "New chat in <project>" starts a chat
  with that project already selected ("Home" starts one with no project).
- Project picker: a search field filters projects by name or folder.

## v1.15.0

- Drawer is compact so more chats fit: Scheduled and Settings are icons in the header, New chat is a
  round button next to search, and the header and profile row are slimmer.
- Vault moved into Settings (Hermes on your PC → Vault); Back from the Vault returns to Settings.

## v1.14.1

- Chat: a message with a picture no longer draws the picture on top of its text; the picture sits
  above the message.
- Picture viewer: a Download button next to Close saves the original picture to
  Pictures/Hermes Remote (Android 10+), or to a file you pick on Android 8/9.

## v1.14.0

- Drawer: "Sort by Last updated" now also orders project groups, so the project with the newest reply
  is on top (it used to stay A-Z). Other sorts (created, tokens, cost) order groups the same way.
- Custom group order: long-press any group header (project, date or status) to move it to the
  top, up, down or to the bottom. The order is saved on the phone, per PC, profile and grouping,
  and kept after restarts. View sheet → "Group order" switches between "Follow sort" and "Custom".
- Drawer cache: chats, projects and the custom order are stored on the phone and shown at once on
  start. They sync on start, when the app comes back to the screen, while chatting, after each
  reply and every ~30 s. If a sync fails, the saved list stays on screen.
- Reconnect: projects are fetched as soon as the live link is up, so chats no longer all land in
  Home until a manual refresh.

## v1.13.1

- Composer: Shift+Enter on a hardware keyboard inserts a newline at the cursor (replacing any
  selection). Enter still inserts a newline; Ctrl+Enter still sends.

## v1.13.0

- Chat stays in sync. Opening (or coming back to) a chat while Hermes is still replying no longer
  shows the prompt a second time at the bottom with part of the reply under it: the running turn
  is matched to the prompt Hermes already stored, and only the not-yet-stored part of the reply
  is added.
- Leaving the app or turning the screen off mid-reply no longer leaves the reply cut off. Coming
  back pings the link and reconnects if it went stale, every finished turn re-reads the chat from
  the PC, and the final reply text replaces a partial one. No pull-to-refresh needed.
- Edit a sent message: long-press it → Edit. Like the desktop, Hermes drops that message and
  everything after it and answers the edited text. Available once the message's reply finished.
- Vault page (drawer → Vault): see the logins, cards and addresses Hermes can fill in, add new
  ones, edit (re-enter the secret; the new version replaces the old) and delete. Secrets are never
  shown on the phone. Password-manager items are listed read-only; unlocking them stays on the PC.
- Bridge: `vault.list`, `vault.sources`, `vault.add`, `vault.remove` on the relay allowlist.
  Update the PC bridge for the vault page.

## v1.12.0

- Files in chat. The paperclip next to the picture button opens the system file picker (any
  type: PDF, docs, spreadsheets, code; up to 6 per message, 10 MB each). Each file is staged on
  the PC through Hermes' `file.attach` and the message carries its `@file:` reference, as on the
  desktop. Sent files show as "📎 name" in the chat, also in history.
- Scheduled: a "New job" button. Give it a name, a schedule (`every 30m`, `every day 8am`,
  `weekdays at 9am`, a cron expression) and what Hermes should do. Jobs made from the phone run
  with the profile's tools and save their result locally; editing, deleting, scripts and other
  delivery targets stay on the PC.
- Bridge: `POST /v1/cron/jobs` (prompt jobs only, delivery forced to local) and `file.attach`
  on the relay allowlist. Update the PC bridge for both.

## v1.11.1

- Scrolling far up an old chat no longer freezes and then crashes. Pictures are decoded at screen
  size (1080 px) and kept in memory instead of at full size on every scroll, and huge
  messages, code blocks and command outputs are clipped for display (Copy still copies all
  of it). Parsed replies are reused rather than parsed again each time they scroll back into view.
- Bridge: a phone hotspot (tethering) stays trusted. Android gives a hotspot a new random MAC
  each time it starts, so the bridge saw a new network each time and asked to trust it again,
  piling up duplicates. Networks whose gateway has a random (locally administered) MAC are now
  trusted by their connection profile (SSID + password). Old duplicates collapse into one entry
  on the next connect. Routers with a real MAC still need that exact router.

## v1.11.0

- Pictures in chat. The picture button in the composer opens the photo picker (up to 6 per
  message, no storage permission). They are sent with the message, scaled to 2048 px JPEG first,
  and Hermes looks at them with its vision tool as on the desktop. A picture-only message works.
- Pictures show in the chat: yours (also ones sent from the desktop), and images Hermes hands back
  as `MEDIA:` lines. Tap one to open it full screen; pinch to zoom.
- Sudo, secret and password-manager prompts can be answered on the phone: unlocking a vault
  (1Password, Bitwarden), saving a site login, a 2FA code, a sudo password or a setup secret. A
  masked card slides up like the desktop's. The value goes once over the paired link and is never
  stored on the phone; the transcript only says "sent" or "skipped". A notification says when one
  waits.
- The drawer grouped by project no longer reorders while a reply streams. Projects you created keep
  their order, auto-found repos follow A-Z, Home last.
- Bridge: `GET /v1/media?path=` returns an image a chat shows. Only image files inside the
  `images/`, `screenshots/` or `cache/` folders of the profiles the phone may use; symlinks out
  and `..` are refused. Needs the updated bridge (run `./install.sh` on the PC).

## v1.10.0

- Hermes profiles. If your PC has more than one Hermes profile, the drawer shows the one in use
  under the PC name; tap it to switch. The chat list, Settings (approval mode, default model) and
  new chats then belong to that profile. Each PC remembers its choice, and a notification opens
  its chat in the right profile. Needs the updated bridge (run `./install.sh` on the PC); with an
  older bridge the app stays on the default profile as before.
- The chat header names the Hermes profile the chat runs in (next to its status), when the PC has
  more than one profile.
- Bridge: `GET /v1/profiles` lists them, and the sessions, approvals and default model endpoints
  take `?profile=` so they act on one profile. The default profile works exactly as before. A new
  `allowed_profiles` key in `config.toml` limits which profiles the phone may use (empty: all).
- Scheduled jobs. *Scheduled* in the drawer lists the cron jobs of the Hermes profile in use with
  their schedule, next and last run and last error; tap one for its recent runs. Run now, pause and
  resume work from the phone; creating and editing jobs stays on the PC.
- Bridge: `GET /v1/cron/jobs`, `GET /v1/cron/jobs/{id}/runs` and `POST /v1/cron/jobs/{id}/pause|resume|trigger`,
  all per profile. Prompts, scripts and delivery targets are not sent to the phone.
- Bridge: a request naming a profile is authenticated first, so an unpaired caller gets 401 and
  learns nothing about which profiles exist.
- Linux tray: a revoked device no longer stays in the Devices menu.

## v1.9.3

- A failed turn (for example "The conversation is too long for this model. Run /compress, then
  /retry." with its details) now shows in red when you open the chat on the phone, like the
  desktop's error banner. Before, it only showed if the phone was watching when it failed.

## v1.9.2

- The drawer no longer jumps around while a reply streams. Live updates now re-read only the
  newest chats and merge them in, instead of re-paging the whole list while it was shifting.
- Chats with the same timestamp keep a fixed order between refreshes.

## v1.9.1

- Enter on a hardware keyboard is a new line again (back to how it was before v1.9.0). Use the
  send button to send.
- The drawer shows 3 chats per date or project by default, like Hermes desktop, then "Show more".
  Change it in Settings > Chat list (3, 5, 10 or All).
- Settings regrouped into clear sections: Connection, Hermes on your PC, Appearance, Chat list,
  About and This device. The accent grid is folded until you tap Accent.
- Markdown: code fences are parsed line by line, so 4-backtick fences, ~~~ fences, indented
  fences and inline triple backticks no longer break the rest of a message.

## v1.9.0

- 30 accent colours, grouped by hue: several blues (sky, ocean, indigo, navy...), greens, reds,
  purples, oranges and neutrals. Your current accent is kept.
- Hardware keyboards: Enter sends, Shift+Enter starts a new line.
- New project: in the project picker, "+ New project" creates a project on the PC from a name
  and a folder path (e.g. ~/Workspace/my-app) and starts the chat there. Needs the updated bridge.
- Project folders in the drawer remember being collapsed after the app restarts.

## v1.8.1

- Theme is now two separate choices: Mode (System, Light, Dark, Midnight) and Accent
  (Gold, Ocean, Forest, Violet, Rose). Every accent works in every mode, so there are no
  more duplicate same-colour themes. Your previous pick carries over.

## v1.8.0

- Theme picker in Settings.
- Default model in Settings: one model the PC uses for every new chat, kept across restarts.
  Picking a model inside a chat still only changes that chat.
- Drawer: each project has a clear folder header and its chats are indented under a guide line,
  so it is easy to see where a project's chats start and end.
- Linux tray app (bridge/linux/tray.py), like the Windows one: status, checks,
  start/stop/restart, networks, pair/revoke devices, logs, start at login. install.sh adds it to
  the app menu.

## v1.7.0

- New chat in a project: on a new chat, a "No project" pill above the model picks one of the
  PC's projects. The chat starts in that project's folder and shows under it on the desktop.
- Drawer groups now show 5 chats first, then 10 more per "Show more".
- Updates are checked every time the app opens or comes back to the foreground (and hourly while
  open). Repeat checks are conditional, so they don't eat GitHub's rate limit.
- Thinking: Opus replies now carry their thinking text once the PC's Claude subscription plugin
  is patched; the app shows it in the collapsible "Thought" block (no app change needed).

## v1.6.0

- Messages you type on the PC now appear on the phone as soon as the turn starts, not only the
  reply. Before, the phone showed the PC's message only after reopening the chat.
- Your own messages from the phone still show once (no duplicate bubble).

## v1.5.0

- Long chat groups show 15 chats, then a "Show 15 more · N left" row. Search and the drawer
  filters still cover every chat: the app loads the whole list and only draws it in pages.
  The open chat is never hidden, and a new search, filter or PC starts each group at one page.
- Copy buttons you can see, for when long-press isn't handy (mouse, PC, emulator):
  - "Copy" under every finished reply copies the whole answer as markdown.
  - Every code block shows its language and a "Copy" button that copies only the code.
- Updates are checked automatically while the app is open: at start and every 6 hours.
- A new version shows a strip under the chat's top bar (✕ hides it for that version) and a row
  at the top of the drawer.
- Tapping either opens "Update to X": what changed in every version between yours and the new
  one, then Update now.
- After an update the app shows "What's new" once, with the changes since the version you had.
- Settings → Changelog lists every version's changes, read from the app itself (works offline).

## v1.4.0

- Messages you steer into a running task now show your own words, not Hermes' wrapper text.
- Opening a chat no longer floods it with Hermes' own notes shown as your messages. Rows Hermes
  writes itself are now shown the way the desktop shows them:
  - "Background process finished" and "Background agent work finished" become one-line notices,
    using the desktop's own label when Hermes stored one.
  - Model and personality switches, and resumed turns, become notices too.
  - Context-compaction summaries, the "[STILL IN PROGRESS …]" restatement, and system notes are
    hidden.
- PC switcher redesigned like the account switchers in Instagram and Facebook:
  - The drawer header shows the PC in use: a letter avatar, its name, and how many PCs are paired.
  - Tap the header to open "Switch PC": one row per PC with its avatar and host, and a radio mark
    on the PC in use. "Add PC" sits at the bottom.
  - Long-press the header to jump straight to the next PC.
  - Rename, Forget, and Switch are in each PC's ⋮ menu, or long-press its row.
- Chat-list groups (date, project or status) collapse and expand when you tap their header. The
  header shows how many chats the group holds. Collapsed groups are remembered per grouping, and
  a search always opens every group so no match is hidden.
- Release notes in the app and on GitHub are now this full changelog, newest first, so updating
  across several versions shows everything that changed. The update card scrolls.

## v1.3.0

- Chat drawer view options (filter icon next to search), matching the desktop app: group by date,
  project or status; sort by last updated, created, status, tokens or cost; filter by status and
  by project. Projects come from the desktop's project list. Pinned chats stay on top; the choice
  is remembered.
- Bridge: allows the read-only `projects.tree` call. Update the bridge on the PC too.

## v1.2.0

- Markdown tables render as real tables: aligned columns, header row, borders, and sideways
  scrolling for wide tables instead of raw `| a | b |` text.

## v1.1.0

- Pair several PCs and switch between them from the drawer; each PC is named by its hostname.
- Pair from a QR picture (screenshot or photo) instead of the camera.
- Ctrl+Enter sends from a hardware keyboard; Enter adds a newline.

## v1.0.0

**Breaking: update the bridge and the app together.** A 1.x bridge does not serve 0.x apps, and a
1.x app cannot use a 0.x bridge. Re-run `./install.sh` (or `install.cmd`), then update the app from
its System tab.

The phone is now another window of the Hermes the desktop app runs. The bridge no longer drives
Hermes' HTTP runs API; it relays one authenticated WebSocket to the desktop's own `hermes serve`
(found through Hermes' spawn ledger, or started when the desktop is closed).

- Replies stream token by token on both screens, wherever the turn was started. No more polling
  or "running on another device".
- The session list shimmers for sessions working anywhere, like the desktop sidebar.
- Approvals and clarify questions can be answered on either screen; answering on one clears the
  other.
- Every slash command works, including `/retry`, `/undo`, `/btw` and `/background`, because it
  runs against the live agent.
- Model and reasoning are set on the chat, as the desktop does.
- Background notifications now cover turns started on the PC in chats opened on the phone.
- New animations: steady text reveal, typing indicator, shimmering rows, spring entrances.
- Removed: the Desktop (RDP) page, the `/v1/runs`, `/v1/models`, `/v1/commands`, `/sync` and
  `/v1/desktop` endpoints, and the installer step that enabled Hermes' API server.
- `config.toml` keys from 0.x are accepted and ignored.

## v0.9.0

Mobile-first UI overhaul. Navigation became a bottom bar (Agent, Sessions, Desktop, System); on
phones the session list is a modal drawer, and at 720 dp and wider it is a fixed pane beside the
chat.

## v0.8.1

Packaging fix for the release workflow: `apksigner` labels its output `Signer #1` in some
build-tools versions and `V2 Signer` in others, so the verification step now matches on the
certificate digest instead of the label.

## v0.8.0

- Reasoning-effort picker, sent per run as `model_options.reasoning_effort`.
- Slash commands and skills, executed on the PC through Hermes' TUI gateway.
- In-app updates from tagged GitHub Releases, with the APK digest and signing certificate checked.
- The application id became `io.github.nideta231.hermesremote`.

## Earlier

The first public commit: the Android app, the bridge, the installer, and the LAN-over-pinned-TLS
and Tailscale connection model described in [docs/SECURITY.md](docs/SECURITY.md).
