package net.nuggetmc.tplus.bot;

import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Pair;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.TerminatorPlus;
import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.api.agent.Agent;
import net.nuggetmc.tplus.api.agent.legacyagent.LegacyMats;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.NeuralNetwork;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.movement.CombatTrainingSnapshot;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.movement.MovementTrainingSnapshot;
import net.nuggetmc.tplus.api.event.BotDamageByPlayerEvent;
import net.nuggetmc.tplus.api.event.BotFallDamageEvent;
import net.nuggetmc.tplus.api.event.BotKilledByPlayerEvent;
import net.nuggetmc.tplus.api.utils.*;
import net.nuggetmc.tplus.bot.combat.BotCombatTiming;
import net.nuggetmc.tplus.bot.combat.BotActionState;
import net.nuggetmc.tplus.bot.combat.CombatDirector;
import net.nuggetmc.tplus.bot.combat.CombatDebugger;
import net.nuggetmc.tplus.bot.combat.CombatIntent;
import net.nuggetmc.tplus.bot.combat.LiveDuelMetricsRecorder;
import net.nuggetmc.tplus.bot.combat.MeleeBehavior;
import net.nuggetmc.tplus.bot.combat.CombatState;
import net.nuggetmc.tplus.bot.combat.MovementBranchFamily;
import net.nuggetmc.tplus.bot.combat.MovementState;
import net.nuggetmc.tplus.bot.combat.PlayerLikeActionController;
import net.nuggetmc.tplus.bot.combat.PlayerTraversalActionExecutor;
import net.nuggetmc.tplus.bot.combat.WindChargeMovePlan;
import net.nuggetmc.tplus.bot.loadout.BotInventory;
import net.nuggetmc.tplus.bot.loadout.Cooldowns;
import net.nuggetmc.tplus.bot.movement.MovementOutputApplier;
import net.nuggetmc.tplus.bot.navigation.BukkitNavigationContext;
import net.nuggetmc.tplus.bot.navigation.MovementV2Controller;
import net.nuggetmc.tplus.bot.navigation.MovementV2Planner;
import net.nuggetmc.tplus.bot.navigation.MovementV2Settings;
import net.nuggetmc.tplus.command.commands.BotCommand;
import net.nuggetmc.tplus.nms.MockConnection;
import net.nuggetmc.tplus.utils.NMSUtils;
import net.nuggetmc.tplus.compat.bukkit.*;
import net.nuggetmc.tplus.compat.bukkit.block.Block;
import net.nuggetmc.tplus.compat.bukkit.block.BlockFace;
import net.nuggetmc.tplus.compat.bukkit.block.data.Waterlogged;
import net.nuggetmc.tplus.compat.bukkit.craftbukkit.CraftEquipmentSlot;
import net.nuggetmc.tplus.compat.bukkit.craftbukkit.CraftServer;
import net.nuggetmc.tplus.compat.bukkit.craftbukkit.CraftWorld;
import net.nuggetmc.tplus.compat.bukkit.craftbukkit.entity.CraftPlayer;
import net.nuggetmc.tplus.compat.bukkit.craftbukkit.inventory.CraftItemStack;
import net.nuggetmc.tplus.compat.bukkit.enchantments.Enchantment;
import net.nuggetmc.tplus.compat.bukkit.entity.Damageable;
import net.nuggetmc.tplus.compat.bukkit.entity.EntityBridge;
import net.nuggetmc.tplus.compat.bukkit.entity.Player;
import net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack;
import net.nuggetmc.tplus.compat.bukkit.scheduler.BukkitScheduler;
import net.nuggetmc.tplus.compat.bukkit.scheduler.BukkitTask;
import net.nuggetmc.tplus.compat.bukkit.util.BoundingBox;
import net.nuggetmc.tplus.compat.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Bot extends ServerPlayer implements Terminator {

    private final TerminatorPlus plugin;
    private final BukkitScheduler scheduler;
    private final Agent agent;
    private final Vector offset;
    public ItemStack defaultItem;
    private NeuralNetwork network;
    private boolean shield;
    private boolean blocking;
    private boolean blockUse;
    private Vector velocity;
    private Vector oldVelocity;
    private boolean removeOnDeath;
    private boolean autoRespawnAllowed;
    private boolean removalCleaned;
    private int aliveTicks;
    private int kills;
    private String trainingLoadout = "";
    private double trainingDamageDealt;
    private double trainingDamageTaken;
    private double trainingSwordDamage;
    private double trainingAxeDamage;
    private double trainingMaceDamage;
    private double trainingTridentDamage;
    private double trainingSpearDamage;
    private double trainingProjectileDamage;
    private double trainingExplosiveDamage;
    private int trainingDirectDamageClassifications;
    private int trainingHeldItemDamageClassifications;
    private int trainingLoadoutFallbackDamageClassifications;
    private String lastTrainingDamageBucket = "none";
    private String lastTrainingDamageClassificationSource = "none";
    private byte groundTicks;
    private byte jumpTicks;
    private byte noFallTicks;
    private List<Block> standingOn = new ArrayList<>();
    private UUID targetPlayer = null;
    private boolean inPlayerList;
    private Location respawnAnchor;
    private SkinData skinData;
    private final BotInventory botInventory;
    private final Cooldowns cooldowns;
    private final CombatState combatState;
    private CombatIntent combatIntent;
    private MovementState movementState;
    private final PlayerLikeActionController actionController;
    private final Set<BukkitTask> scheduledTasks;
    private boolean jumpedThisTick;
    private final MovementOutputApplier movementOutputApplier;
    private final MovementV2Controller movementV2Controller;
    private final PlayerTraversalActionExecutor traversalActionExecutor;
    private boolean lastMovementControllerFallback;
    private boolean lastMovementControllerHeld;
    private int lastMovementV2ControlledTick = -1;
    private int lastMovementV2ActionTick = -1;
    /** Pending wind-charge self-boost (aim + fire tick). Null when not planning a throw. */
    public WindChargeMovePlan pendingWindChargePlan;

    private Bot(MinecraftServer minecraftServer, ServerLevel worldServer, GameProfile profile, boolean addToPlayerList) {
        super(minecraftServer, worldServer, profile, ClientInformation.createDefault());

        this.plugin = TerminatorPlus.getInstance();
        this.scheduler = Bukkit.getScheduler();
        this.agent = plugin.getManager().getAgent();
        this.defaultItem = new ItemStack(Material.AIR);
        this.velocity = new Vector(0, 0, 0);
        this.oldVelocity = velocity.clone();
        this.noFallTicks = 60;
        this.removeOnDeath = true;
        this.autoRespawnAllowed = true;
        this.offset = MathUtils.circleOffset(3);
        this.botInventory = new BotInventory(this);
        this.cooldowns = new Cooldowns();
        this.combatState = new CombatState();
        this.combatIntent = CombatIntent.DEFAULT;
        this.movementState = MovementState.DEFAULT;
        this.actionController = new PlayerLikeActionController();
        this.scheduledTasks = ConcurrentHashMap.newKeySet();
        this.movementOutputApplier = new MovementOutputApplier();
        this.movementV2Controller = new MovementV2Controller();
        this.traversalActionExecutor = new PlayerTraversalActionExecutor();
        if (addToPlayerList) {
            minecraftServer.getPlayerList().getPlayers().add(this);
            inPlayerList = true;
        }

    }

    /** Legacy facade used by the preserved AI code; native callers should use this Bot directly. */
    @Override
    public Player getBukkitEntity() {
        return EntityBridge.player(this);
    }

    private static Packet<?> playerInfoPacket(ServerPlayer player, boolean listed) {
        return new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, player);
    }

    public BukkitTask scheduleBotTask(Runnable action, long delayTicks) {
        if (removalCleaned) return null;
        final BukkitTask[] taskRef = new BukkitTask[1];
        BukkitTask task = scheduler.runTaskLater(plugin, () -> {
            try {
                if (!removalCleaned) {
                    action.run();
                }
            } finally {
                if (taskRef[0] != null) {
                    scheduledTasks.remove(taskRef[0]);
                }
            }
        }, delayTicks);
        taskRef[0] = task;
        scheduledTasks.add(task);
        return task;
    }

    private void cancelScheduledTasks() {
        if (scheduledTasks.isEmpty()) return;
        new ArrayList<>(scheduledTasks).stream()
                .filter(task -> !task.isCancelled())
                .forEach(BukkitTask::cancel);
        scheduledTasks.clear();
    }

    public static Bot createBot(Location loc, String name) {
        return createBot(loc, name, SkinData.fromLegacy(MojangAPI.getSkin(name)).orElse(null));
    }

    @Deprecated
    public static Bot createBot(Location loc, String name, String[] skin) {
        return createBot(loc, name, SkinData.fromLegacy(skin).orElse(null));
    }

    public static Bot createBot(Location loc, String name, SkinData skin) {
        return createBot(loc, name, skin, BotUtils.randomSteveUUID(),
                TerminatorPlus.getInstance().getManager().addToPlayerList());
    }

    static Bot createBot(Location loc, String name, SkinData skin, UUID uuid, boolean addPlayerList) {
        MinecraftServer nmsServer = Objects.requireNonNull(Bukkit.getServer().getHandle(), "server");
        ServerLevel nmsWorld = Objects.requireNonNull(loc.getWorld(), "world").getHandle();

        GameProfile profile = CustomGameProfile.create(uuid, ChatUtils.trim16(name), skin);

        Bot bot = new Bot(nmsServer, nmsWorld, profile, addPlayerList);
        bot.skinData = skin;

        bot.connection = new ServerGamePacketListenerImpl(nmsServer, new MockConnection(), bot, CommonListenerCookie.createInitial(bot.getGameProfile(), false));

        bot.setPos(loc.getX(), loc.getY(), loc.getZ());
        bot.setRot(loc.getYaw(), loc.getPitch());
        bot.getBukkitEntity().setNoDamageTicks(0);
        Packet<?> playerInfo = addPlayerList ? playerInfoPacket(bot, true) : null;
        if (playerInfo != null) Bukkit.getOnlinePlayers().forEach(p -> p.getHandle().connection.send(playerInfo));
        if (addPlayerList) {
            nmsWorld.addNewPlayer(bot);
        } else {
            nmsWorld.addFreshEntity(bot);
        }
        bot.renderAll();

        TerminatorPlus.getInstance().getManager().add(bot);

        return bot;
    }

    @Override
    public String getBotName() {
        return displayName;
    }

    @Override
    public int getEntityId() {
        return getId();
    }

    @Override
    public NeuralNetwork getNeuralNetwork() {
        return network;
    }

    @Override
    public void setNeuralNetwork(NeuralNetwork network) {
        this.network = network;
    }

    @Override
    public boolean hasNeuralNetwork() {
        return network != null;
    }

    private void renderAll() {
        this.entityData.set(net.minecraft.world.entity.player.Player.DATA_PLAYER_MODE_CUSTOMISATION, (byte) 0x7F);
        Packet<?>[] packets = getRenderPacketsNoInfo();
        Bukkit.getOnlinePlayers().forEach(p -> {
            ServerGamePacketListenerImpl connection = p.getHandle().connection;
            for (Packet<?> packet : packets) connection.send(packet);
        });
    }

    private void render(ServerGamePacketListenerImpl connection, Packet<?>[] packets, boolean login) {
        connection.send(packets[0]);
        connection.send(packets[1]);

        if (login) {
            scheduleBotTask(() -> connection.send(packets[2]), 10);
        } else {
            connection.send(packets[2]);
        }
    }

    public void render(ServerGamePacketListenerImpl connection, boolean login) {
        render(connection, getRenderPackets(), login);
    }

    @Override
    public void renderBot(Object packetListener, boolean login) {
        if (!(packetListener instanceof ServerGamePacketListenerImpl)) {
            throw new IllegalArgumentException("packetListener must be a instance of ServerGamePacketListenerImpl");
        }
        render((ServerGamePacketListenerImpl) packetListener, login);
    }

    private Packet<?>[] getRenderPackets() {
        return new Packet[]{
                playerInfoPacket(this, inPlayerList),
                new ClientboundSetEntityDataPacket(this.getId(), NMSUtils.getEntityData(this.entityData)),
                new ClientboundRotateHeadPacket(this, (byte) ((this.yHeadRot * 256f) / 360f))
        };
    }

    private Packet<?>[] getRenderPacketsNoInfo() {
        return new Packet[]{
                new ClientboundAddEntityPacket(this.getId(), this.getUUID(), this.getX(), this.getY(), this.getZ(), this.getXRot(), this.getYRot(), this.getType(), 0, this.getDeltaMovement(), this.getYHeadRot()),
                new ClientboundSetEntityDataPacket(this.getId(), this.entityData.packDirty()),
                new ClientboundRotateHeadPacket(this, (byte) ((this.yHeadRot * 256f) / 360f))
        };
    }

    @Override
    public void setDefaultItem(ItemStack item) {
        this.defaultItem = item;
    }

    @Override
    public Vector getOffset() {
        return offset;
    }

    @Override
    public Vector getVelocity() {
        return velocity.clone();
    }

    @Override
    public void setVelocity(Vector vector) {
        this.velocity = vector;
    }

    @Override
    public void addVelocity(Vector vector) {
        if (MathUtils.isNotFinite(vector)) {
            velocity = vector;
            return;
        }

        velocity.add(vector);
    }

    @Override
    public int getAliveTicks() {
        return aliveTicks;
    }

    @Override
    public int getNoFallTicks() {
        return noFallTicks;
    }

    @Override
    public boolean tickDelay(int i) {
        return aliveTicks % i == 0;
    }

    public BotInventory getBotInventory() {
        return botInventory;
    }

    public Cooldowns getBotCooldowns() {
        return cooldowns;
    }

    public CombatState getCombatState() {
        return combatState;
    }

    public CombatIntent getCombatIntent() {
        return combatIntent;
    }

    public void setCombatIntent(CombatIntent combatIntent) {
        this.combatIntent = combatIntent == null ? CombatIntent.DEFAULT : combatIntent;
    }

    public MovementState getMovementState() {
        return movementState;
    }

    public void setMovementState(MovementState movementState) {
        this.movementState = movementState == null ? MovementState.DEFAULT : movementState;
    }

    public PlayerLikeActionController getActionController() {
        return actionController;
    }

    @Override
    public boolean combatTick(net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity target) {
        CombatDirector director = plugin.getCombatDirector();
        if (director == null) return false;
        return director.tick(this, target);
    }

    @Override
    public boolean tickCommittedCombat(net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity target) {
        CombatDirector director = plugin.getCombatDirector();
        if (director == null) return false;
        return director.tickCommitted(this, target);
    }

    @Override
    public boolean usesMovementController() {
        return network != null && network.usesMovementController();
    }

    @Override
    public void planCombat(net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity target) {
        CombatDirector director = plugin.getCombatDirector();
        if (director == null) return;
        director.plan(this, target, getCombatIntent());
    }

    @Override
    public boolean tryMovementControllerMove(net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity target) {
        if (!usesMovementController()) return false;
        MovementOutputApplier.ApplyResult result = movementOutputApplier.tryApply(this, target, network.movementBrainBank());
        lastMovementControllerFallback = result.fallback();
        lastMovementControllerHeld = result.held();
        LiveDuelMetricsRecorder.recordMovementResult(this, result);
        return !result.fallback();
    }

    @Override
    public boolean tryMovementV2Move(net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity target, Location routeTarget) {
        if (!movementV2Enabled()) {
            if (traversalActionExecutor.active()) traversalActionExecutor.cancel(this, "movement-v2-disabled");
            movementV2Controller.reset();
            return false;
        }
        // Training fitness must not silently change when route steering is
        // enabled for ordinary bots.
        if (!trainingLoadout.isBlank()) {
            cancelMovementV2Action("training-bot");
            return false;
        }
        if (target == null || !target.isValid() || routeTarget == null) {
            if (traversalActionExecutor.active()) {
                return continueMovementV2Action();
            }
            cancelMovementV2Action("missing-target");
            return false;
        }
        // The legacy movement shell already owns swimming and climbing. The
        // V2 grid intentionally falls back instead of treating liquids or
        // climbable blocks as ordinary walkable air.
        if (!traversalActionExecutor.active()
                && (isBotInWater() || getLocation().getBlock().isLiquid())) return false;
        if (actionController.active() && !traversalActionExecutor.active()) return false;

        BukkitNavigationContext context = new BukkitNavigationContext(getLocation());
        int buildingBlocks = PlayerTraversalActionExecutor.countBuildingBlocks(botInventory);
        MovementV2Planner.Capabilities capabilities = new MovementV2Planner.Capabilities(
                movementV2Flag("allow-open", true),
                movementV2Flag("allow-place", true) && buildingBlocks > 0,
                movementV2Flag("allow-pillar", true) && buildingBlocks > 0,
                movementV2Flag("allow-parkour", true),
                movementV2Flag("allow-clutch-drop", true)
                        && PlayerTraversalActionExecutor.hasClutchItem(this),
                movementV2Flag("allow-break", true),
                buildingBlocks
        );
        long maxMicros = Math.max(1L, Math.min(50_000L,
                plugin.getConfig().getLong("ai.movement.v2.max-micros", 2000L)));
        MovementV2Planner.Policy policy = new MovementV2Planner.Policy(
                plugin.getConfig().getInt("ai.movement.v2.max-nodes", 2048),
                maxMicros * 1_000L,
                1,
                plugin.getConfig().getInt("ai.movement.v2.max-normal-drop", 3),
                plugin.getConfig().getInt("ai.movement.v2.max-clutch-drop", 48),
                plugin.getConfig().getInt("ai.movement.v2.max-parkour-distance", 4),
                capabilities.canParkour(),
                capabilities.canClutch(),
                capabilities.canPlace(),
                capabilities.canPillar(),
                capabilities.canBreak()
        );

        MovementV2Controller.Decision decision = movementV2Controller.decide(
                getLocation(),
                target.getUniqueId(),
                routeTarget,
                getCombatIntent(),
                getAliveTicks(),
                context,
                capabilities,
                policy
        );

        if (decision.type() == MovementV2Controller.DecisionType.FALLBACK) {
            if (traversalActionExecutor.active()) traversalActionExecutor.cancel(this, decision.reason());
            return false;
        }

        lastMovementV2ControlledTick = getAliveTicks();
        if (decision.type() == MovementV2Controller.DecisionType.HOLD) {
            setSprinting(false);
            walkRoute(new Vector());
            return true;
        }
        if (decision.type() == MovementV2Controller.DecisionType.ACTION) {
            lastMovementV2ActionTick = getAliveTicks();
            PlayerTraversalActionExecutor.Outcome outcome = traversalActionExecutor.tick(this, decision.step(), context);
            movementV2Controller.recordActionResult(outcome.status(), outcome.reason());
            return true;
        }

        if (traversalActionExecutor.active()) traversalActionExecutor.cancel(this, "route-resumed");
        followMovementV2Step(target, decision.step());
        return true;
    }

    private boolean continueMovementV2Action() {
        MovementV2Planner.Step step = traversalActionExecutor.currentStep();
        if (step == null) return false;
        BukkitNavigationContext context = new BukkitNavigationContext(getLocation());
        PlayerTraversalActionExecutor.Outcome outcome = traversalActionExecutor.tick(this, step, context);
        movementV2Controller.recordActionResult(outcome.status(), outcome.reason());
        lastMovementV2ControlledTick = getAliveTicks();
        lastMovementV2ActionTick = getAliveTicks();
        return true;
    }

    public MovementV2Controller.Status movementV2Status() {
        return movementV2Controller.status();
    }

    public boolean movementV2ActionUsedThisTick() {
        return lastMovementV2ActionTick == getAliveTicks();
    }

    @Override
    public boolean movementV2ActionActive() {
        return traversalActionExecutor.active();
    }

    @Override
    public void cancelMovementV2Action(String reason) {
        if (traversalActionExecutor.active()) traversalActionExecutor.cancel(this, reason);
        movementV2Controller.reset();
    }

    private void followMovementV2Step(net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity target, MovementV2Planner.Step step) {
        Location waypoint = new Location(getBukkitEntity().getWorld(),
                step.to().x() + 0.5, step.to().y(), step.to().z() + 0.5);
        Vector delta = waypoint.toVector().subtract(getLocation().toVector());
        Vector desired = delta.clone().setY(0);
        if (desired.lengthSquared() > 1.0e-6) {
            desired.normalize();
            faceMovement(desired);
        }

        if (step.kind() == MovementV2Planner.Kind.PARKOUR && isBotOnGround()) {
            double horizontal = Math.hypot(delta.getX(), delta.getZ());
            int ticks = Math.max(4, (int) Math.ceil(horizontal / 0.42));
            double vy = Math.min(0.42,
                    (delta.getY() + 0.04 * ticks * (ticks - 1)) / ticks);
            Vector launch = new Vector(delta.getX() / ticks, vy, delta.getZ() / ticks);
            setSprinting(true);
            jump(launch);
            return;
        }

        desired.multiply(0.4);
        if (step.kind() == MovementV2Planner.Kind.STEP_UP && isBotOnGround()) {
            setSprinting(true);
            jump(new Vector(desired.getX(), 0.42, desired.getZ()));
            return;
        }

        if (usesMovementController()) {
            MovementOutputApplier.ApplyResult result = movementOutputApplier.tryApply(
                    this, target, network.movementBrainBank(), waypoint);
            lastMovementControllerFallback = result.fallback();
            lastMovementControllerHeld = result.held();
            LiveDuelMetricsRecorder.recordMovementResult(this, result);
            if (!result.fallback()) return;
        }

        setSprinting(true);
        walkRoute(desired);
    }

    private void faceMovement(Vector direction) {
        if (direction != null && direction.lengthSquared() > 1.0e-6) {
            faceLocation(getLocation().clone().add(direction));
        }
    }

    private boolean movementV2Enabled() {
        return MovementV2Settings.isEnabled(plugin);
    }

    private boolean movementV2Flag(String name, boolean fallback) {
        return plugin.getConfig().getBoolean("ai.movement.v2." + name, fallback);
    }

    @Override
    public boolean executePlannedCombat(net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity target) {
        CombatDirector director = plugin.getCombatDirector();
        if (director == null) return false;
        return director.execute(this, target, getMovementState());
    }

    @Override
    public MovementTrainingSnapshot movementTrainingSnapshot(net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity target) {
        if (lastMovementV2ControlledTick == getAliveTicks()) return MovementTrainingSnapshot.unavailable();
        if (target == null || !target.isValid()) return MovementTrainingSnapshot.unavailable();
        CombatIntent intent = getCombatIntent();
        MovementState movement = getMovementState();
        double distance = getLocation().distance(target.getLocation());
        double horizontalDistance = getLocation().toVector()
                .subtract(target.getLocation().toVector())
                .setY(0)
                .length();
        boolean inMeleeRange = distance <= MeleeBehavior.ATTACK_RANGE;
        boolean legalCritSetup = intent.wantsCritSetup()
                && inMeleeRange
                && movement.isFalling()
                && BotCombatTiming.isCritWindow(this)
                && BotCombatTiming.shouldPlanNormalMelee(this, target);
        boolean legalSprintSetup = intent.wantsSprintHit()
                && inMeleeRange
                && movement.isSprinting()
                && BotCombatTiming.shouldPlanSprintReset(this, target);
        boolean holdCompliant = !intent.wantsHoldPosition() || lastMovementControllerHeld;
        return new MovementTrainingSnapshot(
                true,
                distance,
                horizontalDistance,
                intent.desiredRange(),
                intent.rangeUrgency(),
                intent.wantsCritSetup(),
                intent.wantsSprintHit(),
                intent.wantsHoldPosition(),
                intent.isCommitted(),
                intent.commitProgress(),
                movement.isSprinting(),
                movement.justJumped(),
                movement.isFalling(),
                movement.isRetreating(),
                movement.isCircling(),
                movement.approachSpeed(),
                legalCritSetup,
                legalSprintSetup,
                holdCompliant,
                lastMovementControllerFallback,
                activeMovementFamilyId(intent),
                intent.playId(),
                intent.plannedAction(),
                intent.movementLocked(getAliveTicks()),
                intent.lockFamily().id()
        );
    }

    @Override
    public CombatTrainingSnapshot combatTrainingSnapshot() {
        CombatIntent intent = getCombatIntent();
        return new CombatTrainingSnapshot(
                true,
                trainingLoadout,
                CombatTrainingSnapshot.familyForLoadout(trainingLoadout),
                activeMovementFamilyId(intent),
                trainingDamageDealt,
                trainingDamageTaken,
                trainingSwordDamage,
                trainingAxeDamage,
                trainingMaceDamage,
                trainingTridentDamage,
                trainingSpearDamage,
                trainingProjectileDamage,
                trainingExplosiveDamage,
                trainingDirectDamageClassifications,
                trainingHeldItemDamageClassifications,
                trainingLoadoutFallbackDamageClassifications,
                actionController.directShortcutCount(),
                actionController.instantConsumeShortcutCount(),
                actionController.sameTickActionViolations(),
                actionController.interruptionCount(),
                actionController.healCompletionCount(),
                actionController.healCancelCount(),
                LiveDuelMetricsRecorder.snapshot(this),
                lastTrainingDamageBucket,
                lastTrainingDamageClassificationSource
        );
    }

    @Override
    public boolean applyTrainingLoadout(String loadoutName) {
        boolean applied = BotCommand.applyNamedLoadoutToBot(this, loadoutName);
        if (applied) {
            trainingLoadout = loadoutName == null ? "" : loadoutName.trim().toLowerCase(Locale.ROOT);
        }
        return applied;
    }

    private String activeMovementFamilyId(CombatIntent intent) {
        CombatIntent safe = intent == null ? CombatIntent.DEFAULT : intent;
        if (safe.movementLocked(getAliveTicks())
                && safe.lockFamily() != MovementBranchFamily.GENERAL_FALLBACK) {
            return safe.lockFamily().id();
        }
        return safe.branchFamily().id();
    }

    @Override
    public boolean canSwingAttack(net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity target) {
        return net.nuggetmc.tplus.bot.combat.BotCombatTiming.canSwing(this, target);
    }

    /**
     * Change the active hotbar slot and sync the mainhand item. The bot's
     * own inventory (all 41 slots) remains untouched; this only changes
     * which slot is "held".
     */
    public void selectHotbarSlot(int slot) {
        botInventory.setSelectedHotbarSlot(slot);
    }

    private void sendPacket(Packet<?> packet) {
        Bukkit.getOnlinePlayers().forEach(p -> p.getHandle().connection.send(packet));
    }

    /** Re-advertise the selected mainhand after an NMS inventory-slot swap. */
    public void refreshMainHandEquipment() {
        net.minecraft.world.item.ItemStack held = getMainHandItem();
        sendPacket(new ClientboundSetEquipmentPacket(getId(), List.of(
                new Pair<>(EquipmentSlot.MAINHAND, held.copy())
        )));
    }

    @Override
    public boolean isBotAlive() {
        return isAlive();
    }

    @Override
    public float getBotHealth() {
        return getHealth();
    }

    @Override
    public float getBotMaxHealth() {
        return getMaxHealth();
    }

    @Override
    public void tick() {
        loadChunks();

        super.tick();

        if (!isAlive()) return;

        aliveTicks++;

        if (jumpTicks > 0) --jumpTicks;
        if (noFallTicks > 0) --noFallTicks;

        boolean onTheGround = checkGround();
        if (onTheGround) {
            if (groundTicks < 5) groundTicks++;
        } else {
            groundTicks = 0;
        }
        captureRespawnAnchor(onTheGround);
        // Vanilla Player.attack() reads this.fallDistance and this.onGround()
        // to decide whether an attack is a crit (1.5× damage + particles). We
        // override doTick() to skip aiStep(), which is where vanilla advances
        // those fields, so without this block they stay at 0/true forever and
        // every swing reads as a ground-hit. Maintain them from the bot's own
        // velocity and ground tracker so natural falls and mace dives register
        // as vanilla crits, and so mace smashes get their fall-distance bonus.
        this.setOnGround(onTheGround);
        if (onTheGround) {
            this.fallDistance = 0f;
        } else if (velocity.getY() < 0) {
            this.fallDistance += (float) -velocity.getY();
        }

        updateLocation();
        updateMovementState();
        extinguishFromWaterContact();

        if (!isAlive()) return;

        float health = getHealth();
        float maxHealth = getMaxHealth();
        float regenAmount = 0.025f;
        float amount;

        if (health < maxHealth - regenAmount) {
            amount = health + regenAmount;
        } else {
            amount = maxHealth;
        }

        setHealth(amount);

        fallDamageCheck();

        // Every 2s: keep wind charges + ender pearls topped up.
        // (Swords and axes are intentionally NOT auto-tiered — the loadout is authoritative.)
        if (aliveTicks % 40 == 0) {
            botInventory.ensureMovementKit();
        }

        oldVelocity = velocity.clone();

        doTick();
    }

    private void loadChunks() {
        Level world = level();

        for (int i = chunkPosition().x - 1; i <= chunkPosition().x + 1; i++) {
            for (int j = chunkPosition().z - 1; j <= chunkPosition().z + 1; j++) {
                LevelChunk chunk = world.getChunk(i, j);
                markChunkLoaded(chunk);
            }
        }
    }

    /**
     * Flip {@code LevelChunk.loaded} true without a direct field read/write.
     *
     * <p>In Paper 26.1.x the field is package-private and reading it from this
     * plugin package throws {@code IllegalAccessError} as soon as the JIT
     * touches it, so we reflect. The field has been named {@code loaded} since
     * at least 1.17; if a future Paper rename lands, the startup log below
     * surfaces the failure immediately rather than at first-tick.
     */
    private static final java.lang.reflect.Field CHUNK_LOADED_FIELD;

    static {
        java.lang.reflect.Field found = null;
        try {
            found = LevelChunk.class.getDeclaredField("loaded");
            found.setAccessible(true);
        } catch (NoSuchFieldException e) {
            try {
                // Defensive: some mappings expose the same flag as `isClientLightReady`-adjacent.
                found = LevelChunk.class.getSuperclass().getDeclaredField("loaded");
                found.setAccessible(true);
            } catch (NoSuchFieldException ignored) {
                // Intentionally null — loadChunks() will no-op below.
            }
        }
        CHUNK_LOADED_FIELD = found;
    }

    private static void markChunkLoaded(LevelChunk chunk) {
        if (CHUNK_LOADED_FIELD == null) return;
        try {
            if (!CHUNK_LOADED_FIELD.getBoolean(chunk)) {
                CHUNK_LOADED_FIELD.setBoolean(chunk, true);
            }
        } catch (IllegalAccessException ignored) {
            // Can't happen — field was setAccessible(true) at class init.
        }
    }

    @Override
    public UUID getTargetPlayer() {
        return targetPlayer;
    }

    @Override
    public void setTargetPlayer(UUID target) {
        this.targetPlayer = target;
    }

    @Override
    public boolean isBotOnFire() {
        return this.isOnFire();
    }

    private void fallDamageCheck() {
        if (groundTicks != 0 && noFallTicks == 0 && !(oldVelocity.getY() >= -0.8) && isFallBlocked()) {
            actionController.recordDirectShortcut(this, BotActionState.FALL_CLUTCH,
                    "legacy-environment-fall-block", botInventory.getSelectedHotbarSlot());
            CombatDebugger.log(this, "fall-clutch",
                    "src=environment-block vy=" + fmt(oldVelocity.getY())
                            + " held=" + mainhandType()
                            + " maceHotbar=" + botInventory.hasMace());
            return;
        }
        if (groundTicks != 0 && noFallTicks == 0 && !(oldVelocity.getY() >= -0.8)) {
            BotFallDamageEvent event = new BotFallDamageEvent(this, new ArrayList<>(getStandingOn()));

            plugin.getManager().getAgent().onFallDamage(event);

            if (!event.isCancelled()) {
                hurtServer((ServerLevel) level(), damageSources().fall(), (float) Math.pow(3.6, -oldVelocity.getY()));
            }
        }
    }

    private boolean isFallBlocked() {
        AABB box = getBoundingBox();
        double[] xVals = new double[]{
                box.minX,
                box.maxX - 0.01
        };

        double[] zVals = new double[]{
                box.minZ,
                box.maxZ - 0.01
        };
        BoundingBox playerBox = new BoundingBox(box.minX, position().y - 0.01, box.minZ,
                box.maxX, position().y + getBbHeight(), box.maxZ);
        for (double x : xVals) {
            for (double z : zVals) {
                Location loc = new Location(getBukkitEntity().getWorld(), Math.floor(x), getLocation().getY(), Math.floor(z));
                Block block = loc.getBlock();
                if (block.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged())
                    return true;
                if (BotUtils.NO_FALL.contains(loc.getBlock().getType()) && (BotUtils.overlaps(playerBox, loc.getBlock().getBoundingBox())
                        || loc.getBlock().getType() == Material.WATER || loc.getBlock().getType() == Material.LAVA))
                    return true;
            }
        }
        return false;
    }

    @Override
    public boolean isFalling() {
        return velocity.getY() < -0.8;
    }

    @Override
    public void block(int blockLength, int cooldown) {
        if (!shield || blockUse) {
            if (CombatDebugger.isOn(this)) {
                CombatDebugger.log(this, "shield-skip",
                        "reason=" + (!shield ? "disabled" : "cooldown")
                                + " blockUse=" + blockUse
                                + " off=" + offhandType());
            }
            return;
        }
        CombatDebugger.log(this, "shield-request",
                "length=" + blockLength + " cooldown=" + cooldown + " off=" + offhandType());
        startBlocking();
        scheduleBotTask(() -> stopBlocking(cooldown), blockLength);
    }

    private void startBlocking() {
        this.blocking = true;
        this.blockUse = true;
        actionController.recordPrimaryAction(this, BotActionState.BLOCKING, "shield-start", 40);
        CombatDebugger.log(this, "shield-start", "off=" + offhandType());
        startUsingItem(InteractionHand.OFF_HAND);
        sendPacket(new ClientboundSetEntityDataPacket(getId(), entityData.packDirty()));
    }

    private void stopBlocking(int cooldown) {
        boolean wasBlocking = this.blocking;
        this.blocking = false;
        stopUsingItem();
        scheduleBotTask(() -> this.blockUse = false, cooldown);
        CombatDebugger.log(this, "shield-stop",
                "cooldown=" + cooldown + " wasBlocking=" + wasBlocking + " off=" + offhandType());
        sendPacket(new ClientboundSetEntityDataPacket(getId(), entityData.packDirty()));
    }

    @Override
    public boolean isBotBlocking() {
        return isBlocking();
    }

    @Override
    public void setShield(boolean enabled) {
        this.shield = enabled;

        setItemOffhand(new net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack(enabled ? Material.SHIELD : Material.AIR));
    }

    private void updateLocation() {
        double y;

        MathUtils.clean(velocity);

        if (isBotInWater()) {
            y = Math.min(velocity.getY() + 0.1, 0.1);
            addFriction(0.8);
            velocity.setY(y);
        } else {
            if (groundTicks != 0) {
                velocity.setY(0);
                addFriction(0.5);
                y = 0;
            } else {
                y = velocity.getY();
                if(jumpTicks - 3 <= 0) {
                    velocity.setY(Math.max(y - 0.08, -3.5));
                }
            }
        }
        this.move(MoverType.SELF, new Vec3(velocity.getX(), y, velocity.getZ()));
    }

    private void updateMovementState() {
        Vector horizontalVelocity = velocity.clone();
        horizontalVelocity.setY(0);
        movementState = new MovementState(
                isSprinting(),
                jumpedThisTick,
                isFalling(),
                false,
                false,
                horizontalVelocity.length(),
                getLocation().getDirection()
        );
        jumpedThisTick = false;
    }

    private void extinguishFromWaterContact() {
        WaterExtinguishContact contact = waterContactForExtinguish();
        if (!contact.touchingWater()) return;

        int before = getBukkitEntity().getFireTicks();
        if (before <= 0) return;

        getBukkitEntity().setFireTicks(0);
        int after = getBukkitEntity().getFireTicks();
        CombatDebugger.log(this, "bot-extinguish",
                "source=water-contact fireTicks=" + before + "->" + after
                        + " contact=" + contact.id()
                        + " normalWater=" + contact.normalWater()
                        + " waterlogged=" + contact.waterlogged());
    }

    private boolean isTouchingWaterForExtinguish() {
        return waterContactForExtinguish().touchingWater();
    }

    private WaterExtinguishContact waterContactForExtinguish() {
        AABB box = getBoundingBox();
        net.nuggetmc.tplus.compat.bukkit.World world = getBukkitEntity().getWorld();

        int minX = Mth.floor(box.minX + 1.0E-7D);
        int maxX = Mth.floor(box.maxX - 1.0E-7D);
        int minY = Math.max(world.getMinHeight(), Mth.floor(box.minY + 1.0E-7D));
        int maxY = Math.min(world.getMaxHeight() - 1, Mth.floor(box.maxY - 1.0E-7D));
        int minZ = Mth.floor(box.minZ + 1.0E-7D);
        int maxZ = Mth.floor(box.maxZ - 1.0E-7D);
        if (minY > maxY) {
            return WaterExtinguishContact.NONE;
        }

        boolean water = false;
        boolean waterlogged = false;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    Material type = block.getType();
                    if (type == Material.LAVA) {
                        return WaterExtinguishContact.NONE;
                    }
                    if (type == Material.WATER) {
                        water = true;
                    }
                    if (block.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged()) {
                        waterlogged = true;
                    }
                }
            }
        }
        return WaterExtinguishContact.of(water, waterlogged);
    }

    @Override
    public boolean isBotInWater() {
        Location loc = getLocation();

        for (int i = 0; i <= 2; i++) {
            Material type = loc.getBlock().getType();

            if (type == Material.WATER || type == Material.LAVA) {
                return true;
            }

            loc.add(0, 0.9, 0);
        }

        return false;
    }

    private enum WaterExtinguishContact {
        NONE(false, false, "none"),
        WATER(true, false, "water"),
        WATERLOGGED(false, true, "waterlogged"),
        BOTH(true, true, "water+waterlogged");

        private final boolean normalWater;
        private final boolean waterlogged;
        private final String id;

        WaterExtinguishContact(boolean normalWater, boolean waterlogged, String id) {
            this.normalWater = normalWater;
            this.waterlogged = waterlogged;
            this.id = id;
        }

        static WaterExtinguishContact of(boolean normalWater, boolean waterlogged) {
            if (normalWater && waterlogged) return BOTH;
            if (normalWater) return WATER;
            if (waterlogged) return WATERLOGGED;
            return NONE;
        }

        boolean touchingWater() {
            return normalWater || waterlogged;
        }

        boolean normalWater() {
            return normalWater;
        }

        boolean waterlogged() {
            return waterlogged;
        }

        String id() {
            return id;
        }
    }

    @Override
    public void jump(Vector vel) {
        if (jumpTicks == 0 && groundTicks > 1) {
            jumpTicks = 4;
            velocity = vel;
            jumpedThisTick = true;
            if (CombatDebugger.isOn(this)) {
                CombatDebugger.log(this, "move-jump", "accepted vel=" + fmtVec(vel));
            }
            return;
        }
        // Airborne: jump impulse is rejected but LegacyAgent.move uses this
        // to carry horizontal momentum too. Preserve the XZ component via
        // walk() so the bot doesn't lose all forward motion mid-hop.
        if (vel.getX() != 0 || vel.getZ() != 0) {
            if (CombatDebugger.isOn(this)) {
                CombatDebugger.log(this, "move-jump", "redirect-to-walk vel=" + fmtVec(vel));
            }
            walk(new Vector(vel.getX(), 0, vel.getZ()));
            return;
        }
        if (CombatDebugger.isOn(this)) {
            CombatDebugger.log(this, "move-jump", "ignored vel=" + fmtVec(vel));
        }
    }

    @Override
    public void jump() {
        jump(new Vector(0, 0.42, 0));
    }

    @Override
    public void walk(Vector vel) {
        double max = 0.4;

        double y = velocity.getY();
        Vector sum = velocity.clone().setY(0).add(vel.clone().setY(0));
        if (sum.length() > max) sum.normalize().multiply(max);
        sum.setY(y);

        velocity = sum;
    }

    /**
     * Follow a validated route edge without carrying old sideways momentum
     * through a corner. Vertical velocity is preserved for falls and jumps.
     */
    public void walkRoute(Vector vel) {
        double y = velocity.getY();
        Vector routed = vel == null ? new Vector() : vel.clone().setY(0);
        if (routed.length() > 0.4) routed.normalize().multiply(0.4);
        routed.setY(y);
        velocity = routed;
    }

    @Override
    public void attack(net.nuggetmc.tplus.compat.bukkit.entity.Entity entity) {
        faceLocation(entity.getLocation());
        if (actionController.blocksCombatAction()) {
            CombatDebugger.log(this, "attack-skip",
                    "reason=action-busy state=" + actionController.state()
                            + " src=" + actionController.source()
                            + " left=" + actionController.remainingTicks());
            return;
        }
        actionController.recordPrimaryAction(this, BotActionState.MELEE_ATTACK, "bot-attack", botInventory.getSelectedHotbarSlot());

        // Neural-network training relies on the deterministic legacy damage table so fitness
        // scores are reproducible run-to-run. For everyone else, use the vanilla Bukkit attack
        // so sweep attacks, mace fall-damage scaling, enchantments and density bonuses apply.
        if (network != null && !network.usesMovementController()) {
            double before = entityHealth(entity);
            punch();
            double damage = ItemUtils.getLegacyAttackDamage(
                    getBukkitEntity().getInventory().getItemInMainHand());
            if (entity instanceof Damageable) {
                ((Damageable) entity).damage(damage, getBukkitEntity());
            }
            if (CombatDebugger.isOn(this)) {
                double after = entityHealth(entity);
                CombatDebugger.log(this, "attack-result",
                        "mode=legacy crit=false hp=" + fmt(before) + "->" + fmt(after)
                                + " delta=" + fmt(before - after)
                                + " held=" + mainhandType());
            }
            return;
        }

        if (entity instanceof net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity) {
            if (!MeleeBehavior.isMeleeOrEmpty(botInventory.getSelected())) {
                CombatDebugger.log(this, "attack-skip",
                        "reason=non-melee-held held=" + botInventory.getSelected().getType().name());
                return;
            }
            boolean critPred = BotCombatTiming.isCritWindow(this);
            float chargeBefore = getAttackStrengthScale(0.0f);
            double before = entityHealth(entity);
            boolean targetBlocking = entity instanceof net.nuggetmc.tplus.compat.bukkit.entity.Player player && player.isBlocking();
            float chargeAtVanillaAttack = getAttackStrengthScale(0.0f);
            if (CombatDebugger.isOn(this)) {
                CombatDebugger.log(this, "attack-try",
                        "mode=vanilla held=" + mainhandType()
                                + " chargeBefore=" + fmt(chargeBefore)
                                + " chargeAtVanillaAttack=" + fmt(chargeAtVanillaAttack)
                                + " critPred=" + critPred
                                + " fall=" + fmt(fallDistance)
                                + " vy=" + fmt(velocity.getY())
                                + " sprint=" + isSprinting()
                                + " targetBlocking=" + targetBlocking
                                + " targetHp=" + fmt(before));
            }
            getBukkitEntity().attack(entity);
            float chargeAfterVanillaAttack = getAttackStrengthScale(0.0f);
            punch();
            if (CombatDebugger.isOn(this)) {
                double after = entityHealth(entity);
                CombatDebugger.log(this, "attack-result",
                        "mode=vanilla critPred=" + critPred
                                + " chargeBefore=" + fmt(chargeBefore)
                                + " chargeAtVanillaAttack=" + fmt(chargeAtVanillaAttack)
                                + " chargeAfterVanillaAttack=" + fmt(chargeAfterVanillaAttack)
                                + " targetHp=" + fmt(before) + "->" + fmt(after)
                                + " targetHpDelta=" + fmt(before - after)
                                + " targetBlocking=" + targetBlocking
                                + " held=" + mainhandType());
            }
        } else if (entity instanceof Damageable d) {
            double before = entityHealth(entity);
            punch();
            d.damage(1.0, getBukkitEntity());
            if (CombatDebugger.isOn(this)) {
                double after = entityHealth(entity);
                CombatDebugger.log(this, "attack-result",
                        "mode=damageable crit=false hp=" + fmt(before) + "->" + fmt(after)
                                + " delta=" + fmt(before - after)
                                + " held=" + mainhandType());
            }
        }
    }

    @Override
    public void punch() {
        CombatDebugger.punch(this);
        swing(InteractionHand.MAIN_HAND);
    }

    public boolean checkGround() {
        double vy = velocity.getY();

        if (vy > 0) {
            return false;
        }

        return checkStandingOn();
    }

    public boolean checkStandingOn() {
        World world = getBukkitEntity().getWorld();
        AABB box = getBoundingBox();

        double[] xVals = new double[]{
                box.minX,
                box.maxX
        };

        double[] zVals = new double[]{
                box.minZ,
                box.maxZ
        };
        BoundingBox playerBox = new BoundingBox(box.minX, position().y - 0.01, box.minZ,
                box.maxX, position().y + getBbHeight(), box.maxZ);
        List<Block> standingOn = new ArrayList<>();
        List<Location> locations = new ArrayList<>();

        for (double x : xVals) {
            for (double z : zVals) {
                Location loc = new Location(world, x, position().y - 0.01, z);
                Block block = world.getBlockAt(loc);

                if ((LegacyMats.isSolid(block.getType()) || LegacyMats.canStandOn(block.getType())) && BotUtils.overlaps(playerBox, block.getBoundingBox())) {
                    if (!locations.contains(block.getLocation())) {
                        standingOn.add(block);
                        locations.add(block.getLocation());
                    }
                }
            }
        }

        //Fence/wall check
        for (double x : xVals) {
            for (double z : zVals) {
                Location loc = new Location(world, x, position().y - 0.51, z);
                Block block = world.getBlockAt(loc);
                BoundingBox blockBox = loc.getBlock().getBoundingBox();
                BoundingBox modifiedBox = new BoundingBox(blockBox.getMinX(), blockBox.getMinY(), blockBox.getMinZ(), blockBox.getMaxX(),
                        blockBox.getMinY() + 1.5, blockBox.getMaxZ());

                if ((LegacyMats.FENCE.contains(block.getType()) || LegacyMats.GATES.contains(block.getType()))
                        && LegacyMats.isSolid(block.getType()) && BotUtils.overlaps(playerBox, modifiedBox)) {
                    if (!locations.contains(block.getLocation())) {
                        standingOn.add(block);
                        locations.add(block.getLocation());
                    }
                }
            }
        }

        //Closest block comes first
        Collections.sort(standingOn, (a, b) ->
                Double.compare(BotUtils.getHorizSqDist(a.getLocation(), getLocation()), BotUtils.getHorizSqDist(b.getLocation(), getLocation())));

        this.standingOn = standingOn;
        return !standingOn.isEmpty();
    }

    @Override
    public List<Block> getStandingOn() {
        return standingOn;
    }

    @Override
    public boolean isBotOnGround() {
        return groundTicks != 0;
    }

    @Override
    public void addFriction(double factor) {
        double frictionMin = 0.01;

        double x = velocity.getX();
        double z = velocity.getZ();

        velocity.setX(Math.abs(x) < frictionMin ? 0 : x * factor);
        velocity.setZ(Math.abs(z) < frictionMin ? 0 : z * factor);
    }

    @Override
    public void removeVisually() {
        this.removeTab();
        this.setDead();
    }

    @Override
    public void removeBot() {
        if (!Bukkit.isPrimaryThread()) {
            scheduler.runTask(plugin, this::removeBot);
            return;
        }
        if (removalCleaned) return;
        removalCleaned = true;
        cancelScheduledTasks();

        if (plugin.getManager() != null) {
            plugin.getManager().remove(this);
        }
        if (plugin.getCombatDirector() != null) {
            plugin.getCombatDirector().cleanupBot(getUUID());
        }
        CombatDebugger.disable(getUUID());
        traversalActionExecutor.cancel(this, "bot-removed");
        movementV2Controller.reset();
        MovementOutputApplier.clearBot(getUUID());
        LiveDuelMetricsRecorder.clearBot(getUUID());

        this.remove(RemovalReason.DISCARDED);
        this.removeVisually();
        if (inPlayerList) {
            Bukkit.getServer().getHandle().getPlayerList().getPlayers().remove(this);
            inPlayerList = false;
        }
    }

    private void removeTab() {
        sendPacket(new ClientboundPlayerInfoRemovePacket(Arrays.asList(this.getUUID())));
    }

    public void setRemoveOnDeath(boolean enabled) {
        this.removeOnDeath = enabled;
    }

    @Override
    public boolean isAutoRespawnAllowed() {
        return autoRespawnAllowed;
    }

    @Override
    public void setAutoRespawnAllowed(boolean allowed) {
        this.autoRespawnAllowed = allowed;
        if (!allowed && plugin.getManager() != null) {
            plugin.getManager().cancelPendingRespawn(getUUID());
        }
    }

    private void captureRespawnAnchor(boolean onTheGround) {
        if (respawnAnchor != null || !onTheGround) return;
        respawnAnchor = RespawnSafety.captureAnchor(
                null,
                getLocation(),
                true,
                RespawnSafety::isSafeGrounded
        );
    }

    Location respawnAnchor() {
        return respawnAnchor == null ? null : respawnAnchor.clone();
    }

    public void setRespawnAnchor(Location location) {
        respawnAnchor = location == null ? null : location.clone();
    }

    SkinData skinData() {
        return skinData;
    }

    boolean hasShieldEnabled() {
        return shield;
    }

    void restoreShieldFlag(boolean enabled) {
        shield = enabled;
    }

    void restoreKills(int kills) {
        this.kills = Math.max(0, kills);
    }

    String trainingLoadout() {
        return trainingLoadout;
    }

    void restoreTrainingLoadout(String trainingLoadout) {
        this.trainingLoadout = trainingLoadout == null ? "" : trainingLoadout;
    }

    private void setDead() {
        sendPacket(new ClientboundRemoveEntitiesPacket(getId()));

        this.dead = true;
        this.inventoryMenu.removed(this);
        if (this.containerMenu != null) {
            this.containerMenu.removed(this);
        }
    }

    private void dieCheck() {
        if (removeOnDeath) {

            if (!plugin.getManager().hasPendingRespawn(getUUID())) {
                plugin.getManager().remove(this);
            }

            scheduleBotTask(this::removeBot, 20);

            this.removeTab();
        }
    }

    @Override
    public void die(DamageSource damageSource) {
        cancelMovementV2Action("bot-died");
        if (plugin.getManager() != null) {
            try {
                plugin.getManager().prepareRespawn(this);
            } catch (RuntimeException error) {
                plugin.getLogger().warning("Could not capture respawn state for " + getBotName()
                        + ": " + error.getMessage());
            }
        }
        super.die(damageSource);
        this.dieCheck();
    }

    @Override
    public void push(Entity entity) {
        if (!this.isPassengerOfSameVehicle(entity) && !entity.noPhysics && !this.noPhysics) {
            double d0 = entity.getX() - this.getX();
            double d1 = entity.getZ() - this.getZ();
            double d2 = Mth.absMax(d0, d1);
            if (d2 >= 0.009999999776482582D) {
                d2 = Math.sqrt(d2);
                d0 /= d2;
                d1 /= d2;
                double d3 = 1.0D / d2;
                if (d3 > 1.0D) {
                    d3 = 1.0D;
                }

                d0 *= d3;
                d1 *= d3;
                d0 *= 0.05000000074505806D;
                d1 *= 0.05000000074505806D;

                if (!this.isVehicle()) {
                    velocity.add(new Vector(-d0, 0.0D, -d1));
                }

                if (!entity.isVehicle()) {
                    entity.push(d0, 0.0D, d1);
                }
            }
        }
    }

    public boolean hurtServer(ServerLevel worldServer, DamageSource damagesource, float f) {
        Entity attacker = damagesource.getEntity();

        float damage;

        boolean playerInstance = attacker instanceof ServerPlayer;

        Player killer;

        if (playerInstance) {
            killer = EntityBridge.player((ServerPlayer) attacker);

            BotDamageByPlayerEvent event = new BotDamageByPlayerEvent(this, killer, f);

            agent.onPlayerDamage(event);

            if (event.isCancelled()) {
                CombatDebugger.log(this, "damage",
                        "raw=" + fmt(f) + " applied=0.00 cancelled=true blocking=" + blocking
                                + " src=" + damageSourceToken(damagesource)
                                + " attacker=" + attackerToken(attacker));
                return false;
            }

            damage = event.getDamage();
        } else {
            killer = null;
            damage = f;
        }

        float beforeHp = getHealth();
        boolean damaged = super.hurt(damagesource, damage);
        float afterHp = getHealth();
        if (damaged) {
            double actualDamage = Math.max(0.0, beforeHp - afterHp);
            if (actualDamage > 0.0) {
                trainingDamageTaken += actualDamage;
                LiveDuelMetricsRecorder.recordDamageTaken(this, actualDamage);
                recordAttackerTrainingDamage(damagesource, attacker, actualDamage);
            }
        }

        if (CombatDebugger.isOn(this)) {
            CombatDebugger.log(this, "damage",
                    "raw=" + fmt(f)
                            + " applied=" + fmt(damage)
                            + " hp=" + fmt(beforeHp) + "->" + fmt(afterHp)
                            + " damaged=" + damaged
                            + " blocked=" + (!damaged && blocking)
                            + " blocking=" + blocking
                            + " src=" + damageSourceToken(damagesource)
                            + " attacker=" + attackerToken(attacker));
        }

        if (!damaged && blocking) {
            getBukkitEntity().getWorld().playSound(getLocation(), Sound.ITEM_SHIELD_BLOCK, 1, 1);
        }

        if (damaged && attacker != null) {
            if (playerInstance && !isAlive()) {
                agent.onBotKilledByPlayer(new BotKilledByPlayerEvent(this, killer));

            } else {
                kb(getLocation(), EntityBridge.wrap(attacker).getLocation(), attacker);
            }
        }

        return damaged;
    }

    private void recordAttackerTrainingDamage(DamageSource source, Entity attacker, double amount) {
        if (!(attacker instanceof ServerPlayer player)) return;
        Terminator terminator = plugin.getManager().getBot(EntityBridge.player(player));
        if (terminator instanceof Bot bot && bot != this) {
            bot.recordTrainingDamageDealt(source, attacker, amount);
        }
    }

    private void recordTrainingDamageDealt(DamageSource source, Entity attacker, double amount) {
        trainingDamageDealt += amount;
        LiveDuelMetricsRecorder.recordDamageDealt(this, amount);
        TrainingDamageClassification classification = classifyTrainingDamage(source, attacker);
        switch (classification.bucket()) {
            case "sword" -> trainingSwordDamage += amount;
            case "axe" -> trainingAxeDamage += amount;
            case "mace" -> trainingMaceDamage += amount;
            case "trident" -> trainingTridentDamage += amount;
            case "spear" -> trainingSpearDamage += amount;
            case "explosive" -> trainingExplosiveDamage += amount;
            case "projectile" -> trainingProjectileDamage += amount;
            default -> {
            }
        }
        switch (classification.classificationSource()) {
            case "direct" -> trainingDirectDamageClassifications++;
            case "held" -> trainingHeldItemDamageClassifications++;
            case "loadout-fallback" -> trainingLoadoutFallbackDamageClassifications++;
            default -> {
            }
        }
        lastTrainingDamageBucket = classification.bucket();
        lastTrainingDamageClassificationSource = classification.classificationSource();
        if (CombatDebugger.isOn(this)) {
            CombatDebugger.trainingDamage(this, amount, classification.bucket(), classification.classificationSource(),
                    classification.directType(), classification.heldType(), trainingLoadout);
        }
    }

    private TrainingDamageClassification classifyTrainingDamage(DamageSource source, Entity attacker) {
        Entity direct = source == null ? null : source.getDirectEntity();
        String directType = direct == null ? "" : EntityBridge.wrap(direct).getType().name();
        if (directType.contains("TRIDENT")) return trainingDamage("trident", "direct", directType, heldMaterial(attacker).name());
        if (directType.contains("CRYSTAL") || directType.contains("TNT") || directType.contains("EXPLOSIVE")) {
            return trainingDamage("explosive", "direct", directType, heldMaterial(attacker).name());
        }
        if (directType.contains("ARROW") || directType.contains("FIREBALL") || directType.contains("WIND_CHARGE")) {
            return trainingDamage("projectile", "direct", directType, heldMaterial(attacker).name());
        }

        Material held = heldMaterial(attacker);
        if (held == Material.MACE) return trainingDamage("mace", "held", directType, held.name());
        if (held == Material.TRIDENT) return trainingDamage(tridentTrainingType(), tridentClassificationSource(), directType, held.name());
        String name = held.name();
        if (name.endsWith("_SWORD")) return trainingDamage("sword", "held", directType, held.name());
        if (name.endsWith("_AXE")) return trainingDamage("axe", "held", directType, held.name());
        return trainingDamage("other", "unknown", directType, held.name());
    }

    private String tridentTrainingType() {
        return "spear".equals(trainingLoadout) ? "spear" : "trident";
    }

    private String tridentClassificationSource() {
        return "spear".equals(trainingLoadout) ? "loadout-fallback" : "held";
    }

    private static Material heldMaterial(Entity attacker) {
        if (attacker instanceof ServerPlayer player) {
            return EntityBridge.player(player).getInventory().getItemInMainHand().getType();
        }
        return Material.AIR;
    }

    private static TrainingDamageClassification trainingDamage(String bucket, String classificationSource, String directType, String heldType) {
        return new TrainingDamageClassification(
                bucket == null || bucket.isBlank() ? "other" : bucket,
                classificationSource == null || classificationSource.isBlank() ? "unknown" : classificationSource,
                directType == null || directType.isBlank() ? "none" : directType,
                heldType == null || heldType.isBlank() ? "AIR" : heldType
        );
    }

    private record TrainingDamageClassification(
            String bucket,
            String classificationSource,
            String directType,
            String heldType
    ) {}

    private void kb(Location loc1, Location loc2, Entity attacker) {
        Vector vel = loc1.toVector().subtract(loc2.toVector()).setY(0).normalize().multiply(0.3);

        if (isBotOnGround()) vel.multiply(0.8).setY(0.4);
        if (EntityBridge.wrap(attacker) instanceof Player attackerPlayer && attackerPlayer.getInventory().getItemInMainHand().getItemMeta() != null) {
            if (attackerPlayer.getInventory().getItemInMainHand().getItemMeta().hasEnchant(Enchantment.KNOCKBACK)) {
                int kbLevel = attackerPlayer.getInventory().getItemInMainHand().getItemMeta().getEnchants().get(Enchantment.KNOCKBACK);
                if (kbLevel == 1) {
                    vel.multiply(1.05).setY(.4);
                } else {
                    vel.multiply(1.9).setY(.4);
                }
            }
        }
        velocity = vel;
    }

    @Override
    public int getKills() {
        return kills;
    }

    @Override
    public void incrementKills() {
        kills++;
    }

    @Override
    public Location getLocation() {
        return getBukkitEntity().getLocation();
    }

    @Override
    public BoundingBox getBotBoundingBox() {
        return getBukkitEntity().getBoundingBox();
    }

    @Override
    public void setBotPitch(float pitch) {
        super.setXRot(pitch);
    }

    @Override
    public void faceLocation(Location loc) {
        look(loc.toVector().subtract(getLocation().toVector()), false);
    }

    @Override
    public void look(BlockFace face) {
        look(face.getDirection(), face == BlockFace.DOWN || face == BlockFace.UP);
    }

    private void look(Vector dir, boolean keepYaw) {
        float yaw, pitch;

        if (keepYaw) {
            yaw = this.getYRot();
            pitch = MathUtils.fetchPitch(dir);
        } else {
            float[] vals = MathUtils.fetchYawPitch(dir);
            yaw = vals[0];
            pitch = vals[1];
        }

        setRot(yaw, pitch);
        setYHeadRot(yaw);
        yBodyRot = yaw;
        if (!keepYaw) {
            sendPacket(new ClientboundRotateHeadPacket(this, (byte) (yaw * 256 / 360f)));
        }
    }

    @Override
    public void attemptBlockPlace(Location loc, Material type, boolean down) {
        if (down) {
            look(BlockFace.DOWN);
        } else {
            faceLocation(loc);
        }

        punch();

        Block block = loc.getBlock();
        World world = loc.getWorld();

        if (!LegacyMats.isSolid(block.getType())) {
            CombatDebugger.blockPlace(this, "bot-attemptBlockPlace", type, block, block.getType());
            block.setType(type);
            if (world != null) world.playSound(loc, Sound.BLOCK_STONE_PLACE, SoundCategory.BLOCKS, 1, 1);
        }
    }

    @Override
    public void setItem(net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack item) {
        setItem(item, EquipmentSlot.MAINHAND);
    }

    @Override
    public void setItemOffhand(net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack item) {
        setItem(item, EquipmentSlot.OFFHAND);
    }

    @Override
    public void setItem(ItemStack item, net.nuggetmc.tplus.compat.bukkit.inventory.EquipmentSlot slot) {
        EquipmentSlot nmsSlot = CraftEquipmentSlot.getNMS(slot);
        setItem(item, nmsSlot);
    }

    public void setItem(net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack item, EquipmentSlot slot) {
        if (item == null) item = defaultItem;

        net.nuggetmc.tplus.compat.bukkit.inventory.PlayerInventory inv = getBukkitEntity().getInventory();
        if (slot == EquipmentSlot.MAINHAND) {
            // CRITICAL: vanilla Player.tick detects main-hand item changes by REFERENCE
            // (getMainHandItem() != lastItemInMainHand) and calls resetAttackStrengthTicker()
            // on any mismatch. Our callers (fallDamageCheck, attemptBlockPlace, autoEquip,
            // selectHotbarSlot, clutch tools) routinely pass a brand-new ItemStack that is
            // content-equal to what's already in the hand; without a content-equality skip
            // here, every one of those calls pins the bot's attack charge near zero so
            // canSwing's 0.95 gate never passes and the bot never crits. The log at
            // t=272..292 shows this exact pattern — a successful 1.00-charge hit followed by
            // charge resets to 0.00 every few ticks as fallDamageCheck writes WATER_BUCKET
            // (and similar paths write cobblestone / bucket / clutch items) on each tick.
            //
            // We compare via the NMS net.minecraft.world.item.ItemStack.matches on the
            // asNMSCopy of both the incoming stack and the current hand. Same content ->
            // skip the setItemInMainHand call AND skip the ClientboundSetEquipmentPacket
            // (clients already believe the slot holds this item, because they got the
            // packet for the first write).
            net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack existing = inv.getItemInMainHand();
            net.minecraft.world.item.ItemStack incomingNms = CraftItemStack.asNMSCopy(item);
            net.minecraft.world.item.ItemStack existingNms = CraftItemStack.asNMSCopy(existing);
            if (net.minecraft.world.item.ItemStack.matches(existingNms, incomingNms)) {
                return;
            }
            inv.setItemInMainHand(item);
            sendPacket(new ClientboundSetEquipmentPacket(getId(), new ArrayList<>(Collections.singletonList(
                    new Pair<>(slot, incomingNms)
            ))));
            return;
        }
        if (slot == EquipmentSlot.OFFHAND) {
            inv.setItemInOffHand(item);
        } else if (slot == EquipmentSlot.HEAD) {
            inv.setHelmet(item);
        } else if (slot == EquipmentSlot.CHEST) {
            inv.setChestplate(item);
        } else if (slot == EquipmentSlot.LEGS) {
            inv.setLeggings(item);
        } else if (slot == EquipmentSlot.FEET) {
            inv.setBoots(item);
        }

        sendPacket(new ClientboundSetEquipmentPacket(getId(), new ArrayList<>(Collections.singletonList(
                new Pair<>(slot, CraftItemStack.asNMSCopy(item))
        ))));
    }

    @Override
    public void swim() {
        getBukkitEntity().setSwimming(true);
    }

    @Override
    public void sneak() {
        getBukkitEntity().setSneaking(true);
    }

    @Override
    public void stand() {
        Player player = getBukkitEntity();
        player.setSneaking(false);
        player.setSwimming(false);
    }

    public void doTick() {
        detectEquipmentUpdates();
        baseTick();
        // Vanilla Player.aiStep() normally advances this each tick; we skip aiStep
        // to avoid hunger/ability mutations on bots, but BotCombatTiming reads the
        // resulting attack-strength scale as the gate for full-damage/crit swings.
        // Without this, the scale stays pinned at 0 and no swing ever lands.
        this.attackStrengthTicker++;
    }

    private void detectEquipmentUpdates() {
        // Native ServerPlayer synchronizes equipment during its normal tick. This
        // no-op preserves the old Paper hook used by the ported tick routine.
    }

    @Override
    public boolean isInPlayerList() {
        return inPlayerList;
    }

    @Override
    public World.Environment getDimension() {
        return getBukkitEntity().getWorld().getEnvironment();
    }

    private String mainhandType() {
        ItemStack held = getBukkitEntity().getInventory().getItemInMainHand();
        return held == null ? "AIR" : held.getType().name();
    }

    private String offhandType() {
        ItemStack held = getBukkitEntity().getInventory().getItemInOffHand();
        return held == null ? "AIR" : held.getType().name();
    }

    private static double entityHealth(net.nuggetmc.tplus.compat.bukkit.entity.Entity entity) {
        if (entity instanceof Damageable damageable) {
            try {
                return Math.max(0.0, damageable.getHealth());
            } catch (IllegalStateException ignored) {
                return 0.0;
            }
        }
        return -1.0;
    }

    private static String damageSourceToken(DamageSource source) {
        return safeToken(source == null ? "unknown" : source.toString());
    }

    private static String attackerToken(Entity attacker) {
        if (attacker == null) return "none";
        try {
            net.nuggetmc.tplus.compat.bukkit.entity.Entity bukkit = EntityBridge.wrap(attacker);
            if (bukkit == null) return attacker.getType().toString();
            return bukkit.getType().name() + ":" + safeToken(bukkit.getName());
        } catch (RuntimeException ignored) {
            return safeToken(attacker.getType().toString());
        }
    }

    private static String safeToken(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.replaceAll("\\s+", "_").replaceAll("[^A-Za-z0-9_.:-]", "_");
    }

    private static String fmt(double value) {
        return String.format("%.2f", value);
    }

    private static String fmtVec(Vector vec) {
        return fmt(vec.getX()) + "," + fmt(vec.getY()) + "," + fmt(vec.getZ());
    }
}
