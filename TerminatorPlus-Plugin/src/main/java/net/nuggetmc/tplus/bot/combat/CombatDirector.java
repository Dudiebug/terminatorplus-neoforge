package net.nuggetmc.tplus.bot.combat;

import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.loadout.BotInventory;
import net.nuggetmc.tplus.compat.bukkit.Location;
import net.nuggetmc.tplus.compat.bukkit.Material;
import net.nuggetmc.tplus.compat.bukkit.World;
import net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity;

import java.util.UUID;

/**
 * Per-tick combat decision maker. Given a bot and its current target, picks the
 * best weapon and delegates the actual move to the matching behavior.
 */
public final class CombatDirector {

    private final MeleeBehavior melee = new MeleeBehavior();
    private final TridentBehavior trident = new TridentBehavior();
    private final MaceBehavior mace = new MaceBehavior();
    private final WindChargeBehavior windCharge = new WindChargeBehavior();
    private final ElytraBehavior elytra = new ElytraBehavior();
    private final EnderPearlBehavior pearl = new EnderPearlBehavior();
    private final TotemBehavior totem = new TotemBehavior();
    private final CrystalBehavior crystal = new CrystalBehavior();
    private final AnchorBombBehavior anchor = new AnchorBombBehavior();
    private final UtilityBehavior utility = new UtilityBehavior();
    private final ConsumableBehavior consumable = new ConsumableBehavior();
    private final ComboBehavior combo = new ComboBehavior();
    private final OpportunityScanner scanner = new OpportunityScanner();
    private final CombatSnapshot snapshot = new CombatSnapshot();

    private static final String ACTION_NONE = "none";
    private static final String ACTION_MACE_CHARGING = "director:mace_charging";
    private static final String ACTION_AIRBORNE_MACE = "director:airborne_mace";
    private static final String ACTION_TRIDENT_CHARGING = "director:trident_charging";
    private static final String ACTION_COMBO = "director:combo";
    private static final String ACTION_AERIAL_DIVE = "director:aerial_dive";
    private static final String ACTION_SHIELD_AXE = "director:shield_axe";
    private static final String ACTION_CRYSTAL = "director:crystal";
    private static final String ACTION_ANCHOR = "director:anchor";
    private static final String ACTION_MACE_SMASH = "director:mace_smash";
    private static final String ACTION_MELEE = "director:melee";
    private static final String ACTION_TRIDENT = "director:trident";
    private static final String ACTION_PEARL = "director:pearl";
    private static final String ACTION_COBWEB = "director:cobweb";
    private static final String ACTION_SCANNER_PREFIX = "scanner:";

    public void cleanupBot(UUID botId) {
        combo.clear(botId);
    }

    public boolean tick(Bot bot, LivingEntity target) {
        if (target == null || !target.isValid()) return false;
        plan(bot, target, bot.getCombatIntent());
        // NN bots route combat through executePlannedCombat() inside move(), which is
        // gated on side/ground checks that fail mid-launch. Committed weapon phases
        // (mace charge/airborne, trident charge) must keep ticking or the smash fires
        // after the bot has landed. The per-tick guard in execute() blocks double-fire.
        if (bot.hasNeuralNetwork() && bot.getCombatState().getPhase() == CombatState.Phase.IDLE) {
            return false;
        }

        return execute(bot, target, bot.getMovementState());
    }

    /**
     * Ticks only already-committed weapon phases. This is intentionally narrower
     * than execute(): no scanner, consumables, idle melee, or utility branches.
     */
    public boolean tickCommitted(Bot bot, LivingEntity target) {
        if (target == null || !target.isValid()) return false;
        CombatState.Phase phase = bot.getCombatState().getPhase();
        if (!isCommittedPhase(phase)) {
            CombatDebugger.log(bot, "commit-skip", "reason=idle phase=" + phase);
            return false;
        }

        BotInventory inv = bot.getBotInventory();
        if (!hasCommittedWeapon(phase, inv)) {
            CombatDebugger.log(bot, "commit-skip", "reason=missing-weapon phase=" + phase);
            return false;
        }

        int alive = bot.getAliveTicks();
        if (!bot.getCombatState().markExecuted(alive)) {
            CombatDebugger.log(bot, "commit-skip", "reason=already-executed phase=" + phase + " source=early");
            return true;
        }

        double distance = bot.getLocation().distance(target.getLocation());
        return executeCommittedPhase(bot, target, distance, inv, "early", false);
    }

    /**
     * Writes Director -> MovementNetwork hints only. This method must not choose
     * attacks, mutate loadouts, or bypass CombatDirector execution gates.
     */
    public CombatIntent plan(Bot bot, LivingEntity target, CombatIntent previousIntent) {
        if (target == null || !target.isValid()) {
            bot.setCombatIntent(CombatIntent.DEFAULT);
            return bot.getCombatIntent();
        }

        double distance = bot.getLocation().distance(target.getLocation());
        LiveDuelMetricsRecorder.recordTick(bot, target, distance);
        BotInventory inv = bot.getBotInventory();
        CombatState.Phase phase = bot.getCombatState().getPhase();
        snapshot.update(bot, target);
        boolean meleeRange = distance <= MeleeBehavior.ATTACK_RANGE;
        boolean canSpecial = BotCombatTiming.shouldPlanSprintReset(bot, target);
        boolean wantsCritSetup = meleeRange && BotCombatTiming.readyForVanillaSpecial(bot)
                && !BotCombatTiming.isCritWindow(bot)
                && !BotCombatTiming.targetHasIFrames(target);
        boolean wantsSprintHit = meleeRange && canSpecial;

        CombatIntent intent = switch (phase) {
            case MACE_CHARGING -> buildIntent(bot, target, distance, MovementBranchFamily.MACE, "",
                    DesiredRangeBand.MELEE, ACTION_MACE_CHARGING, false, 40,
                    "mace_charging", wantsCritSetup, wantsSprintHit, false, true,
                    MeleeBehavior.ATTACK_RANGE);
            case AIRBORNE -> buildIntent(bot, target, distance, MovementBranchFamily.MACE, "",
                    DesiredRangeBand.MELEE, ACTION_AIRBORNE_MACE, false, 40,
                    "mace_airborne", wantsCritSetup, wantsSprintHit, false, true,
                    MeleeBehavior.ATTACK_RANGE);
            case CHARGING -> buildIntent(bot, target, distance, MovementBranchFamily.TRIDENT_RANGED, "",
                    DesiredRangeBand.MID, ACTION_TRIDENT_CHARGING, false, 40,
                    "trident_charging", false, false, true, true,
                    TridentBehavior.MAX_DISTANCE);
            case RELEASE, IDLE -> planIdle(bot, target, inv, distance, wantsCritSetup, wantsSprintHit);
        };
        bot.setCombatIntent(intent);
        if (CombatDebugger.isOn(bot)) {
            CombatDebugger.log(bot, "intent", intent.debugSummary());
        }
        return intent;
    }

