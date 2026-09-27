package org.limitless.clordid;

import java.util.Arrays;

/**
 * Detects duplicate client order ids within a trading day.
 * <p>
 * FIX 4.4 requires a ClOrdID (tag 11, 1 to {@value #MAX_LENGTH} printable ASCII characters) to be unique within one
 * trading day for a scope: a FIX session (SenderCompID and TargetCompID), or an entity where the venue says so. The
 * caller numbers the scopes densely from 0 to {@value #MAX_SCOPES} - 1. Uniqueness holds across reconnects, so ids
 * are only freed by {@link #rollover()} at the end of the trading day. Scopes on different trading calendars use
 * separate sets.
 * <p>
 * Each scope has a {@link Pattern} splitting its ids into a prefix, a number and a suffix. The number is stored in
 * a {@link CounterSet} under the scope, the pattern format, the prefix and the suffix. Ids not matching the pattern are packed
 * into 7 bits per character and stored in the scope's table of a {@link FallbackSet}. A pattern is fixed from the
 * first id of its scope until {@link #rollover()}, so that an id always goes to the same set.
 * <p>
 * By default a scope detects its pattern: its first {@value #DETECTION_IDS} ids of the trading day are kept in a
 * small buffer, then {@link PatternDetector} chooses the pattern and the buffered ids move to the sets.
 * <p>
 * A counter costs little per id only while its container, one per prefix and 65,536 numbers, holds many ids. A
 * scope may create containers in bursts of up to {@value #CONTAINER_BURST}, such as the first id of each trader,
 * and in the long run one per {@value #IDS_PER_CONTAINER} new ids. A scope creating them faster, such as random ids
 * ending in digits or numbers far apart, is demoted until {@link #rollover()}: its ids go to the counters only when
 * their container exists, the others to the fallback set. Containers never go away, so an id is always found in the
 * set it went to.
 * <p>
 * The set lives in memory only and is lost with the process. A caller needing recovery rebuilds it by adding the
 * ids of the trading day again, in any order. Whether the id of a rejected order counts as used is up to the
 * caller. It is not thread safe.
 */
public final class ClientOrderIdSet implements AutoCloseable {

    public static final int ADDED = 1;
    public static final int DUPLICATE = 0;
    public static final int MAX_LENGTH = 32;
    public static final int MAX_SCOPES = 256;
    public static final int DEFAULT_SEGMENT_BYTES = 64 * 1024;
    public static final int DETECTION_IDS = 32;
    public static final int IDS_PER_CONTAINER = 4;
    public static final int CONTAINER_BURST = 4096;

    private static final int PREFIX_OFFSET = 2;
    private static final int SAMPLE_STRIDE = 1 + MAX_LENGTH;

    private final int segmentBytes;
    private final byte[] key = new byte[PREFIX_OFFSET + MAX_LENGTH];
    private final byte[] packed = new byte[MAX_LENGTH + Printable.PACK_SLACK];
    private final Pattern[] patterns = new Pattern[MAX_SCOPES];     // configured, null to detect
    private final Pattern[] detected = new Pattern[MAX_SCOPES];     // detected in the trading day
    private final byte[][] samples = new byte[MAX_SCOPES][];        // ids kept while detecting
    private final int[] sampleCounts = new int[MAX_SCOPES];
    private final long[] used = new long[MAX_SCOPES / Long.SIZE];   // scopes with ids in the trading day
    private final long[] demoted = new long[MAX_SCOPES / Long.SIZE];
    private final int[] debts = new int[MAX_SCOPES];     // containers created beyond the allowance, in 1/4
    private long sampled;
    private CounterSet counters;
    private FallbackSet fallback;
    private boolean closed;

    /**
     * Memory and contents of the trading day
     * @param counterIds ids stored as numbers under a prefix
     * @param fallbackIds ids stored whole
     * @param prefixes distinct prefixes of the counters
     * @param containers counter containers, one per prefix and 65,536 numbers
     * @param heapBytes heap bytes of the counter tables and the fallback directories
     * @param offHeapBytes off-heap bytes allocated
     */
    public record Statistics(long counterIds, long fallbackIds, int prefixes, int containers,
                             long heapBytes, long offHeapBytes) {
    }

    /**
     * Constructs a set with {@value #DEFAULT_SEGMENT_BYTES} byte segments
     */
    public ClientOrderIdSet() {
        this(DEFAULT_SEGMENT_BYTES);
    }

    /**
     * Constructs a set
     * @param segmentBytes bytes per segment of each block size, 8192 to 1 MB
     * @throws IllegalArgumentException invalid segment size
     */
    public ClientOrderIdSet(final int segmentBytes) {
        this.segmentBytes = segmentBytes;
        counters = new CounterSet(segmentBytes);
        fallback = new FallbackSet(segmentBytes);
    }

