package xyz.bluspring.kilt

import com.google.gson.GsonBuilder
import dev.architectury.event.EventResult
import dev.architectury.event.events.common.EntityEvent
import dev.architectury.event.events.common.TickEvent.ServerLevelTick
import io.github.fabricators_of_create.porting_lib.entity.events.LivingEntityEvents
import io.github.fabricators_of_create.porting_lib.event.client.InteractEvents
import io.github.fabricators_of_create.porting_lib.event.common.ExplosionEvents
import net.fabricmc.api.EnvType
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.world.InteractionResult
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraftforge.common.ForgeHooks
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.ForgeEventFactory
import net.minecraftforge.event.entity.EntityJoinLevelEvent
import net.minecraftforge.event.entity.living.LivingDropsEvent
import net.minecraftforge.eventbus.api.Event
import net.minecraftforge.fml.ModLoadingPhase
import net.minecraftforge.fml.ModLoadingStage
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent
import net.minecraftforge.server.ServerLifecycleHooks
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import xyz.bluspring.kilt.client.ClientStartingCallback
import xyz.bluspring.kilt.client.KiltClient
import xyz.bluspring.kilt.loader.KiltLoader
import xyz.bluspring.kilt.mixin.MinecraftServerAccessor
import java.util.*

class Kilt : ModInitializer {
    override fun onInitialize() {
        MinecraftForge.EVENT_BUS.start()

        registerFabricEvents()

        ClientStartingCallback.EVENT.register {
            load(false)
        }

        val dist = FabricLoader.getInstance().environmentType

        ServerLifecycleEvents.SERVER_STARTING.register {
            if (dist != EnvType.SERVER)
                return@register

            load(true)
        }
    }

    fun load(onServer: Boolean) {
        loader.initMods()

        loader.runPhaseExecutors(ModLoadingPhase.GATHER)

        // config load should be here
        var loaded = false

        ResourceManagerHelper.get(if (onServer) PackType.SERVER_DATA else PackType.CLIENT_RESOURCES).registerReloadListener(object : SimpleSynchronousResourceReloadListener {
            override fun getFabricId(): ResourceLocation {
                return ResourceLocation(MOD_ID, "forge_mod_loader")
            }

            override fun onResourceManagerReload(resourceManager: ResourceManager) {
                if (loaded)
                    return

                loader.runPhaseExecutors(ModLoadingPhase.LOAD)

                loader.mods.forEach { mod ->
                    mod.eventBus.post(FMLCommonSetupEvent(mod, ModLoadingStage.COMMON_SETUP))
                }

                loader.mods.forEach { mod ->
                    mod.eventBus.post(
                        if (onServer)
                            FMLDedicatedServerSetupEvent(mod, ModLoadingStage.SIDED_SETUP)
                        else {
                            FMLClientSetupEvent(mod, ModLoadingStage.SIDED_SETUP)
                        }
                    )
                }

                if (!onServer) {
                    KiltClient.lateRegisterEvents()
                }

                loader.runPhaseExecutors(ModLoadingPhase.COMPLETE)
                loaded = true
            }
        })
    }

