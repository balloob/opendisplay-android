package org.opendisplay;

import org.junit.Test;
import static org.junit.Assert.*;

/** Unit tests for protocol encoding/decoding and image decoding - no server needed. */
public class ProtocolTest {

    @Test
    public void testCrcKnownValues() {
        // Empty data: init value 0xFFFF
        assertEquals(0xFFFF, OpenDisplayProtocol.crc16(new byte[0], 0, 0));
        // Standard test vector "123456789" = 0x29B1
        byte[] data = "123456789".getBytes();
        assertEquals(0x29B1, OpenDisplayProtocol.crc16(data, 0, data.length));
        // Single zero byte
        assertEquals(0xE1F0, OpenDisplayProtocol.crc16(new byte[]{0x00}, 0, 1));
        // Deterministic
        byte[] hello = "Hello OpenDisplay".getBytes();
        assertEquals(OpenDisplayProtocol.crc16(hello, 0, hello.length),
            OpenDisplayProtocol.crc16(hello, 0, hello.length));
    }

    @Test
    public void testImageRequestRoundTrip() {
        byte[] frame = OpenDisplayProtocol.buildImageRequest(0xFF, -50);
        OpenDisplayProtocol.ParsedFrame parsed =
            OpenDisplayProtocol.parseFrame(frame, frame.length);

        assertNotNull("Frame should parse", parsed);
        assertEquals(OpenDisplayProtocol.PKT_IMAGE_REQUEST, parsed.packetId);
        assertEquals(0xFF, parsed.batteryPercent);
    }

    @Test
    public void testAnnouncementRoundTrip() {
        DisplayConfig cfg = new DisplayConfig();
        cfg.width = 200;
        cfg.height = 300;
        cfg.colorScheme = OpenDisplayProtocol.COLOR_BW_RED;
        cfg.firmwareId = 42;
        cfg.firmwareVersion = 7;
        cfg.manufacturerId = 99;
        cfg.modelId = 5;
        cfg.maxCompressedSize = 1024;
        cfg.rotation = 2;

        byte[] frame = OpenDisplayProtocol.buildAnnouncement(cfg);
        OpenDisplayProtocol.ParsedFrame parsed =
            OpenDisplayProtocol.parseFrame(frame, frame.length);

        assertNotNull("Announcement should parse", parsed);
        assertEquals(OpenDisplayProtocol.PKT_DISPLAY_ANNOUNCEMENT, parsed.packetId);
        assertEquals(200, parsed.announcedWidth);
        assertEquals(300, parsed.announcedHeight);
        assertEquals(OpenDisplayProtocol.COLOR_BW_RED, parsed.announcedColorScheme);
        assertEquals(42, parsed.announcedFirmwareId);
        assertEquals(7, parsed.announcedFirmwareVer);
        assertEquals(99, parsed.announcedManufacturerId);
        assertEquals(5, parsed.announcedModelId);
        assertEquals(1024, parsed.announcedMaxCompressed);
        assertEquals(2, parsed.announcedRotation);
    }

    @Test
    public void testInvalidFrameReturnsNull() {
        assertNull(OpenDisplayProtocol.parseFrame(new byte[0], 0));
        assertNull(OpenDisplayProtocol.parseFrame(new byte[]{1, 2, 3}, 3));

        // Valid structure but wrong CRC
        byte[] frame = OpenDisplayProtocol.buildImageRequest(0xFF, 0);
        frame[frame.length - 1] ^= 0xFF; // corrupt CRC
        assertNull(OpenDisplayProtocol.parseFrame(frame, frame.length));
    }

    @Test
    public void testMonochromeAllWhite() {
        byte[] data = new byte[] {
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF
        };
        int[] pixels = ImageDecoder.decode(data, 8, 8, OpenDisplayProtocol.COLOR_MONOCHROME);
        assertNotNull(pixels);
        assertEquals(64, pixels.length);
        for (int i = 0; i < 64; i++) {
            assertEquals("Pixel " + i, ImageDecoder.WHITE, pixels[i]);
        }
    }

    @Test
    public void testMonochromeAllBlack() {
        byte[] data = new byte[8];
        int[] pixels = ImageDecoder.decode(data, 8, 8, OpenDisplayProtocol.COLOR_MONOCHROME);
        assertNotNull(pixels);
        for (int i = 0; i < 64; i++) {
            assertEquals("Pixel " + i, ImageDecoder.BLACK, pixels[i]);
        }
    }

