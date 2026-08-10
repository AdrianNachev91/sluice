package photos.sluice.adapter.secrets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Narrows a file down to its owner, on whichever permission model the filesystem offers.
 *
 * <p>Two models rather than two platforms. A filesystem answers which attribute views it supports,
 * so the branch is on that answer instead of on the OS name. A network mount on Windows or a
 * container volume on Linux can offer either.
 *
 * <p>A filesystem that cannot narrow a file to one owner is reported rather than hidden. The caller
 * then refuses to store the credential at all. The alternative is telling a user their credential
 * sits in a protected file while it does not.
 */
final class SecretFilePermissions {

    private static final Set<PosixFilePermission> OWNER_ONLY =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    /**
     * Prevents instantiation of this static utility class.
     */
    private SecretFilePermissions() {
    }

    /**
     * Restricts the given file to its owner, and reports whether the filesystem allowed it.
     *
     * @param file {@link Path} the file to restrict
     * @return boolean true when the filesystem applied an owner-only rule
     * @throws IOException when the filesystem supports a model but rejects the change
     */
    static boolean restrictToOwner(final Path file) throws IOException {
        final var posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        // Which view a path answers with says which provider is in play, not what the volume
        // underneath it can enforce. A POSIX provider hands one back for a vfat stick or a network
        // mount just the same. The analysis reads this branch as the only one, so it is suppressed
        // rather than removed; on Windows it is the other branch that runs.
        //noinspection ConstantValue
        if (posix != null) {
            posix.setPermissions(OWNER_ONLY);
            // Reading the rule back is what makes the refusal true. A mount that accepts the change
            // and drops it would otherwise be reported as protected.
            return posix.readAttributes().permissions().equals(OWNER_ONLY);
        }
        final var acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (acl != null) {
            return applyOwnerOnlyAcl(acl);
        }
        return false;
    }

    /**
     * Replaces a file's access rules with a single one naming its owner.
     *
     * <p>Replacing the whole list rather than appending is the point. An entry granting the owner
     * full control means nothing while an inherited entry still grants a wider group.
     *
     * <p>Package-private rather than private because the principal it refuses cannot be produced on
     * demand. A filesystem hands over whichever owner a real file has.
     *
     * @param acl {@link AclFileAttributeView} the view over the file to restrict
     * @return boolean true when a single owner-only rule was applied
     * @throws IOException when the view rejects the change
     */
    static boolean applyOwnerOnlyAcl(final AclFileAttributeView acl) throws IOException {
        final UserPrincipal owner = acl.getOwner();
        // A Windows process whose token names a group as its default owner creates files owned by
        // that group. Granting a group full control restricts the file to everyone in it, so there
        // is no owner-only rule to write and this reports the refusal instead.
        if (owner instanceof GroupPrincipal) {
            return false;
        }
        acl.setAcl(List.of(ownerFullControl(owner)));
        // A volume can accept the change and drop it. Reporting a credential as protected when it
        // is not is the one outcome this refusal exists to prevent, so the rule is read back.
        final List<AclEntry> applied = acl.getAcl();
        return applied.size() == 1
                && applied.getFirst().type() == AclEntryType.ALLOW
                && applied.getFirst().principal().equals(owner);
    }

    /**
     * Builds the single access rule a restricted file carries: its owner, everything, nobody else.
     *
     * @param owner {@link UserPrincipal} the file's owner
     * @return {@link AclEntry} the single rule to apply
     */
    private static AclEntry ownerFullControl(final UserPrincipal owner) {
        return AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .build();
    }
}
