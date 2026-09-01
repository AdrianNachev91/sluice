package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import photos.sluice.adapter.ui.RunResultView.CardAction;
import photos.sluice.adapter.ui.RunResultView.Count;
import photos.sluice.adapter.ui.RunResultView.Tone;
import photos.sluice.application.port.in.CullJobOutcome;
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
import java.util.Set;

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

    // Opens on "of those" because it counts some of the row it sits under, which the indent shows
    // and the words have to survive without.
    private static final String LOW_CONFIDENCE_DATES = "of those, low confidence date";

    private static final String DONE = "Done";

    private static final String CONTINUE = "Continue sifting";

    private static final String CEILING_QUESTION = "Sluice stopped this sift because it went far "
            + "past what it was expected to cost. Nothing more has been spent from your provider "
            + "account balance. Continuing on sends "
            + "the sheets it had not reached, under a fresh limit.";

    private static final String SHARDS_OUTSTANDING = "The sheets are ready, waiting for your "
            + "agent's decisions on them. Nothing moves until they arrive.";

    // Opens on what came back rather than on every sheet being judged. A sheet whose answer covers
    // only some of its photos has a decisions file and has not been judged, and the clause that
    // follows says exactly that.
    private static final String BLOCKED = "Every sheet came back. %s. Nothing was moved.";

    private static final String CANCELLED = "You can continue at any time.";

    // True because an import keeps no record. The folder it came from is the record, so running it
    // again is the resume.
    private static final String IMPORT_STOPPED = "Not all files were imported. Run the import "
            + "again to pick up the rest.";

    // What the counts leave out. A stopped move's rows read like a finished one's, and the photos
    // it never reached are still where they were rather than lost. "of them" rather than a bare
    // count, because a narrowed move leaves the rest of Sorted untouched and out of this number.
    private static final String MOVE_STOPPED = "%s of them are still in Sorted.";

    // Names what it counts, where the line above says "of them". A rescue cannot be narrowed, so
    // there is no in-scope subset for "them" to point back at.
    private static final String RESCUE_STOPPED = "%s photos and videos are still in the folder you "
            + "started from.";

    private static final String RESCUE_STOPPED_ONE = "One photo or video is still in the folder you "
            + "started from.";

    // Reachable: a stop after the last photo, with only notes or non-media left in the tail.
    private static final String RESCUE_STOPPED_NONE = "No photos or videos are left in the folder "
            + "you started from.";

    // Its own words because the Continue offer above is not on this card. A reader told they can
    // continue would go looking for a button that is not there.
    private static final String CANCELLED_BEFORE_ANY_SHEET = "No sheets were built yet.";

    private RunResults() {
    }

    /**
     * What the card says about a job that ended without throwing.
     *
     * <p>Whether a run stopped short is read off what it produced, never off the button. A stop
     * asked for as the last file moved leaves nothing behind, and only the engine can tell that
     * from a stop that did.
     *
     * @param ran {@link RunMode} the mode the job was started in
     * @param outcome what the job produced, which also says how it ended
     * @param narrowedTo what the run was narrowed to, or null where it took whatever it found. A
     *        sort counts only what was in scope, so a full Inbox and a chosen year holding nothing
     *        report the same thing
     * @return {@link RunResultView} the card
     */
    static RunResultView of(final RunMode ran, final @Nullable Object outcome,
                            final @Nullable String narrowedTo) {
        return switch (outcome) {
            case final SortSummary sorted -> sortResult(ran, sorted, narrowedTo);
            case final CommitSummary moved -> movedResult(ran, moved);
            case final CullJobOutcome sift -> siftResult(ran, sift);
            case final RescueSummary rescued -> rescueResult(ran, rescued);
            case final ImportSummary brought -> importResult(ran, brought);
            // A mode whose engine answers with something nothing here reads yet. Headed as
            // finished, because an outcome nothing recognises carries no way to tell. The card
            // still has its Done, which is what stops it stranding the screen.
            case null, default -> new RunResultView(finishedHeading(ran), Tone.FINISHED, null,
                    List.of(), null, null, DONE);
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
        // Not "stopped", which heads a run the reader stopped on purpose. A reader cannot be left
        // reading one word for both, with only the colour behind it telling them which happened.
        return new RunResultView(ran.verb() + " could not finish.", Tone.FAILED, said,
                List.of(), null, null, DONE);
    }

    /**
     * What the card says about a finished sort.
     *
     * @param ran {@link RunMode} the mode the job was started in
     * @param sorted {@link SortSummary} what the sort did
     * @param narrowedTo what the sort was narrowed to, or null where it took whatever it found
     * @return {@link RunResultView} the card
     */
    private static RunResultView sortResult(final RunMode ran, final SortSummary sorted,
                                            final @Nullable String narrowedTo) {
        return new RunResultView(headingFor(ran, sorted.cancelled()), toneFor(sorted.cancelled()),
                sortNote(sorted, narrowedTo),
                sortCounts(sorted), canaryLine(sorted), siftNowOffer(sorted), DONE);
    }

    /**
     * How a run is headed, given whether it stopped short.
     *
     * @param ran {@link RunMode} the mode the job was started in
     * @param stopped whether the run gave up before reaching the end of its scope
     * @return {@link String} the heading
     */
    private static String headingFor(final RunMode ran, final boolean stopped) {
        return stopped ? ran.verb() + " stopped." : finishedHeading(ran);
    }

    /**
     * How a card is toned, given whether the run stopped short.
     *
     * <p>Never {@code FAILED}, which {@link #failed} keeps for a run that threw. A run the reader
     * stopped did what they asked.
     *
     * @param stopped whether the run gave up before reaching the end of its scope
     * @return {@link Tone} the card's tone
     */
    private static Tone toneFor(final boolean stopped) {
        return stopped ? Tone.UNFINISHED : Tone.FINISHED;
    }

    /**
     * What a sort says about itself beyond its counts.
     *
     * <p>A sort stopped before anything moved reports exactly what a sort over an empty Inbox
     * reports, so the counts alone cannot tell the reader which happened to them.
     *
     * @param sorted {@link SortSummary} what the sort did
     * @param narrowedTo what the sort was narrowed to, or null where it took whatever it found
     * @return {@link String} the sentence, or null where something reached Sorted uninterrupted
     */
    private static @Nullable String sortNote(final SortSummary sorted, final @Nullable String narrowedTo) {
        if (sorted.cancelled()) {
            return sorted.processed() == 0
                    ? "Your Inbox is unchanged."
                    : RunWords.grouped(sorted.leftBehind()) + " of them are still in your Inbox.";
        }
        return sortedNothing(sorted, narrowedTo);
    }

    /**
     * What a sort that filed nothing into Sorted says about itself.
     *
     * <p>Said because the card otherwise ends on a heading saying the sort finished, counts that
     * add up to nowhere, and no offer to sift. A reader is left working out which of those three is
     * the fault. Every file went somewhere, and the rows name where, so what is missing is the one
     * sentence tying them to the absent button.
     *
     * <p>A sort counts only what was in scope, so a narrowed one that filed nothing reports what an
     * empty Inbox reports. Only what was asked for tells the cases apart.
     *
     * @param sorted {@link SortSummary} what the sort did
     * @param narrowedTo what the sort was narrowed to, or null where it took whatever it found
     * @return {@link String} the sentence, or null where something did reach Sorted
     */
    private static @Nullable String sortedNothing(final SortSummary sorted, final @Nullable String narrowedTo) {
        if (!sorted.yearsSorted().isEmpty()) {
            return null;
        }
        if (sorted.processed() > 0) {
            return "Nothing ended up in Sorted, so there is nothing to sift yet. " + becauseOf(sorted);
        }
        return narrowedTo == null
                ? "Nothing in your Inbox was ready to sort."
                : "Nothing in " + narrowedTo + " was ready to sort.";
    }

    /**
     * Why none of them reached Sorted, where one thing accounts for all of them.
     *
     * <p>A row names where a file went. This names why it went there. That is the question left
     * over once the offer to sift is missing and every row reads as a destination.
     *
     * <p>Only where a single bucket took the lot. A run split across several has its answer in the
     * rows already. A sentence picking one of them would describe part of the run as the whole.
     *
     * @param sorted {@link SortSummary} what the sort did
     * @return {@link String} the reason, or a pointer to the rows where several share it
     */
    private static String becauseOf(final SortSummary sorted) {
        final int all = sorted.processed();
        if (sorted.lowRes() == all) {
            return "Their file size or their resolution is under what a sift looks at, so they are "
                    + "in Review instead.";
        }
        if (sorted.unsorted() == all) {
            return "Not one of them carries a date that can be trusted, so there is no year to "
                    + "file them under. They are in Review, under Unsorted.";
        }
        if (sorted.reimportsDeleted() == all) {
            return "They are all in your library already, so the copies in your Inbox were removed.";
        }
        return "The rows below say what became of each one.";
    }

    /**
     * The offer to sift what a sort just filed, where the sort filled exactly one timeline.
     *
     * <p>Empty means nothing reached Sorted, so there is nothing to offer. More than one timeline
     * is refused rather than picked between. The button names what it would sift, and naming one of
     * several spends the reader's money on photos they had not asked about. Nothing on the
     * dashboard produces more than one today, and this is the answer for when something does.
     *
     * @param sorted {@link SortSummary} what the sort did
     * @return {@link CardAction} the offer, or null where the card makes none
     */
    private static @Nullable CardAction siftNowOffer(final SortSummary sorted) {
        final Set<Integer> years = sorted.yearsSorted();
        if (years.size() != 1) {
            return null;
        }
        final int year = years.iterator().next();
        return new CardAction.SiftNow("Sift " + year, year, sorted.photosSorted());
    }

    /**
     * What a sort's card counts.
     *
     * @param sorted {@link SortSummary} what the sort did
     * @return a {@link List} of {@link Count} the rows
     */
    private static List<Count> sortCounts(final SortSummary sorted) {
        final SortSummary.Guessed guessed = sorted.guessed();
        final List<Count> rows = new ArrayList<>();
        rows.add(new Count("result-photos-sorted", PHOTOS_SORTED, RunWords.grouped(sorted.photosSorted())));
        addGuessed(rows, "result-photos-sorted-guessed", guessed.photosSorted());
        rows.add(new Count("result-videos-sorted", VIDEOS_SORTED, RunWords.grouped(sorted.videosSorted())));
        addGuessed(rows, "result-videos-sorted-guessed", guessed.videosSorted());
        addWhenAny(rows, "result-reimports", "Already in your library", sorted.reimportsDeleted());
        addWhenAny(rows, "result-byte-dups", "Identical copies removed", sorted.byteDupsDeleted());
        addWhenAny(rows, "result-low-res", "Set aside for review", sorted.lowRes());
        addGuessed(rows, "result-low-res-guessed", guessed.lowRes());
        addWhenAny(rows, "result-unsorted", "Could not be dated", sorted.unsorted());
        return rows;
    }

    /**
     * Adds the part of the row above whose date came off the file rather than off the photo.
     *
     * <p>Under the row it belongs to rather than among them. These files are already counted there,
     * so a row of their own would be a seventh destination on a card whose other rows each name
     * one.
     *
     * @param rows a {@link List} of {@link Count} the rows so far, appended to
     * @param id {@link String} the row's own id
     * @param guessed int how many of the row above were dated that way
     */
    private static void addGuessed(final List<Count> rows, final String id, final int guessed) {
        if (guessed > 0) {
            rows.add(new Count(id, LOW_CONFIDENCE_DATES, RunWords.grouped(guessed), true));
        }
    }

    /**
     * What the card says about a finished move to the library.
     *
     * @param ran {@link RunMode} the mode the job was started in
     * @param moved {@link CommitSummary} what the move did
     * @return {@link RunResultView} the card
     */
    private static RunResultView movedResult(final RunMode ran, final CommitSummary moved) {
        return new RunResultView(headingFor(ran, moved.cancelled()), toneFor(moved.cancelled()),
                moved.cancelled() ? MOVE_STOPPED.formatted(RunWords.grouped(moved.leftBehind())) : null,
                movedCounts(moved), null, null, DONE);
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
            case UNDATED -> "Unsorted";
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
        rows.add(new Count("result-rescued", "Moved to Sorted", RunWords.grouped(rescued.rescued())));
        addWhenAny(rows, "result-rescue-undated", "Moved to Unsorted", rescued.undated());
        addWhenAny(rows, "result-rescue-already", "Deleted: already in Sorted",
                rescued.alreadyInSorted());
        return new RunResultView(headingFor(ran, rescued.cancelled()), toneFor(rescued.cancelled()),
                rescued.cancelled() ? rescueStopped(rescued.leftBehind()) : null, rows, null, null, DONE);
    }

    /**
     * What a stopped rescue says above its counts.
     *
     * @param leftBehind int photos and videos still in the folder the run was given
     * @return {@link String} the sentence
     */
    private static String rescueStopped(final int leftBehind) {
        if (leftBehind == 0) {
            return RESCUE_STOPPED_NONE;
        }
        if (leftBehind == 1) {
            return RESCUE_STOPPED_ONE;
        }
        return RESCUE_STOPPED.formatted(RunWords.grouped(leftBehind));
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
                rows, null, null, DONE);
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
     * What the card says about a sift, whichever of the four ways it ended.
     *
     * @param ran {@link RunMode} the mode the job was started in
     * @param outcome {@link CullJobOutcome} how the sift ended
     * @return {@link RunResultView} the card
     */
    private static RunResultView siftResult(final RunMode ran, final CullJobOutcome outcome) {
        return switch (outcome) {
            case CullJobOutcome.Applied(final CullReport report, final ApplyReport applied, Path _) ->
                    new RunResultView(finishedHeading(ran), Tone.FINISHED, null,
                            siftCounts(report, applied), null, null, DONE);
            case CullJobOutcome.Waiting(final var job, final WaitingReason why, _, Path _) ->
                    new RunResultView(waitingHeading(ran, why), Tone.UNFINISHED, waitingDetail(why),
                            sheetCounts(job.shards()), null,
                            resumeOffer(why, job.prepDir()), DONE);
            case CullJobOutcome.Blocked(final var job, final var findings, _, Path _) ->
                    new RunResultView(ran.verb() + " stopped and needs a look.",
                            Tone.UNFINISHED, BLOCKED.formatted(FindingFamily.wentWrong(findings)),
                            sheetCounts(job.shards()), null, null, DONE);
            // The one case with no prep dir behind it, so nothing counted the sheets. It is reached
            // only before rendering finished, which is why there are none to count.
            case CullJobOutcome.Cancelled _ ->
                    new RunResultView(headingFor(ran, true), toneFor(true),
                            CANCELLED_BEFORE_ANY_SHEET, List.of(), null, null, DONE);
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
     * @return {@link CardAction} the offer, or null where none is open
     */
    private static @Nullable CardAction resumeOffer(final WaitingReason why, final Path prepDir) {
        return switch (why) {
            case CEILING_REACHED -> new CardAction.ContinueRun(CEILING_QUESTION, CONTINUE, prepDir);
            case CANCELLED -> new CardAction.ContinueRun(CANCELLED, CONTINUE, prepDir);
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
            case CANCELLED -> headingFor(ran, true);
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
}
