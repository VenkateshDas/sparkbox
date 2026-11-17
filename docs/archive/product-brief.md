# Sparkbox product brief and data schema

> Historical: the product brief given to the designer before v1. The product and data model it describes were built; current behaviour is in the [README](../README.md) and [architecture.md](architecture.md).

For the product designer. Companion to the design canvas: https://claude.ai/artifact/YDW2SdgL3eqCPC8aCHPfsr (eight draft screens, sample data).

## 1. What Sparkbox is

Sparkbox is a personal learning and knowledge app for one person, on Android. You save things you read, watch and hear from anywhere on your phone. The app turns what you saved into something you remember, use and build on. Think of a feed like Instagram or LinkedIn for your own saves, an Anki-style review, and an assistant that answers questions from what you have saved.

It exists to improve the owner's work and personal life, not to hoard links. Saving is easy. The hard part is remembering, applying and connecting. The product's job is to close that gap.

### Who it is for

One user, a working AI engineer who also saves health, mindset and life-skill content. They read in short bursts on a phone, often on the move. They already keep an Obsidian vault of markdown notes, and the vault is the source of truth.

### Design principles

1. Calm over compulsive. No streaks, no leaderboards, no per-save notifications, no overdue counters. Progress tracks the user's own goals ("pursuits"), never the app's engagement.
2. Finite sessions. Daily review is capped and ends with a done screen. No infinite scroll guilt.
3. Question first. Cards lead with a question that opens a curiosity gap, then reveal the answer on tap.
4. Know, then use. Every saved thing can become a small planned action, and the result is recorded. Remembering is not the finish line.
5. Own words win. The user's own sentence and own cards are first-class, not extras.
6. Honest about confidence. Show how sure the source and the app are (low, medium, high), and mark claims as believe, testing or doubt.
7. Offline first. Everything must work with no network. Show sync honestly.

### Why these choices (evidence in one line each)

- Retrieval practice and spacing improve long-term memory. Review needs the answer shown afterward.
- Curiosity raises memory for the answer. Question-first cards use this.
- Rereading creates an illusion of knowing. Confidence rating before reveal helps calibration.
- If-then plans help follow-through, with modest effect.
- A notification costs a few seconds of focus. Keep one daily digest.
- Review backlogs make people quit. Cap daily load and hide debt.
- Weak evidence, used only as small options: teaching back, incubation ("sleep on it"), audio review on walks.

## 2. Core concepts (shared vocabulary)

| Term | Meaning |
|---|---|
| Capture | Something the user just saved through the share sheet, widget, tile or voice. |
| Unit | Any note shown as a card in the feed. A Unit has a kind: source, claim, topic, person, answer, learning. |
| Source | A Unit made from one capture (article, video, post, image, PDF, voice note). |
| Claim | A single idea in the user's own words with evidence. Replaces the old "topic page of claim tables". |
| Card | One review prompt (question and answer). Extracted by the agent or written by the user. |
| Pursuit | A goal the user is working toward, in their words. Three to five at a time. |
| Lane | Craft (work, professional) or Life (personal). Every Unit may belong to one lane. |
| Action | A small planned use of a Unit, with an if-then sentence and an outcome. |
| Reflection | A one-minute evening check-in. |
| Parked question | A question the user leaves overnight. It returns next morning as a curiosity card. |
| Connection | A short agent-written note linking two Units. |
| Digest | The single daily notification. |
| Agent | The Claude process on the user's Mac that reads captures and writes extractions, claims and weekly summaries. |

Status of a Source:

- captured: saved on phone, not yet in the vault as a note.
- ingested: a note exists, extraction not finished.
- processed: extraction done (summary, key notes, topics, cards).

## 3. Core flows

1. Capture and arrival. User shares a link. A card appears in the feed as "captured". Later it moves to "ingested", then "processed". Design a quiet way to show this change.
2. Morning Spark (about 3 minutes, capped). One curiosity card, 5 to 8 recall cards, one Apply pick. Ends with a done screen.
3. Browse the feed. Newest first. Filter by lane. Lenses: Latest, Inspire (resurfaced old items), Try (units marked to try).
4. Read a Unit. Layered detail from summary down to full text. Write "in my words", create a card from selected text, plan an action, ask about it.
5. Ask. Free-text question. Answer cites claims and Units. Actions: keep as a claim, make cards, teach it back, sleep on it.
6. Apply. Plan an if-then action, later mark did it, not yet or dropped. Add a margin note. Turn a shaky part into a card.
7. Evening Reflect (about 1 minute). Tick what was used. Park one question.
8. Weekly review. Per pursuit: what moved, claims added or challenged, open question, one connection.

