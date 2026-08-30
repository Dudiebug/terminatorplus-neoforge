package net.nuggetmc.tplus.api.agent.legacyagent;

import com.google.common.base.Optional;
import net.nuggetmc.tplus.api.BotManager;
import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.api.TerminatorPlusAPI;
import net.nuggetmc.tplus.api.agent.Agent;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.BotData;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.BotNode;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.NeuralNetwork;
import net.nuggetmc.tplus.api.event.BotDamageByPlayerEvent;
import net.nuggetmc.tplus.api.event.BotDeathEvent;
import net.nuggetmc.tplus.api.event.BotFallDamageEvent;
import net.nuggetmc.tplus.api.event.TerminatorLocateTargetEvent;
import net.nuggetmc.tplus.api.utils.BotUtils;
import net.nuggetmc.tplus.api.utils.MathUtils;
import net.nuggetmc.tplus.api.utils.PlayerUtils;
import net.nuggetmc.tplus.compat.bukkit.*;
import net.nuggetmc.tplus.compat.bukkit.block.Block;
import net.nuggetmc.tplus.compat.bukkit.block.BlockFace;
import net.nuggetmc.tplus.compat.bukkit.block.data.Waterlogged;
import net.nuggetmc.tplus.compat.bukkit.block.data.type.Door;
import net.nuggetmc.tplus.compat.bukkit.block.data.type.TrapDoor;
import net.nuggetmc.tplus.compat.bukkit.entity.*;
import net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack;
import net.nuggetmc.tplus.compat.bukkit.plugin.Plugin;
import net.nuggetmc.tplus.compat.bukkit.scheduler.BukkitRunnable;
import net.nuggetmc.tplus.compat.bukkit.util.BoundingBox;
import net.nuggetmc.tplus.compat.bukkit.util.Vector;

import java.util.*;
import java.util.Map.Entry;
import java.util.regex.Pattern;

// Yes, this code is very unoptimized, I know.
public class LegacyAgent extends Agent {

    private static final Pattern NAME_PATTERN = Pattern.compile("[^A-Za-z]+");
    public final Set<Terminator> noFace = new HashSet<>();
    public final Set<LivingEntity> noJump = new HashSet<>();
    public final Set<Terminator> slow = new HashSet<>();
    private final LegacyBlockCheck blockCheck;
    private Material placementMaterial = Material.COBBLESTONE;
    private final Map<LivingEntity, BukkitRunnable> miningAnim = new HashMap<>();
    private final Set<Boat> boats = new HashSet<>();
    private final Map<LivingEntity, Location> btList = new HashMap<>();
    private final Map<LivingEntity, Boolean> btCheck = new HashMap<>();
    private final Map<LivingEntity, Location> towerList = new HashMap<>();
    // Stuck-detection state: how many ticks the bot has been within 0.1 blocks
    // of its previous position, and that previous position.
    private final Map<LivingEntity, Integer> stuckTicks = new HashMap<>();
    private final Map<LivingEntity, Location> stuckLastLoc = new HashMap<>();
    private static final int STUCK_THRESHOLD_TICKS = 20;
    private final Set<Terminator> boatCooldown = new HashSet<>();
    private final Map<Block, Short> crackList = new HashMap<>();
    private final Map<BukkitRunnable, Byte> mining = new HashMap<>();
    private final Set<Terminator> fallDamageCooldown = new HashSet<>();
    public boolean offsets = true;
    private List<LivingEntity> botsInPlayerList;
    private EnumTargetGoal goal;
    private BoundingBox region;
    private double regionWeightX;
    private double regionWeightY;
    private double regionWeightZ;
    private final TargetingPolicy targetingPolicy;
    private final SurvivalController survivalController;
    private final MovementControllerRouter movementRouter;
    private final BotRuntimeOrchestrator runtimeOrchestrator;

    public static final Set<EntityType> CUSTOM_MOB_LIST = new HashSet<>();
    public static CustomListMode customListMode = CustomListMode.CUSTOM;

    public LegacyAgent(BotManager manager, Plugin plugin) {
        super(manager, plugin);

        this.goal = EnumTargetGoal.NEAREST_VULNERABLE_PLAYER;
        this.blockCheck = new LegacyBlockCheck(this, plugin);
        this.targetingPolicy = new LegacyTargetingPolicy(this);
        this.survivalController = new LegacySurvivalController(this);
        this.movementRouter = new LegacyMovementControllerRouter(this);
        this.runtimeOrchestrator = new BotRuntimeOrchestrator(this);
    }

    public Material getPlacementMaterial() {
        return placementMaterial;
    }

    public void setPlacementMaterial(Material placementMaterial) {
        if (placementMaterial != null) {
            this.placementMaterial = placementMaterial;
        }
    }

    private static boolean checkSideBreak(Material type) {
        return !LegacyMats.BREAK.contains(type);
    }

    @Override
    protected void tick() {
        botsInPlayerList = new ArrayList<>(manager.fetch().stream()
                .filter(t -> t.isInPlayerList())
                .map(b -> b.getBukkitEntity())
                .toList());
        manager.fetch().forEach(runtimeOrchestrator::tick);
    }

    private void center(Terminator bot) {
        if (bot == null || !bot.isBotAlive()) {
            return;
        }

        final LivingEntity botEntity = bot.getBukkitEntity();

        Location prev = null;
        if (btList.containsKey(botEntity)) {
            prev = btList.get(botEntity);
        }

        Location loc = botEntity.getLocation();

        if (prev != null) {
            if (loc.getBlockX() == prev.getBlockX() && loc.getBlockZ() == prev.getBlockZ()) {
                btCheck.put(botEntity, true);
            } else {
                btCheck.put(botEntity, false);
            }
        }

        btList.put(botEntity, loc);
    }

