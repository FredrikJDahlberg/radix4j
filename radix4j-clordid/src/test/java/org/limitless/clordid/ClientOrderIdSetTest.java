package org.limitless.clordid;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;
import static org.limitless.clordid.ClientOrderIdSet.*;

public class ClientOrderIdSetTest {

    @Test
    public void decimalPattern() {
        final Pattern pattern = Pattern.decimal();
        assertEquals(8, numberLength(pattern, "AB00000001"));
        assertEquals(3, numberLength(pattern, "1700000000000000000_123"));
        assertEquals(18, numberLength(pattern, "12345678901234567890"));
        assertEquals(-1, numberLength(pattern, "ORD-"));
        assertEquals(-1, numberLength(pattern, ""));
        assertEquals(1234567890123456789L % 1_000_000_000_000_000_000L,
            pattern.number(bytes("1234567890123456789"), 1, 18));
    }

    @Test
    public void base36Pattern() {
        final Pattern pattern = Pattern.base36(7);
        assertEquals(7, numberLength(pattern, "12345678901XYZ000A1Z"));
        assertEquals(-1, numberLength(pattern, "12345678901XYZ000a1Z"));
        assertEquals(-1, numberLength(pattern, "A1Z"));
        assertEquals(36 * 36 - 1, pattern.number(bytes("ZZ"), 0, 2));
        assertThrows(IllegalArgumentException.class, () -> Pattern.base36(13));
    }

    @Test
    public void decimalSuffixPattern() {
        final Pattern pattern = Pattern.decimal(3);
        assertEquals(8, numberLength(pattern, "ARB00045612X7Y"));
        assertEquals(3, pattern.numberPosition(0, 14, 8));
        assertEquals(45612, pattern.number(bytes("ARB00045612X7Y"), 3, 8));
        assertEquals(1, numberLength(pattern, "1X7Y"));
        assertEquals(-1, numberLength(pattern, "ARBX7Y"));
        assertEquals(-1, numberLength(pattern, "X7Y"));
        assertEquals(-1, numberLength(pattern, "7Y"));
        assertEquals(Pattern.decimal(), Pattern.decimal(0));
        assertNotEquals(Pattern.decimal(), pattern);
        assertEquals("decimal(suffix 3)", pattern.toString());
        assertThrows(IllegalArgumentException.class, () -> Pattern.decimal(-1));
        assertThrows(IllegalArgumentException.class, () -> Pattern.decimal(Pattern.MAX_SUFFIX + 1));
    }

    @Test
    public void suffixIsPartOfTheKey() {
        try (ClientOrderIdSet ids = new ClientOrderIdSet()) {
            ids.pattern(0, Pattern.decimal(3));
            assertEquals(ADDED, add(ids, 0, "ARB00045612X7Y"));
            assertEquals(ADDED, add(ids, 0, "ARB00045612X7Z"));
            assertEquals(ADDED, add(ids, 0, "ARB0045612X7Y"));
            assertEquals(ADDED, add(ids, 0, "AR00045612BX7Y"));
            assertEquals(ADDED, add(ids, 0, "ARB00045613X7Y"));
            assertEquals(ADDED, add(ids, 0, "X7Y"));
            assertEquals(DUPLICATE, add(ids, 0, "ARB00045612X7Y"));
            assertEquals(DUPLICATE, add(ids, 0, "X7Y"));
            assertTrue(contains(ids, 0, "ARB00045613X7Y"));
            assertFalse(contains(ids, 0, "ARB00045614X7Y"));
            assertFalse(contains(ids, 0, "ARB00045612X7W"));
            assertTrue(contains(ids, 0, "AR00045612BX7Y"));
            assertEquals(4, ids.counters().size());
            assertEquals(3, ids.counters().prefixes());
            assertEquals(2, ids.fallback().size());
        }
    }

    @Test
    public void detectsSuffixAndStoresCounters() {
        try (ClientOrderIdSet ids = new ClientOrderIdSet()) {
            for (int i = 0; i < 100_000; ++i) {
                assertEquals(ADDED, add(ids, 0, "ARB%08dX7Y".formatted(45612 + i)));
            }
            assertEquals(Pattern.decimal(3), ids.pattern(0));
            assertEquals(100_000, ids.counters().size());
            assertEquals(1, ids.counters().prefixes());
            assertEquals(DUPLICATE, add(ids, 0, "ARB00045612X7Y"));
            assertTrue(contains(ids, 0, "ARB00145611X7Y"));
            assertFalse(contains(ids, 0, "ARB00145612X7Y"));
        }
    }

