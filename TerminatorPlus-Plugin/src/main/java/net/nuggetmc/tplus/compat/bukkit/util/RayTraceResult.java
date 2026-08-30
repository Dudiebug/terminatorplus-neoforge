package net.nuggetmc.tplus.compat.bukkit.util;
import net.nuggetmc.tplus.compat.bukkit.block.Block; import net.nuggetmc.tplus.compat.bukkit.entity.Entity;
public final class RayTraceResult { private final Vector hitPosition; private final Block hitBlock; private final Entity hitEntity; public RayTraceResult(Vector p,Block b,Entity e){hitPosition=p;hitBlock=b;hitEntity=e;} public Vector getHitPosition(){return hitPosition;} public Block getHitBlock(){return hitBlock;} public Entity getHitEntity(){return hitEntity;} }