## 4. Screens and what each needs

Draft visuals exist for the first eight. The last four are named but not drawn.

### Feed (drawn)
- Header: title, count of Units.
- Lane filter: All, Craft, Life.
- Spark strip: cards due today (capped), time estimate, number of Actions waiting, Start button.
- Lens tabs: Latest, Inspire, Try.
- Card: source name and age, title, curiosity question, why-saved line, lane chip, status chip, actions (Keep, card count, Ask). Captured cards show a waiting message and no actions.
- States: empty vault, nothing new, offline, sync behind, very long titles.

### Unit detail (drawn)
- Layers in order: summary, key notes (each with confidence), why I saved this, in my words (input), cards (extracted and own), linked Units, full extracted text (collapsed), original link, attachments, margin notes.
- Actions: Plan to try, Ask about this. Selecting text offers: make card, highlight, ask.
- States: extraction partial or failed, source had only a title, gated content, long text.

### Spark / review (drawn, interactive)
- Progress (n of N), lane chip, source name, question, optional confidence rating (guess, unsure, sure), Show answer, then grades with next interval (again, hard, good, easy).
- Footer: open source, flag bad card, sleep on it, edit card. Shows daily cap, never an overdue count.
- States: nothing due, cap reached, finished, card edited, card flagged.

### Apply (drawn)
- Planned, done, dropped lists. Each item: Unit title, lane and pursuit, if-then sentence, status buttons, margin note.
- Done items offer "turn the shaky part into a card".

### Ask (drawn)
- Chat. Answer shows cited claims and Units with status and source count. Actions: keep as claim, make cards, teach it back, sleep on it.
- States: no relevant sources found (say so, do not invent), low-confidence sources flagged, offline (agent unavailable).

### Knowledge: claims (drawn)
- List of claims in the user's words. Each shows lane, status (believe, testing, doubt), evidence count, challenged-by count, last revisited. Topics and People are folded below.

### Reflect (drawn)
- Tick list of today's planned actions, a "nothing used" option, a parked-question input, done.

### Week (drawn)
- Card per pursuit: counts saved, claims added, challenged, actions tried. One short agent summary and one open question. One connection card.

### Not drawn yet
- Capture confirmation (share sheet result, "saved on phone").
- Pursuits setup and edit (3 to 5 goals, optional target date).
- Inspire lens (resurfaced and connection cards, thumbs more or less, shuffle).
- Settings (daily card cap, digest time, new cards per day, lanes, sync status).
- Teach mode (explain a claim, agent asks one follow-up, saves your explanation).
- Audio review (hands-free question and answer for walks).
- First-run onboarding and the backlog recovery plan.

## 5. Behavior rules the design must respect

- Daily review cap default 8, user adjustable. When behind, new cards pause. Never show a count of overdue cards. A backlog is paced out over days with a known end date.
- One notification per day, the digest, at the user's chosen time. None per capture.
- Every card shows its source and a way to open it.
- Cards from low-confidence extractions are not created automatically.
- Progress bars track pursuits. No streaks.
- Touch targets 44 px or larger. Works offline. Dark mode expected.
- Sync honesty: show one of three states per capture: saved on phone, synced to Mac, processed.
- The agent may be slow or offline. The phone must remain useful with only what has already synced.

## 6. Data model

### 6.1 Storage reality

- The vault is a folder of markdown files with YAML frontmatter, synced between phone and Mac by Syncthing. Only two vault folders matter here: `Sources/` and `Knowledge/`.
- Syncthing creates conflict copies when two devices edit the same file. So the rule is single writer per file.
  - The agent on the Mac writes notes (sources, claims, topics, answers, weekly digests, connections).
  - The phone never edits those notes. It writes only its own append-only event log: `.sparkbox/events/<device_id>.jsonl`.
  - The agent reads every device's events and folds them into notes. The phone computes review schedules and other state by replaying all event logs.
