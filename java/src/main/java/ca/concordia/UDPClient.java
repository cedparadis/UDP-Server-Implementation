package ca.concordia;

import static java.nio.channels.SelectionKey.OP_READ;
import static java.util.Arrays.asList;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.SocketAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.util.*;

import joptsimple.OptionSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import joptsimple.OptionParser;
import joptsimple.OptionSet;

public class UDPClient {

	private static final Logger logger = LoggerFactory.getLogger(UDPClient.class);
	private static final int DATA = 0;
	private static final int SYN = 1;
	private static final int SYNACK = 2;
	private static final int ACK = 3;
	private static final int NACK = 4;
	private static final int FIN = 5;

	private static HashMap<Long,Packet> ackBuf = new LinkedHashMap<>();

	/*
	Constructor for UDPClient
	 */
	public UDPClient(){

	}
	
		/*
		 * Sending a get method to the server, but save the GET as a string to send as payload
		 */
	private static String sendGET(String testUrl, boolean verbose, Map<String,String>headers) {
		//put in URL to parse elements
		try {
			URL url = new URL(testUrl);
			String host = url.getHost();
			String path = url.getPath();
			String param = url.getQuery();
			int port = url.getPort() != -1 ? url.getPort():url.getDefaultPort();
			StringBuilder request = new StringBuilder();

			if(param!= null) {
				request.append( "GET "+ path + "?"+ param + " HTTP/1.0\r\n" );
			}
			//without parameters
			else {
				request.append( "GET "+ path + " HTTP/1.0\r\n" );
			}

			request.append("Host: " + host + "\r\n");
			if(headers != null) {
				for(String key: headers.keySet()) {
					request.append(key +": " + headers.get(key) +"\r\n");
				}
				request.append("\r\n");
			}
			else {
				request.append("\r\n");
			}
			return request.toString();
			//System.out.println("host: " + host + "\nPath: " + param + "\nPort: " + port);
		}catch(MalformedURLException e) {
			return e.getMessage();
		}
	}

	/*
	 * Sending a post method to the server, but save the POST as a string to send as payload
	 */
	private static String sendPOST(String testUrl, boolean verbose, Map<String,String> headers, String inlineData) throws IOException{
		//put in URL to parse elements
		URL url = new URL(testUrl);
		String host = url.getHost();
		String path = url.getPath();
		String param = url.getQuery();
		int port = url.getPort() != -1 ? url.getPort():url.getDefaultPort();
		StringBuilder request = new StringBuilder();

		request.append("POST " + path + " HTTP/1.0\r\n");

		if(headers != null) {
			for(String key: headers.keySet()) {
				request.append(key +": " + headers.get(key) + "\r\n");
			}
		}

		//set the body of the request

		if(inlineData == null) {
			request.append("Content-length: 0");
			request.append("\r\n");
			request.append("");
		}
		else {
			request.append("Content-length: " + inlineData.length() + "\r\n");
			request.append("\r\n");

			request.append(inlineData);

		}
		return request.toString();
	}




