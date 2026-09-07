package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.adapter.ui.ReviewView.Action;
import photos.sluice.adapter.ui.ReviewView.FolderCard;
import photos.sluice.adapter.ui.ReviewView.Group;
import photos.sluice.adapter.ui.ReviewView.Kind;
import photos.sluice.adapter.ui.ReviewView.Notes;
import photos.sluice.adapter.ui.RunLauncherView.Message;
import photos.sluice.adapter.ui.RunSetupPresenter.Confirmation;
import photos.sluice.application.port.in.RescueRoot;
import photos.sluice.application.port.in.ReviewListing;
import photos.sluice.application.port.in.ReviewListing.FiledBy;
import photos.sluice.application.port.in.ReviewListing.Folder;
import photos.sluice.application.port.in.ReviewListing.Root;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.cull.JunkCategory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Decides what the review screen shows and what a press on it does.
 *
 * <p>Holds the last reading of the three folders, and the words that reading comes to. The screen
 * keeps the controls, asks here after every change, and hands the presses it cannot carry out itself
 * straight back.
 *
 * <p>The reading is taken by calling {@link #refresh}, which walks all three roots and blocks while
 * it does. The caller runs it off whatever thread paints.
 */
@Component
@Profile("!cli")
public class ReviewPresenter {

    private static final Logger log = LoggerFactory.getLogger(ReviewPresenter.class);

    private static final String HEADING = "Review";

    private static final String NOTHING_YET = "Nothing is waiting for you. When a sort or a sift "
            + "puts photos somewhere for you to look at, they show here.";

    private static final String UNREADABLE = "Cannot determine what is waiting in %s: it cannot be "
            + "read. Opening it yourself is the quickest way to find out why.";

    private static final String OPEN = "Open folder";

    private static final String RESCUE_TO_SORTED = RunMode.RESCUE.label();

    private static final String SHOW_NOTES = "Why these are here";

    private static final String LOOKING = "Looking at what is waiting...";

    private static final String NOTHING_WRITTEN = "Nothing was written about this folder.";

    private static final String ALREADY_RUNNING = "Something else started running just now, and "
            + "only one job runs at a time. Nothing was moved. Try again once it finishes.";

    private static final String NOTES_UNREADABLE = "What was written about this folder could not be "
            + "read.";

    private static final String OPEN_THE_FOLDER = "Open the folder to see all the notes.";

    private static final String SCREEN_EXPLAINED = "Everything here is waiting for you to look at "
            + "it. Open a folder, throw away what you don't want, then rescue what is left back to "
            + "Sorted. A folder leaves this screen once its photos and videos are gone. One you "
            + "have put your own file into stays on your disk, with that file in it.";

    private static final String GENERAL_JUNK_HEADING = "General junk";

    private static final String GENERAL_JUNK_EXPLAINED = "A sift judged these worthless and gave no "
            + "reason beyond that.";

    private static final String CATEGORY_JUNK_HEADING = "Junk by category";

    private static final String CATEGORY_JUNK_EXPLAINED = "A sift decided against keeping these. "
            + "Each folder is one of the reasons you set up.";

    private static final String NEVER_SIFTED_HEADING = "A sift never sees these";

    private static final String NEVER_SIFTED_EXPLAINED = "A sort would not put these in Sorted, "
            + "which is the only place a sift looks. A dated folder holds photos whose file size or "
            + "resolution is under what a sift looks at, and Unsorted holds whatever a sort could "
            + "not date.";

    private static final String DUPLICATES_HEADING = "Near-copies";

    // Says the keeper here is a copy, or a reader keeps it and ends up holding that photo twice.
    private static final String DUPLICATES_EXPLAINED = "Each folder holds the photos that looked "
            + "like near-copies of one that was kept. A copy of the kept one is in there too, with "
            + "a note saying why, and the one that was kept is still in Sorted. So the whole "
            + "folder can go once you have compared them.";

    private static final String UNREVIEWABLE_HEADING = "A sift could not judge these";

    // Neither half of this says a thumbnail failed. A photo lands here when the tile is unusable,
    // which covers a decode that failed and a source too small to read, so the picture often
    // exists. And a sift moved these out of Sorted rather than leaving them where it found them.
    private static final String UNREVIEWABLE_EXPLAINED = "These were never judged, because they "
            + "could not be seen clearly enough. They keep the year and month a sift found them "
            + "under.";

    // A fold inside a card on a scrolling page. Past a couple of hundred lines nobody is reading
    // one, they are looking for a name, and the file manager does that better than a VBox.
    private static final int MOST_LINES_DRAWN = 200;

    private static final Predicate<String> ANY_NAME = _ -> true;

    // The order the sections are drawn in: the Review root's three, then the other two roots.
    private static final List<Section> SECTIONS = List.of(
            new Section(Root.REVIEW, FiledBy.A_SIFT, JunkCategory::claims, "general-junk",
                    GENERAL_JUNK_HEADING, GENERAL_JUNK_EXPLAINED),
            new Section(Root.REVIEW, FiledBy.A_SIFT, name -> !JunkCategory.claims(name),
                    "category-junk", CATEGORY_JUNK_HEADING, CATEGORY_JUNK_EXPLAINED),
            new Section(Root.REVIEW, FiledBy.A_SORT, ANY_NAME, "never-sifted",
                    NEVER_SIFTED_HEADING, NEVER_SIFTED_EXPLAINED),
            new Section(Root.DUPLICATES, FiledBy.A_SIFT, ANY_NAME, "duplicates",
                    DUPLICATES_HEADING, DUPLICATES_EXPLAINED),
            new Section(Root.UNREVIEWABLE, FiledBy.A_SIFT, ANY_NAME, "unreviewable",
                    UNREVIEWABLE_HEADING, UNREVIEWABLE_EXPLAINED));

    private final Pipeline pipeline;
    private final RunLauncherPresenter launcher;

    // Volatile throughout. The reading is taken off the thread that paints, and the screen draws
    // from it on the thread that does.
    private volatile ReviewListing listing = new ReviewListing(List.of(), List.of());
    // False until a read has landed. The screen draws once before the first one, off the empty
    // listing above, and an unread root and an empty one are the same value.
    private volatile boolean hasRead;
    private volatile @Nullable Message message;
    // Every fold that is drawn, by the folder it is over.
    private final Map<Path, Fold> folds = new ConcurrentHashMap<>();
    // The folders a press has asked to open, whether or not their reads have landed.
    private final Set<Path> asked = ConcurrentHashMap.newKeySet();
    // Held while a press decides what it means and while a read publishes. The thread that paints
    // takes neither, reading both collections above without it.
    private final Object foldLock = new Object();
    private volatile @Nullable Runnable openDashboard;

    /**
     * Creates the presenter over the facade it reads and the launcher it hands work to.
     *
     * @param pipeline {@link Pipeline} reads the three folders and runs the move
     * @param launcher {@link RunLauncherPresenter} runs the move and reports it, as it does every
     *     other job
     */
    public ReviewPresenter(final Pipeline pipeline, final RunLauncherPresenter launcher) {
        this.pipeline = pipeline;
        this.launcher = launcher;
    }

    /**
     * Says where to send a reader once a move has been started.
     *
     * <p>Moving what is left in a folder takes them to the dashboard, because that is where a running
     * job reports itself.
     *
     * @param openDashboard {@link Runnable} shows the dashboard. Called on the thread that paints
     */
    public void setOpenDashboard(final Runnable openDashboard) {
        this.openDashboard = openDashboard;
    }

    /**
     * Reads the three folders again.
     *
     * <p>Blocks for three tree walks. The caller runs it off whatever thread paints.
     */
    public void refresh() {
        try {
            this.listing = this.pipeline.reviewListing();
            this.message = null;
        } catch (final RuntimeException e) {
            log.info("Could not read what is waiting for review", e);
            // Dropped rather than left standing. Cards from the last reading would otherwise sit
            // under a line saying the folders cannot be read. Each would still offer to move a
            // count nothing can vouch for.
            this.listing = new ReviewListing(List.of(), List.of());
            this.message = RunRefusals.refuseMessage(e);
        }
        this.hasRead = true;
        this.closeNotes();
    }

    /**
     * What the screen shows now.
     *
     * @return {@link ReviewView} everything on it, in display-ready words
     */
    public ReviewView view() {
        final ReviewListing listed = this.listing;
        final Message said = this.message;
        final List<Group> groups = new ArrayList<>();
        for (final Section section : SECTIONS) {
            this.group(listed, section).ifPresent(groups::add);
        }
        if (!this.hasRead) {
            return new ReviewView(HEADING, null, null, LOOKING, List.of(), null);
        }
        // Asked of the reading rather than of the sections built from it. A folder no section
        // claimed would otherwise put this line over folders that are there.
        //
        // A read that failed establishes nothing, so it cannot also report that nothing is waiting.
        final boolean nothingWaiting =
                listed.folders().isEmpty() && listed.unreadable().isEmpty() && said == null;
        // Only over folders. On a screen showing nothing waiting, telling a reader what to do with
        // folders they have not got is one more thing to read past.
        return new ReviewView(HEADING, groups.isEmpty() ? null : SCREEN_EXPLAINED,
                unreadableLine(listed), nothingWaiting ? NOTHING_YET : null, groups, said);
    }

    /**
     * Opens or shuts one folder's notes, reading them where it opens.
     *
     * <p>Walks the folder and reads every note in it, so the caller runs it off whatever thread
     * paints.
     *
     * <p>Every other card's fold is left as it was.
     *
     * <p>A read publishes only where its folder is still one the reader has asked for. A press
     * shutting a fold mid-read leaves that read with nothing to say.
     *
     * @param folder {@link Path} the folder whose notes were pressed
     */
    public void toggleNotes(final Path folder) {
        synchronized (this.foldLock) {
            // Shut covers a fold already drawn and one still being read for.
            if (!this.asked.add(folder)) {
                this.asked.remove(folder);
                this.folds.remove(folder);
                return;
            }
        }
        Fold fold;
        try {
            final List<String> lines = this.pipeline.reviewNotes(folder);
            fold = new Fold(capped(lines), beyondTheFold(lines.size()), null);
        } catch (final RuntimeException e) {
            log.info("Could not read the notes in {}", folder, e);
            fold = new Fold(List.of(), null, NOTES_UNREADABLE);
        }
        synchronized (this.foldLock) {
            if (this.asked.contains(folder)) {
                this.folds.put(folder, fold);
            }
        }
    }

    /**
     * What one folder's fold holds now, or null where no card carries that folder.
     *
     * <p>Answered on its own so a screen can fill one fold after its read lands, rather than
     * drawing every card again.
     *
     * @param folder {@link Path} the folder whose fold is asked about
     * @return {@link Notes} what that fold holds, or null
     */
    public @Nullable Notes notesOn(final Path folder) {
        return this.view().groups().stream()
                .flatMap(group -> group.folders().stream())
                .filter(card -> card.path().equals(folder))
                .findFirst()
                .map(FolderCard::notes)
                .orElse(null);
    }

    /**
     * Moves what is left in one folder back into Sorted.
     *
     * <p>Handed to the launcher rather than run here, so it reports, cancels and refuses exactly as
     * every other job does. A refusal lands on the dashboard, which is where the reader is sent.
     *
     * <p>A job taken between this screen drawing its buttons and the press landing leaves the
     * reader here, told so. A dashboard showing somebody else's run answers nothing they asked.
     *
     * @param action {@link Action} the button that was pressed
     * @throws NullPointerException if the action carries no root, which only a rescue does
     */
    public void rescue(final Action action) {
        final RescueRoot root = Objects.requireNonNull(action.root(),
                "a rescue action carries the root its folder sits under");
        if (!this.launcher.rescueFromReview(root, action.folder(), action.named())) {
            this.message = new Message(ALREADY_RUNNING, true);
            return;
        }
        this.message = null;
        final Runnable open = this.openDashboard;
        if (open != null) {
            open.run();
        }
    }

    /**
     * One section, or nothing where none of its folders is waiting.
     *
     * @param listing {@link ReviewListing} the one reading this whole view is drawn from. Asking the
     *     field again would answer about a second moment, since a fresh reading lands from a thread
     *     of its own
     * @param section {@link Section} which section
     * @return an {@link Optional} of {@link Group} the section
     */
    private Optional<Group> group(final ReviewListing listing, final Section section) {
        final List<FolderCard> cards = listing.folders().stream()
                .filter(folder -> folder.root() == section.root()
                        && folder.filedBy() == section.filedBy()
                        && section.named().test(folder.name()))
                .map(this::card)
                .toList();
        return cards.isEmpty()
                ? Optional.empty()
                : Optional.of(new Group(section.id(), section.heading(), section.explained(), cards));
    }

    /**
     * One heading on the screen, and which of the listing's folders belong under it.
     *
     * @param root {@link Root} the root its folders sit under
     * @param filedBy {@link FiledBy} the job that filed them
     * @param named a {@link Predicate} of {@link String} which of that root's folder names belong
     *     here
     * @param id {@link String} the section's own id, for the screen to set on it
     * @param heading {@link String} what the section is called
     * @param explained {@link String} what put these photos here and what to do about them
     */
    private record Section(Root root, FiledBy filedBy, Predicate<String> named, String id,
                           String heading, String explained) {
    }

    /**
     * One folder's card.
     *
     * @param folder {@link Folder} the folder as the facade described it
     * @return {@link FolderCard} the card
     */
    private FolderCard card(final Folder folder) {
        final Fold open = this.folds.get(folder.path());
        final boolean shown = open != null;
        final String failed = shown ? open.failed() : null;
        final List<String> written = shown ? open.lines() : List.of();
        final Notes notes = new Notes(idFor(folder, "notes"), SHOW_NOTES, shown,
                shown && failed == null ? written : List.of(),
                shown ? open.beyondTheFold() : null,
                shown && !written.isEmpty() ? OPEN_THE_FOLDER : null,
                shown ? nothingIn(failed, written) : null);
        return new FolderCard(idFor(folder, "card"), folder.path(), folder.name(),
                RunWords.held(folder.photos(), folder.videos()),
                Instant.EPOCH.equals(folder.changed())
                        ? "When it last changed is not known"
                        : "Last changed " + RunWords.howLongAgo(folder.changed()),
                notes, this.actions(folder));
    }

    /**
     * The note's lines, cut to what the fold will draw.
     *
     * <p>A line is written per photo a folder ever took, and the fold draws one wrapping label per
     * line. A junk folder off a large backlog carries thousands, which is a page nobody reads and a
     * scene graph that costs to build.
     *
     * @param lines a {@link List} of {@link String} every line the folder's notes hold
     * @return a {@link List} of {@link String} what to draw
     */
    private static List<String> capped(final List<String> lines) {
        return lines.size() <= MOST_LINES_DRAWN ? lines : lines.subList(0, MOST_LINES_DRAWN);
    }

    /**
     * What to say about the lines the fold leaves undrawn, or null where it draws them all.
     *
     * @param held int how many lines the folder's notes hold
     * @return {@link String} the sentence
     */
    private static @Nullable String beyondTheFold(final int held) {
        if (held <= MOST_LINES_DRAWN) {
            return null;
        }
        return RunWords.counted(held - MOST_LINES_DRAWN, "more line is", "more lines are")
                + " in the note file itself.";
    }

    /**
     * What to show under an opened fold in place of the lines, or null where there are lines.
     *
     * @param failed what went wrong reading them, or null where nothing did
     * @param written a {@link List} of {@link String} what the read came back with
     * @return {@link String} the line to show instead, or null
     */
    private static @Nullable String nothingIn(final @Nullable String failed,
                                              final List<String> written) {
        if (failed != null) {
            return failed;
        }
        return written.isEmpty() ? NOTHING_WRITTEN : null;
    }

    /**
     * What can be done about one folder.
     *
     * <p>The move goes inactive while anything is running. The app takes one job at a time, so a
     * press then would put a question whose answer is a refusal.
     *
     * @param folder {@link Folder} the folder
     * @return a {@link List} of {@link Action} its buttons, in the order drawn
     */
    private List<Action> actions(final Folder folder) {
        return List.of(
                new Action(idFor(folder, "open"), OPEN, Kind.OPEN, true, true,
                        folder.name(), folder.name(), folder.path(), null, null),
                new Action(idFor(folder, "move"), RESCUE_TO_SORTED, Kind.RESCUE, false,
                        !this.pipeline.isBusy(), folder.name(), folder.name(), folder.path(),
                        rescueQuestion(folder), folder.root().rescueRoot()));
    }

    /**
     * What to ask before a folder's photos go back into Sorted.
     *
     * <p>Claims no count and promises no removal. A stray file that is not media moves nowhere, so
     * the folder can survive a run that moved every photo in it.
     *
     * <p>Going ahead leads. A reader who opened this has decided to rescue, so the button they came
     * for is the one to meet first.
     *
     * @param folder {@link Folder} the folder about to be moved
     * @return {@link Confirmation} the question
     */
    private static Confirmation rescueQuestion(final Folder folder) {
        return new Confirmation("Rescue " + folder.name() + " to Sorted?",
                "The photos and videos still in it go back into Sorted. A photo Sorted already "
                        + "holds is deleted from the folder rather than put back twice.",
                RESCUE_TO_SORTED, "Cancel", true);
    }

    /**
     * What to say where a root could not be read.
     *
     * @param listing {@link ReviewListing} the reading this view is drawn from
     * @return {@link String} the line, or null where all three were read
     */
    private static @Nullable String unreadableLine(final ReviewListing listing) {
        final List<Path> failed = listing.unreadable();
        if (failed.isEmpty()) {
            return null;
        }
        return UNREADABLE.formatted(RunWords.listed(failed.stream().map(Path::toString).toList()));
    }

    /**
     * Forgets every fold.
     *
     * <p>A reading is what says which folders are there. A fold left open over one that has since
     * gone would draw its lines under a card the fresh reading does not carry.
     */
    private void closeNotes() {
        synchronized (this.foldLock) {
            this.asked.clear();
            this.folds.clear();
        }
    }

    /**
     * What is drawn under one open fold.
     *
     * @param lines a {@link List} of {@link String} what was written there, cut to what is drawn
     * @param beyondTheFold {@link String} what to say about the lines it does not draw, or null
     *     where it draws them all
     * @param failed {@link String} what to say instead where the read failed, or null where it did
     *     not
     */
    private record Fold(List<String> lines, @Nullable String beyondTheFold,
                        @Nullable String failed) {
    }

    /**
     * One of a card's control ids.
     *
     * <p>Built from the folder's own name, so a test and a render can name the control that acts on
     * a known folder. A name can hold anything a filesystem allows, so everything outside a plain
     * word is flattened to one dash.
     *
     * @param folder {@link Folder} the folder the control belongs to
     * @param part {@link String} which control
     * @return {@link String} the id
     */
    private static String idFor(final Folder folder, final String part) {
        return "review-" + part + "-" + idOf(folder.root()) + "-"
                + folder.name().toLowerCase(Locale.UK).replaceAll("[^a-z0-9]+", "-");
    }

    /**
     * The part of a control's id that says which root its folder is under.
     *
     * <p>The root rather than the section, so the two sections under Review do not give one folder
     * two possible ids.
     *
     * @param root {@link Root} which root
     * @return {@link String} the id
     */
    private static String idOf(final Root root) {
        return root.name().toLowerCase(Locale.UK);
    }
}
