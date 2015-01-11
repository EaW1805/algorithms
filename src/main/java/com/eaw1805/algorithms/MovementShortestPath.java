package com.eaw1805.algorithms;

import com.eaw1805.core.GameEngine;
import com.eaw1805.data.constants.ArmyConstants;
import com.eaw1805.data.constants.NationConstants;
import com.eaw1805.data.constants.ProductionSiteConstants;
import com.eaw1805.data.constants.RegionConstants;
import com.eaw1805.data.constants.RelationConstants;
import com.eaw1805.data.constants.TerrainConstants;
import com.eaw1805.data.dto.common.SectorDTO;
import com.eaw1805.data.dto.web.movement.PathDTO;
import com.eaw1805.data.dto.web.movement.PathSectorDTO;
import com.eaw1805.data.model.Game;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jgrapht.alg.BellmanFordShortestPath;
import org.jgrapht.graph.SimpleDirectedWeightedGraph;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Uses graph algorithms to identify the all possible shortest paths for the movement of a unit.
 */
public class MovementShortestPath
        implements ArmyConstants, TerrainConstants, ProductionSiteConstants, NationConstants,
        RegionConstants, RelationConstants {

    /**
     * a log4j logger to print messages.
     */
    private static final Logger LOGGER = LogManager.getLogger(MovementShortestPath.class);

    /**
     * Stores the graph for computing the shortest paths.
     */
    private final SimpleDirectedWeightedGraph<SectorDTO, SimpleWeightedEdge> sectorsGraph;

    /**
     * The maximum window of sectors to consider.
     */
    private final SectorDTO sectors[][];

    /**
     * The minimum X coordinate.
     */
    private final int minX;

    /**
     * The minimum Y coordinate.
     */
    private final int minY;

    /**
     * The starting X coordinate.
     */
    private final int baseX;

    /**
     * The starting Y coordinate.
     */
    private final int baseY;

    /**
     * The type of the unit that will be moved.
     */
    private final int unitType;

    /**
     * The maximum number of neutral sectors that the unit can cross.
     */
    private final int maxNeutral;

    /**
     * The maximum number of enemy sectors that the unit can conquer.
     */
    private final int maxConquer;

    /**
     * The number of war ships in the fleet.
     */
    private final int totWarShips;

    private final List<Integer> otherNations;

    /**
     * Default constructor.
     *
     * @param thisGame              the game to examine.
     * @param sectors               the array of sectors to examine.
     * @param xBase                 the x coordinate of the starting position.
     * @param yBase                 the y coordinate of the starting position.
     * @param minX                  the minimum X coordinate.
     * @param minY                  the minimum Y coordinate.
     * @param unitType              the type of the unit.
     * @param ownerId               the owner of the unit.
     * @param neutralConquerCounter the maximum number of sectors that the unit can conquer.
     * @param conquerCounter        the maximum number of enemy sectors the unit can conquer.
     * @param warShips              the number of warships in case of sea movement.
     */
    public MovementShortestPath(final Game thisGame,
                                final SectorDTO sectors[][],
                                final int xBase,
                                final int yBase,
                                final int minX,
                                final int minY,
                                final int unitType,
                                final int ownerId,
                                final int neutralConquerCounter,
                                final int conquerCounter,
                                final int warShips,
                                final List<Integer> nationsLoaded,
                                final Map<Integer, Map<Integer, Integer>> relationsMap) {
        sectorsGraph = new SimpleDirectedWeightedGraph<SectorDTO, SimpleWeightedEdge>(SimpleWeightedEdge.class);
        this.sectors = sectors;
        baseX = xBase;
        baseY = yBase;
        this.minX = minX;
        this.minY = minY;
        this.unitType = unitType;
        otherNations = nationsLoaded;

        if (unitType == BRIGADE || unitType == CORPS || unitType == ARMY) {
            maxNeutral = neutralConquerCounter;
            maxConquer = conquerCounter;
            totWarShips = 0;

        } else {
            maxNeutral = 100;
            maxConquer = 100;
            totWarShips = warShips;
        }

        createGraphFromSectors(thisGame, ownerId, relationsMap);
    }

    /**
     * Construct the graph from the sectors.
     */
    public void createGraphFromSectors(final Game thisGame, final int ownerId,
                                       final Map<Integer, Map<Integer, Integer>> relationsMap) {
        final boolean navy = !(unitType == ARMY
                || unitType == CORPS
                || unitType == BRIGADE
                || unitType == COMMANDER
                || unitType == SPY
                || unitType == BAGGAGETRAIN);

        final boolean canCrossFreely = (unitType == COMMANDER || unitType == SPY);
        final int totSize = sectors.length;

        // Add the vertices of the graph
        for (final SectorDTO[] thisSectorCol : sectors) {
            for (int thisY = 0; thisY < totSize; thisY++) {
                if (thisSectorCol[thisY] != null) {
                    if (!navy) {
                        // Ignore Ocean & Impassable tiles
                        if (thisSectorCol[thisY].getTerrain().getId() != TERRAIN_O
                                && thisSectorCol[thisY].getTerrain().getId() != TERRAIN_I) {

                            // check relations
                            if ((thisSectorCol[thisY].getX() == baseX && thisSectorCol[thisY].getY() == baseY)
                                    || canCrossFreely
                                    || canCross(thisGame, ownerId, relationsMap, thisSectorCol[thisY])) {
                                sectorsGraph.addVertex(thisSectorCol[thisY]);
                            }
                        }

                    } else if ((thisSectorCol[thisY].getTerrain().getId() == TERRAIN_O)
                            || (thisSectorCol[thisY].getProductionSiteId() == PS_BARRACKS)
                            || (thisSectorCol[thisY].getProductionSiteId() == PS_BARRACKS_FH)
                            || (thisSectorCol[thisY].getProductionSiteId() == PS_BARRACKS_FL)
                            || (thisSectorCol[thisY].getProductionSiteId() == PS_BARRACKS_FM)
                            || (thisSectorCol[thisY].getProductionSiteId() == PS_BARRACKS_FS)) {

                        // check relations of sector
                        if (canEnterPort(thisGame, ownerId, relationsMap, thisSectorCol[thisY])) {
                            sectorsGraph.addVertex(thisSectorCol[thisY]);
                        }
                    }
                }
            }
        }

        // Add the edges of the graph
        for (final SectorDTO sector : sectorsGraph.vertexSet()) {
            createSector(sector.getX() - minX + 1, sector.getY() - minY + 1, navy);
        }
    }

    private boolean canCross(final Game thisGame, final int ownerId,
                             final Map<Integer, Map<Integer, Integer>> relationsMap,
                             final SectorDTO sector) {
        // All units can move over Neutral Sectors
        // or sectors owned by Friendly and Allied nations.
        if (sector.getNationId() == ownerId) {
            // this is the owner of the sector.
            return true;

        } else if (sector.getNationId() == NATION_NEUTRAL) {
            // this is a neutral sector
            return true;

        } else {
            // Check Sector's relations against owner.
            final int relation = relationsMap.get(sector.getNationId()).get(ownerId);

            if (unitType == BAGGAGETRAIN || unitType == SHIP || unitType == FLEET) {
                if (relation <= REL_TRADE) {
                    for (Integer otherNation : otherNations) {
                        if (otherNation != sector.getNationId()) {
                            final int otherRelation = relationsMap.get(sector.getNationId()).get(otherNation);
                            if (otherRelation > REL_PASSAGE) {
                                return false;
                            }
                        }
                    }
                    return true;
                }
                return false;

            } else if (relation <= REL_PASSAGE) {
                return true;

            } else {
                if (unitType == ARMY || unitType == CORPS || unitType == BRIGADE) {
                    // Armies and Corps are also allowed to move over enemy territories
                    // Need to check if we have war against sector's owner
                    final int ourRelation = relationsMap.get(ownerId).get(sector.getNationId());

                    sector.setNeedsConquer(ourRelation == REL_WAR
                            || (sector.getRegionId() != EUROPE && ourRelation == REL_COLONIAL_WAR));

                    return (ourRelation == REL_WAR
                            || (sector.getRegionId() != EUROPE && ourRelation == REL_COLONIAL_WAR));
                }
            }
        }

        return false;
    }

    private boolean canForceMarch(final Game thisGame, final int ownerId,
                                  final Map<Integer, Map<Integer, Integer>> relationsMap,
                                  final SectorDTO sector) {
        // All units can move over Neutral Sectors
        // or sectors owned by Friendly and Allied nations.
        if (sector.getNationId() == ownerId) {
            // this is the owner of the sector.
            return true;

        } else if (sector.getNationId() == NATION_NEUTRAL) {
            // this is a neutral sector
            return false;

        } else {
            // Check Sector's relations against owner.
            final int relation = relationsMap.get(sector.getNationId()).get(ownerId);

            return (relation == REL_ALLIANCE);
        }
    }

    private boolean canEnterPort(final Game thisGame, final int ownerId,
                                 final Map<Integer, Map<Integer, Integer>> relationsMap,
                                 final SectorDTO sector) {
        // Ships can move over Neutral Sectors
        // or sectors owned by Friendly and Allied nations.
        if (sector.getNationId() == NATION_NEUTRAL) {
            // this is a neutral sector
            return true;

        } else if (sector.getNationId() == ownerId) {
            // this is the owner of the sector.
            return true;

        } else {
            // Check Sector's relations against owner.
            final int relation = relationsMap.get(sector.getNationId()).get(ownerId);

            return (totWarShips == 0 && relation <= REL_TRADE) || (relation <= REL_PASSAGE);
        }
    }

    /**
     * Add all edges for this sector.
     *
     * @param posX   the horizontal position of the sector.
     * @param posY   the vertical position of the sector.
     * @param isNavy if this is a sea movement.
     */
    private void createSector(final int posX, final int posY, final boolean isNavy) {
        addEdge(posX, posY, posX - 1, posY - 1, isNavy);
        addEdge(posX, posY, posX - 1, posY, isNavy);
        addEdge(posX, posY, posX - 1, posY + 1, isNavy);
        addEdge(posX, posY, posX, posY - 1, isNavy);
        addEdge(posX, posY, posX, posY + 1, isNavy);
        addEdge(posX, posY, posX + 1, posY - 1, isNavy);
        addEdge(posX, posY, posX + 1, posY, isNavy);
        addEdge(posX, posY, posX + 1, posY + 1, isNavy);
    }

    /**
     * Add an edge to the directed weighted graph.
     *
     * @param posX   the X coordinate of the vertex.
     * @param posY   the Y coordinate of the vertex.
     * @param thatX  the X coordinate of the neighboring vertex.
     * @param thatY  the Y coordinate of the neighboring vertex.
     * @param isNavy if this is a sea movement.
     */
    private void addEdge(final int posX, final int posY, final int thatX, final int thatY, final boolean isNavy) {
        if (sectors[thatX][thatY] != null && sectorsGraph.containsVertex(sectors[thatX][thatY])) {
            final SimpleWeightedEdge thisEdge = new SimpleWeightedEdge();
            final int mpCost;
            if (isNavy) {
                // Fixed costs for Fleets & Ships
                // It will cost one extra movement point per storm coordinate passing through
                if (sectors[thatX][thatY].getStorm() > 0) {
                    mpCost = 2;

                } else {
                    mpCost = 1;
                }

            } else {
                // Use Terrain base costs plus modifiers
                mpCost = sectors[thatX][thatY].getTerrain().getActualMPs();
            }

            sectorsGraph.setEdgeWeight(thisEdge, mpCost);
            sectorsGraph.addEdge(sectors[posX][posY], sectors[thatX][thatY], thisEdge);
        }
    }

    /**
     * identify all possible paths starting from base sector.
     *
     * @param totMP     the total available MPs.
     * @param maxLength the maximum length in sectors.
     * @return a set of paths.
     */
    public Set<PathDTO> getAllPaths(final Game thisGame,
                                    final int ownerId,
                                    final int totMP,
                                    final int maxLength,
                                    final Map<Integer, Map<Integer, Integer>> relationsMap) {
        final Set<PathDTO> paths = new HashSet<PathDTO>();
        final int actualBaseX = baseX - minX + 1;
        final int actualBaseY = baseY - minY + 1;

        if (actualBaseX < 0
                || actualBaseY < 0
                || actualBaseX > sectors.length
                || actualBaseY > sectors[0].length) {
            return paths;
        }

        if (!sectorsGraph.containsVertex(sectors[actualBaseX][actualBaseY])) {
            return paths;
        }

        // Compute single-source shortest paths
        final BellmanFordShortestPath<SectorDTO, SimpleWeightedEdge> dsp = new BellmanFordShortestPath<SectorDTO, SimpleWeightedEdge>(sectorsGraph, sectors[actualBaseX][actualBaseY], maxLength);

        final ExecutorService executorService = Executors.newFixedThreadPool(GameEngine.MAX_THREADS);
        final List<Future<PathDTO>> futures = new ArrayList<Future<PathDTO>>();

        // Add all shortest paths
        final int totSize = sectors.length;
        for (int posX = 0; posX < totSize; posX++) {
            for (int posY = 0; posY < totSize; posY++) {
                if (posX == actualBaseX && posY == actualBaseY) {
                    continue;
                }

                if (sectorsGraph.containsVertex(sectors[posX][posY])) {
                    if (dsp.getCost(sectors[posX][posY]) <= totMP) {
                        final SectorDTO thisSector = sectors[posX][posY];
                        final SectorDTO actualBase = sectors[actualBaseX][actualBaseY];

                        futures.add(executorService.submit(new Callable<PathDTO>() {
                            public PathDTO call() {
                                final StringBuilder strBuilder = new StringBuilder();
                                final StringBuilder pathBuilder = new StringBuilder();

                                int counterNeutral = maxNeutral;
                                int counterConquer = maxConquer;

                                // Extract path
                                final List<SimpleWeightedEdge> edgePath = dsp.getPathEdgeList(thisSector);
                                final List<PathSectorDTO> sectorsPath = new ArrayList<PathSectorDTO>();

                                // Initial sector
                                PathSectorDTO prevSector = cloneImportant(thisGame, ownerId, relationsMap, actualBase);
                                sectorsPath.add(prevSector);
                                strBuilder.append(prevSector.positionToString());

                                int totalCost = 0;
                                PathSectorDTO dblPrevSector = null;

                                // detect if path includes one non-allied sector
                                boolean canForceMarch = true;

                                for (final SimpleWeightedEdge edge : edgePath) {
                                    final SectorDTO theSector = (SectorDTO) edge.getTarget();
                                    final PathSectorDTO pathSector = cloneImportant(thisGame, ownerId, relationsMap, theSector);

                                    canForceMarch &= pathSector.getCanForceMarch();

                                    // Find image to use
                                    if (dblPrevSector != null) {
                                        final String from = getStartingDirection(dblPrevSector.getX(), dblPrevSector.getY(), prevSector.getX(), prevSector.getY());
                                        final String to = getEndingDirection(prevSector.getX(), prevSector.getY(), pathSector.getX(), pathSector.getY());
                                        prevSector.setPath("move-" + from + "-" + to);
                                        pathBuilder.append(prevSector.getPath());
                                        pathBuilder.append(" ");

                                    } else {
                                        // fix image for starting sector
                                        final String to = getEndingDirection(prevSector.getX(), prevSector.getY(), pathSector.getX(), pathSector.getY());
                                        prevSector.setPath("start-" + to);
                                        pathBuilder.append(prevSector.getPath());
                                        pathBuilder.append(" ");
                                    }

                                    // Update conquer counters
                                    if (theSector.getTerrainId() != TERRAIN_O && theSector.getNationId() == NATION_NEUTRAL) {
                                        strBuilder.append(", ");
                                        strBuilder.append(pathSector.positionToString());

                                        if (counterNeutral > 0) {
                                            strBuilder.append("[N");
                                            strBuilder.append(counterNeutral);
                                            strBuilder.append("]");

                                            counterNeutral--;
                                            pathSector.setNeedsConquer(true);
                                        }

                                    } else if (pathSector.getNeedsConquer()) {
                                        if (counterConquer <= 0) {
                                            break;
                                        }

                                        strBuilder.append(", ");
                                        strBuilder.append(pathSector.positionToString());
                                        strBuilder.append("[C");
                                        strBuilder.append(counterNeutral);
                                        strBuilder.append("]");

                                        counterConquer--;
                                    } else {

                                        strBuilder.append(", ");
                                        strBuilder.append(pathSector.positionToString());
                                    }

                                    // Detect images
                                    dblPrevSector = prevSector;
                                    prevSector = pathSector;

                                    // Store new sector in the path
                                    sectorsPath.add(pathSector);
                                    totalCost += pathSector.getActualMPs();
                                }

                                if (dblPrevSector != null) {
                                    // fix image for ending sector
                                    final String from = getEndingDirection(dblPrevSector.getX(), dblPrevSector.getY(), prevSector.getX(), prevSector.getY());
                                    prevSector.setPath("end-" + from);
                                    pathBuilder.append(prevSector.getPath());
                                    pathBuilder.append(" ");

                                    final PathDTO thisDTO = new PathDTO();
                                    thisDTO.setPathSectors(sectorsPath);
                                    thisDTO.setTotalCost(totalCost);
                                    thisDTO.setTotLength(sectorsPath.size());
                                    thisDTO.setTotalConquer(maxConquer - counterConquer);
                                    thisDTO.setTotalConquerNeutral(maxNeutral - counterNeutral);
                                    thisDTO.setCanForceMarch(canForceMarch);
                                    return thisDTO;
                                }


                                return null;
                            }
                        }));
                    }
                }
            }
        }

        // wait for the execution all tasks
        try {
            // wait for all tasks to complete before continuing
            for (Future<PathDTO> task : futures) {
                final PathDTO result = task.get();
                if (result != null) {
                    paths.add(result);
                }
            }

            executorService.shutdownNow();

        } catch (Exception ex) {
            LOGGER.error("Task execution interrupted", ex);
        }

        return paths;
    }

    /**
     * Convert the DB object into a DTO.
     *
     * @param value the DB object to convert.
     * @return the DTO object.
     */

    private PathSectorDTO cloneImportant(final Game thisGame, final int ownerId,
                                         final Map<Integer, Map<Integer, Integer>> relationsMap,
                                         final SectorDTO value) {
        final PathSectorDTO empSec = new PathSectorDTO();
        empSec.setRegionId(value.getRegionId());
        empSec.setActualMPs(value.getTerrain().getActualMPs());
        empSec.setX(value.getX());
        empSec.setY(value.getY());
        empSec.setPath("");
        empSec.setNeedsConquer(value.getNeedsConquer());
        empSec.setCanForceMarch(canForceMarch(thisGame, ownerId, relationsMap, value));
        return empSec;
    }

    private String getEndingDirection(final int startX, final int startY,
                                      final int thisX, final int thisY) {
        final StringBuilder direction = new StringBuilder();

        if (startX < thisX && startY == thisY) {
            direction.append("R");

        } else if (startX < thisX && startY < thisY) {
            direction.append("RD");

        } else if (startX < thisX && startY > thisY) {
            direction.append("RU");

        } else if (startX > thisX && startY == thisY) {
            direction.append("L");

        } else if (startX > thisX && startY < thisY) {
            direction.append("LD");

        } else if (startX > thisX && startY > thisY) {
            direction.append("LU");

        } else if (startX == thisX && startY < thisY) {
            direction.append("D");

        } else if (startX == thisX && startY > thisY) {
            direction.append("U");
        }

        return direction.toString();
    }

    private String getStartingDirection(final int startX, final int startY,
                                        final int thisX, final int thisY) {
        final StringBuilder direction = new StringBuilder();

        if (startX < thisX && startY == thisY) {
            direction.append("L");

        } else if (startX < thisX && startY < thisY) {
            direction.append("LU");

        } else if (startX < thisX && startY > thisY) {
            direction.append("LD");

        } else if (startX > thisX && startY == thisY) {
            direction.append("R");

        } else if (startX > thisX && startY < thisY) {
            direction.append("RU");

        } else if (startX > thisX && startY > thisY) {
            direction.append("RD");

        } else if (startX == thisX && startY < thisY) {
            direction.append("U");

        } else if (startX == thisX && startY > thisY) {
            direction.append("D");
        }

        return direction.toString();
    }

}
