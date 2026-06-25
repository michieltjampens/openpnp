package org.openpnp.machine.reference.driver;

import com.fazecast.jSerialComm.SerialPort;
import eu.settlabs.streams.serialport.SerialStream;

public class AltSerialPortCommunications extends SerialPortCommunications{
    SerialStream serialStream;

    public AltSerialPortCommunications(){
        serialStream = new SerialStream(null);
    }

    @Override
    public synchronized void connect() throws Exception {
        disconnect();

        serialStream.setPort(portName);
        serialStream.initAndConnect();

        var serial = serialStream.getSerialPort();
        serial.setComPortParameters(baud, dataBits.mask, stopBits.mask, parity.mask);
        serial.setFlowControl(flowControl.mask);

        if (setDtr) {
            serial.setDTR();
        }
        if (setRts) {
            serial.setRTS();
        }
        serial.setComPortTimeouts(
                SerialPort.TIMEOUT_READ_SEMI_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING, 0, 0);
    }

    @Override
    public synchronized void disconnect() throws Exception {
        serialStream.disconnect();
    }
}