    @Test
    public void leadingZerosAreDistinct() {
        try (ClientOrderIdSet ids = new ClientOrderIdSet()) {
            assertEquals(ADDED, add(ids, 0, "AB001"));
            assertEquals(ADDED, add(ids, 0, "AB01"));
            assertEquals(ADDED, add(ids, 0, "AB1"));
            assertEquals(ADDED, add(ids, 0, "A1"));
            assertEquals(DUPLICATE, add(ids, 0, "AB01"));
            assertEquals(4, ids.size());
        }
    }

    @Test
    public void scopesAreSeparate() {
        try (ClientOrderIdSet ids = new ClientOrderIdSet()) {
            assertEquals(ADDED, add(ids, 1, "ORD-1"));
            assertEquals(ADDED, add(ids, 2, "ORD-1"));
            assertEquals(DUPLICATE, add(ids, 1, "ORD-1"));
            assertFalse(contains(ids, 3, "ORD-1"));
        }
    }

    @Test
    public void unmatchedIdsUseFallback() {
        try (ClientOrderIdSet ids = new ClientOrderIdSet()) {
            ids.pattern(0, Pattern.decimal());
            ids.pattern(1, Pattern.decimal());
            ids.pattern(5, Pattern.base36(4));
            assertEquals(ADDED, add(ids, 0, "ORD-"));
            assertEquals(DUPLICATE, add(ids, 0, "ORD-"));
            assertEquals(ADDED, add(ids, 1, "ORD-"));
            assertEquals(ADDED, add(ids, 5, "ab12"));
            assertEquals(ADDED, add(ids, 5, "AB12"));
            assertEquals(DUPLICATE, add(ids, 5, "ab12"));
            assertTrue(contains(ids, 0, "ORD-"));
            assertFalse(contains(ids, 2, "ORD-"));
            assertEquals(3, ids.fallback().size());
            assertEquals(1, ids.counters().size());
            assertEquals(4, ids.size());
            assertThrows(IllegalArgumentException.class, () -> add(ids, 0, "1".repeat(MAX_LENGTH + 1)));
        }
    }

    @Test
    public void uuids() {
        try (ClientOrderIdSet ids = new ClientOrderIdSet()) {
            ids.pattern(200, Pattern.none());
            ids.pattern(255, Pattern.none());
            assertEquals(ADDED, add(ids, 200, "EAg7HEEzRdy37rY8NMLV-gA"));
            assertEquals(ADDED, add(ids, 200, "ku0z3UbwT3Kc69bHujxYSwA"));
            assertEquals(DUPLICATE, add(ids, 200, "EAg7HEEzRdy37rY8NMLV-gA"));
            assertEquals(ADDED, add(ids, 255, "EAg7HEEzRdy37rY8NMLV-gA"));
            assertEquals(3, ids.fallback().size());
        }
    }

    @Test
    public void detectsPatterns() {
        assertEquals(Pattern.decimal(), detect(i -> "NG" + (60414976 + i * 3 / 2)));
        assertEquals(Pattern.decimal(), detect(i -> "1698732896532347689_" + (2053 + i)));
        assertEquals(Pattern.decimal(), detect(i -> "11-" + (1312809818L + i * 259L)));
        assertEquals(Pattern.base36(12), detect(i -> "00526749220LPS" + base36(5641 + i, 7)));
        assertEquals(Pattern.base36(12), detect(i -> "12345678901XYZ" + base36(i, 7)));
        assertEquals(Pattern.decimal(3), detect(i -> "ARB%08dX7Y".formatted(45612 + i)));
        assertEquals(Pattern.decimal(1), detect(i -> "ORD-" + (1000 + i * 7) + "B"));
        final SplittableRandom random = new SplittableRandom(9);
        assertEquals(Pattern.none(), detect(i -> "ARB%08d".formatted(45612 + i) + randomString(random, 3)));
        assertEquals(Pattern.none(), detect(i -> uuid(random) + "A"));
        assertEquals(Pattern.none(), detect(i -> randomString(random, 20) + random.nextInt(10)));
        assertEquals(Pattern.none(), detect(i -> "" + random.nextLong(1_000_000_000_000_000L)));
    }

