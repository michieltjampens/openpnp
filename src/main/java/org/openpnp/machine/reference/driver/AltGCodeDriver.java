package org.openpnp.machine.reference.driver;

import eu.settlabs.serialport.SerialStream;
import org.openpnp.machine.reference.ReferenceActuator;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.SimulationModeMachine;
import org.openpnp.machine.reference.driver.exceptions.*;
import org.openpnp.model.AxesLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.spi.*;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;
import org.pmw.tinylog.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AltGCodeDriver extends GcodeDriver {

    GCodeCommandRules rules = new GCodeCommandRules();
    GcodeWriter gcodeWriter = new GcodeWriter(rules);

    boolean checkResponses =true;
    private boolean simulate=false;
    ResponseThread responseThread;
    private int confirmsNeeded=1;

    @Attribute(required=false)
    private long writerPollingInterval = 100;

    @Attribute(required=false)
    private long writerQueueTimeout = 60000;

    @Attribute(required=false)
    private int maxCommandsQueued = 1000;

    @Attribute(required=false)
    private boolean confirmationFlowControl = true;

    @Attribute(required=false)
    private boolean reportedLocationConfirmation = true;

    @Attribute(required = false)
    private int interpolationMaxSteps = 32;

    @Attribute(required = false)
    private int interpolationJerkSteps = 4; // relative to max acceleration

    @Attribute(required = false)
    private double interpolationTimeStep = 0.001;

    @Attribute(required = false)
    private int interpolationMinStep = 16;

    @Element(required = false)
    private Length junctionDeviation = new Length(0.02, LengthUnit.Millimeters);

    @Attribute(required = false)
    private String homeValidTimeout = "0s";

    @Override
    public Integer getInterpolationMaxSteps() {
        return interpolationMaxSteps;
    }

    public void setInterpolationMaxSteps(Integer interpolationMaxSteps) {
        this.interpolationMaxSteps = interpolationMaxSteps;
    }

    @Override
    public Integer getInterpolationJerkSteps() {
        return interpolationJerkSteps;
    }

    public void setInterpolationJerkSteps(Integer interpolationJerkSteps) {
        this.interpolationJerkSteps = interpolationJerkSteps;
    }

    @Override
    public Double getInterpolationTimeStep() {
        return interpolationTimeStep;
    }

    public void setInterpolationTimeStep(Double interpolationTimeStep) {
        this.interpolationTimeStep = interpolationTimeStep;
    }

    @Override
    public Integer getInterpolationMinStep() {
        return interpolationMinStep;
    }

    public void setInterpolationMinStep(Integer interpolationMinStep) {
        this.interpolationMinStep = interpolationMinStep;
    }

    @Override
    public Length getJunctionDeviation() {
        return junctionDeviation;
    }

    public void setJunctionDeviation(Length junctionDeviation) {
        this.junctionDeviation = junctionDeviation;
    }


    public void startResponseHandler(){
        responseThread = new ResponseThread();
        responseThread.setDaemon(true);
        responseThread.start();
    }
    @Override
    public void sendCommand(String command) throws Exception {
        sendSyncGcode(GcodeCommand.create(command).timeout( timeoutMilliseconds ));
    }
    public void sendCommand(String command, long timeout) throws Exception {
        sendSyncGcode(GcodeCommand.create(command).timeout( timeout ));
    }
    @Override
    protected void sendGcode(String gCode, long timeout) throws Exception {
        var gcode = GcodeCommand.create(gCode).timeout( timeout ).enableFuture();
        sendAsyncGcode( gcode );
    }
    public void sendSyncGcode(GcodeCommand gcode) throws GcodeException {
        gcode.enableFuture();
        if( gcode.isInvalid() ){
            Logger.debug("{} -> Invalid gCode command, ignoring: {}",name,gcode.toString());
            return;
        }
        if( simulate || name.toLowerCase().contains("feeder")){
            Logger.debug( "{} -> Simulating, not executing: {}",name,gcode.toString());
            return;
        }
        sendAsyncGcode(gcode);
        try {
            gcode.replyFuture().get(); // no timeout arg — orTimeout/replyTimeoutOccurred guarantee completion
        } catch (ExecutionException e) {
            switch (e.getCause()) {
                case GcodeTimeoutException te -> {
                    Logger.error("{} -> Timed out: {}", name, te.getMessage());
                    throw te;
                }
                case GcodeErrorReplyException ee -> {
                    Logger.error("{} -> Controller error: {}",name, ee.command().reply());
                }
                case GcodeSendFailedException se -> {
                    Logger.error("{} -> Couldn't send: {}",name, se.getMessage());
                }
                case GcodeRegexMismatchException re -> {
                    Logger.error("{} -> Unexpected reply: {}",name, re.command().reply());
                }
                default -> {
                    Logger.error("{} -> Unknown failure: {}",name, e.getCause());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    protected void sendAsyncGcode( GcodeCommand gCode ){
        if( gCode.isInvalid()) {
            return;
        }
        if ( gCode.timeout() == -1) {
            gCode.timeout( infinityTimeoutMilliseconds);
        }

        if (getFirmwareProperty("FIRMWARE_NAME", "").contains("Marlin")) {
            gCode.linesToCheck(2);
        }
       // gCode.confirmsNeeded(confirmsNeeded);
        gcodeWriter.sendGcode( gCode );
    }

    public void setCommand(HeadMountable hm, CommandType type, String text) {
        super.setCommand(hm, type, text);
        if(type==CommandType.COMMAND_CONFIRM_REGEX){
            rules.setConfirmCommandRegex(text);
        }else if(type==CommandType.COMMAND_ERROR_REGEX){
            rules.setErrorCommandRegex(text);
        }
    }

    @Override
    public boolean isMotionPending() {
        return gcodeWriter.isMotionPending();
    }
    @Override
    public void waitForCompletion(HeadMountable hm,
                                  MotionPlanner.CompletionType completionType) throws Exception {
        if (!(completionType.isUnconditionalCoordination()
                || isMotionPending())) {
            return;
        }
        // New
        var gcode = GcodeCommand.create( getCommand(hm, CommandType.MOVE_TO_COMPLETE_COMMAND) );
        if ( gcode.isInvalid()) { // Checks command being null
            return;
        }
        var timeout = completionType == MotionPlanner.CompletionType.WaitForStillstandIndefinitely ?
                -1 : getTimeoutAtMachineSpeed();
        gcode.timeout( timeout )
                .confirmRegex( getCommand(hm, CommandType.MOVE_TO_COMPLETE_REGEX) ); // If null, default is still used
        sendSyncGcode( gcode ); // A blocking method until either timeout or valid reply
        // By default, the driver will check till timeout expired. So I could make a future that is triggered
        // by the driver receiving timeout notification but than the future would use get() and hand if something
        // goes wrong. This was (get a confirm with some margin) does the same thing
        // If timeout is -1, the earlier sendgocdegetreply replaces that with the default
        if (completionType.isEnforcingStillstand()){
            // Remember, we're now standing still.
            motionPending = false;
        }
    }
    @Override
    protected void detectFirmwareSequence() throws Exception {
        org.pmw.tinylog.Logger.debug("=== Detecting firmware and position reporting, please ignore any errors and warnings.");
        var firmwareGcode = GcodeCommand.create("M115").confirmRegex("^.*FIRMWARE.*");
        sendSyncGcode( firmwareGcode );
        if ( firmwareGcode.isConfirmed() ) {
            setDetectedFirmware(firmwareGcode.reply());
        }

        var axesGcode = GcodeCommand.create("M114").confirmRegex(".*[XYZABCDEUVW]:-?\\d+\\.\\d+.*");
        sendSyncGcode( axesGcode );
        if (axesGcode.isConfirmed()) {
            if (firmwareGcode.isConfirmed() ) {
                try {
                    if (getFirmwareProperty("FIRMWARE_NAME", "").contains("Duet")) {
                        var driverAsignGcode = GcodeCommand.create("M584").confirmRegex("^Driver assignments:.*");
                        sendSyncGcode(driverAsignGcode);
                        if (driverAsignGcode.isConfirmed()) {
                            setConfiguredAxes(driverAsignGcode.reply());
                        }
                    }
                    else {
                        setConfiguredAxes(null);
                    }
                }
                catch (Exception e) {
                    // ignore
                }
            }
            setReportedAxes(reportedAxes);
        }
        org.pmw.tinylog.Logger.debug("=== End detecting firmware and position reporting.");
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
            command = alterFeedsAndSpeeds(command,axesHomeLocation,(ReferenceMachine) machine);
        } else {
            // Do not initialize rates in legacy mode.
            command = sendOnChangeSubstituteAllVariables(command, null, null, null);
        }

        var homeCmd = GcodeCommand.create( command )
                .timeout( -1 )
                .confirmRegex( getCommand(null, CommandType.HOME_COMPLETE_REGEX) )
                .onLastConfirmation(()->homeConfirmed(machine))
                .onTimeOut(()->Logger.error("Timed out waiting for home to complete."));
        sendSyncGcode(homeCmd); // Blocks and throws exceptions
    }
    private String alterFeedsAndSpeeds(String command, AxesLocation axesHomeLocation, ReferenceMachine machine ) throws Exception {
        Double feedrate = null;
        Double acceleration = null;
        Double jerk = null;

        for (String variable : getAxisVariables(machine)) {
            ControllerAxis axis = axesHomeLocation.getAxisByVariable(this, variable);
            if (axis == null) {
                command = substituteVariable(command, variable, null);
                command = substituteVariable(command, variable + "L", null);
                continue;
            }

            double coordinate;
            if (axis.getType() == Axis.Type.Rotation) {
                // Never convert rotation to driver units.
                coordinate = axesHomeLocation.getCoordinate(axis);
            }else {
                coordinate = axesHomeLocation.getCoordinate(axis, getUnits());
            }
            command = substituteVariable(command, variable, coordinate);
            command = substituteVariable(command, variable+"L",axis.getLetter());

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
        // Do not initialize rates, if the motion control is unpredictable, i.e. not controlled by us.
        if (getMotionControlType().isUnpredictable()) {
            return sendOnChangeSubstituteAllVariables(command, null, null, null);
        }
        // For the purpose of homing, initialize the rates to the lowest of any axis.
        return sendOnChangeSubstituteAllVariables(command, feedrate, acceleration, jerk);
    }

    /**
     * Presumably connects?
     * @throws Exception
     */
    public synchronized void connect() throws Exception {
        disconnectRequested = false;
        getCommunications().setDriverName(getName());
        org.pmw.tinylog.Logger.debug("[{}] Connect", getCommunications().getConnectionName());

        if(getCommunications() instanceof SerialPortCommunications comms) {
            rules.setCleaning(compressGcode,removeComments);
            rules.setLogging( loggingGcode );
            gcodeWriter.setId(getName());
            if( gcodeWriter.getBaseStream() == null ) {
                gcodeWriter.setBaseStream( new SerialStream(comms.getPortName()) );
                gcodeWriter.enableGlobalHomeTimeout(homeValidTimeout);
                gcodeWriter.enableStepperTimeout("60s");
            }
            if (getFirmwareProperty("FIRMWARE_NAME", "").contains("Smoothie")){
                System.out.println("Smoothieware detected, defaulting to two confirms.");
                confirmsNeeded=2;
            }

            var stream = (SerialStream) gcodeWriter.getBaseStream();
            stream.setEol( comms.getLineEndingType().lineEnding );
            stream.disconnect();

            stream.setPort(comms.getPortName()); // Doesn't do anything if this didn't change

            var port = stream.getSerialPort();
            port.setBaudRate(comms.getBaud());
            port.setFlowControl(comms.getFlowControl().mask );
            port.setNumStopBits( comms.getStopBits().mask );
            port.setBaudRate( comms.getBaud() );

            stream.initAndConnect();
        }else{
            Logger.error("TCP not supported yet");
            return;
        }
        if( name.toLowerCase().contains("feeder") )
            simulate=true;
        connected = false;

        startResponseHandler();

        // Wait a bit while the controller starts up
        /* TODO
            This doesn't actually check anything so rather pointless.
            Instead it's better to just send the command and use the states of the driver to determine
            If there's actually a connecting to a proper controller UNINIT->INIT->IDLE
            Use the confirmation of the connect command to set connected etc instead of assuming 'all is well'
         */
        org.pmw.tinylog.Logger.trace(getName()+" waiting for connection "+connectWaitTimeMilliseconds+"ms");
        Thread.sleep(connectWaitTimeMilliseconds);
        // TODO Nothing is checked ??
        // Consuming any startup/unsolicited messages is done by default

        // Disable the driver
        setEnabled(false);  // TODO this can call the super directly given it's during connect

        // Send startup Gcode
        var connect = getCommand(null, CommandType.CONNECT_COMMAND);
        var cmd = GcodeCommand.create(connect);
        sendAsyncGcode( cmd );   // TODO Nothing is done with the reply??

        connected = true;

        // reset send-on-change behavior
        sendOnChangeResetAll();
    }
    public synchronized void disconnect() {
        disconnectRequested = true;
        connected = false;
        gcodeWriter.disconnect();

        disconnectThreads();
    }
    @Override
    public void setEnabled(boolean enabled) throws Exception {
        if (enabled && !connected) {
            connect();
        }
        if (connected) {
            if (enabled) {
                sendAsyncGcode( GcodeCommand.create( getCommand(null, CommandType.ENABLE_COMMAND)) );
            }
            else {
                try {
                    sendAsyncGcode( GcodeCommand.create(getCommand(null, CommandType.DISABLE_COMMAND)) );
                    gcodeWriter.drainCommandQueue(getTimeoutAtMachineSpeed());
                    /* TODO figure out what the intention is
                        The disable command should probably be understood by the writer to cease all activity
                        Then the confirmation of that, can be the trigger to disconnect?
                     */

                }
                catch (Exception e) {
                    // When the connection is lost, we have IO errors. We should still be able to go on
                    // disabling the machine.
                    org.pmw.tinylog.Logger.warn(e);
                }
                if (isInSimulationMode() || !connectionKeepAlive) {
                    disconnect();
                }
            }
        }
        super.setEnabled(enabled);
    }
    public List<Line> receiveResponses() throws Exception {
    //    bailOnError();
        List<Line> responses = new ArrayList<>();
        // Read any responses that might be queued up.
        responseQueue.drainTo(responses);
        return responses;
    }

    @Override
    public boolean delay(int milliseconds) throws Exception {
        String command = getCommand(null, CommandType.DELAY_COMMAND);
        if (command == null || command.isEmpty()) {
            org.pmw.tinylog.Logger.trace("delaying in gcode driver is not supported");
            return false;
        }
        if (milliseconds < 1) {
            org.pmw.tinylog.Logger.trace("gcode delay not sent for zero duration");
            return true;
        }

        command = substituteVariable(command, "TimeMS", milliseconds);
        command = substituteVariable(command, "TimeSeconds", (double)milliseconds / 1000.0);
        sendSyncGcode( GcodeCommand.create(command) );

        // consider this delay a pending motion for subsequent WaitForCompletion to actually
        // wait which might otherwise be optimized away.
        // motionpending=true; TODO removed this flag given it's handled by queue state
        org.pmw.tinylog.Logger.trace("gcode delay sent");
        return true;
    }
    @Override
    public void setGlobalOffsets(Machine machine, AxesLocation axesLocation)
            throws Exception {
        // Compose the command
        String command = getCommand(null, CommandType.SET_GLOBAL_OFFSETS_COMMAND);
        // If not a valid command, try the legacy POST_VISION_HOME_COMMAND
        if( command==null){
            doLegacyPostVisionHome(axesLocation);
            return;
        }

        // legacy head support
        Head head = machine.getDefaultHead();
        command = substituteVariable(command, "Id", head.getId());
        command = substituteVariable(command, "Name", head.getName());

        boolean isEmpty = true;
        for (String variable : getAxisVariables((ReferenceMachine) machine)) {
            ControllerAxis axis = axesLocation.getAxisByVariable(this, variable);
            if (axis != null) {
                if (hasVariable(command, variable)) {
                    double coordinate;
                    if (axis.getType() == Axis.Type.Rotation) {
                        // Never convert rotation to driver units.
                        coordinate = axesLocation.getCoordinate(axis);
                    }
                    else {
                        coordinate = axesLocation.getCoordinate(axis, getUnits());
                    }
                    command = substituteVariable(command, variable, coordinate);
                    command = substituteVariable(command, variable+"L",
                            axis.getLetter());
                    // Store the new driver coordinate on the axis.
                    axis.setDriverCoordinate(coordinate);
                    isEmpty = false;
                }
                else {
                    // It is imperative that the axis global offset is really set. Otherwise all bets
                    // are off and collisions in subsequent moves are very likely.
                    throw new Exception("Axis variable "+variable+" is missing in SET_GLOBAL_OFFSETS_COMMAND.");
                }
            }
            else {
                command = substituteVariable(command, variable, null);
                command = substituteVariable(command, variable+"L", null);
            }
        }
        if (!isEmpty) {
            // If no axes are included, the G92 command must not be executed, because it would otherwise reset all
            // axes to zero in some controllers!
            sendAsyncGcode( GcodeCommand.create(command).timeout(-1) ); // -1 is also default, but just in case
        }
    }
    private void doLegacyPostVisionHome( AxesLocation axesLocation) throws Exception {
        String postVisionHomeCommand = getCommand(null, CommandType.POST_VISION_HOME_COMMAND);
        ControllerAxis axisX = axesLocation.getAxisByVariable(this, "X");
        ControllerAxis axisY = axesLocation.getAxisByVariable(this, "Y");

        if (postVisionHomeCommand == null || axisX == null  || axisY == null) {
            return;
        }
        // X, Y, are mapped to this driver, legacy support enabled
        postVisionHomeCommand = substituteVariable(postVisionHomeCommand, "X",
                axesLocation.getCoordinate(axisX, getUnits()));
        postVisionHomeCommand = substituteVariable(postVisionHomeCommand, "Y",
                axesLocation.getCoordinate(axisY, getUnits()));
        // Execute the command
        sendAsyncGcode( GcodeCommand.create(postVisionHomeCommand)
                .timeout(-1)
                .onLastConfirmation(()->alterHomeCoords(axesLocation) ) );
    }
    protected void alterHomeCoords( AxesLocation axesLocation ) {
        try {
            ControllerAxis axisX = axesLocation.getAxisByVariable(this, "X");
            ControllerAxis axisY = axesLocation.getAxisByVariable(this, "Y");

            // Store the new current coordinate on the axis.
            axisX.setDriverCoordinate(axesLocation.getCoordinate(axisX, getUnits()));
            axisY.setDriverCoordinate(axesLocation.getCoordinate(axisY, getUnits()));
        }catch (Exception ignored) {
            // This can be ignored because the method is only reached after a command was issued that already
            // checked this
        }
    }
    @Override
    public AxesLocation getReportedLocation(long timeout) throws Exception {
        String command = getCommand(null, CommandType.GET_POSITION_COMMAND);
        if (command == null) {
            throw new Exception(getName()+" configuration error: missing GET_POSITION_COMMAND.");
        }
        var regex = getCommand(null, CommandType.POSITION_REPORT_REGEX);
        if( regex == null) {
            throw new Exception(getName()+" configuration error: missing POSITION_REPORT_REGEX.");
        }

        // True queued reporting
        var cmd = GcodeCommand.create(command).timeout(-1)
                        .markLocationRequest()
                        .confirmRegex(regex);
        sendAsyncGcode( cmd );

        // This part can stay because the earlier markLocationRequest makes sure it ends up in this queue
        AxesLocation lastReportedLocation = reportedLocationsQueue.poll(timeout, TimeUnit.MILLISECONDS);
        if (lastReportedLocation != null) {
            org.pmw.tinylog.Logger.trace("{} got lastReportedLocation {}", getName(), lastReportedLocation);
            return lastReportedLocation;
        }
        // Timeout expired.
        throw new Exception(getName()+" timeout waiting for response to " + command);
    }
    @Override
    public void actuate(Actuator actuator, boolean on) throws Exception {
        String command = getCommand(actuator, CommandType.ACTUATE_BOOLEAN_COMMAND);
        // This substitution must come first, as it may contain nested and escaped {variables}.
        command = substituteVariable(command, "True", on ? on : null);
        command = substituteVariable(command, "False", on ? null : on);

        command = substituteVariable(command, "Id", actuator.getId());
        command = substituteVariable(command, "Name", actuator.getName());
        if (actuator instanceof ReferenceActuator) {
            command = substituteVariable(command, "Index", ((ReferenceActuator)actuator).getIndex());
        }
        command = substituteVariable(command, "BooleanValue", on);
        sendAsyncGcode( GcodeCommand.create(command).onLastConfirmation(()->simulateActuate(actuator,on)));
    }
    private void simulateActuate(Actuator actuator, Object value) {
        try {
            SimulationModeMachine.simulateActuate(actuator, value, true);
        }catch (Exception ignored) {
            // TODO raise this somewhere
        }
    }
    @Override
    public void actuate(Actuator actuator, double value) throws Exception {
        String command = getCommand(actuator, CommandType.ACTUATE_DOUBLE_COMMAND);
        command = substituteVariable(command, "Id", actuator.getId());
        command = substituteVariable(command, "Name", actuator.getName());
        if (actuator instanceof ReferenceActuator) {
            command = substituteVariable(command, "Index", ((ReferenceActuator)actuator).getIndex());
        }
        command = substituteVariable(command, "DoubleValue", value);
        command = substituteVariable(command, "IntegerValue", (int) value);
        sendSyncGcode( GcodeCommand.create(command).onLastConfirmation(()->simulateActuate(actuator,value)));
    }
    @Override
    public String actuatorRead(Actuator actuator, Object parameter) throws Exception {
        /*
         * The logic here is a little complicated. This is the only driver method that is
         * not fire and forget. In this case, we need to know if the command was serviced or not
         * and throw an Exception if not.
         */
        String command = getCommand(actuator, CommandType.ACTUATOR_READ_COMMAND);
        String regex = getCommand(actuator, CommandType.ACTUATOR_READ_REGEX);
        if( regex == null || command == null ) {
            throw new Exception(String.format("Actuator \"%s\" read error: Driver configuration is missing ACTUATOR_READ_COMMAND or ACTUATOR_READ_REGEX.", actuator.getName()));
        }
        command = substituteVariable(command, "Id", actuator.getId());
        command = substituteVariable(command, "Name", actuator.getName());
        if (actuator instanceof ReferenceActuator) {
            command = substituteVariable(command, "Index", ((ReferenceActuator)actuator).getIndex());
        }
        if (parameter != null) {
            if (parameter instanceof Double) { // Backwards compatibility
                Double doubleParameter = (Double) parameter;
                command = substituteVariable(command, "DoubleValue", doubleParameter);
                command = substituteVariable(command, "IntegerValue", (int) doubleParameter.doubleValue());
            }

            command = substituteVariable(command, "Value", parameter);
        }

        var cmd = GcodeCommand.create(command)
                    .confirmRegex(regex)
                    .timeout(timeoutMilliseconds)
                    .onTimeOut(()->Logger.error(String.format("Actuator \"%s\" read error: No matching responses found.", actuator.getName())));
        sendSyncGcode(cmd);
        var reply = cmd.reply();
        org.pmw.tinylog.Logger.trace("actuatorRead response: {}", reply );

        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(reply);
        try {
            matcher.matches(); // Was checked somewhere else, but needed for .group to work
            return matcher.group("Value");
        }
        catch (IllegalArgumentException e) {
            throw new Exception(String.format("Actuator \"%s\" read error: Regex is missing \"Value\" capturing group. See https://github.com/openpnp/openpnp/wiki/GcodeDriver#actuator_read_regex",
                    actuator.getName()), e);
        }
        catch (Exception e) {
            throw new Exception(String.format("Actuator \"%s\" read error: Failed to parse response. See https://github.com/openpnp/openpnp/wiki/GcodeDriver#actuator_read_regex",
                    actuator.getName()), e);
        }
    }
    protected class ResponseThread extends Thread {
        @Override
        public void run() {
            while(checkResponses){
                GcodeCommand cmd;
                try {
                    // Timeout so we can shut this down cleanly with checkResponses
                    cmd = gcodeWriter.getResultsQueue().poll(1, TimeUnit.SECONDS);
                    if( cmd == null ) {
                        continue;
                    }
                    if( responseQueue.size()>5){
                        responseQueue.take();
                    }
                    responseQueue.put(new Line(cmd.reply()));

                    switch(cmd.state()){
                        case FAILED_SEND:
                            org.pmw.tinylog.Logger.error( "{} failed to write command {}", gcodeWriter.id(), cmd.command());
                            disconnect();
                            try {
                                Configuration.get().getMachine().setEnabled(false);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        break;
                        case CONFIRMED:
                        case CONFIRM_REGEX_FAILED:
                            if( cmd.isLocationReply() ){
                                processPositionReport( new Line(cmd.reply()) );
                            }
                            if( cmd.isConfirmed()) {
                                cmd.doConfirmation();
                            }
                            break;
                        case ERROR_REPLY:
                            org.pmw.tinylog.Logger.error("{} error response from controller: {}", cmd.reply(), gcodeWriter.id());
                            break;
                        case TIMEOUT :
                            org.pmw.tinylog.Logger.error("Timeout from controller when sending: {}", cmd.command());
                            cmd.doTimeOut();
                        break;
                        case MAYBE_LOCATION:
                            processPositionReport(new Line(cmd.reply()));
                            break;
                        case UNSOLICITED:
                            if(cmd.command().equals("unhome") ){
                                Configuration.get().getMachine().getMotionPlanner().unhome();
                                Logger.warn("Idle time passed, marking home as invalid.");
                            }
                            break;
                    };
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
