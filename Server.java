import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.*;

/**
 * This class implements the server-side of the instant messaging application.
 * It opens a ServerSocket tied to a specified port and constantly listening
 * for incoming TCP connection request from clients. It also facilitates
 * data provision for clients that want to begin their own private P2P session.
 */
public class Server {
    /**
     * Constants for the server which are defined at startup
     */
    public static String CREDENTIAL_FILE = "credentials.txt";
    public static int BLOCK_DURATION;
    public static int TIMEOUT;

    /**
     * State variables
     */
    private List<String> activeClientNames;
    private List<String[]> credentials;
    private HashMap<String, User> users;

    /**
     * Creates a new server and loads the stored credentials for all users
     */
    public Server() {
        activeClientNames = new ArrayList<>();
        credentials = new ArrayList<>();
        users = new HashMap<>();

        // retrieve credential data
        File credentialFile = new File(Server.CREDENTIAL_FILE);
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(credentialFile));
            String line;
            while ((line = reader.readLine()) != null) {
                credentials.add(line.split(" "));
                users.put(line.split(" ")[0], new User());
            }
        } catch (IOException e) {
            //e.printStackTrace();
        }
    }

    /**
     * Adding a client entry to current collection of active handlers
     * @param client        the ClientHandler to be stored
     * @param username      the name of the user being handled by ClientHandler
     */
    public synchronized void addClient(ClientHandler client, String username) {
        activeClientNames.add(username);
        users.get(username).setThread(client);
        users.get(username).setOnline();
    }

    /**
     * Removes a client entry from the current collection of active handlers
     * @param username      the name of the user to be removed
     */
    public synchronized void removeClient(String username) {
        if (users.containsKey(username) && users.get(username).isOnline()) {
            activeClientNames.remove(username);
            users.get(username).setThread(null);
            users.get(username).setOffline();
        }
    }

    /**
     * @return the list of user credentials
     */
    public List<String[]> getCredentials() {
        return credentials;
    }

    /**
     * @return a mapping of usernames to User structures
     */
    public HashMap<String, User> getUser() {
        return users;
    }

    /**
     * @return the list of users currently online
     */
    public List<String> getActiveUsers() {
        return activeClientNames;
    }

    /**
     * @param username     name of the target user to set the block time of
     */
    public synchronized void setBlockTime(String username) {
        long startTime = System.currentTimeMillis();
        long endTime = startTime + Server.BLOCK_DURATION*1000;
        users.get(username).setBlockTime(endTime);
    }

    public static void main(String[] args) throws Exception {
        // retrieve input parameters for the server
        if (args.length != 3) {
            System.out.println("Required arguments: server_port block_duration timeout");
            return;
        }

        BLOCK_DURATION = Integer.parseInt(args[1]);
        TIMEOUT = Integer.parseInt(args[2]);

        // set up the server's socket
        int serverPort = Integer.parseInt(args[0]);
        ServerSocket serverSocket = new ServerSocket(serverPort);

        Server server = new Server();

        // have server constantly run
        try {
            while (true) {
                // constantly listen for TCP connection requests and accept them
                Socket clientSocket = serverSocket.accept();
                ClientHandler handler = new ClientHandler(server, clientSocket);
                handler.start();
            }
        } catch (SocketException e) {
            //e.printStackTrace();
            serverSocket.close();
        }
    }
}

/**
 * This class implements handling of clients on a separate thread. This
 * includes authentication handling, message redirecting between users,
 * timeout handling among other functions.
 */
class ClientHandler extends Thread {
    /**
     * State variables
     */
    private Socket socket;
    private String username;
    private DataInputStream inputStream;
    private DataOutputStream outputStream;
    private Server server;
    private Timer timeoutTimer;

    /**
     * Initialises a new ClientHandler to facilitate communication between the
     * server and a client.
     * @param server    the currently running server
     * @param socket    the socket connecting the server to a client to be handled
     */
    public ClientHandler (Server server, Socket socket) {
        this.server = server;
        this.socket = socket;
        this.username = null;
        try {
            this.outputStream = new DataOutputStream(socket.getOutputStream());
            this.inputStream = new DataInputStream(socket.getInputStream());
        } catch (IOException e) {
            //e.printStackTrace();
        }
    }

