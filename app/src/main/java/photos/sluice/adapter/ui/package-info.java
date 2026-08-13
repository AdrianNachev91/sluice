// The desktop app's own driving side. The work it does at startup, the housekeeping it redoes when
// its working root moves, and the UI that starts jobs. Every bean here is profile-gated, so a
// process that is not the desktop leaves them unbuilt.
@NullMarked
package photos.sluice.adapter.ui;

import org.jspecify.annotations.NullMarked;
