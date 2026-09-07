package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;

/**
 * What the dashboard draws once a run has ended, chosen from {@link RunResults} and
 * carrying only display-ready values. The view reads fields off this and decides nothing about what
 * they mean.
 *
 * <p>An ending is not a success. A sift that paused for its shards, a run that stopped at its spend
 * ceiling and a job that threw all arrive here. {@code tone} is what separates them.
 *
 * @param heading {@link String} how the run ended, named after the work the user chose
 * @param tone {@link Tone} how the heading should read
 * @param detail a sentence about the ending where the heading needs one, or null
 * @param counts a {@link List} of {@link Count} what the run did, one row each, empty where it did
 *     nothing worth counting
 * @param warning {@link Warning} something that stopped nothing and is still worth a reader's
 *     attention, or null
 * @param action {@link CardAction} the one thing this card offers beyond Done, or null where it
 *     offers nothing
 * @param doneLabel {@link String} what the button back to the launcher says
 * @param location {@link Location} the screen the detail can be acted on from, or null where it
 *     names nowhere to go
 */
public record RunResultView(String heading, Tone tone, @Nullable String detail, List<Count> counts,
                            @Nullable Warning warning,
                            @Nullable CardAction action, String doneLabel,
                            @Nullable Location location) {

    /**
     * A card whose detail names nowhere to go.
     *
     * @param heading {@link String} how the run ended
     * @param tone {@link Tone} how the heading should read
     * @param detail a sentence about the ending, or null
     * @param counts a {@link List} of {@link Count} what the run did
     * @param warning {@link Warning} something worth attention that stopped nothing, or null
     * @param action {@link CardAction} the one thing offered beyond Done, or null
     * @param doneLabel {@link String} what the button back to the launcher says
     */
    public RunResultView(final String heading, final Tone tone, final @Nullable String detail,
                         final List<Count> counts, final @Nullable Warning warning,
                         final @Nullable CardAction action, final String doneLabel) {
        this(heading, tone, detail, counts, warning, action, doneLabel, null);
    }

    /**
     * Defensively copies the mutable collection component.
     *
     * @param heading {@link String} how the run ended
     * @param tone {@link Tone} how the heading should read
     * @param detail a sentence about the ending, or null
     * @param counts a {@link List} of {@link Count} what the run did
     * @param warning {@link Warning} something worth attention that stopped nothing, or null
     * @param action {@link CardAction} the one thing offered beyond Done, or null
     * @param doneLabel {@link String} what the button back to the launcher says
     * @param location {@link Location} the screen the detail can be acted on from, or null
     */
    public RunResultView {
        counts = List.copyOf(counts);
    }

    /**
     * How a run's ending should read.
     *
     * <p>Three rather than two, because most of the ways a sift ends are neither. A sift waiting on
     * an agent's decisions did everything asked of it and is still not finished. Drawn as a
     * success it claims work nobody has done; drawn as a fault it blames the reader for a pause
     * the design intends.
     */
    public enum Tone {

        /** The work asked for was carried out. */
        FINISHED,

        /** The run stopped with work left, and nothing went wrong. */
        UNFINISHED,

        /** The run could not do what it was asked. */
        FAILED
    }

    /**
     * Something a run noticed and did not stop for.
     *
     * <p>Two parts rather than one paragraph. The stripe carries its weight through its own ground
     * and border, so a whole paragraph set bold in the caution colour is shouting. A short line a
     * reader can take in at a glance earns that weight; what it means does not.
     *
     * @param headline {@link String} what a reader needs to know, in one line
     * @param detail {@link String} what caused it and what Sluice did instead
     */
    public record Warning(String headline, String detail) {
    }

    /**
     * One row of what a run did.
     *
     * <p>A row names a place files went, and a card's rows partition what it handled. A count of
     * some of one place is drawn under it instead, indented. A number overlapping its neighbours
     * cannot sit in a list that adds up.
     *
     * @param id {@link String} the control's id, for the screen to set on it
     * @param label {@link String} what was counted
     * @param value {@link String} the count, written out
     * @param partOfTheRowAbove boolean whether this counts some of the row before it rather than a
     *     place of its own
     */
    public record Count(String id, String label, String value, boolean partOfTheRowAbove) {

        /**
         * A row counting a place of its own.
         *
         * @param id {@link String} the control's id
         * @param label {@link String} what was counted
         * @param value {@link String} the count, written out
         */
        public Count(final String id, final String label, final String value) {
            this(id, label, value, false);
        }
    }

    /**
     * The one thing a result card offers beyond Done.
     *
     * <p>Sealed, and the two arms carry different things, because they ask the reader in different
     * places. Continuing states its case on the card, above its own button. Sifting states it in a
     * confirm on the press, since what it costs is not known while the card is being built.
     *
     * <p>A card offers at most one, so the view holds a single nullable field rather than one per
     * arm. Nothing produces both: continuing belongs to a sift that stopped, and sifting to a sort
     * that finished.
     */
    public sealed interface CardAction {

        /**
         * An offer to continue a run that stopped with work still in front of it.
         *
         * <p>{@code prepDir} is the stopped run's own identity, handed back when the button is
         * pressed rather than something for the screen to render. What the screen shows is
         * {@code label} and the question above it.
         *
         * @param note {@link String} what the reader is being asked
         * @param label {@link String} what the button says
         * @param prepDir {@link Path} which stopped run it continues
         */
        record ContinueRun(String note, String label, Path prepDir) implements CardAction {
        }

        /**
         * An offer to sift the timeframe a finished sort filled.
         *
         * <p>No question of its own. What this one spends depends on a count the card cannot have
         * yet. So the question is asked on the press, where a fresh count is in and can be named.
         *
         * <p>{@code justSorted} is what this run put into that timeframe, which the question splits
         * out from the timeframe's whole count. The two differ whenever the year already held
         * photos, and the difference is the part a reader would not otherwise expect to pay for.
         *
         * @param label {@link String} what the button says
         * @param year int the timeframe it would sift
         * @param justSorted int how many photos this run filed into that timeframe
         */
        record SiftNow(String label, int year, int justSorted) implements CardAction {
        }
    }
}
