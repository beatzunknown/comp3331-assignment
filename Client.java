import java.io.IOException;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;

/**
 * This class implements the client-side of the instant messaging application.
 * It opens a socket to connect with a specified server, while also keeping a
 * ServerSocket open that constantly listens for P2P messaging requests.
 */
public class Client {
    /**
     * State variables for storing P2P sessions and current user
     */
    private HashMap<String, PrivateHandler> privateSessions;
    private String username;

    /**
     * Creates a client and initalises its state.
     */
    public Client() {
        this.privateSessions = new HashMap<>();
        this.username = "";
    }

    /**
     * @return the HashMap of P2P sessions
     */
    public HashMap<String, PrivateHandler> getPrivateSessions() {
        return privateSessions;
    }

    /**
     * Keeps track of a new P2P session
     * @param user      the username of the other user in the P2P connection
     * @param handler   the handler responsible for managing that user's connection
     */
    public synchronized void addPrivateSession(String user, PrivateHandler handler) {
        privateSessions.put(user, handler);
    }

    /**
     * @param user      the username of the other user in the P2P connection
     */
    public synchronized void removePrivateSession(String user) {
        if (privateSessions.containsKey(user)) {
            privateSessions.remove(user);
            System.out.println("Private messaging with " + user + " has stopped");
        }
    }

    /**
     * @param username      the username belonging to this client
     */
    public void setUsername(String username) {
        this.username = username;
    }

    /**
     * @return the username of this client
     */
    public String getUsername() {
        return this.username;
    }

    public static void main(String[] args) throws Exception {
        // retrieve input for the client
        if (args.length != 2) {
            System.out.println("Required arguments: server_IP server_port");
            return;
        }
        String serverIP = args[0];
        int serverPort = Integer.parseInt(args[1]);

        Client client = new Client();
        ServerSocket serverSocket = null;

        try {
            // initialise the socket connecting to the server, and the socket streams
            Socket socket = new Socket(serverIP, serverPort);
            DataOutputStream outputStream = new DataOutputStream(socket.getOutputStream());
            DataInputStream inputStream = new DataInputStream(socket.getInputStream());

            // initialise socket receiver and sender threads, and start them
            MessageReceiver receiver = new MessageReceiver(client, inputStream, outputStream, socket);
            MessageSender sender = new MessageSender(client, inputStream, outputStream, socket);
            receiver.start();
            sender.start();

            // initialise ServerSocket for P2P connections
            serverSocket = new ServerSocket();
            serverSocket.bind(new InetSocketAddress(socket.getLocalAddress(), socket.getLocalPort()));

            // constantly listen for incomming P2P requests
            while (true) {
                Socket incoming = serverSocket.accept();
                DataOutputStream out = new DataOutputStream(incoming.getOutputStream());
                DataInputStream in = new DataInputStream(incoming.getInputStream());
                PrivateHandler handler = new PrivateHandler(client, in, out, incoming, "");
                handler.start();
            }

        } catch (IOException e) {
            //e.printStackTrace();
            serverSocket.close();
        }
    }
}

/**
 * This parent handler class provides common variables needed for message handling
 * as well as stream/socket closing.
 */
class MessageHandler extends Thread {
    /**
     * State variables
     */
    protected DataInputStream inputStream;
    protected DataOutputStream outputStream;
    protected Socket socket;
    protected Client client;

    /**
     * Initialises a new MessageHandler
     * @param client    instance of the Client
     * @param dis       the input stream associated with the socket
     * @param dos       the output stream associated with the socket
     * @param socket    the socket attached to the client, connected to the server
     */
    public MessageHandler(Client client, DataInputStream dis, DataOutputStream dos, Socket socket) {
        this.inputStream = dis;
        this.outputStream = dos;
        this.socket = socket;
        this.client = client;
    }

    /**
     * Closes input/output streams, the client's socket and interrupts the
     * thread this handler is running on.
     */
    public void close() {
        try {
            inputStream.close();
            outputStream.close();
            socket.close();
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            //e.printStackTrace();
        }
    }
}

/**
 * This class implements message sending capability on top of general handling
 */
class MessageSender extends MessageHandler {
    /**
     * Variable to attaining keyboard input
     */
    private Scanner scanner;

