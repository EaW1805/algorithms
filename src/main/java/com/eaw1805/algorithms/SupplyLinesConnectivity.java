package com.eaw1805.algorithms;

import com.eaw1805.data.model.Engine;
import com.eaw1805.data.HibernateUtil;
import com.eaw1805.data.constants.NewsConstants;
import com.eaw1805.data.constants.RegionConstants;
import com.eaw1805.data.constants.RelationConstants;
import com.eaw1805.data.constants.TerrainConstants;
import com.eaw1805.data.managers.NewsManager;
import com.eaw1805.data.managers.beans.BarrackManagerBean;
import com.eaw1805.data.managers.beans.RegionManagerBean;
import com.eaw1805.data.managers.beans.RelationsManagerBean;
import com.eaw1805.data.managers.beans.SectorManagerBean;
import com.eaw1805.data.managers.beans.TradeCityManagerBean;
import com.eaw1805.data.model.Game;
import com.eaw1805.data.model.Nation;
import com.eaw1805.data.model.NationsRelation;
import com.eaw1805.data.model.News;
import com.eaw1805.data.model.economy.TradeCity;
import com.eaw1805.data.model.map.Barrack;
import com.eaw1805.data.model.map.Position;
import com.eaw1805.data.model.map.Region;
import com.eaw1805.data.model.map.Sector;
import com.eaw1805.data.model.orders.PatrolOrderDetails;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jgrapht.alg.BellmanFordShortestPath;
import org.jgrapht.graph.SimpleDirectedGraph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Uses graph algorithms to identify if the barracks of each nation are properly supplied.
 */
