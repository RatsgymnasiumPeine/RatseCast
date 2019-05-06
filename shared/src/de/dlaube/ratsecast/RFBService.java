package de.dlaube.ratsecast;

import java.awt.*;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;


/**
 * Remote Frame Buffer Runnable instance for each client connection.<BR>
 * When a VNC viewer opens a connection, a new instance of this class
 * is created.<BR>
 * RFB protocol implemented here follows pdf document at:<BR>
 * <A HREF="https://www.realvnc.com/docs/rfbproto.pdf">https://www.realvnc.com/docs/rfbproto.pdf</A><BR>
 * <BR>
 * Current implementation supports only raw encoding and desktop size pseudo encoding. Authentication is disabled.
 * Supported pixel formats are: 8-bit and 32-bit RGB full color.
 * 
 * @author igor.delac@gmail.com
 *
 */
public class RFBService implements Runnable {

	private final byte[] RFB_VER = "RFB 003.003\n".getBytes();
	private final byte[] SECURITY_TYPE = {0x00, 0x00, 0x00, 0x01};

	private Socket clientSocket;

	private BufferedInputStream in;
	private BufferedOutputStream out;
	

	private byte bits_per_pixel = 0;
	private byte depth = 0;
	private byte big_endian = 0;
	private byte true_color = 0;
	private int red_maximum = 0;
	private int green_maximum = 0;
	private int blue_maximum = 0;
	private byte red_shift = 0;
	private byte green_shift = 0;
	private byte blue_shift = 0;
	private ColorMap8bit colorMap;
	private List<Integer> supportedEncoding;
	public int screenWidth, screenHeight;
	public boolean incrementalFrameBufferUpdate;

	private NativeInterface nativeInterface;

	public RFBService(Socket clientSocket, NativeInterface nativeInterface) throws IOException {
		this.clientSocket = clientSocket;

		this.in = new BufferedInputStream(clientSocket.getInputStream());
		this.out = new BufferedOutputStream(clientSocket.getOutputStream());

		incrementalFrameBufferUpdate = false;

		colorMap = new ColorMap8bit();
		supportedEncoding = new ArrayList<Integer>();

		this.nativeInterface = nativeInterface;
	}
	
	/**
	 * Send protocol version. A string is defined in {@link #RFB_VER} member.<BR>
	 * This is first method that should be executed when a client establish TCP connection.
	 * 
	 * @throws IOException
	 */
	private void sendProtocolVersion() throws IOException {
		out.write(RFB_VER);
		out.flush();
	}

	
	/**
	 * Read byte array that represents protocol version.
	 * It's something like: <I>RFB 003.008\n</I>
	 * 
	 * @return string that is built on byte array
	 * @throws IOException
	 */
	private String readProtocolVersion() throws IOException {
		byte[] buffer = readU8Array(12);
		return new String(buffer);
	}
	
	/**
	 * Send security type bytes. In this implementation, it is the most simple:
	 * no authentication. Other implementations usually require user to type 
	 * password to access RFB service with VNC viewer.
	 * 
	 * @throws IOException
	 */
	private void sendSecurityType() throws IOException {
		out.write(SECURITY_TYPE);
		out.flush();
	}
	
	/**
	 * Shared desktop flag tells RFB server if more VNC viewers are allowed to access
	 * screen at same time. Protocol gives possibility that VNC viewer(s) can decide
	 * to share screen or not.
	 *  
	 * @return byte value <I>0x00</I> or <I>0x01</I>, see RFB protocol specification
	 * @throws IOException
	 */
	private byte readSharedDesktop() throws IOException {
		int buffer = 0;
		buffer = in.read();
		return (byte) buffer;
	}

