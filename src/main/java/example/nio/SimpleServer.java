package example.nio;

import com.google.gson.Gson;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.util.Iterator;
import java.util.Set;

/**
 * Created with IntelliJ IDEA.
 * User: kejun
 * Date: 29/10/14
 * Time: 8:47 PM
 */
public class SimpleServer {
    public interface Handler {
        String respond(String requestMessage);
    }

    private static final String DEFAULT_CHARSET = "UTF-8";
    private static final int DEFAULT_BUFFER_SIZE = 8192;
    private final Charset charset;
    private final int bufferSize;
    private CharsetDecoder decoder;
    private CharsetEncoder encoder;
    private ServerThread runningThread;
    private Handler handler;
    private Thread.UncaughtExceptionHandler exceptionHandler;

    public SimpleServer(Handler handler) {
        this(handler, 0, null);
    }

    public SimpleServer(Handler handler, int bufferSize) {
        this(handler, bufferSize, null);
    }

    public SimpleServer(Handler handler, int bufferSize, String charsetName) {
        this.handler = handler;
        this.bufferSize = bufferSize > 0 ? bufferSize : DEFAULT_BUFFER_SIZE;
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

    private static class ServerThread extends Thread implements MessageParser.MessageProcessor {
        private ServerSocketChannel serverSocketChannel;
        private Selector selector;
        private boolean running;
        private int port;
        private ByteBuffer readBuf;
        private SimpleServer simpleServer;
        private StringBuilder requestStringBuilder;
        private static Gson gson = new Gson();
        private static MessageParser messageParser = new MessageParser();

        ServerThread(SimpleServer simpleServer, int port) throws IOException {
            this(simpleServer, port, simpleServer.bufferSize);
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
            serverSocketChannel.register(selector, serverInterests);

            requestStringBuilder = new StringBuilder();
        }

        public void kill() {
            running = false;
        }

        @Override
        public void run() {
            while (running) {
                int readyChannels = 0;
                try {
                    readyChannels = selector.select();
                } catch (IOException e) {
                    //failed to select
                    continue;
                }
                if (readyChannels == 0) continue;

                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    keyIterator.remove();

                    if(!key.isValid()) {
                        continue;
                    }

                    if (key.isAcceptable()) {
                        SocketChannel socketChannel = null;
                        try {
                            socketChannel = serverSocketChannel.accept();
                            socketChannel.configureBlocking(false);
                            SelectionKey clientKey = socketChannel.register(selector, SelectionKey.OP_READ);
                        } catch (IOException e) {
                            //Fail to connect client
                            e.printStackTrace();
                        }
                    } else if (key.isReadable()) {
                        System.out.println("Readable now");

                        SocketChannel socketChannel = (SocketChannel) key.channel();

                        requestStringBuilder.setLength(0);
                        while (true) {
                            readBuf.clear();
                            int bytesRead = 0;
                            try {
                                bytesRead = socketChannel.read(readBuf);
                            } catch (IOException e) {
                                //Failed to read channel
                                e.printStackTrace();
                            }
                            readBuf.flip();

                            String packet = null;
                            try {
                                packet = simpleServer.decoder.decode(readBuf).toString();
                            } catch (CharacterCodingException e) {
                                //Fail to decode request message
                                e.printStackTrace();

                                //TODO: should ack client the error

                                continue;
                            }

                            requestStringBuilder.append(packet);

                            if (bytesRead <= 0) {
                                break;
                            }
                        }

                        String msg = requestStringBuilder.toString();
                        if(!msg.isEmpty()) {
                            messageParser.parse(requestStringBuilder.toString(), socketChannel, this);
                        }
                    }
                }
            }
        }

        @Override
        public void processSuccess(Message message, SocketChannel socketChannel) {
            if (message != null) {
                message.body = simpleServer.handler.respond(message.body);
                String responseJson = gson.toJson(message);

                ByteBuffer buf = null;
                try {
                    buf = simpleServer.encoder.encode(CharBuffer.wrap(responseJson));
                    socketChannel.write(buf);
                } catch (CharacterCodingException e) {
                    //Fail to encode message
                    e.printStackTrace();
                } catch (IOException e) {
                    //Fail to write
                    e.printStackTrace();
                }
            } else {
                try {
                    socketChannel.close();
                } catch (IOException e) {
                    //Fail to close connection to client, ignore
                }
            }
        }

        @Override
        public void processFail(SocketChannel socketChannel, Exception e) {
//            try {
//                socketChannel.close();
//            } catch (IOException e1) {
//                //Fail to close connection to client, ignore
//            }
        }
    }
}

