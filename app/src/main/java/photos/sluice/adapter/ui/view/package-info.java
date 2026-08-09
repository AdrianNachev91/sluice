// The JavaFX classes themselves: windows, controls, and the bindings between them. Everything here
// sits outside the coverage gate, so everything here has to be thin enough to deserve that. A class
// in this package constructs controls, binds them to presenter state, and forwards events. It holds
// no branch on domain state and no formatting. Anything a test would want to assert about what the
// app is doing belongs on a presenter, one package up.
//
// Three things here are toolkit lifecycle rather than logic, and they are allowed for that reason
// alone. Choosing which scene to show, since there is nowhere earlier than the Application subclass
// to choose from. The order in which that subclass builds, hands over and tears down. A context has
// to be recorded before the call that can throw, or shutdown cannot close it. And guarding a
// reference that only exists once startup got that far. A class may also throw on a resource
// missing from its own build.
//
// Choosing the scene is driven end to end by a test, despite sitting outside the gate. The startup
// ordering and the two guards are not. That is the price of the exclusion rather than an oversight.
// Reaching them needs a Spring context that fails halfway, or a build missing its own resources.
// Anything that is not toolkit lifecycle moves to a presenter, where a test can hold it to account.
@NullMarked
package photos.sluice.adapter.ui.view;

import org.jspecify.annotations.NullMarked;
