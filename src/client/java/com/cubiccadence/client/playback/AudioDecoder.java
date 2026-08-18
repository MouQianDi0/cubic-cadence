package com.cubiccadence.client.playback;

public interface AudioDecoder {
    boolean supports(String contentType);

    // TODO: define the PCM decode input/output contract
}
