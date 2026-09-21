package ru.ruscrafting.farms.paper.mine.incident.rescue

import ru.ruscrafting.farms.domain.worksite.WorksiteCoherentNoise
import kotlin.math.abs

/** Canonical cave shell: uneven vaulted rock and occasional authored-looking timber frames. */
internal object MineLostMinerCaveBlocks {
    const val GEOMETRY_VERSION = 2
    fun plan(layout: MineLostMinerMazeLayout, seed: Long): Map<Triple<Int,Int,Int>,String> {
        val chambers = MineLostMinerMazePlanner.chamberCells(layout,seed)
        val route = MineLostMinerMazePlanner.path(layout)
        val blocks = linkedMapOf<Triple<Int,Int,Int>,String>()
        val ceilings = mutableMapOf<MineLostMinerMazePoint,Int>()
        for(x in 0 until layout.width) for(z in 0 until layout.height) {
            val p=MineLostMinerMazePoint(x,z)
            val edge=listOf(p.copy(x=x-1),p.copy(x=x+1),p.copy(z=z-1),p.copy(z=z+1)).count { it !in chambers }
            val vault=WorksiteCoherentNoise.sample(seed,x*.16,3.7,z*.16)
            val ceiling=(5+(vault*3.0).toInt()-edge).coerceIn(3,7)
            ceilings[p]=ceiling
            for(y in 0..8) {
                val n=WorksiteCoherentNoise.sample(seed,x*.27,y*.32,z*.27)
                val open=p in chambers && y in 1..ceiling && (y<=3 || edge==0 || n>-.25)
                blocks[Triple(x,y,z)]=if(open) "minecraft:air" else when {
                    n>.30 -> "minecraft:tuff"
                    n>.08 -> "minecraft:andesite"
                    n<-.36 -> "minecraft:calcite"
                    else -> "minecraft:deepslate"
                }
            }
        }
        // Frames span a complete narrow section; no arbitrary timber speckles along the walls.
        val frames=linkedSetOf<MineLostMinerMazePoint>()
        route.forEachIndexed { i,p ->
            if(i<10 || i>route.size-10 || frames.any { abs(it.x-p.x)+abs(it.z-p.z)<14 }) return@forEachIndexed
            val before=route[(i-2).coerceAtLeast(0)]; val after=route[(i+2).coerceAtMost(route.lastIndex)]
            val acrossX=abs(after.z-before.z)>=abs(after.x-before.x)
            fun at(offset:Int)=if(acrossX) p.copy(x=p.x+offset) else p.copy(z=p.z+offset)
            var left=-1;while(left>=-5 && at(left) in chambers) left--
            var right=1;while(right<=5 && at(right) in chambers) right++
            if(right-left>8 || left < -5 || right>5) return@forEachIndexed
            val top=(left+1 until right).minOf { ceilings.getValue(at(it)) }.coerceAtLeast(3)+1
            for(offset in left..right) {
                val q=at(offset)
                blocks[Triple(q.x,top,q.z)]="minecraft:stripped_spruce_log[axis=${if(acrossX) "x" else "z"}]"
                if(offset==left || offset==right) for(y in 1 until top)
                    blocks[Triple(q.x,y,q.z)]="minecraft:stripped_spruce_log[axis=y]"
            }
            frames+=p
        }
        MineLostMinerMazePlanner.lampCells(layout).forEach { p ->
            val top=(3..7).firstOrNull { blocks[Triple(p.x,it,p.z)]!="minecraft:air" } ?: 8
            blocks[Triple(p.x,top-1,p.z)]="minecraft:lantern[hanging=true,waterlogged=false]"
        }
        // Dim ambient fill keeps the bends readable while visible lanterns stay rare.
        val ambient=linkedSetOf<MineLostMinerMazePoint>()
        route.forEach { p ->
            if(ambient.none { (it.x-p.x)*(it.x-p.x)+(it.z-p.z)*(it.z-p.z)<36 } && blocks[Triple(p.x,3,p.z)]=="minecraft:air") {
                blocks[Triple(p.x,3,p.z)]="minecraft:light[level=7,waterlogged=false]"
                ambient+=p
            }
        }
        return blocks
    }
}
