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

Algorithm
---------

### Nodes

A tree is built from 64-byte blocks of two kinds: *nodes*, which branch, and *leaves*, which hold
the unshared rest of a single string. Every node has the same fixed layout:

| Field    | Size | Purpose                                                        |
|----------|------|----------------------------------------------------------------|
| header   | 1 B  | string length (3 bits), child count (4 bits), "string ends here" flag (1 bit) |
| string   | 5 B  | inline prefix of up to 5 bytes                                 |
| contains | 2 B  | one "string ends at this key" bit per child                    |
| children | 44 B | 11 × 32-bit child addresses                                    |
| keys     | 11 B | the first byte of each child, one per child                    |
| pad      | 1 B  | pads the node to 64 bytes                                      |

A string is spelled out along a path as alternating *node prefixes* and *keys*: the bytes of a node's
inline prefix, then one key byte that selects a child, then the child's prefix, and so on. Each node
therefore consumes up to 6 bytes of input (5 prefix bytes plus 1 key byte).

A string may end in one of two places, each marked with a flag:

* at the end of a node's prefix — the header flag
* at a key — the key's bit in the `contains` mask

Because a string can end at a key, a key with nothing below it needs no child node: its child
address is `0` (empty). Short tails and branching points are stored in the parent instead of in
nodes of their own, which keeps the node count low.

### Leaves

A tail longer than a node's 5-byte prefix is stored in a leaf instead of a chain of nodes:

| Field  | Size | Purpose                                                              |
|--------|------|----------------------------------------------------------------------|
| header | 1 B  | same byte as a node header, with the string length set to 7, which no node uses |
| length | 1 B  | tail length, 6–62                                                    |
| tail   | 62 B | the rest of the string                                               |

A leaf has no children and always ends one string, so iteration and subtree removal treat it like
a node with a flagged prefix. A tail of up to 62 bytes takes one block, where a chain of nodes would
take one block per 6 bytes. Tails longer than 62 bytes are written as a chain of nodes ending in a
full leaf. For strings that share little beyond their first few bytes, such as random IDs, leaves
cut the memory per string by about a factor of four.

### Example

Adding `tea`, `ten`, `team`, `teammates` and `to` produces four nodes (`●` marks the end of a string):

```
"t"
 ├─ 'e' ──► ""
 │           ├─ 'a' ● ──► "m" ●
 │           │              └─ 'm' ──► "ates" ●
 │           └─ 'n' ●
 └─ 'o' ●
```

`tea`, `ten` and `to` end at keys and take no nodes of their own. `team` ends at the prefix of the
node below `a`, and `teammates` continues through key `m` into the node holding `ates`. No tail
is longer than 5 bytes, so the tree has no leaves.

Adding `abcdefghij` and then `abcXYZ123456` gives a node and two leaves:

```
"abc"
 ├─ 'd' ──► leaf "efghij" ●
 └─ 'X' ──► leaf "YZ123456" ●
```

### Lookup

Starting at the root, lookup compares the node's prefix with the next bytes of the input and then
looks up the following input byte among the node's keys. The header and inline prefix share one
8-byte word, so each node is checked with a single memory read. Lookup fails as soon as a prefix
byte differs or no key matches, and succeeds when the input runs out exactly where a flag is set.
At a leaf, the rest of the input must equal the tail, which is compared 8 bytes at a time.

### Insertion

Insertion walks the tree like lookup, recording the visited nodes on a path stack, and stops at
the first point where the new string leaves the tree. What happens next depends on where that is:

| Where the string leaves the tree                     | Action                                                                                  |
|------------------------------------------------------|-----------------------------------------------------------------------------------------|
| it ends exactly at the end of a node's prefix        | set the node's flag                                                                     |
| it ends at an existing key                           | set the key's `contains` bit                                                            |
| it differs partway through a node's prefix           | *split*: the node keeps the common part, the next prefix byte becomes a key, and the rest of the prefix moves to a new child |
| it differs at the first byte of a node's prefix      | insert a new parent with an empty prefix and two keys: the node's first byte and the string's next byte |
| the prefix matches but no key matches the next byte  | add a key                                                                               |
| a key matches but has no child                       | allocate a child below the key                                                          |
| it differs from, ends inside or extends past a leaf's tail | *split the leaf*: the leaf's block becomes a node holding the common part (preceded by a chain of nodes when the common part is longer than 5 bytes), with one key for each string that continues |

Whatever remains of the string is then written as a new tail: a node when it fits in 5 bytes, a
leaf when it fits in 62, and otherwise a chain of nodes (5 prefix bytes and 1 key each) ending in a
leaf. Splitting a leaf in place keeps its address, so its parent needs no update.

A node holds at most 11 keys. When a full node needs another one, its last key is moved into a new
overflow node together with the new key, and the freed slot becomes an *empty key* (the value `0`)
that points at the overflow node. Empty keys consume no input: lookup follows them to keep
searching for the key byte. The byte value `0` is therefore reserved for this marker.

