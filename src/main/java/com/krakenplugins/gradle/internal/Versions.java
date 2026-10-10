package com.krakenplugins.gradle.internal;

/**
 * Compares dotted version numbers such as {@code 5.1.10}. A suffix after {@code -} is ignored, so {@code 5.1.6-test}
 * compares equal to {@code 5.1.6}.
 */
public final class Versions {

    private Versions() {
    }

    /**
     * Compares two dotted version numbers numerically, segment by segment. Missing segments count as zero.
     *
     * @param left  the first version
     * @param right the second version
     * @return a negative number, zero or a positive number as {@code left} is older than, equal to or newer than
     * {@code right}
     */
    public static int compare(String left, String right) {
        String[] leftParts = numericPart(left).split("\\.");
        String[] rightParts = numericPart(right).split("\\.");
        for (int i = 0; i < Math.max(leftParts.length, rightParts.length); i++) {
            int difference = Long.compare(segment(leftParts, i), segment(rightParts, i));
            if (difference != 0) {
                return difference;
            }
        }
        return 0;
    }

    private static String numericPart(String version) {
        int suffix = version.indexOf('-');
        return suffix < 0 ? version : version.substring(0, suffix);
    }

    private static long segment(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        String digits = parts[index].replaceAll("\\D.*$", "");
        return digits.isEmpty() ? 0 : Long.parseLong(digits);
    }
}
