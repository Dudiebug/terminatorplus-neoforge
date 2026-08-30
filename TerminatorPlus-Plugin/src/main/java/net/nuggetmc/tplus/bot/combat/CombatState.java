package net.nuggetmc.tplus.bot.combat;

import net.nuggetmc.tplus.compat.bukkit.util.Vector;

/**
 * Scratch-pad for multi-tick weapon behaviors (trident run-up, mace
 * airborne tracking). One instance per bot, stored on the Bot.
 */
public final class CombatState {

    public enum Phase { IDLE, CHARGING, MACE_CHARGING, AIRBORNE, RELEASE }

    private Phase phase = Phase.IDLE;
    private int phaseTicks = 0;
    private Vector chargeDirection;
    private double phaseStartY = Double.NaN;
    private int maceAirborneGroundTicks = 0;
    private int macePrejumpAirTicks = 0;
    private int lastExecuteTick = Integer.MIN_VALUE;

    public Phase getPhase() {
        return phase;
    }

    public void setPhase(Phase phase) {
        this.phase = phase;
        this.phaseTicks = 0;
        this.phaseStartY = Double.NaN;
        this.maceAirborneGroundTicks = 0;
        this.macePrejumpAirTicks = 0;
    }

    public int tickPhase() {
        return ++phaseTicks;
    }

    public int getPhaseTicks() {
        return phaseTicks;
    }

    public Vector getChargeDirection() {
        return chargeDirection == null ? null : chargeDirection.clone();
    }

    public void setChargeDirection(Vector v) {
        this.chargeDirection = v == null ? null : v.clone();
    }

    public double getPhaseStartY() {
        return phaseStartY;
    }

    public void setPhaseStartY(double phaseStartY) {
        this.phaseStartY = phaseStartY;
    }

    public int tickMaceAirborneGroundTicks() {
        return ++maceAirborneGroundTicks;
    }

    public void clearMaceAirborneGroundTicks() {
        maceAirborneGroundTicks = 0;
    }

    public int tickMacePrejumpAirTicks() {
        return ++macePrejumpAirTicks;
    }

    public void clearMacePrejumpAirTicks() {
        macePrejumpAirTicks = 0;
    }

    public boolean markExecuted(int aliveTick) {
        if (lastExecuteTick == aliveTick) return false;
        lastExecuteTick = aliveTick;
        return true;
    }

    public void reset() {
        phase = Phase.IDLE;
        phaseTicks = 0;
        chargeDirection = null;
        phaseStartY = Double.NaN;
        maceAirborneGroundTicks = 0;
        macePrejumpAirTicks = 0;
    }
}
