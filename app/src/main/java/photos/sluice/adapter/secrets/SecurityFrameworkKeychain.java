package photos.sluice.adapter.secrets;

import org.jspecify.annotations.Nullable;
import photos.sluice.application.port.out.SecretStoreException;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Talks to the macOS keychain through Security.framework, bound with the JDK's own foreign-function
 * API.
 *
 * <p>No third-party library sits in between, for the same reason the app's other credential bindings
 * take none. A credential store is the last component to hold a dependency that stops following the
 * JDK.
 *
 * <p>macOS offers two doors onto the same store and this binds the modern one. Apple's technote
 * TN3137 says to always use {@code SecItem}, and the {@code SecKeychain} family it replaced has been
 * deprecated since macOS 10.10. The cost is CoreFoundation: every argument travels inside a
 * {@code CFDictionary}, so the dictionary machinery has to be bound alongside the four keychain
 * calls that use it.
 *
 * <p>{@code kSecUseDataProtectionKeychain} is deliberately left unset, so every call here targets the
 * file-based login keychain. That is the store the user's own Keychain Access window shows, and it
 * needs no entitlement. The data-protection keychain is out of reach until the app is signed. It is
 * not wanted even then, because a desktop tool has no access group to share.
 *
 * <p>Entries are found by a service attribute plus an account. The service names the app and the
 * account names the entry, which is what groups this app's credentials together in Keychain Access.
 * That grouping is a naming convention rather than a boundary. Nothing stops another application
 * naming the same service, exactly as nothing stops one on Windows choosing the same target prefix.
 * What macOS adds on top is a per-item access control list. That is real enforcement, anchored to
 * the calling code's identity rather than to the name.
 *
 * <p>Two kinds of global are read here, and they are not read the same way. That is the one thing in
 * this class a signature cannot tell you. The {@code kSec*} keys are declared
 * {@code extern const CFStringRef}, so each is a pointer variable. The symbol lookup answers with
 * the address that pointer sits at, and the value wanted is one dereference on. The two dictionary
 * callback tables are declared as {@code const} structs instead. The symbol's own address is already
 * the struct, so dereferencing one would read the first eight bytes of a callback table as an
 * address. Both mistakes compile and link.
 *
 * <p>A failure arrives as an {@code OSStatus}, an integer the caller reads and this class turns into
 * a refusal.
 */
final class SecurityFrameworkKeychain implements MacKeychain {

    /**
     * The service attribute every entry carries, and every lookup matches on. It groups this app's
     * credentials under one heading in Keychain Access, so the account only has to be unique within
     * them.
     */
    private static final String SERVICE = "Sluice";

    /**
     * {@code kCFStringEncodingUTF8}, from CoreFoundation's own built-in encodings.
     */
    private static final int UTF8 = 0x08000100;

    private static final int SUCCESS = 0;
    private static final int ITEM_NOT_FOUND = -25300;
    private static final int DUPLICATE_ITEM = -25299;
    private static final int USER_CANCELED = -128;
    private static final int AUTH_FAILED = -25293;
    private static final int NO_DEFAULT_KEYCHAIN = -25307;
    private static final int INTERACTION_NOT_ALLOWED = -25308;

    private static final String SECURITY_FRAMEWORK =
            "/System/Library/Frameworks/Security.framework/Security";

    private static final String CORE_FOUNDATION =
            "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation";

    private final MethodHandle itemCopyMatching;
    private final MethodHandle itemAdd;
    private final MethodHandle itemUpdate;
    private final MethodHandle itemDelete;
    private final MethodHandle stringCreate;
    private final MethodHandle dataCreate;
    private final MethodHandle dictionaryCreate;
    private final MethodHandle dataBytes;
    private final MethodHandle dataLength;
    private final MethodHandle release;

    private final MemorySegment attributeClass;
    private final MemorySegment classGenericPassword;
    private final MemorySegment attributeService;
    private final MemorySegment attributeAccount;
    private final MemorySegment attributeLabel;
    private final MemorySegment valueData;
    private final MemorySegment returnData;
    private final MemorySegment returnAttributes;
    private final MemorySegment matchLimit;
    private final MemorySegment matchLimitOne;
    private final MemorySegment booleanTrue;
    private final MemorySegment keyCallBacks;
    private final MemorySegment valueCallBacks;

    private final @Nullable String itemClassOverride;