	/**
	 * Send server initial message to client. All RFB implementations have to do this.
	 * This message tells VNC viewer what are RFB server capabilities, like bits per pixel,
	 * depth, screen width and height, etc.
	 * 
	 * @param width
	 * @param height
	 * @param windowTitle
	 * @throws IOException
	 */
	private void sendServerInit(int width, int height, String windowTitle) throws IOException {
		
		writeU16int(width);
		writeU16int(height);
		
		byte bits_per_pixel = (byte) Toolkit.getDefaultToolkit().getColorModel().getPixelSize();
		byte depth = bits_per_pixel;
		byte big_endian = 0;
		byte true_color = 1;
		
		byte red_max = (byte) 0xFF, green_max = (byte) 0xFF, blue_max = (byte) 0xFF;
		byte red_shift = 16, green_shift = 8, blue_shift = 0;
		
		if (bits_per_pixel == 24) {
			/*
			 * VNC viewers do not support color mode of 24-bits.
			 */
			bits_per_pixel = 32;
			depth = bits_per_pixel;
		}
		
		if (bits_per_pixel == 16) {
			/*
			 * Just in case that display is a 16-bit color mode.
			 * Use appropriate maximum color values and bit positions at which 
			 * each color value begins.
			 */
			red_max = (byte) 0x1F; green_max = (byte) 0x3F; blue_max = (byte) 0x1F;
			red_shift = 11; green_shift = 5; blue_shift = 0;
		}
		
		byte[] pixel_format = {
				bits_per_pixel, // bits-per-pixel
				depth, // depth
				big_endian,  // big-endian-flag
				true_color,  // true-color-flag
				0, red_max, // red-max
				0, green_max, // green-max
				0, blue_max, // blue-max
				red_shift, // red-shift
				green_shift, // green-shift
				blue_shift, // blue-shift
				0, 0, 0 // padding
				};		
		out.write(pixel_format);

		if (windowTitle.length() > 255) {
			windowTitle = windowTitle.substring(0, 255);
		}

		byte[] titleLen = {0, 0, 0, (byte) windowTitle.length()};
		out.write(titleLen);
		out.write(windowTitle.getBytes());			

		out.flush();
	}
	
	/**
	 * Read pixel format message sent from client.
	 * Here the most important is value of bits per pixel. Client may ask to
	 * send frame buffers with lower color value (eg. 8-bits). Other
	 * parameters are ignored.
	 * 
	 * @throws IOException
	 */
	private void readSetPixelFormat() throws IOException {

		byte[] header = readU8Array(4);
		
		if (header[0] != 0x00) {
			throw new IOException();
		}
		
		bits_per_pixel = (byte) in.read();
		depth = (byte) in.read();
		big_endian = (byte) in.read();
		true_color = (byte) in.read();
		red_maximum = readU16int();
		green_maximum = readU16int();
		blue_maximum = readU16int();
		red_shift = (byte) in.read();
		green_shift = (byte) in.read();
		blue_shift = (byte) in.read();
		
		byte[] padding = new byte[3];
		if (in.read(padding) < 3) {
			throw new IOException();
		}
		
		log ("Bits per pixel: " + bits_per_pixel);
		log ("Depth: " + depth);
		log ("Big endian flag: " + big_endian);
		log ("True color flag: " + true_color);
		log ("Red, green, blue max. : " + red_maximum + ", " + green_maximum + ", " + blue_maximum);
		log ("Red, green, blue shift: " + red_shift + ", " + green_shift + ", " + blue_shift);

	}

	/**
	 * Populate list of supported encodings that VNC viewer supports.
	 * This list is for example used when screen size change (eg. JFrame change it's size)
	 * and clients who support desktop size pseudo encoding can be informed about it.
	 * 
	 * @throws IOException
	 */
	private void readSetEncoding() throws IOException {
		
		int messageType = in.read();
		int padding = in.read();
		
		if (messageType == 0x02) {
			
			int numberOfEncodings = readU16int();
			while (numberOfEncodings > 0) {
				int encoding = readU32int();
				supportedEncoding.add(encoding);
				numberOfEncodings--;
			}
			
		}
		else {
			throw new IOException(
					"Error in readSetEncoding(): messageType = " + messageType + ", padding = " + padding + ". Available in buffer: " + in.available() + " bytes.");
		}
		

	}

	/**
	 * Read key event that is sent from client.
	 * Keystroke is then send to system just like the user hit the key.
	 * 
	 * @throws IOException
	 */
	private void readKeyEvent() throws IOException {
		
		int messageType = in.read();
		if (messageType != 0x04) {
			throw new IOException();
		}
		
		/*
		 * Down flag:
		 *  0 -  user released key.
		 *  1 -  user press key.
		 */
		boolean downFlag = in.read() == 1;
		
		@SuppressWarnings("unused")
		int padding = readU16int();
		
		int keyValue = readU32int();		

		nativeInterface.keyDown(keyValue, downFlag);
	}
	
