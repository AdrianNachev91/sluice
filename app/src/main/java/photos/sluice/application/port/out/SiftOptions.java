package photos.sluice.application.port.out;

import org.jspecify.annotations.Nullable;

import java.time.Duration;

/**
 * Knobs passed to {@link VisionSieve#sift}. Its {@code allowPartial} flag waives the
 * one-shard-per-montage requirement, so a run can proceed with some montages unsifted. The
 * {@code timeout} field is meant to bound how long an automated provider waits on its own work.
 * The {@code ceiling} bounds what the run may consume, and is null for a run nobody is paying for.
 *
 * <p>Providers honor these unevenly, and each documents its own take.
 */
public record SiftOptions(boolean allowPartial, @Nullable Duration timeout, @Nullable SpendCeiling ceiling) {

    /**
     * Options with neither a timeout nor a ceiling.
     *
     * @param allowPartial boolean whether a partial shard set is acceptable
     * @return {@link SiftOptions} those options, with no timeout and no ceiling
     */
    public static SiftOptions unbounded(final boolean allowPartial) {
        return new SiftOptions(allowPartial, null, null);
    }
}
