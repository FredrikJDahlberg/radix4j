package org.limitless.clordid;

/**
 * Splits an id into a prefix, a number and a suffix at its end. The split is reversible: the prefix, the number of
 * characters in the number, its value and the suffix give back the id, so different ids never share a split.
 * <ul>
 *   <li>{@link #decimal()}: the trailing digits, at most {@value #MAX_DIGITS}, leading zeros included</li>
 *   <li>{@link #decimal(int)}: the digits before a suffix of a fixed number of characters, such as
 *       {@code ARB00045612X7Y} with a 3-character suffix</li>
 *   <li>{@link #base36(int)}: a fixed number of trailing characters in 0-9 and A-Z</li>
 *   <li>{@link #none()}: no number, for ids without structure such as UUIDs</li>
 *   <li>{@link #auto()}: detect one of the others from the first ids of a scope</li>
 * </ul>
 */
public final class Pattern {

    public static final int MAX_DIGITS = 18;
    public static final int MAX_BASE36_WIDTH = 12;
    public static final int MAX_SUFFIX = 8;

    private static final int DECIMAL = 0;
    private static final int BASE36 = 1;
    private static final int NONE = 2;
    private static final int AUTO = 3;
    private static final int KIND_SHIFT = 5;
    private static final Pattern DECIMAL_PATTERN = new Pattern(DECIMAL, 0);
    private static final Pattern NONE_PATTERN = new Pattern(NONE, 0);
    private static final Pattern AUTO_PATTERN = new Pattern(AUTO, 0);
    private static final byte[] BASE36_VALUES = new byte[256];

    static {
        java.util.Arrays.fill(BASE36_VALUES, (byte) -1);
        for (int i = 0; i < 36; ++i) {
            BASE36_VALUES[i < 10 ? '0' + i : 'A' + i - 10] = (byte) i;
        }
    }

    private final int kind;
    private final int width;
    private final int suffix;

    private Pattern(final int kind, final int width) {
        this(kind, width, 0);
    }

    private Pattern(final int kind, final int width, final int suffix) {
        this.kind = kind;
        this.width = width;
        this.suffix = suffix;
    }

    /**
     * Numbers made of the trailing digits
     * @return pattern
     */
    public static Pattern decimal() {
        return DECIMAL_PATTERN;
    }

    /**
     * Numbers made of the digits before a suffix of a fixed number of characters
     * @param suffix number of characters after the number
     * @return pattern
     * @throws IllegalArgumentException suffix outside 0..{@value #MAX_SUFFIX}
     */
    public static Pattern decimal(final int suffix) {
        if (suffix < 0 || suffix > MAX_SUFFIX) {
            throw new IllegalArgumentException("invalid suffix: " + suffix);
        }
        return suffix == 0 ? DECIMAL_PATTERN : new Pattern(DECIMAL, 0, suffix);
    }

    /**
     * Matches no id
     * @return pattern
     */
    public static Pattern none() {
        return NONE_PATTERN;
    }

    /**
     * Detect the pattern of a scope from its first ids, the default of {@link ClientOrderIdSet}
     * @return pattern
     */
    public static Pattern auto() {
        return AUTO_PATTERN;
    }

    /**
     * Numbers made of a fixed number of trailing upper case base-36 characters
     * @param width number of characters
     * @return pattern
     * @throws IllegalArgumentException width outside 1..{@value #MAX_BASE36_WIDTH}
     */
    public static Pattern base36(final int width) {
        if (width < 1 || width > MAX_BASE36_WIDTH) {
            throw new IllegalArgumentException("invalid width: " + width);
        }
        return new Pattern(BASE36, width);
    }

    /**
     * Number of characters after the number
     * @return suffix length
     */
    public int suffix() {
        return suffix;
    }

    /**
     * Length of the number at the end of the id, before the suffix
     * @param id bytes
     * @param position id start
     * @param length id length
     * @return number of characters, or -1 when the id does not match
     */
    public int numberLength(final byte[] id, final int position, final int length) {
        if (kind == DECIMAL) {
            final int end = position + length - suffix;
            final int limit = Math.min(length - suffix, MAX_DIGITS);
            int count = 0;
            while (count < limit && (id[end - 1 - count] - '0' & 0xff) < 10) {
                ++count;
            }
            return count == 0 ? -1 : count;
        }
        if (kind >= NONE || length < width) {
            return -1;
        }
        final int end = position + length;
        for (int i = end - width; i < end; ++i) {
            if (BASE36_VALUES[id[i] & 0xff] < 0) {
                return -1;
            }
        }
        return width;
    }

    /**
     * Value of a number found by {@link #numberLength}
     * @param id bytes
     * @param position number start
     * @param count number of characters
     * @return value
     */
    public long number(final byte[] id, final int position, final int count) {
        long value = 0;
        if (kind == DECIMAL) {
            for (int i = position; i < position + count; ++i) {
                value = value * 10 + id[i] - '0';
            }
        } else {
            for (int i = position; i < position + count; ++i) {
                value = value * 36 + BASE36_VALUES[id[i] & 0xff];
            }
        }
        return value;
    }

    /**
     * Start of the number in an id matching the pattern
     * @param position id start
     * @param length id length
     * @param count number of characters in the number
     * @return number start
     */
    public int numberPosition(final int position, final int length, final int count) {
        return position + length - suffix - count;
    }

    /**
     * Pattern kind and number length in one byte, stored with the prefix
     * @param count number of characters
     * @return format byte
     */
    byte format(final int count) {
        return (byte) (kind << KIND_SHIFT | count);
    }

    /**
     * Pattern as text, such as {@code decimal}, {@code decimal(suffix 3)} or {@code base36(7)}
     * @return text
     */
    @Override
    public String toString() {
        return switch (kind) {
            case DECIMAL -> suffix == 0 ? "decimal" : "decimal(suffix " + suffix + ")";
            case BASE36 -> "base36(" + width + ")";
            case NONE -> "none";
            default -> "auto";
        };
    }

    @Override
    public boolean equals(final Object object) {
        return object instanceof Pattern pattern && pattern.kind == kind && pattern.width == width &&
            pattern.suffix == suffix;
    }

    @Override
    public int hashCode() {
        return (kind * 31 + width) * 31 + suffix;
    }
}
