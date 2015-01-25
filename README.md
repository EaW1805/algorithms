Algorithms
===

Algorithm implementation for map traversal and fast data access for turn-based strategy game engine and client

## Requirements

1. Oracle Java 1.7 or newer
2. Maven 3
3. A working internet connection
4. The latest stable release of EaW1805/data artifact

## Configuration

No particular configuration is necessary apart from the username/password for accessing the mysql database.
These are provided as environmental properties (dbusername and dbpassword), that are passed using the -D<property>=<value>.
For example:

mvn3 -Ddbusername=example -Ddbpassword=mypassword package

If you are unsure about the settings please contact ichatz@gmail.com

## Execution

This module is not intended to run as a stand-alone mode. It is a core module to be used for all other modules of the repository (EaW1805).

Some test cases are included to test the operation of the algorithms.

## Maven Repository

The artifacts of the project are publicly available by the maven repository hosted on github.

Configure any poms that depend on the algorithms artifact by adding the following snippet to your pom file:

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

## Algorithms implemented

Implementations are based on the org.jgrapht library and the graph abstraction and in particular the SimpleDirectedGraph
implementation. The sectors of the game map are modeled as graph vertices.
Each pair of adjacent map sectors is modeled by a graph edge.
The source of an edge represents the starting sector for a motion path, and the target of an edge represents the ending
   sector. Therefore the terrain of the target sector defines the weight of the graph edge.
   In particular the weight of the edge is equal to the MP (Movement Point) costs of the terrain type of the target sector.

1. DistanceCalculator - uses the Bellman Ford Shortest Path algorithm to measure the distance measured in MPs between
a source and target sectors.
2. FogOfWarInspector - identifies all the sectors that are owned by a position, are allied, are within scout range,
 are within spy reporting range, or near the position's borders.
3. MovementShortestPath -  uses the Bellman Ford Shortest Path algorithm to identify the shortest path based on the sectors
where movement is eligible.
4. SupplyLinesConnectivity - identifies all the sectors that are reachable by the supply lines: (i) sectors that form the
home region of the position, (ii) all trade cities, (iii) barracks within 40MPs range of a trade city.