	private static void runClient(SocketAddress routerAddr, int serverPort,
								  String cmd, Map<String,String> headers, String inlineData , String theURL) throws IOException {

		try(DatagramChannel channel = DatagramChannel.open()){
			URL url = new URL(theURL);
			String host = url.getHost();
			String path = url.getPath();
			InetSocketAddress serverAddr = new InetSocketAddress(host, serverPort);
			long sequenceNumber = handshake(channel,routerAddr, serverAddr);
			if(sequenceNumber == -1) {
				logger.error("No response after timeout");
				return;
			}
			String msg = "";
			if(cmd.equals("get")) {
				msg = sendGET(theURL, true, null);
			}
			else if(cmd.equals("post")){
				msg = sendPOST(theURL, true, null, inlineData);
			}
			Packet p = new Packet.Builder()
					.setType(DATA)
					.setSequenceNumber(1L)
					.setPortNumber(serverAddr.getPort())
					.setPeerAddress(serverAddr.getAddress())
					.setPayload(msg.getBytes())
					.create();
			channel.send(p.toBuffer(), routerAddr);

			logger.info("Sending \"{}\" to router at {}", msg, routerAddr);

			// Try to receive a packet within timeout. Can receive multiple packets
			while(true) {
				//check if delay too long, resend packet
				timer(channel,p,routerAddr);

				// We just want a single response.
				ByteBuffer buf = ByteBuffer.allocate(Packet.MAX_LEN);
				SocketAddress router = channel.receive(buf);
				buf.flip();
				Packet resp = Packet.fromBuffer(buf);
				//check if after sending data we do not get an ACK from server, we send again
				if(!(resp.getType() == ACK && resp.getSequenceNumber() == p.getSequenceNumber())){
					channel.send(p.toBuffer(), routerAddr);
					continue;
				}
				logger.info("Packet: {}", resp);
				logger.info("Router: {}", router);
				String payload = new String(resp.getPayload(), StandardCharsets.UTF_8);
				logger.info("Payload: {}",  payload);

				//build a FIN packet to tell server we want to close connection after its data
				Packet f = resp.toBuilder()
						.setType(FIN)
						.setSequenceNumber(resp.getSequenceNumber()+1)
						.setPortNumber(serverAddr.getPort())
						.setPeerAddress(serverAddr.getAddress())
						.setPayload("FIN".getBytes())
						.create();
				channel.send(f.toBuffer(), routerAddr);

				logger.info("Sending \"{}\" to router at {}", "FIN", routerAddr);

				// method to handle multiple packets received from server
				receiveData(channel, routerAddr, serverAddr, f);

				break;


			} 
		}
	}

			/*
			 * Method to handle the data from server and send back ACK for each data to ensure it is all received
			 */
	private static void receiveData(DatagramChannel channel, SocketAddress routerAddr, InetSocketAddress serverAddr, Packet packet) throws IOException{
		StringBuilder totalPayload = new StringBuilder(); //place all data packet in one string
		while(true) {
			timer(channel,packet,routerAddr); //check if delay too long send back FIN

			// We just want a single response.
			ByteBuffer buf = ByteBuffer.allocate(Packet.MAX_LEN);
			SocketAddress router = channel.receive(buf);
			buf.flip();
			Packet resp = Packet.fromBuffer(buf);

			//append the data from the packet
			if(resp.getType()==DATA) {
				logger.info("Data packet received from Server");
				totalPayload.append(new String(resp.getPayload(),StandardCharsets.UTF_8));
			}
			//send an ack packet to notify the server we received 
			//TODO: method to send back ACK if it was dropped (create timer method in server for dropped ACK)
			Packet respAck = resp.toBuilder()
					.setType(ACK)
					.setSequenceNumber(resp.getSequenceNumber())
					.setPortNumber(serverAddr.getPort())
					.setPeerAddress(serverAddr.getAddress())
					.setPayload("ACK".getBytes())
					.create();

			channel.send(respAck.toBuffer(), router);
			/* if(!(resp.getType() == ACK && resp.getSequenceNumber() == p.getSequenceNumber())){
             	channel.send(p.toBuffer(), routerAddr);
             	continue;
             }*/
			
			//If server sends FYN it means there are no more data, end the connection and display payload
			
			if(resp.getType()==FIN) {
				logger.info("FYN received from Server");
				logger.info("Connection is now over");
				System.out.println(totalPayload.toString());
				return;
			}
		}
	}