    /**
     * Executes legal combat actions. MovementState is observed movement context,
     * not permission to attack; CombatDirector still owns timing and legality.
     */
    public boolean execute(Bot bot, LivingEntity target, MovementState movementState) {
        if (target == null || !target.isValid()) return false;
        bot.getActionController().beginTick(bot);
        bot.setMovementState(movementState);

        double distance = bot.getLocation().distance(target.getLocation());
        BotInventory inv = bot.getBotInventory();
        int alive = bot.getAliveTicks();
        // Tracked for dir-noop telemetry so post-fight log inspection
        // can tell *which* branch almost matched before we fell through.
        String lastBranch = "none";

        CombatDebugger.dirEntry(bot, distance, bot.getCombatState().getPhase(),
                bot.isBotOnGround(), bot.getVelocity().getY());
        CombatDebugger.inventorySnapshot(bot);
        if (CombatDebugger.isOn(bot)) {
            CombatDebugger.log(bot, "dir-ready",
                    "kit[mace=" + inv.hasMace()
                            + " trident=" + inv.hasTrident()
                            + " pearl=" + inv.hasEnderPearl()
                            + " wind=" + inv.hasWindCharge()
                            + " crystal=" + inv.hasCrystalKit()
                            + " anchor=" + inv.hasAnchorKit()
                            + " cobweb=" + inv.hasCobweb()
                            + "] cd[mace=" + bot.getBotCooldowns().ready(MaceBehavior.COOLDOWN_KEY, alive)
                            + " trident=" + bot.getBotCooldowns().ready(TridentBehavior.COOLDOWN_KEY, alive)
                            + " pearl=" + bot.getBotCooldowns().ready(EnderPearlBehavior.COOLDOWN_KEY, alive)
                            + " wind=" + bot.getBotCooldowns().ready(WindChargeBehavior.COOLDOWN_KEY, alive)
                            + " crystal=" + bot.getBotCooldowns().ready(CrystalBehavior.COOLDOWN_KEY, alive)
                            + " anchor=" + bot.getBotCooldowns().ready(AnchorBombBehavior.COOLDOWN_KEY, alive)
                            + " cobweb=" + bot.getBotCooldowns().ready(UtilityBehavior.COOLDOWN_KEY, alive)
                            + "]");
        }

        // A totem uses the offhand and remains a valid emergency swap. Other
        // passive behaviors can replace the leased mainhand item or launch a
        // second action, so stop them while traversal owns the hands/hotbar.
        if (bot.movementV2ActionUsedThisTick()
                || bot.getActionController().isMovementV2TraversalAction()) {
            totem.tick(bot, target);
            CombatDebugger.log(bot, "action-hold",
                    "state=" + bot.getActionController().state()
                            + " src=" + bot.getActionController().source()
                            + " left=" + bot.getActionController().remainingTicks());
            return true;
        }

        elytra.tick(bot, target);
        totem.tick(bot, target);
        consumable.tick(bot, target);
        if (bot.getActionController().blocksCombatAction()) {
            CombatDebugger.log(bot, "action-hold",
                    "state=" + bot.getActionController().state()
                            + " src=" + bot.getActionController().source()
                            + " left=" + bot.getActionController().remainingTicks());
            return true;
        }
        windCharge.tickMovementBoost(bot, target, distance);

        // NN bots can hit execute() twice per tick (committed-phase path in tick(),
        // then executePlannedCombat() inside move()). Weapon branches aren't
        // idempotent — tickPhase() would double-advance and doAttack() could swing
        // twice on the same frame. Utility ticks above are safe to repeat.
        if (!bot.getCombatState().markExecuted(alive)) {
            CombatDebugger.log(bot, "commit-skip",
                    "reason=already-executed phase=" + bot.getCombatState().getPhase() + " source=normal");
            return true;
        }

        CombatIntent intent = bot.getCombatIntent();
        if (intent != null && !ACTION_NONE.equals(intent.plannedAction())) {
            boolean executed = executePlannedAction(bot, target, distance, inv, intent);
            if (!executed) {
                logSweepBranchSkip(bot, target, distance, "plannedFailed", intent.plannedAction());
                CombatDebugger.dirNoop(bot, distance, "planned-action-failed", intent.plannedAction());
            }
            return executed;
        }

        if (executeCommittedPhase(bot, target, distance, inv, "normal", true)) {
            return true;
        }

        if (combo.inProgress(bot)) {
            CombatDebugger.log(bot, "combo-in-progress");
            logSweepBranchSkip(bot, target, distance, "higherPriority", "combo");
            return true;
        }

        lastBranch = "aerial-dive";
        if (bot.getCombatState().getPhase() == CombatState.Phase.IDLE
                && !bot.isBotOnGround() && bot.getVelocity().getY() < -0.3
                && inv.hasMace()
                && BotCombatTiming.shouldPlanCurrentMaceDive(bot, target)) {
            Location botLoc = bot.getLocation();
            Location targetLoc = target.getLocation();
            double dx = targetLoc.getX() - botLoc.getX();
            double dz = targetLoc.getZ() - botLoc.getZ();
            if (dx * dx + dz * dz <= 100.0 && targetLoc.getY() <= botLoc.getY() + 2.0) {
                CombatDebugger.macePhase(bot, CombatState.Phase.IDLE, CombatState.Phase.AIRBORNE);
                bot.getCombatState().setPhase(CombatState.Phase.AIRBORNE);
                bot.getCombatState().setPhaseStartY(botLoc.getY());
                CombatDebugger.weaponPick(bot, "MACE(aerial-dive)", distance, true);
                selectType(inv, Material.MACE);
                mace.ticksFor(bot, target, distance);
                logSweepBranchSkip(bot, target, distance, "higherPriority", "aerialDive");
                return true;
            }
        }

        snapshot.update(bot, target);
        if (CombatDebugger.isOn(bot)) {
            CombatDebugger.log(bot, "snapshot",
                    "botHp=" + String.format("%.2f", snapshot.botHpFraction)
                            + " tgtHp=" + String.format("%.2f", snapshot.targetHpFraction)
                            + " targetAir=" + snapshot.targetAirborne
                            + " targetBlocking=" + snapshot.targetBlocking
                            + " targetEating=" + snapshot.targetEating
                            + " openSky=" + snapshot.openSkyAboveBot
                            + " targetAway=" + snapshot.targetSprintingAway
                            + " targetBow=" + snapshot.targetDrawingBow
                            + " targetPotion=" + snapshot.targetDrinkingPotion
                            + " targetCobweb=" + snapshot.targetInCobweb
                            + " botOnFire=" + snapshot.botOnFire);
        }

        lastBranch = "scanner";
        if (scanner.scan(bot, target, snapshot, combo)) {
            CombatDebugger.log(bot, "scanner-hit");
            logSweepBranchSkip(bot, target, distance, "higherPriority", "scanner");
            return true;
        }
        if (CombatDebugger.isOn(bot)) {
            CombatDebugger.log(bot, "scanner-miss",
                    "dist=" + String.format("%.2f", distance)
                            + " targetAir=" + snapshot.targetAirborne
                            + " targetBlocking=" + snapshot.targetBlocking
                            + " targetNearWall=" + snapshot.targetNearWall
                            + " targetAway=" + snapshot.targetSprintingAway);
        }

        int shieldAxe = inv.findAxe();
        if (snapshot.targetBlocking && shieldAxe >= 0 && distance <= MeleeBehavior.ATTACK_RANGE) {
            inv.selectMainInventorySlot(shieldAxe);
            CombatDebugger.weaponPick(bot, "AXE(shield)", distance, true);
            if (!BotCombatTiming.shouldPlanNormalMelee(bot, target)) {
                CombatDebugger.log(bot, "opp-skip",
                        "name=DIRECTOR_SHIELD_MELEE reason=charge charge="
                                + String.format("%.3f", BotCombatTiming.charge(bot)));
                BotCombatTiming.logSweepCheck(bot, target, distance);
                return false;
            }
            melee.ticksFor(bot, target, distance);
            logSweepBranchSkip(bot, target, distance, "higherPriority", "shieldAxe");
            return true;
        }

        World.Environment env = bot.getDimension();
        lastBranch = "crystal";
        if (env != World.Environment.NETHER && inv.hasCrystalKit()
                && distance >= CrystalBehavior.MIN_DISTANCE && distance <= CrystalBehavior.MAX_DISTANCE
                && bot.getBotCooldowns().ready(CrystalBehavior.COOLDOWN_KEY, alive)) {
            CombatDebugger.weaponPick(bot, "END_CRYSTAL", distance, true);
            selectType(inv, Material.END_CRYSTAL);
            crystal.ticksFor(bot, target, distance);
            logSweepBranchSkip(bot, target, distance, "higherPriority", "crystal");
            return true;
        }

        lastBranch = "anchor";
        if (env != World.Environment.NETHER && inv.hasAnchorKit()
                && distance >= AnchorBombBehavior.MIN_DISTANCE && distance <= AnchorBombBehavior.MAX_DISTANCE
                && bot.getBotCooldowns().ready(AnchorBombBehavior.COOLDOWN_KEY, alive)) {
            CombatDebugger.weaponPick(bot, "RESPAWN_ANCHOR", distance, true);
            selectType(inv, Material.RESPAWN_ANCHOR);
            anchor.ticksFor(bot, target, distance);
            logSweepBranchSkip(bot, target, distance, "higherPriority", "anchor");
            return true;
        }

        boolean grounded = bot.isBotOnGround();
        int sword = inv.findSword();
        int axe = inv.findAxe();
        int tridentMelee = inv.findHotbar(Material.TRIDENT);
        boolean onlyTridentMelee = sword < 0 && axe < 0 && tridentMelee >= 0
                && distance <= TridentBehavior.MELEE_FALLBACK_DISTANCE;

        lastBranch = "melee";
        if (distance <= 3.5 || onlyTridentMelee) {
            boolean hasSwordOrAxe = sword >= 0 || axe >= 0;
            boolean maceSmashReady = inv.hasMace() && !snapshot.targetBlocking
                    && snapshot.openSkyAboveBot && inv.hasWindCharge() && grounded
                    && bot.getBotCooldowns().ready(MaceBehavior.COOLDOWN_KEY, alive)
                    && bot.getBotCooldowns().ready(WindChargeBehavior.COOLDOWN_KEY, alive)
                    && BotCombatTiming.shouldPlanGroundMaceSmash(bot, target, MaceBehavior.LAUNCH_Y);

            if (maceSmashReady) {
                CombatDebugger.weaponPick(bot, "MACE(smash)", distance, true);
                selectType(inv, Material.MACE);
                mace.ticksFor(bot, target, distance);
                logSweepBranchSkip(bot, target, distance, "higherPriority", "maceSmash");
                return true;
            }

            MeleeChoice choice = chooseMelee(bot, target, inv, distance, snapshot.targetBlocking,
                    sword, axe, tridentMelee);
            if (choice.slot >= 0) {
                boolean switched = inv.getSelectedHotbarSlot() != choice.slot;
                inv.selectMainInventorySlot(choice.slot);
                CombatDebugger.log(bot, "melee-choice",
                        "chosen=" + choice.kind
                                + " reason=" + choice.reason
                                + " switched=" + switched
                                + " charge=" + String.format("%.3f", BotCombatTiming.charge(bot))
                                + " sweepPred=" + choice.sweepPred
                                + " sweepVictimCount=" + choice.sweepVictimCount);
                CombatDebugger.weaponPick(bot, choice.pickLabel, distance, true);
            } else {
                CombatDebugger.log(bot, "melee-choice",
                        "chosen=NONE reason=empty switched=false charge="
                                + String.format("%.3f", BotCombatTiming.charge(bot))
                                + " sweepPred=false sweepVictimCount=0");
                CombatDebugger.weaponPick(bot, "MELEE(empty)", distance, true);
            }
            if (!BotCombatTiming.shouldPlanNormalMelee(bot, target)) {
                CombatDebugger.log(bot, "opp-skip",
                        "name=" + ("shield".equals(choice.reason) ? "DIRECTOR_MELEE_SHIELD" : "DIRECTOR_MELEE")
                                + " reason=charge charge="
                                + String.format("%.3f", BotCombatTiming.charge(bot)));
                BotCombatTiming.logSweepCheck(bot, target, distance);
                return false;
            }
            melee.ticksFor(bot, target, distance);
            if ("shield".equals(choice.reason)) {
                logSweepBranchSkip(bot, target, distance, "higherPriority", "shieldAxe");
            }
            return true;
        }

        lastBranch = "trident";
        if (distance >= TridentBehavior.MIN_DISTANCE && distance <= TridentBehavior.MAX_DISTANCE && inv.hasTrident()
                && bot.getBotCooldowns().ready(TridentBehavior.COOLDOWN_KEY, alive)) {
            CombatDebugger.weaponPick(bot, "TRIDENT", distance, true);
            selectType(inv, Material.TRIDENT);
            trident.ticksFor(bot, target, distance);
            logSweepBranchSkip(bot, target, distance, "higherPriority", "trident");
            return true;
        }

        lastBranch = "pearl";
        if (distance >= 28.0 && distance <= 64.0 && inv.hasEnderPearl()
                && bot.getBotCooldowns().ready(EnderPearlBehavior.COOLDOWN_KEY, alive)) {
            CombatDebugger.weaponPick(bot, "ENDER_PEARL", distance, true);
            pearl.ticksFor(bot, target, distance);
            logSweepBranchSkip(bot, target, distance, "higherPriority", "pearl");
            return true;
        }

        lastBranch = "cobweb";
        if (inv.hasCobweb() && distance <= 4.5
                && bot.getBotCooldowns().ready(UtilityBehavior.COOLDOWN_KEY, alive)) {
            if (utility.ticksFor(bot, target, distance) > 0) {
                CombatDebugger.weaponPick(bot, "COBWEB", distance, true);
                logSweepBranchSkip(bot, target, distance, "higherPriority", "cobweb");
                return true;
            }
        }

        logSweepBranchSkip(bot, target, distance, "branchLost", lastBranch);
        CombatDebugger.dirNoop(bot, distance, "no-branch-matched", lastBranch);
        return false;
    }