    /**
     * Handles user login and then constantly listens for incoming messages
     * from the user and processes each request.
     */
    public void run() {
        try {
            // user authentication phase
            if (loginUser()) {
                server.addClient(this, username);
                sendBroadcast(username + " logged in", username);
                startTimeoutTimer();
            } else {
                close();
                return;
            }

            // once user has logged in, load up their message queue to check
            // for messages sent to them when offline
            if (!server.getUser().get(username).isMessageQueueEmpty()) {
                sendMessage(server.getUser().get(username).readMessageQueue());
            }

            // constantly check for incoming requests
            while (!Thread.currentThread().isInterrupted()) {
                String data = readMessage();
                if (data.isEmpty()) continue;
                String[] splitData = data.split(" ");
                switch (splitData[0]) {
                    case "logout":
                        if (data.equals("logout")) {
                            logoutUser();
                        } else {
                            sendMessage("Invalid command. Use format: logout");
                        }
                        break;
                    case "whoelse":
                        if (data.equals("whoelse")) {
                            displayActiveUsersSince(-1);
                        } else {
                            sendMessage("Invalid command. Use format: whoelse");
                        }
                        break;
                    case "whoelsesince":
                        // length 2 for whoelsesince and the time
                        if (splitData.length == 2 && isNumeric(splitData[1])) {
                            displayActiveUsersSince(Integer.parseInt(splitData[1]));
                        } else {
                            sendMessage("Invalid command. Use format: whoelsesince <time>");
                        }
                        break;
                    case "broadcast":
                        // length 2 for broadcast and at least 1 word message
                        if (splitData.length >= 2) {
                            String message = username + ":" + data.substring(splitData[0].length());
                            if (!sendBroadcast(message, username)) {
                                sendMessage("Your message could not be delivered to some recipients");
                            }
                        } else {
                            sendMessage("Invalid command. Use format: broadcast <message>");
                        }
                        break;
                    case "message":
                        // length 3 for message, user and at least 1 word message
                        if (splitData.length >= 3) {
                            String user = splitData[1];
                            if (user.equals(username)) {
                                sendMessage("Error: You can't message yourself");
                            } else if (server.getUser().containsKey(user)) {
                                List<String> messageData = Arrays.asList(splitData).subList(2, splitData.length);
                                String message = username + ": " + String.join(" ", messageData);
                                if (!sendMessageToUser(message, username, user)) {
                                    sendMessage("Your message could not be delivered as the recipient has blocked you");
                                }
                            } else {
                                sendMessage("Error: Invalid user");
                            }
                        } else {
                            sendMessage("Invalid command. User format: message <user> <message>");
                        }
                        break;
                    case "block":
                        // length 2 for block and the user's name
                        if (splitData.length == 2) {
                            String user = splitData[1];
                            User currUser = server.getUser().get(username);
                            if (user.equals(username)) {
                                sendMessage("Error: Cannot block yourself");
                            } else if (!server.getUser().containsKey(user)) {
                                sendMessage("Error: invalid user");
                            } else if (currUser.hasBlacklisted(user)) {
                                sendMessage(user + " is already blocked");
                            } else {
                                currUser.addToBlacklist(user);
                                sendMessage(user + " is blocked");
                            }
                        } else {
                            sendMessage("Invalid command. User format: block <user>");
                        }
                        break;
                    case "unblock":
                        // length 2 for unblock and the user's name
                        if (splitData.length == 2) {
                            String user = splitData[1];
                            User currUser = server.getUser().get(username);
                            if (user.equals(username)) {
                                sendMessage("Error: You are not blocked");
                            } else if (!server.getUser().containsKey(user)) {
                                sendMessage("Error: invalid user");
                            } else if (!currUser.hasBlacklisted(user)) {
                                sendMessage(user + " was not blocked");
                            } else {
                                currUser.removeFromBlacklist(user);
                                sendMessage(user + " is unblocked");
                            }
                        } else {
                            sendMessage("Invalid command. User format: unblock <user>");
                        }
                        break;
                    case "setupprivate":
                        // length 2 for setupprivate and the user's name
                        if (splitData.length == 2) {
                            String user = splitData[1];
                            if (user.equals(username)) {
                                // notify user they are trying to start P2P with self
                                sendMessage("startprivate,self");
                            } else if (server.getUser().containsKey(user)) {
                                User userData = server.getUser().get(user);
                                if (userData.hasBlacklisted(username)) {
                                    sendMessage("startprivate,blocked");
                                } else if (userData.isOnline()) {
                                    // target client's listening socket for P2P will have the same
                                    // address and port as the socket used for communication with
                                    // this server. This is allowed since TCP sockets are defined
                                    // but source address/port as well as destination
                                    String ipAddress = userData.getSocket().getInetAddress().getHostAddress();
                                    int port = userData.getSocket().getPort();
                                    // send port and address data back to the user, so they can
                                    // start a P2P session
                                    String[] message = {"startprivate", user, ipAddress, Integer.toString(port)};
                                    sendMessage(String.join(",", message));
                                } else {
                                    // notify that target user is offline
                                    sendMessage("startprivate,offline");
                                }
                            } else {
                                // notify that the target user doesn't exist
                                sendMessage("startprivate,invalid");
                            }
                        }
                        break;
                    default:
                        sendMessage("That is not a valid command.");
                }
                restartTimer();
            }
        } catch(Exception e) {
            //e.printStackTrace();
            close();
        }
    }

