package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import photos.sluice.adapter.ui.RunResultView.Count;
import photos.sluice.adapter.ui.RunResultView.Resume;
import photos.sluice.adapter.ui.RunResultView.Tone;
import photos.sluice.application.port.in.CullJobOutcome;
import photos.sluice.application.port.in.CurateOutcome;
import photos.sluice.application.port.in.WaitingReason;
import photos.sluice.application.port.out.CullReport;
import photos.sluice.domain.job.ShardTally;
import photos.sluice.domain.commit.CommitSummary;
import photos.sluice.domain.commit.LibraryBucket;
import photos.sluice.domain.cull.ApplyReport;
import photos.sluice.domain.imports.ImportSummary;
import photos.sluice.domain.model.SortSummary;
import photos.sluice.domain.rescue.RescueSummary;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns what a finished job produced into what the result card says.
 *
 * <p>Static and holding nothing. What a run reported is settled the moment it ended, so reading it
 * needs none of the presenter's own state. That also makes every case here reachable from a test
 * with a hand-built summary and no job at all.
 */
final class RunResults {

    // Two of the six sort buckets are always drawn, however small. A sort that moved no photo is
    // the news on that card. Dropping the row at zero leaves that news to a reader who notices an
    // absence.
    private static final String PHOTOS_SORTED = "Photos sorted";
    private static final String VIDEOS_SORTED = "Videos sorted";

    private static final String DONE = "Done";

    private static final String CONTINUE = "Continue sifting";

    private static final String CEILING_QUESTION = "Sluice stopped this sift because it went far "
            + "past what it was expected to cost. Nothing more has been spent from your provider "
            + "account balance. Continuing on sends "
            + "the sheets it had not reached, under a fresh limit.";

    // Names the folder the record went to. Saying only that it was kept invites the question of
    // where, and this is a directory the reader can open.
    private static final String ARCHIVED = "You had already sifted this timeline. Sluice moved that "
            + "record to %s rather than writing over it.";

    private static final String SHARDS_OUTSTANDING = "The sheets are ready, waiting for your "
            + "agent's decisions on them. Nothing moves until they arrive.";

    private static final String BLOCKED = "Every sheet was judged. %s. Nothing was moved.";

    private static final String CANCELLED = "You can continue at any time.";

    // True because an import keeps no record. The folder it came from is the record, so running it
    // again is the resume.
    private static final String IMPORT_STOPPED = "Not all files were imported. Run the import "
            + "again to pick up the rest.";

    // Its own words because the Continue offer above is not on this card. A reader told they can
    // continue would go looking for a button that is not there.
    private static final String CANCELLED_BEFORE_ANY_SHEET = "No sheets were built yet.";

    private RunResults() {
    }

    /**
     * What the card says about a job that ended without throwing.
     *
     * @param ran {@link RunMode} the mode the job was started in
     * @param outcome what the job produced, which for a sift or a curate says how it ended
     * @return {@link RunResultView} the card
     */
    static RunResultView of(final RunMode ran, final @Nullable Object outcome) {
        return switch (outcome) {
            case final SortSummary sorted -> sortResult(ran, sorted);
            case final CommitSummary moved -> movedResult(ran, moved);
            case final CullJobOutcome sift -> siftResult(ran, sift, List.of());
            case final CurateOutcome curated -> curateResult(ran, curated);
            case final RescueSummary rescued -> rescueResult(ran, rescued);
            case final ImportSummary brought -> importResult(ran, brought);
            // A mode whose engine answers with something nothing here reads yet. The heading is
            // still true and the card still has its Done. That is what stops an unknown result
            // stranding the screen on a page with no way off it.
            case null, default -> new RunResultView(finishedHeading(ran), Tone.FINISHED, null,
                    List.of(), null, null, null, DONE);
        };
    }

    /**
     * What the card says about a job that threw.
     *
     * @param ran {@link RunMode} the mode the job was started in
     * @param said {@link String} the failure as a sentence, already put into plain words
     * @return {@link RunResultView} the card
     */
    static RunResultView failed(final RunMode ran, final String said) {
        return new RunResultView(ran.verb() + " stopped.", Tone.FAILED, said,
                List.of(), null, null, null, DONE);
    }

