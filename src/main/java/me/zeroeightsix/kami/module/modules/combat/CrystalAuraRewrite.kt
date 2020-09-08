package me.zeroeightsix.kami.module.modules.combat

import me.zero.alpine.listener.EventHandler
import me.zero.alpine.listener.EventHook
import me.zero.alpine.listener.Listener
import me.zeroeightsix.kami.event.events.PacketEvent
import me.zeroeightsix.kami.manager.mangers.CombatManager
import me.zeroeightsix.kami.manager.mangers.PlayerPacketManager
import me.zeroeightsix.kami.module.Module
import me.zeroeightsix.kami.setting.Settings
import me.zeroeightsix.kami.util.BlockUtils
import me.zeroeightsix.kami.util.CombatUtils.CrystalUtils
import me.zeroeightsix.kami.util.EntityUtils
import me.zeroeightsix.kami.util.InventoryUtils
import me.zeroeightsix.kami.util.LagCompensator
import me.zeroeightsix.kami.util.math.RotationUtils
import me.zeroeightsix.kami.util.math.Vec2f
import net.minecraft.entity.item.EntityEnderCrystal
import net.minecraft.init.Items
import net.minecraft.init.SoundEvents
import net.minecraft.network.play.client.CPacketPlayerTryUseItemOnBlock
import net.minecraft.network.play.server.SPacketSoundEffect
import net.minecraft.util.EnumHand
import net.minecraft.util.SoundCategory
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import java.util.*
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.ConcurrentSkipListSet
import kotlin.math.ceil
import kotlin.math.max

/**
 * Created by Xiaro on 19/07/20
 */
@Module.Info(
        name = "CrystalAuraRewrite",
        description = "A reborn of the CrystalAura",
        category = Module.Category.COMBAT,
        modulePriority = 80
)
// TODO: AutoOffhand
// TODO: HoleBreaker

class CrystalAuraRewrite : Module() {
    /* Settings */
    private val page = register(Settings.e<Page>("Page", Page.GENERAL))

    /* General */
    private val tpsSync = register(Settings.booleanBuilder("TpsSync").withValue(false).withVisibility { page.value == Page.GENERAL }.build())
    private val facePlaceThreshold = register(Settings.floatBuilder("FacePlace").withValue(5.0f).withRange(0.0f, 10.0f).withVisibility { page.value == Page.GENERAL }.build())

    /* Place */
    private val doPlace = register(Settings.booleanBuilder("Place").withValue(true).withVisibility { page.value == Page.PLACE }.build())
    private val autoSwap = register(Settings.booleanBuilder("AutoSwap").withValue(true).withVisibility { page.value == Page.PLACE }.build())
    private val forcePlace = register(Settings.booleanBuilder("RightClickForcePlace").withValue(false).withVisibility { page.value == Page.PLACE }.build())
    private val fastCalc = register(Settings.booleanBuilder("FastCalculation").withValue(false).withVisibility { page.value == Page.PLACE }.build())
    private val feetOnly = register(Settings.booleanBuilder("FeetOnly").withValue(false).withVisibility { page.value == Page.PLACE && !fastCalc.value }.build())
    private val minDamageP = register(Settings.integerBuilder("MinDamagePlace").withValue(4).withRange(0, 20).withVisibility { page.value == Page.PLACE && !fastCalc.value }.build())
    private val maxSelfDamageP = register(Settings.integerBuilder("MaxSelfDamagePlace").withValue(4).withRange(0, 20).withVisibility { page.value == Page.PLACE && !fastCalc.value }.build())
    private val maxCrystal = register(Settings.integerBuilder("MaxCrystal").withValue(1).withRange(1, 5).withVisibility { page.value == Page.PLACE }.build())
    private val placeRange = register(Settings.floatBuilder("PlaceRange").withValue(5.0f).withRange(0.0f, 10.0f).withVisibility { page.value == Page.PLACE }.build())

    /* Explode */
    private val doExplode = register(Settings.booleanBuilder("Explode").withValue(true).withVisibility { page.value == Page.EXPLODE }.build())
    private val forceExplode = register(Settings.booleanBuilder("LeftClickForceExplode").withValue(false).withVisibility { page.value == Page.EXPLODE }.build())
    private val autoForceExplode = register(Settings.booleanBuilder("AutoForceExplode").withValue(true).withVisibility { page.value == Page.EXPLODE }.build())
    private val checkImmune = register(Settings.booleanBuilder("CheckImmune").withValue(false).withVisibility { page.value == Page.EXPLODE }.build())
    private val checkDamage = register(Settings.booleanBuilder("CheckDamage").withValue(false).withVisibility { page.value == Page.EXPLODE }.build())
    private val minDamageE = register(Settings.integerBuilder("MinDamageExplode").withValue(8).withRange(0, 20).withVisibility { page.value == Page.EXPLODE && checkDamage.value }.build())
    private val maxSelfDamageE = register(Settings.integerBuilder("MaxSelfDamageExplode").withValue(6).withRange(0 ,20).withVisibility { page.value == Page.EXPLODE && checkDamage.value }.build())
    private val hitDelay = register(Settings.integerBuilder("HitDelay").withValue(2).withRange(1, 10).withVisibility { page.value == Page.EXPLODE }.build())
    private val explodeRange = register(Settings.floatBuilder("ExplodeRange").withValue(5.0f).withRange(0.0f, 10.0f).withVisibility { page.value == Page.EXPLODE }.build())
    private val wallExplodeRange = register(Settings.floatBuilder("WallExplodeRange").withValue(2.5f).withRange(0.0f, 10.0f).withVisibility { page.value == Page.EXPLODE }.build())
    /* End of settings */

