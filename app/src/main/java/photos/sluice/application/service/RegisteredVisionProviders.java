package photos.sluice.application.service;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.in.VisionProviderCatalog;
import photos.sluice.application.port.out.VisionCuller;
import photos.sluice.application.port.out.VisionProviderDescriptor;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The catalog over whichever vision providers this build registered.
 *
 * <p>Asked of the cullers themselves rather than of a list kept beside them. A provider added later
 * appears here by existing, and there is no second place to forget.
 *
 * <p>The order is by label because injection order is a property of the wiring. A dropdown built on
 * that could reorder itself between two launches of the same install.
 */
@Component
public class RegisteredVisionProviders implements VisionProviderCatalog {

    private final List<VisionProviderDescriptor> ordered;
    private final Map<String, VisionProviderDescriptor> byId;

    /**
     * Indexes what each registered culler says about itself, failing loud on a duplicate id.
     *
     * @param cullers a {@link List} of {@link VisionCuller} every registered provider
     */
    public RegisteredVisionProviders(final List<VisionCuller> cullers) {
        final List<VisionProviderDescriptor> descriptors = cullers.stream()
                .map(VisionCuller::describe)
                .toList();
        this.ordered = descriptors.stream()
                .sorted(Comparator.comparing(VisionProviderDescriptor::label))
                .toList();
        this.byId = descriptors.stream().collect(Collectors.toMap(
                VisionProviderDescriptor::id, Function.identity(),
                (first, _) -> {
                    throw new IllegalStateException("Two vision providers share id '" + first.id() + "'");
                }));
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
}
