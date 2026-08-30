package net.nuggetmc.tplus.compat.bukkit.inventory.meta;

import java.util.*;
import net.nuggetmc.tplus.compat.bukkit.enchantments.Enchantment;

public class ItemMeta implements Cloneable {
    private String displayName;
    private java.util.List<String> lore;
    private final Map<Enchantment,Integer> enchants = new EnumMap<>(Enchantment.class);
    public String getDisplayName(){return displayName;} public void setDisplayName(String s){displayName=s;}
    public java.util.List<String> getLore(){return lore==null?null:new java.util.ArrayList<>(lore);} public void setLore(java.util.List<String> lines){lore=lines==null?null:new java.util.ArrayList<>(lines);}
    public boolean hasEnchant(Enchantment e){return enchants.containsKey(e);} public Map<Enchantment,Integer> getEnchants(){return Collections.unmodifiableMap(enchants);}
    public void addEnchant(Enchantment e,int level,boolean ignore){if(e!=null)enchants.put(e,level);} public int getEnchantLevel(Enchantment e){return enchants.getOrDefault(e,0);}
    public ItemMeta clone(){ItemMeta c=new ItemMeta();c.displayName=displayName;c.lore=lore==null?null:new java.util.ArrayList<>(lore);c.enchants.putAll(enchants);return c;}
    @Override public boolean equals(Object o){return o instanceof ItemMeta m&&Objects.equals(displayName,m.displayName)&&enchants.equals(m.enchants);} @Override public int hashCode(){return Objects.hash(displayName,enchants);}
}
