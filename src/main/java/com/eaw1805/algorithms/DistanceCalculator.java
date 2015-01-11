package com.eaw1805.algorithms;

import com.eaw1805.data.HibernateUtil;
import com.eaw1805.data.constants.RegionConstants;
import com.eaw1805.data.constants.RelationConstants;
import com.eaw1805.data.constants.TerrainConstants;
import com.eaw1805.data.managers.beans.BattalionManagerBean;
import com.eaw1805.data.managers.beans.RelationsManagerBean;
import com.eaw1805.data.managers.beans.SectorManagerBean;
import com.eaw1805.data.model.Game;
import com.eaw1805.data.model.Nation;
import com.eaw1805.data.model.NationsRelation;
import com.eaw1805.data.model.map.Region;
import com.eaw1805.data.model.map.Sector;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jgrapht.alg.BellmanFordShortestPath;
import org.jgrapht.graph.SimpleDirectedGraph;


import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Used to compute distances between sectors in Movement Points.
 */
public class DistanceCalculator
        implements RegionConstants, TerrainConstants {

    /**
     * a log4j logger to print messages.
     */
    private static final Logger LOGGER = LogManager.getLogger(DistanceCalculator.class);

    /**
     * Stores the graph for computing the movement cost.
     */
    private final transient SimpleDirectedGraph<Sector, SimpleWeightedEdge> movementGraph;

    /**
     * Stores the sectors for easy access.
     */
    private final transient Sector[][] sectorsArray;

    /**
     * The game instance.
     */
    private final transient Game thisGame;

    /**
     * The region instance.
     */
    private final transient Region thisRegion;

    /**
     * The nation instance.
     */
    private final transient Nation thisNation;

    /**
     * The relations manager bean.
     */
    private final transient RelationsManagerBean relationsManagerBean;

    /**
     * The sector manager bean.
     */
    private final transient SectorManagerBean sectorManagerBean;

    /**
     * The battalion manager bean.
     */
    private final transient BattalionManagerBean battalionManagerBean;

    /**
     * Default constructor.
     *
     * @param game   the Game to investigate.
     * @param region the Region to investigate.
     */
    public DistanceCalculator(final Game game,
                              final Region region,
                              final Nation nation,
                              final RelationsManagerBean relationsManager,
                              final SectorManagerBean sectorManager,
                              final BattalionManagerBean battalionManager) {
        final int regionSizeX, regionSizeY;
        switch (game.getScenarioId()) {
            case HibernateUtil.DB_FREE:
                regionSizeX = REGION_1804_MAX_X + 2;
                regionSizeY = REGION_1804_MAX_Y + 2;
                break;

            case HibernateUtil.DB_S3:
                regionSizeX = REGION_1808_MAX_X + 2;
                regionSizeY = REGION_1808_MAX_Y + 2;
                break;

            case HibernateUtil.DB_S1:
            case HibernateUtil.DB_S2:
            default:
                regionSizeX = REGION_1805_MAX_X + 2;
                regionSizeY = REGION_1805_MAX_Y + 2;
                break;
        }
        movementGraph = new SimpleDirectedGraph<Sector, SimpleWeightedEdge>(SimpleWeightedEdge.class);
        sectorsArray = new Sector[regionSizeX][regionSizeY];
        thisGame = game;
        thisRegion = region;
        thisNation = nation;

        relationsManagerBean = relationsManager;
        sectorManagerBean = sectorManager;
        battalionManagerBean = battalionManager;

        // Construct the graph from the sectors.
        createGraphFromSectors();
    }

    /**
     * Construct the graph from the sectors.
     */
    private void createGraphFromSectors() {
        LOGGER.debug("Calculating supply lines distances for " + thisNation.getName() + " in " + thisRegion.getName());

        // Identify forces of nation
        final Map<Sector, BigInteger> ownBattalions = battalionManagerBean.countBattalions(thisGame, thisNation, 40, true);

        // Identify enemy nations
        final StringBuilder strEnemies = new StringBuilder();
        final List<Nation> enemies = new ArrayList<Nation>();
        final List<NationsRelation> lstRelations = relationsManagerBean.listByGameNation(thisGame, thisNation);
        for (NationsRelation relation : lstRelations) {
            if (relation.getRelation() == RelationConstants.REL_WAR
                    || (thisRegion.getId() != EUROPE && relation.getRelation() == RelationConstants.REL_COLONIAL_WAR)) {
                enemies.add(relation.getTarget());
                strEnemies.append(relation.getTarget().getName());
                strEnemies.append(" ");
            }
        }
        LOGGER.info("Supply lines for " + thisNation.getName() + "/" + thisRegion.getName() + " -- excluding areas where enemy troops are stationed (" + strEnemies.toString() + ")");

        // Examine armies of enemies so that they are excluded
        final Set<Sector> enemySectors = new HashSet<Sector>();
        for (Nation enemy : enemies) {
            final StringBuilder strEnemyForces = new StringBuilder();
            strEnemyForces.append(enemy.getName());
            strEnemyForces.append(" -- ");
            final Map<Sector, BigInteger> countBattalions = battalionManagerBean.countBattalions(thisGame, enemy, 40, true);
            for (Sector sector : countBattalions.keySet()) {
                if (sector.getPosition().getRegion().getId() == thisRegion.getId()) {
                    // Check if in this area the nation has forces (that were not defeated in battle)
                    if (!ownBattalions.containsKey(sector)) {
                        enemySectors.add(sector);
                        strEnemyForces.append(sector.getPosition().toString());
                        strEnemyForces.append("(");
                        strEnemyForces.append(countBattalions.get(sector));
                        strEnemyForces.append(") ");
                    }
                }
            }
            LOGGER.info("Supply lines for " + thisNation.getName() + "/" + thisRegion.getName() + " -- Forces of " + strEnemyForces.toString());
        }

        // First pass, add all sectors
        final List<Sector> lstSectorsOwned = sectorManagerBean.listByGameRegion(thisGame, thisRegion);
        for (final Sector sector : lstSectorsOwned) {
            // Ignore Ocean & Impassable tiles
            if (sector.getTerrain().getId() != TERRAIN_O
                    && sector.getTerrain().getId() != TERRAIN_I) {

                // Check if foreign/enemy units are stationed in this sector
                if (!enemySectors.contains(sector)) {
                    movementGraph.addVertex(sector);
                }
            }
            sectorsArray[sector.getPosition().getX() + 1][sector.getPosition().getY() + 1] = sector;
        }

        // Third pass, Add the edges of the graph
        for (Sector sector : movementGraph.vertexSet()) {
            createSector(sector);
        }
    }

    /**
     * Add all edges for this sector.
     *
     * @param sector the sector to examine.
     */
    private void createSector(final Sector sector) {
        final int posX = sector.getPosition().getX() + 1;
        final int posY = sector.getPosition().getY() + 1;
        addEdge(posX, posY, posX - 1, posY - 1);
        addEdge(posX, posY, posX - 1, posY);
        addEdge(posX, posY, posX - 1, posY + 1);
        addEdge(posX, posY, posX, posY - 1);
        addEdge(posX, posY, posX, posY + 1);
        addEdge(posX, posY, posX + 1, posY - 1);
        addEdge(posX, posY, posX + 1, posY);
        addEdge(posX, posY, posX + 1, posY + 1);
    }

    /**
     * Add an edge to the directed weighted graph.
     *
     * @param posX  the X coordinate of the vertex.
     * @param posY  the Y coordinate of the vertex.
     * @param thatX the X coordinate of the neighboring vertex.
     * @param thatY the Y coordinate of the neighboring vertex.
     */
    private void addEdge(final int posX, final int posY, final int thatX, final int thatY) {
        if (sectorsArray[thatX][thatY] != null && movementGraph.containsVertex(sectorsArray[thatX][thatY])) {
            final SimpleWeightedEdge thisEdge = new SimpleWeightedEdge();
            final int mpCost;

            if (sectorsArray[thatX][thatY].getPosition().getRegion().getId() == EUROPE) {
                mpCost = sectorsArray[thatX][thatY].getTerrain().getMps();

            } else {
                mpCost = 2 * sectorsArray[thatX][thatY].getTerrain().getMps();
            }

            movementGraph.setEdgeWeight(thisEdge, mpCost);
            movementGraph.addEdge(sectorsArray[posX][posY], sectorsArray[thatX][thatY], thisEdge);
        }
    }

    /**
     * Check if a path exists between the indicated sector and any other sector in the list provided.
     *
     * @param checkThis the starting point.
     * @param anyOfThis a list of points to check.
     * @param totMP     the total number of MPs that can be used.
     * @return true if a path of 40MP cost exists between the starting point and any other point.
     */
    public boolean pathExists(final Sector checkThis, final List<Sector> anyOfThis, final int totMP) {
        boolean found = false;

        if (!movementGraph.containsVertex(checkThis)) {
            return false;
        }

        for (final Sector sector : anyOfThis) {
            // Compute single-source shortest paths
            try {
                final BellmanFordShortestPath<Sector, SimpleWeightedEdge> dsp = new BellmanFordShortestPath<Sector, SimpleWeightedEdge>(movementGraph, sector, totMP);

                if (dsp.getCost(checkThis) <= totMP) {
                    return true;
                }

            } catch (Exception ex) {
                LOGGER.error("No path available connecting " + checkThis.getPosition().toString() + " with " + sector.getPosition().toString());
            }
        }

        return found;
    }

}
