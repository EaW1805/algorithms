package com.eaw1805.algorithms;

import com.eaw1805.data.HibernateUtil;
import com.eaw1805.data.constants.NationConstants;
import com.eaw1805.data.constants.RegionConstants;
import com.eaw1805.data.constants.RelationConstants;
import com.eaw1805.data.constants.TerrainConstants;
import com.eaw1805.data.managers.NationManager;
import com.eaw1805.data.managers.RelationsManager;
import com.eaw1805.data.managers.army.SpyManager;
import com.eaw1805.data.managers.economy.TradeCityManager;
import com.eaw1805.data.managers.fleet.ShipManager;
import com.eaw1805.data.managers.map.SectorManager;
import com.eaw1805.data.model.Game;
import com.eaw1805.data.model.MapElement;
import com.eaw1805.data.model.Nation;
import com.eaw1805.data.model.NationsRelation;
import com.eaw1805.data.model.map.Position;
import com.eaw1805.data.model.map.Region;
import com.eaw1805.data.model.map.Sector;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Used to determine the sectors that are visible for the particular nation based on the fog of war rules.
 */
public class FogOfWarInspector
        implements RegionConstants, TerrainConstants, RelationConstants {

    /**
     * a log4j logger to print messages.
     */
    private static final Logger LOGGER = LogManager.getLogger(FogOfWarInspector.class);

    /**
     * Stores the sectors for easy access.
     */
    private final transient Sector[][] sectorsArray;

    /**
     * Stores the sectors that are directly controlled by the nation.
     */
    private final transient Set<Sector> ownedSectors;

    /**
     * Stores the sectors that are not controlled by the nation but from an ally.
     */
    private final transient Set<Sector> alliedSectors;

    /**
     * Stores the sectors that are not controlled by the nation but are potentially visible.
     */
    private final transient Set<Sector> foreignSectors;

    /**
     * Stores the sectors that are not controlled by the nation but are visible.
     */
    private final transient Set<Sector> visibleSectors;

    /**
     * The game instance.
     */
    private final transient Game thisGame;

    /**
     * The region to investigate;
     */
    private final transient Region thisRegion;

    /**
     * The owner.
     */
    private final transient Nation thisOwner;

    /**
     * light cavalry scouts.
     */
    private final transient Map<Nation, Map<Sector, BigInteger>> scoutingUnits;

    /**
     * List of all active nations.
     */
    private final transient List<Nation> lstNations;

    /**
     * Lower-left corner of map (x coordinate).
     */
    private transient int minX;

    /**
     * Lower-left corner of map (y coordinate).
     */
    private transient int minY;

    /**
     * upper-right corner of map (x coordinate).
     */
    private transient int maxX;

    /**
     * upper-right corner of map (y coordinate).
     */
    private transient int maxY;

    private final int[] regionSizeX, regionSizeY;

    /**
     * nation relations.
     */
    private static Map<Nation, Map<Nation, NationsRelation>> relationsMap;

    /**
     * Default constructor.
     *
     * @param game         the Game to investigate.
     * @param region       the Region to investigate.
     * @param owner        the Nation to investigate.
     * @param aliveNations the list of alive nations.
     * @param scouts       the light cavalry scouts.
     */
    public FogOfWarInspector(final Game game,
                             final Region region,
                             final Nation owner,
                             final List<Nation> aliveNations,
                             final Map<Nation, Map<Sector, BigInteger>> scouts) {
        ownedSectors = new HashSet<Sector>();
        alliedSectors = new HashSet<Sector>();
        foreignSectors = new HashSet<Sector>();
        visibleSectors = new HashSet<Sector>();
        scoutingUnits = scouts;

        switch (game.getScenarioId()) {
            case HibernateUtil.DB_FREE:
                regionSizeX = RegionConstants.REGION_1804_SIZE_X;
                regionSizeY = RegionConstants.REGION_1804_SIZE_Y;
                break;

            case HibernateUtil.DB_S3:
                regionSizeX = RegionConstants.REGION_1808_SIZE_X;
                regionSizeY = RegionConstants.REGION_1808_SIZE_Y;
                break;

            case HibernateUtil.DB_S1:
            case HibernateUtil.DB_S2:
            default:
                regionSizeX = RegionConstants.REGION_1805_SIZE_X;
                regionSizeY = RegionConstants.REGION_1805_SIZE_Y;
                break;
        }

        final int rId = region.getId() - 1;
        sectorsArray = new Sector[regionSizeX[rId] + 2][regionSizeY[rId] + 2];
        thisGame = game;
        thisRegion = region;
        thisOwner = owner;

        lstNations = aliveNations;
        lstNations.remove(thisOwner); // remove owner's nation from list

        // Retrieve nation relations
        relationsMap = mapNationRelation(thisGame);

        // Construct the graph from the sectors.
        createGraphFromSectors();

        // Construct the graph from the allied sectors.
        createGraphFromAlliedSectors();

        // Sectors at a distance of 3 tiles (inclusive) of the borders.
        investigateForeignSectors();

        // Add units
        createGraphFromUnits(thisOwner);

        // Second pass, add all sectors owned by allied or friendly nations
        for (final Nation nation : lstNations) {
            // Examine foreign relations
            final NationsRelation relation = getByNations(thisGame, nation, thisOwner);
            if (relation != null && relation.getRelation() == REL_ALLIANCE) {
                createGraphFromUnits(nation);
            }
        }

        // add all trade cities
        addUnitSectors(TradeCityManager.getInstance().listByGame(thisGame), 0);
    }

    /**
     * Get the Relations from the database that corresponds to the input
     * parameters.
     *
     * @param owner  the Owner of the Report object.
     * @param target the Target of the Report object.
     * @return an Entity object.
     */
    public NationsRelation getByNations(final Game game, final Nation owner, final Nation target) {
        return relationsMap.get(owner).get(target);
    }

    private Map<Nation, Map<Nation, NationsRelation>> mapNationRelation(final Game thisGame) {
        final Map<Nation, Map<Nation, NationsRelation>> mapRelations = new HashMap<Nation, Map<Nation, NationsRelation>>();
        final List<Nation> lstNations = NationManager.getInstance().list();
        for (final Nation nation : lstNations) {
            final List<NationsRelation> lstRelations = RelationsManager.getInstance().listByGameNation(thisGame, nation);
            final Map<Nation, NationsRelation> nationRelations = new HashMap<Nation, NationsRelation>();
            for (final NationsRelation relation : lstRelations) {
                nationRelations.put(relation.getTarget(), relation);
            }
            mapRelations.put(nation, nationRelations);
        }

        return mapRelations;
    }

    public Set<Sector> getVisibleSectors() {
        final Set<Sector> allSectors = new HashSet<Sector>();
        allSectors.addAll(visibleSectors);
        allSectors.addAll(alliedSectors);
        return allSectors;
    }

    /**
     * Construct the graph from the sectors.
     */
    private void createGraphFromSectors() {
        // First pass, add all owned sectors
        final List<Sector> lstSectorsOwned;
        if (thisOwner.getId() == NationConstants.NATION_NEUTRAL) {
            lstSectorsOwned = SectorManager.getInstance().listByGameRegion(thisGame, thisRegion);

        } else {
            lstSectorsOwned = SectorManager.getInstance().listByGameRegionNation(thisGame, thisRegion, thisOwner);
        }

        final int rId = thisRegion.getId() - 1;
        minX = regionSizeX[rId] + 2;
        minY = regionSizeY[rId] + 2;
        maxX = 0;
        maxY = 0;

        for (final Sector sector : lstSectorsOwned) {
            ownedSectors.add(sector);
            sectorsArray[sector.getPosition().getX() + 1][sector.getPosition().getY() + 1] = sector;

            // track lower-left and upper-right corners
            minX = Math.min(minX, sector.getPosition().getX());
            minY = Math.min(minY, sector.getPosition().getY());
            maxX = Math.max(maxX, sector.getPosition().getX());
            maxY = Math.max(maxY, sector.getPosition().getY());
        }

        // Check sectors that are at most 3 sectors away
        final List<Sector> neighboringSectors = SectorManager.getInstance().listByGameRegion(thisGame, thisRegion,
                minX - 3, minY - 3, maxX + 3, maxY + 3);

        addNeighbouringSectors(neighboringSectors);
    }

    /**
     * Construct the graph from the sectors.
     */
    private void createGraphFromAlliedSectors() {
        final int rId = thisRegion.getId() - 1;

        // Second pass, add all sectors owned by allied or friendly nations
        for (final Nation nation : lstNations) {
            // Examine foreign relations
            final NationsRelation relation = getByNations(thisGame, nation, thisOwner);
            if (relation != null && relation.getRelation() == REL_ALLIANCE) {
                minX = regionSizeX[rId] + 2;
                minY = regionSizeY[rId] + 2;
                maxX = 0;
                maxY = 0;

                final List<Sector> lstSectors = SectorManager.getInstance().listByGameRegionNation(thisGame, thisRegion, nation);
                for (final Sector sector : lstSectors) {
                    ownedSectors.add(sector);
                    alliedSectors.add(sector);
                    sectorsArray[sector.getPosition().getX() + 1][sector.getPosition().getY() + 1] = sector;

                    // track lower-left and upper-right corners
                    minX = Math.min(minX, sector.getPosition().getX());
                    minY = Math.min(minY, sector.getPosition().getY());
                    maxX = Math.max(maxX, sector.getPosition().getX());
                    maxY = Math.max(maxY, sector.getPosition().getY());
                }

                // Check sectors that are at most 3 sectors away
                final List<Sector> neighboringSectors = SectorManager.getInstance().listByGameRegion(thisGame, thisRegion,
                        minX - 3, minY - 3, maxX + 3, maxY + 3);

                addNeighbouringSectors(neighboringSectors);
            }
        }
    }

    private void addNeighbouringSectors(final List<Sector> lstSectors) {
        for (final Sector sector : lstSectors) {
            // if sector already exists, skip
            if (foreignSectors.contains(sector) || ownedSectors.contains(sector)) {
                continue;
            }

            foreignSectors.add(sector);
            sectorsArray[sector.getPosition().getX() + 1][sector.getPosition().getY() + 1] = sector;
        }
    }

    private void createGraphFromUnits(final Nation nation) {
        // Check sectors on the tile and the adjacent ones of their spies and light cavalry scouts.
        addUnitSectors(SpyManager.getInstance().listGameRegionNation(thisGame, thisRegion, nation), 1);

        // Iterate through all scouting units
        if (scoutingUnits.get(nation) != null) {
            for (final Map.Entry<Sector, BigInteger> sector : scoutingUnits.get(nation).entrySet()) {
                if (sector.getKey().getPosition().getRegion().getId() == thisRegion.getId()) {
                    // Every army that has at least 40 battalions of Light Cavalry (LC),
                    // each with headcount of more than 500 men, will provide reports
                    if (sector.getValue().intValue() >= 40) {
                        addUnitPosition(sector.getKey().getPosition(), 1);
                    }
                }
            }
        }

        // every port (shipyard) that a merchant or warship is situated.
        addUnitSectors(ShipManager.getInstance().listGameNationRegion(thisGame, nation, thisRegion), 0);
    }

    private <E extends MapElement> void addUnitSectors(final List<E> lstUnits, final int radius) {
        // Iterate through all units
        for (final MapElement thisUnit : lstUnits) {
            addUnitPosition(thisUnit.getPosition(), radius);
        }
    }

    private void addUnitPosition(final Position position, final int radius) {
        if (position.getRegion().getId() != thisRegion.getId()) {
            return;
        }

        final Sector sector = SectorManager.getInstance().getByPosition(position);
        ownedSectors.add(sector);
        alliedSectors.add(sector);

        if (radius > 0) {
            final List<Sector> lstSectors = SectorManager.getInstance().listByGameRegion(thisGame,
                    sector.getPosition().getRegion(),
                    position.getX() - radius, position.getY() - radius,
                    position.getX() + radius, position.getY() + radius);

            for (final Sector thatSector : lstSectors) {
                ownedSectors.add(thatSector);
                alliedSectors.add(thatSector);
            }
        }
    }

    private void checkForeignSector(final Sector sector) {
        final int posX = sector.getPosition().getX() + 1;
        final int minX = Math.max(posX - 3, 1);
        final int maxX = Math.min(posX + 3, sectorsArray.length - 1);

        final int posY = sector.getPosition().getY() + 1;
        final int minY = Math.max(posY - 3, 1);
        final int maxY = Math.min(posY + 3, sectorsArray[0].length - 1);

        for (int thisX = minX; thisX <= maxX; thisX++) {
            for (int thisY = minY; thisY <= maxY; thisY++) {
                final Sector targetSector = sectorsArray[thisX][thisY];
                if (targetSector != null) {
                    if (ownedSectors.contains(targetSector) && targetSector.getPosition().distance(sector.getPosition()) <= 3) {
                        visibleSectors.add(sector);
                        return;
                    }
                }
            }
        }
    }

    private void investigateForeignSectors() {
        for (final Sector sector : foreignSectors) {
            // Compute single-source shortest paths
            checkForeignSector(sector);
        }
    }

}