    private boolean executePlannedAction(
            Bot bot,
            LivingEntity target,
            double distance,
            BotInventory inv,
            CombatIntent intent
    ) {
        String action = intent.plannedAction();
        switch (action) {
            case ACTION_MACE_CHARGING, ACTION_AIRBORNE_MACE, ACTION_TRIDENT_CHARGING -> {
                return executeCommittedPhase(bot, target, distance, inv, "planned", true);
            }
            case ACTION_COMBO -> {
                CombatDebugger.log(bot, "combo-in-progress");
                logSweepBranchSkip(bot, target, distance, "higherPriority", "combo");
                return true;
            }
            case ACTION_AERIAL_DIVE -> {
                return executeAerialDive(bot, target, distance, inv);
            }
            case ACTION_SHIELD_AXE -> {
                return executeShieldAxe(bot, target, distance, inv);
            }
            case ACTION_CRYSTAL -> {
                CombatDebugger.weaponPick(bot, "END_CRYSTAL", distance, true);
                selectType(inv, Material.END_CRYSTAL);
                crystal.ticksFor(bot, target, distance);
                logSweepBranchSkip(bot, target, distance, "higherPriority", "crystal");
                return true;
            }
            case ACTION_ANCHOR -> {
                CombatDebugger.weaponPick(bot, "RESPAWN_ANCHOR", distance, true);
                selectType(inv, Material.RESPAWN_ANCHOR);
                anchor.ticksFor(bot, target, distance);
                logSweepBranchSkip(bot, target, distance, "higherPriority", "anchor");
                return true;
            }
            case ACTION_MACE_SMASH -> {
                CombatDebugger.weaponPick(bot, "MACE(smash)", distance, true);
                selectType(inv, Material.MACE);
                mace.ticksFor(bot, target, distance);
                logSweepBranchSkip(bot, target, distance, "higherPriority", "maceSmash");
                return true;
            }
            case ACTION_MELEE -> {
                return executePlannedMelee(bot, target, distance, inv);
            }
            case ACTION_TRIDENT -> {
                CombatDebugger.weaponPick(bot, "TRIDENT", distance, true);
                selectType(inv, Material.TRIDENT);
                trident.ticksFor(bot, target, distance);
                logSweepBranchSkip(bot, target, distance, "higherPriority", "trident");
                return true;
            }
            case ACTION_PEARL -> {
                CombatDebugger.weaponPick(bot, "ENDER_PEARL", distance, true);
                pearl.ticksFor(bot, target, distance);
                logSweepBranchSkip(bot, target, distance, "higherPriority", "pearl");
                return true;
            }
            case ACTION_COBWEB -> {
                if (utility.ticksFor(bot, target, distance) > 0) {
                    CombatDebugger.weaponPick(bot, "COBWEB", distance, true);
                    logSweepBranchSkip(bot, target, distance, "higherPriority", "cobweb");
                    return true;
                }
                return false;
            }
            default -> {
                if (action.startsWith(ACTION_SCANNER_PREFIX)) {
                    snapshot.update(bot, target);
                    logSnapshot(bot);
                    OpportunityScanner.ScannerPlan scannerPlan = OpportunityScanner.ScannerPlan.fromPlayId(intent.playId());
                    if (scanner.execute(bot, target, snapshot, combo, scannerPlan)) {
                        CombatDebugger.log(bot, "scanner-hit", "play=" + scannerPlan.play().id());
                        logSweepBranchSkip(bot, target, distance, "higherPriority", scannerPlan.play().id());
                        return true;
                    }
                    CombatDebugger.log(bot, "scanner-miss",
                            "planned=" + intent.playId()
                                    + " dist=" + String.format("%.2f", distance)
                                    + " targetAir=" + snapshot.targetAirborne
                                    + " targetBlocking=" + snapshot.targetBlocking
                                    + " targetNearWall=" + snapshot.targetNearWall
                                    + " targetAway=" + snapshot.targetSprintingAway);
                }
                return false;
            }
        }
    }

