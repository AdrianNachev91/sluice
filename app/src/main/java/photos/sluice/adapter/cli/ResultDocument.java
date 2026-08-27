package photos.sluice.adapter.cli;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

/**
 * The one document a command writes when it was asked for machine-readable output.
 *
 * <p>A caller switches on {@code status}, which is the same thing the exit code says, and
 * everything that differs per command sits under {@code result}. So a caller driving several
 * commands writes the outer read once.
 *
 * <p>{@code command} is the verb the parser resolved. A failure that stopped the app before
 * anything was parsed has none, and leaves the field out rather than inventing one.
 *
 * <p>There is no schema version. The app and the skill teaching an agent to drive it ship together,
 * so there is no pairing of versions for a field to disambiguate.
 *
 * @param command {@link String} the verb that ran, or null when none was reached
 * @param status {@link CommandStatus} how it ended
 * @param result {@link Object} what that command has to say, or null when it has nothing
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResultDocument(@Nullable String command, CommandStatus status, @Nullable Object result) {
}
