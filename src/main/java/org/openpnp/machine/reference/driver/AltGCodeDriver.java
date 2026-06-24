package org.openpnp.machine.reference.driver;

import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.model.AxesLocation;
import org.openpnp.model.Configuration;
import org.openpnp.spi.*;
import org.tinylog.Logger;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class AltGCodeDriver extends GcodeDriver {

    GcodeProcessor gcodeWriter;
    GCodeCommandRules rules = new GCodeCommandRules();
    boolean checkResponses =true;
    ResponseThread responseThread;

    public AltGCodeDriver(GcodeProcessor gcodeWriter, GCodeCommandRules rules) {
        this.gcodeWriter = gcodeWriter;
        this.rules = rules;

        startResponseHandler();
    }
    public void startResponseHandler(){
        responseThread = new ResponseThread();
        responseThread.setDaemon(true);
        responseThread.start();
    }
    @Override
    protected void sendGcode(String gCode, long timeout) throws Exception {
         if( gcodeWriter == null ){
             Logger.error("No valid gcode writer available");
             return;
         }
        if (timeout == -1) {
            timeout = infinityTimeoutMilliseconds;
        }
        var cmd = PendingCommand.create(gCode).timeout( timeout );
        var result = gcodeWriter.sendGcode( cmd );
        // Do something with the result?
    }
    protected void sendGcode( PendingCommand gCode ){
        if( gcodeWriter == null ){
            Logger.error("No valid gcode writer available");
            return;
        }

        var result = gcodeWriter.sendGcode( gCode );
        // Do something with the result?
    }
    public void disableMachine() throws Exception {
        Configuration.get().getMachine().setEnabled(false);
    }
    public void sendCommand(String command, long timeout) throws Exception {
        // An error may have popped up in the meantime. Check and bail on it, before sending the next command.
        bailOnError();

        // After sending this, we want one more confirmation.
        // TODO: true queued reporting. For now it is sufficient to poll one for one.
        receivedConfirmationsQueue.clear();
        try {
            // Send the command.
            getCommunications().writeLine(command);
        }
        catch (IOException ex) {
            org.pmw.tinylog.Logger.error(ex, "{} failed to write command {}", getCommunications().getConnectionName(), command);
            disconnect();
            Configuration.get().getMachine().setEnabled(false);
        }
        waitForConfirmation(command, timeout);
        if (command.startsWith("$")) {
            Thread.sleep(dollarWaitTimeMilliseconds);
        }
    }
    public void setCommand(HeadMountable hm, CommandType type, String text) {
        setCommand(hm, type, text);
        if(type==CommandType.COMMAND_CONFIRM_REGEX){
            rules.setConfirmCommandRegex(text);
        }else if(type==CommandType.COMMAND_ERROR_REGEX){
            rules.setErrorCommandRegex(text);
        }
    }
    @Override
    public void home(Machine machine) throws Exception {
        // Home is sent with an infinite timeout since it's tough to tell how long it will
        // take.
        String command = getCommand(null, CommandType.HOME_COMMAND);
        // legacy head support
        Head head = machine.getDefaultHead();
        command = substituteVariable(command, "Id", head.getId());
        command = substituteVariable(command, "Name", head.getName());
        if (isUsingLetterVariables()) {
            AxesLocation axesHomeLocation =  new AxesLocation(machine,
                    CoordinateAxis::getHomeCoordinate);
            Double feedrate = null;
            Double acceleration = null;
            Double jerk = null;
            for (String variable : getAxisVariables((ReferenceMachine) machine)) {
                ControllerAxis axis = axesHomeLocation.getAxisByVariable(this, variable);
                if (axis != null) {
                    double coordinate;
                    if (axis.getType() == Axis.Type.Rotation) {
                        // Never convert rotation to driver units.
                        coordinate = axesHomeLocation.getCoordinate(axis);
                    }
                    else {
                        coordinate = axesHomeLocation.getCoordinate(axis, getUnits());
                    }
                    command = substituteVariable(command, variable, coordinate);
                    command = substituteVariable(command, variable+"L",
                            axis.getLetter());

                    // Because in homing we don't know which axis is moved when and in what combination,
                    // we need to find the lowest rates of any axis.
                    if (axis.getMotionLimit(1) != 0.0) {
                        if (feedrate == null || feedrate > axis.getMotionLimit(1)) {
                            feedrate = axis.getMotionLimit(1);
                        }
                    }
                    if (axis.getMotionLimit(2) != 0.0) {
                        if (acceleration == null || acceleration > axis.getMotionLimit(2)) {
                            acceleration = axis.getMotionLimit(2);
                        }
                    }
                    if (axis.getMotionLimit(3) != 0.0) {
                        if (jerk == null || jerk > axis.getMotionLimit(3)) {
                            jerk = axis.getMotionLimit(3);
                        }
                    }
                }
                else {
                    command = substituteVariable(command, variable, null);
                    command = substituteVariable(command, variable+"L", null);
                }
            }

            if (getMotionControlType().isUnpredictable()) {
                // Do not initialize rates, as the motion control is unpredictable, i.e. not controlled by us.
                command = sendOnChangeSubstituteAllVariables(command, null, null, null);
            }
            else {
                // For the purpose of homing, initialize the rates to the lowest of any axis.
                command = sendOnChangeSubstituteAllVariables(command, feedrate, acceleration, jerk);
            }
        }
        else {
            // Do not initialize rates in legacy mode.
            command = sendOnChangeSubstituteAllVariables(command, null, null, null);
        }

        var homeCmd = PendingCommand.create(command)
                .timeout( -1 )
                .confirmRegex( getCommand(null, CommandType.HOME_COMPLETE_REGEX) )
                .onLastConfirmation(()->homeConfirmed(machine))
                .onTimeOut(()->Logger.error("Timed out waiting for home to complete."));
        sendGcode(homeCmd);
    }
    protected class ResponseThread extends Thread {
        @Override
        public void run() {
            while(checkResponses){
                GcodeSerialWriter.CommandResponse response = null;
                try {
                    // Timeout so we can shut this down cleanly with checkResponses
                    response = gcodeWriter.getResponseQueue().poll(1, TimeUnit.SECONDS);
                    if( response == null )
                        continue;
                    var ori = response.original();
                    switch(response.result()){
                        case FAILED -> {
                            org.pmw.tinylog.Logger.error( "{} failed to write command {}", gcodeWriter.id(), ori.command());
                            disconnect();
                            try {
                                Configuration.get().getMachine().setEnabled(false);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }
                        case CONFIRMED -> {
                            // Nothing to do?
                            ori.doConfirmation();
                        }
                        case ERROR -> {
                            org.pmw.tinylog.Logger.error("{} error response from controller: {}", response.response(), gcodeWriter.id());
                        }
                        case TIMEOUT -> {
                            org.pmw.tinylog.Logger.error("Timeout from controller when sending: {}", ori.command());
                            ori.doTimeOut();
                        }
                        case MAYBE_LOCATION -> processPositionReport(new Line(response.response()));
                    }
                } catch (InterruptedException ignored) {

                }
            }
        }
    }

    public void homeConfirmed(Machine machine) {
        AxesLocation homeLocation = new AxesLocation( machine, this, CoordinateAxis::getHomeCoordinate);
        homeLocation.setToDriverCoordinates(this);

        // reset send-on-change behavior
        sendOnChangeResetAll();
    }
    public void homeTimeOut(){
        // throw error?
    }
}
