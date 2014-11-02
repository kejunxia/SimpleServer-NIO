package example.nio;

import java.io.IOException;

public class StartServer {
    static final int PORT = 9999;

    public static void main(String[] args) {
        SimpleServer simpleServer = new SimpleServer(new SimpleServer.Handler() {
            @Override
            public String respond(String requestMessage) {
                return "echo: " + requestMessage;
            }
        });
        try {
            simpleServer.start(PORT);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }
}