	/**
	 * Read pointer event data. This happens when
	 * user moves mouse in VNC viewer, clicks, etc.
	 * 
	 * @throws IOException
	 */
	private void readPointerEvent() throws IOException {
		
		int messageType = in.read();
		if (messageType != 0x05) {
			throw new IOException();
		}
		
		/*
		 * Button mask:
		 * 1 - left button
		 * 2 - middle button
		 * 4 - right button
		 * 8 - wheel up
		 * 16 - wheel down
		 */
		int buttonMask = in.read();
		
		int x_pos = readU16int();
		int y_pos = readU16int();

		if (buttonMask > 0) {


				/*
				 * Check for button and generate mouse click.
				 */
				if (buttonMask == 1) {
					/*
					 * Left click.
					 */
					nativeInterface.mouseButton(0, true, x_pos, y_pos);
					nativeInterface.mouseButton(0, false, x_pos, y_pos);
				}
				else if (buttonMask == 3) {
					/*
					 * Middle click.
					 */
					nativeInterface.mouseButton(2, true, x_pos, y_pos);
					nativeInterface.mouseButton(2, false, x_pos, y_pos);
				}
				else if (buttonMask == 4) {
					/*
					 * Right click.
					 */
					nativeInterface.mouseButton(1, true, x_pos, y_pos);
					nativeInterface.mouseButton(1, false, x_pos, y_pos);
				}
				else if (buttonMask == 8) {
					/*
					 * Mouse wheel.
					 */
					nativeInterface.mouseWheel(false);
				}
				else if (buttonMask == 16) {
					/*
					 * Mouse wheel.
					 */
					nativeInterface.mouseWheel(true);
				}
			
		}
		else {
			/*
			 * Move mouse pointer only.
			 */
			nativeInterface.mouseMove(x_pos, y_pos);
		}
		
	}

	/**
	 * Transfer client text to system clipboard.
	 * This is triggered when VNC viewer detects
	 * text in clipboard and decide to transfer it to RFB server.
	 * It's up to RFB server how to handle text, it can be transfered
	 * to system clipboard or simply ignored.
	 * 
	 * @throws IOException
	 */
	private void readClientCutText() throws IOException {
		
		byte[] header = readU8Array(4);
		int textLen = readU32int();

		if ( header[0] == 0x06 ) {
			
			/*
			 * Read bytes that are encoded as Latin-1 characters.
			 */
			byte[] textBuffer = readU8Array(textLen);
		    String textLine = new String(textBuffer);
		    
		    /*
		     * Transfer to system clipboard.
		     */
			//StringSelection selection = new StringSelection(textLine);
			//Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
		    //clipboard.setContents(selection, selection);
			
		}
		else {
			throw new IOException();
		}
		
	}

	/**
	 * Reads frame buffer update request.<BR>
	 * Two types are possible: <I>full</I> and <I>incremental</I>
	 * Full request will immediately trigger sending of complete screen to
	 * client, while incremental will let GUI/Swing components to decide when to
	 * send update to clients.
	 * 
	 * @throws IOException
	 */
	private void readFrameBufferUpdateRequest() throws IOException {
		
		int messageType = in.read();
		int incremental = in.read();
		
		if (messageType == 0x03) {
			
			int x_pos = readU16int(); 
			int y_pos = readU16int();
			int width = readU16int();
			int height = readU16int();

			screenWidth  = width;
			screenHeight = height;
			
			if (incremental == 0x00) {
				
				log ("Frame buffer update request received. Full update requested.");
				
				incrementalFrameBufferUpdate = false;
				
				sendFrameBufferUpdate(x_pos, y_pos, width, height, 0, nativeInterface.getImageBuffer(x_pos, y_pos, width, height));
				
				
			}
			else if (incremental == 0x01) {
				
				incrementalFrameBufferUpdate = true;
				
			}
			else {
				throw new IOException();
			}
		}
		else {
			throw new IOException();
		}

	}
	
