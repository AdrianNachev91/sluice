package photos.sluice.application.service;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.SiftException;
import photos.sluice.application.port.out.SiftOptions;
import photos.sluice.application.port.out.SiftReport;
import photos.sluice.application.port.out.SiftSettings;
import photos.sluice.application.port.out.ProviderType;
import photos.sluice.application.port.out.SpendForecast;
import photos.sluice.application.port.out.UnrecognisedProviderException;
import photos.sluice.application.port.out.VisionSieve;
import photos.sluice.domain.sift.PrepDir;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.job.ProgressCallback;
import photos.sluice.secrets.SecretId;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Routes a sift to the {@link VisionSieve} whose id matches the configured provider. Selection
 * is the whole job here: the chosen sieve owns how the shards are obtained.
 *
 * <p>The registered sieves are indexed once by id at construction, where a duplicate id fails
 * loud. The provider lookup itself runs on every {@code sift} call rather than in the
 * constructor. Construction never fails just because no sieve matches the configured provider
 * yet, and each call is decided by whichever provider value is current at that moment. An
 * unknown provider fails loud.
 */
@Component
public class SiftDispatcher {

    private final Map<String, VisionSieve> byId;
    private final SiftSettings settings;

    /**
     * Indexes the given sieves by id, failing loud on duplicate ids.
     *
     * @param sieves a {@link List} of {@link VisionSieve} the vision sieves to register
     * @param settings {@link SiftSettings} sift provider configuration
     */
    public SiftDispatcher(final List<VisionSieve> sieves, final SiftSettings settings) {
        this.byId = sieves.stream().collect(Collectors.toMap(
                sieve -> sieve.describe().id(), Function.identity(),
                (first, _) -> {
                    throw new IllegalStateException(
                            "Two vision sieves share id '" + first.describe().id() + "'");
                }));
        this.settings = settings;
    }

    /**
     * Sifts using the configured provider.
     *
     * @param prep {@link PrepDir} the prep directory to sift
     * @param options {@link SiftOptions} sift behavior options
     * @return {@link SiftReport} the sift report
     */
    public SiftReport sift(final PrepDir prep, final SiftOptions options) throws SiftException {
        return this.select().sift(prep, options);
    }

    /**
     * Sifts using the configured provider, reporting progress.
     *
     * @param prep {@link PrepDir} the prep directory to sift
     * @param options {@link SiftOptions} sift behavior options
     * @param progress {@link ProgressCallback} progress callback
     * @return {@link SiftReport} the sift report
     */
    public SiftReport sift(final PrepDir prep, final SiftOptions options, final ProgressCallback progress) throws SiftException {
        return this.select().sift(prep, options, progress);
    }

    /**
     * Sifts using the configured provider, reporting progress and honoring cancellation.
     *
     * @param prep {@link PrepDir} the prep directory to sift
     * @param options {@link SiftOptions} sift behavior options
     * @param progress {@link ProgressCallback} progress callback
     * @param cancellation {@link CancellationSignal} cancellation signal
     * @return {@link SiftReport} the sift report
     */
    public SiftReport sift(final PrepDir prep, final SiftOptions options, final ProgressCallback progress,
                           final CancellationSignal cancellation)
            throws SiftException {
        return this.select().sift(prep, options, progress, cancellation);
    }

    /**
     * Asks the configured provider what one call over prep would carry on the way in.
     *
     * @param prep {@link PrepDir} the prep directory a run would be made over
     * @return {@link SpendForecast} what one call would carry, or why that is not known
     */
    public SpendForecast forecast(final PrepDir prep) {
        return this.select().forecast(prep);
    }

    /**
     * Whether the configured provider works the given way.
     *
     * <p>Asked of the sieve that would run, rather than worked out from the configured id. The id
     * is a string a user can type, and the provider it names is the only thing that knows how it
     * works.
     *
     * <p>False when nothing is registered under the configured id, rather than a refusal. Every
     * caller is asking in order to decide whether to do something extra, so an id this build cannot
     * sift with answers no to all of them. The refusal for that belongs to a sift actually being
     * attempted, which is what {@link #sift} does.
     *
     * @param type {@link ProviderType} the way of working to test for
     * @return boolean true when a provider is registered for the configured id and works that way
     */
    public boolean configuredProviderIs(final ProviderType type) {
        final VisionSieve sieve = this.byId.get(this.settings.provider());
        return sieve != null && sieve.type() == type;
    }

    /**
     * Which credential the configured provider authenticates with, for a caller that has to know
     * whether one is stored before starting work.
     *
     * <p>The descriptor's answer rather than a lookup of its own. A provider that takes no
     * credential names none, so a null here is the provider saying it needs nothing, not a failure
     * to find out.
     *
     * <p>Null too when nothing is registered under the configured id, the same answer
     * {@link #configuredProviderIs} gives for the same case. A build that cannot sift with the
     * configured id has no credential to demand, and the refusal for the id itself belongs to
     * {@link #sift}.
     *
     * @return {@link SecretId} the credential the configured provider needs, or null when it needs
     *     none
     */
    public @Nullable SecretId configuredCredential() {
        final VisionSieve sieve = this.byId.get(this.settings.provider());
        return sieve == null ? null : sieve.describe().credential();
    }

    /**
     * Looks up the vision sieve registered for the configured provider.
     *
     * @return {@link VisionSieve} the selected vision sieve
     * @throws UnrecognisedProviderException if the configured provider matches nothing registered
     */
    private VisionSieve select() {
        final String provider = this.settings.provider();
        final VisionSieve sieve = this.byId.get(provider);
        if (sieve == null) {
            throw new UnrecognisedProviderException(provider, this.byId.keySet());
        }
        return sieve;
    }
}
