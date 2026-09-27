package org.limitless.radix4j;

import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@State(Scope.Thread)
public class BaseBenchmark {

    public static final int SIZE = 25_000_000;

    @Param({"sequential", "random", "order", "sparse", "session", "base36", "base64"})
    public String dataSet;

    /** Strings stored back to back; string i is at offsets[i] with length offsets[i + 1] - offsets[i]. */
    static byte[] strings;
    static int[] offsets;

    /** Longest prefix shared by all strings, or the first byte of the first string when there is none. */
    static byte[] prefix;
    static String prefixString;

    @Setup(Level.Trial)
    public void generateStrings() {
        final DataSet.Generator generator = DataSet.of(dataSet).generator();
        final byte[] string = new byte[DataSet.MAX_LENGTH];
        final byte[] buffer = new byte[SIZE * DataSet.MAX_LENGTH];
        offsets = new int[SIZE + 1];
        int offset = 0;
        for (int i = 0; i < SIZE; ++i) {
            final int length = generator.next(string);
            System.arraycopy(string, 0, buffer, offset, length);
            offsets[i] = offset;
            offset += length;
        }
        offsets[SIZE] = offset;
        strings = Arrays.copyOf(buffer, offset);

        int prefixLength = length(0);
        for (int i = 1; i < SIZE && prefixLength > 1; ++i) {
            final int mismatch = Arrays.mismatch(strings, 0, prefixLength, strings, offsets[i], offsets[i + 1]);
            if (mismatch >= 0) {
                prefixLength = Math.min(prefixLength, mismatch);
            }
        }
        prefix = Arrays.copyOf(strings, Math.max(prefixLength, 1));
        prefixString = new String(prefix, StandardCharsets.ISO_8859_1);
    }

    static int length(final int index) {
        return offsets[index + 1] - offsets[index];
    }

    public static class BaseState {
        int success;
        int failed;
        int index;

        void setup() {
            success = 0;
            failed = 0;
            index = 0;
        }

        void tearDown() {
            if (failed >= 1) {
                System.out.println("success = " + success + " failed = " + failed);
            }
        }

        int position() {
            return offsets[index];
        }

        int length() {
            return offsets[index + 1] - offsets[index];
        }

        boolean updateStats(final boolean result) {
            index = index + 1 == SIZE ? 0 : index + 1;
            if (result) {
                ++success;
            } else {
                ++failed;
            }
            return result;
        }
    }
}
