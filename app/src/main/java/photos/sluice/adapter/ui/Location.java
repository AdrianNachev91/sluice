package photos.sluice.adapter.ui;

/**
 * A screen a message can send the reader to, and the words on the control that takes them.
 */
public enum Location {

    /** Where every folder, category and provider setting is changed. */
    SETTINGS("Open in Settings"),

    /** Where a sift already under way is carried on or thrown away. */
    RUNS("Open in Runs");

    private final String label;

    Location(final String label) {
        this.label = label;
    }

    /**
     * What the control says.
     *
     * @return {@link String} the words a reader presses
     */
    public String label() {
        return this.label;
    }
}
