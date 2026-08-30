package net.nuggetmc.tplus.compat.bukkit.inventory.meta;
public class FireworkMeta extends ItemMeta { private int power; public int getPower(){return power;} public void setPower(int p){power=p;} @Override public FireworkMeta clone(){FireworkMeta f=new FireworkMeta();f.power=power;return f;} }
