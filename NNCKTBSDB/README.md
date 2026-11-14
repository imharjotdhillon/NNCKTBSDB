# Punjabi Book Translator (Phases 1–5 minus OCR: reader, translation, queue, glossary, verification, export, backup)

Native Android app (Kotlin, Jetpack Compose, Material 3, Room, DataStore) for reading PDF/EPUB books and
translating them English<->Punjabi (Gurmukhi) through a MANUAL Gemini workflow: the app prepares prompts, you
send them to Gemini yourself, then paste the reply back for review. No API, no automation, no uploads.

## STATUS — READ THIS FIRST

**The Android code has NOT been compiled or run.** It was written without an Android SDK, Gradle, or access to
Maven repositories. Expect a few compile errors on the first build; paste them back to be fixed.

What WAS verified here (JVM, real Kotlin compiler, `core/` package only):
- **96 unit tests pass** (`app/src/test/.../`): text utilities, prompt builder, response parser, batch splitter (Phase 2);
  batch planner, job state rules, progress maths, save engine and a lifecycle simulation (Phase 3); glossary matching, term checker, name candidates, verification parser, corrections/state rules, stale-text guard, chapter-summary parser, search escaping/snippets, revision-keeping save (Phase 4); export audit and planner, EPUB writer (unzipped and XML-parsed in the tests), backup format and restore planner (Phase 5). They run through a small
  JUnit stand-in, not Gradle. **Mutation check:** I deliberately broke behaviours (idempotent save, partial-save status,
  percent rounding; Phase 4: approved-only glossary, whole-word matching, "silence = verified", stale-text guard, never lowering a major state, resetting verification on changed text) and the tests failed each time, so they do detect those regressions.
- `TextUtils.resolveZipPath` resolves every manifest/spine/TOC link inside the three sample EPUBs in `samples/`.

What was NOT verified: everything Android (Compose UI, Room incl. the v1→v2→v3→v4 migrations, Paging), the EPUB extractor (needs
Jsoup, unreachable here), the PDF extractor (PDFBox-Android), and a **real round trip with Gemini**. The lifecycle tests use an
in-memory stand-in for the database: they prove the *decisions* the repositories rely on, not Room itself.

## Build an APK in the cloud (GitHub Actions)
1. Create a free GitHub account and a new **private** repository. Upload the contents of this folder (including the hidden `.github` folder).
2. Open the repository's **Actions** tab > "Build APK" > **Run workflow**.
3. When it finishes (several minutes), open the run and download **PunjabiBookTranslator-debug-apk** under Artifacts. Unzip it and copy `app-debug.apk` to your phone.
4. If the run fails, open the failed step (or download the `build-log` artifact) and send me the error text.
Not tested: this workflow has never run.

## Build (Android Studio, on your PC)

1. Install Android Studio (current stable); accept SDK platform 35.
2. *File > Open* this folder; let Gradle sync (it downloads Gradle 8.9 from `gradle/wrapper/gradle-wrapper.properties`;
   there is no `gradlew` script, the IDE does not need one). Accept AGP/Gradle upgrade offers if shown.
3. Run on an emulator or USB-debugging phone (Android 8.0+). APK: *Build > Build APK(s)*.
4. Unit tests: right-click `app/src/test` > Run, or `gradle :app:testDebugUnitTest`.
5. Test files to import: `samples/*.epub` (see `samples/README.txt`), plus your own PDFs.

Pinned versions (`gradle/libs.versions.toml`): AGP 8.6.1, Gradle 8.9, Kotlin 2.0.20, KSP 2.0.20-1.0.0,
Compose BOM 2024.09.03, Room 2.6.1, Paging 3.3.2, DataStore 1.1.1, Navigation 2.8.1, Jsoup 1.18.1,
PDFBox-Android 2.0.27.0; compile/target SDK 35, min SDK 26. Chosen as a compatible set; not verified against live repos.

## Phase 1: reader (summary)
Library with import (SHA-256 duplicate detection), covers, search/sort, resume, details, delete, failed-import retry.
Text extraction into stable paragraph blocks (P0001...). Reader with Original/Translated toggle, light/dark toggle at
top-left, font/spacing/margin/brightness settings, chapters, go-to-page, bookmarks, saved position, PDF Text/Pages views.
INTERNET permission is used only by the in-app Gemini window (see below).

