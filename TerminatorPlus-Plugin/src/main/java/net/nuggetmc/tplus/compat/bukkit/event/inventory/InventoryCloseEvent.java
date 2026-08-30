package net.nuggetmc.tplus.compat.bukkit.event.inventory;
import net.nuggetmc.tplus.compat.bukkit.event.*; import net.nuggetmc.tplus.compat.bukkit.entity.HumanEntity;
public class InventoryCloseEvent extends Event { private final HumanEntity player; private final InventoryView view; public InventoryCloseEvent(HumanEntity p,InventoryView v){player=p;view=v;}public HumanEntity getPlayer(){return player;}public InventoryView getView(){return view;} }
