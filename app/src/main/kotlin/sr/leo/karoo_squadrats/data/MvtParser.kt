package sr.leo.karoo_squadrats.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import kotlin.math.atan
import kotlin.math.pow
import kotlin.math.sinh

/**
 * Minimal MVT (Mapbox Vector Tile) protobuf parser.
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

        val data = decompress(rawData)

        val rings = mutableListOf<Ring>()
        var i = 0
        while (i < data.size) {
            val (tag, newI) = readVarint(data, i)
            if (tag == null) break
            i = newI
            val fn = (tag shr 3).toInt()
            val wt = (tag and 0x7).toInt()
            if (wt == 2) {
                val (length, newI2) = readVarint(data, i)
                if (length == null) break
                i = newI2
                if (fn == 3) { // Layer
                    val layerData = data.copyOfRange(i, i + length.toInt())
                    val (name, extent, features) = parseLayer(layerData)
                    if (name == layerName) {
                        for (geomBytes in features) {
                            val decoded = decodePolygonGeometry(geomBytes, tileZ, tileX, tileY, extent)
                            rings.addAll(decoded)
                        }
                    }
                }
                i += length.toInt()
            } else {
                i = skipField(data, i, wt) ?: break
            }
        }
        return rings
    }

    /**
     * Returns true if the tile data contains any features in the given layer.
     */
    fun hasFeatures(rawData: ByteArray, layerName: String = "squadrats"): Boolean {
        if (rawData.isEmpty()) return false
        val data = decompress(rawData)

        var i = 0
        while (i < data.size) {
            val (tag, newI) = readVarint(data, i)
            if (tag == null) break
            i = newI
            val fn = (tag shr 3).toInt()
            val wt = (tag and 0x7).toInt()
            if (wt == 2) {
                val (length, newI2) = readVarint(data, i)
                if (length == null) break
                i = newI2
                if (fn == 3) {
                    val layerData = data.copyOfRange(i, i + length.toInt())
                    val (name, _, features) = parseLayer(layerData)
                    if (name == layerName && features.isNotEmpty()) return true
                }
                i += length.toInt()
            } else {
                i = skipField(data, i, wt) ?: break
            }
        }
        return false
    }

    private data class LayerInfo(val name: String, val extent: Int, val featureGeometries: List<ByteArray>)

    private fun parseLayer(data: ByteArray): LayerInfo {
        var i = 0
        var name = ""
        var extent = 4096 // MVT default tile extent
        val geoms = mutableListOf<ByteArray>()

        while (i < data.size) {
            val (tag, newI) = readVarint(data, i)
            if (tag == null) break
            i = newI
            val fn = (tag shr 3).toInt()
            val wt = (tag and 0x7).toInt()
            if (wt == 2) {
                val (length, newI2) = readVarint(data, i)
                if (length == null) break
                i = newI2
                when (fn) {
                    1 -> name = String(data, i, length.toInt(), Charsets.UTF_8) // name
                    2 -> { // feature
                        val geomBytes = extractGeometryFromFeature(data.copyOfRange(i, i + length.toInt()))
                        if (geomBytes != null) geoms.add(geomBytes)
                    }
                }
                i += length.toInt()
            } else if (wt == 0) {
                val (value, newI2) = readVarint(data, i)
                if (value == null) break
                i = newI2
                if (fn == 5) extent = value.toInt() // extent
            } else {
                i = skipField(data, i, wt) ?: break
            }
        }
        return LayerInfo(name, extent, geoms)
    }

    private fun extractGeometryFromFeature(data: ByteArray): ByteArray? {
        var i = 0
        while (i < data.size) {
            val (tag, newI) = readVarint(data, i)
            if (tag == null) break
            i = newI
            val fn = (tag shr 3).toInt()
            val wt = (tag and 0x7).toInt()
            if (wt == 2) {
                val (length, newI2) = readVarint(data, i)
                if (length == null) break
                i = newI2
                if (fn == 4) { // geometry
                    return data.copyOfRange(i, i + length.toInt())
                }
                i += length.toInt()
            } else if (wt == 0) {
                val (_, newI2) = readVarint(data, i)
                if (newI2 == i) break
                i = newI2
            } else {
                i = skipField(data, i, wt) ?: break
            }
        }
        return null
    }

    private fun decodePolygonGeometry(
        geomBytes: ByteArray,
        tileZ: Int,
        tileX: Int,
        tileY: Int,
        extent: Int,
    ): List<Ring> {
        // Read all varints
        val vals = mutableListOf<Long>()
        var pos = 0
        while (pos < geomBytes.size) {
            val (v, newPos) = readVarint(geomBytes, pos)
            if (v == null) break
            vals.add(v)
            pos = newPos
        }

        val rings = mutableListOf<Ring>()
        var ring = mutableListOf<Pair<Double, Double>>()
        var cx = 0L
        var cy = 0L
        var idx = 0

        while (idx < vals.size) {
            val cmdInt = vals[idx]; idx++
            val cmdId = (cmdInt and 0x7).toInt()
            val count = (cmdInt shr 3).toInt()

            when (cmdId) {
                1 -> { // MoveTo
                    for (j in 0 until count) {
                        if (idx + 1 >= vals.size) break
                        val dx = zigzagDecode(vals[idx]); idx++
                        val dy = zigzagDecode(vals[idx]); idx++
                        cx += dx
                        cy += dy
                        if (ring.isNotEmpty()) {
                            rings.add(Ring(ring.toList()))
                            ring = mutableListOf()
                        }
                        ring.add(tilePixelToLonLat(tileX, tileY, tileZ, cx.toDouble(), cy.toDouble(), extent))
                    }
                }
                2 -> { // LineTo
                    for (j in 0 until count) {
                        if (idx + 1 >= vals.size) break
                        val dx = zigzagDecode(vals[idx]); idx++
                        val dy = zigzagDecode(vals[idx]); idx++
                        cx += dx
                        cy += dy
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

    private fun zigzagDecode(v: Long): Long = (v shr 1) xor -(v and 1)

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

    private fun decompress(data: ByteArray): ByteArray {
        // Gzip magic bytes: 0x1F 0x8B
        if (data.size < 2 || data[0] != 0x1F.toByte() || data[1] != 0x8B.toByte()) {
            return data // not gzipped
        }
        val bais = ByteArrayInputStream(data)
        val gis = GZIPInputStream(bais)
        val baos = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var len: Int
        while (gis.read(buffer).also { len = it } != -1) {
            baos.write(buffer, 0, len)
        }
        return baos.toByteArray()
    }

    private fun readVarint(data: ByteArray, startPos: Int): Pair<Long?, Int> {
        var result = 0L
        var shift = 0
        var pos = startPos
        while (pos < data.size) {
            val b = data[pos].toInt() and 0xFF
            pos++
            result = result or ((b.toLong() and 0x7F) shl shift)
            if (b and 0x80 == 0) return result to pos
            shift += 7
            if (shift >= 64) return null to pos
        }
        return null to pos
    }

    private fun skipField(data: ByteArray, pos: Int, wireType: Int): Int? {
        return when (wireType) {
            0 -> readVarint(data, pos).second
            1 -> if (pos + 8 <= data.size) pos + 8 else null
            2 -> {
                val (len, newPos) = readVarint(data, pos)
                if (len == null) null else newPos + len.toInt()
            }
            5 -> if (pos + 4 <= data.size) pos + 4 else null
            else -> null
        }
    }
}
