package com.unhandledexpression.wireguard;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.unhandledexpression.wireguard.protocol.Configuration;
import com.unhandledexpression.wireguard.protocol.Hardcoded;
import com.unhandledexpression.wireguard.protocol.State;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;

/**
 * Created by geal on 25/02/2017.
 */

public class VPN extends VpnService {
    private static final int TIMEOUT = 3000; // Wait timeout (milliseconds)

    public VPN() {

    }

    private Thread mThread;
    private ParcelFileDescriptor mInterface;
    //a. Configure a builder for the interface.
    Builder builder = new Builder();

    // Services interface
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Start a new session by creating a new thread.
        mThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.d("wg", "starting VPN");

                    mInterface = builder.setSession("MyVPNService")
                            .addAddress(Hardcoded.myIp, Hardcoded.myIpPrefix)
                            .addDnsServer("8.8.8.8")
                            .addRoute(Hardcoded.route, Hardcoded.routePrefix).establish();

                    FileInputStream in = new FileInputStream(
                            mInterface.getFileDescriptor());
                    FileOutputStream out = new FileOutputStream(
                            mInterface.getFileDescriptor());


                    Configuration config = new Configuration(Hardcoded.myPrivateKey, Hardcoded.myIp,
                            Hardcoded.myIpPrefix, Hardcoded.route, Hardcoded.routePrefix,
                            null, 0,
                            Hardcoded.theirPublicKey, Hardcoded.serverName, Hardcoded.serverPort,
                            null);
                    final State state = new State(config);

                    Log.d("wg", "state: "+state.toString());

                    DatagramChannel channel = DatagramChannel.open();
                    channel.connect(new InetSocketAddress(
                            InetAddress.getByName(Hardcoded.serverName), Hardcoded.serverPort));

                    protect(channel.socket());

                    byte[] initiatorPacket = state.createInitiatorPacket();
                    channel.write(ByteBuffer.wrap(initiatorPacket));

                    ByteBuffer responsePacket = ByteBuffer.allocate(32767);
                    SocketAddress addr = channel.receive(responsePacket);
                    Log.d("wg", "received packet from "+addr);
                    Log.d("wg", "response buffer before flip: "+responsePacket.position());
                    responsePacket.flip();
                    Log.d("wg", "response buffer after flip: position="+responsePacket.position());
                    Log.d("wg", "response buffer after flip: limit()="+responsePacket.limit());
                    state.receive(responsePacket, responsePacket.limit());

                    state.channel = channel;
                    Log.d("wg", "sending keep alive");
                    //keep alive
                    state.send(new byte[0], 0);

                    state.channel.configureBlocking(false);

                    Selector selector = Selector.open();
                    state.channel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);


                    Log.d("wg", "starting loop");
                    ByteBuffer packet = ByteBuffer.allocate(32767);

                    while (true) {
                        if (selector.select(TIMEOUT) == 0) {
                            //Log.d("wg", "TIMEOUT");
                            continue;
                        }

                        Iterator<SelectionKey> keyIter = selector.selectedKeys().iterator();
                        while (keyIter.hasNext()) {
                            SelectionKey key = keyIter.next();

                            if (key.isValid() && key.isReadable()) {
                                Log.d("wg", "RECEIVE");

                                while(true) {
                                    byte[] received = state.receive();
                                    if (received == null) {
                                        Log.d("wg", "received an empty array");
                                        break;
                                    }
                                    Log.d("wg", "hexdump:\n" + Utils.formatHexDump(received, 0, received.length));

                                    out.write(received, 0, received.length);
                                    Log.d("wg", "RECEIVED " + received.length + " bytes");
                                }
                            }

                            if (key.isValid() && key.isWritable()) {

                                int length = in.read(packet.array());

                                if (length > 0) {
                                    Log.d("wg", "SEND "+length+" bytes");
                                    Log.d("wg", "hexdump:\n"+Utils.formatHexDump(packet.array(), 0, length));
                                    //packet.limit(length);
                                    state.send(packet.array(), length);
                                    packet.clear();

                                }
                            }

                            keyIter.remove();
                        }

                    }

                } catch (Exception e) {
                    // Catch any exception
                    e.printStackTrace();
                } finally {
                    try {
                        if (mInterface != null) {
                            mInterface.close();
                            mInterface = null;
                        }
                    } catch (Exception e) {

                    }
                }
            }

        }, "MyVpnRunnable");

        //start the service
        mThread.start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (mThread != null) {
            mThread.interrupt();
        }
        super.onDestroy();
    }
}
