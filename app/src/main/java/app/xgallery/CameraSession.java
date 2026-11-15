package app.xgallery;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PatternMatcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import app.xgallery.ptp.FujiCamera;
import app.xgallery.ptp.ObjectInfo;

/**
 * One connection to the camera, shared by all screens. A single worker thread owns the camera:
 * it runs queued jobs (viewing and downloading photos) first, and loads thumbnails when idle,
 * starting with the ones on screen.
 */
public final class CameraSession {

	public enum State { IDLE, JOINING_WIFI, CONNECTING, CONNECTED, DISCONNECTED }

	public interface Listener {
		/** State, photo count or download progress changed. */
		void onSessionChanged();

		void onThumbnail(int handle);
	}

	/** Results for viewing one photo full size; called on the main thread. */
	public interface PreviewCallback {
		void onProgress(long done, long total);

		void onLoaded(ObjectInfo info, byte[] data);

		void onFailed(String message);
	}

	static final String APP_VERSION = "0.3";
	private static final String CLIENT_NAME = "XGallery";
	private static final int MAX_LOG_LINES = 10000;

	private static CameraSession instance;

	public static synchronized CameraSession get(Context context) {
		if (instance == null) instance = new CameraSession(context.getApplicationContext());
		return instance;
	}

	private final Context app;
	private final ConnectivityManager connectivity;
	private final Handler main = new Handler(Looper.getMainLooper());
	private final List<Listener> listeners = new CopyOnWriteArrayList<>();
	private final ArrayDeque<String> logLines = new ArrayDeque<>();

	private final Object lock = new Object();
	private final ArrayDeque<Runnable> jobs = new ArrayDeque<>();
	private final Map<Integer, byte[]> thumbs = new ConcurrentHashMap<>();
	private final Map<Integer, ObjectInfo> infos = new ConcurrentHashMap<>();
	private final Set<Integer> noThumb = ConcurrentHashMap.newKeySet();

	// Written by the worker or main thread, read by the UI
	private volatile State state = State.IDLE;
	private volatile String message = "";
	private volatile String cameraName = "";
	private volatile int photoCount = 0;
	private volatile int visibleFirst = 1;
	private volatile int visibleLast = 24;

	// Download progress
	private volatile int dlTotal;
	private volatile int dlDone;
	private volatile int dlFailed;
	private volatile String dlName = "";
	private volatile int dlPercent;

	private ConnectivityManager.NetworkCallback networkCallback;
	private volatile FujiCamera camera;
	private volatile Thread worker;
	/** Increases on every connect; stale threads and callbacks check it and give up. */
	private volatile int generation;

	private CameraSession(Context app) {
		this.app = app;
		this.connectivity = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
	}

	// ------------------------------------------------------------- getters

	public State state() { return state; }
	public String message() { return message; }
	public String cameraName() { return cameraName; }
	public int photoCount() { return photoCount; }
	public byte[] thumbnail(int handle) { return thumbs.get(handle); }
	public ObjectInfo info(int handle) { return infos.get(handle); }
	public boolean downloading() { return dlDone + dlFailed < dlTotal; }
	public int downloadTotal() { return dlTotal; }
	public int downloadDone() { return dlDone; }
	public int downloadFailed() { return dlFailed; }
	public String downloadName() { return dlName; }
	public int downloadPercent() { return dlPercent; }

	public void addListener(Listener l) { listeners.add(l); }
	public void removeListener(Listener l) { listeners.remove(l); }

	/** The gallery reports which photos are on screen so their thumbnails load first. */
	public void setVisibleRange(int firstHandle, int lastHandle) {
		visibleFirst = firstHandle;
		visibleLast = lastHandle;
		synchronized (lock) {
			lock.notifyAll();
		}
	}

	public String logText() {
		synchronized (logLines) {
			StringBuilder sb = new StringBuilder();
			for (String s : logLines) sb.append(s).append('\n');
			return sb.toString();
		}
	}

	void log(String line) {
		synchronized (logLines) {
			logLines.addLast(line);
			while (logLines.size() > MAX_LOG_LINES) logLines.removeFirst();
		}
	}

	// ----------------------------------------------------------- connecting

