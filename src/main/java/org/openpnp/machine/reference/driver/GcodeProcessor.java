package org.openpnp.machine.reference.driver;

import org.tinylog.Logger;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;

public class GcodeProcessor{

    GcodeSerialWriter controller;
    GCodeCommandRules rules;

    LinkedBlockingDeque<GcodeSerialWriter.CommandResponse> queue;

    public GcodeProcessor(GcodeSerialWriter stream,  GCodeCommandRules rules) {
        this.controller = stream;
        this.rules = rules;
    }
    public BlockingQueue<GcodeSerialWriter.CommandResponse> getResponseQueue() {
        return controller.getResponseQueue();
    }
    public String id(){
        return controller.id();
    }
    public boolean sendGcode( String command, long timeout ){
        return sendGcode( PendingCommand.create(command).timeout( timeout ) );
    }
    public boolean sendGcode( PendingCommand gCode ) {
        if (gCode == null ) {
            Logger.warn("No valid gCode received");
            return false;
        }
        if( controller.inErrorState() ) {
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
        for( var single : list ) {
            var result = controller.addGcodeCommand( gCode.copyWithNewCommand(single) );
            if( result != GcodeSerialWriter.STREAM_STATE.SEND_ERROR ) {
                return false;
            }
        }
        return true;
    }
}
