package net.nuggetmc.tplus.bot.combat;

import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.loadout.BotInventory;
import net.nuggetmc.tplus.bot.navigation.BukkitNavigationContext;
import net.nuggetmc.tplus.bot.navigation.MovementV2Controller;
import net.nuggetmc.tplus.bot.navigation.MovementV2Planner;
import net.nuggetmc.tplus.compat.bukkit.Bukkit;
import net.nuggetmc.tplus.compat.bukkit.FluidCollisionMode;
import net.nuggetmc.tplus.compat.bukkit.GameMode;
import net.nuggetmc.tplus.compat.bukkit.Location;
import net.nuggetmc.tplus.compat.bukkit.Material;
import net.nuggetmc.tplus.compat.bukkit.Sound;
import net.nuggetmc.tplus.compat.bukkit.SoundCategory;
import net.nuggetmc.tplus.compat.bukkit.World;
import net.nuggetmc.tplus.compat.bukkit.block.Block;
import net.nuggetmc.tplus.compat.bukkit.block.BlockFace;
import net.nuggetmc.tplus.compat.bukkit.block.BlockState;
import net.nuggetmc.tplus.compat.bukkit.block.data.Bisected;
import net.nuggetmc.tplus.compat.bukkit.block.data.Openable;
import net.nuggetmc.tplus.compat.bukkit.block.data.type.Door;
import net.nuggetmc.tplus.compat.bukkit.enchantments.Enchantment;
import net.nuggetmc.tplus.compat.bukkit.entity.Player;
import net.nuggetmc.tplus.compat.bukkit.event.Event;
import net.nuggetmc.tplus.compat.bukkit.event.block.Action;
import net.nuggetmc.tplus.compat.bukkit.event.block.BlockPlaceEvent;
import net.nuggetmc.tplus.compat.bukkit.event.player.PlayerBucketEmptyEvent;
import net.nuggetmc.tplus.compat.bukkit.event.player.PlayerBucketFillEvent;
import net.nuggetmc.tplus.compat.bukkit.event.player.PlayerInteractEvent;
import net.nuggetmc.tplus.compat.bukkit.inventory.EquipmentSlot;
import net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack;
import net.nuggetmc.tplus.compat.bukkit.util.RayTraceResult;
import net.nuggetmc.tplus.compat.bukkit.util.Vector;

import java.util.List;
import java.util.Set;

/**
 * Executes traversal requests with player inventory, reach, timing, and event
 * checks. Route search never calls this class or mutates the world itself.
 */
public final class PlayerTraversalActionExecutor {

    private static final double REACH = 4.5;
    private static final int ACTION_TIMEOUT = 600;
    private static final Set<String> FALLING_BLOCKS = Set.of(
            "SAND", "RED_SAND", "GRAVEL", "DRAGON_EGG", "SCAFFOLDING",
            "POINTED_DRIPSTONE", "ANVIL", "CHIPPED_ANVIL", "DAMAGED_ANVIL"
    );
    private static final List<Material> PREFERRED_BUILDING_BLOCKS = List.of(
            Material.COBBLESTONE,
            Material.COBBLED_DEEPSLATE,
            Material.STONE,
            Material.DIRT,
            Material.OAK_PLANKS
    );

    private MovementV2Planner.Step current;
    private int startedTick;
    private int previousSelectedSlot = -1;
    private int sourceInventorySlot = -1;
    private int leasedHotbarSlot = -1;
    private Material originalActionMaterial = Material.AIR;
    private Material leasedMaterial = Material.AIR;
    private int requiredTicks;
    private double startY;
    private boolean placed;
    private int placedTick;

