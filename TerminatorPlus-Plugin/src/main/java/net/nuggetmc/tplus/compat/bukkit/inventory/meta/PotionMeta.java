package net.nuggetmc.tplus.compat.bukkit.inventory.meta;
import net.nuggetmc.tplus.compat.bukkit.potion.PotionType;
public class PotionMeta extends ItemMeta { private PotionType base; public PotionType getBasePotionType(){return base;} public void setBasePotionType(PotionType t){base=t;} @Override public PotionMeta clone(){PotionMeta p=new PotionMeta();p.base=base;return p;} }
