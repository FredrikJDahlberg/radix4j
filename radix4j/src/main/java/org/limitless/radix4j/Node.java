package org.limitless.radix4j;

import org.limitless.fsmp4j.BlockFlyweight;

import java.lang.foreign.MemorySegment;

public class Node extends BlockFlyweight {

    protected static final int NOT_FOUND = -1;
    protected static final int EMPTY_BLOCK = 0;
    protected static final byte EMPTY_KEY = 0;

    protected static final int BLOCK_COUNT = 11;
    protected static final int KEY_LENGTH = 1;

    // node byte layout
    protected static final int HEADER_OFFSET = 0;
    protected static final int HEADER_LENGTH = 1;
    protected static final int STRING_OFFSET = HEADER_OFFSET + HEADER_LENGTH;
    protected static final int STRING_LENGTH = 5;
    protected static final int CONTAINS_OFFSET = STRING_OFFSET + STRING_LENGTH;
    protected static final int CONTAINS_LENGTH = 2;
    protected static final int BLOCK_OFFSET = CONTAINS_OFFSET + CONTAINS_LENGTH;
    protected static final int BLOCK_LENGTH = BLOCK_COUNT * Integer.BYTES;
    protected static final int KEYS_OFFSET = BLOCK_OFFSET + BLOCK_LENGTH;
    protected static final int KEYS_LENGTH = BLOCK_COUNT * KEY_LENGTH;
    protected static final int PAD_OFFSET = KEYS_OFFSET + KEYS_LENGTH;
    protected static final int PAD_LENGTH = 1;
    protected static final int BYTES = PAD_OFFSET + PAD_LENGTH;

    private static final int KEY_MASK = 0xff;
    private static final int HEADER_MASK = 0xff;

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

    /**
     * Compare the current node with the string at offset.
     * @param offset comparison position
     * @param length remaining string length
     * @param string byte array
     * @return the first mistmatch position or -1 when equal
     */
    public int mismatch(final int offset, final int length, final byte[] string) {
        long nodeString = nativeLong(HEADER_OFFSET);
        final byte header = (byte) (nodeString & HEADER_MASK);
        final int nodeLength = Header.stringLength(header);
        final int remaining = Math.min(length, nodeLength);
        nodeString >>>= 8;
        for (int i = 0; i < remaining; ++i) {
            if ((nodeString & KEY_MASK) != string[i + offset]) {
                return i;
            }
            nodeString >>>= 8;
        }
        if (length == nodeLength && Header.containsString(header)) {
            return -1;
        }
        return remaining;
    }

    /**
     * Add an index to this node
     * @param key key
     * @param offset child offset
     * @param contains true when the key is included
     * @return this
     */
    public Node addChild(final byte key, final int offset, boolean contains) {
        final byte header = header();
        final int count = Header.children(header);
        header(Header.children(header, count + 1));
        child(count, key, offset, contains);
        return this;
    }

    /**
     * Remove the index at the given position.
     * @param position index position
     */
    public void removeChild(int position) {
        final byte header = header();
        final int newCount = Header.children(header) - 1;
        if (position != newCount) {
            key(position, key(newCount));
            containsKey(position, containsKey(newCount));
            child(position, child(newCount));
        }
        header(Header.children(header, newCount));
    }

    /**
     * Return the node header
     * @return header
     */
    public byte header() {
        return nativeByte(HEADER_OFFSET);
    }

    /**
     * Set the node header
     * @param value new header value
     */
    public void header(final byte value) {
        nativeByte(HEADER_OFFSET, value);
    }

    /**
     * Initiate a header
     * @param length string length
     * @param contains complete string
     * @param count index count
     * @return header
     */
    public Node header(final int length, final boolean contains, final int count) {
        byte header;
        header = Header.stringLength(0, length);
        header = Header.children(header, count);
        header = Header.containsString(header, contains);
        header(header);
        return this;
    }

