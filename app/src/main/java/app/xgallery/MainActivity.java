package app.xgallery;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import app.xgallery.ptp.FujiCamera;
import app.xgallery.ptp.ObjectInfo;

/**
 * Step 1 test screen: connect to the camera, read the photo list and the first few thumbnails,
 * and record everything in a log that can be shared.
 */
public final class MainActivity extends Activity {

	private static final String VERSION = "0.1 (step 1: connection test)";
	private static final String CLIENT_NAME = "XGallery";
	private static final int THUMBS_TO_TEST = 10;

	private final Handler ui = new Handler(Looper.getMainLooper());
	private final StringBuilder logText = new StringBuilder();

	private TextView status;
	private TextView logView;
	private ScrollView logScroll;
	private LinearLayout thumbRow;
	private Button connectButton;
	private CheckBox plainBrowse;

	private ConnectivityManager connectivity;
	private ConnectivityManager.NetworkCallback networkCallback;
	private volatile FujiCamera camera;
	private volatile boolean running;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		connectivity = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		setContentView(buildLayout());
		setStatus("1. On the camera, start WIRELESS COMMUNICATION.\n"
				+ "2. In Android Wi-Fi settings, join the camera's network (FUJIFILM-...). "
				+ "If the phone warns there is no internet, choose to stay connected.\n"
				+ "3. Come back here and tap Connect.");
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		final FujiCamera c = camera;
		if (c != null) new Thread(c::abort).start();
		releaseNetwork();
	}

	// ------------------------------------------------------------------ UI

	private View buildLayout() {
		int pad = dp(12);
		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setPadding(pad, pad, pad, pad);
		root.setFitsSystemWindows(true);

		status = new TextView(this);
		status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
		root.addView(status, matchWrap());

		LinearLayout buttons = new LinearLayout(this);
		buttons.setOrientation(LinearLayout.HORIZONTAL);
		connectButton = new Button(this);
		connectButton.setText("Connect");
		connectButton.setOnClickListener(v -> startTest());
		buttons.addView(connectButton);
		Button share = new Button(this);
		share.setText("Share log");
		share.setOnClickListener(v -> shareLog());
		buttons.addView(share);
		root.addView(buttons, matchWrap());

		plainBrowse = new CheckBox(this);
		plainBrowse.setText("Plain browse mode (try this if the normal attempt fails)");
		root.addView(plainBrowse, matchWrap());

		HorizontalScrollView thumbScroll = new HorizontalScrollView(this);
		thumbRow = new LinearLayout(this);
		thumbRow.setOrientation(LinearLayout.HORIZONTAL);
		thumbScroll.addView(thumbRow);
		root.addView(thumbScroll, matchWrap());

		logScroll = new ScrollView(this);
		logView = new TextView(this);
		logView.setTypeface(Typeface.MONOSPACE);
		logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
		logView.setTextIsSelectable(true);
		logScroll.addView(logView);
		root.addView(logScroll, new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
		return root;
	}

	private LinearLayout.LayoutParams matchWrap() {
		return new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
	}

	private int dp(int v) {
		return Math.round(v * getResources().getDisplayMetrics().density);
	}

	private void setStatus(final String s) {
		ui.post(() -> status.setText(s));
	}

	private void log(final String line) {
		synchronized (logText) {
			logText.append(line).append('\n');
		}
		ui.post(() -> {
			logView.append(line + "\n");
			logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
		});
	}

	private void shareLog() {
		String text;
		synchronized (logText) {
			text = logText.toString();
		}
		if (text.isEmpty()) text = "(log is empty)";
		Intent send = new Intent(Intent.ACTION_SEND);
		send.setType("text/plain");
		send.putExtra(Intent.EXTRA_SUBJECT, "XGallery connection log");
		send.putExtra(Intent.EXTRA_TEXT, text);
		startActivity(Intent.createChooser(send, "Share log"));
	}

	// ---------------------------------------------------------------- test

	private void startTest() {
		if (running) return;
		running = true;
		connectButton.setEnabled(false);
		thumbRow.removeAllViews();
		logView.setText("");
		synchronized (logText) {
			logText.setLength(0);
		}
		final boolean plain = plainBrowse.isChecked();

		log("XGallery " + VERSION);
		log("Phone: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE
				+ " (API " + Build.VERSION.SDK_INT + ")");
		log("Mode: " + (plain ? "plain browse" : "normal"));
		setStatus("Looking for a Wi-Fi network...");

		// Ask for the Wi-Fi network even though it has no internet access. Sockets created from it
		// stay on the camera's network instead of being routed over mobile data.
		NetworkRequest request = new NetworkRequest.Builder()
				.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
				.removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
				.build();
		final AtomicBoolean started = new AtomicBoolean(false);
		networkCallback = new ConnectivityManager.NetworkCallback() {
			@Override
			public void onAvailable(Network network) {
				if (!started.compareAndSet(false, true)) return;
				new Thread(() -> runTest(network, plain), "fuji-test").start();
			}

			@Override
			public void onUnavailable() {
				if (!started.compareAndSet(false, true)) return;
				log("No Wi-Fi network available.");
				finish("FAILED: the phone is not connected to Wi-Fi. Join the camera's FUJIFILM-... "
						+ "network in Android settings, then tap Connect again.");
			}

			@Override
			public void onLost(Network network) {
				log("Wi-Fi network lost");
			}
		};
		try {
			connectivity.requestNetwork(request, networkCallback, 15000);
		} catch (RuntimeException e) {
			log("requestNetwork failed: " + e);
			finish("FAILED: could not ask Android for the Wi-Fi network (" + e.getMessage() + ")");
		}
	}

	private void runTest(Network network, boolean plain) {
		String result;
		FujiCamera cam = null;
		try {
			LinkProperties lp = connectivity.getLinkProperties(network);
			if (lp != null) {
				log("Wi-Fi interface: " + lp.getInterfaceName());
				log("Wi-Fi addresses: " + lp.getLinkAddresses());
				log("Wi-Fi routes: " + lp.getRoutes());
			}
			NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
			if (caps != null) log("Wi-Fi capabilities: " + caps);

			setStatus("Connecting to the camera... If the camera shows a prompt, press OK on it.");
			cam = new FujiCamera(() -> network.getSocketFactory().createSocket(), this::log);
			camera = cam;
			long t0 = System.currentTimeMillis();
			cam.connect(CLIENT_NAME, plain);
			long setupMs = System.currentTimeMillis() - t0;
			log(String.format(Locale.US, "Setup took %d ms", setupMs));

			int count = cam.objectCount;
			int n = Math.max(0, Math.min(count, THUMBS_TO_TEST));
			setStatus("Connected to " + cam.cameraName + ". Reading " + n + " of " + count + " photos...");
			long thumbTotal = 0;
			int thumbOk = 0;
			for (int handle = 1; handle <= n; handle++) {
				long a = System.currentTimeMillis();
				ObjectInfo oi = cam.getObjectInfo(handle);
				log("  info: " + (oi == null ? "(none) for handle " + handle : oi.toString()));
				byte[] jpeg = cam.getThumbnail(handle);
				long ms = System.currentTimeMillis() - a;
				if (jpeg == null) {
					log("  thumbnail " + handle + ": none");
					continue;
				}
				Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
				log(String.format(Locale.US, "  thumbnail %d: %d bytes, %s, info+thumb %d ms", handle,
						jpeg.length, bmp == null ? "NOT a decodable image"
								: bmp.getWidth() + "x" + bmp.getHeight(), ms));
				if (bmp != null) {
					thumbOk++;
					thumbTotal += ms;
					showThumb(bmp);
				}
			}
			result = String.format(Locale.US,
					"OK: connected to %s, %d photos, setup %d ms, %d/%d thumbnails, avg %d ms each",
					cam.cameraName, count, setupMs, thumbOk, n, thumbOk == 0 ? 0 : thumbTotal / thumbOk);
		} catch (Throwable t) {
			StringWriter sw = new StringWriter();
			t.printStackTrace(new PrintWriter(sw));
			log(sw.toString());
			result = "FAILED: " + t.getMessage();
		} finally {
			if (cam != null) cam.close();
			camera = null;
		}
		finish(result);
	}

	private void showThumb(final Bitmap bmp) {
		ui.post(() -> {
			ImageView iv = new ImageView(this);
			iv.setImageBitmap(bmp);
			iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
			LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(96), dp(72));
			p.setMargins(0, dp(6), dp(6), dp(6));
			thumbRow.addView(iv, p);
		});
	}

	private void finish(String result) {
		log("RESULT: " + result);
		releaseNetwork();
		running = false;
		final String shown = result + "\n\nTap Share log to send the log.";
		ui.post(() -> {
			status.setText(shown);
			connectButton.setEnabled(true);
		});
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
}
