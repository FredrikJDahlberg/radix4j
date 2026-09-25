Compact Radix Tree for Java
===========================

[![CI](https://github.com/FredrikJDahlberg/radix4j/actions/workflows/ci.yml/badge.svg)](https://github.com/FredrikJDahlberg/radix4j/actions/workflows/ci.yml)
[![Java](https://img.shields.io/badge/Java-23-blue)](https://openjdk.org/projects/jdk/23/)
[![License](https://img.shields.io/github/license/FredrikJDahlberg/radix4j)](LICENSE)

_Experimental_

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
