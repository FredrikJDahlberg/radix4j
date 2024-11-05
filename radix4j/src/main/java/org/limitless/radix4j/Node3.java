package org.limitless.radix4j;

import java.lang.foreign.MemorySegment;

public class Node3 extends Node {

    protected static final int STRING_MAX_BYTES = 3;
    protected static final int KEY_COUNT = 3;
    protected static final int INDEX_LENGTH_BITS = 24;

    // index layout
    protected static final int KEY_INCLUDED_OFFSET_BITS = 0;
    protected static final int KEY_INCLUDED_LENGTH_BITS = 1;
    protected static final int KEY_VALUE_OFFSET_BITS = KEY_INCLUDED_OFFSET_BITS + KEY_INCLUDED_LENGTH_BITS;
    protected static final int KEY_VALUE_LENGTH_BITS = 7;
    protected static final int KEY_LENGTH_BITS = KEY_INCLUDED_LENGTH_BITS + KEY_VALUE_LENGTH_BITS;

    //
    protected static final int KEYS_OFFSET_BITS = KEY_COUNT_OFFSET_BITS + KEY_COUNT_LENGTH_BITS;
    protected static final int KEYS_LENGTH_BITS = KEY_COUNT * KEY_LENGTH_BITS;

    protected static final int INDICES_OFFSET_BITS = KEYS_OFFSET_BITS + KEYS_LENGTH_BITS;
    protected static final int INDICES_LENGTH_BITS = INDEX_LENGTH_BITS * KEY_COUNT;
    protected static final int STRING_OFFSET_BITS = INDICES_OFFSET_BITS + INDICES_LENGTH_BITS;
    protected static final int STRING_LENGTH_BITS = STRING_MAX_BYTES * Byte.SIZE;
    protected static final int SIZE = STRING_OFFSET_BITS + STRING_LENGTH_BITS;

    // masks
    protected static final int STRING_INCLUDED_MASK = (0xff >>> (Byte.SIZE - STRING_COMPLETE_LENGTH_BITS)) << STRING_COMPLETE_OFFSET_BITS;
    protected static final int KEY_INCLUDED_MASK = (0xff >>> (Byte.SIZE - KEY_INCLUDED_LENGTH_BITS)) << KEY_INCLUDED_OFFSET_BITS;
    protected static final int STRING_LENGTH_MASK = (0xff >>> (Byte.SIZE - STRLEN_LENGTH_BITS)) << STRLEN_OFFSET_BITS;
    protected static final int KEY_COUNT_MASK = (0xff >>> (Byte.SIZE - KEY_COUNT_LENGTH_BITS)) << KEY_COUNT_OFFSET_BITS;
    protected static final int KEY_VALUE_MASK = (0xff >>> (Byte.SIZE - KEY_VALUE_LENGTH_BITS)) << KEY_VALUE_OFFSET_BITS;

    // byte layout
    protected static final int KEYS_OFFSET = FLAGS_LENGTH;
    protected static final int KEYS_LENGTH = KEYS_LENGTH_BITS / Byte.SIZE;
    protected static final int INDICES_OFFSET = KEYS_OFFSET + KEYS_LENGTH;
    protected static final int INDICES_LENGTH = INDICES_LENGTH_BITS / Byte.SIZE;
    protected static final int STRING_OFFSET = INDICES_OFFSET + INDICES_LENGTH;
    protected static final int STRING_LENGTH = STRING_LENGTH_BITS / Byte.SIZE;
    protected static final int BYTES = STRING_OFFSET + STRING_LENGTH;

    public Node3() {
    }

    public Node3 wrap(Node3 node) {
        super.wrap(node.memorySegment(), node.segment(), node.block());
        return this;
    }

    public Node3 copyNode(final Node3 srcNode) {
        MemorySegment.copy(srcNode.memorySegment(), srcNode.fieldOffset(0),
            this.memorySegment(), this.fieldOffset(0), BYTES);
        return this;
    }

    public boolean completeKey(int position) {
        return (nativeByte(KEYS_OFFSET + position) & KEY_INCLUDED_MASK) != 0;
    }

    public Node3 completeKey(int position, boolean value) {
        int included = value ? 1 : 0;
        int location = KEYS_OFFSET + position;
        nativeByte(location, (byte) ((nativeByte(KEYS_OFFSET + position) & ~KEY_INCLUDED_MASK) |
            (included << KEY_INCLUDED_OFFSET_BITS)));
        return this;
    }

    public boolean completeString() {
        return (nativeByte(FLAGS_OFFSET) & STRING_INCLUDED_MASK) != 0;
    }

    public Node3 completeString(boolean value) {
        int included = value ? 1 : 0;
        flags((byte) ((flags() & ~STRING_INCLUDED_MASK) | (included << STRING_COMPLETE_OFFSET_BITS)));
        return this;
    }

    protected Node3 copyString(final int position, final int length) {
        if (length >= 1) {
            long stringOffset = fieldOffset(STRING_OFFSET);
            MemorySegment.copy(memorySegment(), stringOffset + position,
                memorySegment(), stringOffset, length);
        }
        stringLength(length);
        return this;
    }

