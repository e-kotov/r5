# Large geographic extents and grid size limits

This fork (`e-kotov/r5`, releases tagged `v*-ek.*`) raises and makes configurable two independent safety guards
that limit how large a region R5 will accept. **Raising one does not raise the other.** Passing both guards means
only that R5 will *attempt* the work: it is not evidence that a network or grid of that size fits in memory, builds in
reasonable time, or that continent-scale routing has been validated.

| Guard | Checked by | What it limits | Upstream R5 | v7.6.1-ek.1 | Default in this release | JVM system property |
|---|---|---|---|---|---|---|
| Bounding-box area | `GeometryUtils.checkWgsEnvelopeSize` | Rough area of the WGS84 envelope of OSM street layers, GTFS, freeform point sets and uploaded spatial data | 975,000 km² | 10,000,000 km² | **100,000,000 km²** | `r5.maxBoundingBoxAreaSqKm` |
| Grid cells | `WebMercatorExtents` constructor (`checkGridSize`) | `width × height` of every Web Mercator grid: whole-network linked grids, destination/origin grids, isochrone grids (r5r `isochrone()`), opportunity grids | 5,000,000 | 50,000,000 | **1,500,000,000** | `r5.maxGridCells` |

Both properties are read each time a guard is evaluated. Unset means "use the default".

## Configuration

Set the properties as JVM options when the JVM starts.

R5 backend or worker:

```sh
java -Xmx64G -Dr5.maxGridCells=2000000000 -Dr5.maxBoundingBoxAreaSqKm=50000000 -jar r5-v7.6.1-ek.2-all.jar
```

r5r (set **before** `library(r5r)` or any call that starts the JVM; an already running JVM ignores later changes):

```r
options(java.parameters = c("-Xmx64G", "-Dr5.maxGridCells=2000000000"))
options(r5r.r5jar = "/path/to/r5-v7.6.1-ek.2-all.jar")
library(r5r)
```

Lowering the limits is also supported, for example `-Dr5.maxGridCells=50000000` restores the v7.6.1-ek.1 grid limit
and `-Dr5.maxGridCells=5000000 -Dr5.maxBoundingBoxAreaSqKm=975000` restores upstream behaviour.

Accepted values, and failures:

