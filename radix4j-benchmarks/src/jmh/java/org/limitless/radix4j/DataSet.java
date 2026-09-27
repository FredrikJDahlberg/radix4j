package org.limitless.radix4j;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * Generated string data sets used by the benchmarks and {@link MemoryComparison}.
 * <ul>
 *   <li>sequential: 32 bytes, a 20-byte prefix followed by a zero padded counter</li>
 *   <li>random: 32 random alphanumeric characters</li>
 *   <li>order: 10 bytes, 2 letters and an 8-digit counter that skips ahead 2-8 one time in three</li>
 *   <li>sparse: 13 bytes, a 3-byte prefix and a 10-digit number increasing by 1-512</li>
 *   <li>session: 21-24 bytes, a 19-digit session id, '_' and a counter from 1, new session every 5,000</li>
 *   <li>base36: 21 bytes, an 11-digit session id, 3 letters and a 7-character base-36 counter,
 *       new session every 1,000,000</li>
 *   <li>base64: 23 bytes, 16 random bytes in unpadded base64url followed by 'A'</li>
 * </ul>
 * The last five model real order and quote ids. The next six model Nasdaq US order ids, at most 20 bytes (Nasdaq
 * Nordic and Nordic Growth Market allow 14), each a strategy prefix and a counter from 1 starting at the sample id:
 * <ul>
 *   <li>arb: 14 bytes, {@code ARB00045612X7Y}, an 8-digit counter and {@code X7Y}</li>
 *   <li>mmk: 14 bytes, {@code MMK39281A510BB}, an 11-character base-36 counter</li>
 *   <li>retse: 14 bytes, {@code RETSE001928344}, a 9-digit counter</li>
 *   <li>vwap: 20 bytes, {@code VWAP9820154320987654}, a 16-digit counter</li>
 *   <li>dma: 19 bytes, {@code DMAXYZ1048596203948}, a 13-digit counter</li>
 *   <li>spdopt: 19 bytes, {@code SPDOPT7492019485736}, a 13-digit counter</li>
 *   <li>nasdaq: the six above mixed, a random strategy per id, each with its own counter</li>
 * </ul>
 */
public enum DataSet {
    SEQUENTIAL, RANDOM, ORDER, SPARSE, SESSION, BASE36, BASE64, ARB, MMK, RETSE, VWAP, DMA, SPDOPT, NASDAQ;

    public static final int MAX_LENGTH = 32;

    private static final byte[] PREFIX = "CUSTOMER-ORDER-2026-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ALPHABET =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.US_ASCII);
    private static final int SESSION_SIZE = 5_000;
    private static final int BASE36_SESSION_SIZE = 1_000_000;
    private static final byte[][] NASDAQ_PREFIXES = {
        bytes("ARB"), bytes("MMK"), bytes("RETSE"), bytes("VWAP"), bytes("DMAXYZ"), bytes("SPDOPT")
    };
    private static final int[] NASDAQ_WIDTHS = {8, 11, 9, 16, 13, 13};
    private static final long[] NASDAQ_STARTS = {
        45_612L, Long.parseLong("39281A510BB", 36), 1_928_344L, 9_820_154_320_987_654L, 1_048_596_203_948L,
        7_492_019_485_736L
    };

    private static byte[] bytes(final String string) {
        return string.getBytes(StandardCharsets.US_ASCII);
    }

    public static DataSet of(final String name) {
        return valueOf(name.toUpperCase(Locale.ROOT));
    }

    /**
     * Returns a generator of the strings in this data set. Generators with the same data set produce the
     * same strings in the same order.
     * @return generator
     */
    public Generator generator() {
        return new Generator(this);
    }

    public static final class Generator {
        private final DataSet dataSet;
        private final SplittableRandom random = new SplittableRandom(42);
        private final byte[] randomBytes = new byte[16];
        private int index;
        private long counter;
        private long session;
        private final long[] nasdaq = NASDAQ_STARTS.clone();

        private Generator(final DataSet dataSet) {
            this.dataSet = dataSet;
        }