    /**
     * What the card says about a finished sort.
     *
     * @param ran {@link RunMode} the mode the job was started in
     * @param sorted {@link SortSummary} what the sort did
     * @return {@link RunResultView} the card
     */
    private static RunResultView sortResult(final RunMode ran, final SortSummary sorted) {
        return new RunResultView(finishedHeading(ran), Tone.FINISHED, null, sortCounts(sorted),
                null, canaryLine(sorted), null, DONE);
    }

    /**
     * What a sort's card counts.
     *
     * @param sorted {@link SortSummary} what the sort did
     * @return a {@link List} of {@link Count} the rows
     */
    private static List<Count> sortCounts(final SortSummary sorted) {
        final List<Count> rows = new ArrayList<>();
        rows.add(new Count("result-photos-sorted", PHOTOS_SORTED, RunWords.grouped(sorted.photosSorted())));
        rows.add(new Count("result-videos-sorted", VIDEOS_SORTED, RunWords.grouped(sorted.videosSorted())));
        addWhenAny(rows, "result-reimports", "Already in your library", sorted.reimportsDeleted());
        addWhenAny(rows, "result-byte-dups", "Identical copies removed", sorted.byteDupsDeleted());
        addWhenAny(rows, "result-low-res", "Set aside for review", sorted.lowRes());
        addWhenAny(rows, "result-unsorted", "Could not be dated", sorted.unsorted());
        return rows;
    }

    /**
     * What the card says about a finished move to the library.
     *
     * @param ran {@link RunMode} the mode the job was started in
     * @param moved {@link CommitSummary} what the move did
     * @return {@link RunResultView} the card
     */
    private static RunResultView movedResult(final RunMode ran, final CommitSummary moved) {
        return new RunResultView(finishedHeading(ran), Tone.FINISHED, null, movedCounts(moved),
                null, null, null, DONE);
    }

    /**
     * What a move's card counts, one row per part of the library it reached.
     *
     * <p>Ordered by the bucket's own declaration rather than by name or by count, so two runs of
     * the same shape draw their rows in the same order.
     *
     * @param moved {@link CommitSummary} what the move did
     * @return a {@link List} of {@link Count} the rows
     */
    private static List<Count> movedCounts(final CommitSummary moved) {
        final List<Count> rows = new ArrayList<>();
        moved.byBucket().entrySet().stream()
                .filter(bucket -> bucket.getValue() > 0)
                .sorted(Comparator.comparing(bucket -> bucket.getKey().ordinal()))
                .forEach(bucket -> rows.add(new Count(
                        "result-moved-" + bucket.getKey().name().toLowerCase(Locale.UK),
                        bucketLabel(bucket.getKey()), RunWords.grouped(bucket.getValue()))));
        // Drawn even where every bucket read zero, since a move that moved nothing has to say so
        // rather than showing an empty card.
        rows.add(new Count("result-moved-total", "Moved to your library", RunWords.grouped(moved.committed())));
        return rows;
    }

    /**
     * What one part of the library is called on the card.
     *
     * @param bucket {@link LibraryBucket} the part
     * @return {@link String} its name
     */
    private static String bucketLabel(final LibraryBucket bucket) {
        return switch (bucket) {
            case PHOTOS -> "Photos";
            case VIDEOS -> "Videos";
            case FUNNY -> "Funny";
            // Nothing in the pipeline files anything here, and the bucket exists because a first
            // path segment has to land somewhere. A card meeting one says what it can rather than
            // dropping the count.
            case OTHER -> "Elsewhere in your library";
        };
    }

