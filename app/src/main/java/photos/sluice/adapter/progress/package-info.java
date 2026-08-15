// Where a job's progress is reported. Its own package rather than a corner of adapter/ui, because
// a log line is not a desktop concern. Every driving adapter needs somewhere for progress to go,
// and adapter/ui's beans are all excluded from the cli profile.
@NullMarked
package photos.sluice.adapter.progress;

import org.jspecify.annotations.NullMarked;
