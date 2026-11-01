package com.conveyal.r5.analyst;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.datasource.DataSourceException;
import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.common.SizeLimits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;

import static com.conveyal.r5.common.SizeLimits.MAX_BOUNDING_BOX_AREA_PROPERTY;
import static com.conveyal.r5.common.SizeLimits.MAX_GRID_CELLS_PROPERTY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Checks the bounding box area guard and the Web Mercator grid cell guard against explicitly defined large extents.
///
/// Bounding boxes are taken from public definitions (see docs/large-extents.md for provenance):
///  - "area of use" bounding boxes from the EPSG Geodetic Parameter Dataset (IOGP), as published at epsg.io;
///  - U.S. Census Bureau 2023 cartographic boundary file cb_2023_us_state_500k (per-state shape bounding boxes).
///
/// Grid dimensions are computed with the same pixel arithmetic as WebMercatorExtents.forBufferedWgsEnvelope (which
/// WebMercatorGridPointSet uses for whole-network grids) at the default zoom level 9. None of these tests allocate
/// anything proportional to the number of cells: WebMercatorExtents holds only five ints.
class LargeExtentLimitsTest {

    private static final int Z = WebMercatorExtents.DEFAULT_ZOOM;

    // Limits used by the previous fork release v7.6.1-ek.1.
    private static final String EK1_MAX_GRID_CELLS = "50000000";
    private static final String EK1_MAX_AREA = "10000000";

    @AfterEach
    void clearProperties () {
        System.clearProperty(MAX_GRID_CELLS_PROPERTY);
        System.clearProperty(MAX_BOUNDING_BOX_AREA_PROPERTY);
    }

    private static Envelope env (double west, double south, double east, double north) {
        return new Envelope(west, east, south, north);
    }

    /// Assert the zoom 9 {west, north, width, height} pixel bounds and cell count of an envelope.
    private static long assertPixelBounds (Envelope envelope, int west, int north, int width, int height, long cells) {
        int[] wnwh = WebMercatorExtents.pixelBoundsForBufferedWgsEnvelope(envelope, Z);
        assertArrayEquals(new int[] {west, north, width, height}, wnwh);
        long product = (long) wnwh[2] * wnwh[3];
        assertEquals(cells, product);
        return product;
    }

    private static void assertArea (Envelope envelope, double expectedKm2) {
        assertEquals(expectedKm2, GeometryUtils.roughWgsEnvelopeArea(envelope), 1);
    }

    private static void assertGridAccepted (Envelope envelope) {
        WebMercatorExtents extents = WebMercatorExtents.forBufferedWgsEnvelope(envelope, Z);
        assertTrue(extents.nCells() > 0);
    }

    private static void assertGridRejected (Envelope envelope) {
        AnalysisServerException e = assertThrows(AnalysisServerException.class,
                () -> WebMercatorExtents.forBufferedWgsEnvelope(envelope, Z));
        assertTrue(e.getMessage().contains(MAX_GRID_CELLS_PROPERTY), e.getMessage());
    }

    private static void assertEnvelopeAccepted (Envelope envelope) {
        assertDoesNotThrow(() -> GeometryUtils.checkWgsEnvelopeSize(envelope, "test"));
    }

