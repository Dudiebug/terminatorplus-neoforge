package net.nuggetmc.tplus.compat.bukkit.entity;
import net.minecraft.server.level.ServerPlayer;
import java.util.Map; import java.util.WeakHashMap;
public final class EntityBridge {
    private static final Map<net.minecraft.world.entity.Entity,Entity> CACHE=new WeakHashMap<>(); private EntityBridge(){}
    public static Entity wrap(net.minecraft.world.entity.Entity e){return CACHE.computeIfAbsent(e,k->k instanceof ServerPlayer p?new Player(p):k instanceof net.minecraft.world.entity.LivingEntity l?new LivingEntity(l):new Entity(k));}
    public static LivingEntity living(net.minecraft.world.entity.LivingEntity e){return (LivingEntity)wrap(e);} public static Player player(ServerPlayer e){return (Player)wrap(e);}
}
