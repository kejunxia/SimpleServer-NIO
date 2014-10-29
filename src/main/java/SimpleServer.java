package main.java;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.util.Iterator;
import java.util.Set;

public class SimpleServer {
    public interface Handler{
        String respond(String requestMessage);
    }

    private static final String DEFAULT_CHARSET = "ISO-8859-1";
    private Charset charset;
    private CharsetDecoder decoder;
    private CharsetEncoder encoder;
    private ServerThread runningThread;
    private Handler handler;
    private Thread.UncaughtExceptionHandler exceptionHandler;

    public SimpleServer(Handler handler) {
        this(handler, null);
    }

    public SimpleServer(Handler handler, String charsetName) {
        this.handler = handler;
        String cs = charsetName == null ? DEFAULT_CHARSET : charsetName;
        charset = Charset.forName(cs);
        decoder = charset.newDecoder();
        encoder = charset.newEncoder();
    }

    public void start(int port) throws IOException {
        stop();
        runningThread = new ServerThread(this, port);
        runningThread.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                if (exceptionHandler != null) {
                    exceptionHandler.uncaughtException(t, e);
                } else {
                    e.printStackTrace();
                }
            }
        });

        runningThread.start();
    }

    public void stop() {
        if (runningThread != null) {
            runningThread.kill();
        }
    }

    public boolean isRunning() {
        return runningThread != null && runningThread.running;
    }

    public int getPort() {
        return runningThread == null ? 0 : runningThread.port;
    }

    public void setExceptionHandler(Thread.UncaughtExceptionHandler exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
    }

    private static class ServerThread extends Thread {
        private ServerSocketChannel serverSocketChannel;
        private Selector selector;
        private boolean running;
        private int port;
        private ByteBuffer readBuf;
        private SimpleServer simpleServer;
        private SelectionKey serverKey;
        private StringBuilder requestStringBuilder;

        ServerThread(SimpleServer simpleServer, int port) throws IOException {
            this(simpleServer, port, 8192);
        }

        ServerThread(SimpleServer simpleServer, int port, int bufferSize) throws IOException {
            this.simpleServer = simpleServer;
            this.port = port;
            running = true;
            readBuf = ByteBuffer.allocate(bufferSize);

            selector = Selector.open();

            serverSocketChannel = ServerSocketChannel.open();
            serverSocketChannel.configureBlocking(false);
            serverSocketChannel.socket().bind(new InetSocketAddress(port));

            int serverInterests = SelectionKey.OP_ACCEPT;
            serverKey = serverSocketChannel.register(selector, serverInterests);

            requestStringBuilder = new StringBuilder();
        }

        public void kill() {
            running = false;
        }

        @Override
        public void run() {
            while (running) {
                try {
                    int readyChannels = selector.select();
                    if (readyChannels == 0) continue;

                    Set<SelectionKey> selectedKeys = selector.selectedKeys();
                    Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

                    while (keyIterator.hasNext()) {
                        SelectionKey key = keyIterator.next();
                        keyIterator.remove();

                        if (key == serverKey) {
                            if (key.isAcceptable()) {
                                SocketChannel socketChannel = serverSocketChannel.accept();
                                socketChannel.configureBlocking(false);

                                SelectionKey clientKey = socketChannel.register(selector, SelectionKey.OP_READ);
                            }
                        } else {
                            if (key.isReadable()) {
                                SocketChannel socketChannel = (SocketChannel) key.channel();

                                requestStringBuilder.setLength(0);
                                while(true) {
                                    readBuf.clear();
                                    int bytesRead = socketChannel.read(readBuf);
                                    readBuf.flip();
                                    requestStringBuilder.append(simpleServer.decoder.decode(readBuf).toString());

                                    if(bytesRead <= 0) {
                                        break;
                                    }
                                }

                                String request = requestStringBuilder.toString();
                                String response = simpleServer.handler.respond(request);

                                ByteBuffer buf = simpleServer.encoder.encode(CharBuffer.wrap(response));
                                socketChannel.write(buf);
                            }
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }
}
