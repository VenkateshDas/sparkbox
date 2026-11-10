# Dropping the inbox: design

Request: capture straight into Knowledge instead of a staging inbox, and process the whole page at capture time so nothing waits for `Process inbox`.

## What exists today
- A capture is one entry in a single Markdown file (`Sources/Inbox.md`). It is append-only while capturing, and Sparkbox rewrites it for notes and deletes under a verified-edit journal.
- On tagging, the app now keeps the whole readable page text privately (`files/content/`), summarises with the user's Why and Ideas as instructions, and re-summarises when they change.
- Related, Ask and search already span the inbox and Knowledge/Sources.
- Sparkbox is **read-only** on Knowledge and Sources by design (README, Settings text). The vault agent (`Agents.md` → `Process inbox`) is what turns inbox entries into Source and Topic notes.

## The decision: who writes into Knowledge?
Dropping the inbox means a capture becomes a note, so someone has to write notes into the vault. Options:

| Option | What changes | Cost |
|---|---|---|
| A. Phone writes a Source note per capture | Each capture creates `Sources/<year>/<date> - <title>.md` (frontmatter, `## Raw Content` from the saved page text, `## Why I captured this`, `## My ideas`, summary). No `Inbox.md`. | The app needs write access to the vault folder (today read-only), must follow `Agents.md`'s Source format exactly, and risks conflicts with Obsidian/GSync editing the same files. Process inbox changes to "link new Source notes into Topics". |
| B. Keep a thin inbox, auto-process on the phone | Inbox stays as a queue only; after tagging, the app files the note as in A and removes the entry. | Same write access as A, plus two moving parts. |
| C. Keep the inbox, treat it as a view | No change to storage. The inbox list becomes a filter over "unprocessed" notes. | Cheapest, but the concept stays. |

## Recommendation
A in two steps, because the risky part is writing outside the inbox file:
1. **Step 1 (low risk):** write each capture as its own note under a new folder `Sources/Captures/` only (never touching existing notes), using the saved page text. Ask, Related and search already read Sources. Keep `Inbox.md` working in parallel behind a setting.
2. **Step 2:** once Step 1 is trusted, change `Process inbox` to link and file `Sources/Captures/` notes into Topics, then retire `Inbox.md`.

## Decision needed from the owner
Allow the app to **write** into one vault subfolder (`Sources/Captures/`)? Everything else stays read-only. Until that is yes, nothing here is built.
