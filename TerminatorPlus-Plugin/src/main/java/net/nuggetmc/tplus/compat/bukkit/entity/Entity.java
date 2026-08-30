package net.nuggetmc.tplus.compat.bukkit.entity;

import net.nuggetmc.tplus.compat.bukkit.Location;
import net.nuggetmc.tplus.compat.bukkit.World;
import net.nuggetmc.tplus.compat.bukkit.util.BoundingBox;
import net.nuggetmc.tplus.compat.bukkit.util.Vector;
import java.util.*;

public class Entity {
    protected final net.minecraft.world.entity.Entity handle;
    public Entity(net.minecraft.world.entity.Entity handle){this.handle=Objects.requireNonNull(handle);}
    public net.minecraft.world.entity.Entity getHandle(){return handle;} public int getEntityId(){return handle.getId();} public UUID getUniqueId(){return handle.getUUID();}
    public Location getLocation(){return new Location(new World((net.minecraft.server.level.ServerLevel)handle.level()),handle.getX(),handle.getY(),handle.getZ(),handle.getYRot(),handle.getXRot());}
    public Location getEyeLocation(){return getLocation().add(0,handle.getEyeHeight(),0);} public World getWorld(){return new World((net.minecraft.server.level.ServerLevel)handle.level());}
    public Vector getVelocity(){var v=handle.getDeltaMovement();return new Vector(v.x,v.y,v.z);} public void setVelocity(Vector v){handle.setDeltaMovement(v.getX(),v.getY(),v.getZ());}
    public boolean teleport(Location loc){if(loc==null)return false;handle.teleportTo(loc.getX(),loc.getY(),loc.getZ());handle.setYRot(loc.getYaw());handle.setXRot(loc.getPitch());return true;}
    public boolean teleport(Entity other){return other!=null&&teleport(other.getLocation());}
    public void remove(){handle.discard();} public boolean isDead(){return !handle.isAlive();} public BoundingBox getBoundingBox(){var a=handle.getBoundingBox();return new BoundingBox(a.minX,a.minY,a.minZ,a.maxX,a.maxY,a.maxZ);}
    public String getName(){return handle.getName().getString();} public String getCustomName(){return handle.getCustomName()==null?null:handle.getCustomName().getString();}
    public EntityType getType(){return EntityType.fromNms(handle);} public List<Entity> getNearbyEntities(double x,double y,double z){List<Entity> out=new ArrayList<>();for(var e:handle.level().getEntities(handle,handle.getBoundingBox().inflate(x,y,z)))out.add(EntityBridge.wrap(e));return out;}
    public void setFireTicks(int ticks){handle.setRemainingFireTicks(ticks);} public int getFireTicks(){return handle.getRemainingFireTicks();} public boolean isOnGround(){return handle.onGround();} public boolean isInWater(){return handle.isInWater();}
    public boolean isValid(){return handle.isAlive();} public void setPassenger(Entity passenger){if(passenger!=null)passenger.getHandle().startRiding(handle,true);} public void addPassenger(Entity passenger){if(passenger!=null)passenger.getHandle().startRiding(handle,true);} public java.util.List<Entity> getPassengers(){return handle.getPassengers().stream().map(EntityBridge::wrap).toList();}
}
