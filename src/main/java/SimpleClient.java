package main.java;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.util.*;

public class SimpleClient {
    private static final String DEFAULT_CHARSET = "ISO-8859-1";
    private Charset charset;
    private CharsetDecoder decoder;
    private CharsetEncoder encoder;
    private ClientThread clientThread;
    private Thread.UncaughtExceptionHandler exceptionHandler;

    public SimpleClient() throws IOException {
        this(null);
    }

    public SimpleClient(String charsetName) throws IOException {
        String cs = charsetName == null ? DEFAULT_CHARSET : charsetName;
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

    public void connect(InetSocketAddress remoteAddress) throws IOException {
        clientThread.connect(remoteAddress);
    }

    public void sendMessage() {

    }

    /**
     * Close this client. After the client is closed, it can't be used any more.
     */
    public void close() {
        if (clientThread != null) {
            clientThread.running = false;
        }
        clientThread = null;
    }

    /**
     * After the client is closed, it can't be used any more.
     *
     * @return
     */
    public boolean isClosed() {
        return clientThread == null || !clientThread.running;
    }

    public void setExceptionHandler(Thread.UncaughtExceptionHandler exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
    }

    private static class ClientThread extends Thread {
        private boolean running;
        private Selector selector;
        private SimpleClient simpleClient;
        private Set<InetSocketAddress> pendingConnections;

        private ClientThread(SimpleClient simpleClient) throws IOException {
            pendingConnections = new HashSet<InetSocketAddress>();
            running = true;
            this.simpleClient = simpleClient;
            selector = Selector.open();
        }

        private void connect(InetSocketAddress remoteAddress) throws IOException {
            selector.wakeup();
            pendingConnections.add(remoteAddress);
        }

        private void clearPendingConnections() throws IOException {
            for(InetSocketAddress address : pendingConnections) {
                SocketChannel socketChannel = SocketChannel.open();
                socketChannel.configureBlocking(false);
                int interests = SelectionKey.OP_CONNECT | SelectionKey.OP_READ | SelectionKey.OP_WRITE;
                socketChannel.register(selector, interests, address);
                socketChannel.connect(address);
            }
            pendingConnections.clear();
        }

        @Override
        public void run() {
            long ts = System.currentTimeMillis();
            int count = 0;
            ByteBuffer byteBuffer = ByteBuffer.allocate(1024);
            long tsLastSend = 0;

            while (running) {
                try {
                    clearPendingConnections();

                    int readyChannels = selector.select();
                    if (readyChannels == 0) continue;

                    Set<SelectionKey> selectedKeys = selector.selectedKeys();
                    Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

                    while (keyIterator.hasNext()) {
                        SelectionKey key = keyIterator.next();
                        keyIterator.remove();

                        InetSocketAddress address = (InetSocketAddress) key.attachment();
                        SocketChannel socketChannel = (SocketChannel) key.channel();
                        if(key.isConnectable()) {
                            socketChannel.finishConnect();
                            continue;
                        }

                        if (key.isReadable()) {
                            System.out.print("Client reading: ");

                            byteBuffer.clear();
                            socketChannel.read(byteBuffer);
                            byteBuffer.flip();

                            String msg = simpleClient.decoder.decode(byteBuffer).toString();
                            System.out.print("Client received server msg: " + msg);

                            long elapsed = System.currentTimeMillis() - tsLastSend;

                            System.out.println();
                            System.out.println("=============Ping: " + elapsed + "=============");
                            System.out.println();
                        } else if (key.isWritable()) {
                            if (System.currentTimeMillis() - ts < 1000) {
                                continue;
                            }
                            ts = System.currentTimeMillis();

                            System.out.print("Client writing: ");

                            String msg = "Ping - " + ++count;
                            ByteBuffer buf = simpleClient.encoder.encode(CharBuffer.wrap(msg));

                            socketChannel.write(buf);

                            tsLastSend = System.currentTimeMillis();

                            System.out.print(msg);
                            System.out.println();
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }
}
