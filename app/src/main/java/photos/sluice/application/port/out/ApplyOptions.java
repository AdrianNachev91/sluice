package photos.sluice.application.port.out;

// Knobs passed to an apply run, and read by ApplyPlanner.validate(). allowPartial waives the
// one-shard-per-montage requirement, so a run can proceed with some montages left completely
// untouched (their photos simply stay where they are). A separate record from CullOptions: apply
// has nothing analogous to a provider timeout.
public record ApplyOptions(boolean allowPartial) {
}
