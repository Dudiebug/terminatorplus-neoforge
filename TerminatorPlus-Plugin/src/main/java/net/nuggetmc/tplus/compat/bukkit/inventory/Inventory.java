package net.nuggetmc.tplus.compat.bukkit.inventory;

public interface Inventory extends InventoryHolder {
    int getSize(); ItemStack getItem(int slot); void setItem(int slot, ItemStack item);
    default ItemStack[] getContents(){ItemStack[] a=new ItemStack[getSize()];for(int i=0;i<a.length;i++)a[i]=getItem(i);return a;}
    default void setContents(ItemStack[] items){for(int i=0;i<getSize();i++)setItem(i,items!=null&&i<items.length?items[i]:null);}
    default int firstEmpty(){for(int i=0;i<getSize();i++){ItemStack s=getItem(i);if(s==null||s.isEmpty())return i;}return -1;}
    default String getTitle(){return "Inventory";}
    default InventoryHolder getHolder(){return null;}
    default void clear(){for(int i=0;i<getSize();i++)setItem(i,null);}
    @Override default Inventory getInventory(){return this;}
}
