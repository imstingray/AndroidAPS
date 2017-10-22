package de.jotomo.ruffyscripter;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.support.annotation.Nullable;

import com.google.common.base.Joiner;

import org.monkey.d.ruffy.ruffy.driver.IRTHandler;
import org.monkey.d.ruffy.ruffy.driver.IRuffyService;
import org.monkey.d.ruffy.ruffy.driver.Ruffy;
import org.monkey.d.ruffy.ruffy.driver.display.Menu;
import org.monkey.d.ruffy.ruffy.driver.display.MenuAttribute;
import org.monkey.d.ruffy.ruffy.driver.display.MenuType;
import org.monkey.d.ruffy.ruffy.driver.display.menu.MenuTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.List;

import de.jotomo.ruffy.spi.BasalProfile;
import de.jotomo.ruffy.spi.BolusProgressReporter;
import de.jotomo.ruffy.spi.CommandResult;
import de.jotomo.ruffy.spi.PumpState;
import de.jotomo.ruffy.spi.RuffyCommands;
import de.jotomo.ruffy.spi.history.Error;
import de.jotomo.ruffy.spi.history.PumpHistoryRequest;
import de.jotomo.ruffyscripter.commands.BolusCommand;
import de.jotomo.ruffyscripter.commands.CancelTbrCommand;
import de.jotomo.ruffyscripter.commands.Command;
import de.jotomo.ruffyscripter.commands.CommandException;
import de.jotomo.ruffyscripter.commands.ReadBasalProfileCommand;
import de.jotomo.ruffyscripter.commands.ReadHistoryCommand;
import de.jotomo.ruffyscripter.commands.ReadPumpStateCommand;
import de.jotomo.ruffyscripter.commands.SetBasalProfileCommand;
import de.jotomo.ruffyscripter.commands.SetTbrCommand;

// TODO regularly read "My data" history (boluses, TBR) to double check all commands ran successfully.
// Automatically compare against AAPS db, or log all requests in the PumpInterface (maybe Milos
// already logs those requests somewhere ... and verify they have all been ack'd by the pump properly

/**
 * provides scripting 'runtime' and operations. consider moving operations into a separate
 * class and inject that into executing commands, so that commands operately solely on
 * operations and are cleanly separated from the thread management, connection management etc
 */
public class RuffyScripter implements RuffyCommands {
    private static final Logger log = LoggerFactory.getLogger(RuffyScripter.class);

    private IRuffyService ruffyService;
    private String unrecoverableError = null;

    @Nullable
    private volatile Menu currentMenu;
    private volatile long menuLastUpdated = 0;

    private volatile long lastCmdExecutionTime;
    private volatile Command activeCmd = null;
    private volatile int retries = 0;

    private boolean started = false;

    private final Object screenlock = new Object();

    private IRTHandler mHandler = new IRTHandler.Stub() {
        @Override
        public void log(String message) throws RemoteException {
//            log.debug("Ruffy says: " + message);
        }

        @Override
        public void fail(String message) throws RemoteException {
            log.warn("Ruffy warns: " + message);
        }

        @Override
        public void requestBluetooth() throws RemoteException {
            log.trace("Ruffy invoked requestBluetooth callback");
        }

        @Override
        public void rtStopped() throws RemoteException {
            log.debug("rtStopped callback invoked");
            currentMenu = null;
            connected = false;
        }

        @Override
        public void rtStarted() throws RemoteException {
            log.debug("rtStarted callback invoked");
            connected = true;
        }

        @Override
        public void rtClearDisplay() throws RemoteException {
        }

        @Override
        public void rtUpdateDisplay(byte[] quarter, int which) throws RemoteException {
        }

        @Override
        public void rtDisplayHandleMenu(Menu menu) throws RemoteException {
            // method is called every ~500ms
            log.debug("rtDisplayHandleMenu: " + menu);

            currentMenu = menu;
            menuLastUpdated = System.currentTimeMillis();

            synchronized (screenlock) {
                screenlock.notifyAll();
            }

            // TODO v2 switch to using IRuffyService.isConnected, rather than guessing connectivity state
            // passed on screen updates
            connected = true;

            // note that a WARNING_OR_ERROR menu can be a valid temporary state (cancelling TBR)
            // of a running command
            if (activeCmd == null && currentMenu.getType() == MenuType.WARNING_OR_ERROR) {
                log.warn("Warning/error menu encountered without a command running");
            }
        }

        @Override
        public void rtDisplayHandleNoMenu() throws RemoteException {
            log.debug("rtDisplayHandleNoMenu callback invoked");
        }
    };

