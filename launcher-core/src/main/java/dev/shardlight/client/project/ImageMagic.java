package dev.shardlight.client.project;

/** Recognizes PNG and JPEG data by their signatures. */
final class ImageMagic {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    private ImageMagic() {
    }

    /** PNG signature followed by an IHDR chunk */
    static boolean isPng(byte[] d) {
        if (d == null || d.length < 24) {
            return false;
        }
        for (int i = 0; i < PNG.length; i++) {
            if (d[i] != PNG[i]) {
                return false;
            }
        }
        return d[12] == 'I' && d[13] == 'H' && d[14] == 'D' && d[15] == 'R';
    }

    /** JPEG start-of-image marker followed by another marker */
    static boolean isJpeg(byte[] d) {
        return d != null && d.length >= 4 && (d[0] & 0xff) == 0xff && (d[1] & 0xff) == 0xd8 && (d[2] & 0xff) == 0xff;
    }

    static boolean isPngOrJpeg(byte[] d) {
        return isPng(d) || isJpeg(d);
    }
}
