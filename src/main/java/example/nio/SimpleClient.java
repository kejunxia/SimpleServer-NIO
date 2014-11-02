package example.nio;

import com.google.gson.Gson;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created with IntelliJ IDEA.
 * User: kejun
 * Date: 29/10/14
 * Time: 8:51 PM
 */
public class SimpleClient {
    private static final String DEFAULT_CHARSET = "UTF-8";
    private static final int DEFAULT_BUFFER_SIZE = 8192;
    private final Charset charset;
    private final int bufferSize;
    private CharsetDecoder decoder;
    private CharsetEncoder encoder;
    private ClientThread clientThread;
    private Thread.UncaughtExceptionHandler exceptionHandler;

    public SimpleClient() throws IOException {
        this(0, null);
    }

    public SimpleClient(int bufferSize) throws IOException {
        this(bufferSize, null);
    }

    public SimpleClient(int bufferSize, String charsetName) throws IOException {
        String cs = charsetName == null ? DEFAULT_CHARSET : charsetName;
        this.bufferSize = bufferSize > 0 ? bufferSize : DEFAULT_BUFFER_SIZE;
        charset = Charset.forName(cs);
        decoder = charset.newDecoder();
        encoder = charset.newEncoder();

        clientThread = new ClientThread(this);
        clientThread.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                if (exceptionHandler != null) {
                    exceptionHandler.uncaughtException(t, e);
                } else {
                    e.printStackTrace();
                }
            }
        });
        clientThread.start();
    }

    public void connect(Connection connection) throws IOException {
        if (clientThread.connections.containsKey(connection.getRemoteAddress())) {
            throw new ConnectException("The connection has already been established.");
        }
        clientThread.connect(connection);
    }

    /**
     * Shutdown this client. After the client is closed, it can't be used any more.
     */
    public void shutdown() {
        if (clientThread != null) {
            clientThread.shutdown();
            clientThread.running = false;
        }

        clientThread = null;
    }

    /**
     * After the client is shutdown, it can't be used any more.
     *
     * @return
     */
    public boolean isShutdown() {
        return clientThread == null || !clientThread.running;
    }

    public void setExceptionHandler(Thread.UncaughtExceptionHandler exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
    }

    private static class ClientThread extends Thread {

        private boolean running;
        private Selector selector;
        private SimpleClient simpleClient;
        private Set<Connection> pendingConnections;
        private Map<InetSocketAddress, Connection> connections;
        private static Gson gson = new Gson();

        private ClientThread(SimpleClient simpleClient) throws IOException {
            pendingConnections = new HashSet<Connection>();
            connections = new ConcurrentHashMap<>();
            running = true;
            this.simpleClient = simpleClient;
            selector = Selector.open();
        }

        void shutdown() {
            for(Connection connection : connections.values()) {
                try {
                    connection.disconnect();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        void connect(Connection connection) throws IOException {
            selector.wakeup();
            pendingConnections.add(connection);
        }

        void sendMessage(InetSocketAddress remoteAddress, String msg, Response response) {
            if (connections.containsKey(remoteAddress)) {
                Connection connection = connections.get(remoteAddress);
                connection.sendMessage(msg, response);
            } else {
                response.fail(new IOException("Connection has lost."));
            }
        }

        private void clearPendingConnections() {
            for (Connection connection : pendingConnections) {
                SocketChannel socketChannel = null;
                try {
                    //open socket
                    socketChannel = SocketChannel.open();
                    socketChannel.configureBlocking(false);
                } catch (IOException e) {
                    e.printStackTrace();
                }

                try {
                    //connect to the socket
                    socketChannel.connect(connection.getRemoteAddress());
                    socketChannel.register(selector, SelectionKey.OP_CONNECT, connection);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            pendingConnections.clear();
        }

        @Override
        public void run() {
            ByteBuffer byteBuffer = ByteBuffer.allocate(simpleClient.bufferSize);
            while (running) {
                for (final Connection connection : connections.values()) {
                    connection.sendHeartbeat();
                    continue;
                }

                clearPendingConnections();

                int readyChannels = 0;
                try {
                    readyChannels = selector.select();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                if (readyChannels == 0) continue;

                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    keyIterator.remove();

                    SocketChannel socketChannel = (SocketChannel) key.channel();
                    Connection connection = (Connection) key.attachment();

                    if (key.isConnectable()) {
                        boolean connected = false;
                        if (socketChannel.isConnectionPending()) {
                            try {
                                connected = socketChannel.finishConnect();
                            } catch (Exception e) {
                                try {
                                    socketChannel.close();
                                } catch (IOException e1) {
                                    //ignore
                                }
                                connection.onConnectFailed(e);
                            }
                        }

                        if (connected) {
                            connection.notifyConnected(socketChannel);

                            int interests = SelectionKey.OP_READ | SelectionKey.OP_WRITE;
                            try {
                                socketChannel.register(selector, interests, connection);
                                connections.put(connection.getRemoteAddress(), connection);

                                connection.onConnected(connection.getRemoteAddress());
                            } catch (ClosedChannelException e) {
                                e.printStackTrace();
                            }
                        }

                        continue;
                    }

                    if (key.isReadable()) {
                        try {
                            connection.read(simpleClient.decoder, byteBuffer);
                        } catch (IOException e) {
                            connections.remove(connection.getRemoteAddress());
                        }
                    } else if (key.isWritable()) {
                        boolean successful = connection.write(simpleClient.encoder, gson);
                        if (!successful) {
                            connections.remove(connection.getRemoteAddress());
                        }
                    }
                }
            }
        }
    }
}