    /**
     * What the card says about a finished rescue.
     *
     * @param ran {@link RunMode} the mode the job was started in
     * @param rescued {@link RescueSummary} what the rescue did
     * @return {@link RunResultView} the card
     */
    private static RunResultView rescueResult(final RunMode ran, final RescueSummary rescued) {
        final List<Count> rows = new ArrayList<>();
        rows.add(new Count("result-rescued", "Moved to your library", RunWords.grouped(rescued.rescued())));
        addWhenAny(rows, "result-rescue-skipped", "Left behind", rescued.skipped().size());
        return new RunResultView(finishedHeading(ran), Tone.FINISHED, null, rows, null, null, null,
                DONE);
    }

    /**
     * What the card says about a finished import.
     *
     * @param ran {@link RunMode} the mode the job was started in
     * @param brought {@link ImportSummary} what the import did
     * @return {@link RunResultView} the card
     */
    private static RunResultView importResult(final RunMode ran, final ImportSummary brought) {
        final List<Count> rows = new ArrayList<>();
        // Drawn at zero as well, since an import that brought nothing in has to say so rather than
        // showing an empty card.
        rows.add(new Count("result-imported", "Imported", RunWords.grouped(brought.broughtIn())));
        addWhenAny(rows, "result-import-already", "Skipped: already in your Inbox",
                brought.alreadyThere());
        addWhenAny(rows, "result-import-unreadable", "Could not be read", brought.couldNotBeRead());
        addWhenAny(rows, "result-import-unverified", "Arrived broken", brought.unverified());
        // A card formatted by Windows keeps a folder of its own that no ordinary user may read, so
        // almost every card import reads one holding no photos.
        addWhenAny(rows, "result-import-unopenable", "Folders could not be opened",
                brought.unreadablePlaces());
        return new RunResultView(
                brought.cancelled() ? ran.verb() + " stopped." : importHeading(ran, brought),
                brought.cancelled() ? Tone.UNFINISHED : Tone.FINISHED,
                brought.cancelled() ? IMPORT_STOPPED : null,
                rows, null, null, null, DONE);
    }

    /**
     * How an import that ran to the end is headed.
     *
     * <p>A plain "finished" is the first thing read, so a run that left photos behind says so
     * there rather than only in a row further down. A pulled card is the way this happens.
     *
     * <p>Folders it could not open are not counted in. Every Windows-formatted card carries one no
     * ordinary user may read, so counting them would head almost every card import as gone wrong.
     *
     * @param ran {@link RunMode} the mode the job was started in
     * @param brought {@link ImportSummary} what the import did
     * @return {@link String} the heading
     */
    private static String importHeading(final RunMode ran, final ImportSummary brought) {
        final int leftBehind = brought.couldNotBeRead() + brought.unverified();
        if (leftBehind == 0) {
            return finishedHeading(ran);
        }
        return ran.verb() + " finished, with " + RunWords.grouped(leftBehind) + " left behind.";
    }

    /**
     * What the card says about a curate, which is a sort and then a sift.
     *
     * <p>A curate whose sift never ran is a sort that stopped, so its card is the sort's. That is
     * the one case where the two halves do not both have something to report.
     *
     * @param ran {@link RunMode} the mode the job was started in
     * @param curated {@link CurateOutcome} what both halves did
     * @return {@link RunResultView} the card
     */
    private static RunResultView curateResult(final RunMode ran, final CurateOutcome curated) {
        final CullJobOutcome sift = curated.cullOutcome();
        final SortSummary sorted = curated.sortSummary();
        if (sift == null) {
            return sortResult(ran, sorted);
        }
        final RunResultView card = siftResult(ran, sift, sortCounts(sorted));
        // The canary fired during this run's own sort, so it rides the one card the run produces.
        // Read off the sort half whatever the sift half went on to do, since a sift that paused
        // does not make a doubtful date any less doubtful.
        return new RunResultView(card.heading(), card.tone(), card.detail(), card.counts(),
                card.archived(), canaryLine(sorted), card.resume(), card.doneLabel());
    }