    private boolean executeAerialDive(Bot bot, LivingEntity target, double distance, BotInventory inv) {
        if (bot.getCombatState().getPhase() != CombatState.Phase.IDLE
                || bot.isBotOnGround()
                || bot.getVelocity().getY() >= -0.3
                || !inv.hasMace()
                || !BotCombatTiming.shouldPlanCurrentMaceDive(bot, target)) {
            return false;
        }
        Location botLoc = bot.getLocation();
        Location targetLoc = target.getLocation();
        double dx = targetLoc.getX() - botLoc.getX();
        double dz = targetLoc.getZ() - botLoc.getZ();
        if (dx * dx + dz * dz > 100.0 || targetLoc.getY() > botLoc.getY() + 2.0) {
            return false;
        }
        CombatDebugger.macePhase(bot, CombatState.Phase.IDLE, CombatState.Phase.AIRBORNE);
        bot.getCombatState().setPhase(CombatState.Phase.AIRBORNE);
        bot.getCombatState().setPhaseStartY(botLoc.getY());
        CombatDebugger.weaponPick(bot, "MACE(aerial-dive)", distance, true);
        selectType(inv, Material.MACE);
        mace.ticksFor(bot, target, distance);
        logSweepBranchSkip(bot, target, distance, "higherPriority", "aerialDive");
        return true;
    }

