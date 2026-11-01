package com.conveyal.r5.analyst;

import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.transit.TransportNetwork;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Opt-in smoke test that really allocates and links a grid larger than the v7.6.1-ek.1 limit of 50 million cells.
/// It is skipped in normal builds and must be run separately with a bounded heap and time limit:
///
///     gradle test --tests '*LargeGridSmokeTest' -Pr5LargeGridSmoke [-Pr5LargeGridSmokeHeap=4g]
///
/// The grid has Sweden's recorded zoom 9 cell count (52,694,883 = 4811 x 10953). A street linkage holds four int
/// arrays per cell, so this needs about 843 MB for the linkage plus the street network and JVM overhead.
/// Linking time depends on the number of cells, even where most of them are far from any street.
@EnabledIfSystemProperty(named = "r5.largeGridSmoke", matches = "true")
class LargeGridSmokeTest {

    private static final Logger LOG = LoggerFactory.getLogger(LargeGridSmokeTest.class);

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void linkSwedenSizedGrid () throws IOException {
        // Work on a copy of the OSM file: loading it creates MapDB files next to it.
        Path tempDir = Files.createTempDirectory("r5-large-grid-smoke-");
        try {
            Path osm = Files.copy(Path.of("./src/test/resources/porto_portugal.osm.pbf"),
                    tempDir.resolve("porto_portugal.osm.pbf"), StandardCopyOption.REPLACE_EXISTING);
            linkSwedenSizedGrid(TransportNetwork.fromFiles(osm.toString(), List.of()));
        } finally {
            try (Stream<Path> paths = Files.walk(tempDir)) {
                paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }

    private void linkSwedenSizedGrid (TransportNetwork network) {
        final int zoom = WebMercatorExtents.DEFAULT_ZOOM;
        // Put the small Porto street network near the northwest corner of the large grid.
        WebMercatorExtents porto = WebMercatorExtents.forBufferedWgsEnvelope(network.streetLayer.envelope, zoom);
        int width = 4811;
        int height = 10953;
        WebMercatorExtents large = new WebMercatorExtents(porto.west - 10, porto.north - 10, width, height, zoom);
        assertEquals(52_694_883, large.nCells());

        Runtime rt = Runtime.getRuntime();
        long before = rt.totalMemory() - rt.freeMemory();
        long start = System.nanoTime();
        WebMercatorGridPointSet grid = new WebMercatorGridPointSet(large);
        LinkedPointSet linkage = new LinkedPointSet(grid, network.streetLayer, StreetMode.WALK, null);
        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
        long after = rt.totalMemory() - rt.freeMemory();

        assertEquals(52_694_883, grid.featureCount());
        assertEquals(52_694_883, linkage.edges.length);
        assertEquals(52_694_883, linkage.distancesToEdge_mm.length);
        // Cells over the Porto street network are linked, cells far away in the Atlantic are not.
        int linked = 0;
        for (int edge : linkage.edges) if (edge >= 0) linked++;
        assertTrue(linked > 1000, "linked " + linked);
        assertTrue(linked < porto.nCells() * 4, "linked " + linked);
        assertEquals(-1, linkage.edges[large.nCells() - 1]);
        LOG.info("Linked {} of {} cells in {} s; heap in use grew by about {} MB (max heap {} MB).",
                linked, large.nCells(), seconds, (after - before) >> 20, rt.maxMemory() >> 20);
    }

}
