package glucoselab;

import java.util.Optional;

/** Rejects a reading with a stable reason code. A bad row is counted and reported, never silently dropped. */
public final class Validator {
    public static final int MIN_MGDL = 20;
    public static final int MAX_MGDL = 600;
    public static final long MAX_FUTURE_SECONDS = 300;

    private Validator() {}

    public static Optional<String> check(Reading r, long nowSeconds) {
        if (r == null) return Optional.of("null_row");
        if (r.deviceId() == null || r.deviceId().isBlank()) return Optional.of("missing_device");
        if (r.subjectId() == null || r.subjectId().isBlank()) return Optional.of("missing_subject");
        if (r.ts() <= 0) return Optional.of("bad_timestamp");
        if (r.ts() > nowSeconds + MAX_FUTURE_SECONDS) return Optional.of("future_timestamp");
        if (r.mgdl() < MIN_MGDL || r.mgdl() > MAX_MGDL) return Optional.of("out_of_range");
        return Optional.empty();
    }
}