    private boolean executeShieldAxe(Bot bot, LivingEntity target, double distance, BotInventory inv) {
        int shieldAxe = inv.findAxe();
        if (shieldAxe < 0 || distance > MeleeBehavior.ATTACK_RANGE) return false;
        inv.selectMainInventorySlot(shieldAxe);
        CombatDebugger.weaponPick(bot, "AXE(shield)", distance, true);
        if (!BotCombatTiming.shouldPlanNormalMelee(bot, target)) {
            CombatDebugger.log(bot, "opp-skip",
                    "name=DIRECTOR_SHIELD_MELEE reason=charge charge="
                            + String.format("%.3f", BotCombatTiming.charge(bot)));
            BotCombatTiming.logSweepCheck(bot, target, distance);
            return false;
        }
        melee.ticksFor(bot, target, distance);
        logSweepBranchSkip(bot, target, distance, "higherPriority", "shieldAxe");
        return true;
    }

    private boolean executePlannedMelee(Bot bot, LivingEntity target, double distance, BotInventory inv) {
        snapshot.update(bot, target);
        int sword = inv.findSword();
        int axe = inv.findAxe();
        int tridentMelee = inv.findHotbar(Material.TRIDENT);
        MeleeChoice choice = chooseMelee(bot, target, inv, distance, snapshot.targetBlocking, sword, axe, tridentMelee);
        if (choice.slot >= 0) {
            boolean switched = inv.getSelectedHotbarSlot() != choice.slot;
            inv.selectMainInventorySlot(choice.slot);
            CombatDebugger.log(bot, "melee-choice",
                    "chosen=" + choice.kind
                            + " reason=" + choice.reason
                            + " switched=" + switched
                            + " charge=" + String.format("%.3f", BotCombatTiming.charge(bot))
                            + " sweepPred=" + choice.sweepPred
                            + " sweepVictimCount=" + choice.sweepVictimCount);
            CombatDebugger.weaponPick(bot, choice.pickLabel, distance, true);
        } else {
            CombatDebugger.log(bot, "melee-choice",
                    "chosen=NONE reason=empty switched=false charge="
                            + String.format("%.3f", BotCombatTiming.charge(bot))
                            + " sweepPred=false sweepVictimCount=0");
            CombatDebugger.weaponPick(bot, "MELEE(empty)", distance, true);
        }
        if (!BotCombatTiming.shouldPlanNormalMelee(bot, target)) {
            CombatDebugger.log(bot, "opp-skip",
                    "name=" + ("shield".equals(choice.reason) ? "DIRECTOR_MELEE_SHIELD" : "DIRECTOR_MELEE")
                            + " reason=charge charge="
                            + String.format("%.3f", BotCombatTiming.charge(bot)));
            BotCombatTiming.logSweepCheck(bot, target, distance);
            return false;
        }
        melee.ticksFor(bot, target, distance);
        if ("shield".equals(choice.reason)) {
            logSweepBranchSkip(bot, target, distance, "higherPriority", "shieldAxe");
        }
        return true;
    }

    private void logSnapshot(Bot bot) {
        if (!CombatDebugger.isOn(bot)) return;
        CombatDebugger.log(bot, "snapshot",
                "botHp=" + String.format("%.2f", snapshot.botHpFraction)
                        + " tgtHp=" + String.format("%.2f", snapshot.targetHpFraction)
                        + " targetAir=" + snapshot.targetAirborne
                        + " targetBlocking=" + snapshot.targetBlocking
                        + " targetEating=" + snapshot.targetEating
                        + " openSky=" + snapshot.openSkyAboveBot
                        + " targetAway=" + snapshot.targetSprintingAway
                        + " targetBow=" + snapshot.targetDrawingBow
                        + " targetPotion=" + snapshot.targetDrinkingPotion
                        + " targetCobweb=" + snapshot.targetInCobweb
                        + " botOnFire=" + snapshot.botOnFire);
    }

    private void selectType(BotInventory inv, Material type) {
        inv.selectMaterial(type);
    }

