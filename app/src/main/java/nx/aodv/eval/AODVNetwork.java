package nx.aodv.eval;

import android.util.Log;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;
import com.google.android.gms.tasks.OnFailureListener;

import java.io.IOException;
import java.io.Serializable;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

class AODVNetwork {

    private static final String TAG = "connectedcrossroad";
    private static final Strategy STRATEGY = Strategy.P2P_CLUSTER;
    private static final short DEFAULT_NAME = 0;

    private static final int UDP_PORT = 5055;
    private static final int UDP_BUFSIZE = 2048;
    private static final short UDP_BROADCAST_ADDR = (short) 0xFFFF;

    private static final int AODV_HEADER_SIZE = 20; //bytes for UDP header

    private static final int MAX_NEIGHBORS = 3;

    private static long HELLO_INTERVAL = 2000;
    private static long ROUTE_EXPIRY_INTERVAL = 3000;
    private static long ROUTE_TIMEOUT = 7000;
    private static long QUEUE_TIMEOUT = 7000;
    private static long QUEUE_INTERVAL = 500;
    private static long QUEUE_POLLING_TIMEOUT = 5000;

    // Data RX/TX totals; payload-only and entire packet data.
    private int totalPayloadTx;
    private int totalPayloadRx;
    private int totalTx;
    private int totalRx;

    //self routing info
    private final AODVRoute self;

    //key is address, value is route
    private final Map<Short, AODVRoute> routeTable;

    //key is endpointId, value is route
    private final Map<String, AODVRoute> ccNeighborsTable;

    //key is address, value is route
    private final Map<Short, AODVRoute> udpNeighborsTable;

    //key is address, value is endpointId
    private final Map<Short, String> neighborAddressToId;

    //lock to synchronize threads when modifying route tables
    private final Object routeTableLock = new Object();

    //queue to store data while waiting for RREP
    private BlockingQueue<AODVTxData> dataTxQueue;
    //queue for udp messages
    private final BlockingQueue<AODVMessage> udpTxQueue;
    //queue to handle incoming AODV messages
    private final BlockingQueue<AODVMessage> handleAODVQueue;

    //handles discovery, advertising, and connecting
    private final ConnectionsClient connectionsClient;

    //Text view to display messages received to user
    private final TextView lastMessageRx;
    //Text view to display number of connected nodes to user
    private final TextView numConnectedText;
    // Text view to show the routing table.
    private final TextView tvRouteTable;

    //sockets for communicating with MK6s over UDP
    private DatagramSocket listenerSocket;
    private DatagramSocket senderSocket;

    //Thread to periodically send hello messages to neighbors to update information
    private final Thread helloTxThread;
    //Thread to pull from queue and send data, waiting for RREPs if necessary
    private final Thread dataTxThread;
    //Thread to periodically check for expired route entries
    private final Thread routeExpiryThread;
    //Thread to receive UDP messages from MK6 nodes
    private final Thread udpServerThread;
    //Thread for send udp messages so it's not on the main thread
    private final Thread udpTxThread;
    //Thread to handle incoming AODV messages;
    private final Thread handleAODVThread;

    private boolean searching = false;

