package org.limitless.radix4j;

import org.limitless.fsmp4j.BlockFlyweight;

public class Node extends BlockFlyweight {

    protected static final int NOT_FOUND = -1;
    protected static final int KEY_LENGTH_BITS = 8;
    protected static final int INDEX_LENGTH_BITS = 24;

    protected static final int EMPTY_BLOCK = 0;
    protected static final byte EMPTY_KEY = 0;
    protected static final int INDEX_LENGTH = INDEX_LENGTH_BITS / Byte.SIZE;
    protected static final int BYTES = 16; // bytes

    // bit layout
    protected static final int NODE_TYPE_OFFSET = 0;
    protected static final int NODE_TYPE_LENGTH = 2;
    protected static final int STRING_COMPLETE_OFFSET_BITS = NODE_TYPE_OFFSET + NODE_TYPE_LENGTH;
    protected static final int STRING_COMPLETE_LENGTH_BITS = 1;
    protected static final int STRLEN_OFFSET_BITS = STRING_COMPLETE_OFFSET_BITS + STRING_COMPLETE_LENGTH_BITS;
    protected static final int STRLEN_LENGTH_BITS = 3;
    protected static final int KEY_COUNT_OFFSET_BITS = STRLEN_OFFSET_BITS + STRLEN_LENGTH_BITS;
    protected static final int KEY_COUNT_LENGTH_BITS = 2;

    // byte layout
    protected static final int FLAGS_OFFSET = 0;
    protected static final int FLAGS_LENGTH = 1;

    @Override
    public int block() {
        return (segment() << 24) | super.block() & 0x00ffffff;
    }

    public Node wrap(Node node) {
        wrap(node.memorySegment(), node.segment(), node.block());
        return this;
    }

    protected byte flags() {
        return nativeByte(FLAGS_OFFSET);
    }

    protected Node flags(final byte value) {
        nativeByte(FLAGS_OFFSET, value);
        return this;
    }

    public int position(byte key) {
        return NOT_FOUND;
    }

    @Override
    public int encodedLength() {
        return BYTES;
    }

    @Override
    protected StringBuilder append(StringBuilder stringBuilder) {
        return stringBuilder;
    }

    public int mismatch(final byte[] string, final int stringOffset, final int stringLength) {
        return 0;
    }
}