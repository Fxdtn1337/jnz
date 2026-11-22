package dev.jnz.frontend;

import java.util.Collections;
import java.util.List;

/** 一个方法的 native 签名和变换后的栈式指令。每条指令占三个 int。 */
public final class Program {
    public final int id, classId, maxLocals, maxStack;
    public final String entry, owner, name, desc;
    public final boolean isStatic;
    public final int[] code;
    public final int[] handlers;
    public final List<Linkage> links;

    Program(int id, int classId, String entry, String owner, String name, String desc, boolean isStatic,
            int maxLocals, int maxStack, int[] code, List<Linkage> links, int[] handlers) {
        this.id = id;
        this.classId = classId;
        this.entry = entry;
        this.owner = owner;
        this.name = name;
        this.desc = desc;
        this.isStatic = isStatic;
        this.maxLocals = maxLocals;
        this.maxStack = maxStack;
        this.code = code;
        this.handlers = handlers;
        this.links = Collections.unmodifiableList(links);
    }
}
