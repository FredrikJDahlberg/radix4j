package org.limitless.radix4j;

import org.limitless.fsmp4j.BlockFlyweight;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

public class Node extends BlockFlyweight {

    protected static final int NOT_FOUND = -1;
    protected static final int EQUAL = -1;
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
    protected static final int OVERFLOW_OFFSET = KEYS_OFFSET + KEYS_LENGTH;  // overflow position + 1, 0 for none
    protected static final int OVERFLOW_LENGTH = 1;
    protected static final int BYTES = OVERFLOW_OFFSET + OVERFLOW_LENGTH;

    // bucket byte layout: a node without children holding the rest of one or more strings,
    // each a length byte followed by the tail
    protected static final int BUCKET_LENGTH_OFFSET = HEADER_OFFSET + HEADER_LENGTH;
    protected static final int BUCKET_LENGTH_LENGTH = 1;
    protected static final int BUCKET_OFFSET = BUCKET_LENGTH_OFFSET + BUCKET_LENGTH_LENGTH;
    protected static final int BUCKET_BYTES = BYTES - BUCKET_OFFSET;
    protected static final int TAIL_LENGTH = BUCKET_BYTES - 1;
    protected static final int MAX_BUCKET_STRINGS = BUCKET_BYTES / 2;

    private static final int KEY_MASK = 0xff;
    private static final int HEADER_MASK = 0xff;
    private static final VarHandle LONG_VIEW = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());
    private static final boolean LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

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
     * Read the header and the inline string with a single access
     * @return the header in the lowest byte followed by the string bytes
     * @see #headerOf(long)
     */
    public long headerAndString() {
        return nativeLong(HEADER_OFFSET);
    }

    /**
     * Extract the header from {@link #headerAndString()}
     * @param headerAndString header and string
     * @return header
     */
    public static byte headerOf(final long headerAndString) {
        return (byte) (headerAndString & HEADER_MASK);
    }

    /**
     * Compare a node with the string at offset.
     * @param headerAndString the node's {@link #headerAndString()}
     * @param offset comparison position
     * @param length remaining string length
     * @param string byte array
     * @return the first mismatch position or -1 when equal
     */
    public static int mismatch(final long headerAndString, final int offset, final int length, final byte[] string) {
        long nodeString = headerAndString;
        final byte header = headerOf(nodeString);
        final int nodeLength = Header.stringLength(header);
        final int remaining = Math.min(length, nodeLength);
        nodeString >>>= Byte.SIZE;
        for (int i = 0; i < remaining; ++i) {
            if ((nodeString & KEY_MASK) != (string[i + offset] & KEY_MASK)) {
                return i;
            }
            nodeString >>>= Byte.SIZE;
        }
        if (length == nodeLength && Header.containsString(header)) {
            return EQUAL;
        }
        return remaining;
    }

    /**
     * Check if this node is a bucket
     * @return true for buckets
     */
    public boolean isBucket() {
        return Header.isBucket(header());
    }

    /**
     * Check if this node is a hybrid bucket, shared by keys of one node, whose tails start with their key
     * @return true for hybrid buckets
     */
    public boolean isHybrid() {
        return Header.isHybrid(header());
    }

    /**
     * Turn this node into an empty hybrid bucket
     * @return this
     */
    public Node hybrid() {
        header(Header.HYBRID);
        nativeByte(BUCKET_LENGTH_OFFSET, (byte) 0);
        return this;
    }

    /**
     * Check if a tail starts with the byte
     * @param first first byte
     * @return true when found
     */
    public boolean bucketHas(final byte first) {
        final int used = bucketLength();
        for (int entry = 0; entry < used; entry += 1 + tailLength(entry)) {
            if (nativeByte(BUCKET_OFFSET + entry + 1) == first) {
                return true;
            }
        }
        return false;
    }

    /**
     * Byte of the tail at the entry
     * @param entry entry offset
     * @param position tail position
     * @return byte
     */
    public byte tailByte(final int entry, final int position) {
        return nativeByte(BUCKET_OFFSET + entry + 1 + position);
    }

    /**
     * Remove the tails starting with the byte
     * @param first first byte
     * @return number of tails removed
     */
    public int bucketRemove(final byte first) {
        int removed = 0;
        for (int entry = 0; entry < bucketLength(); ) {
            if (tailByte(entry, 0) == first) {
                bucketRemove(entry);
                ++removed;
            } else {
                entry += 1 + tailLength(entry);
            }
        }
        return removed;
    }

    /**
     * Check if an earlier key has the same child, a hybrid bucket
     * @param position key position
     * @return true when the child is shared with an earlier key
     */
    public boolean sharesChild(final int position) {
        final int block = child(position);
        if (block != EMPTY_BLOCK) {
            for (int i = 0; i < position; ++i) {
                if (child(i) == block) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Turn this node into a bucket holding one tail
     * @param string bytes
     * @param offset tail offset
     * @param length tail length, 1 to {@link #TAIL_LENGTH}
     * @return this
     */
    public Node bucket(final byte[] string, final int offset, final int length) {
        header(Header.BUCKET);
        nativeByte(BUCKET_LENGTH_OFFSET, (byte) 0);
        bucketAdd(string, offset, length);
        return this;
    }

    /**
     * Remove all tails from the bucket
     */
    public void bucketClear() {
        nativeByte(BUCKET_LENGTH_OFFSET, (byte) 0);
    }

    /**
     * Bytes used by the bucket tails, including their length bytes
     * @return length
     */
    public int bucketLength() {
        return nativeByte(BUCKET_LENGTH_OFFSET) & KEY_MASK;
    }

    /**
     * Number of tails in the bucket
     * @return count
     */
    public int bucketCount() {
        final int used = bucketLength();
        int count = 0;
        for (int entry = 0; entry < used; entry += 1 + tailLength(entry)) {
            ++count;
        }
        return count;
    }

    /**
     * Check if a tail fits in the bucket
     * @param length tail length
     * @return true when it fits
     */
    public boolean bucketFits(final int length) {
        return bucketLength() + 1 + length <= BUCKET_BYTES;
    }

    /**
     * Append a tail to the bucket, which must fit
     * @param string bytes
     * @param offset tail offset
     * @param length tail length
     */
    public void bucketAdd(final byte[] string, final int offset, final int length) {
        final int used = bucketLength();
        nativeByte(BUCKET_OFFSET + used, (byte) length);
        nativeByteArray(offset, string, BUCKET_OFFSET + used + 1, length);
        nativeByte(BUCKET_LENGTH_OFFSET, (byte) (used + 1 + length));
    }

    /**
     * Length of the tail at the entry
     * @param entry entry offset
     * @return tail length
     */
    public int tailLength(final int entry) {
        return nativeByte(BUCKET_OFFSET + entry) & KEY_MASK;
    }

    /**
     * Copy the tail at the entry
     * @param entry entry offset
     * @param string destination
     * @param offset destination offset
     */
    public void tail(final int entry, final byte[] string, final int offset) {
        nativeByteArray(BUCKET_OFFSET + entry + 1, tailLength(entry), offset, string);
    }

    /**
     * Find a tail equal to the string at offset
     * @param offset string offset
     * @param length string length
     * @param string bytes
     * @return entry offset, or -1 - the number of tails when not found
     */
    public int bucketFind(final int offset, final int length, final byte[] string) {
        // compare the length and the first byte of each tail with one read
        final MemorySegment memory = memorySegment();
        final long bucket = fieldOffset(BUCKET_OFFSET);
        final int used = memory.get(ValueLayout.JAVA_BYTE, bucket - 1) & KEY_MASK;
        final short head = LITTLE_ENDIAN ? (short) (length | string[offset] << Byte.SIZE)
            : (short) (length << Byte.SIZE | string[offset] & KEY_MASK);
        int count = 0;
        for (int entry = 0; entry < used; ++count) {
            final short value = memory.get(ValueLayout.JAVA_SHORT_UNALIGNED, bucket + entry);
            if (value == head && tailMismatch(entry, offset, length, string) == length) {
                return entry;
            }
            entry += 1 + (LITTLE_ENDIAN ? value & KEY_MASK : (value >>> Byte.SIZE) & KEY_MASK);
        }
        return -1 - count;
    }

    /**
     * Check if any tail starts with the prefix at offset
     * @param offset prefix offset
     * @param length prefix length
     * @param prefix bytes
     * @return true when a tail starts with the prefix
     */
    public boolean bucketStartsWith(final int offset, final int length, final byte[] prefix) {
        final int used = bucketLength();
        for (int entry = 0; entry < used; entry += 1 + tailLength(entry)) {
            if (tailLength(entry) >= length && tailMismatch(entry, offset, length, prefix) == length) {
                return true;
            }
        }
        return false;
    }

    /**
     * Remove the tail at the entry
     * @param entry entry offset
     */
    public void bucketRemove(final int entry) {
        final int used = bucketLength();
        final int next = entry + 1 + tailLength(entry);
        if (next < used) {
            final MemorySegment memory = memorySegment();
            MemorySegment.copy(memory, fieldOffset(BUCKET_OFFSET + next),
                memory, fieldOffset(BUCKET_OFFSET + entry), used - next);
        }
        nativeByte(BUCKET_LENGTH_OFFSET, (byte) (used - next + entry));
    }

    /**
     * Remove the tails starting with the prefix at offset
     * @param offset prefix offset
     * @param length prefix length
     * @param prefix bytes
     * @return number of tails removed
     */
    public int bucketRemove(final int offset, final int length, final byte[] prefix) {
        int removed = 0;
        for (int entry = 0; entry < bucketLength(); ) {
            final int tailLength = tailLength(entry);
            if (tailLength >= length && tailMismatch(entry, offset, length, prefix) == length) {
                bucketRemove(entry);
                ++removed;
            } else {
                entry += 1 + tailLength;
            }
        }
        return removed;
    }

    /**
     * Compare the first bytes of the tail at the entry with the string at offset, eight bytes at a time
     * @param entry entry offset
     * @param offset string offset
     * @param length bytes to compare, at most the tail length
     * @param string bytes
     * @return the first mismatch position, or length when equal
     */
    private int tailMismatch(final int entry, final int offset, final int length, final byte[] string) {
        final int tail = BUCKET_OFFSET + entry + 1;
        int i = 0;
        for (; i + Long.BYTES <= length; i += Long.BYTES) {
            final long difference = nativeLong(tail + i) ^ (long) LONG_VIEW.get(string, offset + i);
            if (difference != 0) {
                return i + Long.numberOfTrailingZeros(difference) / Byte.SIZE;
            }
        }
        for (; i < length; ++i) {
            if (nativeByte(tail + i) != string[offset + i]) {
                return i;
            }
        }
        return length;
    }

    /**
     * Number of strings ending in this node: the node string and the keys that end a string, or the bucket tails
     * @return count
     */
    public int stringCount() {
        final byte header = header();
        if (Header.isBucket(header)) {
            return bucketCount();
        }
        int count = Header.containsString(header) ? 1 : 0;
        for (int i = Header.children(header) - 1; i >= 0; --i) {
            if (containsKey(i)) {
                ++count;
            }
        }
        return count;
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
        final int overflow = overflow();
        if (overflow == position) {
            overflow(NOT_FOUND);
        } else if (overflow == newCount) {
            overflow(position);
        }
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
        overflow(NOT_FOUND);
        return this;
    }

    /**
     * Position of the overflow key, which leads to a node holding the keys beyond {@link #BLOCK_COUNT} and does
     * not consume a byte of the string. Its key byte is {@link #EMPTY_KEY}, but a key of 0 may also be a string byte.
     * @return position or {@link #NOT_FOUND}
     */
    public int overflow() {
        return nativeByte(OVERFLOW_OFFSET) - 1;
    }

    /**
     * Set the position of the overflow key
     * @param position position or {@link #NOT_FOUND}
     */
    public void overflow(final int position) {
        nativeByte(OVERFLOW_OFFSET, (byte) (position + 1));
    }

    /**
     * Check if the key at the given position is the overflow key
     * @param position key position
     * @return true for the overflow key
     */
    public boolean isOverflow(final int position) {
        return nativeByte(OVERFLOW_OFFSET) == position + 1;
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
     * Find the position of the given key, or of the overflow key when the node has none
     * @param count key count
     * @param key value
     * @return position or -1 when not found
     */
    public int keyPosition(final int count, final byte key) {
        final int overflow = overflow();
        for (int i = 0; i < count; ++i) {
            if (key(i) == key && i != overflow) {
                return i;
            }
        }
        return overflow;
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
        if (Header.isBucket(header)) {
            final byte[] bytes = new byte[TAIL_LENGTH];
            final int used = bucketLength();
            for (int entry = 0; entry < used; entry += 1 + tailLength(entry)) {
                tail(entry, bytes, 0);
                if (entry >= 1) {
                    builder.append("\", \"");
                }
                builder.append(new String(bytes, 0, tailLength(entry)));
            }
            return builder.append(Header.isHybrid(header) ? "\" hybrid}" : "\" bucket}");
        }
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
                builder.append(isOverflow(i) ? '@' :  value);
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

        // buckets use string lengths that inline strings never reach, contain strings and have no children
        private static final int BUCKET_STRLEN = STRLEN_MASK;
        private static final int HYBRID_STRLEN = STRLEN_MASK - 1;
        public static final byte BUCKET = (byte) ((BUCKET_STRLEN << STRLEN_OFFSET) | (CONTAINS_STRING_MASK << CONTAINS_STRING_OFFSET));
        public static final byte HYBRID = (byte) ((HYBRID_STRLEN << STRLEN_OFFSET) | (CONTAINS_STRING_MASK << CONTAINS_STRING_OFFSET));

        public static boolean isBucket(final int header) {
            return stringLength(header) >= HYBRID_STRLEN;
        }

        public static boolean isHybrid(final int header) {
            return stringLength(header) == HYBRID_STRLEN;
        }

        public static int containsStringCount(final byte header) {
            return ((header >>> CONTAINS_STRING_OFFSET) & CONTAINS_STRING_MASK);
        }

        public static boolean containsString(final byte header) {
            return containsStringCount(header) != 0;
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
    }
}
