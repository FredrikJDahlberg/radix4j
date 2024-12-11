package org.limitless.radix4j;

public class BaseBenchmark {

    public static final byte[] STRING = "ABCDEFGHI000000000".getBytes();
    public static final int STRING_LENGTH = STRING.length;

    public static final int SIZE = 25_000_000;
    public static final int MAX = SIZE * STRING_LENGTH;
    public static final byte[] strings = new byte[SIZE * STRING_LENGTH];

    public BaseBenchmark() {
        int offset = 0;
        for (int i = 0; i < SIZE; ++i) {
            System.arraycopy(STRING, 0, strings, offset, STRING_LENGTH);
            offset += STRING_LENGTH;
            ByteUtils.intToChars(i, offset, STRING_LENGTH, strings);
        }
    }

    public static class BaseState {
        int success;
        int failed;
        int stringOffset;

        void setup() {
            success = 0;
            failed = 0;
            stringOffset = 0;
        }

        void tearDown() {
            if (failed >= 1) {
                System.out.println("HashSet: success = " + success + " failed = " + failed);
            }
        }

        boolean updateStats(final boolean result) {
            stringOffset = (stringOffset + STRING_LENGTH) % MAX;
            if (result) {
                ++success;
            } else {
                ++failed;
            }
            return result;
        }
    }
}
