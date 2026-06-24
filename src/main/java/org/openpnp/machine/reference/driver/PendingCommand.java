package org.openpnp.machine.reference.driver;

import java.util.function.Predicate;
import java.util.regex.Pattern;

public class PendingCommand {

    private String command;
    private long timeout=-1;

    private Predicate<String> confirmRegex=Pattern.compile("^ok.*").asMatchPredicate();

    private Runnable onConfirmation=()->{};
    boolean once=false;
    private Runnable onTimeOut=()->{};

    private PendingCommand(String command){
        this.command = command;
    }
    public String command(){
        return command;
    }
    public long timeout(){
        return timeout;
    }
    public static PendingCommand create(String command){
        return new PendingCommand(command);
    }
    public PendingCommand timeout(long timeout){
        this.timeout = timeout;
        return this;
    }
    public PendingCommand confirmRegex( String confirmRegex){
        this.confirmRegex = Pattern.compile(confirmRegex,Pattern.CASE_INSENSITIVE).asMatchPredicate();
        return this;
    }
    public PendingCommand onLastConfirmation(Runnable onConfirmation){
        this.onConfirmation = onConfirmation;
        once=true;
        return this;
    }
    public PendingCommand onEveryConfirmation(Runnable onConfirmation){
        this.onConfirmation = onConfirmation;
        once=false;
        return this;
    }
    public void doConfirmation(){
        this.onConfirmation.run();
    }
    public void doTimeOut(){
        this.onTimeOut.run();
    }
    public PendingCommand onTimeOut(Runnable onTimeOut){
        this.onTimeOut = onTimeOut;
        return this;
    }
    public PendingCommand copyWithNewCommand(String command){
        var newCommand = new PendingCommand(command);
        newCommand.confirmRegex = this.confirmRegex;
        newCommand.onTimeOut=this.onTimeOut;
        if( this.command.endsWith('\n'+command) || !once )
            newCommand.onConfirmation=this.onConfirmation;
        newCommand.timeout=this.timeout;
        return newCommand;
    }
}
