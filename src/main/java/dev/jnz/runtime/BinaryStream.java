package dev.jnz.runtime;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

final class BinaryStream {
    private BinaryStream() {}

    static byte[] limited(InputStream input, int max, String name) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count > max - result.size()) throw new IOException("资源超限 " + name);
            result.write(buffer, 0, count);
        }
        return result.toByteArray();
    }

    static void end(DataInputStream input, String name) throws IOException {
        if (input.read() != -1) throw new IOException("索引含多余数据 " + name);
    }
}