    /**
     * Set the pattern of a scope, {@link Pattern#auto()} by default
     * @param scope scope in 0..{@value #MAX_SCOPES} - 1
     * @param pattern pattern
     * @throws IllegalArgumentException invalid scope
     * @throws IllegalStateException the scope has ids in the trading day
     */
    public void pattern(final int scope, final Pattern pattern) {
        checkScope(scope);
        if ((used[scope >>> 6] & 1L << scope) != 0) {
            throw new IllegalStateException("scope " + scope + " has ids, change its pattern at rollover");
        }
        patterns[scope] = pattern == Pattern.auto() ? null : pattern;
    }

    /**
     * Pattern of a scope: the configured one, the detected one, or {@link Pattern#auto()} while detecting
     * @param scope scope in 0..{@value #MAX_SCOPES} - 1
     * @return pattern
     * @throws IllegalArgumentException invalid scope
     */
    public Pattern pattern(final int scope) {
        checkScope(scope);
        final Pattern pattern = effective(scope);
        return pattern != null ? pattern : Pattern.auto();
    }

    /**
     * Check if a scope is demoted: it creates no more containers until {@link #rollover()}
     * @param scope scope in 0..{@value #MAX_SCOPES} - 1
     * @return true when demoted
     * @throws IllegalArgumentException invalid scope
     */
    public boolean demoted(final int scope) {
        checkScope(scope);
        return isDemoted(scope);
    }

    /**
     * Add an id unless it was added earlier in the trading day
     * @param scope scope in 0..{@value #MAX_SCOPES} - 1
     * @param id bytes
     * @param position id start
     * @param length id length
     * @return {@link #ADDED} or {@link #DUPLICATE}
     * @throws IllegalArgumentException invalid scope, or id empty, longer than {@value #MAX_LENGTH} or not printable
     * @throws IllegalStateException closed set, or memory for the trading day exhausted
     */
    public int add(final int scope, final byte[] id, final int position, final int length) {
        checkOpen();
        checkScope(scope);
        checkId(id, position, length);
        used[scope >>> 6] |= 1L << scope;
        final Pattern pattern = effective(scope);
        if (pattern == null) {
            return sample(scope, id, position, length) ? ADDED : DUPLICATE;
        }
        return store(scope, pattern, id, position, length) ? ADDED : DUPLICATE;
    }

    /**
     * Check if an id was added in the trading day
     * @param scope scope in 0..{@value #MAX_SCOPES} - 1
     * @param id bytes
     * @param position id start
     * @param length id length
     * @return true when present
     * @throws IllegalArgumentException invalid scope, or id empty, longer than {@value #MAX_LENGTH} or not printable
     * @throws IllegalStateException closed set
     */
    public boolean contains(final int scope, final byte[] id, final int position, final int length) {
        checkOpen();
        checkScope(scope);
        checkId(id, position, length);
        final Pattern pattern = effective(scope);
        if (pattern == null) {
            return findSample(scope, id, position, length);
        }
        final int count = pattern.numberLength(id, position, length);
        if (count < 0) {
            return fallback.contains(scope, packed, 0, Printable.pack(id, position, length, packed, 0));
        }
        final int keyLength = key(scope, pattern, count, id, position, length);
        final long number = pattern.number(id, pattern.numberPosition(position, length, count), count);
        return counters.contains(key, 0, keyLength, number) ||
            isDemoted(scope) && fallback.contains(scope, packed, 0, Printable.pack(id, position, length, packed, 0));
    }

    /**
     * Start a new trading day, freeing all ids of the previous day at once. Scopes without a configured pattern
     * detect theirs again.
     * @throws IllegalStateException closed set
     */
    public void rollover() {
        checkOpen();
        counters.close();
        fallback.close();
        counters = new CounterSet(segmentBytes);
        fallback = new FallbackSet(segmentBytes);
        Arrays.fill(used, 0);
        Arrays.fill(demoted, 0);
        Arrays.fill(debts, 0);
        Arrays.fill(detected, null);
        Arrays.fill(samples, null);
        Arrays.fill(sampleCounts, 0);
        sampled = 0;
    }

    /**
     * Number of ids added in the trading day
     * @return size
     */
    public long size() {
        return counters.size() + fallback.size() + sampled;
    }

    /**
     * Memory and contents of the trading day
     * @return statistics
     */
    public Statistics statistics() {
        return new Statistics(counters.size(), fallback.size(), counters.prefixes(), counters.containers(),
            counters.heapBytes() + fallback.heapBytes(), counters.offHeapBytes() + fallback.offHeapBytes());
    }

    CounterSet counters() {
        return counters;
    }

