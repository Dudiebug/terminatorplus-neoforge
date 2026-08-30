package net.nuggetmc.tplus.compat.bukkit.configuration;

import java.util.*;

/** Lightweight dotted-path configuration view backed by a nested map. */
public class ConfigurationSection {
    protected final Map<String,Object> values;
    public ConfigurationSection() { this(new LinkedHashMap<>()); }
    public ConfigurationSection(Map<String,Object> values) { this.values = values == null ? new LinkedHashMap<>() : values; }
    public Object get(String path) { return get(path, null); }
    public Object get(String path, Object def) { Object value = resolve(path); return value == null ? def : value; }
    @SuppressWarnings("unchecked") protected Object resolve(String path) {
        if (path == null || path.isEmpty()) return values;
        Object current = values;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?,?> map)) return null;
            current = map.get(part);
        }
        return current;
    }
    @SuppressWarnings("unchecked") public void set(String path, Object value) {
        String[] parts = path.split("\\."); Map<String,Object> current = values;
        for (int i=0;i<parts.length-1;i++) {
            Object next = current.get(parts[i]);
            if (!(next instanceof Map<?,?>)) { next = new LinkedHashMap<String,Object>(); current.put(parts[i], next); }
            current = (Map<String,Object>) next;
        }
        if (value == null) current.remove(parts[parts.length-1]); else current.put(parts[parts.length-1], value);
    }
    public boolean contains(String path) { return resolve(path) != null; }
    public boolean isSet(String path) { return contains(path); }
    public boolean getBoolean(String path) { return getBoolean(path,false); }
    public boolean getBoolean(String path, boolean def) { Object v=get(path); return v==null?def:(v instanceof Boolean b?b:Boolean.parseBoolean(String.valueOf(v))); }
    public int getInt(String path) { return getInt(path,0); }
    public int getInt(String path,int def) { Object v=get(path); if(v instanceof Number n)return n.intValue(); try{return v==null?def:Integer.parseInt(v.toString());}catch(Exception e){return def;} }
    public long getLong(String path) { return getLong(path,0L); }
    public long getLong(String path,long def) { Object v=get(path); if(v instanceof Number n)return n.longValue(); try{return v==null?def:Long.parseLong(v.toString());}catch(Exception e){return def;} }
    public double getDouble(String path) { return getDouble(path,0d); }
    public double getDouble(String path,double def) { Object v=get(path); if(v instanceof Number n)return n.doubleValue(); try{return v==null?def:Double.parseDouble(v.toString());}catch(Exception e){return def;} }
    public String getString(String path) { return getString(path,null); }
    public String getString(String path,String def) { Object v=get(path); return v==null?def:String.valueOf(v); }
    public List<Integer> getIntegerList(String path) { return numbers(path, Integer.class); }
    public List<Long> getLongList(String path) { return numbers(path, Long.class); }
    public List<String> getStringList(String path) { Object v=get(path); if(!(v instanceof Collection<?> c))return List.of(); return c.stream().map(String::valueOf).toList(); }
    public List<?> getList(String path) { Object v=get(path); return v instanceof List<?> l?l:List.of(); }
    @SuppressWarnings("unchecked") public ConfigurationSection getConfigurationSection(String path) { Object v=get(path); return v instanceof Map<?,?> m ? new ConfigurationSection((Map<String,Object>)m) : null; }
    public ConfigurationSection createSection(String path) { set(path,new LinkedHashMap<String,Object>()); return getConfigurationSection(path); }
    public Set<String> getKeys(boolean deep) { return deep ? flattenKeys(values,"") : values.keySet(); }
    public Map<String,Object> getValues(boolean deep) { return deep ? flatten(values) : new LinkedHashMap<>(values); }
    private static Map<String,Object> flatten(Map<String,Object> map) { Map<String,Object> out=new LinkedHashMap<>(); flattenInto(map,"",out); return out; }
    @SuppressWarnings("unchecked") private static void flattenInto(Map<String,Object> map,String prefix,Map<String,Object> out){for(var e:map.entrySet()){String k=prefix.isEmpty()?e.getKey():prefix+"."+e.getKey(); if(e.getValue() instanceof Map<?,?> m)flattenInto((Map<String,Object>)m,k,out); else out.put(k,e.getValue());}}
    private static Set<String> flattenKeys(Map<String,Object> map,String prefix){return flatten(map).keySet();}
    private <T extends Number> List<T> numbers(String path, Class<T> type){Object v=get(path);if(!(v instanceof Collection<?> c))return List.of();List<T> out=new ArrayList<>();for(Object x:c)if(x instanceof Number n)out.add(type==Integer.class?(T)Integer.valueOf(n.intValue()):(T)Long.valueOf(n.longValue()));return out;}
}
