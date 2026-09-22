package ru.ruscrafting.farms.paper.worksite

import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import kotlin.math.abs

/** Offline cuboid-face audit, using the same transformed geometry as BlockDisplay rendering.
 * Opposite-facing contact surfaces and edge-only contacts are not z-fighting.
 * This deliberately reports buried coplanar faces too: transparency or a later animation
 * can expose them. It does not claim to validate non-cuboid Minecraft block models.
 */
internal object WorksiteDisplayGeometryValidator {
    const val PLANE_TOLERANCE = .001
    private const val AREA_TOLERANCE = .000001
    data class Box(val id: String, val center: Vector3f, val size: Vector3f, val rotation: Quaternionf = Quaternionf())
    data class Conflict(val first: String, val firstFace: String, val second: String, val secondFace: String,
        val area: Double, val separation: Double)
    private data class Point(val x: Double, val y: Double) {
        operator fun minus(other: Point) = Point(x - other.x, y - other.y)
        fun cross(other: Point) = x * other.y - y * other.x
    }
    private data class Face(val id: String, val name: String, val normal: Vector3d, val origin: Vector3d,
        val u: Vector3d, val v: Vector3d, val corners: List<Vector3d>)
    private data class Bounds(val min: Vector3d, val max: Vector3d) {
        fun separated(other: Bounds): Boolean = (0..2).any { axis ->
            max.get(axis) + PLANE_TOLERANCE < other.min.get(axis) ||
                other.max.get(axis) + PLANE_TOLERANCE < min.get(axis)
        }
    }

    fun conflicts(boxes: List<Box>): List<Conflict> {
        require(boxes.map { it.id }.distinct().size == boxes.size) { "Duplicate display part IDs" }
        val faces = boxes.map { box ->
            require(box.center.isFinite && box.size.isFinite && (0..2).all { box.size.get(it) > 0 }) {
                "Invalid display cuboid ${box.id}"
            }
            require(box.rotation.isFinite && abs(box.rotation.lengthSquared() - 1f) < .0001f) {
                "Invalid display rotation ${box.id}"
            }
            faces(box)
        }
        // Large mechanical models contain hundreds of small, distant parts.
        // Disjoint swept face bounds cannot overlap; retain the plane tolerance
        // here so this broad phase never drops near-coplanar narrow-phase hits.
        val bounds = faces.map { boxFaces ->
            val min = Vector3d(Double.POSITIVE_INFINITY)
            val max = Vector3d(Double.NEGATIVE_INFINITY)
            boxFaces.forEach { face -> face.corners.forEach { min.min(it); max.max(it) } }
            Bounds(min, max)
        }
        return buildList {
            for (i in faces.indices) for (j in i + 1 until faces.size) {
                if (bounds[i].separated(bounds[j])) continue
                for (a in faces[i]) for (b in faces[j]) {
                    if (a.normal.dot(b.normal) < .99999999) continue
                    val distance = abs(Vector3d(b.origin).sub(a.origin).dot(a.normal))
                    if (distance > PLANE_TOLERANCE) continue
                    val overlap = clip(project(a.corners, a), project(b.corners, a))
                    val area = abs(signedArea(overlap))
                    if (area > AREA_TOLERANCE) add(Conflict(a.id, a.name, b.id, b.name, area, distance))
                }
            }
        }
    }

    private fun faces(box: Box): List<Face> = buildList {
        for (axis in 0..2) for (sign in listOf(-1, 1)) {
            val localNormal = Vector3d().setComponent(axis, sign.toDouble())
            val localU = Vector3d().setComponent((axis + 1) % 3, 1.0)
            val localV = Vector3d(localNormal).cross(localU)
            val normal = box.rotation.transform(localNormal).normalize()
            val u = box.rotation.transform(Vector3d(localU)).normalize()
            val v = box.rotation.transform(Vector3d(localV)).normalize()
            val origin = Vector3d(box.center).add(Vector3d(normal).mul(box.size.get(axis) / 2.0))
            val halfU = box.size.get((axis + 1) % 3) / 2.0
            val halfV = box.size.get((axis + 2) % 3) / 2.0
            val corners = listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1).map { (x, y) ->
                Vector3d(origin).add(Vector3d(u).mul(x * halfU)).add(Vector3d(v).mul(y * halfV))
            }
            add(Face(box.id, "${"xyz"[axis]}${if (sign > 0) "+" else "-"}", normal, origin, u, v, corners))
        }
    }

    private fun project(corners: List<Vector3d>, face: Face): List<Point> = corners.map {
        val offset = Vector3d(it).sub(face.origin)
        Point(offset.dot(face.u), offset.dot(face.v))
    }.let { if (signedArea(it) < 0) it.reversed() else it }

    /** Sutherland-Hodgman clipping keeps actual rotated face overlap, not AABB overlap. */
    private fun clip(subject: List<Point>, boundary: List<Point>): List<Point> {
        var output = subject
        for (i in boundary.indices) {
            if (output.isEmpty()) break
            val start = boundary[i]
            val edge = boundary[(i + 1) % boundary.size] - start
            val input = output
            output = buildList {
                var previous = input.last()
                var previousDistance = edge.cross(previous - start)
                for (current in input) {
                    val distance = edge.cross(current - start)
                    if ((distance >= 0) != (previousDistance >= 0)) {
                        val t = previousDistance / (previousDistance - distance)
                        add(Point(previous.x + (current.x - previous.x) * t, previous.y + (current.y - previous.y) * t))
                    }
                    if (distance >= 0) add(current)
                    previous = current
                    previousDistance = distance
                }
            }
        }
        return output
    }

    private fun signedArea(points: List<Point>): Double = points.indices.sumOf { i ->
        points[i].cross(points[(i + 1) % points.size])
    } / 2
}
