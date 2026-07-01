package org.openpnp.machine.reference.driver;

import org.openpnp.machine.reference.driver.exceptions.GcodeErrorReplyException;
import org.openpnp.machine.reference.driver.exceptions.GcodeRegexMismatchException;
import org.openpnp.machine.reference.driver.exceptions.GcodeSendFailedException;
import org.openpnp.machine.reference.driver.exceptions.GcodeTimeoutException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    private final AtomicReference<COMMAND_STATE> state= new AtomicReference<>(COMMAND_STATE.PENDING);

    public enum COMMAND_STATE{PENDING, UNDERWAY, SEND_OK, RECEIVE_OK, FAILED_SEND,CONFIRMED,CONFIRM_REGEX_FAILED, ERROR_REPLY,TIMEOUT, MAYBE_LOCATION, UNSOLICITED};

    private GCODE_COMMAND gcode=GCODE_COMMAND.STD;

    public enum GCODE_COMMAND{ STD,LOCATION,HOME, REPLY_FUTURE}

    long sendTimestamp=-1;
    long replyTimestamp=-1;
    List<String> replies=new ArrayList<>();
    private int linesToCheck=1;
    private int confirmsNeeded=1;

    private CompletableFuture<GcodeCommand> future;
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
        return String.join("\n",replies);
    }
    public List<String> replyAsList(){
        return replies;
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
        cmd.replies.add(reply);
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
    public GcodeCommand enableFuture(){
        if( command != null )
            future=new CompletableFuture<>();
        return this;
    }
    public String confirmRegex( ){
        return this.originalRegex;
    }
    public boolean isDefaultRegex(){
        return  this.originalRegex.equals("^ok.*");
    }
    public GcodeCommand confirmsNeeded( int confirmsNeeded ){
        this.confirmsNeeded = confirmsNeeded;
        return this;
    }
    public boolean isReceived(){
        return state.get()==COMMAND_STATE.RECEIVE_OK;
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
    public GcodeCommand linesToCheck( int lines ){
        this.linesToCheck = lines;
        return this;
    }
    public boolean alsoCheckNextLine(){
        return this.linesToCheck!=0;
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
    public boolean isRegexFailed(){
        return state.get()==COMMAND_STATE.CONFIRM_REGEX_FAILED;
    }
    public CompletableFuture<GcodeCommand> replyFuture(){
        return this.future;
    }
    public void completeFuture(){
        if(future == null) return;
        switch (state.get()) {
            case CONFIRMED -> future.complete(this);
            case TIMEOUT -> future.completeExceptionally(new GcodeTimeoutException(this));
            case ERROR_REPLY -> future.completeExceptionally(new GcodeErrorReplyException(this, reply()));
            case FAILED_SEND -> future.completeExceptionally(new GcodeSendFailedException(this));
            case CONFIRM_REGEX_FAILED -> future.completeExceptionally(new GcodeRegexMismatchException(this, reply()));
            default -> future.complete(null); // shouldn't normally happen, but don't hang forever
        }
    }
    /* **** Changing the state of the command **** */
    public void markUnderway(){
        if( state.compareAndSet(COMMAND_STATE.PENDING,COMMAND_STATE.UNDERWAY) ) {
            if (future != null) {
                future.orTimeout(timeout + 100, TimeUnit.MILLISECONDS);
            }
        }
        System.out.println(command+ " -> Underway to controller.");
    }
    public boolean markSendOk(){
        sendTimestamp= Instant.now().toEpochMilli();
        if( state.compareAndSet(COMMAND_STATE.UNDERWAY,COMMAND_STATE.SEND_OK) ) {
            System.out.println(command + " -> Send succeeded.");
            return true;
        }else if( state.get() == COMMAND_STATE.RECEIVE_OK ){
            System.out.println(command + " -> Not marking send ok because already received.");
            return false;
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
        completeFuture();
    }
    public void markReplied(String reply){
        this.replies.add(reply);
        linesToCheck--;
        replyTimestamp= Instant.now().toEpochMilli();
        if( confirmRegex.test(reply) ){
            if( confirmsNeeded == 2 && state.compareAndSet(COMMAND_STATE.SEND_OK,COMMAND_STATE.RECEIVE_OK) ){
                confirmsNeeded--;
                System.out.println(command+" -> Receival confirmed.");
            }else if( state.compareAndSet(COMMAND_STATE.SEND_OK,COMMAND_STATE.CONFIRMED) ){
                System.out.println( command+" -> Confirmed with " +reply);
            }else if( state.compareAndSet(COMMAND_STATE.RECEIVE_OK,COMMAND_STATE.CONFIRMED) ){
                System.out.println( command+" -> Confirmed after receival with " +reply);
            }else if( state.compareAndSet(COMMAND_STATE.UNDERWAY,COMMAND_STATE.CONFIRMED ) ){
                System.out.println( command+" -> Confirmed with " +reply+" while considered underway!");
            }else if( state.compareAndSet(COMMAND_STATE.CONFIRM_REGEX_FAILED,COMMAND_STATE.CONFIRMED ) ){
                System.out.println( command+" -> Confirmed with " +reply+" after receiving a bad one earlier");
            }else{
                System.err.println( command+" -> Want to mark confirmed but it's now "+state.get());
            }
            if( state.get() == COMMAND_STATE.CONFIRMED )
                completeFuture();
        }else{
            if( state.compareAndSet(COMMAND_STATE.SEND_OK,COMMAND_STATE.CONFIRM_REGEX_FAILED) ){
                System.out.println(command+" -> Transitioned from SEND_OK to "+state.get());
                System.out.println( command+" -> Regex failed on "+reply + " vs "+originalRegex );
            }else if( state.compareAndSet(COMMAND_STATE.UNDERWAY,COMMAND_STATE.CONFIRM_REGEX_FAILED ) ){
                System.out.println( command+" -> Regex failed on "+reply+", even before send_ok, marking as regex_failed...");
            }else if( state.get() != COMMAND_STATE.CONFIRM_REGEX_FAILED ){ // If not already regex failed
                System.err.println( command+" -> Want to mark confirmed_regex_failed but it's now "+state.get());
            }
            if(linesToCheck==0) {
                completeFuture();
            }
        }
    }
    public void markTimedOut(){
        if( state.compareAndSet(COMMAND_STATE.SEND_OK,COMMAND_STATE.TIMEOUT) ) {
            System.out.println(command+" -> Marked as timeout after "+state.get());
        }else if( state.get() == COMMAND_STATE.CONFIRM_REGEX_FAILED ){
            System.out.println(command+" -> Didn't mark as timeout because a failed regex earlier.");
        }else{
            System.err.println(command+" -> Couldn't mark as timeout because "+state.get());
        }
        completeFuture();
    }
    public void markError(){
        if( state.compareAndSet(COMMAND_STATE.SEND_OK,COMMAND_STATE.ERROR_REPLY) ){
            System.out.println(command+" -> Transitioned from SEND_OK to "+state.get());
        }else if( state.compareAndSet(COMMAND_STATE.UNDERWAY,COMMAND_STATE.ERROR_REPLY ) ){
            System.out.println( command+" -> Was matched to error before marked as send...");
        }else{
            System.out.println( command+" -> Want to mark as an error reply but it's now "+state.get());
        }
        completeFuture();
    }
    public GcodeCommand markAsUnsolicited(){
        state.set(COMMAND_STATE.UNSOLICITED);
        return this;
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
        newCommand.confirmsNeeded=this.confirmsNeeded;
        if( this.command.endsWith('\n'+command) || this.command.equals(command)|| !once ) {
            newCommand.onConfirmation = this.onConfirmation;
            newCommand.future=this.future;  // Make sure the future is also taken
        }
        newCommand.timeout=this.timeout;
        return newCommand;
    }
}