    private static void assertEnvelopeRejectedForArea (Envelope envelope) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> GeometryUtils.checkWgsEnvelopeSize(envelope, "test"));
        assertTrue(e.getMessage().contains(MAX_BOUNDING_BOX_AREA_PROPERTY), e.getMessage());
    }

    // ---- Recorded country-scale cases ----

    /// Sweden's recorded street-network envelope needed 52,694,883 cells at zoom 9, which exceeds the 50 million
    /// limit of v7.6.1-ek.1. 4811 x 10953 is a factorization of that count (the bounds themselves were not recorded
    /// in this repository); it is consistent with Sweden's EPSG:3006 extent below (5150 x 11182 cells).
    @Test
    void swedenRecordedCellCount () {
        WebMercatorExtents sweden = new WebMercatorExtents(69_187, 30_302, 4811, 10953, Z);
        assertEquals(52_694_883, sweden.nCells());
        System.setProperty(MAX_GRID_CELLS_PROPERTY, EK1_MAX_GRID_CELLS);
        assertThrows(AnalysisServerException.class, () -> new WebMercatorExtents(69_187, 30_302, 4811, 10953, Z));
    }

    @Test
    void swedenEpsg3006 () {
        Envelope sweden = env(10.03, 54.96, 24.17, 69.07);
        assertArea(sweden, 1_156_266);
        assertPixelBounds(sweden, 69187, 30302, 5150, 11182, 57_587_300L);
        assertEnvelopeAccepted(sweden);
        assertGridAccepted(sweden);
        System.setProperty(MAX_GRID_CELLS_PROPERTY, EK1_MAX_GRID_CELLS);
        assertGridRejected(sweden);
    }

    /// Germany's recorded envelope (15,298,287 cells) already fit the v7.6.1-ek.1 limit; so does its EPSG extent.
    @Test
    void germanyEpsg4839 () {
        Envelope germany = env(5.86, 47.27, 15.04, 55.09);
        assertArea(germany, 555_798);
        assertPixelBounds(germany, 67669, 41400, 3343, 4557, 15_234_051L);
        assertEnvelopeAccepted(germany);
        assertGridAccepted(germany);
        System.setProperty(MAX_GRID_CELLS_PROPERTY, EK1_MAX_GRID_CELLS);
        System.setProperty(MAX_BOUNDING_BOX_AREA_PROPERTY, EK1_MAX_AREA);
        assertEnvelopeAccepted(germany);
        assertGridAccepted(germany);
        // The recorded cell count is also below the old limit.
        assertTrue(15_298_287L < Long.parseLong(EK1_MAX_GRID_CELLS));
    }

    // ---- Europe ----

    /// The EPSG:3035 (ETRS89-extended / LAEA Europe) area of use reaches 84.73 N to include offshore Svalbard. R5
    /// deliberately rejects latitudes beyond 80 degrees, and that validation is retained.
    @Test
    void europeEpsg3035FullAreaOfUseFailsLatitudeCheck () {
        Envelope europe = env(-35.58, 24.6, 44.83, 84.73);
        assertArea(europe, 34_536_843);
        assertPixelBounds(europe, 52581, 1313, 29278, 54978, 1_609_645_884L);
        DataSourceException e = assertThrows(DataSourceException.class,
                () -> GeometryUtils.checkWgsEnvelopeSize(europe, "test"));
        assertTrue(e.getMessage().contains("Latitude"), e.getMessage());
        // Also exceeds the default grid limit.
        assertGridRejected(europe);
    }

    /// EPSG:3035 area of use with its north edge held to the 80 degree latitude limit. This is the largest reference
    /// extent, and the one the default grid cell limit was chosen to admit.
    @Test
    void europeEpsg3035To80N () {
        Envelope europe = env(-35.58, 24.6, 44.83, 80.0);
        assertArea(europe, 33_645_080);
        long cells = assertPixelBounds(europe, 52581, 14714, 29278, 41577, 1_217_291_406L);
        assertTrue(cells <= SizeLimits.DEFAULT_MAX_GRID_CELLS);
        assertTrue(cells > 500_000_000L, "Would not fit the initially proposed 500 million cell limit.");
        assertEnvelopeAccepted(europe);
        assertGridAccepted(europe);
        // Rejected under the previous fork limits, and under a configured limit just below its size.
        System.setProperty(MAX_BOUNDING_BOX_AREA_PROPERTY, EK1_MAX_AREA);
        assertEnvelopeRejectedForArea(europe);
        System.setProperty(MAX_GRID_CELLS_PROPERTY, Long.toString(cells - 1));
        assertGridRejected(europe);
        System.setProperty(MAX_GRID_CELLS_PROPERTY, Long.toString(cells));
        assertGridAccepted(europe);
    }

    /// EPSG:4258 (ETRS89) area of use, which excludes the Atlantic islands and Turkey, held to 80 N.
    @Test
    void europeEpsg4258To80N () {
        Envelope europe = env(-16.1, 33.26, 38.0, 80.0);
        assertArea(europe, 17_177_862);
        assertPixelBounds(europe, 59674, 14714, 19698, 37969, 747_913_362L);
        assertEnvelopeAccepted(europe);
        assertGridAccepted(europe);
        System.setProperty(MAX_GRID_CELLS_PROPERTY, "500000000");
        assertGridRejected(europe);
    }

    // ---- China ----

    /// EPSG:4490 (China Geodetic Coordinate System 2000) area of use, "China - onshore and offshore".
    @Test
    void chinaEpsg4490 () {
        Envelope china = env(73.62, 16.7, 134.77, 53.56);
        assertArea(china, 22_767_259);
        assertPixelBounds(china, 92340, 42355, 22265, 17013, 378_794_445L);
        assertEnvelopeAccepted(china);
        assertGridAccepted(china);
        System.setProperty(MAX_BOUNDING_BOX_AREA_PROPERTY, EK1_MAX_AREA);
        System.setProperty(MAX_GRID_CELLS_PROPERTY, EK1_MAX_GRID_CELLS);
        assertEnvelopeRejectedForArea(china);
        assertGridRejected(china);
    }

    // ---- United States ----

    /// Control: the contiguous United States (48 states + DC), from U.S. Census Bureau cb_2023_us_state_500k.
    /// This is NOT the whole United States; see the tests below.
    @Test
    void contiguousUsControlCensus () {
        Envelope conus = env(-124.763068, 24.521304, -66.949895, 49.384358);
        assertArea(conus, 14_186_823);
        assertPixelBounds(conus, 20111, 44798, 21050, 11525, 242_601_250L);
        assertEnvelopeAccepted(conus);
        assertGridAccepted(conus);
        System.setProperty(MAX_BOUNDING_BOX_AREA_PROPERTY, EK1_MAX_AREA);
        assertEnvelopeRejectedForArea(conus);
    }

    /// Control: EPSG:5070 (NAD83 / Conus Albers) area of use, "CONUS onshore".
    @Test
    void contiguousUsControlEpsg5070 () {
        Envelope conus = env(-124.79, 24.41, -66.91, 49.38);
        assertArea(conus, 14_275_139);
        assertPixelBounds(conus, 20101, 44801, 21074, 11566, 243_741_884L);
        assertEnvelopeAccepted(conus);
        assertGridAccepted(conus);
    }

    /// The whole United States (50 states + DC) is NOT supported as a single envelope: Alaska's Aleutian Islands
    /// extend west across the 180 degree meridian to 172.46 E (Attu). The naive min/max envelope spans almost the
    /// whole globe, and R5 cannot represent an envelope that wraps around the antimeridian. The antimeridian check is
    /// retained, so the envelope is rejected rather than silently replaced with a smaller one.
    @Test
    void fullUsIncludingAlaskaAndHawaiiIsRejectedAtAntimeridian () {
        Envelope us = env(-179.146711, 18.910361, 179.77847, 71.387815);
        assertArea(us, 164_064_549);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> GeometryUtils.checkWgsEnvelopeSize(us, "test"));
        assertTrue(e.getMessage().contains("antimeridian"), e.getMessage());
        // Even with the largest allowed area limit, the antimeridian check still rejects it.
        System.setProperty(MAX_BOUNDING_BOX_AREA_PROPERTY, Double.toString(SizeLimits.ABSOLUTE_MAX_BOUNDING_BOX_AREA_SQ_KM));
        assertThrows(IllegalArgumentException.class, () -> GeometryUtils.checkWgsEnvelopeSize(us, "test"));
        // As a grid it would also be far beyond the largest indexable grid.
        int[] wnwh = WebMercatorExtents.pixelBoundsForBufferedWgsEnvelope(us, Z);
        assertTrue((long) wnwh[2] * wnwh[3] > SizeLimits.ABSOLUTE_MAX_GRID_CELLS);
    }

    /// Alaska alone has the same antimeridian problem.
    @Test
    void alaskaIsRejectedAtAntimeridian () {
        Envelope alaska = env(-179.146711, 51.215567, 179.77847, 71.387815);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> GeometryUtils.checkWgsEnvelopeSize(alaska, "test"));
        assertTrue(e.getMessage().contains("antimeridian"), e.getMessage());
    }

    /// Hawaii alone lies entirely in the western hemisphere and fits.
    @Test
    void hawaiiCensus () {
        Envelope hawaii = env(-178.334698, 18.910361, -154.806773, 28.402123);
        assertArea(hawaii, 2_526_373);
        assertPixelBounds(hawaii, 606, 54743, 8567, 3780, 32_383_260L);
        assertEnvelopeAccepted(hawaii);
        assertGridAccepted(hawaii);
    }

    /// For information only, this is NOT the United States: the 50 states and DC with Alaska's islands east of the
    /// antimeridian (the western Aleutians, 172.46 E to 180) dropped. It passes both guards at the default limits;
    /// it is recorded here so that its acceptance is not mistaken for support of the whole United States.
    @Test
    void usWesternHemispherePortionIsNotTheUs () {
        Envelope partial = env(-179.146711, 18.910361, -66.949895, 71.387815);
        assertArea(partial, 51_285_117);
        assertPixelBounds(partial, 310, 27804, 40851, 30719, 1_254_901_869L);
        assertEnvelopeAccepted(partial);
        assertGridAccepted(partial);
    }

    // ---- Zoom 10, the default zoom of r5r isochrone() ----

    private static long cellsAtZoom (Envelope envelope, int zoom) {
        int[] wnwh = WebMercatorExtents.pixelBoundsForBufferedWgsEnvelope(envelope, zoom);
        return (long) wnwh[2] * wnwh[3];
    }

    @Test
    void zoom10CellCounts () {
        final int z10 = 10;
        assertEquals(60_920_405L, cellsAtZoom(env(5.86, 47.27, 15.04, 55.09), z10));
        assertEquals(230_283_876L, cellsAtZoom(env(10.03, 54.96, 24.17, 69.07), z10));
        assertEquals(4_868_999_316L, cellsAtZoom(env(-35.58, 24.6, 44.83, 80.0), z10));
        assertEquals(2_991_577_510L, cellsAtZoom(env(-16.1, 33.26, 38.0, 80.0), z10));
        assertEquals(970_297_752L, cellsAtZoom(env(-124.763068, 24.521304, -66.949895, 49.384358), z10));
        assertEquals(974_967_536L, cellsAtZoom(env(-124.79, 24.41, -66.91, 49.38), z10));
        assertEquals(129_498_772L, cellsAtZoom(env(-178.334698, 18.910361, -154.806773, 28.402123), z10));
        // China at zoom 10 is just over the default limit, but can be admitted by configuration.
        Envelope china = env(73.62, 16.7, 134.77, 53.56);
        long chinaCells = cellsAtZoom(china, z10);
        assertEquals(1_515_099_225L, chinaCells);
        AnalysisServerException e = assertThrows(AnalysisServerException.class,
                () -> WebMercatorExtents.forBufferedWgsEnvelope(china, z10));
        assertTrue(e.getMessage().contains("1515099225"), e.getMessage());
        System.setProperty(MAX_GRID_CELLS_PROPERTY, "1600000000");
        assertEquals(chinaCells, WebMercatorExtents.forBufferedWgsEnvelope(china, z10).nCells());
        // Europe at zoom 10 exceeds the largest indexable grid, so no configuration can admit it.
        assertTrue(cellsAtZoom(env(-16.1, 33.26, 38.0, 80.0), z10) > SizeLimits.ABSOLUTE_MAX_GRID_CELLS);
    }

    // ---- Global sanity ----

    /// Retained validation: an envelope covering nearly the whole permitted latitude range is still rejected.
    @Test
    void nearGlobalEnvelopeRejected () {
        Envelope world = env(-170, -80, 170, 80);
        assertEnvelopeRejectedForArea(world);
        int[] wnwh = WebMercatorExtents.pixelBoundsForBufferedWgsEnvelope(world, Z);
        assertTrue((long) wnwh[2] * wnwh[3] > SizeLimits.ABSOLUTE_MAX_GRID_CELLS);
        assertGridRejected(world);
    }

}
