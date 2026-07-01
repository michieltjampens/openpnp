package org.openpnp.machine.reference.driver.exceptions;

import org.openpnp.machine.reference.driver.GcodeCommand;

public class GcodeException extends Exception {
    private final GcodeCommand command;
    public GcodeException(GcodeCommand command, String message) {
        super(message);
        this.command = command;
    }
    public GcodeCommand command() { return command; }
}