- Designers do not need to design this, but must know: user changes appear on the phone at once, and appear in vault notes later, after the agent runs.

### 6.2 Entity summary

| Entity | Lives in | Written by |
|---|---|---|
| Source note | `Sources/<Category>/<year>/<date> - <title>.md` | Phone writes the capture entry, agent writes the note |
| Claim note | `Knowledge/Claims/<slug>.md` | Agent, from user events |
| Answer note | `Knowledge/Answers/<slug>.md` | Agent, from a "keep answer" event |
| Topic and Person notes | `Knowledge/Topics/`, `Knowledge/People/` | Agent (existing, kept) |
| Pursuits | `Knowledge/Pursuits.md` | User, edited in app through events |
| Cards from extraction | Inside source note, section "Recall questions" | Agent |
| Cards by the user | `.sparkbox/events/*.jsonl` (card_created) | Phone |
| Reviews, actions, reflections | `.sparkbox/events/*.jsonl` | Phone |
| Weekly digest, connections | `Knowledge/Weekly/<week>.md` | Agent |

### 6.3 Unit (the shape the feed and detail screen read)

One row per note, built by scanning frontmatter. Not stored separately.

| Field | Type | Notes |
|---|---|---|
| id | string | vault path of the note, stable |
| kind | enum | source, claim, topic, person, answer, learning |
| title | string | |
| lane | enum or null | craft, life |
| status | enum | captured, ingested, processed (sources only) |
| summary | string | two to three lines |
| key_notes | list of {text, confidence} | confidence: low, medium, high |
| why_saved | string or null | the user's own words |
| intent | enum or null | try, learn, reference, inspire |
| curiosity_question | string or null | question shown on the feed card |
| pursuit_ids | list of string | zero to two |
| tags | list of string | |
| topic_ids, person_ids | list of string | |
| captured_at | datetime | |
| updated_at | datetime | |
| source_url | string or null | |
| source_name | string | domain or app |
| author | string or null | |
| via | enum | share_sheet, widget, tile, voice, telegram |
| attachments | list of {path, mime} | |
| linked_unit_ids | list of string | |
| in_my_words | string or null | latest value from events |
| keep | boolean | user marked it worth keeping, makes extracted cards live |
| extraction_quality | enum | full, partial, title_only, failed |
| body | markdown | full extracted text |

### 6.4 Source note frontmatter (exists today, plus new fields)

Existing: `type: source`, `source` (url), `author`, `captured` (date), `via`, `topics`, `people`, `processed` (boolean).

Existing body sections: Raw Content, Why I captured this, Claude's Extraction (Key claims with confidence, Topics identified, People mentioned, Relevance flags).

New fields: `summary`, `intent`, `lane`, `pursuits`, `curiosity_question`, `extraction_quality`. New body section: `### Recall questions` (two to four Q and A pairs, only from medium or high confidence claims).

### 6.5 Claim

| Field | Type | Notes |
|---|---|---|
| id | string | slug |
| statement | string | the title, one idea in the user's words |
| status | enum | believe, testing, doubt |
| lane | enum | craft, life |
| pursuit_ids | list of string | |
| evidence_unit_ids | list of string | |
| challenged_by_unit_ids | list of string | |
| explanation | markdown | |
| origin | enum | answer, manual, weekly |
| created_at | datetime | |
| last_revisited_at | datetime | |
| revisit_due_at | datetime or null | |

### 6.6 Card

| Field | Type | Notes |
|---|---|---|
| id | string | |
| unit_id | string | where it came from |
| claim_id | string or null | |
| origin | enum | extracted, user |
| type | enum | qa, cloze |
| question | string | |
| answer | string | |
| source_confidence | enum or null | low, medium, high |
| state | enum | active, suspended, retired |
| created_at | datetime | |

Schedule state, derived by replaying reviews: `due_at`, `interval_days`, `ease`, `reps`, `lapses`. Algorithm starts as SM-2. Every review is logged so a better scheduler can replace it later.

### 6.7 Review event

| Field | Type | Notes |
|---|---|---|
| card_id | string | |
| ts | datetime | |
| device_id | string | |
| self_rating | enum or null | guess, unsure, sure |
| grade | enum | again, hard, good, easy |
| duration_ms | integer | |

