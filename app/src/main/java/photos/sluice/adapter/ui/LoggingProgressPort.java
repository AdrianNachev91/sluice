package photos.sluice.adapter.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.ProgressPort;

// The only ProgressPort implementation until a desktop dashboard adapter exists to replace it.
// Every event is a single log line, so a job is still observable (console, log file) with nothing
// watching for UI events yet.
@Component
public class LoggingProgressPort implements ProgressPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingProgressPort.class);

    @Override
    public void phaseStarted(String phase) {
        log.info("{}", phase);
    }

    @Override
    public void tick(String phase, int current, int total) {
        log.info("{} {}/{}", phase, current, total);
    }

    @Override
    public void phaseFinished(String phase) {
        log.info("{} done", phase);
    }
}
