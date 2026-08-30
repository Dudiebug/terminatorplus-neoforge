package net.nuggetmc.tplus.compat.bukkit.configuration.file;

import net.nuggetmc.tplus.compat.bukkit.configuration.Configuration;
import java.io.*;
import java.nio.file.*;
import org.yaml.snakeyaml.Yaml;

public class FileConfiguration extends Configuration {
    public FileConfiguration(){super();}
    public FileConfiguration(java.util.Map<String,Object> values){super(values);}
    public void save(File file) throws IOException { if(file.getParentFile()!=null)file.getParentFile().mkdirs(); try(Writer w=Files.newBufferedWriter(file.toPath())){new Yaml().dump(getValues(false),w);} }
    public void load(File file) throws IOException { values.clear(); if(!file.isFile())return; Object parsed=new Yaml().load(Files.newBufferedReader(file.toPath())); if(parsed instanceof java.util.Map<?,?> map) merge(map,values); }
    @SuppressWarnings("unchecked") private static void merge(java.util.Map<?,?> in,java.util.Map<String,Object> out){for(var e:in.entrySet()){if(e.getKey()==null)continue;Object v=e.getValue();if(v instanceof java.util.Map<?,?> m){java.util.Map<String,Object> n=new java.util.LinkedHashMap<>();merge(m,n);v=n;}out.put(String.valueOf(e.getKey()),v);}}
    public String saveToString(){return new Yaml().dump(getValues(false));}
}
