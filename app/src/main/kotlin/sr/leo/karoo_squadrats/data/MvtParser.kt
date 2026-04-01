@file:OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)

package sr.leo.karoo_squadrats.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoPacked
import kotlin.math.atan
import kotlin.math.pow
import kotlin.math.sinh

/**
 * Minimal MVT (Mapbox Vector Tile) parser.
 *
 * Parses gzipped MVT tiles and extracts polygon geometries from a named layer.
 * Coordinates are returned as lon/lat pairs derived from the tile position.
 *
 * In MVT, polygons are encoded as a sequence of rings (closed coordinate loops):
 * - Exterior rings (clockwise winding) define the outer boundary of a collected region.
 * - Interior rings / holes (counter-clockwise winding) represent uncollected tiles
 *   cut out from the exterior ring.
 * A single feature can contain multiple exterior rings (multi-polygon), each with
 * its own holes. Winding order distinguishes exterior rings from holes.
 */
object MvtParser {

    // -- MVT protobuf schema (only the fields we need) --
    // See: https://github.com/mapbox/vector-tile-spec/blob/master/2.1/vector_tile.proto

    @Serializable
    private data class MvtTile(
        @ProtoNumber(3) val layers: List<MvtLayer> = emptyList(),
    )

    @Serializable
    private data class MvtLayer(
        @ProtoNumber(1) val name: String = "",
        @ProtoNumber(2) val features: List<MvtFeature> = emptyList(),
        @ProtoNumber(5) val extent: Int = 4096,
    )

    @Serializable
    private data class MvtFeature(
        @ProtoNumber(4) @ProtoPacked val geometry: List<Int> = emptyList(),
    )

    data class Ring(val points: List<Pair<Double, Double>>) // lon, lat pairs

    /**
     * Parse an MVT tile (possibly gzipped) and extract polygon rings from the given layer.
     * Returns a list of rings, where each ring is a list of (lon, lat) pairs.
     */
    fun extractPolygons(
        rawData: ByteArray,
        layerName: String,
        tileZ: Int,
        tileX: Int,
        tileY: Int,
    ): List<Ring> {
        if (rawData.isEmpty()) return emptyList()
        val tile = decodeTile(rawData)
        val rings = mutableListOf<Ring>()
        for (layer in tile.layers) {
            if (layer.name != layerName) continue
            for (feature in layer.features) {
                rings.addAll(decodePolygonGeometry(feature.geometry, tileZ, tileX, tileY, layer.extent))
            }
        }
        return rings
    }

    /**
     * Returns true if the tile data contains any features in the given layer.
     */
    fun hasFeatures(rawData: ByteArray, layerName: String = "squadrats"): Boolean {
        if (rawData.isEmpty()) return false
        val tile = decodeTile(rawData)
        return tile.layers.any { it.name == layerName && it.features.isNotEmpty() }
    }

    private fun decodeTile(rawData: ByteArray): MvtTile {
        return ProtoBuf.decodeFromByteArray(MvtTile.serializer(), rawData)
    }

    /**
     * Decode MVT geometry commands (MoveTo, LineTo, ClosePath) into coordinate rings.
     * See: https://github.com/mapbox/vector-tile-spec/tree/master/2.1#43-geometry-encoding
     */
    private fun decodePolygonGeometry(
        geometry: List<Int>,
        tileZ: Int,
        tileX: Int,
        tileY: Int,
        extent: Int,
    ): List<Ring> {
        val rings = mutableListOf<Ring>()
        var ring = mutableListOf<Pair<Double, Double>>()
        var cx = 0
        var cy = 0
        var idx = 0

        while (idx < geometry.size) {
            val cmdInt = geometry[idx]; idx++
            val cmdId = cmdInt and 0x7
            val count = cmdInt ushr 3

            when (cmdId) {
                1 -> { // MoveTo
                    repeat(count) {
                        if (idx + 1 >= geometry.size) return@repeat
                        cx += zigzagDecode(geometry[idx]); idx++
                        cy += zigzagDecode(geometry[idx]); idx++
                        if (ring.isNotEmpty()) {
                            rings.add(Ring(ring.toList()))
                            ring = mutableListOf()
                        }
                        ring.add(tilePixelToLonLat(tileX, tileY, tileZ, cx.toDouble(), cy.toDouble(), extent))
                    }
                }
                2 -> { // LineTo
                    repeat(count) {
                        if (idx + 1 >= geometry.size) return@repeat
                        cx += zigzagDecode(geometry[idx]); idx++
                        cy += zigzagDecode(geometry[idx]); idx++
                        ring.add(tilePixelToLonLat(tileX, tileY, tileZ, cx.toDouble(), cy.toDouble(), extent))
                    }
                }
                7 -> { // ClosePath
                    if (ring.isNotEmpty()) {
                        ring.add(ring[0]) // close the ring
                        rings.add(Ring(ring.toList()))
                        ring = mutableListOf()
                    }
                }
            }
        }
        if (ring.isNotEmpty()) {
            rings.add(Ring(ring.toList()))
        }
        return rings
    }

    private fun zigzagDecode(v: Int): Int = (v ushr 1) xor -(v and 1)

    private fun tilePixelToLonLat(
        tileX: Int,
        tileY: Int,
        tileZ: Int,
        px: Double,
        py: Double,
        extent: Int,
    ): Pair<Double, Double> {
        val n = 2.0.pow(tileZ)
        val lon = (tileX + px / extent) / n * 360.0 - 180.0
        val latRad = atan(sinh(Math.PI * (1.0 - 2.0 * (tileY + py / extent) / n)))
        val lat = Math.toDegrees(latRad)
        return lon to lat
    }
}
