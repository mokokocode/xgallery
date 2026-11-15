package app.xgallery;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import app.xgallery.ptp.ObjectInfo;

/** The gallery: tap a photo to view it, long-press to select, then download. */
public final class MainActivity extends Activity implements CameraSession.Listener {

	private static final int COLUMNS = 4;
	private static final int MENU_LOG = 1;
	private static final int MENU_SELECT_ALL = 2;
	private static final int MENU_DOWNLOAD = 3;
	private static final int MENU_CANCEL = 4;

	private CameraSession session;
	private GridView grid;
	private ThumbAdapter adapter;
	private LinearLayout statusPanel;
	private TextView statusText;
	private Button connectButton;
	private LinearLayout downloadBar;
	private TextView downloadText;
	private ProgressBar downloadProgress;

	private final Set<Integer> selected = new HashSet<>();
	private boolean selecting;
	private int shownCount = -1;

	static final LruCache<Integer, Bitmap> bitmaps = new LruCache<Integer, Bitmap>(
			(int) Math.min(48L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 6)) {
		@Override
		protected int sizeOf(Integer key, Bitmap b) {
			return b.getByteCount();
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		session = CameraSession.get(this);
		setContentView(buildLayout());
		if (session.state() == CameraSession.State.IDLE) session.connect();
	}

	@Override
	protected void onStart() {
		super.onStart();
		session.addListener(this);
		onSessionChanged();
		adapter.notifyDataSetChanged();
	}

	@Override
	protected void onStop() {
		super.onStop();
		session.removeListener(this);
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		if (isFinishing()) {
			session.disconnect();
			bitmaps.evictAll();
		}
	}

	@Override
	public void onBackPressed() {
		if (selecting) {
			endSelection();
			return;
		}
		super.onBackPressed();
	}

	// ------------------------------------------------------------------ layout

	private View buildLayout() {
		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setFitsSystemWindows(true);

		statusPanel = new LinearLayout(this);
		statusPanel.setOrientation(LinearLayout.VERTICAL);
		statusPanel.setPadding(dp(16), dp(12), dp(16), dp(12));
		statusText = new TextView(this);
		statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
		statusPanel.addView(statusText);
		connectButton = new Button(this);
		connectButton.setText("Connect");
		connectButton.setOnClickListener(v -> session.connect());
		statusPanel.addView(connectButton, new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
		root.addView(statusPanel, matchWrap());

		grid = new GridView(this);
		grid.setNumColumns(COLUMNS);
		grid.setHorizontalSpacing(dp(2));
		grid.setVerticalSpacing(dp(2));
		grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
		adapter = new ThumbAdapter();
		grid.setAdapter(adapter);
		grid.setOnItemClickListener((parent, view, position, id) -> onTap(position + 1));
		grid.setOnItemLongClickListener((parent, view, position, id) -> {
			onLongPress(position + 1);
			return true;
		});
		grid.setOnScrollListener(new AbsListView.OnScrollListener() {
			@Override
			public void onScrollStateChanged(AbsListView view, int scrollState) {
			}

			@Override
			public void onScroll(AbsListView view, int first, int visible, int total) {
				if (visible > 0) session.setVisibleRange(first + 1, first + visible);
			}
		});
		root.addView(grid, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

		downloadBar = new LinearLayout(this);
		downloadBar.setOrientation(LinearLayout.VERTICAL);
		downloadBar.setPadding(dp(16), dp(8), dp(16), dp(8));
		downloadText = new TextView(this);
		downloadBar.addView(downloadText);
		downloadProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
		downloadProgress.setMax(100);
		downloadBar.addView(downloadProgress, matchWrap());
		root.addView(downloadBar, matchWrap());
		return root;
	}

	private LinearLayout.LayoutParams matchWrap() {
		return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT);
	}

	private int dp(int v) {
		return Math.round(v * getResources().getDisplayMetrics().density);
	}

	// ------------------------------------------------------------ session updates

	@Override
	public void onSessionChanged() {
		CameraSession.State st = session.state();
		boolean connected = st == CameraSession.State.CONNECTED;
		statusPanel.setVisibility(connected ? View.GONE : View.VISIBLE);
		statusText.setText(session.message());
		connectButton.setVisibility(st == CameraSession.State.IDLE || st == CameraSession.State.DISCONNECTED
				? View.VISIBLE : View.GONE);

		int count = connected ? session.photoCount() : 0;
		if (count != shownCount) {
			shownCount = count;
			bitmaps.evictAll();
			selected.clear();
			if (selecting) endSelection();
			adapter.notifyDataSetChanged();
		}

		if (session.downloadTotal() > 0 && session.downloading()) {
			downloadBar.setVisibility(View.VISIBLE);
			int current = session.downloadDone() + session.downloadFailed() + 1;
			downloadText.setText(String.format(Locale.US, "Downloading %d of %d: %s",
					current, session.downloadTotal(), session.downloadName()));
			downloadProgress.setProgress(session.downloadPercent());
		} else {
			downloadBar.setVisibility(View.GONE);
		}
		updateTitle();
	}

	@Override
	public void onThumbnail(int handle) {
		int pos = handle - 1;
		int first = grid.getFirstVisiblePosition();
		View cell = grid.getChildAt(pos - first);
		if (cell instanceof ThumbCell) ((ThumbCell) cell).bind(handle);
	}

	private void updateTitle() {
		if (selecting) {
			setTitle(selected.size() + " selected");
		} else if (session.state() == CameraSession.State.CONNECTED) {
			setTitle(session.cameraName() + "  ·  " + session.photoCount() + " photos");
		} else {
			setTitle("XGallery");
		}
		invalidateOptionsMenu();
	}

	// --------------------------------------------------------------- selection

	private void onTap(int handle) {
		if (selecting) {
			toggle(handle);
			return;
		}
		Intent i = new Intent(this, ViewerActivity.class);
		i.putExtra(ViewerActivity.EXTRA_HANDLE, handle);
		startActivity(i);
	}

	private void onLongPress(int handle) {
		if (!selecting) {
			selecting = true;
			selected.clear();
		}
		toggle(handle);
	}

	private void toggle(int handle) {
		if (!selected.remove(handle)) selected.add(handle);
		if (selected.isEmpty()) {
			endSelection();
			return;
		}
		refreshVisibleCells();
		updateTitle();
	}

	private void endSelection() {
		selecting = false;
		selected.clear();
		refreshVisibleCells();
		updateTitle();
	}

	private boolean allSelected() {
		return shownCount > 0 && selected.size() == shownCount;
	}

	private void refreshVisibleCells() {
		for (int i = 0; i < grid.getChildCount(); i++) {
			View v = grid.getChildAt(i);
			if (v instanceof ThumbCell) ((ThumbCell) v).bind(((ThumbCell) v).handle);
		}
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		menu.clear();
		if (selecting) {
			menu.add(0, MENU_SELECT_ALL, 0, allSelected() ? "Select none" : "Select all")
					.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
			menu.add(0, MENU_DOWNLOAD, 1, "Download")
					.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
			menu.add(0, MENU_CANCEL, 2, "Cancel")
					.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
		} else {
			menu.add(0, MENU_LOG, 0, "Log")
					.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
		}
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case MENU_LOG:
				startActivity(new Intent(this, LogActivity.class));
				return true;
			case MENU_SELECT_ALL:
				if (allSelected()) {
					endSelection();
				} else {
					for (int h = 1; h <= shownCount; h++) selected.add(h);
					refreshVisibleCells();
					updateTitle();
				}
				return true;
			case MENU_DOWNLOAD:
				List<Integer> list = new ArrayList<>(selected);
				Collections.sort(list);
				session.download(list);
				endSelection();
				return true;
			case MENU_CANCEL:
				endSelection();
				return true;
		}
		return super.onOptionsItemSelected(item);
	}

	// ----------------------------------------------------------------- the grid

	static Bitmap decodeThumb(CameraSession s, int handle) {
		Bitmap b = bitmaps.get(handle);
		if (b != null) return b;
		byte[] jpeg = s.thumbnail(handle);
		if (jpeg == null) return null;
		BitmapFactory.Options o = new BitmapFactory.Options();
		o.inPreferredConfig = Bitmap.Config.RGB_565;
		b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
		if (b != null) bitmaps.put(handle, b);
		return b;
	}

	private final class ThumbAdapter extends BaseAdapter {
		@Override
		public int getCount() {
			return Math.max(shownCount, 0);
		}

		@Override
		public Object getItem(int position) {
			return position + 1;
		}

		@Override
		public long getItemId(int position) {
			return position + 1;
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			ThumbCell cell = convertView instanceof ThumbCell ? (ThumbCell) convertView : new ThumbCell(MainActivity.this);
			cell.bind(position + 1);
			return cell;
		}
	}

	private final class ThumbCell extends FrameLayout {
		int handle;
		private final ImageView image;
		private final View shade;
		private final TextView check;

		ThumbCell(Context c) {
			super(c);
			setBackgroundColor(0xFF202020);
			image = new ImageView(c);
			image.setScaleType(ImageView.ScaleType.CENTER_CROP);
			addView(image, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
			shade = new View(c);
			shade.setBackgroundColor(0x663D7EFF);
			addView(shade, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
			check = new TextView(c);
			check.setGravity(Gravity.CENTER);
			check.setTextColor(Color.WHITE);
			check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
			LayoutParams lp = new LayoutParams(dp(24), dp(24), Gravity.TOP | Gravity.END);
			lp.setMargins(0, dp(4), dp(4), 0);
			addView(check, lp);
		}

		void bind(int h) {
			handle = h;
			Bitmap b = decodeThumb(session, h);
			image.setImageBitmap(b);
			ObjectInfo oi = session.info(h);
			image.setRotation(oi == null ? 0 : oi.rotationDegrees());
			boolean isSel = selected.contains(h);
			shade.setVisibility(isSel ? VISIBLE : GONE);
			if (selecting) {
				check.setVisibility(VISIBLE);
				check.setText(isSel ? "✓" : "");
				check.setBackground(circle(isSel));
			} else {
				check.setVisibility(GONE);
			}
		}

		@Override
		protected void onMeasure(int widthSpec, int heightSpec) {
			super.onMeasure(widthSpec, widthSpec); // square cells
		}
	}

	private android.graphics.drawable.GradientDrawable circle(boolean filled) {
		android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
		d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
		d.setColor(filled ? 0xFF3D7EFF : 0x55000000);
		d.setStroke(dp(2), Color.WHITE);
		return d;
	}
}
