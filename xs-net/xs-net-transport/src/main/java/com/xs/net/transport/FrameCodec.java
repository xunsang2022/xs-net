package com.xs.net.transport;

import com.xs.net.message.TransportKind;

public interface FrameCodec {
    TransportKind kind();

    byte[] encode(byte[] payload);

    byte[] decode(byte[] frame);
}
