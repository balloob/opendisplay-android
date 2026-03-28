package org.opendisplay;

/**
 * Decodes OpenDisplay image data into an ARGB pixel array.
 * Platform-independent (no Android dependencies).
 *
 * Image data is row-padded: each row starts at a byte boundary.
 * This matches the encoding used by py-opendisplay.
 */
public class ImageDecoder {

    // ARGB color constants
    public static final int BLACK  = 0xFF000000;
    public static final int WHITE  = 0xFFFFFFFF;
    public static final int RED    = 0xFFFF0000;
    public static final int YELLOW = 0xFFFFFF00;
    public static final int BLUE   = 0xFF0000FF;
    public static final int GREEN  = 0xFF00FF00;

    /**
     * Decodes image data to an ARGB int array (width * height pixels).
     * Returns null if the data is too small for the given dimensions.
     */
    public static int[] decode(byte[] data, int width, int height, int colorScheme) {
        switch (colorScheme) {
            case OpenDisplayProtocol.COLOR_MONOCHROME:
                return decodeMonochrome(data, width, height);
            case OpenDisplayProtocol.COLOR_BW_RED:
                return decodeBwRed(data, width, height);
            case OpenDisplayProtocol.COLOR_BW_YELLOW:
                return decodeBwYellow(data, width, height);
            case OpenDisplayProtocol.COLOR_BW_RED_YELLOW:
                return decodeBwRedYellow(data, width, height);
            case OpenDisplayProtocol.COLOR_6COLOR:
                return decode6Color(data, width, height);
            default:
                return null;
        }
    }

    /**
     * Scheme 0x00: Monochrome, 1 bit per pixel, MSB = leftmost.
     * 0 = black, 1 = white. Each row padded to byte boundary.
     */
    private static int[] decodeMonochrome(byte[] data, int width, int height) {
        int bytesPerRow = (width + 7) / 8;
        int expectedBytes = bytesPerRow * height;
        if (data.length < expectedBytes) return null;

        int[] pixels = new int[width * height];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int byteIdx = y * bytesPerRow + x / 8;
                int bitPos = 7 - (x % 8);
                boolean white = ((data[byteIdx] >> bitPos) & 1) == 1;
                pixels[y * width + x] = white ? WHITE : BLACK;
            }
        }

        return pixels;
    }

    /**
     * Scheme 0x01: B/W + Red, two bitplanes.
     * Each plane is row-padded independently.
     * (0,0)=Black, (1,0)=White, (1,1)=Red
     */
    private static int[] decodeBwRed(byte[] data, int width, int height) {
        int bytesPerRow = (width + 7) / 8;
        int planeSize = bytesPerRow * height;
        if (data.length < planeSize * 2) return null;

        int[] pixels = new int[width * height];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int byteIdx = y * bytesPerRow + x / 8;
                int bitPos = 7 - (x % 8);

                int bw = (data[byteIdx] >> bitPos) & 1;
                int red = (data[planeSize + byteIdx] >> bitPos) & 1;

                int color;
                if (bw == 1 && red == 1) {
                    color = RED;
                } else if (bw == 1) {
                    color = WHITE;
                } else {
                    color = BLACK;
                }
                pixels[y * width + x] = color;
            }
        }

        return pixels;
    }

    /**
     * Scheme 0x02: B/W + Yellow, two bitplanes.
     * (0,0)=Black, (1,0)=White, (0,1)=Yellow
     */
    private static int[] decodeBwYellow(byte[] data, int width, int height) {
        int bytesPerRow = (width + 7) / 8;
        int planeSize = bytesPerRow * height;
        if (data.length < planeSize * 2) return null;

        int[] pixels = new int[width * height];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int byteIdx = y * bytesPerRow + x / 8;
                int bitPos = 7 - (x % 8);

                int bw = (data[byteIdx] >> bitPos) & 1;
                int yellow = (data[planeSize + byteIdx] >> bitPos) & 1;

                int color;
                if (yellow == 1) {
                    color = YELLOW;
                } else if (bw == 1) {
                    color = WHITE;
                } else {
                    color = BLACK;
                }
                pixels[y * width + x] = color;
            }
        }

        return pixels;
    }

    /**
     * Scheme 0x03: B/W + Red + Yellow, 2 bits per pixel, 4 pixels per byte.
     * Row-padded: each row starts at a byte boundary.
     * 0=Black, 1=White, 2=Yellow, 3=Red
     */
    private static int[] decodeBwRedYellow(byte[] data, int width, int height) {
        int bytesPerRow = (width + 3) / 4;
        int expectedBytes = bytesPerRow * height;
        if (data.length < expectedBytes) return null;

        int[] pixels = new int[width * height];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int byteIdx = y * bytesPerRow + x / 4;
                int shift = 6 - (x % 4) * 2;
                int value = (data[byteIdx] >> shift) & 0x03;

                int color;
                switch (value) {
                    case 1:  color = WHITE; break;
                    case 2:  color = YELLOW; break;
                    case 3:  color = RED; break;
                    default: color = BLACK; break;
                }
                pixels[y * width + x] = color;
            }
        }

        return pixels;
    }

    /**
     * Scheme 0x04: 6-color, 4 bits per pixel, 2 pixels per byte.
     * Row-padded: each row starts at a byte boundary.
     * 0=Black, 1=White, 2=Yellow, 3=Red, 5=Blue, 6=Green
     */
    private static int[] decode6Color(byte[] data, int width, int height) {
        int bytesPerRow = (width + 1) / 2;
        int expectedBytes = bytesPerRow * height;
        if (data.length < expectedBytes) return null;

        int[] pixels = new int[width * height];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int byteIdx = y * bytesPerRow + x / 2;
                int value;
                if (x % 2 == 0) {
                    value = (data[byteIdx] >> 4) & 0x0F;
                } else {
                    value = data[byteIdx] & 0x0F;
                }
                pixels[y * width + x] = nibbleToColor(value);
            }
        }

        return pixels;
    }

    private static int nibbleToColor(int value) {
        switch (value) {
            case 0: return BLACK;
            case 1: return WHITE;
            case 2: return YELLOW;
            case 3: return RED;
            case 5: return BLUE;
            case 6: return GREEN;
            default: return WHITE;
        }
    }
}