    public Outcome tick(Bot bot, MovementV2Planner.Step request, BukkitNavigationContext context) {
        requirePrimaryThread();
        if (bot == null || request == null || context == null || !bot.isBotAlive()) {
            return fail(bot, "missing-or-dead");
        }
        if (!request.kind().changesWorld()) {
            return fail(bot, "not-an-action-step");
        }
        if (current == null || !current.equals(request)) {
            if (current != null) cancel(bot, "request-changed");
            Outcome begun = begin(bot, request, context);
            if (begun.status() != MovementV2Controller.ActionOutcome.RUNNING) return begun;
        }

        bot.getActionController().beginTick(bot);
        if (!bot.getActionController().active()) {
            return fail(bot, "action-owner-lost");
        }
        if (bot.getAliveTicks() - startedTick > ACTION_TIMEOUT) {
            return fail(bot, "timeout");
        }

        return switch (current.kind()) {
            case OPEN -> tickOpen(bot, context);
            case BREAK -> tickBreak(bot, context);
            case PLACE_BRIDGE -> tickPlace(bot, context);
            case PILLAR -> tickPillar(bot, context);
            case CLUTCH_DROP -> tickClutch(bot, context);
            default -> fail(bot, "unsupported:" + current.kind().name().toLowerCase());
        };
    }

    public void cancel(Bot bot, String reason) {
        requirePrimaryThread();
        if (current == null) return;
        recoverPlacedWater(bot);
        if (bot != null) bot.getActionController().interrupt(bot, reason);
        restoreInventory(bot);
        clear();
    }

    public boolean active() {
        return current != null;
    }

    public MovementV2Planner.Step currentStep() {
        return current;
    }

    private Outcome begin(Bot bot, MovementV2Planner.Step request, BukkitNavigationContext context) {
        PlayerLikeActionController owner = bot.getActionController();
        owner.beginTick(bot);
        if (owner.active()) return Outcome.failed("primary-action-busy:" + owner.state());

        Block actionBlock = context.liveBlockAt(request.actionPos());
        if (actionBlock == null || !authorized(bot, actionBlock.getLocation())) {
            return Outcome.failed("unauthorized-or-unloaded");
        }
        if (!withinReach(bot, actionBlock) && request.kind() != MovementV2Planner.Kind.CLUTCH_DROP) {
            return Outcome.failed("out-of-reach");
        }

        previousSelectedSlot = bot.getBotInventory().getSelectedHotbarSlot();
        sourceInventorySlot = -1;
        leasedHotbarSlot = -1;
        leasedMaterial = Material.AIR;
        originalActionMaterial = actionBlock.getType();
        startY = bot.getLocation().getY();
        placed = false;
        placedTick = -1;
        requiredTicks = 1;

        int requestedSlot = switch (request.kind()) {
            case BREAK -> findBestToolSlot(bot, actionBlock);
            case PLACE_BRIDGE, PILLAR -> findBuildingBlockSlot(bot.getBotInventory());
            case CLUTCH_DROP -> clutchItemSlot(bot);
            default -> -1;
        };
        if (request.kind() == MovementV2Planner.Kind.PLACE_BRIDGE
                || request.kind() == MovementV2Planner.Kind.PILLAR
                || request.kind() == MovementV2Planner.Kind.CLUTCH_DROP) {
            if (requestedSlot < 0) return Outcome.failed("required-item-missing");
        }

        if (requestedSlot >= 0) {
            sourceInventorySlot = requestedSlot;
            leasedHotbarSlot = bot.getBotInventory().leaseToHotbar(requestedSlot);
            if (leasedHotbarSlot < 0) {
                clearLease();
                return Outcome.failed("no-hotbar-lease");
            }
            bot.getBotInventory().setSelectedHotbarSlot(leasedHotbarSlot);
            bot.getBotInventory().refreshSelectedItem();
            ItemStack leased = bot.getBotInventory().raw().getItem(leasedHotbarSlot);
            leasedMaterial = leased == null ? Material.AIR : leased.getType();
        }
        requiredTicks = durationFor(bot, request, actionBlock);
        if (request.kind() == MovementV2Planner.Kind.BREAK && requiredTicks > ACTION_TIMEOUT) {
            restoreInventory(bot);
            clear();
            return Outcome.failed("break-too-slow");
        }

        BotActionState state = switch (request.kind()) {
            case OPEN -> BotActionState.OPENING;
            case BREAK -> BotActionState.MINING;
            case PLACE_BRIDGE -> BotActionState.PLACING_BLOCK;
            case PILLAR -> BotActionState.PILLARING;
            case CLUTCH_DROP -> BotActionState.FALL_CLUTCH;
            default -> BotActionState.IDLE;
        };
        if (!owner.start(bot, state, ACTION_TIMEOUT + 5, leasedHotbarSlot,
                "movement-v2-" + request.kind().name().toLowerCase())) {
            restoreInventory(bot);
            clear();
            return Outcome.failed("action-owner-refused");
        }

        current = request;
        startedTick = bot.getAliveTicks();
        CombatDebugger.log(bot, "movement-v2-action-start",
                "kind=" + request.kind()
                        + " at=" + request.actionPos()
                        + " item=" + leasedMaterial
                        + " ticks=" + requiredTicks);
        return Outcome.running("started");
    }