* `r5.maxGridCells`: a plain decimal integer from 1 to 2,147,483,639. The ceiling is the largest grid that can be
  indexed with Java `int` array indexes (the JDK's soft maximum array length, `Integer.MAX_VALUE - 8`); grid cells are
  addressed and stored that way throughout R5, so larger values cannot be supported without a redesign. Signs,
  separators (`1_000`, `1,000`), exponents and suffixes are rejected.
* `r5.maxBoundingBoxAreaSqKm`: a positive decimal number (exponent allowed, e.g. `1e8`) no greater than 510,000,000
  (about the surface area of the Earth). `NaN`, `Infinity`, negative, zero, hexadecimal and suffixed values are rejected.
* An invalid value throws `IllegalArgumentException` naming the property and the accepted range at the first guarded
  operation (network build, grid creation, upload). It never silently falls back to a default.

Rejection messages state the computed size, the effective limit and the property that controls it, e.g.
`Requested number of destinations (1500050000 = width 50000 x height 30001 at zoom 9) exceeds limit (1500000000)...`.

Note: r5r's `build_network()` (as of r5r 2.4.0.9001) catches the street-layer area error and replaces it with a fixed
message, "Geographic extent of street layer exceeds limit of 975,000 km^2", whatever the effective limit is. The
configured limit is still applied; only that r5r message is wrong. Grid-size and configuration errors reach R
unchanged.

## Validation retained

These checks are unchanged and cannot be configured:

* Longitudes must be finite with |lon| ≤ 180 and latitudes finite with |lat| ≤ 80.
* Envelopes that appear to span the antimeridian (180°) are rejected.
* Web Mercator zoom must be 9–12.

Added checks: grid width and height must be non-negative and no larger than the world at the given zoom
(256 × 2^zoom pixels), and `width × height` is computed in checked `long` arithmetic before it is compared with the
limit. Previously the product was computed in `int` arithmetic, so e.g. a 65,536 × 65,536 grid (2^32 cells) wrapped to
0 and passed the check. Places that multiply raw task dimensions (`TravelTimeSurfaceTask`, `RegionalTask`,
`PathWriter`, broker `Job`) and the upload pixel check (`Grid.checkPixelCount`) now use checked arithmetic too.

## Reference extents

Bounding boxes are taken from public definitions, then converted to Web Mercator pixels with exactly the code R5 uses
for whole-network grids (`WebMercatorExtents.forBufferedWgsEnvelope`), at R5's default zoom 9 and at zoom 10, which is
the default `zoom` of r5r's `isochrone()`. The values below are
asserted in `src/test/java/com/conveyal/r5/analyst/LargeExtentLimitsTest.java`, and were cross-checked against an
independent re-implementation of the same formulas. The rough area is `GeometryUtils.roughWgsEnvelopeArea`, which is
what the area guard compares. "Linkage" is the memory of one street-mode linkage of a grid that size
(see [Memory](#memory)).

Sources:

* **EPSG**: "area of use" bounding boxes of coordinate reference systems in the EPSG Geodetic Parameter Dataset
  (IOGP), retrieved from `https://epsg.io/<code>.json` on 2026-11-01.
* **Census**: shape bounding boxes in the U.S. Census Bureau 2023 cartographic boundary file `cb_2023_us_state_500k`
  (`https://www2.census.gov/geo/tiger/GENZ2023/shp/cb_2023_us_state_500k.zip`, SHA-256
  `4a9b4f5cf993cd23738ac49b58fbb556f1f097fcf5e404a9dc10348dd41f7432`, retrieved 2026-11-01).

| Extent | Source | W, S, E, N (degrees) | Rough area (km²) | Zoom 9 W × H (pixels) | Cells at zoom 9 | Cells at zoom 10 | Linkage at zoom 9 | Area guard (default) | Grid guard at zoom 9 (default) |
|---|---|---|---|---|---|---|---|---|---|
| Sweden, recorded street-network envelope | recorded by user; bounds not in repo | — | — | 4811 × 10953 (a factorization) | 52,694,883 | — | 0.84 GB | — | pass (fails ek.1's 50M) |
| Sweden | EPSG:3006 "Sweden – onshore and offshore" | 10.03, 54.96, 24.17, 69.07 | 1,156,266 | 5150 × 11182 | 57,587,300 | 230,283,876 | 0.92 GB | pass | pass (fails ek.1's 50M) |
| Germany, recorded envelope | recorded by user | — | — | — | 15,298,287 | — | 0.24 GB | — | pass (also passed ek.1) |
| Germany | EPSG:4839 "Germany – onshore" | 5.86, 47.27, 15.04, 55.09 | 555,798 | 3343 × 4557 | 15,234,051 | 60,920,405 | 0.24 GB | pass | pass |
| Europe | EPSG:3035 "EU countries and candidates", incl. Svalbard, Iceland, Canary Is., Azores, Türkiye | −35.58, 24.6, 44.83, 84.73 | 34,536,843 | 29278 × 54978 | 1,609,645,884 | 6,438,363,624 | 25.8 GB | **rejected: latitude > 80** | rejected (> 1.5B) |
| Europe, EPSG:3035 held to 80° N | EPSG:3035, north edge set to R5's latitude limit (derived) | −35.58, 24.6, 44.83, 80.0 | 33,645,080 | 29278 × 41577 | 1,217,291,406 | 4,868,999,316 (unsupported) | 19.5 GB | pass | pass |
| Europe (ETRS89 area) | EPSG:4258, excludes Atlantic islands and Türkiye; north edge 84.73 | −16.1, 33.26, 38.0, 84.73 | 17,714,949 | 19698 × 51370 | 1,011,886,260 | 4,047,442,300 | 16.2 GB | **rejected: latitude > 80** | pass |
| Europe (ETRS89 area) held to 80° N | EPSG:4258 (derived) | −16.1, 33.26, 38.0, 80.0 | 17,177,862 | 19698 × 37969 | 747,913,362 | 2,991,577,510 (unsupported) | 12.0 GB | pass | pass |
| China | EPSG:4490 "China – onshore and offshore" | 73.62, 16.7, 134.77, 53.56 | 22,767,259 | 22265 × 17013 | 378,794,445 | 1,515,099,225 (needs config) | 6.1 GB | pass | pass |
| Contiguous US (control) | Census, 48 states + DC | −124.763068, 24.521304, −66.949895, 49.384358 | 14,186,823 | 21050 × 11525 | 242,601,250 | 970,297,752 | 3.9 GB | pass | pass |
| Contiguous US (control) | EPSG:5070 "CONUS onshore" | −124.79, 24.41, −66.91, 49.38 | 14,275,139 | 21074 × 11566 | 243,741,884 | 974,967,536 | 3.9 GB | pass | pass |
| Hawaii | Census | −178.334698, 18.910361, −154.806773, 28.402123 | 2,526,373 | 8567 × 3780 | 32,383,260 | 129,498,772 | 0.52 GB | pass | pass |
| **United States, 50 states + DC** | Census (Alaska spans 172.46° E → −129.97° W) | −179.146711, 18.910361, 179.77847, 71.387815 | 164,064,549 | — | > 2.1 billion | — | — | **rejected: antimeridian** | **not supported** |
| Alaska | Census | −179.146711, 51.215567, 179.77847, 71.387815 | — | — | — | — | — | **rejected: antimeridian** | **not supported** |
| *Not the US:* 50 states + DC without Alaska's islands east of 180° | Census (derived) | −179.146711, 18.910361, −66.949895, 71.387815 | 51,285,117 | 40851 × 30719 | 1,254,901,869 | 5,019,321,200 | 20.1 GB | pass | pass |

How the defaults were chosen:

* The initially proposed grid limit of 500 million cells admits China and the contiguous US, but **not** Europe under
  either EPSG definition (748 million and 1.22 billion cells at zoom 9). The default was raised to 1.5 billion so that
  the larger EPSG:3035 Europe extent (held to 80° N) fits with about 23% headroom, still below the 2,147,483,639 hard
  ceiling. At zoom 10 Europe needs 3.0–4.9 billion cells, which no configuration can admit, and China needs
  1,515,099,225, just over the default (admit it with e.g. `-Dr5.maxGridCells=1600000000`).
* The proposed area limit of 100 million km² admits every reference extent above (the largest is 34.5 million km²),
  and still rejects near-global envelopes (e.g. −170…170° × −80…80° is about 672 million km²), which usually indicate
  stray or misprojected points.
* The rough area is a crude, bounding-box-based estimate; the area of the region itself can be much smaller.

### Europe and latitude 80° N

R5 rejects latitudes beyond 80° (Longyearbyen, the northernmost permanent settlement, is at 78° N). Both EPSG
"Europe" areas of use extend to 84.73° N to cover offshore Svalbard, so they are rejected by that retained check.
A real street network for Europe is checked against the envelope of its street vertices, not an administrative area,
so whether a given OSM extract passes depends on the data (Svalbard's roads are at about 78° N). This was not tested
with a real Europe extract.

### The whole United States and the antimeridian

The full United States including Alaska and Hawaii is **not supported as a single network or grid** by this release.
Alaska's Aleutian Islands cross the 180° meridian (Attu Island, 172.46° E). R5 envelopes are plain min/max longitude
ranges and cannot wrap around the antimeridian, so the naive envelope spans almost the whole globe; it is rejected by
the retained antimeridian check (independently of any configured limit) and would in any case exceed the largest
indexable grid. This is a separate geographic problem (antimeridian-aware envelopes and grids), not a size-limit
problem, and the antimeridian check has deliberately not been disabled. The contiguous US is a control, not a
substitute. Possible approaches, none implemented here: separate networks for the contiguous US, Hawaii and Alaska,
or an OSM extract of Alaska clipped to the western hemisphere (which would not be all of Alaska).

## Memory

Admitting a grid does not mean it fits in RAM. Estimates per grid cell, from the arrays the code actually allocates:

| Structure | Allocation | Bytes per cell | When |
|---|---|---|---|
| `WebMercatorExtents`, `WebMercatorGridPointSet` | 5 ints; coordinates computed on demand | 0 | always |
| `LinkedPointSet` (street linkage) | 4 × `int[nCells]` (`edges`, `distancesToEdge_mm`, `distances0_mm`, `distances1_mm`) | 16 per street mode | every linked grid; Conveyal network builds pre-link a whole-network grid for WALK and any `buildGridsForModes` and serialize it in `network.dat`. r5r network builds do **not** pre-link a grid; r5r `isochrone()` creates and links a grid covering the whole network envelope at its `zoom` (default 10). |
| Street search times to points (`PointSetTimes`, `LinkedPointSet.eval`) | `int[nCells]` | 4 per search result held | per origin and access/egress street mode |
| `TravelTimeResult` | `int[nPercentiles][nCells]` | 4 × percentiles | per origin, for grid destinations |
| Transposed egress cost table | `TIntIntMap[nCells]` | 4 (8 when the heap is ≥ 32 GB and compressed oops are disabled), plus a hash map per cell near transit | transit propagation to grid destinations |
| `Grid` (opportunity data) | `double[width][height]` | 8 per layer | opportunity grids; uploads are additionally limited to 10⁹ pixels × layers by `Grid.checkPixelCount` (unchanged) |

Further limits and unknowns:

* Single-point travel time surfaces written by `TimeGridWriter` are limited to 2³¹−1 bytes (4 bytes × percentiles ×
  cells), i.e. at most about 536 million cells for one percentile. This existing check fails with a clear error.
* Regional result buffers (`GridResultWriter`) are files of 4 bytes × cells × thresholds per origin, on disk.
* The serialized size of linkages in `network.dat` (Kryo) and the time to link every cell (each cell performs a
  spatial-index lookup, even far from any street) have not been measured at continental scale. In the opt-in smoke
  test below, a 52.7 million cell grid around the small Porto test network linked in 51 s on 4 cores with a 4 GB heap
  (its four linkage arrays alone are 843 MB).
* JVM heap overhead, GC headroom and street/transit network size come on top of the per-cell figures.

## Tests

* `SizeLimitsTest`: defaults, valid and malformed property values, checked cell counts and overflow.
* `WebMercatorExtentsLimitsTest`: edges of the upstream, ek.1, default and absolute limits; int-overflow products;
  negative and larger-than-world dimensions; malformed configuration; guard independence; raw task dimensions.
* `LargeExtentLimitsTest`: every reference extent above, without allocating any grid.
* `SmallExtentRegressionTest`: exact pixel bounds, areas and grid coordinates for four small regions at zooms 9–12,
  identical to the v7.6.1-ek.1 code.
* `LargeGridSmokeTest` (opt-in, bounded heap and 20 minute timeout): links a real 4811 × 10953 = 52,694,883 cell grid
  to the Porto test network:

  ```sh
  gradle test --tests '*LargeGridSmokeTest' -Pr5LargeGridSmoke -Pr5LargeGridSmokeHeap=4g
  ```
