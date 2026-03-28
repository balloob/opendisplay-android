package org.opendisplay;

/**
 * OpenDisplay Basic Standard WiFi protocol - packet encoding/decoding.
 * All multi-byte values are little-endian.
 *
 * Frame format: [length:2 LE][version:1][single_packets...][crc16:2 LE]
 * Single packet: [number:1][id:1][payload...]
 */
public class OpenDisplayProtocol {

    public static final int PROTOCOL_VERSION = 0x01;
    public static final int DEFAULT_PORT = 2446;
    public static final String MDNS_SERVICE_TYPE = "_opendisplay._tcp.";

    // Packet IDs (display -> server)
    public static final int PKT_DISPLAY_ANNOUNCEMENT = 0x01;
    public static final int PKT_IMAGE_REQUEST = 0x02;

    // Packet IDs (server -> display)
    public static final int PKT_NO_IMAGE = 0x81;
    public static final int PKT_NEW_IMAGE = 0x82;
    public static final int PKT_REQUEST_CONFIG = 0x83;

    // Color schemes
    public static final int COLOR_MONOCHROME = 0x00;
    public static final int COLOR_BW_RED = 0x01;
    public static final int COLOR_BW_YELLOW = 0x02;
    public static final int COLOR_BW_RED_YELLOW = 0x03;
    public static final int COLOR_6COLOR = 0x04;

    // Refresh types
    public static final int REFRESH_NORMAL = 0x00;
    public static final int REFRESH_FAST = 0x01;

    // AC powered sentinel
    public static final int BATTERY_AC_POWERED = 0xFF;

    // ---------------------------------------------------------------
    // CRC16-CCITT (poly 0x1021, init 0xFFFF, no reflection)
    // ---------------------------------------------------------------