				/*
				 * Method to initiate handshake with server
				 */
	private static long handshake(DatagramChannel channel,SocketAddress routerAddr, InetSocketAddress serverAddr) throws IOException {
		String payload = "SYN";
		Packet syn = new Packet.Builder()
				.setType(SYN)
				.setSequenceNumber(1L)
				.setPortNumber(serverAddr.getPort())
				.setPeerAddress(serverAddr.getAddress())
				.setPayload(payload.getBytes())
				.create();
		//Send a SYN to initiate handshake
		logger.info("Initiating connection, sending Handshake SYN");
		channel.send(syn.toBuffer(), routerAddr);

		//Try to receive SYNACK from server
		timer(channel,syn,routerAddr);

		ByteBuffer buf = ByteBuffer.allocate(Packet.MAX_LEN);
		channel.receive(buf);
		buf.flip();
		Packet resp = Packet.fromBuffer(buf);

		// String msg = new String(resp.getPayload(), StandardCharsets.UTF_8);


		//check if it received SYN-ACK
		if(resp.getType() == SYNACK) {
			logger.info("Received SYNACK Handshake");
			payload = "ACK";
			//send ACK
			Packet ack = new Packet.Builder()
					.setType(ACK)
					.setSequenceNumber(3L)
					.setPortNumber(serverAddr.getPort())
					.setPeerAddress(serverAddr.getAddress())
					.setPayload(payload.getBytes())
					.create();
			logger.info("Sending Handshake ACK, connection established.");
			channel.send(ack.toBuffer(), routerAddr);	
			return ack.getSequenceNumber();

		}
		else {
			return -1;
		}




	}

					/*
					 * Method to resend packet after delay
					 * TODO:implement same timer method in SERVER
					 */
	private static void timer(DatagramChannel channel, Packet packet, SocketAddress router )throws IOException{
		channel.configureBlocking(false);
		Selector selector = Selector.open();
		channel.register(selector, OP_READ);
		selector.select(1000);

		Set<SelectionKey>keys = selector.selectedKeys();
		if(keys.isEmpty()) {
			channel.send(packet.toBuffer(), router);
			timer(channel,packet,router);
		}
		keys.clear();
		return;
	}



	public static void main(String[] args) throws IOException {
		OptionParser parser = new OptionParser();
		parser.accepts("router-host", "Router hostname")
		.withOptionalArg()
		.defaultsTo("localhost");

		parser.accepts("router-port", "Router port number")
		.withOptionalArg()
		.defaultsTo("3000");

		parser.accepts("server-host", "EchoServer hostname")
		.withOptionalArg()
		.defaultsTo("localhost");

		parser.accepts("server-port", "EchoServer listening port")
		.withOptionalArg()
		.defaultsTo("8007");

		parser.acceptsAll(asList("file", "f"), "File")
				.withOptionalArg();
		parser.acceptsAll(asList("v", "verbose"), "Verbose");
		OptionSpec<String> headers_arg = parser.accepts("h").withRequiredArg();
		parser.acceptsAll(asList("d", "inline-data"), "Inline data").withRequiredArg();
		OptionSet opts = parser.parse(args);

		// Router address
		String routerHost = (String) opts.valueOf("router-host");
		int routerPort = Integer.parseInt((String) opts.valueOf("router-port"));

		// Server address
		String serverHost = (String) opts.valueOf("server-host");
		int serverPort = Integer.parseInt((String) opts.valueOf("server-port"));

		//Command line arguments
		List<String> list = opts.valuesOf(headers_arg);
		String file_arg = (String) opts.valueOf("file");
		String inlineData = (String) opts.valueOf("inline-data");
		boolean verbose = opts.has("v");
		String theURL = args[args.length-1];
		SocketAddress routerAddress = new InetSocketAddress(routerHost, routerPort);
		Map<String,String> headers = null;
		if (list.size() > 0) {
			//allocate headers to a non null HashMap
			headers = new HashMap<String, String>();
			for (int i = 0; i < list.size(); i++) {
				//check for correct format
				if (!(list.get(i)).contains(":")) {
					System.out.println("Invalid format in header" +list.get(i));
					System.exit(0);
					//if format ok, add the key value pairs to headers
				} else {
					System.out.println(list.get(i));
					String str = list.get(i);
					String[] arrOfString = str.split(":", 2);
					String key = arrOfString[0];
					String value = arrOfString[1];
					headers.put(key, value);
				}
			}
		}
		runClient(routerAddress, serverPort, args[0], headers, inlineData, theURL);
	}
}

