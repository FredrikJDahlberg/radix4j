package org.limitless.radix4j;

import org.limitless.fsmp4j.BlockFlyweight;
import org.limitless.fsmp4j.ByteUtils;

import java.lang.foreign.MemorySegment;

public class Node14 extends BlockFlyweight {

    protected static final int NOT_FOUND = -1;
    protected static final int EMPTY_BLOCK = 0;
    protected static final byte EMPTY_KEY = 0;

    protected static final int MAX_STRING_LENGTH = 7;
    protected static final int MAX_INDEX_COUNT = 14;

    private static final int BLOCK_OFFSET_BITS = 0;
    private static final int BLOCK_LENGTH_BITS = 16;
    private static final int SEGMENT_OFFSET_BITS = BLOCK_OFFSET_BITS + BLOCK_LENGTH_BITS;
    private static final int SEGMENT_LENGTH_BITS = 8;

    private static final int BLOCK_MASK = -1 >>> (Integer.SIZE - BLOCK_LENGTH_BITS);
    private static final int SEGMENT_MASK = -1 >>> (Integer.SIZE - SEGMENT_LENGTH_BITS);

    // node byte layout
    protected static final int HEADER_OFFSET = 0;
    protected static final int HEADER_LENGTH = 1;
    protected static final int STRING_OFFSET = HEADER_OFFSET + HEADER_LENGTH;
    protected static final int STRING_LENGTH = 7;
    protected static final int INDEX_OFFSET = STRING_OFFSET + STRING_LENGTH;
    protected static final int INDEX_LENGTH = MAX_INDEX_COUNT * Index.BYTES;
    protected static final int BYTES = INDEX_OFFSET + INDEX_LENGTH;

    public int offset() {
        return ((segment() & 0xff) << 16) | (super.block() & 0x0000ffff);
    }

    public Node14 wrap(Node14 node) {
        wrap(node.memorySegment(), node.segment(), node.block());
        return this;
    }

    @Override
    public int encodedLength() {
        return BYTES;
    }

    public int mismatch(final byte[] string, final int stringOffset, final int stringLength) {
        final byte header = header();
        final int nodeLength = Header.stringLength(header);
        final int remainingString = stringLength - stringOffset;
        final int remaining = Math.min(remainingString, nodeLength);
        long str = nativeLong(HEADER_OFFSET);
        str >>>= 8;
        for (int i = 0; i < remaining; ++i) {
            if ((str & Index.KEY_MASK) != string[i + stringOffset]) {
                return i;
            }
            str >>>= 8;
        }
        if (remainingString == nodeLength && Header.completeString(header)) {
            return -1;
        }
        return remaining;
    }

    public static long addressFromOffset(final int offset) {
        int segment = ((offset >>> SEGMENT_OFFSET_BITS) & SEGMENT_MASK) + 1;
        int block = (offset >>> BLOCK_OFFSET_BITS) & BLOCK_MASK;
        return ByteUtils.pack(segment, block);
    }

    public byte header() {
        return nativeByte(HEADER_OFFSET);
    }

    public Node14 header(final byte value) {
        nativeByte(HEADER_OFFSET, value);
        return this;
    }

    public Node14 header(final int length, final boolean complete, final int count) {
        byte header;
        header = Header.stringLength(0, length);
        header = Header.indexCount(header, count);
        header = Header.completeString(header, complete);
        header(header);
        return this;
    }

    public int index(final int position) {
        return nativeInt(INDEX_OFFSET + position * Integer.BYTES);
    }

    public Node14 index(final int position, final int index) {
        nativeInt(INDEX_OFFSET + position * Integer.BYTES, index);
        return this;
    }

    public Node14 index(final int position, final byte key, final int block, boolean complete) {
        final int index = Index.key(0, key) | Index.completeKey(0, complete) |
            Index.offset(0, block);
        nativeInt(INDEX_OFFSET + position * Integer.BYTES, index);
        return this;
    }

    protected void string(final byte[] string, final int position, final int stringLength) {
        final int length = Math.min(STRING_LENGTH, stringLength);
        header(Header.stringLength(header(), length));
        if (length >= 1) {
            nativeByteArray(position, string, STRING_OFFSET, length);
        }
    }

    public void string(byte[] string) {
        final byte header = header();
        nativeByteArray(STRING_OFFSET, Header.stringLength(header), string);
    }

    public byte string(int position) {
        return nativeByte(STRING_OFFSET + position);
    }

    public void string(int position, byte ch) {
        nativeByte(STRING_OFFSET + position, ch);
    }

    protected void moveString(final int position, final int length) {
        if (length >= 1) {
            long stringOffset = fieldOffset(STRING_OFFSET);
            MemorySegment.copy(memorySegment(), stringOffset + position,
                memorySegment(), stringOffset, length);
        }
        header(Header.stringLength(header(), length));
    }

    public void copy(final Node14 srcNode) {
        MemorySegment.copy(srcNode.memorySegment(), srcNode.fieldOffset(0),
            this.memorySegment(), this.fieldOffset(0), BYTES);
    }

    public void removeIndex(int position) {
        final byte header = header();
        final int newCount = Header.indexCount(header) - 1;
        if (position != newCount) {
            index(position, index(newCount));
        }
        header(Header.indexCount(header, newCount));
    }

    public int position(final int count, final byte key) {
        if (count == 0) {
            return NOT_FOUND;
        }
        int found = NOT_FOUND;
        for (int i = 0; i < count; ++i) {
            final int index = index(i);
            final byte indexKey = Index.key(index);
            if (indexKey == key) {
                return i;
            }
            if (indexKey == EMPTY_KEY) {
                found = i;
            }
        }
        return found;
    }

