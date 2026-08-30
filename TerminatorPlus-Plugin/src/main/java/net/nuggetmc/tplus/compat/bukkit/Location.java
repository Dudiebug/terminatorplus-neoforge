package net.nuggetmc.tplus.compat.bukkit;

import net.nuggetmc.tplus.compat.bukkit.block.Block;
import net.nuggetmc.tplus.compat.bukkit.util.Vector;

public class Location implements Cloneable {
    private World world; private double x,y,z; private float yaw,pitch;
    public Location(World world,double x,double y,double z){this(world,x,y,z,0,0);} public Location(World world,double x,double y,double z,float yaw,float pitch){this.world=world;this.x=x;this.y=y;this.z=z;this.yaw=yaw;this.pitch=pitch;}
    public World getWorld(){return world;} public void setWorld(World w){world=w;} public double getX(){return x;} public double getY(){return y;} public double getZ(){return z;} public float getYaw(){return yaw;} public float getPitch(){return pitch;}
    public void setX(double x){this.x=x;} public void setY(double y){this.y=y;} public void setZ(double z){this.z=z;} public void setYaw(float yaw){this.yaw=yaw;} public void setPitch(float pitch){this.pitch=pitch;}
    public Location add(double x,double y,double z){this.x+=x;this.y+=y;this.z+=z;return this;} public Location add(Vector v){return add(v.getX(),v.getY(),v.getZ());}
    public Location subtract(double x,double y,double z){return add(-x,-y,-z);} public Location subtract(Vector v){return add(-v.getX(),-v.getY(),-v.getZ());}
    public Vector toVector(){return new Vector(x,y,z);} public Block getBlock(){return world == null ? null : world.getBlockAt(getBlockX(),getBlockY(),getBlockZ());}
    public int getBlockX(){return floor(x);} public int getBlockY(){return floor(y);} public int getBlockZ(){return floor(z);} public static int locToBlock(double value){return floor(value);} private static int floor(double v){return (int)Math.floor(v);}
    public double distance(Location other){return Math.sqrt(distanceSquared(other));} public double distanceSquared(Location other){if(other==null||world!=other.world) return Double.POSITIVE_INFINITY;double dx=x-other.x,dy=y-other.y,dz=z-other.z;return dx*dx+dy*dy+dz*dz;}
    public Vector getDirection(){double pitchRad=Math.toRadians(pitch),yawRad=Math.toRadians(yaw);double xz=-Math.cos(pitchRad);return new Vector(-xz*Math.sin(yawRad),-Math.sin(pitchRad),xz*Math.cos(yawRad));}
    public java.util.List<net.nuggetmc.tplus.compat.bukkit.entity.Player> getNearbyPlayers(double radius){
        if(world==null) return java.util.List.of();
        double r=Math.max(0.0,radius), r2=r*r;
        return world.getPlayers().stream().filter(p->p.getLocation().distanceSquared(this)<=r2).toList();
    }
    @Override public Location clone(){return new Location(world,x,y,z,yaw,pitch);} @Override public String toString(){return "Location{"+(world==null?"null":world.getName())+","+x+","+y+","+z+"}";}
}
