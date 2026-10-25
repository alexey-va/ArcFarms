package ru.ruscrafting.farms.paper.lumber.sawmill

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.joml.Quaternionf
import org.joml.Vector3f

/** One authored scene. Block poses are cuboid centers; item poses are model origins. */
internal object CedarSawmillGeometry {
    // Scale the complete authored machine about its floor anchor, including all controls.
    const val SCENE_SCALE = 1.7f
    private val document: JsonObject = checkNotNull(javaClass.getResourceAsStream("/sawmill/cedar-machine.json"))
        .bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
    val origin: Vector3f = document.getAsJsonArray("origin").vector()
    val sceneRotation = Quaternionf().rotationY(Math.toRadians(document["sceneYawDegrees"]?.asDouble ?: 0.0).toFloat())
    val sceneYaw: Float = (document["sceneYawDegrees"]?.asFloat ?: 0f)
    val interactionCenter: Vector3f = document.getAsJsonArray("leverCenter").vector().mul(SCENE_SCALE).rotate(sceneRotation)
    val interactionSize: Vector3f = document.getAsJsonArray("leverSize").vector().mul(SCENE_SCALE)
    const val BODY_BLOCK_LIGHT = 12
    const val BODY_SKY_LIGHT = 12
    const val VIEW_RANGE_BLOCKS = 48.0
    const val ITEM_CONTEXT = "NONE"

    data class Part(
        val id: String,
        val blockData: String?,
        val itemModel: String?,
        val center: Vector3f,
        val size: Vector3f,
        val rotation: Quaternionf,
        val moving: Boolean,
        val control: Boolean,
        internal val motion: String,
        internal val pivot: Vector3f,
        internal val ratio: Float,
        internal val section: Int,
    )

    val parts: List<Part> = document.getAsJsonArray("parts").map { element ->
        val part = element.asJsonObject
        val angles = part.getAsJsonArray("rotation")?.vector() ?: Vector3f()
        Part(
            id = part["id"].asString,
            blockData = part["block"]?.asString,
            itemModel = part["itemModel"]?.asString,
            center = part.getAsJsonArray("center").vector(),
            size = part.getAsJsonArray("size").vector(),
            rotation = Quaternionf().rotationXYZ(angles.x, angles.y, angles.z),
            moving = part["motion"].asString != "fixed",
            control = part["control"]?.asBoolean ?: false,
            motion = part["motion"].asString,
            pivot = part.getAsJsonArray("pivot")?.vector() ?: Vector3f(),
            ratio = part["ratio"]?.asFloat ?: 1f,
            section = part["section"]?.asInt ?: 0,
        ).also {
            require((it.blockData == null) != (it.itemModel == null)) { "Exactly one content type: ${it.id}" }
            require(it.center.isFinite && it.size.isFinite && it.size.x > 0f && it.size.y > 0f && it.size.z > 0f)
        }
    }.also { list ->
        require(list.size <= 256 && list.map(Part::id).distinct().size == list.size)
    }

    /** Bounds after native Y+180; emitted from exactly the packed item-model elements. */
    val slabContactVertices: List<Vector3f> = document.getAsJsonObject("nativeModels")
        .getAsJsonObject("slab").getAsJsonArray("boxes").flatMap { element ->
            val box = element.asJsonObject
            val low = box.getAsJsonArray("min").vector()
            val high = box.getAsJsonArray("max").vector()
            listOf(Vector3f(low.x, low.y, 0f), Vector3f(low.x, high.y, 0f),
                Vector3f(high.x, low.y, 0f), Vector3f(high.x, high.y, 0f))
        }

    internal fun sourceDocument(): JsonObject = document.deepCopy().also { export ->
        export.addProperty("sceneScale", SCENE_SCALE)
        for ((key, vector) in listOf("leverCenter" to interactionCenter, "leverSize" to interactionSize)) export.add(key, JsonArray().apply {
            add(vector.x); add(vector.y); add(vector.z)
        })
    }
    private fun JsonArray.vector() = Vector3f(this[0].asFloat, this[1].asFloat, this[2].asFloat)
}
