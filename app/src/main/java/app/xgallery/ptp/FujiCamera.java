package app.xgallery.ptp;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Client for Fujifilm's "Wireless Communication" Wi-Fi protocol (the one FUJIFILM Camera Remote uses).
 *
 * The protocol was reverse-engineered by Daniel Cook; this class follows the sequence used by his
 * MIT-licensed libfuji (https://github.com/petabyt/libfuji). It is PTP with USB-style containers
 * sent over a single TCP socket, plus a Fuji-specific handshake and a set of vendor properties.
 *
 * No Android imports here on purpose, so this file can be tested on a desktop JVM.
 * Not thread-safe: use from one thread at a time.
 */
public final class FujiCamera implements Closeable {

	public interface Log {
		void log(String line);
	}

	/** Returns a new, unconnected socket (on Android: one bound to the camera's Wi-Fi network). */
	public interface SocketOpener {
		Socket open() throws IOException;
	}

	public static final class Response {
		public int code;
		public int[] params = new int[0];
		/** Data phase payload, or null if the camera sent none. */
		public byte[] data;

		public boolean ok() {
			return code == RC_OK;
		}
	}

	// Standard PTP
	static final int OC_OPEN_SESSION = 0x1002;
	static final int OC_CLOSE_SESSION = 0x1003;
	static final int OC_GET_OBJECT_INFO = 0x1008;
	static final int OC_GET_THUMB = 0x100A;
	static final int OC_GET_DEVICE_PROP_VALUE = 0x1015;
	static final int OC_SET_DEVICE_PROP_VALUE = 0x1016;
	static final int OC_TERMINATE_OPEN_CAPTURE = 0x1018;
	static final int OC_INITIATE_OPEN_CAPTURE = 0x101C;
	static final int OC_GET_PARTIAL_OBJECT = 0x101B;
	static final int OC_FUJI_GET_PROPERTY_DESCRIPTORS = 0x902B;
	static final int MAX_CHUNK = 0x100000;
	public static final int RC_OK = 0x2001;
	static final int RC_SESSION_ALREADY_OPEN = 0x201E;

	// Fuji vendor properties
	static final int DPC_EVENTS = 0xD212;
	static final int DPC_SELECTED_IMGS_MODE = 0xD220;
	static final int DPC_OBJECT_COUNT = 0xD222;
	static final int DPC_STORAGE_ID = 0xD244;
	static final int DPC_ENABLE_CORRECT_FILE_SIZE = 0xD227;
	static final int DPC_CAMERA_STATE = 0xDF00;
	static final int DPC_CLIENT_STATE = 0xDF01;
	static final int DPC_IMAGE_GET_VERSION = 0xDF21;
	static final int DPC_GET_OBJECT_VERSION = 0xDF22;
	static final int DPC_REMOTE_VERSION = 0xDF24;
	static final int DPC_REMOTE_GET_OBJECT_VERSION = 0xDF25;

	// Camera states (0xDF00)
	public static final int STATE_WAIT_FOR_ACCESS = 0;
	public static final int STATE_MULTIPLE_TRANSFER = 1;
	public static final int STATE_FULL_ACCESS = 2;
	public static final int STATE_PC_AUTO_SAVE = 3;
	public static final int STATE_REMOTE_ACCESS = 6;

	// Client states (0xDF01)
	static final int MODE_VIEW_MULTIPLE = 1;
	static final int MODE_VIEW_ALL_IMGS = 2;
	static final int MODE_REMOTE = 5;
	static final int MODE_REMOTE_IMG_VIEW = 11;

	static final int REMOTE_VERSION_TO_SET = 0x2000C;
	static final int PROTOCOL_VERSION = 0x8f53e4f2;

	private static final int TYPE_COMMAND = 1;
	private static final int TYPE_DATA = 2;
	private static final int TYPE_RESPONSE = 3;
	private static final int MAX_CONTAINER = 64 * 1024 * 1024;

	private static final long DEFAULT_TIMEOUT_MS = 15_000;
	/** Used where the camera may be showing a dialog and waiting for the user to press OK. */
	private static final long USER_TIMEOUT_MS = 120_000;

	private final String host;
	private final int cmdPort;
	private final int eventPort;
	private final int videoPort;
	private final SocketOpener opener;
	private final Log log;
	private final long t0 = System.nanoTime();

	private Socket cmd;
	private InputStream in;
	private OutputStream out;
	private Socket eventSock;
	private Socket videoSock;
	private int transaction;
	private boolean sessionOpen;
	private volatile boolean closed;

	// What we learned about the camera
	public String cameraName = "";
	public int cameraState = -1;
	public int objectCount = -1;
	public int selectedImgsMode = -1;
	public int getObjectVersion = -1;
	public int remoteGetObjectVersion = -1;
	public int imageGetVersion = -1;
	public int remoteVersion = -1;
	public int storageId = -1;

	public FujiCamera(SocketOpener opener, Log log) {
		this("192.168.0.1", 55740, 55741, 55742, opener, log);
	}

	public FujiCamera(String host, int cmdPort, int eventPort, int videoPort, SocketOpener opener, Log log) {
		this.host = host;
		this.cmdPort = cmdPort;
		this.eventPort = eventPort;
		this.videoPort = videoPort;
		this.opener = opener;
		this.log = log;
	}

	// ---------------------------------------------------------------- setup

	/**
	 * Connects and puts the camera into image-gallery mode.
	 *
	 * @param clientName name the camera will show / remember for this phone (max 26 chars)
	 * @param plainBrowse false: do what libfuji does (remote mode, then remote image view, on cameras that
	 *                    support remote control). true: always ask for plain "view all images" mode.
	 */
	public void connect(String clientName, boolean plainBrowse) throws IOException {
		say("Connecting to " + host + ":" + cmdPort);
		try {
			cmd = openSocket(cmdPort, 3000);
		} catch (IOException e) {
			say("First connect attempt failed (" + e + "), retrying");
			sleep(500);
			cmd = openSocket(cmdPort, 3000);
		}
		in = cmd.getInputStream();
		out = cmd.getOutputStream();
		say("TCP connected");

		handshake(clientName);

		// Fuji cameras need a short pause before OpenSession
		sleep(50);

		// Fuji starts at transaction 1, not 0
		transaction = 1;
		Response r = command("OpenSession", OC_OPEN_SESSION, DEFAULT_TIMEOUT_MS, 1);
		if (!r.ok() && r.code != RC_SESSION_ALREADY_OPEN) {
			throw new IOException("OpenSession refused: " + hex(r.code));
		}
		sessionOpen = true;

		pollEvents();
		if (cameraState == STATE_WAIT_FOR_ACCESS || cameraState == -1) {
			say(">>> Waiting for the camera to grant access. If it is asking, press OK on the camera.");
			long deadline = System.currentTimeMillis() + USER_TIMEOUT_MS;
			while (cameraState == STATE_WAIT_FOR_ACCESS || cameraState == -1) {
				if (System.currentTimeMillis() > deadline) {
					throw new IOException("Camera never granted access (state " + cameraState + ")");
				}
				sleep(100);
				pollEvents();
			}
		}
		say("Camera state: " + cameraState + " (" + stateName(cameraState) + ")");

		getObjectVersion = getPropInt("GetObjectVersion", DPC_GET_OBJECT_VERSION);
		remoteGetObjectVersion = getPropInt("RemoteGetObjectVersion", DPC_REMOTE_GET_OBJECT_VERSION);
		imageGetVersion = getPropInt("ImageGetVersion", DPC_IMAGE_GET_VERSION);
		remoteVersion = getPropInt("RemoteVersion", DPC_REMOTE_VERSION);

		boolean remotePath = !plainBrowse && (remoteVersion != -1 || cameraState == STATE_REMOTE_ACCESS);

		int mode;
		if (remotePath) {
			mode = MODE_REMOTE;
		} else if (cameraState == STATE_MULTIPLE_TRANSFER) {
			mode = MODE_VIEW_MULTIPLE;
		} else {
			mode = MODE_VIEW_ALL_IMGS;
		}
		say("Setting client mode " + mode + (plainBrowse ? " (plain browse requested)" : ""));
		// Some cameras show a Yes/No dialog here and only answer once the user responds
		Response set = setProp16("ClientState", DPC_CLIENT_STATE, mode, USER_TIMEOUT_MS);
		if (!set.ok()) {
			throw new IOException("Camera refused client mode " + mode + ": " + hex(set.code));
		}
		pollEvents();

		if (cameraState == STATE_MULTIPLE_TRANSFER) {
			throw new IOException("Camera is in 'transfer selected images' mode. "
					+ "Start plain WIRELESS COMMUNICATION on the camera instead.");
		}

		if (remotePath && remoteVersion != -1) {
			check(setProp32("RemoteVersion", DPC_REMOTE_VERSION, REMOTE_VERSION_TO_SET), "set RemoteVersion");
		} else {
			int v = getPropInt("GetObjectVersion", DPC_GET_OBJECT_VERSION);
			check(setProp32("GetObjectVersion", DPC_GET_OBJECT_VERSION, v), "set GetObjectVersion");
		}

		storageId = getPropInt("StorageID", DPC_STORAGE_ID);

		if (remotePath && cameraState == STATE_REMOTE_ACCESS) {
			// libfuji: the camera stops responding unless remote mode is started and ended once
			say("Starting and ending remote mode once");
			command("GetPropertyDescriptors", OC_FUJI_GET_PROPERTY_DESCRIPTORS, DEFAULT_TIMEOUT_MS);
			int openCaptureTransaction = transaction;
			check(command("InitiateOpenCapture", OC_INITIATE_OPEN_CAPTURE, DEFAULT_TIMEOUT_MS, 0, 0),
					"InitiateOpenCapture");
			pollEvents();
			sleep(50);
			eventSock = openSideSocket("event", eventPort);
			videoSock = openSideSocket("liveview", videoPort);
			pollEvents();
			check(command("TerminateOpenCapture", OC_TERMINATE_OPEN_CAPTURE, DEFAULT_TIMEOUT_MS,
					openCaptureTransaction), "TerminateOpenCapture");

			if (remoteGetObjectVersion != -1) {
				say("Switching to remote image gallery");
				pollEvents();
				check(setProp16("CameraState", DPC_CAMERA_STATE, STATE_REMOTE_ACCESS, DEFAULT_TIMEOUT_MS),
						"set CameraState");
				pollEvents();
				pollEvents();
				check(setProp16("ClientState", DPC_CLIENT_STATE, MODE_REMOTE_IMG_VIEW, DEFAULT_TIMEOUT_MS),
						"set ClientState 11");
				pollEvents();
				remoteGetObjectVersion = getPropInt("RemoteGetObjectVersion", DPC_REMOTE_GET_OBJECT_VERSION);
				check(setProp32("RemoteGetObjectVersion", DPC_REMOTE_GET_OBJECT_VERSION, 5),
						"set RemoteGetObjectVersion");
				pollEvents();
			}
		}

		if (objectCount < 0) {
			// The count normally arrives as an event; ask directly if it did not
			objectCount = getPropInt("ObjectCount", DPC_OBJECT_COUNT);
		}
		say("Setup complete. Camera reports " + objectCount + " images.");
	}

	private void handshake(String clientName) throws IOException {
		if (clientName.length() > 26) clientName = clientName.substring(0, 26);
		ByteBuffer p = ByteBuffer.allocate(82).order(ByteOrder.LITTLE_ENDIAN);
		p.putInt(82);
		p.putInt(1); // init command request
		p.putInt(PROTOCOL_VERSION);
		p.putInt(0x5d48a5ad);
		p.putInt(0x0b7fb287);
		p.putInt(0xd0ded5d3);
		p.putInt(0);
		p.put(clientName.getBytes(StandardCharsets.UTF_16LE));
		byte[] packet = p.array();

		IOException last = null;
		for (int attempt = 1; attempt <= 2; attempt++) {
			try {
				say("Handshake attempt " + attempt + " as \"" + clientName + "\"");
				say(">>> If the camera shows a prompt, press OK on the camera.");
				out.write(packet);
				out.flush();
				byte[] head = new byte[4];
				readFully(head, 0, 4, System.currentTimeMillis() + USER_TIMEOUT_MS, true);
				int len = le32(head, 0);
				if (len < 8 || len > 4096) throw new IOException("Bad handshake reply length " + len);
				byte[] rest = new byte[len - 4];
				readFully(rest, 0, rest.length, System.currentTimeMillis() + DEFAULT_TIMEOUT_MS, false);
				int type = le32(rest, 0);
				say("Handshake reply: type " + type + ", " + len + " bytes: " + hexDump(rest, 96));
				if (type != 2) {
					throw new IOException("Camera rejected the handshake (reply type " + type + ")");
				}
				// length, type, 5 x u32, then UTF-16 name
				StringBuilder name = new StringBuilder();
				for (int i = 24; i + 1 < rest.length; i += 2) {
					int c = (rest[i] & 0xff) | ((rest[i + 1] & 0xff) << 8);
					if (c == 0) break;
					name.append((char) c);
				}
				cameraName = name.toString();
				say("Camera name: " + cameraName);
				return;
			} catch (IOException e) {
				last = e;
				say("Handshake attempt " + attempt + " failed: " + e);
			}
		}
		throw last;
	}

	// ------------------------------------------------------------- gallery

	/** Object handles are simply 1..objectCount on this protocol. */
	public ObjectInfo getObjectInfo(int handle) throws IOException {
		Response r = command("GetObjectInfo", OC_GET_OBJECT_INFO, DEFAULT_TIMEOUT_MS, handle);
		if (!r.ok() || r.data == null) return null;
		return ObjectInfo.parse(handle, r.data);
	}

	/** @return JPEG bytes, or null if the camera has no thumbnail for this object */
	public byte[] getThumbnail(int handle) throws IOException {
		Response r = command("GetThumb", OC_GET_THUMB, DEFAULT_TIMEOUT_MS, handle);
		if (!r.ok() || r.data == null || r.data.length < 100) return null;
		return r.data;
	}

	/** Receives a file as it downloads. */
	public interface Sink {
		/** Called once, before any data, with the file's real name and size. */
		void begin(ObjectInfo info) throws IOException;

		void write(byte[] buf, int off, int len, long done, long total) throws IOException;
	}

	/**
	 * Downloads a full file in 1 MB chunks. Same sequence as libfuji: the camera only reports the true
	 * file size while EnableCorrectFileSize (0xD227) is 1, so it is switched on for the download.
	 */
	public ObjectInfo download(int handle, Sink sink) throws IOException {
		pollEvents();
		setProp16("EnableCorrectFileSize", DPC_ENABLE_CORRECT_FILE_SIZE, 1, DEFAULT_TIMEOUT_MS);
		try {
			ObjectInfo oi = getObjectInfo(handle);
			if (oi == null) throw new IOException("Camera has no info for photo " + handle);
			sink.begin(oi);
			long done = 0;
			while (done < oi.size) {
				int n = (int) Math.min(oi.size - done, MAX_CHUNK);
				Response r = command("GetPartialObject", OC_GET_PARTIAL_OBJECT, DEFAULT_TIMEOUT_MS,
						handle, (int) done, n);
				if (!r.ok()) throw new IOException("Download refused: " + hex(r.code));
				if (r.data == null || r.data.length == 0) throw new IOException("Camera sent no data");
				done += r.data.length;
				sink.write(r.data, 0, r.data.length, done, oi.size);
			}
			return oi;
		} finally {
			if (!closed) {
				pollEvents();
				setProp16("EnableCorrectFileSize", DPC_ENABLE_CORRECT_FILE_SIZE, 0, DEFAULT_TIMEOUT_MS);
			}
		}
	}

	// ------------------------------------------------------------ teardown

	@Override
	public void close() {
		if (closed) return;
		closed = true;
		try {
			if (sessionOpen && cmd != null && !cmd.isClosed()) {
				try {
					command("CloseSession", OC_CLOSE_SESSION, 3000);
				} catch (IOException e) {
					say("CloseSession: " + e);
				}
				// Fuji's "goodbye" packet
				out.write(new byte[]{8, 0, 0, 0, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff});
				out.flush();
			}
		} catch (IOException e) {
			say("Goodbye: " + e);
		}
		closeQuietly(eventSock);
		closeQuietly(videoSock);
		closeQuietly(cmd);
		say("Disconnected");
	}

	/** Closes the sockets immediately, to abort a blocked call from another thread. */
	public void abort() {
		closed = true;
		closeQuietly(eventSock);
		closeQuietly(videoSock);
		closeQuietly(cmd);
	}

	// ---------------------------------------------------------- properties

	private void pollEvents() throws IOException {
		Response r = command("GetEvents", OC_GET_DEVICE_PROP_VALUE, DEFAULT_TIMEOUT_MS, DPC_EVENTS);
		if (!r.ok() || r.data == null || r.data.length < 2) return;
		int n = le16(r.data, 0);
		for (int i = 0; i < n && 2 + i * 6 + 6 <= r.data.length; i++) {
			int code = le16(r.data, 2 + i * 6);
			int value = le32(r.data, 2 + i * 6 + 2);
			say("    event " + hex(code) + " = " + value + " (" + hex(value) + ")");
			if (code == DPC_CAMERA_STATE) cameraState = value;
			else if (code == DPC_OBJECT_COUNT) objectCount = value;
			else if (code == DPC_SELECTED_IMGS_MODE) selectedImgsMode = value;
		}
	}

	/** @return the value, or -1 if the camera does not have this property */
	private int getPropInt(String name, int prop) throws IOException {
		Response r = command("GetProp " + name, OC_GET_DEVICE_PROP_VALUE, DEFAULT_TIMEOUT_MS, prop);
		int v = -1;
		if (r.ok() && r.data != null) {
			if (r.data.length == 1) v = r.data[0] & 0xff;
			else if (r.data.length == 2) v = le16(r.data, 0);
			else if (r.data.length == 4) v = le32(r.data, 0);
		}
		say("    " + name + " = " + (v == -1 ? "(not supported)" : hex(v)));
		return v;
	}

	private Response setProp16(String name, int prop, int value, long timeoutMs) throws IOException {
		byte[] d = {(byte) value, (byte) (value >> 8)};
		return commandWithData("SetProp " + name + "=" + value, OC_SET_DEVICE_PROP_VALUE, timeoutMs, d, prop);
	}

	private Response setProp32(String name, int prop, int value) throws IOException {
		byte[] d = {(byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24)};
		return commandWithData("SetProp " + name + "=" + hex(value), OC_SET_DEVICE_PROP_VALUE,
				DEFAULT_TIMEOUT_MS, d, prop);
	}

	private void check(Response r, String what) throws IOException {
		if (!r.ok()) throw new IOException(what + " failed: camera answered " + hex(r.code));
	}

	// ----------------------------------------------------------- transport

	private Response command(String name, int code, long timeoutMs, int... params) throws IOException {
		return commandWithData(name, code, timeoutMs, null, params);
	}

	private Response commandWithData(String name, int code, long timeoutMs, byte[] data, int... params)
			throws IOException {
		if (closed && code != OC_CLOSE_SESSION) throw new IOException("Connection closed");
		long start = System.nanoTime();
		Response r;
		try {
			sendContainers(code, data, params);
			r = readResponse(timeoutMs);
		} catch (NoReplyException e) {
			// libfuji resends once when the camera ignores a command completely
			say(name + ": no reply, sending again");
			sendContainers(code, data, params);
			r = readResponse(timeoutMs);
		}
		transaction++;
		long ms = (System.nanoTime() - start) / 1_000_000;
		StringBuilder sb = new StringBuilder();
		sb.append(name).append(" [").append(hex(code));
		for (int p : params) sb.append(' ').append(hex(p));
		sb.append("] -> ").append(hex(r.code));
		if (r.data != null) {
			sb.append(", ").append(r.data.length).append(" bytes");
			if (r.data.length <= 64) sb.append(": ").append(hexDump(r.data, 64));
		}
		sb.append(" (").append(ms).append(" ms)");
		say(sb.toString());
		return r;
	}

	private void sendContainers(int code, byte[] data, int[] params) throws IOException {
		ByteBuffer c = ByteBuffer.allocate(12 + 4 * params.length).order(ByteOrder.LITTLE_ENDIAN);
		c.putInt(12 + 4 * params.length);
		c.putShort((short) TYPE_COMMAND);
		c.putShort((short) code);
		c.putInt(transaction);
		for (int p : params) c.putInt(p);
		out.write(c.array());
		if (data != null) {
			ByteBuffer d = ByteBuffer.allocate(12 + data.length).order(ByteOrder.LITTLE_ENDIAN);
			d.putInt(12 + data.length);
			d.putShort((short) TYPE_DATA);
			d.putShort((short) code);
			d.putInt(transaction);
			d.put(data);
			out.write(d.array());
		}
		out.flush();
	}

	private Response readResponse(long timeoutMs) throws IOException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		Response r = new Response();
		boolean first = true;
		while (true) {
			byte[] head = new byte[8];
			readFully(head, 0, 8, deadline, first);
			first = false;
			int len = le32(head, 0);
			if (len == 8 && le32(head, 4) == 0xffffffff) {
				throw new IOException("Camera closed the connection (goodbye packet)");
			}
			if (len < 12 || len > MAX_CONTAINER) {
				throw new IOException("Bad container length " + len + ": " + hexDump(head, 8));
			}
			int type = le16(head, 4);
			byte[] body = new byte[len - 8];
			readFully(body, 0, body.length, deadline, false);
			// body: transaction (4), then payload or params
			if (type == TYPE_DATA) {
				r.data = new byte[body.length - 4];
				System.arraycopy(body, 4, r.data, 0, r.data.length);
			} else if (type == TYPE_RESPONSE) {
				r.code = le16(head, 6);
				int n = (body.length - 4) / 4;
				r.params = new int[n];
				for (int i = 0; i < n; i++) r.params[i] = le32(body, 4 + i * 4);
				if (r.code == 0xffff) throw new IOException("Camera sent goodbye code 0xFFFF");
				return r;
			} else {
				throw new IOException("Unexpected container type " + type + ": " + hexDump(head, 8));
			}
		}
	}

	/** Thrown when not a single byte of a reply arrived in time. */
	private static final class NoReplyException extends IOException {
		NoReplyException() {
			super("No reply from camera");
		}
	}

	private void readFully(byte[] buf, int off, int len, long deadline, boolean nothingReadYet)
			throws IOException {
		int got = 0;
		while (got < len) {
			long left = deadline - System.currentTimeMillis();
			if (left <= 0) {
				if (got == 0 && nothingReadYet) throw new NoReplyException();
				throw new IOException("Timed out mid-reply (" + got + "/" + len + " bytes)");
			}
			cmd.setSoTimeout((int) Math.min(left, 1000));
			int n;
			try {
				n = in.read(buf, off + got, len - got);
			} catch (SocketTimeoutException e) {
				continue;
			}
			if (n < 0) throw new EOFException("Camera closed the connection");
			got += n;
		}
	}

	private Socket openSocket(int port, int timeoutMs) throws IOException {
		Socket s = opener.open();
		try {
			s.setTcpNoDelay(true);
			s.connect(new InetSocketAddress(host, port), timeoutMs);
		} catch (IOException e) {
			closeQuietly(s);
			throw e;
		}
		return s;
	}

	/** Event / liveview sockets: the camera expects them to be opened; whatever arrives is discarded. */
	private Socket openSideSocket(String what, int port) {
		try {
			final Socket s = openSocket(port, 3000);
			say("Opened " + what + " socket on port " + port);
			Thread t = new Thread(() -> {
				byte[] sink = new byte[16384];
				try {
					InputStream is = s.getInputStream();
					while (is.read(sink) >= 0) {
						// discard
					}
				} catch (IOException ignored) {
				}
			}, "fuji-" + what + "-drain");
			t.setDaemon(true);
			t.start();
			return s;
		} catch (IOException e) {
			say("Could not open " + what + " socket on port " + port + ": " + e);
			return null;
		}
	}

	// -------------------------------------------------------------- helpers

	private void say(String s) {
		long ms = (System.nanoTime() - t0) / 1_000_000;
		log.log(String.format(Locale.US, "%6d.%03d  %s", ms / 1000, ms % 1000, s));
	}

	public static String stateName(int state) {
		switch (state) {
			case STATE_WAIT_FOR_ACCESS: return "waiting for access";
			case STATE_MULTIPLE_TRANSFER: return "transfer selected images";
			case STATE_FULL_ACCESS: return "full access";
			case STATE_PC_AUTO_SAVE: return "PC auto save";
			case STATE_REMOTE_ACCESS: return "remote access";
			default: return "unknown";
		}
	}

	static String hex(int v) {
		return "0x" + Integer.toHexString(v).toUpperCase(Locale.US);
	}

	static String hexDump(byte[] b, int max) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < b.length && i < max; i++) {
			sb.append(String.format(Locale.US, "%02x", b[i] & 0xff));
			if (i + 1 < b.length && i + 1 < max) sb.append(' ');
		}
		if (b.length > max) sb.append(" ...");
		return sb.toString();
	}

	static int le16(byte[] b, int o) {
		return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8);
	}

	static int le32(byte[] b, int o) {
		return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24);
	}

	private static void sleep(long ms) throws IOException {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted");
		}
	}

	private static void closeQuietly(Socket s) {
		if (s == null) return;
		try {
			s.close();
		} catch (IOException ignored) {
		}
	}
}
