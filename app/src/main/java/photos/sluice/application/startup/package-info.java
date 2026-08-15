// What a failed start looks like once something has read the framework's own exception and decided
// what kind of failure it was. Every surface renders these types its own way, and none of them
// re-inspects a framework exception to do it.
@NullMarked
package photos.sluice.application.startup;

import org.jspecify.annotations.NullMarked;
