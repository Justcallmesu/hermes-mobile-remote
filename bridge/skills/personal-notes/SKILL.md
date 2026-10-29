---
name: personal-notes
description: "Use when the user braindumps, plans or says notes/todo."
version: 1.0.0
---

# Personal notes (Hermes Remote Notes)

Use this for braindumps, plans, todos, "save/remember this in my notes", "what's on my list".
These are the user's Google Keep-style notes. The phone app's **Notes** page shows the same notes, and edits from either side appear on the other within seconds.

Storage: one Markdown file per note in `~/.config/hermes-remote/notes/` (`hermes-remote-bridge notes path`). Use the CLI, not raw file writes, so the header (title, colour, pin, labels, times) stays valid.

## Commands
```bash
N=hermes-remote-bridge
$N notes list                         # pinned first, newest next: <id>  title  #labels
$N notes list -q odoo                 # search title, text and labels
$N notes list --label plans --all     # by label, including archived
$N notes show <id>                    # full note (--json for fields)
$N notes add --title "Weekend" "- [ ] paint\n- [ ] call Kevin" --add-label plans
$N notes add --title "Dump" -         # body from stdin (long text)
$N notes append <id> "- [ ] new item" # add to the end
$N notes check <id> paint             # tick the first checklist item containing 'paint' (--uncheck)
$N notes edit <id> --title T --color yellow --pin --add-label work --remove-label x --archive
$N notes edit <id> - < newbody.md     # replace the whole body
$N notes rm <id>                      # delete (ask the user first)
```
Colours: default red orange yellow green teal blue purple pink brown gray.

## How to work with them
- Braindump: offer to save the ideas as one note: a short title, then bullets or a `- [ ]` checklist. Keep the user's words; don't pad.
- Planning: break goals into `- [ ]` items, one action per line, label by area (`work`, `home`, `personal`). Pin what is active this week.
- Before adding, `list -q <topic>` and append to an existing note rather than making near-duplicates.
- "What's on my list": `list`, `show` the relevant notes, summarise the open `- [ ]` items.
- Never delete or overwrite a whole body without saying what changes; prefer append/check/edit.
- Notes may hold private info: don't paste them into other services unless asked.
- If the command is missing, the PC has no Hermes Remote bridge, or an old one: say so; the update is `hermes-remote-bridge update`.