	/**
	 * Send frame buffer update to client.
	 * 
	 * @param x position of rectangle, value 0 is upper top corner
	 * @param y position of rectangle, value 0 is left corner
	 * @param width width in pixels
	 * @param height height in pixels
	 * @param encodingType encoding type, 0 is raw, for other values see RFB protocol spec.
	 * @param screen screen buffer which holds image
	 * @throws IOException
	 */
	public void sendFrameBufferUpdate(int x, int y, int width, int height, int encodingType, int[] screen) throws IOException {
		
		if (x + width > screenWidth || y + height > screenHeight) {
			err ("Invalid frame update size request:"); 
			err (" x, y = " + x + ", " + y);
			err (" Width x height = " + width + " x " + height);
			err (" Screen width x height = " + screenWidth + " x " + screenHeight);
			return;
		}
		
		byte messageType = 0x00;
		byte padding     = 0x00;
		
		out.write(messageType);
		out.write(padding);
		
		int numberOfRectangles = 1;
		
		writeU16int(numberOfRectangles);	
		
		writeU16int(x);
		writeU16int(y);
		writeU16int(width);
		writeU16int(height);
		writeS32int(encodingType);

		log ("Framebuffer update at (" + x + ", " + y + "). Rectangle: " + width + "x" + height + 
				", encoding: " + encodingType + ", incremental: " + incrementalFrameBufferUpdate +
				", bits per pixel: " + bits_per_pixel);

		writeBuffer(screen);
		out.flush();
	}
	
	/**
	 * Sends two rectangles in frame buffer update, first is screen with old dimensions,
	 * and second is desktop size pseudo encoding rectangle.
	 *  
	 * @throws IOException
	 */
	public void sendDesktopSize() throws IOException
	{
		
		if (supportedEncoding.contains(-223)) {

			int x = 0;
			int y = 0;

			int screenWidth = nativeInterface.getScreenWidth();
			int screenHeight = nativeInterface.getScreenHeight();

			int encodingType = 0;
					
			byte messageType = 0x00;
			byte padding     = 0x00;
			
			out.write(messageType);
			out.write(padding);
			
			int numberOfRectangles = 2;
			
			writeU16int(numberOfRectangles);	
		
			
			encodingType = 0;
			
			writeU16int(x);
			writeU16int(y);
			writeU16int(screenWidth);
			writeU16int(screenHeight);
			writeS32int(encodingType);

			writeBuffer(nativeInterface.getImageBuffer(x, y, screenWidth, screenHeight));

			encodingType = -223;
			
			writeU16int(x);
			writeU16int(y);
			writeU16int(screenWidth);
			writeU16int(screenHeight);
			writeS32int(encodingType);
			
			out.flush();
			
			log ("New screen size: " + screenWidth + " x " + screenHeight);
			
		}
		else {
			err ("Client does not support DesktopSize pseudo encoding.");
		}
	}

	private void writeBuffer(int[] screen) throws IOException {
		for (int rgbValue : screen) {

			int red   = (rgbValue & 0x000000FF);
			int green = (rgbValue & 0x0000FF00) >> 8;
			int blue  = (rgbValue & 0x00FF0000) >> 16;

			if (bits_per_pixel == 8) {
				out.write((byte) colorMap.get8bitPixelValue(red, green, blue));
			}
			else {
				out.write(red);
				out.write(green);
				out.write(blue);
				out.write(0);
			}
		}
	}

