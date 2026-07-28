package org.openpnp.machine.reference.driver;

import eu.settlabs.core.interfaces.Writable;
import eu.settlabs.core.base.BaseStream;
import org.tinylog.Logger;

import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public class GcodeWriter implements Writable {

    private final LinkedBlockingDeque<GcodeCommand> pendingCommands = new LinkedBlockingDeque<>();
    private final LinkedBlockingDeque<GcodeCommand> responseQueue = new LinkedBlockingDeque<>();

    private BaseStream controller;
    private Writable writer;

    public enum STREAM_STATE{IDLE,SEND_REQUEST,SEND_ERROR,QUEUE_ERROR,TIMEOUT, SEND_OK,WAITING};

    private final GCodeCommandRules rules;

    private volatile AtomicReference<STREAM_STATE> state = new AtomicReference<>(STREAM_STATE.IDLE);

    private final ScheduledExecutorService timedExecutor = Executors.newScheduledThreadPool(1);
    private ScheduledFuture<?> timeoutFuture;

    private long homeTimeout=-1;
    private long steppersTimeout=60*1000;
    private long lastTimestamp=-1;
    private String id="gcodewriter";

    public GcodeWriter(GCodeCommandRules rules ) {

        this.rules = rules;
    }
    public void setId( String id ) {
        this.id=id;
    }
    public void setBaseStream( BaseStream controller) {
        if ( controller==null) {
            return;
        }
        if( this.controller != null){
            this.controller.disconnect();
        }
        this.controller=controller;
        if( controller.isWritable()) {
            writer = (Writable) controller;
            controller.addTarget(this);
            org.pmw.tinylog.Logger.debug("{} -> Adding {} as target ",controller.id(),id());
        }
    }
    public BaseStream getBaseStream() {
        return controller;
    }
    public void disconnect() {
        if( controller != null){
            controller.disconnect();
        }
    }
    public void enableGlobalHomeTimeout( String timeout ) {
        enableGlobalHomeTimeout( GCodeTools.periodStringToMillis( timeout ) );
    }
    public void enableGlobalHomeTimeout( long timeout ) {
        if( timeout <= 5000 )
            return;
        this.homeTimeout=timeout;
        timedExecutor.schedule(this::homeValidTimeoutOccurred, timeout, TimeUnit.MILLISECONDS);
    }
    public void enableStepperTimeout( String timeout ) {
        long ms = GCodeTools.periodStringToMillis( timeout );
        if( ms <= 5000 )
            return;
        this.steppersTimeout=ms;
        timedExecutor.schedule( this::stepperDisableTimeoutOccurred, ms, TimeUnit.MILLISECONDS);
    }
    public void stepperDisableTimeoutOccurred(){
        var left = timeLeft( steppersTimeout );
        if( left == steppersTimeout )
            sendGcode( GcodeCommand.create("M84") );
        timedExecutor.schedule( this::stepperDisableTimeoutOccurred, left, TimeUnit.MILLISECONDS);
    }
    public void drainCommandQueue(long timeout){
        // TODO Figure out what the purpose of this actually is?
    }

    public boolean sendGcode( GcodeCommand gCode ) {
        if (gCode == null || gCode.isInvalid()) {
            org.pmw.tinylog.Logger.debug("{} -> No valid gCode received",id());
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
            org.pmw.tinylog.Logger.debug("{} -> Empty command after pre process", id());
            return false;
        }
        // Copied from
        list.forEach( single -> org.pmw.tinylog.Logger.debug("[{}] -> {}, {}", id(), single, gCode.timeout()));

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
    public boolean inErrorState(){
        return state.get() == STREAM_STATE.SEND_ERROR || state.get() == STREAM_STATE.QUEUE_ERROR;
    }
    public boolean isMotionPending(){
        return !pendingCommands.isEmpty();
    }
    public STREAM_STATE addGcodeCommand( GcodeCommand cmd ){
        pendingCommands.offer( cmd );
        if( pendingCommands.size()==1) {
            org.pmw.tinylog.Logger.debug("{} -> Queue empty, sending directly: {}",id(),cmd.command() );
            return sendCommand();
        }
        return state.get();
    }
    private synchronized STREAM_STATE sendCommand(){

        if (pendingCommands.isEmpty()) {
            org.pmw.tinylog.Logger.debug("{} -> -2 Queue error",id());
            state.compareAndSet(STREAM_STATE.IDLE, STREAM_STATE.QUEUE_ERROR);
            return state.get();
        }
        var cmd = pendingCommands.getFirst();

        if( !cmd.command().equals("M999") && !state.compareAndSet(STREAM_STATE.IDLE, STREAM_STATE.SEND_REQUEST)) {
            org.pmw.tinylog.Logger.debug("{} -> -1 Wrong state:{}",id(),state );
            return state.get();
        }

        cmd.markUnderway();
        if( writer.writeLine(id(),cmd.command()) ) { // No idea how long this takes
            lastTimestamp = Instant.now().toEpochMilli();
            if( cmd.markSendOk() ) {
                if (state.compareAndSet(STREAM_STATE.SEND_REQUEST, STREAM_STATE.SEND_OK)) {
                    if( timeoutFuture != null ) {
                        timeoutFuture.cancel(true);
                    }
                    if( cmd.timeout() !=-1 ) {
                        timeoutFuture = timedExecutor.schedule(this::replyTimeoutOccurred, cmd.timeout(), TimeUnit.MILLISECONDS);
                    }else {
                        org.pmw.tinylog.Logger.debug("{} -> {} -> No timeout specified!",id(),cmd.command());
                    }
                } else {
                    //System.out.println("2b Transmission already handled: " + state.get());
                }
            }else{
                // Reply already raced ahead and resolved the command before we could mark SEND_OK.
                // The transaction is over; settle the stream state to match reality.
                if (state.compareAndSet(STREAM_STATE.SEND_REQUEST, STREAM_STATE.IDLE)) {
                    if (!pendingCommands.isEmpty()) {
                        sendCommand();
                    }
                }
            }
        }else {
            cmd.markFailedToSend();
            pendingCommands.clear();    // Failed to send and no retry mechanism yet, so clear queue
            if( state.compareAndSet(STREAM_STATE.SEND_REQUEST, STREAM_STATE.SEND_ERROR) ) {
                org.pmw.tinylog.Logger.debug("{} -> 2c State changed to send_error",id());
                responseQueue.add(cmd);
            }else{
                org.pmw.tinylog.Logger.debug("{} -> 2d State isn't SEND_REQUEST but {}",id(),state.get());
            }
        }
        //System.out.println("3 Finished send command for "+cmd.command()+" , pending: "+pendingCommands.size());
        return state.get();
    }

    public BlockingQueue<GcodeCommand> getResultsQueue() {
        return responseQueue;
    }

    private void stopWaiting(){
        if( state.get() != STREAM_STATE.WAITING) {
            org.pmw.tinylog.Logger.debug("{} -> Somehow reached this while not waiting...? {}",id(),state.get());
            return;
        }
        if( state.compareAndSet(STREAM_STATE.WAITING, STREAM_STATE.IDLE) ) {
            //System.out.println("Finished waiting, back to idle");
            sendCommand();
        }else{
            org.pmw.tinylog.Logger.debug("{} -> Somehow state change since if? {}",id(),state.get());
        }
    }

    private void replyTimeoutOccurred(){

        STREAM_STATE previous = state.getAndSet(STREAM_STATE.TIMEOUT); // or TIMEOUT, if you track it separately
        if (previous == STREAM_STATE.SEND_OK || previous == STREAM_STATE.SEND_REQUEST) {
            // legit timeout — command never got its reply in time
            var item = pendingCommands.removeFirst(); // Grab the first element to fill in  the response queue
            item.markTimedOut();
            responseQueue.add( item );
            pendingCommands.clear(); // For now no retry logic is implemented
        } else if (previous == STREAM_STATE.IDLE) {
            // reply already arrived and transitioned us to IDLE before the timeout fired —
            // this timeout is stale/spurious, the future should've been cancelled but wasn't
            org.pmw.tinylog.Logger.debug("{} -> Stale timeout fired, already IDLE — ignoring",id());
            // don't double-complete cmd, don't fire callbacks for a finished command
        }
    }
    private void homeValidTimeoutOccurred(){
        var left = timeLeft( homeTimeout);
        if( left == homeTimeout)
            responseQueue.add( GcodeCommand.create("unhome").markAsUnsolicited() );
        timedExecutor.schedule(this::homeValidTimeoutOccurred, left, TimeUnit.MILLISECONDS);
    }
    private long timeLeft( long timeout ){
        var left = Instant.now().toEpochMilli() - lastTimestamp;
        if( left+5 > timeout || lastTimestamp==-1){ // Some margin
            left = timeout;
        }
        return left;
    }
    /* ***** Writable  **** */
    @Override
    public boolean writeLine(String origin, String msg) {
        lastTimestamp = Instant.now().toEpochMilli();
        msg=msg.trim();

        // Anything received is assumed to be a reply to what was last send
        // Do this first just to be sure it won't trigger during processing
        if( timeoutFuture != null ) {
            timeoutFuture.cancel(true);
        }
        if( pendingCommands.isEmpty() ) {
            org.pmw.tinylog.Logger.debug("{} -> eceived line: {} but nothing pending",id(),msg);
            responseQueue.offer( GcodeCommand.createDummy("dummy:"+msg) );
            return true;
        }
       // System.out.println(id+" -> Received line: "+msg+ " while waiting on reply for "+pendingCommands.getFirst().command());
        // Got a line and it matches the regex
        var item = pendingCommands.getFirst(); // Peeks at the top, doesn't remove it yet
        item.markReplied(msg); // This line actually marks it as replied and checks confirmed
        if( item.command().startsWith("$") ){
            if( state.compareAndSet(STREAM_STATE.SEND_OK, STREAM_STATE.WAITING) ) {
                timedExecutor.schedule(this::stopWaiting, rules.getDollarWaitTimeMilliseconds(), TimeUnit.MILLISECONDS);
            }else{
                org.pmw.tinylog.Logger.debug("{} -> Tried to wait, but state was wrong: {}",id(),state.get());
            }
        }
        if( !rules.isErrorMessage(msg) ){ // Or try again logic?
            if( item.isConfirmed() ) {
                pendingCommands.removeFirst();
                org.pmw.tinylog.Logger.trace("[{}] confirmed {}", id(), item.command());
            }else if( item.isReceived()){
                return true;
            }else if( item.isRegexFailed() ){
                org.pmw.tinylog.Logger.debug("{} -> NOT confirmed: {}",id(),item.command());
                if( item.alsoCheckNextLine()) // Allows checking multiple lines
                    return true;
                pendingCommands.removeFirst();
            }
            if( state.compareAndSet(STREAM_STATE.SEND_OK, STREAM_STATE.IDLE) ) {
                //System.out.println("Back to idle");
                if (!pendingCommands.isEmpty()) {
                   // System.out.println("More work to do!");
                    sendCommand();
                }else{
                    org.pmw.tinylog.Logger.debug("{} -> {} Was last in queue, going idle",id(),item.command());
                    org.pmw.tinylog.Logger.debug("----------------------------------------------");
                }
            }else{
                org.pmw.tinylog.Logger.debug(" -> {} not back to idle because {} ",id(),item.command(),state.get());
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
        return id+"/"+controller.id();
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
