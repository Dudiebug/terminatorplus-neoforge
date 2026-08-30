package net.nuggetmc.tplus.compat.bukkit.event.inventory;
import net.nuggetmc.tplus.compat.bukkit.inventory.Inventory; import net.nuggetmc.tplus.compat.bukkit.entity.HumanEntity;
public final class InventoryView { private final Inventory top,bottom; private final HumanEntity player; public InventoryView(Inventory top,Inventory bottom,HumanEntity player){this.top=top;this.bottom=bottom;this.player=player;} public Inventory getTopInventory(){return top;} public Inventory getBottomInventory(){return bottom;} public HumanEntity getPlayer(){return player;} }