public class SupplyLinesConnectivity
        implements RegionConstants, TerrainConstants, RelationConstants {

    /**
     * a log4j logger to print messages.
     */
    private static final Logger LOGGER = LogManager.getLogger(SupplyLinesConnectivity.class);

    /**
     * Stores the graph for computing the connectivity.
     */
    private final transient SimpleDirectedGraph<Sector, SimpleEdge> sectorsGraph;

    /**
     * Stores the graph for computing the movement cost.
     */
    private final transient SimpleDirectedGraph<Sector, SimpleWeightedEdge> movementGraph;

    /**
     * Stores the sectors for easy access.
     */
    private final transient Sector[][][] sectorsArray;

    /**
     * The current instance of the game engine.
     */
    private final transient Engine gameEngine;

    /**
     * The game instance.
     */
    private final transient Game thisGame;

    /**
     * The owner.
     */
    private final transient Nation thisOwner;

    /**
     * An instance of the distance calculator for each region.
     */
    private final transient Map<Region, DistanceCalculator> distCalc;

    /**
     * Store all barracks that are in supply.
     */
    private final transient Map<Region, List<Sector>> barracksInSupply;

    /**
     * Store all barracks that are not in supply.
     */
    private final transient Map<Region, List<Sector>> barracksNotInSupply;

    /**
     * Stores all supplied barracks and the sector that they can support.
     */
    private final transient Map<Sector, BellmanFordShortestPath<Sector, SimpleEdge>> supplySources;

    /**
     * The sectors under patrol indexed to patrol order.
     */
    private final transient Map<Position, Set<PatrolOrderDetails>> patrolledSectorsIdxOrder;

    /**
     * The relations manager bean.
     */
    private final transient RelationsManagerBean relationsManagerBean;

    /**
     * The sector manager bean.
     */
    private final transient SectorManagerBean sectorManagerBean;

    private final transient RegionManagerBean regionManagerBean;

    private final transient BarrackManagerBean barrackManagerBean;

    private final transient TradeCityManagerBean tradeCityManagerBean;

    /**
     * Default constructor.
     *
     * @param gEngine       the instance of the Game Engine.
     * @param owner         the Nation to investigate.
     * @param dcalc         the distance calculators for each region.
     * @param activePatrols the active patrol orders.
     */
    public SupplyLinesConnectivity(final Engine gEngine,
                                   final Nation owner,
                                   final Map<Region, DistanceCalculator> dcalc,
                                   final Map<Integer, PatrolOrderDetails> activePatrols,
                                   final RelationsManagerBean relationsManager,
                                   final SectorManagerBean sectorManager,
                                   final RegionManagerBean regionManager,
                                   final BarrackManagerBean barrackManager,
                                   final TradeCityManagerBean tradeCityManager) {

        relationsManagerBean = relationsManager;
        sectorManagerBean = sectorManager;
        regionManagerBean = regionManager;
        barrackManagerBean = barrackManager;
        tradeCityManagerBean = tradeCityManager;

        final int regionSizeX, regionSizeY;
        switch (gEngine.getGame().getScenarioId()) {
            case HibernateUtil.DB_FREE:
                regionSizeX = RegionConstants.REGION_1804_MAX_X + 2;
                regionSizeY = RegionConstants.REGION_1804_MAX_Y + 2;
                break;

            case HibernateUtil.DB_S3:
                regionSizeX = RegionConstants.REGION_1808_MAX_X + 2;
                regionSizeY = RegionConstants.REGION_1808_MAX_Y + 2;
                break;

            case HibernateUtil.DB_S1:
            case HibernateUtil.DB_S2:
            default:
                regionSizeX = RegionConstants.REGION_1805_MAX_X + 2;
                regionSizeY = RegionConstants.REGION_1805_MAX_Y + 2;
                break;
        }

        gameEngine = gEngine;
        sectorsGraph = new SimpleDirectedGraph<Sector, SimpleEdge>(SimpleEdge.class);
        movementGraph = new SimpleDirectedGraph<Sector, SimpleWeightedEdge>(SimpleWeightedEdge.class);
        sectorsArray = new Sector[RegionConstants.REGION_LAST + 1][regionSizeX][regionSizeY];
        supplySources = new HashMap<Sector, BellmanFordShortestPath<Sector, SimpleEdge>>();
        barracksNotInSupply = new HashMap<Region, List<Sector>>();
        barracksInSupply = new HashMap<Region, List<Sector>>();
        patrolledSectorsIdxOrder = new HashMap<Position, Set<PatrolOrderDetails>>();
        distCalc = dcalc;
        thisGame = gameEngine.getGame();
        thisOwner = owner;

        // Initialize Maps & Lists
        final List<Region> lstRegion = regionManagerBean.list();
        for (final Region region : lstRegion) {
            barracksInSupply.put(region, new ArrayList<Sector>());
            barracksNotInSupply.put(region, new ArrayList<Sector>());
        }

        // Process active patrol orders
        for (final PatrolOrderDetails orderDetails : activePatrols.values()) {
            // check that this is still active patrol with at least 3000 tonnage
            if (orderDetails.getTonnage() < 3000) {
                continue;
            }

            // Check relations with nation conducting the patrol
            if (owner.getId() == orderDetails.getNation().getId()) {
                continue;
            }

            // Retrieve relations with foreign nation
            final NationsRelation relation = relationsManagerBean.getByNations(thisGame, orderDetails.getNation(), owner);

            // Check relations
            if (relation.getRelation() >= REL_COLONIAL_WAR) {
                // Index patrol order by sectors affected
                for (final Sector sector : orderDetails.getPath()) {
                    addPatrolSector(sector, orderDetails);
                }
            }
        }

        // Construct the graph from the sectors.
        createGraphFromSectors();
    }

    /**
     * Add a sector that is patrolled by a ship/fleet.
     *
     * @param sector the sector patrolled.
     * @param order  the patrol order.
     */
    protected final void addPatrolSector(final Sector sector, final PatrolOrderDetails order) {
        Set<PatrolOrderDetails> theSectors;
        if (patrolledSectorsIdxOrder.containsKey(sector.getPosition())) {
            theSectors = patrolledSectorsIdxOrder.get(sector.getPosition());
        } else {
            theSectors = new HashSet<PatrolOrderDetails>();
            patrolledSectorsIdxOrder.put(sector.getPosition(), theSectors);
        }
        theSectors.add(order);
    }

    /**
     * Construct the graph from the sectors.
     */
    private void createGraphFromSectors() {
        // First pass, add all owned sectors
        final List<Sector> lstSectorsOwned = sectorManagerBean.listByGameNation(thisGame, thisOwner);
        for (final Sector sector : lstSectorsOwned) {
            sectorsGraph.addVertex(sector);
            movementGraph.addVertex(sector);
            sectorsArray[sector.getPosition().getRegion().getId()][sector.getPosition().getX() + 1][sector.getPosition().getY() + 1] = sector;
        }

        // Second pass, add all sectors owned by allied or friendly nations
        final List<Nation> lstNations = gameEngine.getAliveNations();
        for (final Nation nation : lstNations) {
            // Examine foreign relations
            final NationsRelation relation = relationsManagerBean.getByNations(thisGame, nation, thisOwner);
            if (relation != null && relation.getRelation() <= REL_PASSAGE) {
                final List<Sector> lstSectors = sectorManagerBean.listByGameNation(thisGame, nation);
                for (final Sector sector : lstSectors) {
                    sectorsGraph.addVertex(sector);
                    movementGraph.addVertex(sector);
                    sectorsArray[sector.getPosition().getRegion().getId()][sector.getPosition().getX() + 1][sector.getPosition().getY() + 1] = sector;
                }
            }
        }

        // Retrieve sea sectors for EUROPE only
        final Region europe = regionManagerBean.getByID(EUROPE);
        final List<Sector> lstSea = sectorManagerBean.listSeaByGameRegion(thisGame, europe, false);
        for (final Sector sector : lstSea) {
            boolean blockedByPatrol = false;
            // - Giblartar 8/46
            // - Copenhagen 36/14, 37/14
            // - Vosporos 55/42, 58/39
            if ((sector.getPosition().getX() == 7 && sector.getPosition().getY() == 45)
                    || (sector.getPosition().getX() == 35 && sector.getPosition().getY() == 13)
                    || (sector.getPosition().getX() == 36 && sector.getPosition().getY() == 13)
                    || (sector.getPosition().getX() == 54 && sector.getPosition().getY() == 41)
                    || (sector.getPosition().getX() == 57 && sector.getPosition().getY() == 38)) {

                // check that sea sector is not patrolled by enemy forces
                final Position thisPos = sector.getPosition();
                if (patrolledSectorsIdxOrder.containsKey(thisPos)) {
                    for (final PatrolOrderDetails orderDetails : patrolledSectorsIdxOrder.get(thisPos)) {
                        blockedByPatrol |= checkPatrol(orderDetails, thisPos);
                    }
                }
            }

            if (!blockedByPatrol) {
                sectorsGraph.addVertex(sector);
                movementGraph.addVertex(sector);
                sectorsArray[sector.getPosition().getRegion().getId()][sector.getPosition().getX() + 1][sector.getPosition().getY() + 1] = sector;
            }
        }

        // Third pass, Add the edges of the graph
        for (Sector sector : sectorsGraph.vertexSet()) {
            createSector(sector);
        }
    }

    /**
     * Determine the roll target and through the roll to check if the patrol will intercept the supply lines.
     *
     * @param orderDetails the patrol order.
     * @param thisPos      the position.
     * @return true if it is intercepted.
     */
    private boolean checkPatrol(final PatrolOrderDetails orderDetails, final Position thisPos) {
        // Determine if position is a coastal tile
        int rollTarget = 0;

        // +30% if interception take place at a coastal tile that is adjacent to a land tile owned by the patrolling fleet's country.
        final boolean isOwnCoastal = sectorManagerBean.checkNationCoastal(thisPos, orderDetails.getNation());
        if (isOwnCoastal) {
            rollTarget += 30;
        }

        // +3% per unspent movement point of the patrolling fleet.
        rollTarget += 3 * orderDetails.getUnspentMP();

        // Throw roll
        final int roll = gameEngine.getRandomGen().nextInt(100) + 1;
        return (roll < rollTarget);
    }

    /**
     * Add all edges for this sector.
     *
     * @param sector the sector to examine.
     */
    private void createSector(final Sector sector) {
        final int posX = sector.getPosition().getX() + 1;
        final int posY = sector.getPosition().getY() + 1;
        addEdge(sector.getPosition().getRegion().getId(), posX, posY, posX - 1, posY - 1);
        addEdge(sector.getPosition().getRegion().getId(), posX, posY, posX - 1, posY);
        addEdge(sector.getPosition().getRegion().getId(), posX, posY, posX - 1, posY + 1);
        addEdge(sector.getPosition().getRegion().getId(), posX, posY, posX, posY - 1);
        addEdge(sector.getPosition().getRegion().getId(), posX, posY, posX, posY + 1);
        addEdge(sector.getPosition().getRegion().getId(), posX, posY, posX + 1, posY - 1);
        addEdge(sector.getPosition().getRegion().getId(), posX, posY, posX + 1, posY);
        addEdge(sector.getPosition().getRegion().getId(), posX, posY, posX + 1, posY + 1);
    }

    /**
     * Add an edge to the directed weighted graph.
     *
     * @param region the region of examination.
     * @param posX   the X coordinate of the vertex.
     * @param posY   the Y coordinate of the vertex.
     * @param thatX  the X coordinate of the neighboring vertex.
     * @param thatY  the Y coordinate of the neighboring vertex.
     */
    private void addEdge(final int region, final int posX, final int posY, final int thatX, final int thatY) {
        boolean addEdge = false;

        if (sectorsArray[region][thatX][thatY] != null && sectorsGraph.containsVertex(sectorsArray[region][thatX][thatY])) {
            // Add an edge between sectors if any of the rules below is true:
            // 1. Source is an Ocean sector and target has a shipyard
            if (sectorsArray[region][posX][posY].getTerrain().getId() == TERRAIN_O && sectorsArray[region][thatX][thatY].hasBarrack()) {

                // check that sea sector is not patrolled by enemy forces
                boolean blockedByPatrol = false;
                if (patrolledSectorsIdxOrder.containsKey(sectorsArray[region][posX][posY].getPosition())) {
                    for (final PatrolOrderDetails orderDetails : patrolledSectorsIdxOrder.get(sectorsArray[region][posX][posY].getPosition())) {
                        blockedByPatrol |= checkPatrol(orderDetails, sectorsArray[region][posX][posY].getPosition());
                    }
                }

                addEdge = !blockedByPatrol;

            } else if (sectorsArray[region][thatX][thatY].getTerrain().getId() == TERRAIN_O && sectorsArray[region][posX][posY].hasBarrack()) {
                // 2. Target is an Ocean sector and source has a shipyard

                // check that sea sector is not patrolled by enemy forces
                boolean blockedByPatrol = false;
                if (patrolledSectorsIdxOrder.containsKey(sectorsArray[region][thatX][thatY].getPosition())) {
                    for (final PatrolOrderDetails orderDetails : patrolledSectorsIdxOrder.get(sectorsArray[region][thatX][thatY].getPosition())) {
                        blockedByPatrol |= checkPatrol(orderDetails, sectorsArray[region][thatX][thatY].getPosition());
                    }
                }

                addEdge = !blockedByPatrol;

            } else if ((sectorsArray[region][posX][posY].getTerrain().getId() == TERRAIN_O && sectorsArray[region][thatX][thatY].getTerrain().getId() == TERRAIN_O)
                    || (sectorsArray[region][posX][posY].getTerrain().getId() != TERRAIN_O && sectorsArray[region][posX][posY].getTerrain().getId() != TERRAIN_I && sectorsArray[region][thatX][thatY].getTerrain().getId() != TERRAIN_O && sectorsArray[region][thatX][thatY].getTerrain().getId() != TERRAIN_I)) {
                // 3. Both sectors are sea sectors
                // 4. Both sectors are land sectors (excluding impassable)
                addEdge = true;
            }
        }

        if (addEdge) {
            final SimpleEdge thisEdge = new SimpleEdge();
            sectorsGraph.addEdge(sectorsArray[region][posX][posY], sectorsArray[region][thatX][thatY], thisEdge);
        }
    }

    public void setupSupplyLines() {

        // Examine all European trade cities owned by player
        supplyTradeCities();

        // Examine all barracks owned by player and positioned in home region
        supplyHomeBarracks();

        // Examine all barracks with an uninterrupted path to an already supplied barrack
        supplyBarracks();

        // Examine all not supplied barracks continuously until no further change is made
        reexamineNotSupplied();

        // Report not supplied barracks
        if (gameEngine instanceof GameEngine) {
            reportNotSupplied();
        }
    }

    /**
     * Check if the particular sector is within range of the supply lines.
     *
     * @param thisSector the sector to check.
     * @return true, if it is within supply lines.
     */
    public boolean checkSupply(final Sector thisSector) {
        boolean inSupply = false;

        final List<Sector> supplied = barracksInSupply.get(thisSector.getPosition().getRegion());
        if (!supplied.isEmpty()) {
            if (supplied.contains(thisSector)) {
                inSupply = true;
            }
        }

        // Now check again to find a path of 40MP cost
        if (!inSupply) {
            final DistanceCalculator regionCalc = distCalc.get(thisSector.getPosition().getRegion());
            if (regionCalc.pathExists(thisSector, supplied, 40)) {
                inSupply = true;
            }
        }

        return inSupply;
    }

    /**
     * Retrieve supplied barracks that act as supply sources.
     *
     * @return supplied barracks that act as supply sources.
     */
    public Map<Region, List<Sector>> getBarracksInSupply() {
        return barracksInSupply;
    }

    private void supplyTradeCities() {
        final List<TradeCity> lstTradeCity = tradeCityManagerBean.listByGame(thisGame);
        for (final TradeCity tradeCity : lstTradeCity) {
            if (tradeCity.getPosition().getRegion().getId() == EUROPE) {
                final Sector sector = sectorsArray[EUROPE][tradeCity.getPosition().getX() + 1][tradeCity.getPosition().getY() + 1];

                if (sector != null && sector.getNation().getId() == thisOwner.getId()) {
                    // Compute single-source shortest paths for this source of supply
                    final BellmanFordShortestPath<Sector, SimpleEdge> bfsp = new BellmanFordShortestPath<Sector, SimpleEdge>(sectorsGraph, sector);

                    // Update Map
                    supplySources.put(sector, bfsp);

                    // Update lists
                    barracksInSupply.get(sector.getPosition().getRegion()).add(sector);
                }
            }
        }
    }

    private void supplyHomeBarracks() {
        final List<Barrack> lstBarracks = barrackManagerBean.listByGameNation(thisGame, thisOwner);
        for (final Barrack barrack : lstBarracks) {
            if (barrack.getPosition().getRegion().getId() == EUROPE) {
                final Sector sector = sectorsArray[EUROPE][barrack.getPosition().getX() + 1][barrack.getPosition().getY() + 1];
                if (sector != null && getSphere(sector, thisOwner) == 1 && !supplySources.containsKey(sector)) {
                    // Compute single-source shortest paths for this source of supply
                    final BellmanFordShortestPath<Sector, SimpleEdge> bfsp = new BellmanFordShortestPath<Sector, SimpleEdge>(sectorsGraph, sector);

                    // Update Map
                    supplySources.put(sector, bfsp);

                    // Update lists
                    barracksInSupply.get(sector.getPosition().getRegion()).add(sector);
                }
            }
        }
    }

    private void supplyBarracks() {
        // Examine each barrack to identify if it is in supply
        final List<Barrack> lstBarracks = barrackManagerBean.listByGameNation(thisGame, thisOwner);
        for (final Barrack barrack : lstBarracks) {
            final Sector sector = sectorsArray[barrack.getPosition().getRegion().getId()][barrack.getPosition().getX() + 1][barrack.getPosition().getY() + 1];

            if (barrack.getPosition().getRegion().getId() != EUROPE) {
                // Supply in the colonies
                // All barracks / depots are considered supply sources, regardless of their position
                // (i.e. they do not need to trace a path to another friendly controlled barrack)
                barrack.setNotSupplied(false);

            } else {

                if (sector == null) {

                    // Player does not support any source of supply
                    barrack.setNotSupplied(true);

                    final Sector sectorRecheck = sectorManagerBean.getByPosition(barrack.getPosition());

                    // Update lists
                    barracksNotInSupply.get(barrack.getPosition().getRegion()).add(sectorRecheck);


                } else if (supplySources.containsKey(sector)) {
                    // skip this barrack

                } else if (supplySources.isEmpty()) {
                    // Player does not support any source of supply
                    barrack.setNotSupplied(true);

                    // Update lists
                    barracksNotInSupply.get(barrack.getPosition().getRegion()).add(sector);

                } else {
                    // Assume that it is not supplied, unless an uninterrupted access is found.
                    barrack.setNotSupplied(true);

                    // A barrack is considered a supply source if there is an uninterrupted access
                    // from the home nation or any foreign occupied trade city all the way to the barrack.
                    try {
                        for (final BellmanFordShortestPath<Sector, SimpleEdge> source : supplySources.values()) {
                            if (source.getPathEdgeList(sector) != null) {
                                // A path exists
                                barrack.setNotSupplied(false);
                                break;
                            }
                        }

                    } catch (Exception ex) {
                        LOGGER.debug("No path found connecting " + sector.getPosition().toString() + " with " + barrack.getPosition().toString());
                    }
                }
            }

            // Update lists
            if (barrack.getNotSupplied()) {
                barracksNotInSupply.get(barrack.getPosition().getRegion()).add(sector);

            } else {
                barracksInSupply.get(barrack.getPosition().getRegion()).add(sector);
            }
        }
    }

    private void reexamineNotSupplied() {
        int totChanges = 1;
        final Set<Sector> changedSectors = new HashSet<Sector>();
        final List<Region> lstRegion = regionManagerBean.list();
        while (totChanges != 0 && !barracksNotInSupply.isEmpty()) {
            totChanges = 0;
            // Examine all not supplied barracks
            for (final Region region : lstRegion) {
                final List<Sector> notSupplied = barracksNotInSupply.get(region);
                if (notSupplied.isEmpty()) {
                    // Skip this region
                    continue;
                }

                final List<Sector> supplied = barracksInSupply.get(region);
                if (supplied.isEmpty()) {
                    // No way to find supply route -- skip this region
                    continue;
                }

                final DistanceCalculator regionCalc = distCalc.get(region);

                for (final Sector sector : notSupplied) {
                    if (sector != null) {
                        final Barrack barrack = barrackManagerBean.getByPosition(sector.getPosition());
                        try {
                            final boolean result = regionCalc.pathExists(sector, supplied, 40);
                            if (result) {
                                // Barrack is in supply range
                                changedSectors.add(sector);
                                supplied.add(sector);

                                barrack.setNotSupplied(false);
                                totChanges++;
                            }
                        } catch (Exception ex) {
                            // barrack not in supply.
                        }
                    }
                }

                for (final Sector sector : changedSectors) {
                    notSupplied.remove(sector);
                }
            }
        }
    }

    private void reportNotSupplied() {
        final List<Region> lstRegion = regionManagerBean.list();
        for (final Region region : lstRegion) {

            // Update flags for not Supplied barracks
            final List<Sector> notSupplied = barracksNotInSupply.get(region);
            if (!notSupplied.isEmpty()) {
                for (final Sector sector : notSupplied) {
                    if (sector != null) {
                        final Barrack barrack = barrackManagerBean.getByPosition(sector.getPosition());
                        barrack.setNotSupplied(true);
                        barrackManagerBean.update(barrack);

                        LOGGER.info("Barrack at [" + barrack.getPosition() + "] owned by " + thisOwner.getName() + " is out of supply.");
                        news(thisOwner, thisOwner, NewsConstants.NEWS_ECONOMY, 0, "Barrack at " + barrack.getPosition() + " is not reachable by our supply lines.");
                    }
                }

                // Update flags for Supplied barracks
                final List<Sector> supplied = barracksInSupply.get(region);
                for (final Sector sector : supplied) {
                    final Barrack barrack = barrackManagerBean.getByPosition(sector.getPosition());
                    if (barrack != null) {
                        barrack.setNotSupplied(false);
                        barrackManagerBean.update(barrack);
                    }
                }
            }
        }
    }

    /**
     * Identify if sector is a home region, inside sphere of influence, or outside of the receiving nation.
     *
     * @param sector   the sector to examine.
     * @param receiver the receiving nation.
     * @return 1 if home region, 2 if in sphere of influence, 3 if outside.
     */
    private int getSphere(final Sector sector, final Nation receiver) {
        if (sector == null || String.valueOf(sector.getPoliticalSphere()).length() == 0) {
            return 3;
        }

        final char thisNationCodeLower = String.valueOf(receiver.getCode()).toLowerCase().charAt(0);
        final char thisSectorCodeLower = String.valueOf(sector.getPoliticalSphere()).toLowerCase().charAt(0);
        int sphere = 1;

        // Check if this is not home region
        if (thisNationCodeLower != thisSectorCodeLower) {
            sphere = 2;

            final char thisNationCode = String.valueOf(receiver.getCode()).toLowerCase().charAt(0);

            // Check if this is outside sphere of influence
            if (sector.getNation().getSphereOfInfluence().toLowerCase().indexOf(thisNationCode) < 0) {
                sphere = 3;
            }
        }

        return sphere;
    }

    /**
     * Add a news entry for this turn.
     *
     * @param nation       the owner of the news entry.
     * @param subject      the subject of the news entry.
     * @param type         the type of the news entry.
     * @param baseNewsId   the base news entry.
     * @param announcement the value of the news entry.
     * @return the ID of the new entry.
     */
    protected int news(final Nation nation, final Nation subject, final int type, final int baseNewsId, final String announcement) {
        final News thisNewsEntry = new News();
        thisNewsEntry.setGame(thisGame);
        thisNewsEntry.setTurn(thisGame.getTurn());
        thisNewsEntry.setNation(nation);
        thisNewsEntry.setSubject(subject);
        thisNewsEntry.setType(type);
        thisNewsEntry.setBaseNewsId(baseNewsId);
        thisNewsEntry.setAnnouncement(false);
        thisNewsEntry.setText(announcement);
        NewsManager.getInstance().add(thisNewsEntry);

        return thisNewsEntry.getNewsId();
    }

}