    @Test
    public void duplicatesFoundWhileAndAfterDetecting() {
        try (ClientOrderIdSet ids = new ClientOrderIdSet()) {
            for (int i = 0; i < DETECTION_IDS - 1; ++i) {
                assertEquals(ADDED, add(ids, 9, "ORD-" + i));
                assertEquals(DUPLICATE, add(ids, 9, "ORD-" + i));
            }
            assertEquals(Pattern.auto(), ids.pattern(9));
            assertTrue(contains(ids, 9, "ORD-0"));
            assertEquals(DETECTION_IDS - 1, ids.size());
            assertEquals(ADDED, add(ids, 9, "ORD-" + (DETECTION_IDS - 1)));
            assertEquals(Pattern.decimal(), ids.pattern(9));
            assertEquals(DETECTION_IDS, ids.counters().size());
            assertEquals(DETECTION_IDS, ids.size());
            for (int i = 0; i < DETECTION_IDS; ++i) {
                assertEquals(DUPLICATE, add(ids, 9, "ORD-" + i));
            }
            assertEquals(ADDED, add(ids, 9, "ORD-" + DETECTION_IDS));
            assertEquals(ADDED, add(ids, 9, "odd one"));
            assertTrue(contains(ids, 9, "odd one"));
            assertEquals(1, ids.fallback().size());
            assertThrows(IllegalStateException.class, () -> ids.pattern(9, Pattern.none()));

            ids.rollover();
            assertEquals(Pattern.auto(), ids.pattern(9));
            assertFalse(contains(ids, 9, "ORD-0"));
            ids.pattern(9, Pattern.none());
            assertEquals(Pattern.none(), ids.pattern(9));
            assertEquals(ADDED, add(ids, 9, "ORD-0"));
            assertEquals(1, ids.fallback().size());
        }
    }

    private static Pattern detect(final java.util.function.IntFunction<String> ids) {
        try (ClientOrderIdSet set = new ClientOrderIdSet()) {
            for (int i = 0; i < DETECTION_IDS; ++i) {
                assertEquals(ADDED, add(set, 0, ids.apply(i)));
            }
            return set.pattern(0);
        }
    }

    private static String base36(long value, final int width) {
        final char[] chars = new char[width];
        for (int i = width - 1; i >= 0; --i) {
            chars[i] = Character.toUpperCase(Character.forDigit((int) (value % 36), 36));
            value /= 36;
        }
        return new String(chars);
    }

