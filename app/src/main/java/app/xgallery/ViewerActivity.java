package app.xgallery;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.Locale;

import app.xgallery.ptp.ObjectInfo;

/** Shows one photo: the thumbnail straight away, then the full-size image once it arrives. */
public final class ViewerActivity extends Activity {

	static final String EXTRA_HANDLE = "handle";
	private static final int MENU_DOWNLOAD = 1;
	private static final int MAX_SIDE = 2560;

	private final Handler main = new Handler(Looper.getMainLooper());
	private CameraSession session;
	private int handle;
	private ImageView image;
	private TextView progress;
	private CameraSession.PreviewJob job;
	private ObjectInfo loadedInfo;
	private byte[] loadedData;
	private boolean destroyed;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		session = CameraSession.get(this);
		handle = getIntent().getIntExtra(EXTRA_HANDLE, 1);
		ObjectInfo oi = session.info(handle);
		setTitle(oi != null ? oi.filename : "Photo " + handle);

		FrameLayout root = new FrameLayout(this);
		root.setFitsSystemWindows(true);
		root.setBackgroundColor(Color.BLACK);
		image = new ImageView(this);
		image.setScaleType(ImageView.ScaleType.FIT_CENTER);
		root.addView(image, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
				FrameLayout.LayoutParams.MATCH_PARENT));
		progress = new TextView(this);
		progress.setTextColor(Color.WHITE);
		progress.setBackgroundColor(0x99000000);
		int p = Math.round(8 * getResources().getDisplayMetrics().density);
		progress.setPadding(p * 2, p, p * 2, p);
		root.addView(progress, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
				FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
		setContentView(root);

		// Thumbnail first, so something shows immediately
		Bitmap thumb = MainActivity.decodeThumb(session, handle);
		if (thumb != null) {
			image.setImageBitmap(thumb);
			image.setRotation(oi == null ? 0 : oi.rotationDegrees());
		}
		progress.setText("Loading full size...");

		job = session.loadFullSize(handle, new CameraSession.PreviewCallback() {
			@Override
			public void onProgress(long done, long total) {
				progress.setText(String.format(Locale.US, "Loading full size... %d%%",
						total == 0 ? 100 : done * 100 / total));
			}

			@Override
			public void onLoaded(ObjectInfo info, byte[] data) {
				loadedInfo = info;
				loadedData = data;
				setTitle(info.filename);
				progress.setText("Decoding...");
				new Thread(() -> {
					Bitmap b = decodeFull(data);
					main.post(() -> {
						if (destroyed) return;
						if (b != null) {
							image.setRotation(0);
							image.setImageBitmap(b);
							progress.setVisibility(android.view.View.GONE);
						} else {
							progress.setText("Can't display this file (not a JPEG?). It can still be downloaded.");
						}
					});
				}, "decode").start();
			}

			@Override
			public void onFailed(String message) {
				progress.setText("Couldn't load the full photo: " + message);
			}
		});
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		destroyed = true;
		if (job != null) job.cancel();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		menu.add(0, MENU_DOWNLOAD, 0, "Download")
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == MENU_DOWNLOAD) {
			if (loadedData != null) {
				// Already have the full file, save it directly
				session.saveLoaded(loadedInfo, loadedData, null);
			} else {
				session.download(Collections.singletonList(handle));
				Toast.makeText(this, "Downloading", Toast.LENGTH_SHORT).show();
			}
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	/** Decodes at a size that fits the screen, upright according to the photo's EXIF data. */
	private static Bitmap decodeFull(byte[] data) {
		BitmapFactory.Options bounds = new BitmapFactory.Options();
		bounds.inJustDecodeBounds = true;
		BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
		if (bounds.outWidth <= 0) return null;
		int sample = 1;
		while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2;
		if (Math.max(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2;
		BitmapFactory.Options o = new BitmapFactory.Options();
		o.inSampleSize = sample;
		Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
		if (b == null) return null;
		int degrees = 0;
		try {
			ExifInterface exif = new ExifInterface(new ByteArrayInputStream(data));
			int orient = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
			if (orient == ExifInterface.ORIENTATION_ROTATE_90) degrees = 90;
			else if (orient == ExifInterface.ORIENTATION_ROTATE_180) degrees = 180;
			else if (orient == ExifInterface.ORIENTATION_ROTATE_270) degrees = 270;
		} catch (Exception ignored) {
			// no EXIF; show as is
		}
		if (degrees == 0) return b;
		Matrix m = new Matrix();
		m.postRotate(degrees);
		return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
	}
}
