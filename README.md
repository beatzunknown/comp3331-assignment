# COMP3331 Client-Server Messaging System

## About
The aim was to develop a client and server for an instant messaging system that operated on top of TCP. The clients are also able to initialise P2P private messaging with other clients that bypass the server.

## Compilation
Compile the client with
```
javac Client.java
```
and compile the server with
```
javac Server.java
```

## Execution
Note that fields in angled brackets are variables to be set by yourself. Description of parameters:
- server_port: the port you wish to run the server on
- server_IP: the IP address of the machine the server is running on
- block_duration: the time (in seconds) where a user should be prevent from logging in, after 3 failed attempts
- timeout: the time (in seconds) which a user must be inactive for before they are automatically logged out

Executing the server:
```
java Server <server_port> <block_duration (seconds)> <timeout (seconds)>
```
Executing the client:
```
java Client <server_IP> <server_port>
```