    void tickBot(Terminator bot) {
        if (!bot.isBotAlive()) {
            return;
        }

        if (bot.tickDelay(20)) {
            center(bot);
        }

        Location loc = bot.getLocation();
        LivingEntity botEntity = bot.getBukkitEntity();

        LivingEntity livingTarget = targetingPolicy.selectTarget(bot, loc);

        survivalController.beforeTarget(bot, loc);

        if (livingTarget == null) {
            // Do not abandon an already-committed bridge, pillar, mine, or
            // clutch merely because the target disappeared during the action.
            // A null target may only continue the existing V2 action.
            if (bot.movementV2ActionActive() && bot.tryMovementV2Move(null, null)) {
                return;
            }
            survivalController.onIdle(bot);
            return;
        }

        // Stuck detection: only meaningful when the bot has somewhere to go
        // AND isn't already in melee range. A bot that's standing on top of
        // its target looks "stuck" to a position-delta check even though it's
        // legitimately swinging — jolting it here just makes a crowd of bots
        // jump forever (per the screenshot around the sleeping villager).
        double distToTarget = loc.getWorld() == livingTarget.getWorld()
                ? loc.distance(livingTarget.getLocation())
                : Double.MAX_VALUE;
        boolean inMeleeRange = distToTarget <= 4.5;
        Location prev = stuckLastLoc.get(botEntity);
        if (!bot.movementV2ActionActive()
                && !inMeleeRange && prev != null && prev.getWorld() == loc.getWorld()
                && prev.distanceSquared(loc) < 0.01) {
            int count = stuckTicks.getOrDefault(botEntity, 0) + 1;
            if (count >= STUCK_THRESHOLD_TICKS && bot.isBotOnGround()) {
                double ang = 2 * Math.random() * Math.PI;
                bot.jump(new Vector(Math.cos(ang) * 0.35, 0.42, Math.sin(ang) * 0.35));
                stuckTicks.put(botEntity, 0);
            } else {
                stuckTicks.put(botEntity, count);
            }
        } else {
            stuckTicks.put(botEntity, 0);
        }
        stuckLastLoc.put(botEntity, loc.clone());

        survivalController.beforeMovement(bot, livingTarget);

        LivingEntity botPlayer = bot.getBukkitEntity();
        Location target = offsets ? livingTarget.getLocation().add(bot.getOffset()) : livingTarget.getLocation();

        MovementMode movementMode = movementRouter.mode(bot);
        boolean ai = movementMode != MovementMode.LEGACY;
        boolean movementController = movementMode == MovementMode.MOVEMENT_CONTROLLER_NN;

        NeuralNetwork network = ai ? bot.getNeuralNetwork() : null;

        if (movementMode == MovementMode.FULL_REPLACEMENT_NN) {
            network.feed(BotData.generate(bot, livingTarget));
        }

        bot.tickCommittedCombat(livingTarget);
        planRoutedCombat(bot, livingTarget, movementMode);

        // Disabled-by-default Movement V2 gets first refusal only for the
        // modern legacy/controller movement modes. Returning false preserves
        // the obstacle shell and NN fallback below exactly as before.
        if (usesModernCombatPipeline(movementMode)
                && bot.tryMovementV2Move(livingTarget, target)) {
            executeRoutedCombat(bot, livingTarget, movementMode);
            return;
        }

        // Full-replacement NN keeps the old deterministic 3-tick combat cadence
        // for training compatibility. Legacy and movement-controller modes use
        // the modern plan -> movement -> execute pipeline below.
        boolean combatTickReady = movementMode == MovementMode.FULL_REPLACEMENT_NN && bot.tickDelay(3);
        if (combatTickReady) {
            Location botEyeLoc = botPlayer.getEyeLocation();
            Location playerEyeLoc = livingTarget.getEyeLocation();
            Location playerLoc = livingTarget.getLocation();

            if (network.check(BotNode.BLOCK) && loc.distance(livingTarget.getLocation()) < 6) {
                bot.block(10, 10);
            }

            boolean handledByDirector = bot.combatTick(livingTarget);
            if (!handledByDirector
                    && (LegacyUtils.checkFreeSpace(botEyeLoc, playerEyeLoc)
                    || LegacyUtils.checkFreeSpace(botEyeLoc, playerLoc))) {
                attack(bot, livingTarget, loc);
            }
        }

        boolean waterGround = (LegacyMats.WATER.contains(loc.clone().add(0, -0.1, 0).getBlock().getType())
                && !LegacyMats.AIR.contains(loc.clone().add(0, -0.6, 0).getBlock().getType()));

        boolean withinTargetXZ = false, sameXZ = false;

        if (btCheck.containsKey(botPlayer)) sameXZ = btCheck.get(botPlayer);

        if (waterGround || bot.isBotOnGround() || onBoat(botPlayer)) {
            byte sideResult = 1;

            if (towerList.containsKey(botPlayer)) {
                if (loc.getBlockY() > livingTarget.getLocation().getBlockY()) {
                    towerList.remove(botPlayer);
                    resetHand(bot, livingTarget, botPlayer);
                }
            }

            Block block = loc.clone().add(0, 1, 0).getBlock();

            if (Math.abs(loc.getBlockX() - target.getBlockX()) <= 3 &&
                    Math.abs(loc.getBlockZ() - target.getBlockZ()) <= 3) {
                withinTargetXZ = true;
            }

            boolean bothXZ = withinTargetXZ || sameXZ;

            if (checkAt(bot, block, botPlayer)) {
                executeRoutedCombat(bot, livingTarget, movementMode);
                return;
            }

            // Gate/obstacle handlers kick off block-break animations. Previously
            // they returned early, so the bot stopped walking entirely while the
            // block was being broken — in a corner with something in front of
            // the bot, that's a lockup. Fall through to move() so the bot keeps
            // pressing into the path while the block crumbles.
            checkFenceAndGates(bot, loc.getBlock(), botPlayer);
            checkObstacles(bot, loc.getBlock(), botPlayer);

            if (checkDown(bot, botPlayer, livingTarget.getLocation(), bothXZ)) {
                executeRoutedCombat(bot, livingTarget, movementMode);
                return;
            }

            if ((withinTargetXZ || sameXZ) && checkUp(bot, livingTarget, botPlayer, target, withinTargetXZ, sameXZ)) {
                executeRoutedCombat(bot, livingTarget, movementMode);
                return;
            }

            if (bothXZ) sideResult = checkSide(bot, livingTarget, botPlayer);

            switch (sideResult) {
                case 1:
                    resetHand(bot, livingTarget, botPlayer);
                    if (movementController) {
                        movementRouter.move(bot, livingTarget, loc, target, movementMode, !noJump.contains(botPlayer) && !waterGround);
                    } else if (!noJump.contains(botPlayer) && !waterGround) {
                        movementRouter.move(bot, livingTarget, loc, target, movementMode, true);
                    }
                    executeRoutedCombat(bot, livingTarget, movementMode);
                    return;

                case 2:
                    if (movementController) {
                        movementRouter.move(bot, livingTarget, loc, target, movementMode, !waterGround);
                    } else if (!waterGround) {
                        movementRouter.move(bot, livingTarget, loc, target, movementMode, true);
                    }
            }
        } else if (LegacyMats.WATER.contains(loc.getBlock().getType())) {
            swim(bot, target, botPlayer, livingTarget, LegacyMats.WATER.contains(loc.clone().add(0, -1, 0).getBlock().getType()));
        }

        executeRoutedCombat(bot, livingTarget, movementMode);
    }

    void move(Terminator bot, LivingEntity livingTarget, Location loc, Location target, MovementMode movementMode, boolean allowMovement) {
        if (movementMode == MovementMode.MOVEMENT_CONTROLLER_NN) {
            if (allowMovement && !bot.tryMovementControllerMove(livingTarget)) {
                moveLegacy(bot, livingTarget, loc, target, false);
            }
            return;
        }

        moveLegacy(bot, livingTarget, loc, target, movementMode == MovementMode.FULL_REPLACEMENT_NN);
    }

    private boolean usesModernCombatPipeline(MovementMode mode) {
        return mode == MovementMode.LEGACY
                || mode == MovementMode.MOVEMENT_CONTROLLER_NN;
    }

    private void planRoutedCombat(Terminator bot, LivingEntity target, MovementMode mode) {
        if (usesModernCombatPipeline(mode)) {
            bot.planCombat(target);
        }
    }

    private boolean executeRoutedCombat(Terminator bot, LivingEntity target, MovementMode mode) {
        if (!usesModernCombatPipeline(mode)) {
            return false;
        }
        return bot.executePlannedCombat(target);
    }

    private void moveLegacy(Terminator bot, LivingEntity livingTarget, Location loc, Location target, boolean ai) {
        Vector position = loc.toVector();
        Vector vel = target.toVector().subtract(position).normalize();

        if (bot.tickDelay(5)) bot.faceLocation(livingTarget.getLocation());
        if (!bot.isBotOnGround()) return; // calling this a second time later on

        bot.stand(); // eventually create a memory system so packets do not have to be sent every tick
        // (Previously: bot.setItem(null). That blanked the mainhand every move
        //  tick, so if a scanner play or combat branch fires later in the same
        //  server tick the bot swings with EquipmentSlot.HAND = AIR. A 274k-
        //  line log showed 6,472 melee-hits with w=AIR because of this line.
        //  The hotbar slot tracker is authoritative — leave the hand alone.)

        try {
            vel.add(bot.getVelocity());
        } catch (IllegalArgumentException e) {
            if (MathUtils.isNotFinite(vel)) {
                MathUtils.clean(vel);
            }
        }

        if (vel.length() > 1) vel.normalize();

        double distance = loc.distance(target);

        if (distance <= 5) {
            vel.multiply(0.3);
        } else {
            vel.multiply(0.4);
        }

        if (slow.contains(bot)) {
            vel.setY(0).multiply(0.5);
        } else {
            vel.setY(0.4);
        }

        vel.setY(vel.getY() - Math.random() * 0.05);

        if (ai) {
            NeuralNetwork network = bot.getNeuralNetwork();

            if (network.dynamicLR()) {
                if (bot.isBotBlocking()) {
                    vel.multiply(0.6);
                }

                if (distance <= 6) {

                    // positive y rotation means left, negative means right
                    // if left > right, value will be positive

                    double value = network.value(BotNode.LEFT) - network.value(BotNode.RIGHT);

                    vel.rotateAroundY(value * Math.PI / 8);

                    if (network.check(BotNode.JUMP)) {
                        bot.jump(vel);
                    } else {
                        bot.walk(vel.clone().setY(0));
                        scheduleTaskLater(() -> bot.jump(vel), 10);
                    }

                    return;
                }
            } else {
                boolean left = network.check(BotNode.LEFT);
                boolean right = network.check(BotNode.RIGHT);

                if (bot.isBotBlocking()) {
                    vel.multiply(0.6);
                }

                if (left != right && distance <= 6) {

                    if (left) {
                        vel.rotateAroundY(Math.PI / 4);
                    }

                    if (right) {
                        vel.rotateAroundY(-Math.PI / 4);
                    }

                    if (network.check(BotNode.JUMP)) {
                        bot.jump(vel);
                    } else {
                        bot.walk(vel.clone().setY(0));
                        scheduleTaskLater(() -> bot.jump(vel), 10);
                    }

                    return;
                }
            }
        }

        bot.jump(vel);
    }

    MovementMode movementMode(Terminator bot) {
        if (!bot.hasNeuralNetwork()) return MovementMode.LEGACY;
        return bot.usesMovementController() ? MovementMode.MOVEMENT_CONTROLLER_NN : MovementMode.FULL_REPLACEMENT_NN;
    }

    enum MovementMode {
        LEGACY,
        FULL_REPLACEMENT_NN,
        MOVEMENT_CONTROLLER_NN
    }

