# Sparkbox: feedback on design round 2

For the product designer. Screens reviewed: Feed, Unit, Hub, Spark (revealed), Apply, Claims, Evening Reflect, plus DESIGN.md.

## Overall

The structure is right. Feed card family, Hub page, Capture Map, Claims and Apply are approved as information architecture. The visual system and several screens need a third round. Please address section 2 and 3 before the next delivery.

## 1. What to keep

- No floating Capture button; one primary action per screen.
- Status chip and lane chip on feed cards.
- Kind filter and topic / person / entity cards sharing one frame.
- "Made 1 note, 3 topics (1 new)..." strip opening the Capture Map.
- Hub: your take, appears in, connected, claims, open questions, follow.
- Spark reveal: grades with intervals, Edit, Park 24h, Flag.
- Apply: if-then layout and "turn the shaky part into a card".
- Claims: status chips and evidence bars.
- "Ask" instead of a model name.

## 2. Must fix

1. **Overdue wording.** Claims shows "Scheduled (2d overdue)". We never show overdue. Spark header says "Card 3 of 7" and "Daily batch of 7 completed": contradictory.
2. **Invented features and jargon.**
   - Remove "Rest Cadence / screen cooldown" (Reflect): a sleep tracker we do not have.
   - Remove "Agent Observation: find relevant papers" (Hub): the app does no web research.
   - Rename: "Synthesized Vault Insight", "Vault Synthesized", "Graph Affinity", "Curiosity vector", "SRS Interval", "Update retention estimate", "Archetype", "Config".
   - Replace "caught up with your sanctuary" and "no synthetic urgency" with plain copy.
3. **Spark answer.** Remove the nested screenshot ("Kinetic Pulse Launch / Review revealed"), the "Fig 1.2" caption and the mono `source_unit_088 // line 142`. Keep the answer; put the source quote behind a "Source" expand.
4. **Sync label.** "Synced 8m ago to Mac" is untrue: the app only reads the vault folder and cannot know the Mac received anything. Use "Vault checked 8m ago". Remove the profile avatar from every screen: the app has no accounts.
5. **Apply and Reflect.** Header says "Note Detail" (copy-paste) and there is no tab bar. Apply's Add button overlaps the footer text. Say how the user reaches both screens.
6. **One token table.** DESIGN.md conflicts with itself: primary `#4edea3` vs `#10b981`; surface-container `#1b2027` vs `#1c232d`; outline `#86948a` vs `#222d3d`; on-surface `#dee2ed` vs `#f1f5f9`. Screens use `#4edea3`. Deliver one final table.
7. **Doc vs screens.** DESIGN.md bans blur, glow and mono labels, but screens use backdrop-blur everywhere, decorative glow circles behind Apply/Reflect headers, a pulsing sync dot, and 9-14 mono uses per screen (labels like "Synced...", "CRAFT . Pursuit:", "IF/THEN", "Zero streaks to maintain"). Make the screens follow the doc.
8. **Label size.** Feed has 15 labels at 10px, 22 at 11px, 1 at 9px. Minimum 12px.
9. **Contrast.** `#86948a` on cards is 5.2:1 (ok). `#64748b` (doc's placeholder/unchecked) is 3.3:1 and disabled `#475569` is 2.4:1: both under 4.5:1 for small text. Replace.
10. **Capture Map truncation.** Names are cut ("Software...", "Eval Harnes...", "Elena Rost...", "Qwen 2...") because "Opens Hub" takes the space. Use a chevron; let names wrap.

## 3. Should fix

- **Feed:** three filter rows before the first card (lane, kind, Latest/Inspire/Try); Spark strip cut at "2 Action...". Use one filter control. Drop the repeated "View Person Hub" links; make the whole card tappable. "Hub" is our working word: use "Open topic / person / entity".
- **Hub:** 7 stacked sections; breadcrumb "Feed > Feed". Remove the Archetype switch (demo toggle) and the inline Reveal Answer.
- **Unit:** URL pill truncated; page still long. Keep summary, key notes and in my words open; collapse the rest.
- **Reflect:** meant to take one minute, has five blocks. Banner copy mentions streaks; remove it.
- **Claims:** "0 challenging / Revisited 4d ago" wraps badly; "challengesattached" missing a space; only one card has a primary button.
- **Samples:** remove real people (Sam Altman, Peter Attia, blog.samaltman.com). Use placeholders.
- **Light mode:** not delivered. The app follows system light/dark.
- **Frames:** screenshots range from 238 to 513px wide and are cut at 1600px tall. Deliver 390x844 frames.

## 4. Missing screens (round 3)

- Spark: question side, finished, cap reached, nothing due.
- Ask (v1 was dropped and never revised). Include: streaming status line, citations, "Not in your knowledge base" state, low-confidence / single-source flag, offline.
- Search (word and meaning-based).
- Browse for topics, people, entities (the vault has about 35 topics and 108 people; needs sort and search).
- States: empty feed, offline, captured only, ingested, agent not finished, one source, possible duplicate.
- Capture failed with Retry / Needs attention.
- Week, Pursuits setup, Settings, Inspire lens.
- Light theme and the final token table.

## 5. Data reality (design for it)

Today the vault has 312 source notes, 35 topics, 108 people, 0 entities, 0 claims, 0 recall questions. Source notes have `description`, not summary; no curiosity question, lane, per-claim confidence or entity list. So:

- Design the "not processed yet" version of Feed card, Unit and Hub (no curiosity question, no key-note confidence, no entities).
- Existing notes must backfill silently. Only new activity creates topic / person / entity cards, or the first run floods the feed.
- Spark is empty until cards exist; design that first-run state, including "write your own card".

## 6. Handoff format

Native Android (Java, code-built views), not web. The current app uses graphite surfaces, system sans, sizes 12/14/16/20/48, 4dp spacing, 48dp targets, light and dark. Either map your design onto these tokens, or propose a new palette and say what changes. Deliver: token table, component list, per-screen specs with all states, light and dark.