    private enum class Page {
        GENERAL, PLACE, EXPLODE
    }

    /* Variables */
    private val placeMap = ConcurrentSkipListMap<Float, BlockPos>(Comparator.reverseOrder())
    private val crystalList = ConcurrentSkipListSet<EntityEnderCrystal>(compareBy { it.getDistance(mc.player) })
    private var swapTimer = 0
    private var hitTimer = 0
    private var inactiveTicks = 0

    override fun isActive(): Boolean {
        return isEnabled && (canPlace() || canExplode())
    }

    override fun onEnable() {
        if (mc.player == null) {
            this.disable()
            return
        }
        if (getCrystalHand() != null && mc.player.getCooledAttackStrength(0f) >= 0.2f) {
            swapTimer = 3
        }
    }

    override fun onDisable() {
        placeMap.clear()
        crystalList.clear()
        swapTimer = 0
        hitTimer = 0
        inactiveTicks = 0
    }

    @EventHandler
    private val receiveListener = Listener(EventHook { event: PacketEvent.Receive ->
        if (mc.player == null || event.packet !is SPacketSoundEffect) return@EventHook
        val packet = event.packet
        if (packet.getCategory() == SoundCategory.BLOCKS && packet.getSound() == SoundEvents.ENTITY_GENERIC_EXPLODE) {
            val crystalList = CrystalUtils.getCrystalList(10f)
            for (crystal in crystalList) {
                if (crystal.getDistance(packet.x, packet.y, packet.z) > 5) continue
                crystal.setDead()
            }
        }
    })

    override fun onUpdate() {
        if (CombatManager.getTopPriority() > modulePriority) return
        updateTickCounts()
        updateMap()

        if (hitTimer >= getHitDelay()) {
            if (canExplode()) explode()
            else if (canPlace()) place()
        } else {
            hitTimer++
            if (canPlace()) place()
        }
        spoofRotation()
    }

    /* Main functions */
    private fun updateTickCounts() {
        if (getCrystalHand() == null) {
            swapTimer = 0
            if (autoSwap.value && canPlace()) InventoryUtils.swapSlotToItem(426)
        } else {
            swapTimer++
        }

        if (isActive()) {
            inactiveTicks = 0
        } else {
            inactiveTicks++
        }
    }

    private fun updateMap() {
        placeMap.clear()
        placeMap.putAll(CrystalUtils.getPlacePos(CombatManager.target, mc.player, placeRange.value, fastCalc.value, feetOnly.value))

        crystalList.clear()
        crystalList.addAll(CrystalUtils.getCrystalList(max(placeRange.value, explodeRange.value)))
    }

    private fun place() {
        getPlacingPos()?.let { pos ->
            getCrystalHand()?.let { hand ->
                lastRotation = Vec2f(RotationUtils.getRotationTo(Vec3d(pos).add(0.5, 1.0, 0.5), true))
                mc.player.connection.sendPacket(CPacketPlayerTryUseItemOnBlock(pos, BlockUtils.getHitSide(pos), hand, 0F, 0F, 0F))
            }
        }
    }

    private fun explode() {
        getExplodingCrystal()?.let { crystal ->
            hitTimer = 0
            val lookAtPos = if (mc.player.canEntityBeSeen(crystal)) {
                crystal.getPositionEyes(1f) // If we can see the eyes then we look at the eye pos
            } else if (EntityUtils.canEntityFeetBeSeen(crystal)) {
                crystal.positionVector // If we can see the feet then we look at the feet pos
            } else {
                EntityUtils.canEntityHitboxBeSeen(crystal) // If we can it any vertex of the hit box then we look at it
            }?: crystal.getPositionEyes(1f)  // If now then just look at the eye pos

            lastRotation = Vec2f(RotationUtils.getRotationTo(lookAtPos, true))
            mc.playerController.attackEntity(mc.player, crystal)
            mc.player.swingArm(EnumHand.MAIN_HAND)
        }
    }
    /* End of main functions */

    /* General */
    private fun shouldFacePlace(): Boolean {
        return facePlaceThreshold.value > 0f && CombatManager.target?.let {
            it.health <= facePlaceThreshold.value
        } ?: false
    }