	@Override
	public void run() {
		
		try {

			/*
			 * RFB server has to send protocol version string first.
			 * And wait for VNC viewer to replay with protocol version string.
			 */
			sendProtocolVersion();
			String protocolVer = readProtocolVersion();
			if (!protocolVer.startsWith("RFB")) {
				throw new IOException();
			}
			log ("Protocol ver.: " + protocolVer.substring(0, protocolVer.length() - 1));
			
			/*
			 * RFB server sends security type bytes that may request a user to type password.
			 * In this implementation, this is set to simples possible option: no authentication at all.
			 */
			sendSecurityType();

			/*
			 * RFB server reads shared desktop flag. It's a single byte that tells RFB server
			 * should it support multiple VNC viewers connected at same time or not. 
			 */
			byte sharedDesktop = readSharedDesktop();
			log ("Shared desktop flag seleced by client: " + sharedDesktop);
			
			/*
			 * RFB server sends ServerInit message that includes screen resolution,
			 * number of colors, depth, screen title, etc.
			 */
			screenWidth = nativeInterface.getScreenWidth();
			screenHeight = nativeInterface.getScreenHeight();
			String windowTitle = "Test";
			sendServerInit(screenWidth, screenHeight, windowTitle);			
			
			/*
			 * Main loop where clients messages are read from socket.
			 */
			while (true) {

				/*
				 * Mark first byte and read it.
				 */
				in.mark(1);
				int messageType = in.read();
				if (messageType == -1) {
					break;
				}
				/*
				 * Go one byte back. 
				 */
				in.reset();
				
				/*
				 * Depending on message type, read complete message on socket.
				 */
				if (messageType == 0) {
					/*
					 * Set Pixel Format
					 */
					readSetPixelFormat();
				}
				else if (messageType == 2) {
					/*
					 * Set Encodings
					 */
					readSetEncoding();
				}
				else if (messageType == 3) {
					/*
					 * Frame Buffer Update Request
					 */
					readFrameBufferUpdateRequest();
				}
				else if (messageType == 4) {
					/*
					 * Key Event
					 */
					readKeyEvent();
				}
				else if (messageType == 5) {
					/*
					 * Pointer Event
					 */
					readPointerEvent();
				}
				else if (messageType == 6) {
					/*
					 * Client Cut Text
					 */
					readClientCutText();
				}
				else {
					err("Unknown message type. Received message type = " + messageType);
				}
			}
			
			log("Client connection closed.");
			log("");
			
			clientSocket.close();
		
		} catch (SocketException e) {		
			log("Client connection closed.");
			
		} catch (IOException e) {		
			e.printStackTrace();
		}
	}
	
	/**
	 * Write line of text on std.out.
	 * 
	 * @param line text line
	 */
	private void log(String line) {
		System.out.println(line);
	}

	/**
	 * Write line of text on std.err.
	 * 
	 * @param line text line
	 */
	private void err(String line) {
		System.err.println(line);
	}

	/**
	 * Read 2 bytes from socket and return integer value.
	 *
	 * @return integer
	 * @throws IOException
	 */
	private int readU16int() throws IOException {

		byte[] buffer = new byte[2];

		readBuffer(buffer);

		int value = ((buffer[0] & 0xFF) << 8)
				+ (buffer[1] & 0xFF);

		return value;

	}

	private void readBuffer(byte[] buffer) throws IOException {
		int offset = 0, left = buffer.length;
		while (offset < buffer.length) {
			int numOfBytesRead = 0;
			numOfBytesRead = in.read(buffer, offset, left);
			offset = offset + numOfBytesRead;
			left = left - numOfBytesRead;
		}
	}

	/**
	 * Read 4 bytes from socket and return integer value.
	 *
	 * @return integer
	 * @throws IOException
	 */
	private int readU32int() throws IOException {

		byte[] buffer = new byte[4];

		readBuffer(buffer);

		int value = (
				((buffer[0] << 24) & 0xFF000000)
						| ((buffer[1] << 16) & 0x00FF0000)
						| ((buffer[2] << 8 ) & 0x0000FF00)
						| ( buffer[3]        & 0x000000FF));

		return value;

	}

	/**
	 * Write 2 bytes on socket.
	 *
	 * @param value integer value between 0 and 65535
	 * @throws IOException
	 */
	private void writeU16int(int value) throws IOException {

		byte[] buffer = new byte[2];

		buffer[0] = (byte) ((value & 0x0000FF00) >> 8);
		buffer[1] = (byte) ((value & 0x000000FF) );

		out.write(buffer);

	}

	/**
	 * Write 4 bytes on socket.
	 *
	 * @param value integer value between <I>Integer.MIN_VALUE</I> and <I>Integer.MAX_VALUE</I>
	 * @throws IOException
	 */
	private void writeS32int(int value) throws IOException {

		byte[] buffer = new byte[4];

		buffer[0] = (byte) ((value & 0xFF000000) >> 24);
		buffer[1] = (byte) ((value & 0x00FF0000) >> 16);
		buffer[2] = (byte) ((value & 0x0000FF00) >> 8);
		buffer[3] = (byte) ((value & 0x000000FF) );

		out.write(buffer);

	}

	/**
	 * Read array of bytes from socket.
	 *
	 * @param len how many bytes to read
	 * @return byte array
	 * @throws IOException
	 */
	private byte[] readU8Array(int len) throws IOException {
		byte[] buffer = new byte[len];
		readBuffer(buffer);
		return buffer;
	}
	
}
