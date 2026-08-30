package net.nuggetmc.tplus.bot.navigation;

import net.nuggetmc.tplus.compat.bukkit.Bukkit;
import net.nuggetmc.tplus.compat.bukkit.Location;
import net.nuggetmc.tplus.compat.bukkit.Material;
import net.nuggetmc.tplus.compat.bukkit.World;
import net.nuggetmc.tplus.compat.bukkit.block.Block;
import net.nuggetmc.tplus.compat.bukkit.block.data.BlockData;
import net.nuggetmc.tplus.compat.bukkit.block.data.Openable;
import net.nuggetmc.tplus.compat.bukkit.block.data.Waterlogged;
import net.nuggetmc.tplus.compat.bukkit.util.BoundingBox;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Main-thread, read-only view of the bot's current chunk and its eight
 * neighbours. The planner cannot see or search outside this 3x3 window.
 */
public final class BukkitNavigationContext implements MovementV2Planner.WorldView {

    public static final int CHUNK_RADIUS = MovementV2Planner.CONTEXT_CHUNK_RADIUS;
    public static final int CHUNK_COUNT = MovementV2Planner.CONTEXT_CHUNK_COUNT;

    private static final Set<String> HAZARDS = Set.of(
            "LAVA", "FIRE", "SOUL_FIRE", "CACTUS", "SWEET_BERRY_BUSH",
            "MAGMA_BLOCK", "POWDER_SNOW", "CAMPFIRE", "SOUL_CAMPFIRE",
            "WITHER_ROSE", "POINTED_DRIPSTONE", "END_PORTAL", "NETHER_PORTAL",
            "COBWEB", "LADDER", "VINE", "WEEPING_VINES", "TWISTING_VINES",
            "WEEPING_VINES_PLANT", "TWISTING_VINES_PLANT", "SCAFFOLDING",
            "WATER", "BUBBLE_COLUMN", "KELP", "KELP_PLANT", "SEAGRASS", "TALL_SEAGRASS"
    );
    private final World world;
    private final int centerChunkX;
    private final int centerChunkZ;
    private final int minX;
    private final int maxX;
    private final int minZ;
    private final int maxZ;
    private final double borderMinX;
    private final double borderMaxX;
    private final double borderMinZ;
    private final double borderMaxZ;
    private final Map<MovementV2Planner.Pos, MovementV2Planner.Cell> cells = new HashMap<>();

    public BukkitNavigationContext(Location center) {
        requirePrimaryThread();
        if (center == null || center.getWorld() == null) {
            throw new IllegalArgumentException("Navigation context requires a world location");
        }
        this.world = center.getWorld();
        this.centerChunkX = center.getBlockX() >> 4;
        this.centerChunkZ = center.getBlockZ() >> 4;
        this.minX = (centerChunkX - CHUNK_RADIUS) << 4;
        this.maxX = ((centerChunkX + CHUNK_RADIUS + 1) << 4) - 1;
        this.minZ = (centerChunkZ - CHUNK_RADIUS) << 4;
        this.maxZ = ((centerChunkZ + CHUNK_RADIUS + 1) << 4) - 1;
        Location borderCenter = world.getWorldBorder().getCenter();
        double halfBorder = world.getWorldBorder().getSize() * 0.5;
        this.borderMinX = borderCenter.getX() - halfBorder;
        this.borderMaxX = borderCenter.getX() + halfBorder;
        this.borderMinZ = borderCenter.getZ() - halfBorder;
        this.borderMaxZ = borderCenter.getZ() + halfBorder;
    }

    public int centerChunkX() {
        return centerChunkX;
    }

    public int centerChunkZ() {
        return centerChunkZ;
    }

    /** Project a distant target onto this bot's current 3x3 planning window. */
    public MovementV2Planner.Pos projectInside(MovementV2Planner.Pos target) {
        requirePrimaryThread();
        if (target == null) throw new IllegalArgumentException("Target position is required");
        int x = Math.max(minX, Math.min(maxX, target.x()));
        int y = Math.max(world.getMinHeight() + 1, Math.min(world.getMaxHeight() - 2, target.y()));
        int z = Math.max(minZ, Math.min(maxZ, target.z()));
        return new MovementV2Planner.Pos(x, y, z);
    }

    @Override
    public boolean inBounds(MovementV2Planner.Pos pos) {
        requirePrimaryThread();
        if (pos == null) return false;
        if (pos.x() < minX || pos.x() > maxX || pos.z() < minZ || pos.z() > maxZ) return false;
        double centerX = pos.x() + 0.5;
        double centerZ = pos.z() + 0.5;
        if (centerX < borderMinX || centerX > borderMaxX
                || centerZ < borderMinZ || centerZ > borderMaxZ) return false;
        if (pos.y() < world.getMinHeight() || pos.y() >= world.getMaxHeight()) return false;
        return world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4);
    }

    @Override
    public MovementV2Planner.Cell cell(MovementV2Planner.Pos pos) {
        if (!inBounds(pos)) return MovementV2Planner.Cell.UNAVAILABLE;
        requirePrimaryThread();
        MovementV2Planner.Cell cached = cells.get(pos);
        if (cached != null) return cached;
        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        Material material = block.getType();
        String name = material.name();
        BlockData data = block.getBlockData();
        boolean hazard = HAZARDS.contains(name)
                || block.isLiquid()
                || (data instanceof Waterlogged waterlogged && waterlogged.isWaterlogged());
        boolean passable = block.isPassable();
        boolean standable = !passable && !hazard && hasFullHeightTop(block);
        boolean openable = data instanceof Openable
                && !name.startsWith("IRON_");
        boolean breakable = !material.isAir() && material.getHardness() >= 0.0f;
        boolean replaceable = block.isReplaceable();
        MovementV2Planner.Cell result = new MovementV2Planner.Cell(true, passable, standable, hazard,
                openable, breakable, replaceable);
        cells.put(pos, result);
        return result;
    }

    public Block liveBlockAt(MovementV2Planner.Pos pos) {
        if (!inBounds(pos)) return null;
        requirePrimaryThread();
        return world.getBlockAt(pos.x(), pos.y(), pos.z());
    }

    private static boolean hasFullHeightTop(Block block) {
        BoundingBox box = block.getBoundingBox();
        if (box.getVolume() <= 1.0e-7) return false;
        double expectedTop = block.getY() + 1.0;
        return Math.abs(box.getMaxY() - expectedTop) <= 0.03125;
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Live Bukkit navigation context may only be read on the server thread");
        }
    }
}
