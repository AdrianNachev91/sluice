package photos.sluice.adapter.cli;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

/**
 * A command that was refused, in both shapes at once: what a person is told, and what a machine
 * acts on.
 *
 * <p>The two travel together because they are one decision. Whatever works out that a command
 * cannot run already knows the folder, the property or the credential involved.
 *
 * @param kind {@link RefusalKind} which refusal this is
 * @param sentence {@link String} what a person is told, on the error stream
 * @param detail a {@link SequencedMap} of {@link String} to {@link Object} the fields this kind
 *        carries, in a fixed order
 */
public record Refusal(RefusalKind kind, String sentence, SequencedMap<String, Object> detail) {

    /**
     * Copies the fields, so nothing can change a refusal after it was worked out.
     *
     * @param kind {@link RefusalKind} which refusal this is
     * @param sentence {@link String} what a person is told, on the error stream
     * @param detail a {@link SequencedMap} of {@link String} to {@link Object} the fields this kind carries
     */
    public Refusal {
        detail = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(detail));
    }

    /**
     * A refusal carrying no fields beyond which one it is.
     *
     * @param kind {@link RefusalKind} which refusal this is
     * @param sentence {@link String} what a person is told
     * @return {@link Refusal} the refusal
     */
    public static Refusal of(final RefusalKind kind, final String sentence) {
        return new Refusal(kind, sentence, Fields.of());
    }

    /**
     * The document's own shape: which refusal it is, and the fields that go with it. The sentence
     * stays off it.
     *
     * @return {@link Payload} what the document says about this refusal
     */
    public Payload payload() {
        return new Payload(this.kind, this.detail);
    }

    /**
     * Joins several sentences into the one a refusal shows.
     *
     * @param sentences a {@link List} of {@link String} the sentences, in reading order
     * @return {@link String} them, run together
     */
    static String sentences(final List<String> sentences) {
        return String.join(" ", sentences);
    }

    /**
     * A value the caller typed, as a sentence repeating it back should show it.
     *
     * <p>A shell hands over a blank argument as readily as any other, from an unset variable or a
     * stray pair of quotes. Dropped straight into a sentence it leaves a hole the reader cannot
     * see, so it is named rather than shown.
     *
     * @param value {@link String} what the caller typed
     * @return {@link String} the value, or what to call it where there is nothing to show
     */
    static String shown(final String value) {
        return value.isBlank() ? "an empty value" : value;
    }

    /**
     * What a refused command's document carries.
     *
     * @param kind {@link RefusalKind} which refusal this is
     * @param detail a {@link SequencedMap} of {@link String} to {@link Object} the fields that go
     *        with that kind
     */
    public record Payload(RefusalKind kind, SequencedMap<String, Object> detail) {
    }
}