## Phase 2: translation workspace (new)
Reader > **Translate** opens the workspace:
1. **Setup**: direction (English<->Punjabi, with Swap; stored per book), style (Literary default, Natural, Faithful,
   Formal; per book default + per-request override), scope: PDF *pages from here* (1/5/10/20, default 5), *custom pages*;
   EPUB *current chapter*, *from here* (~5k/10k/20k characters, ends on a paragraph boundary); or a *paragraph-id range*
   for both. Option to skip already-translated paragraphs and to include neighbouring context (2 before, 1 after).
   Shows range, paragraph/word/character counts, estimated reply size and context. Oversized selections are split into
   parts automatically; each part is submitted manually; nothing is truncated or dropped.
2. **Prompt**: generated from the real paragraph ids and text (source is embedded as escaped JSON, so quotes/newlines
   are safe). Copy prompt, Open Gemini (opens the in-app Gemini window; the app never reads the page).
3. **Paste**: large text field; the clipboard is read only when you tap "Paste from clipboard".
4. **Read the reply**: tolerant parsing (code fences, surrounding chatter, alternate keys, truncated replies,
   a bad quote in one entry, plain-text `P0001:` fallback). Failures keep your pasted text and offer a **repair prompt**
   asking Gemini to reformat without changing the translation. Nothing is invented to fill gaps.
5. **Review**: original and editable translation per paragraph, with flags for missing / empty / duplicate / recovered
   entries; unmatched entries shown, not discarded. Saves only when you tap Save.
6. **Save** is one transaction and idempotent: re-importing the same reply changes nothing (no duplicates, no revision
   bump); blank entries are skipped; other paragraphs' translations are never touched. Flagged-but-unedited paragraphs
   are saved as NEEDS_REVIEW, otherwise TRANSLATED. The library's "translated %" counts real saved paragraphs.
Database moves to **schema v2** (adds `books.translationStyle`; migration included, no destructive fallback).

## Phase 3: batch translation queue (new)
Open it from the **Queue** tab (pick a book) or from the reader's contents sheet ("Translation queue and progress").

- **Plan batches**: PDF: 1, 5 (default), 10, 20 or a custom number of pages, over the whole book or a page range. EPUB: batches sized by
  text volume (about 5k / 10k / 20k characters or custom), always ending on a paragraph boundary and tending to stop at chapter
  ends. Already-translated paragraphs are skipped (switchable), and paragraphs already in an open batch are never queued twice.
  A batch too long for one Gemini reply is split into numbered parts; nothing is dropped.
- **Queue groups**: Imported/awaiting review, Waiting for your Gemini reply, Failed or incomplete, Prompts ready (not sent),
  Pending, Paused, Completed, Cancelled. **No status ever says Gemini is "translating"**: a batch becomes "waiting for reply" only after
  you copy the prompt or open Gemini, and "completed" only when every paragraph in it really has a saved translation.
- **Actions**: prepare / open prompt, paste reply, review, pause and resume (one batch or all pending), cancel, retry (a retry asks
  Gemini only for the paragraphs that are still missing), remove (record only; saved translations are never touched), clear planned,
  go to text.
- **Independent saving**: each batch saves in one transaction together with its own status. A bad or partial reply never erases
  anything saved earlier; a partial save leaves the batch "incomplete" with the exact number of missing paragraphs.
- **Recovery**: queue state, the prompt, and the reply you pasted are stored in the database. After closing the app, a batch in
  "awaiting reply" reopens at the paste step, and one in "review" or "failed" reopens with your reply re-read.
- **Progress**: computed from paragraphs, never from pages. A page with 10 of 12 paragraphs translated is "partial". The queue shows
  total / fully translated / partly / untranslated pages (EPUB: chapters), paragraphs awaiting review, verified, missing paragraphs,
  and lists of incomplete and needs-review sections that open the reader at that spot.
- Database moves to **schema v3** (new `translation_jobs` table; additive migration; no destructive fallback).

### Phase 3 manual test checklist (on the phone)
1. Import a multi-page PDF. Queue tab > the book > keep 5 pages > Add batches. Expect N batches of 5 pages (last one shorter), all "Pending".
2. Add again: expect "already in queued batches", no duplicates.
3. Prepare prompt on batch 1: the batch should move to "Prompts ready". Copy the prompt: it should move to "Waiting for your Gemini reply".
4. Paste a real Gemini reply, Read, Save: the batch becomes Completed and the progress card rises. The percentage must match paragraphs saved.
5. Next batch: paste a reply with the last few entries deleted. Save: batch becomes "Failed or incomplete: N paragraphs still untranslated". Retry missing: the new prompt contains only those paragraphs.
6. Paste the same reply twice and save twice: second save must say it was identical.
7. Paste gibberish: batch becomes Failed, your text is kept, repair prompt offered. Kill the app (swipe away), reopen the queue, open the batch: your pasted text is still there.
8. Kill the app while a batch is in review: reopen, the review is rebuilt (unsaved edits are lost).
9. Pause a pending batch, resume it; cancel one; remove one. Confirm saved translations remain readable under Translated.
10. Open "Show incomplete": tap a page, the reader opens on that page.

