package app.xgallery;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.IOException;
import java.util.Locale;

/** Saves files into the phone's shared folders (photos show up in the Gallery app). */
final class PhotoSaver {

	private PhotoSaver() {
	}

	static String folderFor(String filename) {
		String n = filename.toLowerCase(Locale.US);
		if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return Environment.DIRECTORY_PICTURES + "/XGallery";
		if (n.endsWith(".mov") || n.endsWith(".mp4")) return Environment.DIRECTORY_MOVIES + "/XGallery";
		return Environment.DIRECTORY_DOWNLOADS + "/XGallery";
	}

	/** Creates a hidden (pending) file; call finish() when all bytes are written. */
	static Uri begin(Context ctx, String filename) throws IOException {
		String n = filename.toLowerCase(Locale.US);
		Uri collection;
		String mime;
		if (n.endsWith(".jpg") || n.endsWith(".jpeg")) {
			collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
			mime = "image/jpeg";
		} else if (n.endsWith(".mov") || n.endsWith(".mp4")) {
			collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
			mime = n.endsWith(".mov") ? "video/quicktime" : "video/mp4";
		} else {
			collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
			mime = "application/octet-stream";
		}
		ContentValues v = new ContentValues();
		v.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
		v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
		v.put(MediaStore.MediaColumns.RELATIVE_PATH, folderFor(filename));
		v.put(MediaStore.MediaColumns.IS_PENDING, 1);
		Uri uri = ctx.getContentResolver().insert(collection, v);
		if (uri == null) throw new IOException("Android refused to create " + filename);
		return uri;
	}

	static void finish(Context ctx, Uri uri) {
		ContentValues v = new ContentValues();
		v.put(MediaStore.MediaColumns.IS_PENDING, 0);
		ctx.getContentResolver().update(uri, v, null, null);
	}

	static void discard(Context ctx, Uri uri) {
		try {
			ctx.getContentResolver().delete(uri, null, null);
		} catch (RuntimeException ignored) {
			// nothing more to do
		}
	}
}
