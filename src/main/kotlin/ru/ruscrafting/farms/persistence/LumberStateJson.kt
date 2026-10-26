package ru.ruscrafting.farms.persistence

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import ru.ruscrafting.farms.domain.ArcFarmsState

/** The retired runtime used the same schema-1 map name; only its exact old entries are discarded. */
internal object LumberStateJson {
    private val plain: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    val gson: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
        .registerTypeAdapter(ArcFarmsState::class.java, JsonDeserializer<ArcFarmsState> { json, _, _ ->
            val document = json.asJsonObject.deepCopy()
            val lumber = document.get("lumbermills")
            if (lumber?.isJsonObject == true) {
                val entries = lumber.asJsonObject
                require(entries.size() <= 256) { "Legacy lumber zone map is unbounded" }
                entries.entrySet().toList().forEach { (id, value) ->
                    if (value.isJsonObject) {
                        val old = value.asJsonObject
                        if (!old.has("roundId") && old.has("sequence") && old.has("engineVersion") && old.has("phase") && old.has("felled")) {
                            require(old.get("engineVersion").asInt in 1..2 && old.get("sequence").asString.toLongOrNull()?.let { it >= 0 } == true &&
                                old.get("phase").asString in LEGACY_PHASES && old.get("felled").asInt in 0..100_000) { "Malformed retired lumber state: $id" }
                            entries.remove(id)
                        } else if (old.has("roundId")) {
                            if (!old.has("boardCartLoaded")) old.addProperty("boardCartLoaded", false)
                            if (!old.has("incident")) old.add("incident", com.google.gson.JsonNull.INSTANCE)
                            if (!old.has("incidentHandledRoundId")) old.addProperty("incidentHandledRoundId", 0L)
                            if (!old.has("incidentGeneration")) old.addProperty("incidentGeneration", 0L)
                        }
                    }
                }
            }
            plain.fromJson(document, ArcFarmsState::class.java)
        }).create()

    private val LEGACY_PHASES = setOf("IDLE", "FELLING", "SKIDDING", "SAWING", "STACKING", "DISPATCH", "INCIDENT", "PROCESSING", "COOLDOWN")
}
