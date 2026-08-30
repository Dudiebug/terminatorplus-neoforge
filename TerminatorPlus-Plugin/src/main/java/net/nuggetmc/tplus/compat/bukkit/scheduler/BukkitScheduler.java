package net.nuggetmc.tplus.compat.bukkit.scheduler;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.nuggetmc.tplus.compat.bukkit.plugin.Plugin;

/** Tick scheduler facade. The NeoForge bridge can bind its tick executor; fallback is a 20Hz daemon. */
public final class BukkitScheduler {
    private static final BukkitScheduler INSTANCE=new BukkitScheduler(); private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"terminatorplus-scheduler");t.setDaemon(true);return t;}); private final AtomicInteger ids=new AtomicInteger(); private final java.util.Map<Integer,BukkitTask> tasks=new java.util.concurrent.ConcurrentHashMap<>();
    public static BukkitScheduler instance(){return INSTANCE;}
    private long delay(long ticks){return Math.max(0,ticks)*50L;}
    public BukkitTask runTask(Plugin plugin,Runnable action){return runTaskLater(plugin,action,0);}
    public BukkitTask runTaskLater(Plugin plugin,Runnable action,long ticks){return schedule(action,delay(ticks),0);}
    public BukkitTask runTaskTimer(Plugin plugin,Runnable action,long delayTicks,long periodTicks){return schedule(action,delay(delayTicks),Math.max(1,delay(periodTicks)));}
    public BukkitTask runTaskAsynchronously(Plugin plugin,Runnable action){return schedule(action,0,0);}
    public int scheduleSyncRepeatingTask(Plugin plugin,Runnable action,long delayTicks,long periodTicks){return runTaskTimer(plugin,action,delayTicks,periodTicks).getTaskId();}
    public void cancelTask(int id){BukkitTask task=tasks.remove(id);if(task!=null)task.cancel();}
    public <T> Future<T> callSyncMethod(Plugin plugin,java.util.concurrent.Callable<T> callable){return executor.submit(()->{try{return callable.call();}catch(Exception e){throw new CompletionException(e);}});}
    private BukkitTask schedule(Runnable action,long delay,long period){int id=ids.incrementAndGet();final BukkitTask[] ref=new BukkitTask[1];Runnable wrapped=()->{try{action.run();}catch(Throwable ignored){}if(ref[0]!=null&&ref[0].isCancelled())tasks.remove(id);};ScheduledFuture<?> f=period>0?executor.scheduleAtFixedRate(wrapped,delay,period,TimeUnit.MILLISECONDS):executor.schedule(wrapped,delay,TimeUnit.MILLISECONDS);BukkitTask t=new BukkitTask(id,f);ref[0]=t;tasks.put(id,t);return t;}
    public void shutdown(){tasks.values().forEach(BukkitTask::cancel);tasks.clear();executor.shutdownNow();}
}
