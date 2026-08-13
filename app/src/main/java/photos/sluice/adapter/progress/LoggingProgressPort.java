package photos.sluice.adapter.progress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.ProgressPort;

/**
 * A {@link ProgressPort} that reports every event as a single log line: phase start, tick, and
 * phase end. That keeps a job observable via the console or log file.
 *
 * <p>Registered under every profile. {@code Pipeline} requires a {@link ProgressPort} and is itself
 * unprofiled. So any process that builds a context at all needs one of these to exist.
 *
 * <p>Which means a second implementation cannot simply be added beside this one. Two candidates
 * make {@code Pipeline}'s injection ambiguous and the context fails to start, for any process that
 * builds one. A new reporter is therefore profile-exclusive against this one, or primary over it.
 * {@code CliProfileStartupTest} counts the registered ports, and is what fails if neither is done.
 */
@Component
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
