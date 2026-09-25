Compact Radix Tree for Java
===========================

[![CI](https://github.com/FredrikJDahlberg/radix4j/actions/workflows/ci.yml/badge.svg)](https://github.com/FredrikJDahlberg/radix4j/actions/workflows/ci.yml)
[![Java](https://img.shields.io/badge/Java-23-blue)](https://openjdk.org/projects/jdk/23/)
[![License](https://img.shields.io/github/license/FredrikJDahlberg/radix4j)](LICENSE)

radix4j is a set of byte strings stored in a compressed radix tree (a trie where chains of single-child
nodes are merged into one node). Nodes live off-heap in fixed-size 64-byte blocks, so a tree
creates no garbage and places no load on the Java heap no matter how many strings it holds.

Algorithm
---------

### Nodes

Every node has the same fixed layout:

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
node below `a`, and `teammates` continues through key `m` into the node holding `ates`.

### Lookup

Starting at the root, lookup compares the node's prefix with the next bytes of the input and then
looks up the following input byte among the node's keys. The header and inline prefix share one
8-byte word, so each node is checked with a single memory read. Lookup fails as soon as a prefix
byte differs or no key matches, and succeeds when the input runs out exactly where a flag is set.

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

Whatever remains of the string is then written as a chain of new nodes, 5 prefix bytes and 1 key
per node, with the final byte flagged.

A node holds at most 11 keys. When a full node needs another one, its last key is moved into a new
overflow node together with the new key, and the freed slot becomes an *empty key* (the value `0`)
that points at the overflow node. Empty keys consume no input: lookup follows them to keep
searching for the key byte. The byte value `0` is therefore reserved for this marker.

### Removal

Removing a string clears its flag or `contains` bit. A key that ends no string and has no child is
dropped, and a node left with no keys and no string is freed. Using the path stack recorded during
the search, removal then walks back towards the root and repeats the cleanup in each ancestor that
became empty. Removing every string that starts with a prefix works the same way, except that the
whole subtree below the prefix is freed.

### Memory

Nodes are allocated from an [fsmp4j](https://github.com/fredrikjdahlberg/fsmp4j) block pool that
hands out 64-byte blocks from off-heap memory segments. A child address is 32 bits: a 16-bit segment
index and a 16-bit block index within the segment, with `0` reserved as the empty address. Nodes are
read and written through a few reusable flyweight objects that are moved from block to block, so
`add`, `contains` and `remove` allocate no Java objects apart from the byte array of a `String`
argument and the occasional doubling of the path stack in very deep trees.

Dependencies
------------

* [fsmp4j](https://github.com/fredrikjdahlberg/fsmp4j) — off-heap fixed-size memory pool (`org.limitless:fsmp4j:1.0.5`)

`fsmp4j` is published to GitHub Packages. Add the repository and credentials to `~/.gradle/gradle.properties`:

    gpr.user=<github username>
    gpr.key=<github personal access token with read:packages scope>

Build
-----

### Java Build

Build the project with [Gradle](http://gradle.org/) using this [build.gradle](https://github.com/fredrikjdahlberg/radix4j/blob/main/build.gradle) file.

You require the following to build radix4j

* The Latest release of Java 23. radix4j is tested with Java 23.

Full clean and build:

    $ ./gradlew

Benchmarks
----------

### Jmh

Run benchmarks:

    $ ./gradlew jmh

License
-------

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for the full text, and
<https://www.apache.org/licenses/LICENSE-2.0> for the canonical copy. Copyright is recorded in
[NOTICE](NOTICE); §4d obliges anyone redistributing radix4j to carry that file forward. Both files
ship inside the jar under `META-INF/`.