    protected Node3 string(byte[] string, int position, int stringLength) {
        int length = Math.min(STRING_LENGTH, stringLength);
        stringLength(length);
        if (length >= 1) {
            nativeByteArray(position, string, STRING_OFFSET, length);
        }
        return this;
    }

    protected void string(byte[] string) {
        nativeByteArray(STRING_OFFSET, stringLength(), string);
    }

    public int stringLength() {
        return (flags() & STRING_LENGTH_MASK) >>> STRLEN_OFFSET_BITS;
    }

    public Node3 stringLength(int length) {
        flags((byte) ((flags() & ~STRING_LENGTH_MASK) | ((length << STRLEN_OFFSET_BITS) & STRING_LENGTH_MASK)));
        return this;
    }

    public int keyCount() {
        return ((flags() & KEY_COUNT_MASK) >>> KEY_COUNT_OFFSET_BITS);
    }

    public Node3 keyCount(int count) {
        flags((byte) ((flags() & ~KEY_COUNT_MASK) | ((count << KEY_COUNT_OFFSET_BITS) & KEY_COUNT_MASK)));
        return this;
    }

    public byte key(int position) {
        return (byte) ((nativeByte(KEYS_OFFSET + position) & KEY_VALUE_MASK) >>> KEY_VALUE_OFFSET_BITS);
    }

    public Node3 key(int position, byte value) {
        nativeByte(KEYS_OFFSET + position, (byte) (value << KEY_VALUE_OFFSET_BITS));
        return this;
    }

    public Node3 setIndex(final byte key, final int block) {
        keyCount(1);
        index(0, key, block);
        return this;
    }

    public int child(int position) {
        int location = position * INDEX_LENGTH + INDICES_OFFSET;
        return nativeByte(location) | (nativeByte(location + 1) << 8) | (nativeByte(location + 2) << 16);
    }

    public byte getStringByte(int position) {
        return nativeByte(Node3.STRING_OFFSET + position);
    }

    public Node3 index(int position, byte key, int block) {
        key(position, key);
        int location = position * INDEX_LENGTH + INDICES_OFFSET;
        nativeByte(location, (byte) block);
        nativeByte(location + 1, (byte) (block >>> 8));
        nativeByte(location + 2, (byte) (block >>> 16));
        return this;
    }

    @Override
    public int mismatch(final byte[] string, final int stringOffset, final int stringLength) {
        final int nodeLength = stringLength();
        final int remainingString = stringLength - stringOffset;
        int remaining = Math.min(remainingString, nodeLength);
        if (remaining >= 1 && string[stringOffset] != nativeByte(STRING_OFFSET)) {
            return 0;
        }
        if (remaining >= 2 && string[stringOffset + 1] != nativeByte(STRING_OFFSET + 1)) {
            return 1;
        }
        if (remaining >= 3 && string[stringOffset + 2] != nativeByte(STRING_OFFSET + 2)) {
            return 2;
        }
        if (remainingString == nodeLength && completeString()) {
            return -1;
        }
        return nodeLength;
    }

    /**
     * Find the position of the key
     * @param key a key
     * @return position or not found
     */
    @Override
    public int position(byte key) {
        int count = keyCount();
        if (count == 0) {
            return NOT_FOUND;
        }
        byte key1 = key(0);
        if (key1 == key || key1 == EMPTY_KEY) {
            return 0;
        }
        if (count == 1) {
            return NOT_FOUND;
        }
        byte key2 = key(1);
        if (key2 == key || key2 == EMPTY_KEY) {
            return 1;
        }
        if (count == 2) {
            return NOT_FOUND;
        }
        byte key3 = key(2);
        if (key3 == key || key3 == EMPTY_KEY) {
            return 2;
        }
        return NOT_FOUND;
    }

    public StringBuilder append(StringBuilder builder) {
        builder.setLength(0);
        builder.append("{Node#").append(block()).append(", ");
        int length = builder.length();
        if (completeString()) {
            builder.append('S');
        }
        int count = keyCount();
        for (int i = 0; i < count; ++i) {
            if (completeKey(i)) {
                builder.append(i);
            }
        }
        if (builder.length() != length) {
            builder.append(", ");
        }
        int stringLength = stringLength();
        builder.append('\"');
        if (stringLength >= 1) {
            final byte[] bytes = new byte[stringLength];
            nativeByteArray(STRING_OFFSET, stringLength, bytes);
            builder.append(new String(bytes, 0, stringLength));
        }
        builder.append("\", ");
        if (count >= 1) {
            builder.append("[");
            for (int i = 0; i < count; ++i) {
                builder.append((char) key(i)).append('=').append(child(i)).append(',');
            }
            builder.append("]");
        }
        builder.append("}");
        return builder;
    }

    @Override
    public String toString() {
        return append(new StringBuilder(128)).toString();
    }

    public static void clear(final Node3 node) {
        node.nativeLong(0, 0);
        node.nativeLong(Long.BYTES, 0);
    }
}
