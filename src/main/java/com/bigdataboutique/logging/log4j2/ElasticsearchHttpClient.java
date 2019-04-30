/**
 *    Copyright 2014-2018 Jörg Prante and Itamar Syn-Hershko
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bigdataboutique.logging.log4j2;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.appender.AppenderLoggingException;
import org.apache.logging.log4j.status.StatusLogger;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ElasticsearchHttpClient implements Closeable {

    private static final Logger LOGGER = StatusLogger.getLogger();

    private static final char[] HEX_CHARS = {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    };

    private static final Set<Character> JS_ESCAPE_CHARS;

    static {
        Set<Character> mandatoryEscapeSet = new HashSet<>();
        mandatoryEscapeSet.add('"');
        mandatoryEscapeSet.add('\\');
        JS_ESCAPE_CHARS = Collections.unmodifiableSet(mandatoryEscapeSet);
    }

    private final Queue<Map<String, Object>> requests = new ConcurrentLinkedQueue<>();
    private long numberOfQueuedRequests = 0;
    private final int maxQueuedRequests;

    private final URL bulkUrl;
    private final URL clusterUrl;

    private final String index;
    private final String type;
    private final Map<String, List<String>> applyTags;

    private final int maxActionsPerBulkRequest;

    private final boolean logResponses;

    private final ScheduledExecutorService service;

    private final String hostname;
    private final String ipAddress;

    private long lastErrorReported = 0;

    private boolean clusterAvailable = false;
    private volatile boolean closed;

    ElasticsearchHttpClient(String url, String index, String type, Map<String, List<String>> applyTags,
                            int maxActionsPerBulkRequest, long flushSecs, boolean logResponses, int maxQueuedRequests)
            throws MalformedURLException {

        this.clusterUrl = new URL(url);
        this.bulkUrl = new URL(url + "_bulk");

        this.index = index;
        this.type = type;
        this.applyTags = applyTags;
        this.maxActionsPerBulkRequest = maxActionsPerBulkRequest;
        this.logResponses = logResponses;
        this.closed = false;
        this.service = Executors.newScheduledThreadPool(5);
        this.service.scheduleAtFixedRate(this::flush, flushSecs, flushSecs, TimeUnit.SECONDS);

        // Get some info on the machine running us, which we will then add to all logs
        String _hostname = null, _ipAddress = null;
        try {
            final InetAddress addr = InetAddress.getLocalHost();
            _hostname = addr.getHostName();
            _ipAddress = addr.getHostAddress();
        } catch (UnknownHostException e) {
            /**/
        }
        this.hostname = _hostname;
        this.ipAddress = _ipAddress;
        this.maxQueuedRequests = maxQueuedRequests;
    }

    public void index(Map<String, Object> source) {
        if (numberOfQueuedRequests < this.maxQueuedRequests) {
            source.put("hostName", hostname);
            source.put("hostIp", ipAddress);
            for (Map.Entry<String, List<String>> entry : applyTags.entrySet()) {
                source.putIfAbsent(entry.getKey(), entry.getValue());
            }
            requests.offer(source);
            ++numberOfQueuedRequests;
        }
    }

    private boolean checkConnection() throws IOException {
        final HttpURLConnection connection = (HttpURLConnection) clusterUrl.openConnection();
        connection.setDoOutput(true);
        connection.setRequestMethod("HEAD");
        connection.getInputStream().close();
        return true;
    }

    private void flush() {
        if (!clusterAvailable) {
            try {
                clusterAvailable = checkConnection();
            } catch (IOException e) {
                // Only report errors once every 5 minutes or when the cluster availability state changes
                if (clusterAvailable || System.currentTimeMillis() - lastErrorReported >= 300000) {
                    LOGGER.debug(e);
                    LOGGER.warn("Elasticsearch cluster unavailable, skipping flush (" + e.getMessage() + ")");
                    lastErrorReported = System.currentTimeMillis();
                }
                clusterAvailable = false;
                return;
            }
        }

        while (!requests.isEmpty()) {
            // Generate index name to be used for this current bulk
            final String indexName = index.indexOf('\'') < 0 ? index : getIndexNameDateFormat(index).format(new Date());

            try {
                final HttpURLConnection connection = (HttpURLConnection) bulkUrl.openConnection();
                connection.setDoOutput(true);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("content-type", "application/x-ndjson");

                final StringBuilder sb = new StringBuilder();
                int i = maxActionsPerBulkRequest;
                Map<String, Object> request;
                while ((request = requests.poll()) != null && (i-- >= 0)) {
                    --numberOfQueuedRequests;
                    sb.append(build(indexName, type, request));
                }

                OutputStreamWriter writer = new OutputStreamWriter(connection.getOutputStream(), StandardCharsets.UTF_8);
                writer.write(sb.toString());
                writer.close();

                if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    // read response
                    if (logResponses) {
                        sb.setLength(0);
                        BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
                        String s;
                        while ((s = in.readLine()) != null) {
                            sb.append(s);
                        }
                        in.close();
                        LOGGER.trace(sb.toString());
                    }
                } else {
                    throw new AppenderLoggingException("Received non-OK response from Elasticsearch: "
                            + connection.getResponseCode() + " " + connection.getResponseMessage());
                }
            } catch (AppenderLoggingException e) {
                LOGGER.error("Encountered error while pushing to Elasticsearch, some logs may have been dropped", e);
            } catch (IOException e) {
                LOGGER.error("Encountered error while pushing to Elasticsearch, logs were dropped", e);
                clusterAvailable = false;
                return;
            }
        }
    }

    public void close() {
        if (!closed) {
            service.schedule(this::flush, 0, TimeUnit.NANOSECONDS);
            service.shutdown();
            try {
                if (!service.awaitTermination(5, TimeUnit.SECONDS)) {
                    final List<Runnable> threads = service.shutdownNow();
                    if (threads.size() > 0) {
                        LOGGER.warn("Some bulk tasks were dropped while shutting down");
                    }
                }
            } catch (InterruptedException e) {
                service.shutdownNow();
            }
        }
        closed = true;
    }

    private static String build(final String index, final String type, final Map<String, Object> source) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"index\":")
                .append("{\"_index\":\"").append(index)
                .append("\",\"_type\":\"").append(type)
                .append("\"}}\n{");
        build(sb, source);
        sb.append("}\n");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    static void build(StringBuilder sb, Object object) {
        if (object instanceof Map) {
            sb.append('{');
            build(sb, (Map<String, Object>) object);
            sb.append('}');
        } else if (object instanceof List) {
            sb.append("[");
            build(sb, (List<Object>) object);
            sb.append("]");
        } else if (object != null) {
            if (object instanceof Number) {
                sb.append(object);
            } else if (object instanceof Boolean) {
                sb.append(((Boolean) object) ? "true" : "false");
            } else if (object instanceof Date) {
                sb.append('"');
                sb.append(format((Date) object));
                sb.append('"');
            } else {
                sb.append('"');
                escape(sb, object.toString());
                sb.append('"');
            }
        } else {
            sb.append("null");
        }
    }

    private static void build(StringBuilder sb, List<Object> list) {
        boolean started = false;
        for (Object object : list) {
            if (started) {
                sb.append(',');
            }
            build(sb, object);
            started = true;
        }
    }

    private static void build(StringBuilder sb, Map<String, Object> map) {
        boolean started = false;
        for (Map.Entry<String, Object> me : map.entrySet()) {
            if (started) {
                sb.append(',');
            }

            // try to parse message as JSON and flatten the structure if we are parsing the "message" field
            if ("message".equals(me.getKey()) && me.getValue() != null) {
                JsonParser parser = new JsonParser(new StringReader(me.getValue().toString()));
                try {
                    build(sb, (Map<String, Object>) parser.parse());
                } catch (Throwable e) {
                    sb.append("\"").append(me.getKey()).append("\":");
                    build(sb, me.getValue());
                }
            } else {
                sb.append("\"").append(me.getKey()).append("\":");
                build(sb, me.getValue());
            }
            started = true;
        }
    }

    private static void escape(StringBuilder out, CharSequence plainText) {
        int pos = 0;
        int len = plainText.length();
        for (int charCount, i = 0; i < len; i += charCount) {
            int codePoint = Character.codePointAt(plainText, i);
            charCount = Character.charCount(codePoint);
            if (!isControlCharacter(codePoint) && !mustEscapeCharInJsString(codePoint)) {
                continue;
            }
            out.append(plainText, pos, i);
            pos = i + charCount;
            switch (codePoint) {
                case '\b':
                    out.append("\\b");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '/':
                    out.append("\\/");
                    break;
                case '"':
                    out.append("\\\"");
                    break;
                default:
                    appendHexJavaScriptRepresentation(out, codePoint);
                    break;
            }
        }
        out.append(plainText, pos, len);
    }

    private static boolean isControlCharacter(int codePoint) {
        return codePoint < 0x20
                || codePoint == 0x2028  // Line separator
                || codePoint == 0x2029  // Paragraph separator
                || (codePoint >= 0x7f && codePoint <= 0x9f);
    }


    private static void appendHexJavaScriptRepresentation(StringBuilder sb, int codePoint) {
        sb.append("\\u")
                .append(HEX_CHARS[(codePoint >>> 12) & 0xf])
                .append(HEX_CHARS[(codePoint >>> 8) & 0xf])
                .append(HEX_CHARS[(codePoint >>> 4) & 0xf])
                .append(HEX_CHARS[codePoint & 0xf]);
    }

    private static boolean mustEscapeCharInJsString(int codepoint) {
        if (!Character.isSupplementaryCodePoint(codepoint)) {
            char c = (char) codepoint;
            return JS_ESCAPE_CHARS.contains(c);
        }
        return false;
    }

    private static final String ISO_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.S'Z'";

    private static final TimeZone GMT = TimeZone.getTimeZone("GMT");

    private static final ThreadLocal<Map<String, SimpleDateFormat>> df = ThreadLocal.withInitial(HashMap::new);

    private static SimpleDateFormat getDateFormat(String format) {
        Map<String, SimpleDateFormat> formatters = df.get();
        SimpleDateFormat formatter = formatters.get(format);
        if (formatter == null) {
            formatter = new SimpleDateFormat();
            formatter.applyPattern(ISO_FORMAT);
            formatter.setTimeZone(GMT);
            formatters.put(format, formatter);
        }
        return formatter;
    }

    private SimpleDateFormat getIndexNameDateFormat(String index) {
        Map<String, SimpleDateFormat> formatters = df.get();
        SimpleDateFormat formatter = formatters.get(index);
        if (formatter == null) {
            formatter = new SimpleDateFormat();
            formatter.applyPattern(index);
            formatters.put(index, formatter);
        }
        return formatter;
    }

    private static String format(Date date) {
        if (date == null) {
            return null;
        }
        return getDateFormat(ISO_FORMAT).format(date);
    }

    static class JsonParser {

        private static final int DEFAULT_BUFFER_SIZE = 1024;

        private final Reader reader;

        private final char[] buf;

        private int index;

        private int fill;

        private int ch;

        private StringBuilder sb;

        private int start;

        public JsonParser(Reader reader) {
            this(reader, DEFAULT_BUFFER_SIZE);
        }

        public JsonParser(Reader reader, int buffersize) {
            this.reader = reader;
            buf = new char[buffersize];
            start = -1;
        }

        public Object parse() throws IOException {
            read();
            skipBlank();
            Object result = parseValue();
            skipBlank();
            if (ch != -1) {
                throw new IOException("unexpected character: " + ch);
            }
            return result;
        }

        private Object parseValue() throws IOException {
            switch (ch) {
                case 'n':
                    return parseNull();
                case 't':
                    return parseTrue();
                case 'f':
                    return parseFalse();
                case '"':
                    return parseString();
                case '[':
                    return parseList();
                case '{':
                    return parseMap();
                case '-':
                case '+':
                case '0':
                case '1':
                case '2':
                case '3':
                case '4':
                case '5':
                case '6':
                case '7':
                case '8':
                case '9':
                    return parseNumber();
            }
            throw new IOException("value");
        }

        private List<Object> parseList() throws IOException {
            read();
            List<Object> list = new ArrayList<Object>();
            skipBlank();
            if (parseChar(']')) {
                return list;
            }
            do {
                skipBlank();
                list.add(parseValue());
                skipBlank();
            } while (parseChar(','));
            if (!parseChar(']')) {
                expected("',' or ']'");
            }
            return list;
        }

        private Map<String, Object> parseMap() throws IOException {
            read();
            Map<String, Object> object = new LinkedHashMap<String, Object>();
            skipBlank();
            if (parseChar('}')) {
                return object;
            }
            do {
                skipBlank();
                if (ch != '"') {
                    expected("name");
                }
                String name = parseString();
                skipBlank();
                if (!parseChar(':')) {
                    expected("':'");
                }
                skipBlank();
                object.put(name, parseValue());
                skipBlank();
            } while (parseChar(','));
            if (!parseChar('}')) {
                expected("',' or '}'");
            }
            return object;
        }

        private Object parseNull() throws IOException {
            read();
            checkForChar('u');
            checkForChar('l');
            checkForChar('l');
            return null;
        }

        private Object parseTrue() throws IOException {
            read();
            checkForChar('r');
            checkForChar('u');
            checkForChar('e');
            return Boolean.TRUE;
        }

        private Object parseFalse() throws IOException {
            read();
            checkForChar('a');
            checkForChar('l');
            checkForChar('s');
            checkForChar('e');
            return Boolean.FALSE;
        }

        private void checkForChar(char ch) throws IOException {
            if (!parseChar(ch)) {
                expected("'" + ch + "'");
            }
        }

        private String parseString() throws IOException {
            read();
            startCapture();
            while (ch != '"') {
                if (ch == '\\') {
                    pauseCapture();
                    parseEscaped();
                    startCapture();
                } else if (ch < 0x20) {
                    expected("valid string character");
                } else {
                    read();
                }
            }
            String s = endCapture();
            read();
            return s;
        }

        private void parseEscaped() throws IOException {
            read();
            switch (ch) {
                case '"':
                case '/':
                case '\\':
                    sb.append((char) ch);
                    break;
                case 'b':
                    sb.append('\b');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'f':
                    sb.append('\f');
                    break;
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 'u':
                    char[] hex = new char[4];
                    for (int i = 0; i < 4; i++) {
                        read();
                        if (!isHexDigit()) {
                            expected("hexadecimal digit");
                        }
                        hex[i] = (char) ch;
                    }
                    sb.append((char) Integer.parseInt(String.valueOf(hex), 16));
                    break;
                default:
                    expected("valid escape sequence");
            }
            read();
        }

        private Object parseNumber() throws IOException {
            startCapture();
            parseChar('-');
            int firstDigit = ch;
            if (!parseDigit()) {
                expected("digit");
            }
            if (firstDigit != '0') {
                while (parseDigit()) {
                }
            }
            parseFraction();
            parseExponent();
            return endCapture();
        }

        private boolean parseFraction() throws IOException {
            if (!parseChar('.')) {
                return false;
            }
            if (!parseDigit()) {
                expected("digit");
            }
            while (parseDigit()) {
            }
            return true;
        }

        private boolean parseExponent() throws IOException {
            if (!parseChar('e') && !parseChar('E')) {
                return false;
            }
            if (!parseChar('+')) {
                parseChar('-');
            }
            if (!parseDigit()) {
                expected("digit");
            }
            while (parseDigit()) {
            }
            return true;
        }

        private boolean parseChar(char ch) throws IOException {
            if (this.ch != ch) {
                return false;
            }
            read();
            return true;
        }

        private boolean parseDigit() throws IOException {
            if (!isDigit()) {
                return false;
            }
            read();
            return true;
        }

        private void skipBlank() throws IOException {
            while (isWhiteSpace()) {
                read();
            }
        }

        private void read() throws IOException {
            if (ch == -1) {
                throw new IOException("unexpected end of input");
            }
            if (index == fill) {
                if (start != -1) {
                    sb.append(buf, start, fill - start);
                    start = 0;
                }
                fill = reader.read(buf, 0, buf.length);
                index = 0;
                if (fill == -1) {
                    ch = -1;
                    return;
                }
            }
            ch = buf[index++];
        }

        private void startCapture() {
            if (sb == null) {
                sb = new StringBuilder();
            }
            start = index - 1;
        }

        private void pauseCapture() {
            int end = ch == -1 ? index : index - 1;
            sb.append(buf, start, end - start);
            start = -1;
        }

        private String endCapture() {
            int end = ch == -1 ? index : index - 1;
            String captured;
            if (sb.length() > 0) {
                sb.append(buf, start, end - start);
                captured = sb.toString();
                sb.setLength(0);
            } else {
                captured = new String(buf, start, end - start);
            }
            start = -1;
            return captured;
        }

        private boolean isWhiteSpace() {
            return ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r';
        }

        private boolean isDigit() {
            return ch >= '0' && ch <= '9';
        }

        private boolean isHexDigit() {
            return ch >= '0' && ch <= '9'
                    || ch >= 'a' && ch <= 'f'
                    || ch >= 'A' && ch <= 'F';
        }

        private void expected(String expected) throws IOException {
            if (ch == -1) {
                throw new IOException("unexpected end of input");
            }
            throw new IOException("expected " + expected);
        }
    }
}