    /**
     * What the card says about a sift, whichever of the four ways it ended.
     *
     * @param ran {@link RunMode} the mode the job was started in
     * @param outcome {@link CullJobOutcome} how the sift ended
     * @param before a {@link List} of {@link Count} rows to put above the sift's own, for a curate
     * @return {@link RunResultView} the card
     */
    private static RunResultView siftResult(final RunMode ran, final CullJobOutcome outcome,
                                            final List<Count> before) {
        final Path movedTo = outcome.archivedPriorRun();
        final String archived = movedTo == null ? null : ARCHIVED.formatted(movedTo);
        return switch (outcome) {
            case CullJobOutcome.Applied(final CullReport report, final ApplyReport applied, Path _) ->
                    new RunResultView(finishedHeading(ran), Tone.FINISHED, null,
                            joined(before, siftCounts(report, applied)), archived, null, null, DONE);
            case CullJobOutcome.Waiting(final var job, final WaitingReason why, _, Path _) ->
                    new RunResultView(waitingHeading(ran, why), Tone.UNFINISHED, waitingDetail(why),
                            joined(before, sheetCounts(job.shards())), archived, null,
                            resumeOffer(why, job.prepDir()), DONE);
            case CullJobOutcome.Blocked(final var job, final var findings, _, Path _) ->
                    new RunResultView(ran.verb() + " stopped and needs a look.",
                            Tone.UNFINISHED, BLOCKED.formatted(FindingFamily.wentWrong(findings)),
                            joined(before, sheetCounts(job.shards())), archived, null, null, DONE);
            // The one case with no prep dir behind it, so nothing counted the sheets. It is reached
            // only before rendering finished, which is why there are none to count.
            case CullJobOutcome.Cancelled _ ->
                    new RunResultView(ran.verb() + " was cancelled.", Tone.UNFINISHED,
                            CANCELLED_BEFORE_ANY_SHEET, before, archived, null, null, DONE);
        };
    }

    /**
     * What a finished sift's card counts.
     *
     * <p>The categories come from the run itself rather than from a list written here, because a
     * user names their own. A category nothing was filed under is left out for the same reason a
     * zeroed sort bucket is.
     *
     * @param applied {@link ApplyReport} what applying the decisions did
     * @return a {@link List} of {@link Count} the rows
     */
    private static List<Count> siftCounts(final CullReport report, final ApplyReport applied) {
        final List<Count> rows = new ArrayList<>();
        rows.add(new Count("result-reviewed", "Photos looked at", RunWords.grouped(applied.reviewed())));
        rows.add(new Count("result-sheets", "Sheets judged", RunWords.grouped(report.montagesCulled())));
        rows.add(new Count("result-api-calls", "Calls to your provider", RunWords.grouped(report.apiCalls())));
        applied.byCategory().entrySet().stream()
                .filter(category -> category.getValue() > 0)
                .sorted(Map.Entry.comparingByKey())
                .forEach(category -> rows.add(new Count(
                        "result-category-" + category.getKey().toLowerCase(Locale.UK),
                        category.getKey(), RunWords.grouped(category.getValue()))));
        addWhenAny(rows, "result-near-dup-groups", "Near-duplicate groups", applied.nearDupGroups());
        addWhenAny(rows, "result-near-dup-rejects", "Copies set aside", applied.nearDupRejects());
        addWhenAny(rows, "result-unreviewable", "Could not be judged", applied.unreviewable());
        return rows;
    }

    /**
     * How far through its sheets a sift that did not finish got.
     *
     * <p>A run that spent money and then paused has to account for it. Without a currency figure,
     * what it can say is how many of its sheets now hold a decision. That is the denomination the
     * rest of this design settled on.
     *
     * <p>Read off the prep dir's own tally rather than off what this call judged. Carrying a
     * stopped run on judges only the sheets still without a decision. That call's own count
     * therefore leaves out everything the first one paid for. The tally counts what is on disk,
     * which is the question a reader is asking.
     *
     * @param sheets {@link ShardTally} what the prep dir holds
     * @return a {@link List} of {@link Count} the rows
     */
    private static List<Count> sheetCounts(final ShardTally sheets) {
        return List.of(new Count("result-sheets-judged", "Sheets judged",
                RunWords.grouped(sheets.valid()) + " of " + RunWords.grouped(sheets.total())));
    }

