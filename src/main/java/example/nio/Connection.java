package example.nio;

import com.google.gson.Gson;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public abstract class Connection implements MessageParser.MessageProcessor{
    private static final int DEFAULT_HEARTBEAT_INTERVAL = 0;

    private Queue<Request> requestQueue;
    private long lastHeartbeatTs = 0;
    private long idCounter = 0;
    private Map<Long, Request> requestsInWait;
    private final int heartbeatInterval;
    private StringBuilder stringBuilder;
    private InetSocketAddress remoteAddress;
    private SocketChannel socketChannel;
    private static MessageParser messageParser = new MessageParser();

    public Connection(InetSocketAddress remoteAddress) {
        this(remoteAddress, 0);
    }

    public Connection(InetSocketAddress remoteAddress, int heartbeatInterval) {
        this.remoteAddress = remoteAddress;
        this.requestQueue = new ConcurrentLinkedQueue<>();
        this.requestsInWait = new ConcurrentHashMap<>();
        this.heartbeatInterval = heartbeatInterval > 0 ? heartbeatInterval : DEFAULT_HEARTBEAT_INTERVAL;
        this.stringBuilder = new StringBuilder();
    }

    public InetSocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    public abstract void onConnected(InetSocketAddress remoteAddress);

    public abstract void onConnectFailed(Exception e);

    public abstract void onConnectionLost(Exception e);

    public void onConnectionHeartbeatEchoed(){}

    /**
     * Disconnect this connection only when the connection {@link #isConnected()}
     * @return false when the operation is ignored because the the connection is currently not
     * {@link #isConnected()}, otherwise true.
     * @throws java.io.IOException
     */
    public boolean disconnect() throws IOException {
        if(!isConnected()) {
            return false;
        }

        try {
            socketChannel.close();
        } finally {
            socketChannel = null;
        }
        return true;
    }

    /**
     * Send message only when the connection {@link #isConnected()}
     * @param msg
     * @param response
     * @return false when the message is ignored because the the connection is currently not
     * {@link #isConnected()}, otherwise true.
     */
    public boolean sendMessage(String msg, Response response) {
        boolean isConnected = isConnected();
        if(isConnected) {
            requestQueue.offer(createRequest(msg, response));
        }
        return isConnected;
    }

    /**
     * Indicate whether or not the connection is connected.
     * @return
     */
    public boolean isConnected() {
        return socketChannel != null && socketChannel.isConnected();
    }

    void notifyConnected(SocketChannel socketChannel) {
        this.socketChannel = socketChannel;
        lastHeartbeatTs = System.currentTimeMillis();
    }

    /**
     * Try to send heartbeat if the connection is required to keep sending heartbeat
     */
    void sendHeartbeat() {
        //If connection requires heartbeat test, send heartbeat request
        if (heartbeatInterval > 0) {
            if (System.currentTimeMillis() - lastHeartbeatTs >= heartbeatInterval) {
                sendMessage("", new Response() {
                    @Override
                    public void success(Message message) {
                        onConnectionHeartbeatEchoed();
                    }

                    @Override
                    public void fail(Exception e) {
                        handleConnectionError(e);
                    }
                });

                lastHeartbeatTs = System.currentTimeMillis();
            }
        }
    }

    /**
     *
     * @param charsetDecoder
     * @param byteBuffer
     * @return whether or not reading is successful
     * @throws java.io.IOException
     */
    void read(CharsetDecoder charsetDecoder, ByteBuffer byteBuffer) throws IOException {
        int bytesRead = 0;

        //reset string builder
        stringBuilder.setLength(0);

        while(true) {
            byteBuffer.clear();
            try {
                bytesRead = socketChannel.read(byteBuffer);
            } catch (IOException e) {
                handleConnectionError(e);
                throw e;
            }
            byteBuffer.flip();

            try {
                stringBuilder.append(charsetDecoder.decode(byteBuffer).toString());
            } catch (CharacterCodingException e) {
                e.printStackTrace();
            }

            if(bytesRead <= 0) {
                break;
            }
        }

        String msgJson = stringBuilder.toString();
        messageParser.parse(msgJson, socketChannel, this);
    }

    @Override
    public void processSuccess(Message message, SocketChannel socketChannel) {
        Request req = requestsInWait.remove(message.id);
        message.responseTs = System.currentTimeMillis();
        req.response.success(message);
    }

    @Override
    public void processFail(SocketChannel socketChannel, Exception e) {
//        try {
//            socketChannel.close();
//        } catch (IOException e1) {
//            //Fail to close connection to client, ignore
//        }
    }

    /**
     * Wend all queued messages to connected server
     * @param charsetEncoder
     * @param gson
     * @return whether or not there are IOException occurred during writing.
     */
    boolean write(CharsetEncoder charsetEncoder, Gson gson) {
        boolean errorOccurred = false;
        for(Request req : requestQueue) {
            String envelope = gson.toJson(req.message);
            ByteBuffer buf = null;
            try {
                buf = charsetEncoder.encode(CharBuffer.wrap(envelope));
            } catch (CharacterCodingException e) {
                e.printStackTrace();
            }
            try {
                socketChannel.write(buf);
                requestsInWait.put(req.message.id, req);
            } catch (IOException e) {
                handleConnectionError(e);

                errorOccurred = true;
            }
        }

        //clear request queue
        requestQueue.clear();

        return !errorOccurred;
    }

    private void handleConnectionError(Exception e) {
        String error = String.format("Connection to %s lost", remoteAddress);
        IOException ioException = e == null ? new IOException(error) : new IOException(error, e);
        onConnectionLost(ioException);

        try {
            socketChannel.close();
        } catch (IOException e1) {
            //ignore
        } finally {
            socketChannel = null;
        }
    }

    private Request createRequest(String body, Response response) {
        Message message = new Message();
        message.id = idCounter++;
        message.body = body;
        message.requestTs = System.currentTimeMillis();
        message.responseTs = 0;

        Request request = new Request();
        request.message = message;
        request.response = response;

        return request;
    }
}