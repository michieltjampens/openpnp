package org.openpnp.machine.reference.driver;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public class GcodeCommand {

    private String command;
    private long timeout=-1;

    private Predicate<String> confirmRegex=Pattern.compile("^ok.*").asMatchPredicate();
    private String originalRegex="^ok.*";
    private Runnable onConfirmation=()->{};
    private boolean once=false;
    private Runnable onTimeOut=()->{};
    private COMMAND_STATE state=COMMAND_STATE.PENDING;

    public enum COMMAND_STATE{PENDING,SEND,FAILED_SEND,CONFIRMED,ERROR,TIMEOUT, MAYBE_LOCATION, UNSOLICITED};

    private GCODE_COMMAND gcode=GCODE_COMMAND.STD;

    public enum GCODE_COMMAND{ STD,LOCATION,HOME, REPLY_FUTURE}

    long sendTimestamp=-1;
    long replyTimestamp=-1;
    String reply="";

    private CompletableFuture<String> future;
    /**
     *
     * @param command
     */
    private GcodeCommand(String command){
        this.command = command;
    }

    public String command(){
        return command;
    }
    public String reply(){
        return reply;
    }
    public long timeout(){
        return timeout;
    }

    public static GcodeCommand create(String command){
        return new GcodeCommand(command);
    }
    public boolean isInvalid(){
        return command==null;
    }
    public static GcodeCommand createDummy( String reply ){
        var cmd = new GcodeCommand("");
        cmd.replyTimestamp= Instant.now().toEpochMilli();
        cmd.reply=reply;
        cmd.state=COMMAND_STATE.UNSOLICITED;
        return cmd;
    }
    public GcodeCommand timeout(long timeout){
        this.timeout = timeout;
        return this;
    }
    public GcodeCommand confirmRegex(String confirmRegex){
        this.originalRegex=confirmRegex;
        this.confirmRegex = Pattern.compile(confirmRegex,Pattern.CASE_INSENSITIVE).asMatchPredicate();
        return this;
    }
    public String confirmRegex( ){
        return this.originalRegex;
    }
    public boolean isDefaultRegex(){
        return  this.originalRegex.equals("^ok.*");
    }
    public GcodeCommand onLastConfirmation(Runnable onConfirmation){
        this.onConfirmation = onConfirmation;
        once=true;
        return this;
    }
    public GcodeCommand onEveryConfirmation(Runnable onConfirmation){
        this.onConfirmation = onConfirmation;
        once=false;
        return this;
    }
    /* *** Special gcodes? *** */
    public GcodeCommand markLocationRequest(){
        gcode=GCODE_COMMAND.LOCATION;
        return this;
    }
    public boolean isLocationReply(){
        return gcode==GCODE_COMMAND.LOCATION;
    }

    public CompletableFuture<String> createReplyFuture(){
        gcode = GCODE_COMMAND.REPLY_FUTURE;
        future = new CompletableFuture<>();
        return future;
    }
    public CompletableFuture<String> replyFuture(){
        return this.future;
    }
    public boolean hasFuture(){
        return gcode==GCODE_COMMAND.REPLY_FUTURE;
    }
    public void completeFuture(){
        if(future != null) {
            future.complete(reply);
        }
    }
    /* **** Changing the state of the command **** */
    public void markSendOk(){
        sendTimestamp= Instant.now().toEpochMilli();
        state=COMMAND_STATE.SEND;
    }
    public void markFailedToSend(){
        sendTimestamp= Instant.now().toEpochMilli();
        state=COMMAND_STATE.FAILED_SEND;
    }
    public void markReplied(){
        replyTimestamp= Instant.now().toEpochMilli();
        state=COMMAND_STATE.MAYBE_LOCATION;
    }
    public void markTimedOut(){
        state=COMMAND_STATE.TIMEOUT;
    }
    public void markConfirmed(){
        state=COMMAND_STATE.CONFIRMED;
    }
    public void markError(){
        state=COMMAND_STATE.ERROR;
    }
    public COMMAND_STATE state(){
        return state;
    }

    public void doConfirmation(){
        this.onConfirmation.run();
    }
    public void doTimeOut(){
        this.onTimeOut.run();
    }
    public GcodeCommand onTimeOut(Runnable onTimeOut){
        this.onTimeOut = onTimeOut;
        return this;
    }
    public GcodeCommand copyWithNewCommand(String command){
        var newCommand = new GcodeCommand(command);
        newCommand.confirmRegex = this.confirmRegex;
        newCommand.onTimeOut = this.onTimeOut;
        if( this.command.endsWith('\n'+command) || this.command.equals(command)|| !once ) {
            newCommand.onConfirmation = this.onConfirmation;
            newCommand.future=this.future;  // Make sure the future is also taken
        }
        newCommand.timeout=this.timeout;
        return newCommand;
    }
}
