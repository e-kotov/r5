package com.conveyal.r5.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.conveyal.r5.common.SizeLimits.ABSOLUTE_MAX_BOUNDING_BOX_AREA_SQ_KM;
import static com.conveyal.r5.common.SizeLimits.ABSOLUTE_MAX_GRID_CELLS;
import static com.conveyal.r5.common.SizeLimits.DEFAULT_MAX_BOUNDING_BOX_AREA_SQ_KM;
import static com.conveyal.r5.common.SizeLimits.DEFAULT_MAX_GRID_CELLS;
import static com.conveyal.r5.common.SizeLimits.MAX_BOUNDING_BOX_AREA_PROPERTY;
import static com.conveyal.r5.common.SizeLimits.MAX_GRID_CELLS_PROPERTY;
import static com.conveyal.r5.common.SizeLimits.checkedCellCount;
import static com.conveyal.r5.common.SizeLimits.parseMaxBoundingBoxAreaSqKm;
import static com.conveyal.r5.common.SizeLimits.parseMaxGridCells;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Parsing and validation of the configurable size limits and of checked cell counts.
class SizeLimitsTest {

    @AfterEach
    void clearProperties () {
        System.clearProperty(MAX_GRID_CELLS_PROPERTY);
        System.clearProperty(MAX_BOUNDING_BOX_AREA_PROPERTY);
    }

    @Test
    void defaults () {
        assertEquals(1_500_000_000L, DEFAULT_MAX_GRID_CELLS);
        assertEquals(100_000_000D, DEFAULT_MAX_BOUNDING_BOX_AREA_SQ_KM);
        assertEquals(2_147_483_639L, ABSOLUTE_MAX_GRID_CELLS);
        assertEquals(DEFAULT_MAX_GRID_CELLS, SizeLimits.maxGridCells());
        assertEquals(DEFAULT_MAX_BOUNDING_BOX_AREA_SQ_KM, SizeLimits.maxBoundingBoxAreaSqKm());
        assertEquals(DEFAULT_MAX_GRID_CELLS, parseMaxGridCells(null));
        assertEquals(DEFAULT_MAX_BOUNDING_BOX_AREA_SQ_KM, parseMaxBoundingBoxAreaSqKm(null));
    }

    @Test
    void validGridCellValues () {
        assertEquals(1, parseMaxGridCells("1"));
        assertEquals(5_000_000, parseMaxGridCells("5000000"));
        assertEquals(50_000_000, parseMaxGridCells(" 50000000 "));
        assertEquals(ABSOLUTE_MAX_GRID_CELLS, parseMaxGridCells(Long.toString(ABSOLUTE_MAX_GRID_CELLS)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", " ", "abc", "-5", "+5", "0", "00", "1_000", "1,000", "1e9", "1.5", "0x10",
        "2147483640", "2147483647", "9223372036854775807", "99999999999999999999999"
    })
    void invalidGridCellValues (String value) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> parseMaxGridCells(value));
        assertTrue(e.getMessage().contains(MAX_GRID_CELLS_PROPERTY), e.getMessage());
    }

    @Test
    void validAreaValues () {
        assertEquals(975_000, parseMaxBoundingBoxAreaSqKm("975000"));
        assertEquals(10_000_000, parseMaxBoundingBoxAreaSqKm("1e7"));
        assertEquals(0.5, parseMaxBoundingBoxAreaSqKm("0.5"));
        assertEquals(ABSOLUTE_MAX_BOUNDING_BOX_AREA_SQ_KM, parseMaxBoundingBoxAreaSqKm("510000000"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", "abc", "-1", "+1", "0", "0.0", "NaN", "Infinity", "-Infinity", "1e400", "0x10", "100d", "100f",
        "1,000", "510000000.1", "6e8", "1e"
    })
    void invalidAreaValues (String value) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parseMaxBoundingBoxAreaSqKm(value));
        assertTrue(e.getMessage().contains(MAX_BOUNDING_BOX_AREA_PROPERTY), e.getMessage());
    }

    /// The limits are read from JVM system properties each time they are evaluated, and malformed values fail
    /// loudly at that point rather than silently reverting to the defaults.
    @Test
    void systemProperties () {
        System.setProperty(MAX_GRID_CELLS_PROPERTY, "50000000");
        assertEquals(50_000_000, SizeLimits.maxGridCells());
        System.setProperty(MAX_BOUNDING_BOX_AREA_PROPERTY, "10000000");
        assertEquals(10_000_000, SizeLimits.maxBoundingBoxAreaSqKm());
        System.setProperty(MAX_GRID_CELLS_PROPERTY, "lots");
        assertThrows(IllegalArgumentException.class, SizeLimits::maxGridCells);
        System.setProperty(MAX_BOUNDING_BOX_AREA_PROPERTY, "-3");
        assertThrows(IllegalArgumentException.class, SizeLimits::maxBoundingBoxAreaSqKm);
    }

    @Test
    void checkedCellCounts () {
        assertEquals(0, checkedCellCount(0, 0));
        assertEquals(52_694_883, checkedCellCount(4811, 10953));
        assertEquals(ABSOLUTE_MAX_GRID_CELLS, checkedCellCount(1, ABSOLUTE_MAX_GRID_CELLS));
        assertThrows(IllegalArgumentException.class, () -> checkedCellCount(1, ABSOLUTE_MAX_GRID_CELLS + 1));
        assertThrows(IllegalArgumentException.class, () -> checkedCellCount(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> checkedCellCount(10, -1));
        // 65536 * 65536 = 2^32, which wraps to exactly zero in int arithmetic.
        assertEquals(0, 65536 * 65536);
        assertThrows(IllegalArgumentException.class, () -> checkedCellCount(65536, 65536));
        // Int product wraps to a negative number.
        assertTrue(50_000 * 50_000 < 0);
        assertThrows(IllegalArgumentException.class, () -> checkedCellCount(50_000, 50_000));
        // Even the product of two maximal ints fits in a long, but the product of two maximal longs does not.
        assertThrows(IllegalArgumentException.class,
                () -> checkedCellCount(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertThrows(ArithmeticException.class, () -> checkedCellCount(Long.MAX_VALUE, 2));
    }

}
