package org.openpnp.machine.reference.driver.exceptions;

import org.openpnp.machine.reference.driver.GcodeCommand;

public class GcodeTimeoutException extends GcodeException {
    public GcodeTimeoutException(GcodeCommand command) {
        super(command, command + " timed out waiting for reply");
    }
}