    private fun getHitDelay(): Int {
        return if (!tpsSync.value) {
            hitDelay.value
        } else {
            ceil(hitDelay.value * (20f / LagCompensator.tickRate)).toInt()
        }

    }

    private fun countValidCrystal(): Int {
        var count = 0
        CombatManager.target?.let { target ->
            for (crystal in crystalList) {
                if (crystal.getDistance(mc.player) > placeRange.value) continue
                if (!fastCalc.value && !shouldForcePlace()) {
                    val damage = CrystalUtils.calcExplosionDamage(crystal, target)
                    val selfDamage = CrystalUtils.calcExplosionDamage(crystal, mc.player)
                    if (!checkDamagePlace(damage, selfDamage)) continue
                }
                count++
            }
        }
        return count
    }
    /* End of general */

    /* Placing */
    private fun canPlace(): Boolean {
        return doPlace.value
                && getCrystalHand() != null
                && swapTimer > 2
                && getPlacingPos() != null
                && countValidCrystal() < maxCrystal.value
    }

    private fun getCrystalHand(): EnumHand? {
        return when (Items.END_CRYSTAL) {
            mc.player.heldItemMainhand.getItem() -> EnumHand.MAIN_HAND
            mc.player.heldItemOffhand.getItem() -> EnumHand.OFF_HAND
            else -> null
        }
    }

    private fun getPlacingPos(): BlockPos? {
        if (placeMap.isEmpty()) return null
        for ((damage, pos) in placeMap) {
            if (mc.player.getDistanceSq(pos) > placeRange.value * placeRange.value) continue
            if (!CrystalUtils.canPlaceCollide(pos)) continue
            if (!fastCalc.value && !shouldForcePlace()) {
                val selfDamage = CrystalUtils.calcExplosionDamage(pos, mc.player)
                if (!checkDamagePlace(damage, selfDamage)) continue
            }
            return pos
        }
        return null
    }

    private fun shouldForcePlace(): Boolean {
        return shouldFacePlace()
                || (forcePlace.value && mc.gameSettings.keyBindUseItem.isKeyDown
                && mc.player.heldItemMainhand.getItem() == Items.END_CRYSTAL)
    }

    /**
     * @return True if passed placing damage check
     */
    private fun checkDamagePlace(damage: Float, selfDamage: Float): Boolean {
        return (damage >= minDamageP.value) && (selfDamage <= maxSelfDamageP.value)
    }
    /* End of placing */

    /* Exploding */
    private fun canExplode(): Boolean {
        return doExplode.value && getExplodingCrystal() != null && CombatManager.target?.let { target ->
            if (checkImmune.value && target.isInvulnerable) return false
            if (checkDamage.value && !shouldForceExplode()) {
                var maxDamage = 0f
                var maxSelfDamage = 0f
                for (crystal in crystalList) {
                    maxDamage = max(maxDamage, CrystalUtils.calcExplosionDamage(crystal, target))
                    maxSelfDamage = max(maxSelfDamage, CrystalUtils.calcExplosionDamage(crystal, mc.player))
                }
                if (!checkDamageExplode(maxDamage, maxSelfDamage)) return false
            }
            return true
        } ?: false
    }

    private fun shouldForceExplode(): Boolean {
        return shouldFacePlace()
                || (autoForceExplode.value && placeMap.isNotEmpty() && placeMap.firstKey() > minDamageE.value)
                || (forceExplode.value && mc.gameSettings.keyBindAttack.isKeyDown
                && mc.player.heldItemMainhand.getItem() != Items.DIAMOND_PICKAXE
                && mc.player.heldItemMainhand.getItem() != Items.GOLDEN_APPLE)
    }

    /**
     * @return True if passed exploding damage check
     */
    private fun checkDamageExplode(damage: Float, selfDamage: Float): Boolean {
        return (damage >= minDamageE.value) && (selfDamage <= maxSelfDamageE.value)
    }

    private fun getExplodingCrystal(): EntityEnderCrystal? {
        if (crystalList.isEmpty()) return null
        return crystalList.firstOrNull { (mc.player.canEntityBeSeen(it) || EntityUtils.canEntityFeetBeSeen(it)) && it.getDistance(mc.player) < explodeRange.value }
                ?: crystalList.firstOrNull { EntityUtils.canEntityHitboxBeSeen(it) != null && it.getDistance(mc.player) < wallExplodeRange.value }
    }
    /* End of exploding */

    /* Rotation spoof */
    private var lastRotation = Vec2f(0f, 0f)

    private fun spoofRotation(rotation: Vec2f = lastRotation) {
        if (inactiveTicks > 50) return
        lastRotation = rotation
        PlayerPacketManager.addPacket(this, (PlayerPacketManager.PlayerPacket(rotating = true, rotation = rotation)))
    }
    /* End of rotation spoof */
}