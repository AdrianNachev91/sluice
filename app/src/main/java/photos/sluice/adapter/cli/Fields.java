package photos.sluice.adapter.cli;

import java.util.LinkedHashMap;
import java.util.SequencedMap;

/**
 * Builds the named values a document carries, keeping the order they were written in.
 *
 * <p>A map wherever the keys depend on which kind of thing is being reported.
 *
 * <p>The order is kept because it is what a person diffing two runs reads.
 */
final class Fields {

    /**
     * Prevents instantiation of this static utility class.
     */
    private Fields() {
    }

    /**
     * Pairs up alternating keys and values.
     *
     * @param pairs {@link Object}[] a key, its value, a key, its value
     * @return a {@link SequencedMap} of {@link String} to {@link Object} the named values
     * @throws IllegalArgumentException when a key was given no value
     */
    static SequencedMap<String, Object> of(final Object... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("A field needs a name and a value: got " + pairs.length + " of them");
        }
        final SequencedMap<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            fields.put((String) pairs[i], pairs[i + 1]);
        }
        return fields;
    }
}
