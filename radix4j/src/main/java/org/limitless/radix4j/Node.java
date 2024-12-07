package org.limitless.radix4j;

import org.limitless.fsmp4j.BlockFlyweight;

import java.lang.foreign.MemorySegment;

public class Node extends BlockFlyweight {

    protected static final int NOT_FOUND = -1;
    protected static final int EMPTY_BLOCK = 0;
    protected static final byte EMPTY_KEY = 0;

    protected static final int INDEX_COUNT = 11;
    protected static final int KEY_LENGTH = 1;

    // node byte layout
    protected static final int HEADER_OFFSET = 0;
    protected static final int HEADER_LENGTH = 1;
    protected static final int STRING_OFFSET = HEADER_OFFSET + HEADER_LENGTH;
    protected static final int STRING_LENGTH = 5;
    protected static final int INCLUDE_OFFSET = STRING_OFFSET + STRING_LENGTH;
    protected static final int INCLUDE_LENGTH = 2;
    protected static final int INDEX_OFFSET = INCLUDE_OFFSET + INCLUDE_LENGTH;
    protected static final int INDEX_LENGTH = INDEX_COUNT * Integer.BYTES;
    protected static final int KEYS_OFFSET = INDEX_OFFSET + INDEX_LENGTH;
    protected static final int KEYS_LENGTH = INDEX_COUNT * KEY_LENGTH;
    protected static final int PAD_OFFSET = KEYS_OFFSET + KEYS_LENGTH;
    protected static final int PAD_LENGTH = 1;
    protected static final int BYTES = PAD_OFFSET + PAD_LENGTH;

    private static final int KEY_MASK = 0xff;

    public int offset() {
        return (int) Address.toOffset(segment(), super.block());
    }

    public Node wrap(Node node) {
        wrap(node.memorySegment(), node.segment(), node.block());
        return this;
    }

    @Override
    public int encodedLength() {
        return BYTES;
    }

    public int mismatch(final int offset, final int length, final byte[] string) {
        long stringValue = nativeLong(HEADER_OFFSET);
        final byte header = (byte) (stringValue & 0xff);
        final int nodeLength = Header.stringLength(header);
        final int remaining = Math.min(length, nodeLength);
        stringValue >>>= 8;
        for (int i = 0; i < remaining; ++i) {
            if ((stringValue & KEY_MASK) != string[i + offset]) {
                return i;
            }
            stringValue >>>= 8;
        }
        if (length == nodeLength && Header.includeString(header)) {
            return -1;
        }
        return remaining;
    }

    public Node addIndex(final byte key, final int offset, boolean complete) {
        final byte header = header();
        final int count = Header.indexCount(header);
        header(Header.indexCount(header, count + 1));
        index(count, key, offset, complete);
        return this;
    }

    public void removeIndex(int position) {
        final byte header = header();
        final int newCount = Header.indexCount(header) - 1;
        if (position != newCount) {
            key(position, key(newCount));
            includeKey(position, includeKey(newCount));
            index(position, index(newCount));
        }
        header(Header.indexCount(header, newCount));
    }

    public byte header() {
        return nativeByte(HEADER_OFFSET);
    }

    public void header(final byte value) {
        nativeByte(HEADER_OFFSET, value);
    }

    public Node header(final int length, final boolean complete, final int count) {
        byte header;
        header = Header.stringLength(0, length);
        header = Header.indexCount(header, count);
        header = Header.includeString(header, complete);
        header(header);
        return this;
    }

    public byte key(final int position) {
        return nativeByte(KEYS_OFFSET + position);
    }

    public void key(final int position, final byte value) {
        nativeByte(KEYS_OFFSET + position, value);
    }

    public boolean includeKey(final int position) {
        return (nativeByte(INCLUDE_OFFSET + position / Byte.SIZE) & (1 << position % Byte.SIZE)) != 0;
    }

    public void includeKey(final int position, final boolean include) {
        final int index = position / Byte.SIZE;
        byte flag = (byte) (1 << (position % Byte.SIZE));
        byte includes = nativeByte(INCLUDE_OFFSET + index);
        if (include) {
            includes |= flag;
        } else {
            includes &= (byte) ~flag;
        }
        nativeByte(INCLUDE_OFFSET + index, includes);
    }

    public int index(final int position) {
        return nativeInt(INDEX_OFFSET + position * Integer.BYTES);
    }

    public Node index(final int position, final int index) {
        nativeInt(INDEX_OFFSET + position * Integer.BYTES, index);
        return this;
    }

    public void index(final int position, final byte key, final int block, boolean include) {
        key(position, key);
        index(position, block);
        includeKey(position, include);
    }

    public int keyPosition(final int count, final byte key) {
        int found = NOT_FOUND;
        for (int i = 0; i < count; ++i) {
            final byte nodeKey = key(i);
            if (nodeKey == key) {
                return i;
            }
            if (nodeKey == EMPTY_KEY) {
                found = i;
            }
        }
        return found;
    }

    public void string(final byte[] string, final int position, final int stringLength) {
        final int length = Math.min(STRING_LENGTH, stringLength);
        if (length >= 1) {
            nativeByteArray(position, string, STRING_OFFSET, length);
        }
    }

    public void string(final byte[] string) {
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

    public void copy(final Node srcNode) {
        MemorySegment.copy(srcNode.memorySegment(), srcNode.fieldOffset(0),
            this.memorySegment(), this.fieldOffset(0), BYTES);
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
        if (Header.includeString(header)) {
            builder.append('!');
        }

        final int count = Header.indexCount(header);
        if (count >= 1) {
            builder.append(" [");
            for (int i = 0; i < count; ++i) {
                builder.append((char) key(i));
                if (includeKey(i)) {
                    builder.append('!');
                } else {
                    builder.append('=').append(index(i));
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

    public static final class Address {

        // address bit layout
        private static final int BLOCK_OFFSET_BITS = 0;
        private static final int BLOCK_LENGTH_BITS = 16;
        private static final int SEGMENT_OFFSET_BITS = BLOCK_OFFSET_BITS + BLOCK_LENGTH_BITS;
        private static final int SEGMENT_LENGTH_BITS = 16;
        private static final int SIZE = SEGMENT_OFFSET_BITS + SEGMENT_LENGTH_BITS;

        private static final int BLOCK_MASK = -1 >>> (Integer.SIZE - BLOCK_LENGTH_BITS);
        private static final int SEGMENT_MASK = -1 >>> (Integer.SIZE - SEGMENT_LENGTH_BITS);

        public static final int MAX_BLOCKS = 1 << BLOCK_LENGTH_BITS;
        public static final int MAX_SEGMENTS = 1 << SEGMENT_LENGTH_BITS;

        public static long fromOffset(final int offset) {
            int segment = ((offset >>> SEGMENT_OFFSET_BITS) & SEGMENT_MASK) + 1;
            int block = (offset >>> BLOCK_OFFSET_BITS) & BLOCK_MASK;
            return ((long) segment) << Integer.SIZE | block;
        }

        public static long toOffset(int segment, int block) {
            return ((long) (SEGMENT_MASK & segment) << SEGMENT_OFFSET_BITS) | ((BLOCK_MASK & block) << BLOCK_OFFSET_BITS);
        }
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

        public static boolean includeString(final byte header) {
            return ((header >>> STRING_COMPLETE_OFFSET) & STRING_COMPLETE_MASK) != 0;
        }

        public static byte includeString(final byte header, final boolean value) {
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

        public static byte clearStringLength(final int header) {
            return (byte) (header & ~(STRLEN_MASK << STRLEN_OFFSET));
        }
    }
}
