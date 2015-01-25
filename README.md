Algorithms
===

Algorithm implementation for map traversal and fast data access for turn-based strategy game engine and client

## Requirements

1. Oracle Java 1.7 or newer
2. Maven 3
3. A working internet connection

## Configuration

No particular configuration is necessary apart from the username/password for accessing the mysql database.
These are provided as environmental properties (dbusername and dbpassword), that are passed using the -D<property>=<value>.
For example:

mvn3 -Ddbusername=example -Ddbpassword=mypassword package

If you are unsure about the settings please contact ichatz@gmail.com

## Execution

This module is not intended to run as a stand-alone mode. It is a core module to be used for all other modules of the repository (EaW1805).

Some test cases are included to test the operation of the data managers.

## Maven Repository

The artifacts of the project are publicly available by the maven repository hosted on github.

Configure any poms that depend on the data artifact by adding the following snippet to your pom file:

```
<repositories>
    <repository>
        <id>EaW1805-algorithms-mvn-repo</id>
        <url>https://raw.github.com/EaW1805/algorithms/mvn-repo/</url>
        <snapshots>
            <enabled>true</enabled>
            <updatePolicy>always</updatePolicy>
        </snapshots>
    </repository>
</repositories>
```