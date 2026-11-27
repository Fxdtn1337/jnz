package dev.jnz;

import dev.jnz.frontend.Program;
import dev.jnz.frontend.Frontend;
import dev.jnz.frontend.PreObfuscator;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.Collections;

final class Report {
    private Report() {}
    static byte[] render(String status, List<Program> programs, List<String[]> skips) {
        return render(status, programs, skips, Collections.<Integer>emptySet(), Collections.<Frontend.Initializer>emptyList(),
                new PreObfuscator.Stats(), 0, 0, "", 0, 0);
    }
    static byte[] render(String status, List<Program> programs, List<String[]> skips, Set<Integer> direct,
                         List<Frontend.Initializer> initializers, PreObfuscator.Stats pre, int bridges,
                         int loaderClasses, String nativeBackend, int compactedPools, int preservedPools) {
        StringBuilder json = new StringBuilder("{\n  \"status\": ").append(quote(status))
                .append(",\n  \"version\": ").append(quote(CliInfo.VERSION))
                .append(",\n  \"jniBridges\": ").append(bridges)
                .append(",\n  \"loaderClasses\": ").append(loaderClasses)
                .append(",\n  \"nativeBackend\": ").append(quote(nativeBackend))
                .append(",\n  \"javaBridges\": ").append(programs.stream().mapToInt(Program::javaBridges).sum())
                .append(",\n  \"constantPools\": {\"compacted\":").append(compactedPools)
                .append(",\"preserved\":").append(preservedPools).append('}')
                .append(",\n  \"preObfuscation\": {\"literals\":").append(pre.literals)
                .append(",\"aesLiterals\":").append(pre.aesLiterals)
                .append(",\"xorLiterals\":").append(pre.xorLiterals)
                .append(",\"dynamicSites\":").append(pre.dynamicSites).append(",\"jumps\":").append(pre.jumps)
                .append(",\"dispatchers\":").append(pre.dispatchers)
                .append(",\"skippedClasses\":").append(pre.skippedClasses)
                .append("},\n  \"methods\": [");
        for (int i=0; i<programs.size(); i++) {
            Program p = programs.get(i);
            if (i > 0) json.append(',');
            json.append("\n    {\"id\":").append(p.id).append(",\"class\":").append(quote(p.owner))
                .append(",\"entry\":").append(quote(p.entry))
                .append(",\"method\":").append(quote(p.name)).append(",\"descriptor\":").append(quote(p.desc))
                .append(",\"backend\":").append(quote(direct.contains(p.id) ? "direct" : "vm"))
                .append(",\"vmLayers\":").append(nativeBackend.isEmpty() || direct.contains(p.id)
                        ? 0 : nativeBackend.equals("rust") ? 2 : 1)
                .append(",\"links\":").append(p.links.size())
                .append(",\"javaBridges\":").append(p.javaBridges())
                .append(",\"handlers\":").append(p.handlers.length / 4)
                .append(",\"instructions\":").append(p.code.length/3).append('}');
        }
        json.append("\n  ],\n  \"initializers\": [");
        for (int i = 0; i < initializers.size(); i++) {
            Frontend.Initializer item = initializers.get(i);
            if (i > 0) json.append(',');
            json.append("\n    {\"class\":").append(quote(item.owner)).append(",\"entry\":").append(quote(item.entry))
                .append(",\"method\":").append(quote(item.name)).append(",\"descriptor\":").append(quote(item.descriptor))
                .append(",\"helper\":").append(quote(item.helperName)).append(",\"helperDescriptor\":").append(quote(item.helperDescriptor))
                .append(",\"retainedInstructions\":").append(item.retainedInstructions)
                .append(",\"loweredInstructions\":").append(item.loweredInstructions).append('}');
        }
        json.append("\n  ],\n  \"skips\": [");
        for (int i=0; i<skips.size(); i++) {
            String[] s = skips.get(i);
            if (i > 0) json.append(',');
            json.append("\n    {\"class\":").append(quote(s[0])).append(",\"method\":").append(quote(s[1]))
                .append(",\"descriptor\":").append(quote(s[2])).append(",\"reason\":").append(quote(s[3])).append('}');
        }
        return json.append("\n  ]\n}\n").toString().getBytes(StandardCharsets.UTF_8);
    }
    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i=0; i<value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 32 || Character.isSurrogate(c)) out.append(String.format("\\u%04x", (int)c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
}
