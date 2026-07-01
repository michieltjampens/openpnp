package org.openpnp.machine.reference.driver.exceptions;

import org.openpnp.machine.reference.driver.GcodeCommand;

public class GcodeRegexMismatchException extends GcodeException {
    private final String reply;
    public GcodeRegexMismatchException(GcodeCommand command, String reply) {
        super(command, command + " reply didn't match expected pattern: " + reply);
        this.reply = reply;
    }
    public String reply() { return reply; }
}