        /**
         * Writes the next string to the start of the buffer.
         * @param string buffer of at least {@link #MAX_LENGTH} bytes
         * @return string length
         */
        public int next(final byte[] string) {
            final int length = switch (dataSet) {
                case SEQUENTIAL -> sequentialString(string);
                case RANDOM -> randomString(string);
                case ORDER -> orderString(string);
                case SPARSE -> sparseString(string);
                case SESSION -> sessionString(string);
                case BASE36 -> base36String(string);
                case BASE64 -> base64String(string);
                case ARB, MMK, RETSE, VWAP, DMA, SPDOPT -> nasdaqString(dataSet.ordinal() - ARB.ordinal(), string);
                case NASDAQ -> nasdaqString(random.nextInt(NASDAQ_PREFIXES.length), string);
            };
            ++index;
            return length;
        }

        private int sequentialString(final byte[] string) {
            System.arraycopy(PREFIX, 0, string, 0, PREFIX.length);
            return PREFIX.length + digits(index, MAX_LENGTH - PREFIX.length, string, PREFIX.length);
        }

        private int randomString(final byte[] string) {
            for (int i = 0; i < MAX_LENGTH; ++i) {
                string[i] = ALPHABET[random.nextInt(ALPHABET.length)];
            }
            return MAX_LENGTH;
        }

        private int orderString(final byte[] string) {
            counter += random.nextInt(3) == 0 ? random.nextInt(2, 9) : 1;
            string[0] = 'A';
            string[1] = 'B';
            return 2 + digits(10_000_000L + counter, 8, string, 2);
        }

        private int sparseString(final byte[] string) {
            counter += random.nextInt(1, 513);
            string[0] = '2';
            string[1] = '2';
            string[2] = '-';
            return 3 + digits(1_300_000_000L + counter, 10, string, 3);
        }

        private int sessionString(final byte[] string) {
            if (index % SESSION_SIZE == 0) {
                session += random.nextInt(1, 20_001);
            }
            int length = digits(1_700_000_000_000_000_000L + session, 19, string, 0);
            string[length++] = '_';
            final long value = index % SESSION_SIZE + 1;
            return length + digits(value, Long.toString(value).length(), string, length);
        }

        private int base36String(final byte[] string) {
            if (index % BASE36_SESSION_SIZE == 0) {
                session = random.nextLong(10_000_000_000L);
            }
            int length = digits(session, 11, string, 0);
            string[length++] = 'X';
            string[length++] = 'Y';
            string[length++] = 'Z';
            return length + base36(index % BASE36_SESSION_SIZE, 7, string, length);
        }

        private int base64String(final byte[] string) {
            random.nextBytes(randomBytes);
            final byte[] encoded = Base64.getUrlEncoder().withoutPadding().encode(randomBytes);
            System.arraycopy(encoded, 0, string, 0, encoded.length);
            string[encoded.length] = 'A';
            return encoded.length + 1;
        }

        private int nasdaqString(final int strategy, final byte[] string) {
            final byte[] prefix = NASDAQ_PREFIXES[strategy];
            final int width = NASDAQ_WIDTHS[strategy];
            final long value = nasdaq[strategy]++;
            System.arraycopy(prefix, 0, string, 0, prefix.length);
            if (strategy == 1) {
                return prefix.length + base36(value, width, string, prefix.length);
            }
            int length = prefix.length + digits(value, width, string, prefix.length);
            if (strategy == 0) {
                string[length++] = 'X';
                string[length++] = '7';
                string[length++] = 'Y';
            }
            return length;
        }

        private static int base36(long value, final int count, final byte[] string, final int offset) {
            for (int i = offset + count - 1; i >= offset; --i) {
                string[i] = ALPHABET[(int) (value % 36)];
                value /= 36;
            }
            return count;
        }

        private static int digits(long value, final int count, final byte[] string, final int offset) {
            for (int i = offset + count - 1; i >= offset; --i) {
                string[i] = (byte) ('0' + value % 10);
                value /= 10;
            }
            return count;
        }
    }
}
