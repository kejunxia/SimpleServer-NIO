package example.nio;

public interface Response {
    void success(Message message);
    void fail(Exception e);
}