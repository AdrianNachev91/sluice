// Where a job's progress goes when no surface has claimed it: one log line per event, readable in
// a console or a log file. Its own package rather than a corner of adapter/ui, because a log line
// is not a desktop concern.
@NullMarked
package photos.sluice.adapter.progress;

import org.jspecify.annotations.NullMarked;