## Phase 5: export and backup (new; OCR NOT built)
- **Export** (Queue tab > book > Tools > Export): EPUB or PDF, "Translation only" or "Original and translation". The screen shows a completeness audit first.
  An incomplete book is refused unless you tick "Export anyway"; then every untranslated paragraph shows "[Not translated yet]". Text is never invented or
  copied from the original. Pages with no selectable text are left out and counted. Verification is reported but not required.
- **EPUB** is a real EPUB 3 (mimetype first and stored, nav + OPF, one XHTML per chapter, embedded Noto Sans Gurmukhi, `lang="pa"`). The writer is tested on the JVM
  (unzipped and XML-parsed). It has NOT been opened in a real e-reader here: open the result in at least one (e.g. Google Play Books, Calibre) and check Gurmukhi.
- **PDF** is drawn with Android's text layout and the bundled Gurmukhi font, selectable text, A4, page numbers. Untested on a device: check conjuncts/matras, Latin fallback
  (the Gurmukhi font may lack Latin glyphs and rely on system fallback) and page breaks.
- **Backup / Restore** (Settings): one JSON file with translations, glossary and chapter summaries per book, keyed by the book's file fingerprint and paragraph ids. It does not contain
  books, credentials or Google data. Restore needs the same book imported first; empty paragraphs are filled, differing ones are kept unless you tick "replace" (old text goes to history).
  Restored translations are marked Not verified; revision history and findings are not included in backups.
- **OCR is not implemented.** I could not confirm a Gurmukhi-capable on-device OCR engine and its data files from here, so I did not build on a guess. Scanned pages stay excluded.

## Choosing the AI chat (Settings > AI chat)
Gemini (default), ChatGPT, Claude, Copilot or Perplexity. Only the website the in-app window opens changes; the prompts, the reply parsing and everything else are
the same, and it is still manual copy and paste with no API. Untested with anything but the design target (Gemini). Each site's sign-in may be refused inside an app window
(use "Open in browser"), and each site's terms and privacy settings apply to what you paste. Button and help texts say "the AI" or the chosen site's name.

## In-app AI window (formerly "Gemini window"; added after Phase 4)
"Open Gemini" now opens Google's Gemini website inside the app (a plain WebView). It is deliberately **manual**: the app injects no script, does not
read the page, fill the box, press Send or read the reply, and never touches cookies or sign-in data. You tap "Copy prompt", paste into Gemini
(long-press), send, use Gemini's copy button on the reply, tap Back, then "Paste from clipboard". Non-Google links open in your own browser, and there is
an "Open in browser" fallback. **This adds the INTERNET permission** (used only by that window; the app makes no requests of its own).
Untested: Google often refuses sign-in inside embedded WebViews ("disallowed_useragent"); if that happens use "Open in browser". Fully automatic
send/retrieve would need the Gemini API or page automation, which this project's spec excludes.

## Phase 4: translation intelligence (new)
Everything below still goes through the manual copy/paste Gemini workflow. Nothing is sent or read automatically.
- **Glossary** (Queue tab > book > Tools > Glossary): terms with type, note and an *Approved* flag. Only approved terms that actually
  appear in the batch are added to a translation prompt (capped at 40; the screen says how many were left out). "Where it appears"
  lists every paragraph containing the term and whether its saved translation uses the approved rendering; "Mark as Needs review"
  flags the others without touching their text. Changing a term's translation remembers the old one so stale paragraphs are findable.
  "Suggest names" proposes recurring capitalised names from English text (it cannot work for Gurmukhi; add those by hand).
- **Approved examples:** up to 3 paragraphs you edited or that were verified are included in prompts, only to keep names/terms consistent.
- **Chapter summaries:** write one yourself or ask Gemini (long chapters are summarised in parts, each updating the previous). A saved
  summary is added to that chapter's translation and verification prompts. Nothing is saved until you tap Save.
- **Verify with Gemini:** pick a chapter or paragraph range; the app prepares a review prompt with original + translation pairs. The reply is
  read leniently (truncated or slightly malformed JSON is salvaged). A paragraph becomes *Verified* only if the reply says it is fine;
  paragraphs the reply does not mention stay *Not verified*. If a paragraph's translation changed after the prompt was made, its result is skipped.