    /**
     * Initialises a message sender that sends user entered data
     * @param client    instance of the Client
     * @param dis       the input stream associated with the socket
     * @param dos       the output stream associated with the socket
     * @param socket    the socket attached to the client, connected to the server
     */
    public MessageSender(Client client, DataInputStream dis, DataOutputStream dos, Socket socket) {
        super(client, dis, dos, socket);
        this.scanner = new Scanner(System.in);
    }

    /**
     * Constantly checks for keyboard input from the user.
     * Once entered the input is interpreted and necessary functions are
     * performed, otherwise the data is just sent to the server.
     */
    public void run() {
        try {
            while (true) {
                String toSend = scanner.nextLine();
                if (!handlePrivateMessage(toSend)) {
                    // only send data to the server, if it isn't related to P2P
                    outputStream.writeUTF(toSend);
                    if (toSend.equals("logout")) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            //e.printStackTrace();
            close();
        }
    }

    /**
     * Checks if the user-written message was to start/stop a P2P session or
     * to send a private message. If so, the necessary connections are made.
     * @param message   the message inputted by the current user (Client)
     * @return True if the message was related to P2P messaging, else False
     */
    public boolean handlePrivateMessage(String message) {
        String[] splitMsg = message.split(" ");
        try {
            switch (splitMsg[0]) {
                case "startprivate":
                    if (splitMsg.length == 2) {
                        if (client.getPrivateSessions().containsKey(splitMsg[1])) {
                            System.out.println("Private session with this user is already active.");
                        } else {
                            // contact the server to retrieve port and address details
                            // of the user to start P2P with
                            outputStream.writeUTF("setupprivate" + " " + splitMsg[1]);
                        }
                    } else {
                        System.out.println("Invalid command. Use format: startprivate <user>");
                    }
                    break;
                case "private":
                    // minimum length 3 for private, user and at least 1 word message
                    if (splitMsg.length >= 3) {
                        String user = splitMsg[1];
                        // P2P session must exist to send a message
                        if (!client.getPrivateSessions().containsKey(user)) {
                            System.out.println("Error: Private messaging to " + user + " is not enabled");
                        } else {
                            // build the message (starts at index 2 of split message)
                            List<String> messageData = Arrays.asList(splitMsg).subList(2, splitMsg.length);
                            String msg = client.getUsername() + " (private): " + String.join(" ", messageData);
                            // send using the P2P handler for that user
                            client.getPrivateSessions().get(user).sendMessage(msg);
                        }
                    } else {
                        System.out.println("Invalid command. Use format: private <user> <message>");
                    }
                    break;
                case "stopprivate":
                    if (splitMsg.length == 2) {
                        String user = splitMsg[1];
                        // if the current client has a P2P session active with that user
                        // then close it
                        if (!client.getPrivateSessions().containsKey(user)) {
                            System.out.println("Error: Private messaging to " + user + " is not enabled");
                        } else {
                            client.getPrivateSessions().get(user).close();
                        }
                    } else {
                        System.out.println("Invalid command. Use format: stopprivate <user>");
                    }
                    break;
                default:
                    return false;
            }
        } catch (IOException e) {
            //e.printStackTrace();
            return false;
        }
        return true;
    }
}

/**
 * This class implements message receiving on top of basic handling.
 */
class MessageReceiver extends MessageHandler {
    /**
     * Initialises a message receiver that receives messages from the server
     * @param client    instance of the Client
     * @param dis       the input stream associated with the socket
     * @param dos       the output stream associated with the socket
     * @param socket    the socket attached to the client, connected to the server
     */
    public MessageReceiver(Client client, DataInputStream dis, DataOutputStream dos, Socket socket) {
        super(client, dis, dos, socket);
    }

    /**
     * Constantly checks for incoming data from the server.
     * Upon reception the data is interpreted.
     * The necessary functions are executed and/or messages
     * are displayed to the user.
     */
    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                String toDisplay = inputStream.readUTF();
                String[] splitMsg = toDisplay.split(" ");
                // edge case for authentication (after successful login)
                if (splitMsg[0].equals("name") && splitMsg.length == 2) {
                    client.setUsername(splitMsg[1]);
                } else if (!handlePrivateMessage(toDisplay)) {
                    // edge case to not double print "logout" after user types it
                    if (!toDisplay.equals("logout")) System.out.println(toDisplay);
                    // handle inactivity timeout message and timeout
                    if (toDisplay.equals("Your session has timed out and you have been logged out.") || toDisplay.equals("logout")) {
                        close();
                        System.exit(0);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            //e.printStackTrace();
            close();
            System.exit(0);
        }
    }

    /**
     * Checks if the server has responded with port and address information
     * of a specified user to initialise a P2P connection with the user.
     * @param message   the message received by the server
     * @return True if the message was related to P2P initialisation, else False
     */
    public boolean handlePrivateMessage(String message) {
        String[] splitMsg = message.split(",");
        if (splitMsg[0].equals("startprivate")) {
            // length 4 for startprivate, name, address, port
            if (splitMsg.length == 4) {
                try {
                    // connect to the user on a new socket
                    Socket newSocket = new Socket(splitMsg[2], Integer.parseInt(splitMsg[3]));
                    DataOutputStream out = new DataOutputStream(newSocket.getOutputStream());
                    DataInputStream in = new DataInputStream(newSocket.getInputStream());

                    // initiate and start a new PrivateHandler for the P2P session
                    PrivateHandler receiver = new PrivateHandler(client, in, out, newSocket, splitMsg[1]);
                    receiver.start();
                } catch (IOException e) {
                    //e.printStackTrace();
                }
            } else if (splitMsg.length == 2) {
                switch (splitMsg[1]) {
                    case "offline":
                        System.out.println("Error: this user is offline");
                        break;
                    case "invalid":
                        System.out.println("Error: this user doesn't exist");
                        break;
                    case "self":
                        System.out.println("Error: can't start private messaging with yourself");
                        break;
                    case "blocked":
                        System.out.println("Error: this user has blocked you");
                        break;
                }
            }
            return true;
        }
        return false;
    }
}

/**
 * This class implements handling of private P2P messaging
 */
class PrivateHandler extends MessageReceiver {
    /**
     * Username of the other connected client
     */
    private String username;

    /**
     * Initialises a message sender that sends user entered data
     * @param client    instance of the Client
     * @param dis       the input stream associated with the socket
     * @param dos       the output stream associated with the socket
     * @param socket    the socket attached to the client, connected to the server
     * @param username  the username of the other client in the P2P connection
     */
    public PrivateHandler(Client client, DataInputStream dis, DataOutputStream dos, Socket socket, String username) {
        super(client, dis, dos, socket);
        this.username = username;
    }

    /**
     * This function initially handles connection setup which involves
     * the exchanging over usernames between the two peers in the session.
     * Then it constantly checks for incoming messages from the other peer
     * to display to the user.
     */
    @Override
    public void run() {
        try {
            String otherUser = "";
            // empty username means private request is incoming
            if (username.isEmpty()) {
                otherUser = inputStream.readUTF();
                this.username = otherUser;
                outputStream.writeUTF(client.getUsername());
            } else {
                // otherwise this client sent the outgoing request
                outputStream.writeUTF(client.getUsername());
                otherUser = inputStream.readUTF();
                if (otherUser.equals(username)) {
                    System.out.println("Started private messaging with " + otherUser);
                } else {
                    close();
                    return;
                }
            }

            client.addPrivateSession(otherUser, this);

            while (!Thread.currentThread().isInterrupted()) {
                String toDisplay = inputStream.readUTF();
                System.out.println(toDisplay);
            }
        } catch (IOException e) {
            //e.printStackTrace();
            close();
        }
    }

    /**
     * This function closes the streams/sockets as handled by the parent
     * class.
     * Additionally the record of this current P2P session is removed and
     * the thread is interrupted to stop this session.
     */
    @Override
    public void close() {
        super.close();
        client.removePrivateSession(username);
        Thread.currentThread().interrupt();
    }

    /**
     * @param message   the message to send to the other peer
     */
    public void sendMessage(String message) {
        try {
            outputStream.writeUTF(message);
        } catch (IOException e) {
            //e.printStackTrace();
            close();
        }
    }
}