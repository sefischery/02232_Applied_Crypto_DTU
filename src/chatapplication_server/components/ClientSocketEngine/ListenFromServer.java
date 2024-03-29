/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package chatapplication_server.components.ClientSocketEngine;

import chatapplication_server.ComponentManager;
import java.io.IOException;
import java.io.ObjectInputStream;
import chatapplication_server.components.ConfigManager;
import crypto.cryptoManager;

/**
 *
 * @author atgianne
 */
public class ListenFromServer extends Thread 
{
    public void run()
    {
        while(true) {
                ObjectInputStream sInput = ClientEngine.getInstance().getStreamReader();
                
                synchronized( sInput )
                {
                    try
                    {
                        String msg = (String) sInput.readObject();
                        String userName = ConfigManager.getInstance().getValue("Client.Username");
                        System.out.println("THE  USER  CLIENT (ME) RECEIVED A MESSAGE!" + userName);
                        System.out.println("Received Encrypted text: " + msg);
                        /** Decrypt the incoming messages from the clients */
                        msg = cryptoManager.decrypt(msg, cryptoManager.AES_secret_client_key);
                        System.out.println(" I DECRYPTED: " + msg);

                        if(msg.contains( "#" ))
                        {
                            ClientSocketGUI.getInstance().appendPrivateChat(msg + "\n");
                        }
                        else
                        {
                            ClientSocketGUI.getInstance().append(msg + "\n");
                        }
                    }
                    catch(IOException e) 
                    {
                        ClientSocketGUI.getInstance().append( "Server has closed the connection: " + e.getMessage() +"\n" );
                        ComponentManager.getInstance().fatalException(e);
                    }
                    catch(ClassNotFoundException cfe) 
                    {
                        ClientSocketGUI.getInstance().append( "Server has closed the connection: " + cfe.getMessage() );
                        ComponentManager.getInstance().fatalException(cfe);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
        }
    }
}
