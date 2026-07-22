package photos.sluice.application.port.out;

// One user-configured classification category. name is the action id a culling decision carries;
// ShardValidator accepts exactly the configured names. description is the "what belongs here"
// prose an automated vision provider renders into its culling prompt. Both are required. A blank
// name is unroutable. A blank description would render a hollow prompt section and silently
// degrade cull recall, so construction fails loud instead.
public record CullCategory(String name, String description) {

    public CullCategory {
        // Config binding can pass null reflectively; the IDE reads these guards as always-false.
        //noinspection ConstantValue
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Cull category name must not be blank");
        }
        //noinspection ConstantValue
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Cull category '" + name + "' has no description");
        }
    }
}
