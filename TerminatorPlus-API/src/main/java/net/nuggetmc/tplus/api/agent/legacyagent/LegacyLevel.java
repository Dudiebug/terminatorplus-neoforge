package net.nuggetmc.tplus.api.agent.legacyagent;

import net.nuggetmc.tplus.compat.bukkit.Location;

public enum LegacyLevel {
    ABOVE(0, 2, 0),
    BELOW(0, -1, 0),
    AT(0, 1, 0),
    AT_D(0, 0, 0),
    NORTH_U(0, 2, -1),
    SOUTH_U(0, 2, 1),
    EAST_U(1, 2, 0),
    WEST_U(-1, 2, 0),
    NORTH(0, 1, -1),
    SOUTH(0, 1, 1),
    EAST(1, 1, 0),
    WEST(-1, 1, 0),
    NORTH_D(0, 0, -1),
    SOUTH_D(0, 0, 1),
    EAST_D(1, 0, 0),
    WEST_D(-1, 0, 0),
    NORTHWEST_D(-1, 0, -1),
    SOUTHWEST_D(-1, 0, 1),
    NORTHEAST_D(1, 0, -1),
    SOUTHEAST_D(1, 0, 1),
    NORTH_D_2(0, -1, -1),
    SOUTH_D_2(0, -1, 1),
    EAST_D_2(1, -1, 0),
    WEST_D_2(-1, -1, 0);
	
	private final int offsetX;
	private final int offsetY;
	private final int offsetZ;
	
	private LegacyLevel(int offsetX, int offsetY, int offsetZ) {
		this.offsetX = offsetX;
		this.offsetY = offsetY;
		this.offsetZ = offsetZ;
	}

    public boolean isSide() {
        return this != ABOVE && this != BELOW && this != AT && this != AT_D;
    }
    
    public boolean isSideAt() {
        return this == NORTH || this == SOUTH || this == EAST || this == WEST;
    }
    
    public boolean isSideUp() {
        return this == NORTH_U || this == SOUTH_U || this == EAST_U || this == WEST_U;
    }
    
    public boolean isSideDown() {
        return this == NORTH_D || this == SOUTH_D || this == EAST_D || this == WEST_D;
    }
    
    public boolean isSideDown2() {
        return this == NORTH_D_2 || this == SOUTH_D_2 || this == EAST_D_2 || this == WEST_D_2;
    }
    
    public LegacyLevel sideUp() {
    	switch(this) {
    		case NORTH: return NORTH_U;
    		case SOUTH: return SOUTH_U;
    		case EAST: return EAST_U;
    		case WEST: return WEST_U;
    		case NORTH_D: return NORTH;
    		case SOUTH_D: return SOUTH;
    		case EAST_D: return EAST;
    		case WEST_D: return WEST;
    		case NORTH_D_2: return NORTH_D;
    		case SOUTH_D_2: return SOUTH_D;
    		case EAST_D_2: return EAST_D;
    		case WEST_D_2: return WEST_D;
    		default:
    			return null;
    	}
    }
    
    public LegacyLevel sideDown() {
    	switch(this) {
    		case NORTH_U: return NORTH;
    		case SOUTH_U: return SOUTH;
    		case EAST_U: return EAST;
    		case WEST_U: return WEST;
    		case NORTH: return NORTH_D;
    		case SOUTH: return SOUTH_D;
    		case EAST: return EAST_D;
    		case WEST: return WEST_D;
    		case NORTH_D: return NORTH_D_2;
    		case SOUTH_D: return SOUTH_D_2;
    		case EAST_D: return EAST_D_2;
    		case WEST_D: return WEST_D_2;
    		default:
    			return null;
    	}
    }
    
	public static LegacyLevel getOffset(Location start, Location end) {
		int diffX = end.getBlockX() - start.getBlockX();
		int diffY = end.getBlockY() - start.getBlockY();
		int diffZ = end.getBlockZ() - start.getBlockZ();
		for (LegacyLevel level : LegacyLevel.values()) {
			if (level.offsetX == diffX && level.offsetY == diffY && level.offsetZ == diffZ) {
				return level;
			}
		}
		return null;
	}
	
	public Location offset(Location loc) {
		return loc.add(offsetX, offsetY, offsetZ);
	}
    
    public static class LevelWrapper {
    	private LegacyLevel level;
    	
    	public LevelWrapper(LegacyLevel level) { 
    		this.level = level;
    	}
    	
    	public LegacyLevel getLevel() {
    		return level;
    	}
    	
    	public void setLevel(LegacyLevel level) {
    		this.level = level;
    	}
    }
}
