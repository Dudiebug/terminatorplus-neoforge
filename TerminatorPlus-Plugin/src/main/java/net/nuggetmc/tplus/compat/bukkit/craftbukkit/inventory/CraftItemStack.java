package net.nuggetmc.tplus.compat.bukkit.craftbukkit.inventory;
import net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack;
public final class CraftItemStack { private CraftItemStack(){} public static net.minecraft.world.item.ItemStack asNMSCopy(ItemStack item){return item==null?net.minecraft.world.item.ItemStack.EMPTY:item.asNmsCopy();} public static ItemStack asBukkitCopy(net.minecraft.world.item.ItemStack item){return new ItemStack(item);} }
