package chatapplication_server.components.ServerSocketEngine;

import SocketActionMessages.ChatMessage;
import chatapplication_server.components.ConfigManager;
import chatapplication_server.statistics.ServerStatistics;
import crypto.cryptoManager;
import javax.crypto.*;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OptionalDataException;
import java.io.StreamCorruptedException;
import java.net.*;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Vector;

import static crypto.cryptoManager.*;

/**
 *
 * @author atgianne
 */
public class SocketConnectionHandler implements Runnable 
{
    /** Did we receive a signal to shut down */
    protected boolean mustShutdown;
    
    /** Flag for indicating whether we are handling a socket or not */
    protected boolean isSocketOpen;
    
    /** The socket connection that we are handling */
    private Socket handleConnection;
    
     /** String identifier of this ConnectionHandler thread (since we have more than 1 in the ConnectionHandling pool) */
    private String handlerName;
    
    /** The username of the client that we are handling */
    private String userName;
    
    /** The only type of message that we will receive */
    private ChatMessage cm;
    
     /** Instance of the ConfigManager component */
    ConfigManager configManager;
    
    /** Object for keeping track in the logging stream of the actions performed in this socket connection */
    ServerStatistics connectionStat;
    
    /** Socket Stream reader/writer that will be used throughout the whole connection... */
    private ObjectOutputStream socketWriter;
    private ObjectInputStream socketReader;
    
    /**
     * Creates a new instance of SocketConnectionHandler
     */
    public SocketConnectionHandler() 
    {        
        /** Get the running instance of the Configuration Manager component */
        configManager = ConfigManager.getInstance();
        
        /** Auxiliary object for printing purposes */
        connectionStat = new ServerStatistics();
                
        /** Initialize the mustShutdown flag... */
        mustShutdown = false;
        
        /** Initialize the isSocketOpen flag, the Handler and the sensor type identifiers... */
        isSocketOpen = false;
        handlerName = null;
        
        /** Initialize the socket connection */
        handleConnection = null;
        
        /** Initialize the socket connection stream reader/writer... */
        socketWriter = null;
        socketReader = null;
    }
    
    /**
     * Method for printing some information about the socket connection that this ConnectionHandler thread is
     * accommodating.
     */
    public void printSocketInfo()
    {
        /** Check to see if there is a connection... */
        if ( handleConnection != null )
        {
            /** If it is not closed... */
            if ( !handleConnection.isClosed() )
            {
                /** Print some auxiliary information... */
                SocketServerGUI.getInstance().appendEvent("\n----------[" + handlerName + "]:: Configuration properties of assigned socket connection----------\n" );
                SocketServerGUI.getInstance().appendEvent( "Remote Address:= " + handleConnection.getInetAddress().toString() + "\n" );
                SocketServerGUI.getInstance().appendEvent( "Remote Port:= " + handleConnection.getPort() + "\n" );
                SocketServerGUI.getInstance().appendEvent( "Client UserName:= " + userName + "\n" );
                SocketServerGUI.getInstance().appendEvent( "Local Socket Address:= " + handleConnection.getLocalSocketAddress().toString() + "\n" );
            }
        }
    }
    
     /**
     * Method for setting the socket connection that this SocketConnectionHandler thread object will handle.
     * We must also set the stream reader/writer of the assigned socket connection.
     * Finally, we must notify it to wake up;as it was in an idle state (in the ConnectionHandling pool) waiting for a
     * new connection to be assigned.
     * 
     * IMPORTANT NOTE It must run in a synchronized block
     * 
     * @param s A reference to the newly established socket connection that this SocketConnectionHandler will handle
     */
    synchronized void setSocketConnection( Socket s ) throws Exception {
        /** Set the isSocketOpen flag to true... */
        isSocketOpen = true;
        
        /** Assign the socket connection to this Connection Handler */
        handleConnection = s;
        
        /** Print to the logging stream that this SSLConnectionHandler is assigned to this socket connection... */
       SocketServerGUI.getInstance().appendEvent( "[SSEngine]:: " + handlerName + " assigned to socket (" + handleConnection.getRemoteSocketAddress() + ") (" + connectionStat.getCurrentDate() + ")\n" );

        /** If the socket's stream writer/reader are set up correctly...then notify the thread to start working */
        if ( setSocketStreamReaderWriter() )
        {
            System.out.println("SOCKET SET UP - DONE - NOTIFYING LOCAL THREAD");
            /** Notify the local thread to wake up */
            notify();
        }
    }
    