    public RuffyScripter(Context context) {
        boolean boundSucceeded = false;

        try {
            Intent intent = new Intent(context, Ruffy.class);
            context.startService(intent);

            ServiceConnection mRuffyServiceConnection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    log.debug("ruffy service connected");
                    ruffyService = IRuffyService.Stub.asInterface(service);
                    try {
                        ruffyService.setHandler(mHandler);
                    } catch (Exception e) {
                        log.error("Ruffy handler has issues", e);
                    }
                    idleDisconnectMonitorThread.start();
                    started = true;
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    log.debug("ruffy service disconnected");
                }
            };
            boundSucceeded = context.bindService(intent, mRuffyServiceConnection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            log.error("Binding to ruffy service failed", e);
        }

        if (!boundSucceeded) {
            log.error("No connection to ruffy. Pump control unavailable.");
        }
    }

    @Override
    public boolean isPumpAvailable() {
        return started;
    }

    private volatile boolean connected = false;
    private volatile long lastDisconnected = 0;

    private Thread idleDisconnectMonitorThread = new Thread(new Runnable() {
        @Override
        public void run() {
            while (unrecoverableError == null) {
                try {
                    long now = System.currentTimeMillis();
                    long connectionTimeOutMs = 5000;
                    if (connected && activeCmd == null
                            && now > lastCmdExecutionTime + connectionTimeOutMs
                            // don't disconnect too frequently, confuses ruffy?
                            && now > lastDisconnected + 15 * 1000) {
                        log.debug("Disconnecting after " + (connectionTimeOutMs / 1000) + "s inactivity timeout");
                        lastDisconnected = now;
                        ruffyService.doRTDisconnect();
                        connected = false;
                        // don't attempt anything fancy in the next 10s, let the pump settle
                        SystemClock.sleep(10 * 1000);
                    }
                } catch (Exception e) {
                    // TODO do we need to catch this exception somewhere else too? right now it's
                    // converted into a command failure, but it's not classified as unrecoverable;
                    // eventually we might try to recover ... check docs, there's also another
                    // execption we should watch interacting with a remote service.
                    // SecurityException was the other, when there's an AIDL mismatch;
                    //unrecoverableError = "Ruffy service went away";
                    log.debug("Exception in idle disconnect monitor thread, carrying on", e);
                }
                SystemClock.sleep(1000);
            }
        }
    }, "idle-disconnect-monitor");

    @Override
    public boolean isPumpBusy() {
        return activeCmd != null;
    }

    @Override
    public CommandResult readPumpState() {
        return runCommand(new ReadPumpStateCommand());
    }

    public void returnToRootMenu() {
        // returning to main menu using the 'back' key does not cause a vibration
        while (getCurrentMenu().getType() != MenuType.MAIN_MENU && getCurrentMenu().getType() != MenuType.STOP) {
//            if (getCurrentMenu().getType() == MenuType.WARNING_OR_ERROR) {
//                String errorMsg = (String) getCurrentMenu().getAttribute(MenuAttribute.MESSAGE);
//                confirmAlert(errorMsg, 1000);
//                // TODO this isn't gonna work out ... this method can't know if something was enacted ...
//                // gotta keep that state in the command instance
//                throw new CommandException().success(false).enacted(false)
//                        .message("Warning/error " + errorMsg + " raised while returning to main menu");
//            }
            log.debug("Going back to main menu, currently at " + getCurrentMenu().getType());
            pressBackKey();
            waitForMenuUpdate();
        }
    }

    private static class Returnable {
        CommandResult cmdResult;
    }

    /**
     * Always returns a CommandResult, never throws
     */
    public CommandResult runCommand(final Command cmd) {
        log.debug("Attempting to run cmd: " + cmd);
        if (unrecoverableError != null) {
            return new CommandResult().success(false).enacted(false).message(unrecoverableError);
        }

        List<String> violations = cmd.validateArguments();
        if (!violations.isEmpty()) {
            return new CommandResult().message(Joiner.on("\n").join(violations)).state(readPumpStateInternal());
        }

        synchronized (RuffyScripter.class) {
            try {
                activeCmd = cmd;
                retries = 3;
                long connectStart = System.currentTimeMillis();
                ensureConnected();
                final RuffyScripter scripter = this;
                final Returnable returnable = new Returnable();
                class CommandRunner {
                    public void run() {
                        try {

                            // Except for GetPumpStateCommand: fail on all requests if the pump is suspended.
                            // All trickery of not executing but returning success, so that AAPS can non-sensically TBR away when suspended
                            // are dangerous in the current model where commands are dispatched without checking state beforehand, so
                            // the above tactic would result in boluses not being applied and no warning being raised.
                            // (Doing this check on the ComboPlugin level would require the plugin to fetch state from the pump,
                            //  deal with states changes (running/stopped), propagating that to AAPS and so on, adding more state,
                            //  which adds complexity I don't want in v1 and which requires more up-front design to do well,
                            //  esp. with AAPS).

                            // So, for v1, just check the pump is not suspended before executing commands and raising an error for all
                            // but the GetPumpStateCommand. For v2, we'll have to come up with a better idea how to deal with the pump's
                            // state. Maybe having read-only commands and write/treatment commands treated differently, or maybe
                            // build an abstraction on top of the commands, so that e.g. a method on RuffyScripter encapsulates checking
                            // pre-condititions, running one or several commands, checking-post conditions and what not.
                            // Or maybe stick with commands, have them specify if they can run in stop mode. Think properly at which
                            // level to handle state and logic.
                            // For now, when changing cartridges and such: tell AAPS to stop the loop, change cartridge and resume the loop.
                            if (currentMenu == null || currentMenu.getType() == MenuType.STOP) {
                                if (cmd.needsRunMode()) {
                                    returnable.cmdResult = new CommandResult().success(false).enacted(false).message("Pump is suspended");
                                    return;
                                }
                            }
                            log.debug("Connection ready to execute cmd " + cmd);
                            PumpState pumpState = readPumpStateInternal();
                            log.debug("Pump state before running command: " + pumpState);
                            long cmdStartTime = System.currentTimeMillis();
                            cmd.setScripter(scripter);
                            returnable.cmdResult = cmd.execute();
                            long cmdEndTime = System.currentTimeMillis();
                            returnable.cmdResult.completionTime = cmdEndTime;
                            log.debug("Executing " + cmd + " took " + (cmdEndTime - cmdStartTime) + "ms");
                        } catch (CommandException e) {
                            returnable.cmdResult = e.toCommandResult();
                        } catch (Exception e) {
                            log.error("Unexpected exception running cmd", e);
                            returnable.cmdResult = new CommandResult().exception(e).message("Unexpected exception running cmd");
                        } finally {
                            lastCmdExecutionTime = System.currentTimeMillis();
                        }
                    }
                }
                Thread cmdThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        new CommandRunner().run();
                    }
                }, cmd.toString());
                long executionStart = System.currentTimeMillis();
                cmdThread.start();

                // time out if nothing has been happening for more than 90s or after 4m
                // (to fail before the next loop iteration issues the next command)
                long dynamicTimeout = System.currentTimeMillis() + 90 * 1000;
                long overallTimeout = System.currentTimeMillis() + 4 * 60 * 1000;
                int retries = 3;
                while (cmdThread.isAlive()) {
                    log.trace("Waiting for running command to complete");
                    SystemClock.sleep(500);
                    long now = System.currentTimeMillis();
                    if (now > dynamicTimeout) {
                        boolean menuRecentlyUpdated = now < menuLastUpdated + 5 * 1000;
                        boolean inMenuNotMainMenu = currentMenu != null && currentMenu.getType() != MenuType.MAIN_MENU;
                        if (menuRecentlyUpdated || inMenuNotMainMenu) {
                            // command still working (or waiting for pump to complete, e.g. bolus delivery)
                            dynamicTimeout = now + 30 * 1000;
                        } else {
                            log.error("Dynamic timeout running command " + activeCmd);
                            cmdThread.interrupt();
                            SystemClock.sleep(5000);
                            log.error("Timed out thread dead yet? " + cmdThread.isAlive());
                            return new CommandResult().success(false).enacted(false).message("Command stalled, check pump!");
                        }
                    }
                    if (!ruffyService.isConnected()) {
                        if (retries > 0) {
                            retries--;
                            cmdThread.interrupt();
                            reconnect();
                            cmdThread = new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    new CommandRunner().run();
                                }
                            }, cmd.toString());
                            cmdThread.start();
                            dynamicTimeout = System.currentTimeMillis() + 90 * 1000;
                            overallTimeout = System.currentTimeMillis() + 4 * 60 * 1000;
                        }
                    }
                    if (now > overallTimeout) {
                        String msg = "Command " + cmd + " timed out after 4 min, check pump!";
                        log.error(msg);
                        return new CommandResult().success(false).enacted(false).message(msg);
                    }
                }

                if (returnable.cmdResult.state == null) {
                    returnable.cmdResult.state = readPumpStateInternal();
                }
                long connectDurationSec = (executionStart - connectStart) / 1000;
                long executionDurationSec = (System.currentTimeMillis() - executionStart) / 1000;
                returnable.cmdResult.duration = "Connect: " + connectDurationSec + "s, execution: " + executionDurationSec + "s";
                returnable.cmdResult.request = cmd.toString();
                log.debug("Command result: " + returnable.cmdResult);
                return returnable.cmdResult;
            } catch (CommandException e) {
                CommandResult commandResult = e.toCommandResult();
                if (commandResult.state == null) commandResult.state = readPumpStateInternal();
                return commandResult;
            } catch (Exception e) {
                // TODO catching E here AND in CommandRunner?
                // TODO detect and report pump warnings/errors differently?
                log.error("Error in ruffyscripter/ruffy", e);
                try {
                    return new CommandResult()
                            .exception(e)
                            .message("Unexpected exception communication with ruffy: " + e.getMessage())
                            .state(readPumpStateInternal());
                } catch (Exception e1) {
                    // nothing more we can try
                }
                return new CommandResult().exception(e).message("Unexpected exception communication with ruffy: " + e.getMessage());
            } finally {
                activeCmd = null;
            }
        }
    }

    /** On connection lost the pump raises an error immediately (when setting a TBR or giving a bolus),
     * there's no timeout. But: a reconnect is still possible which can then confirm the alarm and
     * foward it to an app.*/
    public void reconnect() {
//        try {
            log.debug("Connection was lost, trying to reconnect");
            ensureConnected();
            if (getCurrentMenu().getType() == MenuType.WARNING_OR_ERROR) {
                String message = (String) getCurrentMenu().getAttribute(MenuAttribute.MESSAGE);
                if (activeCmd instanceof BolusCommand && message.equals("BOLUS CANCELLED")
                        || (activeCmd instanceof CancelTbrCommand || activeCmd instanceof SetTbrCommand)
                        && message.equals("TBR CANCELLED")) {
                    // confirm alert
                    verifyMenuIsDisplayed(MenuType.WARNING_OR_ERROR);
                    pressCheckKey();
                    // dismiss alert
                    verifyMenuIsDisplayed(MenuType.WARNING_OR_ERROR);
                    pressCheckKey();
                    waitForMenuToBeLeft(MenuType.WARNING_OR_ERROR);
                    // TODO multiple alarms can be raised, e.g. if pump enters STOP mode due
                    // to battery_empty, that alert is raised and then causes a TBR CANCELLED
                    // if one was running
                    // TODO report those errors back!
                    // ... need a more controlled way to 'assemble' return data
                    // like adding a CommandResult field to a command and merge stuff into it?
                }

            }
            returnToRootMenu();
    }

    /**
     * If there's an issue, this times out eventually and throws a CommandException
     */
    private void ensureConnected() {
        try {
            boolean menuUpdateRecentlyReceived = currentMenu != null && menuLastUpdated + 1000 > System.currentTimeMillis();
            log.debug("ensureConnect, connected: " + connected + ", receiving menu updates: " + menuUpdateRecentlyReceived);
            if (menuUpdateRecentlyReceived) {
                log.debug("Pump is sending us menu updates, so we're connected");
                return;
            }

            // Occasionally the rtConnect is called a few seconds after the rtDisconnected
            // callback was called, in response to your disconnect request via doRtDisconnect.
            // When connecting again shortly after disconnecting, the pump sometimes fails
            // to come up. So for v1, just wait. This happens rarely, so no overly fancy logic needed.
            // TODO v2 see if we can do this cleaner, use isDisconnected as well maybe. GL#34.
            // TODO remove this, will be in the way of quickly reconnecting after an exception and dealing
            // with an alarm; we'll then see if the pump can deal with this
/*            if (System.currentTimeMillis() < lastDisconnected + 10 * 1000) {
                log.debug("Waiting 10s to let pump settle after recent disconnect");
                SystemClock.sleep(10 * 1000);
            }*/

            boolean connectInitSuccessful = ruffyService.doRTConnect() == 0;
            log.debug("Connect init successful: " + connectInitSuccessful);
            log.debug("Waiting for first menu update to be sent");
            // Note: there was an 'if(currentMenu == null)' around the next call, since
            // the rtDisconnected callback sets currentMenu = null. However, there were
            // race conditions, so it was removed. And really, waiting for an update
            // to come in is a much safer bet.

            // waitForMenuUpdate times out after 60s and throws a CommandException.
            // if the user just pressed a button on the combo, the screen needs to time first
            // before a connection is possible. In that case, it takes 45s before the
            // connection comes up.
//            waitForMenuUpdate(90, "Timeout connecting to pump");
            long timeoutExpired = System.currentTimeMillis() + 90 * 1000;
            long initialUpdateTime = menuLastUpdated;
            long again = System.currentTimeMillis() + 30 * 1000;
            while (initialUpdateTime == menuLastUpdated) {
                if (System.currentTimeMillis() > timeoutExpired) {
                    throw new CommandException().message("Timeout connecting to pump");
                }
                SystemClock.sleep(50);
                if (again < System.currentTimeMillis()) {
                    // TODO test
                    ruffyService.doRTDisconnect();
                    SystemClock.sleep(2000);
                    ruffyService.doRTConnect();
                    again = System.currentTimeMillis() + 30 * 1000;
                }
            }
        } catch (CommandException e) {
            throw e;
        } catch (Exception e) {
            throw new CommandException().exception(e).message("Unexpected exception while initiating/restoring pump connection");
        }
    }

    // TODO v2 add remaining info we can extract from the main menu, low battery and low
    // cartridge warnings, running extended bolus (how does that look if a TBR is active as well?)

    /**
     * This reads the state of the, which is whatever is currently displayed on the display,
     * no actions are performed.
     */
    public PumpState readPumpStateInternal() {
        PumpState state = new PumpState();
        Menu menu = currentMenu;
        if (menu == null) {
            return new PumpState().errorMsg("Menu is not available");
        }
        MenuType menuType = menu.getType();
        if (menuType == MenuType.MAIN_MENU) {
            Double tbrPercentage = (Double) menu.getAttribute(MenuAttribute.TBR);
            if (tbrPercentage != 100) {
                state.tbrActive = true;
                Double displayedTbr = (Double) menu.getAttribute(MenuAttribute.TBR);
                state.tbrPercent = displayedTbr.intValue();
                MenuTime durationMenuTime = ((MenuTime) menu.getAttribute(MenuAttribute.RUNTIME));
                state.tbrRemainingDuration = durationMenuTime.getHour() * 60 + durationMenuTime.getMinute();
                state.tbrRate = ((double) menu.getAttribute(MenuAttribute.BASAL_RATE));
            }
            state.batteryState = ((int) menu.getAttribute(MenuAttribute.BATTERY_STATE));
            state.insulinState = ((int) menu.getAttribute(MenuAttribute.INSULIN_STATE));
            // TODO v2, read current base basal rate, which is shown center when no TBR is active.
            // Check if that holds true when an extended bolus is running.
            // Add a field to PumpStatus, rather than renaming/overloading tbrRate to mean
            // either TBR rate or basal rate depending on whether a TBR is active.
        } else if (menuType == MenuType.WARNING_OR_ERROR) {
            state.errorMsg = (String) menu.getAttribute(MenuAttribute.MESSAGE);
        } else if (menuType == MenuType.STOP) {
            state.suspended = true;
            state.batteryState = ((int) menu.getAttribute(MenuAttribute.BATTERY_STATE));
            state.insulinState = ((int) menu.getAttribute(MenuAttribute.INSULIN_STATE));
        } else {
            StringBuilder sb = new StringBuilder();
            for (MenuAttribute menuAttribute : menu.attributes()) {
                sb.append(menuAttribute);
                sb.append(": ");
                sb.append(menu.getAttribute(menuAttribute));
                sb.append("\n");
            }
            state.errorMsg = "Pump is on menu " + menuType + ", listing attributes: \n" + sb.toString();
        }
        return state;
    }

    // below: methods to be used by commands
    // TODO move into a new Operations(scripter) class commands can delegate to,
    // so this class can focus on providing a connection to run commands
    // (or maybe reconsider putting it into a base class)

    public static class Key {
        public static byte NO_KEY = (byte) 0x00;
        public static byte MENU = (byte) 0x03;
        public static byte CHECK = (byte) 0x0C;
        public static byte UP = (byte) 0x30;
        public static byte DOWN = (byte) 0xC0;
        public static byte BACK = (byte) 0x33;
    }

    interface Step {
        void run(boolean waitForPumpUpdateAfterwards);
    }
