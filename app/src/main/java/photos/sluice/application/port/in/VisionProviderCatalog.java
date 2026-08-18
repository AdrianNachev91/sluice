package photos.sluice.application.port.in;

import photos.sluice.application.port.out.VisionProviderDescriptor;

import java.util.List;
import java.util.Optional;

/**
 * Every vision provider this install actually has, for a surface that has to offer a choice between
 * them.
 */
public interface VisionProviderCatalog {

    /**
     * Every registered provider, in a stable order so a dropdown does not reshuffle between
     * launches.
     *
     * @return a {@link List} of {@link VisionProviderDescriptor} what this install can cull with
     */
    List<VisionProviderDescriptor> providers();

    /**
     * The provider registered under one id.
     *
     * @param id {@link String} the provider id to look for
     * @return an {@link Optional} of {@link VisionProviderDescriptor} that provider, or empty when
     *         nothing is registered under that id
     */
    Optional<VisionProviderDescriptor> byId(String id);
}