    private Outcome tickOpen(Bot bot, BukkitNavigationContext context) {
        Block block = verifiedBlock(bot, context, true);
        if (block == null) return fail(bot, "open-block-changed-or-occluded");
        faceBlock(bot, block);
        if (bot.getAliveTicks() - startedTick < requiredTicks) return Outcome.running("opening");
        if (!(block.getBlockData() instanceof Openable openable)) return fail(bot, "not-openable");
        if (block.getType().name().startsWith("IRON_")) return fail(bot, "requires-redstone");
        if (openable.isOpen()) return succeed(bot, "already-open");

        Player player = player(bot);
        player.swingMainHand();
        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK,
                player.getInventory().getItemInMainHand(), block, BlockFace.UP, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || event.useInteractedBlock() == Event.Result.DENY) {
            return fail(bot, "open-cancelled");
        }
        if (block.getType() != originalActionMaterial
                || !(block.getBlockData() instanceof Openable)) {
            return fail(bot, "open-block-changed-during-event");
        }

        openable = (Openable) block.getBlockData();
        openable.setOpen(true);
        block.setBlockData(openable, true);
        syncDoorHalf(block, true);
        block.getWorld().playSound(block.getLocation(), Sound.BLOCK_WOODEN_DOOR_OPEN,
                SoundCategory.BLOCKS, 1.0f, 1.0f);
        return succeed(bot, "opened");
    }

    private Outcome tickBreak(Bot bot, BukkitNavigationContext context) {
        Block block = verifiedBlock(bot, context, true);
        if (block == null) return fail(bot, "break-block-changed-or-occluded");
        faceBlock(bot, block);
        if (bot.getAliveTicks() % 4 == 0) player(bot).swingMainHand();
        if (bot.getAliveTicks() - startedTick < requiredTicks) return Outcome.running("mining");
        if (!player(bot).breakBlock(block)) return fail(bot, "break-refused");
        return succeed(bot, "broken");
    }

    private Outcome tickPlace(Bot bot, BukkitNavigationContext context) {
        Block block = verifiedReplaceable(bot, context);
        if (block == null) return fail(bot, "place-target-changed-or-occluded");
        faceBlock(bot, block);
        if (bot.getAliveTicks() - startedTick < requiredTicks) return Outcome.running("placing");
        if (!placeCarriedBlock(bot, block)) return fail(bot, "place-refused");
        return succeed(bot, "placed");
    }

    private Outcome tickPillar(Bot bot, BukkitNavigationContext context) {
        Block block = context.liveBlockAt(current.actionPos());
        if (block == null || (!placed && block.getType() != originalActionMaterial)) {
            return fail(bot, "pillar-target-changed");
        }
        bot.look(BlockFace.DOWN);

        if (!placed && bot.isBotOnGround() && bot.getLocation().getY() < startY + 0.1) {
            bot.jump(new Vector(0.0, 0.42, 0.0));
            return Outcome.running("pillar-jump");
        }
        // Wait until the player's feet have cleared the new block. Placing at
        // half-jump height would put a full cube through the player hitbox.
        if (!placed && bot.getLocation().getY() >= startY + 1.01) {
            if (!withinReach(bot, block) || !placeCarriedBlock(bot, block)) {
                return fail(bot, "pillar-place-refused");
            }
            placed = true;
            placedTick = bot.getAliveTicks();
        }
        if (placed && bot.isBotOnGround()
                && bot.getLocation().getY() >= current.to().y() - 0.1) {
            return succeed(bot, "pillared");
        }
        if (bot.getAliveTicks() - startedTick > 24) return fail(bot, "pillar-timeout");
        return Outcome.running(placed ? "pillar-landing" : "pillar-rising");
    }

    private Outcome tickClutch(Bot bot, BukkitNavigationContext context) {
        Block landing = context.liveBlockAt(current.actionPos());
        if (landing == null || (!placed && landing.getType() != originalActionMaterial)) {
            return fail(bot, "clutch-landing-changed");
        }

        steerToward(bot, current.to(), 0.34);
        bot.look(BlockFace.DOWN);
        int elapsed = bot.getAliveTicks() - startedTick;

        if (!placed) {
            boolean descending = bot.getVelocity().getY() < -0.05;
            if (!descending && elapsed > 12) return fail(bot, "did-not-leave-edge");
            double aboveLanding = bot.getLocation().getY() - current.to().y();
            if (descending && aboveLanding <= 3.25
                    && withinReach(bot, landing)
                    && clearInteractionRay(bot, landing, landing.getRelative(BlockFace.DOWN))) {
                boolean placedClutch = bot.getDimension() == World.Environment.NETHER
                        ? placeNetherClutch(bot, landing)
                        : emptyWaterBucket(bot, landing);
                if (!placedClutch) return fail(bot, "clutch-placement-refused");
                placed = true;
                placedTick = bot.getAliveTicks();
            }
            if (bot.getLocation().getY() < current.to().y() - 1.0) {
                return fail(bot, "missed-landing");
            }
            return Outcome.running("clutch-falling");
        }

        boolean landed = bot.isBotOnGround() || bot.isBotInWater()
                || bot.getLocation().getY() <= current.to().y() + 0.35;
        if (!landed) return Outcome.running("clutch-landing");
        if (bot.getDimension() != World.Environment.NETHER
                && player(bot).getGameMode() != GameMode.CREATIVE) {
            if (bot.getAliveTicks() - placedTick < 5) {
                return Outcome.running("clutch-water-settle");
            }
            if (!fillWaterBucket(bot, landing)) return fail(bot, "water-pickup-refused");
        }
        return succeed(bot, "clutched");
    }

    private Block verifiedBlock(Bot bot, BukkitNavigationContext context, boolean lineOfSight) {
        Block block = context.liveBlockAt(current.actionPos());
        if (block == null || block.getType() != originalActionMaterial) return null;
        if (!authorized(bot, block.getLocation()) || !withinReach(bot, block)) return null;
        if (lineOfSight && !clearInteractionRay(bot, block, block)) return null;
        return block;
    }

    private Block verifiedReplaceable(Bot bot, BukkitNavigationContext context) {
        Block block = context.liveBlockAt(current.actionPos());
        if (block == null || block.getType() != originalActionMaterial) return null;
        if (!context.cell(current.actionPos()).replaceable()) return null;
        Block anchor = placementAnchor(block);
        if (anchor == null || !authorized(bot, block.getLocation()) || !withinReach(bot, block)) return null;
        if (!clearInteractionRay(bot, block, anchor)) return null;
        return block;
    }

    private boolean placeCarriedBlock(Bot bot, Block target) {
        ItemStack held = bot.getBotInventory().raw().getItem(leasedHotbarSlot);
        if (held == null || held.getType() == Material.AIR || !validBuildingBlock(held.getType())) return false;
        if (!target.canPlace(held.getType().createBlockData())) return false;
        Block against = placementAnchor(target);
        if (against == null) return false;
        if (!withinReach(bot, target) || !clearInteractionRay(bot, target, against)) return false;
        player(bot).swingMainHand();
        BlockState replaced = target.getState();
        BlockPlaceEvent event = new BlockPlaceEvent(target, replaced, against, held.clone(),
                player(bot), true, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || !event.canBuild()) return false;
        if (target.getType() != replaced.getType()) return false;
        if (!leasedItemIs(bot, held.getType())) return false;
        if (player(bot).getGameMode() != GameMode.CREATIVE
                && !bot.getBotInventory().decrementMainInventorySlot(leasedHotbarSlot, 1)) return false;
        target.setType(held.getType(), true);
        target.getWorld().playSound(target.getLocation(), target.getBlockData().getSoundGroup().getPlaceSound(),
                SoundCategory.BLOCKS, 1.0f, 1.0f);
        return true;
    }

    private boolean emptyWaterBucket(Bot bot, Block target) {
        if (leasedHotbarSlot < 0) return false;
        ItemStack held = bot.getBotInventory().raw().getItem(leasedHotbarSlot);
        if (held == null || held.getType() != Material.WATER_BUCKET) return false;
        if (!target.canPlace(Material.WATER.createBlockData())) return false;
        player(bot).swingMainHand();
        Block support = target.getRelative(BlockFace.DOWN);
        PlayerBucketEmptyEvent event = new PlayerBucketEmptyEvent(player(bot), target, support,
                BlockFace.UP, Material.WATER_BUCKET, held.clone(), EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return false;
        if (target.getType() != originalActionMaterial
                || !leasedItemIs(bot, Material.WATER_BUCKET)) return false;
        target.setType(Material.WATER, true);
        if (player(bot).getGameMode() != GameMode.CREATIVE) {
            bot.getBotInventory().setMainInventorySlot(leasedHotbarSlot, new ItemStack(Material.BUCKET));
        }
        target.getWorld().playSound(target.getLocation(), Sound.ITEM_BUCKET_EMPTY,
                SoundCategory.PLAYERS, 1.0f, 1.0f);
        return true;
    }

    private boolean placeNetherClutch(Bot bot, Block target) {
        if (leasedHotbarSlot < 0) return false;
        ItemStack held = bot.getBotInventory().raw().getItem(leasedHotbarSlot);
        if (held == null || held.getType() != Material.TWISTING_VINES) return false;
        if (!target.canPlace(Material.TWISTING_VINES.createBlockData())) return false;
        player(bot).swingMainHand();
        Block against = target.getRelative(BlockFace.DOWN);
        if (against.isPassable()) return false;
        BlockState replaced = target.getState();
        BlockPlaceEvent event = new BlockPlaceEvent(target, replaced, against, held.clone(),
                player(bot), true, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || !event.canBuild()) return false;
        if (target.getType() != originalActionMaterial
                || !leasedItemIs(bot, Material.TWISTING_VINES)) return false;
        if (player(bot).getGameMode() != GameMode.CREATIVE
                && !bot.getBotInventory().decrementMainInventorySlot(leasedHotbarSlot, 1)) return false;
        target.setType(Material.TWISTING_VINES, true);
        target.getWorld().playSound(target.getLocation(), Sound.BLOCK_WEEPING_VINES_PLACE,
                SoundCategory.BLOCKS, 1.0f, 1.0f);
        return true;
    }

    private boolean fillWaterBucket(Bot bot, Block water) {
        ItemStack held = bot.getBotInventory().raw().getItem(leasedHotbarSlot);
        if (held == null || held.getType() != Material.BUCKET) return false;
        if (water.getType() != Material.WATER
                || !authorized(bot, water.getLocation())
                || !withinReach(bot, water)
                || !clearInteractionRay(bot, water, water.getRelative(BlockFace.DOWN))) return false;
        player(bot).swingMainHand();
        Block support = water.getRelative(BlockFace.DOWN);
        PlayerBucketFillEvent event = new PlayerBucketFillEvent(player(bot), water, support,
                BlockFace.UP, Material.BUCKET, held.clone(), EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || water.getType() != Material.WATER
                || !leasedItemIs(bot, Material.BUCKET)) return false;
        water.setType(Material.AIR, true);
        bot.getBotInventory().setMainInventorySlot(leasedHotbarSlot, new ItemStack(Material.WATER_BUCKET));
        water.getWorld().playSound(water.getLocation(), Sound.ITEM_BUCKET_FILL,
                SoundCategory.PLAYERS, 1.0f, 1.0f);
        return true;
    }

    private boolean leasedItemIs(Bot bot, Material material) {
        if (leasedHotbarSlot < 0) return false;
        ItemStack currentItem = bot.getBotInventory().raw().getItem(leasedHotbarSlot);
        return currentItem != null && currentItem.getType() == material && currentItem.getAmount() > 0;
    }

    private void recoverPlacedWater(Bot bot) {
        if (bot == null || current == null || !placed || leasedHotbarSlot < 0
                || current.kind() != MovementV2Planner.Kind.CLUTCH_DROP
                || bot.getDimension() == World.Environment.NETHER
                || player(bot).getGameMode() == GameMode.CREATIVE) return;
        MovementV2Planner.Pos pos = current.actionPos();
        Block water = bot.getBukkitEntity().getWorld().getBlockAt(pos.x(), pos.y(), pos.z());
        fillWaterBucket(bot, water);
    }

    private Outcome succeed(Bot bot, String reason) {
        bot.getActionController().complete(bot, reason);
        restoreInventory(bot);
        CombatDebugger.log(bot, "movement-v2-action-complete",
                "kind=" + (current == null ? "none" : current.kind()) + " result=" + reason);
        clear();
        return Outcome.success(reason);
    }

    private Outcome fail(Bot bot, String reason) {
        if (bot != null) {
            bot.getActionController().interrupt(bot, reason);
            CombatDebugger.log(bot, "movement-v2-action-failed",
                    "kind=" + (current == null ? "none" : current.kind()) + " reason=" + reason);
        }
        restoreInventory(bot);
        clear();
        return Outcome.failed(reason);
    }

    private void restoreInventory(Bot bot) {
        if (bot == null) {
            clearLease();
            return;
        }
        BotInventory inventory = bot.getBotInventory();
        if (sourceInventorySlot >= BotInventory.HOTBAR_SIZE
                && leasedHotbarSlot >= 0
                && sourceInventorySlot != leasedHotbarSlot) {
            inventory.swapMainInventorySlots(sourceInventorySlot, leasedHotbarSlot);
        }
        inventory.restoreSelectedSlot(previousSelectedSlot);
        inventory.refreshSelectedItem();
        clearLease();
    }

    private void clearLease() {
        previousSelectedSlot = -1;
        sourceInventorySlot = -1;
        leasedHotbarSlot = -1;
        leasedMaterial = Material.AIR;
    }

    private void clear() {
        current = null;
        startedTick = 0;
        originalActionMaterial = Material.AIR;
        requiredTicks = 0;
        startY = 0.0;
        placed = false;
        placedTick = -1;
        clearLease();
    }

    private static int durationFor(Bot bot, MovementV2Planner.Step request, Block block) {
        return switch (request.kind()) {
            case OPEN -> 2;
            case PLACE_BRIDGE -> 4;
            case PILLAR -> 20;
            case CLUTCH_DROP -> 80;
            case BREAK -> {
                float speed = block.getBreakSpeed(player(bot));
                if (!Float.isFinite(speed) || speed <= 0.0f) yield ACTION_TIMEOUT + 1;
                double ticks = Math.ceil(1.0 / speed);
                yield ticks > Integer.MAX_VALUE ? ACTION_TIMEOUT + 1 : Math.max(1, (int) ticks);
            }
            default -> 1;
        };
    }

    public static int countBuildingBlocks(BotInventory inventory) {
        if (inventory == null) return 0;
        int count = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = inventory.raw().getItem(slot);
            if (item != null && validBuildingBlock(item.getType())) count += item.getAmount();
        }
        return count;
    }

    public static boolean hasClutchItem(Bot bot) {
        return bot != null && clutchItemSlot(bot) >= 0;
    }

    private static int findBuildingBlockSlot(BotInventory inventory) {
        for (Material preferred : PREFERRED_BUILDING_BLOCKS) {
            int slot = inventory.findMainInventory(preferred);
            if (slot >= 0) return slot;
        }
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = inventory.raw().getItem(slot);
            if (item != null && validBuildingBlock(item.getType())) return slot;
        }
        return -1;
    }

    private static int clutchItemSlot(Bot bot) {
        Material required = bot.getDimension() == World.Environment.NETHER
                ? Material.TWISTING_VINES
                : Material.WATER_BUCKET;
        return bot.getBotInventory().findMainInventory(required);
    }

    private static int findBestToolSlot(Bot bot, Block block) {
        BotInventory inventory = bot.getBotInventory();
        int best = inventory.getSelectedHotbarSlot();
        double bestScore = breakScore(block, inventory.raw().getItem(best));
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = inventory.raw().getItem(slot);
            double score = breakScore(block, item);
            if (score > bestScore + 1.0e-6) {
                best = slot;
                bestScore = score;
            }
        }
        return best;
    }

    private static double breakScore(Block block, ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return 1.0;
        double speed = Math.max(0.0, block.getDestroySpeed(item));
        int efficiency = item.getEnchantmentLevel(Enchantment.EFFICIENCY);
        if (speed > 1.0 && efficiency > 0) speed += efficiency * efficiency + 1.0;
        return speed + (block.isPreferredTool(item) ? 0.001 : 0.0);
    }

    private static boolean validBuildingBlock(Material material) {
        if (material == null || !material.isItem() || !material.isBlock() || !material.isSolid()) return false;
        String name = material.name();
        return !FALLING_BLOCKS.contains(name)
                && !name.contains("SHULKER_BOX")
                && !name.endsWith("_BED")
                && !name.contains("CHEST")
                && !name.contains("SPAWNER")
                && material != Material.TNT;
    }

    private static boolean authorized(Bot bot, Location location) {
        if (location == null || location.getWorld() == null) return false;
        if (!location.getWorld().getWorldBorder().isInside(location)) return false;
        Player player = player(bot);
        if (player.isOp()) return true;
        int radius = Bukkit.getServer().getSpawnRadius();
        if (radius <= 0 || location.getWorld().getEnvironment() != World.Environment.NORMAL) return true;
        Location spawn = location.getWorld().getSpawnLocation();
        return Math.max(Math.abs(location.getBlockX() - spawn.getBlockX()),
                Math.abs(location.getBlockZ() - spawn.getBlockZ())) > radius;
    }

    private static boolean withinReach(Bot bot, Block block) {
        return player(bot).getEyeLocation().distanceSquared(block.getLocation().add(0.5, 0.5, 0.5))
                <= REACH * REACH;
    }

    private static boolean clearInteractionRay(Bot bot, Block target, Block allowedAnchor) {
        Location eye = player(bot).getEyeLocation();
        Vector direction = target.getLocation().add(0.5, 0.5, 0.5).toVector().subtract(eye.toVector());
        double distance = direction.length();
        if (distance <= 1.0e-6) return true;
        RayTraceResult hit = eye.getWorld().rayTraceBlocks(eye, direction.normalize(), distance + 0.1,
                FluidCollisionMode.NEVER, true);
        if (hit == null || hit.getHitBlock() == null) return true;
        return hit.getHitBlock().equals(target) || hit.getHitBlock().equals(allowedAnchor);
    }

    private static Block placementAnchor(Block target) {
        for (BlockFace face : new BlockFace[]{BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH,
                BlockFace.EAST, BlockFace.WEST, BlockFace.UP}) {
            Block candidate = target.getRelative(face);
            if (!candidate.isPassable()) return candidate;
        }
        return null;
    }

    private static void syncDoorHalf(Block block, boolean open) {
        if (!(block.getBlockData() instanceof Door door)) return;
        Block other = door.getHalf() == Bisected.Half.TOP
                ? block.getRelative(BlockFace.DOWN)
                : block.getRelative(BlockFace.UP);
        if (other.getBlockData() instanceof Door otherDoor) {
            otherDoor.setOpen(open);
            other.setBlockData(otherDoor, true);
        }
    }

    private static void steerToward(Bot bot, MovementV2Planner.Pos destination, double speed) {
        Vector desired = new Vector(destination.x() + 0.5 - bot.getLocation().getX(), 0.0,
                destination.z() + 0.5 - bot.getLocation().getZ());
        if (desired.lengthSquared() > 1.0e-6) desired.normalize().multiply(speed);
        bot.walk(desired);
    }

    private static Player player(Bot bot) {
        return (Player) bot.getBukkitEntity();
    }

    private static void faceBlock(Bot bot, Block block) {
        bot.faceLocation(block.getLocation().add(0.5, 0.5, 0.5));
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Traversal actions may only run on the server thread");
        }
    }

    public record Outcome(MovementV2Controller.ActionOutcome status, String reason) {
        static Outcome running(String reason) {
            return new Outcome(MovementV2Controller.ActionOutcome.RUNNING, reason);
        }

        static Outcome success(String reason) {
            return new Outcome(MovementV2Controller.ActionOutcome.SUCCESS, reason);
        }

        static Outcome failed(String reason) {
            return new Outcome(MovementV2Controller.ActionOutcome.FAILED, reason);
        }
    }
}
