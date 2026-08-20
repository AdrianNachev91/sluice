package photos.sluice.application.port.in;

import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.ProviderCheck;
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

    /**
     * Asks one provider whether the credential stored for it is accepted, and what that credential
     * can run.
     *
     * <p>The check lives here rather than on the provider itself. Handing out the provider would
     * hand out its culling too, and a configuration surface has no business starting a run.
     *
     * <p>May take as long as a network call to a service outside this machine.
     *
     * @param id {@link String} the provider id to ask, as {@link #providers()} spells it
     * @return {@link ProviderCheck} what that provider said
     * @throws IllegalArgumentException if nothing is registered under that id
     */
    ProviderCheck check(String id);

    /**
     * Sibling of {@link #check(String)}, checked against the given settings rather than what is
     * stored. For a surface trying a connection setting before it is saved.
     *
     * @param id {@link String} the provider id to ask, as {@link #providers()} spells it
     * @param candidate {@link CullProviderSettings} the connection settings to check
     * @return {@link ProviderCheck} what that provider said
     * @throws IllegalArgumentException if nothing is registered under that id
     */
    ProviderCheck check(String id, CullProviderSettings candidate);
}