    FallbackSet fallback() {
        return fallback;
    }

    /**
     * Free the ids of the trading day
     */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            counters.close();
            fallback.close();
        }
    }

    private Pattern effective(final int scope) {
        final Pattern pattern = patterns[scope];
        return pattern != null ? pattern : detected[scope];
    }

    /**
     * Store an id in the set its pattern chooses
     * @return true when added
     */
    private boolean store(final int scope, final Pattern pattern, final byte[] id, final int position, final int length) {
        final int count = pattern.numberLength(id, position, length);
        if (count < 0) {
            return fallback.add(scope, packed, 0, Printable.pack(id, position, length, packed, 0));
        }
        final int keyLength = key(scope, pattern, count, id, position, length);
        final long number = pattern.number(id, pattern.numberPosition(position, length, count), count);
        if (isDemoted(scope)) {
            final int added = counters.addExisting(key, 0, keyLength, number);
            if (added != CounterSet.MISSING) {
                return added == CounterSet.ADDED;
            }
            return fallback.add(scope, packed, 0, Printable.pack(id, position, length, packed, 0));
        }
        final int containers = counters.containers();
        if (!counters.add(key, 0, keyLength, number)) {
            return false;
        }
        // each new id pays off a quarter of a container, each new container adds one
        int debt = Math.max(0, debts[scope] - 1);
        if (counters.containers() != containers) {
            debt += IDS_PER_CONTAINER;
            if (debt > IDS_PER_CONTAINER * CONTAINER_BURST) {
                demoted[scope >>> 6] |= 1L << scope;
            }
        }
        debts[scope] = debt;
        return true;
    }

    private boolean isDemoted(final int scope) {
        return (demoted[scope >>> 6] & 1L << scope) != 0;
    }

    /**
     * Keep an id of a scope that is detecting its pattern, and detect it when the buffer is full
     * @return true when added
     */
    private boolean sample(final int scope, final byte[] id, final int position, final int length) {
        if (findSample(scope, id, position, length)) {
            return false;
        }
        byte[] buffer = samples[scope];
        if (buffer == null) {
            buffer = samples[scope] = new byte[DETECTION_IDS * SAMPLE_STRIDE];
        }
        final int count = sampleCounts[scope];
        buffer[count * SAMPLE_STRIDE] = (byte) length;
        System.arraycopy(id, position, buffer, count * SAMPLE_STRIDE + 1, length);
        sampleCounts[scope] = count + 1;
        ++sampled;
        if (count + 1 == DETECTION_IDS) {
            final Pattern pattern = PatternDetector.detect(buffer, SAMPLE_STRIDE, DETECTION_IDS);
            detected[scope] = pattern;
            for (int i = 0; i < DETECTION_IDS; ++i) {
                store(scope, pattern, buffer, i * SAMPLE_STRIDE + 1, buffer[i * SAMPLE_STRIDE]);
            }
            samples[scope] = null;
            sampleCounts[scope] = 0;
            sampled -= DETECTION_IDS;
        }
        return true;
    }

    private boolean findSample(final int scope, final byte[] id, final int position, final int length) {
        final byte[] buffer = samples[scope];
        for (int i = 0; i < sampleCounts[scope]; ++i) {
            final int start = i * SAMPLE_STRIDE;
            if (buffer[start] == length && Arrays.equals(buffer, start + 1, start + 1 + length, id, position, position + length)) {
                return true;
            }
        }
        return false;
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("closed");
        }
    }

    private static void checkScope(final int scope) {
        if (scope < 0 || scope >= MAX_SCOPES) {
            throw new IllegalArgumentException("invalid scope: " + scope);
        }
    }

    private static void checkId(final byte[] id, final int position, final int length) {
        if (length < 1 || length > MAX_LENGTH) {
            throw new IllegalArgumentException("invalid id length: " + length);
        }
        if (!Printable.isPrintable(id, position, length)) {
            throw new IllegalArgumentException("id is not printable ASCII");
        }
    }

    /**
     * Key of the counters: scope, format, prefix and suffix. The suffix length is fixed for the scope in the
     * trading day, so the key splits back into the prefix and the suffix.
     * @return key length
     */
    private int key(final int scope, final Pattern pattern, final int count,
                    final byte[] id, final int position, final int length) {
        key[0] = (byte) scope;
        key[1] = pattern.format(count);
        final int suffix = pattern.suffix();
        final int prefixLength = length - suffix - count;
        System.arraycopy(id, position, key, PREFIX_OFFSET, prefixLength);
        System.arraycopy(id, position + length - suffix, key, PREFIX_OFFSET + prefixLength, suffix);
        return PREFIX_OFFSET + prefixLength + suffix;
    }
}
