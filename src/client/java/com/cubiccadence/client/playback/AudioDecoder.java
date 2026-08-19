package com.cubiccadence.client.playback;

import java.io.IOException;

public interface AudioDecoder {
    boolean supports(String contentType);

    DecodedAudio decode(byte[] encodedBytes) throws IOException;
}