    void fallDamageCheck(Terminator bot) {
        if (!bot.isFalling()) return;

        Material itemType = bot.getDimension() == World.Environment.NETHER
                ? Material.TWISTING_VINES
                : Material.WATER_BUCKET;

        LivingEntity le = bot.getBukkitEntity();
        if (le instanceof net.nuggetmc.tplus.compat.bukkit.entity.Player p) {
            ItemStack held = p.getInventory().getItemInMainHand();
            if (held != null) {
                Material heldType = held.getType();
                // Don't swap the weapon out for a water bucket during a mace-smash dive.
                // Vanilla mace fall-damage scaling means the smash ITSELF negates the fall
                // damage on a successful hit, and writing a water bucket to mainhand mid-
                // dive wipes the mace + resets attackStrengthTicker, so the crushing smash
                // downgrades to a 0-damage air swing.
                if (heldType == Material.MACE) return;
                // Already holding the clutch item — no write needed. Just keep looking down.
                if (heldType == itemType) {
                    bot.look(BlockFace.DOWN);
                    return;
                }
            }
        }

        bot.look(BlockFace.DOWN);
    }

    @Override
    public void onBotDeath(BotDeathEvent event) {
        if (!drops) {
            event.getDrops().clear();
        }
    }

    @Override
    public void onPlayerDamage(BotDamageByPlayerEvent event) {
        Terminator bot = event.getBot();
        Location loc = bot.getLocation();
        Player player = event.getPlayer();

        double dot = loc.toVector().subtract(player.getLocation().toVector()).normalize().dot(loc.getDirection());

        if (bot.isBotBlocking() && dot >= -0.1) {
            player.getWorld().playSound(bot.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1, 1);
            event.setCancelled(true);
        }
    }