    /**
     * Key value at the given position
     * @param position key position
     * @return key
     */
    public byte key(final int position) {
        return nativeByte(KEYS_OFFSET + position);
    }

    /**
     * Set the key at the given position
     * @param position key position
     * @param key key value
     */
    public void key(final int position, final byte key) {
        nativeByte(KEYS_OFFSET + position, key);
    }

    /**
     * Check if the key tree contains the key at the given position
     * @param position key position
     * @return true when included
     */
    public boolean containsKey(final int position) {
        return (nativeByte(CONTAINS_OFFSET + position / Byte.SIZE) & (1 << position % Byte.SIZE)) != 0;
    }

    /**
     * Set the contains flag at the given position
     * @param position flag position
     * @param included flag
     */
    public void containsKey(final int position, final boolean included) {
        final int index = position / Byte.SIZE;
        final byte flag = (byte) (1 << (position % Byte.SIZE));
        byte contains = nativeByte(CONTAINS_OFFSET + index);
        if (included) {
            contains |= flag;
        } else {
            contains &= (byte) ~flag;
        }
        nativeByte(CONTAINS_OFFSET + index, contains);
    }

    /**
     * Get the block index at the given position
     * @param position index position
     * @return block index
     */
    public int child(final int position) {
        return nativeInt(BLOCK_OFFSET + position * Integer.BYTES);
    }

    /**
     * Set the block index at the given position
     * @param position index position
     * @param block index
     */
    public void child(final int position, final int block) {
        nativeInt(BLOCK_OFFSET + position * Integer.BYTES, block);
    }

    /**
     * Set the key, offset and included flag at the given position
     * @param position position
     * @param key key
     * @param offset  child offset
     * @param included flag
     */
    public void child(final int position, final byte key, final int offset, boolean included) {
        key(position, key);
        child(position, offset);
        containsKey(position, included);
    }

    /**
     * Find the position of the given key
     * @param count key count
     * @param key value
     * @return position or -1 when not found
     */
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

    /**
     * Set the node string value
     * @param string value
     * @param offset string offset
     * @param length string length
     */
    public void string(final byte[] string, final int offset, final int length) {
        final int nodeLength = Math.min(STRING_LENGTH, length);
        if (nodeLength >= 1) {
            nativeByteArray(offset, string, STRING_OFFSET, length);
        }
    }

    /**
     * Get the string value
     * @param offset source offset
     * @param length destination length
     * @param string destination
     */
    public void string(final int offset, final int length, final byte[] string) {
        final byte header = header();
        final int stringLength = Math.min(length, Header.stringLength(header));
        nativeByteArray(STRING_OFFSET + offset, stringLength, string);
    }

    /**
     * Get the string value
     * @param offset source offset
     * @param length destination length
     * @param string destination
     * @param dstOffset destination offset
     */
    public void string(final int offset, final int length, final byte[] string, final int dstOffset) {
        nativeByteArray(STRING_OFFSET + offset, length, dstOffset, string);
    }

    /**
     * Get the character at position
     * @param position string position (zero based)
     * @return byte
     */
    public byte charAt(final int position) {
        return nativeByte(STRING_OFFSET + position);
    }

    /**
     * Set the character at position
     * @param position string position (zero based)
     * @param ch character
     */
    public void charAt(final int position, final byte ch) {
        nativeByte(STRING_OFFSET + position, ch);
    }

    /**
     * Deletes the substring before position
     * @param position string position (zero based)
     * @param length  string length
     */
    protected void removePrefix(final int position, final int length) {
        if (length >= 1) {
            final long stringOffset = fieldOffset(STRING_OFFSET);
            final MemorySegment memory = memorySegment();
            MemorySegment.copy(memory, stringOffset + position,
                memory, stringOffset, length);
        }
        header(Header.stringLength(header(), length));
    }

