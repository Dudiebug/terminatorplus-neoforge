package net.nuggetmc.tplus.compat.bukkit.block;
import net.nuggetmc.tplus.compat.bukkit.util.Vector;
public enum BlockFace { DOWN(0,-1,0),UP(0,1,0),NORTH(0,0,-1),SOUTH(0,0,1),WEST(-1,0,0),EAST(1,0,0),SELF(0,0,0); private final int x,y,z; BlockFace(int x,int y,int z){this.x=x;this.y=y;this.z=z;} public int getModX(){return x;} public int getModY(){return y;} public int getModZ(){return z;} public Vector getDirection(){return new Vector(x,y,z);} }
