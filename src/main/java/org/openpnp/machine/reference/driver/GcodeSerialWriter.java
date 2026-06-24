package org.openpnp.machine.reference.driver;

import eu.settlabs.streams.serialport.SerialStream;
import eu.settlabs.xml.XMLdigger;
import org.tinylog.Logger;

import java.time.Instant;
import java.util.concurrent.*;

public class GcodeSerialWriter extends SerialStream {

    LinkedBlockingDeque<PendingCommand> queue = new LinkedBlockingDeque<>();
    LinkedBlockingDeque<CommandResponse> responseQueue = new LinkedBlockingDeque<>();

    public record CommandResponse( PendingCommand original, long elapsed, String response,  SEND_RESULT result){};
    public enum STREAM_STATE{IDLE,SEND_ERROR,QUEUE_ERROR,TIMEOUT,SENDING,WAITING};
    enum SEND_RESULT{FAILED,CONFIRMED,ERROR,TIMEOUT, MAYBE_LOCATION};
    GCodeCommandRules rules;
    private long lastSend=-1;

    private STREAM_STATE state = STREAM_STATE.IDLE;

    ScheduledExecutorService timeoutExecutor = Executors.newScheduledThreadPool(1);
    ScheduledFuture<?> timeoutFuture;

    public GcodeSerialWriter(XMLdigger stream, GCodeCommandRules rules) {
        super(stream);
        this.rules = rules;
    }
    public BlockingQueue<CommandResponse> getResponseQueue() {
        return responseQueue;
    }
    public boolean inErrorState(){
        return state == STREAM_STATE.SEND_ERROR || state == STREAM_STATE.QUEUE_ERROR;
    }
    public STREAM_STATE addGcodeCommand( PendingCommand cmd ){
        queue.offer( cmd );
        return sendCommand();
    }
    private STREAM_STATE sendCommand(){
        if (queue.isEmpty())
            return STREAM_STATE.QUEUE_ERROR;

        if( state != STREAM_STATE.IDLE) // Which means waiting also causes this
            return state;

        var cmd = queue.getFirst();
        if( writeString(cmd.command()) ) {
            timeoutFuture = timeoutExecutor.schedule(this::timeoutOccurred, cmd.timeout(), TimeUnit.MILLISECONDS);
            state = STREAM_STATE.SENDING;
            lastSend = Instant.now().toEpochMilli();
        }else {
            queue.clear();    // Failed to send and no retry mechanism yet, so clear queue
            state = STREAM_STATE.SEND_ERROR;
            responseQueue.add( new CommandResponse( cmd,0,"",SEND_RESULT.FAILED) );
        }
        return state;
    }
    private void stopWaiting(){
        if( state!=STREAM_STATE.WAITING)
            return;
        state = STREAM_STATE.IDLE;
        sendCommand();
    }
    @Override
    protected void processMessageEvent(byte[] data){

        // Anything received is assumed to be a reply to what was last send
        // Do this first just to be sure it won't trigger during processing
        if( timeoutFuture != null )
            timeoutFuture.cancel(true);

        String msg = new String(data).replace(eol, ""); // replace actually needed?
        if( readerIdle )
            flagAsActive();

        // Log anything and everything (except empty strings)
        if( !msg.isBlank() && rules.isLogging() ) {        // If the message isn't an empty string and logging is enabled, store the data with logback
            Logger.tag("RAW").warn( id + "\t" + msg);
        }

        // Calculate the time between sending the command and getting a reply
        var elapsed = Instant.now().toEpochMilli() - lastSend;

        // Got a line and it matches the regex
        var item = queue.getFirst(); // Peeks at the top, doesn't remove it yet

        SEND_RESULT result = SEND_RESULT.MAYBE_LOCATION;

        if( item.command().startsWith("$") ){
            state=STREAM_STATE.WAITING;
            timeoutExecutor.schedule(this::stopWaiting,rules.getDollarWaitTimeMilliseconds(),TimeUnit.MILLISECONDS);
        }
        if( !rules.isErrorMessage(msg) ){ // Or try again logic?
            queue.removeFirst();
            if( rules.isConfirmationMessage(msg) ) {
                result = SEND_RESULT.CONFIRMED;
                org.pmw.tinylog.Logger.trace("[{}] confirmed {}", id(), item.command());
            }
            if( !queue.isEmpty() ){
                sendCommand();
            }else if( state == STREAM_STATE.SENDING ){
                state=STREAM_STATE.IDLE;
            }
        }else{
            // TODO Try again or give up or flush queue? For now copy original and just give up
            result = SEND_RESULT.ERROR;
            queue.clear();
        }
        responseQueue.add( new CommandResponse(item,elapsed,msg,result) );
    }
    public void timeoutOccurred(){
        state = STREAM_STATE.TIMEOUT; // Change state to indicate an error occurred
        var item = queue.removeFirst(); // Grab the first element to fill in  the response queue
        responseQueue.add( new CommandResponse(item,item.timeout(),"",SEND_RESULT.TIMEOUT) );
        queue.clear(); // For now no retry logic is implemented
    }
}