    @Suppress("removal")
    private fun registerFabricEvents() {
        LivingEntityEvents.DROPS.register { entity, source, drops, level, recentlyHit ->
            MinecraftForge.EVENT_BUS.post(LivingDropsEvent(entity, source, drops, level, recentlyHit))
        }

        LivingEntityEvents.TICK.register {
            ForgeHooks.onLivingTick(it)
        }

        EntitySleepEvents.ALLOW_SLEEPING.register { player, pos ->
            ForgeEventFactory.onPlayerSleepInBed(player, Optional.of(pos))
        }

        EntitySleepEvents.ALLOW_SETTING_SPAWN.register { player, pos ->
            ForgeEventFactory.onPlayerSpawnSet(player, player.level().dimension(), pos, false)
        }

        EntitySleepEvents.ALLOW_SLEEP_TIME.register { player, pos, _ ->
            val ret = ForgeEventFactory.fireSleepingTimeCheck(player, Optional.of(pos))

            if (ret)
                InteractionResult.SUCCESS
            else
                InteractionResult.FAIL
        }

        ServerLifecycleEvents.SERVER_STARTED.register {
            ServerLifecycleHooks.handleServerStarted(it)
        }

        ServerLifecycleEvents.SERVER_STOPPING.register {
            ServerLifecycleHooks.handleServerStopping(it)
        }

        ServerLifecycleEvents.SERVER_STOPPED.register {
            ServerLifecycleHooks.expectServerStopped()
            ServerLifecycleHooks.handleServerStopped(it)
        }

        ExplosionEvents.START.register { level, explosion ->
            ForgeEventFactory.onExplosionStart(level, explosion)
        }

        ExplosionEvents.DETONATE.register { level, explosion, entities, diameter ->
            ForgeEventFactory.onExplosionDetonate(level, explosion, entities, diameter)
        }

        InteractEvents.USE.register { minecraft, hit, hand ->
            val player = minecraft.player

            when (hit.type) {
                HitResult.Type.BLOCK -> {
                    val result = ForgeHooks.onRightClickBlock(player, hand, (hit as BlockHitResult).blockPos, hit)

                    result.cancellationResult
                }

                HitResult.Type.MISS -> {
                    ForgeHooks.onItemRightClick(player, hand) ?: InteractionResult.PASS
                }

                HitResult.Type.ENTITY -> {
                    ForgeHooks.onInteractEntity(player, (hit as EntityHitResult).entity, hand) ?: InteractionResult.PASS
                }

                else -> throw IllegalStateException("this should be impossible.")
            }
        }

        InteractEvents.ATTACK.register { minecraft, hit ->
            val player = minecraft.player

            when (hit.type) {
                HitResult.Type.BLOCK -> {
                    val result = ForgeHooks.onLeftClickBlock(player, (hit as BlockHitResult).blockPos, hit.direction)

                    result.cancellationResult
                }

                HitResult.Type.ENTITY -> {
                    val result = ForgeHooks.onPlayerAttackTarget(minecraft.player, (hit as EntityHitResult).entity)

                    if (!result)
                        InteractionResult.FAIL
                    else
                        InteractionResult.PASS
                }

                HitResult.Type.MISS -> {
                    ForgeHooks.onEmptyLeftClick(player)

                    InteractionResult.PASS
                }

                else -> throw IllegalStateException("impossible")
            }
        }

        EntityEvent.ENTER_SECTION.register { entity, sectionX, sectionY, sectionZ, prevX, prevY, prevZ ->
            ForgeHooks.onEntityEnterSection(entity, ChunkPos.asLong(BlockPos(sectionX, sectionY, sectionZ)), ChunkPos.asLong(
                BlockPos(prevX, prevY, prevZ)
            ))
        }

        EntityEvent.ANIMAL_TAME.register { animal, player ->
            if (ForgeEventFactory.onAnimalTame(animal, player))
                EventResult.interruptDefault()
            else
                EventResult.pass()
        }

        EntityEvent.LIVING_DEATH.register { entity, source ->
            if (ForgeHooks.onLivingDeath(entity, source))
                EventResult.interruptDefault()
            else
                EventResult.pass()
        }

        EntityEvent.LIVING_HURT.register { entity, source, amount ->
            val newAmount = ForgeHooks.onLivingHurt(entity, source, amount)

            // TODO: set amount
            if (newAmount != 0F) {
                EventResult.interruptDefault()
            } else {
                EventResult.pass()
            }
        }

        EntityEvent.ADD.register { entity, level ->
            if (MinecraftForge.EVENT_BUS.post(EntityJoinLevelEvent(entity, level)))
                EventResult.interruptDefault()
            else
                EventResult.pass()
        }

        ServerLevelTick.SERVER_PRE.register {
            ForgeEventFactory.onPreServerTick((it as MinecraftServerAccessor)::callHaveTime, it)
        }

        ServerLevelTick.SERVER_POST.register {
            ForgeEventFactory.onPostServerTick((it as MinecraftServerAccessor)::callHaveTime, it)
        }

        ServerLevelTick.SERVER_LEVEL_PRE.register {
            ForgeEventFactory.onPreLevelTick(it, (it.server as MinecraftServerAccessor)::callHaveTime)
        }

        ServerLevelTick.SERVER_LEVEL_POST.register {
            ForgeEventFactory.onPostLevelTick(it, (it.server as MinecraftServerAccessor)::callHaveTime)
        }

        ServerLevelTick.PLAYER_PRE.register {
            ForgeEventFactory.onPlayerPreTick(it)
        }

        ServerLevelTick.PLAYER_POST.register {
            ForgeEventFactory.onPlayerPostTick(it)
        }
    }

    private fun eventBusToArchitectury(result: Event.Result): EventResult {
        return when (result) {
            Event.Result.ALLOW -> EventResult.interruptTrue()
            Event.Result.DEFAULT -> EventResult.pass()
            Event.Result.DENY -> EventResult.interruptFalse()
            else -> EventResult.pass()
        }
    }

    companion object {
        const val MOD_ID = "kilt"

        val logger: Logger = LoggerFactory.getLogger(Kilt::class.java)
        val loader: KiltLoader = KiltLoader()
        val gson = GsonBuilder().setPrettyPrinting().create()
    }
}