package org.openpnp.machine.reference.driver.exceptions;

import org.openpnp.machine.reference.driver.GcodeCommand;

public class GcodeErrorReplyException extends GcodeException {
    private final String reply;
    public GcodeErrorReplyException(GcodeCommand command, String reply) {
        super(command, command + " received error reply: " + reply);
        this.reply = reply;
    }
    public String reply() { return reply; }
}