    /**
     * The offer to continue a stopped run, where one is open.
     *
     * <p>Open wherever this side stopped the run: its own spending limit, or the reader asking it
     * to. Both leave sheets built and some of them judged, and continuing sends only the ones still
     * without a decision.
     *
     * <p>A sift waiting on somebody's agent is the one that gets no offer. Nothing has arrived to
     * act on until they answer, so pressing it would meet the same pause again.
     *
     * @param why {@link WaitingReason} why the sift paused
     * @param prepDir {@link Path} the paused run's own directory
     * @return {@link Resume} the offer, or null where none is open
     */
    private static @Nullable Resume resumeOffer(final WaitingReason why, final Path prepDir) {
        return switch (why) {
            case CEILING_REACHED -> new Resume(CEILING_QUESTION, CONTINUE, prepDir);
            case CANCELLED -> new Resume(CANCELLED, CONTINUE, prepDir);
            case SHARDS_OUTSTANDING -> null;
        };
    }

    /**
     * What the card's heading says about a sift that paused.
     *
     * @param ran {@link RunMode} the mode the job was started in
     * @param why {@link WaitingReason} why it paused
     * @return {@link String} the heading
     */
    private static String waitingHeading(final RunMode ran, final WaitingReason why) {
        return switch (why) {
            case SHARDS_OUTSTANDING -> ran.verb() + " is waiting on your agent.";
            case CEILING_REACHED -> ran.verb() + " stopped at its spending limit.";
            case CANCELLED -> ran.verb() + " was cancelled.";
        };
    }

    /**
     * The sentence under the heading of a sift that paused.
     *
     * <p>Only the one ending that offers no way on has a sentence here. The other two put theirs
     * above the button that continues the run, where a reader deciding whether to press it is
     * already looking.
     *
     * @param why {@link WaitingReason} why it paused
     * @return {@link String} the sentence, or null where the offer below carries it
     */
    private static @Nullable String waitingDetail(final WaitingReason why) {
        return switch (why) {
            case SHARDS_OUTSTANDING -> SHARDS_OUTSTANDING;
            case CEILING_REACHED, CANCELLED -> null;
        };
    }

    /**
     * What the card's heading says about work that was carried out.
     *
     * @param ran {@link RunMode} the mode the job was started in
     * @return {@link String} the heading
     */
    private static String finishedHeading(final RunMode ran) {
        return ran.verb() + " finished.";
    }

    /**
     * The warning stripe's line, where this run's sort tripped something worth reading.
     *
     * <p>Says what it means for the photos rather than what tripped. A reader has never heard of a
     * sidecar and cannot check whether one paired. What they can check is a date.
     *
     * @param sorted {@link SortSummary} what the sort did
     * @return {@link String} the line, or null where nothing tripped
     */
    private static RunResultView.@Nullable Warning canaryLine(final SortSummary sorted) {
        return sorted.warnings().isEmpty()
                ? null
                : new RunResultView.Warning("The dates on these photos may be wrong.",
                        "They came with date files alongside them, and almost none of those matched "
                                + "a photo. Sluice fell back to the date each file was last written.");
    }

    /**
     * Adds a row only where it counts something.
     *
     * @param rows a {@link List} of {@link Count} the rows so far
     * @param id {@link String} the row's control id
     * @param label {@link String} what it says
     * @param count int what it counted
     */
    private static void addWhenAny(final List<Count> rows, final String id, final String label,
                                   final int count) {
        if (count > 0) {
            rows.add(new Count(id, label, RunWords.grouped(count)));
        }
    }

    /**
     * Two sets of rows as one.
     *
     * @param first a {@link List} of {@link Count} the rows to put above
     * @param second a {@link List} of {@link Count} the rows to put below
     * @return a {@link List} of {@link Count} the rows together
     */
    private static List<Count> joined(final List<Count> first, final List<Count> second) {
        final List<Count> rows = new ArrayList<>(first);
        rows.addAll(second);
        return rows;
    }
}