    @Override
    public void onFallDamage(BotFallDamageEvent event) {
        Terminator bot = event.getBot();
        // Mace hits negate fall damage — skip the water-bucket clutch entirely.
        if (bot.getBukkitEntity() instanceof Player macePlayer) {
            for (int i = 0; i < 9; i++) {
                ItemStack hotbarItem = macePlayer.getInventory().getItem(i);
                if (hotbarItem != null && hotbarItem.getType() == Material.MACE) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
        World world = bot.getBukkitEntity().getWorld();

        bot.look(BlockFace.DOWN);

        Material itemType;
        Material placeType;
        Sound sound;
        Location groundLoc = null;
        boolean nether = bot.getDimension() == World.Environment.NETHER;
        double yPos = bot.getBukkitEntity().getLocation().getY();

        if (nether) {
            itemType = Material.TWISTING_VINES;
            sound = Sound.BLOCK_WEEPING_VINES_PLACE;
            placeType = itemType;
            
            for (Block block : event.getStandingOn()) {
            	if (LegacyMats.canPlaceTwistingVines(block)) {
            		groundLoc = block.getLocation();
            		break;
            	}
            }
        } else {
            itemType = Material.WATER_BUCKET;
            sound = Sound.ITEM_BUCKET_EMPTY;
            placeType = Material.WATER;
            
            for (Block block : event.getStandingOn()) {
            	if (LegacyMats.canPlaceWater(block, Optional.of(yPos))) {
            		groundLoc = block.getLocation();
            		break;
            	}
            }
        }
        
        if (groundLoc == null) return;
        
        Location loc = !LegacyMats.shouldReplace(groundLoc.getBlock(), yPos, nether) ? groundLoc.add(0, 1, 0) : groundLoc;
        boolean waterloggable = !nether && loc.getBlock().getBlockData() instanceof Waterlogged;
        boolean waterlogged = waterloggable && ((Waterlogged)loc.getBlock().getBlockData()).isWaterlogged();

        event.setCancelled(true);

        if (loc.getBlock().getType() != placeType && !waterlogged) {
            bot.punch();
            if (waterloggable) {
            	Waterlogged data = (Waterlogged)loc.getBlock().getBlockData();
            	data.setWaterlogged(true);
            	loc.getBlock().setBlockData(data);
            } else
            	loc.getBlock().setType(placeType);
            world.playSound(loc, sound, 1, 1);

            if (itemType == Material.WATER_BUCKET) {
                scheduleTaskLater(() -> {
                    Block block = loc.getBlock();

                    boolean waterloggedNow = !nether && block.getBlockData() instanceof Waterlogged
                    	&& ((Waterlogged)block.getBlockData()).isWaterlogged();
                    if (block.getType() == Material.WATER || waterloggedNow) {
                        bot.look(BlockFace.DOWN);
                        world.playSound(loc, Sound.ITEM_BUCKET_FILL, 1, 1);
                        if (waterloggedNow) {
                        	Waterlogged data = (Waterlogged)loc.getBlock().getBlockData();
                        	data.setWaterlogged(false);
                        	loc.getBlock().setBlockData(data);
                        } else
                        	block.setType(Material.AIR);
                    }
                }, 5);
            }
        }
    }

    private void swim(Terminator bot, Location loc, LivingEntity playerNPC, LivingEntity target, boolean anim) {
        if (playerNPC instanceof Player) {
            ((Player) playerNPC).setSneaking(false);
        }

        Location at = bot.getLocation();

        Vector vector = loc.toVector().subtract(at.toVector());
        if (at.getBlockY() < target.getLocation().getBlockY()) {
            vector.setY(0);
        }

        vector.normalize().multiply(0.05);
        vector.setY(vector.getY() * 1.2);

        if (miningAnim.containsKey(playerNPC)) {
            BukkitRunnable task = miningAnim.get(playerNPC);
            if (task != null) {
                task.cancel();
                taskList.remove(task);
                miningAnim.remove(playerNPC);
            }
        }

        if (anim) {
            bot.swim();
        } else {
            vector.setY(0);
            vector.multiply(0.7);
        }

        bot.faceLocation(target.getLocation());
        bot.addVelocity(vector);
    }

    void stopMining(Terminator bot) {
        LivingEntity playerNPC = bot.getBukkitEntity();
        if (miningAnim.containsKey(playerNPC)) {
            BukkitRunnable task = miningAnim.get(playerNPC);
            if (task != null) {
                task.cancel();
                taskList.remove(task);
                miningAnim.remove(playerNPC);
            }
        }
    }

    void clearIdleTracking(Terminator bot) {
        LivingEntity botEntity = bot.getBukkitEntity();
        // Idle (no target) -- clear any pending stuck counter so we don't
        // jolt the bot when they legitimately have nowhere to go.
        stuckTicks.remove(botEntity);
        stuckLastLoc.remove(botEntity);
    }

    LegacyBlockCheck blockCheck() {
        return blockCheck;
    }

    @Override
    public void onBotRemoved(Terminator bot) {
        if (bot == null) return;

        noFace.remove(bot);
        slow.remove(bot);
        boatCooldown.remove(bot);
        fallDamageCooldown.remove(bot);

        LivingEntity entity = null;
        try {
            entity = bot.getBukkitEntity();
        } catch (RuntimeException ignored) {
        }

        if (entity != null) {
            BukkitRunnable miningTask = miningAnim.remove(entity);
            if (miningTask != null) {
                if (!miningTask.isCancelled()) {
                    miningTask.cancel();
                }
                taskList.remove(miningTask);
            }
            noJump.remove(entity);
            btList.remove(entity);
            btCheck.remove(entity);
            towerList.remove(entity);
            stuckTicks.remove(entity);
            stuckLastLoc.remove(entity);
            if (botsInPlayerList != null) {
                botsInPlayerList.remove(entity);
            }

            LivingEntity finalEntity = entity;
            boats.removeIf(boat -> boat == null
                    || !boat.isValid()
                    || boat.getPassengers().contains(finalEntity));
        }
    }

    void scheduleLegacyTaskLater(Runnable action, long delayTicks) {
        scheduleTaskLater(action, delayTicks);
    }

    private byte checkSide(Terminator npc, LivingEntity target, LivingEntity playerNPC) {  // make it so they don't jump when checking side
        Location a = playerNPC.getEyeLocation();
        Location b = target.getLocation().add(0, 1, 0);

        if (npc.getLocation().distance(target.getLocation()) < 2.9 && LegacyUtils.checkFreeSpace(a, b)) {
            resetHand(npc, target, playerNPC);
            return 1;
        }

        LegacyLevel level = checkNearby(target, npc);

        if (level == null) {
            resetHand(npc, target, playerNPC);
            return 1;
        } else if (level.isSide() || level == LegacyLevel.BELOW || level == LegacyLevel.ABOVE) {
            return 0;
        } else {
            return 2;
        }
    }

    private LegacyLevel checkNearby(LivingEntity target, Terminator npc) {
        LivingEntity player = npc.getBukkitEntity();

        npc.faceLocation(target.getLocation());

        BlockFace dir = player.getFacing();
        LegacyLevel level = null;
        Block get = null;
        
        BoundingBox box = player.getBoundingBox();
        double[] xVals = new double[]{
                box.getMinX(),
                box.getMaxX() - 0.01
        };

        double[] zVals = new double[]{
                box.getMinZ(),
                box.getMaxZ() - 0.01
        };
        List<Location> locStanding = new ArrayList<>();
    	for (double x : xVals) {
            for (double z : zVals) {
            	Location loc = new Location(player.getWorld(), Math.floor(x), npc.getLocation().getBlockY(), Math.floor(z));
            	if (!locStanding.contains(loc))
            		locStanding.add(loc);
            }
    	}
    	Collections.sort(locStanding, (a, b) ->
    		Double.compare(BotUtils.getHorizSqDist(a, player.getLocation()), BotUtils.getHorizSqDist(b, player.getLocation())));
    	
        //Break potential obstructing walls
    	for (Location loc : locStanding) {
    		boolean up = false;
    		get = loc.getBlock();
    		if (!LegacyMats.FENCE.contains(get.getType())) {
    			up = true;
    			get = loc.add(0, 1, 0).getBlock();
    			if (!LegacyMats.FENCE.contains(get.getType())) {
    				get = null;
    			}
    		}
    		
    		if (get != null) {
    			int distanceX = get.getLocation().getBlockX() - player.getLocation().getBlockX();
    			int distanceZ = get.getLocation().getBlockZ() - player.getLocation().getBlockZ();
    			if (distanceX == 1 && distanceZ == 0) {
    				if (dir == BlockFace.NORTH || dir == BlockFace.SOUTH) {
    					npc.faceLocation(get.getLocation());
    					level = up ? LegacyLevel.EAST : LegacyLevel.EAST_D;
    				}
    			} else if (distanceX == -1 && distanceZ == 0) {
    				if (dir == BlockFace.NORTH || dir == BlockFace.SOUTH) {
    					npc.faceLocation(get.getLocation());
    					level = up ? LegacyLevel.WEST : LegacyLevel.WEST_D;
    				}
    			} else if (distanceX == 0 && distanceZ == 1) {
    				if (dir == BlockFace.EAST || dir == BlockFace.WEST) {
    					npc.faceLocation(get.getLocation());
    					level = up ? LegacyLevel.SOUTH : LegacyLevel.SOUTH_D;
    				}
    			} else if (distanceX == 0 && distanceZ == -1) {
    				if (dir == BlockFace.EAST || dir == BlockFace.WEST) {
    					npc.faceLocation(get.getLocation());
    					level = up ? LegacyLevel.NORTH : LegacyLevel.NORTH_D;
    				}
    			}
    			
    	        if (level != null) {
    	            preBreak(npc, player, get, level);
        	        return level;
    	        }
    		}
    	}

        switch (dir) {
            case NORTH:
                get = player.getLocation().add(0, 1, -1).getBlock();
                if (checkSideBreak(get.getType())) {
                    level = LegacyLevel.NORTH;
                } else if (checkSideBreak(get.getLocation().add(0, -1, 0).getBlock().getType())) {
                    get = get.getLocation().add(0, -1, 0).getBlock();
                    level = LegacyLevel.NORTH_D;
                } else if (LegacyMats.FENCE.contains(get.getLocation().add(0, -2, 0).getBlock().getType())) {
                    get = get.getLocation().add(0, -2, 0).getBlock();
                    level = LegacyLevel.NORTH_D_2;
                } else {
                	Block standing = npc.getStandingOn().isEmpty() ? null : npc.getStandingOn().get(0);
                	if(standing == null)
                		break;
                	boolean obstructed = standing.getLocation().getBlockY() == player.getLocation().getBlockY()
                		|| (standing.getLocation().getBlockY() + 1 == player.getLocation().getBlockY()
                		&& (LegacyMats.FENCE.contains(standing.getType()) || LegacyMats.GATES.contains(standing.getType())));
                	if(obstructed) {
                		Block belowStanding = standing.getLocation().add(0, -1, 0).getBlock();
                		if(!LegacyMats.BREAK.contains(belowStanding.getType()) && !LegacyMats.NONSOLID.contains(belowStanding.getType())) {
                			//Break standing block
                			get = standing;
                			level = LegacyLevel.BELOW;
                		} else {
	                		//Break above
	                		Block above = npc.getLocation().add(0, 2, 0).getBlock();
	                		Block aboveSide = get.getLocation().add(0, 1, 0).getBlock();
	                		if(!LegacyMats.BREAK.contains(above.getType())) {
	                			get = above;
	                            level = LegacyLevel.ABOVE;
	                		} else if(!LegacyMats.BREAK.contains(aboveSide.getType())) {
	                			get = aboveSide;
	                            level = LegacyLevel.NORTH_U;
	                		}
                		}
                	}
                }
                break;
            case SOUTH:
                get = player.getLocation().add(0, 1, 1).getBlock();
                if (checkSideBreak(get.getType())) {
                    level = LegacyLevel.SOUTH;
                } else if (checkSideBreak(get.getLocation().add(0, -1, 0).getBlock().getType())) {
                    get = get.getLocation().add(0, -1, 0).getBlock();
                    level = LegacyLevel.SOUTH_D;
                } else if (LegacyMats.FENCE.contains(get.getLocation().add(0, -2, 0).getBlock().getType())) {
                    get = get.getLocation().add(0, -2, 0).getBlock();
                    level = LegacyLevel.SOUTH_D_2;
                } else {
                	Block standing = npc.getStandingOn().isEmpty() ? null : npc.getStandingOn().get(0);
                	if(standing == null)
                		break;
                	boolean obstructed = standing.getLocation().getBlockY() == player.getLocation().getBlockY()
                		|| (standing.getLocation().getBlockY() + 1 == player.getLocation().getBlockY()
                		&& (LegacyMats.FENCE.contains(standing.getType()) || LegacyMats.GATES.contains(standing.getType())));
                	if(obstructed) {
                		Block belowStanding = standing.getLocation().add(0, -1, 0).getBlock();
                		if(!LegacyMats.BREAK.contains(belowStanding.getType()) && !LegacyMats.NONSOLID.contains(belowStanding.getType())) {
                			//Break standing block
                			get = standing;
                			level = LegacyLevel.BELOW;
                		} else {
	                		//Break above
	                		Block above = npc.getLocation().add(0, 2, 0).getBlock();
	                		Block aboveSide = get.getLocation().add(0, 1, 0).getBlock();
	                		if(!LegacyMats.BREAK.contains(above.getType())) {
	                			get = above;
	                            level = LegacyLevel.ABOVE;
	                		} else if(!LegacyMats.BREAK.contains(aboveSide.getType())) {
	                			get = aboveSide;
	                            level = LegacyLevel.SOUTH_U;
	                		}
                		}
                	}
                }
                break;
            case EAST:
                get = player.getLocation().add(1, 1, 0).getBlock();
                if (checkSideBreak(get.getType())) {
                    level = LegacyLevel.EAST;
                } else if (checkSideBreak(get.getLocation().add(0, -1, 0).getBlock().getType())) {
                    get = get.getLocation().add(0, -1, 0).getBlock();
                    level = LegacyLevel.EAST_D;
                } else if (LegacyMats.FENCE.contains(get.getLocation().add(0, -2, 0).getBlock().getType())) {
                    get = get.getLocation().add(0, -2, 0).getBlock();
                    level = LegacyLevel.EAST_D_2;
                } else {
                	Block standing = npc.getStandingOn().isEmpty() ? null : npc.getStandingOn().get(0);
                	if(standing == null)
                		break;
                	boolean obstructed = standing.getLocation().getBlockY() == player.getLocation().getBlockY()
                		|| (standing.getLocation().getBlockY() + 1 == player.getLocation().getBlockY()
                		&& (LegacyMats.FENCE.contains(standing.getType()) || LegacyMats.GATES.contains(standing.getType())));
                	if(obstructed) {
                		Block belowStanding = standing.getLocation().add(0, -1, 0).getBlock();
                		if(!LegacyMats.BREAK.contains(belowStanding.getType()) && !LegacyMats.NONSOLID.contains(belowStanding.getType())) {
                			//Break standing block
                			get = standing;
                			level = LegacyLevel.BELOW;
                		} else {
	                		//Break above
	                		Block above = npc.getLocation().add(0, 2, 0).getBlock();
	                		Block aboveSide = get.getLocation().add(0, 1, 0).getBlock();
	                		if(!LegacyMats.BREAK.contains(above.getType())) {
	                			get = above;
	                            level = LegacyLevel.ABOVE;
	                		} else if(!LegacyMats.BREAK.contains(aboveSide.getType())) {
	                			get = aboveSide;
	                            level = LegacyLevel.EAST_U;
	                		}
                		}
                	}
                }
                break;
            case WEST:
                get = player.getLocation().add(-1, 1, 0).getBlock();
                if (checkSideBreak(get.getType())) {
                    level = LegacyLevel.WEST;
                } else if (checkSideBreak(get.getLocation().add(0, -1, 0).getBlock().getType())) {
                    get = get.getLocation().add(0, -1, 0).getBlock();
                    level = LegacyLevel.WEST_D;
                } else if (LegacyMats.FENCE.contains(get.getLocation().add(0, -2, 0).getBlock().getType())) {
                    get = get.getLocation().add(0, -2, 0).getBlock();
                    level = LegacyLevel.WEST_D_2;
                } else {
                	Block standing = npc.getStandingOn().isEmpty() ? null : npc.getStandingOn().get(0);
                	if(standing == null)
                		break;
                	boolean obstructed = standing.getLocation().getBlockY() == player.getLocation().getBlockY()
                		|| (standing.getLocation().getBlockY() + 1 == player.getLocation().getBlockY()
                		&& (LegacyMats.FENCE.contains(standing.getType()) || LegacyMats.GATES.contains(standing.getType())));
                	if(obstructed) {
                		Block belowStanding = standing.getLocation().add(0, -1, 0).getBlock();
                		if(!LegacyMats.BREAK.contains(belowStanding.getType()) && !LegacyMats.NONSOLID.contains(belowStanding.getType())) {
                			//Break standing block
                			get = standing;
                			level = LegacyLevel.BELOW;
                		} else {
	                		//Break above
	                		Block above = npc.getLocation().add(0, 2, 0).getBlock();
	                		Block aboveSide = get.getLocation().add(0, 1, 0).getBlock();
	                		if(!LegacyMats.BREAK.contains(above.getType())) {
	                			get = above;
	                            level = LegacyLevel.ABOVE;
	                		} else if(!LegacyMats.BREAK.contains(aboveSide.getType())) {
	                			get = aboveSide;
	                            level = LegacyLevel.WEST_U;
	                		}
                		}
                	}
                }
                break;
            default:
                break;
        }

        if (level == LegacyLevel.EAST_D || level == LegacyLevel.WEST_D || level == LegacyLevel.NORTH_D || level == LegacyLevel.SOUTH_D
        		|| level == LegacyLevel.EAST_D_2 || level == LegacyLevel.WEST_D_2 || level == LegacyLevel.NORTH_D_2 || level == LegacyLevel.SOUTH_D_2) {
            if (LegacyMats.AIR.contains(player.getLocation().add(0, 2, 0).getBlock().getType())
                    && LegacyMats.AIR.contains(get.getLocation().add(0, 2, 0).getBlock().getType())
                    && !LegacyMats.FENCE.contains(get.getType()) && !LegacyMats.GATES.contains(get.getType())) {
                return null;
            }
        }
        if (level == LegacyLevel.ABOVE || level == LegacyLevel.BELOW) {
            Block check;

            switch (dir) {
                case NORTH:
                	check = player.getLocation().add(0, 2, -1).getBlock();
                    break;
                case SOUTH:
                	check = player.getLocation().add(0, 2, 1).getBlock();
                    break;
                case EAST:
                	check = player.getLocation().add(1, 2, 0).getBlock();
                    break;
                case WEST:
                	check = player.getLocation().add(-1, 2, 0).getBlock();
                    break;
                default:
                	check = null;
            }
            if (LegacyMats.AIR.contains(player.getLocation().add(0, 2, 0).getBlock().getType())
                && LegacyMats.AIR.contains(check.getType()))
                return null;
        }

        if (level != null) {
        	if (level == LegacyLevel.BELOW) {
                noJump.add(player);
                scheduleTaskLater(() -> {
                	noJump.remove(player);
                }, 15);
                
        		npc.look(BlockFace.DOWN);
        		downMine(npc, player, get);
        	} else if (level == LegacyLevel.ABOVE)
        		npc.look(BlockFace.UP);
            preBreak(npc, player, get, level);
        }

        return level;
    }

    private boolean checkUp(Terminator npc, LivingEntity target, LivingEntity playerNPC, Location loc, boolean c, boolean sameXZ) {
        Location a = playerNPC.getLocation();
        Location b = target.getLocation();

        a.setY(0);
        b.setY(0);

        boolean above = LegacyWorldManager.aboveGround(playerNPC.getLocation());

        BlockFace dir = playerNPC.getFacing();
        Block get;

        switch (dir) {
            case NORTH:
                get = playerNPC.getLocation().add(0, 1, -1).getBlock();
                break;
            case SOUTH:
                get = playerNPC.getLocation().add(0, 1, 1).getBlock();
                break;
            case EAST:
                get = playerNPC.getLocation().add(1, 1, 0).getBlock();
                break;
            case WEST:
                get = playerNPC.getLocation().add(-1, 1, 0).getBlock();
                break;
            default:
                get = null;
        }

        if (get == null || LegacyMats.BREAK.contains(get.getType())) {
            if (a.distance(b) >= 16 && above) return false;
        }

        if (playerNPC.getLocation().getBlockY() < target.getLocation().getBlockY() - 1) {
            Material m0 = playerNPC.getLocation().getBlock().getType();
            Material m1 = playerNPC.getLocation().add(0, 1, 0).getBlock().getType();
            Material m2 = playerNPC.getLocation().add(0, 2, 0).getBlock().getType();

            if (LegacyMats.BREAK.contains(m0) && LegacyMats.BREAK.contains(m1) && LegacyMats.BREAK.contains(m2)) {

                Block place = playerNPC.getLocation().getBlock();

                if (miningAnim.containsKey(playerNPC)) {
                    BukkitRunnable task = miningAnim.get(playerNPC);
                    if (task != null) {
                        task.cancel();
                        taskList.remove(task);
                        miningAnim.remove(playerNPC);
                    }
                }

                npc.look(BlockFace.DOWN);

                // maybe put this in lower if statement onGround()
                if (m0 != Material.WATER)
	                scheduleTaskLater(() -> {
	                    npc.sneak();
	                    npc.punch();
	                    npc.look(BlockFace.DOWN);
	
	                    scheduleTaskLater(() -> {
	                        npc.look(BlockFace.DOWN);
	                    }, 1);
	
	                    blockCheck.placeBlock(npc, playerNPC, place);
	
	                    if (!towerList.containsKey(playerNPC)) {
	                        if (c) {
	                            towerList.put(playerNPC, playerNPC.getLocation());
	                        }
	                    }
	                }, 3);

                if (npc.isBotOnGround()) {
                    if (target.getLocation().distance(playerNPC.getLocation()) < 16) {
                        if (noJump.contains(playerNPC)) {

                            scheduleTaskLater(() -> {
                                npc.setVelocity(new Vector(0, 0.5, 0));
                            }, 1);

                        } else {
                            Vector vector = loc.toVector().subtract(playerNPC.getLocation().toVector()).normalize();
                            npc.stand();

                            Vector move = npc.getVelocity().add(vector);
                            if (move.length() > 1) move = move.normalize();
                            move.multiply(0.1);
                            move.setY(0.5);

                            npc.setVelocity(move);
                            return true;
                        }
                    } else {
                        if (npc.isBotOnGround()) {
                            Location locBlock = playerNPC.getLocation();
                            locBlock.setX(locBlock.getBlockX() + 0.5);
                            locBlock.setZ(locBlock.getBlockZ() + 0.5);

                            Vector vector = locBlock.toVector().subtract(playerNPC.getLocation().toVector());
                            if (vector.length() > 1) vector = vector.normalize();
                            vector.multiply(0.1);
                            vector.setY(0.5);

                            npc.addVelocity(vector);
                            return true;
                        }
                    }
                }

                return false;

            } else if (LegacyMats.BREAK.contains(m0) && LegacyMats.BREAK.contains(m1) && !LegacyMats.BREAK.contains(m2)) {
                Block block = npc.getLocation().add(0, 2, 0).getBlock();
                npc.look(BlockFace.UP);
                preBreak(npc, playerNPC, block, LegacyLevel.ABOVE);

                if (npc.isBotOnGround()) {
                    Location locBlock = playerNPC.getLocation();
                    locBlock.setX(locBlock.getBlockX() + 0.5);
                    locBlock.setZ(locBlock.getBlockZ() + 0.5);

                    Vector vector = locBlock.toVector().subtract(playerNPC.getLocation().toVector());
                    if (vector.length() > 1) vector = vector.normalize();
                    vector.multiply(0.1);
                    vector.setY(0);

                    npc.addVelocity(vector);
                }

                return true;
            } else if (sameXZ && LegacyMats.BREAK.contains(m1)) {
                Block block = npc.getStandingOn().isEmpty() ? null : npc.getStandingOn().get(0);
                if (block != null && block.getLocation().getBlockY() == playerNPC.getLocation().getBlockY()
                	&& !LegacyMats.BREAK.contains(block.getType())) {
                    npc.look(BlockFace.DOWN);

                    downMine(npc, playerNPC, block);
                    preBreak(npc, playerNPC, block, LegacyLevel.BELOW);
                	return true;
                }
            }
        }

        return false;
    }

    private boolean checkDown(Terminator npc, LivingEntity player, Location loc, boolean c) { // possibly a looser check for c

        if (LegacyUtils.checkFreeSpace(npc.getLocation(), loc) || LegacyUtils.checkFreeSpace(player.getEyeLocation(), loc))
            return false;

        if (c && npc.getLocation().getBlockY() > loc.getBlockY() + 1) {
            Block block = npc.getStandingOn().isEmpty() ? null : npc.getStandingOn().get(0);
            if (block == null)
            	return false;
            npc.look(BlockFace.DOWN);

            downMine(npc, player, block);
            preBreak(npc, player, block, LegacyLevel.BELOW);
            return true;
        } else {
            Location a = loc.clone();
            Location b = player.getLocation();

            a.setY(0);
            b.setY(0);

            if (npc.getLocation().getBlockY() > loc.getBlockY() + 10 && a.distance(b) < 10) {
                Block block = npc.getStandingOn().isEmpty() ? null : npc.getStandingOn().get(0);
                if (block == null)
                	return false;
                npc.look(BlockFace.DOWN);

                downMine(npc, player, block);
                preBreak(npc, player, block, LegacyLevel.BELOW);
                return true;

            } else {
                return false;
            }
        }
    }

    private void downMine(Terminator npc, LivingEntity player, Block block) {
        if (!LegacyMats.NO_CRACK.contains(block.getType())) {
            Location locBlock = player.getLocation();
            locBlock.setX(locBlock.getBlockX() + 0.5);
            locBlock.setZ(locBlock.getBlockZ() + 0.5);

            Vector vector = locBlock.toVector().subtract(player.getLocation().toVector());
            if (vector.length() > 1) vector = vector.normalize();
            vector.setY(0);
            vector.multiply(0.1);
            npc.setVelocity(vector);
        }

        if (npc.isBotInWater()) {
            Location locBlock = player.getLocation();
            locBlock.setX(locBlock.getBlockX() + 0.5);
            locBlock.setZ(locBlock.getBlockZ() + 0.5);

            Vector vector = locBlock.toVector().subtract(player.getLocation().toVector());
            if (vector.length() > 1) vector = vector.normalize();
            vector.multiply(0.3);
            vector.setY(-1);

            if (!fallDamageCooldown.contains(npc)) {
                fallDamageCooldown.add(npc);

                scheduleTaskLater(() -> {
                    fallDamageCooldown.remove(npc);
                }, 10);
            }

            npc.setVelocity(vector);
        }
    }

    private boolean checkFenceAndGates(Terminator bot, Block block, LivingEntity player) {
        if (LegacyMats.FENCE.contains(block.getType()) || LegacyMats.GATES.contains(block.getType())) {
            preBreak(bot, player, block, LegacyLevel.AT_D);
            return true;
        }

        return false;
    }
    
    private boolean checkObstacles(Terminator bot, Block block, LivingEntity player) {
        if (LegacyMats.OBSTACLES.contains(block.getType()) || isDoorObstacle(block)) {
            preBreak(bot, player, block, LegacyLevel.AT_D);
            return true;
        }

        return false;
    }
    
    private boolean isDoorObstacle(Block block) {
    	if (block.getType().data == Door.class)
    		return true;
    	if (block.getType().data == TrapDoor.class && ((TrapDoor)block.getBlockData()).isOpen())
    		return true;
    	return false;
    }

    private boolean checkAt(Terminator bot, Block block, LivingEntity player) {
        if (LegacyMats.BREAK.contains(block.getType())) {
            return false;
        } else {
            preBreak(bot, player, block, LegacyLevel.AT);
            return true;
        }
    }

    private void preBreak(Terminator bot, LivingEntity player, Block block, LegacyLevel level) {
        // Previously this method picked an "optimal" pickaxe/axe/shovel for
        // the block and wrote it into the bot's mainhand via bot.setItem(tool).
        // That routed through setItemInMainHand which writes into the SELECTED
        // hotbar slot (almost always slot 0 = the bot's primary weapon), so
        // every mining action silently overwrote the mace / sword / mace-kit
        // loadout. block.breakNaturally() (the actual break call further in
        // this class) doesn't care what the bot is holding, so the tool-swap
        // was purely cosmetic while corrupting the inventory. Removed — the
        // bot now mines with whatever it's currently holding and the block
        // still breaks correctly.

        if (level.isSideDown() || level.isSideDown2()) {
            bot.setBotPitch(69);

            scheduleTaskLater(() -> {
                btCheck.put(player, true);
            }, 5);
        } else if (level.isSideUp()) {
            bot.setBotPitch(-53);
        }else if (level == LegacyLevel.AT_D || level == LegacyLevel.AT) {
            Location blockLoc = block.getLocation().add(0.5, -1, 0.5);
            bot.faceLocation(blockLoc);
        }

        if (!miningAnim.containsKey(player)) {

            BukkitRunnable task = new BukkitRunnable() {

                @Override
                public void run() {
                    if (player.isValid()
                            && bot.getBukkitEntity().getWorld() == player.getWorld()
                            && bot.getLocation().distance(player.getLocation()) <= 5.0) {
                        cancel();
                        taskList.remove(this);
                        miningAnim.remove(player);
                        return;
                    }
                    bot.punch();
                }
            };

            task.runTaskTimer(plugin, 0, 4);
            taskList.add(task);
            miningAnim.put(player, task);
        }

        blockBreakEffect(bot, player, block, new LegacyLevel.LevelWrapper(level));
    }

    private void blockBreakEffect(Terminator bot, LivingEntity player, Block block, LegacyLevel.LevelWrapper wrapper) {

        if (LegacyMats.NO_CRACK.contains(block.getType())) return;

        if (!crackList.containsKey(block)) {
            BukkitRunnable task = new BukkitRunnable() {

                @Override
                public void run() {
                    byte i = mining.get(this);

                    Block cur;
                    if (wrapper.getLevel() == null)
                    	 cur = player.getLocation().add(0, 1, 0).getBlock();
                    else if (wrapper.getLevel() == LegacyLevel.BELOW)
                    	cur = bot.getStandingOn().isEmpty() ? null : bot.getStandingOn().get(0);
                    else
                    	cur = wrapper.getLevel().offset(player.getLocation()).getBlock();

                    // Fix boat clutching while breaking block
                    // As a side effect, the bot is able to break multiple blocks at once while over lava
                    if ((wrapper.getLevel().isSideAt() || wrapper.getLevel().isSideUp())
                    	&& bot.getLocation().add(0, -2, 0).getBlock().getType() == Material.LAVA
                    	&& block.getLocation().clone().add(0, 1, 0).equals(cur.getLocation())) {
                    	cur = block;
                    	wrapper.setLevel(wrapper.getLevel().sideDown());
                    	
                    	if (wrapper.getLevel().isSideDown() || wrapper.getLevel().isSideDown2())
                    		bot.setBotPitch(69);
                    	else if (wrapper.getLevel().isSideUp())
                    		bot.setBotPitch(-53);
                    	else if (wrapper.getLevel().isSide())
                    		bot.setBotPitch(0);
                    }
                    if ((wrapper.getLevel().isSideAt() || wrapper.getLevel().isSideDown())
                    	&& bot.getLocation().add(0, -1, 0).getBlock().getType() == Material.LAVA
                    	&& block.getLocation().clone().add(0, -1, 0).equals(cur.getLocation())) {
                    	cur = block;
                    	wrapper.setLevel(wrapper.getLevel().sideUp());
                    	
                    	if (wrapper.getLevel().isSideDown() || wrapper.getLevel().isSideDown2())
                    		bot.setBotPitch(69);
                    	else if (wrapper.getLevel().isSideUp())
                    		bot.setBotPitch(-53);
                    	else if (wrapper.getLevel().isSide())
                    		bot.setBotPitch(0);
                    }
                    
                    // wow this repeated code is so bad lmao

                    if (player.isDead() || cur == null || (!block.equals(cur) || block.getType() != cur.getType())) {
                        this.cancel();
                        taskList.remove(this);

                        TerminatorPlusAPI.getInternalBridge().sendBlockDestructionPacket(crackList.get(block), block, -1);

                        crackList.remove(block);
                        mining.remove(this);
                        return;
                    }

                    Sound sound = LegacyUtils.breakBlockSound(block);

                    int nextCrack = Math.min(9, i + miningCrackIncrement(bot, block));
                    if (nextCrack >= 9) {
                        this.cancel();
                        taskList.remove(this);

                        TerminatorPlusAPI.getInternalBridge().sendBlockDestructionPacket(crackList.get(block), block, -1);


                        if (sound != null) {
                            for (Player all : Bukkit.getOnlinePlayers())
                                all.playSound(block.getLocation(), sound, SoundCategory.BLOCKS, 1, 1);
                        }

                        block.breakNaturally();

                        if (wrapper.getLevel() == LegacyLevel.ABOVE) {
                            noJump.add(player);

                            scheduleTaskLater(() -> {
                                noJump.remove(player);
                            }, 15);
                        }

                        crackList.remove(block);
                        mining.remove(this);
                        return;
                    }

                    if (sound != null) {
                        for (Player all : Bukkit.getOnlinePlayers())
                            all.playSound(block.getLocation(), sound, SoundCategory.BLOCKS, (float) 0.3, 1);
                    }

                    if (block.getType() == Material.BARRIER || block.getType() == Material.BEDROCK || block.getType() == Material.END_PORTAL_FRAME
                    		|| block.getType() == Material.STRUCTURE_BLOCK
                    		|| block.getType() == Material.COMMAND_BLOCK || block.getType() == Material.REPEATING_COMMAND_BLOCK
                    		|| block.getType() == Material.CHAIN_COMMAND_BLOCK)
                        return;

                    if (LegacyMats.INSTANT_BREAK.contains(block.getType())) { // instant break blocks
                        block.breakNaturally();
                        return;
                    }

                    TerminatorPlusAPI.getInternalBridge().sendBlockDestructionPacket(crackList.get(block), block, nextCrack);

                    mining.put(this, (byte) nextCrack);
                }
            };

            taskList.add(task);
            mining.put(task, (byte) 0);
            crackList.put(block, (short) random.nextInt(2000));
            task.runTaskTimer(plugin, 0, 2);
        }
    }

    private static int miningCrackIncrement(Terminator bot, Block block) {
        Material blockType = block == null ? Material.AIR : block.getType();
        ItemStack held = bot != null && bot.getBukkitEntity() instanceof Player player
                ? player.getInventory().getItemInMainHand()
                : null;
        Material tool = held == null ? Material.AIR : held.getType();
        double hardness = miningHardness(blockType);
        double speed = toolSpeed(tool, blockType);
        int ticksToBreak = (int) Math.ceil(hardness * 18.0 / Math.max(0.25, speed));
        ticksToBreak = Math.max(4, Math.min(80, ticksToBreak));
        return Math.max(1, (int) Math.ceil(10.0 / (ticksToBreak / 2.0)));
    }

    private static double miningHardness(Material material) {
        if (material == null || material == Material.AIR) return 0.1;
        String name = material.name();
        if (LegacyMats.INSTANT_BREAK.contains(material)) return 0.1;
        if (name.contains("OBSIDIAN")) return 50.0;
        if (name.contains("DEEPSLATE")) return 4.5;
        if (name.contains("STONE") || name.contains("COBBLE") || name.contains("BRICK")) return 2.0;
        if (name.contains("DIRT") || name.contains("GRASS") || name.contains("SAND") || name.contains("GRAVEL")) return 0.8;
        if (name.contains("LOG") || name.contains("PLANK") || name.contains("WOOD")) return 1.6;
        if (name.contains("WOOL") || name.contains("LEAVES")) return 0.4;
        return material.isSolid() ? 1.5 : 0.4;
    }

    private static double toolSpeed(Material tool, Material block) {
        if (tool == null || block == null) return 1.0;
        String toolName = tool.name();
        String blockName = block.name();
        boolean pick = blockName.contains("STONE") || blockName.contains("COBBLE") || blockName.contains("ORE")
                || blockName.contains("OBSIDIAN") || blockName.contains("BRICK") || blockName.contains("DEEPSLATE");
        boolean shovel = blockName.contains("DIRT") || blockName.contains("GRASS") || blockName.contains("SAND")
                || blockName.contains("GRAVEL") || blockName.contains("CLAY") || blockName.contains("SNOW");
        boolean axe = blockName.contains("LOG") || blockName.contains("PLANK") || blockName.contains("WOOD")
                || blockName.contains("STEM") || blockName.contains("HYPHAE");
        if (pick && toolName.endsWith("_PICKAXE")) return materialTierSpeed(toolName);
        if (shovel && toolName.endsWith("_SHOVEL")) return materialTierSpeed(toolName);
        if (axe && toolName.endsWith("_AXE")) return materialTierSpeed(toolName);
        if (blockName.contains("WOOL") && toolName.endsWith("_SHEARS")) return 5.0;
        return 1.0;
    }

    private static double materialTierSpeed(String toolName) {
        if (toolName.startsWith("NETHERITE_")) return 9.0;
        if (toolName.startsWith("DIAMOND_")) return 8.0;
        if (toolName.startsWith("IRON_")) return 6.0;
        if (toolName.startsWith("STONE_")) return 4.0;
        if (toolName.startsWith("GOLDEN_")) return 12.0;
        if (toolName.startsWith("WOODEN_")) return 2.0;
        return 1.0;
    }

    private void placeWaterDown(Terminator bot, World world, Location loc) {
        if (loc.getBlock().getType() == Material.WATER) return;

        bot.look(BlockFace.DOWN);
        bot.punch();
        loc.getBlock().setType(Material.WATER);
        world.playSound(loc, Sound.ITEM_BUCKET_EMPTY, 1, 1);

        scheduleTaskLater(() -> {
            Block block = loc.getBlock();

            if (block.getType() == Material.WATER) {
                bot.look(BlockFace.DOWN);
                world.playSound(loc, Sound.ITEM_BUCKET_FILL, 1, 1);
                block.setType(Material.AIR);
            }
        }, 5);
    }

    void miscellaneousChecks(Terminator bot, LivingEntity target) {
        LivingEntity botPlayer = bot.getBukkitEntity();
        World world = botPlayer.getWorld();
        Location loc = bot.getLocation();
        Material placementMaterial = getPlacementMaterial();

        if (bot.isBotOnFire()) {
            if (bot.getDimension() != World.Environment.NETHER) {
                placeWaterDown(bot, world, loc);
            }
        }

        Material atType = loc.getBlock().getType();

        if (atType == Material.FIRE || atType == Material.SOUL_FIRE) {
            if (bot.getDimension() != World.Environment.NETHER) {
                placeWaterDown(bot, world, loc);
                world.playSound(loc, Sound.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 1, 1);
            } else {
                bot.look(BlockFace.DOWN);
                bot.punch();
                world.playSound(loc, Sound.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 1, 1);
                loc.getBlock().setType(Material.AIR);
            }
        }

        if (atType == Material.LAVA) {
            if (bot.getDimension() == World.Environment.NETHER) {
                bot.attemptBlockPlace(loc, placementMaterial, false);
            } else {
                placeWaterDown(bot, world, loc);
            }
        }

        Location head = loc.clone().add(0, 1, 0);
        Material headType = head.getBlock().getType();

        if (headType == Material.LAVA) {
            if (bot.getDimension() == World.Environment.NETHER) {
                bot.attemptBlockPlace(head, placementMaterial, false);
            } else {
                placeWaterDown(bot, world, head);
            }
        }

        if (headType == Material.FIRE || headType == Material.SOUL_FIRE) {
            if (bot.getDimension() == World.Environment.NETHER) {
                bot.look(BlockFace.DOWN);
                bot.punch();
                world.playSound(head, Sound.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 1, 1);
                head.getBlock().setType(Material.AIR);
            } else {
                placeWaterDown(bot, world, head);
            }
        }

        Location under = loc.clone().add(0, -1, 0);
        Material underType = under.getBlock().getType();

        if (underType == Material.FIRE || underType == Material.SOUL_FIRE) {
            Block place = under.getBlock();
            bot.look(BlockFace.DOWN);
            bot.punch();
            world.playSound(under, Sound.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 1, 1);
            place.setType(Material.AIR);
        }

        Location under2 = loc.clone().add(0, -2, 0);
        Material under2Type = under2.getBlock().getType();

        if (under2Type == Material.MAGMA_BLOCK) {
            if (LegacyMats.SPAWN.contains(under2Type)) {
                bot.attemptBlockPlace(under2, placementMaterial, true);
            }
        }

        if (botPlayer.getLocation().getBlockY() <= target.getLocation().getBlockY() + 1) {
            if (!miningAnim.containsKey(botPlayer)) {
                Vector vel = botPlayer.getVelocity();
                double y = vel.getY();

                if (y >= -0.6) {
                    if (loc.clone().add(0, -0.6, 0).getBlock().getType() == Material.WATER
                            && !LegacyMats.NO_CRACK.contains(under2Type)
                            && botPlayer.getEyeLocation().getBlock().getType().isAir()) {

                        Block place = loc.clone().add(0, -1, 0).getBlock();
                        if (LegacyMats.WATER.contains(place.getType())) {
                            bot.attemptBlockPlace(place.getLocation(), placementMaterial, true);
                        }
                    }
                }
            }
        }

        underType = loc.clone().add(0, -0.6, 0).getBlock().getType();

        if (underType == Material.LAVA) {
            if (!boatCooldown.contains(bot)) {
                boatCooldown.add(bot);

                Location place = loc.clone().add(0, -0.1, 0);

                bot.look(BlockFace.DOWN);
                bot.punch();

                Boat boat = (Boat) world.spawnEntity(place, EntityType.OAK_BOAT);

                scheduleTaskLater(() -> {
                    if (!boat.isDead()) {
                        boats.remove(boat);
                        boat.remove();
                    }
                }, 20);

                scheduleTaskLater(() -> {
                    bot.look(BlockFace.DOWN);
                }, 1);

                boats.add(boat);

                Location targetLoc = target.getLocation();

                bot.stand();
                Vector vector = targetLoc.toVector().subtract(bot.getLocation().toVector()).normalize();
                vector.multiply(0.8);

                Vector move = bot.getVelocity().add(vector).setY(0);
                if (move.length() > 1) move = move.normalize();
                move.multiply(0.5);
                move.setY(0.42);
                bot.setVelocity(move);

                scheduleTaskLater(() -> {
                    boatCooldown.remove(bot);
                    if (bot.isBotAlive()) {
                        bot.faceLocation(target.getLocation());
                    }
                }, 5);
            }
        }
    }

    private void resetHand(Terminator npc, LivingEntity target, LivingEntity playerNPC) {
        if (!noFace.contains(npc)) { // LESSLAG if there is no if statement here
            npc.faceLocation(target.getLocation());
        }

        if (miningAnim.containsKey(playerNPC)) {
            BukkitRunnable task = miningAnim.get(playerNPC);
            if (task != null) {
                task.cancel();
                taskList.remove(task);
                miningAnim.remove(playerNPC);
            }
        }

        // Previously: npc.setItem(null) — intended to clear the mining tool
        // back to empty hand, but the mining tool was never actually being
        // placed (see preBreak), and setItem(null) writes AIR/defaultItem
        // into the selected slot, wiping whatever weapon was there.
        // CombatDirector re-selects the correct weapon each tick anyway.
    }

    private boolean onBoat(LivingEntity player) {
        Set<Boat> cache = new HashSet<>();

        boolean check = false;

        for (Boat boat : boats) {
            if (player.getWorld() != boat.getWorld()) continue;

            if (boat.isDead()) {
                cache.add(boat);
                continue;
            }

            if (player.getLocation().distance(boat.getLocation()) < 1) {
                check = true;
                break;
            }
        }

        boats.removeAll(cache);

        return check;
    }

    private void attack(Terminator bot, LivingEntity target, Location loc) {
        if ((target instanceof Player && PlayerUtils.isInvincible(((Player) target).getGameMode())))
            return;

        if (target.getNoDamageTicks() >= 5 || loc.distance(target.getLocation()) >= 4)
            return;

        // Respect vanilla attack-strength charge: a 3-tick swing loop on a sword produces
        // ~25% damage every hit and never crits/sweeps. canSwingAttack also checks i-frames
        // so we don't waste a swing on a target that can't take the hit.
        if (!bot.canSwingAttack(target)) return;

        bot.attack(target);
    }
    
    public void setRegion(BoundingBox region, double regionWeightX, double regionWeightY, double regionWeightZ) {
        this.region = region;
        this.regionWeightX = regionWeightX;
        this.regionWeightY = regionWeightY;
        this.regionWeightZ = regionWeightZ;
    }
    
    public BoundingBox getRegion() {
    	return region;
    }
    
    public double getRegionWeightX() {
    	return regionWeightX;
    }
    
    public double getRegionWeightY() {
    	return regionWeightY;
    }
    
    public double getRegionWeightZ() {
    	return regionWeightZ;
    }
    
    public EnumTargetGoal getTargetType() {
        return goal;
    }

    public void setTargetType(EnumTargetGoal goal) {
        this.goal = goal;
    }

    LivingEntity locateTarget(Terminator bot, Location loc, EnumTargetGoal... targetGoal) {
        LivingEntity result = null;

        EnumTargetGoal g = goal;
        if (targetGoal.length > 0) g = targetGoal[0];
        switch (g) {
            default:
                return null;

            case NEAREST_PLAYER: {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (!botsInPlayerList.contains(player) && validateCloserEntity(player, loc, result)) {
                        result = player;
                    }
                }

                break;
            }

            case NEAREST_VULNERABLE_PLAYER: {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (!botsInPlayerList.contains(player) && !PlayerUtils.isInvincible(player.getGameMode()) && validateCloserEntity(player, loc, result)) {
                        result = player;
                    }
                }

                break;
            }

            case NEAREST_HOSTILE: {
                for (LivingEntity entity : bot.getBukkitEntity().getWorld().getLivingEntities()) {
                    if ((entity instanceof Monster || (customListMode == CustomListMode.HOSTILE && CUSTOM_MOB_LIST.contains(entity.getType()))) && validateCloserEntity(entity, loc, result)) {
                        result = entity;
                    }
                }

                break;
            }

            case NEAREST_RAIDER: {
                for (LivingEntity entity : bot.getBukkitEntity().getWorld().getLivingEntities()) {
                    boolean raider = entity instanceof Raider || (entity instanceof Vex vex && vex.getSummoner() instanceof Raider);
                    if ((raider || (customListMode == CustomListMode.RAIDER && CUSTOM_MOB_LIST.contains(entity.getType()))) && validateCloserEntity(entity, loc, result)) {
                        result = entity;
                    }
                }

                break;
            }

            case NEAREST_MOB: {
                for (LivingEntity entity : bot.getBukkitEntity().getWorld().getLivingEntities()) {
                    if ((entity instanceof Mob || (customListMode == CustomListMode.MOB && CUSTOM_MOB_LIST.contains(entity.getType()))) && validateCloserEntity(entity, loc, result)) {
                        result = entity;
                    }
                }

                break;
            }

            case NEAREST_BOT: {
                for (Terminator otherBot : manager.fetch()) {
                    if (bot != otherBot) {
                        LivingEntity player = otherBot.getBukkitEntity();

                        if (validateCloserEntity(player, loc, result)) {
                            result = player;
                        }
                    }
                }

                break;
            }

            case NEAREST_BOT_DIFFER: {
                String name = bot.getBotName();

                for (Terminator otherBot : manager.fetch()) {
                    if (bot != otherBot) {
                        LivingEntity player = otherBot.getBukkitEntity();

                        if (!name.equals(otherBot.getBotName()) && validateCloserEntity(player, loc, result)) {
                            result = player;
                        }
                    }
                }

                break;
            }

            case NEAREST_BOT_DIFFER_ALPHA: {
                String name = NAME_PATTERN.matcher(bot.getBotName()).replaceAll("");

                for (Terminator otherBot : manager.fetch()) {
                    if (bot != otherBot) {
                        LivingEntity player = otherBot.getBukkitEntity();

                        if (!name.equals(NAME_PATTERN.matcher(otherBot.getBotName()).replaceAll("")) && validateCloserEntity(player, loc, result)) {
                            result = player;
                        }
                    }
                }
                
                break;
            }

            case CUSTOM_LIST: {
                for (LivingEntity entity : bot.getBukkitEntity().getWorld().getLivingEntities()) {
                    if (customListMode == CustomListMode.CUSTOM && CUSTOM_MOB_LIST.contains(entity.getType()) && validateCloserEntity(entity, loc, result)) {
                        result = entity;
                    }
                }

                break;
            }

            case PLAYER: {
                if (bot.getTargetPlayer() != null) {
                    Player player = Bukkit.getPlayer(bot.getTargetPlayer());
                    if (player != null && !botsInPlayerList.contains(player) && validateCloserEntity(player, loc, null)) {
                        result = player;
                    }
                }

                break;
            }
        }
        TerminatorLocateTargetEvent event = new TerminatorLocateTargetEvent(bot, result);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return null;
        return event.getTarget();
    }