    AODVNetwork(ConnectionsClient connectionsClient,
                TextView numConnectedText,
                TextView lastMessageRx,
                TextView tvRouteTable) {

        this.totalPayloadRx = this.totalPayloadTx = 0;
        this.totalRx = this.totalTx = 0;

        this.self = new AODVRoute();
        this.self.address = DEFAULT_NAME;
        this.routeTable = Collections.synchronizedMap(new HashMap<Short, AODVRoute>());
        this.ccNeighborsTable = Collections.synchronizedMap(new HashMap<String, AODVRoute>());
        this.udpNeighborsTable = Collections.synchronizedMap(new HashMap<Short, AODVRoute>());
        this.neighborAddressToId = Collections.synchronizedMap(new HashMap<Short, String>());
        this.dataTxQueue = new LinkedBlockingQueue<>();
        this.udpTxQueue = new LinkedBlockingQueue<>();
        this.handleAODVQueue = new LinkedBlockingQueue<>();
        this.connectionsClient = connectionsClient;
        this.lastMessageRx = lastMessageRx;
        this.numConnectedText = numConnectedText;
        this.tvRouteTable = tvRouteTable;

        Runnable helloTxRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    senderSocket = new DatagramSocket(null);
                    senderSocket.setReuseAddress(true);
                    senderSocket.setBroadcast(true);
                    //set to MK6 wifi address, make this configurable
                    //senderSocket.bind(new InetSocketAddress("192.168.10.255", UDP_PORT));
                    //senderSocket.bind(new InetSocketAddress(UDP_PORT));
                    while (!Thread.interrupted()) {
                        //send a hello message to neighbors every hello interval
                        AODVMessage helloMsg = initHELLO();
                        broadcastMessage(helloMsg);
                        Thread.sleep(HELLO_INTERVAL);
                    }
                } catch (SocketException | InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    senderSocket.close();
                }
            }
        };
        this.helloTxThread = new Thread(helloTxRunnable);

        Runnable dataTxRunnable = new Runnable() {
            @Override
            public void run() {
                while (!Thread.interrupted()) {
                    Collection<AODVTxData> txDatas = new ArrayList<>();
                    try {
                        while(!dataTxQueue.isEmpty()) {
                            AODVTxData txData = dataTxQueue.poll(QUEUE_POLLING_TIMEOUT, TimeUnit.MILLISECONDS);
                            if (txData != null && txData.msg != null) {
                                Log.d(TAG, "dataTxThread: Pulled AODV data from queue");
                                AODVMessage msg = txData.msg;
                                synchronized (routeTableLock) {
                                    AODVRoute route = getRouteByAddress(msg.header.destAddr);
                                    //if we have a route to the destination, send the message
                                    if (route != null) {
                                        msg.header.nextId = route.nextHopId;
                                        msg.header.nextAddr = route.nextHopAddr;
                                        msg.header.hopCnt = route.hopCnt;
                                        sendMessage(msg);
                                    } else if (txData.lifetime > System.currentTimeMillis()) {
                                        //add message back to queue if it has not expired and rrep may not have returned
                                        txDatas.add(txData);
                                    }
                                }
                            }
                            //need this so we don't poll infinitely when there is one rreq waiting
                        }
                        Thread.sleep(QUEUE_INTERVAL);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } finally {
                        dataTxQueue.drainTo(txDatas);
                        dataTxQueue = new LinkedBlockingQueue<>(txDatas);
                    }
                }
            }
        };
        this.dataTxThread = new Thread(dataTxRunnable);

        Runnable routeExpiryRunnable = new Runnable() {
            @Override
            public void run() {
                while (!Thread.interrupted()) {
                    try {
                        synchronized (routeTableLock) {
                            long timeMillis = System.currentTimeMillis();
                            //remove expired routes
                            for (AODVRoute route : routeTable.values()) {
                                if (route.timeout < timeMillis) {
                                    Log.v("perf", "Route to " + route.address + " (" + route.id + ") expired.");
                                    removeRouteByAddress(route.address);
                                }
                            }
                            //remove expired udp neighbors
                            for (AODVRoute route : udpNeighborsTable.values()) {
                                if (route.timeout < timeMillis) {
                                    removeRouteByAddress(route.address);
                                }
                            }
                        }
                        //connections may have been removed so update num connected on main thread somehow
                        Thread.sleep(ROUTE_EXPIRY_INTERVAL);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        };
        this.routeExpiryThread = new Thread(routeExpiryRunnable);

        Runnable udpServerRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    listenerSocket = new DatagramSocket(null);
                    listenerSocket.setReuseAddress(true);
                    listenerSocket.setBroadcast(true);
                    //set to MK6 wifi address, make this configurable
                    //listenerSocket.bind(new InetSocketAddress("192.168.10.255", UDP_PORT));
                    listenerSocket.bind(new InetSocketAddress(UDP_PORT));
                    byte[] inBuffer = new byte[UDP_BUFSIZE];
                    while (!Thread.interrupted()) {
                        DatagramPacket packet = new DatagramPacket(inBuffer, inBuffer.length);
                        listenerSocket.receive(packet);
                        String received = new String(packet.getData(), 0, packet.getLength());
                        AODVMessage recv = deserializeAODVPacket(packet.getData());
                        if (recv != null && recv.header.sendDevType != AODVDeviceType.AND &&
                                (recv.header.nextAddr == self.address || recv.header.nextAddr == UDP_BROADCAST_ADDR)) {
                            handleAODVQueue.add(recv);
                            //handleAODVMessage(recv);
                        } /*else {
                            Log.d(TAG, "AODVServer: Dropping UDP message");
                        }*/
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    listenerSocket.close();
                }
            }
        };
        this.udpServerThread = new Thread(udpServerRunnable);

        Runnable udpTxRunnable = new Runnable() {
            @Override
            public void run() {
                while (!Thread.interrupted()) {
                    try {
                        AODVMessage msg;
                        msg = udpTxQueue.poll(QUEUE_POLLING_TIMEOUT, TimeUnit.MILLISECONDS);
                        if (msg != null) {
                            broadcastUDPMessage(msg);
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        };
        this.udpTxThread = new Thread(udpTxRunnable);

        Runnable handleAODVRunnable = new Runnable() {
            @Override
            public void run() {
                while(!Thread.interrupted()) {
                    try {
                        AODVMessage msg;
                        msg = handleAODVQueue.poll(QUEUE_POLLING_TIMEOUT, TimeUnit.MILLISECONDS);
                        if (msg != null) {
                            synchronized (routeTableLock) {
                                handleAODVMessage(msg);
                                showTrafficTotals();
                            }
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                }
            }
        };
        this.handleAODVThread = new Thread(handleAODVRunnable);

        Log.v("perf", "AODV networking started.");
    }

    void start() {
        startAdvertising();
        startDiscovery();
        helloTxThread.start();
        dataTxThread.start();
        routeExpiryThread.start();
        udpServerThread.start();
        udpTxThread.start();
        handleAODVThread.start();
    }

    void stop() {
        helloTxThread.interrupt();
        dataTxThread.interrupt();
        routeExpiryThread.interrupt();
        udpServerThread.interrupt();
        udpTxThread.interrupt();
        handleAODVThread.interrupt();
        stopDiscovery();
        stopAdvertising();
        connectionsClient.stopAllEndpoints();
        Log.v("perf", "Networking stopped");
    }

    void startDiscovery() {
        if (!searching) {
            searching = true;
            connectionsClient.startDiscovery(
                    TAG, endpointDiscoveryCallback,
                    new DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
            );
        }
    }

    void stopDiscovery() {
        if (searching) {
            connectionsClient.stopDiscovery();
            searching = false;
        }
    }

    void startAdvertising() {
        //Advertising may fail. To keep this demo simple, we don't handle failures.
        connectionsClient.startAdvertising(
                String.valueOf(self.address), TAG, connectionLifecycleCallback,
                new AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        );
    }

    void stopAdvertising() {
        connectionsClient.stopAdvertising();
    }

    //Give the device a human readable address (one-time at startup before advertising)
    void setAddress(short address) {
        this.self.address = address;
    }

    short getAddress() {
        return self.address;
    }

    void setIntervalsAndTimeouts(long HELLO_INTERVAL,
            long ROUTE_EXPIRY_INTERVAL,
            long ROUTE_TIMEOUT,
            long QUEUE_TIMEOUT,
            long QUEUE_INTERVAL,
            long QUEUE_POLLING_TIMEOUT){
//        this.HELLO_INTERVAL = HELLO_INTERVAL;
//        this.ROUTE_EXPIRY_INTERVAL = ROUTE_EXPIRY_INTERVAL;
        this.ROUTE_TIMEOUT = ROUTE_TIMEOUT;
//        this.QUEUE_TIMEOUT = QUEUE_TIMEOUT;
//        this.QUEUE_INTERVAL = QUEUE_INTERVAL;
//        this.QUEUE_POLLING_TIMEOUT = QUEUE_POLLING_TIMEOUT;
    }

    public int getLocalSize() {
        synchronized (routeTableLock) {
            return 1 + ccNeighborsTable.size() + udpNeighborsTable.size();
        }
    }

    private AODVRoute getRouteByAddress(short address) {
        AODVRoute route = null;
        if (routeTable.containsKey(address)) {
            route = routeTable.get(address);
        } else if (udpNeighborsTable.containsKey(address)) {
            route = udpNeighborsTable.get(address);
        } else if (ccNeighborsTable.containsKey(neighborAddressToId.get(address))) {
            route = ccNeighborsTable.get(neighborAddressToId.get(address));
        }
        return route;
    }


    private void removeRouteByAddress(short address) {
        if (routeTable.containsKey(address)) {
            routeTable.remove(address);
        } else if (udpNeighborsTable.containsKey(address)) {
            udpNeighborsTable.remove(address);
        } else if (ccNeighborsTable.containsKey(neighborAddressToId.get(address))) {
            ccNeighborsTable.remove(neighborAddressToId.get(address));
            neighborAddressToId.remove(address);
        }
    }

    void sendMessage(short address, String data) {
        synchronized (routeTableLock) {
            AODVMessage userMessage = initDATA(address, data);
            AODVRoute route = getRouteByAddress(address);
            if (route != null) {
                userMessage.header.nextId = route.nextHopId;
                userMessage.header.hopCnt = route.hopCnt;
                userMessage.header.destSeqNum = route.seqNum;
                sendMessage(userMessage);
            } else {
                AODVTxData txData = new AODVTxData(userMessage);
                //add to message queue to be processed when route is available
                dataTxQueue.add(txData);
                AODVMessage rreq = initRREQ(address);
                broadcastMessage(rreq);
            }
        }

        showTrafficTotals();
    }

    private void sendMessage(AODVMessage msg) {
        msg.header.sendAddr = self.address;
        msg.header.sendDevType = AODVDeviceType.AND;
        if (msg.header.nextId != null) {
            sendCCMessage(msg);
        } else {
            udpTxQueue.add(msg);
        }
        this.totalPayloadTx += msg.header.length;
    }

    private void broadcastMessage(AODVMessage msg) {
        msg.header.sendAddr = self.address;
        broadcastCCMessage(msg);
        udpTxQueue.add(msg);
        this.totalPayloadTx += msg.header.length;
    }

    private synchronized void sendCCMessage(AODVMessage msg) {
        try {
            Log.d(TAG, "Data collect: SEND");
            byte[] bytes = SerializationHelper.serialize(msg);
            Payload payload = Payload.fromBytes(bytes);
            totalTx += bytes.length;
            totalPayloadTx += msg.header.length;
            connectionsClient.sendPayload(msg.header.nextId, payload);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void broadcastCCMessage(AODVMessage msg) {
        synchronized (routeTableLock) {
            for (AODVRoute neighbor : ccNeighborsTable.values()) {
                msg.header.nextAddr = neighbor.address;
                msg.header.nextId = neighbor.nextHopId;
                totalPayloadTx += msg.header.length;
                sendCCMessage(msg);
            }
        }
    }

    private synchronized void broadcastUDPMessage(AODVMessage msg) {
        try {
                msg.header.nextAddr = UDP_BROADCAST_ADDR;
                byte[] outBuffer = serializeAODVPacket(msg);
                InetAddress broadcastAddr = InetAddress.getByName("255.255.255.255");
                //InetAddress broadcastAddr = InetAddress.getByName("192.168.10.255");
                //DatagramPacket packet = new DatagramPacket(outBuffer, outBuffer.length, broadcastAddr, UDP_PORT);
                //send to MK6 wifi
                DatagramPacket packet = new DatagramPacket(outBuffer, outBuffer.length, broadcastAddr, UDP_PORT);
                senderSocket.send(packet);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleAODVMessage(AODVMessage msg) {
        if (this.ccNeighborsTable.containsKey(msg.header.sendId)) {
            this.ccNeighborsTable.get(msg.header.sendId).address =
            msg.header.srcAddr;
        }

        this.totalPayloadRx += msg.header.length;

        switch (msg.header.type) {
            case HELO:
                handleHELLO(msg);
                break;
            case DATA:
                handleDATA(msg);
                break;
            case RREQ:
                handleRREQ(msg);
                break;
            case RREP:
                handleRREP(msg);
                break;
            case RERR:
                handleRERR(msg);
                break;
            default:
                Log.d(TAG, "handleAODVMessage: unknown type");
                break;
        }
    }

    private void handleHELLO(AODVMessage msg) {
        Log.v(TAG, "Data collect: HELO");
        short sendAddr = msg.header.sendAddr;
        String sendId = msg.header.sendId;
        AODVRoute neighbor;
        if (ccNeighborsTable.containsKey(sendId)) {
            neighbor = ccNeighborsTable.get(sendId);
            //we can only know neighbor Addr from hello messages
            if (neighbor != null) {
                if (!neighborAddressToId.containsKey(sendAddr)) {
                    Log.v("perf", "Discovered neighbor: " + sendAddr + " (" + sendId + ")");
                    Log.v("perf", "Bytes sent: " + totalTx + ", received: " + totalRx);
                    neighborAddressToId.put(sendAddr, sendId);
                }
                neighbor.address = msg.header.srcAddr;
                neighbor.nextHopAddr = sendAddr;
                neighbor.seqNum = msg.header.srcSeqNum;
                //neighbor.bcastSeqNum = msg.header.bcastSeqNum;
            } else {
                Log.d(TAG, "handleHello: CC neighbor was null");
            }
        } else if (udpNeighborsTable.containsKey(sendAddr)) {
            neighbor = udpNeighborsTable.get(sendAddr);
            if (neighbor != null) {
                neighbor.seqNum = msg.header.srcSeqNum;
                //neighbor.bcastSeqNum = msg.header.bcastSeqNum;
                neighbor.timeout = System.currentTimeMillis() + ROUTE_TIMEOUT;
            } else {
            }
        } else {
                //make new route for neighbor
                neighbor = new AODVRoute();
                neighbor.address = msg.header.srcAddr;
                neighbor.nextHopAddr = sendAddr;
                neighbor.seqNum = msg.header.srcSeqNum;
                //neighbor.bcastSeqNum = msg.header.bcastSeqNum;
                //need a timeout for these neighbors because connection is only maintained by hellos
                neighbor.timeout = System.currentTimeMillis() + ROUTE_TIMEOUT;
                udpNeighborsTable.put(sendAddr, neighbor);
        }
    }

    private void handleDATA(AODVMessage msg) {
        Log.v(TAG, "Data collect: DATA");
        long startTime = System.currentTimeMillis();
        short destAddr = msg.header.destAddr;
        if (destAddr == self.address) {
            //do whatever with data, in our case post it to the text view
//            updateLastMessageRx(msg.header.srcAddr, msg.payload);
        } else {
            Log.v(TAG, "Data collect: intermediary hop");
            AODVRoute route = getRouteByAddress(destAddr);
            if (route != null) {
                msg.header.nextId = route.nextHopId;
                sendMessage(msg);
            } else {
                short srcAddr = msg.header.srcAddr;
                AODVMessage rerr = initRERR(srcAddr);
                if (rerr != null) {
                    self.seqNum++; //increase seq num for rerr?
                    sendMessage(rerr);
                } else {
                    //nothing can be done, drop message
                    Log.d(TAG, "handleData: dropping RERR message");
                }
            }
        }
        long stopTime = System.currentTimeMillis();
        long elapsedMS = stopTime - startTime;
        Log.v(TAG, "Data collect: handle data elapsed MS: " + elapsedMS);
    }

    private void handleRREQ(AODVMessage msg) {
        Log.v(TAG, "Data collect: RREQ");
        short srcAddr = msg.header.srcAddr;
        short destAddr = msg.header.destAddr;
        if (srcAddr == self.address) {
            return;
        }
        AODVRoute srcRoute = getRouteByAddress(srcAddr);
        //set up reverse route to src if one doesn't exist
        if (srcRoute == null) {
            String sendId = msg.header.sendId;
            srcRoute = new AODVRoute();
            srcRoute.address = srcAddr;
            srcRoute.seqNum = msg.header.srcSeqNum;
            srcRoute.nextHopId = sendId;
            srcRoute.nextHopAddr = msg.header.sendAddr;
            srcRoute.hopCnt = (byte) (msg.header.hopCnt + 1);
            srcRoute.timeout = System.currentTimeMillis() + ROUTE_TIMEOUT;
            routeTable.put(srcAddr, srcRoute);
//            updateRouteTableDisplay();
        }
        //check bcast seq num for route freshness and to prevent loops
        if (msg.header.bcastSeqNum <= srcRoute.bcastSeqNum) {
            return;
        }
        srcRoute.bcastSeqNum = msg.header.bcastSeqNum;

        AODVRoute destRoute = getRouteByAddress(destAddr);
        if (destAddr == self.address || (destRoute != null && msg.header.destSeqNum <= destRoute.seqNum)) {
            self.seqNum++; //inc seq num?
            AODVMessage rrep = initRREP(destAddr, srcAddr);
            if (rrep != null) {
                sendMessage(rrep);
            } else {
                Log.d(TAG, "handleRREQ: dropping RREP to: " + srcAddr);
            }
        } else {
            //make sure no loops are happening with this
            Log.d(TAG, "handleRREQ: rebroadcasting RREQ for: " + destAddr);
            broadcastMessage(msg);
        }
    }

    private void handleRREP(AODVMessage msg) {
        Log.v(TAG, "Data collect: RREP");
        short srcAddr = msg.header.srcAddr;
        short destAddr = msg.header.destAddr;
        AODVRoute srcRoute = getRouteByAddress(srcAddr);
        //to prevent loops, only forward one of each rrep
        //is this correct?
        if (srcRoute != null && msg.header.srcSeqNum <= srcRoute.seqNum) {
            return;
        }
        if (srcRoute == null) {
            Log.d(TAG, "handleRREP: Creating forward route to: " + srcAddr);
            srcRoute = new AODVRoute();
            srcRoute.address = srcAddr;
            srcRoute.nextHopId = msg.header.sendId;
            srcRoute.nextHopAddr = msg.header.sendAddr;
            srcRoute.seqNum = msg.header.srcSeqNum;
            srcRoute.hopCnt = (byte) (msg.header.hopCnt - 1);
            routeTable.put(srcAddr, srcRoute);
//            updateRouteTableDisplay();
        }
        srcRoute.timeout = System.currentTimeMillis() + ROUTE_TIMEOUT;
        AODVRoute destRoute = getRouteByAddress(destAddr);
        if (destAddr == self.address) {
            Log.d(TAG, "handleRREP: RREP reached destination");
        } else if (destRoute != null) {
            Log.d(TAG, "handleRREP: Forwarding RREP to next hop");
            msg.header.nextId = destRoute.nextHopId;
            msg.header.nextAddr = destRoute.nextHopAddr;
            msg.header.hopCnt--;
            sendMessage(msg);
        } else {
            Log.d(TAG, String.format("handleRREP: ERROR in RREP from %s to %s", srcAddr, destAddr));
            //self.seqNum++;
            AODVMessage rerr = initRERR(srcAddr);
            if (rerr != null) {
                sendMessage(rerr);
            } else {
                Log.d(TAG, "handleRREP: dropping RERR to: " + srcAddr);
            }
        }
    }

    private void handleRERR(AODVMessage msg) {
        Log.v(TAG, "Data collect: RERR");
        short srcAddr = msg.header.srcAddr;
        short destAddr = msg.header.destAddr;
        Log.d(TAG, "handleRERR: Received AODV RERR message from: " + srcAddr);
        removeRouteByAddress(srcAddr);
        if (destAddr == self.address) {
            Log.d(TAG, "handleRERR: RERR reached destination");
        } else {
            AODVRoute route = getRouteByAddress(destAddr);
            if (route != null) {
                Log.d(TAG, "handleRERR: Forwarding RERR to next hop: " + route.nextHopAddr);
                msg.header.nextId = route.nextHopId;
                //msg.header.sendAddr = self.address;
                sendMessage(msg);
            } else {
                Log.d(TAG, "handleRERR: Dropping RERR to: " + destAddr);
            }
        }
    }

    //always broadcast these to all neighbors
    private AODVMessage initHELLO() {
        AODVMessage msg = new AODVMessage();
        msg.header.type = AODVMessageType.HELO;
        msg.header.srcAddr = self.address;
        msg.header.srcSeqNum = self.seqNum;
        //msg.header.bcastSeqNum = self.bcastSeqNum;
        msg.header.hopCnt = 0;
        msg.header.length = 0;
        return msg;
    }

    //ids need to get set somewhere else because route may not exist or may change
    private AODVMessage initDATA(short destAddr, String data) {
        AODVMessage msg = new AODVMessage();
        msg.header.type = AODVMessageType.DATA;
        msg.header.srcAddr = self.address;
        msg.header.srcSeqNum = self.seqNum;
        //msg.header.bcastSeqNum = self.bcastSeqNum;
        msg.header.destAddr = destAddr;
        msg.header.length = (short) data.length();
        msg.payload = data;
        return msg;
    }

    //always broadcast these to all neighbors
    private AODVMessage initRREQ(short destAddr) {
        AODVMessage msg = new AODVMessage();
        msg.header.type = AODVMessageType.RREQ;
        msg.header.srcAddr = self.address;
        msg.header.srcSeqNum = self.seqNum;
        msg.header.sendAddr = self.address;
        msg.header.bcastSeqNum = ++self.bcastSeqNum; //inc on each rreq
        msg.header.destAddr = destAddr;
        msg.header.length = 0;
        //may still have active route but need updated information
        AODVRoute route = getRouteByAddress(destAddr);
        if (route != null) {
            msg.header.destSeqNum = route.seqNum;
            msg.header.hopCnt = route.hopCnt;
        } else {
            msg.header.destSeqNum = 0;
            msg.header.hopCnt = 0;
        }
        return msg;
    }

    private AODVMessage initRREP(short srcAddr, short destAddr) {
        AODVMessage msg = null;
        AODVRoute destRoute = getRouteByAddress(destAddr);
        if (destRoute != null) {
            msg = new AODVMessage();
            msg.header.type = AODVMessageType.RREP;
            msg.header.srcAddr = srcAddr;
            msg.header.srcSeqNum = self.seqNum;
            //msg.header.bcastSeqNum = self.bcastSeqNum;
            msg.header.destAddr = destRoute.address;
            msg.header.destSeqNum = destRoute.seqNum;
            msg.header.nextId = destRoute.nextHopId;
            msg.header.nextAddr = destRoute.nextHopAddr;
            msg.header.hopCnt = destRoute.hopCnt;
            msg.header.length = 0;
        }
        return msg;
    }

    private AODVMessage initRERR(short destAddr) {
        AODVMessage msg = null;
        //need an active route to dest;
        AODVRoute route = getRouteByAddress(destAddr);
        if (route != null) {
            msg = new AODVMessage();
            msg.header.type = AODVMessageType.RERR;
            msg.header.srcAddr = self.address;
            msg.header.srcSeqNum = self.seqNum;
            msg.header.bcastSeqNum = self.bcastSeqNum;
            msg.header.destAddr = route.address;
            msg.header.destSeqNum = route.seqNum;
            msg.header.nextId = route.nextHopId;
            msg.header.nextAddr = route.nextHopAddr;
            msg.header.hopCnt = Byte.MAX_VALUE; //signifying broken link to prevent loops
            msg.header.length = 0;
        }
        return msg;
    }

    //numbers don't really mean anything, for compatibility with C version
    private enum AODVMessageType implements Serializable {
        NONE((byte) 0),
        RREQ((byte) 120),
        RREP((byte) 121),
        RERR((byte) 122),
        HELO((byte) 123),
        DATA((byte) 124);

        private final byte id;
        private static final Map<Byte, AODVMessageType> valToType = new HashMap<>();
        static {
            for (AODVMessageType type : AODVMessageType.values()) {
                valToType.put(type.getValue(), type);
            }
        }

        AODVMessageType(byte id) { this.id = id; }

        byte getValue() { return id; }

        static AODVMessageType valueOf(byte id) {
            return valToType.containsKey(id) ? valToType.get(id) : NONE;
        }
    }

    private enum AODVDeviceType implements Serializable {
        NONE((byte) 0),
        AND((byte) 1),
        MK6((byte) 2);

        private final byte id;
        private static final Map<Byte, AODVDeviceType> valToType = new HashMap<>();
        static {
            for (AODVDeviceType type : AODVDeviceType.values()) {
                valToType.put(type.getValue(), type);
            }
        }
        AODVDeviceType(byte id) { this.id = id; }

        byte getValue() { return id; }

        static AODVDeviceType valueOf(byte id) {
            return valToType.containsKey(id) ? valToType.get(id) : NONE;
        }
    }

    private static class AODVHeader implements Serializable {

        //shorts for compatibility with C version (Java doesn't have unsigned types...)
        //if these overflow there will be issues, shouldn't happen unless its running for a long time
        AODVMessageType type;
        short srcAddr; //origination of packet
        short destAddr; //final destination of packet
        short nextAddr;
        String nextId; //endpointId for routing
        short sendAddr;
        String sendId; //endpointId for routing, this will be set in onReceivedPayload;
        AODVDeviceType sendDevType;
        short srcSeqNum;
        short bcastSeqNum;
        short destSeqNum;
        byte hopCnt;
        short length; //length of payload

        AODVHeader() {
            this.type = AODVMessageType.NONE;
            this.srcAddr = 0;
            this.destAddr = 0;
            this.nextAddr = 0;
            this.nextId = null;
            this.sendAddr = 0;
            this.sendId = null;
            this.sendDevType = AODVDeviceType.AND;
            this.srcSeqNum = 0;
            this.bcastSeqNum = 0;
            this.destSeqNum = 0;
            this.hopCnt = 0;
            this.length = 0;
        }

    }

    private static class AODVMessage implements Serializable {

        AODVHeader header;
        String payload;

        AODVMessage() {
            this.header = new AODVHeader();
            this.payload = null;
        }

    }

    private static class AODVTxData {

        AODVMessage msg;
        long lifetime; //for expiration of message in data queue

        AODVTxData(AODVMessage msg) {
            this.msg = msg;
            this.lifetime = System.currentTimeMillis() + QUEUE_TIMEOUT;
        }

    }

    private static class AODVRoute {

        short address; //final destination of route
        String id;
        short nextHopAddr;
        String nextHopId; //for routing control
        short seqNum;
        short bcastSeqNum;
        byte hopCnt;
        long timeout;
        //final Object lock = new Object();

        AODVRoute() {
            this.address = 0;
            this.id = null;
            this.nextHopAddr = 0;
            this.nextHopId = null;
            this.seqNum = 0;
            this.bcastSeqNum = 0;
            this.hopCnt = 0;
            this.timeout = 0L;
        }

    }

    //map from Java AODVMessage to C tAODVPacket
    private byte[] serializeAODVPacket(AODVMessage msg) {
        byte[] bytes = null;
        ByteBuffer packet;
        try {
            packet = ByteBuffer.allocate(AODV_HEADER_SIZE + msg.header.length)
                    .put(msg.header.type.getValue())
                    .put((byte) 0) //placeholder for compatibility with tAODVPacket
                    .putShort(msg.header.srcAddr)
                    .putShort(msg.header.srcSeqNum)
                    .putShort(msg.header.destAddr)
                    .putShort(msg.header.destSeqNum)
                    .putShort(msg.header.nextAddr)
                    .putShort(msg.header.sendAddr)
                    .put(msg.header.sendDevType.getValue())
                    .putShort(msg.header.bcastSeqNum)
                    .put(msg.header.hopCnt)
                    .putShort(msg.header.length);
            if (msg.payload != null) {
                packet = packet.put(msg.payload.getBytes()); //specify encoding?
            }
            bytes = packet.array();
        } catch (BufferOverflowException e) {
            Log.d(TAG, "serializeAODVPacket: buffer overflow");

        }
        return bytes;
    }

    //map from C tAODVPacket to Java AODVMessage
    private AODVMessage deserializeAODVPacket(byte[] bytes) {
        ByteBuffer packet = ByteBuffer.wrap(bytes);
        AODVMessage msg = new AODVMessage();
        try {
            msg.header.type = AODVMessageType.valueOf(packet.get());
            packet.get(); //placeholder for compatibility with tAODVPacket
            msg.header.srcAddr = packet.getShort();
            msg.header.srcSeqNum = packet.getShort();
            msg.header.destAddr = packet.getShort();
            msg.header.destSeqNum = packet.getShort();
            msg.header.nextAddr = packet.getShort();
            msg.header.sendAddr = packet.getShort();
            msg.header.sendDevType = AODVDeviceType.valueOf(packet.get());
            msg.header.bcastSeqNum = packet.getShort();
            msg.header.hopCnt = packet.get();
            msg.header.length = packet.getShort();
            byte[] data = new byte[msg.header.length];
            packet.get(data, 0, data.length);
            msg.payload = new String(data); //decode this?
        } catch (BufferUnderflowException e) {
            msg = null;
            Log.d(TAG, "deserializeAODVPacket: buffer underflow");
        }
        return msg;
    }

    private void updateDevicesConnected() {
        int localSize = getLocalSize();
        String display = String.format(Locale.US, "Devices in local network: %d", localSize);
        numConnectedText.setText(display);
        updateRouteTableDisplay();
    }

    private void updateRouteTableDisplay() {
        String displayText = "Next Hops:\n";
        for (Map.Entry<Short, AODVRoute> e : routeTable.entrySet()) {
            displayText += e.getKey().toString() + " -> " + e.getValue().nextHopId + "\n";
        }

        synchronized (routeTableLock) {
            displayText += "\nLocals (UDP):\n";
            for (Map.Entry<Short, AODVRoute> e : udpNeighborsTable.entrySet()) {
                displayText += e.getKey().toString() + " -> " + e.getValue().nextHopId + "\n";
            }

            displayText += "\nLocals (CC):\n";
            for (Map.Entry<String, AODVRoute> e : ccNeighborsTable.entrySet()) {
                displayText += e.getKey() + "\n";
            }
        }

        this.tvRouteTable.setText(displayText);
    }

    private void updateLastMessageRx(short address, String message) {
        String display = String.format("%s: %s", address, message);
        lastMessageRx.setText(display);
    }

    private void showTrafficTotals() {
        Log.v("data", "Payload RX: " + this.totalPayloadRx);
        Log.v("data", "Payload TX: " + this.totalPayloadTx);
        Log.v("data", "Total RX: " + this.totalRx);
        Log.v("data", "Total TX: " + this.totalTx);

        return;
    }

    private final EndpointDiscoveryCallback endpointDiscoveryCallback = new EndpointDiscoveryCallback()
    {
        /**
         * Called when an endpoint is found. If the endpoint (device) isn't already in our
         * network, we temporarily stop discovery and send a connection request to the device.
         * Sometimes, this will fail if device A sends device B a request at the same time
         * device B sends device A a request (simultaneous connection clash).
         * If this is the case, one device will send the other device a connection request
         * again.
         * @param endpointId endpoint (device) that has been discovered
         * @param info some information about the device, such as name
         *
         * Add node to routing table
         * Forward node to neighbors if necessary
         */
        @Override
        public void onEndpointFound(@NonNull final String endpointId, @NonNull final DiscoveredEndpointInfo info) {
            if (!ccNeighborsTable.containsKey(endpointId)) {
                Log.d(TAG, "onEndpointFound: Connecting to " + endpointId);
                connectionsClient.requestConnection(String.valueOf(self.address),
                                                    endpointId,
                                                    connectionLifecycleCallback
                ).addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        //ConnectionsStatusCodes.STATUS_ENDPOINT_IO_ERROR;
                        Log.d(TAG, "onEndpointFound: Connection request failure " + e.getMessage());

                        // connection fails fairly often, sometimes after waiting a while it connects
                        // request connection again on one? of the devices
                        // 8012: STATUS_ENDPOINT_IO_ERROR is the simultaneous connection request error
                        if (e.getMessage().startsWith("8012") && self.address != Short.parseShort(info.getEndpointName())) {
                            //temporary solution to reduce the frequency of collisions
                            if (new Random().nextInt() % 2 == 0) {
                                Log.d(TAG, "onEndpointFound: Sending another connection request.");
                                connectionsClient.requestConnection(String.valueOf(self.address),
                                        endpointId, connectionLifecycleCallback);
                            }
                        }
                    }
                });
            } else {
                Log.d(TAG, "onEndpointFound: Endpoint is already a neighbor");
            }
        }

        @Override
        public void onEndpointLost(@NonNull String endpointId) {
            Log.d(TAG, "onEndpointLost: " + endpointId);
        }
    };

    private final ConnectionLifecycleCallback connectionLifecycleCallback = new ConnectionLifecycleCallback()
    {
        /**
         * Called when a connection request has been received. Reject the request if the
         * device is in the network, accept otherwise.
         * @param endpointId endpoint (device) that sent the request
         * @param connectionInfo some info about the device (e.g. name)
         */
        @Override
        public void onConnectionInitiated(@NonNull String endpointId, @NonNull ConnectionInfo connectionInfo) {
            if (!ccNeighborsTable.containsKey(endpointId)) {
                Log.d(TAG, "onConnectionInitiated: Accepting connection");
                connectionsClient.acceptConnection(endpointId, payloadCallback);
                //connectionInfo.getEndpointName(); //this could reduce need for some address fields / hello messages
            } else {
                Log.d(TAG, "onConnectionInitiated: Invalid endpoint");
            }
        }

        /**
         * Called after a connection request is accepted. If it was successful, verify again
         * that the device isn't already in the network. If it's already in the network, disconnect
         * from it. Else, officially add it as a node in the network
         * @param endpointId endpoint (device) that we just connected to
         * @param result contains status codes (e.g. success)
         */
        //add newly connected neighbor to neighbors table
        @Override
        public void onConnectionResult(@NonNull String endpointId, ConnectionResolution result) {
            if (result.getStatus().isSuccess()) {
                Log.i(TAG, "onConnectionResult: Connection successful");
                synchronized (routeTableLock) {
                    if (ccNeighborsTable.containsKey(endpointId)) {
                        //connectionsClient.disconnectFromEndpoint(endpointId);
                    } else if (ccNeighborsTable.size() < MAX_NEIGHBORS) {
                        AODVRoute newNeighbor = new AODVRoute();
                        newNeighbor.id = endpointId;
                        newNeighbor.nextHopId = endpointId;
                        ccNeighborsTable.put(endpointId, newNeighbor);
                        Log.d(TAG, "onConnectionResult: Neighbor added: " + endpointId);
                        updateDevicesConnected();
                    } else {
                        Log.d(TAG, "onConnectionResult: Too many neighbors: " + endpointId);
                    }
                }
            } else {
                Log.i(TAG, "onConnectionResult: Connection failed, retrying: " + endpointId);
                connectionsClient.requestConnection(String.valueOf(self.address), endpointId, connectionLifecycleCallback);
            }
        }

        //remove the disconnected neighbor from neighbors table
        @Override
        public void onDisconnected(@NonNull String endpointId) {
            synchronized (routeTableLock) {
                AODVRoute route = ccNeighborsTable.get(endpointId);
                if (route != null) {
                    removeRouteByAddress(route.address);
                    Log.i(TAG, "onDisconnected: disconnected from " + endpointId);
                } else {
                    Log.d(TAG, "onDisconnected: Failed to remove neighbor");
                }
            }
            Log.v("perf", "Disconnected from " + endpointId);
            updateDevicesConnected();
        }
    };

    private final PayloadCallback payloadCallback = new PayloadCallback()
    {
        /**
         * Handle incoming payloads. First, we deserialize the payload and determine
         * whether it is a set containing endpoint ids (new devices in the network)
         * or if it is a message from another device. Once we process the message/endpoints,
         * we forward the data to the rest of the network
         * @param endpointId The device who sent us the payload
         * @param payload the payload, which contains either a message or endpoints
         *
         */
        @Override
        public void onPayloadReceived(@NonNull String endpointId, Payload payload) {
            byte[] payloadBytes = payload.asBytes();
            totalRx += payloadBytes.length;
            try {
                Object deserialized = SerializationHelper.deserialize(payloadBytes);
                if (deserialized instanceof AODVMessage) {
                    AODVMessage msg = (AODVMessage) deserialized;
                    //this is the only place we can set the sender Id, which is needed for some control
                    msg.header.sendId = endpointId;
                    handleAODVQueue.add(msg);
                }
            } catch (IOException | ClassNotFoundException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onPayloadTransferUpdate(@NonNull String endpointId, PayloadTransferUpdate update) {
        }
    };
}
