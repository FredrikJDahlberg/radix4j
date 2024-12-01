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
    protected static final int MAX_BLOCK_COUNT = 1 << 24;
    protected static final int MAX_SEGMENT_COUNT = 1 << 8;

    // header bit layout
    protected static final int STRING_COMPLETE_OFFSET_BITS = 0;
    protected static final int STRING_COMPLETE_LENGTH_BITS = 1;
    protected static final int STRLEN_OFFSET_BITS = STRING_COMPLETE_OFFSET_BITS + STRING_COMPLETE_LENGTH_BITS;
    protected static final int STRLEN_LENGTH_BITS = 3;
    protected static final int INDEX_COUNT_OFFSET_BITS = STRLEN_OFFSET_BITS + STRLEN_LENGTH_BITS;
    protected static final int INDEX_COUNT_LENGTH_BITS = 4;

    protected static final int STRING_COMPLETE_MASK = 0xff >>> (Byte.SIZE - STRING_COMPLETE_LENGTH_BITS) << STRING_COMPLETE_OFFSET_BITS;
    protected static final int INDEX_COUNT_MASK = 0xff >>> (Byte.SIZE - INDEX_COUNT_LENGTH_BITS) << INDEX_COUNT_OFFSET_BITS;
    protected static final int STRLEN_MASK = 0xff >>> (Byte.SIZE - STRLEN_LENGTH_BITS) << STRLEN_OFFSET_BITS;

    // index bit layout
    protected static final int BLOCK_OFFSET_BITS = 0;
    protected static final int BLOCK_LENGTH_BITS = 24;
    protected static final int KEY_OFFSET_BITS = BLOCK_OFFSET_BITS + BLOCK_LENGTH_BITS;
    protected static final int KEY_LENGTH_BITS = MAX_STRING_LENGTH;
    protected static final int KEY_COMPLETE_OFFSET_BITS = KEY_OFFSET_BITS + KEY_LENGTH_BITS;
    protected static final int KEY_COMPLETE_LENGTH_BITS = 1;

    protected static final int INDEX_LENGTH_BITS = KEY_COMPLETE_OFFSET_BITS + KEY_COMPLETE_LENGTH_BITS;
    protected static final int INDEX_BYTES = INDEX_LENGTH_BITS / Byte.SIZE;

    protected static final int INDEX_COMPLETE_MASK = -1 >>> (Integer.SIZE - KEY_COMPLETE_LENGTH_BITS) << KEY_COMPLETE_OFFSET_BITS;
    protected static final int INDEX_KEY_MASK = -1 >>> (Integer.SIZE - KEY_LENGTH_BITS) << KEY_OFFSET_BITS;
    protected static final int INDEX_BLOCK_MASK = -1 >>> (Integer.SIZE - BLOCK_LENGTH_BITS) << BLOCK_OFFSET_BITS;

    // node byte layout
    protected static final int HEADER_OFFSET = 0;
    protected static final int HEADER_LENGTH = 1;
    protected static final int STRING_OFFSET = HEADER_OFFSET + HEADER_LENGTH;
    protected static final int STRING_LENGTH = 7;
    protected static final int INDEX_OFFSET = STRING_OFFSET + STRING_LENGTH;
    protected static final int INDEX_LENGTH = MAX_INDEX_COUNT * INDEX_BYTES;
    protected static final int BYTES = INDEX_OFFSET + INDEX_LENGTH;

    public int offset() {
        return ((segment() & 0xff) << 16) | (super.block() & 0x0000ffff);
    }

    public Node14 wrap(Node14 node) {
        wrap(node.memorySegment(), node.segment(), node.block());
        return this;
    }

    public Node14 wrap(final MemorySegment memory, final int offset) {
        final int segment = offset >>> 16;
        final int block = offset & 0x0000ffff;
        wrap(memory, segment, block);
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
            if ((str & 0xff) != string[i + stringOffset]) {
                return i;
            }
            str >>>= 8;
        }
        if (remainingString == nodeLength && Header.completeString(header)) {
            return -1;
        }
        return nodeLength;
    }

    public static long addressFromOffset(final int offset) {
        int segment = ((offset & 0x00ff_0000) >>> 16) + 1; // FIXME
        int block = offset & 0x0000_ffff;
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
        byte header = 0;
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
        // FIXME
        int index = ((key & 0x7f) << KEY_OFFSET_BITS) | ((block & 0x00ff_ffff) << BLOCK_OFFSET_BITS);
        if (complete) {
            index |= INDEX_COMPLETE_MASK;
        } else {
            index &= ~INDEX_COMPLETE_MASK;
        }
        nativeInt(INDEX_OFFSET + position * Integer.BYTES, index);
        return this;
    }

    protected Node14 string(final byte[] string, final int position, final int stringLength) {
        final int length = Math.min(STRING_LENGTH, stringLength);
        header(Header.stringLength(header(), length));
        if (length >= 1) {
            nativeByteArray(position, string, STRING_OFFSET, length);
        }
        return this;
    }

    protected void string(final byte[] string) {
        nativeByteArray(STRING_OFFSET, Header.stringLength(header()), string);
    }

    protected void string(final int stringLength, byte[] string) {
        nativeByteArray(STRING_OFFSET, stringLength, string);
    }

    public byte string(int position) {
        return nativeByte(STRING_OFFSET + position);
    }

    public Node14 string(int position, byte ch) {
        nativeByte(STRING_OFFSET + position, ch);
        return this;
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

    public int position(byte key) {
        byte header = header();
        int count = Header.indexCount(header);
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
        builder.append("{Node").append(segment()).append('#').append(block()).append(", ");
        int length = builder.length();

        //if (Header.completeString(header)) {
        //    builder.append('S');
        //}
        //if (builder.length() != length) {
        //    builder.append(", ");
        //}

        int stringLength = Header.stringLength(header);
        builder.append('\"');
        if (stringLength >= 1) {
            final byte[] bytes = new byte[stringLength];
            nativeByteArray(STRING_OFFSET, stringLength, bytes);
            builder.append(new String(bytes, 0, stringLength));
        }
        builder.append('\"');
        if (Header.completeString(header)) {
            builder.append('!');
        }

        int count = Header.indexCount(header);
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

        public static boolean completeString(final byte header) {
            return (header & STRING_COMPLETE_MASK) != 0;
        }

        public static byte completeString(final byte header, final boolean value) {
            if (value) {
                return (byte) (header | STRING_COMPLETE_MASK);
            } else {
                return (byte) (header & ~STRING_COMPLETE_MASK);
            }
        }

        public static int indexCount(final int header) {
            return (header & INDEX_COUNT_MASK) >> INDEX_COUNT_OFFSET_BITS;
        }

        public static byte indexCount(final int header, final int count) {
            // FIXME
            return (byte) ((header & ~INDEX_COUNT_MASK) | ((count & 0xf) << INDEX_COUNT_OFFSET_BITS));
        }

        public static int stringLength(final int header) {
            return (header & STRLEN_MASK) >>> STRLEN_OFFSET_BITS;
        }

        public static byte stringLength(final int header, final int length) {
            // FIXME
            return (byte) ((header & ~STRLEN_MASK) | ((length & 0x7) << STRLEN_OFFSET_BITS));
        }
    }

    // index helpers
    public static final class Index {

        public static boolean completeKey(final int index) {
            return (index & INDEX_COMPLETE_MASK) != 0;
        }

        public static int completeKey(int index, boolean value) {
            if (value) {
                return index | INDEX_COMPLETE_MASK;
            } else {
                return index & ~INDEX_COMPLETE_MASK;
            }
        }

        public static int offset(final int index) {
            return (index & INDEX_BLOCK_MASK) >> BLOCK_OFFSET_BITS;
        }

        public static int offset(final int index, final int block) {
            // FIXME
            return (index & ~INDEX_BLOCK_MASK) | ((block & 0x00ffffff) << BLOCK_OFFSET_BITS);
        }

        public static byte key(final int index) {
            return (byte) ((index & INDEX_KEY_MASK) >>> KEY_OFFSET_BITS);
        }

        public static int key(final int index, final byte key) {
            // FIXME
            return (index & ~INDEX_KEY_MASK) | ((key & 0x7f) << KEY_OFFSET_BITS);
        }
    }
}
