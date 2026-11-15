package app.xgallery.ptp;

/** Standard PTP ObjectInfo dataset, as returned by GetObjectInfo (0x1008). */
public final class ObjectInfo {
	public int handle;
	public int storageId;
	public int format;
	public long size;
	public int thumbSize;
	public int width;
	public int height;
	public String filename = "";
	public String dateCreated = "";
	/** EXIF-style orientation (1 = upright, 6 = rotate 90 clockwise, 8 = 270, 3 = 180). */
	public int orientation = 1;

	static ObjectInfo parse(int handle, byte[] d) {
		ObjectInfo oi = new ObjectInfo();
		oi.handle = handle;
		// Fixed part is 52 bytes; the strings follow
		if (d.length < 53) return oi;
		oi.storageId = FujiCamera.le32(d, 0);
		oi.format = FujiCamera.le16(d, 4);
		oi.size = FujiCamera.le32(d, 8) & 0xffffffffL;
		oi.thumbSize = FujiCamera.le32(d, 14);
		oi.width = FujiCamera.le32(d, 26);
		oi.height = FujiCamera.le32(d, 30);
		int[] pos = {52};
		oi.filename = readString(d, pos);
		oi.dateCreated = readString(d, pos);
		readString(d, pos); // date modified
		// Fuji puts e.g. "Orientation:6" in the keywords field (seen in libfuji's notes)
		String keywords = readString(d, pos);
		int i = keywords.indexOf("Orientation:");
		if (i >= 0) {
			try {
				oi.orientation = Integer.parseInt(keywords.substring(i + 12).trim().split("\\D")[0]);
			} catch (RuntimeException ignored) {
				// leave as 1
			}
		}
		return oi;
	}

	/** Degrees to rotate clockwise for display, from the EXIF-style orientation value. */
	public int rotationDegrees() {
		switch (orientation) {
			case 3: return 180;
			case 6: return 90;
			case 8: return 270;
			default: return 0;
		}
	}

	/** PTP string: one byte character count (including the terminating null), then UTF-16LE. */
	private static String readString(byte[] d, int[] pos) {
		if (pos[0] >= d.length) return "";
		int n = d[pos[0]++] & 0xff;
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < n && pos[0] + 1 < d.length; i++) {
			int c = (d[pos[0]] & 0xff) | ((d[pos[0] + 1] & 0xff) << 8);
			pos[0] += 2;
			if (c != 0) sb.append((char) c);
		}
		return sb.toString();
	}

	@Override
	public String toString() {
		return "#" + handle + " " + filename + " " + size + " bytes " + width + "x" + height + " " + dateCreated
				+ " orientation " + orientation;
	}
}
