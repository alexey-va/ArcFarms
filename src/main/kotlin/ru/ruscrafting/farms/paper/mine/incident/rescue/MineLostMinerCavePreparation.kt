package ru.ruscrafting.farms.paper.mine.incident.rescue

import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import java.util.logging.Logger
import java.util.logging.Level

/** One immutable terrain plan per zone, calculated off the gameplay thread before block capture. */
internal class MineLostMinerCavePreparation(private val tasks: WorksiteTaskPort, private val logger: Logger) {
    data class Plan(val seed: Long,val layout: MineLostMinerMazeLayout,val blocks: Map<Triple<Int,Int,Int>,String>)
    private class Request(val key: String,val seed: Long,var plan: Plan? = null)
    private val requests=mutableMapOf<String,Request>()

    fun prepare(zone: String,key: String,seed: Long): Plan? {
        requests[zone]?.takeIf { it.key==key && it.seed==seed }?.let { return it.plan }
        val request=Request(key,seed)
        requests[zone]=request
        val token=tasks.lifecycleToken()
        if(!tasks.runAsync(token) {
            val result=runCatching {
                val layout=MineLostMinerMazePlanner.plan(MineLostMinerMazeWorld.MAZE_CELLS,seed)
                Plan(seed,layout,MineLostMinerCaveBlocks.plan(layout,seed))
            }
            tasks.runSync(token) {
                if(requests[zone] !== request) return@runSync
                result.fold(onSuccess={ request.plan=it },onFailure={
                    requests.remove(zone)
                    logger.log(Level.WARNING,"Mine rescue terrain preparation failed zone=$zone",it)
                })
            }
        }) requests.remove(zone,request)
        return request.plan
    }

    fun clear() = requests.clear()
}
