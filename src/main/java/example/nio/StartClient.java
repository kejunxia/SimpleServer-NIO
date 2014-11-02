package example.nio;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;

/**
 * Created by kejun on 29/10/14.
 */
public class StartClient {
    static SimpleClient client;

    static {
        try {
            client = new SimpleClient();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        ConsoleThread thread = new ConsoleThread();
        thread.start();
    }

    static class ConsoleThread extends Thread {
        final Connection connection;

        ConsoleThread() {
            InetSocketAddress address = new InetSocketAddress("localhost", StartServer.PORT);
            connection = new Connection(address) {
                @Override
                public void onConnected(InetSocketAddress remoteAddress) {
                    System.out.println("Client connected to " + remoteAddress.toString());
                    System.out.println();
                }

                @Override
                public void onConnectFailed(Exception e) {
                    System.out.println(e.getMessage());
                    e.printStackTrace();
                }

                @Override
                public void onConnectionLost(Exception e) {
                    System.out.println(e.getMessage());
                    e.printStackTrace();
                }

                @Override
                public void onConnectionHeartbeatEchoed() {
//                    System.out.println("Heartbeat echo from " + getRemoteAddress());
                }
            };

            try {
                client.connect(connection);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private void input() {
            System.out.print(String.format("Chat to %s: ", connection.getRemoteAddress()));
            BufferedReader buffer = new BufferedReader(new InputStreamReader(System.in));
            String msg = null;
            try {
                msg = buffer.readLine();
            } catch (IOException e) {
//                e.printStackTrace();
            }

            connection.sendMessage(msg, new Response() {
                @Override
                public void success(Message message) {
                    System.out.println("I got: " + message.body);
                    System.out.println();
                }

                @Override
                public void fail(Exception e) {

                }
            });
        }

        @Override
        public void run() {
            while(true) {
                input();
            }
        }
    }
}
