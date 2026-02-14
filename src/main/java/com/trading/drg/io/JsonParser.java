package com.trading.drg.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Zero-dependency JSON parser.
 *
 * <p>
 * Implements a recursive descent parser for a subset of JSON sufficient to read
 * graph definitions. Avoids bringing in heavy dependencies like Jackson or Gson
 * to keep the library lightweight and suitable for restricted environments.
 *
 * <p>
 * Supports:
 * <ul>
 * <li>Objects {@code { ... }}</li>
 * <li>Arrays {@code [ ... ]}</li>
 * <li>Strings {@code "..."} with basic escaping</li>
 * <li>Numbers (Integer, Long, Double)</li>
 * <li>Booleans and Null</li>
 * </ul>
 */
public final class JsonParser {
    private JsonParser() {
        // Utility class
    }

    /** Parses a JSON file into a GraphDefinition. */
    public static GraphDefinition parseFile(Path path) throws IOException {
        return parse(Files.readString(path));
    }

    /** Parses a JSON string into a GraphDefinition. */
    public static GraphDefinition parse(String json) {
        Object root = new MiniParser(json).parseValue();
        @SuppressWarnings("unchecked")
        var rootMap = (Map<String, Object>) root;
        @SuppressWarnings("unchecked")
        var graphObj = (Map<String, Object>) rootMap.get("graph");
        if (graphObj == null)
            throw new IllegalArgumentException("Missing 'graph' key");

        GraphDefinition def = new GraphDefinition();
        GraphDefinition.GraphInfo info = new GraphDefinition.GraphInfo();
        info.setName((String) graphObj.get("name"));
        info.setVersion((String) graphObj.get("version"));

        @SuppressWarnings("unchecked")
        var nodesList = (List<Object>) graphObj.get("nodes");
        List<GraphDefinition.NodeDef> nodeDefs = new ArrayList<>();

        if (nodesList != null) {
            for (Object nodeObj : nodesList) {
                @SuppressWarnings("unchecked")
                var nm = (Map<String, Object>) nodeObj;
                GraphDefinition.NodeDef nd = new GraphDefinition.NodeDef();
                nd.setName((String) nm.get("name"));
                nd.setType((String) nm.get("type"));
                if (nm.get("source") instanceof Boolean b)
                    nd.setSource(b);
                @SuppressWarnings("unchecked")
                var deps = (List<Object>) nm.get("dependencies");
                if (deps != null) {
                    List<String> ds = new ArrayList<>();
                    for (Object d : deps)
                        ds.add((String) d);
                    nd.setDependencies(ds);
                }
                @SuppressWarnings("unchecked")
                var props = (Map<String, Object>) nm.get("properties");
                nd.setProperties(props != null ? props : Collections.emptyMap());
                nodeDefs.add(nd);
            }
        }
        info.setNodes(nodeDefs);
        def.setGraph(info);
        return def;
    }

    /** Use a recursive descent parser for simplicity. */
    private static final class MiniParser {
        private final String input;
        private int pos;

        MiniParser(String input) {
            this.input = input;
        }

        Object parseValue() {
            skipWS();
            if (pos >= input.length())
                throw err("Unexpected end");
            char c = input.charAt(pos);
            return switch (c) {
                case '"' -> parseString();
                case '{' -> parseObject();
                case '[' -> parseArray();
                case 't', 'f' -> parseBool();
                case 'n' -> parseNull();
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9'))
                        yield parseNumber();
                    throw err("Unexpected: " + c);
                }
            };
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < input.length()) {
                char c = input.charAt(pos++);
                if (c == '"')
                    return sb.toString();
                if (c == '\\') {
                    char e = input.charAt(pos++);
                    switch (e) {
                        case '"', '\\', '/' -> sb.append(e);
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        default -> sb.append(e);
                    }
                } else
                    sb.append(c);
            }
            throw err("Unterminated string");
        }

        private Map<String, Object> parseObject() {
            expect('{');
            var map = new LinkedHashMap<String, Object>();
            skipWS();
            if (pos < input.length() && input.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWS();
                String k = parseString();
                skipWS();
                expect(':');
                map.put(k, parseValue());
                skipWS();
                if (pos < input.length() && input.charAt(pos) == ',')
                    pos++;
                else
                    break;
            }
            skipWS();
            expect('}');
            return map;
        }

        private List<Object> parseArray() {
            expect('[');
            var list = new ArrayList<>();
            skipWS();
            if (pos < input.length() && input.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWS();
                if (pos < input.length() && input.charAt(pos) == ',')
                    pos++;
                else
                    break;
            }
            skipWS();
            expect(']');
            return list;
        }

        private Number parseNumber() {
            int s = pos;
            if (pos < input.length() && input.charAt(pos) == '-')
                pos++;
            while (pos < input.length() && input.charAt(pos) >= '0' && input.charAt(pos) <= '9')
                pos++;
            boolean fp = false;
            if (pos < input.length() && input.charAt(pos) == '.') {
                fp = true;
                pos++;
                while (pos < input.length() && input.charAt(pos) >= '0' && input.charAt(pos) <= '9')
                    pos++;
            }
            if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
                fp = true;
                pos++;
                if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-'))
                    pos++;
                while (pos < input.length() && input.charAt(pos) >= '0' && input.charAt(pos) <= '9')
                    pos++;
            }
            String ns = input.substring(s, pos);
            if (fp)
                return Double.parseDouble(ns);
            long l = Long.parseLong(ns);
            return (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) ? (int) l : l;
        }

        private Boolean parseBool() {
            if (input.startsWith("true", pos)) {
                pos += 4;
                return true;
            }
            if (input.startsWith("false", pos)) {
                pos += 5;
                return false;
            }
            throw err("Expected boolean");
        }

        private Object parseNull() {
            if (input.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw err("Expected null");
        }

        private void expect(char c) {
            skipWS();
            if (pos >= input.length() || input.charAt(pos) != c)
                throw err("Expected '" + c + "'");
            pos++;
        }

        private void skipWS() {
            while (pos < input.length() && " \t\n\r".indexOf(input.charAt(pos)) >= 0)
                pos++;
        }

        private IllegalArgumentException err(String msg) {
            return new IllegalArgumentException(msg + " at pos " + pos);
        }
    }
}
