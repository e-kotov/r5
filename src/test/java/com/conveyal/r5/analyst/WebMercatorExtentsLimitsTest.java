package com.conveyal.r5.analyst;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.r5.analyst.cluster.TravelTimeSurfaceTask;
import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.common.SizeLimits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;

import static com.conveyal.r5.common.SizeLimits.MAX_BOUNDING_BOX_AREA_PROPERTY;
import static com.conveyal.r5.common.SizeLimits.MAX_GRID_CELLS_PROPERTY;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Edges of the grid cell and bounding box area guards, overflow handling, and configuration failures.
/// Only WebMercatorExtents objects (five ints) are created, never grids of the tested sizes.
class WebMercatorExtentsLimitsTest {

    @AfterEach
    void clearProperties () {
        System.clearProperty(MAX_GRID_CELLS_PROPERTY);
        System.clearProperty(MAX_BOUNDING_BOX_AREA_PROPERTY);
    }

    private static WebMercatorExtents extents (int width, int height, int zoom) {
        return new WebMercatorExtents(0, 0, width, height, zoom);
    }

    private static AnalysisServerException rejected (int width, int height, int zoom) {
        return assertThrows(AnalysisServerException.class, () -> extents(width, height, zoom));
    }

    /// Edges of the previous limits: upstream R5 (5 million) and fork release v7.6.1-ek.1 (50 million).
    @Test
    void previousLimitEdgesUnderConfiguration () {
        System.setProperty(MAX_GRID_CELLS_PROPERTY, "5000000");
        assertEquals(5_000_000, extents(2500, 2000, 9).nCells());
        rejected(2500, 2001, 9);
        System.setProperty(MAX_GRID_CELLS_PROPERTY, "50000000");
        assertEquals(50_000_000, extents(10_000, 5000, 9).nCells());
        rejected(10_000, 5001, 9);
    }

    /// Edge of the default limit, 1.5 billion cells, which is 50,000 x 30,000.
    @Test
    void defaultLimitEdge () {
        assertEquals(1_500_000_000L, SizeLimits.maxGridCells());
        assertEquals(1_500_000_000, extents(50_000, 30_000, 9).nCells());
        AnalysisServerException e = rejected(50_000, 30_001, 9);
        assertTrue(e.getMessage().contains("1500050000"), e.getMessage());
        assertTrue(e.getMessage().contains(MAX_GRID_CELLS_PROPERTY), e.getMessage());
        // Old limits are admitted by default.
        assertDoesNotThrow(() -> extents(10_000, 5001, 9));
    }

    /// Edge of the largest configurable limit, which is the largest grid indexable by Java int arrays.
    @Test
    void absoluteLimitEdge () {
        System.setProperty(MAX_GRID_CELLS_PROPERTY, Long.toString(SizeLimits.ABSOLUTE_MAX_GRID_CELLS));
        // 46340^2 = 2,147,395,600 is the largest square below the ceiling; zoom 12 is 1,048,576 pixels wide.
        assertEquals(2_147_395_600, extents(46_340, 46_340, 12).nCells());
        rejected(46_341, 46_341, 12);
        // 2,147,483,639 = 1 x 2147483639 cannot be built: no grid is that wide, even at zoom 12.
        rejected(1, (int) SizeLimits.ABSOLUTE_MAX_GRID_CELLS, 12);
    }

    /// Products that overflow int arithmetic were silently compared with the limit before; now they are computed in
    /// long arithmetic and rejected.
    @Test
    void overflowingProductsRejected () {
        // 65536 * 65536 wraps to exactly 0 as an int, which the old check admitted.
        assertEquals(0, 65536 * 65536);
        AnalysisServerException e = rejected(65536, 65536, 9);
        assertTrue(e.getMessage().contains("4294967296"), e.getMessage());
        // 50,000 * 50,000 wraps to a negative int, which the old check also admitted.
        assertTrue(50_000 * 50_000 < 0);
        rejected(50_000, 50_000, 9);
        // At zoom 12 a full-world-width grid is 2^20 pixels on each side: 2^40 cells.
        rejected(1 << 20, 1 << 20, 12);
        // Even with the largest configured limit.
        System.setProperty(MAX_GRID_CELLS_PROPERTY, Long.toString(SizeLimits.ABSOLUTE_MAX_GRID_CELLS));
        rejected(65536, 65536, 9);
        rejected(50_000, 50_000, 9);
    }

    @Test
    void invalidDimensionsRejected () {
        rejected(-1, 10, 9);
        rejected(10, -1, 9);
        rejected(Integer.MIN_VALUE, Integer.MIN_VALUE, 9);
        // Wider or taller than the world at this zoom level (256 * 2^9 = 131072 pixels).
        rejected(131_073, 1, 9);
        rejected(1, 131_073, 9);
        assertDoesNotThrow(() -> extents(131_072, 1, 9));
        // The zoom range check is unchanged.
        rejected(10, 10, 8);
        rejected(10, 10, 13);
    }