Derived for display: calibration (how often "sure" was right), cards recalled this week, per pursuit.

### 6.8 Action (Apply)

| Field | Type | Notes |
|---|---|---|
| id | string | |
| unit_id | string | |
| pursuit_id | string or null | |
| if_text | string | "When I ..." |
| then_text | string | "I will ..." |
| status | enum | planned, done, dropped |
| planned_at | datetime | |
| resolved_at | datetime or null | |
| outcome_note | string or null | |
| spawned_card_ids | list of string | |

### 6.9 Pursuit

| Field | Type | Notes |
|---|---|---|
| id | string | |
| name | string | the user's words |
| lane | enum | craft, life |
| description | string or null | |
| target_date | date or null | |
| active | boolean | |

### 6.10 Reflection and parked question

Reflection: `date`, `used_action_ids` (list), `nothing_used` (boolean), `note` (optional).

Parked question: `id`, `text`, `origin` (reflection, ask), `resurface_on` (date, usually next morning), `answered_by` (claim or answer id, optional).

### 6.11 Answer (from Ask)

`id`, `question`, `answer_md`, `cited_unit_ids`, `cited_claim_ids`, `created_at`, `kept` (boolean), `became_claim_id` (optional). Only kept answers become notes.

### 6.12 Connection and weekly digest (agent output)

Connection: `id`, `unit_a`, `unit_b`, `reason` (one sentence), `created_at`, `feedback` (more, less, or null).

Weekly digest: `week_start`, list per pursuit of {saved, claims_added, claims_challenged, actions_planned, actions_done, summary, open_question}, plus connection ids.

### 6.13 Settings (on device)

`daily_card_cap` (default 8), `new_cards_per_day`, `digest_time`, `quiet_hours`, `lanes` (names), `dark_mode`. Plus sync status per device: last synced, pending changes.

### 6.14 User event types (the phone writes these)

`capture_added`, `keep_set`, `in_my_words_set`, `card_created`, `card_edited`, `card_flagged`, `review_logged`, `action_planned`, `action_resolved`, `reflection_saved`, `question_parked`, `claim_status_set`, `answer_kept`, `connection_feedback`, `pursuit_saved`, `settings_changed`.

Every event: `id`, `ts`, `device_id`, `type`, `payload`.

### 6.15 Example records

Unit (feed card):

```json
{
  "id": "Sources/AI Systems/2026/2026-11-11 - Stop Coding, Start Building a Factory.md",
  "kind": "source",
  "title": "Stop coding, start building a factory",
  "lane": "craft",
  "status": "processed",
  "summary": "Builders shift effort from writing code to building the system that produces it.",
  "why_saved": "Idea to try",
  "intent": "try",
  "curiosity_question": "What changes when you build the system that makes the code?",
  "extraction_quality": "partial",
  "captured_at": "2026-11-11T10:00:00Z"
}
```

Review event:

```json
{"id":"e_01","ts":"2026-11-12T07:41:10Z","device_id":"phone","type":"review_logged",
 "payload":{"card_id":"c_204","self_rating":"unsure","grade":"good","duration_ms":9200}}
```

Action:

```json
{"id":"a_17","unit_id":"Sources/.../Spec-Driven Development.md","pursuit_id":"p_sparkbox",
 "if_text":"When I finish a Sparkbox spec","then_text":"I will start a new chat and ask it to find holes",
 "status":"planned","planned_at":"2026-11-12T07:50:00Z"}
```

## 7. Open questions for the designer and owner

1. Confirm the three to five real pursuits (the canvas uses placeholders).
2. Naming: Craft and Life, Spark, Apply, Reflect, Claims are working names.
3. Swipe triage on new captures (keep, archive, ask): add now or later?
4. How should Inspire mix old items, connections and shuffle without feeling like a slot machine?
5. How to show "saved on phone, synced, processed" quietly, without anxiety?
6. Should People notes become a lightweight "who shaped me" view, or stay hidden?

## 8. Scope notes

- Mac agent runs outside the app. The app may show its work but cannot start it, except by syncing.
- Out of scope for the first version: social features, sharing, multi-user, iOS, streaks, a graph view.
- Sample numbers and text in the canvas are placeholders, not real data.
