package photos.sluice.application.port.out;

/**
 * One model a vision provider offers, as a configuration surface needs it: the value that gets
 * saved, and what to call it on screen.
 *
 * <p>The two are separate because a provider's own identifier is rarely what a person would
 * recognise. A surface shows the label and stores the id.
 *
 * @param id {@link String} the value saved as the configured model, and sent to the provider
 * @param label {@link String} what a surface calls it, in the user's own terms
 */
public record ModelOption(String id, String label) {

    /**
     * Refuses a blank half. An option with no id saves nothing, and one with no label draws an empty
     * row a user cannot tell from its neighbours.
     */
    public ModelOption {
        // A provider outside this module builds these, and nothing at that boundary enforces the
        // annotations; the IDE reads these guards as always-false.
        //noinspection ConstantValue
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A model option needs an id");
        }
        //noinspection ConstantValue
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("Model option '" + id + "' needs a label");
        }
    }
}
