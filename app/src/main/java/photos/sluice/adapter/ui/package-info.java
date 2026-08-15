// The desktop app's own driving side. The work it does at startup, the housekeeping it redoes when
// its working root moves, and the UI that starts jobs. Every bean here is excluded from the cli
// profile, so a command line leaves them unbuilt. Any other process builds them.
@NullMarked
package photos.sluice.adapter.ui;

import org.jspecify.annotations.NullMarked;
