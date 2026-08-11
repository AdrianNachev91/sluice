package photos.sluice.adapter.secrets;

import photos.sluice.application.port.out.SecretStoreException;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;

/**
 * Talks to the Windows Credential Manager through {@code advapi32}, bound with the JDK's own
 * foreign-function API.
 *
 * <p>No third-party library sits in between. The two that exist are unmaintained, and a credential
 * store is the last component to hold a dependency that stops following the JDK. The functions bound
 * here are four, and their shapes have been stable for the whole life of the API.
 *
 * <p>Entries are stored as generic credentials, which is the kind Windows keeps for an application
 * rather than for a domain logon. They are scoped to the account that stored them and to this one
 * computer. A credential therefore survives a logoff, and never follows the account to another
 * machine.
 *
 * <p>The secret is stored as UTF-8 bytes. Windows treats the blob as opaque and both ends of it are
 * here, so the encoding is a choice rather than a constraint. UTF-8 keeps a byte count that matches
 * the value's own length in every other part of this app.
 *
 * <p>Every failure is read from {@code GetLastError} rather than guessed from a false return. The
 * code for a missing entry is the one this class treats as an answer instead of a fault.
 */
final class Advapi32CredentialManager implements WindowsCredentialManager {

    /**
     * The layout Windows documents for {@code CREDENTIALW}, which comes out at 80 bytes on a 64-bit
     * process.
     *
     * <p>The padding after the blob size is required rather than decorative. A struct layout inserts
     * none of its own, and refuses to build at all when a member would land at an offset its own
     * alignment forbids. So the pointer following a lone int has to be pushed into place by hand.
     */
    private static final MemoryLayout CREDENTIALW = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("Flags"),
            ValueLayout.JAVA_INT.withName("Type"),
            ValueLayout.ADDRESS.withName("TargetName"),
            ValueLayout.ADDRESS.withName("Comment"),
            ValueLayout.JAVA_LONG.withName("LastWritten"),
            ValueLayout.JAVA_INT.withName("CredentialBlobSize"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("CredentialBlob"),
            ValueLayout.JAVA_INT.withName("Persist"),
            ValueLayout.JAVA_INT.withName("AttributeCount"),
            ValueLayout.ADDRESS.withName("Attributes"),
            ValueLayout.ADDRESS.withName("TargetAlias"),
            ValueLayout.ADDRESS.withName("UserName"));

    private static final int CRED_TYPE_GENERIC = 1;
    private static final int CRED_PERSIST_LOCAL_MACHINE = 2;
    private static final int ERROR_NOT_FOUND = 1168;

    // Instance state rather than constants, because the captured call state is per platform and
    // only Windows records GetLastError in it. Resolving these while the class loads would make
    // merely mentioning it fatal elsewhere, as an Error no caller catches.
    private final MemoryLayout callState;
    private final VarHandle lastErrorHandle;
    private final MethodHandle credRead;
    private final MethodHandle credWrite;
    private final MethodHandle credDelete;
    private final MethodHandle credFree;

