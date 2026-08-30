package photos.sluice.adapter.cli;

/**
 * The wire shape for what a redo asked for, and the reading that builds it.
 */
public final class RedoPayloads {

    /**
     * Prevents instantiation of this static utility class.
     */
    private RedoPayloads() {
    }

    /**
     * The instructions to hand an agent, once a run's rejected answers are set aside.
     *
     * @param prompt {@link String} the text to hand it
     */
    public record PromptPayload(String prompt) {
    }
}