    /// A malformed configuration fails at the first guarded operation with a message naming the property.
    @Test
    void malformedConfigurationFailsClearly () {
        System.setProperty(MAX_GRID_CELLS_PROPERTY, "500M");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> extents(10, 10, 9));
        assertTrue(e.getMessage().contains(MAX_GRID_CELLS_PROPERTY), e.getMessage());
        System.setProperty(MAX_GRID_CELLS_PROPERTY, "3000000000");
        assertThrows(IllegalArgumentException.class, () -> extents(10, 10, 9));
        System.clearProperty(MAX_GRID_CELLS_PROPERTY);

        Envelope small = new Envelope(13.08, 13.77, 52.33, 52.68);
        System.setProperty(MAX_BOUNDING_BOX_AREA_PROPERTY, "1e9");
        e = assertThrows(IllegalArgumentException.class, () -> GeometryUtils.checkWgsEnvelopeSize(small, "test"));
        assertTrue(e.getMessage().contains(MAX_BOUNDING_BOX_AREA_PROPERTY), e.getMessage());
        System.setProperty(MAX_BOUNDING_BOX_AREA_PROPERTY, "big");
        assertThrows(IllegalArgumentException.class, () -> GeometryUtils.checkWgsEnvelopeSize(small, "test"));
    }

    /// The area guard edges: previous fork limit, default limit, and a configured limit.
    @Test
    void areaLimitEdges () {
        // A 1 degree tall band on the equator, 10 degrees wide: about 123,505 km2 per 10 degrees of longitude.
        Envelope band = new Envelope(0, 10, -0.5, 0.5);
        double area = GeometryUtils.roughWgsEnvelopeArea(band);
        assertEquals(123_505, area, 1);
        System.setProperty(MAX_BOUNDING_BOX_AREA_PROPERTY, Double.toString(area));
        assertDoesNotThrow(() -> GeometryUtils.checkWgsEnvelopeSize(band, "test"));
        System.setProperty(MAX_BOUNDING_BOX_AREA_PROPERTY, Double.toString(Math.nextDown(area)));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> GeometryUtils.checkWgsEnvelopeSize(band, "test"));
        assertTrue(e.getMessage().contains("exceeds limit"), e.getMessage());
        System.clearProperty(MAX_BOUNDING_BOX_AREA_PROPERTY);

        // 11,000,000 km2 exceeds the v7.6.1-ek.1 limit (10 million) but not the default (100 million).
        Envelope eleven = new Envelope(-50, 50, -4.5, 4.5);
        double elevenArea = GeometryUtils.roughWgsEnvelopeArea(eleven);
        assertTrue(elevenArea > 10_000_000 && elevenArea < 100_000_000, Double.toString(elevenArea));
        assertDoesNotThrow(() -> GeometryUtils.checkWgsEnvelopeSize(eleven, "test"));
        System.setProperty(MAX_BOUNDING_BOX_AREA_PROPERTY, "10000000");
        assertThrows(IllegalArgumentException.class, () -> GeometryUtils.checkWgsEnvelopeSize(eleven, "test"));
        System.clearProperty(MAX_BOUNDING_BOX_AREA_PROPERTY);

        // Just over the default limit is rejected.
        Envelope huge = new Envelope(-179, 1, -70, 70);
        assertTrue(GeometryUtils.roughWgsEnvelopeArea(huge) > SizeLimits.DEFAULT_MAX_BOUNDING_BOX_AREA_SQ_KM);
        assertThrows(IllegalArgumentException.class, () -> GeometryUtils.checkWgsEnvelopeSize(huge, "test"));
    }

    /// Raising one guard does not raise the other.
    @Test
    void guardsAreIndependent () {
        // Sweden's EPSG:3006 extent: 1.16 million km2, 57,587,300 cells at zoom 9.
        Envelope sweden = new Envelope(10.03, 24.17, 54.96, 69.07);
        System.setProperty(MAX_GRID_CELLS_PROPERTY, "50000000");
        assertDoesNotThrow(() -> GeometryUtils.checkWgsEnvelopeSize(sweden, "test"));
        assertThrows(AnalysisServerException.class, () -> WebMercatorExtents.forBufferedWgsEnvelope(sweden, 9));
        System.clearProperty(MAX_GRID_CELLS_PROPERTY);
        System.setProperty(MAX_BOUNDING_BOX_AREA_PROPERTY, "975000");
        assertThrows(IllegalArgumentException.class, () -> GeometryUtils.checkWgsEnvelopeSize(sweden, "test"));
        assertDoesNotThrow(() -> WebMercatorExtents.forBufferedWgsEnvelope(sweden, 9));
    }

    /// Raw task dimensions (deserialized from JSON, not yet validated) are multiplied with overflow checks.
    @Test
    void rawTaskDimensionsChecked () {
        TravelTimeSurfaceTask task = new TravelTimeSurfaceTask();
        task.zoom = 9;
        task.width = 65536;
        task.height = 65536;
        assertThrows(IllegalArgumentException.class, task::nTargetsPerOrigin);
        assertThrows(AnalysisServerException.class, task::getWebMercatorExtents);
        task.width = 100;
        task.height = 200;
        assertEquals(20_000, task.nTargetsPerOrigin());
    }

}
