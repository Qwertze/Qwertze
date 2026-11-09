package com.porter.qwerte.printer.schematic;

import java.util.List;

/**
 * A parsed .litematic: its regions with the block storage still packed, plus what the file says about itself. Pure (no
 * Minecraft classes); turning a palette entry into a block state happens later, in the runtime StateResolver.
 */
public final class SchematicData {
    public final String name;
    public final String author;
    public final int version;
    public final int dataVersion;
    public final List<Region> regions;
    public final long totalVolume;
    /** Things worth telling the user (a newer file version, ...). */
    public final List<String> warnings;

    public SchematicData(String name, String author, int version, int dataVersion, List<Region> regions, long totalVolume, List<String> warnings) {
        this.name = name;
        this.author = author;
        this.version = version;
        this.dataVersion = dataVersion;
        this.regions = List.copyOf(regions);
        this.totalVolume = totalVolume;
        this.warnings = List.copyOf(warnings);
    }

    public Region region(String regionName) {
        for (Region r : regions) if (r.name.equals(regionName)) return r;
        return null;
    }

    /**
     * One region. {@code posX..} is the file's Position and {@code sizeX..} its (possibly negative) Size; the cells are
     * stored with the absolute size, starting at the region's smallest corner ({@link #minRelX()}). The palette entries
     * are {@link LitematicFormat#stateKey} strings; the bits stay packed.
     */
    public static final class Region {
        public final String name;
        public final int posX, posY, posZ;
        public final int sizeX, sizeY, sizeZ;
        public final String[] palette;
        public final long[] bits;
        public final int bitsPer;
        private int badIndexes;

        public Region(String name, int posX, int posY, int posZ, int sizeX, int sizeY, int sizeZ, String[] palette, long[] bits, int bitsPer) {
            this.name = name;
            this.posX = posX;
            this.posY = posY;
            this.posZ = posZ;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.palette = palette;
            this.bits = bits;
            this.bitsPer = bitsPer;
        }

        public int absX() {
            return Math.abs(sizeX);
        }

        public int absY() {
            return Math.abs(sizeY);
        }

        public int absZ() {
            return Math.abs(sizeZ);
        }

        public long volume() {
            return (long) absX() * absY() * absZ();
        }

        /** The region's smallest corner relative to the placement origin (untransformed). */
        public int minRelX() {
            return LitematicFormat.minCorner(posX, sizeX);
        }

        public int minRelY() {
            return LitematicFormat.minCorner(posY, sizeY);
        }

        public int minRelZ() {
            return LitematicFormat.minCorner(posZ, sizeZ);
        }

        /** The palette index of a cell (0 to abs size - 1 on each axis), or -1 when the file's index is not in the palette. */
        public int paletteIndex(int x, int y, int z) {
            int i = LitematicFormat.get(bits, bitsPer, LitematicFormat.index(x, y, z, absX(), absZ()));
            if (i >= palette.length) {
                badIndexes++;
                return -1;
            }
            return i;
        }

        /** The palette entry of a cell, or null for a bad index. */
        public String keyAt(int x, int y, int z) {
            int i = paletteIndex(x, y, z);
            return i < 0 ? null : palette[i];
        }

        /** How many cells so far were found to refer to a palette entry that does not exist. */
        public int badIndexes() {
            return badIndexes;
        }
    }
}
