package com.dtc.transit.masterdata.bus;

/**
 * Normalisation for vehicle registration numbers.
 *
 * <p>The same plate is written many ways by different depots: {@code DL1PC1234}, {@code DL 1PC 1234},
 * {@code dl-1pc-1234}. Uniqueness has to be judged on one canonical form, or the same bus is imported
 * three times (edge case EC-DATA-01). The value as entered is kept separately for display, because a
 * stripped plate is harder for staff to read back.
 */
public final class RegistrationNo {

    private RegistrationNo() {}

    /**
     * Upper-cases and removes every separator.
     *
     * @throws IllegalArgumentException if nothing alphanumeric remains
     */
    public static String normalise(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("registration number must not be null");
        }
        String normalised = raw.toUpperCase().replaceAll("[^A-Z0-9]", "");
        if (normalised.isEmpty()) {
            throw new IllegalArgumentException("registration number '" + raw + "' has no alphanumeric characters");
        }
        return normalised;
    }

    /** Normalises for comparison without throwing, used when filtering on a partial term. */
    public static String normaliseForSearch(String raw) {
        return raw == null ? null : raw.toUpperCase().replaceAll("[^A-Z0-9]", "");
    }
}
