package photos.sluice.adapter.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.ProgressPort;

/**
 * A {@link ProgressPort} that reports every event as a single log line: phase start, tick, and
 * phase end. That keeps a job observable via the console or log file. This is the only
 * {@link ProgressPort} implementation until a desktop dashboard adapter exists to replace it.
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
    public void phaseStarted(String phase) {
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
    public void tick(String phase, int current, int total) {
        log.info("{} {}/{}", phase, current, total);
    }

    /**
     * Logs the end of a phase.
     *
     * @param phase {@link String} the phase name
     */
    @Override
    public void phaseFinished(String phase) {
        log.info("{} done", phase);
    }
}
