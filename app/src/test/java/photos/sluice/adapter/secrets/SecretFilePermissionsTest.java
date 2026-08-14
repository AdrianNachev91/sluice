package photos.sluice.adapter.secrets;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

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

        assertThat(SecretFilePermissions.applyOwnerOnlyAcl(view, null)).isTrue();

        assertThat(view.applied).singleElement().satisfies(entry -> {
            assertThat(entry.principal()).isEqualTo(owner);
            assertThat(entry.type()).isEqualTo(AclEntryType.ALLOW);
            assertThat(entry.permissions()).isEqualTo(EnumSet.allOf(AclEntryPermission.class));
        });
    }

    // A Windows token can name a group as the default owner of everything it creates. That is
    // ordinary on an administrator account, and it is what the CI runner does. Granting the group would
    // restrict the file to everyone in it, and refusing would leave that machine unable to store a
    // credential at all. The account running the process is the one that needs the access.
    @Test
    void namesTheProcessAccountWhenTheFileIsOwnedByAGroup() throws IOException {
        final var view = new RecordingAclView(new NamedGroup("BUILTIN\\Administrators"));
        final var process = new NamedPrincipal("SLUICE\\runner");

        assertThat(SecretFilePermissions.applyOwnerOnlyAcl(view, process)).isTrue();

        assertThat(view.applied).singleElement()
                .satisfies(entry -> assertThat(entry.principal()).isEqualTo(process));
    }

    // Nothing is left to grant, so storing a credential this cannot protect is the wrong answer.
    @Test
    void refusesAGroupOwnedFileWhenTheProcessAccountCannotBeResolved() throws IOException {
        final var view = new RecordingAclView(new NamedGroup("BUILTIN\\Administrators"));

        assertThat(SecretFilePermissions.applyOwnerOnlyAcl(view, null)).isFalse();

        assertThat(view.applied).isNull();
    }

    // A lookup service answering with a group rather than an account leaves the same problem one
    // step later, so it is refused at the same place.
    @Test
    void refusesWhenTheResolvedProcessAccountIsItselfAGroup() throws IOException {
        final var view = new RecordingAclView(new NamedGroup("BUILTIN\\Administrators"));

        assertThat(SecretFilePermissions.applyOwnerOnlyAcl(view, new NamedGroup("BUILTIN\\Users")))
                .isFalse();

        assertThat(view.applied).isNull();
    }

    // A volume can accept an access-rule change and drop it. Reporting the credential as protected
    // then tells the user something untrue about it, which is what the refusal exists to prevent.
    @Test
    void refusesWhenTheRuleDidNotSurviveBeingApplied() throws IOException {
        final var view = new DiscardingAclView(new NamedPrincipal("SLUICE\\owner"));

        assertThat(SecretFilePermissions.applyOwnerOnlyAcl(view, null)).isFalse();
    }

    // A rule granting a group full control would leave every member of it able to read the
    // credential, while the app reported the file as protected. The group is never the grantee, no
    // matter which side of the decision it arrives on.
    @Test
    void neverGrantsAGroupFullControl() throws IOException {
        final var ownedByGroup = new RecordingAclView(new NamedGroup("BUILTIN\\Administrators"));
        final var ownedByAccount = new RecordingAclView(new NamedPrincipal("SLUICE\\owner"));

        SecretFilePermissions.applyOwnerOnlyAcl(ownedByGroup, new NamedGroup("BUILTIN\\Users"));
        SecretFilePermissions.applyOwnerOnlyAcl(ownedByAccount, new NamedGroup("BUILTIN\\Users"));

        assertThat(ownedByGroup.applied).isNull();
        assertThat(ownedByAccount.applied).singleElement()
                .satisfies(entry -> assertThat(entry.principal()).isNotInstanceOf(GroupPrincipal.class));
    }

    @Test
    void appliesOwnerOnlyPermissionsAndConfirmsTheyHeld() throws IOException {
        final var view = new RecordingPosixView();

        assertThat(SecretFilePermissions.applyOwnerOnlyPosix(view)).isTrue();

        assertThat(view.applied).isEqualTo(
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }

    @Test
    void refusesWhenTheOwnerOnlyPermissionsDidNotSurviveBeingApplied() throws IOException {
        final var view = new DiscardingPosixView();

        assertThat(SecretFilePermissions.applyOwnerOnlyPosix(view)).isFalse();
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

    // Keeps whatever permissions were applied, so a refusal is distinguishable from an empty set.
    private static class RecordingPosixView implements PosixFileAttributeView {

        private Set<PosixFilePermission> applied = EnumSet.noneOf(PosixFilePermission.class);

        @Override
        public String name() {
            return "posix";
        }

        @Override
        public PosixFileAttributes readAttributes() {
            return new FakePosixAttributes(this.applied);
        }

        @Override
        public void setPermissions(final Set<PosixFilePermission> perms) {
            this.applied = Set.copyOf(perms);
        }

        @Override
        public void setGroup(final GroupPrincipal group) {
            throw new UnsupportedOperationException("nothing under test changes a file's group");
        }

        @Override
        public UserPrincipal getOwner() {
            throw new UnsupportedOperationException("nothing under test reads a file's owner");
        }

        @Override
        public void setOwner(final UserPrincipal owner) {
            throw new UnsupportedOperationException("nothing under test changes a file's owner");
        }

        @Override
        public void setTimes(final FileTime lastModifiedTime, final FileTime lastAccessTime,
                final FileTime createTime) {
            throw new UnsupportedOperationException(
                    "nothing under test changes a file's timestamps");
        }
    }

    // Accepts the change and reports the permissions unchanged afterwards, the way a mount that
    // accepts a permission change and drops it behaves.
    private static final class DiscardingPosixView extends RecordingPosixView {

        @Override
        public PosixFileAttributes readAttributes() {
            return new FakePosixAttributes(EnumSet.noneOf(PosixFilePermission.class));
        }
    }

    private record FakePosixAttributes(Set<PosixFilePermission> permissions)
            implements PosixFileAttributes {

        @Override
        public UserPrincipal owner() {
            throw new UnsupportedOperationException("nothing under test reads a file's owner");
        }

        @Override
        public GroupPrincipal group() {
            throw new UnsupportedOperationException("nothing under test reads a file's group");
        }

        @Override
        public FileTime lastModifiedTime() {
            throw new UnsupportedOperationException("nothing under test reads a file's timestamps");
        }

        @Override
        public FileTime lastAccessTime() {
            throw new UnsupportedOperationException("nothing under test reads a file's timestamps");
        }

        @Override
        public FileTime creationTime() {
            throw new UnsupportedOperationException("nothing under test reads a file's timestamps");
        }

        @Override
        public boolean isRegularFile() {
            throw new UnsupportedOperationException("nothing under test reads a file's kind");
        }

        @Override
        public boolean isDirectory() {
            throw new UnsupportedOperationException("nothing under test reads a file's kind");
        }

        @Override
        public boolean isSymbolicLink() {
            throw new UnsupportedOperationException("nothing under test reads a file's kind");
        }

        @Override
        public boolean isOther() {
            throw new UnsupportedOperationException("nothing under test reads a file's kind");
        }

        @Override
        public long size() {
            throw new UnsupportedOperationException("nothing under test reads a file's size");
        }

        @Override
        public @Nullable Object fileKey() {
            return null;
        }
    }
}
