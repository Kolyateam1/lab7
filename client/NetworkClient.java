package client;

import common.commands.*;
import common.network.Request;
import common.network.Response;
import client.utils.InputValidator;

import java.io.*;
import java.net.*;
import java.util.HashSet;
import java.util.Set;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class NetworkClient {
    private final String host;
    private final int port;
    private SocketChannel channel;
    private InputValidator validator;
    private CommandReader commandReader;
    private boolean running;
    private String currentLogin;
    private String currentPasswordHash;
    private final Set<String> scriptStack = new HashSet<>();

    private static final int TIMEOUT_MS = 5000;
    private static final int MAX_RECONNECT_ATTEMPTS = 3;
    private static final int RECONNECT_DELAY_MS = 2000;

    public NetworkClient(String host, int port) {
        this.host = host;
        this.port = port;
        this.validator = new InputValidator();
        this.commandReader = new CommandReader(validator);
        this.running = true;
    }

    public void start() {
        System.out.println("Подключение к серверу " + host + ":" + port);

        if (!connectAndAuthenticate()) {
            System.err.println("Не удалось подключиться или авторизоваться. Программа завершена.");
            return;
        }

        System.out.println("\n=== Добро пожаловать, " + currentLogin + "! ===");
        System.out.println("Введите 'help' для списка команд\n");

        while (running) {
            System.out.print("> ");
            String input = validator.readLine(null);
            if (input == null || input.trim().isEmpty()) continue;

            if (input.trim().equalsIgnoreCase("exit")) {
                System.out.println("До свидания!");
                break;
            }

            executeCommand(input.trim());
        }

        disconnect();
    }

    private boolean connectAndAuthenticate() {
        int attempts = 0;
        while (attempts < MAX_RECONNECT_ATTEMPTS) {
            try {
                connect();


                while (true) {
                    System.out.println("\n=== АВТОРИЗАЦИЯ ===");
                    System.out.println("1. Вход (login)");
                    System.out.println("2. Регистрация (register)");
                    System.out.print("Выберите действие (1/2): ");

                    String choice = validator.readLine(null);
                    if (choice == null) continue;

                    if (choice.equals("1")) {
                        String login = validator.readNonEmptyLine("Логин: ");
                        String password = validator.readNonEmptyLine("Пароль: ");
                        String hash = md5(password);

                        Command cmd = new LoginCommand(login, hash);
                        Request request = new Request(cmd, login, hash);

                        sendRequest(request);
                        Response response = receiveResponse();

                        if (response.isSuccess()) {
                            currentLogin = login;
                            currentPasswordHash = hash;
                            System.out.println("✓ " + response.getMessage());
                            return true;
                        } else {
                            System.out.println("✗ " + response.getMessage());
                        }
                    }
                    else if (choice.equals("2")) {
                        String login = validator.readNonEmptyLine("Новый логин: ");
                        String password = validator.readNonEmptyLine("Пароль: ");
                        String confirm = validator.readNonEmptyLine("Подтвердите пароль: ");

                        if (!password.equals(confirm)) {
                            System.out.println("✗ Пароли не совпадают!");
                            continue;
                        }

                        String hash = md5(password);
                        Command cmd = new RegisterCommand(login, hash);
                        Request request = new Request(cmd, login, hash);

                        sendRequest(request);
                        Response response = receiveResponse();

                        if (response.isSuccess()) {
                            System.out.println("✓ " + response.getMessage());
                        } else {
                            System.out.println("✗ " + response.getMessage());
                        }
                    }
                    else {
                        System.out.println("✗ Введите 1 или 2");
                    }
                }
            } catch (Exception e) {
                System.err.println("Ошибка: " + e.getMessage());
                attempts++;
                if (attempts < MAX_RECONNECT_ATTEMPTS) {
                    System.out.println("Повторная попытка через " + RECONNECT_DELAY_MS + " мс...");
                    sleep(RECONNECT_DELAY_MS);
                }
            }
        }
        return false;
    }

    private void connect() throws IOException {
        if (channel != null && channel.isOpen()) {
            try { channel.close(); } catch (IOException e) {}
        }

        channel = SocketChannel.open();
        channel.socket().connect(new InetSocketAddress(host, port), TIMEOUT_MS);
        channel.configureBlocking(true);
        channel.socket().setSoTimeout(TIMEOUT_MS);
    }

    private void disconnect() {
        try {
            if (channel != null) channel.close();
        } catch (IOException e) {}
        channel = null;
    }

    private void executeCommand(String input) {
        String[] parts = input.trim().split("\\s+", 2);
        String cmdName = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1] : "";

        if (cmdName.equals("execute_script")) {
            if (arg.isEmpty()) {
                System.out.println("Укажите имя файла");
                return;
            }
            executeScript(arg);
            return;
        }

        if (cmdName.equals("exit")) {
            System.out.println("До свидания!");
            running = false;
            return;
        }

        try {
            if (channel == null || !channel.isOpen() || !channel.isConnected()) {
                System.out.println("Соединение потеряно. Переподключение...");
                connect();
            }

            Command command = commandReader.readCommand(input);
            if (command == null) return;

            Request request = new Request(command, currentLogin, currentPasswordHash);
            sendRequest(request);

            Response response = receiveResponse();

            if (response.isSuccess()) {
                if (response.getData() != null) {
                    if (response.getData() instanceof java.util.List) {
                        java.util.List<?> list = (java.util.List<?>) response.getData();
                        if (list.isEmpty()) {
                            System.out.println("Коллекция пуста");
                        } else {
                            list.forEach(System.out::println);
                        }
                    } else {
                        System.out.println(response.getData());
                    }
                } else if (!response.getMessage().isEmpty()) {
                    System.out.println(response.getMessage());
                }
            } else {
                System.err.println("Ошибка: " + response.getMessage());
            }

        } catch (SocketTimeoutException e) {
            System.err.println("Таймаут соединения. Сервер не отвечает.");
        } catch (EOFException e) {
            System.err.println("Сервер закрыл соединение");
            running = false;
        } catch (IOException e) {
            System.err.println("Ошибка соединения: " + e.getMessage());
            System.out.println("Попробуйте выполнить команду позже.");
        } catch (ClassNotFoundException e) {
            System.err.println("Ошибка протокола: " + e.getMessage());
        }
    }

    private void sendRequest(Request request) throws IOException {
        byte[] data = serialize(request);
        ByteBuffer lengthBuffer = ByteBuffer.allocate(4);
        lengthBuffer.putInt(data.length);
        lengthBuffer.flip();
        channel.write(lengthBuffer);

        ByteBuffer dataBuffer = ByteBuffer.wrap(data);
        channel.write(dataBuffer);
    }

    private Response receiveResponse() throws IOException, ClassNotFoundException {
        ByteBuffer lengthBuffer = ByteBuffer.allocate(4);
        while (lengthBuffer.hasRemaining()) {
            if (channel.read(lengthBuffer) == -1) {
                throw new EOFException("Сервер закрыл соединение");
            }
        }
        lengthBuffer.flip();
        int length = lengthBuffer.getInt();

        if (length <= 0 || length > 10 * 1024 * 1024) {
            throw new IOException("Неверная длина ответа: " + length);
        }

        ByteBuffer dataBuffer = ByteBuffer.allocate(length);
        while (dataBuffer.hasRemaining()) {
            if (channel.read(dataBuffer) == -1) {
                throw new EOFException("Сервер закрыл соединение");
            }
        }

        return deserialize(dataBuffer.array());
    }

    private byte[] serialize(Object obj) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(obj);
            oos.flush();
            return bos.toByteArray();
        }
    }

    private Response deserialize(byte[] data) throws IOException, ClassNotFoundException {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(data);
             ObjectInputStream ois = new ObjectInputStream(bis)) {
            return (Response) ois.readObject();
        }
    }

    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 не поддерживается", e);
        }
    }

    private void executeScript(String filename) {
        File file = new File(filename);
        if (!file.exists()) {
            System.out.println("Файл не найден: " + filename);
            return;
        }

        if (scriptStack.contains(filename)) {
            System.out.println("Обнаружена рекурсия! Выполнение скрипта остановлено.");
            return;
        }

        scriptStack.add(filename);

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            int lineNum = 0;
            while ((line = br.readLine()) != null) {
                lineNum++;
                if (line.trim().isEmpty() || line.trim().startsWith("#")) continue;
                System.out.println("[" + filename + ":" + lineNum + "] " + line);
                executeCommand(line.trim());
            }
        } catch (IOException e) {
            System.err.println("Ошибка чтения скрипта: " + e.getMessage());
        } finally {
            scriptStack.remove(filename);
        }
    }

    private void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}