    @Override
    public StringBuilder append(StringBuilder builder) {
        byte header = header();
        builder.setLength(0);
        builder.append("{Node").append(segment()).append('#').append(block()).append(", \"");
        final int stringLength = Header.stringLength(header);
        if (stringLength >= 1) {
            final byte[] bytes = new byte[stringLength];
            nativeByteArray(STRING_OFFSET, stringLength, bytes);
            builder.append(new String(bytes, 0, stringLength));
        }
        builder.append('\"');
        if (Header.completeString(header)) {
            builder.append('!');
        }

        final int count = Header.indexCount(header);
        if (count >= 1) {
            builder.append(" [");
            for (int i = 0; i < count; ++i) {
                final int index = index(i);
                builder.append((char) Index.key(index));
                if (Index.completeKey(index)) {
                    builder.append('!');
                } else {
                    builder.append('=').append(Index.offset(index));
                }
                builder.append(',').append(' ');
            }
            builder.append(']');
        }
        return builder.append('}');
    }

    @Override
    public String toString() {
        return append(new StringBuilder(64)).toString();
    }

    // header helpers
    public static final class Header {

        // bit layout
        private static final int STRING_COMPLETE_OFFSET = 0;
        private static final int STRING_COMPLETE_LENGTH = 1;
        private static final int STRLEN_OFFSET = STRING_COMPLETE_OFFSET + STRING_COMPLETE_LENGTH;
        private static final int STRLEN_LENGTH = 3;
        private static final int INDEX_COUNT_OFFSET = STRLEN_OFFSET + STRLEN_LENGTH;
        private static final int INDEX_COUNT_LENGTH = 4;

        private static final byte STRING_COMPLETE_MASK = 0xff >>> (Byte.SIZE - STRING_COMPLETE_LENGTH);
        private static final byte INDEX_COUNT_MASK = 0xff >>> (Byte.SIZE - INDEX_COUNT_LENGTH);
        private static final byte STRLEN_MASK = 0xff >>> (Byte.SIZE - STRLEN_LENGTH);

        public static boolean completeString(final byte header) {
            return ((header >>> STRING_COMPLETE_OFFSET) & STRING_COMPLETE_MASK) != 0;
        }

        public static byte completeString(final byte header, final boolean value) {
            if (value) {
                return (byte) (header | (STRING_COMPLETE_MASK << STRING_COMPLETE_OFFSET));
            } else {
                return (byte) (header & ~(STRING_COMPLETE_MASK << STRING_COMPLETE_OFFSET));
            }
        }

        public static int indexCount(final int header) {
            return (header >>> INDEX_COUNT_OFFSET) & INDEX_COUNT_MASK;
        }

        public static byte indexCount(final int header, final int count) {
            return (byte) ((header & ~(INDEX_COUNT_MASK << INDEX_COUNT_OFFSET)) | ((count & INDEX_COUNT_MASK) << INDEX_COUNT_OFFSET));
        }

        public static int stringLength(final int header) {
            return (header >>> STRLEN_OFFSET) & STRLEN_MASK;
        }

        public static byte stringLength(final int header, final int length) {
            return (byte) ((header & ~(STRLEN_MASK << STRLEN_OFFSET)) | ((length & STRLEN_MASK) << STRLEN_OFFSET));
        }
    }

    // index helpers
    public static final class Index {

        // index bit layout
        private static final int BLOCK_OFFSET = 0;
        private static final int BLOCK_LENGTH = 24;
        private static final int KEY_OFFSET = BLOCK_OFFSET + BLOCK_LENGTH;
        private static final int KEY_LENGTH = MAX_STRING_LENGTH;
        private static final int KEY_COMPLETE_OFFSET = KEY_OFFSET + KEY_LENGTH;
        private static final int KEY_COMPLETE_LENGTH = 1;

        private static final int INDEX_LENGTH = KEY_COMPLETE_OFFSET + KEY_COMPLETE_LENGTH;
        private static final int BYTES = INDEX_LENGTH / Byte.SIZE;

        private static final int KEY_COMPLETE_MASK = -1 >>> (Integer.SIZE - KEY_COMPLETE_LENGTH);
        private static final int KEY_MASK = -1 >>> (Integer.SIZE - KEY_LENGTH);
        private static final int BLOCK_MASK = -1 >>> (Integer.SIZE - BLOCK_LENGTH);

        public static boolean completeKey(final int index) {
            return (index & (KEY_COMPLETE_MASK << KEY_COMPLETE_OFFSET)) != 0;
        }

        public static int completeKey(int index, boolean value) {
            if (value) {
                return index | (KEY_COMPLETE_MASK << KEY_COMPLETE_OFFSET);
            } else {
                return index & ~(KEY_COMPLETE_MASK << KEY_COMPLETE_OFFSET);
            }
        }

        public static int offset(final int index) {
            return (index >>> BLOCK_OFFSET) & BLOCK_MASK;
        }

        public static int offset(final int segment, final int block) {
            return (segment & ~(BLOCK_MASK << BLOCK_OFFSET)) | ((block & BLOCK_MASK) << BLOCK_OFFSET);
        }

        public static byte key(final int index) {
            return (byte) ((index >>> KEY_OFFSET) & KEY_MASK);
        }

        public static int key(final int index, final byte key) {
            return (index & ~(KEY_MASK << KEY_OFFSET) | ((key & KEY_MASK) << KEY_OFFSET));
        }
    }
}
