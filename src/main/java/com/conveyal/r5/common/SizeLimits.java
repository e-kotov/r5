package com.conveyal.r5.common;

import java.util.regex.Pattern;

/// Configurable safety limits on the geographic size of inputs and on the number of cells in Web Mercator grids.
///
/// Two independent guards exist, and raising one does not raise the other:
///
///  - The rough area of WGS84 bounding boxes (OSM street layers, GTFS, uploaded spatial data, freeform point sets),
///    checked in [GeometryUtils#checkWgsEnvelopeSize]. Configure with the JVM system property
///    `-Dr5.maxBoundingBoxAreaSqKm=<km2>`.
///  - The number of cells (width * height) in any Web Mercator grid, checked whenever a
///    [com.conveyal.r5.analyst.WebMercatorExtents] is constructed. Configure with the JVM system property
///    `-Dr5.maxGridCells=<cells>`.
///
/// The properties are read each time a guard is evaluated, so a malformed value fails at the first guarded operation
/// with an [IllegalArgumentException] naming the property, rather than silently falling back to a default.
///
/// Admitting a grid or envelope under these limits is not a guarantee that the corresponding data structures will fit
/// in memory. Grid-shaped structures allocate several bytes per cell (for example, a street linkage holds four
/// `int` arrays, 16 bytes per cell, for each street mode), so very large grids can require many gigabytes of heap.
public final class SizeLimits {

    /// JVM system property overriding [#DEFAULT_MAX_GRID_CELLS].
    public static final String MAX_GRID_CELLS_PROPERTY = "r5.maxGridCells";

    /// JVM system property overriding [#DEFAULT_MAX_BOUNDING_BOX_AREA_SQ_KM].
    public static final String MAX_BOUNDING_BOX_AREA_PROPERTY = "r5.maxBoundingBoxAreaSqKm";

    /// Default maximum number of cells in a Web Mercator grid. Upstream R5 uses 5 million, and fork release
    /// v7.6.1-ek.1 used 50 million. This default admits a zoom 9 grid over the EPSG:3035 "Europe" area of use with its
    /// north edge held to the 80 degree latitude limit (1,217,291,406 cells), the largest of the reference extents
    /// recorded in docs/large-extents.md, with some margin. See that document for memory requirements.
    public static final long DEFAULT_MAX_GRID_CELLS = 1_500_000_000L;

    /// Default maximum rough area of a WGS84 bounding box. Upstream R5 uses 975 thousand km2 (about California), and
    /// fork release v7.6.1-ek.1 used 10 million km2. Even this larger limit rejects envelopes approaching global
    /// extent, which usually indicate stray or misprojected points.
    public static final double DEFAULT_MAX_BOUNDING_BOX_AREA_SQ_KM = 100_000_000;

    /// Hard ceiling on the configurable grid cell limit. Grid cells are addressed with Java `int` indexes and stored
    /// in Java arrays, whose length cannot reach Integer.MAX_VALUE on common JVMs (this is the same "soft maximum"
    /// array length used by the JDK itself). Values above this cannot be indexed downstream, so they are rejected.
    public static final long ABSOLUTE_MAX_GRID_CELLS = Integer.MAX_VALUE - 8;

    /// Hard ceiling on the configurable area limit: the surface area of the Earth, about 510 million km2.
    /// A limit at or above this would no longer reject anything meaningful, so larger values are rejected.
    public static final double ABSOLUTE_MAX_BOUNDING_BOX_AREA_SQ_KM = 510_000_000;

    /// A non-negative decimal integer without sign, separators or exponent.
    private static final Pattern INTEGER = Pattern.compile("[0-9]+");

    /// A non-negative decimal number, optionally with a fraction and/or exponent (no sign, hex, NaN or Infinity).
    private static final Pattern DECIMAL = Pattern.compile("[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?");

    private SizeLimits () { }

    /// @return the effective maximum number of cells in a Web Mercator grid.
    /// @throws IllegalArgumentException if the system property is set to an invalid or unsupported value.
    public static long maxGridCells () {
        return parseMaxGridCells(System.getProperty(MAX_GRID_CELLS_PROPERTY));
    }

