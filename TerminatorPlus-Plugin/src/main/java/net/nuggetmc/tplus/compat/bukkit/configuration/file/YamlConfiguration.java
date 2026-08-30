package net.nuggetmc.tplus.compat.bukkit.configuration.file;

import java.io.File;
import java.io.IOException;

public class YamlConfiguration extends FileConfiguration {
    public static YamlConfiguration loadConfiguration(File file){YamlConfiguration c=new YamlConfiguration();try{c.load(file);}catch(IOException ignored){}return c;}
}
