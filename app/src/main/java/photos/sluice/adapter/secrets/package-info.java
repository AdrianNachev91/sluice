// Credential storage across the tiers a machine offers, behind the SecretStore port.
// TieredSecretStore orders the tiers and decides which of them each operation reaches; a read
// tries all, a save reaches one, a remove reaches every writable one. One class per tier,
// deliberately: a tier that only runs on its own platform is then its own unit. That keeps the
// code choosing between tiers separable from the code talking to any one of them.
//
// A platform's credential store splits further, into the tier and the native binding under it.
// PlatformKeyring picks the tier, the tier decides what an answer means, and the binding only
// carries bytes to and from the operating system. The split is what makes the deciding half
// testable on every runner. A binding's own operations only run on the platform whose library it
// binds, so no single run can cover one.
@NullMarked
package photos.sluice.adapter.secrets;

import org.jspecify.annotations.NullMarked;
