package com.game.client;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

final class NettyFrameCodec {

    static final int MAX_FRAME_LENGTH = 65536;

    private NettyFrameCodec() {
    }

    static void write(DataOutputStream output, byte[] body) throws IOException {
        if (body.length == 0 || body.length > MAX_FRAME_LENGTH) {
            throw new IOException("Invalid frame length: " + body.length);
        }
        output.writeInt(body.length);
        output.write(body);
        output.flush();
    }

    static byte[] read(DataInputStream input) throws IOException {
        int bodyLength = input.readInt();
        if (bodyLength <= 0 || bodyLength > MAX_FRAME_LENGTH) {
            throw new IOException("Invalid frame length: " + bodyLength);
        }
        byte[] body = input.readNBytes(bodyLength);
        if (body.length != bodyLength) {
            throw new IOException("Incomplete frame: expected=" + bodyLength + ", actual=" + body.length);
        }
        return body;
    }
}