/*

    private long lastPumpWrite = 0;

    private void wrapNoNotTheSubwayKind(Step step, boolean waitForPumpUpdateAfterwards) {
       if (!connected) {
          // try to reconnect, with a timeout before the pump raises a menu timeout
           // timeout = lastPumpWrite + 15 * 1000 // avoid default pump timeout of 20s
       }
       step.run(waitForPumpUpdateAfterwards);
        // TODO there's a chance the above was not executed by the pump; assume that if we're not
        // still connected and abort the command and retry if it it's retryable
        // isConnected
       lastPumpWrite = System.currentTimeMillis();

        // TODO: spike: source the ruffy driver package and do away with the remote service

        refuse to debug and fix incomprehensive code that Sandra wrote, can't explain why she
                did what she did nor commented on it

        if (!connected) {
//            cancelInternal();
//          if (activeCmd.isRetriable) {
        }
    }
*/

    // === pump ops ===
    public Menu getCurrentMenu() {
        long timeout = System.currentTimeMillis() + 5 * 1000;
        // TODO this is probably due to a disconnect and rtDisconnect having nulled currentMenu.
        // This here might just work, but needs a more controlled approach when implementing
        // something to deal with connection loses
        while (currentMenu == null) {
            if (System.currentTimeMillis() > timeout) {
                throw new CommandException().message("Unable to read current menu");
            }
            log.debug("currentMenu == null, waiting");
            waitForMenuUpdate();
        }
        return currentMenu;
    }

    public void pressUpKey() {
//        wrapNoNotTheSubwayKind(new Step() {
//            @Override
//            public void doStep() {
        log.debug("Pressing up key");
        pressKey(Key.UP);
        log.debug("Releasing up key");
//            }
//        });
    }

    public void pressDownKey() {
        log.debug("Pressing down key");
        pressKey(Key.DOWN);
        log.debug("Releasing down key");
    }

    public void pressCheckKey() {
        log.debug("Pressing check key");
        pressKey(Key.CHECK);
        log.debug("Releasing check key");
    }

    public void pressMenuKey() {
        log.debug("Pressing menu key");
        pressKey(Key.MENU);
        log.debug("Releasing menu key");
    }

    public void pressBackKey() {
        log.debug("Pressing back key");
        pressKey(Key.BACK);
        log.debug("Releasing back key");
    }

    public void pressKeyMs(final byte key, long ms) {
        long stepMs = 100;
        try {
            log.debug("Scroll: Pressing key for " + ms + " ms with step " + stepMs + " ms");
            ruffyService.rtSendKey(key, true);
            ruffyService.rtSendKey(key, false);
            while (ms > stepMs) {
                SystemClock.sleep(stepMs);
                ruffyService.rtSendKey(key, false);
                ms -= stepMs;
            }
            SystemClock.sleep(ms);
            ruffyService.rtSendKey(Key.NO_KEY, true);
            log.debug("Releasing key");
        } catch (Exception e) {
            throw new CommandException().exception(e).message("Error while pressing buttons");
        }
    }

    // TODO sort out usages of this method and waitForMenu update, which have the same intent,
    // but approach things differently;
    public boolean waitForScreenUpdate(long timeout) {
        synchronized (screenlock) {
            try {
                screenlock.wait(timeout);
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    // TODO v2, rework these two methods: waitForMenuUpdate shoud only be used by commands
    // then anything longer than a few seconds is an error;
    // only ensureConnected() uses the method with the timeout parameter; inline that code,
    // so we can use a custom timeout and give a better error message in case of failure

    // TODO confirmAlarms? and report back which were cancelled?

    /**
     * Confirms and dismisses the given alert if it's raised before the timeout
     */
    public boolean confirmAlert(String alertMessage, int maxWaitMs) {
        long inFiveSeconds = System.currentTimeMillis() + maxWaitMs;
        boolean alertProcessed = false;
        while (System.currentTimeMillis() < inFiveSeconds && !alertProcessed) {
            if (getCurrentMenu().getType() == MenuType.WARNING_OR_ERROR) {
                // Note that the message is permanently displayed, while the error code is blinking.
                // A wait till the error code can be read results in the code hanging, despite
                // menu updates coming in, so just check the message.
                // TODO quick try if the can't make reading the error code work ..
                String errorMsg = (String) getCurrentMenu().getAttribute(MenuAttribute.MESSAGE);
                if (!errorMsg.equals(alertMessage)) {
                    throw new CommandException().success(false).enacted(false)
                            .message("An alert other than the expected " + alertMessage + " was raised by the pump: "
                                    + errorMsg + ". Please check the pump.");
                }
                // confirm alert
                verifyMenuIsDisplayed(MenuType.WARNING_OR_ERROR);
                pressCheckKey();
                // dismiss alert
                verifyMenuIsDisplayed(MenuType.WARNING_OR_ERROR);
                pressCheckKey();
                waitForMenuToBeLeft(MenuType.WARNING_OR_ERROR);
                alertProcessed = true;
            }
            SystemClock.sleep(10);
        }
        return alertProcessed;
    }

    /**
     * Wait until the menu is updated
     */
    public void waitForMenuUpdate() {
        waitForMenuUpdate(60, "Timeout waiting for menu update");
    }

    public void waitForMenuUpdate(long timeoutInSeconds, String errorMessage) {
        long timeoutExpired = System.currentTimeMillis() + timeoutInSeconds * 1000;
        long initialUpdateTime = menuLastUpdated;
        while (initialUpdateTime == menuLastUpdated) {
            if (System.currentTimeMillis() > timeoutExpired) {
                throw new CommandException().message(errorMessage);
            }
            SystemClock.sleep(50);
        }
    }

    private void pressKey(final byte key) {
        try {
            ruffyService.rtSendKey(key, true);
            SystemClock.sleep(200);
            ruffyService.rtSendKey(Key.NO_KEY, true);
        } catch (Exception e) {
            throw new CommandException().exception(e).message("Error while pressing buttons");
        }
    }

    public void navigateToMenu(MenuType desiredMenu) {
//        MenuType startedFrom = getCurrentMenu().getType();
//        boolean movedOnce = false;
        int retries = 20;
        while (getCurrentMenu().getType() != desiredMenu) {
            retries --;
            MenuType currentMenuType = getCurrentMenu().getType();
            log.debug("Navigating to menu " + desiredMenu + ", current menu: " + currentMenuType);
//            if (movedOnce && currentMenuType == startedFrom) {
//                throw new CommandException().message("Menu not found searching for " + desiredMenu
//                        + ". Check menu settings on your pump to ensure it's not hidden.");
//            }
            if (retries == 0) {
                throw new CommandException().message("Menu not found searching for " + desiredMenu
                        + ". Check menu settings on your pump to ensure it's not hidden.");
            }
            pressMenuKey();
//            waitForMenuToBeLeft(currentMenuType);
            SystemClock.sleep(200);
//            movedOnce = true;
        }
    }

    /**
     * Wait till a menu changed has completed, "away" from the menu provided as argument.
     */
    public void waitForMenuToBeLeft(MenuType menuType) {
        long timeout = System.currentTimeMillis() + 60 * 1000;
        while (getCurrentMenu().getType() == menuType) {
            if (System.currentTimeMillis() > timeout) {
                throw new CommandException().message("Timeout waiting for menu " + menuType + " to be left");
            }
            SystemClock.sleep(10);
        }
    }

    public void verifyMenuIsDisplayed(MenuType expectedMenu) {
        verifyMenuIsDisplayed(expectedMenu, null);
    }

    public void verifyMenuIsDisplayed(MenuType expectedMenu, String failureMessage) {
        int retries = 600;
        while (getCurrentMenu().getType() != expectedMenu) {
            if (retries > 0) {
                SystemClock.sleep(100);
                retries = retries - 1;
            } else {
                if (failureMessage == null) {
                    failureMessage = "Invalid pump state, expected to be in menu " + expectedMenu + ", but current menu is " + currentMenu.getType();
                }
                throw new CommandException().message(failureMessage);
            }
        }
    }

    public void verifyRootMenuIsDisplayed() {
        int retries = 600;
        while (getCurrentMenu().getType() != MenuType.MAIN_MENU && getCurrentMenu().getType() != MenuType.STOP) {
            if (retries > 0) {
                SystemClock.sleep(100);
                retries = retries - 1;
            } else {
                throw new CommandException().message("Invalid pump state, expected to be in menu MAIN or STOP but current menu is " + currentMenu.getType());
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T readBlinkingValue(Class<T> expectedType, MenuAttribute attribute) {
        int retries = 5;
        Object value = getCurrentMenu().getAttribute(attribute);
        while (!expectedType.isInstance(value)) {
            value = getCurrentMenu().getAttribute(attribute);
            waitForScreenUpdate(1000);
            retries--;
            if (retries == 0) {
                throw new CommandException().message("Failed to read blinkng value: " + attribute + "=" + value + " type=" + value);
            }
        }
        return (T) value;
    }

    @Override
    public CommandResult deliverBolus(double amount, BolusProgressReporter bolusProgressReporter) {
        return runCommand(new BolusCommand(amount, bolusProgressReporter));
    }

    @Override
    public void cancelBolus() {
        if (activeCmd instanceof BolusCommand) {
            ((BolusCommand) activeCmd).requestCancellation();
        }
    }

    @Override
    public CommandResult setTbr(int percent, int duration) {
        return runCommand(new SetTbrCommand(percent, duration));
    }

    @Override
    public CommandResult cancelTbr() {
        return runCommand(new CancelTbrCommand());
    }

    @Override
    public CommandResult takeOverAlarms() {
        if (getCurrentMenu().getType() != MenuType.WARNING_OR_ERROR) {
            return new CommandResult().success(false).enacted(false).message("No alarm active on the pump");
        }
        while (currentMenu.getType() == MenuType.WARNING_OR_ERROR) {
            new Error(System.currentTimeMillis(),
                    "",
                    // TODO
                    // codes unqiue across W/E?
//                    (int) currentMenu.getAttribute(MenuAttribute.WARNING),
//                    (int) currentMenu.getAttribute(MenuAttribute.ERROR),
                    (String) currentMenu.getAttribute(MenuAttribute.MESSAGE));
        }
        // confirm alert
        verifyMenuIsDisplayed(MenuType.WARNING_OR_ERROR);
        pressCheckKey();
        // dismiss alert
        verifyMenuIsDisplayed(MenuType.WARNING_OR_ERROR);
        pressCheckKey();
        waitForMenuToBeLeft(MenuType.WARNING_OR_ERROR);

        PumpState pumpState = readPumpStateInternal();
        return new CommandResult()
                .success(true)
                .enacted(false /* well, no treatments were enacted ... */)
                .message(pumpState.errorMsg) // todo yikes?
                .state(pumpState);
    }

    @Override
    public CommandResult readHistory(PumpHistoryRequest request) {
        return runCommand(new ReadHistoryCommand(request));
    }

    @Override
    public CommandResult readBasalProfile(int number) {
        return runCommand(new ReadBasalProfileCommand(number));
    }

    @Override
    public CommandResult setBasalProfile(BasalProfile basalProfile) {
        return runCommand(new SetBasalProfileCommand(basalProfile));
    }

    @Override
    public CommandResult setDateAndTime(Date date) {
        // TODO I'm a faker!
        return new CommandResult().success(true).enacted(false);
    }

    @Override
    public void requestPairing() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void sendAuthKey(String key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void unpair() {
        throw new UnsupportedOperationException();
    }
}