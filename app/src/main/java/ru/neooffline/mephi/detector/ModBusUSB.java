package ru.neooffline.mephi.detector;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ModBusUSB {

    private byte[] readBuffer = new byte[255];
    private UsbSerialPort mActivePort;
    private UsbDeviceConnection mActiveConnection;
    private UsbManager mUsbManager;
    private boolean isConnected;
    private List<UsbSerialPort> mUSBSerialPorts;

    public boolean Connected() {return isConnected;}
    public synchronized UsbDeviceConnection ActiveUSBConnection() {return mActiveConnection;}
    public synchronized UsbSerialPort ActivePort() {return mActivePort;}

    public class MBResponse {
        public byte[] Raw;
        public int SlaveID;
        public int FuncCode;
    }

    public class MBRegResponse extends MBResponse {
        public int[] Data16;
    }

    public class MBBoolResponse extends MBResponse {
        public boolean[] DataBoolean;
    }

    public class MBExceptionResponse extends MBResponse {
        public int Exception;
    }

    public int ResponsesValid = 0;
    public int ResponsesError = 0;
    public int ResponsesTimeout = 0;

/*

    public modBusUSB(Context context, UsbSerialPort port) {
        // get USBManager object
        mUsbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        // enumerate USB Devices
        mUSBSerialPorts = new ArrayList<UsbSerialPort>();

        mActivePort = port;
        // if no connection has been set, selects 1st available USBSerialPort
        //refreshDeviceList();
    }
*/
    public ModBusUSB(Context context) {
        // get USBManager object
//        this.context = context;
        mUsbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);

        // enumerate USB Devices
        mUSBSerialPorts = new ArrayList<UsbSerialPort>();
        // if no connection has been set, selects 1st available USBSerialPort
//        mActivePort = port;
        isConnected = false;
    }

    public synchronized boolean Connect() throws IOException {
        // checks if a USBSerialPort was found/set
        if ((mActivePort == null)|(mActiveConnection == null)) {
            refreshUSBSerialDevices(); // try to find Serial hardware
        }

        if ((mActivePort == null)|(mActiveConnection == null)) {
            return false;
        } else {
            if (mActiveConnection == null) {
                return false;
            } else {
                mActivePort.open(mActiveConnection);
                mActivePort.setParameters(9600, 8, UsbSerialPort.STOPBITS_2, UsbSerialPort.PARITY_NONE);
                isConnected = true;
                return true;
            }
        }
    }

    public synchronized void SetSerialParams(int baudRate, int dataBits, int stopBits, int parity) throws IOException {
        if (mActivePort != null ) {
            mActivePort.setParameters(baudRate, dataBits, stopBits, parity);
        }
    }

    public synchronized void Disconnect() throws IOException {
        if (mActivePort != null) {
            mActivePort.close();
            mActivePort = null;
            mActiveConnection = null;
            isConnected = false;
        }
    }

    public synchronized void ReadHoldingRegisters(int slaveID, int regStart, int regCount ) throws IOException {
        final byte FuncCode = 0x03;
        ReadRegisters(FuncCode,slaveID,regStart,regCount);
    }

    public synchronized void ReadInputRegisters(int slaveID, int regStart, int regCount) throws IOException {
        final byte FuncCode = 0x04;
        ReadRegisters(FuncCode,slaveID,regStart,regCount);
    }

    private void ReadRegisters(byte funcCode, int slaveID, int regStart, int regCount) throws IOException {
        byte[] reqPDU = new byte[5];
        // Build Request PDU
        reqPDU[0] = funcCode;
        reqPDU[1] = (byte)((regStart-1) >> 8);
        reqPDU[2] = (byte)(regStart-1 );
        reqPDU[3] = (byte)(regCount >> 8);
        reqPDU[4] = (byte)(regCount );
        sendPDU(slaveID,reqPDU);
    }

    public synchronized void WriteHoldingRegisters(int slaveID, int regStart, int[] regValues ) throws IOException {
        // ToDo: Build and Send Request Packet.
        byte FuncCode = 0x11;
        int regCount = regValues.length;
        byte[] reqPDU = new byte[regCount*2+6];
        // Build Request PDU
        reqPDU[0] = FuncCode;
        reqPDU[1] = (byte)((regStart-1) >> 8);
        reqPDU[2] = (byte)(regStart-1 );
        reqPDU[3] = (byte)(regCount >> 8);
        reqPDU[4] = (byte)(regCount );
        reqPDU[5] = (byte)(regCount*2);
        for (int i=0; i<regCount; i++) {
            reqPDU[6+i*2] = (byte) (regValues[i] >> 8);
            reqPDU[7+i*2] = (byte) (regValues[i]);
        }
        sendPDU(slaveID,reqPDU);
    }

    private synchronized void refreshUSBSerialDevices() {
        UsbDevice mActiveDevice;

        final List<UsbSerialDriver> drivers =
                UsbSerialProber.getDefaultProber().findAllDrivers(mUsbManager);

        // ToDo: call function from input param
        mUSBSerialPorts.clear();
        for (final UsbSerialDriver driver : drivers) {
            mUSBSerialPorts.addAll(driver.getPorts());
        }
        // try to connect
        if (!mUSBSerialPorts.isEmpty() & (mActivePort==null)) {
            mActivePort = mUSBSerialPorts.get(0);
        }
        if (mActivePort != null) {
            mActiveDevice = mActivePort.getDriver().getDevice();
            if (mActiveDevice !=null) {
               mActiveConnection = mUsbManager.openDevice(mActiveDevice);
            }
        }
    }

    protected void sendPDU(int slaveID, byte[] PDU) throws IOException {
        // Sends ModBus PDU (Process Data Unit) on serial RTU

        byte[] query = new byte[PDU.length+1];
        query[0] = (byte)slaveID;
        System.arraycopy(PDU, 0, query, 1, PDU.length);

        if ((mActivePort!=null) & (isConnected)) {
            mActivePort.write(AddCRC(query), 1);
        }
    }

    public MBResponse readPDU() throws IOException {
        final int timeoutMillis = 500;
        MBResponse aResponse = new MBResponse();

        if ((mActivePort!=null) & (isConnected)) {
            int byteCount = mActivePort.read(readBuffer, timeoutMillis);

            // increment counters
            if (byteCount==0) {
                ResponsesTimeout++;
            } else {
                byte[] checkPacket = new byte[byteCount-2];
                System.arraycopy(readBuffer, 0, checkPacket, 0, byteCount-2);
                if (byteCount<5) {
                    ResponsesError++;
                } else {
                    int rCRC = getCRC(checkPacket);
                    if ( ((byte)rCRC==readBuffer[byteCount-2]) & ((byte)(rCRC>>8)==readBuffer[byteCount-1]) ) {
                        ResponsesValid++;
                        aResponse = ProcessResponsePDU(byteCount);
                    } else {
                        ResponsesError++;
                    }
                }
            }
        }
        return aResponse;
    }

    public int[] ReadRegPDU() throws IOException {
        int[] result = {};

        MBResponse aResponse = readPDU();

        if (aResponse instanceof MBRegResponse) {
            MBRegResponse aRegResponse = (MBRegResponse) aResponse;
            result = aRegResponse.Data16;
        }

        return result;

    }

    private MBResponse ProcessResponsePDU(int byteCount) {
        MBResponse nResponse;

        // Process PDU
        if (readBuffer[1]>0x80) {
            MBExceptionResponse eResponse = new MBExceptionResponse();
            eResponse.FuncCode = (int)readBuffer[1]& 0xFF-0x80;
            eResponse.Exception = (int)readBuffer[2]& 0xFF;
            nResponse = eResponse;
        } else {
            switch (readBuffer[1]) {
                case 0x03: case 0x04:
                    MBRegResponse rResponse = new MBRegResponse();
                    int regCount = (readBuffer[2] & 0xFF)/2;
                    rResponse.Data16 = new int[regCount];
                    for (int i=0; i<regCount;i++) {
                        rResponse.Data16[i] = (((int)readBuffer[3+i*2]&0xff)<<8) | ((int)readBuffer[4+i*2]&0xff);
                    }
                    nResponse = rResponse;
                    break;
                default:
                    nResponse = new MBResponse();
            }
            nResponse.FuncCode = (int)readBuffer[1];
        }
        nResponse.SlaveID = readBuffer[0];
        nResponse.Raw = new byte[byteCount];
        System.arraycopy(readBuffer, 0, nResponse.Raw, 0, byteCount);

        return nResponse;
    }

    private byte[] AddCRC(byte[] input) {
        byte[] result = new byte[input.length+2];
        int crc = getCRC(input);
        System.arraycopy(input, 0, result, 0, input.length);
        result[input.length]  =(byte)(crc);
        result[input.length+1]=(byte)(crc>>8);
        return result;
    }

    private int getCRC(byte[] input) {
        int iPos = 0; // loop position in input buffer
        int crc = 0xFFFF;
        while (iPos < input.length) {
            crc ^= (input[iPos] & 0xFF);
            iPos++;
            for (int j = 0; j < 8; j++) {
                boolean bitOne = ((crc & 0x1) == 0x1);
                crc >>>= 1;
                if (bitOne) {
                    crc ^= 0x0000A001;
                }
            }
        }
        return crc;
    }

}