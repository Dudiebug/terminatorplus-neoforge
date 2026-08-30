package net.nuggetmc.tplus.compat.bukkit.util;
public final class BoundingBox {
    private double minX,minY,minZ,maxX,maxY,maxZ;
    public BoundingBox(double minX,double minY,double minZ,double maxX,double maxY,double maxZ){this.minX=minX;this.minY=minY;this.minZ=minZ;this.maxX=maxX;this.maxY=maxY;this.maxZ=maxZ;}
    public static BoundingBox of(double a,double b,double c,double d,double e,double f){return new BoundingBox(a,b,c,d,e,f);}
    public double getMinX(){return minX;} public double getMinY(){return minY;} public double getMinZ(){return minZ;} public double getMaxX(){return maxX;} public double getMaxY(){return maxY;} public double getMaxZ(){return maxZ;}
    public double getCenterX(){return (minX+maxX)*0.5;} public double getCenterY(){return (minY+maxY)*0.5;} public double getCenterZ(){return (minZ+maxZ)*0.5;} public double getWidthX(){return maxX-minX;} public double getWidthZ(){return maxZ-minZ;} public double getHeight(){return maxY-minY;}
    public double getVolume(){return Math.max(0,getWidthX()*getHeight()*getWidthZ());}
    public boolean overlaps(BoundingBox o){return maxX>o.minX&&minX<o.maxX&&maxY>o.minY&&minY<o.maxY&&maxZ>o.minZ&&minZ<o.maxZ;}
    public boolean contains(double x,double y,double z){return x>=minX&&x<=maxX&&y>=minY&&y<=maxY&&z>=minZ&&z<=maxZ;}
    public BoundingBox expand(double x,double y,double z){minX-=x;maxX+=x;minY-=y;maxY+=y;minZ-=z;maxZ+=z;return this;}
    public BoundingBox clone(){return new BoundingBox(minX,minY,minZ,maxX,maxY,maxZ);} public net.minecraft.world.phys.AABB asAabb(){return new net.minecraft.world.phys.AABB(minX,minY,minZ,maxX,maxY,maxZ);}
}