    /**
     * Handles user login.
     * This includes username/password authentication and blocking.
     * @return whether or not the user was successfully logged in
     */
    private boolean loginUser() {
        boolean loggedIn = false;
        short attemptsLeft = 3;
        boolean usernameEntered = false;
        String username = "";
        String expectedPassword = "";

        // request a username to be entered and verify it is valid
        while (!usernameEntered) {
            sendMessage("Username: ");
            username = readMessage();
            if (username.isEmpty()) continue;
            for (String[] credential : server.getCredentials()) {
                if (username.equals(credential[0])) {
                    usernameEntered = true;
                    // keep record of the password for later
                    expectedPassword = credential[1];
                    break;
                }
            }

            if (usernameEntered) {
                long userTimeout = server.getUser().getOrDefault(username, new User()).getBlockTime();
                if (server.getActiveUsers().contains(username)) {
                    sendMessage("This user is already logged in.");
                    usernameEntered = false;
                } else if (System.currentTimeMillis() < userTimeout) {
                    sendMessage("Your account is blocked due to multiple login failures. Please try again later.");
                    usernameEntered = false;
                }
            } else {
                sendMessage("This username doesn't exist. Please try again");
            }
        }

        // handle password checking for the entered username
        // the user is given 3 sequential attempts
        while (attemptsLeft > 0) {
            sendMessage("Password: ");
            String password = readMessage();
            if (password.isEmpty()) continue;
            if (password.equals(expectedPassword)) {
                this.username = username;
                loggedIn = true;
                break;
            } else {
                if (--attemptsLeft > 0)
                    sendMessage("Invalid password. Please try again.");
            }
        }

        // check for 3 failed attempts
        if (attemptsLeft == 0) {
            sendMessage("Invalid password. Your account has been blocked. Please try again later.");
            server.setBlockTime(username);
        }

        // login success message
        if (loggedIn) {
            sendMessage("Successfully logged in! Welcome to the chat server!");
            // send the username of the user logged in to the client as a form
            // of "handshaking" or confirmation
            sendMessage("name " + username);
        }

        return loggedIn;
    }

    /**
     * Logs the user out of their current session and closes the thread
     */
    public void logoutUser() {
        sendMessage("logout");
        sendBroadcast(username + " logged out", username);
        close();
    }

    /**
     * Sends a UTF string message to the user
     * @param message 	the message to send to the user
     */
    public void sendMessage(String message) {
        try {
            outputStream.writeUTF(message);
        } catch (IOException e) {
            //e.printStackTrace();
            close();
        }
    }

    /**
     * @return the incoming message sent from the user to the server
     */
    public String readMessage() {
        String message = "";
        try {
            message = inputStream.readUTF();
        } catch (IOException e) {
            //e.printStackTrace();
            close();
        }
        return message;
    }

    /**
     * Sends a broadcast message to all online users
     * @param message 		the message to broadcast to online users
     * @param senderName 	the name of the user sending the broadcast
     * @return whether or not the broadcast message was successfully send
    to all users
     */
    public boolean sendBroadcast(String message, String senderName) {
        List<String> users = new ArrayList<>(server.getActiveUsers());
        users.remove(senderName);
        int numMsgToSend = users.size();
        for (String username : users) {
            numMsgToSend -= (sendMessageToUser(message, senderName, username) ? 1 : 0);
        }
        return (numMsgToSend == 0);
    }

    /**
     * Sends a message from this user to another
     * @param message 			the message to be sent
     * @param sender 			the name of the user sending the message
     * @param receiverName 		the name of the user to send the message to
     * @return whether or not the messages was successfully sent to the user
     */
    public boolean sendMessageToUser(String message, String sender, String receiverName) {
        User user = server.getUser().get(receiverName);
        if (!user.hasBlacklisted(sender)) {
            if (user.isOnline()) {
                ClientHandler handler = user.getThread();
                handler.sendMessage(message);
            } else {
                user.addToMessageQueue(message);
            }
            return true;
        } else {
            return false;
        }
    }

    /**
     * Sends a message to the user with a list of all the users that have been
     * active since a given time
     * @param secondsBack 	the time to check whether users have been active since
     */
    public void displayActiveUsersSince(long secondsBack) {
        List<String> users;
        // negative parameter means show all active users
        if (secondsBack < 0) {
            users = new ArrayList<>(server.getActiveUsers());
            users.remove(username);
        } else {
            // otherwise we account for the specified "time since"
            long time = System.currentTimeMillis() / 1000 - secondsBack;
            users = new ArrayList<>(server.getUser().keySet());
            users.remove(username);
            for (String name : server.getUser().keySet()) {
                User user = server.getUser().get(name);
                if (!user.isOnline() && user.getLastOnline() < time) {
                    users.remove(name);
                }
            }
        }
        sendMessage(String.join("\n", users));
    }