    private static String uuid(final SplittableRandom random) {
        final byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String randomString(final SplittableRandom random, final int length) {
        final String alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        final StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; ++i) {
            builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return builder.toString();
    }

    @Test
    public void randomIdsEndingInDigitsDemoteTheScope() {
        try (ClientOrderIdSet ids = new ClientOrderIdSet()) {
            ids.pattern(0, Pattern.decimal());
            for (int i = 1; i <= 5_000; ++i) {
                assertEquals(ADDED, add(ids, 0, "AB" + i));
            }
            final SplittableRandom random = new SplittableRandom(7);
            final String[] randomIds = new String[50_000];
            for (int i = 0; i < randomIds.length; ++i) {
                randomIds[i] = randomString(random, 12) + i % 10;
                assertEquals(ADDED, add(ids, 0, randomIds[i]));
            }
            assertTrue(ids.demoted(0));
            final int budget = CONTAINER_BURST * IDS_PER_CONTAINER / (IDS_PER_CONTAINER - 1);
            assertTrue(ids.counters().containers() <= 4 + budget + 1);
            assertTrue(ids.fallback().size(0) >= randomIds.length - budget - 1);
            for (final String id : randomIds) {
                assertTrue(contains(ids, 0, id));
                assertEquals(DUPLICATE, add(ids, 0, id));
            }

            // existing containers keep counting, a new one goes to the fallback set
            final long counters = ids.counters().size();
            assertEquals(ADDED, add(ids, 0, "AB5001"));
            assertEquals(counters + 1, ids.counters().size());
            assertEquals(ADDED, add(ids, 0, "AB1000000"));
            assertEquals(DUPLICATE, add(ids, 0, "AB1000000"));
            assertEquals(counters + 1, ids.counters().size());
            assertTrue(contains(ids, 0, "AB1000000"));
            assertFalse(contains(ids, 0, "AB1000001"));
            assertEquals(5_000 + randomIds.length + 2, ids.size());
            assertFalse(ids.demoted(1));

            ids.rollover();
            assertFalse(ids.demoted(0));
        }
    }

    @Test
    public void sparseNumbersDemoteTheScope() {
        try (ClientOrderIdSet ids = new ClientOrderIdSet()) {
            ids.pattern(3, Pattern.decimal());
            for (long i = 0; i < 20_000; ++i) {
                assertEquals(ADDED, add(ids, 3, "X" + (1_000_000_000L + i * 100_000)));
            }
            assertTrue(ids.demoted(3));
            assertTrue(ids.counters().containers() < 6_000);
            for (long i = 0; i < 20_000; ++i) {
                assertTrue(contains(ids, 3, "X" + (1_000_000_000L + i * 100_000)));
                assertFalse(contains(ids, 3, "X" + (1_000_000_001L + i * 100_000)));
            }
        }
    }

    @Test
    public void manyCountersStayCounters() {
        try (ClientOrderIdSet ids = new ClientOrderIdSet()) {
            ids.pattern(0, Pattern.decimal());
            final int traders = 3_000;
            for (int i = 0; i < 300 * traders; ++i) {
                assertEquals(ADDED, add(ids, 0, "T" + i % traders + "-" + i / traders));
            }
            assertFalse(ids.demoted(0));
            assertEquals(300 * traders, ids.counters().size());
            assertEquals(3 * traders, ids.counters().containers());
        }
    }

    @Test
    public void scopesAreBytes() {
        try (ClientOrderIdSet ids = new ClientOrderIdSet()) {
            assertEquals(ADDED, add(ids, 0, "ORD-1"));
            assertEquals(ADDED, add(ids, MAX_SCOPES - 1, "ORD-1"));
            for (final int scope : new int[]{-1, MAX_SCOPES, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
                assertThrows(IllegalArgumentException.class, () -> add(ids, scope, "ORD-1"));
                assertThrows(IllegalArgumentException.class, () -> contains(ids, scope, "ORD-1"));
                assertThrows(IllegalArgumentException.class, () -> ids.pattern(scope, Pattern.none()));
            }
        }
    }

    @Test
    public void patternFixedUntilRollover() {
        try (ClientOrderIdSet ids = new ClientOrderIdSet()) {
            ids.pattern(3, Pattern.decimal());
            add(ids, 3, "ORD-1");
            assertThrows(IllegalStateException.class, () -> ids.pattern(3, Pattern.none()));
            ids.pattern(4, Pattern.none());
            ids.rollover();
            ids.pattern(3, Pattern.none());
            assertEquals(ADDED, add(ids, 3, "ORD-1"));
            assertEquals(1, ids.fallback().size());
        }
    }

    @Test
    public void onlyPrintableIds() {
        try (ClientOrderIdSet ids = new ClientOrderIdSet()) {
            assertEquals(ADDED, add(ids, 0, " !~ORD 1"));
            for (final String id : new String[]{"", "ORD\u00011", "ORD\u007f", "ORD\t1", "ORD-\u00e91"}) {
                final byte[] bytes = id.getBytes(StandardCharsets.ISO_8859_1);
                assertThrows(IllegalArgumentException.class, () -> ids.add(0, bytes, 0, bytes.length), id);
                assertThrows(IllegalArgumentException.class, () -> ids.contains(0, bytes, 0, bytes.length), id);
            }
            assertEquals(1, ids.size());
        }
    }

    @Test
    public void uniqueWithinTradingDay() {
        try (ClientOrderIdSet ids = new ClientOrderIdSet()) {
            assertEquals(ADDED, add(ids, 0, "ORD-1"));
            assertEquals(ADDED, add(ids, 0, "ORD-2"));
            assertEquals(DUPLICATE, add(ids, 0, "ORD-1"));
            ids.rollover();
            assertEquals(0, ids.size());
            assertFalse(contains(ids, 0, "ORD-1"));
            assertEquals(ADDED, add(ids, 0, "ORD-1"));
            assertEquals(DUPLICATE, add(ids, 0, "ORD-1"));
        }
    }

    @Test
    public void maxLength() {
        try (ClientOrderIdSet ids = new ClientOrderIdSet()) {
            assertEquals(ADDED, add(ids, 0, "A".repeat(MAX_LENGTH - 18) + "1".repeat(18)));
            assertThrows(IllegalArgumentException.class, () -> add(ids, 0, "1".repeat(MAX_LENGTH + 1)));
        }
    }

    @Test
    public void closeFreesSet() {
        final ClientOrderIdSet ids = new ClientOrderIdSet();
        ids.pattern(0, Pattern.decimal());
        add(ids, 0, "ORD-1");
        add(ids, 0, "X-Y");
        assertEquals(new ClientOrderIdSet.Statistics(1, 1, 1, 1, ids.statistics().heapBytes(),
            ids.statistics().offHeapBytes()), ids.statistics());
        ids.close();
        ids.close();
        assertThrows(IllegalStateException.class, () -> add(ids, 0, "ORD-2"));
        assertThrows(IllegalStateException.class, () -> contains(ids, 0, "ORD-1"));
        assertThrows(IllegalStateException.class, ids::rollover);
    }

    private static int numberLength(final Pattern pattern, final String id) {
        return pattern.numberLength(bytes(id), 0, id.length());
    }

    private static int add(final ClientOrderIdSet ids, final int scope, final String id) {
        return ids.add(scope, bytes(id), 0, id.length());
    }

    private static boolean contains(final ClientOrderIdSet ids, final int scope, final String id) {
        return ids.contains(scope, bytes(id), 0, id.length());
    }

    private static byte[] bytes(final String string) {
        return string.getBytes(StandardCharsets.US_ASCII);
    }
}
