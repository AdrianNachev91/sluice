package photos.sluice.adapter.cli;

/**
 * A scope argument the parser read and a verb refused.
 *
 * <p>It carries the whole refusal rather than a message. Whatever worked out that the arguments
 * cannot name a scope already knows which refusal it is and what fields go with it. So nothing
 * downstream has to read that back out of a sentence.
 */
public class ScopeRefusedException extends RuntimeException {

    private final transient Refusal refusal;

    /**
     * Creates the refusal.
     *
     * @param refusal {@link Refusal} which refusal this is, and what to tell the person
     */
    public ScopeRefusedException(final Refusal refusal) {
        super(refusal.sentence());
        this.refusal = refusal;
    }

    /**
     * Which refusal this is, and what to tell the person.
     *
     * @return {@link Refusal} the refusal
     */
    public Refusal refusal() {
        return this.refusal;
    }
}