    private boolean executeCommittedPhase(
            Bot bot,
            LivingEntity target,
            double distance,
            BotInventory inv,
            String source,
            boolean logSweepSkip
    ) {
        CombatState.Phase phase = bot.getCombatState().getPhase();
        switch (phase) {
            case MACE_CHARGING -> {
                if (!inv.hasMace()) {
                    CombatDebugger.log(bot, "commit-skip", "reason=missing-weapon phase=" + phase + " weapon=MACE");
                    return false;
                }
                CombatDebugger.log(bot, "commit-tick", "phase=" + phase + " source=" + source);
                CombatDebugger.weaponPick(bot, "MACE(charging)", distance, true);
                selectType(inv, Material.MACE);
                mace.ticksFor(bot, target, distance);
                if (logSweepSkip) logSweepBranchSkip(bot, target, distance, "higherPriority", "maceCharging");
                return true;
            }
            case AIRBORNE -> {
                if (!inv.hasMace()) {
                    CombatDebugger.log(bot, "commit-skip", "reason=missing-weapon phase=" + phase + " weapon=MACE");
                    return false;
                }
                CombatDebugger.log(bot, "commit-tick", "phase=" + phase + " source=" + source);
                CombatDebugger.weaponPick(bot, "MACE(airborne-commit)", distance, true);
                selectType(inv, Material.MACE);
                mace.ticksFor(bot, target, distance);
                if (logSweepSkip) logSweepBranchSkip(bot, target, distance, "higherPriority", "airborneMace");
                return true;
            }
            case CHARGING -> {
                if (!inv.hasTrident()) {
                    CombatDebugger.log(bot, "commit-skip", "reason=missing-weapon phase=" + phase + " weapon=TRIDENT");
                    return false;
                }
                CombatDebugger.log(bot, "commit-tick", "phase=" + phase + " source=" + source);
                CombatDebugger.weaponPick(bot, "TRIDENT(charging)", distance, true);
                selectType(inv, Material.TRIDENT);
                trident.ticksFor(bot, target, distance);
                if (logSweepSkip) logSweepBranchSkip(bot, target, distance, "higherPriority", "tridentCharging");
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void logSweepBranchSkip(Bot bot, LivingEntity target, double distance, String reason, String branch) {
        BotCombatTiming.logSweepSkipIfRelevant(bot, target, distance, reason, branch);
    }

    private static boolean isCommittedPhase(CombatState.Phase phase) {
        return phase == CombatState.Phase.MACE_CHARGING
                || phase == CombatState.Phase.AIRBORNE
                || phase == CombatState.Phase.CHARGING;
    }

    private static boolean hasCommittedWeapon(CombatState.Phase phase, BotInventory inv) {
        return switch (phase) {
            case MACE_CHARGING, AIRBORNE -> inv.hasMace();
            case CHARGING -> inv.hasTrident();
            default -> true;
        };
    }

    private MeleeChoice chooseMelee(
            Bot bot,
            LivingEntity target,
            BotInventory inv,
            double distance,
            boolean targetBlocking,
            int sword,
            int axe,
            int tridentMelee
    ) {
        boolean sweepPred = false;
        int sweepVictimCount = 0;
        if (sword >= 0) {
            sweepPred = BotCombatTiming.predictsSweepWithSword(bot, target, distance);
            sweepVictimCount = BotCombatTiming.sweepVictimCount(bot, target);
        }

        if (targetBlocking && axe >= 0) {
            return new MeleeChoice(axe, "AXE", "AXE(shield)", "shield", sweepPred, sweepVictimCount);
        }
        if (sweepPred && sweepVictimCount > 0 && sword >= 0) {
            return new MeleeChoice(sword, "SWORD", "SWORD", "sweep", true, sweepVictimCount);
        }
        if (sword >= 0 && axe >= 0 && sweepVictimCount > 0 && selectedTypeEndsWith(inv, "_SWORD")
                && BotCombatTiming.charge(bot) < BotCombatTiming.READY_CHARGE) {
            return new MeleeChoice(sword, "SWORD", "SWORD", "sweep", false, sweepVictimCount);
        }
        if (axe >= 0) {
            return new MeleeChoice(axe, "AXE", "AXE", "axe-heavy", sweepPred, sweepVictimCount);
        }
        if (sword >= 0) {
            return new MeleeChoice(sword, "SWORD", "SWORD", "fallback", sweepPred, sweepVictimCount);
        }
        if (tridentMelee >= 0) {
            return new MeleeChoice(tridentMelee, "TRIDENT", "TRIDENT(melee)", "fallback", false, 0);
        }
        if (inv.findBestMeleeWeaponSlot() < 0) {
            int fistSlot = inv.findEmptyHotbarSlot();
            if (fistSlot >= 0) {
                return new MeleeChoice(fistSlot, "FIST", "FIST", "empty-hand", false, 0);
            }
        }
        return new MeleeChoice(-1, "NONE", "MELEE(empty)", "empty", false, 0);
    }

    private static boolean selectedTypeEndsWith(BotInventory inv, String suffix) {
        return inv.getSelected().getType().name().endsWith(suffix);
    }

    private static boolean scannerWantsHold(OpportunityScanner.ScannerPlay play) {
        if (play.family() == MovementBranchFamily.MACE) {
            return false;
        }
        return !play.interruptible();
    }

    private CombatIntent planIdle(
            Bot bot,
            LivingEntity target,
            BotInventory inv,
            double distance,
            boolean wantsCritSetup,
            boolean wantsSprintHit
    ) {
        if (combo.inProgress(bot)) {
            return buildIntent(bot, target, distance, MovementBranchFamily.MOBILITY, "",
                    DesiredRangeBand.CLOSE, ACTION_COMBO, false, 20, "combo",
                    false, false, true, false, MeleeBehavior.ATTACK_RANGE);
        }

        if (!bot.isBotOnGround() && bot.getVelocity().getY() < -0.3
                && inv.hasMace()
                && BotCombatTiming.shouldPlanCurrentMaceDive(bot, target)) {
            Location botLoc = bot.getLocation();
            Location targetLoc = target.getLocation();
            double dx = targetLoc.getX() - botLoc.getX();
            double dz = targetLoc.getZ() - botLoc.getZ();
            if (dx * dx + dz * dz <= 100.0 && targetLoc.getY() <= botLoc.getY() + 2.0) {
                return buildIntent(bot, target, distance, MovementBranchFamily.MACE, "",
                        DesiredRangeBand.MELEE, ACTION_AERIAL_DIVE, false, 30, "aerial_dive",
                        wantsCritSetup, wantsSprintHit, false, false, MeleeBehavior.ATTACK_RANGE);
            }
        }

        snapshot.update(bot, target);
        OpportunityScanner.ScannerPlan scannerPlan = scanner.plan(bot, target, snapshot, combo);
        if (scannerPlan.present()) {
            OpportunityScanner.ScannerPlay play = scannerPlan.play();
            return buildIntent(bot, target, distance, play.family(), scannerPlan.playId(),
                    play.rangeBand(), play.actionIdentity(), play.interruptible(), play.lockTicks(),
                    play.id(), wantsCritSetup, wantsSprintHit, scannerWantsHold(play), false,
                    weaponRangeFor(play.family(), play.rangeBand()));
        }

        int shieldAxe = inv.findAxe();
        if (snapshot.targetBlocking && shieldAxe >= 0 && distance <= MeleeBehavior.ATTACK_RANGE) {
            return buildIntent(bot, target, distance, MovementBranchFamily.MELEE, "",
                    DesiredRangeBand.MELEE, ACTION_SHIELD_AXE, true, 10, "shield_axe",
                    wantsCritSetup, wantsSprintHit, false, false, MeleeBehavior.ATTACK_RANGE);
        }

        World.Environment env = bot.getDimension();
        if (env != World.Environment.NETHER && inv.hasCrystalKit()
                && distance >= CrystalBehavior.MIN_DISTANCE && distance <= CrystalBehavior.MAX_DISTANCE
                && bot.getBotCooldowns().ready(CrystalBehavior.COOLDOWN_KEY, bot.getAliveTicks())) {
            return buildIntent(bot, target, distance, MovementBranchFamily.EXPLOSIVE_SURVIVAL, "",
                    DesiredRangeBand.CLOSE, ACTION_CRYSTAL, false, 30, "crystal",
                    false, false, true, false, CrystalBehavior.MAX_DISTANCE);
        }

        if (env != World.Environment.NETHER && inv.hasAnchorKit()
                && distance >= AnchorBombBehavior.MIN_DISTANCE && distance <= AnchorBombBehavior.MAX_DISTANCE
                && bot.getBotCooldowns().ready(AnchorBombBehavior.COOLDOWN_KEY, bot.getAliveTicks())) {
            return buildIntent(bot, target, distance, MovementBranchFamily.EXPLOSIVE_SURVIVAL, "",
                    DesiredRangeBand.CLOSE, ACTION_ANCHOR, false, 30, "anchor",
                    false, false, true, false, AnchorBombBehavior.MAX_DISTANCE);
        }

        boolean grounded = bot.isBotOnGround();
        int sword = inv.findSword();
        int axe = inv.findAxe();
        int tridentMelee = inv.findHotbar(Material.TRIDENT);
        boolean onlyTridentMelee = sword < 0 && axe < 0 && tridentMelee >= 0
                && distance <= TridentBehavior.MELEE_FALLBACK_DISTANCE;

        if (distance <= 3.5 || onlyTridentMelee) {
            boolean hasSwordOrAxe = sword >= 0 || axe >= 0;
            boolean maceSmashReady = inv.hasMace() && !snapshot.targetBlocking
                    && snapshot.openSkyAboveBot && inv.hasWindCharge() && grounded
                    && bot.getBotCooldowns().ready(MaceBehavior.COOLDOWN_KEY, bot.getAliveTicks())
                    && bot.getBotCooldowns().ready(WindChargeBehavior.COOLDOWN_KEY, bot.getAliveTicks())
                    && BotCombatTiming.shouldPlanGroundMaceSmash(bot, target, MaceBehavior.LAUNCH_Y);
            if (maceSmashReady) {
                return buildIntent(bot, target, distance, MovementBranchFamily.MACE, "",
                        DesiredRangeBand.MELEE, ACTION_MACE_SMASH, false, 40, "mace_smash",
                        wantsCritSetup, wantsSprintHit, false, false, MeleeBehavior.ATTACK_RANGE);
            }

            MovementBranchFamily family = !hasSwordOrAxe && tridentMelee >= 0
                    ? MovementBranchFamily.SPEAR_MELEE
                    : MovementBranchFamily.MELEE;
            return buildIntent(bot, target, distance, family, "",
                    DesiredRangeBand.MELEE, ACTION_MELEE, true, 8, "melee",
                    wantsCritSetup, wantsSprintHit, false, false, MeleeBehavior.ATTACK_RANGE);
        }

        if (distance >= TridentBehavior.MIN_DISTANCE && distance <= TridentBehavior.MAX_DISTANCE && inv.hasTrident()
                && bot.getBotCooldowns().ready(TridentBehavior.COOLDOWN_KEY, bot.getAliveTicks())) {
            return buildIntent(bot, target, distance, MovementBranchFamily.TRIDENT_RANGED, "",
                    DesiredRangeBand.MID, ACTION_TRIDENT, true, 20, "trident",
                    false, false, false, false, TridentBehavior.MAX_DISTANCE);
        }

        if (distance >= 28.0 && distance <= 64.0 && inv.hasEnderPearl()
                && bot.getBotCooldowns().ready(EnderPearlBehavior.COOLDOWN_KEY, bot.getAliveTicks())) {
            return buildIntent(bot, target, distance, MovementBranchFamily.MOBILITY, "",
                    DesiredRangeBand.LONG, ACTION_PEARL, false, 30, "pearl",
                    false, false, false, false, 64.0);
        }

        if (inv.hasCobweb() && distance <= 4.5
                && bot.getBotCooldowns().ready(UtilityBehavior.COOLDOWN_KEY, bot.getAliveTicks())) {
            return buildIntent(bot, target, distance, MovementBranchFamily.MELEE, "",
                    DesiredRangeBand.CLOSE, ACTION_COBWEB, true, 15, "cobweb",
                    wantsCritSetup, wantsSprintHit, false, false, 4.5);
        }

        return buildIntent(bot, target, distance, MovementBranchFamily.GENERAL_FALLBACK, "",
                DesiredRangeBand.MELEE, ACTION_NONE, true, 0, "",
                wantsCritSetup, wantsSprintHit, false, false, MeleeBehavior.ATTACK_RANGE);
    }

    private CombatIntent buildIntent(
            Bot bot,
            LivingEntity target,
            double distance,
            MovementBranchFamily branchFamily,
            String playId,
            DesiredRangeBand rangeBand,
            String action,
            boolean interruptible,
            int lockTicks,
            String lockReason,
            boolean wantsCritSetup,
            boolean wantsSprintHit,
            boolean wantsHoldPosition,
            boolean committed,
            double weaponRange
    ) {
        int alive = bot.getAliveTicks();
        int lockUntilTick = lockTicks <= 0 ? 0 : alive + lockTicks;
        double desiredRange = rangeBand.center();
        MovementObjective objective = movementObjectiveFor(action, distance, desiredRange);
        CombatActionCategory actionCategory = actionCategoryFor(action);
        double minSafeRange = minSafeRangeFor(actionCategory, rangeBand);
        double maxUsefulRange = Math.max(weaponRange, maxUsefulRangeFor(actionCategory, rangeBand));
        return new CombatIntent(
                branchFamily,
                playId,
                desiredRange,
                rangeBand,
                rangeUrgency(distance, desiredRange),
                lockUntilTick > alive ? branchFamily : MovementBranchFamily.GENERAL_FALLBACK,
                lockUntilTick > alive ? lockReason : "",
                lockUntilTick,
                interruptible,
                action,
                wantsCritSetup,
                wantsSprintHit,
                wantsHoldPosition,
                committed,
                commitProgress(bot.getCombatState()),
                weaponRange,
                objective,
                actionCategory,
                minSafeRange,
                maxUsefulRange,
                distance - desiredRange,
                lockUntilTick > alive ? lockUntilTick - alive : 0,
                BotCombatTiming.charge(bot),
                BotCombatTiming.targetHasIFrames(target),
                snapshot.botHpFraction,
                snapshot.targetHpFraction,
                snapshot.botHpFraction - snapshot.targetHpFraction,
                snapshot.targetBlocking,
                snapshot.targetAirborne,
                snapshot.targetRising,
                snapshot.targetSprintingAway,
                snapshot.targetInCobweb,
                snapshot.targetOverVoid,
                snapshot.openSkyAboveBot,
                snapshot.botInLavaArea,
                snapshot.botOnFire,
                needsLineOfSight(actionCategory)
        );
    }

    private static MovementObjective movementObjectiveFor(String action, double distance, double desiredRange) {
        CombatActionCategory category = actionCategoryFor(action);
        return switch (category) {
            case SHIELD_AXE -> MovementObjective.SHIELD_PRESSURE;
            case MACE_CHARGE, MACE_AIRBORNE, MACE_SMASH, AERIAL_DIVE -> MovementObjective.VERTICAL_SETUP;
            case TRIDENT_CHARGE, TRIDENT_THROW -> MovementObjective.RANGED_LOS;
            case CRYSTAL, ANCHOR -> MovementObjective.EXPLOSIVE_ESCAPE;
            case PEARL -> distance > desiredRange ? MovementObjective.PEARL_ENGAGE : MovementObjective.PEARL_DISENGAGE;
            case COBWEB -> MovementObjective.COBWEB_PRESSURE;
            case COMBO -> MovementObjective.HOLD;
            case SCANNER_PUNISH -> MovementObjective.APPROACH;
            case MELEE -> distance > desiredRange + 0.75 ? MovementObjective.APPROACH : MovementObjective.ORBIT;
            case NONE -> distance > desiredRange + 1.0 ? MovementObjective.APPROACH : MovementObjective.ORBIT;
        };
    }

    private static CombatActionCategory actionCategoryFor(String action) {
        String value = action == null ? "" : action.toLowerCase(java.util.Locale.ROOT);
        if (value.contains("shield_axe")) return CombatActionCategory.SHIELD_AXE;
        if (value.contains("mace_charging")) return CombatActionCategory.MACE_CHARGE;
        if (value.contains("airborne_mace")) return CombatActionCategory.MACE_AIRBORNE;
        if (value.contains("mace_smash")) return CombatActionCategory.MACE_SMASH;
        if (value.contains("trident_charging")) return CombatActionCategory.TRIDENT_CHARGE;
        if (value.contains("trident")) return CombatActionCategory.TRIDENT_THROW;
        if (value.contains("crystal")) return CombatActionCategory.CRYSTAL;
        if (value.contains("anchor")) return CombatActionCategory.ANCHOR;
        if (value.contains("pearl")) return CombatActionCategory.PEARL;
        if (value.contains("cobweb")) return CombatActionCategory.COBWEB;
        if (value.contains("combo")) return CombatActionCategory.COMBO;
        if (value.contains("aerial_dive")) return CombatActionCategory.AERIAL_DIVE;
        if (value.startsWith(ACTION_SCANNER_PREFIX)) return CombatActionCategory.SCANNER_PUNISH;
        if (value.contains("melee")) return CombatActionCategory.MELEE;
        return CombatActionCategory.NONE;
    }

    private static double minSafeRangeFor(CombatActionCategory category, DesiredRangeBand rangeBand) {
        return switch (category) {
            case CRYSTAL -> CrystalBehavior.MIN_DISTANCE;
            case ANCHOR -> AnchorBombBehavior.MIN_DISTANCE;
            case TRIDENT_CHARGE, TRIDENT_THROW -> TridentBehavior.MIN_DISTANCE;
            case PEARL -> 8.0;
            case MACE_CHARGE, MACE_AIRBORNE, MACE_SMASH, AERIAL_DIVE -> 2.0;
            case SHIELD_AXE, COBWEB, MELEE, SCANNER_PUNISH -> 1.4;
            default -> Math.max(1.4, rangeBand.center() - 1.5);
        };
    }

    private static double maxUsefulRangeFor(CombatActionCategory category, DesiredRangeBand rangeBand) {
        return switch (category) {
            case TRIDENT_CHARGE, TRIDENT_THROW -> TridentBehavior.MAX_DISTANCE;
            case PEARL -> 64.0;
            case CRYSTAL -> CrystalBehavior.MAX_DISTANCE;
            case ANCHOR -> AnchorBombBehavior.MAX_DISTANCE;
            case COBWEB -> 4.5;
            case MACE_CHARGE, MACE_AIRBORNE, MACE_SMASH, AERIAL_DIVE -> 6.0;
            default -> Math.max(rangeBand.center() + 2.0, MeleeBehavior.ATTACK_RANGE);
        };
    }

    private static boolean needsLineOfSight(CombatActionCategory category) {
        return category == CombatActionCategory.TRIDENT_CHARGE
                || category == CombatActionCategory.TRIDENT_THROW
                || category == CombatActionCategory.CRYSTAL
                || category == CombatActionCategory.ANCHOR;
    }

    private static double weaponRangeFor(MovementBranchFamily family, DesiredRangeBand rangeBand) {
        return switch (family) {
            case TRIDENT_RANGED -> TridentBehavior.MAX_DISTANCE;
            case MOBILITY -> rangeBand == DesiredRangeBand.LONG ? 64.0 : 30.0;
            case PROJECTILE_RANGED -> 30.0;
            case EXPLOSIVE_SURVIVAL -> CrystalBehavior.MAX_DISTANCE;
            default -> MeleeBehavior.ATTACK_RANGE;
        };
    }

    private static double rangeUrgency(double distance, double desiredRange) {
        if (!Double.isFinite(distance) || desiredRange <= 0.0) return 0.0;
        return Math.min(1.0, Math.abs(distance - desiredRange) / desiredRange);
    }

    private static double commitProgress(CombatState state) {
        if (state.getPhase() == CombatState.Phase.IDLE) return 0.0;
        return Math.min(1.0, state.getPhaseTicks() / 40.0);
    }

    private record MeleeChoice(
            int slot,
            String kind,
            String pickLabel,
            String reason,
            boolean sweepPred,
            int sweepVictimCount
    ) {}
}
