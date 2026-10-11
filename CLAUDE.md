# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Full clean build and test
./gradlew

# Run all tests
./gradlew :radix4j:test :radix4j-clordid:test

# Run a single test
./gradlew :radix4j:test --tests "org.limitless.radix4j.RadixTreeTest.splitShortPrefix"

# Run JMH benchmarks (RadixTreeBenchmark and HashMapBenchmark)
./gradlew jmh

# Release: bump version in radix4j/build.gradle, commit, push main and the v<version> tag
# (JitPack builds the tag on first request, see jitpack.yml)
.github/tag-release.sh 1.0.4
```

Requires Java 23.

## Architecture

This is a three-module Gradle project:
- `radix4j` — the radix tree library
- `radix4j-clordid` — `ClientOrderIdSet`, a duplicate check for FIX client order ids (see below)
- `radix4j-benchmarks` — JMH benchmarks and `MemoryComparison`, comparing against `HashSet`

### Memory model

All tree nodes live in **off-heap memory** managed by `fsmp4j` (`BlockPool<Node>`). `Node` is a flyweight (extends `BlockFlyweight`) that wraps a `MemorySegment` — it has no heap fields and is repositioned via `wrap()` rather than allocated per access. The four reusable `Node` instances (`node`, `root`, `child`, `parent`) in `RadixTree` are pre-allocated and mutated in place throughout all operations.

Addresses are 32-bit: the upper 16 bits are the segment index, the lower 16 bits are the block index within that segment (`Node.Address`). `EMPTY_BLOCK = 0` is the null sentinel.

### Node layout (64 bytes)

| Field | Offset | Size | Purpose |
|---|---|---|---|
| header | 0 | 1 B | bit-packed: containsString(1), stringLen(3), childCount(4) |
| string | 1 | 5 B | inline string prefix (up to 5 bytes) |
| contains | 6 | 2 B | per-child "contains string" bitmask (11 bits) |
| children | 8 | 44 B | 11 × 4-byte child block offsets |
| keys | 52 | 11 B | 1-byte key per child |
| overflow | 63 | 1 B | position + 1 of the overflow key, 0 for none |

`BLOCK_COUNT = 11` is the maximum number of children per node. A full node gets more keys through an overflow key
(key byte 0, marked by the overflow field, since 0 is also a valid string byte) leading to a node with the rest; it
does not consume a byte of the string. `STRING_LENGTH = 5` is the maximum inline prefix length.

`Header` (inner class of `Node`) encodes/decodes the header byte: bit 0 = containsString flag, bits 1–3 = inline string length, bits 4–7 = child count.

### Bucket layout (64 bytes)

A bucket holds the unshared tails of up to `bucketStrings` strings (default and maximum `MAX_BUCKET_STRINGS` = 31, a protected constructor argument): header (1 B, `Header.BUCKET`: string length 7, containsString set, no children), bytes used (1 B), then `BUCKET_BYTES` = 62 bytes of entries, each a length byte and a tail of 1–`TAIL_LENGTH` = 61 bytes, unsorted. `Header.isBucket()` must be checked before treating a block as a node — every search loop (`mismatch`, `contains`, `findPrefix`) does this first. `bucketFind` compares the length and first byte of each entry with one short read, and returns -1 - the number of tails when not found. A new tail of ≤ 61 bytes is written as a bucket (with `bucketStrings = 1`, only tails > 5 bytes: the bucket is a leaf and shorter tails are inline nodes); longer tails become a node chain ending in a bucket. A tail reaching a full bucket makes `addTail()` sort the bucket's tails and the new one and `build()` the bucket block in place (keeping its address) into a node holding their common prefix (≤ 5 bytes) with a key per next byte; keys beyond a full node go to overflow nodes.

A hybrid bucket (`Header.HYBRID`, string length 6) is the child of several keys of one node, and its tails start with their key: the search loops back up one byte on reaching it. Invariant: a key points to a hybrid bucket exactly while the bucket holds tails starting with it (a string ending at the key still uses the key's contains flag), and nodes are never shared. Hybrid buckets come from `build()`, where `packEnd()` puts the tails of consecutive keys that fit together in one (without crossing into an overflow node), and from `shareBucket()`, where a new key joins the bucket of one of the two nearest keys with room (a bucket becomes hybrid by prefixing its tails with its key). A full hybrid bucket is shared out among its keys by `split()`, without a new level; a single key's tails become a bucket or a subtree of their own. Before `addKey()` moves the last key of a full node to an overflow node, `unshare()` gives it a bucket of its own. Removing the last tail of a key detaches the key (`removeKey()`, `detach()`); `forEach`, `freeSubtree` and `removeStrings` skip a child that an earlier key shares (`Node.sharesChild()`). `forEach(prefix)` visits the bucket where the prefix ends, which may also hold other strings; `removeStrings(prefix)` removes only the matching tails. `Node.stringCount()` counts the strings in a node or bucket.

### Search inner class

`Search` (private inner class of `RadixTree`) holds all traversal state:
- `mismatch()` — finds the insertion point for `add()`; sets `mismatchType` to one of `COMMON_PREFIX`, `NO_COMMON_PREFIX`, `SUBSTRING`, `MISSING_KEY`, `COMMON_PREFIX_AND_KEY`, `BUCKET`
- `contains()` — fast-path lookup, no path recording
- `forEach()` — iterative DFS using the `path[]` stack
- `removeStrings()` — removes an entire subtree and cleans up the ancestor path

`path[]` is a `long[]` stack encoding `(key, position, blockOffset)` per visited node, grown lazily.

### ByteUtils

SWAR (SIMD Within A Register) helpers for scanning packed bytes in `int`/`long` words — used for performance-critical byte search operations.

### Public API

`RadixTree` methods accept `String`, `byte[]`, or `(position, length, byte[])` overloads:
- `add()` — insert
- `contains()` — lookup
- `remove()` — delete a specific string
- `forEach(Consumer<Node>)` — iterate all nodes (protected, used in tests via `Checker`)
- `forEach(length, prefix, Consumer<Node>)` — iterate nodes under a prefix (protected)
- `removeStrings(length, prefix)` — remove all strings sharing a prefix (protected)

### radix4j-clordid

`ClientOrderIdSet` (package `org.limitless.clordid`) holds one trading day of ids per scope (a FIX session,
0–255); `rollover()` closes the day's arena. Ids are 1–32 printable ASCII characters (`Printable` validates
and packs them into 7 bits per character).
- `Pattern` — per scope: trailing decimal digits, decimal digits before a fixed-width suffix (`decimal(3)` for
  `ARB00045612X7Y`), fixed-width base 36, none, or auto (default: `PatternDetector` chooses from the scope's
  first 32 ids); fixed until `rollover()`
- Demotion — a scope creating counter containers faster than one per 4 ids, beyond a burst of 4,096, stops creating
  them until `rollover()`: its ids without an existing container go to `FallbackSet`
- `CounterSet` — ids matching the pattern: (scope, format, prefix + suffix) → prefix id in `PrefixTable`, then run,
  array, bitmap or Elias-Fano (sparse numbers, about L + 2 bits each) containers (`Chunk`) per 65,536-value chunk,
  in 16–8192 byte blocks in steps of 1.5 and 2
- `LinearIndex` — paged linear hash table of chained buckets, with int fields per id, behind `PrefixTable` and
  the `CounterSet` directory; grows one bucket at a time, never rehashes the whole table
- `FallbackSet` — other ids: a linear hash table per scope, buckets are slotted `Bucket` blocks in size classes
  of 32–1024 bytes, split one at a time; the bucket directory is off-heap in 8 KB `Chunk`s, only their
  addresses are on the heap; no per-id removal
- `BlockPools` — fsmp4j pools in size classes and 32-bit block addresses (segment + 1, block), used by both sets
- `CounterSet`, `FallbackSet` and the classes above are package-private: `ClientOrderIdSet` validates scopes, ids
  and the closed state; `statistics()` is the public view of their sizes and memory
