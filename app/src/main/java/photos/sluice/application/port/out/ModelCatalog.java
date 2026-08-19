package photos.sluice.application.port.out;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The models a vision provider offers, and which of them it recommends.
 *
 * <p>A provider answers with one of these so a configuration surface can offer a choice rather than
 * a text field. Choosing from what the provider listed is what makes an unusable model id
 * unreachable instead of merely reported later.
 *
 * <p>The options are in the order a surface offers them, and the first one is what a surface starts
 * on when nothing is recommended. A provider that ranks its own models puts the one it would rather
 * a user landed on by accident first.
 *
 * <p>What a provider carries in its own {@link VisionProviderDescriptor} is reachable with no
 * credential and no network. That is what lets a fresh install open on a working default. A
 * provider that can also ask its service answers with the account's own catalog through
 * {@link ProviderCheck.Accepted}, and that one supersedes this.
 *
 * @param options a {@link List} of {@link ModelOption} every model this catalog offers, in the
 *     order to offer them
 * @param recommended {@link String} id of the one to mark as recommended, or null when this catalog
 *     recommends none of them
 */
public record ModelCatalog(List<ModelOption> options, @Nullable String recommended) {

    /**
     * Holds its own list, refuses an empty one, and ties any recommendation to it.
     *
     * <p>An empty catalog draws a picker with nothing to select. Refusing it here is half of the
     * rule; {@link VisionProviderDescriptor} refusing a catalog exactly when the provider runs no
     * model of its own is the other half. Together they mean a provider offering a model setting
     * cannot be built offering nothing.
     *
     * <p>A recommendation naming a model the catalog does not offer is a default that cannot be
     * selected. Recommending nothing at all is allowed. An account may have no access to whichever
     * model the provider would otherwise name, and promoting a second choice would invent a
     * recommendation nobody made.
     */
    public ModelCatalog {
        options = List.copyOf(options);
        if (options.isEmpty()) {
            throw new IllegalArgumentException("A model catalog must offer at least one model");
        }
        if (recommended != null && options.stream().noneMatch(option -> option.id().equals(recommended))) {
            throw new IllegalArgumentException(
                    "Recommended model '" + recommended + "' is not one of the offered options");
        }
    }
}