    @Test
    public void testMonochromeCheckerboard() {
        // 8x8, cell_size=1: alternating pixels
        // Row 0: B W B W B W B W = 0b01010101 = 0x55
        // Row 1: W B W B W B W B = 0b10101010 = 0xAA
        byte[] data = new byte[] {
            0x55, (byte) 0xAA, 0x55, (byte) 0xAA,
            0x55, (byte) 0xAA, 0x55, (byte) 0xAA
        };
        int[] pixels = ImageDecoder.decode(data, 8, 8, OpenDisplayProtocol.COLOR_MONOCHROME);
        assertNotNull(pixels);
        assertEquals(ImageDecoder.BLACK, pixels[0]);   // (0,0)
        assertEquals(ImageDecoder.WHITE, pixels[1]);   // (1,0)
        assertEquals(ImageDecoder.WHITE, pixels[8]);   // (0,1)
        assertEquals(ImageDecoder.BLACK, pixels[9]);   // (1,1)
    }

    @Test
    public void testDecoderRejectsShortData() {
        // 8x8 mono needs (8+7)/8 * 8 = 8 bytes, give it 4
        assertNull(ImageDecoder.decode(new byte[4], 8, 8, OpenDisplayProtocol.COLOR_MONOCHROME));
        // 100x1 mono needs (100+7)/8 = 13 bytes per row, 1 row = 13 bytes total
        assertNull(ImageDecoder.decode(new byte[12], 100, 1, OpenDisplayProtocol.COLOR_MONOCHROME));
    }

    @Test
    public void testRowPadding() {
        // 10px wide, 2 rows, all white
        // Each row: ceil(10/8) = 2 bytes
        // Byte 0: 8 white pixels = 0xFF
        // Byte 1: 2 white pixels + 6 padding = 0xC0
        byte[] data = new byte[] {
            (byte) 0xFF, (byte) 0xC0,
            (byte) 0xFF, (byte) 0xC0
        };
        int[] pixels = ImageDecoder.decode(data, 10, 2, OpenDisplayProtocol.COLOR_MONOCHROME);
        assertNotNull(pixels);
        assertEquals(20, pixels.length);
        for (int i = 0; i < 20; i++) {
            assertEquals("Pixel " + i, ImageDecoder.WHITE, pixels[i]);
        }
    }

    @Test
    public void testLargeImageFrameRoundTrip() {
        // Verify uint32 length works for images > 64KB
        byte[] image = new byte[100000];
        for (int i = 0; i < image.length; i++) {
            image[i] = (byte) 0xAA;
        }

        // Manually build a new-image frame with uint32 fields
        byte[] imgLenBytes = new byte[4];
        OpenDisplayProtocol.writeUint32LE(imgLenBytes, 0, image.length);
        byte[] pollBytes = new byte[4];
        OpenDisplayProtocol.writeUint32LE(pollBytes, 0, 60);

        byte[] payload = new byte[4 + 4 + 1 + image.length];
        System.arraycopy(imgLenBytes, 0, payload, 0, 4);
        System.arraycopy(pollBytes, 0, payload, 4, 4);
        payload[8] = (byte) OpenDisplayProtocol.REFRESH_NORMAL;
        System.arraycopy(image, 0, payload, 9, image.length);

        byte[] single = OpenDisplayProtocol.buildSinglePacket(0,
            OpenDisplayProtocol.PKT_NEW_IMAGE, payload);
        byte[] frame = OpenDisplayProtocol.buildFrame(single);

        OpenDisplayProtocol.ParsedFrame parsed =
            OpenDisplayProtocol.parseFrame(frame, frame.length);
        assertNotNull(parsed);
        assertEquals(OpenDisplayProtocol.PKT_NEW_IMAGE, parsed.packetId);
        assertEquals(100000, parsed.imageLength);
        assertEquals(60, parsed.pollInterval);
        assertEquals(100000, parsed.imageData.length);
        assertEquals((byte) 0xAA, parsed.imageData[0]);
        assertEquals((byte) 0xAA, parsed.imageData[99999]);
    }
}