- **Pending corrections:** each finding shows original, current translation and Gemini's suggestion; you Accept, Edit or Keep mine.
  Nothing changes without your choice.
- **Editing and history:** every translated paragraph in the reader has Edit. Each change keeps the old text; "Earlier versions" restores one.
  Re-importing a different reply for an already-translated paragraph also keeps the old text as a revision. Saved text is Unicode-normalised (NFC).
- **Search** (magnifier in the reader, or Tools > Search): original and translated text, with highlighted snippets; tapping opens the reader there.
- Schema is v4 (`MIGRATION_3_4`, additive). Replacing a translation now uses insert/update, not REPLACE, so revisions are never cascade-deleted.

### Phase 4 manual test checklist (on the phone)
1. Glossary: add "Amrit" -> your Punjabi spelling (Approved). Prepare a prompt for a batch containing it: the prompt screen says "1 glossary term" and the text shows a GLOSSARY section. A batch without the name shows none.
2. Add a Draft (unapproved) term: it must never appear in a prompt.
3. Change a term's translation, tap "Where it appears": paragraphs still using the old rendering show as needing review; flag them; the reader shows "Needs review".
4. Reader > Translated > Edit a paragraph, save, Edit again > Earlier versions > Restore. Blank text must be refused.
5. Verify a chapter with a real Gemini reply. Check the counts line; paragraphs omitted from the reply must say "not covered".
6. Edit a paragraph after preparing a verification prompt, then paste the reply: that paragraph must be reported as skipped.
7. Pending corrections: Accept one (text changes, revision kept), Keep mine on another (text unchanged, state returns to Verified).
8. Chapter summaries: generate for a chapter, edit, Save; the next translation prompt for that chapter contains it.
9. Search a name in English and a Punjabi word; tap a result.
10. Upgrade test: install Phase 3, translate something, then install this build over it. Existing translations must still be there (migration v3->v4).

### Limits and assumptions (honest list)
- The size limits (6,000 output tokens per request, 30,000 prompt tokens) and token estimates are **conservative
  guesses**; Gemini's real limits are not known to the app. Adjust `SizeEstimator` once you see real behaviour.
- Not yet done: OCR for scanned PDFs (with manual correction). Everything else in the spec's five phases exists, but only the pure logic has been compiled and tested. Android share-sheet import of a reply is not implemented (paste only).
- Phase 3 limits: (1) the 6,000-token reply limit is a guess, and with Punjabi output it is conservative: a realistic five-page window
  (~9,000 characters) will often be split into 2 prompts. Raise `SizeEstimator.MAX_OUTPUT_TOKENS_PER_REQUEST` once you see how much Gemini
  returns in one reply. (2) Your pasted reply is stored when you tap "Read the reply", not while typing, and edits made in the review screen
  are not stored until you save. (3) There is no background work: nothing runs between sessions, so WorkManager is not used for the queue.
  (4) Pages with no selectable text are excluded from page counts and progress until OCR exists. (5) Going from the reader to the queue
  closes the reader (so position saving can't clash), so Back from the queue returns to the previous screen, not the reader.
  (6) Glossary, summaries, verification and search are reached from the queue screen's Tools row, not a separate bottom-bar tab.
- Phase 4 limits: (1) a verification in progress (prompt + pasted reply) is kept only while the app is open; prepare it again after closing. Saved results and findings are stored. (2) Latin glossary terms match whole words ignoring case; Gurmukhi terms match as substrings, so a short term can match inside a longer word. (3) Name suggestions and approved-example picking are heuristics. (4) Search uses SQL LIKE (case-insensitive for ASCII only), no ranking. (5) "Verified" means Gemini's pasted reply (or your own review) said so; it is not proof of correctness. (6) Room schema JSON for v4 could not be generated here; Android Studio creates `app/schemas/.../4.json` on first build. (7) A prompt for Gurmukhi source text gets the glossary only for terms that literally occur in it.
- No text-range selection inside the reader; use pages, chapters or paragraph ids.
- Pages without text (scanned) are skipped with an explanation.
- Unescaped double quotes inside one entry make that entry unreadable as JSON (it is reported missing; the raw text stays
  in the paste box and the repair prompt can fix it).
- Phase 1 limits still apply (Readium not used; no pinch-zoom; EPUB images/inline italics not shown; legacy-font
  Gurmukhi PDFs extract badly; global rather than per-book reading preferences; English-only UI strings).

## Licenses
Noto Sans Gurmukhi: SIL OFL 1.1 (`app/src/main/assets/licenses/`). Jsoup MIT; PDFBox-Android Apache 2.0; AndroidX/Compose Apache 2.0.
