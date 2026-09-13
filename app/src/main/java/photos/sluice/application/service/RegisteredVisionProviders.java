package photos.sluice.application.service;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.in.VisionProviderCatalog;
import photos.sluice.application.port.out.SiftProviderSettings;
import photos.sluice.application.port.out.ProviderCheck;
import photos.sluice.application.port.out.VisionSieve;
import photos.sluice.application.port.out.VisionProviderDescriptor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The catalog over whichever vision providers this build registered.
 *
 * <p>Asked of the sieves themselves rather than of a list kept beside them. A provider added later
 * appears here by existing, and there is no second place to forget.
 *
 * <p>The order is by label because injection order is a property of the wiring. A dropdown built on
 * that could reorder itself between two launches of the same install.
 */
@Component
public class RegisteredVisionProviders implements VisionProviderCatalog {

    private final List<VisionProviderDescriptor> ordered;
    private final Map<String, VisionProviderDescriptor> byId;
    private final Map<String, VisionSieve> sievesById;

    /**
     * Indexes what each registered sieve says about itself, failing loud on a duplicate id.
     *
     * @param sieves a {@link List} of {@link VisionSieve} every registered provider
     */
    public RegisteredVisionProviders(final List<VisionSieve> sieves) {
        // One describe() per sieve, and both maps keyed off that same answer. Asking twice would
        // let a provider that answers differently each time be indexed under an id its descriptor
        // does not carry.
        final var descriptors = new ArrayList<VisionProviderDescriptor>();
        final var sievesByProviderId = new LinkedHashMap<String, VisionSieve>();
        final var descriptorsByProviderId = new LinkedHashMap<String, VisionProviderDescriptor>();
        for (final VisionSieve sieve : sieves) {
            final VisionProviderDescriptor descriptor = sieve.describe();
            if (sievesByProviderId.put(descriptor.id(), sieve) != null) {
                throw new IllegalStateException("Two vision providers share id '" + descriptor.id() + "'");
            }
            descriptorsByProviderId.put(descriptor.id(), descriptor);
            descriptors.add(descriptor);
        }
        this.ordered = descriptors.stream()
                .sorted(Comparator.comparing(VisionProviderDescriptor::label))
                .toList();
        this.byId = Map.copyOf(descriptorsByProviderId);
        this.sievesById = Map.copyOf(sievesByProviderId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The stable order the port asks for is alphabetical by label.
     */
    @Override
    public List<VisionProviderDescriptor> providers() {
        return this.ordered;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<VisionProviderDescriptor> byId(final String id) {
        return Optional.ofNullable(this.byId.get(id));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every id a surface can offer came out of {@link #providers()}, so the refusal below is for
     * a caller that built an id instead of choosing one.
     */
    @Override
    public ProviderCheck check(final String id) {
        return this.sieveFor(id).check();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every id a surface can offer came out of {@link #providers()}, so the refusal below is for
     * a caller that built an id instead of choosing one.
     */
    @Override
    public ProviderCheck check(final String id, final SiftProviderSettings candidate) {
        return this.sieveFor(id).check(candidate);
    }

    private VisionSieve sieveFor(final String id) {
        final VisionSieve sieve = this.sievesById.get(id);
        if (sieve == null) {
            throw new IllegalArgumentException("No vision provider is registered under '" + id
                    + "'; registered: " + this.ordered.stream()
                    .map(VisionProviderDescriptor::id)
                    .collect(Collectors.joining(", ")));
        }
        return sieve;
    }
}