### Removal

Removing a string clears its flag or `contains` bit. A key that ends no string and has no child is
dropped, and a node left with no keys and no string is freed, as is the leaf of a removed string. Using the path stack recorded during
the search, removal then walks back towards the root and repeats the cleanup in each ancestor that
became empty. Removing every string that starts with a prefix works the same way, except that the
whole subtree below the prefix is freed.

### Memory

Nodes and leaves are allocated from an [fsmp4j](https://github.com/fredrikjdahlberg/fsmp4j) block
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

`./gradlew jmh` runs `RadixTreeBenchmark` only. To include the `HashMap` comparison, run the JMH jar directly:

    $ ./gradlew :radix4j-benchmarks:jmhJar
    $ java -jar radix4j-benchmarks/build/libs/radix4j-benchmarks-1.0.0-jmh.jar 'RadixTreeBenchmark|HashMapBenchmark'

### Memory

Measured with `MemoryComparison`, which adds 32-byte strings one at a time and reports the heap and
off-heap memory in use. Each structure runs in its own JVM with a 4 GB heap (`-Xmx4g`) on an Apple
M1 Pro (32 GB) with OpenJDK 23.0.2:

    $ ./gradlew :radix4j-benchmarks:jmhJar
    $ java -Xmx4g -cp radix4j-benchmarks/build/libs/radix4j-benchmarks-1.0.0-jmh.jar \
        org.limitless.radix4j.MemoryComparison <radix|hashset> <sequential|random> <count>

Two data sets were used:

* *sequential* — IDs with a shared prefix and a counter, `CUSTOMER-ORDER-2026-000000000000`, `…001`, …
* *random* — 32 random alphanumeric characters

| Strings    | HashSet&lt;String&gt; (heap)          | RadixTree, sequential (off-heap) | RadixTree, random (off-heap) |
|-----------:|---------------------------------------:|---------------------------------:|-----------------------------:|
| 10 M       | 1,058 MB                               | 67 MB                            | 3,237 MB                     |
| 30 M       | 3,234 MB                               | 203 MB                           | 9,809 MB                     |
| 60 M       | `OutOfMemoryError` at 38.7 M           | 406 MB                           | not run                      |
| per string | ~111 bytes                             | ~7 bytes                         | ~340 bytes                   |

`HashSet<String>` costs about 111 bytes per string whatever the content: a `String`, its `byte[]`, a
`HashMap.Node` and a table slot. With a 4 GB heap it fails with `OutOfMemoryError` after
38.7 million strings. RadixTree keeps its nodes off-heap, so it places no load on the heap or the
garbage collector, and its cost depends on how much the strings share. The sequential IDs share
everything but their last few bytes and cost about 7 bytes each, so 60 million fit in 406 MB.
Random strings share almost nothing beyond their first few bytes. Each one needs about five 64-byte
nodes of its own, about three times the memory of a `HashSet`. RadixTree is the better choice for
keys with long common prefixes, such as IDs, paths, symbols and URLs, and the wrong choice for
random keys such as UUIDs or hashes.

### Speed

Measured on an Apple M1 Pro (32 GB) with OpenJDK 23.0.2. The data set is 25 million 18-byte strings
`ABCDEFGHI000000000` … `ABCDEFGHI025000000`. `add`, `contains` and `remove` are timed over 25 million
calls in a single shot; the table shows the average time per call. The remaining operations are
single calls over the full data set. The prefix `ABCDEFGHI0` matches every string.

| Operation                              | RadixTree  | HashSet&lt;String&gt; |
|----------------------------------------|-----------:|----------------------:|
| `add`                                  | 154 ns/op  | 53 ns/op              |
| `contains`                             | 119 ns/op  | 45 ns/op              |
| `remove`                               | 148 ns/op  | 43 ns/op              |
| iterate all                            | 67 ms      | 215 ms                |
| iterate strings with prefix            | 66 ms      | 276 ms                |
| remove strings with prefix             | 116 ms     | 364 ms                |

The `HashSet` baseline creates a `String` from the bytes on every call, as a caller holding the bytes
would have to. Single-string operations are about three times faster in the `HashSet`: a hash
lookup usually touches one or two cache lines, while RadixTree visits one node per 6 bytes of
string. Operations over many strings favor RadixTree: it walks compact nodes instead of scattered
objects, and prefix operations only visit the matching subtree. RadixTree iteration visits tree
nodes rather than individual strings.

License
-------

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for the full text, and
<https://www.apache.org/licenses/LICENSE-2.0> for the canonical copy. Copyright is recorded in
[NOTICE](NOTICE); §4d obliges anyone redistributing radix4j to carry that file forward. Both files
ship inside the jar under `META-INF/`.
