package net.nuggetmc.tplus.compat.bukkit.util;

import net.nuggetmc.tplus.compat.bukkit.Location;
import java.util.Objects;

/** Small mutable vector kept internal while the gameplay code migrates to Vec3. */
public class Vector implements Cloneable {
    private double x;
    private double y;
    private double z;
    public Vector() { this(0, 0, 0); }
    public Vector(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
    public double getX() { return x; } public double getY() { return y; } public double getZ() { return z; }
    public Vector setX(double x) { this.x = x; return this; } public Vector setY(double y) { this.y = y; return this; } public Vector setZ(double z) { this.z = z; return this; }
    public Vector add(Vector other) { x += other.x; y += other.y; z += other.z; return this; }
    public Vector add(double x, double y, double z) { this.x += x; this.y += y; this.z += z; return this; }
    public Vector subtract(Vector other) { x -= other.x; y -= other.y; z -= other.z; return this; }
    public Vector multiply(double value) { x *= value; y *= value; z *= value; return this; }
    public Vector multiply(Vector value) { x *= value.x; y *= value.y; z *= value.z; return this; }
    public Vector divide(double value) { if (value != 0) { x /= value; y /= value; z /= value; } return this; }
    public double length() { return Math.sqrt(lengthSquared()); } public double lengthSquared() { return x*x+y*y+z*z; }
    public double dot(Vector other) { return x*other.x+y*other.y+z*other.z; }
    public Vector crossProduct(Vector other) { return new Vector(y*other.z-z*other.y,z*other.x-x*other.z,x*other.y-y*other.x); }
    public Vector normalize() { double length=length(); if (length>1.0E-9) divide(length); return this; }
    public Vector zero() { x=y=z=0; return this; } public boolean isZero(){return x==0&&y==0&&z==0;}
    public Vector rotateAroundY(double angle) { double cos=Math.cos(angle),sin=Math.sin(angle),nx=x*cos+z*sin; z=z*cos-x*sin; x=nx; return this; }
    public Location toLocation(net.nuggetmc.tplus.compat.bukkit.World world) { return new Location(world,x,y,z); }
    @Override public Vector clone(){return new Vector(x,y,z);}
    @Override public boolean equals(Object o){return o instanceof Vector v&&Double.compare(x,v.x)==0&&Double.compare(y,v.y)==0&&Double.compare(z,v.z)==0;}
    @Override public int hashCode(){return Objects.hash(x,y,z);} @Override public String toString(){return "Vector{"+x+","+y+","+z+"}";}
}