    /**
     * Copy source to this node
     * @param source node
     */
    public void copy(final Node source) {
        MemorySegment.copy(source.memorySegment(), source.fieldOffset(0),
            this.memorySegment(), this.fieldOffset(0), BYTES);
    }

    /**
     * Appends string representation of the object to the builder.
     * @param builder string builder
     * @return builder
     */
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
        if (Header.containsString(header)) {
            builder.append('.');
        }

        final int count = Header.children(header);
        if (count >= 1) {
            builder.append(" [");
            for (int i = 0; i < count; ++i) {
                final char value = (char) key(i);
                builder.append(value == EMPTY_KEY ? '@' :  value);
                if (containsKey(i)) {
                    builder.append('.');
                }
                builder.append('=').append(child(i)).append(',');
            }
            builder.append(']');
        }
        return builder.append('}');
    }

    /**
     * Returns a string representation of the object.
     * @return string
     */
    @Override
    public String toString() {
        return append(new StringBuilder(64)).toString();
    }

    protected static final class Address {

        // bit layout
        private static final int BLOCK_OFFSET_BITS = 0;
        private static final int BLOCK_LENGTH_BITS = 16;
        private static final int SEGMENT_OFFSET_BITS = BLOCK_OFFSET_BITS + BLOCK_LENGTH_BITS;
        private static final int SEGMENT_LENGTH_BITS = 16;

        private static final int BLOCK_MASK = -1 >>> (Integer.SIZE - BLOCK_LENGTH_BITS);
        private static final int SEGMENT_MASK = -1 >>> (Integer.SIZE - SEGMENT_LENGTH_BITS);

        public static final int MAX_BLOCKS = 1 << BLOCK_LENGTH_BITS;
        public static final int MAX_SEGMENTS = 1 << SEGMENT_LENGTH_BITS;

        public static long fromOffset(final int offset) {
            final int segment = ((offset >>> SEGMENT_OFFSET_BITS) & SEGMENT_MASK) + 1;
            final int block = (offset >>> BLOCK_OFFSET_BITS) & BLOCK_MASK;
            return ((long) segment) << Integer.SIZE | block;
        }

        public static long toOffset(int segment, int block) {
            return ((long) (SEGMENT_MASK & segment) << SEGMENT_OFFSET_BITS) | ((BLOCK_MASK & block) << BLOCK_OFFSET_BITS);
        }
    }

    protected static final class Header {

        // bit layout
        private static final int CONTAINS_STRING_OFFSET = 0;
        private static final int CONTAINS_STRING_LENGTH = 1;
        private static final int STRLEN_OFFSET = CONTAINS_STRING_OFFSET + CONTAINS_STRING_LENGTH;
        private static final int STRLEN_LENGTH = 3;
        private static final int INDEX_COUNT_OFFSET = STRLEN_OFFSET + STRLEN_LENGTH;
        private static final int INDEX_COUNT_LENGTH = 4;

        private static final byte CONTAINS_STRING_MASK = 0xff >>> (Byte.SIZE - CONTAINS_STRING_LENGTH);
        private static final byte INDEX_COUNT_MASK = 0xff >>> (Byte.SIZE - INDEX_COUNT_LENGTH);
        private static final byte STRLEN_MASK = 0xff >>> (Byte.SIZE - STRLEN_LENGTH);

        public static boolean containsString(final byte header) {
            return ((header >>> CONTAINS_STRING_OFFSET) & CONTAINS_STRING_MASK) != 0;
        }

        public static byte containsString(final byte header, final boolean value) {
            if (value) {
                return (byte) (header | (CONTAINS_STRING_MASK << CONTAINS_STRING_OFFSET));
            } else {
                return (byte) (header & ~(CONTAINS_STRING_MASK << CONTAINS_STRING_OFFSET));
            }
        }

        public static int children(final int header) {
            return (header >>> INDEX_COUNT_OFFSET) & INDEX_COUNT_MASK;
        }

        public static byte children(final int header, final int count) {
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
