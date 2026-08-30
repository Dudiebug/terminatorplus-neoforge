package net.nuggetmc.tplus.compat.bukkit.inventory.meta;
public class Damageable extends ItemMeta { private int damage; public int getDamage(){return damage;} public void setDamage(int d){damage=Math.max(0,d);} @Override public Damageable clone(){Damageable d=new Damageable();d.setDamage(damage);return d;} }
