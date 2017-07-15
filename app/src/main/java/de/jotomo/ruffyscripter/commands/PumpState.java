package de.jotomo.ruffyscripter.commands;

import java.util.Date;

/**
 * State representing the state of the MAIN_MENU.
 */
public class PumpState {
    public Date timestamp = new Date();
    public boolean tbrActive = false;
    public int tbrPercent = -1;
    public int tbrRemainingDuration = -1;
    public boolean isErrorOrWarning = false;
    public int errorCode = -1;

    public PumpState tbrActive(boolean tbrActive) {
        this.tbrActive = tbrActive;
        return this;
    }

    public PumpState tbrPercent(int tbrPercent) {
        this.tbrPercent = tbrPercent;
        return this;
    }

    public PumpState tbrRemainingDuration(int tbrRemainingDuration) {
        this.tbrRemainingDuration = tbrRemainingDuration;
        return this;
    }

    public PumpState isErrorOrWarning(boolean isErrorOrWarning) {
        this.isErrorOrWarning = isErrorOrWarning;
        return this;
    }

    public PumpState errorCode(int errorCode) {
        this.errorCode = errorCode;
        return this;
    }

    @Override
    public String toString() {
        return "PumpState{" +
                "tbrActive=" + tbrActive +
                ", tbrPercent=" + tbrPercent +
                ", tbrRemainingDuration=" + tbrRemainingDuration +
                ", isErrorOrWarning=" + isErrorOrWarning +
                ", errorCode=" + errorCode +
                ", timestamp=" + timestamp +
                '}';
    }
}