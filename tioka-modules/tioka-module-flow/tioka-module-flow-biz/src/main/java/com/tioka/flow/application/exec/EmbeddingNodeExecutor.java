package com.tioka.flow.application.exec;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Component
public class EmbeddingNodeExecutor implements NodeExecutor {
    @Override
    public String type() { return "Embedding"; }

    @Override
    public Object execute(NodeExecContext ctx, Map<String, Object> node, Object input) throws Exception {
        String text = extractText(input);
        double[] vec = hashEmbed(text, 32);
        List<Double> values = new ArrayList<>(vec.length);
        for (double v : vec) values.add(v);
        return Map.of("dim", values.size(), "embedding", values, "text", text);
    }

    private static String extractText(Object input) {
        if (input == null) return "";
        if (input instanceof Map<?, ?> m) {
            Object t = m.containsKey("text") ? m.get("text") : m.get("prompt");
            return t == null ? String.valueOf(input) : String.valueOf(t);
        }
        return String.valueOf(input);
    }

    static double[] hashEmbed(String text, int dim) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] dig = md.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
        double[] out = new double[dim];
        for (int i = 0; i < dim; i++) {
            out[i] = ((dig[i % dig.length] & 0xff) / 255.0) * 2 - 1;
        }
        return out;
    }
}
