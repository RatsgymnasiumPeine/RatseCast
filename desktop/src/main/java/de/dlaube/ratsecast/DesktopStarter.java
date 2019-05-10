package de.dlaube.ratsecast;

import java.awt.*;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class DesktopStarter {
    public static void main(String[] args) throws IOException {
        DesktopNativeInterface desktopNativeInterface = new DesktopNativeInterface();

        int port = 5902;
        ServerSocket serverSocket;
        serverSocket = new ServerSocket(port);
        
        while(true){
            /*
             * Wait and accept new client.
             */
            
            Socket client = serverSocket.accept();
            
            /*
             * Create new object for each client.
             */
            RFBService rfbService = new RFBService(client, desktopNativeInterface);
            /*
             * Handle new client session in separate thread.
             */
            (new Thread(rfbService, "RFBService")).start();

        }
    }
}