	/** Asks Android to join the camera's Wi-Fi, then connects. Android shows a network picker. */
	public void connect() {
		if (state == State.JOINING_WIFI || state == State.CONNECTING || state == State.CONNECTED) return;
		final int gen = ++generation;
		resetForNewConnection();
		log("XGallery " + APP_VERSION + " on " + Build.MANUFACTURER + " " + Build.MODEL
				+ ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
		setState(State.JOINING_WIFI, "Pick the camera's network (FUJIFILM-...) in the dialog.");

		WifiNetworkSpecifier specifier = new WifiNetworkSpecifier.Builder()
				.setSsidPattern(new PatternMatcher("FUJIFILM-", PatternMatcher.PATTERN_PREFIX))
				.build();
		NetworkRequest request = new NetworkRequest.Builder()
				.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
				.removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
				.setNetworkSpecifier(specifier)
				.build();
		networkCallback = new ConnectivityManager.NetworkCallback() {
			private boolean started;

			@Override
			public void onAvailable(Network network) {
				if (gen != generation || started) return;
				started = true;
				log("Joined camera Wi-Fi");
				Thread t = new Thread(() -> runWorker(gen, network), "camera-worker");
				worker = t;
				t.start();
			}

			@Override
			public void onUnavailable() {
				if (gen != generation) return;
				log("Android did not join a FUJIFILM-... network (none found, cancelled, or timed out)");
				fail(gen, "Couldn't join the camera's Wi-Fi. Make sure WIRELESS COMMUNICATION is "
						+ "running on the camera, then tap Connect.");
			}

			@Override
			public void onLost(Network network) {
				if (gen != generation) return;
				log("Camera Wi-Fi lost");
				fail(gen, "The camera's Wi-Fi went away.");
			}
		};
		try {
			// Long timeout: it includes the time taken to answer the dialog
			connectivity.requestNetwork(request, networkCallback, 60000);
		} catch (RuntimeException e) {
			log("requestNetwork failed: " + e);
			fail(gen, "Android refused the Wi-Fi request: " + e.getMessage());
		}
	}

	/** Closes the connection and leaves the camera's Wi-Fi. */
	public void disconnect() {
		generation++;
		stopWorker(true);
		releaseNetwork();
		setState(State.IDLE, "");
	}

	private void resetForNewConnection() {
		synchronized (lock) {
			jobs.clear();
		}
		thumbs.clear();
		infos.clear();
		noThumb.clear();
		photoCount = 0;
		dlTotal = dlDone = dlFailed = dlPercent = 0;
		dlName = "";
	}

	private void fail(int gen, String why) {
		if (gen != generation) return;
		generation++;
		stopWorker(false);
		releaseNetwork();
		failQueuedJobs();
		setState(State.DISCONNECTED, why);
	}

	private void stopWorker(boolean polite) {
		final FujiCamera c = camera;
		camera = null;
		Thread w = worker;
		worker = null;
		if (w != null) w.interrupt();
		synchronized (lock) {
			lock.notifyAll();
		}
		if (c != null) {
			// close() talks to the camera, so keep it off the main thread
			new Thread(() -> {
				if (polite) c.close();
				else c.abort();
			}, "camera-close").start();
		}
	}

	private void releaseNetwork() {
		ConnectivityManager.NetworkCallback cb = networkCallback;
		networkCallback = null;
		if (cb == null) return;
		try {
			connectivity.unregisterNetworkCallback(cb);
		} catch (RuntimeException ignored) {
			// already unregistered
		}
	}

	// ---------------------------------------------------------------- worker

	private void runWorker(int gen, Network network) {
		setState(State.CONNECTING, "Connecting... If the camera asks, press OK on the camera.");
		FujiCamera cam = new FujiCamera(() -> network.getSocketFactory().createSocket(), this::log);
		camera = cam;
		try {
			cam.connect(CLIENT_NAME, false);
			if (gen != generation) {
				cam.close();
				return;
			}
			cameraName = cam.cameraName;
			photoCount = Math.max(0, cam.objectCount);
			setState(State.CONNECTED, "");

			while (gen == generation) {
				Runnable job;
				int thumb;
				synchronized (lock) {
					while (true) {
						if (gen != generation) return;
						// Order: viewing a photo, then on-screen thumbnails (so scrolling works during
						// downloads), then downloads, then the remaining thumbnails
						job = null;
						thumb = -1;
						if (jobs.peekFirst() instanceof PreviewJob) {
							job = jobs.pollFirst();
						} else {
							thumb = nextVisibleThumbnail();
							if (thumb < 0) job = jobs.pollFirst();
							if (job == null && thumb < 0) thumb = nextThumbnail();
						}
						if (job != null || thumb > 0) break;
						lock.wait();
					}
				}
				if (job != null) job.run();
				else loadThumbnail(cam, thumb);
			}
		} catch (Throwable t) {
			if (gen != generation) return;
			log(stackTrace(t));
			fail(gen, "Disconnected: " + t.getMessage());
		}
	}

	private int nextVisibleThumbnail() {
		int n = photoCount;
		int first = Math.max(1, visibleFirst);
		int last = Math.min(n, visibleLast);
		for (int h = first; h <= last; h++) {
			if (needsThumb(h)) return h;
		}
		return -1;
	}

	/** Next thumbnail to fetch: on-screen ones first, then outward from there. */
	private int nextThumbnail() {
		int n = photoCount;
		if (n == 0) return -1;
		int first = Math.max(1, Math.min(visibleFirst, n));
		int last = Math.max(first, Math.min(visibleLast, n));
		for (int h = first; h <= last; h++) {
			if (needsThumb(h)) return h;
		}
		for (int d = 1; d < n; d++) {
			int below = last + d;
			int above = first - d;
			if (below > n && above < 1) break;
			if (below <= n && needsThumb(below)) return below;
			if (above >= 1 && needsThumb(above)) return above;
		}
		return -1;
	}

	private boolean needsThumb(int h) {
		return !thumbs.containsKey(h) && !noThumb.contains(h);
	}

	private void loadThumbnail(FujiCamera cam, int handle) throws IOException {
		// libfuji: some cameras hang on GetThumb unless GetObjectInfo comes first
		ObjectInfo oi = cam.getObjectInfo(handle);
		if (oi != null) infos.put(handle, oi);
		byte[] jpeg = cam.getThumbnail(handle);
		if (jpeg == null) noThumb.add(handle);
		else thumbs.put(handle, jpeg);
		main.post(() -> {
			for (Listener l : listeners) l.onThumbnail(handle);
		});
	}

	private void enqueue(Runnable job, boolean urgent) {
		synchronized (lock) {
			if (urgent) jobs.addFirst(job);
			else jobs.addLast(job);
			lock.notifyAll();
		}
	}

	/** Jobs that never ran get their failure callbacks so no screen waits forever. */
	private void failQueuedJobs() {
		List<Runnable> left;
		synchronized (lock) {
			left = new ArrayList<>(jobs);
			jobs.clear();
		}
		for (Runnable r : left) {
			if (r instanceof PreviewJob) ((PreviewJob) r).failLater("Disconnected");
		}
		if (dlDone + dlFailed < dlTotal) {
			dlFailed = dlTotal - dlDone;
		}
	}

	// --------------------------------------------------------------- preview

	/** Thrown from inside a download to stop it early. */
	private static final class Cancelled extends RuntimeException {
		Cancelled() {
			super("cancelled");
		}
	}

	/** A cancellable full-size load for the viewer screen. */
	public final class PreviewJob implements Runnable {
		private final int handle;
		private final PreviewCallback cb;
		private volatile boolean cancelled;

		PreviewJob(int handle, PreviewCallback cb) {
			this.handle = handle;
			this.cb = cb;
		}

		public void cancel() {
			cancelled = true;
			synchronized (lock) {
				jobs.remove(this);
			}
		}

		void failLater(String why) {
			if (!cancelled) main.post(() -> cb.onFailed(why));
		}

		@Override
		public void run() {
			if (cancelled) return;
			FujiCamera cam = camera;
			if (cam == null) {
				failLater("Disconnected");
				return;
			}
			final ByteArrayOutputStream buf = new ByteArrayOutputStream();
			final ObjectInfo[] info = new ObjectInfo[1];
			try {
				cam.download(handle, new FujiCamera.Sink() {
					@Override
					public void begin(ObjectInfo oi) {
						info[0] = oi;
						infos.put(handle, oi);
					}

					@Override
					public void write(byte[] b, int off, int len, long done, long total) {
						if (cancelled) throw new Cancelled();
						buf.write(b, off, len);
						main.post(() -> {
							if (!cancelled) cb.onProgress(done, total);
						});
					}
				});
			} catch (Cancelled c) {
				log("Viewing photo " + handle + " cancelled");
				return;
			} catch (IOException e) {
				failLater(e.getMessage());
				// Let the worker treat it as a connection problem
				throw new RuntimeException(e);
			}
			final byte[] data = buf.toByteArray();
			main.post(() -> {
				if (!cancelled) cb.onLoaded(info[0], data);
			});
		}
	}

	/** Loads one photo full size, ahead of queued downloads. */
	public PreviewJob loadFullSize(int handle, PreviewCallback cb) {
		PreviewJob job = new PreviewJob(handle, cb);
		if (state != State.CONNECTED) {
			main.post(() -> cb.onFailed("Not connected"));
			return job;
		}
		enqueue(job, true);
		return job;
	}

	// -------------------------------------------------------------- download

	/** Queues photos to be saved to the phone, in the given order. */
	public void download(List<Integer> handles) {
		if (handles.isEmpty() || state != State.CONNECTED) return;
		if (dlDone + dlFailed >= dlTotal) {
			dlTotal = dlDone = dlFailed = 0;
		}
		dlTotal += handles.size();
		for (final int h : handles) {
			enqueue(() -> downloadOne(h), false);
		}
		changed();
	}

	/** Saves bytes the viewer already loaded, without asking the camera again. */
	public void saveLoaded(final ObjectInfo info, final byte[] data, final Runnable onDone) {
		new Thread(() -> {
			String result;
			try {
				Uri uri = PhotoSaver.begin(app, info.filename);
				try (OutputStream out = app.getContentResolver().openOutputStream(uri)) {
					out.write(data);
				}
				PhotoSaver.finish(app, uri);
				result = "Saved " + info.filename + " to " + PhotoSaver.folderFor(info.filename);
			} catch (Exception e) {
				log("Saving " + info.filename + " failed: " + e);
				result = "Couldn't save " + info.filename + ": " + e.getMessage();
			}
			final String shown = result;
			log(shown);
			main.post(() -> android.widget.Toast.makeText(app, shown, android.widget.Toast.LENGTH_SHORT).show());
			if (onDone != null) main.post(onDone);
		}, "save").start();
	}

	private void downloadOne(int handle) {
		FujiCamera cam = camera;
		if (cam == null) return;
		ObjectInfo known = infos.get(handle);
		dlName = known != null ? known.filename : "photo " + handle;
		dlPercent = 0;
		changed();

		final Uri[] uri = new Uri[1];
		final OutputStream[] out = new OutputStream[1];
		boolean ok = false;
		try {
			cam.download(handle, new FujiCamera.Sink() {
				@Override
				public void begin(ObjectInfo oi) {
					infos.put(handle, oi);
					dlName = oi.filename;
					try {
						uri[0] = PhotoSaver.begin(app, oi.filename);
						out[0] = app.getContentResolver().openOutputStream(uri[0]);
					} catch (IOException | RuntimeException e) {
						throw new SaveFailed(String.valueOf(e.getMessage()));
					}
					if (out[0] == null) throw new SaveFailed("could not open the file for writing");
				}

				@Override
				public void write(byte[] b, int off, int len, long done, long total) {
					try {
						out[0].write(b, off, len);
					} catch (IOException e) {
						throw new SaveFailed(e.getMessage());
					}
					int pct = total == 0 ? 100 : (int) (done * 100 / total);
					if (pct != dlPercent) {
						dlPercent = pct;
						changed();
					}
				}
			});
			try {
				out[0].close();
				out[0] = null;
				PhotoSaver.finish(app, uri[0]);
			} catch (IOException | RuntimeException e) {
				throw new SaveFailed(String.valueOf(e.getMessage()));
			}
			ok = true;
			log("Saved " + dlName);
		} catch (SaveFailed e) {
			log("Saving " + dlName + " failed: " + e.getMessage());
		} catch (IOException e) {
			// Connection problem: let the worker handle it
			throw new RuntimeException(e);
		} finally {
			if (out[0] != null) {
				try {
					out[0].close();
				} catch (IOException ignored) {
				}
			}
			if (!ok && uri[0] != null) PhotoSaver.discard(app, uri[0]);
			if (ok) dlDone++;
			else dlFailed++;
			changed();
			if (dlDone + dlFailed >= dlTotal) {
				final String done = dlFailed == 0
						? "Saved " + dlDone + (dlDone == 1 ? " photo" : " photos") + " to your phone"
						: "Saved " + dlDone + ", failed " + dlFailed;
				main.post(() -> android.widget.Toast.makeText(app, done, android.widget.Toast.LENGTH_LONG).show());
			}
		}
	}

	/** A phone storage error: skip this photo but keep the connection. */
	private static final class SaveFailed extends RuntimeException {
		SaveFailed(String m) {
			super(m);
		}
	}

	// --------------------------------------------------------------- helpers

	private void setState(State s, String msg) {
		state = s;
		message = msg;
		log("State: " + s + (msg.isEmpty() ? "" : " - " + msg));
		changed();
	}

	private void changed() {
		main.post(() -> {
			for (Listener l : listeners) l.onSessionChanged();
		});
	}

	static String stackTrace(Throwable t) {
		StringWriter sw = new StringWriter();
		t.printStackTrace(new PrintWriter(sw));
		return sw.toString();
	}
}
