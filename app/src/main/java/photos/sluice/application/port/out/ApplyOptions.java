package photos.sluice.application.port.out;

/**
 * Knobs passed to an apply run, read by {@code ApplyPlanner.validate()}. Its {@code allowPartial}
 * flag waives the one-shard-per-montage requirement, so a run can proceed with some montages left
 * completely untouched. Their photos simply stay where they are.
 *
 * <p>Kept separate from {@link CullOptions} because apply has nothing analogous to a provider
 * timeout.
 */
public record ApplyOptions(boolean allowPartial) {
}
