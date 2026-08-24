package photos.sluice.adapter.ui;

/**
 * The five kinds of work the dashboard can start, each carrying the words the app says about it.
 *
 * <p>These are the user's words rather than the engine's. What the screen calls sifting is what the
 * code calls culling, and what it calls moving to the library is what the code calls committing.
 *
 * <p>An enum rather than the button ids as strings. The screen hands one of these back to say which
 * button was pressed, and a value that came from this list cannot be one nothing recognises. A
 * lookup on a string would need an answer for a spelling no button could produce.
 */
public enum RunMode {

    /** Dates what is in the Inbox and files it into Sorted. */
    SORT("Sort", "Sorting",
            "Reads the dates on what is in your Inbox and moves it into Sorted, by year and month. "
                    + "Takes the oldest year in your Inbox.",
            // No hint. The line under the mode buttons already says which year is taken, and saying
            // it twice leaves two sentences to keep in step.
            "", 3),

    /** Has a vision provider look at what is in Sorted and sort it into the photo categories. */
    SIFT("Sift", "Sifting",
            "Sifts through your sorted photos and organises them into categories.",
            "Type a year, or click one below. Add a run of months after it like 2019 6-8, or pick "
                    + "months out like 2019 6,8,11.", 3),

    /**
     * Moves what is in Sorted into the library.
     *
     * <p>Both words are the vocabulary lock's own, rather than a possessive reading of them. What a
     * progress area shows is "Moving to library...", which is the phrase that table names.
     */
    MOVE_TO_LIBRARY("Move to library", "Moving to library",
            "Moves what is in Sorted into your library.",
            "Leave this empty to move everything in Sorted. Or type a year, and a run of months "
                    + "after it if you want less, like 2019 6-8.", 1),

    /** Sorts and then sifts, in one go. */
    CURATE("Curate", "Curating",
            "Sorts, then sifts automatically. Takes the oldest year in your Inbox.",
            "Curating sorts your photos before it looks at them, so what the looking costs is not "
                    + "known until the sorting is done.", 6),

    /**
     * Moves what is left in a Review folder into the library.
     *
     * <p>Its hint is a placeholder rather than an answer. It names the missing screen rather than an
     * empty result, so that a Review folder holding files is never called empty.
     */
    RESCUE("Rescue", "Rescuing",
            "Moves what is left in a Review folder into your library.",
            "Rescue arrives with the Review screen.", 1);

    private final String label;
    private final String verb;
    private final String explained;
    private final String scopeHint;
    private final int phases;

    /**
     * Words one mode.
     *
     * @param label {@link String} what the button choosing it says
     * @param verb {@link String} how to name what it does, at the start of a sentence
     * @param explained {@link String} what it does to somebody's photos, in one sentence
     * @param scopeHint {@link String} what its scope field accepts, empty where it takes nothing
     * @param phases int the most progress phases a run of it can report
     */
    RunMode(final String label, final String verb, final String explained, final String scopeHint,
            final int phases) {
        this.label = label;
        this.verb = verb;
        this.explained = explained;
        this.scopeHint = scopeHint;
        this.phases = phases;
    }

    /**
     * The most progress phases a run of this mode can report.
     *
     * <p>Room for this many bars is held from the first frame, so the controls under them do not
     * move as each phase arrives. A screen that shifted every time the work advanced would put the
     * button somewhere else each time somebody reached for it.
     *
     * <p>A ceiling rather than a promise. A run that ends early reports fewer, and the reserved
     * room simply goes unused. An engine reporting more than this grows the bars past the
     * reservation and moves the row below once. That is the old behaviour rather than a break.
     *
     * @return int the most bars this mode can draw
     */
    public int phases() {
        return this.phases;
    }

    /**
     * What the button choosing this mode says.
     *
     * @return {@link String} the mode's own name
     */
    public String label() {
        return this.label;
    }

    /**
     * How to name what this mode does, at the start of a sentence.
     *
     * @return {@link String} the mode as a verb
     */
    public String verb() {
        return this.verb;
    }

    /**
     * What this mode does to somebody's photos, in one sentence.
     *
     * <p>The button row names five actions and says nothing about any of them. A name alone tells a
     * reader which one they picked, never what it is about to do. Two of the five move files out of
     * a folder they will not think to look in afterwards.
     *
     * @return {@link String} what it does
     */
    public String explained() {
        return this.explained;
    }

    /**
     * What this mode's scope field accepts, said before anything is typed into it.
     *
     * <p>Stated up front rather than only refused afterwards. The mode is chosen before anybody
     * types, so the screen knows what it will accept and can say so, which the command line cannot.
     *
     * @return {@link String} the hint under the field, empty where the field takes nothing
     */
    public String scopeHint() {
        return this.scopeHint;
    }

    /**
     * What the button that starts this mode says.
     *
     * <p>Built from the same name the mode's own button carries, so the two cannot come to disagree
     * about what this work is called.
     *
     * <p>Run is the verb here, which the vocabulary carve-out allows. What it must not become is the
     * noun: a sift a user started is a sift, never a run.
     *
     * @return {@link String} what it says
     */
    public String started() {
        return "Run " + this.label;
    }
}
