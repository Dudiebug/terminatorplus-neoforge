package net.nuggetmc.tplus.compat.bukkit.inventory;

/** In-memory inventory used by the menu facades until the vanilla menu opens. */
public final class SimpleInventory implements Inventory {
    private final InventoryHolder holder;
    private final ItemStack[] contents;
    private final String title;

    public SimpleInventory(InventoryHolder holder, int size, String title) {
        if (size < 0) throw new IllegalArgumentException("size");
        this.holder = holder;
        this.contents = new ItemStack[size];
        this.title = title == null ? "Inventory" : title;
    }

    @Override public int getSize() { return contents.length; }
    @Override public ItemStack getItem(int slot) { return slot < 0 || slot >= contents.length ? null : contents[slot]; }
    @Override public void setItem(int slot, ItemStack item) { if (slot >= 0 && slot < contents.length) contents[slot] = item == null ? null : item.clone(); }
    @Override public String getTitle() { return title; }
    @Override public InventoryHolder getHolder() { return holder; }
}
