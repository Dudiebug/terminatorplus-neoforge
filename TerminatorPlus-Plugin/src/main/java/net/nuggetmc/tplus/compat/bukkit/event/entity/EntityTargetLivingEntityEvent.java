package net.nuggetmc.tplus.compat.bukkit.event.entity;
import net.nuggetmc.tplus.compat.bukkit.event.*; import net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity;
public class EntityTargetLivingEntityEvent extends Event implements Cancellable { private final LivingEntity target; private boolean cancelled; public EntityTargetLivingEntityEvent(LivingEntity target){this.target=target;} public LivingEntity getTarget(){return target;} public boolean isCancelled(){return cancelled;}public void setCancelled(boolean v){cancelled=v;} }