    /**
     * Binds the four functions, failing where any of them cannot be found.
     *
     * <p>Private because a caller has no way to handle a machine without {@code advapi32}, and
     * {@link #open()} is where that possibility is turned into an answer.
     *
     * @param advapi32 {@link SymbolLookup} the loaded library to resolve the functions in
     */
    private Advapi32CredentialManager(final SymbolLookup advapi32) {
        final Linker linker = Linker.nativeLinker();
        final Linker.Option captureLastError = Linker.Option.captureCallState("GetLastError");
        this.callState = Linker.Option.captureStateLayout();
        this.lastErrorHandle = this.callState.varHandle(groupElement("GetLastError"));
        this.credRead = linker.downcallHandle(symbol(advapi32, "CredReadW"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                captureLastError);
        this.credWrite = linker.downcallHandle(symbol(advapi32, "CredWriteW"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                captureLastError);
        this.credDelete = linker.downcallHandle(symbol(advapi32, "CredDeleteW"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT),
                captureLastError);
        this.credFree = linker.downcallHandle(symbol(advapi32, "CredFree"),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    }

    /**
     * Binds the Windows Credential Manager.
     *
     * <p>Refuses rather than answering with nothing, so what an absent library means is decided by
     * the caller instead of here. This class reports what the machine did; nothing in it rules on
     * whether that machine is a broken install or simply not a Windows one.
     *
     * <p>The library is loaded into the global scope, so it stays reachable for as long as the
     * handles built from it. Nothing here owns a moment at which unloading it would be correct.
     *
     * @return {@link WindowsCredentialManager} the bound credential store
     * @throws IllegalArgumentException when this machine has no {@code advapi32} to load, or it
     *         exports none of the functions named here
     * @throws UnsatisfiedLinkError when the library is present but cannot be loaded
     */
    static WindowsCredentialManager open() {
        return new Advapi32CredentialManager(
                SymbolLookup.libraryLookup("advapi32.dll", Arena.global()));
    }

    @Override
    public Optional<byte[]> read(final String target) {
        try (final Arena arena = Arena.ofConfined()) {
            final MemorySegment state = arena.allocate(this.callState);
            final MemorySegment found = arena.allocate(ValueLayout.ADDRESS);
            final int answered = this.callRead(state, wide(arena, target), found);
            if (answered == 0) {
                this.failUnlessEntryIsSimplyAbsent(state, "read", target);
                return Optional.empty();
            }
            // Windows allocated this itself, so it is freed through CredFree rather than with the
            // arena. Widening it to the struct is what makes its fields readable. The size of the
            // whole allocation stays unknown, since the strings and the blob sit past the struct.
            final MemorySegment credential =
                    found.get(ValueLayout.ADDRESS, 0).reinterpret(CREDENTIALW.byteSize());
            try {
                return Optional.of(blobOf(credential));
            } finally {
                this.free(credential);
            }
        }
    }

    @Override
    public void write(final String target, final String userName, final byte[] secret) {
        try (final Arena arena = Arena.ofConfined()) {
            final MemorySegment state = arena.allocate(this.callState);
            final MemorySegment credential = arena.allocate(CREDENTIALW);
            setInt(credential, "Type", CRED_TYPE_GENERIC);
            setPointer(credential, "TargetName", wide(arena, target));
            setInt(credential, "CredentialBlobSize", secret.length);
            setPointer(credential, "CredentialBlob", arena.allocateFrom(ValueLayout.JAVA_BYTE, secret));
            setInt(credential, "Persist", CRED_PERSIST_LOCAL_MACHINE);
            setPointer(credential, "UserName", wide(arena, userName));
            if (this.callWrite(state, credential) == 0) {
                throw failed("store", target, this.lastError(state));
            }
        }
    }

    @Override
    public void delete(final String target) {
        try (final Arena arena = Arena.ofConfined()) {
            final MemorySegment state = arena.allocate(this.callState);
            if (this.callDelete(state, wide(arena, target)) == 0) {
                this.failUnlessEntryIsSimplyAbsent(state, "remove", target);
            }
        }
    }

    /**
     * Resolves one function in the loaded library.
     *
     * <p>Refuses rather than answering with nothing, so a library that loads but is missing a
     * function leaves {@link #open()} the same way a missing library does. What either one means is
     * the caller's ruling, not this class's.
     *
     * @param advapi32 {@link SymbolLookup} the loaded library
     * @param name {@link String} the function's exported name
     * @return {@link MemorySegment} the function's address
     */
    private static MemorySegment symbol(final SymbolLookup advapi32, final String name) {
        return advapi32.find(name).orElseThrow(() -> new IllegalArgumentException(
                "advapi32 on this machine exports no " + name));
    }

    /**
     * Reads whichever error the last call recorded.
     *
     * @param state {@link MemorySegment} the captured call state
     * @return int the Windows error code
     */
    private int lastError(final MemorySegment state) {
        return (int) this.lastErrorHandle.get(state, 0L);
    }

    /**
     * Copies a credential's stored bytes out of the memory Windows handed over, before that memory
     * is released.
     *
     * @param credential {@link MemorySegment} the credential Windows filled in
     * @return byte array the stored bytes
     */
    private static byte[] blobOf(final MemorySegment credential) {
        final int size = credential.get(ValueLayout.JAVA_INT,
                CREDENTIALW.byteOffset(groupElement("CredentialBlobSize")));
        return credential.get(ValueLayout.ADDRESS,
                        CREDENTIALW.byteOffset(groupElement("CredentialBlob")))
                .reinterpret(size)
                .toArray(ValueLayout.JAVA_BYTE);
    }

    /**
     * Builds the failure reported when the credential store refuses an operation.
     *
     * <p>The Windows error code is carried in the message. It is the only thing that distinguishes
     * one refusal from another, and a user reporting a problem can quote it.
     *
     * @param operation {@link String} what was being attempted, in the words of the caller
     * @param target {@link String} the entry the operation named
     * @param error int the code Windows recorded
     * @return {@link SecretStoreException} the failure to report
     */
    private static SecretStoreException failed(final String operation, final String target,
            final int error) {
        return new SecretStoreException("The Windows Credential Manager refused to " + operation
                + " the entry '" + target + "', with error code " + error);
    }

    /**
     * Encodes a string the way the {@code W} suffix on these functions requires: little-endian
     * UTF-16 with a terminating null.
     *
     * <p>The little-endian charset is named rather than the plain UTF-16 one, and the difference is
     * not cosmetic. Plain UTF-16 is big-endian, so every character would arrive with its two bytes
     * the wrong way round. It also leads with a byte order mark, which would become the first
     * characters of the entry name.
     *
     * @param arena {@link Arena} the allocation scope for the encoded string
     * @param text {@link String} the string to encode
     * @return {@link MemorySegment} the encoded, null-terminated string
     */
    private static MemorySegment wide(final Arena arena, final String text) {
        final byte[] encoded = text.getBytes(StandardCharsets.UTF_16LE);
        final MemorySegment segment = arena.allocate(encoded.length + 2L);
        MemorySegment.copy(encoded, 0, segment, ValueLayout.JAVA_BYTE, 0, encoded.length);
        segment.set(ValueLayout.JAVA_BYTE, encoded.length, (byte) 0);
        segment.set(ValueLayout.JAVA_BYTE, encoded.length + 1L, (byte) 0);
        return segment;
    }

    /**
     * Writes an int into one of the credential structure's fields.
     *
     * @param credential {@link MemorySegment} the structure to write into
     * @param field {@link String} the field's documented name
     * @param value int the value to write
     */
    private static void setInt(final MemorySegment credential, final String field, final int value) {
        credential.set(ValueLayout.JAVA_INT,
                CREDENTIALW.byteOffset(groupElement(field)), value);
    }

    /**
     * Writes a pointer into one of the credential structure's fields.
     *
     * @param credential {@link MemorySegment} the structure to write into
     * @param field {@link String} the field's documented name
     * @param value {@link MemorySegment} the memory to point at
     */
    private static void setPointer(final MemorySegment credential, final String field,
            final MemorySegment value) {
        credential.set(ValueLayout.ADDRESS,
                CREDENTIALW.byteOffset(groupElement(field)), value);
    }

    /**
     * Reports a refused call as a failure, unless the refusal only says the entry is not there.
     *
     * <p>Returns quietly in that one case. Both callers already treat an absent entry as their
     * ordinary outcome, each in its own way. A read answers with nothing, and a removal has nothing
     * left to do.
     *
     * @param state {@link MemorySegment} the captured call state
     * @param operation {@link String} what was being attempted, in the words of the caller
     * @param target {@link String} the entry the operation named
     * @throws SecretStoreException when the refusal was anything but a missing entry
     */
    private void failUnlessEntryIsSimplyAbsent(final MemorySegment state,
            final String operation, final String target) {
        final int error = this.lastError(state);
        if (error != ERROR_NOT_FOUND) {
            throw failed(operation, target, error);
        }
    }

    /**
     * Invokes {@code CredReadW}, turning the checked throwable the invocation declares into the
     * failure this class reports.
     *
     * @param state {@link MemorySegment} where the call records its error code
     * @param target {@link MemorySegment} the encoded entry name
     * @param found {@link MemorySegment} where the call writes the credential it allocated
     * @return int zero when the call was refused
     */
    private int callRead(final MemorySegment state, final MemorySegment target,
            final MemorySegment found) {
        try {
            return (int) this.credRead.invokeExact(state, target, CRED_TYPE_GENERIC, 0, found);
        } catch (final Throwable unreachable) {
            throw binding(unreachable);
        }
    }

    /**
     * Invokes {@code CredWriteW}.
     *
     * @param state {@link MemorySegment} where the call records its error code
     * @param credential {@link MemorySegment} the filled-in credential structure
     * @return int zero when the call was refused
     */
    private int callWrite(final MemorySegment state, final MemorySegment credential) {
        try {
            return (int) this.credWrite.invokeExact(state, credential, 0);
        } catch (final Throwable unreachable) {
            throw binding(unreachable);
        }
    }

    /**
     * Invokes {@code CredDeleteW}.
     *
     * @param state {@link MemorySegment} where the call records its error code
     * @param target {@link MemorySegment} the encoded entry name
     * @return int zero when the call was refused
     */
    private int callDelete(final MemorySegment state, final MemorySegment target) {
        try {
            return (int) this.credDelete.invokeExact(state, target, CRED_TYPE_GENERIC, 0);
        } catch (final Throwable unreachable) {
            throw binding(unreachable);
        }
    }

    /**
     * Releases memory the credential store allocated.
     *
     * @param credential {@link MemorySegment} the credential to release
     */
    private void free(final MemorySegment credential) {
        try {
            this.credFree.invokeExact(credential);
        } catch (final Throwable unreachable) {
            throw binding(unreachable);
        }
    }

    /**
     * Wraps whatever a native invocation threw.
     *
     * <p>A downcall handle declares {@code Throwable} because a native function may raise anything.
     * These four raise nothing. Their whole failure vocabulary is a false return plus an error code.
     * So reaching here means this binding is wrong, rather than a credential that could not be
     * stored. The message says the same thing, since a user who sees it is looking at a defect and
     * not at something their machine did.
     *
     * @param cause {@link Throwable} what the invocation threw
     * @return {@link SecretStoreException} the failure to report
     */
    private static SecretStoreException binding(final Throwable cause) {
        return new SecretStoreException(
                "Sluice's binding to the Windows Credential Manager is built wrong and could not be "
                        + "called", cause);
    }
}