    /**
     * @return the name of the user this handler is handling
     */
    public String getUsername() {
        return this.username;
    }

    /**
     * Starts a timer for the duration of the server specified timeout
     * duration. Once the time is over, the user is logged out.
     */
    public void startTimeoutTimer() {
        TimerTask timerTask = new TimerTask() {
            @Override
            public void run() {
                sendMessage("Your session has timed out and you have been logged out.");
                logoutUser();
            }
        };
        timeoutTimer = new Timer();
        timeoutTimer.schedule(timerTask, server.TIMEOUT * 1000);
    }

    /**
     * Restarts the timer for when to logout a user due to inactivity.
     * This function is called after the user sends a message to the server.
     */
    public void restartTimer() {
        timeoutTimer.cancel();
        startTimeoutTimer();
    }

    /**
     * @return the socket this handler is handling
     */
    public Socket getSocket() {
        return this.socket;
    }

    /**
     * Clean up the ClientHandler.
     * Closes socket and its stream threads.
     * Also removes the client and interrupts the thread
     */
    public void close() {
        try {
            server.removeClient(username);
            inputStream.close();
            outputStream.close();
            socket.close();
            Thread.currentThread().interrupt();

        } catch (IOException e) {
            //e.printStackTrace();
        }
    }

    /**
     * @param number    the "number" string to check
     * @return whether or not the string is actually a number
     */
    private boolean isNumeric(String number) {
        try {
            int num = Integer.parseInt(number);
        } catch (NumberFormatException | NullPointerException e) {
            return false;
        }
        return true;
    }
}

/**
 * This class is used for handling and tracking the state of a user
 */
class User {
    /**
     * This user's state variables
     */
    private long blockTime;
    private HashSet<String> blacklist;
    private ClientHandler thread;
    private boolean isOnline;
    private long lastOnline;
    private List<String> messageQueue;

    /**
     * Initialises a new User with dummy data
     */
    public User() {
        this.blacklist = new HashSet<>();
        this.isOnline = false;
        this.blockTime = -1;
        this.thread = null;
        this.lastOnline	= System.currentTimeMillis() / 1000;
        this.messageQueue = new ArrayList<>();
    }

    /**
     * @return the block time for 3 incorrect password attempts
     */
    public long getBlockTime() {
        return blockTime;
    }

    /**
     * @param blockTime     the block time for 3 incorrect passwords
     *                      as specified at server startup
     */
    public synchronized void setBlockTime(long blockTime) {
        this.blockTime = blockTime;
    }

    /**
     * @param user      the user to enquire about
     * @return whether or not the current user has blacklisted the specified user
     */
    public boolean hasBlacklisted(String user) {
        return blacklist.contains(user);
    }

    /**
     * @param user      the user to be added to the current user's blacklist
     */
    public synchronized void addToBlacklist(String user) {
        blacklist.add(user);
    }

    /**
     * @param user      the user to be removed from the current user's blacklist
     */
    public synchronized void removeFromBlacklist(String user) {
        blacklist.remove(user);
    }

    /**
     * @return the thread responsible for handling messages to/from this user
     */
    public ClientHandler getThread() {
        return thread;
    }

    /**
     * @param thread    the thread responsible for handling messages to/from this user
     */
    public synchronized void setThread(ClientHandler thread) {
        this.thread = thread;
    }

    /**
     * @return whether or not this user is currently online
     */
    public boolean isOnline() {
        return isOnline;
    }

    /**
     * Set this user's status to online
     */
    public synchronized void setOnline() {
        isOnline = true;
        setLastOnline();
    }

    /**
     * Set this user's status to offline
     */
    public synchronized void setOffline() {
        isOnline = false;
        setLastOnline();
    }

    /**
     * @return the time this user was last online
     */
    public long getLastOnline() {
        return lastOnline;
    }

    /**
     * Updates the time this user was last online (current time)
     */
    public synchronized void setLastOnline() {
        this.lastOnline	= System.currentTimeMillis() / 1000;
    }

    /**
     * @param message   the message to be added to this user's offline messaging queue
     */
    public synchronized void addToMessageQueue(String message) {
        messageQueue.add(message);
    }

    /**
     * @return the messages sent to this user while they were offline
     */
    public synchronized String readMessageQueue() {
        String messages = String.join("\n", messageQueue);
        messageQueue.clear();
        return messages;
    }

    /**
     * @return whether or not the offline message queue for this user is empty
     */
    public boolean isMessageQueueEmpty() {
        return messageQueue.isEmpty();
    }

    /**
     * @return the socket the server uses to communicate with this user
     */
    public Socket getSocket() {
        return thread.getSocket();
    }
}