package net.nuggetmc.tplus.compat.bukkit.scheduler;
import net.nuggetmc.tplus.compat.bukkit.plugin.Plugin;
public abstract class BukkitRunnable implements Runnable {
    private volatile BukkitTask task;
    public abstract void run();
    public BukkitTask runTask(Plugin plugin){task=BukkitScheduler.instance().runTask(plugin,this);return task;}
    public BukkitTask runTaskLater(Plugin plugin,long delay){task=BukkitScheduler.instance().runTaskLater(plugin,this,delay);return task;}
    public BukkitTask runTaskTimer(Plugin plugin,long delay,long period){task=BukkitScheduler.instance().runTaskTimer(plugin,this,delay,period);return task;}
    public void cancel(){if(task!=null)task.cancel();}
    public boolean isCancelled(){return task!=null&&task.isCancelled();}
}
