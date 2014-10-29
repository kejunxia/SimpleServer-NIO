package main.java;

import java.io.IOException;
import java.net.InetSocketAddress;

public class Main {
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

        try {
            SimpleClient client = new SimpleClient();
            client.connect(new InetSocketAddress("localhost", PORT));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
