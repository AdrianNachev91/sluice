package photos.sluice.domain.model;

public enum Confidence {
    TRUSTED,
    LOW,
    // Assigned by DateResolver, never by a DateSource: a LOW-confidence date that also fails the
    // plausibility guard (future or pre-2000).
    UNSORTABLE
}
