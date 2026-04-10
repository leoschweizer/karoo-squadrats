package sr.leo.karoo_squadrats.map

import sr.leo.karoo_squadrats.grid.SquadratGrid
import sr.leo.karoo_squadrats.grid.SquadratGrid.TileCoord

/**
 * Extracts contour polylines around groups of uncollected tiles.
 *
 * Instead of drawing each uncollected tile as a full square (which doubles
 * shared edges and makes collected holes invisible), this traces the boundary
 * between uncollected tiles and their collected/absent neighbors.
 */
object ContourExtractor {

    data class Contour(val points: List<Pair<Double, Double>>) // lat, lon pairs

    data class GridLines(
        /** Boundary contours around uncollected regions (and holes). */
        val contours: List<Contour>,
        /** Internal grid lines between adjacent uncollected tiles (each drawn once). */
        val innerEdges: List<Contour>,
    )

    internal data class GridPoint(val x: Int, val y: Int)

    /**
     * Extract boundary contours and internal grid lines from a set of uncollected tile keys.
     */
    fun extract(uncollectedTiles: Set<Long>): GridLines {
        if (uncollectedTiles.isEmpty()) return GridLines(emptyList(), emptyList())

        val boundaryEdges = extractBoundaryEdges(uncollectedTiles)
        val contours = if (boundaryEdges.isNotEmpty()) chainEdges(boundaryEdges) else emptyList()
        val innerEdges = extractInnerEdges(uncollectedTiles)

        return GridLines(contours, innerEdges)
    }

    internal fun extractBoundaryEdges(
        uncollectedTiles: Set<Long>,
    ): List<Pair<GridPoint, GridPoint>> {
        val edges = mutableListOf<Pair<GridPoint, GridPoint>>()

        for (key in uncollectedTiles) {
            val tile = TileCoord.fromKey(key)
            val x = tile.x
            val y = tile.y

            // Top: draw if neighbor above is not uncollected
            if (!uncollectedTiles.contains(TileCoord(x, y - 1).toKey())) {
                edges.add(GridPoint(x, y) to GridPoint(x + 1, y))
            }
            // Bottom: draw if neighbor below is not uncollected
            if (!uncollectedTiles.contains(TileCoord(x, y + 1).toKey())) {
                edges.add(GridPoint(x, y + 1) to GridPoint(x + 1, y + 1))
            }
            // Left: draw if neighbor left is not uncollected
            if (!uncollectedTiles.contains(TileCoord(x - 1, y).toKey())) {
                edges.add(GridPoint(x, y) to GridPoint(x, y + 1))
            }
            // Right: draw if neighbor right is not uncollected
            if (!uncollectedTiles.contains(TileCoord(x + 1, y).toKey())) {
                edges.add(GridPoint(x + 1, y) to GridPoint(x + 1, y + 1))
            }
        }

        return edges
    }

    /**
     * Extract internal grid edges - shared edges between two adjacent uncollected tiles.
     * Each shared edge is emitted only once (from the tile with the smaller coordinate).
     */
    internal fun extractInnerEdges(
        uncollectedTiles: Set<Long>,
    ): List<Contour> {
        val edges = mutableListOf<Contour>()

        for (key in uncollectedTiles) {
            val tile = TileCoord.fromKey(key)
            val x = tile.x
            val y = tile.y

            // Shared horizontal edge with tile below (only emit from upper tile)
            if (uncollectedTiles.contains(TileCoord(x, y + 1).toKey())) {
                val (lon1, lat1) = SquadratGrid.tileCornerLonLat(x, y + 1)
                val (lon2, lat2) = SquadratGrid.tileCornerLonLat(x + 1, y + 1)
                edges.add(Contour(listOf(lat1 to lon1, lat2 to lon2)))
            }
            // Shared vertical edge with tile to the right (only emit from left tile)
            if (uncollectedTiles.contains(TileCoord(x + 1, y).toKey())) {
                val (lon1, lat1) = SquadratGrid.tileCornerLonLat(x + 1, y)
                val (lon2, lat2) = SquadratGrid.tileCornerLonLat(x + 1, y + 1)
                edges.add(Contour(listOf(lat1 to lon1, lat2 to lon2)))
            }
        }

        return edges
    }

    internal fun chainEdges(edges: List<Pair<GridPoint, GridPoint>>): List<Contour> {
        // Build adjacency graph
        val adjacency = mutableMapOf<GridPoint, MutableList<GridPoint>>()

        for ((a, b) in edges) {
            adjacency.getOrPut(a) { mutableListOf() }.add(b)
            adjacency.getOrPut(b) { mutableListOf() }.add(a)
        }

        val contours = mutableListOf<Contour>()

        while (adjacency.isNotEmpty()) {
            val start = adjacency.keys.first()
            val chain = mutableListOf(start)
            var current = start
            var next = adjacency[current]!!.first()

            while (true) {
                adjacency[current]!!.remove(next)
                adjacency[next]?.remove(current)
                if (adjacency[current]!!.isEmpty()) adjacency.remove(current)
                if (adjacency[next]?.isEmpty() == true) adjacency.remove(next)

                chain.add(next)
                current = next

                if (current == start) break // closed loop
                val neighbors = adjacency[current]
                if (neighbors.isNullOrEmpty()) break // dead end

                next = neighbors.first()
            }

            // Convert grid points to lat/lon (PolylineEncoder expects lat, lon)
            val points = chain.map { gp ->
                val (lon, lat) = SquadratGrid.tileCornerLonLat(gp.x, gp.y)
                lat to lon
            }

            contours.add(Contour(points))
        }

        return contours
    }
}
