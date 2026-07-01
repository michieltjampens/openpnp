package org.openpnp.machine.reference.driver.exceptions;

import org.openpnp.machine.reference.driver.GcodeCommand;

public class GcodeSendFailedException extends GcodeException {
    public GcodeSendFailedException(GcodeCommand command) {
        super(command, "Failed to send: " + command);
    }
}
