package photos.sluice.domain.cull;

/**
 * One user-configured classification category. The {@code name} field is the action id a culling
 * decision carries, and {@link ShardValidator} accepts only the recorded names. The
 * {@code description} field is the "what belongs here" prose an automated vision provider renders
 * into its culling prompt.
 *
 * <p>Both fields are required. A blank name is unroutable. A blank description would render a
 * hollow prompt section and silently degrade cull recall, so construction fails loud instead.
 *
 * <p>The name also becomes the folder a culled file is moved into, which is what constrains its
 * shape. {@link CategoryName} owns that rule and states why.
 *
 * <p>A card is a domain value rather than a port type because {@link PrepDir} carries the set a run
 * was prepped under. The cull settings port hands back the configured cards the same way it hands
 * back a {@link MontageConfig}.
 */
public record CullCategory(String name, String description) {

    /**
     * Validates that both name and description are present, and that the name can become a folder.
     *
     * @param name {@link String} the action id this category carries
     * @param description {@link String} the "what belongs here" prompt prose
     */
    public CullCategory {
        // Config binding can pass null reflectively; the IDE reads these guards as always-false.
        //noinspection ConstantValue
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Cull category name must not be blank");
        }
        final String problem = CategoryName.problemWith(name);
        if (problem != null) {
            throw new IllegalArgumentException("Cull category name '" + name + "' " + problem);
        }
        //noinspection ConstantValue
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Cull category '" + name + "' has no description");
        }
    }
}
