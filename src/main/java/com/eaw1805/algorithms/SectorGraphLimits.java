package com.eaw1805.algorithms;


public class SectorGraphLimits {

    private transient int minX = 0;

    private transient int minY = 0;

    private transient int maxX = 85;

    private transient int maxY = 85;

    private transient int availPoints = 0;

    public SectorGraphLimits(final int posX, final int posY,
                             final int totX, final int totY,
                             final int movementPtsAvail, final int minPoints) {
        super();
        availPoints = movementPtsAvail;
        int maxTiles = 0;
        int mps = movementPtsAvail;
        while (mps > 0) {
            mps -= minPoints;
            maxTiles++;
        }
        if (posX - maxTiles > 0) {
            minX = posX - maxTiles;
        } else {
            minX = 0;
        }

        if (posY - maxTiles > 0) {
            minY = posY - maxTiles;
        } else {
            minY = 0;
        }

        if (posX + maxTiles < totX) {
            maxX = posX + maxTiles;
        } else {
            maxX = totX - 1;
        }

        if (posY + maxTiles < totY) {
            maxY = posY + maxTiles;
        } else {
            maxY = totY - 1;
        }
    }

    public int getMinX() {
        return minX;
    }

    public int getMinY() {
        return minY;
    }

    public int getMaxX() {
        return maxX;
    }

    public int getMaxY() {
        return maxY;
    }

    public int getAvailPoints() {
        return availPoints;
    }

}
