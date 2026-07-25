package photos.sluice.application.port.out;

// Where a running job reports what it's doing, so a driving adapter (the desktop dashboard, a
// console logger) can render it without polling. Only one job is active at a time, so no event
// carries a job id - every event describes the current job's own progress. phaseStarted/
// phaseFinished always bracket a phase, even one whose total is zero and that never ticks, so a
// listener can tell "not started yet" from "done with nothing to do". phase is a short
// human-readable label the caller controls directly ("Sorting...", "Building montages...",
// "Applying decisions...") rather than a code a listener has to translate.
public interface ProgressPort {

    void phaseStarted(String phase);

    // One already-sized unit of the named phase finished, e.g. phase="Sorting...", current=850,
    // total=1204.
    void tick(String phase, int current, int total);

    void phaseFinished(String phase);
}
