# Sparkbox 1.9.2 phone verification

Install the signed release APK as an update to Sparkbox. It keeps the existing package and signing configuration. Use disposable notes for editing, deletion and interruption checks.

## 1. Full Markdown editing

- In Feed, tap a note's visible **⋮ → Edit note**. Test a Source, Topic and Person. Edit the body and frontmatter, save, reopen, and verify the actual Markdown in Obsidian.
- Open a long note: confirm the editor contains its ending, not just a clipped preview.
- Type a draft, go Back and choose Keep draft. Reopen the same editor. Rotate the phone and repeat.
- With a draft open, change the same note in Obsidian. Sparkbox must refuse the stale save and retain the draft. Copy draft and Reload provide recovery without an automatic overwrite.

## 2. Keep bookmarks

- Keep a Source with no recall cards, a Topic and a Person. Find each through **Kept**, including an older Topic missing from the normal stream.
- Search within Kept, unkeep a result, restart Sparkbox and verify the remaining bookmarks persist.
- Main Feed items must not become pinned. Keep must not boost Spark review order.

## 3. Card management

- Open **Cards** from a Source, Topic and Person/generic note. Add a card to each. Empty lists must still support Add.
- From a Topic, edit a card grouped under a linked Source. Verify its original Source changed, not a copied Topic card.
- Delete an AI-generated card; verify the confirmation names its owning note. Flagged/parked cards remain manageable.
- Study a card stored directly on a Topic. Reopen after restarting and confirm edits/deletions and existing review history persist.
- Try an empty question or answer. The dialog must retain the entered text rather than dismiss it.

## 4. Model selection

- In Settings, open an existing model setting. Search by model/provider name or ID, choose a model, reopen and confirm the selection.
- Scout's list should contain models advertising tools. A role change must not modify another role's setting.
- Try Custom ID and Cancel. Then go offline: cached catalog and current selection must remain usable. A failed Refresh must not erase them.

## 5. Text capture destinations

- **Inbox** remains the default. Confirm text and pasted links still follow normal capture processing.
- Choose **Existing note**, find a note by title/path and save a distinctive paragraph. It should appear unchanged under **My Notes**, once, with capture attribution.
- Choose **New note** with a manual title. Confirm creation in `Knowledge/Topics/`, visibility in Feed and preservation of your wording.
- Repeat with a blank title. AI should generate only the title, retaining your body text.
- Use airplane mode to append text to an existing note and create a manually titled note. Neither should need an AI key/network. A blank-title note should remain pending until AI is available; supply a manual title through Pending if desired.

## 6. Voice capture destinations

- Record a short clip to Existing note, a manually titled New note, and an untitled New note. Check the transcript reaches the selected destination.
- Change destination while recording, then stop from the sheet. Also test stopping from the notification/widget after selecting a destination.
- Record offline, reopen Sparkbox and restore network access. The recording should remain pending until transcription is possible.

## 7. Interrupted work and target changes

- With a disposable queued capture, close/reopen Sparkbox during processing. Verify one append/note, not duplicates. Already checkpointed transcripts/titles should be reused.
- Remove/move the chosen target before its write. The capture must remain pending with a readable error; it must not silently recreate the old path. Select a new destination when no write was attempted.
- Test a duplicate new-note title. The existing note must remain untouched; the new capture gets a distinct filename.
- After a successful direct capture, its pending row should disappear and its note should become available without manually refreshing Feed.

## 8. Article images

- Share an article whose chart contains a fact absent from its body. Inspect the saved Source and generated interpretation for that chart information.
- Repeat with an article containing more than three figures. Let background passes finish; verify all selected figures contribute and later images are not silently dropped.
- Try a lazy-loaded/responsive figure. Inaccessible/unsupported media must leave a readable error rather than pretend it was inspected.

## Verification boundary

Automated checks cover parsing, bookmark collection, direct Topic study, model-catalog filtering, exact-text insertion, duplicate suppression, and before/after/conflicting write recovery decisions. Release compilation and Android lint run locally. No connected phone/emulator was available; microphone behavior, Android scheduling, SAF storage, real model requests, image interpretation quality and UI usability require the checks above.
