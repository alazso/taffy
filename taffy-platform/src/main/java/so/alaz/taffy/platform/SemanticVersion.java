package so.alaz.taffy.platform;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A version in the form of Semantic Versioning 2.0.0, as {@link UpdateCheck} compares it.
 *
 * <p>The order is the precedence of the specification: major, minor and patch as numbers, then a version with
 * pre-release identifiers below the same version without them, then the identifiers from the left, where two
 * numeric identifiers compare as numbers, a numeric one is below one with a letter or hyphen, two others compare
 * by ASCII order, and a longer list is above its own start. Build metadata takes no part: it is read and dropped.
 *
 * @param preRelease the identifiers between the {@code -} and the build metadata, empty for a release
 */
record SemanticVersion(int major, int minor, int patch, List<String> preRelease)
    implements Comparable<SemanticVersion> {

    /** The longest text {@link #parse} reads. */
    static final int MAX_LENGTH = 64;

    private static final Pattern FORM = Pattern.compile(
        "[vV]?(\\d{1,9})\\.(\\d{1,9})(?:\\.(\\d{1,9}))?"
            + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
            + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?");

    private static final Pattern NUMERIC = Pattern.compile("\\d+");

    SemanticVersion {
        preRelease = List.copyOf(preRelease);
    }

    /**
     * Reads a version such as {@code 1.4.0}, {@code v1.4.0}, {@code 1.4}, {@code 1.5.0-rc.1},
     * {@code 1.5.0-SNAPSHOT} or {@code 1.4.0+build.7}. Whitespace around the text is dropped, a leading {@code v}
     * is allowed, a missing patch number is 0, and each number has at most 9 digits.
     *
     * @return the version, or null when the text is longer than {@link #MAX_LENGTH} characters or has another form
     */
    static @Nullable SemanticVersion parse(String text) {
        String trimmed = text.strip();
        if (trimmed.length() > MAX_LENGTH) {
            return null;
        }
        Matcher matcher = FORM.matcher(trimmed);
        if (!matcher.matches()) {
            return null;
        }
        String patch = matcher.group(3);
        String preRelease = matcher.group(4);
        return new SemanticVersion(
            Integer.parseInt(matcher.group(1)),
            Integer.parseInt(matcher.group(2)),
            patch == null ? 0 : Integer.parseInt(patch),
            preRelease == null ? List.of() : List.of(preRelease.split("\\.")));
    }

    /** Whether the version has pre-release identifiers, as {@code 1.5.0-rc.1} and {@code 1.5.0-SNAPSHOT} do. */
    boolean isPreRelease() {
        return !preRelease.isEmpty();
    }

    @Override
    public int compareTo(SemanticVersion other) {
        int core = Integer.compare(major, other.major);
        if (core == 0) {
            core = Integer.compare(minor, other.minor);
        }
        if (core == 0) {
            core = Integer.compare(patch, other.patch);
        }
        if (core != 0) {
            return core;
        }
        if (preRelease.isEmpty() || other.preRelease.isEmpty()) {
            return Boolean.compare(preRelease.isEmpty(), other.preRelease.isEmpty());
        }
        int shared = Math.min(preRelease.size(), other.preRelease.size());
        for (int index = 0; index < shared; index++) {
            int order = compareIdentifiers(preRelease.get(index), other.preRelease.get(index));
            if (order != 0) {
                return order;
            }
        }
        return Integer.compare(preRelease.size(), other.preRelease.size());
    }

    private static int compareIdentifiers(String left, String right) {
        boolean leftNumeric = NUMERIC.matcher(left).matches();
        boolean rightNumeric = NUMERIC.matcher(right).matches();
        if (leftNumeric && rightNumeric) {
            String leftDigits = withoutLeadingZeros(left);
            String rightDigits = withoutLeadingZeros(right);
            int order = Integer.compare(leftDigits.length(), rightDigits.length());
            return order != 0 ? order : Integer.signum(leftDigits.compareTo(rightDigits));
        }
        if (leftNumeric != rightNumeric) {
            return leftNumeric ? -1 : 1;
        }
        return Integer.signum(left.compareTo(right));
    }

    private static String withoutLeadingZeros(String digits) {
        int start = 0;
        while (start < digits.length() - 1 && digits.charAt(start) == '0') {
            start++;
        }
        return digits.substring(start);
    }

    /** The version as {@code major.minor.patch}, followed by {@code -} and the identifiers when it has any. */
    @Override
    public String toString() {
        String core = major + "." + minor + "." + patch;
        return preRelease.isEmpty() ? core : core + "-" + String.join(".", preRelease);
    }
}
