package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import photos.sluice.adapter.ui.RunLauncherView.Message;
import photos.sluice.adapter.ui.RunSetupPresenter.Confirmation;
import photos.sluice.application.port.in.RescueRoot;

import java.nio.file.Path;
import java.util.List;

/**
 * What the review screen draws, chosen from a {@link ReviewPresenter} and carrying only display-ready
 * values. The view reads fields off this and decides nothing about what they mean.
 *
 * @param heading {@link String} the screen's own name
 * @param explained what this screen is and what to do with it, or null where there is nothing on
 *     it to explain
 * @param unreadable what to say where one or more of the folders could not be read, or null where
 *     they all could
 * @param nothingYet what to say in place of the groups where there is nothing waiting at all, or
 *     null where there is
 * @param groups a {@link List} of {@link Group} one per section, in the order drawn
 * @param message {@link Message} what the screen has to report, or null where it has nothing
 */
public record ReviewView(String heading, @Nullable String explained, @Nullable String unreadable,
                         @Nullable String nothingYet, List<Group> groups,
                         @Nullable Message message) {

    /**
     * Defensively copies the mutable collection component.
     *
     * @param heading {@link String} the screen's own name
     * @param explained what this screen is and what to do with it
     * @param unreadable what to say where a folder could not be read
     * @param nothingYet what to say where there is nothing waiting at all
     * @param groups a {@link List} of {@link Group} one per section
     * @param message {@link Message} what the screen has to report
     */
    public ReviewView {
        groups = List.copyOf(groups);
    }

    /**
     * One heading, and every folder under it.
     *
     * <p>Grouped rather than explained per card, because every folder under one heading has the
     * same answer.
     *
     * @param id {@link String} the section's id, for the screen to set on it
     * @param heading {@link String} what this kind is called
     * @param explained {@link String} what put these photos here and what to do about them
     * @param folders a {@link List} of {@link FolderCard} the folders of this kind, newest first
     */
    public record Group(String id, String heading, String explained, List<FolderCard> folders) {

        /**
         * Defensively copies the mutable list.
         *
         * @param id {@link String} the section's id
         * @param heading {@link String} what this kind is called
         * @param explained {@link String} what put these photos here
         * @param folders a {@link List} of {@link FolderCard} the folders of this kind
         */
        public Group {
            folders = List.copyOf(folders);
        }
    }

    /**
     * One folder, as the card that stands for it.
     *
     * @param id {@link String} the card's id, for the screen to set on it
     * @param path {@link Path} which folder this is, handed back on a press. An identity, never
     *     something for the screen to read
     * @param name {@link String} what the folder is called, as a reader would say it
     * @param held {@link String} what is in it, counted
     * @param age {@link String} when it last changed, as a reader would say it
     * @param notes the fold over what Sluice wrote about these photos, or null under a root where
     *     nothing is ever written beside them
     * @param actions a {@link List} of {@link Action} what can be done about it, in the order drawn
     */
    public record FolderCard(String id, Path path, String name, String held, String age,
                             @Nullable Notes notes, List<Action> actions) {

        /**
         * Defensively copies the mutable list.
         *
         * @param id {@link String} the card's id
         * @param path {@link Path} which folder this is
         * @param name {@link String} what the folder is called
         * @param held {@link String} what is in it
         * @param age {@link String} when it last changed
         * @param notes the fold over what Sluice wrote, or null where nothing is ever written
         * @param actions a {@link List} of {@link Action} what can be done about it
         */
        public FolderCard {
            actions = List.copyOf(actions);
        }
    }

    /**
     * The card's fold over what Sluice wrote beside these photos.
     *
     * <p>Read only once it is opened. A note names every photo in its folder. Drawing them all up
     * front would read the whole of what a reader has so far asked to see none of.
     *
     * @param id {@link String} the fold's own control id, for the screen to set on it
     * @param label {@link String} what the control says
     * @param shown boolean whether the lines below it are showing
     * @param lines a {@link List} of {@link String} what was written, empty while it is shut
     * @param nothingWritten what to say in place of the lines where the folder carries no note, or
     *     null where it carries one
     */
    public record Notes(String id, String label, boolean shown, List<String> lines,
                        @Nullable String nothingWritten) {

        /**
         * Defensively copies the mutable list.
         *
         * @param id {@link String} the fold's own control id
         * @param label {@link String} what the control says
         * @param shown boolean whether the lines are showing
         * @param lines a {@link List} of {@link String} what was written
         * @param nothingWritten what to say where the folder carries no note
         */
        public Notes {
            lines = List.copyOf(lines);
        }
    }

    /**
     * One thing that can be done to a folder.
     *
     * <p>Carries the folder's own identity rather than leaving the screen to look it up. That is
     * handed straight back, never something for the screen to read.
     *
     * @param id {@link String} the control's id, for the screen to set on it
     * @param label {@link String} what the button says
     * @param kind {@link Kind} what pressing it does
     * @param leading boolean whether this is the way on from the card, drawn to be reached for
     * @param live boolean whether it can be pressed now. A control the screen is still offering,
     *     drawn inactive, rather than one it has taken away
     * @param folder {@link String} the folder's name below its own root
     * @param named {@link String} that folder as this card named it. A press landing on another
     *     screen then names it the way the reader just saw it named
     * @param path {@link Path} where the folder is
     * @param confirm {@link Confirmation} what to ask first, or null where the action needs no asking
     * @param root {@link RescueRoot} which root a rescue resolves the folder under, null on every
     *     other kind
     */
    public record Action(String id, String label, Kind kind, boolean leading, boolean live,
                         String folder, String named, Path path, @Nullable Confirmation confirm,
                         @Nullable RescueRoot root) {
    }

    /**
     * What an action does, which is what the screen switches on.
     */
    public enum Kind {

        /** Hands the folder to the file manager, where weeding and deleting happen. */
        OPEN,

        /** Moves what is left in the folder back into Sorted, dissolving the folder if it empties. */
        RESCUE
    }
}
