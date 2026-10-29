---
name: project-blueprint
description: "Use when working on a project: read/update its blueprint."
version: 1.0.0
---

# Project blueprint (Hermes Remote Blueprint)

Each Hermes project can have a **blueprint**: a flow diagram (Start, Feature, Flow, Decision, End boxes with arrows, like draw.io) and a task board (columns like Docs / Todo / Working / Done with cards, like Trello). The user sees and edits the same blueprint on the phone (drawer > project > blueprint icon), and changes from either side show up on the other within seconds.

## Always, when working on a project
1. **Read the blueprint first**, before planning or coding:
   `hermes-remote-bridge blueprint md --here` (from inside the project folder) or `blueprint md -p "<project name or id>"`.
   No blueprint: carry on normally; offer once to create one if the work is more than a quick fix.
2. Use it as the plan: the flow says how the product fits together, the board says what is done, in progress and next. Respect card checklists, priorities and due dates; ask before contradicting them.
3. **Keep it current while you work**, as you go, not only at the end:
   - starting a card: `card move <card> Working`, `--assignee hermes` if it is yours
   - finishing a step: `card check <card> <item text>`
   - done (tested): `card move <card> Done`
   - new work found: `card add -c Todo "<title>" --item ... --tag ...` (search first, no duplicates)
   - a new feature or flow: `node add --kind feature --after "<node>" "<label>"`, link its card with `--node`
   - log decisions worth remembering: `log "<one line>"`
4. Cards and nodes can be named by id or by a unique part of the title. If a name is ambiguous the command lists the ids.

## Control
- `control: shared` (default): the user leads. Update status, checklists and add found work; do not reorganise columns, delete cards/nodes or rewrite the flow without asking.
- `control: hermes`: set only when the user explicitly asks you to take over (`blueprint control hermes`). Then you own the board and diagram: plan the flow, create and order cards, move them, tidy with `layout`. The user mostly reads and makes small adjustments; respect them and never undo a change the user made. `blueprint control shared` hands it back.

## Commands
```bash
B="hermes-remote-bridge blueprint"
$B list                                  # blueprints, plus projects without one
$B md --here                             # read (Markdown: flow, board, recent activity)
$B show -p shop --json                   # full JSON (ids, coordinates)
$B create -p "<project>"                 # new blueprint for a Hermes project (asks nothing)
$B card add -p shop -c Todo "Login page" --tag auth --item "form" --item "API" --priority high --due 2026-11-30 --node Login
$B card move -p shop login Working       # card by id or unique title part; column by name or id
$B card check -p shop login form         # tick a checklist item (uncheck to undo)
$B card edit -p shop login --body "..." --assignee hermes --priority medium
$B card rm -p shop login                 # ask the user first
$B node add -p shop --kind feature --after Start "Login"   # kinds: start end feature flow decision note
$B node link -p shop "Checkout" End [label]
$B node edit -p shop Login -t "Sign in" --body "note"
$B column add -p shop Review | column rename -p shop review "QA" | column rm -p shop review -c Todo
$B layout -p shop                        # tidy the diagram top to bottom
$B log -p shop "Chose Stripe over PayPal (fees)"
$B op -p shop '[{"op":"card.add","title":"A"},{"op":"card.move","id":"A","column":"Done"}]'  # batch, all or nothing
$B trash -p shop                         # moves to the trash (restorable from the app)
```
Run from the project folder and `-p` can be dropped (`--here` is implied when no project is named).
Priorities: low medium high. Assignee: me, hermes. Dates: YYYY-MM-DD. Every change is logged with who made it.

## Don't
- Don't edit the JSON files by hand (`blueprint path`); use the commands, which merge with the phone's edits.
- Don't mark a card Done before the work is verified.
- If the command is missing, the PC has an old Hermes Remote bridge: say so (update it from the phone: PC bridge > Update).
