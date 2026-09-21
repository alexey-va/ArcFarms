package ru.ruscrafting.farms.paper.mine.expedition

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.domain.mine.expedition.ExpeditionPoint
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import ru.ruscrafting.farms.persistence.MineFurnishingPose
import ru.ruscrafting.farms.persistence.MineFurnishingRepository
import ru.ruscrafting.farms.persistence.MineFurnishingState
import java.util.UUID

/** Explicit, idle-site-only editor. Inventory gestures never consume or move player items. */
internal class MineFurnishingEditor(
    private val plugin: Plugin,
    private val tasks: WorksiteTaskPort,
    private val locale: ArcFarmsLocale?,
    private val scenes: () -> Collection<MineExpeditionScene>,
    private val markers: MineExpeditionMarkers,
) : Listener {
    private data class Session(val site: Long, var key: String? = null,
        var base: ExpeditionPoint? = null, var pose: MineFurnishingPose = MineFurnishingPose(), var saving: Boolean = false)
    private class Menu(val owner: UUID) : InventoryHolder {
        lateinit var view: Inventory
        override fun getInventory() = view
    }
    private val repository = MineFurnishingRepository(plugin.dataFolder.toPath())
    private var state = MineFurnishingState()
    private var ready = false
    private data class PendingSave(val player:UUID,val session:Session,val state:MineFurnishingState,
        val future:java.util.concurrent.CompletableFuture<*>)
    private var pendingSave:PendingSave? = null
    private val sessions = mutableMapOf<UUID,Session>()
    init {
        plugin.server.pluginManager.registerEvents(this,plugin)
    }
    /** Called by loaded-state activation, after the owning gameplay epoch becomes active. */
    fun initialize() {
        ready=false
        val token=tasks.lifecycleToken()
        repository.loadAsync().whenComplete { value,error -> tasks.runSync(token) {
            if(error == null) { state=value;ready=true }
            else plugin.logger.severe("Cannot load expedition furnishing positions: ${error.message}")
        } }
    }
    fun pose(site: Long,id: String): MineFurnishingPose = sessions.values.firstOrNull { it.key=="$site/$id" }?.pose
        ?: state.poses["$site/$id"] ?: MineFurnishingPose()
    fun position(scene: MineExpeditionScene,id:String,base:ExpeditionPoint): ExpeditionPoint {
        val own=pose(scene.journalSequence,id)
        val parent=MineExpeditionFurnishings.parent(scene.kind,id)
        val anchor=parent?.let { key -> MineExpeditionFurnishings.fixtures(scene).firstOrNull { it.id==key }?.at }
        if(parent==null || anchor==null) return base.offset(own.x,own.y,own.z)
        val group=pose(scene.journalSequence,parent)
        val radians=Math.toRadians(yaw(scene,parent).toDouble())
        val dx=(base.x-anchor.x).toDouble();val dz=(base.z-anchor.z).toDouble()
        val x=kotlin.math.round(dx*kotlin.math.cos(radians)+dz*kotlin.math.sin(radians)).toInt()
        val z=kotlin.math.round(-dx*kotlin.math.sin(radians)+dz*kotlin.math.cos(radians)).toInt()
        return ExpeditionPoint(anchor.x+x+group.x+own.x,base.y+group.y+own.y,anchor.z+z+group.z+own.z)
    }
    fun yaw(scene:MineExpeditionScene,id:String):Int = (MineExpeditionFurnishings.baseYaw(scene,id)+pose(scene.journalSequence,id).yaw+
        (MineExpeditionFurnishings.parent(scene.kind,id)?.let { MineExpeditionFurnishings.baseYaw(scene,it)+pose(scene.journalSequence,it).yaw } ?: 0))%360
    fun selected(site: Long,id: String) = sessions.values.any { it.key=="$site/$id" }
    fun locked(scene: MineExpeditionScene) = sessions.values.any { it.site==scene.journalSequence }
    fun command(player: Player, action: String?) {
        if(!player.hasPermission("arcfarms.admin")) return
        if(action=="off") { cancel(player);return }
        if(!ready) { tell(player,"loading");return }
        val existing=sessions[player.uniqueId]
        if(existing!=null) { if(existing.key!=null) open(player,existing) else tell(player,"select");return }
        val scene=scenes().firstOrNull { it.ready && it.reserved && it.contains(player.location) }
        if(scene==null) { tell(player,"idle-only");return }
        if(locked(scene)) { tell(player,"occupied");return }
        sessions[player.uniqueId]=Session(scene.journalSequence)
        tell(player,"select")
    }
    @EventHandler(priority=EventPriority.LOWEST)
    fun select(event: PlayerInteractEntityEvent) {
        val session=sessions[event.player.uniqueId] ?: return
        event.isCancelled=true
        if(event.hand!=org.bukkit.inventory.EquipmentSlot.HAND || session.saving) return
        if(!valid(event.player,session)) { cancel(event.player);return }
        val target=markers.editable(event.rightClicked) ?: return
        val key=target.editKey ?: return
        if(!key.startsWith("${session.site}/") || event.player.location.distanceSquared(event.rightClicked.location)>64) return
        session.key=key; session.base=target.editBase; session.pose=state.poses[key] ?: MineFurnishingPose()
        open(event.player,session)
    }
    @EventHandler(priority=EventPriority.LOWEST)
    fun menuKey(event: PlayerSwapHandItemsEvent) {
        val session=sessions[event.player.uniqueId] ?: return
        event.isCancelled=true
        if(session.key!=null) open(event.player,session) else tell(event.player,"select")
    }
    @EventHandler(priority=EventPriority.LOWEST)
    fun click(event: InventoryClickEvent) {
        val holder=event.view.topInventory.holder as? Menu ?: return
        event.isCancelled=true
        val player=event.whoClicked as? Player ?: return
        val session=sessions[player.uniqueId] ?: return
        if(holder.owner!=player.uniqueId || session.saving || !valid(player,session)) return
        val p=session.pose
        when(event.rawSlot) {
            10 -> session.pose=p.copy(x=p.x-1)
            11 -> session.pose=p.copy(x=p.x+1)
            12 -> session.pose=p.copy(y=p.y-1)
            13 -> session.pose=p.copy(y=p.y+1)
            14 -> session.pose=p.copy(z=p.z-1)
            15 -> session.pose=p.copy(z=p.z+1)
            19 -> session.pose=p.copy(yaw=(p.yaw+345)%360)
            20 -> session.pose=p.copy(yaw=(p.yaw+15)%360)
            21 -> session.pose=MineFurnishingPose()
            23 -> { save(player,session); return }
            24 -> { cancel(player);return }
            else -> return
        }
        if(!placementValid(session)) { session.pose=p;tell(player,"blocked") }
        // Close to inspect the preview in the world; F reopens the same panel.
        player.closeInventory();tell(player,"preview")
    }
    @EventHandler(priority=EventPriority.LOWEST)
    fun drag(event: InventoryDragEvent) { if(event.view.topInventory.holder is Menu) event.isCancelled=true }
    @EventHandler fun quit(event: PlayerQuitEvent) { sessions.remove(event.player.uniqueId) }
    fun tick() {
        // Observe completion on the current gameplay tick: a reload must not strand an old epoch's save callback.
        pendingSave?.takeIf { it.future.isDone }?.let { save ->
            pendingSave=null;save.session.saving=false
            val result=runCatching { save.future.getNow(null) }
            if(result.isSuccess) {
                state=save.state;sessions.remove(save.player)
                Bukkit.getPlayer(save.player)?.let { tell(it,"saved") }
            } else {
                plugin.logger.warning("Cannot save expedition furnishing: ${result.exceptionOrNull()?.message}")
                Bukkit.getPlayer(save.player)?.let { tell(it,"failed") }
            }
        }
        sessions.keys.toList().forEach { id ->
            val player=Bukkit.getPlayer(id)
            if(player==null || !valid(player,sessions.getValue(id))) sessions.remove(id)
        }
    }
    private fun valid(player: Player,s:Session)=player.hasPermission("arcfarms.admin") &&
        scenes().any { it.journalSequence==s.site && it.ready && it.reserved && it.contains(player.location) }
    private fun placementValid(s:Session):Boolean {
        if(runCatching { s.pose.validate() }.isFailure) return false
        val scene=scenes().firstOrNull { it.journalSequence==s.site } ?: return false
        val base=s.base ?: return false
        val p=position(scene,s.key!!.substringAfter('/'),base)
        val id=s.key!!.substringAfter('/')
        val fixtures=MineExpeditionFurnishings.fixtures(scene)
        val affected=fixtures.filter { it.id==id || MineExpeditionFurnishings.parent(scene.kind,it.id)==id }
        for(fixture in affected) {
            val anchor=position(scene,fixture.id,fixture.at)
            val turn=org.joml.Quaternionf().rotateY(Math.toRadians(yaw(scene,fixture.id).toDouble()).toFloat())
            for(part in MineDisplayBlueprints.model(fixture.model)) {
                // Sample the complete rotation envelope of moving parts, including large wheel paddles.
                val phases=if(part.moving) (0..15).map { it*kotlin.math.PI.toFloat()/8 } else listOf(0f)
                for(phase in phases) for(x in listOf(-.5f,.5f)) for(y in listOf(-.5f,.5f)) for(z in listOf(-.5f,.5f)) {
                    val corner=MineDisplayBlueprints.rotation(part,phase).transform(org.joml.Vector3f(part.size).mul(x,y,z))
                        .add(MineDisplayBlueprints.center(part,phase)).mul(fixture.scale)
                    turn.transform(corner)
                    val point=ExpeditionPoint(kotlin.math.floor(anchor.x+.5+corner.x).toInt(),
                        kotlin.math.floor(anchor.y+corner.y.toDouble()).toInt(),kotlin.math.floor(anchor.z+.5+corner.z).toInt())
                    if(!scene.plan.bounds.contains(point)) return false
                }
            }
        }
        val at=scene.at(p)
        return at.chunk.isLoaded && !at.block.type.isSolid && !at.clone().add(0.0,1.0,0.0).block.type.isSolid
    }
    private fun save(player:Player,s:Session) {
        val key=s.key ?: return
        if(pendingSave!=null) { tell(player,"saving");return }
        if(!placementValid(s)) { tell(player,"blocked");return }
        val next=state.copy(poses=state.poses+(key to s.pose))
        s.saving=true
        pendingSave=PendingSave(player.uniqueId,s,next,repository.saveAsync(next))
        player.closeInventory();tell(player,"saving")
    }
    private fun cancel(player:Player) {
        val session=sessions[player.uniqueId]
        if(session?.saving==true) { tell(player,"saving");return }
        sessions.remove(player.uniqueId);player.closeInventory();tell(player,"cancelled")
    }
    private fun open(player:Player,s:Session) {
        val holder=Menu(player.uniqueId)
        holder.view=Bukkit.createInventory(holder,27,text("title",player))
        val choices=mapOf(10 to "x-minus",11 to "x-plus",12 to "y-minus",13 to "y-plus",14 to "z-minus",15 to "z-plus",
            19 to "turn-left",20 to "turn-right",21 to "reset",23 to "save",24 to "cancel")
        choices.forEach { (slot,key) -> holder.view.setItem(slot,ItemStack(when(slot) {
            23 -> Material.LIME_CONCRETE;24 -> Material.RED_CONCRETE;21 -> Material.CLOCK;else -> Material.ARROW
        }).apply { editMeta { it.displayName(text(key,player));it.lore(listOf(Component.text("${s.key} · ${s.pose.x}, ${s.pose.y}, ${s.pose.z} · ${s.pose.yaw}°"))) } }) }
        player.openInventory(holder.view)
    }
    private fun text(key:String,p:Player)=locale?.renderPath("admin.expeditions.editor.$key",p) ?: Component.text(key)
    private fun tell(p:Player,key:String)=p.sendMessage(text(key,p))
    fun cancelPreviews() {
        sessions.keys.forEach { id -> Bukkit.getPlayer(id)?.let { player ->
            if(player.openInventory.topInventory.holder is Menu) player.closeInventory()
        } }
        sessions.clear()
    }
    fun close() { HandlerList.unregisterAll(this);sessions.clear();repository.close() }
}
