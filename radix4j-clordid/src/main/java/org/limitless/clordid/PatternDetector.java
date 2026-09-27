package org.limitless.clordid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Chooses the {@link Pattern} of a scope from a sample of its ids. A pattern is accepted when at least 90% of the
 * ids match it, the prefixes repeat, at most one distinct prefix per {@value #IDS_PER_PREFIX} matching ids, and
 * the numbers under a prefix are dense, with a median gap of at most {@value #MAX_MEDIAN_GAP}. Random ids have
 * distinct prefixes, so they never pass. Decimal is tried first, then decimal before a suffix from the shortest
 * to the longest suffix, then base 36 from the widest to the narrowest width, and {@link Pattern#none()} when
 * nothing passes. The suffix is part of the prefix here, so a suffix that varies fails like a random prefix.
 * <p>
 * Detection runs once per scope and trading day, so it allocates freely.
 */
final class PatternDetector {

    static final int IDS_PER_PREFIX = 4;
    static final int MAX_MEDIAN_GAP = 4096;

    private PatternDetector() {
    }

    /**
     * Choose a pattern
     * @param samples ids, each a length byte followed by the id, {@code stride} bytes apart
     * @param stride bytes per id
     * @param count number of ids
     * @return pattern
     */
    static Pattern detect(final byte[] samples, final int stride, final int count) {
        for (int suffix = 0; suffix <= Pattern.MAX_SUFFIX; ++suffix) {
            final Pattern pattern = Pattern.decimal(suffix);
            if (accepts(pattern, samples, stride, count)) {
                return pattern;
            }
        }
        for (int width = Pattern.MAX_BASE36_WIDTH; width >= 1; --width) {
            final Pattern pattern = Pattern.base36(width);
            if (accepts(pattern, samples, stride, count)) {
                return pattern;
            }
        }
        return Pattern.none();
    }

    private static boolean accepts(final Pattern pattern, final byte[] samples, final int stride, final int count) {
        final Map<String, List<Long>> numbers = new HashMap<>();
        int matched = 0;
        for (int i = 0; i < count; ++i) {
            final int position = i * stride + 1;
            final int length = samples[i * stride] & 0xff;
            final int digits = pattern.numberLength(samples, position, length);
            if (digits >= 0) {
                ++matched;
                final int start = pattern.numberPosition(position, length, digits);
                final String prefix = new String(samples, position, start - position) + '\0' +
                    new String(samples, start + digits, pattern.suffix()) + '\0' + digits;
                numbers.computeIfAbsent(prefix, key -> new ArrayList<>())
                    .add(pattern.number(samples, start, digits));
            }
        }
        if (matched * 10 < count * 9 || numbers.size() * IDS_PER_PREFIX > matched) {
            return false;
        }
        final List<Long> gaps = new ArrayList<>();
        for (final List<Long> values : numbers.values()) {
            final long[] sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
            for (int i = 1; i < sorted.length; ++i) {
                gaps.add(sorted[i] - sorted[i - 1]);
            }
        }
        if (gaps.isEmpty()) {
            return false;
        }
        final long[] sorted = gaps.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2] <= MAX_MEDIAN_GAP;
    }
}
