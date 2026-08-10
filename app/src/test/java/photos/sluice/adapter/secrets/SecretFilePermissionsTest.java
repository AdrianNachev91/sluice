package photos.sluice.adapter.secrets;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// The access-rule half is exercised through a view of its own rather than through a real file. Only
// one of the two permission models exists on any one machine, and the owner a real file gets is
// whatever the runner's account happens to be. Both are decided elsewhere, so neither can be asked
// for here. The real filesystem is covered end to end by FileSecretTierTest instead.
class SecretFilePermissionsTest {

    @Test
    void replacesEveryAccessRuleWithOneGrantingTheOwnerFullControl() throws IOException {
        final var owner = new NamedPrincipal("SLUICE\\owner");
        final var view = new RecordingAclView(owner);

        assertThat(SecretFilePermissions.applyOwnerOnlyAcl(view)).isTrue();

        assertThat(view.applied).singleElement().satisfies(entry -> {
            assertThat(entry.principal()).isEqualTo(owner);
            assertThat(entry.type()).isEqualTo(AclEntryType.ALLOW);
            assertThat(entry.permissions()).isEqualTo(EnumSet.allOf(AclEntryPermission.class));
        });
    }

    // A volume can accept an access-rule change and drop it. Reporting the credential as protected
    // then tells the user something untrue about it, which is what the refusal exists to prevent.
    @Test
    void refusesWhenTheRuleDidNotSurviveBeingApplied() throws IOException {
        final var view = new DiscardingAclView(new NamedPrincipal("SLUICE\\owner"));

        assertThat(SecretFilePermissions.applyOwnerOnlyAcl(view)).isFalse();
    }

    // A Windows process whose token names a group as its default owner creates files owned by that
    // group. A rule granting it full control would leave every member of the group able to read the
    // credential, while the app reports the file as protected.
    @Test
    void refusesAFileOwnedByAGroupRatherThanGrantingTheGroupFullControl() throws IOException {
        final var view = new RecordingAclView(new NamedGroup("BUILTIN\\Administrators"));

        assertThat(SecretFilePermissions.applyOwnerOnlyAcl(view)).isFalse();

        assertThat(view.applied).isNull();
    }

    private record NamedPrincipal(String name) implements UserPrincipal {

        @Override
        public String getName() {
            return this.name;
        }
    }

    private record NamedGroup(String name) implements GroupPrincipal {

        @Override
        public String getName() {
            return this.name;
        }
    }

    // Takes the rules and reports none afterwards, the way a volume that accepts an access-rule
    // change and drops it behaves.
    private static final class DiscardingAclView extends RecordingAclView {

        DiscardingAclView(final UserPrincipal owner) {
            super(owner);
        }

        @Override
        public List<AclEntry> getAcl() {
            return List.of();
        }
    }

    // Keeps whatever rules were applied, and null until any were, so a refusal is distinguishable
    // from an empty rule list.
    private static class RecordingAclView implements AclFileAttributeView {

        private final UserPrincipal owner;
        private @Nullable List<AclEntry> applied;

        RecordingAclView(final UserPrincipal owner) {
            this.owner = owner;
        }

        @Override
        public String name() {
            return "acl";
        }

        @Override
        public List<AclEntry> getAcl() {
            return this.applied == null ? List.of() : this.applied;
        }

        @Override
        public void setAcl(final List<AclEntry> acl) {
            this.applied = List.copyOf(acl);
        }

        @Override
        public UserPrincipal getOwner() {
            return this.owner;
        }

        @Override
        public void setOwner(final UserPrincipal owner) {
            throw new UnsupportedOperationException("nothing under test changes a file's owner");
        }
    }
}
