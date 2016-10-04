package com.dx168.bizsocket.tcp;

import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.util.Collection;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArrayList;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;

/**
 * Creates a socket connection to a tcp server.
 */
public abstract class SocketConnection implements Connection, ReconnectionManager.PreReConnect {
    public static final int DEFAULT_HEART_BEAT_INTERVAL = 30000;

    /**
     * A collection of ConnectionListeners which listen for connection closing
     * and reconnection events.
     */
    protected final Collection<ConnectionListener> connectionListeners = new CopyOnWriteArrayList<ConnectionListener>();
    protected final Collection<PacketListener> packetListeners = new CopyOnWriteArrayList<PacketListener>();
    protected final PacketFactory packetFactory;

    private Socket socket;
    private String host;
    private int port;
    private BufferedSource reader;
    private BufferedSink writer;

    private PacketWriter packetWriter;
    private PacketReader packetReader;
    private Timer timer;
    private int heartbeat = DEFAULT_HEART_BEAT_INTERVAL;//心跳间隔
    private ReconnectionManager reconnectionManager;

    public SocketConnection() {
        this(null,0);
    }

    public SocketConnection(String host, int port) {
        this.host = host;
        this.port = port;

        packetFactory = createPacketFactory();
    }

    @Override
    public void connect() throws Exception {
        disconnect();
        socket = createSocket(host,port);

        initConnection();

        onSocketConnected();
        callConnectionListenerConnected();

        if (packetFactory.supportHeartBeat()) {
            startHeartBeat();
        }
    }

    public boolean connectAndStartWatch() {
        try {
            bindReconnectionManager();
            connect();
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }

        return true;
    }

    @Override
    public void disconnect() {
        try {
            if (packetReader != null) {
                packetReader.shutdown();
            }
        } catch (Throwable e) {
            //e.printStackTrace();
        }
        try {
            if (packetWriter != null) {
                packetWriter.shutdown();
            }
        } catch (Throwable e) {
            //e.printStackTrace();
        }
        stopHeartBeat();
        if (socket != null && !isSocketClosed()) {
            if (socket != null && !isSocketClosed()) {
                try {
                    socket.close();
                } catch (Exception e) {
                    //e.printStackTrace();
                }
                try {
                    socket.shutdownInput();
                } catch (Exception e) {
                    //e.printStackTrace();
                }

                socket = null;
            }

            for (ConnectionListener connectionListener : connectionListeners) {
                connectionListener.connectionClosed();
            }
        }
    }

    @Override
    public boolean isConnected() {
        return !isSocketClosed();
    }

    protected abstract PacketFactory createPacketFactory();

    public PacketFactory getPacketFactory() {
        return packetFactory;
    }

    public boolean isSocketClosed() {
        return socket == null || socket.isClosed() || !socket.isConnected();
    }

    public BufferedSource getReader() {
        return reader;
    }

    public BufferedSink getWriter() {
        return writer;
    }

    public void addConnectionListener(ConnectionListener connectionListener) {
        if (connectionListeners.contains(connectionListener)) {
            return;
        }
        this.connectionListeners.add(connectionListener);
    }

    public void removeConnectionListener(ConnectionListener connectionListener) {
        this.connectionListeners.remove(connectionListener);
    }

    public void addPacketListener(PacketListener packetListener) {
        if (packetListeners.contains(packetListener)) {
            return;
        }
        packetListeners.add(packetListener);
    }

    public void removePacketListener(PacketListener packetListener) {
        packetListeners.remove(packetListener);
    }

    public void setHostAddress(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void setHeartbeat(int heartbeat) {
        this.heartbeat = heartbeat;
    }

    public void reconnect() throws Exception {
        connect();
    }

    public void bindReconnectionManager() {
        if (reconnectionManager == null) {
            reconnectionManager = new ReconnectionManager();
            reconnectionManager.bind(this);
        }
        reconnectionManager.setPreReConnect(this);
    }

    public void unbindReconnectionManager() {
        if (reconnectionManager != null) {
            reconnectionManager.bind(this);
        }
    }

    protected Socket createSocket(String host, int port) throws Exception {
        Socket socket = new Socket(host, port);
        socket.setKeepAlive(true);
        socket.setTcpNoDelay(true);
        return socket;
    }

    private void notifyConnectException(Exception exception) {
        for (ConnectionListener connectionListener : connectionListeners) {
            connectionListener.connectionClosedOnError(exception);
        }
    }

    private void initReaderAndWriter() {
        try {
            reader = Okio.buffer(Okio.source(socket.getInputStream()));
            writer = Okio.buffer(Okio.sink(socket.getOutputStream()));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void initConnection() {
        if (isSocketClosed()) {
            return;
        }
        boolean isFirstInitialization = packetReader == null || packetWriter == null;

        initReaderAndWriter();
        if (isFirstInitialization) {
            this.packetWriter = new PacketWriter(this);
            this.packetReader = new PacketReader(this);
        }
        this.packetWriter.setWriter(writer);
        this.packetReader.setReader(reader);
        this.packetWriter.startup();
        this.packetReader.startup();
    }

    public void sendPacket(Packet packet) {
        if (isSocketClosed()) {
            return;
        }
        packetWriter.sendPacket(packet);
    }

    public void startHeartBeat() {
        stopHeartBeat();
        TimerTask timerTask = new TimerTask() {
            @Override
            public void run() {
                sendPacket(packetFactory.buildHeartBeatPacket());
            }
        };
        timer = new Timer();
        timer.scheduleAtFixedRate(timerTask, 0, heartbeat);
    }

    private void stopHeartBeat() {
        if (null != timer) {
            timer.cancel();
        }
    }

    public void handleReadWriteError(Exception e) {
        if ((e instanceof SocketException) || (e instanceof EOFException)) {
            notifyConnectionError(e);
        }
    }

    void notifyConnectionError(Exception exception) {
        if (!isSocketClosed()){
            stopHeartBeat();
            packetReader.shutdown();
            packetWriter.shutdown();

            // Notify connection listeners of the error.
            for (ConnectionListener connectionListener : connectionListeners) {
                connectionListener.connectionClosedOnError(exception);
            }
        }
    }

    private void callConnectionListenerConnected() {
        for (ConnectionListener connectionListener : connectionListeners) {
            connectionListener.connected(this);
        }
    }

    @Override
    public void doPreReConnect(SocketConnection connection) {
        try {
            connection.reconnect();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    protected void onSocketConnected() {

    }

    /**
     * call this method when packet send successful
     * @param packet
     */
    void notifySendSuccessful(Packet packet) {
        for (PacketListener packetListener : packetListeners) {
            packetListener.onSendSuccessful(packet);
        }
    }

    void handlerReceivedPacket(Packet packet) {
        for (PacketListener packetListener : packetListeners) {
            packetListener.processPacket(packet);
        }
    }

    public void clearWriteQuote() {
        if (packetWriter != null) {
            packetWriter.clearQuoue();
        }
    }
}