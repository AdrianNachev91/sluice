// Credential storage across the tiers a machine offers, behind the SecretStore port.
// TieredSecretStore orders the tiers and decides which of them each operation reaches; a read
// tries all, a save reaches one, a remove reaches every writable one. One class per tier,
// deliberately: a tier that only runs on its own platform is then its own unit. That keeps the
// code choosing between tiers separable from the code talking to any one of them.
@NullMarked
package photos.sluice.adapter.secrets;

import org.jspecify.annotations.NullMarked;
