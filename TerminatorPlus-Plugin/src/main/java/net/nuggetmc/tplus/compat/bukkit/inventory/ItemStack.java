package net.nuggetmc.tplus.compat.bukkit.inventory;

import java.io.*;
import java.util.*;
import net.nuggetmc.tplus.compat.bukkit.Material;
import net.nuggetmc.tplus.compat.bukkit.enchantments.Enchantment;
import net.nuggetmc.tplus.compat.bukkit.inventory.meta.*;

/** Internal item facade backed by a native 1.21.1 ItemStack. */
public class ItemStack implements Cloneable {
    private Material type;
    private int amount;
    private ItemMeta meta;
    private final Map<Enchantment,Integer> enchants = new EnumMap<>(Enchantment.class);
    public ItemStack(Material type){this(type,1);}
    public ItemStack(Material type,int amount){this.type=type==null?Material.AIR:type;this.amount=Math.max(0,amount);}
    public ItemStack(net.minecraft.world.item.ItemStack nms){this(Material.fromNms(nms),nms==null?0:nms.getCount());}
    public Material getType(){return type;} public void setType(Material type){this.type=type==null?Material.AIR:type;}
    public int getAmount(){return amount;} public void setAmount(int amount){this.amount=Math.max(0,amount);}
    public boolean isEmpty(){return type==Material.AIR||amount<=0;}
    public ItemMeta getItemMeta(){if(meta==null)meta=defaultMeta();return meta.clone();}
    public boolean setItemMeta(ItemMeta value){meta=value==null?null:value.clone();return true;}
    private ItemMeta defaultMeta(){return switch(type){case POTION,SPLASH_POTION,LINGERING_POTION,TIPPED_ARROW->new PotionMeta();case FIREWORK_ROCKET->new FireworkMeta();default->new ItemMeta();};}
    public ItemStack clone(){ItemStack c=new ItemStack(type,amount);c.meta=meta==null?null:meta.clone();c.enchants.putAll(enchants);return c;}
    public boolean isSimilar(ItemStack other){return other!=null&&type==other.type&&Objects.equals(meta,other.meta)&&enchants.equals(other.enchants);}
    public void addUnsafeEnchantment(Enchantment e,int level){if(e!=null)enchants.put(e,level);}
    public boolean containsEnchantment(Enchantment e){return enchants.containsKey(e);} public Map<Enchantment,Integer> getEnchantments(){return Collections.unmodifiableMap(enchants);}
    public int getEnchantmentLevel(Enchantment e){return enchants.getOrDefault(e,0);}
    public net.minecraft.world.item.ItemStack asNmsCopy(){net.minecraft.world.item.Item item=type==null?null:type.item();if(item==null||type==Material.AIR)return net.minecraft.world.item.ItemStack.EMPTY;net.minecraft.world.item.ItemStack out=new net.minecraft.world.item.ItemStack(item,amount);return out;}
    public byte[] serializeAsBytes(){try{ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);d.writeUTF(type.name());d.writeInt(amount);d.writeInt(enchants.size());for(var e:enchants.entrySet()){d.writeUTF(e.getKey().name());d.writeInt(e.getValue());}if(meta instanceof PotionMeta pm){d.writeByte(1);d.writeUTF(pm.getBasePotionType()==null?"":pm.getBasePotionType().name());}else d.writeByte(0);return b.toByteArray();}catch(IOException e){throw new IllegalStateException(e);}}
    public static ItemStack deserializeBytes(byte[] bytes){try{DataInputStream d=new DataInputStream(new ByteArrayInputStream(bytes));ItemStack s=new ItemStack(Material.matchMaterial(d.readUTF()),d.readInt());int n=d.readInt();for(int i=0;i<n;i++)s.addUnsafeEnchantment(Enchantment.valueOf(d.readUTF()),d.readInt());if(d.readByte()==1){PotionMeta pm=new PotionMeta();String p=d.readUTF();if(!p.isEmpty())pm.setBasePotionType(net.nuggetmc.tplus.compat.bukkit.potion.PotionType.valueOf(p));s.setItemMeta(pm);}return s;}catch(Exception e){throw new IllegalArgumentException("Invalid item bytes",e);}}
    @Override public boolean equals(Object o){return o instanceof ItemStack s&&isSimilar(s)&&amount==s.amount;} @Override public int hashCode(){return Objects.hash(type,amount,meta,enchants);}
}