    /**
     * Method for setting up the stream reader/writer of the assigned to us secure socket connection between the
     * chat clients and the server.
     *
     * @return TRUE If the set up was successful; FALSE otherwise
     */
    public boolean setSocketStreamReaderWriter() throws Exception {
        try
        {
            /** Set up the stream reader/writer for this socket connection... */
            socketWriter = new ObjectOutputStream( handleConnection.getOutputStream() );
            socketReader = new ObjectInputStream( handleConnection.getInputStream() );

            /** First the server sends the certificate to the client*/
            java.security.cert.Certificate ServerCert;
            try {
                /** Extract the server certificate from his JKS*/
                ServerCert = ExtractCertFromJKS(cryptoManager.ServerKeyStore, cryptoManager.ServerKeyStorePass,
                        cryptoManager.Serveralias);
                /** Extract the public key from the extracted certificate*/
                ServerPubKey_ServSide = ExtractPubKeyFromCert(ServerCert);
                System.out.println("Sent Server Cert to Client!");
                /** Transmit the certificate to the socket*/
                cryptoManager.SendCert(ServerCert, socketWriter);
            } catch (KeyStoreException | CertificateException | NoSuchAlgorithmException e) {
                e.printStackTrace();
            }
            /** Then the server receives the certificate from the client*/
            java.security.cert.Certificate ClientCert = cryptoManager.ReceiveCert(socketReader);
            System.out.println("<<<<<<<<<<<<<<<<Client Cert Received>>>>>>>>>>>>>>>>>>");
            System.out.println(ClientCert);
            System.out.println("<<<<<<<<<<<<<<<<END Cert Received END>>>>>>>>>>>>>>>>>>");
            /** Verify that the certificate was signed by the trusted CA!*/
            // System.out.println("ROOT CA -> -> -> -> " + Base64.getEncoder().encodeToString(RootCAPubKey.getEncoded()));
            /** Verify that the received client certificate was signed using the private key corresponding to the publickey of the rootca*/
            cryptoManager.VerifyCert(ClientCert, RootCAPubKey); //Will throw exception if not verified!

            /** Extract the client's public key from the cert */
            PublicKey clientPublicKey = ExtractPubKeyFromCert(ClientCert);
            /** Generate a random 256 bit AES Key*/
            KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
            keyGenerator.init(256);
            SecretKey AES_KEY = keyGenerator.generateKey();
            byte[] AES_key_as_bytes = AES_KEY.getEncoded();
            PrivateKey ServerPrivKey_ServSide = ExtractPrivKeyFromJKS(ServerKeyStore,ServerKeyStorePass, Serveralias, ServerKeyStorePass);
            //Let's do a digital signature to provide authenticity and integrity.
            byte[] signatureBytes = cryptoManager.SignMsg(AES_key_as_bytes, ServerPrivKey_ServSide);
            /** Encrypt the AES key*/
            byte[] encrypted_AES_key = cryptoManager.encrypt_RSA(clientPublicKey, AES_key_as_bytes);
            /**Collect the signature + the encrypted AES key */
            byte[] signature_and_enc_key = new byte[signatureBytes.length + encrypted_AES_key.length];
            System.arraycopy(signatureBytes, 0, signature_and_enc_key, 0, signatureBytes.length);
            System.arraycopy(encrypted_AES_key, 0, signature_and_enc_key, signatureBytes.length, encrypted_AES_key.length);
            /** Send the signature + encrypted AES key to the client, so that we now have a shared secret!*/
            socketWriter.writeObject(signature_and_enc_key);

            /** Read the username from the client */
            String EncryptedUserName = (String) socketReader.readObject();
            /** Decrypt the username with the symmetric AES key*/
            userName = cryptoManager.decrypt(EncryptedUserName, AES_KEY);

            /** Store the Client's certificate in a hash table with the username as key*/
            Clients_PublicKeys_ServerSide.put(userName, clientPublicKey);
            /** Store the Shared secret AES key in a hash table with the username as key*/
            Clients_SecretKeys_ServerSide.put(userName, AES_KEY);

            System.out.println("Received username: " + userName);
            SocketServerGUI.getInstance().appendEvent( userName + " just connected at port number: " + handleConnection.getPort() + "\n" );

            return true;
        }
        catch ( StreamCorruptedException sce )
        {
            /** Keep track of the exception in the logging stream... */
            SocketServerGUI.getInstance().appendEvent( "[" + handlerName + "]:: Stream corrupted excp during stream reader/writer init -- " + sce.getMessage() + " (" + connectionStat.getCurrentDate() + ")\n" );
            
            /** Notify the SocketServerEngine that we are about to die in order to create a new SSLConnectionHandler in our place */
            SocketServerEngine.getInstance().addConnectionHandlerToPool( handlerName );

            /** Notify the SocketServerEngine to remove us from the occupance pool... */
            SocketServerEngine.getInstance().removeConnHandlerOccp( handlerName );
            
            /** Then shut down... */
            this.stop();

            return false;
        }
        catch ( ClassNotFoundException cnfe )
            {
                /** Keep track of this exception in the logging stream... */
                SocketServerGUI.getInstance().appendEvent( userName + " Exception reading streams:" + cnfe + "\n" );
                
                return false;
            }
        catch ( OptionalDataException ode )
        {
            /** Keep track of the exception in the logging stream... */
            SocketServerGUI.getInstance().appendEvent( "[" + handlerName + "]:: Optional data excp during stream reader/writer init -- " + ode.getMessage() + " (" + connectionStat.getCurrentDate() + ")\n" );
            
            /** Notify the SocketServerEngine that we are about to die in order to create a new SSLConnectionHandler in our place */
            SocketServerEngine.getInstance().addConnectionHandlerToPool( handlerName );

            /** Notify the SocketServerEngine to remove us from the occupance pool... */
            SocketServerEngine.getInstance().removeConnHandlerOccp( handlerName );
            
            /** Then shut down... */
            this.stop();

            return false;
        }
        catch (IOException | CertificateException | NoSuchPaddingException | InvalidKeyException | IllegalBlockSizeException | BadPaddingException ioe )
        {
            /** Keep track of the exception in the logging stream... */
            SocketServerGUI.getInstance().appendEvent( "[" + handlerName + "]: IOException during stream read/writer init -- " + ioe.getMessage() + " (" + connectionStat.getCurrentDate() + ")\n" );
            
            /** Notify the SocketServerEngine that we are about to die in order to create a new SSLConnectionHandler in our place */
            SocketServerEngine.getInstance().addConnectionHandlerToPool( handlerName );

            /** Notify the SocketServerEngine to remove us from the occupance pool... */
            SocketServerEngine.getInstance().removeConnHandlerOccp( handlerName );
            
            /** Then shut down... */
            this.stop();

            return false;
        }
    }
    