    /// @return the effective maximum rough area of a WGS84 bounding box in square kilometers.
    /// @throws IllegalArgumentException if the system property is set to an invalid or unsupported value.
    public static double maxBoundingBoxAreaSqKm () {
        return parseMaxBoundingBoxAreaSqKm(System.getProperty(MAX_BOUNDING_BOX_AREA_PROPERTY));
    }

    /// Parse a grid cell limit. A null value yields the default; anything else must be a plain positive integer
    /// no greater than [#ABSOLUTE_MAX_GRID_CELLS].
    public static long parseMaxGridCells (String value) {
        if (value == null) {
            return DEFAULT_MAX_GRID_CELLS;
        }
        String trimmed = value.trim();
        if (!INTEGER.matcher(trimmed).matches()) {
            throw invalid(MAX_GRID_CELLS_PROPERTY, value, "a plain decimal integer such as 500000000");
        }
        final long cells;
        try {
            cells = Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            // Only possible for digit strings that overflow a long.
            throw invalid(MAX_GRID_CELLS_PROPERTY, value, "an integer no greater than " + ABSOLUTE_MAX_GRID_CELLS);
        }
        if (cells < 1 || cells > ABSOLUTE_MAX_GRID_CELLS) {
            throw invalid(MAX_GRID_CELLS_PROPERTY, value,
                    "an integer between 1 and " + ABSOLUTE_MAX_GRID_CELLS + " (the largest grid that can be " +
                    "indexed by Java int arrays)");
        }
        return cells;
    }

    /// Parse a bounding box area limit in km2. A null value yields the default; anything else must be a positive
    /// finite decimal number no greater than [#ABSOLUTE_MAX_BOUNDING_BOX_AREA_SQ_KM].
    public static double parseMaxBoundingBoxAreaSqKm (String value) {
        if (value == null) {
            return DEFAULT_MAX_BOUNDING_BOX_AREA_SQ_KM;
        }
        String trimmed = value.trim();
        if (!DECIMAL.matcher(trimmed).matches()) {
            throw invalid(MAX_BOUNDING_BOX_AREA_PROPERTY, value, "a plain decimal number of km2 such as 100000000");
        }
        double area = Double.parseDouble(trimmed);
        if (!Double.isFinite(area) || area <= 0 || area > ABSOLUTE_MAX_BOUNDING_BOX_AREA_SQ_KM) {
            throw invalid(MAX_BOUNDING_BOX_AREA_PROPERTY, value,
                    String.format("a number greater than 0 and at most %.0f (the surface area of the Earth)",
                            ABSOLUTE_MAX_BOUNDING_BOX_AREA_SQ_KM));
        }
        return area;
    }

    /// Compute width * height in checked long arithmetic, rejecting negative dimensions and products that cannot be
    /// used as a Java array length or int index. This does not apply the configurable limit, which is checked when
    /// a WebMercatorExtents is constructed; it only prevents silent int overflow where raw dimensions are multiplied.
    /// @return the number of cells, guaranteed to be in the range [0, ABSOLUTE_MAX_GRID_CELLS].
    public static int checkedCellCount (long width, long height) {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException(String.format(
                    "Grid dimensions must be non-negative (width %d, height %d).", width, height));
        }
        final long cells = Math.multiplyExact(width, height);
        if (cells > ABSOLUTE_MAX_GRID_CELLS) {
            throw new IllegalArgumentException(String.format(
                    "Grid of %d x %d = %d cells exceeds the largest supported grid (%d cells, limited by Java int " +
                    "array indexing).", width, height, cells, ABSOLUTE_MAX_GRID_CELLS));
        }
        return (int) cells;
    }

    private static IllegalArgumentException invalid (String property, String value, String expected) {
        return new IllegalArgumentException(String.format(
                "Invalid value '%s' for JVM system property %s: expected %s.", value, property, expected));
    }

}
