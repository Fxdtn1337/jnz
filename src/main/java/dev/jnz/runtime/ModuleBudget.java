package dev.jnz.runtime;

import java.io.IOException;

/** 每次拼接都有一个独占游标，跨块核对长度。 */
final class ModuleBudget {
    private long remaining;

    ModuleBudget(long length) { remaining = length; }

    void accept(int count) throws IOException {
        if (count > remaining) throw new IOException("模块超出索引长度");
        remaining -= count;
    }

    boolean complete() { return remaining == 0; }
}
