package org.opendisplay;

/**
 * Configuration describing a display's capabilities,
 * sent to the server in the announcement packet (0x01).
 */
public class DisplayConfig {
    public int width = 100;
    public int height = 100;
    public int colorScheme = OpenDisplayProtocol.COLOR_MONOCHROME;
    public int firmwareId = 1;
    public int firmwareVersion = 1;
    public int manufacturerId = 0;
    public int modelId = 0;
    public int maxCompressedSize = 0;
    public int rotation = 0;
}
