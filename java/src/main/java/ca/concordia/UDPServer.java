package ca.concordia;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import joptsimple.OptionSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import joptsimple.OptionParser;
import joptsimple.OptionSet;

public class UDPServer {

    private static final Logger logger = LoggerFactory.getLogger(UDPServer.class);
    private String directory;
    private static final int DATA = 0;
    private static final int SYN = 1;
    private static final int SYNACK = 2;
    private static final int ACK = 3;
    private static final int NACK = 4;
    private static final int FIN = 5;
    
    HashMap<Long,String> finalResponse = new LinkedHashMap<>();

	/*
	Constructor for UDPServer
	 */

	public UDPServer(String directory, boolean verbose){
	this.directory = directory;
	}
    
   
    		/*
    		 * Return list of files in directory OR content of a specific file
    		 * Saved in a string to put in packet payload
    		 */
    private String handleGET(String file) {
    	if(file.equals("/")) {
     		File dir = new File(directory);
 			File[]list = dir.listFiles();
 			StringBuilder listing = new StringBuilder();
 			StringBuilder directoryBuilder = new StringBuilder("========\n");
 			int i = 0;
 			while(i < list.length) {
 				if(list[i].isFile()) {
 					directoryBuilder.append("File: ");
 				}
 				else if(list[i].isDirectory()) {
 					directoryBuilder.append("Directory: ");
 				}
 				else
 					continue;
 				directoryBuilder.append(list[i].getName() + "\n");
 				i++;
 			}
 			listing.append("HTTP/1.0 200 Ok\n");
 			listing.append("Date: " + java.time.LocalDate.now() +"\n");
 			listing.append("Server: localhost\n");
 			listing.append("\n\n");
 			listing.append(directoryBuilder.toString());
 			return listing.toString();
    	}
    	else {
    		try {

				File f = new File(directory + file);
				File t = new File(file);
				StringBuilder listing = new StringBuilder();
				if(!f.exists()) {
					listing.append("HTTP/1.0 405 Not Found\r\n");
					listing.append("Date: " + java.time.LocalDate.now()+"\r\n");
					listing.append(f);
					return listing.toString();
				}
				else if(unAuthorized(file)) {
					listing.append("HTTP/1.0 403 Forbidden");
					listing.append("Date: " + java.time.LocalDate.now());
					return listing.toString();
				}
				else if(f.isFile()) {
					BufferedReader reader = new BufferedReader(new FileReader(f));
					StringBuilder content = new StringBuilder("Content: \n");
					String line = reader.readLine();
					while(line!=null) {
						content.append(line);
						content.append("\n");
						line = reader.readLine();
					}
					reader.close();
					listing.append("HTTP/1.0 200 Ok\n");
					listing.append("Date: " + java.time.LocalDate.now() +"\n");
					listing.append("Server: localhost\n");
					listing.append("\n\n");
					listing.append(content.toString());
					return listing.toString();
				}

			} catch (FileNotFoundException e) {
				// TODO Auto-generated catch block
				return e.getMessage();
			}
			catch(IOException i) {
				return i.getMessage();
			}
    	}
		return "";
    }
    /*
	 * Create content in a file
	 * Saved in a string to put in packet payload
	 */
    private String handlePOST(String file, String body) {
    	PrintWriter fileWriter;
    	StringBuilder response = new StringBuilder();
    	if(unAuthorized(file)) {
			response.append("HTTP/1.0 403 Forbidden\r\n");
			response.append("Date: " + java.time.LocalDate.now() + "\r\n");
			return response.toString();
		}
    	else {
    		try {
    			fileWriter = new PrintWriter(new FileOutputStream(directory+file, false));
				fileWriter.println(body);
				fileWriter.flush();
				fileWriter.close();
    		response.append("HTTP/1.0 201 Created\r\n");
    		response.append("Date: " + java.time.LocalDate.now() + "\r\n");
    		response.append("Server: localhost\r\n");
    		response.append("Content-length: " + file.length() + "\r\n");
    		response.append("\r\n");
    		return response.toString();
    		}catch (FileNotFoundException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			catch(IOException i) {
				System.out.println(i);
			}
    	}
		return "";
    }
    				/*
    				 * Method to extract payload from client packet
    				 * Create response packet payload 
    				 */
    private String createResponse(String directory, String payload) {
    	String[] requestArray1 = payload.split("\r\n\r\n");
    	String[] requestArray2 = requestArray1[0].split("\r\n");
    	String[] separator = requestArray2[0].split(" ");
    	String method = separator[0];
    	String file = separator[1].replace("HTTP/1.0","");
        
        if(file.length() > 1) {
			int length = file.length();
			System.out.println(length);
			file = file.startsWith("/") ? file.substring(1) : file;
		}
    	 if(method.equals("GET")) {
    		 
         	return handleGET(file);
         	
         }else if(method.equals("POST")) {
        	return handlePOST(file, requestArray1[1]);
         }
    	 return "";
    	
    }
    
    
    private boolean unAuthorized(String file) {
		File f = new File(file);
		File currentDir = new File(directory);

		if(f.isDirectory()) {
			return true;
		}
		//check if file is outside current dir
		try {
			if(f.getCanonicalPath().contains(currentDir.getCanonicalPath())) {
				return false; // able to write here
			}
		} catch(IOException e) {
			return true;
		}

		return false;

	}
    
    						/*
    						 * Method to handle a response that is bigger than packet size
    						 * Put the response in multiple packets and stored in Hashmap
    						 */
    private HashMap<Long,Packet> handleResponse(String response, Packet packet, SocketAddress router, DatagramChannel channel){
    	HashMap<Long,Packet> responseMap = new LinkedHashMap<>(); //linkedHashMap keep insertion order
    	long sequenceNumber = packet.getSequenceNumber() +1;
    	byte[] payload = response.getBytes();
    	
    	if(payload.length <= Packet.MAX_PAYLOAD) {
    		Packet resp = packet.toBuilder().setSequenceNumber(sequenceNumber).setType(DATA).setPayload(payload).create();
    		responseMap.put(resp.getSequenceNumber(), resp);
    		sequenceNumber = sequenceNumber + resp.getPayload().length + Packet.MIN_LEN;
    	}
    	else {
    		int currentIndex = 0;
    		while(currentIndex < payload.length) {
    			int maxIndex = Math.min(currentIndex + Packet.MAX_PAYLOAD, payload.length);
    			byte[] packetPayload = new byte[maxIndex - currentIndex];
    			int i = 0;
    			for(int j = currentIndex; j < maxIndex;j++) {
    				packetPayload[i] = payload[j]; 
    				i++;
    			}
    			sequenceNumber = sequenceNumber + currentIndex;
    			Packet resp = packet.toBuilder().setSequenceNumber(sequenceNumber).setType(DATA).setPayload(packetPayload).create();
    			responseMap.put(sequenceNumber, resp);
    			currentIndex = maxIndex;
    		}
    	}
    	//putting a FIN at the end of the hashmap of data packets
    	Packet fin = packet.toBuilder().setSequenceNumber(sequenceNumber+1).setType(FIN).setPayload("END of connection".getBytes()).create();
    	responseMap.put(fin.getSequenceNumber(), fin);
    	//logger.info("Sending FIN packet to the server");
    	return responseMap;
    }
    
    				/*
    				 * Send the packets and check if we have ACK from client before sending it back
    				 */
    private void sendPackets(HashMap<Long,Packet> response, DatagramChannel channel, SocketAddress router, ByteBuffer buffer) throws IOException{
    	for(Map.Entry<Long, Packet>packet: response.entrySet()) {
    		if(packet.getValue().getType() == DATA) {
    		channel.send(packet.getValue().toBuffer(),router );
    		checkAck(channel, router, packet.getValue(), buffer);
    		}
    		else {
    			channel.send(packet.getValue().toBuffer(), router);
    			
    		}
    	}
    	
    }
    
    private void checkAck(DatagramChannel channel, SocketAddress router, Packet packet, ByteBuffer buffer) throws IOException{
    	Packet rcv = null;
    	buffer.clear();
    	router = channel.receive(buffer);
    	buffer.flip();
        rcv = Packet.fromBuffer(buffer);
        buffer.flip();
        if(!(rcv.getType()==ACK && packet.getSequenceNumber() == rcv.getSequenceNumber())) {
        	channel.send(packet.toBuffer(), router);
        	checkAck(channel,router,packet, buffer);
        }
    }
    
    
    private void listenAndServe(int port) throws IOException {

        try (DatagramChannel channel = DatagramChannel.open()) {
            channel.bind(new InetSocketAddress(port));
            logger.info("EchoServer is listening at {}", channel.getLocalAddress());
            ByteBuffer buf = ByteBuffer
                    .allocate(Packet.MAX_LEN)
                    .order(ByteOrder.BIG_ENDIAN);

            for (; ; ) {
                buf.clear();
                SocketAddress router = channel.receive(buf);

                // Parse a packet from the received raw data.
               

             
                
        
                
                
                
                HashMap<Long,Packet> routerResponse = choosePacket(buf,directory,router,channel);
                if(routerResponse != null) {
                	sendPackets(routerResponse,channel,router, buf);
                }
                
                
                // Send the response to the router not the client.
                // The peer address of the packet is the address of the client already.
                // We can use toBuilder to copy properties of the current packet.
                // This demonstrate how to create a new packet from an existing packet.
               /* Packet resp = packet.toBuilder()
                        .setPayload(response.getBytes())
                        .create();
                channel.send(resp.toBuffer(), router);
*/
            }
        }
    }
    
    private HashMap<Long,Packet> choosePacket(ByteBuffer buffer, String directory, SocketAddress router, DatagramChannel channel) throws IOException{
    	Packet packet = null;
    	HashMap<Long, Packet> packets = new HashMap<>();
    	buffer.flip();
        packet = Packet.fromBuffer(buffer);
        buffer.flip();
        String payload = new String(packet.getPayload(), UTF_8);
        if(packet.getType() == SYN) {
        	return handleHandshake(packet);
        }
        else if(packet.getType() == ACK) {
        	logger.info("ACK received");
        	return null;
        }
        else if(packet.getType()== DATA) {
        	logger.info("DATA packet received");
        	finalResponse.put(packet.getSequenceNumber(), payload);
        	logger.info("Sending ACK for data received");
        	return sendAck(packet);
        }
        else if(packet.getType()== FIN) {
        	logger.info("FIN packet received");
        	StringBuilder messageBuilder = new StringBuilder();
        	SortedSet<Long> keys = new TreeSet<>(finalResponse.keySet());
        	for(long key : keys) {
        		messageBuilder.append(finalResponse.get(key));
        	}
        	logger.info("Sending DATA packet");
        	String response = createResponse(directory,messageBuilder.toString());
        	return handleResponse(response,packet,router,channel);
        }
        else {
        	logger.info("Invalid type");
        	return null;
        }
    }

    private HashMap<Long,Packet> handleHandshake(Packet packet){
    	HashMap<Long,Packet> handshakeResponse= new HashMap<>();
    	String message = "SYNACK";
    	Packet response = packet.toBuilder()
    			.setSequenceNumber(packet.getSequenceNumber()+1)
    			.setType(SYNACK)
    			.setPayload(message.getBytes(StandardCharsets.UTF_8))
    			.create();
    	handshakeResponse.put(packet.getSequenceNumber(), response);
    	logger.info("Handshake SYN received");
    	return handshakeResponse;
    }
    
    
    private HashMap<Long,Packet> sendAck(Packet packet)throws IOException{
    	HashMap<Long,Packet> responseMap = new HashMap<>();
    	Packet response = packet.toBuilder().setSequenceNumber(packet.getSequenceNumber()).setPayload("ACK".getBytes()).setType(ACK).create();
    	responseMap.put(packet.getSequenceNumber(),response);
    	return responseMap;
    }
    
    
    
    public static void main(String[] args) throws IOException {
        OptionParser parser = new OptionParser();
        parser.acceptsAll(asList("port", "p"), "Listening port")
                .withOptionalArg()
                .defaultsTo("8007");
		boolean verbose = false;
		String dir = "";
		OptionSpec<String> dir_arg = parser.accepts("d").withRequiredArg().ofType(String.class);
		OptionSpec<Void> verbose_args = parser.accepts("v");
		OptionSet opts = parser.parse(args);
		int port = Integer.parseInt((String) opts.valueOf("port"));
		verbose = opts.has(verbose_args);
		dir = opts.valueOf(dir_arg);
        UDPServer server = new UDPServer(dir, verbose);
        server.listenAndServe(port);
    }
}