     /**
     * Method for setting the identifier name of this ConnectionHandler thread.
     * Since we will have "ConnectionHandlers.Name" number of thread in the Connectionhandling pool, we must have
     * an identifier for for being able to distinguish them.
     *
     * @param s The String identifier to be given in this ConnectionHandler thread
     */
    public void setHandlerIdentifierName( String s )
    {
        handlerName = s;
    }
    
    /**
     * Method for getting the identifier name of this ConnectionHandler thread.
     *
     * @return The String identifier of this ConnectionHandler thread
     */
    public String getHandlerIdentifierName()
    {
        return handlerName;
    }
    
    /*
    * Method for getting the userName of the connected client handled by this thread.
    *
    * @return The String user Name of the connected client
    */
    public String getUserName()
    {
        return userName;
    }
    
    /**
     * Method for getting the Socket connection operated by this handler
     * 
     * @return The socket connection that is currently handled 
     */
    public Socket getHandleSocket()
    {
        return handleConnection;
    }
    
     /**
     * Java thread entry point...
     * This method contains the main functionality of the SocketConnectionHandler. When the worker handler is in
     * idle state, it just waits to be notified by the SocketServerEngine that a new socket connection has been
     * established and must be handled by this worker.
     * Once a socket connection is assigned to this ConnectionHandler, it waits until there is some data for 
     * reception.
     * Upon shut down, it returns to the Connectionhandling pool for future use by another socket connection.
     */
    public synchronized void run()
    {
        while ( !mustShutdown )
        {
            /** If we are in idle state, don't do anything;just wait to be notified */
            if ( handleConnection == null )
            {
                try
                {
                    wait();
                }
                catch ( InterruptedException e )
                {
                    /** IN NORMAL OPERATION THIS SHOULD NEVER HAPPEN... */
                    /** Print to logging stream that something went wrong */
                    SocketServerGUI.getInstance().appendEvent("[" + handlerName + "]:: ConnectionHandler in idle state died..." + e.getMessage() + " (" + connectionStat.getCurrentDate() + ")\n" );
                    
                    /** Notify the SocketServerEngine that we are about to die in order to create a new SSLConnectionHandler in our place */
                    SocketServerEngine.getInstance().addConnectionHandlerToPool( handlerName );

                    /** Notify the SocketServerEngine to remove us from the occupance pool... */
                    SocketServerEngine.getInstance().removeConnHandlerOccp( handlerName );

                    /** Then shut down... */
                    this.stop();
                    
                    /** Stop this SSLConnectionHandler worker */
                    return;
                }
            }
            
             /** 
             * If we are notified/assigned to handle a socket connection... 
             * Call the receiveContent method for waiting data/requests from the Alix client. The Connection Handler
             * thread will stay in this method during the lifetime of the assigned socket connection
             */
            if ( handleConnection != null )
            {
                receiveContent();
                
                /** If we finished the 'handling' of the assigned socket connection, add ourselves in the connectionHandling pool for future use */
                socketConnectionHandlerRelease();

                /** Also, inform the SocketServerEngine to remove us from the occupance pool... */
                SocketServerEngine.getInstance().removeConnHandlerOccp( this.handlerName );
            }
        }
    }
    