    public static int crc16(byte[] data, int offset, int length) {
        int crc = 0xFFFF;
        for (int i = offset; i < offset + length; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ 0x1021;
                } else {
                    crc <<= 1;
                }
                crc &= 0xFFFF;
            }
        }
        return crc;
    }

    // ---------------------------------------------------------------
    // Frame building
    // ---------------------------------------------------------------

    /**
     * Wraps one or more single packets into an outer frame:
     * [length:4 LE][version:1][packets...][crc:2 LE]
     */
    public static byte[] buildFrame(byte[]... singlePackets) {
        int packetsLen = 0;
        for (byte[] p : singlePackets) {
            packetsLen += p.length;
        }

        int totalLen = 4 + 1 + packetsLen + 2;
        byte[] frame = new byte[totalLen];

        writeUint32LE(frame, 0, totalLen);
        frame[4] = (byte) PROTOCOL_VERSION;

        int pos = 5;
        for (byte[] p : singlePackets) {
            System.arraycopy(p, 0, frame, pos, p.length);
            pos += p.length;
        }

        // CRC over version + packets
        int crc = crc16(frame, 4, 1 + packetsLen);
        writeUint16LE(frame, pos, crc);

        return frame;
    }

    /** Builds a single_packet: [number:1][id:1][payload...] */
    public static byte[] buildSinglePacket(int number, int id, byte[] payload) {
        int payloadLen = (payload != null) ? payload.length : 0;
        byte[] pkt = new byte[2 + payloadLen];
        pkt[0] = (byte) number;
        pkt[1] = (byte) id;
        if (payload != null) {
            System.arraycopy(payload, 0, pkt, 2, payloadLen);
        }
        return pkt;
    }

    // ---------------------------------------------------------------
    // Outgoing packet builders (display -> server)
    // ---------------------------------------------------------------

    /** Builds a complete image request frame (0x02). */
    public static byte[] buildImageRequest(int batteryPercent, int rssiDbm) {
        byte[] payload = new byte[] {
            (byte) batteryPercent,
            (byte) rssiDbm
        };
        byte[] single = buildSinglePacket(0, PKT_IMAGE_REQUEST, payload);
        return buildFrame(single);
    }

    /** Builds a complete display announcement frame (0x01). */
    public static byte[] buildAnnouncement(DisplayConfig cfg) {
        byte[] payload = new byte[16];
        int i = 0;
        writeUint16LE(payload, i, cfg.width);            i += 2;
        writeUint16LE(payload, i, cfg.height);           i += 2;
        payload[i++] = (byte) cfg.colorScheme;
        writeUint16LE(payload, i, cfg.firmwareId);       i += 2;
        writeUint16LE(payload, i, cfg.firmwareVersion);  i += 2;
        writeUint16LE(payload, i, cfg.manufacturerId);   i += 2;
        writeUint16LE(payload, i, cfg.modelId);          i += 2;
        writeUint16LE(payload, i, cfg.maxCompressedSize); i += 2;
        payload[i] = (byte) cfg.rotation;

        byte[] single = buildSinglePacket(0, PKT_DISPLAY_ANNOUNCEMENT, payload);
        return buildFrame(single);
    }

    // ---------------------------------------------------------------
    // Incoming frame parser
    // ---------------------------------------------------------------

    /**
     * Parses an outer frame. Returns null if invalid or CRC fails.
     * Works for both client-side (parsing server responses) and
     * server-side (parsing client requests).
     */
    public static ParsedFrame parseFrame(byte[] data, int length) {
        if (length < 8) return null;

        int frameLen = (int) readUint32LE(data, 0);
        if (frameLen != length) return null;

        int version = data[4] & 0xFF;
        if (version != PROTOCOL_VERSION) return null;

        // Verify CRC
        int receivedCrc = readUint16LE(data, length - 2);
        int calculatedCrc = crc16(data, 4, length - 6);
        if (receivedCrc != calculatedCrc) return null;

        // Parse first single packet
        int offset = 5; // 4 (length) + 1 (version)
        int packetsEnd = length - 2;

        if (offset + 2 > packetsEnd) return null;

        int packetNumber = data[offset++] & 0xFF;
        int packetId = data[offset++] & 0xFF;

        ParsedFrame frame = new ParsedFrame();
        frame.packetNumber = packetNumber;
        frame.packetId = packetId;

        switch (packetId) {
            // Server -> Display
            case PKT_NO_IMAGE:
                if (offset + 4 > packetsEnd) return null;
                frame.pollInterval = readUint32LE(data, offset);
                break;

            case PKT_NEW_IMAGE:
                if (offset + 9 > packetsEnd) return null;
                frame.imageLength = (int) readUint32LE(data, offset);
                offset += 4;
                frame.pollInterval = readUint32LE(data, offset);
                offset += 4;
                frame.refreshType = data[offset++] & 0xFF;
                if (offset + frame.imageLength > packetsEnd) return null;
                frame.imageData = new byte[frame.imageLength];
                System.arraycopy(data, offset, frame.imageData, 0, frame.imageLength);
                break;

            case PKT_REQUEST_CONFIG:
                break;

            // Display -> Server
            case PKT_IMAGE_REQUEST:
                if (offset + 2 > packetsEnd) return null;
                frame.batteryPercent = data[offset++] & 0xFF;
                frame.rssi = data[offset++];
                break;

            case PKT_DISPLAY_ANNOUNCEMENT:
                if (offset + 16 > packetsEnd) return null;
                frame.announcedWidth = readUint16LE(data, offset);      offset += 2;
                frame.announcedHeight = readUint16LE(data, offset);     offset += 2;
                frame.announcedColorScheme = data[offset++] & 0xFF;
                frame.announcedFirmwareId = readUint16LE(data, offset); offset += 2;
                frame.announcedFirmwareVer = readUint16LE(data, offset); offset += 2;
                frame.announcedManufacturerId = readUint16LE(data, offset); offset += 2;
                frame.announcedModelId = readUint16LE(data, offset);    offset += 2;
                frame.announcedMaxCompressed = readUint16LE(data, offset); offset += 2;
                frame.announcedRotation = data[offset] & 0xFF;
                break;

            default:
                return null;
        }

        return frame;
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    public static int readUint16LE(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    public static long readUint32LE(byte[] data, int offset) {
        return (data[offset] & 0xFF)
             | ((data[offset + 1] & 0xFF) << 8)
             | ((data[offset + 2] & 0xFF) << 16)
             | ((long)(data[offset + 3] & 0xFF) << 24);
    }

    public static void writeUint16LE(byte[] buf, int offset, int value) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    public static void writeUint32LE(byte[] buf, int offset, long value) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
        buf[offset + 2] = (byte) ((value >> 16) & 0xFF);
        buf[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    // ---------------------------------------------------------------
    // Parsed frame data
    // ---------------------------------------------------------------

    public static class ParsedFrame {
        public int packetNumber;
        public int packetId;

        // Server -> Display: 0x81 (no image), 0x82 (new image)
        public long pollInterval;
        public int imageLength;
        public int refreshType;
        public byte[] imageData;

        // Display -> Server: 0x02 (image request)
        public int batteryPercent;
        public int rssi;

        // Display -> Server: 0x01 (announcement)
        public int announcedWidth;
        public int announcedHeight;
        public int announcedColorScheme;
        public int announcedFirmwareId;
        public int announcedFirmwareVer;
        public int announcedManufacturerId;
        public int announcedModelId;
        public int announcedMaxCompressed;
        public int announcedRotation;
    }
}
