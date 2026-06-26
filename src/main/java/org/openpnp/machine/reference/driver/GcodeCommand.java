package org.openpnp.machine.reference.driver;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
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
    private volatile AtomicReference<COMMAND_STATE> state= new AtomicReference<>(COMMAND_STATE.PENDING);

    public enum COMMAND_STATE{PENDING, UNDERWAY, SEND_OK, FAILED_SEND,CONFIRMED,CONFIRM_REGEX_FAILED, ERROR_REPLY,TIMEOUT, MAYBE_LOCATION, UNSOLICITED};

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
        if( command==null) {
            return;
        }
        this.command = command.strip();
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
        cmd.state.set(COMMAND_STATE.UNSOLICITED);
        return cmd;
    }
    public GcodeCommand timeout(long timeout){
        this.timeout = timeout;
        return this;
    }
    public GcodeCommand confirmRegex(String confirmRegex){
        if(confirmRegex==null) {
            return this;
        }
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
    public boolean isConfirmed(){
        return state.get()==COMMAND_STATE.CONFIRMED;
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
            future.complete(isConfirmed()?reply:null);
        }
    }
    /* **** Changing the state of the command **** */
    public void markUnderway(){
        state.compareAndSet(COMMAND_STATE.PENDING,COMMAND_STATE.UNDERWAY);
        System.out.println(command+ " -> Underway to controller.");
    }
    public boolean markSendOk(){
        sendTimestamp= Instant.now().toEpochMilli();
        if( state.compareAndSet(COMMAND_STATE.UNDERWAY,COMMAND_STATE.SEND_OK) ){
            System.out.println(command+" -> Send succeeded.");
            return true;
        }else{
            System.out.println( command+" -> Not marking as send because "+state.get());
            return false;
        }
    }
    public void markFailedToSend(){
        sendTimestamp= Instant.now().toEpochMilli();
        if( state.compareAndSet(COMMAND_STATE.UNDERWAY,COMMAND_STATE.FAILED_SEND) ){
            System.out.println(command+" -> Failed to send.");
        }else{
            System.err.println( command+" -> Not marking as failed to send because "+state.get());
        }
    }
    public void markReplied(String reply){
        this.reply=reply;
        replyTimestamp= Instant.now().toEpochMilli();
        if( confirmRegex.test(reply) ){
            if( state.compareAndSet(COMMAND_STATE.SEND_OK,COMMAND_STATE.CONFIRMED) ){
                System.out.println( command+" -> Confirmed with " +reply);
            }else if( state.compareAndSet(COMMAND_STATE.UNDERWAY,COMMAND_STATE.CONFIRMED ) ){
                System.out.println( command+" -> Confirmed with " +reply+" while considered underway!");
            }else{
                System.err.println( command+" -> Want to mark confirmed but it's now "+state.get());
            }
        }else{
            if( state.compareAndSet(COMMAND_STATE.SEND_OK,COMMAND_STATE.CONFIRM_REGEX_FAILED) ){
                System.out.println(command+" -> Transitioned from SEND_OK to "+state.get());
                System.out.println( command+" -> Regex failed on "+reply );
            }else if( state.compareAndSet(COMMAND_STATE.UNDERWAY,COMMAND_STATE.CONFIRM_REGEX_FAILED ) ){
                System.out.println( command+" -> Regex failed on "+reply+", even before send_ok, marking as regex_failed...");
            }else{
                System.err.println( command+" -> Want to mark confirmed_regex_failed but it's now "+state.get());
            }
        }
    }
    public void markTimedOut(){
        if( !state.compareAndSet(COMMAND_STATE.SEND_OK,COMMAND_STATE.TIMEOUT) ){
            System.err.println(command+" -> Couldn't mark as timeout because "+state.get());
        }else{
            System.out.println(command+" -> Marked as timeout after "+state.get());
        }
    }
    public void markError(){
        if( state.compareAndSet(COMMAND_STATE.SEND_OK,COMMAND_STATE.ERROR_REPLY) ){
            System.out.println(command+" -> Transitioned from SEND_OK to "+state.get());
        }else if( state.compareAndSet(COMMAND_STATE.UNDERWAY,COMMAND_STATE.ERROR_REPLY ) ){
            System.out.println( command+" -> Was matched to error before marked as send...");
        }else{
            System.out.println( command+" -> Want to mark as an error reply but it's now "+state.get());
        }
    }
    public COMMAND_STATE state(){
        return state.get();
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
