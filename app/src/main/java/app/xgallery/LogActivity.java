package app.xgallery;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

/** The connection log, for troubleshooting. */
public final class LogActivity extends Activity {

	private static final int MENU_SHARE = 1;
	private static final int MENU_REFRESH = 2;
	/** Showing a huge log in one TextView is slow. */
	private static final int MAX_SHOWN_CHARS = 200_000;
	private static final int MAX_SHARED_CHARS = 300_000;

	private TextView text;
	private ScrollView scroll;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setTitle("Log");
		scroll = new ScrollView(this);
		scroll.setFitsSystemWindows(true);
		text = new TextView(this);
		text.setTypeface(Typeface.MONOSPACE);
		text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
		text.setTextIsSelectable(true);
		int p = Math.round(8 * getResources().getDisplayMetrics().density);
		text.setPadding(p, p, p, p);
		scroll.addView(text);
		setContentView(scroll);
		refresh();
	}

	private void refresh() {
		String log = CameraSession.get(this).logText();
		if (log.length() > MAX_SHOWN_CHARS) {
			log = "(earlier lines hidden)\n"
					+ log.substring(log.length() - MAX_SHOWN_CHARS);
		}
		text.setText(log.isEmpty() ? "(log is empty)" : log);
		scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		menu.add(0, MENU_REFRESH, 0, "Refresh")
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
		menu.add(0, MENU_SHARE, 1, "Share")
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == MENU_SHARE) {
			Intent send = new Intent(Intent.ACTION_SEND);
			send.setType("text/plain");
			send.putExtra(Intent.EXTRA_SUBJECT, "XGallery log");
			String log = CameraSession.get(this).logText();
			// Android can't pass very large text to another app; send the most recent part
			if (log.length() > MAX_SHARED_CHARS) log = log.substring(log.length() - MAX_SHARED_CHARS);
			send.putExtra(Intent.EXTRA_TEXT, log);
			startActivity(Intent.createChooser(send, "Share log"));
			return true;
		}
		if (item.getItemId() == MENU_REFRESH) {
			refresh();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}
}
