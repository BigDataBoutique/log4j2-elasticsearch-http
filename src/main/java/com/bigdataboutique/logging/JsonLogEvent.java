package com.bigdataboutique.logging;

import com.bigdataboutique.logging.log4j2.ElasticsearchHttpProvider;

import java.util.HashMap;

public class JsonLogEvent {
    private final HashMap<String, Object> map = new HashMap<>();

    /**
     * Create a json log event, with key-value pairs in the format key:value, skipping doubly defined keys
     * @param fields list of key:value strings to create fields from
     */
    public JsonLogEvent(String ... fields) {
        for (final String field : fields) {
            String[] f = field.split(":");
            if (f.length != 2) continue; // skip invalid records
            map.putIfAbsent(f[0], f[1]);
        }
    }

    public JsonLogEvent withMessage(final String message) {
        map.put("message", message);
        return this;
    }

    @Override
    public String toString() {
        return ElasticsearchHttpProvider.toJsonString(map);
    }
}
