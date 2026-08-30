package net.nuggetmc.tplus.api;

import com.mojang.authlib.GameProfile;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.NeuralNetwork;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.movement.CombatTrainingSnapshot;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.movement.MovementTrainingSnapshot;
import net.nuggetmc.tplus.compat.bukkit.Location;
import net.nuggetmc.tplus.compat.bukkit.Material;
import net.nuggetmc.tplus.compat.bukkit.World;
import net.nuggetmc.tplus.compat.bukkit.block.Block;
import net.nuggetmc.tplus.compat.bukkit.block.BlockFace;
import net.nuggetmc.tplus.compat.bukkit.entity.Entity;
import net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity;
import net.nuggetmc.tplus.compat.bukkit.inventory.EquipmentSlot;
import net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack;
import net.nuggetmc.tplus.compat.bukkit.util.BoundingBox;
import net.nuggetmc.tplus.compat.bukkit.util.Vector;

import java.util.List;
import java.util.UUID;

public interface Terminator {

    String getBotName();

    int getEntityId();

    GameProfile getGameProfile();

    LivingEntity getBukkitEntity();

    NeuralNetwork getNeuralNetwork();

    void setNeuralNetwork(NeuralNetwork neuralNetwork);

    boolean hasNeuralNetwork();

    Location getLocation();

    BoundingBox getBotBoundingBox();

    boolean isBotAlive(); //Has to be named like this because paper re-obfuscates it

    float getBotHealth();

    float getBotMaxHealth();

    boolean isBotOnFire();

    boolean isFalling();

    boolean isBotBlocking();

    void block(int length, int cooldown);

    boolean isBotInWater();

    boolean isBotOnGround();

    List<Block> getStandingOn();

    void setBotPitch(float pitch);

    default void setBotXRot(float pitch) {
        setBotPitch(pitch);
    }

    void jump(Vector velocity);

    void jump();

    void walk(Vector velocity);

    void look(BlockFace face);

    void faceLocation(Location location);

    void attack(Entity target);

    void attemptBlockPlace(Location loc, Material type, boolean down);

    void punch();

    void swim();

    void sneak();

    void stand();

    void addFriction(double factor);

    void removeVisually();

    void removeBot();

    default boolean isAutoRespawnAllowed() {
        return true;
    }

    default void setAutoRespawnAllowed(boolean allowed) {
    }

    int getKills();

    void incrementKills();

    void setItem(ItemStack item);

    void setItem(ItemStack item, EquipmentSlot slot);

    void setItemOffhand(ItemStack item);

    void setDefaultItem(ItemStack item);

    Vector getOffset();

    Vector getVelocity();

    void setVelocity(Vector velocity);

    void addVelocity(Vector velocity);

    int getAliveTicks();

    int getNoFallTicks();

    boolean tickDelay(int ticks);

    void renderBot(Object packetListener, boolean login);

    UUID getTargetPlayer();

    void setTargetPlayer(UUID target);

    boolean isInPlayerList();

    World.Environment getDimension();

    void setShield(boolean b);

    /**
     * Runs the combat director for this bot against the given target,
     * picking a hotbar weapon and triggering the appropriate behavior
     * (melee, trident, mace, wind charge, elytra+firework, etc).
     *
     * @return true if combat was handled; false if the caller should
     *         fall back to its default attack/targeting logic.
     */
    default boolean combatTick(LivingEntity target) {
        return false;
    }

    /**
     * Runs only already-committed combat phases, such as mace airborne tracking
     * or trident charge/release. This lets multi-tick weapon actions continue
     * even when the caller's normal melee/movement branch would skip this tick.
     */
    default boolean tickCommittedCombat(LivingEntity target) {
        return false;
    }

    default boolean usesMovementController() {
        return false;
    }

    default void planCombat(LivingEntity target) {
    }

    default boolean tryMovementControllerMove(LivingEntity target) {
        return false;
    }

    /**
     * Give the opt-in Movement V2 layer first refusal for routed pursuit. A
     * null target lets an implementation finish an action that was already in
     * progress; it must not begin a new route. Implementations return false to
     * preserve the exact legacy/controller movement path.
     */
    default boolean tryMovementV2Move(LivingEntity target, Location routeTarget) {
        return false;
    }

    /** Whether an opt-in traversal action currently owns the bot's hands. */
    default boolean movementV2ActionActive() {
        return false;
    }

    /** Cancel any opt-in traversal action when its target/lifecycle ends. */
    default void cancelMovementV2Action(String reason) {
    }

    default boolean executePlannedCombat(LivingEntity target) {
        return combatTick(target);
    }

    default MovementTrainingSnapshot movementTrainingSnapshot(LivingEntity target) {
        return MovementTrainingSnapshot.unavailable();
    }

    default CombatTrainingSnapshot combatTrainingSnapshot() {
        return CombatTrainingSnapshot.unavailable();
    }

    default boolean applyTrainingLoadout(String loadoutName) {
        return false;
    }

    /**
     * Vanilla-safe swing gate: true only when the bot's attack-strength charge is
     * fully recharged AND the target isn't deep in an i-frame window. Callers
     * that go through {@link #attack(Entity)} directly (legacy agent, bot agent,
     * etc) should consult this before swinging so the bot doesn't waste 75% of
     * its damage on a partially-charged strike.
     *
     * <p>Default returns true so unimplemented adapters don't silently block
     * attacks; real bot impls ({@link net.nuggetmc.tplus.api.Terminator} → {@code Bot})
     * back this with the actual attack-strength ticker check.
     */
    default boolean canSwingAttack(LivingEntity target) {
        return true;
    }
}
