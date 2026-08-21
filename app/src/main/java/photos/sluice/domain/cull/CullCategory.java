package photos.sluice.domain.cull;

import java.util.List;

/**
 * One user-configured classification category. The {@code name} field is the action id a culling
 * decision carries, and {@link ShardValidator} accepts only the recorded names. The
 * {@code description} field is the "what belongs here" prose an automated vision provider renders
 * into its culling prompt. {@code examples} are the sample subjects rendered beside it.
 *
 * <p>Name and description are required. A blank name is unroutable. A blank description would
 * render a hollow prompt section and silently degrade cull recall, so construction fails loud
 * instead. Examples are optional, and an empty list is how a card says it offers none.
 *
 * <p>{@code enabled} says whether a cull routes to this card at all. A disabled card is left out of
 * the set a run records at prep time, so it reaches neither the prompt nor validation. It is never
 * null once constructed: config binding passes null for a key nobody wrote, and a card nobody
 * switched off is on.
 *
 * <p>The name also becomes the folder a culled file is moved into, which is what constrains its
 * shape. {@link CategoryName} owns that rule and states why.
 *
 * <p>A card is a domain value rather than a port type because {@link PrepDir} carries the set a run
 * was prepped under. The cull settings port hands back the configured cards the same way it hands
 * back a {@link MontageConfig}.
 */
public record CullCategory(String name, String description, List<String> examples, Boolean enabled) {

    // Every enabled card's description and examples reach the system prompt of every montage request
    // a run makes. Length here is a cost paid per sheet, which is why there is a ceiling at all.
    // The longest card this app itself ships runs to 917 characters, so the description bound is
    // roughly double what we write. An example is a phrase rather than a sentence. A list longer
    // than twenty has stopped being a hint and become the description.
    private static final int MAX_DESCRIPTION = 2000;
    private static final int MAX_EXAMPLE = 100;
    private static final int MAX_EXAMPLES = 20;

    /**
     * Validates that name and description are present, and that the name can become a folder.
     * Normalizes the two optional components, both of which config binding can leave null.
     *
     * @param name {@link String} the action id this category carries
     * @param description {@link String} the "what belongs here" prompt prose
     * @param examples a {@link List} of {@link String} sample subjects, empty when the card offers none
     * @param enabled {@link Boolean} whether a cull routes to this card, null meaning it does
     */
    public CullCategory {
        // Config binding can pass null reflectively; the IDE reads these guards as always-false.
        //noinspection ConstantValue
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Photo category name must not be blank");
        }
        final String problem = CategoryName.problemWith(name);
        if (problem != null) {
            throw new IllegalArgumentException("Photo category name '" + name + "' " + problem);
        }
        //noinspection ConstantValue
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Photo category '" + name + "' has no description");
        }
        if (description.length() > MAX_DESCRIPTION) {
            throw new IllegalArgumentException("Photo category '" + name + "' has a description longer "
                    + "than the " + MAX_DESCRIPTION + " characters one may take");
        }
        // An entry that names nothing would otherwise reach the prompt as an empty bullet. Every
        // source can produce one, a screen and a config file alike.
        //noinspection ConstantValue
        examples = examples == null ? List.of()
                : examples.stream().filter(example -> example != null && !example.isBlank())
                        .map(String::strip).toList();
        if (examples.size() > MAX_EXAMPLES) {
            throw new IllegalArgumentException("Photo category '" + name + "' offers more than the "
                    + MAX_EXAMPLES + " examples one may carry");
        }
        for (final String example : examples) {
            if (example.length() > MAX_EXAMPLE) {
                throw new IllegalArgumentException("Photo category '" + name + "' has an example longer "
                        + "than the " + MAX_EXAMPLE + " characters one may take");
            }
        }
        //noinspection ConstantValue
        enabled = enabled == null || enabled;
    }

    /**
     * A card offering no examples and switched on. For the many callers that set neither.
     *
     * @param name {@link String} the action id this category carries
     * @param description {@link String} the "what belongs here" prompt prose
     * @return {@link CullCategory} the card
     */
    public static CullCategory of(final String name, final String description) {
        return new CullCategory(name, description, List.of(), Boolean.TRUE);
    }

    /**
     * The longest a description may be. For a control that has to stop a reader typing past it, and
     * a sentence that has to say so.
     *
     * @return int the character ceiling
     */
    public static int maxDescription() {
        return MAX_DESCRIPTION;
    }

    /**
     * The longest one example may be.
     *
     * @return int the character ceiling
     */
    public static int maxExample() {
        return MAX_EXAMPLE;
    }

    /**
     * The most examples one card may carry.
     *
     * @return int the ceiling
     */
    public static int maxExamples() {
        return MAX_EXAMPLES;
    }

}
