package net.nuggetmc.tplus.compat.bukkit;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.nuggetmc.tplus.compat.bukkit.block.Block;
import net.nuggetmc.tplus.compat.bukkit.entity.EntityBridge;
import java.util.*;

public class World {
    public enum Environment { NORMAL, NETHER, THE_END }
    private final ServerLevel handle;
    public World(ServerLevel handle){this.handle=Objects.requireNonNull(handle);}
    public ServerLevel getHandle(){return handle;} public String getName(){return handle.dimension().location().toString();}
    public Environment getEnvironment(){String id=handle.dimension().location().toString();return id.endsWith("the_nether")?Environment.NETHER:id.endsWith("the_end")?Environment.THE_END:Environment.NORMAL;}
    public Block getBlockAt(int x,int y,int z){return new Block(handle,new BlockPos(x,y,z));} public Block getBlockAt(BlockPos pos){return new Block(handle,pos);} public Block getBlockAt(Location location){return location==null?null:getBlockAt(location.getBlockX(),location.getBlockY(),location.getBlockZ());}
    public List<net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity> getLivingEntities(){List<net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity> out=new ArrayList<>();for(Entity e:handle.getAllEntities())if(e instanceof LivingEntity l)out.add(EntityBridge.living(l));return out;}
    public List<net.nuggetmc.tplus.compat.bukkit.entity.Player> getPlayers(){List<net.nuggetmc.tplus.compat.bukkit.entity.Player> out=new ArrayList<>();for(net.minecraft.server.level.ServerPlayer p:handle.players())out.add(EntityBridge.player(p));return out;}
    public void spawnParticle(Particle particle,Location loc,int count,double ox,double oy,double oz,double extra){handle.sendParticles(net.minecraft.core.particles.ParticleTypes.POOF,loc.getX(),loc.getY(),loc.getZ(),count,ox,oy,oz,extra);}
    public void playSound(Location loc,Sound sound,float volume,float pitch){handle.playSound(null,loc.getX(),loc.getY(),loc.getZ(),net.minecraft.sounds.SoundEvents.STONE_PLACE,net.minecraft.sounds.SoundSource.PLAYERS,volume,pitch);}
    public void playSound(Location loc,Sound sound,SoundCategory category,float volume,float pitch){playSound(loc,sound,volume,pitch);}
    public int getMinHeight(){return handle.getMinBuildHeight();} public int getMaxHeight(){return handle.getMaxBuildHeight();}
    public Block getHighestBlockAt(int x,int z){int y=handle.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)-1;return getBlockAt(x,y,z);}
    public Block getHighestBlockAt(int x,int z,HeightMap map){
        var type=switch(map==null?HeightMap.MOTION_BLOCKING_NO_LEAVES:map){case MOTION_BLOCKING->net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING;case OCEAN_FLOOR->net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR;case WORLD_SURFACE->net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE;default->net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES;};
        return getBlockAt(x,handle.getHeight(type,x,z)-1,z);
    }
    public WorldBorder getWorldBorder(){return new WorldBorder(handle.getWorldBorder());}
    public boolean isChunkLoaded(int x,int z){return handle.hasChunk(x,z);}
    public net.nuggetmc.tplus.compat.bukkit.util.RayTraceResult rayTraceBlocks(Location start, net.nuggetmc.tplus.compat.bukkit.util.Vector direction, double maxDistance, FluidCollisionMode fluidMode, boolean ignorePassable){
        if(start==null||direction==null||maxDistance<=0) return null;
        net.minecraft.world.phys.Vec3 from=new net.minecraft.world.phys.Vec3(start.getX(),start.getY(),start.getZ());
        net.minecraft.world.phys.Vec3 to=from.add(direction.getX(),direction.getY(),direction.getZ()).normalize().scale(maxDistance);
        to=from.add(to);
        ClipContext.Block blockMode=ignorePassable?ClipContext.Block.COLLIDER:ClipContext.Block.OUTLINE;
        ClipContext.Fluid fluid=fluidMode==FluidCollisionMode.NEVER?ClipContext.Fluid.NONE:fluidMode==FluidCollisionMode.SOURCE_ONLY?ClipContext.Fluid.SOURCE_ONLY:ClipContext.Fluid.ANY;
        BlockHitResult hit=handle.clip(new ClipContext(from,to,blockMode,fluid,net.minecraft.world.phys.shapes.CollisionContext.empty()));
        if(hit==null||hit.getType()==net.minecraft.world.phys.HitResult.Type.MISS) return null;
        BlockPos pos=hit.getBlockPos();
        return new net.nuggetmc.tplus.compat.bukkit.util.RayTraceResult(new net.nuggetmc.tplus.compat.bukkit.util.Vector(hit.getLocation().x,hit.getLocation().y,hit.getLocation().z),getBlockAt(pos),null);
    }
    public Location getSpawnLocation(){BlockPos p=handle.getSharedSpawnPos();return new Location(this,p.getX(),p.getY(),p.getZ());}
    public void dropItem(Location loc,net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack stack){if(stack!=null&&!stack.isEmpty())handle.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(handle,loc.getX(),loc.getY(),loc.getZ(),stack.asNmsCopy()));}
    public <T extends net.nuggetmc.tplus.compat.bukkit.entity.Entity> T spawn(Location loc,Class<T> type,java.util.function.Consumer<T> configure){
        net.minecraft.world.entity.item.ItemEntity marker=new net.minecraft.world.entity.item.ItemEntity(handle,loc.getX(),loc.getY(),loc.getZ(),net.minecraft.world.item.ItemStack.EMPTY);
        net.nuggetmc.tplus.compat.bukkit.entity.Entity wrapped;
        if(type==net.nuggetmc.tplus.compat.bukkit.entity.Arrow.class) wrapped=new net.nuggetmc.tplus.compat.bukkit.entity.Arrow(marker);
        else if(type==net.nuggetmc.tplus.compat.bukkit.entity.EnderCrystal.class) wrapped=new net.nuggetmc.tplus.compat.bukkit.entity.EnderCrystal(marker);
        else if(type==net.nuggetmc.tplus.compat.bukkit.entity.EnderPearl.class) wrapped=new net.nuggetmc.tplus.compat.bukkit.entity.EnderPearl(marker);
        else if(type==net.nuggetmc.tplus.compat.bukkit.entity.Firework.class) wrapped=new net.nuggetmc.tplus.compat.bukkit.entity.Firework(marker);
        else if(type==net.nuggetmc.tplus.compat.bukkit.entity.Trident.class) wrapped=new net.nuggetmc.tplus.compat.bukkit.entity.Trident(marker);
        else if(type==net.nuggetmc.tplus.compat.bukkit.entity.WindCharge.class) wrapped=new net.nuggetmc.tplus.compat.bukkit.entity.WindCharge(marker);
        else if(type==net.nuggetmc.tplus.compat.bukkit.entity.SplashPotion.class) wrapped=new net.nuggetmc.tplus.compat.bukkit.entity.SplashPotion(marker);
        else if(type==net.nuggetmc.tplus.compat.bukkit.entity.ThrownPotion.class) wrapped=new net.nuggetmc.tplus.compat.bukkit.entity.ThrownPotion(marker);
        else if(type==net.nuggetmc.tplus.compat.bukkit.entity.ThrownExpBottle.class) wrapped=new net.nuggetmc.tplus.compat.bukkit.entity.ThrownExpBottle(marker);
        else if(type==net.nuggetmc.tplus.compat.bukkit.entity.ArmorStand.class) wrapped=new net.nuggetmc.tplus.compat.bukkit.entity.ArmorStand(marker);
        else wrapped=new net.nuggetmc.tplus.compat.bukkit.entity.Entity(marker);
        @SuppressWarnings("unchecked") T result=(T)wrapped; if(configure!=null)configure.accept(result); return result;
    }
    public net.nuggetmc.tplus.compat.bukkit.entity.Entity spawnEntity(Location loc,net.nuggetmc.tplus.compat.bukkit.entity.EntityType type){if(type==net.nuggetmc.tplus.compat.bukkit.entity.EntityType.OAK_BOAT)return new net.nuggetmc.tplus.compat.bukkit.entity.Boat(new net.minecraft.world.entity.item.ItemEntity(handle,loc.getX(),loc.getY(),loc.getZ(),net.minecraft.world.item.ItemStack.EMPTY));return spawn(loc,net.nuggetmc.tplus.compat.bukkit.entity.Entity.class,null);}
    public void createExplosion(Location loc,float power,boolean fire,boolean breakBlocks,net.nuggetmc.tplus.compat.bukkit.entity.Entity source){handle.explode(source==null?null:source.getHandle(),loc.getX(),loc.getY(),loc.getZ(),power,fire,breakBlocks?net.minecraft.world.level.Level.ExplosionInteraction.BLOCK:net.minecraft.world.level.Level.ExplosionInteraction.NONE);}
}
