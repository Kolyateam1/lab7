package server;

import common.network.Request;
import common.network.Response;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;

public class ServerCore {
    private final int port;
    private final CollectionManager collectionManager;
    private final RequestHandler requestHandler;
    private final ForkJoinPool readPool;
    private volatile boolean running;

    private final Map<SocketChannel, ByteArrayOutputStream> pendingData;
    private final Map<SocketChannel, Integer> expectedLength;

    public ServerCore(int port) {
        this.port = port;
        this.collectionManager = new CollectionManager();
        this.requestHandler = new RequestHandler(collectionManager);
        this.readPool = new ForkJoinPool();
        this.running = true;
        this.pendingData = new ConcurrentHashMap<>();
        this.expectedLength = new ConcurrentHashMap<>();

        // Инициализация БД
        DatabaseManager.initTables();
    }

    public void start() {
        try (Selector selector = Selector.open();
             ServerSocketChannel serverChannel = ServerSocketChannel.open()) {

            serverChannel.bind(new InetSocketAddress(port));
            serverChannel.configureBlocking(false);
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);
            ServerLogger.info("Сервер запущен на порту " + port);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running = false;
                ServerLogger.info("Сервер завершает работу...");
                readPool.shutdown();
            }));

            while (running) {
                selector.select(1000); // таймаут, чтобы можно было проверить running

                Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                while (keys.hasNext()) {
                    SelectionKey key = keys.next();
                    keys.remove();

                    if (!key.isValid()) continue;

                    if (key.isAcceptable()) {
                        acceptConnection(key, selector);
                    } else if (key.isReadable()) {
                        SocketChannel client = (SocketChannel) key.channel();
                        key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
                        readPool.submit(new ReadTask(client, selector));
                    }
                }
            }
        } catch (IOException e) {
            ServerLogger.severe("Ошибка сервера: " + e.getMessage());
        }
    }

    private void acceptConnection(SelectionKey key, Selector selector) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel client = server.accept();
        client.configureBlocking(false);
        client.register(selector, SelectionKey.OP_READ);
        pendingData.put(client, new ByteArrayOutputStream());
        ServerLogger.info("Новое подключение: " + client.getRemoteAddress());
    }

    private class ReadTask extends RecursiveAction {
        private final SocketChannel client;
        private final Selector selector;
        private final byte[] remainingData;

        ReadTask(SocketChannel client, Selector selector) {
            this(client, selector, new byte[0]);
        }

        ReadTask(SocketChannel client, Selector selector, byte[] remaining) {
            this.client = client;
            this.selector = selector;
            this.remainingData = remaining;
        }

        @Override
        protected void compute() {
            ByteArrayOutputStream baos = pendingData.get(client);
            if (baos == null) return;

            try {
                if (remainingData.length > 0) {
                    baos.write(remainingData);
                }

                ByteBuffer buffer = ByteBuffer.allocate(8192);
                int bytesRead = client.read(buffer);

                if (bytesRead == -1) {
                    closeConnection(client);
                    return;
                }

                if (bytesRead > 0) {
                    buffer.flip();
                    byte[] data = new byte[buffer.remaining()];
                    buffer.get(data);
                    baos.write(data);
                }

                byte[] fullData = baos.toByteArray();
                ByteArrayInputStream bais = new ByteArrayInputStream(fullData);
                byte[] remaining = null;

                while (bais.available() > 0) {
                    Integer length = expectedLength.get(client);

                    if (length == null) {
                        if (bais.available() < 4) break;
                        byte[] lenBytes = new byte[4];
                        bais.read(lenBytes);
                        length = ByteBuffer.wrap(lenBytes).getInt();
                        expectedLength.put(client, length);
                    }

                    if (bais.available() < length) break;

                    byte[] msgBytes = new byte[length];
                    bais.read(msgBytes);

                    byte[] finalMsg = msgBytes.clone();
                    new Thread(() -> processRequest(client, finalMsg)).start();
                    expectedLength.remove(client);
                }

                remaining = new byte[bais.available()];
                bais.read(remaining);
                baos.reset();
                baos.write(remaining);

                SelectionKey key = client.keyFor(selector);
                if (key != null && client.isOpen()) {
                    key.interestOps(SelectionKey.OP_READ);
                }
                if (remaining.length > 0) {
                    ReadTask next = new ReadTask(client, selector, remaining);
                    next.fork();
                }

            } catch (IOException e) {
                ServerLogger.warning("Ошибка чтения: " + e.getMessage());
                closeConnection(client);
            }
        }
    }

    private void processRequest(SocketChannel client, byte[] data) {
        try {
            Request request = deserialize(data);
            if (request == null) {
                ServerLogger.warning("Не удалось десериализовать запрос");
                return;
            }

            ServerLogger.info("Получена команда: " + request.getCommand().getName() +
                    " от " + request.getLogin());

            Response response = requestHandler.handle(request);
            byte[] responseData = serialize(response);

            new Thread(() -> sendResponse(client, responseData)).start();

        } catch (Exception e) {
            ServerLogger.warning("Ошибка обработки запроса: " + e.getMessage());
        }
    }

    private void sendResponse(SocketChannel client, byte[] data) {
        try {
            ByteBuffer lengthBuffer = ByteBuffer.allocate(4);
            lengthBuffer.putInt(data.length);
            lengthBuffer.flip();
            client.write(lengthBuffer);

            ByteBuffer dataBuffer = ByteBuffer.wrap(data);
            client.write(dataBuffer);

            ServerLogger.info("Ответ отправлен клиенту");
        } catch (IOException e) {
            ServerLogger.warning("Ошибка отправки: " + e.getMessage());
            closeConnection(client);
        }
    }

    private void closeConnection(SocketChannel client) {
        try {
            pendingData.remove(client);
            expectedLength.remove(client);
            client.close();
            ServerLogger.info("Клиент отключился");
        } catch (IOException e) {
            ServerLogger.warning("Ошибка закрытия соединения");
        }
    }

    private byte[] serialize(Object obj) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(obj);
            oos.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            ServerLogger.severe("Ошибка сериализации: " + e.getMessage());
            return new byte[0];
        }
    }

    private Request deserialize(byte[] data) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(data);
             ObjectInputStream ois = new ObjectInputStream(bis)) {
            return (Request) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            ServerLogger.warning("Ошибка десериализации: " + e.getMessage());
            return null;
        }
    }
}