Compact Radix Tree for Java
===========================

[![CI](https://github.com/FredrikJDahlberg/radix4j/actions/workflows/ci.yml/badge.svg)](https://github.com/FredrikJDahlberg/radix4j/actions/workflows/ci.yml)
[![JitPack](https://jitpack.io/v/FredrikJDahlberg/radix4j.svg)](https://jitpack.io/#FredrikJDahlberg/radix4j)
[![Java](https://img.shields.io/badge/Java-23-blue)](https://openjdk.org/projects/jdk/23/)
[![License](https://img.shields.io/github/license/FredrikJDahlberg/radix4j)](LICENSE)

radix4j is a set of byte strings stored in a compressed radix tree (a trie where chains of single-child
nodes are merged into one node). Nodes live off-heap in fixed-size 64-byte blocks, so a tree
creates no garbage and places no load on the Java heap no matter how many strings it holds.

Usage
-----

### Dependency

radix4j is published through [JitPack](https://jitpack.io/#FredrikJDahlberg/radix4j), built from the git release tags.
Its dependency [fsmp4j](https://github.com/FredrikJDahlberg/fsmp4j) comes from JitPack as well:

```groovy
repositories {
    maven { url = uri('https://jitpack.io') }
}

dependencies {
    implementation 'com.github.FredrikJDahlberg:radix4j:<tag>'
}
```

### Example

```java
import org.limitless.radix4j.RadixTree;

RadixTree tree = new RadixTree();
try {
    tree.add("CUSTOMER-ORDER-2026-000001");          // true
    tree.add("CUSTOMER-ORDER-2026-000002");          // true
    tree.add("CUSTOMER-ORDER-2026-000002");          // false, already present

    // add a string from a range of a byte array, without creating a String
    byte[] buffer = "id=CUSTOMER-ORDER-2026-000003;".getBytes(StandardCharsets.US_ASCII);
    tree.add(3, 26, buffer);                         // true

    tree.contains("CUSTOMER-ORDER-2026-000003");     // true
    tree.remove("CUSTOMER-ORDER-2026-000001");       // true
    tree.contains("CUSTOMER-ORDER-2026-000001");     // false
    tree.size();                                     // 2
} finally {
    tree.close();                                    // frees the off-heap memory
}
```

`add`, `contains` and `remove` take a `String`, a `byte[]` or a range of a `byte[]`. A tree is not
thread-safe, and its memory is released only by `close()`.

A tree allocates its 64-byte blocks in segments and can use up to 65,536 segments. `new RadixTree()`
uses segments of 256 blocks (16 KiB), so a tree can grow to 1 GiB, about 20 million random 32-byte
strings. `new RadixTree(blocksPerSegment)` takes 64 to 65,536 blocks per segment: smaller segments
waste less memory in small trees, larger ones allow trees of up to 256 GiB.

Client order ids
----------------

The `radix4j-clordid` module holds `ClientOrderIdSet`, a duplicate check for FIX client order ids
(ClOrdID, tag 11): an id must be unique within one trading day per session. Ids are 1 to 32 printable
ASCII characters, and sessions are numbered 0 to 255 by the caller.

```groovy
dependencies {
    implementation 'com.github.FredrikJDahlberg:radix4j-clordid:<tag>'
}
```

```java
import org.limitless.clordid.ClientOrderIdSet;
import org.limitless.clordid.Pattern;

try (ClientOrderIdSet ids = new ClientOrderIdSet()) {
    ids.pattern(1, Pattern.none());                  // session 1 sends UUIDs

    byte[] id = "ORD-000001".getBytes(StandardCharsets.US_ASCII);
    ids.add(0, id, 0, id.length);                    // ADDED
    ids.add(0, id, 0, id.length);                    // DUPLICATE
    ids.add(2, id, 0, id.length);                    // ADDED, another session
    ids.contains(0, id, 0, id.length);               // true

    ids.rollover();                                  // next trading day, frees all ids at once
}
```

Ids that match their session's pattern, a trailing counter by default, are split into a prefix and a number,
and the numbers are kept in run, array and bitmap containers, so counters cost well under 1 byte per id.
Other ids, such as UUIDs, are packed into 7 bits per character and kept in a linear hash table per session,
at about 31 bytes per UUID. Everything lives off-heap in fsmp4j pools and grows a block at a time. The set
lives in memory only and is not thread-safe; whether a rejected order's id counts as used is up to the
caller.

[`ClientOrderIdSetExample`](radix4j-clordid/src/test/java/org/limitless/clordid/example/ClientOrderIdSetExample.java)
is a complete example: a FIX gateway that gives each session a scope and a pattern, and checks the ClOrdID of every
NewOrderSingle in place in the received bytes.

Algorithm
---------

### Nodes

A tree is built from 64-byte blocks of two kinds: *nodes*, which branch, and *buckets*, which hold
the unshared rest of up to 31 strings. Every node has the same fixed layout:

| Field    | Size | Purpose                                                        |
|----------|------|----------------------------------------------------------------|
| header   | 1 B  | string length (3 bits), child count (4 bits), "string ends here" flag (1 bit) |
| string   | 5 B  | inline prefix of up to 5 bytes                                 |
| contains | 2 B  | one "string ends at this key" bit per child                    |
| children | 44 B | 11 × 32-bit child addresses                                    |
| keys     | 11 B | the first byte of each child, one per child                    |
| overflow | 1 B  | position + 1 of the overflow key (see Insertion), 0 for none   |

A string is spelled out along a path as alternating *node prefixes* and *keys*: the bytes of a node's
inline prefix, then one key byte that selects a child, then the child's prefix, and so on. Each node
therefore consumes up to 6 bytes of input (5 prefix bytes plus 1 key byte).

A string may end in one of two places, each marked with a flag:

* at the end of a node's prefix — the header flag
* at a key — the key's bit in the `contains` mask

Because a string can end at a key, a key with nothing below it needs no child node: its child
address is `0` (empty). Short tails and branching points are stored in the parent instead of in
nodes of their own, which keeps the node count low.

### Buckets

Spelled out in nodes alone, every string would end in a chain of nodes holding its unshared tail, one
64-byte block per 6 bytes. Instead the tails below a point where strings stop sharing are packed
together in a bucket:

| Field   | Size | Purpose                                                              |
|---------|------|----------------------------------------------------------------------|
| header  | 1 B  | same byte as a node header, with the string length set to 7 (6 in a hybrid bucket), which no node uses |
| used    | 1 B  | bytes of entries in use                                              |
| entries | 62 B | up to 31 entries, each a length byte and a tail of 1–61 bytes, unsorted |

A new tail is appended while it fits, and a tail longer than 61 bytes is written as a chain of nodes
ending in a bucket. When a tail no longer fits, the bucket *bursts*: its tails and the new one are
sorted, and the block is rewritten in place as a node holding their common prefix (up to 5 bytes),
with a key for each next byte and the tails below each key in a new bucket, or in a subtree built the
same way when they do not fit in one.

A key with only a few tails would leave most of a bucket empty, so neighbouring keys of a node share a
*hybrid bucket*. Its tails start with their key byte, and lookup steps back one byte on reaching it.
A burst puts consecutive keys whose tails fit together in one hybrid bucket, and a new key joins the
bucket of one of its two nearest keys when it has room. A full hybrid bucket is split among its keys
without adding a level: keys whose tails still fit together keep sharing a bucket, and the others get
a bucket or a subtree of their own. A key points to a hybrid bucket only while the bucket holds tails
starting with that key, and nodes are never shared.

### Example

Adding `tea`, `ten`, `team`, `teammates` and `to` gives a tree of a single bucket holding all five
strings. Spelled out in nodes alone, they would take four nodes (`●` marks the end of a string):

```
"t"
 ├─ 'e' ──► ""
 │           ├─ 'a' ● ──► "m" ●
 │           │              └─ 'm' ──► "ates" ●
 │           └─ 'n' ●
 └─ 'o' ●
```

`tea`, `ten` and `to` end at keys and take no nodes of their own. `team` ends at the prefix of the
node below `a`, and `teammates` continues through key `m` into the node holding `ates`.

Adding the IDs `ORD-0001` to `ORD-0040` gives two nodes and three buckets:

```
"ORD-0"
 └─ '0' ──► ""
             ├─ '0' ─┬─► hybrid bucket "01" … "09", "10" … "19"
             ├─ '1' ─┘
             ├─ '2' ─┬─► hybrid bucket "20" … "39"
             ├─ '3' ─┘
             └─ '4' ──► bucket "0"
```

The first bucket burst at the seventh ID into the node `ORD-0` and a bucket below key `0`, which burst
in turn at the 21st ID. Keys `0` and `1` share a hybrid bucket, whose tails keep their key byte, and
so do `2` and `3`. Key `4` has a bucket of its own, since the bucket of `2` and `3` had no room left.

### Lookup

Starting at the root, lookup compares the node's prefix with the next bytes of the input and then
looks up the following input byte among the node's keys. The header and inline prefix share one
8-byte word, so each node is checked with a single memory read. Lookup fails as soon as a prefix
byte differs or no key matches, and succeeds when the input runs out exactly where a flag is set.
At a bucket, the rest of the input must equal one of its tails: each entry's length and first byte
are compared with a single 2-byte read, and a matching entry's tail 8 bytes at a time.

### Insertion

Insertion walks the tree like lookup, recording the visited nodes on a path stack, and stops at
the first point where the new string leaves the tree. What happens next depends on where that is:

| Where the string leaves the tree                     | Action                                                                                  |
|------------------------------------------------------|-----------------------------------------------------------------------------------------|
| it ends exactly at the end of a node's prefix        | set the node's flag                                                                     |
| it ends at an existing key                           | set the key's `contains` bit                                                            |
| it differs partway through a node's prefix           | *split*: the node keeps the common part, the next prefix byte becomes a key, and the rest of the prefix moves to a new child |
| it differs at the first byte of a node's prefix      | insert a new parent with an empty prefix and two keys: the node's first byte and the string's next byte |
| the prefix matches but no key matches the next byte  | add a key, whose tail joins the bucket of a neighbouring key when it has room           |
| a key matches but has no child                       | allocate a child below the key                                                          |
| it reaches a bucket                                  | add the rest of the string as a tail if it fits, otherwise *burst* the bucket, or split a full hybrid bucket among its keys |

Whatever remains of the string is then written as a new tail: a bucket when it fits in 61 bytes,
and otherwise a chain of nodes (5 prefix bytes and 1 key each) ending in a bucket. A bucket bursts in
place, keeping its address, so its parent needs no update.

A node holds at most 11 keys. When a full node needs another one, its last key is moved into a new
overflow node together with the new key, and the freed slot becomes an *empty key* (the value `0`)
that points at the overflow node. Empty keys consume no input: lookup follows them to keep
searching for the key byte. The node's overflow field marks the empty key, so `0` is still a valid
byte in a string. A key that shares a hybrid bucket gets a bucket of its own before it moves, so a
bucket is never shared by keys of two nodes.

### Removal

Removing a string clears its flag or `contains` bit, or deletes its tail from a bucket. A bucket left
empty is freed, and a key with no tails left in a hybrid bucket stops pointing at it. A key that ends
no string and has no child is dropped, and a node left with no keys and no string is freed. Using the
path stack recorded during the search, removal then walks back towards the root and repeats the
cleanup in each ancestor that became empty. Removing every string that starts with a prefix works the
same way, except that the whole subtree below the prefix is freed; when the prefix ends inside a
bucket, only the tails that match it are removed.

### Memory

Nodes and buckets are allocated from an [fsmp4j](https://github.com/fredrikjdahlberg/fsmp4j) block
pool that hands out 64-byte blocks from off-heap memory segments. A child address is 32 bits: a 16-bit segment
index and a 16-bit block index within the segment, with `0` reserved as the empty address. Nodes are
read and written through a few reusable flyweight objects that are moved from block to block, so
`add`, `contains` and `remove` allocate no Java objects apart from the byte array of a `String`
argument and the occasional doubling of the path stack in very deep trees.

Dependencies
------------

* [fsmp4j](https://github.com/fredrikjdahlberg/fsmp4j) — off-heap fixed-size memory pool (`com.github.FredrikJDahlberg:fsmp4j:v1.0.8`, from JitPack)

Build
-----

### Java Build

Build the project with [Gradle](http://gradle.org/) using this [build.gradle](https://github.com/fredrikjdahlberg/radix4j/blob/main/build.gradle) file.

You require the following to build radix4j

* The Latest release of Java 23. radix4j is tested with Java 23.

Full clean and build:

    $ ./gradlew

### Release

Set the version, commit, and push a `v<version>` tag; JitPack builds the tag the first time it is requested:

    $ .github/tag-release.sh 1.0.4

Benchmarks
----------

### Jmh

Run benchmarks:

    $ ./gradlew jmh

`./gradlew jmh` runs `RadixTreeBenchmark` and the `HashSet` baseline `HashMapBenchmark` over every data set
described under [Memory](#memory-1), which takes about an hour. To run one benchmark or data set, use the JMH
jar directly:

    $ ./gradlew :radix4j-benchmarks:jmhJar
    $ java -jar radix4j-benchmarks/build/libs/radix4j-benchmarks-1.0.0-jmh.jar RadixTreeBenchmark -p dataSet=session

`ClientOrderIdBenchmark` compares lookups in a `ClientOrderIdSet` and a `RadixTree`, and `MemoryComparison` takes
`clordid` in place of `radix` or `hashset` to measure a `ClientOrderIdSet`:

    $ java -jar radix4j-benchmarks/build/libs/radix4j-benchmarks-1.0.0-jmh.jar ClientOrderIdBenchmark
    $ java -Xmx4g -cp radix4j-benchmarks/build/libs/radix4j-benchmarks-1.0.0-jmh.jar org.limitless.radix4j.MemoryComparison clordid order 10000000

### Memory

Measured with `MemoryComparison`, which adds generated strings one at a time and reports the heap and
off-heap memory in use. Each structure runs in its own JVM with a 4 GB heap (`-Xmx4g`) on an Apple
M1 Pro (32 GB) with OpenJDK 23.0.2:

    $ ./gradlew :radix4j-benchmarks:jmhJar
    $ java -Xmx4g -cp radix4j-benchmarks/build/libs/radix4j-benchmarks-1.0.0-jmh.jar \
        org.limitless.radix4j.MemoryComparison <radix|hashset> <data set> <count>

The table below uses two data sets of 32-byte strings:

* `sequential` — IDs with a shared prefix and a counter, `CUSTOMER-ORDER-2026-000000000000`, `…001`, …
* `random` — 32 random alphanumeric characters

| Strings | HashSet&lt;String&gt; (heap)   | RadixTree, sequential (off-heap) | RadixTree, random (off-heap) |
|--------:|--------------------------------:|---------------------------------:|-----------------------------:|
| 10 M    | 1,058 MB · 111 B/string         | 37 MB · 4.0 B/string             | 461 MB · 48 B/string         |
| 30 M    | 3,234 MB · 113 B/string         | 111 MB · 3.9 B/string            | 1,531 MB · 54 B/string       |
| 60 M    | `OutOfMemoryError` at 38.7 M    | 223 MB · 3.9 B/string            | 3,051 MB · 53 B/string       |

`HashSet<String>` costs about 111 bytes per string whatever the content: a `String`, its `byte[]`, a
`HashMap.Node` and a table slot. With a 4 GB heap it fails with `OutOfMemoryError` after
38.7 million strings. RadixTree keeps its nodes off-heap, so it places no load on the heap or the
garbage collector, and its cost depends on how much the strings share. The sequential IDs share
everything but their last few bytes, and up to 31 of those short tails share a bucket, so they cost
about 4 bytes each: 60 million fit in 223 MB. Random strings share almost nothing beyond their first few
bytes, so only two of their 28-byte tails fit in a bucket: with the nodes above, about 50 bytes per
string, still half as much as a `HashSet`. RadixTree is at its best with keys that share long prefixes, such as IDs, paths,
symbols and URLs, but random keys such as UUIDs or hashes also take less memory than in a `HashSet`.

#### Order and quote IDs

The data sets below are modelled on samples of real client order and quote IDs from trading venues,
generated at 10 million strings each:

| Data set  | Format                                                                  | Length   | HashSet&lt;String&gt; | RadixTree  |
|-----------|-------------------------------------------------------------------------|---------:|----------------------:|-----------:|
| `session` | 19-digit session id, `_` and a counter, new session every 5,000         | 21–24 B  | 103 B/string          | 5.9 B/string  |
| `base36`  | 11-digit session id, 3 letters and a 7-character base-36 counter        | 21 B     | 105 B/string          | 7.4 B/string  |
| `order`   | 2 letters and an 8-digit counter that skips ahead 2–8 one time in three | 10 B     | 95 B/string           | 5.9 B/string  |
| `base64`  | 16 random bytes in base64url                                            | 23 B     | 103 B/string          | 44 B/string   |
| `sparse`  | 3-character prefix and a 10-digit number increasing by 1–512            | 13 B     | 95 B/string           | 8.6 B/string  |

IDs made of a session or prefix and a counter cost 6–9 bytes per string, 11 to 17 times less than a
`HashSet`. Random IDs cost about 44 bytes, for the same reason as the random strings above. In
`sparse`, numbers that grow by up to 512 at a time leave their last three digits nearly random, so
the strings below most keys have only a few short tails, which neighbouring keys pack into shared
hybrid buckets.

### Speed

Measured with `./gradlew jmh` on an Apple M1 Pro (32 GB) with OpenJDK 23.0.2, using 25 million
strings from each of the data sets above. `add`, `contains` and `remove` are timed over 25 million
calls, in the order the strings were generated, in a single shot; the table shows the average time
per call. The `HashSet` baseline creates a `String` from the bytes on every call, as a caller holding
the bytes would have to.

| Data set     | `add` RadixTree | `add` HashSet | `contains` RadixTree | `contains` HashSet | `remove` RadixTree | `remove` HashSet |
|--------------|----------------:|--------------:|---------------------:|-------------------:|-------------------:|-----------------:|
| `sequential` | 225 ns          | 83 ns         | 163 ns               | 59 ns              | 162 ns             | 63 ns            |
| `session`    | 171 ns          | 55 ns         | 132 ns               | 52 ns              | 133 ns             | 49 ns            |
| `base36`     | 242 ns          | 47 ns         | 158 ns               | 46 ns              | 219 ns             | 42 ns            |
| `order`      | 206 ns          | 80 ns         | 137 ns               | 70 ns              | 138 ns             | 62 ns            |
| `sparse`     | 194 ns          | 166 ns        | 128 ns               | 199 ns             | 139 ns             | 169 ns           |
| `random`     | 873 ns          | 200 ns        | 717 ns               | 221 ns             | 1,068 ns           | 196 ns           |
| `base64`     | 777 ns          | 205 ns        | 679 ns               | 204 ns             | 775 ns             | 183 ns           |

The remaining operations are single calls over the full data set. The prefix is the longest prefix
shared by all strings, which matches every string, except in `random` and `base64`, which share
none: there a one-character prefix matches 1.6% of the strings.

| Data set     | iterate all RadixTree | iterate all HashSet | iterate prefix RadixTree | iterate prefix HashSet | remove prefix RadixTree | remove prefix HashSet |
|--------------|----------------------:|--------------------:|-------------------------:|-----------------------:|------------------------:|----------------------:|
| `sequential` | 33 ms                 | 242 ms              | 33 ms                    | 338 ms                 | 97 ms                   | 420 ms                |
| `session`    | 38 ms                 | 261 ms              | 37 ms                    | 398 ms                 | 102 ms                  | 421 ms                |
| `base36`     | 88 ms                 | 153 ms              | 90 ms                    | 218 ms                 | 144 ms                  | 309 ms                |
| `order`      | 62 ms                 | 378 ms              | 62 ms                    | 398 ms                 | 196 ms                  | 524 ms                |
| `sparse`     | 75 ms                 | 394 ms              | 74 ms                    | 489 ms                 | 220 ms                  | 564 ms                |
| `random`     | 1,033 ms              | 519 ms              | 23 ms                    | 677 ms                 | 45 ms                   | 632 ms                |
| `base64`     | 770 ms                | 554 ms              | 18 ms                    | 561 ms                 | 37 ms                   | 678 ms                |

Single-string operations are two to five times faster in the `HashSet` for IDs built from a counter:
a hash lookup touches one or two cache lines, while RadixTree visits one node per 6 bytes of string
and then scans a bucket. Calling in generation order also helps the `HashSet` here, since consecutive
IDs hash to nearby table slots. Random strings are slow in both, but RadixTree pays more: each lookup
ends in a bucket somewhere in 1.3 GB of blocks. `sparse` is the exception, where RadixTree is close
on `add` and ahead on `contains` and `remove`. Operations over many strings favor RadixTree for IDs
with shared prefixes, two to eleven times faster: it walks compact blocks of up to 31 strings instead
of scattered objects, and prefix operations only visit the matching subtree. Iterating a tree of
random strings is one and a half to two times slower, because it holds nearly a block per string.
RadixTree iteration visits tree nodes and buckets rather than individual strings.

License
-------

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for the full text, and
<https://www.apache.org/licenses/LICENSE-2.0> for the canonical copy. Copyright is recorded in
[NOTICE](NOTICE); §4d obliges anyone redistributing radix4j to carry that file forward. Both files
ship inside the jar under `META-INF/`.
