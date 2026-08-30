package net.nuggetmc.tplus.api.agent.legacyagent;

import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.api.utils.BotUtils;

import net.nuggetmc.tplus.compat.bukkit.*;
import net.nuggetmc.tplus.compat.bukkit.block.Block;
import net.nuggetmc.tplus.compat.bukkit.block.BlockFace;
import net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity;
import net.nuggetmc.tplus.compat.bukkit.entity.Player;
import net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack;
import net.nuggetmc.tplus.compat.bukkit.plugin.Plugin;
import net.nuggetmc.tplus.compat.bukkit.util.BoundingBox;

import com.google.common.base.Optional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class LegacyBlockCheck {

    private final LegacyAgent agent;

    public LegacyBlockCheck(LegacyAgent agent, Plugin plugin) {
        this.agent = agent;
    }

    // Guard for the clutch-path punches below. A bot mid-clutch is placing a
    // block under its feet, not attacking — but the punch animation still
    // burns the vanilla attack-strength ticker and shows up in logs as a
    // wasted swing. Only emit the animation if a real target is in melee
    // range AND the bot is holding a weapon that could actually damage it.
    private static boolean isMeleeInHand(Terminator bot) {
        if (!(bot.getBukkitEntity() instanceof Player player)) return false;
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null) return false;
        Material m = held.getType();
        if (m == Material.AIR) return false;
        if (m == Material.MACE || m == Material.TRIDENT) return true;
        String name = m.name();
        return name.endsWith("_SWORD") || name.endsWith("_AXE");
    }

    private static boolean targetInPunchRange(Terminator bot, LivingEntity target) {
        if (target == null || !target.isValid() || target.isDead()) return false;
        return bot.getLocation().distanceSquared(target.getLocation()) <= 16.0; // 4-block radius
    }

    private void placeFinal(Location loc) {
        Material placementMaterial = agent.getPlacementMaterial();
        if (loc.getBlock().getType() != placementMaterial) {
            for (Player all : Bukkit.getOnlinePlayers())
                all.playSound(loc, Sound.BLOCK_STONE_PLACE, SoundCategory.BLOCKS, 1, 1);
            // The old path wrote a temporary block ItemStack here, which wiped
            // the selected hotbar slot. World placement is logical state only.
            loc.getBlock().setType(placementMaterial);

            Block under = loc.clone().add(0, -1, 0).getBlock();
            if (under.getType() == Material.LAVA) {
                under.setType(placementMaterial);
            }
        }
    }

    public void placeBlock(Terminator bot, LivingEntity player, Block block) {

        Location loc = block.getLocation();

        Block under = loc.clone().add(0, -1, 0).getBlock();

        if (LegacyMats.SPAWN.contains(under.getType())) {
            placeFinal(loc.clone().add(0, -1, 0));
            agent.scheduleLegacyTaskLater(() -> {
                placeFinal(block.getLocation());
            }, 2);
        }

        Set<Block> face = new HashSet<>(Arrays.asList(loc.clone().add(1, 0, 0).getBlock(),
                loc.clone().add(-1, 0, 0).getBlock(),
                loc.clone().add(0, 0, 1).getBlock(),
                loc.clone().add(0, 0, -1).getBlock()));

        boolean a = face.stream().anyMatch(side -> !LegacyMats.SPAWN.contains(side.getType()));

        if (a) {
            placeFinal(block.getLocation());
            return;
        }

        Set<Block> edge = new HashSet<>(Arrays.asList(loc.clone().add(1, -1, 0).getBlock(),
                loc.clone().add(-1, -1, 0).getBlock(),
                loc.clone().add(0, -1, 1).getBlock(),
                loc.clone().add(0, -1, -1).getBlock()));

        boolean b = edge.stream().anyMatch(side -> !LegacyMats.SPAWN.contains(side.getType()));

        if (b && LegacyMats.SPAWN.contains(under.getType())) {
            placeFinal(loc.clone().add(0, -1, 0));
            agent.scheduleLegacyTaskLater(() -> {
                placeFinal(block.getLocation());
            }, 2);
            return;
        }

        Block c1 = loc.clone().add(1, -1, 1).getBlock();
        Block c2 = loc.clone().add(1, -1, -1).getBlock();
        Block c3 = loc.clone().add(-1, -1, 1).getBlock();
        Block c4 = loc.clone().add(-1, -1, -1).getBlock();

        boolean t = false;

        if (!LegacyMats.SPAWN.contains(c1.getType()) || !LegacyMats.SPAWN.contains(c2.getType())) {

            Block b1 = loc.clone().add(1, -1, 0).getBlock();
            if (LegacyMats.SPAWN.contains(b1.getType())) {
                placeFinal(b1.getLocation());
            }

            t = true;

        } else if (!LegacyMats.SPAWN.contains(c3.getType()) || !LegacyMats.SPAWN.contains(c4.getType())) {

            Block b1 = loc.clone().add(-1, -1, 0).getBlock();
            if (LegacyMats.SPAWN.contains(b1.getType())) {
                placeFinal(b1.getLocation());
            }

            t = true;
        }

        if (t) {
            agent.scheduleLegacyTaskLater(() -> {
                Block b2 = loc.clone().add(0, -1, 0).getBlock();
                if (LegacyMats.SPAWN.contains(b2.getType())) {
                    for (Player all : Bukkit.getOnlinePlayers())
                        all.playSound(loc, Sound.BLOCK_STONE_PLACE, SoundCategory.BLOCKS, 1, 1);
                    placeFinal(b2.getLocation());
                }
            }, 1);

            agent.scheduleLegacyTaskLater(() -> {
                for (Player all : Bukkit.getOnlinePlayers())
                    all.playSound(loc, Sound.BLOCK_STONE_PLACE, SoundCategory.BLOCKS, 1, 1);
                placeFinal(block.getLocation());
            }, 3);
            return;
        }

        for (Player all : Bukkit.getOnlinePlayers())
            all.playSound(loc, Sound.BLOCK_STONE_PLACE, SoundCategory.BLOCKS, 1, 1);
        placeFinal(block.getLocation());
    }
    
    public boolean tryPreMLG(Terminator bot, Location botLoc) {
    	if(bot.isBotOnGround() || bot.getVelocity().getY() >= -0.8D || bot.getNoFallTicks() > 7)
    		return false;
    	if (tryPreMLG(bot, botLoc, 3))
    		return true;
    	return tryPreMLG(bot, botLoc, 2);
    }
    
    private boolean tryPreMLG(Terminator bot, Location botLoc, int blocksBelow) {
        BoundingBox box = bot.getBotBoundingBox();
        double[] xVals = new double[]{
                box.getMinX(),
                box.getMaxX() - 0.01
        };

        double[] zVals = new double[]{
                box.getMinZ(),
                box.getMaxZ() - 0.01
        };
        Set<Location> below2Set = new HashSet<>();
        
    	for (double x : xVals) {
            for (double z : zVals) {
            	Location below = botLoc.clone();
            	below.setX(x);
            	below.setZ(z);
            	below.setY(bot.getLocation().getBlockY());
            	for (int i = 0; i < blocksBelow - 1; i++) {
            		below.setY(below.getY() - 1);
            		
            		// Blocks before must all be pass-through
            		Material type = below.getBlock().getType();
            		if (LegacyMats.isSolid(type) || LegacyMats.canStandOn(type))
            			return false;
            	}
            	below.setY(bot.getLocation().getBlockY() - blocksBelow);
            	below2Set.add(below.getBlock().getLocation());
            }
    	}
    	
    	// Second block below must have at least one unplaceable block (that is landable)
    	boolean nether = bot.getDimension() == World.Environment.NETHER;
    	Iterator<Location> itr = below2Set.iterator();
    	while (itr.hasNext()) {
    		Block next = itr.next().getBlock();
    		boolean placeable = nether ? LegacyMats.canPlaceTwistingVines(next)
    			: LegacyMats.canPlaceWater(next, Optional.absent());
    		if (placeable || (!LegacyMats.isSolid(next.getType()) && !LegacyMats.canStandOn(next.getType())))
    			itr.remove();
    	}
    	
    	// Clutch
    	if (!below2Set.isEmpty()) {
    		List<Location> below2List = new ArrayList<>(below2Set);
    		below2List.sort((a, b) -> {
    			Block aBlock = a.clone().add(0, 1, 0).getBlock();
    			Block bBlock = b.clone().add(0, 1, 0).getBlock();
    			if (aBlock.getType().isAir() && !bBlock.getType().isAir())
    				return -1;
    			if (!bBlock.getType().isAir() && aBlock.getType().isAir())
    				return 1;
    			return Double.compare(BotUtils.getHorizSqDist(a, botLoc), BotUtils.getHorizSqDist(b, botLoc));
    		});
    		
    		Location faceLoc = below2List.get(0);
    		Location loc = faceLoc.clone().add(0, 1, 0);
            bot.faceLocation(faceLoc);
            bot.look(BlockFace.DOWN);

            agent.scheduleLegacyTaskLater(() -> {
                bot.faceLocation(faceLoc);
            }, 1);

            if (isMeleeInHand(bot)) {
                bot.punch();
            }
            for (Player all : Bukkit.getOnlinePlayers())
                all.playSound(loc, Sound.BLOCK_STONE_PLACE, SoundCategory.BLOCKS, 1, 1);
            // The old path wrote a temporary block ItemStack here, which wiped
            // the selected hotbar slot. World placement is logical state only.
            loc.getBlock().setType(agent.getPlacementMaterial());
    	}

    	return false;
    }

    public void clutch(Terminator bot, LivingEntity target) {
        Location botLoc = bot.getLocation();

        Material type = botLoc.clone().add(0, -1, 0).getBlock().getType();
        Material type2 = botLoc.clone().add(0, -2, 0).getBlock().getType();

        if (!(LegacyMats.SPAWN.contains(type) && LegacyMats.SPAWN.contains(type2))) return;

        if (target.getLocation().getBlockY() >= botLoc.getBlockY()) {
            Location loc = botLoc.clone().add(0, -1, 0);

            Set<Block> face = new HashSet<>(Arrays.asList(
                    loc.clone().add(1, 0, 0).getBlock(),
                    loc.clone().add(-1, 0, 0).getBlock(),
                    loc.clone().add(0, 0, 1).getBlock(),
                    loc.clone().add(0, 0, -1).getBlock()
            ));

            Location at = null;
            for (Block side : face) {
                if (!LegacyMats.SPAWN.contains(side.getType())) {
                    at = side.getLocation();
                }
            }

            if (at != null) {
                agent.slow.add(bot);
                agent.noFace.add(bot);

                agent.scheduleLegacyTaskLater(() -> {
                    bot.stand();
                    agent.slow.remove(bot);
                }, 12);

                agent.scheduleLegacyTaskLater(() -> {
                    agent.noFace.remove(bot);
                }, 15);

                Location faceLoc = at.clone().add(0, -1.5, 0);

                bot.faceLocation(faceLoc);
                bot.look(BlockFace.DOWN);

                agent.scheduleLegacyTaskLater(() -> {
                    bot.faceLocation(faceLoc);
                }, 1);

                if (isMeleeInHand(bot) && targetInPunchRange(bot, target)) {
                    bot.punch();
                }
                bot.sneak();
                for (Player all : Bukkit.getOnlinePlayers())
                    all.playSound(loc, Sound.BLOCK_STONE_PLACE, SoundCategory.BLOCKS, 1, 1);
                // Placement is logical world state and does not alter the held item.
                loc.getBlock().setType(agent.getPlacementMaterial());
            }
        }
    }
}
