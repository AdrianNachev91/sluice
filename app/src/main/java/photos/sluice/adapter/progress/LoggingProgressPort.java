package photos.sluice.adapter.progress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.ProgressPort;

/**
 * A {@link ProgressPort} that reports every event as a single log line: phase start, tick, and
 * phase end. That keeps a job observable via the console or log file.
 *
 * <p>Registered under every profile but the command line's, which reports on its own error stream
 * instead. {@code Pipeline} requires a {@link ProgressPort} and is itself unprofiled. So every
 * process that builds a context at all needs exactly one reporter registered in it.
 *
 * <p>Exactly one binds in both directions. Two candidates make {@code Pipeline}'s injection
 * ambiguous and the context fails to start. None leaves it with nothing to inject, and the context
 * fails just the same. So a reporter added beside this one is profile-exclusive against it, and
 * neither gate may widen or narrow without the other's moving to match.
 * {@code CliProfileStartupTest} counts the ports registered under the cli profile and requires
 * exactly one. It counts registrations rather than resolutions, which is why marking a second one
 * primary does not satisfy it.
 */
@Component
@Profile("!cli")
public class LoggingProgressPort implements ProgressPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingProgressPort.class);

    /**
     * Logs the start of a phase.
     *
     * @param phase {@link String} the phase name
     */
    @Override
    public void phaseStarted(final String phase) {
        log.info("{}", phase);
    }

    /**
     * Logs progress within a phase.
     *
     * @param phase {@link String} the phase name
     * @param current int the current progress count
     * @param total int the total count for this phase
     */
    @Override
    public void tick(final String phase, final int current, final int total) {
        log.info("{} {}/{}", phase, current, total);
    }

    /**
     * Logs the end of a phase.
     *
     * @param phase {@link String} the phase name
     */
    @Override
    public void phaseFinished(final String phase) {
        log.info("{} done", phase);
    }
}