    private boolean validateCloserEntity(LivingEntity entity, Location loc, LivingEntity result) {
        double regionDistEntity = getWeightedRegionDist(entity.getLocation());
        if (regionDistEntity == Double.MAX_VALUE)
            return false;
        double regionDistResult = result == null ? 0 : getWeightedRegionDist(result.getLocation());
        return loc.getWorld() == entity.getWorld() && !entity.isDead()
                && (result == null || (loc.distanceSquared(entity.getLocation()) + regionDistEntity) < (loc.distanceSquared(result.getLocation())) + regionDistResult);
    }

    private double getWeightedRegionDist(Location loc) {
        if (region == null)
            return 0;
        double diffX = Math.max(0, Math.abs(region.getCenterX() - loc.getX()) - region.getWidthX() * 0.5);
        double diffY = Math.max(0, Math.abs(region.getCenterY() - loc.getY()) - region.getHeight() * 0.5);
        double diffZ = Math.max(0, Math.abs(region.getCenterZ() - loc.getZ()) - region.getWidthZ() * 0.5);
        if (regionWeightX == 0 && regionWeightY == 0 && regionWeightZ == 0)
            if (diffX > 0 || diffY > 0 || diffZ > 0)
                return Double.MAX_VALUE;
        return diffX * diffX * regionWeightX + diffY * diffY * regionWeightY + diffZ * diffZ * regionWeightZ;
    }

    @Override
    public void stopAllTasks() {
    	super.stopAllTasks();

        // A disabled agent no longer ticks actions, so release any leased
        // movement item immediately instead of leaving it in the bot's hand.
        manager.fetch().forEach(bot -> bot.cancelMovementV2Action("agent-stopped"));

        miningAnim.values().stream()
                .filter(task -> task != null && !task.isCancelled())
                .forEach(BukkitRunnable::cancel);
        miningAnim.clear();

        boats.removeIf(boat -> {
            if (boat != null && !boat.isDead()) {
                boat.remove();
            }
            return true;
        });

        noFace.clear();
        noJump.clear();
        slow.clear();
        btList.clear();
        btCheck.clear();
        towerList.clear();
        stuckTicks.clear();
        stuckLastLoc.clear();
        boatCooldown.clear();
        fallDamageCooldown.clear();
        botsInPlayerList = null;

    	Iterator<Entry<Block, Short>> itr = crackList.entrySet().iterator();
    	while(itr.hasNext()) {
    		Block block = itr.next().getKey();
            TerminatorPlusAPI.getInternalBridge().sendBlockDestructionPacket(crackList.get(block), block, -1);
            itr.remove();
    	}
    	mining.clear();
    }
}
