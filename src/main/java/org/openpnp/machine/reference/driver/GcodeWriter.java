package org.openpnp.machine.reference.driver;

import eu.settlabs.core.interfaces.Writable;
import eu.settlabs.streams.BaseStream;
import org.tinylog.Logger;

import java.util.Arrays;
import java.util.concurrent.*;

public class GcodeWriter implements Writable {

    private final LinkedBlockingDeque<GcodeCommand> pendingCommands = new LinkedBlockingDeque<>();
    private final LinkedBlockingDeque<GcodeCommand> responseQueue = new LinkedBlockingDeque<>();

    private final BaseStream controller;
    private Writable writer;

    public enum STREAM_STATE{IDLE,SEND_ERROR,QUEUE_ERROR,TIMEOUT, SEND_OK,WAITING};

    private final GCodeCommandRules rules;

    private STREAM_STATE state = STREAM_STATE.IDLE;

    private final ScheduledExecutorService timedExecutor = Executors.newScheduledThreadPool(1);
    private ScheduledFuture<?> timeoutFuture;

    public GcodeWriter(GCodeCommandRules rules, BaseStream controller) {
        this.controller = controller;
        if( controller.isWritable()) {
            writer = (Writable) controller;
            controller.addTarget(this);
        }
        this.rules = rules;
    }

    public void drainCommandQueue(long timeout){
        // TODO Figure out what the purpose of this actually is?
    }

    public boolean sendGcode( GcodeCommand gCode ) {
        if (gCode == null || gCode.isInvalid()) {
            Logger.warn("No valid gCode received");
            return false;
        }
        if( inErrorState() ) {
            return false;
        }

        var list = Arrays.stream(gCode.command().split("\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        if( list.isEmpty() ) {
            org.pmw.tinylog.Logger.debug("{} empty command after pre process", controller.id());
            return false;
        }
        // Copied from
        list.forEach( single -> org.pmw.tinylog.Logger.debug("[{}] >> {}, {}", controller.id(), single, gCode.timeout()));

        if( list.size() == 1 ) {
            addGcodeCommand( gCode );
            return !inErrorState();
        }else{
            for( var single : list ) {
                addGcodeCommand( gCode.copyWithNewCommand(single) );
                if( inErrorState() ) {
                    return false;
                }
            }
        }
        return true;
    }
    public boolean nothingPending() {
        return pendingCommands.isEmpty()&&responseQueue.isEmpty();
    }
    public boolean inErrorState(){
        return state == STREAM_STATE.SEND_ERROR || state == STREAM_STATE.QUEUE_ERROR;
    }
    public STREAM_STATE addGcodeCommand( GcodeCommand cmd ){
        pendingCommands.offer( cmd );
        return sendCommand();
    }
    private STREAM_STATE sendCommand(){
        if (pendingCommands.isEmpty()) {
            return STREAM_STATE.QUEUE_ERROR;
        }
        if( state != STREAM_STATE.IDLE) { // Which means waiting also causes this
            return state;
        }
        var cmd = pendingCommands.getFirst();
        System.out.println("Sending:"+cmd.command());
        if( writer.writeLine(id(),cmd.command()) ) {
            cmd.markSendOk();
            timeoutFuture = timedExecutor.schedule(this::timeoutOccurred, cmd.timeout(), TimeUnit.MILLISECONDS);
            state = STREAM_STATE.SEND_OK;
        }else {
            cmd.markFailedToSend();
            pendingCommands.clear();    // Failed to send and no retry mechanism yet, so clear queue
            state = STREAM_STATE.SEND_ERROR;
            responseQueue.add( cmd );
        }
        return state;
    }

    public BlockingQueue<GcodeCommand> getResultsQueue() {
        return responseQueue;
    }

    private void stopWaiting(){
        if( state!= STREAM_STATE.WAITING) {
            return;
        }
        state = STREAM_STATE.IDLE;
        sendCommand();
    }

    public void timeoutOccurred(){
        state = STREAM_STATE.TIMEOUT; // Change state to indicate an error occurred
        var item = pendingCommands.removeFirst(); // Grab the first element to fill in  the response queue
        item.markTimedOut();
        responseQueue.add( item );
        pendingCommands.clear(); // For now no retry logic is implemented
    }

    /* ***** Writable  **** */
    @Override
    public boolean writeLine(String origin, String msg) {
        System.out.println("Received:"+msg);
        // Anything received is assumed to be a reply to what was last send
        // Do this first just to be sure it won't trigger during processing
        if( timeoutFuture != null ) {
            timeoutFuture.cancel(true);
        }
        if( pendingCommands.isEmpty() ) {
            responseQueue.offer( GcodeCommand.createDummy(msg) );
            return true;
        }
        // Got a line and it matches the regex
        var item = pendingCommands.getFirst(); // Peeks at the top, doesn't remove it yet
        item.markReplied(msg);
        if( item.command().startsWith("$") ){
            state = STREAM_STATE.WAITING;
            timedExecutor.schedule(this::stopWaiting,rules.getDollarWaitTimeMilliseconds(),TimeUnit.MILLISECONDS);
        }
        if( !rules.isErrorMessage(msg) ){ // Or try again logic?
            pendingCommands.removeFirst();
            if( item.isConfirmed() ) {
                System.out.println("confirmed:"+item.command());
                org.pmw.tinylog.Logger.trace("[{}] confirmed {}", id(), item.command());
            }else{
                System.out.println("NOT confirmed:"+item.command());
            }
            if( !pendingCommands.isEmpty() ){
                sendCommand();
            }else if( state == STREAM_STATE.SEND_OK){
                state= STREAM_STATE.IDLE;
            }
        }else{
            // TODO Try again or give up or flush queue? For now copy original and just give up
            item.markError();
            pendingCommands.clear();
        }
        responseQueue.add( item );
        return true;
    }

    @Override
    public String id() {
        return "gcodewriter";
    }

    @Override
    public boolean isConnectionValid() {
        return true;
    }

    @Override
    public Writable getWritable() {
        return this;
    }

    @Override
    public void giveObject(String info, Object object) {

    }
}