    /**
     * Method for handling any data transmission/reception of the assigned socket connection. The SocketConnectionHandler retrieves
     * the stream sent by the client. Whenever,
     * the stream is empty (client doesn't send anything), the SocketConnectionHandler remains idle and goes back to business
     * only when necessary!!
     */
    public void receiveContent()
    {        
        while ( isSocketOpen )
        {    
            try
            {  
                /** Wait until there is something in the stream to be read... */
                cm = ( ChatMessage )socketReader.readObject();

                // Switch on the type of message receive
                switch(cm.getType())
                {
                case ChatMessage.MESSAGE:
                        String message = cm.getMessage();
                        System.out.println("SERVER RECEIVED A MESSAGE FROM "+ userName+ "! -> ENCRYPTED: "+ message);
                        String dec_chatMsg = cryptoManager.decrypt(message, Clients_SecretKeys_ServerSide.get(userName));
                        System.out.println("SERVER DECRYPTED THE MESSAGE FROM "+ userName+ "! -> DECRYPTED: "+ dec_chatMsg);
                        SocketServerEngine.getInstance().broadcast(userName + ": " + dec_chatMsg);
                        break;
                case ChatMessage.LOGOUT:
                        SocketServerGUI.getInstance().appendEvent(userName + " disconnected with a LOGOUT message.\n");
                         /** If we finished the 'handling' of the assigned socket connection, add ourselves in the connectionHandling pool for future use */
                        socketConnectionHandlerRelease();

                        /** Also, inform the SocketServerEngine to remove us from the occupance pool... */
                        SocketServerEngine.getInstance().removeConnHandlerOccp( this.handlerName );
                        
                        isSocketOpen = false;
                        break;
                case ChatMessage.WHOISIN:
                    SocketServerEngine.getInstance().printEstablishedSocketInfo();
                    break;
                case ChatMessage.PRIVATEMESSAGE:
                    String temp[] = cm.getMessage().split(",");
                    int PortNo = Integer.valueOf(temp[0]);
                    String Chat = temp[1];

                    System.out.println("At Server :  " +PortNo +temp[1]);
                    SocketServerEngine.getInstance().writeMsgSpecificClient(PortNo, Chat);
                    break;              
		}
                
            }
            catch ( ClassNotFoundException cnfe )
            {
                /** Keep track of this exception in the logging stream... */
                SocketServerGUI.getInstance().appendEvent( userName + " Exception reading streams:" + cnfe.getMessage() + "\n" );
                isSocketOpen = false;
            }
            catch ( OptionalDataException ode )
            {
                /** Keep track of this exception in the logging stream... */
                SocketServerGUI.getInstance().appendEvent( userName + " Exception reading streams:" + ode.getMessage() + "\n" );
                isSocketOpen = false;
            }
            catch ( IOException e )
            {
                /** Keep track of this exception in the logging stream... */
                SocketServerGUI.getInstance().appendEvent( userName + " Exception reading streams:" + e.getMessage() + "\n" );
                
                /** Change the socket status... */
                isSocketOpen = false;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
    
    /*
    * Write a String to the Client output stream
    *
    * msg The string to be written to the client output stream
    */
   public boolean writeMsg( String msg ) throws Exception {


           // if Client is still connected send the message to it
           if( !isSocketOpen ) 
           {
                /** If we finished the 'handling' of the assigned socket connection, add ourselves in the connectionHandling pool for future use */
                socketConnectionHandlerRelease();

                /** Also, inform the SocketServerEngine to remove us from the occupance pool... */
                SocketServerEngine.getInstance().removeConnHandlerOccp( this.handlerName );
                
                return false;
           }
           // write the message to the stream
           try 
           {
               /** Encrypt for the broadcast based on the username*/
               String ciphertext = cryptoManager.encrypt(msg, Clients_SecretKeys_ServerSide.get(userName));
               socketWriter.writeObject(ciphertext);
           }
           // if an error occurs, do not abort just inform the user
           catch( IOException e ) 
           {
                SocketServerGUI.getInstance().appendEvent("Error sending message to " + userName + "\n");
                SocketServerGUI.getInstance().appendEvent( e.toString() );
           }
           return true;
   }
    
    /**
     * Method that is called whenever a ConnectionHandler thread finished the execution of an assigned socket 
     * connection. In that case, it must add itself in the Connectionhandling pool of the SocketServerEngine component
     * for future use and return to idle state waiting for new connections.
     */
    public void socketConnectionHandlerRelease()
    {
        /** First clear the reference to the previous connection... */
        handleConnection = null;

        /** Initialize the auxiliary identifier variables... */
        isSocketOpen = false;
        
        /** Print to the logging stream that this SSLConnectionHandler is returing in the ConnectionHandling pool... */
        SocketServerGUI.getInstance().appendEvent( "[" + handlerName + "]:: Finished SckHandling -- Back in the pool (" + connectionStat.getCurrentDate() + ")\n");
        
        /** Get the connectionHandling pool from the SSLEngineServer component to add ourselves */
        Vector connectionPool = SocketServerEngine.getInstance().getConnectionHandlingPool();

        synchronized ( connectionPool )
        {
            connectionPool.addElement( this );
        }
    }
    
     /**
     * Method for notifying this SocketConnectionHandler thread to stop its execution.
     */
    public void stop()
    {
        synchronized ( this )
        {
            /** First get out from execution mode the Connection Handler... */
            isSocketOpen = false;
            
            /** Signal the Connection Handler thread to stop its execution... */
            mustShutdown = true;
            
            /** Notify the ConnectionHandler thread in case it is in an idle state waiting... */
            notify();
        }
    }
}