    /**
     * Binds the functions and globals this class reads, failing where any of them cannot be found.
     *
     * <p>Private because a caller has no way to handle a machine whose Security.framework will not
     * load. {@link #open()} is where that possibility is turned into an answer.
     *
     * @param itemClassOverride {@link String} an item class to send in place of the generic-password
     *         one, or null to send that one. Only a test supplies it.
     * @param security {@link Framework} the loaded Security.framework
     * @param coreFoundation {@link Framework} the loaded CoreFoundation framework
     */
    private SecurityFrameworkKeychain(final @Nullable String itemClassOverride,
            final Framework security, final Framework coreFoundation) {
        this.itemClassOverride = itemClassOverride;
        final Linker linker = Linker.nativeLinker();
        this.itemCopyMatching = linker.downcallHandle(security.symbol("SecItemCopyMatching"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS));
        this.itemAdd = linker.downcallHandle(security.symbol("SecItemAdd"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS));
        this.itemUpdate = linker.downcallHandle(security.symbol("SecItemUpdate"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS));
        this.itemDelete = linker.downcallHandle(security.symbol("SecItemDelete"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        this.stringCreate = linker.downcallHandle(
                coreFoundation.symbol("CFStringCreateWithCString"),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT));
        // CFIndex is a signed long, which is eight bytes on every machine this ships to. Declaring
        // it as an int would truncate the length of anything large and read the wrong half of it.
        this.dataCreate = linker.downcallHandle(coreFoundation.symbol("CFDataCreate"),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_LONG));
        this.dictionaryCreate = linker.downcallHandle(coreFoundation.symbol("CFDictionaryCreate"),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS));
        this.dataBytes = linker.downcallHandle(coreFoundation.symbol("CFDataGetBytePtr"),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.dataLength = linker.downcallHandle(coreFoundation.symbol("CFDataGetLength"),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        this.release = linker.downcallHandle(coreFoundation.symbol("CFRelease"),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        this.attributeClass = security.pointerAt("kSecClass");
        this.classGenericPassword = security.pointerAt("kSecClassGenericPassword");
        this.attributeService = security.pointerAt("kSecAttrService");
        this.attributeAccount = security.pointerAt("kSecAttrAccount");
        this.attributeLabel = security.pointerAt("kSecAttrLabel");
        this.valueData = security.pointerAt("kSecValueData");
        this.returnData = security.pointerAt("kSecReturnData");
        this.returnAttributes = security.pointerAt("kSecReturnAttributes");
        this.matchLimit = security.pointerAt("kSecMatchLimit");
        this.matchLimitOne = security.pointerAt("kSecMatchLimitOne");
        this.booleanTrue = coreFoundation.pointerAt("kCFBooleanTrue");
        this.keyCallBacks = coreFoundation.symbol("kCFTypeDictionaryKeyCallBacks");
        this.valueCallBacks = coreFoundation.symbol("kCFTypeDictionaryValueCallBacks");
    }

    /**
     * Binds the keychain through this machine's Security.framework.
     *
     * <p>Refuses rather than answering with nothing, so what an absent framework means is decided by
     * the caller instead of here. This class reports what the machine did; nothing in it rules on
     * whether that machine is a broken install or simply not a Mac.
     *
     * <p>The frameworks are loaded into the global scope, so they stay reachable for as long as the
     * handles built from them. Nothing here owns a moment at which unloading them would be correct.
     *
     * @return {@link MacKeychain} the bound keychain
     * @throws IllegalArgumentException when this machine is missing either framework, or one of them
     *         exports none of the names read here
     * @throws UnsatisfiedLinkError when a framework is present but cannot be loaded
     */
    static MacKeychain open() {
        return open(null);
    }

    /**
     * Binds the keychain, sending the given item class in place of the generic-password one.
     *
     * <p>Package-private so a test can provoke a refusal on demand, which no other operation here
     * offers. A read and a removal answer "no such item" rather than failing. A store of a fresh
     * value succeeds, and a store over an existing one updates it. So a working keychain refuses
     * nothing this class otherwise asks of it, and the branch reporting a refusal would go unrun.
     *
     * <p>An item class the keychain does not define is refused rather than merely matching nothing.
     * The class is what tells it which kind of item is being described.
     *
     * @param itemClass {@link String} the item class to send, or null to send the generic-password
     *         one
     * @return {@link MacKeychain} the bound keychain
     * @throws IllegalArgumentException when this machine is missing either framework, or one of them
     *         exports none of the names read here
     * @throws UnsatisfiedLinkError when a framework is present but cannot be loaded
     */
    static MacKeychain open(final @Nullable String itemClass) {
        return new SecurityFrameworkKeychain(itemClass,
                new Framework("Security.framework",
                        SymbolLookup.libraryLookup(SECURITY_FRAMEWORK, Arena.global())),
                new Framework("CoreFoundation.framework",
                        SymbolLookup.libraryLookup(CORE_FOUNDATION, Arena.global())));
    }

    @Override
    public Optional<byte[]> read(final String name) {
        try (final Arena arena = Arena.ofConfined(); final CfScope scope = new CfScope()) {
            final MemorySegment query = this.dictionary(arena, scope,
                    new MemorySegment[] {this.attributeClass, this.attributeService,
                            this.attributeAccount, this.returnData, this.matchLimit},
                    new MemorySegment[] {this.itemClass(arena, scope),
                            this.string(arena, scope, SERVICE), this.string(arena, scope, name),
                            this.booleanTrue, this.matchLimitOne});
            final MemorySegment slot = arena.allocate(ValueLayout.ADDRESS);
            final int status = this.callCopyMatching(query, slot);
            if (status == ITEM_NOT_FOUND) {
                return Optional.empty();
            }
            this.throwIfRefused(status, "read", name);
            final MemorySegment data = slot.get(ValueLayout.ADDRESS, 0);
            // A match that produced no bytes is a credential this machine holds and would not hand
            // over. Reported as absence it would send the store to the file tier, leaving a machine
            // able to store a credential it can never read back.
            if (data.equals(MemorySegment.NULL)) {
                throw new SecretStoreException(SecretStoreException.Tier.KEYRING,
                        "The keychain matched the entry '" + name
                                + "' and then handed over no value for it");
            }
            // The keychain allocated this and handed ownership over with it, so it is released here
            // rather than left to the framework.
            scope.own(data);
            return Optional.of(this.bytesOf(data));
        }
    }

    @Override
    public boolean holds(final String name) {
        try (final Arena arena = Arena.ofConfined(); final CfScope scope = new CfScope()) {
            // Attributes are asked for and the value is not, which is what buys the no-prompt
            // guarantee. Apple documents attributes as unencrypted; that the data is the part
            // needing a password is what follows from it. So this call can be made while a settings
            // label is drawn, without putting a keychain dialog on screen.
            final MemorySegment query = this.dictionary(arena, scope,
                    new MemorySegment[] {this.attributeClass, this.attributeService,
                            this.attributeAccount, this.returnAttributes, this.matchLimit},
                    new MemorySegment[] {this.itemClass(arena, scope),
                            this.string(arena, scope, SERVICE), this.string(arena, scope, name),
                            this.booleanTrue, this.matchLimitOne});
            final MemorySegment slot = arena.allocate(ValueLayout.ADDRESS);
            final int status = this.callCopyMatching(query, slot);
            if (status == ITEM_NOT_FOUND) {
                return false;
            }
            this.throwIfRefused(status, "look for", name);
            final MemorySegment attributes = slot.get(ValueLayout.ADDRESS, 0);
            if (!attributes.equals(MemorySegment.NULL)) {
                scope.own(attributes);
            }
            return true;
        }
    }

    @Override
    public void write(final String name, final String label, final byte[] secret) {
        try (final Arena arena = Arena.ofConfined(); final CfScope scope = new CfScope()) {
            final MemorySegment attributes = this.dictionary(arena, scope,
                    new MemorySegment[] {this.attributeClass, this.attributeService,
                            this.attributeAccount, this.attributeLabel, this.valueData},
                    new MemorySegment[] {this.itemClass(arena, scope),
                            this.string(arena, scope, SERVICE), this.string(arena, scope, name),
                            this.string(arena, scope, label), this.data(arena, scope, secret)});
            final int added = this.callAdd(attributes);
            // There is no upsert here. An add over an entry the service and account already name is
            // refused, and updating it is a second call with its own dictionary.
            if (added == DUPLICATE_ITEM) {
                this.update(arena, scope, name, label, secret);
                return;
            }
            this.throwIfRefused(added, "store", name);
        }
    }

    @Override
    public void delete(final String name) {
        try (final Arena arena = Arena.ofConfined(); final CfScope scope = new CfScope()) {
            final MemorySegment query = this.dictionary(arena, scope,
                    new MemorySegment[] {this.attributeClass, this.attributeService,
                            this.attributeAccount},
                    new MemorySegment[] {this.itemClass(arena, scope),
                            this.string(arena, scope, SERVICE), this.string(arena, scope, name)});
            final int status = this.callDelete(query);
            if (status == ITEM_NOT_FOUND) {
                return;
            }
            this.throwIfRefused(status, "remove", name);
        }
    }

    /**
     * Says what a status code means, for the codes worth wording.
     *
     * <p>Each of these names what the code covers rather than the likeliest way to reach it. A code
     * standing for a family gets worded as the family. A message naming one cause sends everyone
     * who arrived by another to a repair that changes nothing for them.
     *
     * <p>Package-private so the wording and the four integers can be tested. Those integers are
     * transcribed by hand from Apple's headers, and this class sits outside the coverage gate. A
     * transposed digit would otherwise reach a user as a bare number where a sentence belonged.
     *
     * @param status int the status the keychain answered with
     * @return {@link String} what it means, or the code itself where that is all there is to say
     */
    static String explain(final int status) {
        return switch (status) {
            // Not worded as "locked", though that is the commonest cause. The same code covers an
            // item whose access control asks the user to confirm, which is what an unsigned build
            // can raise after an update. Telling that user their keychain is locked would send them
            // to unlock something that is already open.
            case INTERACTION_NOT_ALLOWED -> "the keychain needs to ask you something and nothing"
                    + " here can put that request on screen (error " + status + ")";
            case USER_CANCELED -> "the keychain request was dismissed (error " + status + ")";
            // The workaround is offered rather than asserted. It comes from Apple's own engineer
            // for one macOS 26 regression, and this code also covers an ordinary wrong password.
            case AUTH_FAILED -> "macOS would not authenticate against your keychain. If it keeps"
                    + " happening, locking and unlocking that keychain in Keychain Access has been"
                    + " reported to clear it (error " + status + ")";
            case NO_DEFAULT_KEYCHAIN -> "this machine has no default keychain (error " + status
                    + ")";
            default -> "error " + status;
        };
    }

    /**
     * The item class every call sends.
     *
     * <p>Production reads the framework's own generic-password constant. A test may replace it with
     * a class the keychain does not define, which is the only way to make a working keychain refuse
     * something. Nothing but that seam reaches the second branch.
     *
     * @param arena {@link Arena} the allocation scope of the call being made
     * @param scope {@link CfScope} what releases anything created here
     * @return {@link MemorySegment} the item class to send
     */
    private MemorySegment itemClass(final Arena arena, final CfScope scope) {
        if (this.itemClassOverride == null) {
            return this.classGenericPassword;
        }
        return this.string(arena, scope, this.itemClassOverride);
    }

    /**
     * Copies the bytes out of a keychain-allocated data object.
     *
     * @param data {@link MemorySegment} the data object to read
     * @return byte array the bytes it holds
     */
    private byte[] bytesOf(final MemorySegment data) {
        final long length = this.callDataLength(data);
        if (length == 0) {
            return new byte[0];
        }
        return this.callDataBytes(data).reinterpret(length).toArray(ValueLayout.JAVA_BYTE);
    }

    /**
     * Overwrites the value and the label of an entry the keychain already held.
     *
     * @param arena {@link Arena} the allocation scope of the call being made
     * @param scope {@link CfScope} what releases anything created here
     * @param name {@link String} the entry to overwrite
     * @param label {@link String} what a user browsing their own keychain sees the entry called
     * @param secret byte array the bytes to store
     */
    private void update(final Arena arena, final CfScope scope, final String name,
            final String label, final byte[] secret) {
        final MemorySegment query = this.dictionary(arena, scope,
                new MemorySegment[] {this.attributeClass, this.attributeService,
                        this.attributeAccount},
                new MemorySegment[] {this.itemClass(arena, scope),
                        this.string(arena, scope, SERVICE), this.string(arena, scope, name)});
        // The class is deliberately absent from what gets changed. It names which kind of item the
        // query described, and is not itself an attribute of one.
        final MemorySegment changes = this.dictionary(arena, scope,
                new MemorySegment[] {this.attributeLabel, this.valueData},
                new MemorySegment[] {this.string(arena, scope, label),
                        this.data(arena, scope, secret)});
        this.throwIfRefused(this.callUpdate(query, changes), "store", name);
    }

    /**
     * Reports a call the keychain refused, naming what the status code means where it is worth
     * saying.
     *
     * <p>Returns quietly on success. Each caller separately decides what the codes meaning absence
     * or a clash mean for its own operation, before reaching here.
     *
     * <p>The four codes worded here are the ones a user can act on, and each of them is reachable on
     * an ordinary machine. Everything else arrives as its number, which is enough to look up and
     * more honest than a guess at what it meant.
     *
     * @param status int the status the keychain answered with
     * @param operation {@link String} what was being attempted, in the words of the caller
     * @param name {@link String} the entry the operation named
     * @throws SecretStoreException when the status is anything but success
     */
    private void throwIfRefused(final int status, final String operation, final String name) {
        if (status == SUCCESS) {
            return;
        }
        throw new SecretStoreException(SecretStoreException.Tier.KEYRING,
                "The keychain refused to " + operation + " the entry '" + name + "': "
                        + explain(status));
    }

    /**
     * Wraps whatever a native invocation threw.
     *
     * <p>A downcall handle declares {@code Throwable} because a native function may raise anything.
     * These raise nothing. Their whole failure vocabulary is a status code. So reaching here means
     * this binding is wrong, rather than a credential that could not be stored. The message says the
     * same thing, since a user who sees it is looking at a defect and not at something their machine
     * did.
     *
     * @param cause {@link Throwable} what the invocation threw
     * @return {@link SecretStoreException} the failure to report
     */
    private static SecretStoreException binding(final Throwable cause) {
        return new SecretStoreException(SecretStoreException.Tier.KEYRING,
                "Sluice's binding to the macOS keychain is built wrong and could not be called",
                cause);
    }

    /**
     * Builds the dictionary one call describes itself with.
     *
     * <p>The two arrays are read in step, so a key at one position is paired with the value at the
     * same one. Mismatched lengths are refused here rather than passed on, and they go wrong in two
     * different ways. Fewer values than keys sends a count past the end of the value array, and
     * CoreFoundation reads whatever follows in memory as a value without checking. Fewer keys than
     * values sends a count that fits, and silently drops the values nobody named a key for.
     *
     * @param arena {@link Arena} the allocation scope of the call being made
     * @param scope {@link CfScope} what releases the dictionary afterwards
     * @param keys {@link MemorySegment} array the keys, in order
     * @param values {@link MemorySegment} array the values, in the same order
     * @return {@link MemorySegment} the built dictionary
     */
    private MemorySegment dictionary(final Arena arena, final CfScope scope,
            final MemorySegment[] keys, final MemorySegment[] values) {
        if (keys.length != values.length) {
            throw new IllegalArgumentException("a keychain query needs one value per key, given "
                    + keys.length + " keys and " + values.length + " values");
        }
        return scope.own(created(this.callDictionaryCreate(addressArray(arena, keys),
                addressArray(arena, values), keys.length), "a dictionary"));
    }

    /**
     * Lays a run of pointers out in memory for a native call to read as an array.
     *
     * @param arena {@link Arena} the allocation scope of the call being made
     * @param pointers {@link MemorySegment} array the pointers, in order
     * @return {@link MemorySegment} where that run starts
     */
    private static MemorySegment addressArray(final Arena arena, final MemorySegment[] pointers) {
        final MemorySegment array = arena.allocate(ValueLayout.ADDRESS, pointers.length);
        for (int index = 0; index < pointers.length; index++) {
            array.setAtIndex(ValueLayout.ADDRESS, index, pointers[index]);
        }
        return array;
    }

    /**
     * Turns a Java string into a CoreFoundation one.
     *
     * @param arena {@link Arena} the allocation scope of the call being made
     * @param scope {@link CfScope} what releases the string afterwards
     * @param text {@link String} what the string should hold
     * @return {@link MemorySegment} the built string
     */
    private MemorySegment string(final Arena arena, final CfScope scope, final String text) {
        return scope.own(created(this.callStringCreate(arena.allocateFrom(text)), "a string"));
    }

    /**
     * Turns a byte array into a CoreFoundation data object, which copies the bytes.
     *
     * @param arena {@link Arena} the allocation scope of the call being made
     * @param scope {@link CfScope} what releases the data afterwards
     * @param bytes byte array what the data should hold
     * @return {@link MemorySegment} the built data
     */
    private MemorySegment data(final Arena arena, final CfScope scope, final byte[] bytes) {
        return scope.own(created(
                this.callDataCreate(arena.allocateFrom(ValueLayout.JAVA_BYTE, bytes), bytes.length),
                "a data object"));
    }

    /**
     * Refuses an object CoreFoundation declined to build.
     *
     * <p>A failed create answers with null, and handing that on lands it inside a dictionary as a
     * key or a value. CoreFoundation does not check, so the failure would surface later as a crash
     * in a call that had nothing wrong with it.
     *
     * @param created {@link MemorySegment} what the create call answered
     * @param what {@link String} what was being built, for the message
     * @return {@link MemorySegment} the built object
     */
    private static MemorySegment created(final MemorySegment created, final String what) {
        if (created.equals(MemorySegment.NULL)) {
            throw new SecretStoreException(SecretStoreException.Tier.KEYRING,
                    "macOS would not build " + what + " to describe a keychain entry with");
        }
        return created;
    }

    /**
     * Invokes {@code SecItemCopyMatching}, turning the checked throwable the invocation declares
     * into the failure this class reports.
     *
     * @param query {@link MemorySegment} the dictionary describing what to match
     * @param slot {@link MemorySegment} where the call puts what it found
     * @return int the status the keychain answered with
     */
    private int callCopyMatching(final MemorySegment query, final MemorySegment slot) {
        try {
            return (int) this.itemCopyMatching.invokeExact(query, slot);
        } catch (final Throwable unreachable) {
            throw binding(unreachable);
        }
    }

    /**
     * Invokes {@code SecItemAdd}, asking for nothing back. The added item is described entirely by
     * what went in, so there is nothing in the result worth the release it would owe.
     *
     * @param attributes {@link MemorySegment} the dictionary describing the item to add
     * @return int the status the keychain answered with
     */
    private int callAdd(final MemorySegment attributes) {
        try {
            return (int) this.itemAdd.invokeExact(attributes, MemorySegment.NULL);
        } catch (final Throwable unreachable) {
            throw binding(unreachable);
        }
    }

    /**
     * Invokes {@code SecItemUpdate}.
     *
     * @param query {@link MemorySegment} the dictionary describing which item to change
     * @param changes {@link MemorySegment} the dictionary describing what to change about it
     * @return int the status the keychain answered with
     */
    private int callUpdate(final MemorySegment query, final MemorySegment changes) {
        try {
            return (int) this.itemUpdate.invokeExact(query, changes);
        } catch (final Throwable unreachable) {
            throw binding(unreachable);
        }
    }

    /**
     * Invokes {@code SecItemDelete}.
     *
     * @param query {@link MemorySegment} the dictionary describing which item to remove
     * @return int the status the keychain answered with
     */
    private int callDelete(final MemorySegment query) {
        try {
            return (int) this.itemDelete.invokeExact(query);
        } catch (final Throwable unreachable) {
            throw binding(unreachable);
        }
    }

    /**
     * Invokes {@code CFDictionaryCreate} with the callback tables that retain and release whatever
     * is put in.
     *
     * @param keys {@link MemorySegment} the array of keys
     * @param values {@link MemorySegment} the array of values
     * @param count int how many pairs there are
     * @return {@link MemorySegment} the built dictionary, or null where it could not be built
     */
    private MemorySegment callDictionaryCreate(final MemorySegment keys, final MemorySegment values,
            final int count) {
        try {
            return (MemorySegment) this.dictionaryCreate.invokeExact(MemorySegment.NULL, keys,
                    values, (long) count, this.keyCallBacks, this.valueCallBacks);
        } catch (final Throwable unreachable) {
            throw binding(unreachable);
        }
    }

    /**
     * Invokes {@code CFStringCreateWithCString}. The allocator is left null, which CoreFoundation
     * reads as the default one.
     *
     * @param text {@link MemorySegment} the null-terminated bytes to read
     * @return {@link MemorySegment} the built string, or null where it could not be built
     */
    private MemorySegment callStringCreate(final MemorySegment text) {
        try {
            return (MemorySegment) this.stringCreate.invokeExact(MemorySegment.NULL, text, UTF8);
        } catch (final Throwable unreachable) {
            throw binding(unreachable);
        }
    }

    /**
     * Invokes {@code CFDataCreate}.
     *
     * @param bytes {@link MemorySegment} the bytes to copy
     * @param length int how many of them there are
     * @return {@link MemorySegment} the built data, or null where it could not be built
     */
    private MemorySegment callDataCreate(final MemorySegment bytes, final int length) {
        try {
            return (MemorySegment) this.dataCreate.invokeExact(MemorySegment.NULL, bytes,
                    (long) length);
        } catch (final Throwable unreachable) {
            throw binding(unreachable);
        }
    }

    /**
     * Invokes {@code CFDataGetBytePtr}.
     *
     * @param data {@link MemorySegment} the data object to read
     * @return {@link MemorySegment} where its bytes start
     */
    private MemorySegment callDataBytes(final MemorySegment data) {
        try {
            return (MemorySegment) this.dataBytes.invokeExact(data);
        } catch (final Throwable unreachable) {
            throw binding(unreachable);
        }
    }

    /**
     * Invokes {@code CFDataGetLength}.
     *
     * @param data {@link MemorySegment} the data object to measure
     * @return long how many bytes it holds
     */
    private long callDataLength(final MemorySegment data) {
        try {
            return (long) this.dataLength.invokeExact(data);
        } catch (final Throwable unreachable) {
            throw binding(unreachable);
        }
    }

    /**
     * Invokes {@code CFRelease}.
     *
     * @param object {@link MemorySegment} the object to release
     */
    private void callRelease(final MemorySegment object) {
        try {
            this.release.invokeExact(object);
        } catch (final Throwable unreachable) {
            throw binding(unreachable);
        }
    }

    /**
     * A loaded framework, paired with the name to blame when it is missing something.
     *
     * <p>The pairing is the point. Two frameworks are read here, and the selector logs whichever
     * message comes back as its diagnosis of the machine. A lookup that names the wrong one sends
     * its only reader after the wrong library. Carrying the name beside the lookup means a call
     * site cannot get that pairing wrong, where passing the name per call would let it.
     *
     * @param name {@link String} what to call this framework in a failure
     * @param lookup {@link SymbolLookup} the loaded framework
     */
    private record Framework(String name, SymbolLookup lookup) {

        /**
         * Resolves one symbol, refusing rather than answering with nothing.
         *
         * <p>A framework that loads but is missing a name then leaves {@link #open()} the same way
         * a missing framework does. What either one means is the caller's ruling, not this class's.
         *
         * @param symbolName {@link String} the exported name
         * @return {@link MemorySegment} the symbol's own address
         */
        private MemorySegment symbol(final String symbolName) {
            return this.lookup.find(symbolName).orElseThrow(() -> new IllegalArgumentException(
                    "this machine's " + this.name + " exports no " + symbolName));
        }

        /**
         * Reads the pointer a global pointer variable holds.
         *
         * <p>Separate from {@link #symbol} because the two answer different questions, and using
         * either where the other belongs compiles and links. A symbol lookup answers where a
         * variable lives. For a global declared as a pointer, that address is where the pointer
         * sits rather than what it points at. The value wanted is one dereference on.
         *
         * @param symbolName {@link String} the exported name of a global pointer variable
         * @return {@link MemorySegment} the pointer that variable holds
         */
        private MemorySegment pointerAt(final String symbolName) {
            return this.symbol(symbolName)
                    .reinterpret(ValueLayout.ADDRESS.byteSize())
                    .get(ValueLayout.ADDRESS, 0);
        }
    }

    /**
     * Holds every CoreFoundation object one call owns, and releases them when that call ends.
     *
     * <p>Native memory here has two owners and the arena covers only one of them. Anything allocated
     * to hand to a native function belongs to the arena and dies with it. Anything CoreFoundation
     * built is reference counted and has to be released by name, which is what this does. A
     * dictionary retains its keys and values, so releasing it is not enough to release them.
     *
     * <p>Objects go out last in, first out, so a dictionary is released before anything it holds.
     * The reference counting is happy either way, since a dictionary holds its own count on each
     * key and value.
     */
    private final class CfScope implements AutoCloseable {

        private final List<MemorySegment> owned = new ArrayList<>();

        /**
         * Takes ownership of an object, so that closing this scope releases it.
         *
         * @param object {@link MemorySegment} the object to release later
         * @return {@link MemorySegment} that same object, for the caller to keep using
         */
        private MemorySegment own(final MemorySegment object) {
            this.owned.add(object);
            return object;
        }

        @Override
        public void close() {
            for (int index = this.owned.size() - 1; index >= 0; index--) {
                SecurityFrameworkKeychain.this.callRelease(this.owned.get(index));
            }
        }
    }
}
