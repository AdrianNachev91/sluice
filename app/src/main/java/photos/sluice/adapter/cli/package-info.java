// The command line: one process, one command, then exit. The desktop's sibling driving adapter over
// the same facade, and every bean here is gated to the cli profile so a desktop run builds none of
// them.
//
// The two surfaces share nothing but the ports beneath them. A sibling adapter's classes are out of
// reach by rule, so where the same idea appears on both sides it is written twice on purpose.
@NullMarked
package photos.sluice.adapter.cli;

import org.jspecify.annotations.NullMarked;
