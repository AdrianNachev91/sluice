package photos.sluice.adapter.secrets;

import org.jspecify.annotations.Nullable;

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
            return applyOwnerOnlyPosix(posix);
        }
        final var acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (acl != null) {
            return applyOwnerOnlyAcl(acl, processPrincipal(file));
        }
        return false;
    }

    /**
     * Restricts a file to its owner under the POSIX permission model, and reports whether the
     * change held.
     *
     * <p>Package-private rather than private because a mount that accepts the change and silently
     * drops it cannot be produced on demand, the same reason {@link #applyOwnerOnlyAcl} is
     * package-private.
     *
     * @param view {@link PosixFileAttributeView} the view over the file to restrict
     * @return boolean true when the file now carries exactly the owner-only permissions
     * @throws IOException when the view rejects the change
     */
    static boolean applyOwnerOnlyPosix(final PosixFileAttributeView view) throws IOException {
        view.setPermissions(OWNER_ONLY);
        // Reading the rule back is what makes the refusal true. A mount that accepts the change and
        // drops it would otherwise be reported as protected.
        return view.readAttributes().permissions().equals(OWNER_ONLY);
    }

    /**
     * Replaces a file's access rules with a single one naming one account.
     *
     * <p>Replacing the whole list rather than appending is the point. An entry granting one account
     * full control means nothing while an inherited entry still grants a wider group.
     *
     * <p>The file's own owner is used where it names an account. Where it names a group, the account
     * running this process is used instead. A Windows token can default new objects to
     * {@code BUILTIN\Administrators}, which is ordinary on an administrator account and on CI. A
     * rule granting that group would restrict the file to everyone in it. Refusing there instead
     * would leave such a machine unable to store a credential at all.
     *
     * <p>Package-private rather than private because neither of those owners can be produced on
     * demand. A filesystem hands over whichever one a real file has.
     *
     * @param acl {@link AclFileAttributeView} the view over the file to restrict
     * @param fallback {@link UserPrincipal} the account to name when the owner is a group, or null
     *         when this platform could not resolve one
     * @return boolean true when a single rule naming one account was applied
     * @throws IOException when the view rejects the change
     */
    static boolean applyOwnerOnlyAcl(final AclFileAttributeView acl,
            final @Nullable UserPrincipal fallback) throws IOException {
        final UserPrincipal owner = acl.getOwner();
        final UserPrincipal grantee = owner instanceof GroupPrincipal ? fallback : owner;
        if (grantee == null || grantee instanceof GroupPrincipal) {
            return false;
        }
        acl.setAcl(List.of(ownerFullControl(grantee)));
        // A volume can accept the change and drop it. Reporting a credential as protected when it
        // is not is the one outcome this refusal exists to prevent, so the rule is read back.
        final List<AclEntry> applied = acl.getAcl();
        return applied.size() == 1
                && applied.getFirst().type() == AclEntryType.ALLOW
                && applied.getFirst().principal().equals(grantee);
    }

    /**
     * Looks up the account this process runs as, for a file whose owner names a group.
     *
     * @param file {@link Path} the file whose filesystem resolves the name
     * @return {@link UserPrincipal} that account, or null when the platform cannot resolve it
     */
    private static @Nullable UserPrincipal processPrincipal(final Path file) {
        try {
            return file.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"));
        } catch (final IOException | RuntimeException unresolvable) {
            // A name the lookup service does not recognise leaves nothing to grant, and the caller
            // refuses rather than storing a credential it cannot protect.
            return null;
        }
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
