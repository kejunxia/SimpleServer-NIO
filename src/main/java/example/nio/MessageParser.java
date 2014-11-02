package example.nio;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.nio.channels.SocketChannel;

/**
 * Created by kejun on 31/10/14.
 */
public class MessageParser {
    private static Gson gson = new Gson();

    interface MessageProcessor {
        void processSuccess(Message message, SocketChannel socketChannel);
        void processFail(SocketChannel socketChannel, Exception e);
    }

    void parse(String input, SocketChannel socketChannel, MessageProcessor messageProcessor) {
        String[] requests = input.split("}");

        for(String request : requests) {
            if(request != null && !request.isEmpty()) {
                try {
                    Message message = gson.fromJson(request + "}", Message.class);
                    messageProcessor.processSuccess(message, socketChannel);
                } catch (JsonSyntaxException e) {
                    System.out.println("Failed to parse json message: " + request + "}");

                    messageProcessor.processFail(socketChannel, e);
                }
            }
        }
    }


}
