import java.io.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Map.Entry;
import java.util.regex.Pattern;

public class AADS {
    /* classes and interface for JSON parser */
    protected static class JSONException extends RuntimeException {
        private static final long serialVersionUID = 0; // Serialization ID

        public JSONException(final String message) {
            super(message);
        }

        public JSONException(final String message, final Throwable cause) {
            super(message, cause);
        }

        public JSONException(final Throwable cause) {
            super(cause.getMessage(), cause);
        }
    }

    protected interface JSONString {
        public String toJSONString(); // Allowing a class to produce its own JSON serialization.
    }

    protected static class JSONTokener {
        private long character; // current read character position on the current line当前行的当前读字符的位置
        private boolean eof; // flag to indicate if the end of the input has been found是否是输入末尾
        private long index; // current read index of the input输入的当前读的下标
        private long line; // current line of the input输入的当前行
        private char previous; // previous character read from the input从输入读取的上一个字符
        private final Reader reader; // Reader for the input
        private boolean usePrevious; // flag to indicate that a previous character was requested是否已经请求上一个字符
        private long characterPreviousLine; // the number of characters read in the previous line上一行读取的字符总数

        /**
         * Construct a JSONTokener from a Reader. The caller must close the Reader.
         */
        public JSONTokener(Reader reader) {
            this.reader = reader.markSupported() ? reader : new BufferedReader(reader);
            this.character = 1;
            this.eof = false;
            this.index = 0;
            this.line = 1;
            this.previous = 0;
            this.usePrevious = false;
            this.characterPreviousLine = 0;
        }

        public JSONTokener(String s) {
            this(new StringReader(s));
        }

        /**
         * Get the next character in the source string.
         */
        public char next() throws JSONException {
            int c;
            // 若请求过字符，则复原flag标志，获取上一次请求的字符
            if (this.usePrevious) {
                this.usePrevious = false;
                c = this.previous;
            } else {
                try {
                    c = this.reader.read(); // 从流中读取单个字符
                } catch (IOException exception) {
                    throw new JSONException(exception);
                }
            }

            // End of stream
            if (c <= 0) {
                this.eof = true;
                return 0;
            }

            this.incrementIndexes(c); // update indexes
            this.previous = (char) c; // 当前字符作为下一次操作的previous
            return this.previous; // 返回当前字符
        }

        /**
         * Get the next n characters.
         */
        public String next(int n) throws JSONException {
            if (n == 0) return "";

            char[] chars = new char[n];
            int pos = 0;

            // 遍历n个字符
            while (pos < n) {
                chars[pos] = this.next();
                if (this.end()) {
                    throw new JSONException("Substring bounds error");
//                    throw this.syntaxError("Substring bounds error");
                }
                pos++; // TODO  改为pos++;
            }
            return new String(chars); // 读取的n个字符 转为 字符串
        }

        /**
         * Get the next char in the string, skipping whitespace.
         */
        public char nextClean() throws JSONException {
            // TODO 考虑改为while
            while (true) {
                char c = this.next(); // 获取下个字符
                if (c == 0 || c > ' ') { // 读到文本结尾 / 读取的是ASCII码表中空格后的字符，就返回该字符
                    return c;
                }
            }
        }

        /**
         * Get the next value (Boolean, Double, Integer, JSONArray, JSONObject, Long, String, JSONObject.NULL object)
         */
        public Object nextValue() throws JSONException {
            char c = this.nextClean();
            // 判断是否有更深的对象 / 数组的开始符号，若有抛出异常
            switch (c) {
                case '{':
                    this.back();
                    try {
                        return new JSONObject(this);
                    } catch (StackOverflowError e) {
                        throw new JSONException("JSON Object depth too large to process.", e);
                    }
                case '[':
                    this.back();
                    try {
                        return new JSONArray(this);
                    } catch (StackOverflowError e) {
                        throw new JSONException("JSON Array depth too large to process.", e);
                    }
            }
            return nextSimpleValue(c); // 返回下一个值
        }

        /**
         * Increments the internal indexes according to the previous character
         * read and the character passed as the current character.
         * 根据 读取的前一个字符 和 作为当前字符传递的字符 增加内部索引
         */
        private void incrementIndexes(int c) {
            if (c > 0) {
                this.index++;
                if (c == '\r') { // 回车（将读取下一行，当前字符作为下一次读取时的previous character，将character复原
                    this.line++;
                    this.characterPreviousLine = this.character;
                    this.character = 0;
                } else if (c == '\n') { // 换行
                    if (this.previous != '\r') { // 不是回车
                        this.line++;
                        this.characterPreviousLine = this.character;
                    }
                    this.character = 0; // 将character复原
                } else {
                    this.character++; // 将读取这行的下一个字符
                }
            }
        }

        /**
         * Decrements the indexes based on the previous character read.
         */
        private void decrementIndexes() {
            this.index--;
            if (this.previous == '\r' || this.previous == '\n') { // 读取回车或换行
                // 回到回车/换行的位置
                this.line--;
                this.character = this.characterPreviousLine;
            } else if (this.character > 0) {
                // 回退一个字符
                this.character--;
            }
        }

        // TODO 这里加了public
        public Object nextSimpleValue(char c) {
            String string;
            // 若读取的字符是引号，则需要读取字符串
            switch (c) {
                case '"': // 双引号
                case '\'': // 单引号
                    return this.nextString(c);
            }

            // 累计读取boolean值、null值、数字，直至文本结尾/格式字符
            StringBuilder sb = new StringBuilder();
            while (c >= ' ' && ",:]}/\\\"[{;=#".indexOf(c) < 0) {
                /*
                    , 逗号     : 冒号        ] 右中括号      } 右花括号      / 左斜杆      \\ 右斜杆
                    \" 双引号   [ 左中括号    { 左花括号      ; 分号         = 等号        # 井号
                */
                sb.append(c);
                c = this.next();
            }
            // while退出时，正常应该读取到eof；否则回退一个字符
            if (!this.eof) this.back();

            string = sb.toString().trim(); // 结果转为字符串，并移除空格
            if ("".equals(string)) throw new JSONException("Missing value");
            return JSONObject.stringToValue(string);
        }

        /**
         * Return the characters up to the next close quote character.
         * Backslash processing is done. The formal JSON format does not
         * allow strings in single quotes, but an implementation is allowed to
         * accept them.
         * 返回到下一个关闭的引号字符为止 读取的字符
         * 已完成反斜杠处理
         * 标准JSON不允许单引号中的字符串，但允许实现接受它们
         */
        public String nextString(char quote) throws JSONException {
            char c;
            StringBuilder sb = new StringBuilder();

            while (true) {
                c = this.next(); // 读取下一个字符
                switch (c) {
                    case 0: // 文本结尾
                    case '\n': // 换行
                    case '\r': // 回车
                        throw new JSONException("Unterminated string. Character with int code "
                                + (int) c + " is not allowed within a quoted string.");
                    case '\\': // 反斜杠
                        c = this.next();
                        switch (c) {
                            case 'b': // 退格
                                sb.append('\b');
                                break;
                            case 't': // Tab制表符
                                sb.append('\t');
                                break;
                            case 'n': // 换行
                                sb.append('\n');
                                break;
                            case 'f': // 换页符
                                sb.append('\f');
                                break;
                            case 'r': // 回车
                                sb.append('\r');
                                break;
                            case 'u': // 十六进制的Unicode字符
                                String next = this.next(4);
                                try {
                                    sb.append((char) Integer.parseInt(next, 16)); // 十六进制转为二进制字符
                                } catch (NumberFormatException e) {
                                    throw new JSONException("Illegal escape. \\u must be followed by a 4 digit hexadecimal number. \\"
                                            + next + " is not valid.", e);
                                }
                                break;
                            case '"':
                            case '\'':
                            case '\\':
                            case '/':
                                sb.append(c);
                                break;
                            default:
                                throw new JSONException("Illegal escape. Escape sequence  \\" + c + " is not valid.");
                        }
                        break;
                    default:
                        if (c == quote) { // 若等于引号，表示字符串读取完毕，返回
                            return sb.toString();
                        }
                        sb.append(c); // 加入StringBuilder
                }
            }
        }

        /**
         * Back up one character.
         * 可以在尝试解析下一个数字或标识符之前测试数字或字母
         */
        public void back() throws JSONException {
            // 最多回退一个字符，若已经在字符串开头，无需回退
            if (this.usePrevious || this.index <= 0)
                throw new JSONException("Stepping back two steps is not supported");

            this.decrementIndexes();
            this.usePrevious = true;
            this.eof = false;
        }

        /**
         * Checks if reaching the end of the input.
         */
        public boolean end() {
            return this.eof && !this.usePrevious;
        }

        public void close() throws IOException {
            if (reader != null) reader.close();
        }

        @Override
        public String toString() {
            return " at " + this.index + " [character " + this.character + " line " + this.line + "]";
        }
    }

    /**
     * Configuration base object (immutable) for parsers.
     */
    @SuppressWarnings({""})
    protected static class ParserConfiguration {
        /* Used to indicate no limit to the maximum nesting depth when parsing a document. */
        public static final int UNDEFINED_MAXIMUM_NESTING_DEPTH = -1;
        /* The default maximum nesting depth when parsing a document. */
        public static final int DEFAULT_MAXIMUM_NESTING_DEPTH = 512;
        /* Specifies if values should be kept as strings (true), or if they should try to be guessed into JSON values (numeric, boolean, string). */
        protected boolean keepStrings;
        /* The maximum nesting depth when parsing an object. */
        protected int maxNestingDepth;

        public ParserConfiguration() {
            this.keepStrings = false;
            this.maxNestingDepth = DEFAULT_MAXIMUM_NESTING_DEPTH;
        }

        /**
         * Constructs a new ParserConfiguration with the specified settings.
         */
        protected ParserConfiguration(final boolean keepStrings, final int maxNestingDepth) {
            this.keepStrings = keepStrings;
            this.maxNestingDepth = maxNestingDepth;
        }

        /**
         * Provides a new instance of the same configuration ("deep" clone).
         */
        @Override
        protected ParserConfiguration clone() {
            return new ParserConfiguration(this.keepStrings, this.maxNestingDepth);
        }

        /**
         * The maximum nesting depth that the parser will descend before throwing an exception
         * when parsing an object (Map, Collection) into JSON-related objects.
         */
//        public int getMaxNestingDepth() {
//            return maxNestingDepth;
//        }

        /**
         * 定义解析器在解析对象时将下降的最大嵌套深度
         * Defines the maximum nesting depth that the parser will descend before throwing an exception
         * when parsing an object (e.g. Map, Collection) into JSON-related objects.
         * The default max nesting depth is 512. The parser will throw a JsonException if reaching the maximum depth.
         * Using any negative value as a parameter is equivalent to setting no limit to the nesting depth.
         * The parses will go as deep as the maximum call stack size allows.
         */
        @SuppressWarnings("unchecked")
        public <T extends ParserConfiguration> T withMaxNestingDepth(int maxNestingDepth) {
            T newConfig = (T) this.clone();
            if (maxNestingDepth > UNDEFINED_MAXIMUM_NESTING_DEPTH) {
                newConfig.maxNestingDepth = maxNestingDepth;
            } else {
                newConfig.maxNestingDepth = UNDEFINED_MAXIMUM_NESTING_DEPTH;
            }
            return newConfig;
        }
    }

    /**
     * Configuration object (immutable) for the JSON parser.
     */
    protected static class JSONParserConfiguration extends ParserConfiguration {
        private boolean overwriteDuplicateKey; // 是否覆盖key

        public JSONParserConfiguration() {
            super();
            this.overwriteDuplicateKey = false;
        }

        @Override
        protected JSONParserConfiguration clone() {
            JSONParserConfiguration clone = new JSONParserConfiguration();
            clone.overwriteDuplicateKey = overwriteDuplicateKey;
            clone.maxNestingDepth = maxNestingDepth;
            return clone;
        }

        @SuppressWarnings("unchecked")
        @Override
        public JSONParserConfiguration withMaxNestingDepth(final int maxNestingDepth) {
            JSONParserConfiguration clone = this.clone();
            clone.maxNestingDepth = maxNestingDepth;
            return clone;
        }

        /**
         * 控制解析器在遇到重复键时的行为
         * Controls the parser's behavior when meeting duplicate keys.
         * If set to false, the parser will throw a JSONException when meeting a duplicate key.
         * Or the duplicate key's value will be overwritten.
         */
        public JSONParserConfiguration withOverwriteDuplicateKey(final boolean overwriteDuplicateKey) {
            JSONParserConfiguration clone = this.clone();
            clone.overwriteDuplicateKey = overwriteDuplicateKey;
            return clone;
        }

        /**
         * 遇到重复键时，控制解析器是否覆盖重复键
         */
        public boolean isOverwriteDuplicateKey() {
            return this.overwriteDuplicateKey;
        }
    }

    /**
     * 实现writer接口的方法，用于实现StringBuilder的数据读写
     */
    protected static class StringBuilderWriter extends Writer {
        private final StringBuilder builder;

        public StringBuilderWriter() {
            builder = new StringBuilder();
            lock = builder;
        }

        public StringBuilderWriter(int initialSize) {
            builder = new StringBuilder(initialSize);
            lock = builder;
        }

        @Override
        public void write(int c) {
            builder.append((char) c);
        }

        @Override
        public void write(char[] cbuf, int offset, int length) {
            if ((offset < 0) || (offset > cbuf.length) || (length < 0) ||
                    ((offset + length) > cbuf.length) || ((offset + length) < 0)) {
                throw new IndexOutOfBoundsException();
            } else if (length == 0) {
                return;
            }
            builder.append(cbuf, offset, length);
        }

        @Override
        public void write(String str) {
            builder.append(str);
        }

        @Override
        public void write(String str, int offset, int length) {
            builder.append(str, offset, offset + length);
        }

        @Override
        public StringBuilderWriter append(CharSequence csq) {
            write(String.valueOf(csq));
            return this;
        }

        @Override
        public StringBuilderWriter append(CharSequence csq, int start, int end) {
            if (csq == null) csq = "null";
            return append(csq.subSequence(start, end));
        }

        @Override
        public StringBuilderWriter append(char c) {
            write(c);
            return this;
        }

        @Override
        public String toString() {
            return builder.toString();
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() throws IOException {
        }
    }

    protected static class JSONObject {
        private Map<String, Object> map;

        /**
         * 1) JSONObject.NULL is equivalent to the null value in JavaScript.
         * 2) Java's null is equivalent to the undefined value in JavaScript.
         */
        private static final class Null {
            /**
             * There is only a single instance of the NULL object.
             */
            @Override
            protected final Object clone() {
                return this;
            }

            /**
             * A Null object is equal to the null value and to itself.
             */
            @Override
//            @SuppressWarnings("lgtm[java/unchecked-cast-in-equals]")
            public boolean equals(Object object) {
                return object == null || object == this;
            }

            /**
             * Returns a hash code value for the object. A Null object is equal to the null value and to itself.
             */
            @Override
            public int hashCode() {
                return 0;
            }

            @Override
            public String toString() {
                return "null";
            }
        }

        public static final Object NULL = new Null();

        // Regular Expression Pattern that matches JSON Numbers
        public static final Pattern NUMBER_PATTERN = Pattern.compile("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?");

        /**
         * Check whether the object is a NaN or infinite number.
         */
        public static void verify(Object o) throws JSONException {
            if (o instanceof Number && !IsFiniteNumber((Number) o))
                throw new JSONException("JSON doesn't allow non-finite numbers.");
        }

        private static boolean IsFiniteNumber(Number n) {
            if (n instanceof Double && (((Double) n).isInfinite() || ((Double) n).isNaN())) { // 不合法的Double类型
                return false;
            } else if (n instanceof Float && (((Float) n).isInfinite() || ((Float) n).isNaN())) { // 不合法的Float类型
                return false;
            }
            return true;
        }

        public JSONObject() {
            this.map = new HashMap<String, Object>();
        }

        protected JSONObject(int initialCapacity) {
            this.map = new HashMap<String, Object>(initialCapacity);
        }

        /**
         * Construct a JSONObject from a source JSON text string (The mostly used constructor).
         */
        protected JSONObject(String source) throws JSONException {
            this(new JSONTokener(source));
        }

        /**
         * Construct a JSONObject from a JSONTokener.
         */
        public JSONObject(JSONTokener x) throws JSONException {
            this(x, new JSONParserConfiguration());
        }

        /**
         * Construct a JSONObject from a JSONTokener with custom json parse configurations.
         */
        public JSONObject(JSONTokener x, JSONParserConfiguration jsonParserConfiguration) throws JSONException {
            this();
            char c;
            String key;

            // 读取的第一个字符是{，表示是对象的开始；否则报错
            if (x.nextClean() != '{') throw new JSONException("A JSONObject text must start with '{'.");

            while (true) {
                c = x.nextClean(); // 读取下一个字符
                switch (c) {
                    case 0: // 读取到文本结尾
                        throw new JSONException("A JSONObject text must end with '}'.");
                    case '}': // 读取到}，表示对象读取完毕
                        return;
                    default: // 读取key
                        key = x.nextSimpleValue(c).toString();
                }

                // key、value通过冒号分隔
                c = x.nextClean();
                if (c != ':') throw new JSONException("Expected a ':' after a key.");

                if (key != null) {
                    boolean keyExists = this.opt(key) != null;
                    // 若存在重复的key
                    if (keyExists && !jsonParserConfiguration.isOverwriteDuplicateKey())
                        throw new JSONException("\"" + key + "\" is a duplicate key.");

                    // 获取下一个值，若值非空，则加入map
                    Object value = x.nextValue();
                    if (value != null) {
                        this.put(key, value);
                    }
                }

                // 键值对通过逗号分隔
                switch (x.nextClean()) {
                    case ';':
                    case ',':
                        // 表示完成json对象的构建
                        if (x.nextClean() == '}') {
                            return;
                        }
                        // JSON对象必须用{}包装内部
                        if (x.end()) {
                            throw new JSONException("A JSONObject text must end with '}'.");
                        }
                        // 此时表示：遇到逗号/分号，且下个字符不是}，则回退，解析新的键值对
                        x.back();
                        break;
                    case '}': // 表示完成json对象的构建
                        return;
                    default:
                        throw new JSONException("Expected a ',' or '}'.");
                }
            }
        }

        /**
         * Get an optional value associated with a key.
         */
        public Object opt(String key) {
            if (key == null) {
                return null;
            }
            return this.map.get(key);
//            return key == null ? null : this.map.get(key);
        }

        /**
         * Get an optional JSONArray associated with a key.
         * It returns null if there is no such key, or if its value is not a JSONArray.
         */
        public JSONArray optJSONArray(String key) {
            return this.optJSONArray(key, null);
        }

        public JSONArray optJSONArray(String key, JSONArray defaultValue) {
            Object object = this.opt(key);
            return object instanceof JSONArray ? (JSONArray) object : defaultValue;
        }

        /**
         * Get an optional JSONObject associated with a key.
         * It returns null if there is no such key, or if its value is not a JSONObject.
         */
        public JSONObject optJSONObject(String key) {
            return this.optJSONObject(key, null);
        }

        public JSONObject optJSONObject(String key, JSONObject defaultValue) {
            Object object = this.opt(key);
            return object instanceof JSONObject ? (JSONObject) object : defaultValue;
        }

        /**
         * Put a key/value pair in the JSONObject.
         * If the value is null, then the key will be removed from the JSONObject if it is present.
         */
        public JSONObject put(String key, Object value) throws JSONException {
            if (key == null) throw new NullPointerException("Null key.");

            if (value != null) {
                verify(value); // 验证是否是合法的value

                // 根据value的类型，分别执行不同的map.put方法
                if (value instanceof Boolean) { // 1.Boolean
                    this.map.put(key, (Boolean) value ? Boolean.TRUE : Boolean.FALSE);
                } else if (value instanceof Double) { // 2.Double
                    this.map.put(key, (Double) value);
                } else if (value instanceof Float) { // 3.Float
                    this.map.put(key, (Float) value);
                } else if (value instanceof Integer) { // 4.Integer
                    this.map.put(key, (Integer) value);
                } else if (value instanceof Long) { // 5.Long
                    this.map.put(key, (Long) value);
                } else if (value instanceof Collection<?>) { // 6.Array
//                    this.map.put(key, new JSONArray((Collection<?>) value));
                } else if (value instanceof Map<?, ?>) {
//                    this.map.put(key, new JSONObject((Map<?, ?>) value));
                } else { // 7.Object
                    this.map.put(key, value);
                }
            } else {
                this.remove(key);
            }
            return this;
        }

        public Object remove(String key) {
            return this.map.remove(key);
        }

        /**
         * Produce a string in double quotes with backslash sequences in all the right places.
         */
        @SuppressWarnings("resource")
        public static String quote(String string) {
            // 字符串为空，返回""
            if (string == null || string.isEmpty()) {
                return "\"\"";
            }

            Writer sw = new StringBuilderWriter(string.length() + 2); // 大小要算上左右双引号
            try {
                return quote(string, sw).toString();
            } catch (IOException ignored) {
                return "";
            }
        }

        /**
         * Quotes a string and appends the result to a given Writer.
         */
        public static Writer quote(String string, Writer w) throws IOException {
            // 字符串为空，返回""
            if (string == null || string.isEmpty()) {
                w.write("\"\"");
                return w;
            }

            char b; // 上一个遍历的字符
            char c = 0; // 当前遍历的字符
            String hStr;
            int i;
            int len = string.length();

            w.write('"'); // 写入左双引号

            // 遍历字符串
            for (i = 0; i < len; i++) {
                b = c;
                c = string.charAt(i);
                switch (c) {
                    case '\\': // 反斜杠
                    case '"': // 双引号
                        w.write('\\');
                        w.write(c);
                        break;
                    case '/': // 左斜杠
                        if (b == '<') { // 避免被认为是HTML/XML标签的开始
                            w.write('\\');
                        }
                        w.write(c);
                        break;
                    case '\b': // 退格
                        w.write("\\b");
                        break;
                    case '\t': // Tab制表符
                        w.write("\\t");
                        break;
                    case '\n': // 换行
                        w.write("\\n");
                        break;
                    case '\f': // 换页符
                        w.write("\\f");
                        break;
                    case '\r': // 回车
                        w.write("\\r");
                        break;
                    default:
                        if (c < ' ' || (c >= '\u0080' && c < '\u00a0') || (c >= '\u2000' && c < '\u2100')) { // ASCII码表中空格后的字符 / Unicode字符
                            w.write("\\u");
                            hStr = Integer.toHexString(c); // 字符转为十六进制字符串
                            w.write("0000", 0, 4 - hStr.length()); // 写入(4-hStr.length())个0
                            w.write(hStr); // 写入0之外的其他字符
                        } else { // 普通字符
                            w.write(c);
                        }
                }
            }

            // 写入右双引号
            w.write('"');
            return w;
        }

        public int length() {
            return this.map.size();
        }

        protected Set<Entry<String, Object>> entrySet() {
            return this.map.entrySet();
        }

        /**
         * Convert a string into a number, boolean, or null. If the string can't be converted, return the string.
         */
        public static Object stringToValue(String string) {
            if ("".equals(string)) {
                return string;
            }
            if ("true".equalsIgnoreCase(string)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(string)) {
                return Boolean.FALSE;
            }
            if ("null".equalsIgnoreCase(string)) {
                return JSONObject.NULL;
            }

            // Convert String to a number.
            char initial = string.charAt(0); // 第一个字符
            if ((initial >= '0' && initial <= '9') || initial == '-') {
                try {
                    return stringToNumber(string);
                } catch (Exception ignore) {
                }
            }
            return string;
        }

        /**
         * Check whether the val is a decimal.
         */
        protected static boolean isDecimalNotation(final String val) {
            return val.indexOf('.') > -1 || val.indexOf('e') > -1 || val.indexOf('E') > -1 || "-0".equals(val);
        }

        /**
         * 将String转换为Number(使用尽可能窄的类型)
         * 可能返回BigDecimal、Double、BigInteger、Long、Integer
         * 若返回Double，它应始终是有效的Double，而不是NaN或+-infinity
         */
        protected static Number stringToNumber(final String val) throws NumberFormatException {
            char initial = val.charAt(0);
            if ((initial >= '0' && initial <= '9') || initial == '-') {
                // 若是小数
                if (isDecimalNotation(val)) {
                    // 使用BigDecimal，以保留原始表示
                    try {
                        BigDecimal bd = new BigDecimal(val);
                        // 由于BigDecimal不支持-0.0，这里强制使用小数
                        if (initial == '-' && BigDecimal.ZERO.compareTo(bd) == 0) {
                            return Double.valueOf(-0.0);
                        }
                        return bd;
                    } catch (NumberFormatException retryAsDouble) {
                        // TODO 涉及16进制表示，暂不实现
                        System.out.println("Hex Floats related contents are not available now.");
//                        // this is to support "Hex Floats" like this: 0x1.0P-1074
//                        try {
//                            Double d = Double.valueOf(val);
//                            if (d.isNaN() || d.isInfinite()) {
//                                throw new NumberFormatException("val [" + val + "] is not a valid number.");
//                            }
//                            return d;
//                        } catch (NumberFormatException ignore) {
//                            throw new NumberFormatException("val [" + val + "] is not a valid number.");
//                        }
                    }
                }

                // TODO  处理八进制Octal，暂不实现
//                if (initial == '0' && val.length() > 1) {
//                    char at1 = val.charAt(1);
//                    if (at1 >= '0' && at1 <= '9') {
//                        throw new NumberFormatException("val [" + val + "] is not a valid number.");
//                    }
//                } else if (initial == '-' && val.length() > 2) {
//                    char at1 = val.charAt(1);
//                    char at2 = val.charAt(2);
//                    if (at1 == '0' && at2 >= '0' && at2 <= '9') {
//                        throw new NumberFormatException("val [" + val + "] is not a valid number.");
//                    }
//                }

                // 处理整数（将所有值缩小到最小的合理对象表示：Integer, Long, BigInteger）
                BigInteger bi = new BigInteger(val);
                if (bi.bitLength() <= 31) {
                    return Integer.valueOf(bi.intValue());
                }
                if (bi.bitLength() <= 63) {
                    return Long.valueOf(bi.longValue());
                }
                return bi;
            }
            throw new NumberFormatException("Value [" + val + "] is not a valid number.");
        }

        /**
         * Produce a string from a Number.
         */
        public static String numberToString(Number number) throws JSONException {
            if (number == null) throw new JSONException("Null pointer.");
            verify(number); // 验证number的合法性

            String string = number.toString();
            if (string.indexOf('.') > 0 && string.indexOf('e') < 0 && string.indexOf('E') < 0) {
                // 删除尾随零
                while (string.endsWith("0")) {
                    string = string.substring(0, string.length() - 1);
                }
                // 删除小数点
                if (string.endsWith(".")) {
                    string = string.substring(0, string.length() - 1);
                }
            }
            return string;
        }

        /**
         * If { indentFactor > 0 } and the JSONObject has only one key, the object will be output on a single line:
         * {"key": 1}
         * If an object has 2 or more keys, it will be output across multiple lines:
         * {
         * "key1": 1,
         * "key2": "value 2",
         * "key3": 3
         * }
         */
        @Override
        public String toString() {
            try {
                return this.toString(0);
            } catch (Exception e) {
                return null;
            }
        }

        @SuppressWarnings("resource")
        public String toString(int indentFactor) throws JSONException {
            // 6 characters are the minimum to serialise a key value pair
            int initialSize = map.size() * 6;
            Writer w = new StringBuilderWriter(Math.max(initialSize, 16));
            return this.write(w, indentFactor, 0).toString();
        }

//        /**
//         * Write the contents of the JSONObject as JSON text to a writer. For
//         * compactness, no whitespace is added.
//         *
//         * Warning: This method assumes that the data structure is acyclical.
//         */
//        public Writer write(Writer writer) throws JSONException {
//            return this.write(writer, 0, 0);
//        }

        /**
         * Returns a java.util.Map containing all of the entries in this object.
         */
        public Map<String, Object> toMap() {
            Map<String, Object> results = new HashMap<String, Object>();
            for (Entry<String, Object> entry : this.entrySet()) { // 遍历所有entries
                Object value;
                if (entry.getValue() == null || NULL.equals(entry.getValue())) {
                    value = null;
                } else if (entry.getValue() instanceof JSONObject) {
                    value = ((JSONObject) entry.getValue()).toMap();
                } else if (entry.getValue() instanceof JSONArray) {
                    value = ((JSONArray) entry.getValue()).toList();
                } else {
                    value = entry.getValue();
                }
                results.put(entry.getKey(), value);
            }
            return results;
        }

        /**
         * add indents
         */
        protected static void indent(Writer writer, int indent) throws IOException {
            for (int i = 0; i < indent; i++) {
                writer.write(' ');
            }
        }

        @SuppressWarnings("resource")
        public Writer write(Writer writer, int indentFactor, int indent) throws JSONException {
            try {
                boolean needsComma = false; // 是否需要逗号
                final int length = this.length(); // map的长度

                // 写入对象的开始标记
                writer.write('{');

                // 只有一个键值对，最终显示为一行
                if (length == 1) {
                    final Entry<String, ?> entry = this.entrySet().iterator().next(); // 获取entry
                    final String key = entry.getKey(); // 获取key
                    writer.write(quote(key)); // 将key转为带双引号的字符串并写入
                    writer.write(':'); // 写入key、value的分隔符

                    // 写入indentFactor个缩进
                    if (indentFactor > 0) {
                        writer.write(' ');
                    }

                    // 写入具体value
                    try {
                        writeValue(writer, entry.getValue(), indentFactor, indent);
                    } catch (Exception e) {
                        throw new JSONException("Unable to write JSONObject value for key: " + key, e);
                    }
                } else if (length != 0) {
                    // 有多个键值对，最终显示为多行
                    final int newIndent = indent + indentFactor;

                    // 遍历所有entries
                    for (final Entry<String, ?> entry : this.entrySet()) {
                        if (needsComma) { // 需要逗号
                            writer.write(',');
                        }

                        // 缩进数>0，则另起一行，写入缩进
                        if (indentFactor > 0) {
                            writer.write('\n');
                        }
                        indent(writer, newIndent);

                        // 获取key，写入带双引号的key、冒号、缩进、具体value
                        final String key = entry.getKey();
                        writer.write(quote(key));
                        writer.write(':');
                        if (indentFactor > 0) {
                            writer.write(' ');
                        }
                        try {
                            writeValue(writer, entry.getValue(), indentFactor, newIndent);
                        } catch (Exception e) {
                            throw new JSONException("Unable to write JSONObject value for key: " + key, e);
                        }
                        needsComma = true; // 每个entry的最后需要有逗号
                    }

                    // 最后一个entry遍历后换行，写入缩进
                    if (indentFactor > 0) {
                        writer.write('\n');
                    }
                    indent(writer, indent);
                }
                writer.write('}'); // json对象的结束标志
                return writer;
            } catch (IOException exception) {
                throw new JSONException(exception);
            }
        }

        @SuppressWarnings("resource")
        protected static Writer writeValue(Writer writer, Object value, int indentFactor, int indent) throws JSONException, IOException {
            if (value == null || value.equals(null)) {
                writer.write("null");
            } else if (value instanceof JSONString) {
                Object o;
                try {
                    o = ((JSONString) value).toJSONString(); // 将value转换为JSONString
                } catch (Exception e) {
                    throw new JSONException(e);
                }
                writer.write(o != null ? o.toString() : quote(value.toString()));
            } else if (value instanceof String) { // The mostly used branch.
                quote(value.toString(), writer);
                return writer;
            } else if (value instanceof Number) {
                final String numberAsString = numberToString((Number) value);
                if (NUMBER_PATTERN.matcher(numberAsString).matches()) { // 是JSON的Number类型
                    writer.write(numberAsString);
                } else { // 转换为带双引号的字符串
                    quote(numberAsString, writer);
                }
            } else if (value instanceof Boolean) {
                writer.write(value.toString());
//            } else if (value instanceof Enum<?>) {
//                writer.write(quote(((Enum<?>)value).name()));
            } else if (value instanceof JSONObject) {
                ((JSONObject) value).write(writer, indentFactor, indent);
//            } else if (value instanceof JSONArray) {
//                ((JSONArray) value).write(writer, indentFactor, indent);
//            } else if (value instanceof Map) {
//                Map<?, ?> map = (Map<?, ?>) value;
//                new JSONObject(map).write(writer, indentFactor, indent);
//            } else if (value instanceof Collection) {
//                Collection<?> coll = (Collection<?>) value;
//                new JSONArray(coll).write(writer, indentFactor, indent);
//            } else if (value.getClass().isArray()) {
//                new JSONArray(value).write(writer, indentFactor, indent);
            } else { // 其他类型，则转化为带双引号的字符串
                quote(value.toString(), writer);
            }
            return writer;
        }
    }

    protected static class JSONArray implements Iterable<Object> {
        private final ArrayList<Object> myArrayList;

        public JSONArray() {
            this.myArrayList = new ArrayList<Object>();
        }

        public JSONArray(int initialCapacity) throws JSONException {
            if (initialCapacity < 0) throw new JSONException("JSONArray initial capacity cannot be negative.");
            this.myArrayList = new ArrayList<Object>(initialCapacity);
        }

        public JSONArray(JSONTokener x) throws JSONException {
            this();
            // JSON数组的第一个符号必须是[
            if (x.nextClean() != '[') {
                throw new JSONException("A JSONArray text must start with '['.");
            }

            char nextChar = x.nextClean(); // 获取下一个字符
            if (nextChar == 0) { // 读取到文本结尾，但不是]，则抛出异常
                throw new JSONException("Expected a ',' or ']'.");
            }

            // 只要不是]，就循环
            if (nextChar != ']') {
                x.back(); // 回退一个字符
                while (true) {
                    if (x.nextClean() == ',') { // 逗号，表示数组为空
                        x.back();
                        this.myArrayList.add(JSONObject.NULL);
                    } else { // 添加下一个值
                        x.back();
                        this.myArrayList.add(x.nextValue());
                    }

                    // 处理结尾
                    switch (x.nextClean()) {
                        case 0: // 读取到文本结尾，但不是]，则抛出异常
                            throw new JSONException("Expected a ',' or ']'.");
                        case ',':
                            nextChar = x.nextClean();
                            if (nextChar == 0) { // 读取到文本结尾，但不是]，则抛出异常
                                throw new JSONException("Expected a ',' or ']'.");
                            }
                            if (nextChar == ']') { // 表示完成JSONArray的构造
                                return;
                            }
                            x.back();
                            break; // 退出switch继续循环
                        case ']': // 表示完成JSONArray的构造
                            return;
                        default:
                            throw new JSONException("Expected a ',' or ']'.");
                    }
                }
            }
        }

        /**
         * Construct a JSONArray from a source JSON text (The mostly used constructor).
         */
        public JSONArray(String source) throws JSONException {
            this(new JSONTokener(source));
        }

        /**
         * Construct a JSONArray from a Collection.
         */
//        public JSONArray(Collection<?> collection, JSONParserConfiguration jsonParserConfiguration) {
//            this(collection, 0, jsonParserConfiguration);
//        }

        /**
         * Construct a JSONArray from a Collection.
         */
//        public JSONArray(Collection<?> collection) {
//            this(collection, 0, new JSONParserConfiguration());
//        }

        /**
         * Construct a JSONArray from a collection with recursion depth.
         */
//        public JSONArray(Collection<?> collection, int recursionDepth, JSONParserConfiguration jsonParserConfiguration) {
//            if (recursionDepth > jsonParserConfiguration.getMaxNestingDepth())
//                throw new JSONException("JSONArray has reached recursion depth limit of " + jsonParserConfiguration.getMaxNestingDepth());
//            if (collection == null) {
//                this.myArrayList = new ArrayList<Object>();
//            } else {
//                this.myArrayList = new ArrayList<Object>(collection.size());
//                this.addAll(collection, true, recursionDepth, jsonParserConfiguration);
//            }
//        }

        /**
         * Construct a JSONArray from another JSONArray (shallow copy).
         */
        public JSONArray(JSONArray array) {
            if (array == null) {
                this.myArrayList = new ArrayList<Object>();
            } else {
                this.myArrayList = new ArrayList<Object>(array.myArrayList);
            }
        }

        /**
         * Construct a JSONArray from an Iterable (shallow copy).
         */
//        public JSONArray(Iterable<?> iter) {
//            this();
//            if (iter == null) return;
//            this.addAll(iter, true);
//        }

        /**
         * Construct a JSONArray from an array.
         */
//        public JSONArray(Object array) throws JSONException {
//            this();
//            if (!array.getClass().isArray())
//                throw new JSONException("JSONArray initial value should be a string or collection or array.");
//            this.addAll(array, true, 0);
//        }
        @Override
        public Iterator<Object> iterator() {
            return this.myArrayList.iterator();
        }

        public int length() {
            return this.myArrayList.size();
        }

        public void clear() {
            this.myArrayList.clear();
        }

        /**
         * Get the optional object value associated with an index.
         */
        public Object opt(int index) {
            if (index < 0 || index >= this.length()) {
                return null;
            } else {
                return this.myArrayList.get(index);
            }
//            return (index < 0 || index >= this.length()) ? null : this.myArrayList.get(index);
        }

        /**
         * Append an object value. This increases the array's length by one.
         */
        public JSONArray put(Object value) {
            JSONObject.verify(value);

            // 根据value的类型，分别执行不同的array.add方法
            if (value instanceof Boolean) { // 1.Boolean
                this.myArrayList.add((Boolean) value ? Boolean.TRUE : Boolean.FALSE);
            } else if (value instanceof Double) { // 2.Double
                this.myArrayList.add((Double) value);
            } else if (value instanceof Float) { // 3.Float
                this.myArrayList.add((Float) value);
            } else if (value instanceof Integer) { // 4.Integer
                this.myArrayList.add((Integer) value);
            } else if (value instanceof Long) { // 5.Long
                this.myArrayList.add((Long) value);
            } else if (value instanceof Collection<?>) { // 6.Array
//                this.myArrayList.add(new JSONArray((Collection<?>) value));
            } else if (value instanceof Map<?, ?>) {

            } else { // 7.Object
                this.myArrayList.add(value);
            }
            return this;
        }

        /**
         * Put or replace an object value in the JSONArray.
         * 如果index > JSONArray.length，则按需添加空元素进行填充
         */
        public JSONArray put(int index, Object value) throws JSONException {
            if (index < 0) throw new JSONException("JSONArray[" + index + "] not found.");

            // 只要在数组下标范围内
            if (index < this.length()) {
                JSONObject.verify(value);
                this.myArrayList.set(index, value);
                return this;
            }

            // 若是最后一个位置，则调用put()添加一个
            if (index == this.length()) {
                return this.put(value);
            }

            // if we are inserting past the length, we want to grow the array all at once instead of incrementally.
            // 如果插入的内容超过了长度，希望一次性增加数组，而不是逐步增加
            this.myArrayList.ensureCapacity(index + 1); // 指定容量+1

            // 用JSONObject.NULL对象填充剩余的空位
            while (index != this.length()) {
                this.myArrayList.add(JSONObject.NULL);
            }

            // 其余情况，put值，并在put()中对value类型进行分类判断
            return this.put(value);
        }

        public Object remove(int index) {
            if (index >= 0 && index < this.length()) {
                return this.myArrayList.remove(index);
            } else {
                return null;
            }
//            return index >= 0 && index < this.length()
//                    ? this.myArrayList.remove(index)
//                    : null;
        }

        // TODO  这里的putAll后面尝试进行合并，目前先分开放
        // TODO   putAll()先实现参数为JSONArray array的那个
        /**
         * Put a collection's elements in to the JSONArray.
         */
//        public JSONArray putAll(Collection<?> collection) {
//            this.addAll(collection, false);
//            return this;
//        }

        /**
         * Put an Iterable's elements in to the JSONArray.
         */
//        public JSONArray putAll(Iterable<?> iter) {
//            this.addAll(iter, false);
//            return this;
//        }

        /**
         * Put a JSONArray's elements in to the JSONArray (shallow copy).
         */
        public JSONArray putAll(JSONArray array) {
            this.myArrayList.addAll(array.myArrayList);
            return this;
        }

        /**
         * Put an array's elements in to the JSONArray.
         */
//        public JSONArray putAll(Object array) throws JSONException {
//            this.addAll(array, false);
//            return this;
//        }

        /**
         * Returns a java.util.List containing all of the elements in this array.
         * If an element in the array is a JSONArray or JSONObject it will also be converted to a List and a Map respectively.
         * Warning: This method assumes that the data structure is acyclical.
         */
        public List<Object> toList() {
            List<Object> result = new ArrayList<Object>(this.myArrayList.size());
            // 遍历数组列表
            for (Object element : this.myArrayList) {
                if (element == null || JSONObject.NULL.equals(element)) { // 1.空
                    result.add(null);
                } else if (element instanceof JSONArray) { // 2.JSON数组
                    result.add(((JSONArray) element).toList());
                } else if (element instanceof JSONObject) { // 3.JSON对象
                    // TODO  待定实现
                    result.add(((JSONObject) element).toMap());
//                    System.out.println("The method toMap() is not supported now.");
                } else { // 4.其他类型
                    result.add(element);
                }
            }
            return result;
        }

        public boolean isEmpty() {
            return this.myArrayList.isEmpty();
        }

//        /**
//         * Add a collection's elements to the JSONArray.
//         */
//        private void addAll(Collection<?> collection, boolean wrap, int recursionDepth, JSONParserConfiguration jsonParserConfiguration) {
//            // 将集合collection的大小加入ArrayList的大小
//            this.myArrayList.ensureCapacity(this.myArrayList.size() + collection.size());
//
//            if (wrap) {
//                for (Object o: collection){
//                    this.put(JSONObject.wrap(o, recursionDepth + 1, jsonParserConfiguration));
//                }
//            } else {
//                for (Object o: collection){
//                    this.put(o);
//                }
//            }
//        }
//
//        /**
//         * Add an Iterable's elements to the JSONArray.
//         */
//        private void addAll(Iterable<?> iter, boolean wrap) {
//            if (wrap) {
//                for (Object o: iter){
//                    this.put(JSONObject.wrap(o));
//                }
//            } else {
//                for (Object o: iter){
//                    this.put(o);
//                }
//            }
//        }
//
//        /**
//         * Add an array's elements to the JSONArray.
//         */
//        private void addAll(Object array, boolean wrap) throws JSONException {
//            this.addAll(array, wrap, 0);
//        }
//
//        /**
//         * Add an array's elements to the JSONArray.
//         */
//        private void addAll(Object array, boolean wrap, int recursionDepth) {
//            addAll(array, wrap, recursionDepth, new JSONParserConfiguration());
//        }
//
//        /**
//         * Add an array's elements to the JSONArray.
//         */
//        private void addAll(Object array, boolean wrap, int recursionDepth, JSONParserConfiguration jsonParserConfiguration) throws JSONException {
//            // 判断array的类型
//            if (array.getClass().isArray()) { // 数组
//                int length = Array.getLength(array); // 获取数组长度
//                this.myArrayList.ensureCapacity(this.myArrayList.size() + length); // 扩充原数组列表的大小
//
//                // 是否需要包装
//                if (wrap) {
//                    for (int i = 0; i < length; i++) {
//                        this.put(JSONObject.wrap(Array.get(array, i), recursionDepth + 1, jsonParserConfiguration));
//                    }
//                } else {
//                    for (int i = 0; i < length; i++) {
//                        this.put(Array.get(array, i));
//                    }
//                }
//            } else if (array instanceof JSONArray) { // JSON数组（浅拷贝）
//                this.myArrayList.addAll(((JSONArray)array).myArrayList);
//            } else if (array instanceof Collection) { // 集合
//                this.addAll((Collection<?>)array, wrap, recursionDepth, jsonParserConfiguration);
//            } else if (array instanceof Iterable) { // 可迭代对象
//                this.addAll((Iterable<?>)array, wrap);
//            } else {
//                throw new JSONException("JSONArray initial value should be a string or collection or array.");
//            }
//        }

        /**
         * Get the string associated with an index.
         */
        public String getString(int index) throws JSONException {
            Object object = this.opt(index);
            if (object instanceof String) {
                return (String) object;
            }
            throw new JSONException(index + " String " + object + " " + null + ".");
        }

        /**
         * Produce a JSONObject by combining a JSONArray of names with the values of this JSONArray.
         */
        public JSONObject toJSONObject(JSONArray names) throws JSONException {
            if (names == null || names.isEmpty() || this.isEmpty()) return null;

            JSONObject jo = new JSONObject(names.length());
            for (int i = 0; i < names.length(); i++) {
                jo.put(names.getString(i), this.opt(i));
            }
            return jo;
        }

        /**
         * If { indentFactor > 0 } and the JSONArray has only one element, the array will be output on a single line:
         * [1]
         * If an array has 2 or more elements, then it will be output across multiple lines:
         * [
         * 1,
         * "value 2",
         * 3
         * ]
         */
        @Override
        public String toString() {
            try {
                return this.toString(0);
            } catch (Exception e) {
                return null;
            }
        }

        @SuppressWarnings("resource")
        public String toString(int indentFactor) throws JSONException {
            int initialSize = myArrayList.size() * 2; // each value requires a comma
            Writer sw = new StringBuilderWriter(Math.max(initialSize, 16));
            return this.write(sw, indentFactor, 0).toString();
        }

        /**
         * Write the contents of the JSONArray as JSON text to a writer. No whitespace is added.
         */
        public Writer write(Writer writer) throws JSONException {
            return this.write(writer, 0, 0);
        }

        @SuppressWarnings("resource")
        public Writer write(Writer writer, int indentFactor, int indent) throws JSONException {
            try {
                boolean needsComma = false; // 是否需要逗号
                int length = this.length(); // map的长度

                // 写入数组的开始标记
                writer.write('[');

                // 只有一个元素，最终显示为一行
                if (length == 1) {
                    try {
                        JSONObject.writeValue(writer, this.myArrayList.get(0), indentFactor, indent);
                    } catch (Exception e) {
                        throw new JSONException("Unable to write JSONArray value at index: 0", e);
                    }
                } else if (length != 0) {
                    // 有多个元素，最终显示为多行
                    final int newIndent = indent + indentFactor;

                    // 遍历所有元素
                    for (int i = 0; i < length; i++) {
                        if (needsComma) { // 需要逗号
                            writer.write(',');
                        }

                        // 缩进数>0，则另起一行，写入缩进
                        if (indentFactor > 0) {
                            writer.write('\n');
                        }
                        JSONObject.indent(writer, newIndent);

                        // 写入每个元素的值
                        try {
                            JSONObject.writeValue(writer, this.myArrayList.get(i),
                                    indentFactor, newIndent);
                        } catch (Exception e) {
                            throw new JSONException("Unable to write JSONArray value at index: " + i, e);
                        }
                        needsComma = true; // 每个元素的最后需要有逗号
                    }

                    // 最后一个元素遍历后换行，写入缩进
                    if (indentFactor > 0) {
                        writer.write('\n');
                    }
                    JSONObject.indent(writer, indent);
                }
                writer.write(']'); // json数组的结束标志
                return writer;
            } catch (IOException e) {
                throw new JSONException(e);
            }
        }
    }

    /* Part: AdjacencyList Graph */
//    protected interface Entry<K, V> {
//        K getKey();
//        V getValue();
//    }

    protected interface Tuple<A, B> {
        A getFirst();

        B getSecond();

        String toString();
    }

    protected interface Edge<E> {
        E getElement();
    }

    protected interface Vertex<V> {
        V getElement();
    }

    protected interface Graph<V, E> {
        int numVertices();

        int numEdges();

        Iterable<Vertex<V>> vertices();

        Iterable<Tuple<Edge<E>, List<Vertex<V>>>> edges();

        int outDegree(Vertex<V> v) throws IllegalArgumentException;

        int inDegree(Vertex<V> v) throws IllegalArgumentException;

        Iterable<Edge<E>> outgoingEdges(Vertex<V> v) throws IllegalArgumentException;

        Iterable<Edge<E>> incomingEdges(Vertex<V> v) throws IllegalArgumentException;

        Edge<E> getEdge(Vertex<V> u, Vertex<V> v) throws IllegalArgumentException;

        /**
         * Returns the vertices of edge e as an array of length two.
         * If the graph is directed, the first vertex is the origin, and
         * the second is the destination.  If the graph is undirected, the
         * order is arbitrary.
         */
        Vertex<V>[] endVertices(Edge<E> e) throws IllegalArgumentException;

        /**
         * Returns the vertex that is opposite vertex v on edge e.
         */
        Vertex<V> opposite(Vertex<V> v, Edge<E> e) throws IllegalArgumentException;

        Vertex<V> insertVertex(V element);

        Edge<E> insertEdge(Vertex<V> u, Vertex<V> v, E element) throws IllegalArgumentException;

        void removeVertex(Vertex<V> v) throws IllegalArgumentException;

        void removeEdge(Edge<E> e) throws IllegalArgumentException;
    }

    protected static class InnerTuple<A, B> implements Tuple<A, B> {
        private A first;
        private B second;

        public InnerTuple(A newFirst, B newSecond) {
            first = newFirst;
            second = newSecond;
        }

        public A getFirst() {
            return first;
        }

        public B getSecond() {
            return second;
        }

        @Override
        public String toString() {
            return "Tuple{ " +
                    "first=" + first +
                    ", second=" + second +
                    " }";
        }
    }

    protected interface Position<E> {
        /**
         * Returns the element stored at this position.
         */
        E getElement() throws IllegalStateException;
    }

    protected interface PositionalList<E> extends Iterable<E> {
        int size();

        boolean isEmpty();

        Position<E> first();

        Position<E> last();

        /**
         * 返回位置p的前一个位置
         */
        Position<E> before(Position<E> p) throws IllegalArgumentException;

        /**
         * 返回位置p的后一个位置
         */
        Position<E> after(Position<E> p) throws IllegalArgumentException;

        Position<E> addFirst(E e);

        Position<E> addLast(E e);

        Position<E> addBefore(Position<E> p, E e) throws IllegalArgumentException;

        Position<E> addAfter(Position<E> p, E e) throws IllegalArgumentException;

        E get(Position<E> p) throws IllegalArgumentException;

        E set(Position<E> p, E e) throws IllegalArgumentException;

        E remove(Position<E> p) throws IllegalArgumentException;

        /**
         * 返回存储在列表中的元素的迭代器
         */
        Iterator<E> iterator();

        /**
         * 以可迭代形式，返回从第一个到最后一个返回列表的位置
         */
        Iterable<Position<E>> positions();
    }

    /**
     * 双向链表，实现存储的位置列表
     */
    protected static class LinkedPositionalList<E> implements PositionalList<E> {
        private static class Node<E> implements Position<E> {
            private E element;
            private Node<E> prev;
            private Node<E> next;

            public Node(E e, Node<E> p, Node<E> n) {
                element = e;
                prev = p;
                next = n;
            }

            public E getElement() throws IllegalStateException {
                if (next == null) throw new IllegalStateException("Position no longer valid");
                return element;
            }

            public Node<E> getPrev() {
                return prev;
            }

            public Node<E> getNext() {
                return next;
            }

            public void setElement(E e) {
                element = e;
            }

            public void setPrev(Node<E> p) {
                prev = p;
            }

            public void setNext(Node<E> n) {
                next = n;
            }
        }

        private Node<E> header; // 头结点

        private Node<E> trailer; // 尾结点

        private int size = 0; // 列表的元素个数（不含头尾结点）

        public LinkedPositionalList() {
            header = new Node<>(null, null, null);
            trailer = new Node<>(null, header, null);
            header.setNext(trailer); // header -> trailer
        }

        /**
         * 验证某个position是否属于相应类别，且不能是已被删除的（未验证该position是否属于此特定列表实例）
         */
        private Node<E> validate(Position<E> p) throws IllegalArgumentException {
            if (!(p instanceof Node)) throw new IllegalArgumentException("Invalid p");
            Node<E> node = (Node<E>) p;
            if (node.getNext() == null) throw new IllegalArgumentException("p is no longer in the list");
            return node;
        }

        /**
         * 将给定的节点作为位置返回；若是哨兵，则返回null，避免暴露哨兵
         */
        private Position<E> position(Node<E> node) {
            if (node == header || node == trailer) return null;
            return node;
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public boolean isEmpty() {
            return size == 0;
        }

        @Override
        public Position<E> first() {
            return position(header.getNext());
        }

        @Override
        public Position<E> last() {
            return position(trailer.getPrev());
        }

        @Override
        public Position<E> before(Position<E> p) throws IllegalArgumentException {
            Node<E> node = validate(p);
            return position(node.getPrev());
        }

        @Override
        public Position<E> after(Position<E> p) throws IllegalArgumentException {
            Node<E> node = validate(p);
            return position(node.getNext());
        }

        private Position<E> addBetween(E e, Node<E> pred, Node<E> succ) {
            Node<E> newest = new Node<>(e, pred, succ);
            pred.setNext(newest);
            succ.setPrev(newest);
            size++;
            return newest;
        }

        @Override
        public Position<E> addFirst(E e) {
            return addBetween(e, header, header.getNext());
        }

        @Override
        public Position<E> addLast(E e) {
            return addBetween(e, trailer.getPrev(), trailer);
        }

        @Override
        public Position<E> addBefore(Position<E> p, E e) throws IllegalArgumentException {
            Node<E> node = validate(p);
            return addBetween(e, node.getPrev(), node);
        }

        @Override
        public Position<E> addAfter(Position<E> p, E e) throws IllegalArgumentException {
            Node<E> node = validate(p);
            return addBetween(e, node, node.getNext());
        }

        @Override
        public E get(Position<E> p) throws IllegalArgumentException {
            Node<E> node = validate(p);
            E answer = node.getElement();
            return answer;
        }

        @Override
        public E set(Position<E> p, E e) throws IllegalArgumentException {
            Node<E> node = validate(p);
            E answer = node.getElement();
            node.setElement(e);
            return answer;
        }

        @Override
        public E remove(Position<E> p) throws IllegalArgumentException {
            Node<E> node = validate(p);
            Node<E> predecessor = node.getPrev();
            Node<E> successor = node.getNext();
            predecessor.setNext(successor);
            successor.setPrev(predecessor);
            size--;

            E answer = node.getElement();
            node.setElement(null);
            node.setNext(null);
            node.setPrev(null);
            return answer;
        }

        /**
         * 返回列表位置的可迭代表示
         */
        // 每个实例都包含对包含列表的隐式引用，使得能直接调用列表的方法
        private class PositionIterator implements Iterator<Position<E>> {
            private Position<E> cursor = first(); // 指向下一个遍历的元素
            private Position<E> recent = null; // 最近遍历的元素

            public boolean hasNext() {
                return (cursor != null);
            }

            public Position<E> next() throws NoSuchElementException {
                if (cursor == null) throw new NoSuchElementException("nothing left");
                recent = cursor; // 遍历下一个位置
                cursor = after(cursor); // cursor指向下一个位置
                return recent;
            }

            public void remove() throws IllegalStateException {
                if (recent == null) throw new IllegalStateException("nothing to remove");
                LinkedPositionalList.this.remove(recent); // 移除最近访问的位置
                recent = null; // 避免再次移除
            }
        }

        private class PositionIterable implements Iterable<Position<E>> {
            public Iterator<Position<E>> iterator() {
                return new PositionIterator();
            }
        }

        @Override
        public Iterable<Position<E>> positions() {
            return new PositionIterable();
        }

        /**
         * 返回列表元素的可迭代表示
         */
        private class ElementIterator implements Iterator<E> {
            Iterator<Position<E>> posIterator = new PositionIterator();

            public boolean hasNext() {
                return posIterator.hasNext();
            }

            public E next() {
                return posIterator.next().getElement();
            } // return element!

            public void remove() {
                posIterator.remove();
            }
        }

        @Override
        public Iterator<E> iterator() {
            return new ElementIterator();
        }

        /**
         * Debugging
         */
        public String toString() {
            StringBuilder sb = new StringBuilder("(");
            Node<E> walk = header.getNext();
            while (walk != trailer) {
                sb.append(walk.getElement());
                walk = walk.getNext();
                if (walk != trailer)
                    sb.append(", ");
            }
            sb.append(")");
            return sb.toString();
        }
    }

    protected static class AdjacencyListGraph<V, E> implements Graph<V, E> {
        private boolean isDirected;
        private PositionalList<Vertex<V>> vertices = new LinkedPositionalList<>();
        private PositionalList<Edge<E>> edges = new LinkedPositionalList<>();
        private PositionalList<Tuple<Edge<E>, List<Vertex<V>>>> edgesWithVertices = new LinkedPositionalList<>();

        protected static class MapEntry<K, V> implements Entry<K, V> {
            private K k; // key
            private V v; // value

            public MapEntry(K key, V value) { //构造器
                k = key;
                v = value;
            }

            // Entry interface的公共方法
            public K getKey() {
                return k;
            }

            public V getValue() {
                return v;
            }

            public void setKey(K key) {
                k = key; //赋值新key
            }

            public V setValue(V value) {
                V old = v; //旧值
                v = value; //赋值新value
                return old; //返回旧值
            }

            public String toString() {
                return "<" + k + ", " + v + ">";
            }
        }

        /**
         * vertex ADT
         */
        private class InnerVertex<V> implements Vertex<V> {
            private V element;
            private Position<Vertex<V>> pos; // 顶点在图中的位置
            private List<Vertex<V>> adjVert; // 与该顶点相邻的顶点列表

            public InnerVertex(V elem, boolean graphIsDirected) {
                element = elem;
                // 初始化存储 邻接顶点 的列表
                adjVert = new ArrayList<>();
            }

            /**
             * 验证此顶点实例属于给定的图
             */
            public boolean validate(Graph<V, E> graph) {
                return (AdjacencyListGraph.this == graph && pos != null);
            }

            public V getElement() {
                return element;
            }

            /**
             * 将 该顶点的位置p 存储在图的顶点列表中
             */
            public void setPosition(Position<Vertex<V>> p) {
                pos = p;
            }

            /**
             * 返回该顶点在图的顶点列表中的位置
             */
            public Position<Vertex<V>> getPosition() {
                return pos;
            }

            /**
             * 返回 该顶点的相邻结点 的列表
             */
            public List<Vertex<V>> getAdjVert() {
                return adjVert;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                Vertex<?> vertex = (Vertex<?>) o;
                return Objects.equals(element, vertex.getElement());
            }
        }

        /**
         * edge ADT
         */
        private class InnerEdge<E> implements Edge<E> {
            private E element;
            private Position<Edge<E>> pos;
            private Vertex<V>[] endpoints;

            @SuppressWarnings({"unchecked"})
            public InnerEdge(Vertex<V> u, Vertex<V> v, E elem) {
                element = elem;
                endpoints = (Vertex<V>[]) new Vertex[]{u, v}; // 存储 边的两个顶点
            }

            public E getElement() {
                return element;
            }

            /**
             * 返回端点的数组
             */
            public Vertex<V>[] getEndpoints() {
                return endpoints;
            }

            /**
             * 验证此边实例属于给定的图
             */
            public boolean validate(Graph<V, E> graph) {
                return AdjacencyListGraph.this == graph && pos != null;
            }

            /**
             * 将此边的位置存储在图的顶点列表内
             */
            public void setPosition(Position<Edge<E>> p) {
                pos = p;
            }

            /**
             * 返回此边在图的顶点列表中的位置
             */
            public Position<Edge<E>> getPosition() {
                return pos;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                Edge<?> edge = (Edge<?>) o;
                return Objects.equals(element, edge.getElement());
            }
        }

        public AdjacencyListGraph(boolean directed) {
            isDirected = directed;
        }

        // 图的具体实现
        public int numVertices() {
            return vertices.size();
        }

        public Iterable<Vertex<V>> vertices() {
            return vertices;
        }

        public int numEdges() {
            return edges.size();
        }

        //        public Iterable<Edge<E>> edges() {
        //            return edges;
        //        }

        // reconstruct this method, outputting the edge with its end vertices
        public Iterable<Tuple<Edge<E>, List<Vertex<V>>>> edges() {
            for (Edge<E> edge : edges) {
                Vertex<V>[] vert = endVertices(edge);
                List<Vertex<V>> ends = new ArrayList<>();
                ends.add(vert[0]);
                ends.add(vert[1]);
                InnerTuple<Edge<E>, List<Vertex<V>>> innerTuple = new InnerTuple<>(edge, ends);
                edgesWithVertices.addLast(innerTuple);
            }
            return edgesWithVertices;
        }

        public int outDegree(Vertex<V> v) throws IllegalArgumentException {
            InnerVertex<V> vert = validate(v);
            return vert.getAdjVert().size();
        }

        public Iterable<Edge<E>> outgoingEdges(Vertex<V> v) throws IllegalArgumentException {
            InnerVertex<V> vert = validate(v);
            List<Vertex<V>> neighbours = vert.getAdjVert(); // 获取顶点v的邻接表
            List<Edge<E>> outgoings = new ArrayList<>(); // 存放 出边

            for (Vertex<V> neighbour : neighbours) {
                outgoings.add(getEdge(vert, neighbour));
            }
            return outgoings;
        }

        public int inDegree(Vertex<V> v) throws IllegalArgumentException {
            if (!isDirected) {
                return outDegree(v);
            }

            InnerVertex<V> vert = validate(v);
            List<Edge<E>> incomings = new ArrayList<>();
            int cnt = 0;

            // 遍历图中的所有边
            for (Edge<E> edge : edges) {
                InnerEdge<E> innerEdge = (InnerEdge<E>) edge;
                Vertex<V>[] endpoints = innerEdge.getEndpoints();

                // 检查有向图的边的终点是否是当前顶点
                if (endpoints[1].equals(vert)) {
                    cnt++;
                }
            }
            return cnt;
        }

        public Iterable<Edge<E>> incomingEdges(Vertex<V> v) throws IllegalArgumentException {
            if (!isDirected) {
                return outgoingEdges(v);
            }

            InnerVertex<V> vert = validate(v);
            List<Edge<E>> incomings = new ArrayList<>();

            // 遍历图中的所有边
            for (Edge<E> edge : edges) {
                InnerEdge<E> innerEdge = (InnerEdge<E>) edge;
                Vertex<V>[] endpoints = innerEdge.getEndpoints();

                // 检查有向图的边的终点是否是当前顶点
                if (endpoints[1].equals(vert)) {
                    incomings.add(innerEdge); // 若是，则加入边列表
                }
            }
            return incomings;
//        InnerVertex<V> vert = validate(v);
//        List<Edge<E>> incomings = new ArrayList<>();
//
//        System.out.println("vert:"+vert.getElement());
//
//
//        if (vertices.isEmpty()) throw new IllegalArgumentException("There is no incoming edge");
//        for (Vertex<V> vertex : vertices()) {
//            InnerVertex<V> tmp = validate(vertex); // 转为InnerVertex
//
//            // 遍历每个顶点的邻接表
//            for (Vertex<V> neighbour : tmp.getAdjVert()) {
//                System.out.println("neigh:"+neighbour.getElement());
//                if (neighbour == vert) {
//                    System.out.println("1:"+getEdge(neighbour, vert));
//                    incomings.add(getEdge(neighbour, vert));
//                }
//            }
//        }
//        return incomings;
        }

        public Edge<E> getEdge(Vertex<V> u, Vertex<V> v) throws IllegalArgumentException {
            InnerVertex<V> origin = validate(u);
            InnerVertex<V> destination = validate(v);

            for (Edge<E> edge : edges) {
                InnerEdge<E> innerEdge = (InnerEdge<E>) edge;
                Vertex<V>[] endpoints = innerEdge.getEndpoints();
                if (isDirected) {
                    // 有向图
                    if (endpoints[0].equals(origin) && endpoints[1].equals(destination)) {
                        return innerEdge; // 返回找到的边对象
                    }
                } else {
                    // 无向图
                    if (endpoints[0].equals(origin) && endpoints[1].equals(destination) || endpoints[0].equals(destination) && endpoints[1].equals(origin)) {
                        return innerEdge; // 返回找到的边对象
                    }
                }
            }
            return null; // 如果u和v之间没有边，则返回null
        }

        public Vertex<V>[] endVertices(Edge<E> e) throws IllegalArgumentException {
            InnerEdge<E> edge = validate(e);
            return edge.getEndpoints();
        }

//        public boolean containsVertex(V element) {
//            for (Vertex<V> v : vertices()) {
//                InnerVertex<V> newV = new InnerVertex<>(element, isDirected);
//                System.out.println("01: " + newV + "; " + newV.getElement());
//                System.out.println("02: " + v + "; " + v.getElement());
//                System.out.println("03: " + newV.getElement() == v.getElement());
//                if (newV.getElement().equals(v.getElement())) return true; // 包含指定顶点
//            }
//            return false; // 不包含指定顶点
//        }

        public Vertex<V> insertVertex(V element) {
            InnerVertex<V> v = new InnerVertex<>(element, isDirected);
            v.setPosition(vertices.addLast(v));
            return v;
        }

        public Edge<E> insertEdge(Vertex<V> u, Vertex<V> v, E element) throws IllegalArgumentException {
            if (getEdge(u, v) == null) {
                InnerEdge<E> e = new InnerEdge<>(u, v, element);
                e.setPosition(edges.addLast(e));

                InnerVertex<V> origin = validate(u);
                InnerVertex<V> destination = validate(v);

                // 信息加入邻接表
                origin.getAdjVert().add(destination);
                if (!isDirected) {
                    destination.getAdjVert().add(origin);
                }
                return e;
            } else {
                throw new IllegalArgumentException("Edge from u to v exists");
            }
        }

        @SuppressWarnings({"unchecked"})
        private InnerVertex<V> validate(Vertex<V> v) {
            if (!(v instanceof InnerVertex)) throw new IllegalArgumentException("Invalid vertex");
            InnerVertex<V> vert = (InnerVertex<V>) v;
            if (!vert.validate(this)) throw new IllegalArgumentException("Invalid vertex");
            return vert;
        }

        @SuppressWarnings({"unchecked"})
        private InnerEdge<E> validate(Edge<E> e) {
            if (!(e instanceof InnerEdge)) throw new IllegalArgumentException("Invalid edge");
            InnerEdge<E> edge = (InnerEdge<E>) e;
            if (!edge.validate(this)) throw new IllegalArgumentException("Invalid edge");
            return edge;
        }

        public void removeVertex(Vertex<V> v) throws IllegalArgumentException {
            InnerVertex<V> vert = validate(v);

            // 从图中删除所有关联边
            if (outgoingEdges(vert) != null) {
                for (Edge<E> e : outgoingEdges(vert)) {
                    if (e != null) {
                        removeEdge(e);
                    }
                }
            }
            if (incomingEdges(vert) != null) {
                for (Edge<E> e : incomingEdges(vert)) {
                    if (e != null) {
                        removeEdge(e);
                    }
                }
            }

            // 从顶点列表删除该顶点
            vertices.remove(vert.getPosition());
            vert.setPosition(null);
        }

        @SuppressWarnings({"unchecked"})
        public void removeEdge(Edge<E> e) throws IllegalArgumentException {
            InnerEdge<E> edge = validate(e);

            // 获取边的两个端点
            Vertex<V>[] endpoints = edge.getEndpoints();
            InnerVertex<V> u = validate(endpoints[0]);
            InnerVertex<V> v = validate(endpoints[1]);

            // 从邻接表中移除边
            u.getAdjVert().remove(v);
            if (!isDirected) {
                v.getAdjVert().remove(u);
            }

            // 从边列表中移除边
            edges.remove(edge.getPosition());
            edge.setPosition(null); // 使边对象无效
        }

        /**
         * 返回边e上与顶点v对立的顶点
         */
        public Vertex<V> opposite(Vertex<V> v, Edge<E> e) throws IllegalArgumentException {
            InnerEdge<E> edge = validate(e);
            Vertex<V>[] endpoints = edge.getEndpoints();
            if (endpoints[0] == v) {
                return endpoints[1];
            } else if (endpoints[1] == v) {
                return endpoints[0];
            } else {
                throw new IllegalArgumentException("v is not incident to this edge");
            }
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (Vertex<V> v : vertices) {
                sb.append("Vertex " + v.getElement() + "\n");
                if (isDirected) sb.append(" [outgoing]");
                sb.append(" " + outDegree(v) + " adjacencies:");
                for (Edge<E> e : outgoingEdges(v))
                    sb.append(String.format(" (%s, %s)", opposite(v, e).getElement(), e.getElement()));
                sb.append("\n");
                if (isDirected) {
                    sb.append(" [incoming]");
                    sb.append(" " + inDegree(v) + " adjacencies:");
                    for (Edge<E> e : incomingEdges(v))
                        sb.append(String.format(" (%s, %s)", opposite(v, e).getElement(), e.getElement()));
                    sb.append("\n");
                }
            }
            return sb.toString();
        }
    }

    protected static JSONObject getInput() throws IllegalArgumentException {
        // 1. Getting the input.
        Scanner input = new Scanner(System.in);
        StringBuilder sb = new StringBuilder();
        while (input.hasNextLine()) {
            String nextLine = input.nextLine().trim(); // 省略空格

            // 遇到空行时停止读取
            if (nextLine.isEmpty()) break;
//            if (nextLine.isEmpty() || "\n".equals(nextLine)) {
//                break; // 遇到空行 / "end"时停止读取
//            }
            // 将当前遍历行加入StringBuilder
            sb.append(nextLine);
        }
        input.close();

        // 2. Parsing Json String to JsonObject / JsonArray.
        JSONObject jsonObject = new JSONObject(sb.toString());
//        System.out.println("JSONObject toString: " + jsonObject.toString() + "\n");

        return jsonObject;
    }

//    protected static List<Site> collectList; // 取货地点
//    protected static List<Site> deliverList; // 送货地点

    protected static PreProcessData preProcessData(JSONObject rawData) throws IllegalArgumentException, IllegalAccessException, ParseException {
        // 测试(Json parser成功)
//        System.out.println("Orders: " + jsonObject.optJSONArray("Orders"));
//        System.out.println("Orders/CollectedId: " + jsonObject.optJSONArray("Orders").opt(0));
//        System.out.println("y?n: " + ((jsonObject.optJSONArray("Orders").opt(0)) instanceof JSONObject));
//        System.out.println("Vehicles: ");
//        JSONObject jo = (JSONObject) (jsonObject.optJSONArray("Vehicles")).opt(0);
//        System.out.println("jo: " + jo);
//        System.out.println(jo.opt("StartTime"));
//        System.out.println(jo.optJSONArray("VehicleCapacity").opt(0));

        // 1. Getting 'InstanceName', without considering 'Configuration'.
        String instanceName = (rawData.opt("InstanceName")).toString();

        // 2. 获取 地点 的具体信息
        JSONObject matrix = rawData.optJSONObject("Matrix");
        // 获取每个地点的坐标
        JSONArray matrixData = new JSONArray(String.valueOf(matrix.opt("Locations")));
        // 存放处理后的所有地点
        List<Site> locationList = new ArrayList<>();
        // 获取每个地点到其他地点的距离\时间
        JSONArray LocationData = new JSONArray(String.valueOf(matrix.opt("Data")));
        JSONArray tmpData = new JSONArray(String.valueOf(LocationData.getString(0))); // 表示距离时间的一维数组

        for (int i = 0; i < matrixData.length(); i++) {
            String[] coordinates = ((String) matrixData.opt(i)).split(","); // 根据逗号分割字符串，获取坐标
            JSONArray tmp = new JSONArray(String.valueOf(tmpData.getString(i))); // 第i个地点与其他地点的距离、时间
            Site site = new Site(coordinates, tmp); // 创建Site
            locationList.add(site);
        }
        System.out.println("location List: " + locationList.toString());
        System.out.println("There are " + locationList.size() + " locations in this input.\n");

//        JSONArray matrixData = new JSONArray(String.valueOf(matrix.opt("Data")));
//        JSONArray locations = new JSONArray(matrixData.getString(0)); // 获取表示地点的数组
//        System.out.println("locations: " + locations.toString());

        // 3. 获取 所有车辆 的具体信息
        JSONArray vehicles = rawData.optJSONArray("Vehicles");
        List<Vehicle> vehicleList = new ArrayList<>(); // 存放所有车辆的信息

        for (Object o : vehicles.toList()) { // 每个元素是HashMap
            Map<String, Object> v;
            if (o instanceof HashMap) {
                v = (Map<String, Object>) o;
            } else {
                throw new JSONException("This is not a HashMap object.");
            }

            // 构造车辆DTO
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss"); // 定义日期格式，获取startTime
            // 获取车辆标识码、weight
            List<Map<String, Object>> tmp = (ArrayList) v.get("VehicleCapacity");
            Map<String, Object> capacityMap = tmp.get(0);

            // 创建Vehicle DTO
            Vehicle dto = new Vehicle(Long.parseLong((String) capacityMap.get("CompartmentId")),
                    Integer.parseInt((String) v.get("StartSite")),
                    sdf.parse((String) v.get("StartTime")),
                    (Integer) capacityMap.get("Weight"),
                    Integer.parseInt((String) v.get("EndSite")),
                    new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                    new ArrayList<>(), new ArrayList<>(), Integer.parseInt((String) v.get("StartSite")));
            vehicleList.add(dto); // 车辆DTO加入列表
        }
        System.out.println("Vehicle list: " + vehicleList.toString());
        System.out.println("There are " + vehicleList.size() + " vehicles in this input.\n");

        // 4. 获取 所有订单 的具体信息，构造Customer类
        JSONArray orders = rawData.optJSONArray("Orders");
        List<Customer> customerList = new ArrayList<>(); // 存放所有客户
//        collectList = new ArrayList<>(); // 初始化
//        deliverList = new ArrayList<>(); // 初始化
        if (orders.toList() instanceof ArrayList) { // orders是ArrayList类型
            for (Object o : orders.toList()) {
                Map<String, Object> mapO = (HashMap) o; // o是HashMap类型

                // 构造Customer DTO
//                Customer customer = new Customer();
//                customer.setCollectId((String) mapO.get("CollectId"));
//                customer.setDeliverId((String) mapO.get("DeliverId"));

                // 根据siteId，找到CollectSite、DeliverSite DTO
                Site collectSite = new Site(), deliverSite = new Site();
                for (Site site : locationList) {
                    if (mapO.get("CollectSiteId").equals(String.valueOf(site.getId()))) {
//                        customer.setCollectSite(site);
                        collectSite = site;
                    }
                    if (mapO.get("DeliverSiteId").equals(String.valueOf(site.getId()))) {
//                        customer.setDeliverSite(site);
                        deliverSite = site;
                    }
                }

                // 处理CollectTime DTO
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
                Date earliestCollect = sdf.parse((String) mapO.get("EarliestCollect1"));
                Date latestCollect = sdf.parse((String) mapO.get("LatestCollect1"));
                long diff = latestCollect.getTime() - earliestCollect.getTime();
                Time collect = new Time(earliestCollect, latestCollect, diff / (1000.0 * 60 * 60), (String) mapO.get("CollectId"), 0);

                // 处理DeliverTime DTO
                Date earliestDeliver = sdf.parse((String) mapO.get("EarliestDeliver1"));
                Date latestDeliver = sdf.parse((String) mapO.get("LatestDeliver1"));
                diff = latestDeliver.getTime() - earliestDeliver.getTime();
                Time deliver = new Time(earliestDeliver, latestDeliver, diff / (1000.0 * 60 * 60), (String) mapO.get("DeliverId"), 0);

//                // 遍历   TODO 20241125 1733注释⭐
//                for (int i = 0; i < tmpData.length(); i++) {
//                    JSONArray tmp = new JSONArray(String.valueOf(tmpData.getString(i))); // 第i个地点与其他地点的距离、时间
////                    System.out.println("collect ID: " + (Integer.parseInt((String) mapO.get("CollectSiteId"))) + "; i: " + i);
//                    if ((Integer.parseInt((String) mapO.get("CollectSiteId"))) == i) { // 找到当前遍历的数组
//                        curData = tmp;
//                    }
//                }
////                System.out.println("curData: " + curData.toString());
//                // 获取DeliverSiteId对应的距离、时间数据
//                String str = curData.getString(Integer.parseInt((String) mapO.get("DeliverSiteId")));
//                JSONArray ja = new JSONArray(str);

                // 创建Customer DTO
                Customer customer = new Customer(
                        (String) mapO.get("CollectId"), (String) mapO.get("DeliverId"),
                        collectSite, deliverSite, collect, deliver, // 取送货的地点、时间
                        ((Integer) mapO.get("CollectTimeInMinutes")).longValue(), // 送货耗时
                        ((Integer) mapO.get("DeliverTimeInMinutes")).longValue(), // 送货耗时
                        (Integer) mapO.get("Weight"), // weight
                        false); // 初始未取货
//                        (Integer) ja.opt(0), (Integer) ja.opt(1)); // distance, time
                customerList.add(customer); // 加入列表

//                // 构建取货地点、送货地点列表
//                collectList.add(collectSite);
//                deliverList.add(deliverSite);
            }
        }
        System.out.println("There are " + customerList.size() + " customers in this input.");
        System.out.println("Contents in the customerList: \n" + customerList.toString());

        // TODO 放入report，这是测试数据的一个方法
        // 查看CollectTimeWindow、DeliverTimeWindow的时间数据
//        Set<Date> s = new HashSet<>();
//        List<Date> l = new ArrayList<>();
//        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
//        for (Customer c : customerList) {
//            s.add(c.getDeliverTimeWindow().end);
//            l.add(c.getDeliverTimeWindow().end);
//        }
//        for (Date d : s) {
//            System.out.println("m: " + sdf.format(d.getTime()));
//        }
//        System.out.println("Set size: " + s.size());
//        System.out.println("List size: " + l.size());

        // 5. 查看orders的取送货地点的情况
        // TODO  20241125 20:30注释测试customer图的代码
//        AdjacencyListGraph<SitePair, Integer> ordersGraph = new AdjacencyListGraph(true);
//
//        // insert vertex
//        List<Vertex<SitePair>> verticesList = new ArrayList<>();
//        for (Customer c : customerList) {
//            // 处理取货地点
//            if (ordersGraph.numVertices() == 0) {
//                // 初始时，图为空
//                SitePair sitePair = new SitePair(c.getCollectSite().getId(), c.getCollectSite());
//                Vertex<SitePair> v = ordersGraph.insertVertex(sitePair);
//                verticesList.add(v);
//            } else {
//                // 判断顶点是否在图中
//                boolean containFlag1 = false; // 判断是否已在图中
//                for (Vertex<SitePair> vert : ordersGraph.vertices()) {
//                    if (vert.getElement().getId() == c.getCollectSite().getId()) { // 根据id判断是否是同个地点
//                        containFlag1 = true;
//                    }
//                }
//                if (!containFlag1) {
//                    SitePair test = new SitePair(c.getCollectSite().getId(), c.getCollectSite());
//                    Vertex<SitePair> v = ordersGraph.insertVertex(test);
//                    verticesList.add(v);
//                }
//            }
//
//            // 处理送货地点
//            boolean containFlag2 = false; // 判断是否已在图中
//            for (Vertex<SitePair> vert : ordersGraph.vertices()) {
//                if (vert.getElement().getId() == c.getDeliverSite().getId()) {
//                    containFlag2 = true;
//                }
//            }
//            if (!containFlag2) {
//                SitePair test = new SitePair(c.getDeliverSite().getId(), c.getDeliverSite());
//                Vertex<SitePair> v = ordersGraph.insertVertex(test);
//                verticesList.add(v);
//            }
//        }
//
////        // 测试已加入列表的所有顶点      TODO   可以考虑放入report作为分析
////        System.out.println("Vertices List in the graph: ");
////        for (Vertex<Test> t : verticesList) {
////            System.out.println("vertex: " + t.getElement().toString());
////        }
//
//        // insert edges
//        for (Customer c : customerList) {
//            // 已知地点从1-100编号，则verticesList中顶点根据地点序号排序
//            Vertex<SitePair> collectV = null, deliverV = null;
//            for (Vertex<SitePair> v : verticesList) {
//                if (c.getCollectSite().getId() == v.getElement().getSite().getId()) {
//                    collectV = v; // 取货地
//                }
//                if (c.getDeliverSite().getId() == v.getElement().getSite().getId()) {
//                    deliverV = v; // 送货地
//                }
//            }
//            if (collectV != null && deliverV != null) {
//                // 边的权重，应当等于两个site间的距离  TODO  19:33开始处理
////                ordersGraph.insertEdge(collectV, deliverV, c.getDistance());
//            }
//        }
//
//        // 查看图的顶点\边信息
//        System.out.println("After constructing the graph: \n" + ordersGraph.toString());
//        System.out.println("Edges in the graph: ");
//        // 获取元组，包括边、边的两个顶点
//        for (Tuple<Edge<Integer>, List<Vertex<SitePair>>> tuple : ordersGraph.edges()) {
//            Edge<Integer> edge = tuple.getFirst();
//            List<Vertex<SitePair>> endVertices = tuple.getSecond();
//            System.out.println("Vertices(Sites): { " + endVertices.get(0).getElement() + ", " +
//                    endVertices.get(1).getElement() + " }, Edge(Distance): " + edge.getElement());
//        }

        // 6. 构造Route类
        AdjacencyListGraph<SiteGraph, Integer> graph = new AdjacencyListGraph(true); // 包含所有地点及路程的图

        // 遍历locationList,创建所有地点(vertex)
        List<Vertex<SiteGraph>> allVerticesList = new ArrayList<>();
        for (Site site : locationList) {
            SiteGraph siteGraph = new SiteGraph(site.getCoordinates());
            Vertex<SiteGraph> v = graph.insertVertex(siteGraph);
            allVerticesList.add(v);
        }

//        // 测试已加入列表的所有顶点      TODO   可以考虑放入report作为分析
//        System.out.println("Vertices List in the graph: ");
//        for (Vertex<Test> t : verticesList) {
//            System.out.println("vertex: " + t.getElement().toString());
//        }

        // 遍历所有顶点,创建边(edge)
        for (Site v : locationList) {
            JSONArray disAndTime = v.getDisAndTime();
            for (int i = 0; i < disAndTime.length(); i++) {
//                if (disAndTime.opt(i)!=null){
                if (i != v.getId()) {
                    // 只要不为null,则表示是到其他地点的距离\时间,则添加边
                    JSONArray arr = new JSONArray((String) disAndTime.opt(i));
                    // 获取有向弧的狐头
                    Vertex<SiteGraph> startV = allVerticesList.get((int) v.getId());
                    // 获取有向弧的狐尾
                    Vertex<SiteGraph> endV = allVerticesList.get(i);
                    // arr.opt(0)距离,arr.opt(1)时间
//                    graph.insertEdge(v, endV, (Integer) arr.opt(0));
                    graph.insertEdge(startV, endV, (Integer) arr.opt(1));
                }
            }
        }

        // 查看图的顶点\边信息 TODO  可放入report
//        System.out.println("After constructing the graph 'graph': \n" + graph);
//        System.out.println("Edges in the graph 'graph': ");
//        // 获取元组，包括边、边的两个顶点
//        for (Tuple<Edge<Integer>, List<Vertex<SiteGraph>>> tuple : graph.edges()) {
//            Edge<Integer> edge = tuple.getFirst();
//            List<Vertex<SiteGraph>> endVertices = tuple.getSecond();
//            System.out.println("Vertices(Sites): { " + endVertices.get(0).getElement() + ", " +
//                    endVertices.get(1).getElement() + " }, Edge(Distance): " + edge.getElement());
//        }

        // 7. 赋值全局变量、参数
        return new PreProcessData(instanceName, locationList, vehicleList, customerList,
                graph, new Random(20717331L));
    }

    /**
     * time DTO
     */
    protected static class Time {
        private long id;
        private Date start; // 开始时间
        private Date end; // 结束时间
        private double duration; // 时长
        private String jobId; // 任务id
        private int distance; // 行驶距离

        private static int next = 0; // 自增id

        public Time() {
            this.id = 0;
            this.start = new Date();
            this.end = new Date();
            this.duration = 0;
            this.jobId = "";
            this.distance = 0;
        }

        public Time(Date start, Date end, double duration, String jobId, int distance) {
            this.id = next++;
            this.start = start;
            this.end = end;
            this.duration = duration;
            this.jobId = jobId;
            this.distance = distance;
        }

        public long getId() {
            return id;
        }

        public Date getStart() {
            return start;
        }

        public void setStart(Date start) {
            this.start = start;
        }

        public Date getEnd() {
            return end;
        }

        public void setEnd(Date end) {
            this.end = end;
        }

        public double getDuration() {
            return duration;
        }

        public void setDuration(double duration) {
            this.duration = duration;
        }

        public String getJobId() {
            return jobId;
        }

        public void setJobId(String jobId) {
            this.jobId = jobId;
        }

        public int getDistance() {
            return distance;
        }

        public void setDistance(int distance) {
            this.distance = distance;
        }

        @Override
        public String toString() {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            return "TimeDto{" +
                    "id=" + id +
                    ", start='" + sdf.format(start) + '\'' +
                    ", end='" + sdf.format(end) + '\'' +
                    ", duration=" + duration +
                    ", jobId='" + jobId + '\'' +
                    ", distance=" + distance +
                    '}';
        }
    }

    /**
     * Time node list DTO
     */
    protected static class TimeList {
        private long id;
        private List<Time> driveTimeList;
        private List<Time> otherTimeList;
        private List<Time> breakTimeList;

        private static int next = 0; // 自增id

        public TimeList() {
            this.id = 0;
            this.driveTimeList = new ArrayList<>();
            this.otherTimeList = new ArrayList<>();
            this.breakTimeList = new ArrayList<>();
        }

        public TimeList(List<Time> driveTimeList, List<Time> otherTimeList, List<Time> breakTimeList) {
            this.id = 0;
            this.driveTimeList = driveTimeList;
            this.otherTimeList = otherTimeList;
            this.breakTimeList = breakTimeList;
        }

        public long getId() {
            return id;
        }

        public void setDriveTimeList(List<Time> driveTimeList) {
            this.driveTimeList = driveTimeList;
        }

        public List<Time> getDriveTimeList() {
            return driveTimeList;
        }

        public void setOtherTimeList(List<Time> otherTimeList) {
            this.otherTimeList = otherTimeList;
        }

        public List<Time> getOtherTimeList() {
            return otherTimeList;
        }

        public void setBreakTimeList(List<Time> breakTimeList) {
            this.breakTimeList = breakTimeList;
        }

        public List<Time> getBreakTimeList() {
            return breakTimeList;
        }

        @Override
        public String toString() {
            return "CustomerDto{" +
                    "id=" + id +
                    ", driveTimeList=" + driveTimeList.toString() +
                    ", otherTimeList=" + otherTimeList.toString() +
                    ", breakTimeList=" + breakTimeList.toString() +
                    "}\n";
        }
    }

    /**
     * Vehicle DTO
     */
    protected static class Vehicle {
        private long id; // 车辆识别码
        private int startSite; // 出发地id
        private Date startTime; // 车辆开始工作的时间
        private int weight; // 车辆容量
        private int endSite; // 目的地id

        private final double mContinuousDrivingInHours = 4.5; // 最大持续驾驶时间
        private final int mDailyDriveInHours = 9; // 每日最大驾驶时间
        private final int mDurationInHours = 13; // 最大路线时长

        // time list for the output
        private List<Time> driveTimeList;
        private List<Time> otherTimeList;
        private List<Time> breakTimeList;
        private List<Time> waitTimeList;
        private List<Time> delayTimeList;

        private int curSiteId; // 当前所在地id

        public Vehicle() {
            this.id = 0;
            this.startSite = 0;
            this.startTime = new Date();
            this.weight = 0;
            this.endSite = 0;
            this.driveTimeList = new ArrayList<>();
            this.otherTimeList = new ArrayList<>();
            this.breakTimeList = new ArrayList<>();
            this.waitTimeList = new ArrayList<>();
            this.delayTimeList = new ArrayList<>();
            this.curSiteId = 0;
        }

        public Vehicle(long id, int startSite, Date startTime, int weight, int endSite,
                       List<Time> driveTimeList, List<Time> otherTimeList, List<Time> breakTimeList,
                       List<Time> waitTimeList, List<Time> delayTimeList, int curSiteId) {
            this.id = id; // id设为compartmentId
            this.startSite = startSite;
            this.startTime = startTime;
            this.weight = weight;
            this.endSite = endSite;
            this.driveTimeList = driveTimeList;
            this.otherTimeList = otherTimeList;
            this.breakTimeList = breakTimeList;
            this.waitTimeList = waitTimeList;
            this.delayTimeList = delayTimeList;
            this.curSiteId = curSiteId; // 初始值应为发车地id，即startSite
        }

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id; // 将id设置为车辆唯一标识码
        }

        public int getStartSite() {
            return startSite;
        }

        public void setStartSite(int startSite) {
            this.startSite = startSite;
        }

        public Date getStartTime() {
            return startTime;
        }

        public void setStartTime(Date startTime) {
            this.startTime = startTime;
        }

        public int getWeight() {
            return weight;
        }

        public void setWeight(int weight) {
            this.weight = weight;
        }

        public int getEndSite() {
            return endSite;
        }

        public void setEndSite(int endSite) {
            this.endSite = endSite;
        }

        public double getMContinuousDrivingInHours() {
            return mContinuousDrivingInHours;
        }

        public int getmDailyDriveInHours() {
            return mDailyDriveInHours;
        }

        public int getMDurationInHours() {
            return mDurationInHours;
        }

        public void setDriveTimeList(List<Time> driveTimeList) {
            this.driveTimeList = driveTimeList;
        }

        public List<Time> getDriveTimeList() {
            return driveTimeList;
        }

        public void setOtherTimeList(List<Time> otherTimeList) {
            this.otherTimeList = otherTimeList;
        }

        public List<Time> getOtherTimeList() {
            return otherTimeList;
        }

        public void setBreakTimeList(List<Time> breakTimeList) {
            this.breakTimeList = breakTimeList;
        }

        public List<Time> getBreakTimeList() {
            return breakTimeList;
        }

        public void setWaitTimeList(List<Time> waitTimeList) {
            this.waitTimeList = waitTimeList;
        }

        public List<Time> getWaitTimeList() {
            return waitTimeList;
        }

        public void setDelayTimeList(List<Time> delayTimeList) {
            this.delayTimeList = delayTimeList;
        }

        public List<Time> getDelayTimeList() {
            return delayTimeList;
        }

        public int getCurSiteId() {
            return curSiteId;
        }

        public void setCurSiteId(int curSiteId) {
            this.curSiteId = curSiteId;
        }

        @Override
        public String toString() {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            return "VehicleDto{" +
                    "id=" + id +
                    ", startSite='" + startSite + '\'' +
                    ", startTime='" + sdf.format(startTime) + '\'' +
                    ", weight=" + weight +
                    ", endSite='" + endSite + '\'' +
                    ", mContinuousDrivingInHours=" + mContinuousDrivingInHours +
                    ", mDailyDriveInHours=" + mDailyDriveInHours +
                    ", mDurationInHours=" + mDurationInHours +
                    ", driveTimeList='" + driveTimeList.toString() + '\'' +
                    ", otherTimeList='" + otherTimeList.toString() + '\'' +
                    ", breakTimeList='" + breakTimeList.toString() + '\'' +
                    ", waitTimeList='" + waitTimeList.toString() + '\'' +
                    ", delayTimeList='" + delayTimeList.toString() + '\'' +
                    ", curSiteId=" + curSiteId +
                    "}\n";
        }
    }

    /**
     * Site DTO
     */
    protected static class Site {
        private long id; // （取货/送货）地点Id，自增实现
        private String[] coordinates; // （取货/送货）地点坐标
        private JSONArray disAndTime; // 到其他地点的距离/时间

        private static int next = 0; // 自增id

        public Site() {
            this.id = 0;
            this.coordinates = new String[]{};
            this.disAndTime = new JSONArray();
        }

        public Site(String[] coordinates, JSONArray disAndTime) {
            this.id = next++;
            this.coordinates = coordinates;
            this.disAndTime = disAndTime;
        }

        public long getId() {
            return id;
        }

        public String[] getCoordinates() {
            return coordinates;
        }

        public void setCoordinates(String[] coordinates) {
            this.coordinates = coordinates;
        }

        public JSONArray getDisAndTime() {
            return disAndTime;
        }

        public void setDisAndTime(JSONArray disAndTime) {
            this.disAndTime = disAndTime;
        }

        @Override
        public String toString() {
            return "SiteDto{" +
                    "id=" + id +
                    ", coordinates=" + Arrays.toString(coordinates) +
                    ", disAndTime=" + disAndTime.toString() +
                    '}';
        }
    }

    /**
     * SiteGraph DTO
     */
    protected static class SiteGraph {
        private long id; // （取货/送货）地点Id，自增实现
        private String[] coordinates; // （取货/送货）地点坐标
//        private JSONArray disAndTime; // 到其他地点的距离/时间

        private static int next = 0; // 自增id

        public SiteGraph() {
            this.id = 0;
            this.coordinates = new String[]{};
//            this.disAndTime = new JSONArray();
        }

        public SiteGraph(String[] coordinates) {
            this.id = next++;
            this.coordinates = coordinates;
        }
//        public SiteGraph(String[] coordinates, JSONArray disAndTime) {
//            this.id = next++;
//            this.coordinates = coordinates;
//            this.disAndTime = disAndTime;
//        }

        public long getId() {
            return id;
        }

        public String[] getCoordinates() {
            return coordinates;
        }

        public void setCoordinates(String[] coordinates) {
            this.coordinates = coordinates;
        }

//        public JSONArray getDisAndTime() {
//            return disAndTime;
//        }
//
//        public void setDisAndTime(JSONArray disAndTime) {
//            this.disAndTime = disAndTime;
//        }

        @Override
        public String toString() {
            return "SiteDto{" +
                    "id=" + id +
                    ", coordinates=" + Arrays.toString(coordinates) +
//                    ", disAndTime=" + disAndTime.toString() +
                    '}';
        }
    }

    /**
     * site pair for constructing the graph DTO
     */
    protected static class SitePair {
        private long id; // 地点Id（取货/送货）
        private Site site;

        public SitePair() {
            this.id = 0;
            this.site = new Site();
        }

        public SitePair(long id, Site site) {
            this.id = id;
            this.site = site;
        }

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id; // 将id设置为地点id
        }

        public Site getSite() {
            return site;
        }

        public void setSite(Site site) {
            this.site = site;
        }

        @Override
        public String toString() {
            return "SitePairDto{" +
                    "id=" + id +
                    ", site=" + site.toString() +
                    '}';
        }
    }

    /**
     * Customer DTO
     */
    protected static class Customer {
        private long id;
        private String collectId; // 取货id
        private String deliverId; // 送货id
        private Site collectSite; // 取货地点Dto
        private Site deliverSite; // 送货地点Dto
        private Time collectTimeWindow; // 取货时间窗口
        private Time deliverTimeWindow; // 送货时间窗口
        private long collectTimeinMinutes; // 取货耗时
        private long deliverTimeinMinutes; // 送货耗时
        private int weight; // 货物重量
        private boolean isDelivered;//是否已经送货

        private static int next = 0; // 自增id

        public Customer() {
            this.id = 0;
            this.collectId = "";
            this.deliverId = "";
            this.collectSite = new Site();
            this.deliverSite = new Site();
            this.collectTimeWindow = new Time();
            this.deliverTimeWindow = new Time();
            this.collectTimeinMinutes = 0;
            this.deliverTimeinMinutes = 0;
            this.weight = 0;
            this.isDelivered = false;
        }

        public Customer(String collectId, String deliverId,
                        Site collectSite, Site deliverSite,
                        Time collectTimeWindow, Time deliverTimeWindow,
                        long collectTimeinMinutes, long deliverTimeinMinutes,
                        int weight, boolean isDelivered) {
            this.id = next++;
            this.collectId = collectId;
            this.deliverId = deliverId;
            this.collectSite = collectSite;
            this.deliverSite = deliverSite;
            this.collectTimeWindow = collectTimeWindow;
            this.deliverTimeWindow = deliverTimeWindow;
            this.collectTimeinMinutes = collectTimeinMinutes;
            this.deliverTimeinMinutes = deliverTimeinMinutes;
            this.weight = weight;
            this.isDelivered = isDelivered;
        }

        public long getId() {
            return id;
        }

        public void setCollectId(String collectId) {
            this.collectId = collectId;
        }

        public String getCollectId() {
            return collectId;
        }

        public void setDeliverId(String deliverId) {
            this.deliverId = deliverId;
        }

        public String getDeliverId() {
            return deliverId;
        }

        public Site getCollectSite() {
            return collectSite;
        }

        public void setCollectSite(Site collectSite) {
            this.collectSite = collectSite;
        }

        public Site getDeliverSite() {
            return deliverSite;
        }

        public void setDeliverSite(Site deliverSite) {
            this.deliverSite = deliverSite;
        }

        public Time getCollectTimeWindow() {
            return collectTimeWindow;
        }

        public void setCollectTimeWindow(Time collectTimeWindow) {
            this.collectTimeWindow = collectTimeWindow;
        }

        public Time getDeliverTimeWindow() {
            return deliverTimeWindow;
        }

        public void setDeliverTimeWindow(Time deliverTimeWindow) {
            this.deliverTimeWindow = deliverTimeWindow;
        }

        public long getCollectTimeinMinutes() {
            return collectTimeinMinutes;
        }

        public void setCollectTimeinMinutes(long collectTimeinMinutes) {
            this.collectTimeinMinutes = collectTimeinMinutes;
        }

        public long getDeliverTimeinMinutes() {
            return deliverTimeinMinutes;
        }

        public void setDeliverTimeinMinutes(long deliverTimeinMinutes) {
            this.deliverTimeinMinutes = deliverTimeinMinutes;
        }

        public int getWeight() {
            return weight;
        }

        public void setWeight(int weight) {
            this.weight = weight;
        }

        public boolean isDelivered() {
            return isDelivered;
        }

        public void setDelivered(boolean isDelivered) {
            this.isDelivered = isDelivered;
        }

//        public int getDistance() {
//            return distance;
//        }
//
//        public void setDistance(int distance) {
//            this.distance = distance;
//        }
//
//        public int getTimeInSecond() {
//            return timeInSecond;
//        }
//
//        public void setTimeInSecond(int timeInSecond) {
//            this.timeInSecond = timeInSecond;
//        }

        @Override
        public String toString() {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            return "CustomerDto{" +
                    "id=" + id +
                    ", collectId='" + collectId + '\'' +
                    ", deliverId='" + deliverId + '\'' +
                    ", collectSite='" + collectSite.toString() + '\'' +
                    ", deliverSite='" + deliverSite.toString() + '\'' +
                    ", collectTimeWindow='" + collectTimeWindow.toString() + '\'' +
                    ", deliverTimeWindow='" + deliverTimeWindow.toString() + '\'' +
                    ", collectTimeinMinutes=" + collectTimeinMinutes + "min" +
                    ", deliverTimeinMinutes=" + deliverTimeinMinutes + "min" +
                    ", weight=" + weight +
                    ", isDelivered=" + isDelivered +
                    "}\n";
        }
    }

    /**
     * Route(gene) DTO
     */
    protected static class Route {
        private long id;
        private Vehicle vehicle; // 车辆信息
        private List<Customer> customers; // 客户列表
        private Date startTime; // 路线开始时间
        private Date endTime; // 路线结束时间
        private long overallDuration; // 总工作时长
        private int overallDistance; // 总路程
        private int overallWeight; // 总重量
        private long overallBreak; // 总休息时长
        private int randN; // 随机获取的车辆下标

        private static int next = 0; // 自增id
        //        private List<Long> siteIds = new ArrayList<>(); // 记录route中已存在的collectSiteId、deliverSiteId
//        private Map<Long, Integer> siteIds = new HashMap<>();

        public Route() {
            this.id = 0;
            this.vehicle = new Vehicle();
            this.customers = new ArrayList<>();
            this.startTime = new Date();
            this.endTime = new Date();
            this.overallDuration = 0;
            this.overallDistance = 0;
            this.overallWeight = 0;
            this.overallBreak = 0;
            this.randN = 0;
        }

        public Route(Vehicle vehicle, List<Customer> customers,
                     Date startTime, Date endTime,
                     long overallDuration, int overallDistance,
                     int overallWeight, int overallBreak, int randN) {
            this.id = next++;
            this.vehicle = vehicle;
            this.customers = customers;
            this.startTime = startTime;
            this.endTime = endTime;
            this.overallDuration = overallDuration;
            this.overallDistance = overallDistance;
            this.overallWeight = overallWeight;
            this.overallBreak = overallBreak;
            this.randN = randN;
        }

        public long getId() {
            return id;
        }

        public void setVehicle(Vehicle vehicle) {
            this.vehicle = vehicle;
        }

        public Vehicle getVehicle() {
            return vehicle;
        }

        public void setCustomers(List<Customer> customers) {
            this.customers = customers;
        }

        public List<Customer> getCustomers() {
            return customers;
        }

        public void setStartTime(Date startTime) {
            this.startTime = startTime;
        }

        public Date getStartTime() {
            return startTime;
        }

        public void setEndTime(Date endTime) {
            this.endTime = endTime;
        }

        public Date getEndTime() {
            return endTime;
        }

        public void setOverallDuration(long overallDuration) {
            this.overallDuration = overallDuration;
        }

        public long getOverallDuration() {
            return overallDuration;
        }

        public void setOverallDistance(int overallDistance) {
            this.overallDistance = overallDistance;
        }

        public int getOverallDistance() {
            return overallDistance;
        }

        public void setOverallWeight(int overallWeight) {
            this.overallWeight = overallWeight;
        }

        public int getOverallWeight() {
            return overallWeight;
        }

        public void setOverallBreak(long overallBreak) {
            this.overallBreak = overallBreak;
        }

        public long getOverallBreak() {
            return overallBreak;
        }

        public void setRandN(int randN) {
            this.randN = randN;
        }

        public int getRandN() {
            return randN;
        }

        // TODO   处理如何判断break rules⭐
        protected long handleDirectBreak(long overallDuration, long breakTime, long otherTime, long driveTime) {
//            long curTime = overallDuration + breakTime + otherTime + driveTime;
            long total = overallDuration;
            long breakDuration = 45 * 60 * 1000;
            breakTime += breakDuration;
            total += breakDuration;
//            createTimeNode(getVehicle(), curTime - breakDuration, breakDuration, "rest45", 0);
            return total;
        }

        protected long handleTwoPartBreak(long overallDuration, long breakTime, long otherTime, long driveTime) {
            long total = Long.MAX_VALUE;
            // 找到总时间最少的那个first break
            for (int firstBreak = 15 * 60 * 1000; firstBreak <= 30 * 60 * 1000; firstBreak += 60 * 1000) {
                long secondBreak = 45 * 60 * 1000 - firstBreak;
//                long curTime1 = overallDuration + breakTime + otherTime + driveTime + firstBreak;
                long curTime1 = overallDuration + firstBreak;
                breakTime += firstBreak;
//                createTimeNode(getVehicle(), curTime1 - firstBreak, firstBreak, "restFirst", 0);
                long curTime2 = curTime1 + secondBreak;
                breakTime += secondBreak;
//                createTimeNode(getVehicle(), curTime1, secondBreak, "restSecond", 0);
                System.out.println("cur1: " + curTime1);
                System.out.println("cur2: " + curTime2);
                System.out.println("total: " + total);
                if (curTime2 < total) {
                    total = curTime2;
                }
            }
            return total;
        }

        protected void needBreaks(long overallDuration, long breakTime, long otherTime, long driveTime) {
//            System.out.println("休息overallDuration： "+overallDuration);
//            System.out.println("休息breakTime： "+breakTime);
//            System.out.println("休息otherTime： "+otherTime);
//            System.out.println("休息driveTime： "+driveTime);
//
//            if (driveTime >= 4.5 * 60 * 60 * 1000 ) {
//                long totalDuration1 = handleDirectBreak(overallDuration, breakTime, otherTime, driveTime);
//                long totalDuration2 = handleTwoPartBreak(overallDuration, breakTime, otherTime, driveTime);
//                System.out.println("这1:"+totalDuration1);
//                System.out.println("这2:"+totalDuration2);
//                if (totalDuration1 < totalDuration2) {
//                    // 选择直接休息45分钟的方案
//                    System.out.println("选择直接休息45分钟");
//                } else {
//                    // 选择分两段休息的方案
//                    System.out.println("选择分两段休息");
//                }
//            }
//            boolean flag=false;

//            // 1. 驾驶4.5h，至少休息45min
//            if (driveTime >= 4.5 * 60 * 60 * 1000) {
//                // TODO  尝试枚举，判断30min/45min，选用用时更少的休息规则
//                if (breakTime < 45 * 60 * 1000) { // 休息45min
//                    breakTime += 45 * 60 * 1000;
//                    driveTime = 0; // 重置驾驶时间
//                    flag=true;
//                }
//            }
//            // 2. 总工作时间6~9h，应休息30min
//            if (overallDuration >= 6 * 60 * 60 * 1000 && overallDuration < 9 * 60 * 60 * 1000) {
//                if (breakTime < 30 * 60 * 1000) {
//                    System.out.println("q");
//                    breakTime += 30 * 60 * 1000;
////                    createTimeNode(getVehicle(),
////                            getVehicle().getStartTime().getTime()+overallDuration,
////                            30 * 60 * 1000,"break",0);
//                    flag=true;
//                }
//            }
//            // 3. 总工作时间超过9h，应休息45min
//            if (overallDuration >= 9 * 60 * 60 * 1000) {
//                if (breakTime < 45 * 60 * 1000) {
//                    System.out.println("q2");
//                    breakTime += 45 * 60 * 1000;
////                    createTimeNode(getVehicle(),
////                            getVehicle().getStartTime().getTime()+overallDuration,
////                            45 * 60 * 1000,"break",0);
////                    // 补满45min的休息
////                    long tmpTime = breakTime;
////                    breakTime += (45 * 60 * 1000 - tmpTime);
//                    flag=true;
//                }
//            }
//            // 4. 更新总休息时长
//            if (flag){
//                setOverallBreak(breakTime);
//                createTimeNode(getVehicle(),
//                        getVehicle().getStartTime().getTime()+overallDuration,
//                        breakTime,"break",0);
//            }
//            return breakTime;
        }

        // create a time node for the output
        public void createTimeNode(Vehicle vehicle, long startTime, long duration, String type, int distance) {
            if (vehicle != null) {
                Date start = new Date(startTime);
                Date end = new Date(startTime + duration);

                if (type.equals("drive")) {
                    Time time = new Time(start, end, duration, "drive", distance);
                    List<Time> driveList = vehicle.getDriveTimeList();
                    driveList.add(time);
                    vehicle.setDriveTimeList(driveList);
                    System.out.println("driveTimeList: vehicle " + vehicle.getId() + "; " + vehicle.getDriveTimeList());
                } else if (type.equals("break")) {
                    Time time = new Time(start, end, duration, "break", distance);
                    List<Time> breakList = vehicle.getBreakTimeList();
                    breakList.add(time);
                    vehicle.setBreakTimeList(breakList);
                    System.out.println("breakTimeList: vehicle " + vehicle.getId() + "; " + vehicle.getBreakTimeList());
                } else if (type.equals("wait")) {
                    Time time = new Time(start, end, duration, "wait", distance);
                    List<Time> waitList = vehicle.getWaitTimeList();
                    waitList.add(time);
                    vehicle.setWaitTimeList(waitList);
                    System.out.println("waitTimeList: vehicle " + vehicle.getId() + "; " + vehicle.getWaitTimeList());
                } else if (type.equals("delay")) {
                    Time time = new Time(start, end, duration, "delay", distance);
                    List<Time> delayList = vehicle.getDelayTimeList();
                    delayList.add(time);
                    vehicle.setDelayTimeList(delayList);
                    System.out.println("delayTimeList: vehicle " + vehicle.getId() + "; " + vehicle.getDelayTimeList());
                } else if (type.equals("return")) { // return to the depot
                    Time time = new Time(start, end, duration, "return", distance);
                    List<Time> driveList = vehicle.getDriveTimeList(); // store in the driveList
                    driveList.add(time);
                    vehicle.setDriveTimeList(driveList);
                    System.out.println("driveTimeList: vehicle " + vehicle.getId() + "; " + vehicle.getDriveTimeList());
                } else { // collect/deliver
                    Time time = new Time(start, end, duration, type, distance);
                    List<Time> otherList = vehicle.getOtherTimeList();
                    otherList.add(time);
                    vehicle.setOtherTimeList(otherList);
                    System.out.println("otherTimeList: vehicle " + vehicle.getId() + "; " + vehicle.getOtherTimeList());
                }
            }
        }

        // TODO  20241201 2038注释
//        // 计算成本
//        public long computeCost(Customer customer, PreProcessData data) {
//            long res = 0;
//            if (customer != null) {
//                long curTime = getOverallDuration(); // 初值为当前路线已经设置的总时间
//
//                Site startSite = data.getLocationList().get(getVehicle().getCurSiteId()); // 每次请求，都从当前位置开始
////                System.out.println("startSite: " + startSite.toString());
//                Site collectSite = data.getLocationList().get((int) customer.getCollectSite().getId()); // 取货地点，计算车辆从发车地点到取货地点的时间
////                System.out.println("collectSite: " + collectSite.toString());
//
//                //      判断发车地点、取货地点是否是同个地点
//                if (startSite == collectSite) {
//                    // 若是，则判断发车时间是否在时间窗口内
//                    if (curTime >= customer.getCollectTimeWindow().getStart().getTime()
//                            && curTime <= customer.getCollectTimeWindow().getEnd().getTime()) {
////                        System.out.println("!!directly COLLECT!!"); // test
//                        // 在时间窗口内，则直接取货，计算取货用时
//                        curTime += customer.getCollectTimeinMinutes() * 60 * 1000;
//                    } else if (curTime < customer.getCollectTimeWindow().getStart().getTime()) {
////                        System.out.println("!!directly COLLECT!!"); // test
//                        // 若需要等待直到取货时间窗口开放，则需要加上等待窗口开放的时间
//                        curTime += (customer.getCollectTimeWindow().getStart().getTime() - curTime)
//                                + customer.getCollectTimeinMinutes() * 60 * 1000;
//                    }
////                    else {
////                        // 不满足时间窗口约束
////                        System.out.println("The vehicle " + getVehicle().getId() +
////                                " should arrive between the collect time window.");
////                        return -1;
////                    }
//                } else {
//                    // 否则，需要加上行驶路程的时间、距离，判断发车时间是否在时间窗口内
//                    JSONArray arr = new JSONArray((String) startSite.getDisAndTime().toList().get((int) collectSite.getId()));
//                    long collectRouteTime = ((Integer) arr.opt(1)).longValue(); // 加上发车地点->取货地点的时间
//
//                    if ((curTime + collectRouteTime * 1000)
//                            >= customer.getCollectTimeWindow().getStart().getTime()
//                            && (curTime + collectRouteTime * 1000)
//                            <= customer.getCollectTimeWindow().getEnd().getTime()) {
////                        System.out.println("!!go to another site to COLLECT!!"); // test
//                        // 取货，计算取货用时
//                        curTime += collectRouteTime * 1000
//                                + customer.getCollectTimeinMinutes() * 60 * 1000;
//                    } else if ((curTime + collectRouteTime * 1000)
//                            < customer.getCollectTimeWindow().getStart().getTime()) {
////                        System.out.println("!!go to another site to COLLECT!!"); // test
//                        // 若需要等待直到取货时间窗口开放，则需要加上等待窗口开放的时间
//                        curTime += collectRouteTime * 1000
//                                + (customer.getCollectTimeWindow().getStart().getTime() - (curTime + collectRouteTime * 1000))
//                                + customer.getCollectTimeinMinutes() * 60 * 1000;
//                    }
////                    else {
////                        // 不满足时间窗口约束
////                        System.out.println("The vehicle " + getVehicle().getId() +
////                                " should arrive between the collect time window.");
////                        return -1;
////                    }
//                }
//
//                // 4. 车辆必须在送货时间窗口内到达（若早于，则等待；若晚于，则拒绝添加该请求）
//                Site deliverSite = data.getLocationList().get((int) customer.getDeliverSite().getId()); // 送货地点
////                System.out.println("deliverSite: " + deliverSite.toString());
//
//                if (collectSite != deliverSite) { // PDPTW问题的取送货地点通常是不同的
//                    // 判断到达送货地点的时间是否在时间窗口内
//                    JSONArray arr = new JSONArray((String) collectSite.getDisAndTime().toList().get((int) deliverSite.getId()));
//                    long deliverRouteTime = ((Integer) arr.opt(1)).longValue(); // 加上取货地点->送货地点的时间
////                    long tmp = curTime; // 记录更新操作前的当前时间
//
////                    System.out.println("deliverRouteTime: " + deliverRouteTime + "s");
////                    sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
////                    Date deliverWindowStart = new Date(customer.getDeliverTimeWindow().getStart().getTime());
////                    Date deliverWindowEnd = new Date(customer.getDeliverTimeWindow().getEnd().getTime());
////                    System.out.println("deliverWindow: " + sdf.format(deliverWindowStart) + "; " + sdf.format(deliverWindowEnd));
////                    System.out.println("deliverTime: " + customer.getDeliverTimeinMinutes() + "ms");
//
//                    if ((curTime + deliverRouteTime * 1000)
//                            >= customer.getDeliverTimeWindow().getStart().getTime()
//                            && (curTime + deliverRouteTime * 1000)
//                            <= customer.getDeliverTimeWindow().getEnd().getTime()) {
////                        System.out.println("!!directly DELIVER!!"); // test
//                        // 送货，计算送货用时
//                        curTime += deliverRouteTime * 1000
//                                + customer.getDeliverTimeinMinutes() * 60 * 1000;
//                    } else if ((curTime + deliverRouteTime * 1000)
//                            < customer.getDeliverTimeWindow().getStart().getTime()) {
////                        System.out.println("!!directly DELIVER!!"); // test
//                        // 若需要等待直到送货时间窗口开放，则需要加上等待窗口开放的时间
////                        breakTime += Math.abs(customer.getDeliverTimeWindow().getStart().getTime() - (curTime + deliverRouteTime * 1000));
//                        curTime += deliverRouteTime * 1000
//                                + (customer.getDeliverTimeWindow().getStart().getTime() - (curTime + deliverRouteTime * 1000))
//                                + customer.getDeliverTimeinMinutes() * 60 * 1000;
//                    }
////                    else {
////                        // 不满足时间窗口约束
////                        System.out.println("The vehicle " + getVehicle().getId() +
////                                " should arrive between the deliver time window.");
////                        return -1;
////                    }
//                }
//                res = (curTime - getVehicle().getStartTime().getTime()) / (1000 * 60);
//            }
//            return res;
//        }

        // TODO  20241130 0927⭐⭐⭐↓
//        public Tuple<Boolean,GlobalData> canAddACustomer(Customer customer, PreProcessData data) {
////    public Boolean canAddACustomer(Customer customer, PreProcessData data) {
//        InnerTuple<Boolean,GlobalData> res;
//        long dailyDriveTime = 0; // 每日累积驾驶时间
//        TimeList timeList=new TimeList();
//
//        if (customer != null) {
//                // 1. 每个请求的取货地点必须在对应的送货地点前被访问
//                for (Customer otherCustomer : getCustomers()) {
//                    // 找到当前请求的取货地点，已在该路线中存在，则比较顺序
//                    if (otherCustomer.getDeliverSite().getId()==customer.getCollectSite().getId()) {
//                        // 由于新请求的indexOf返回-1，若只要已存在的请求的下标＜当前请求的下标，则不满足
//                        if (getCustomers().indexOf(otherCustomer) < getCustomers().indexOf(customer)) {
//                            System.out.println("The customer " + customer.getId() + " should meet the precedence constraints: " +
//                                    "the collect site must be the predecessor of the delivered site.");
//                            return new InnerTuple<>(false,new GlobalData());
////                            return false;
//                        }
//                    }
//                }
//
//                // 2. 客户容量不超过车辆总承重
//                if (getOverallWeight() + customer.getWeight() > getVehicle().getWeight()) {
//                    System.out.println("The capacity of vehicle " + getVehicle().getId() +
//                            " is full and new customers cannot be added.");
////                    return false;
//                    return new InnerTuple<>(false,new GlobalData());
//                }
//
//                // 3. 车辆必须在取货时间窗口内到达（若早于，则等待；若晚于，则拒绝添加该请求）
//                long curTime = (getVehicle().getStartTime()).getTime(); // vehicle的发车时间作为起始时间
//                long breakTime = 0, otherTime = 0, driveTime = 0;
//
//                // 车辆的发车地点（包含到其他地点的距离\时间）
////                Site startSite = data.getLocationList().get(getVehicle().getStartSite());
//                Site startSite = data.getLocationList().get(getVehicle().getCurSiteId()); // 每次请求，都从当前位置开始
//                System.out.println("startSite: " + startSite.toString());
//                // 取货地点，计算车辆从发车地点到取货地点的时间
//                Site collectSite = data.getLocationList().get((int) customer.getCollectSite().getId());
//                System.out.println("collectSite: " + collectSite.toString());
//
//                //      判断发车地点、取货地点是否是同个地点
//                if (startSite == collectSite) {
//                    // 若是，则判断发车时间是否在时间窗口内
//                    long tmp = curTime; // 记录更新操作前的当前时间
//                    if (curTime >= customer.getCollectTimeWindow().getStart().getTime()
//                            && curTime <= customer.getCollectTimeWindow().getEnd().getTime()) {
//                        System.out.println("!!directly COLLECT!!"); // test
//                        // 在时间窗口内，则直接取货，计算取货用时
//                        curTime += customer.getCollectTimeinMinutes() * 60 * 1000;
//                        otherTime += customer.getCollectTimeinMinutes() * 60 * 1000;
//                        timeList=createTimeNodeTmp(getVehicle(),
//                                tmp,
//                                customer.getCollectTimeinMinutes() * 60 * 1000,
//                                "other",
//                                timeList); // 创建时间节点
////                        needBreaks(curTime, breakTime, otherTime, driveTime); // 判断是否需要休息
//                    } else if (curTime < customer.getCollectTimeWindow().getStart().getTime()) {
//                        System.out.println("!!directly COLLECT!!"); // test
//                        // 若需要等待直到取货时间窗口开放，则需要加上等待窗口开放的时间
//                        curTime += (customer.getCollectTimeWindow().getStart().getTime() - curTime)
//                                + customer.getCollectTimeinMinutes() * 60 * 1000;
//                        breakTime += customer.getCollectTimeWindow().getStart().getTime() - curTime;
//                        timeList=createTimeNodeTmp(getVehicle(),
//                                tmp,
//                                customer.getCollectTimeWindow().getStart().getTime() - curTime,
//                                "break",
//                                timeList); // 创建时间节点
////                        needBreaks(curTime, breakTime, otherTime, driveTime); // 判断是否需要休息
//                        otherTime += customer.getCollectTimeinMinutes() * 60 * 1000;
//                        createTimeNode(getVehicle(),
//                                tmp + breakTime,
//                                customer.getCollectTimeinMinutes() * 60 * 1000,
//                                "other"); // 创建时间节点
////                        needBreaks(curTime, breakTime, otherTime, driveTime); // 判断是否需要休息
//                    } else {
//                        // 不满足时间窗口约束
//                        System.out.println("The vehicle " + getVehicle().getId() +
//                                " should arrive between the collect time window.");
////                        return false;
//                        return new InnerTuple<>(false,new GlobalData());
//                    }
//                } else {
//                    // 否则，需要加上行驶路程的时间、距离，判断发车时间是否在时间窗口内
//                    JSONArray arr = new JSONArray((String) startSite.getDisAndTime().toList().get((int) collectSite.getId()));
//                    long collectRouteTime = ((Integer) arr.opt(1)).longValue(); // 加上发车地点->取货地点的时间
//                    long tmp = curTime; // 记录更新操作前的当前时间
//
//                    System.out.println("collectRouteTime: " + collectRouteTime + "s");
//                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
//                    Date collectWindowStart = new Date(customer.getCollectTimeWindow().getStart().getTime());
//                    Date collectWindowEnd = new Date(customer.getCollectTimeWindow().getEnd().getTime());
//                    System.out.println("collectWindow: " + sdf.format(collectWindowStart) + "; " + sdf.format(collectWindowEnd));
//                    System.out.println("collectTime: " + customer.getCollectTimeinMinutes() + "min");
//                    System.out.println("carStartTime: " + sdf.format(getVehicle().getStartTime()));
//
//                    if ((curTime + collectRouteTime * 1000)
//                            >= customer.getCollectTimeWindow().getStart().getTime()
//                            && (curTime + collectRouteTime * 1000)
//                            <= customer.getCollectTimeWindow().getEnd().getTime()) {
//                        System.out.println("!!go to another site to COLLECT!!"); // test
//
//                        // 取货，计算取货用时
//                        curTime += collectRouteTime * 1000
//                                + customer.getCollectTimeinMinutes() * 60 * 1000;
//                        driveTime += collectRouteTime * 1000;
//                        dailyDriveTime += collectRouteTime * 1000; // 更新每日累积驾驶时间
//                        timeList=createTimeNodeTmp(getVehicle(),
//                                tmp,
//                                collectRouteTime * 1000,
//                                "drive",
//                                timeList); // 创建时间节点
////                        needBreaks(curTime, breakTime, otherTime, driveTime); // 判断是否需要休息
//                        otherTime += customer.getCollectTimeinMinutes() * 60 * 1000;
//                        timeList=createTimeNodeTmp(getVehicle(),
//                                tmp + collectRouteTime * 1000,
//                                customer.getCollectTimeinMinutes() * 60 * 1000,
//                                "other",
//                                timeList); // 创建时间节点
////                        needBreaks(curTime, breakTime, otherTime, driveTime); // 判断是否需要休息
//                    } else if ((curTime + collectRouteTime * 1000)
//                            < customer.getCollectTimeWindow().getStart().getTime()) {
//                        System.out.println("!!go to another site to COLLECT!!"); // test
//
//                        // 若需要等待直到取货时间窗口开放，则需要加上等待窗口开放的时间
//                        breakTime += Math.abs(customer.getCollectTimeWindow().getStart().getTime() - (curTime + collectRouteTime * 1000));
//                        timeList=createTimeNodeTmp(getVehicle(),
//                                tmp,
//                                Math.abs(customer.getCollectTimeWindow().getStart().getTime() - (curTime + collectRouteTime * 1000)),
//                                "break",
//                                timeList); // 创建时间节点
//                        curTime += collectRouteTime * 1000
//                                + (customer.getCollectTimeWindow().getStart().getTime() - (curTime + collectRouteTime * 1000))
//                                + customer.getCollectTimeinMinutes() * 60 * 1000;
//                        driveTime += collectRouteTime * 1000;
//                        dailyDriveTime += collectRouteTime * 1000; // 更新每日累积驾驶时间
//                        timeList=createTimeNodeTmp(getVehicle(),
//                                tmp
//                                        + Math.abs(customer.getCollectTimeWindow().getStart().getTime() - (curTime + collectRouteTime * 1000)),
//                                collectRouteTime * 1000,
//                                "drive",
//                                timeList); // 创建时间节点
////                        needBreaks(curTime, breakTime, otherTime, driveTime); // 判断是否需要休息
//                        otherTime += customer.getCollectTimeinMinutes() * 60 * 1000;
//                        timeList=createTimeNodeTmp(getVehicle(),
//                                tmp
//                                        + Math.abs(customer.getCollectTimeWindow().getStart().getTime() - (curTime + collectRouteTime * 1000))
//                                        + collectRouteTime * 1000,
//                                customer.getCollectTimeinMinutes() * 60 * 1000,
//                                "other",
//                                timeList); // 创建时间节点
////                        needBreaks(curTime, breakTime, otherTime, driveTime); // 判断是否需要休息
//                    } else {
//                        // 不满足时间窗口约束
//                        System.out.println("The vehicle " + getVehicle().getId() +
//                                " should arrive between the collect time window.");
////                        return false;
//                        return new InnerTuple<>(false,new GlobalData());
//                    }
//                }
//
//                // 测试总用时
//                Date overallCollectTime = new Date(curTime);
//                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
//                System.out.println("Overall Time: " + sdf.format(overallCollectTime));
//                System.out.println("Overall Drive Time: " + (driveTime / (1000 * 60)) + "min");
//                System.out.println("Overall Other work Time: " + (otherTime / (1000 * 60)) + "min");
//                System.out.println("Overall Break Time: " + (breakTime / (1000 * 60)) + "min\n");
//
//                // 4. 车辆必须在送货时间窗口内到达（若早于，则等待；若晚于，则拒绝添加该请求）
//                Site deliverSite = data.getLocationList().get((int) customer.getDeliverSite().getId()); // 送货地点
//                System.out.println("deliverSite: " + deliverSite.toString());
//
//                if (collectSite != deliverSite) { // TODO  取货地点、送货地点不同
//                    // 判断到达送货地点的时间是否在时间窗口内
//                    JSONArray arr = new JSONArray((String) collectSite.getDisAndTime().toList().get((int) deliverSite.getId()));
//                    long deliverRouteTime = ((Integer) arr.opt(1)).longValue(); // 加上取货地点->送货地点的时间
//                    long tmp = curTime; // 记录更新操作前的当前时间
//
//                    System.out.println("deliverRouteTime: " + deliverRouteTime + "s");
//                    sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
//                    Date deliverWindowStart = new Date(customer.getDeliverTimeWindow().getStart().getTime());
//                    Date deliverWindowEnd = new Date(customer.getDeliverTimeWindow().getEnd().getTime());
//                    System.out.println("deliverWindow: " + sdf.format(deliverWindowStart) + "; " + sdf.format(deliverWindowEnd));
//                    System.out.println("deliverTime: " + customer.getDeliverTimeinMinutes() + "ms");
//
//                    if ((curTime + deliverRouteTime * 1000)
//                            >= customer.getDeliverTimeWindow().getStart().getTime()
//                            && (curTime + deliverRouteTime * 1000)
//                            <= customer.getDeliverTimeWindow().getEnd().getTime()) {
//                        System.out.println("!!directly DELIVER!!"); // test
//
//                        // 送货，计算送货用时
//                        curTime += deliverRouteTime * 1000
//                                + customer.getDeliverTimeinMinutes() * 60 * 1000;
//                        driveTime += deliverRouteTime * 1000;
//                        dailyDriveTime += deliverRouteTime * 1000; // 更新每日累积驾驶时间
//                        timeList=createTimeNodeTmp(getVehicle(),
//                                tmp,
//                                deliverRouteTime * 1000,
//                                "drive",
//                                 timeList); // 创建时间节点
////                        needBreaks(curTime, breakTime, otherTime, driveTime); // 判断是否需要休息
//                        otherTime += customer.getDeliverTimeinMinutes() * 60 * 1000;
//                        timeList=createTimeNodeTmp(getVehicle(),
//                                tmp + (deliverRouteTime * 1000),
//                                customer.getDeliverTimeinMinutes() * 60 * 1000,
//                                "other",
//                                timeList); // 创建时间节点
////                        needBreaks(curTime, breakTime, otherTime, driveTime); // 判断是否需要休息
//                    } else if ((curTime + deliverRouteTime * 1000)
//                            < customer.getDeliverTimeWindow().getStart().getTime()) {
//                        System.out.println("!!directly DELIVER!!"); // test
//
//                        // 若需要等待直到送货时间窗口开放，则需要加上等待窗口开放的时间
//                        breakTime += Math.abs(customer.getDeliverTimeWindow().getStart().getTime() - (curTime + deliverRouteTime * 1000));
//                        timeList=createTimeNodeTmp(getVehicle(),
//                                tmp,
//                                Math.abs(customer.getDeliverTimeWindow().getStart().getTime() - (curTime + deliverRouteTime * 1000)),
//                                "break",
//                                timeList); // 创建时间节点
//                        curTime += deliverRouteTime * 1000
//                                + (customer.getDeliverTimeWindow().getStart().getTime() - (curTime + deliverRouteTime * 1000))
//                                + customer.getDeliverTimeinMinutes() * 60 * 1000;
//                        driveTime += deliverRouteTime * 1000;
//                        dailyDriveTime += deliverRouteTime * 1000; // 更新每日累积驾驶时间
//                        timeList=createTimeNodeTmp(getVehicle(),
//                                tmp
//                                        + Math.abs(customer.getDeliverTimeWindow().getStart().getTime() - (curTime + deliverRouteTime * 1000)),
//                                deliverRouteTime * 1000,
//                                "drive",
//                                timeList); // 创建时间节点
////                        needBreaks(curTime, breakTime, otherTime, driveTime); // 判断是否需要休息
//                        otherTime += customer.getDeliverTimeinMinutes() * 60 * 1000;
//                        timeList=createTimeNodeTmp(getVehicle(),
//                                tmp
//                                        + Math.abs(customer.getDeliverTimeWindow().getStart().getTime() - (curTime + deliverRouteTime * 1000))
//                                        + deliverRouteTime * 1000,
//                                customer.getDeliverTimeinMinutes() * 60 * 1000,
//                                "other",
//                                timeList); // 创建时间节点
////                        needBreaks(curTime, breakTime, otherTime, driveTime); // 判断是否需要休息
//                    } else {
//                        // 不满足时间窗口约束
//                        System.out.println("The vehicle " + getVehicle().getId() +
//                                " should arrive between the deliver time window.");
////                        return false;
//                        return new InnerTuple<>(false,new GlobalData());
//                    }
//                }
//
//                // 测试总用时
//                Date overallDeliverTime = new Date(curTime);
//                System.out.println("Overall Time: " + sdf.format(overallDeliverTime));
//                System.out.println("Overall Drive Time: " + (driveTime / (1000 * 60)) + "min");
//                System.out.println("Overall Other work Time: " + (otherTime / (1000 * 60)) + "min");
//                System.out.println("Overall Break Time: " + (breakTime / (1000 * 60)) + "min\n");
//
//                // 5. 判断是否满足Maximum约束，并计算成本（overall duration time）
//                System.out.println("route duration!!!: " +
//                        ((curTime - (getVehicle().getStartTime()).getTime()) / (1000 * 60 * 60.0)) + "h");
//                System.out.println("daily drive time!!!: " + (dailyDriveTime / (1000 * 60.0)) + "min");
//
//                // 1) route duration
//                long mDuration = (long) getVehicle().getMDurationInHours() * 60 * 60 * 1000;
//                if ((curTime - (getVehicle().getStartTime()).getTime()) > mDuration) {
//                    // 不满足约束
//                    System.out.println("The vehicle " + getVehicle().getId() + " has worked more than 13 hours in one day.");
////                    return false;
//                    return new InnerTuple<>(false,new GlobalData());
//                }
//
//                // 2) daily drive time
//                long mDailyDrive = (long) getVehicle().getmDailyDriveInHours() * 60 * 60 * 1000;
//                if ((dailyDriveTime - (getVehicle().getStartTime()).getTime()) > mDailyDrive) {
//                    // 不满足约束
//                    System.out.println("The vehicle " + getVehicle().getId() + " has driven more than 9 hours in one day.");
////                    return false;
//                    return new InnerTuple<>(false,new GlobalData());
//                }
//
//                // 6. 将成功创建使用的参数设置到全局data中
//                GlobalData globalData=new GlobalData(
//                        curTime - (getVehicle().getStartTime()).getTime(), // 从发车到现在的总用时
////                        curTime,
//                        deliverSite,
//                        breakTime,
//                        overallDeliverTime,
//                        getId(),
//                        customer.getId(),
//                        timeList);
//                res=new InnerTuple<>(true,globalData);
////                data.setCurTime(curTime - (getVehicle().getStartTime()).getTime());
////                data.setDeliverSite(deliverSite);
////                data.setBreakTime(breakTime);
////                data.setOverallDeliverTime(overallDeliverTime);
////                return true;
//                return res;
//            } else {
//                System.out.println("Please ensure that the new request is valid.");
//                return new InnerTuple<>(false,new GlobalData());
//    //                  return false;
//            }
//        }
//
//        public long addACustomer(Customer customer, PreProcessData data, GlobalData globalData) {
////            long dailyDriveTime = 0; // 每日累积驾驶时间
//            if (customer != null) {
//                // 1. 获取全局data中需要使用的数据
////                long curTime = data.getCurTime();
////                Site deliverSite = data.getDeliverSite();
////                long breakTime = data.getBreakTime();
////                Date overallDeliverTime = data.getOverallDeliverTime();
//                long curTime = globalData.getCurTime();
//                Site deliverSite = globalData.getDeliverSite();
//                long breakTime = globalData.getBreakTime();
//                Date overallDeliverTime = globalData.getOverallDeliverTime();
//                long routeId=globalData.getRouteId();
//                long customerId=globalData.getCustomerId();
//                TimeList timeList=globalData.getTimeList();
//
//                // 3. 添加请求customer
//                getCustomers().add(customer);
////                siteIds.add(customer.getCollectSite().getId()); // 添加collectSite的id
////                siteIds.add(customer.getDeliverSite().getId()); // 添加deliverSite的id
//
//                // 3. 更新相关参数
//                System.out.println("vehicle " + getVehicle().getId() + "; cursiteid before: " + getVehicle().getCurSiteId()); // test
//                // 设置当前所在地点的id
////                getVehicle().setCurSiteId((int) deliverSite.getId());
//                Vehicle curVehicle = getVehicle(); // 获取当前route的车辆
//                curVehicle.setCurSiteId((int) deliverSite.getId());
//                setVehicle(curVehicle);
//                System.out.println("vehicle " + getVehicle().getId() + "; cursiteid after: " + getVehicle().getCurSiteId()); // test
//
//                // 测试
////                System.out.println("OOO weight: " + getOverallWeight());
////                System.out.println("weight: " + customer.getWeight());
////                System.out.println("OOO duration: " + getOverallDuration());
////                System.out.println("duration: " + curTime);
////                System.out.println("OOO deliver: " + overallDeliverTime);
////                System.out.println("startTime: " + getVehicle().getStartTime());
//
//                int newOverallWeight = getOverallWeight() + customer.getWeight();
//                setOverallWeight(newOverallWeight); // 设置车辆总重量
//                long newOverallDuration = getOverallDuration() + curTime;
//                setOverallDuration(newOverallDuration); // 设置overall route duration
//                long newOverallBreak = getOverallBreak() + breakTime;
//                setOverallBreak(newOverallBreak); // TODO  待实现验证
//                setOverallDistance(0); // TODO  暂不处理distance
//                Date newStartTime = getVehicle().getStartTime();
////                Date newStartTime=new Date(curTime);
//                System.out.println("newStartTime: "+newStartTime);
//                setStartTime(newStartTime); // 设置为路线开始时间
//                setEndTime(overallDeliverTime); // 设置为送货结束的时间（有新请求加入路线时，会更新为最新的结束时间）
//                System.out.println();
////                setOverallWeight(getOverallWeight() + customer.getWeight()); // 设置车辆总重量
////                setOverallDuration(getOverallDuration() + curTime); // 设置overall route duration
////                setOverallBreak(getOverallBreak() + breakTime); // TODO  待实现验证
////                setOverallDistance(0); // TODO  暂不处理distance
////                setStartTime(getVehicle().getStartTime()); // 设置为路线开始时间
//
//                // 4. 更新data中的数据
//                List<Vehicle> sourceVehicleList = data.getVehicleList(); // 获取全局列表Vehicle List
//                curVehicle = sourceVehicleList.get(getRandN()); // 获取全局列表中当前操作的车辆
//                curVehicle.setCurSiteId((int) deliverSite.getId()); // 更新该车辆的信息
//                sourceVehicleList.set(getRandN(), curVehicle); // 更新后的车辆设置回原全局列表中
//
//                // 返回route id
//                return getId();
//            }
//            return -1;
//        }
        // TODO  20241130 0927⭐⭐⭐👆

        public Tuple<Boolean, GlobalData> canAddACustomerCollectFirst(Customer customer, PreProcessData data, boolean collectFirst) {
            long dailyDriveTime = 0; // 每日累积驾驶时间
            int totalDistance = 0;
            InnerTuple<Boolean, GlobalData> res;

            if (customer != null) {
                // 1. 每个请求的取货地点必须在对应的送货地点前被访问
                if (collectFirst) { // 取货阶段
                    for (Customer otherCustomer : getCustomers()) {
                        // 已经送货但还没取货，则不满足
                        if (otherCustomer.getDeliverSite().getId() == customer.getCollectSite().getId()) {
                            if (getCustomers().indexOf(otherCustomer) < getCustomers().indexOf(customer)) {
                                System.out.println("The customer " + customer.getId() + " should meet the precedence constraints: " +
                                        "the collect site must be the predecessor of the delivered site.");
                                return new InnerTuple<>(false, new GlobalData());
                            }
                        }
                    }
                } else { // 送货阶段
                    for (Customer otherCustomer : getCustomers()) {
                        // 已经送了，就不满足
                        if (otherCustomer.getDeliverSite().getId() == customer.getDeliverSite().getId()) {
                            if (getCustomers().indexOf(otherCustomer) < getCustomers().indexOf(customer)) {
                                System.out.println("The customer " + customer.getId() + " should meet the precedence constraints: " +
                                        "the collect site must be the predecessor of the delivered site.");
                                return new InnerTuple<>(false, new GlobalData());
                            }
                        }
                    }
                }

                // 2. 客户容量不超过车辆总承重
                if (collectFirst
                        && (getOverallWeight() + customer.getWeight() > getVehicle().getWeight())) {
                    System.out.println("The capacity of vehicle " + getVehicle().getId() +
                            " is full and new customers cannot be added.");
//                    return false;
                    return new InnerTuple<>(false, new GlobalData());
                }

                // 3. 车辆必须在取货时间窗口内到达（若早于，则等待；若晚于，则拒绝添加该请求）
                long curTime;
                if (!getCustomers().isEmpty()) {
                    curTime = (getVehicle().getStartTime()).getTime() + getOverallDuration(); // 上一次请求的总时间作为当前请求的开始时间
                } else {
                    curTime = (getVehicle().getStartTime()).getTime(); // vehicle的发车时间作为起始时间
                }
                long breakTime = 0, otherTime = 0, driveTime = 0;
                System.out.println("每次请求开始时间！！：" + new Date(curTime));

                if (collectFirst) {
                    // 车辆的发车地点（包含到其他地点的距离\时间）
                    Site startSite = data.getLocationList().get(getVehicle().getCurSiteId()); // 每次请求，都从当前位置开始
                    System.out.println("startSite: " + startSite.toString());
                    // 取货地点，计算车辆从发车地点到取货地点的时间
                    Site collectSite = data.getLocationList().get((int) customer.getCollectSite().getId());
                    System.out.println("collectSite: " + collectSite.toString());

                    //      判断发车地点、取货地点是否是同个地点
                    if (startSite == collectSite) {
                        // 若是，则判断发车时间是否在时间窗口内
                        long tmp = curTime; // 记录更新操作前的当前时间
                        if (curTime >= customer.getCollectTimeWindow().getStart().getTime()
                                && curTime <= customer.getCollectTimeWindow().getEnd().getTime()) {
                            System.out.println("!!!!!!directly COLLECT!!"); // test
                            // 在时间窗口内，则直接取货，计算取货用时
                            curTime += customer.getCollectTimeinMinutes() * 60 * 1000;
                            otherTime += customer.getCollectTimeinMinutes() * 60 * 1000;
                            createTimeNode(getVehicle(),
                                    tmp,
                                    customer.getCollectTimeinMinutes() * 60 * 1000,
                                    customer.getCollectId(),
                                    0); // 创建时间节点
                        } else if (curTime < customer.getCollectTimeWindow().getStart().getTime()) {
                            System.out.println("!!!!!!wait and directly COLLECT!!"); // test
                            // 若需要等待直到取货时间窗口开放，则需要加上等待窗口开放的时间
                            curTime += (customer.getCollectTimeWindow().getStart().getTime() - curTime)
                                    + customer.getCollectTimeinMinutes() * 60 * 1000;
                            breakTime += customer.getCollectTimeWindow().getStart().getTime() - curTime;
                            createTimeNode(getVehicle(),
                                    tmp,
                                    customer.getCollectTimeWindow().getStart().getTime() - curTime,
                                    "wait",
                                    0); // 创建时间节点
//                        needBreaks(curTime-tmp, breakTime, otherTime, driveTime); // check whether a break is need
                            otherTime += customer.getCollectTimeinMinutes() * 60 * 1000;
                            createTimeNode(getVehicle(),
                                    tmp + breakTime,
                                    customer.getCollectTimeinMinutes() * 60 * 1000,
                                    customer.getCollectId(),
                                    0); // 创建时间节点
//                        needBreaks(curTime-tmp, breakTime, otherTime, driveTime); // check whether a break is need
                        } else {
                            // 不满足时间窗口约束
                            System.out.println("The vehicle " + getVehicle().getId() +
                                    " should arrive between the collect time window.");
//                        return false;
                            return new InnerTuple<>(false, new GlobalData());
                        }
                        needBreaks(curTime - tmp, breakTime, otherTime, driveTime); // check whether a break is need
                    } else {
                        JSONArray arr = new JSONArray((String) startSite.getDisAndTime().toList().get((int) collectSite.getId()));
                        long collectRouteTime = ((Integer) arr.opt(1)).longValue(); // 加上发车地点->取货地点的时间
                        int collectRouteDistance = (Integer) arr.opt(0); // distance between start site and collect site
                        long tmp = curTime; // 记录更新操作前的当前时间

                        System.out.println("collectRouteTime: " + collectRouteTime + "s");
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
                        Date collectWindowStart = new Date(customer.getCollectTimeWindow().getStart().getTime());
                        Date collectWindowEnd = new Date(customer.getCollectTimeWindow().getEnd().getTime());
                        System.out.println("collectWindow: " + sdf.format(collectWindowStart) + "; " + sdf.format(collectWindowEnd));
                        System.out.println("collectTime: " + customer.getCollectTimeinMinutes() + "min");
                        System.out.println("carStartTime: " + sdf.format(getVehicle().getStartTime()));

                        if ((curTime + collectRouteTime * 1000)
                                >= customer.getCollectTimeWindow().getStart().getTime()
                                && (curTime + collectRouteTime * 1000)
                                <= customer.getCollectTimeWindow().getEnd().getTime()) {
                            System.out.println("!!!!!!go to another site to COLLECT!!"); // test

                            // 取货，计算取货用时
                            curTime += collectRouteTime * 1000
                                    + customer.getCollectTimeinMinutes() * 60 * 1000;
                            driveTime += collectRouteTime * 1000;
                            dailyDriveTime += collectRouteTime * 1000; // 更新每日累积驾驶时间
                            totalDistance += collectRouteDistance;
                            createTimeNode(getVehicle(),
                                    tmp,
                                    collectRouteTime * 1000,
                                    "drive",
                                    collectRouteDistance); // 创建时间节点
                            needBreaks(curTime - tmp, breakTime, otherTime, driveTime); // check whether a break is need
                            otherTime += customer.getCollectTimeinMinutes() * 60 * 1000;
                            createTimeNode(getVehicle(),
                                    tmp + collectRouteTime * 1000,
                                    customer.getCollectTimeinMinutes() * 60 * 1000,
                                    customer.getCollectId(),
                                    0); // 创建时间节点
                            needBreaks(curTime - tmp, breakTime, otherTime, driveTime); // check whether a break is need
                        } else if ((curTime + collectRouteTime * 1000)
                                < customer.getCollectTimeWindow().getStart().getTime()) {
                            System.out.println("!!!!!!go to another site to COLLECT!!"); // test

                            // 若需要等待直到取货时间窗口开放，则需要加上等待窗口开放的时间
                            breakTime += Math.abs(customer.getCollectTimeWindow().getStart().getTime() - (curTime + collectRouteTime * 1000));
                            createTimeNode(getVehicle(),
                                    tmp,
                                    Math.abs(customer.getCollectTimeWindow().getStart().getTime() - (curTime + collectRouteTime * 1000)),
                                    "wait",
                                    0); // 创建时间节点
                            curTime += collectRouteTime * 1000
                                    + (customer.getCollectTimeWindow().getStart().getTime() - (curTime + collectRouteTime * 1000))
                                    + customer.getCollectTimeinMinutes() * 60 * 1000;
                            driveTime += collectRouteTime * 1000;
                            dailyDriveTime += collectRouteTime * 1000; // 更新每日累积驾驶时间
                            totalDistance += collectRouteDistance;
                            createTimeNode(getVehicle(),
                                    tmp
                                            + Math.abs(customer.getCollectTimeWindow().getStart().getTime() - (curTime + collectRouteTime * 1000)),
                                    collectRouteTime * 1000,
                                    "drive",
                                    collectRouteDistance); // 创建时间节点
                            needBreaks(curTime - tmp, breakTime, otherTime, driveTime); // check whether a break is need
                            otherTime += customer.getCollectTimeinMinutes() * 60 * 1000;
                            createTimeNode(getVehicle(),
                                    tmp
                                            + Math.abs(customer.getCollectTimeWindow().getStart().getTime() - (curTime + collectRouteTime * 1000))
                                            + collectRouteTime * 1000,
                                    customer.getCollectTimeinMinutes() * 60 * 1000,
                                    customer.getCollectId(),
                                    0); // 创建时间节点
                            needBreaks(curTime - tmp, breakTime, otherTime, driveTime); // check whether a break is need
                        } else {
                            // 不满足时间窗口约束
                            System.out.println("The vehicle " + getVehicle().getId() +
                                    " should arrive between the collect time window.");
//                        return false;
                            return new InnerTuple<>(false, new GlobalData());
                        }
                    }

                    // 测试总用时
                    Date overallCollectTime = new Date(curTime);
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
                    System.out.println("Overall Time: " + sdf.format(overallCollectTime));
                    System.out.println("Overall Drive Time: " + (driveTime / (1000 * 60)) + "min");
                    System.out.println("Overall Other work Time: " + (otherTime / (1000 * 60)) + "min");
                    System.out.println("Overall Break Time: " + (breakTime / (1000 * 60)) + "min\n");

                    // 6. 将成功创建使用的参数设置到全局data中
                    GlobalData globalData = new GlobalData(
                            curTime - (getVehicle().getStartTime()).getTime(),
                            data.getLocationList().get((int) customer.getCollectSite().getId()),
                            breakTime, overallCollectTime,
                            getId(), customer.getId(),
                            dailyDriveTime, totalDistance);
                    res = new InnerTuple<>(true, globalData);
                    // 设置到全局
                    data.setCurTime(curTime - (getVehicle().getStartTime()).getTime());
                    data.setDeliverSite(data.getLocationList().get((int) customer.getCollectSite().getId()));
                    data.setBreakTime(breakTime);
                    data.setOverallDeliverTime(overallCollectTime);
                    return res;
                }

                // 只在送货时/完成所有取货后,检查送货时间窗口
                Date overallDeliverTime = new Date();
                Site deliverSite = new Site();
                if (!collectFirst || (collectFirst && getCustomers().size() == data.getCustomerList().size())) {
                    Site startSite = data.getLocationList().get(getVehicle().getCurSiteId());
                    deliverSite = data.getLocationList().get((int) customer.getDeliverSite().getId()); // 送货地点
                    System.out.println("deliverSite1: " + customer.getId() + "; " + deliverSite.toString());

                    if (startSite != deliverSite) { // 取货地点、送货地点不同
                        // 判断到达送货地点的时间是否在时间窗口内
                        JSONArray arr = new JSONArray((String) startSite.getDisAndTime().toList().get((int) deliverSite.getId()));
                        long deliverRouteTime = ((Integer) arr.opt(1)).longValue(); // 加上取货地点->送货地点的时间
                        int deliverRouteDistance = (Integer) arr.opt(0); // distance between collect site and deliver site
                        long tmp = curTime; // 记录更新操作前的当前时间

                        System.out.println("deliverRouteTime: " + deliverRouteTime + "s");
//                    System.out.println("22: " + curTime + "ms; " + customer.getDeliverTimeWindow().getStart().getTime() + "ms");
//                    System.out.println("222: " + ((customer.getDeliverTimeWindow().getStart().getTime()) - curTime) + "ms");
//
                        SimpleDateFormat sdf;
                        sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
                        Date deliverWindowStart = new Date(customer.getDeliverTimeWindow().getStart().getTime());
                        Date deliverWindowEnd = new Date(customer.getDeliverTimeWindow().getEnd().getTime());
                        System.out.println("deliverWindow: " + sdf.format(deliverWindowStart) + "; " + sdf.format(deliverWindowEnd));
                        System.out.println("deliverTime: " + customer.getDeliverTimeinMinutes() + "ms");

                        if ((curTime + deliverRouteTime * 1000)
                                >= customer.getDeliverTimeWindow().getStart().getTime()
                                && (curTime + deliverRouteTime * 1000)
                                <= customer.getDeliverTimeWindow().getEnd().getTime()) {
                            System.out.println("!!go to another site to DELIVER!!"); // test

                            // 送货，计算送货用时
                            curTime += deliverRouteTime * 1000
                                    + customer.getDeliverTimeinMinutes() * 60 * 1000;
                            driveTime += deliverRouteTime * 1000;
                            dailyDriveTime += deliverRouteTime * 1000; // 更新每日累积驾驶时间
                            totalDistance += deliverRouteDistance;
                            createTimeNode(getVehicle(),
                                    tmp,
                                    deliverRouteTime * 1000,
                                    "drive",
                                    deliverRouteDistance); // 创建时间节点4
                            needBreaks(curTime - tmp, breakTime, otherTime, driveTime); // check whether a break is need
                            otherTime += customer.getDeliverTimeinMinutes() * 60 * 1000;
                            createTimeNode(getVehicle(),
                                    tmp + (deliverRouteTime * 1000),
                                    customer.getDeliverTimeinMinutes() * 60 * 1000,
                                    customer.getDeliverId(),
                                    0); // 创建时间节点
                            needBreaks(curTime - tmp, breakTime, otherTime, driveTime); // check whether a break is need
                        } else if ((curTime + deliverRouteTime * 1000)
                                < customer.getDeliverTimeWindow().getStart().getTime()) {
                            System.out.println("!!go to another site to DELIVER!!"); // test

                            // 若需要等待直到送货时间窗口开放，则需要加上等待窗口开放的时间
                            breakTime += Math.abs(customer.getDeliverTimeWindow().getStart().getTime() - (curTime + deliverRouteTime * 1000));
                            createTimeNode(getVehicle(),
                                    tmp,
                                    Math.abs(customer.getDeliverTimeWindow().getStart().getTime() - (curTime + deliverRouteTime * 1000)),
                                    "wait",
                                    0); // 创建时间节点
                            curTime += deliverRouteTime * 1000
                                    + (customer.getDeliverTimeWindow().getStart().getTime() - (curTime + deliverRouteTime * 1000))
                                    + customer.getDeliverTimeinMinutes() * 60 * 1000;
                            driveTime += deliverRouteTime * 1000;
                            dailyDriveTime += deliverRouteTime * 1000; // 更新每日累积驾驶时间
                            totalDistance += deliverRouteDistance;
                            createTimeNode(getVehicle(),
                                    tmp
                                            + Math.abs(customer.getDeliverTimeWindow().getStart().getTime() - (curTime + deliverRouteTime * 1000)),
                                    deliverRouteTime * 1000,
                                    "drive",
                                    deliverRouteDistance); // 创建时间节点
                            needBreaks(curTime - tmp, breakTime, otherTime, driveTime); // check whether a break is need
                            otherTime += customer.getDeliverTimeinMinutes() * 60 * 1000;
                            createTimeNode(getVehicle(),
                                    tmp
                                            + Math.abs(customer.getDeliverTimeWindow().getStart().getTime() - (curTime + deliverRouteTime * 1000))
                                            + deliverRouteTime * 1000,
                                    customer.getDeliverTimeinMinutes() * 60 * 1000,
                                    customer.getDeliverId(),
                                    0); // 创建时间节点
                            needBreaks(curTime - tmp, breakTime, otherTime, driveTime); // check whether a break is need
                        } else {
                            // 不满足时间窗口约束
                            System.out.println("The vehicle " + getVehicle().getId() +
                                    " should arrive between the deliver time window.");
//                        return false;
                            return new InnerTuple<>(false, new GlobalData());
                        }
                    } else {
                        long tmp = curTime;
                        if (curTime >= customer.getDeliverTimeWindow().getStart().getTime()
                                && curTime <= customer.getDeliverTimeWindow().getEnd().getTime()) {
                            System.out.println("!!directly DELIVER!!");
                            curTime += customer.getDeliverTimeinMinutes() * 60 * 1000;
                            otherTime += customer.getDeliverTimeinMinutes() * 60 * 1000;
                            createTimeNode(getVehicle(),
                                    tmp,
                                    customer.getDeliverTimeinMinutes() * 60 * 1000,
                                    customer.getDeliverId(),
                                    0);
                        } else if (curTime < customer.getDeliverTimeWindow().getStart().getTime()) {
                            System.out.println("!!directly DELIVER!!");
                            curTime += (customer.getDeliverTimeWindow().getStart().getTime() - curTime)
                                    + customer.getDeliverTimeinMinutes() * 60 * 1000;
                            breakTime += customer.getDeliverTimeWindow().getStart().getTime() - curTime;
                            createTimeNode(getVehicle(),
                                    tmp,
                                    customer.getDeliverTimeWindow().getStart().getTime() - curTime,
                                    "wait",
                                    0);
                            otherTime += customer.getDeliverTimeinMinutes() * 60 * 1000;
                            createTimeNode(getVehicle(), tmp + breakTime, customer.getDeliverTimeinMinutes() * 60 * 1000, customer.getDeliverId(), 0);
                        } else {
                            System.out.println("The vehicle " + getVehicle().getId() +
                                    " should arrive between the deliver time window.");
                            return new InnerTuple<>(false, new GlobalData());
                        }
                        needBreaks(curTime - tmp, breakTime, otherTime, driveTime);
                    }

                    // 测试总用时
                    overallDeliverTime = new Date(curTime);
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
                    System.out.println("Overall Time': " + sdf.format(overallDeliverTime));
                    System.out.println("Overall Drive Time: " + (driveTime / (1000 * 60)) + "min");
                    System.out.println("Overall Other work Time: " + (otherTime / (1000 * 60)) + "min");
                    System.out.println("Overall Break Time: " + (breakTime / (1000 * 60)) + "min");
                    System.out.println("Overall Distance: " + totalDistance + "\n");
                }

                // 只有在所有取货和送货都完成后才进行Maximum约束检查
//                if (!collectFirst && getCustomers().size() == data.getCustomerList().size()) {
                if (!collectFirst) {
                    System.out.println("route duration: " +
                            ((curTime - (getVehicle().getStartTime()).getTime()) / (1000 * 60 * 60.0)) + "h");
                    System.out.println("daily drive time: " + (dailyDriveTime / (1000 * 60.0)) + "min");

                    // 1) route duration
                    long mDuration = (long) getVehicle().getMDurationInHours() * 60 * 60 * 1000;
                    if ((curTime - (getVehicle().getStartTime()).getTime()) > mDuration) {
                        // 不满足约束
                        System.out.println("The vehicle " + getVehicle().getId() + " has worked more than 13 hours in one day.");
//                    return false;
                        return new InnerTuple<>(false, new GlobalData());
                    }
                    // 2) daily drive time
                    long mDailyDrive = (long) getVehicle().getmDailyDriveInHours() * 60 * 60 * 1000;
                    if ((dailyDriveTime - (getVehicle().getStartTime()).getTime()) > mDailyDrive) {
                        // 不满足约束
                        System.out.println("The vehicle " + getVehicle().getId() + " has driven more than 9 hours in one day.");
//                    return false;
                        return new InnerTuple<>(false, new GlobalData());
                    }

                    // 6. 将成功创建使用的参数设置到全局data中
                    GlobalData globalData = new GlobalData(
                            curTime - (getVehicle().getStartTime()).getTime(),
//                            data.getLocationList().get((int) customer.getDeliverSite().getId()),
                            deliverSite,
                            breakTime, overallDeliverTime,
                            getId(), customer.getId(),
                            dailyDriveTime,
                            totalDistance);
                    res = new InnerTuple<>(true, globalData);

                    // 设置到全局
                    System.out.println("更新: " + new Date(curTime) + "; 更新: " + getVehicle().getStartTime());
                    data.setCurTime(curTime - (getVehicle().getStartTime()).getTime());
                    data.setDeliverSite(deliverSite);
//                    data.setDeliverSite(data.getLocationList().get((int) customer.getDeliverSite().getId()));
                    data.setBreakTime(breakTime);
                    data.setOverallDeliverTime(overallDeliverTime);

                    // 标记为已送货
                    customer.setDelivered(true);
                } else {
                    res = new InnerTuple<>(true, new GlobalData());
                }

//                // 车辆的发车地点（包含到其他地点的距离\时间）
////                Site startSite = data.getLocationList().get(getVehicle().getStartSite());
//                Site startSite = data.getLocationList().get(getVehicle().getCurSiteId()); // 每次请求，都从当前位置开始
//                System.out.println("startSite: " + startSite.toString());
//                // 取货地点，计算车辆从发车地点到取货地点的时间
//                Site collectSite = data.getLocationList().get((int) customer.getCollectSite().getId());
//                System.out.println("collectSite: " + collectSite.toString());
//
//                //      判断发车地点、取货地点是否是同个地点
//                if (startSite == collectSite) {
//                    // 若是，则判断发车时间是否在时间窗口内
//                    long tmp = curTime; // 记录更新操作前的当前时间
//                    if (curTime >= customer.getCollectTimeWindow().getStart().getTime()
//                            && curTime <= customer.getCollectTimeWindow().getEnd().getTime()) {
//                        System.out.println("!!directly COLLECT!!"); // test
//                        // 在时间窗口内，则直接取货，计算取货用时
//                        curTime += customer.getCollectTimeinMinutes() * 60 * 1000;
//                        otherTime += customer.getCollectTimeinMinutes() * 60 * 1000;
//                        createTimeNode(getVehicle(),
//                                tmp,
//                                customer.getCollectTimeinMinutes() * 60 * 1000,
//                                customer.getCollectId(),
//                                0); // 创建时间节点
////                        needBreaks(curTime-tmp, breakTime, otherTime, driveTime); // check whether a break is need
//                    } else if (curTime < customer.getCollectTimeWindow().getStart().getTime()) {
//                        System.out.println("!!directly COLLECT!!"); // test
//                        // 若需要等待直到取货时间窗口开放，则需要加上等待窗口开放的时间
//                        curTime += (customer.getCollectTimeWindow().getStart().getTime() - curTime)
//                                + customer.getCollectTimeinMinutes() * 60 * 1000;
//                        breakTime += customer.getCollectTimeWindow().getStart().getTime() - curTime;
//                        createTimeNode(getVehicle(),
//                                tmp,
//                                customer.getCollectTimeWindow().getStart().getTime() - curTime,
//                                "wait",
//                                0); // 创建时间节点
////                        needBreaks(curTime-tmp, breakTime, otherTime, driveTime); // check whether a break is need
//                        otherTime += customer.getCollectTimeinMinutes() * 60 * 1000;
//                        createTimeNode(getVehicle(),
//                                tmp + breakTime,
//                                customer.getCollectTimeinMinutes() * 60 * 1000,
//                                customer.getCollectId(),
//                                0); // 创建时间节点
////                        needBreaks(curTime-tmp, breakTime, otherTime, driveTime); // check whether a break is need
//                    } else {
//                        // 不满足时间窗口约束
//                        System.out.println("The vehicle " + getVehicle().getId() +
//                                " should arrive between the collect time window.");
////                        return false;
//                        return new InnerTuple<>(false,new GlobalData());
//                    }
//                    needBreaks(curTime-tmp, breakTime, otherTime, driveTime); // check whether a break is need
//                } else {
//                    // 否则，需要加上行驶路程的时间、距离，判断发车时间是否在时间窗口内
//                    JSONArray arr = new JSONArray((String) startSite.getDisAndTime().toList().get((int) collectSite.getId()));
//                    long collectRouteTime = ((Integer) arr.opt(1)).longValue(); // 加上发车地点->取货地点的时间
//                    int collectRouteDistance= (Integer) arr.opt(0); // distance between start site and collect site
//                    long tmp = curTime; // 记录更新操作前的当前时间
//
//                    System.out.println("collectRouteTime: " + collectRouteTime + "s");
//                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
//                    Date collectWindowStart = new Date(customer.getCollectTimeWindow().getStart().getTime());
//                    Date collectWindowEnd = new Date(customer.getCollectTimeWindow().getEnd().getTime());
//                    System.out.println("collectWindow: " + sdf.format(collectWindowStart) + "; " + sdf.format(collectWindowEnd));
//                    System.out.println("collectTime: " + customer.getCollectTimeinMinutes() + "min");
//                    System.out.println("carStartTime: " + sdf.format(getVehicle().getStartTime()));
//
//                    if ((curTime + collectRouteTime * 1000)
//                            >= customer.getCollectTimeWindow().getStart().getTime()
//                            && (curTime + collectRouteTime * 1000)
//                            <= customer.getCollectTimeWindow().getEnd().getTime()) {
//                        System.out.println("!!go to another site to COLLECT!!"); // test
//
//                        // 取货，计算取货用时
//                        curTime += collectRouteTime * 1000
//                                + customer.getCollectTimeinMinutes() * 60 * 1000;
//                        driveTime += collectRouteTime * 1000;
//                        dailyDriveTime += collectRouteTime * 1000; // 更新每日累积驾驶时间
//                        createTimeNode(getVehicle(),
//                                tmp,
//                                collectRouteTime * 1000,
//                                "drive",
//                                collectRouteDistance); // 创建时间节点
//                        totalDistance+=collectRouteDistance;
//                        needBreaks(curTime-tmp, breakTime, otherTime, driveTime); // check whether a break is need
//                        otherTime += customer.getCollectTimeinMinutes() * 60 * 1000;
//                        createTimeNode(getVehicle(),
//                                tmp + collectRouteTime * 1000,
//                                customer.getCollectTimeinMinutes() * 60 * 1000,
//                                customer.getCollectId(),
//                                0); // 创建时间节点
//                        needBreaks(curTime-tmp, breakTime, otherTime, driveTime); // check whether a break is need
//                    } else if ((curTime + collectRouteTime * 1000)
//                            < customer.getCollectTimeWindow().getStart().getTime()) {
//                        System.out.println("!!go to another site to COLLECT!!"); // test
//
//                        // 若需要等待直到取货时间窗口开放，则需要加上等待窗口开放的时间
//                        breakTime += Math.abs(customer.getCollectTimeWindow().getStart().getTime() - (curTime + collectRouteTime * 1000));
//                        createTimeNode(getVehicle(),
//                                tmp,
//                                Math.abs(customer.getCollectTimeWindow().getStart().getTime() - (curTime + collectRouteTime * 1000)),
//                                "wait",
//                                0); // 创建时间节点
//                        curTime += collectRouteTime * 1000
//                                + (customer.getCollectTimeWindow().getStart().getTime() - (curTime + collectRouteTime * 1000))
//                                + customer.getCollectTimeinMinutes() * 60 * 1000;
//                        driveTime += collectRouteTime * 1000;
//                        dailyDriveTime += collectRouteTime * 1000; // 更新每日累积驾驶时间
//                        createTimeNode(getVehicle(),
//                                tmp
//                                        + Math.abs(customer.getCollectTimeWindow().getStart().getTime() - (curTime + collectRouteTime * 1000)),
//                                collectRouteTime * 1000,
//                                "drive",
//                                collectRouteDistance); // 创建时间节点
//                        totalDistance+=collectRouteDistance;
//                        needBreaks(curTime-tmp, breakTime, otherTime, driveTime); // check whether a break is need
//                        otherTime += customer.getCollectTimeinMinutes() * 60 * 1000;
//                        createTimeNode(getVehicle(),
//                                tmp
//                                        + Math.abs(customer.getCollectTimeWindow().getStart().getTime() - (curTime + collectRouteTime * 1000))
//                                        + collectRouteTime * 1000,
//                                customer.getCollectTimeinMinutes() * 60 * 1000,
//                                customer.getCollectId(),
//                                0); // 创建时间节点
//                        needBreaks(curTime-tmp, breakTime, otherTime, driveTime); // check whether a break is need
//                    } else {
//                        // 不满足时间窗口约束
//                        System.out.println("The vehicle " + getVehicle().getId() +
//                                " should arrive between the collect time window.");
////                        return false;
//                        return new InnerTuple<>(false,new GlobalData());
//                    }
//                }

//                // 测试总用时
//                Date overallCollectTime = new Date(curTime);
//                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
//                System.out.println("Overall Time: " + sdf.format(overallCollectTime));
//                System.out.println("Overall Drive Time: " + (driveTime / (1000 * 60)) + "min");
//                System.out.println("Overall Other work Time: " + (otherTime / (1000 * 60)) + "min");
//                System.out.println("Overall Break Time: " + (breakTime / (1000 * 60)) + "min\n");

//                // 4. 车辆必须在送货时间窗口内到达（若早于，则等待；若晚于，则拒绝添加该请求）
//                Site deliverSite = data.getLocationList().get((int) customer.getDeliverSite().getId()); // 送货地点
//                System.out.println("deliverSite: " + deliverSite.toString());
//
//                if (collectSite != deliverSite) { // TODO  取货地点、送货地点不同
//                    // 判断到达送货地点的时间是否在时间窗口内
//                    JSONArray arr = new JSONArray((String) collectSite.getDisAndTime().toList().get((int) deliverSite.getId()));
//                    long deliverRouteTime = ((Integer) arr.opt(1)).longValue(); // 加上取货地点->送货地点的时间
//                    int deliverRouteDistance= (Integer) arr.opt(0); // distance between collect site and deliver site
//                    long tmp = curTime; // 记录更新操作前的当前时间
//
//                    System.out.println("deliverRouteTime: " + deliverRouteTime + "s");
////                    System.out.println("22: " + curTime + "ms; " + customer.getDeliverTimeWindow().getStart().getTime() + "ms");
////                    System.out.println("222: " + ((customer.getDeliverTimeWindow().getStart().getTime()) - curTime) + "ms");
////
//                    sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
//                    Date deliverWindowStart = new Date(customer.getDeliverTimeWindow().getStart().getTime());
//                    Date deliverWindowEnd = new Date(customer.getDeliverTimeWindow().getEnd().getTime());
//                    System.out.println("deliverWindow: " + sdf.format(deliverWindowStart) + "; " + sdf.format(deliverWindowEnd));
//                    System.out.println("deliverTime: " + customer.getDeliverTimeinMinutes() + "ms");
//
//                    if ((curTime + deliverRouteTime * 1000)
//                            >= customer.getDeliverTimeWindow().getStart().getTime()
//                            && (curTime + deliverRouteTime * 1000)
//                            <= customer.getDeliverTimeWindow().getEnd().getTime()) {
//                        System.out.println("!!directly DELIVER!!"); // test
//
//                        // 送货，计算送货用时
//                        curTime += deliverRouteTime * 1000
//                                + customer.getDeliverTimeinMinutes() * 60 * 1000;
//                        driveTime += deliverRouteTime * 1000;
//                        dailyDriveTime += deliverRouteTime * 1000; // 更新每日累积驾驶时间
//                        createTimeNode(getVehicle(),
//                                tmp,
//                                deliverRouteTime * 1000,
//                                "drive",
//                                deliverRouteDistance); // 创建时间节点4
//                        totalDistance+=deliverRouteDistance;
//                        needBreaks(curTime-tmp, breakTime, otherTime, driveTime); // check whether a break is need
//                        otherTime += customer.getDeliverTimeinMinutes() * 60 * 1000;
//                        createTimeNode(getVehicle(),
//                                tmp + (deliverRouteTime * 1000),
//                                customer.getDeliverTimeinMinutes() * 60 * 1000,
//                                customer.getDeliverId(),
//                                0); // 创建时间节点
//                        needBreaks(curTime-tmp, breakTime, otherTime, driveTime); // check whether a break is need
//                    } else if ((curTime + deliverRouteTime * 1000)
//                            < customer.getDeliverTimeWindow().getStart().getTime()) {
//                        System.out.println("!!directly DELIVER!!"); // test
//
//                        // 若需要等待直到送货时间窗口开放，则需要加上等待窗口开放的时间
//                        breakTime += Math.abs(customer.getDeliverTimeWindow().getStart().getTime() - (curTime + deliverRouteTime * 1000));
//                        createTimeNode(getVehicle(),
//                                tmp,
//                                Math.abs(customer.getDeliverTimeWindow().getStart().getTime() - (curTime + deliverRouteTime * 1000)),
//                                "wait",
//                                0); // 创建时间节点
//                        curTime += deliverRouteTime * 1000
//                                + (customer.getDeliverTimeWindow().getStart().getTime() - (curTime + deliverRouteTime * 1000))
//                                + customer.getDeliverTimeinMinutes() * 60 * 1000;
//                        driveTime += deliverRouteTime * 1000;
//                        dailyDriveTime += deliverRouteTime * 1000; // 更新每日累积驾驶时间
//                        createTimeNode(getVehicle(),
//                                tmp
//                                        + Math.abs(customer.getDeliverTimeWindow().getStart().getTime() - (curTime + deliverRouteTime * 1000)),
//                                deliverRouteTime * 1000,
//                                "drive",
//                                deliverRouteDistance); // 创建时间节点
//                        totalDistance+=deliverRouteDistance;
//                        needBreaks(curTime-tmp, breakTime, otherTime, driveTime); // check whether a break is need
//                        otherTime += customer.getDeliverTimeinMinutes() * 60 * 1000;
//                        createTimeNode(getVehicle(),
//                                tmp
//                                        + Math.abs(customer.getDeliverTimeWindow().getStart().getTime() - (curTime + deliverRouteTime * 1000))
//                                        + deliverRouteTime * 1000,
//                                customer.getDeliverTimeinMinutes() * 60 * 1000,
//                                customer.getDeliverId(),
//                                0); // 创建时间节点
//                        needBreaks(curTime-tmp, breakTime, otherTime, driveTime); // check whether a break is need
//                    } else {
//                        // 不满足时间窗口约束
//                        System.out.println("The vehicle " + getVehicle().getId() +
//                                " should arrive between the deliver time window.");
////                        return false;
//                        return new InnerTuple<>(false,new GlobalData());
//                    }
//                } else { // PDPTW问题的取送货地点通常是不同的
//                }

//                // 测试总用时
//                Date overallDeliverTime = new Date(curTime);
//                System.out.println("Overall Time: " + sdf.format(overallDeliverTime));
//                System.out.println("Overall Drive Time: " + (driveTime / (1000 * 60)) + "min");
//                System.out.println("Overall Other work Time: " + (otherTime / (1000 * 60)) + "min");
//                System.out.println("Overall Break Time: " + (breakTime / (1000 * 60)) + "min");
//                System.out.println("Overall Distance: " + totalDistance+"\n");

                // 5. 判断是否满足Maximum约束，并计算成本（overall duration time）
//                System.out.println("route duration: " +
//                        ((curTime - (getVehicle().getStartTime()).getTime()) / (1000 * 60 * 60.0)) + "h");
//                System.out.println("daily drive time: " + (dailyDriveTime / (1000 * 60.0)) + "min");
//
//                // 1) route duration
//                long mDuration = (long) getVehicle().getMDurationInHours() * 60 * 60 * 1000;
//                if ((curTime - (getVehicle().getStartTime()).getTime()) > mDuration) {
//                    // 不满足约束
//                    System.out.println("The vehicle " + getVehicle().getId() + " has worked more than 13 hours in one day.");
////                    return false;
//                    return new InnerTuple<>(false,new GlobalData());
//                }
//                // 2) daily drive time
//                long mDailyDrive = (long) getVehicle().getmDailyDriveInHours() * 60 * 60 * 1000;
//                if ((dailyDriveTime - (getVehicle().getStartTime()).getTime()) > mDailyDrive) {
//                    // 不满足约束
//                    System.out.println("The vehicle " + getVehicle().getId() + " has driven more than 9 hours in one day.");
////                    return false;
//                    return new InnerTuple<>(false,new GlobalData());
//                }

//                // 6. 将成功创建使用的参数设置到全局data中
//                GlobalData globalData=new GlobalData(
//                        curTime - (getVehicle().getStartTime()).getTime(),
//                        deliverSite,breakTime,overallDeliverTime,
//                        getId(),customer.getId(),
//                        dailyDriveTime);
//                res=new InnerTuple<>(true,globalData);
//                // 设置到全局中
//                data.setCurTime(curTime - (getVehicle().getStartTime()).getTime());
//                data.setDeliverSite(deliverSite);
//                data.setBreakTime(breakTime);
//                data.setOverallDeliverTime(overallDeliverTime);
//                return true;
                return res;
            } else {
                System.out.println("Please ensure that the new request is valid.");
                return new InnerTuple<>(true, new GlobalData());
//                  return false;
            }
        }

        // TODO  20241129 2115为了实现成本比较 重写函数（后续看这个函数）1130 1100最新可使用这个⭐⭐⭐👇
        public Tuple<Boolean, GlobalData> canAddACustomer(Customer customer, PreProcessData data) {
            long dailyDriveTime = 0; // 每日累积驾驶时间
            int totalDistance = 0;
            InnerTuple<Boolean, GlobalData> res;

            if (customer != null) {
                // 1. 每个请求的取货地点必须在对应的送货地点前被访问
                for (Customer otherCustomer : getCustomers()) {
                    // 找到当前请求的取货地点，已在该路线中存在，则比较顺序
                    if (otherCustomer.getDeliverSite().getId() == customer.getCollectSite().getId()) {
                        // 由于新请求的indexOf返回-1，若只要已存在的请求在当前请求的前面，则不满足
                        if (getCustomers().indexOf(otherCustomer) < getCustomers().indexOf(customer)) {
                            System.out.println("The customer " + customer.getId() + " should meet the precedence constraints: " +
                                    "the collect site must be the predecessor of the delivered site.");
                            return new InnerTuple<>(false, new GlobalData());
//                            return -1;
                        }
                    }
                }

                // 2. 客户容量不超过车辆总承重
                if (getOverallWeight() + customer.getWeight() > getVehicle().getWeight()) {
                    System.out.println("The capacity of vehicle " + getVehicle().getId() +
                            " is full and new customers cannot be added.");
//                    return false;
                    return new InnerTuple<>(false, new GlobalData());
                }

                // 3. 车辆必须在取货时间窗口内到达（若早于，则等待；若晚于，则拒绝添加该请求）
                long curTime;
                if (!getCustomers().isEmpty()) {
                    curTime = (getVehicle().getStartTime()).getTime() + getOverallDuration(); // 上一次请求的总时间作为当前请求的开始时间
                } else {
                    curTime = (getVehicle().getStartTime()).getTime(); // vehicle的发车时间作为起始时间
                }

                long breakTime = 0, otherTime = 0, driveTime = 0;

                System.out.println("每次请求开始时间：" + new Date(curTime));

                // 车辆的发车地点（包含到其他地点的距离\时间）
//                Site startSite = data.getLocationList().get(getVehicle().getStartSite());
                Site startSite = data.getLocationList().get(getVehicle().getCurSiteId()); // 每次请求，都从当前位置开始
                System.out.println("startSite: " + startSite.toString());
                // 取货地点，计算车辆从发车地点到取货地点的时间
                Site collectSite = data.getLocationList().get((int) customer.getCollectSite().getId());
                System.out.println("collectSite: " + collectSite.toString());

                //      判断发车地点、取货地点是否是同个地点
                if (startSite == collectSite) {
                    // 若是，则判断发车时间是否在时间窗口内
                    long tmp = curTime; // 记录更新操作前的当前时间
                    if (curTime >= customer.getCollectTimeWindow().getStart().getTime()
                            && curTime <= customer.getCollectTimeWindow().getEnd().getTime()) {
                        System.out.println("!!directly COLLECT!!"); // test
                        // 在时间窗口内，则直接取货，计算取货用时
                        curTime += customer.getCollectTimeinMinutes() * 60 * 1000;
                        otherTime += customer.getCollectTimeinMinutes() * 60 * 1000;
                        createTimeNode(getVehicle(),
                                tmp,
                                customer.getCollectTimeinMinutes() * 60 * 1000,
                                customer.getCollectId(),
                                0); // 创建时间节点
//                        needBreaks(curTime-tmp, breakTime, otherTime, driveTime); // check whether a break is need
                    } else if (curTime < customer.getCollectTimeWindow().getStart().getTime()) {
                        System.out.println("!!directly COLLECT!!"); // test
                        // 若需要等待直到取货时间窗口开放，则需要加上等待窗口开放的时间
                        curTime += (customer.getCollectTimeWindow().getStart().getTime() - curTime)
                                + customer.getCollectTimeinMinutes() * 60 * 1000;
                        breakTime += customer.getCollectTimeWindow().getStart().getTime() - curTime;
                        createTimeNode(getVehicle(),
                                tmp,
                                customer.getCollectTimeWindow().getStart().getTime() - curTime,
                                "wait",
                                0); // 创建时间节点
//                        needBreaks(curTime-tmp, breakTime, otherTime, driveTime); // check whether a break is need
                        otherTime += customer.getCollectTimeinMinutes() * 60 * 1000;
                        createTimeNode(getVehicle(),
                                tmp + breakTime,
                                customer.getCollectTimeinMinutes() * 60 * 1000,
                                customer.getCollectId(),
                                0); // 创建时间节点
//                        needBreaks(curTime-tmp, breakTime, otherTime, driveTime); // check whether a break is need
                    } else {
                        // 不满足时间窗口约束
                        System.out.println("The vehicle " + getVehicle().getId() +
                                " should arrive between the collect time window.");
//                        return false;
                        return new InnerTuple<>(false, new GlobalData());
                    }
                    needBreaks(curTime - tmp, breakTime, otherTime, driveTime); // check whether a break is need
                } else {
                    // 否则，需要加上行驶路程的时间、距离，判断发车时间是否在时间窗口内
                    JSONArray arr = new JSONArray((String) startSite.getDisAndTime().toList().get((int) collectSite.getId()));
                    long collectRouteTime = ((Integer) arr.opt(1)).longValue(); // 加上发车地点->取货地点的时间
                    int collectRouteDistance = (Integer) arr.opt(0); // distance between start site and collect site
                    long tmp = curTime; // 记录更新操作前的当前时间

                    System.out.println("collectRouteTime: " + collectRouteTime + "s");
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
                    Date collectWindowStart = new Date(customer.getCollectTimeWindow().getStart().getTime());
                    Date collectWindowEnd = new Date(customer.getCollectTimeWindow().getEnd().getTime());
                    System.out.println("collectWindow: " + sdf.format(collectWindowStart) + "; " + sdf.format(collectWindowEnd));
                    System.out.println("collectTime: " + customer.getCollectTimeinMinutes() + "min");
                    System.out.println("carStartTime: " + sdf.format(getVehicle().getStartTime()));

                    if ((curTime + collectRouteTime * 1000)
                            >= customer.getCollectTimeWindow().getStart().getTime()
                            && (curTime + collectRouteTime * 1000)
                            <= customer.getCollectTimeWindow().getEnd().getTime()) {
                        System.out.println("!!go to another site to COLLECT!!"); // test

                        // 取货，计算取货用时
                        curTime += collectRouteTime * 1000
                                + customer.getCollectTimeinMinutes() * 60 * 1000;
                        driveTime += collectRouteTime * 1000;
                        dailyDriveTime += collectRouteTime * 1000; // 更新每日累积驾驶时间
                        totalDistance += collectRouteDistance;
                        createTimeNode(getVehicle(),
                                tmp,
                                collectRouteTime * 1000,
                                "drive",
                                collectRouteDistance); // 创建时间节点
                        needBreaks(curTime - tmp, breakTime, otherTime, driveTime); // check whether a break is need
                        otherTime += customer.getCollectTimeinMinutes() * 60 * 1000;
                        createTimeNode(getVehicle(),
                                tmp + collectRouteTime * 1000,
                                customer.getCollectTimeinMinutes() * 60 * 1000,
                                customer.getCollectId(),
                                0); // 创建时间节点
                        needBreaks(curTime - tmp, breakTime, otherTime, driveTime); // check whether a break is need
                    } else if ((curTime + collectRouteTime * 1000)
                            < customer.getCollectTimeWindow().getStart().getTime()) {
                        System.out.println("!!go to another site to COLLECT!!"); // test

                        // 若需要等待直到取货时间窗口开放，则需要加上等待窗口开放的时间
                        breakTime += Math.abs(customer.getCollectTimeWindow().getStart().getTime() - (curTime + collectRouteTime * 1000));
                        createTimeNode(getVehicle(),
                                tmp,
                                Math.abs(customer.getCollectTimeWindow().getStart().getTime() - (curTime + collectRouteTime * 1000)),
                                "wait",
                                0); // 创建时间节点
                        curTime += collectRouteTime * 1000
                                + (customer.getCollectTimeWindow().getStart().getTime() - (curTime + collectRouteTime * 1000))
                                + customer.getCollectTimeinMinutes() * 60 * 1000;
                        driveTime += collectRouteTime * 1000;
                        dailyDriveTime += collectRouteTime * 1000; // 更新每日累积驾驶时间
                        totalDistance += collectRouteDistance;
                        createTimeNode(getVehicle(),
                                tmp
                                        + Math.abs(customer.getCollectTimeWindow().getStart().getTime() - (curTime + collectRouteTime * 1000)),
                                collectRouteTime * 1000,
                                "drive",
                                collectRouteDistance); // 创建时间节点
                        needBreaks(curTime - tmp, breakTime, otherTime, driveTime); // check whether a break is need
                        otherTime += customer.getCollectTimeinMinutes() * 60 * 1000;
                        createTimeNode(getVehicle(),
                                tmp
                                        + Math.abs(customer.getCollectTimeWindow().getStart().getTime() - (curTime + collectRouteTime * 1000))
                                        + collectRouteTime * 1000,
                                customer.getCollectTimeinMinutes() * 60 * 1000,
                                customer.getCollectId(),
                                0); // 创建时间节点
                        needBreaks(curTime - tmp, breakTime, otherTime, driveTime); // check whether a break is need
                    } else {
                        // 不满足时间窗口约束
                        System.out.println("The vehicle " + getVehicle().getId() +
                                " should arrive between the collect time window.");
//                        return false;
                        return new InnerTuple<>(false, new GlobalData());
                    }
                }

                // 测试总用时
                Date overallCollectTime = new Date(curTime);
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
                System.out.println("Overall Time: " + sdf.format(overallCollectTime));
                System.out.println("Overall Drive Time: " + (driveTime / (1000 * 60)) + "min");
                System.out.println("Overall Other work Time: " + (otherTime / (1000 * 60)) + "min");
                System.out.println("Overall Break Time: " + (breakTime / (1000 * 60)) + "min\n");

                // 4. 车辆必须在送货时间窗口内到达（若早于，则等待；若晚于，则拒绝添加该请求）
                Site deliverSite = data.getLocationList().get((int) customer.getDeliverSite().getId()); // 送货地点
                System.out.println("deliverSite: " + deliverSite.toString());

                if (collectSite != deliverSite) { // 取货地点、送货地点不同
                    // 判断到达送货地点的时间是否在时间窗口内
                    JSONArray arr = new JSONArray((String) collectSite.getDisAndTime().toList().get((int) deliverSite.getId()));
                    long deliverRouteTime = ((Integer) arr.opt(1)).longValue(); // 加上取货地点->送货地点的时间
                    int deliverRouteDistance = (Integer) arr.opt(0); // distance between collect site and deliver site
                    long tmp = curTime; // 记录更新操作前的当前时间

                    System.out.println("deliverRouteTime: " + deliverRouteTime + "s");
//                    System.out.println("22: " + curTime + "ms; " + customer.getDeliverTimeWindow().getStart().getTime() + "ms");
//                    System.out.println("222: " + ((customer.getDeliverTimeWindow().getStart().getTime()) - curTime) + "ms");
//
                    sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
                    Date deliverWindowStart = new Date(customer.getDeliverTimeWindow().getStart().getTime());
                    Date deliverWindowEnd = new Date(customer.getDeliverTimeWindow().getEnd().getTime());
                    System.out.println("deliverWindow: " + sdf.format(deliverWindowStart) + "; " + sdf.format(deliverWindowEnd));
                    System.out.println("deliverTime: " + customer.getDeliverTimeinMinutes() + "ms");

                    if ((curTime + deliverRouteTime * 1000)
                            >= customer.getDeliverTimeWindow().getStart().getTime()
                            && (curTime + deliverRouteTime * 1000)
                            <= customer.getDeliverTimeWindow().getEnd().getTime()) {
                        System.out.println("!!go to another site to DELIVER!!"); // test

                        // 送货，计算送货用时
                        curTime += deliverRouteTime * 1000
                                + customer.getDeliverTimeinMinutes() * 60 * 1000;
                        driveTime += deliverRouteTime * 1000;
                        dailyDriveTime += deliverRouteTime * 1000; // 更新每日累积驾驶时间
                        totalDistance += deliverRouteDistance;
                        createTimeNode(getVehicle(),
                                tmp,
                                deliverRouteTime * 1000,
                                "drive",
                                deliverRouteDistance); // 创建时间节点4
                        needBreaks(curTime - tmp, breakTime, otherTime, driveTime); // check whether a break is need
                        otherTime += customer.getDeliverTimeinMinutes() * 60 * 1000;
                        createTimeNode(getVehicle(),
                                tmp + (deliverRouteTime * 1000),
                                customer.getDeliverTimeinMinutes() * 60 * 1000,
                                customer.getDeliverId(),
                                0); // 创建时间节点
                        needBreaks(curTime - tmp, breakTime, otherTime, driveTime); // check whether a break is need
                    } else if ((curTime + deliverRouteTime * 1000)
                            < customer.getDeliverTimeWindow().getStart().getTime()) {
                        System.out.println("!!go to another site to DELIVER!!"); // test

                        // 若需要等待直到送货时间窗口开放，则需要加上等待窗口开放的时间
                        breakTime += Math.abs(customer.getDeliverTimeWindow().getStart().getTime() - (curTime + deliverRouteTime * 1000));
                        createTimeNode(getVehicle(),
                                tmp,
                                Math.abs(customer.getDeliverTimeWindow().getStart().getTime() - (curTime + deliverRouteTime * 1000)),
                                "wait",
                                0); // 创建时间节点
                        curTime += deliverRouteTime * 1000
                                + (customer.getDeliverTimeWindow().getStart().getTime() - (curTime + deliverRouteTime * 1000))
                                + customer.getDeliverTimeinMinutes() * 60 * 1000;
                        driveTime += deliverRouteTime * 1000;
                        dailyDriveTime += deliverRouteTime * 1000; // 更新每日累积驾驶时间
                        totalDistance += deliverRouteDistance;
                        createTimeNode(getVehicle(),
                                tmp
                                        + Math.abs(customer.getDeliverTimeWindow().getStart().getTime() - (curTime + deliverRouteTime * 1000)),
                                deliverRouteTime * 1000,
                                "drive",
                                deliverRouteDistance); // 创建时间节点
                        needBreaks(curTime - tmp, breakTime, otherTime, driveTime); // check whether a break is need
                        otherTime += customer.getDeliverTimeinMinutes() * 60 * 1000;
                        createTimeNode(getVehicle(),
                                tmp
                                        + Math.abs(customer.getDeliverTimeWindow().getStart().getTime() - (curTime + deliverRouteTime * 1000))
                                        + deliverRouteTime * 1000,
                                customer.getDeliverTimeinMinutes() * 60 * 1000,
                                customer.getDeliverId(),
                                0); // 创建时间节点
                        needBreaks(curTime - tmp, breakTime, otherTime, driveTime); // check whether a break is need
                    } else {
                        // 不满足时间窗口约束
                        System.out.println("The vehicle " + getVehicle().getId() +
                                " should arrive between the deliver time window.");
//                        return false;
                        return new InnerTuple<>(false, new GlobalData());
                    }
                }

                // 测试总用时
                Date overallDeliverTime = new Date(curTime);
                System.out.println("Overall Time: " + sdf.format(overallDeliverTime));
                System.out.println("Overall Drive Time: " + (driveTime / (1000 * 60)) + "min");
                System.out.println("Overall Other work Time: " + (otherTime / (1000 * 60)) + "min");
                System.out.println("Overall Break Time: " + (breakTime / (1000 * 60)) + "min");
                System.out.println("Overall Distance: " + totalDistance + "\n");

                // 5. 判断是否满足Maximum约束，并计算成本（overall duration time）
                System.out.println("route duration: " +
                        ((curTime - (getVehicle().getStartTime()).getTime()) / (1000 * 60 * 60.0)) + "h");
                System.out.println("daily drive time: " + (dailyDriveTime / (1000 * 60.0)) + "min");

                // 1) route duration
                long mDuration = (long) getVehicle().getMDurationInHours() * 60 * 60 * 1000;
                if ((curTime - (getVehicle().getStartTime()).getTime()) > mDuration) {
                    // 不满足约束
                    System.out.println("The vehicle " + getVehicle().getId() + " has worked more than 13 hours in one day.");
                    return new InnerTuple<>(false, new GlobalData());
                }
                // 2) daily drive time
                long mDailyDrive = (long) getVehicle().getmDailyDriveInHours() * 60 * 60 * 1000;
                if ((dailyDriveTime - (getVehicle().getStartTime()).getTime()) > mDailyDrive) {
                    // 不满足约束
                    System.out.println("The vehicle " + getVehicle().getId() + " has driven more than 9 hours in one day.");
                    return new InnerTuple<>(false, new GlobalData());
                }

                // 6. 将成功创建使用的参数设置到全局data中
                GlobalData globalData = new GlobalData(
                        curTime - (getVehicle().getStartTime()).getTime(),
                        deliverSite, breakTime, overallDeliverTime,
                        getId(), customer.getId(),
                        dailyDriveTime,
                        totalDistance);
                res = new InnerTuple<>(true, globalData);
                // 设置到全局中
                data.setCurTime(curTime - (getVehicle().getStartTime()).getTime());
                data.setDeliverSite(deliverSite);
                data.setBreakTime(breakTime);
                data.setOverallDeliverTime(overallDeliverTime);

                // 标记为已送货
                customer.setDelivered(true);
            } else {
                System.out.println("Please ensure that the new request is valid.");
                return new InnerTuple<>(true, new GlobalData());
            }
            return res;
        }

        public long addACustomer(Customer customer, PreProcessData data, GlobalData globalData) {
            if (customer != null) {
                // 1. 获取全局数据中用到的数据
                long curTime = data.getCurTime();
                Site deliverSite = data.getDeliverSite(); // collectSite or deliverSite
                long breakTime = data.getBreakTime();
                Date overallDeliverTime = data.getOverallDeliverTime();
                int totalDistance = globalData.getOverallDistance();
                System.out.println("data.getCurTime：" + data.getCurTime());
                System.out.println("deliverSite: " + deliverSite);
//                long routeId=globalData.getRouteId();
//                long customerId=globalData.getCustomerId();
//                long curTime = globalData.getCurTime();
//                Site deliverSite = globalData.getDeliverSite();
//                long breakTime = globalData.getBreakTime();
//                Date overallDeliverTime = globalData.getOverallDeliverTime();

                // 3. 满足约束，允许添加请求customer
                getCustomers().add(customer);
//                siteIds.add(customer.getCollectSite().getId()); // 添加collectSite的id
//                siteIds.add(customer.getDeliverSite().getId()); // 添加deliverSite的id

                // 3. 更新相关参数
                System.out.println("vehicle " + getVehicle().getId() + "; curSiteId before: " + getVehicle().getCurSiteId()); // test
                // 设置当前所在地点的id
//                getVehicle().setCurSiteId((int) deliverSite.getId());
                Vehicle curVehicle = getVehicle(); // 获取当前route的车辆
                curVehicle.setCurSiteId((int) deliverSite.getId());
                setVehicle(curVehicle);
                System.out.println("vehicle " + getVehicle().getId() + "; cursiteid after: " + getVehicle().getCurSiteId()); // test

//                // 测试
//                System.out.println("OOO weight: " + getOverallWeight());
//                System.out.println("weight: " + customer.getWeight());
//                System.out.println("OOO duration: " + getOverallDuration());
//                System.out.println("duration: " + curTime);
//                System.out.println("OOO deliver: " + overallDeliverTime);
//                System.out.println("startTime: " + getVehicle().getStartTime());

                if (customer.isDelivered()) {
                    int newOverallWeight = getOverallWeight() + customer.getWeight(); // 设置车辆总重量
                    setOverallWeight(newOverallWeight);
                }
//                long newOverallDuration = getOverallDuration() + curTime;
                long newOverallDuration = curTime; // 设置overall route duration until now
                setOverallDuration(newOverallDuration);
                long newOverallBreak = getOverallBreak() + breakTime; // TODO  待验证
                setOverallBreak(newOverallBreak);
                setOverallDistance(getOverallDistance() + totalDistance); // TODO  有问题
                setStartTime(getVehicle().getStartTime()); // 设置为路线开始时间 TODO  1130 1048
                setEndTime(overallDeliverTime); // 设置为送货结束的时间（有新请求加入路线时，会更新为最新的结束时间）
                System.out.println("添加时的curTime：" + curTime);
                System.out.println("添加时的new overall duration：" + newOverallDuration);  // TODO  1130 1020
//               尝试 startTime+getOverallDuration()
//                setStartTime(newStartTime); // 设置为路线开始时间 TODO  1130 1043
//                setOverallDuration(getOverallDuration() + curTime); // 设置overall route duration

                // 4. 更新data中的车辆信息
                List<Vehicle> sourceVehicleList = data.getVehicleList(); // 获取全局列表Vehicle List
                curVehicle = sourceVehicleList.get(getRandN()); // 获取全局列表中当前操作的车辆
                curVehicle.setCurSiteId((int) deliverSite.getId()); // 更新该车辆的信息
                sourceVehicleList.set(getRandN(), curVehicle); // 更新后的车辆设置回原全局列表中
                data.setVehicleList(sourceVehicleList);
//                data.setCurTime(curTime);
//                data.setOverallDeliverTime(overallDeliverTime);

                // 返回route id
                return getId();
            }
            return -1;
        }
        // TODO  20241129 2115为了实现成本比较 重写函数（后续看这个函数）1130 1100最新可使用这个⭐⭐⭐👆

        public Boolean returnToDepot(Customer customer, PreProcessData data, long dailyDriveTime) {
            if (customer != null) {
                // 1. 获取全局data中需要使用的数据
                System.out.println("return back time：" + data.getCurTime());
                long curTime = data.getCurTime(); // 送完货的耗时
                Site deliverSite = data.getDeliverSite();
                Date overallDeliverTime = data.getOverallDeliverTime(); // 送完货的时间

                // 2. 获取终点到起点的时间
                Site endSite = data.getLocationList().get(getVehicle().getEndSite()); // 车辆的终点
                long returnTime;
                int returnDistance;
                if (endSite == deliverSite) { // 车辆终点=送货终点
                    returnTime = 0;
                    returnDistance = 0;
                } else { // 车辆终点!=送货终点
                    JSONArray arr = new JSONArray((String) deliverSite.getDisAndTime().toList().get((int) endSite.getId()));
                    returnTime = ((Integer) arr.opt(1)).longValue(); // 送货终点->起点的时间
                    returnDistance = (Integer) arr.opt(0); // distance between deliver site and start site
                }
                System.out.println("end site:" + endSite.toString());
                System.out.println("deliver site: " + deliverSite.getId() + ";\n content: " + deliverSite.toString());
                System.out.println("vehicle id: " + getVehicle().getStartSite());
                System.out.println("return time: " + returnTime * 1000);

                // 3. 获取总时间,创建时间节点
                try {
                    curTime += returnTime * 1000;
                    createTimeNode(getVehicle(),
                            overallDeliverTime.getTime(),
                            returnTime * 1000, // unit: ms
                            "return",
                            returnDistance); // 创建时间节点

                    // 4. 获取drive time
                    dailyDriveTime += returnTime * 1000;

                    // 5. 更新相关参数
                    System.out.println("vehicle " + getVehicle().getId() + "; cursiteid before: " + getVehicle().getCurSiteId()); // test
                    //      设置当前所在地点的id
                    Vehicle curVehicle = getVehicle(); // 获取当前route的车辆
                    curVehicle.setCurSiteId((int) endSite.getId());
                    setVehicle(curVehicle);
                    System.out.println("vehicle " + getVehicle().getId() + "; cursiteid after: " + getVehicle().getCurSiteId()); // test

//                long newOverallDuration = getOverallDuration() + curTime;
                    long newOverallDuration = curTime;

                    // 6. 判断约束,不满足则抛出异常,由initializePopulation()拒绝该请求
                    // 1) route duration
                    long mDuration = (long) curVehicle.getMDurationInHours() * 60 * 60 * 1000;
                    if (newOverallDuration > mDuration) {
                        throw new RuntimeException("The vehicle " + curVehicle.getId() + " has worked more than 13 hours in one day.");
                    }
                    // 2) daily drive time
                    long mDailyDrive = (long) curVehicle.getmDailyDriveInHours() * 60 * 60 * 1000;
                    if ((dailyDriveTime - (curVehicle.getStartTime()).getTime()) > mDailyDrive) {
                        throw new RuntimeException("The vehicle " + curVehicle.getId() + " has driven more than 9 hours in one day.");
                    }

                    // 7. update parameters
                    setOverallDuration(newOverallDuration); // 设置overall route duration
                    setOverallDistance(getOverallDistance() + returnDistance); // TODO
                    System.out.println("new curTime：" + curTime);
                    System.out.println("new overall duration：" + newOverallDuration);
                    setEndTime(new Date(getStartTime().getTime() + newOverallDuration)); // 设置为回到起点的时间
//                setEndTime(new Date(getEndTime().getTime()+newOverallDuration)); // 设置为回到起点的时间
//                    System.out.println();
                } catch (RuntimeException e) {
                    throw new RuntimeException("The customer cannot meet the constraints.");
                }
            } else {
                throw new RuntimeException("The customer should not be empty.");
            }
            return true;
        }

        @Override
        public String toString() {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            return "RouteDto{" +
                    "id=" + id +
                    ", vehicle='" + vehicle.toString() + '\'' +
                    ", customers='" + customers.toString() + '\'' +
                    ", startTime='" + sdf.format(startTime) + '\'' +
                    ", endTime='" + sdf.format(endTime) + '\'' +
                    ", overallDuration=" + overallDuration +
                    ", overallDistance=" + overallDistance +
                    ", overallWeight=" + overallWeight +
                    ", overallBreak=" + overallBreak +
                    '}';
        }
    }

    /**
     * Individual(chromosome) DTO
     */
    protected static class Individual {
        private long id;
        private List<Route> routes; // 路线列表
        private double fitness; // 适应度值(选择：overall duration time)

        private static int next = 0; // 自增id

        public Individual() {
            this.id = 0;
            this.routes = new ArrayList<>();
            this.fitness = 0.0;
        }

        public Individual(List<Route> routes, double fitness) {
            this.id = next++;
            this.routes = routes;
            this.fitness = fitness;
        }

        public long getId() {
            return id;
        }

        public void setRoutes(List<Route> routes) {
            this.routes = routes;
        }

        public List<Route> getRoutes() {
            return routes;
        }

        public void setFitness(double fitness) {
            this.fitness = fitness;
        }

        public double getFitness() {
            return fitness;
        }

        @Override
        public String toString() {
            return "IndividualDto{" +
                    "id=" + id +
                    ", routes=" + routes.toString() +
                    ", fitness=" + fitness +
                    "}\n";
        }
    }

    /**
     * Population DTO
     */
    protected static class Population {
        private long id;
        private List<Individual> individuals; // 个体列表
        private int size; // 种群大小
        private double overallFitness; // 个体的总适应度
        private List<Double> pointers; // SUS算法的指针位置
        private List<Integer> pointerToIndividual; // SUS的指针对应的个体列表

        private static int next = 0; // 自增id

        public Population() {
            this.id = 0;
            this.individuals = new ArrayList<>();
            this.size = 0;
            this.overallFitness = 0.0;
            this.pointers = new ArrayList<>();
            this.pointerToIndividual = new ArrayList<>();
        }

        public Population(List<Individual> individuals, int size, double overallFitness,
                          List<Double> pointers, List<Integer> pointerToIndividual) {
            this.id = next++;
            this.individuals = individuals;
            this.size = size;
            this.overallFitness = overallFitness;
            this.pointers = pointers;
            this.pointerToIndividual = pointerToIndividual;
        }

        public long getId() {
            return id;
        }

        public void setIndividuals(List<Individual> individuals) {
            this.individuals = individuals;
        }

        public List<Individual> getIndividuals() {
            return individuals;
        }

        public void setSize(int size) {
            this.size = size;
        }

        public int getSize() {
            return size;
        }

        public void setOverallFitness(double overallFitness) {
            this.overallFitness = overallFitness;
        }

        public double getOverallFitness() {
            return overallFitness;
        }

        public void setPointers(List<Double> pointers) {
            this.pointers = pointers;
        }

        public List<Double> getPointers() {
            return pointers;
        }

        public void setPointerToIndividual(List<Integer> pointerToIndividual) {
            this.pointerToIndividual = pointerToIndividual;
        }

        public List<Integer> getPointerToIndividual() {
            return pointerToIndividual;
        }

        @Override
        public String toString() {
            return "PopulationDto{" +
                    "id=" + id +
                    ", individuals=" + individuals.toString() +
                    ", size=" + size +
                    ", overallFitness=" + overallFitness +
                    ", pointers=" + pointers.toString() +
                    ", pointerToIndividual=" + pointerToIndividual.toString() +
                    '}';
        }
    }

    /**
     * Global Data
     */
    protected static class GlobalData {
        private long id;
        private long curTime;
        private Site deliverSite;
        private long breakTime;
        private Date overallDeliverTime; // overall deliver time
        private long routeId;
        private long customerId;
        //        private TimeList timeList; // used for output
        private long dailyDriveTime; // daily drive time
        private int overallDistance; // overall distance

        private static int next = 0; // 自增id

        public GlobalData() {
            this.id = 0;
            this.curTime = 0;
            this.deliverSite = new Site();
            this.breakTime = 0;
            this.overallDeliverTime = new Date();
            this.routeId = 0;
            this.customerId = 0;
//            this.timeList=new TimeList();
            this.dailyDriveTime = 0;
        }

        //        public GlobalData(long curTime,Site deliverSite,long breakTime,Date overallDeliverTime,
//                          long routeId,long customerId, TimeList timeList) {
//            this.id = next++;
//            this.curTime =curTime;
//            this.deliverSite =deliverSite;
//            this.breakTime =breakTime;
//            this.overallDeliverTime =overallDeliverTime;
//            this.routeId =routeId;
//            this.customerId =customerId;
//            this.timeList=timeList;
//        }
        public GlobalData(long curTime, Site deliverSite, long breakTime, Date overallDeliverTime,
                          long routeId, long customerId, long dailyDriveTime, int overallDistance) {
            this.id = next++;
            this.curTime = curTime;
            this.deliverSite = deliverSite;
            this.breakTime = breakTime;
            this.overallDeliverTime = overallDeliverTime;
            this.routeId = routeId;
            this.customerId = customerId;
            this.dailyDriveTime = dailyDriveTime;
            this.overallDistance = overallDistance;
        }

        public long getId() {
            return id;
        }

        public void setCurTime(long curTime) {
            this.curTime = curTime;
        }

        public long getCurTime() {
            return curTime;
        }

        public void setDeliverSite(Site deliverSite) {
            this.deliverSite = deliverSite;
        }

        public Site getDeliverSite() {
            return deliverSite;
        }

        public void setBreakTime(long breakTime) {
            this.breakTime = breakTime;
        }

        public long getBreakTime() {
            return breakTime;
        }

        public void setOverallDeliverTime(Date overallDeliverTime) {
            this.overallDeliverTime = overallDeliverTime;
        }

        public Date getOverallDeliverTime() {
            return overallDeliverTime;
        }

        public void setRouteId(long routeId) {
            this.routeId = routeId;
        }

        public long getRouteId() {
            return routeId;
        }

        public void setCustomerId(long customerId) {
            this.customerId = customerId;
        }

        public long getCustomerId() {
            return customerId;
        }

        //        public void setTimeList(TimeList timeList) {
//            this.timeList=timeList;
//        }
//
//        public TimeList  getTimeList() {
//            return timeList;
//        }
        public void setDailyDriveTime(long dailyDriveTime) {
            this.dailyDriveTime = dailyDriveTime;
        }

        public long getDailyDriveTime() {
            return dailyDriveTime;
        }

        public void setOverallDistance(int overallDistance) {
            this.overallDistance = overallDistance;
        }

        public int getOverallDistance() {
            return overallDistance;
        }

        @Override
        public String toString() {
            return "GlobalDataDto{" +
                    "id=" + id +
                    "curTime=" + curTime +
                    "deliverSite=" + deliverSite.toString() +
                    "breakTime=" + breakTime +
                    "overallDeliverTime=" + overallDeliverTime.toString() +
                    "routeId=" + routeId +
                    "customerId=" + customerId +
//                    "timeList=" + timeList.toString() +
                    "dailyDriveTime=" + dailyDriveTime +
                    "overallDistance=" + overallDistance +
                    "}\n";
        }
    }

    /**
     * pre process DTO
     */
    protected static class PreProcessData {
        String instanceName;
        List<Site> locationList;
        List<Vehicle> vehicleList;
        List<Customer> customerList;
        //        AdjacencyListGraph<SitePair, Integer> ordersGraph; // 查看orders的取送货地点的信息
        AdjacencyListGraph<SiteGraph, Integer> graph; // 所有地点的图

        /* 初始化分配请求customer的数据 */
        Site deliverSite;
        long curTime;
        Date overallDeliverTime;
        long breakTime;

        Random random; // 生成随机数

        public PreProcessData() {
            this.instanceName = "";
            this.locationList = new ArrayList<>();
            this.vehicleList = new ArrayList<>();
            this.customerList = new ArrayList<>();
//            this.ordersGraph = new AdjacencyListGraph<>(true);
            this.graph = new AdjacencyListGraph<>(true);

            this.deliverSite = new Site();
            this.curTime = 0;
            this.overallDeliverTime = new Date();
            this.breakTime = 0;

            this.random = new Random(20717331L); // set student id as seed
        }

        public PreProcessData(String instanceName, List<Site> locationList,
                              List<Vehicle> vehicleList, List<Customer> customerList,
                              AdjacencyListGraph<SiteGraph, Integer> graph, Random random) {
            this.instanceName = instanceName;
            this.locationList = locationList;
            this.vehicleList = vehicleList;
            this.customerList = customerList;
//            this.ordersGraph = ordersGraph;
            this.graph = graph;
            this.random = random;
        }

        public void setInstanceName(String instanceName) {
            this.instanceName = instanceName;
        }

        public String getInstanceName() {
            return instanceName;
        }

        public void setLocationList(List<Site> locationList) {
            this.locationList = locationList;
        }

        public List<Site> getLocationList() {
            return locationList;
        }

        public void setVehicleList(List<Vehicle> vehicleList) {
            this.vehicleList = vehicleList;
        }

        public List<Vehicle> getVehicleList() {
            return vehicleList;
        }

        public void setCustomerList(List<Customer> customerList) {
            this.customerList = customerList;
        }

        public List<Customer> getCustomerList() {
            return customerList;
        }

//        public void setOrdersGraph(AdjacencyListGraph<SitePair, Integer> ordersGraph) {
//            this.ordersGraph = ordersGraph;
//        }
//
//        public AdjacencyListGraph<SitePair, Integer> getOrdersGraph() {
//            return ordersGraph;
//        }

        public void setGraph(AdjacencyListGraph<SiteGraph, Integer> graph) {
            this.graph = graph;
        }

        public AdjacencyListGraph<SiteGraph, Integer> getGraph() {
            return graph;
        }

        public void setDeliverSite(Site deliverSite) {
            this.deliverSite = deliverSite;
        }

        public Site getDeliverSite() {
            return deliverSite;
        }

        public void setCurTime(long curTime) {
            this.curTime = curTime;
        }

        public long getCurTime() {
            return curTime;
        }

        public void setOverallDeliverTime(Date overallDeliverTime) {
            this.overallDeliverTime = overallDeliverTime;
        }

        public Date getOverallDeliverTime() {
            return overallDeliverTime;
        }

        public void setBreakTime(long breakTime) {
            this.breakTime = breakTime;
        }

        public long getBreakTime() {
            return breakTime;
        }

        public void setRandom(Random random) {
            this.random = random;
        }

        public Random getRandom() {
            return random;
        }
    }

    /**
     * DTO for storing successful added customer
     */
    protected static class SucCustomerDto {
        private long id;
        private Customer customer;
        private Individual individual;
        private int individualIdx;
        private Route route;
        private int routeIdx;
        private GlobalData globalData;

        private static int next = 0; // 自增id

        public SucCustomerDto() {
            this.id = 0;
            this.customer = new Customer();
            this.individual = new Individual();
            this.route = new Route();
            this.individualIdx = 0;
            this.routeIdx = 0;
            this.globalData = new GlobalData();
        }

        public SucCustomerDto(Customer customer, Individual individual, Route route,
                              int individualIdx, int routeIdx, GlobalData globalData) {
            this.id = next++;
            this.customer = customer;
            this.individual = individual;
            this.route = route;
            this.individualIdx = individualIdx;
            this.routeIdx = routeIdx;
            this.globalData = globalData;
        }

        public void setCustomer(Customer customer) {
            this.customer = customer;
        }

        public Customer getCustomer() {
            return customer;
        }

        public void setIndividual(Individual individual) {
            this.individual = individual;
        }

        public Individual getIndividual() {
            return individual;
        }

        public void setRoute(Route route) {
            this.route = route;
        }

        public Route getRoute() {
            return route;
        }

        public void setIndividualIdx(int individualIdx) {
            this.individualIdx = individualIdx;
        }

        public int getIndividualIdx() {
            return individualIdx;
        }

        public void setRouteIdx(int routeIdx) {
            this.routeIdx = routeIdx;
        }

        public int getRouteIdx() {
            return routeIdx;
        }

        public void setGlobalData(GlobalData globalData) {
            this.globalData = globalData;
        }

        public GlobalData getGlobalData() {
            return globalData;
        }

        @Override
        public String toString() {
            return "SucCustomerDto{" +
                    "id=" + id +
                    ", customer=" + customer.toString() +
                    ", individual=" + individual.toString() +
                    ", individualIdx=" + individualIdx +
                    ", route=" + route.toString() +
                    ", routeIdx=" + routeIdx +
                    ", globalData=" + globalData +
                    '}';
        }
    }

    protected static List<Customer> unassignedCustomer = new ArrayList<>(); // unassigned customers

    protected static Tuple<List<Individual>, Map<Integer, SucCustomerDto>> assignCustomers(
            List<Individual> individuals, PreProcessData data, boolean method,
            Map<Integer, SucCustomerDto> sucCustomers, List<Customer> customerList) {
        Tuple<List<Individual>, Map<Integer, SucCustomerDto>> res;

        // 4. 对于每个要插入的请求，检查当前（部分）解决方案中所有现有路线的所有可行插入点
        //      测试路线中取货和送货节点的所有可能插入位置，同时考虑优先级、容量和时间约束
        if (!method) { // 先取货后送货
            // 对所有订单按照取货顺序排序
            List<Customer> collectCustomers = new ArrayList<>(customerList);
            collectCustomers.sort((o1, o2) -> Long.compare(o1.getCollectSite().getId(), o2.getCollectSite().getId()));
            System.out.println("Sorted collectCustomers: " + collectCustomers);

            // 遍历取货
            for (Customer customer : collectCustomers) {
                long min = Long.MAX_VALUE;
                Route bestRoute = null;
                Individual bestIndividual = null;
                int routeIdx = -1, individualIdx = -1;
                GlobalData globalData = new GlobalData();

                for (int i = 0; i < individuals.size(); i++) {
                    Individual individual = individuals.get(i);
                    for (int j = 0; j < individual.getRoutes().size(); j++) {
                        Route r = individual.getRoutes().get(j);
                        Tuple<Boolean, GlobalData> canAdd = r.canAddACustomerCollectFirst(customer, data, true);
                        System.out.println("Can add this customer?? " + canAdd.getFirst());
                        if (canAdd != null && canAdd.getFirst()) {
                            long cost = canAdd.getSecond().getCurTime();
                            System.out.println("The cost of this route::: " + cost);
                            System.out.println("min cost::: " + min);
                            if (cost < min) {
                                min = cost;
                                bestRoute = r;
                                bestIndividual = individual;
                                individualIdx = i;
                                routeIdx = j;
                                globalData = canAdd.getSecond();

                                // 构造DTO加入列表
                                SucCustomerDto dto = new SucCustomerDto(customer, bestIndividual, bestRoute, individualIdx, routeIdx, globalData);
                                sucCustomers.put(routeIdx, dto);
//                                System.out.println("dto： "+ dto);
                            }
//                            globalData = canAdd.getSecond();
                        }
                    }
                }

                if (bestRoute != null) {
                    long routeId = bestRoute.addACustomer(customer, data, globalData); // TODO 改过了
                    if (routeId == -1) {
                        unassignedCustomer.add(customer);
                    }
                    double newFitness = bestRoute.getOverallDuration() / (1000 * 60 * 60.0);
                    bestIndividual.setFitness(newFitness);
                    List<Route> rawRoutes = bestIndividual.getRoutes();
                    rawRoutes.set(routeIdx, bestRoute);
                    bestIndividual.setRoutes(rawRoutes);
                    individuals.set(individualIdx, bestIndividual);
                } else {
                    unassignedCustomer.add(customer);
                }
            }
//            System.out.println("大小: "+sucCustomers.toString());
            // 对所有订单按照送货顺序进行排序
            List<Customer> deliverCustomers = new ArrayList<>(customerList);
            deliverCustomers.sort((o1, o2) -> Long.compare(o1.getDeliverSite().getId(), o2.getDeliverSite().getId()));
            System.out.println("Sorted deliverCustomers: " + deliverCustomers);

            // 遍历送货
            for (Customer customer : deliverCustomers) {
                long min = Long.MAX_VALUE;
                Route bestRoute = null;
                Individual bestIndividual = null;
                int routeIdx = -1, individualIdx = -1;
                GlobalData globalData = new GlobalData();

                for (int i = 0; i < individuals.size(); i++) {
                    Individual individual = individuals.get(i);
                    for (int j = 0; j < individual.getRoutes().size(); j++) {
                        Route r = individual.getRoutes().get(j);
                        Tuple<Boolean, GlobalData> canAdd = r.canAddACustomerCollectFirst(customer, data, false);
                        if (canAdd != null && canAdd.getFirst()) {
                            long cost = canAdd.getSecond().getCurTime();
//                            long cost = r.computeCost(customer, data);
                            System.out.println("The cost of this route::！ " + cost);
                            System.out.println("min cost::！ " + min);
                            if (cost < min) {
                                min = cost;
                                bestRoute = r;
                                bestIndividual = individual;
                                individualIdx = i;
                                routeIdx = j;
                                globalData = canAdd.getSecond();
                                System.out.println("min cost::！！ " + min);

                                // 构造DTO加入列表
                                SucCustomerDto dto = new SucCustomerDto(customer, bestIndividual, bestRoute, individualIdx, routeIdx, globalData);
                                sucCustomers.put(routeIdx, dto);
//                                System.out.println("dto2： "+ dto);
                            }
                        }
                    }
                }

                if (bestRoute != null) {
                    long routeId = bestRoute.addACustomer(customer, data, globalData);
                    if (routeId == -1) {
                        System.out.println("这？");
                        unassignedCustomer.add(customer);
                    }
                    double newFitness = bestRoute.getOverallDuration() / (1000 * 60 * 60.0);
                    bestIndividual.setFitness(newFitness);
                    List<Route> rawRoutes = bestIndividual.getRoutes();
                    rawRoutes.set(routeIdx, bestRoute);
                    bestIndividual.setRoutes(rawRoutes);
                    individuals.set(individualIdx, bestIndividual);
                } else {
                    System.out.println("这2？");
                    unassignedCustomer.add(customer);
                }
            }
        } else {
            // 以订单为单位,遍历所有请求Customer，为当前请求Customer分配一辆新车Vehicle，并为该车临时初始化一条新路线Route
            for (int k = 0; k < customerList.size(); k++) {
                Customer customer = customerList.get(k);
                long min = Long.MAX_VALUE; // 设为最大值
                Route bestRoute = null;
                Individual bestIndividual = null;
                int routeIdx = -1, individualIdx = -1; // 最优路线/个体的下标
                GlobalData globalData = new GlobalData();

                // 遍历所有个体的所有路线
                for (int i = 0; i < individuals.size(); i++) {
                    Individual individual = individuals.get(i);
                    for (int j = 0; j < individual.getRoutes().size(); j++) {
                        Route r = individual.getRoutes().get(j);
                        Tuple<Boolean, GlobalData> canAdd = r.canAddACustomer(customer, data); // 在Route中检查是否满足约束，并计算成本
                        System.out.println("Can add this customer? " + canAdd.getFirst());
                        if (canAdd != null && canAdd.getFirst()) {
                            // 能添加
//                            long cost = r.computeCost(customer, data); // 计算成本
                            long cost = canAdd.getSecond().getCurTime();
                            System.out.println("The cost of this route: " + cost);
                            if (cost < min) {
                                // 更新最小值
                                min = cost;
                                bestRoute = r;
                                bestIndividual = individual;
                                individualIdx = i;
                                routeIdx = j;
                                globalData = canAdd.getSecond();

                                // 构造DTO加入列表
                                SucCustomerDto dto = new SucCustomerDto(customer, bestIndividual, bestRoute, individualIdx, routeIdx, globalData);
                                sucCustomers.put(routeIdx, dto);
//                                System.out.println("dto0： "+dto.toString());
                            }
                            globalData = canAdd.getSecond();
                            // TODO  20241201 1025 正在弄若返程错误，则不add这个customer
                        }
//                    else if (canAdd != null && !canAdd.getFirst()){ // unsuccessful
//                        throw new RuntimeException(""+canAdd.getFirst());
//                    }
                    }
                }

                // TODO  20241130 1310尝试在循环外addACustomer
                // 找到成本最低的分配路线
                if (bestRoute != null) {
                    long routeId = bestRoute.addACustomer(customer, data, globalData);
                    if (routeId == -1) {
                        unassignedCustomer.add(customer);
                    }

                    // 更新
//                    System.out.println("bestIndividual.getFitness(): "+bestIndividual.getFitness());
//                    System.out.println("bestRoute.getOverallDuration(): "+bestRoute.getOverallDuration());
//                    double newFitness = bestIndividual.getFitness() + bestRoute.getOverallDuration();
                    double newFitness = bestRoute.getOverallDuration() / (1000 * 60 * 60.0);
                    bestIndividual.setFitness(newFitness); // fitness
                    List<Route> rawRoutes = bestIndividual.getRoutes();
                    rawRoutes.set(routeIdx, bestRoute);
                    bestIndividual.setRoutes(rawRoutes); // 更新route
                    individuals.set(individualIdx, bestIndividual); // 更新individual
                    // TODO  20241130 1310尝试在循环外addACustomer
                } else {
                    unassignedCustomer.add(customer);
                }
            }
        }

        res = new InnerTuple<>(individuals, sucCustomers);
        return res;
    }

    /**
     * 1. For each request to be inserted, all feasible insertions in all existing routes of
     * the current (partial) solution are examined.
     * 2. To this end, all possible insertion positions for the pickup and the delivery node
     * in a route are tested taking into consideration precedence, capacity and time constraints.
     * 3. A new vehicle is allocated and a new route for this vehicle is tentatively initialized
     * with the current request.
     * 4. Among all feasible insertions, the one that causes minimal additional cost is selected
     * and implemented.
     */
    protected static Population initializePopulation(PreProcessData data, int nPop) {
        // 1. initialization
        Population population = new Population(new ArrayList<>(), nPop, 0.0,
                new ArrayList<>(), new ArrayList<>());
        List<Individual> individuals = new ArrayList<>(nPop); // 种群的个体数最多200个
        Random random = data.getRandom(); // 获取随机数种子为studentID的随机数生成类
        int len = data.getVehicleList().size(); // 车辆数

        // 2. generate nPop number of individuals
        for (int i = 0; i < nPop; i++) {
            List<Route> routeList = new ArrayList<>();

            // 生成每条路线，加入个体，随机将车辆分配到路线中
            int randSize = random.nextInt(len); // 随机获取生成的路线总数、随机获取车辆id，[0, len)
            System.out.println("random.nextInt(len): " + randSize);
            if (randSize == 0 && len == 1) { // 若只有一辆车，则设置为1
                randSize = 1;
            }
//            else if (len == 2) { // 两辆车，尝试设置为2  TODO  1202 0000加上的
//                randSize = 2;
//            }
            System.out.println("random.nextInt(len): " + randSize);

            for (int j = 0; j < randSize; j++) {
                // 1)随机获取车辆id
//                int randN = random.nextInt(len); // [0, len)
//                System.out.println("randN: "+randN);
                Vehicle vehicle = data.getVehicleList().get(random.nextInt(len));
                int randN = data.getVehicleList().indexOf(vehicle);
                System.out.println("vehicle: " + vehicle);
                System.out.println("randN: " + randN);

                // 创建一条新路线(请求(Customer)列表为空)，随机分配一辆车
//                Route newRoute = new Route(data.getVehicleList().get(randN),
                Route newRoute = new Route(vehicle,
                        new ArrayList<>(), new Date(), new Date(),
                        0, 0, 0, 0, randN);
                System.out.println("1: " + newRoute.getRandN());
                routeList.add(newRoute);
            }
            // 3)创建个体，配置路线列表等属性(fitness初始化为0)
            Individual individual = new Individual(routeList, 0.0);
            individuals.add(individual);
        }

        System.out.println("initial individuals: " + individuals);

        // 3. using random number to determine whether to collect the goods first and then deliver them in batches,
        //    or in units of orders
        Map<Integer, SucCustomerDto> sucCustomers = new HashMap<>(); // 存放能成功添加的元素,key=routeId,value=DTO
        boolean method = random.nextBoolean();

        // 4. 对于每个要插入的请求，检查当前（部分）解决方案中所有现有路线的所有可行插入点
        //      测试路线中取货和送货节点的所有可能插入位置，同时考虑优先级、容量和时间约束
        Tuple<List<Individual>, Map<Integer, SucCustomerDto>> res =
                assignCustomers(individuals, data, method, sucCustomers, data.getCustomerList());
        if (res != null) {
            individuals = res.getFirst(); // individual list
            sucCustomers = res.getSecond(); // successful customers
        }

        System.out.println("individuals: " + individuals);
        System.out.println("sucCustomers: " + sucCustomers);

//        if (!method) { // 先取货后送货
//            // TODO 20241201 1436注释↓
////            // 取货阶段
////            Map<Integer, SucCustomerDto> sucPickupCustomers = new HashMap<>();
////            for (int k = 0; k < data.getCustomerList().size(); k++) {
////                Customer customer = data.getCustomerList().get(k);
////                long minPickupCost = Long.MAX_VALUE;
////                Route bestPickupRoute = null;
////                Individual bestPickupIndividual = null;
////                int pickupRouteIdx = -1, pickupIndividualIdx = -1;
////                GlobalData pickupGlobalData = new GlobalData();
////
////                for (int i = 0; i < individuals.size(); i++) {
////                    Individual individual = individuals.get(i);
////                    for (int j = 0; j < individual.getRoutes().size(); j++) {
////                        Route r = individual.getRoutes().get(j);
////                        Tuple<Boolean, GlobalData> canAdd = r.canAddACustomerCollectFirst(customer, data, true);
////                        System.out.println("Can add this customer for pickup? " + canAdd.getFirst());
////                        if (canAdd!= null && canAdd.getFirst()) {
////                            long pickupCost = r.computeCost(customer, data);
////                            System.out.println("The pickup cost of this route: " + pickupCost);
////                            if (pickupCost < minPickupCost) {
////                                minPickupCost = pickupCost;
////                                bestPickupRoute = r;
////                                bestPickupIndividual = individual;
////                                pickupIndividualIdx = i;
////                                pickupRouteIdx = j;
////                                pickupGlobalData = canAdd.getSecond();
////                            }
////                            pickupGlobalData = canAdd.getSecond();
////                        }
////                    }
////                }
////
////                if (bestPickupRoute!= null) {
////                    // 构造DTO加入取货成功列表
////                    SucCustomerDto pickupDto = new SucCustomerDto(customer, bestPickupIndividual, bestPickupRoute, pickupIndividualIdx, pickupRouteIdx, pickupGlobalData);
////                    sucPickupCustomers.put(pickupRouteIdx, pickupDto);
////                } else {
////                    unassignedCustomer.add(customer);
////                }
////            }
////
////            // 送货阶段
////            Map<Integer, SucCustomerDto> sucDeliveryCustomers = new HashMap<>();
////            for (Entry<Integer, SucCustomerDto> pickupEntry : sucPickupCustomers.entrySet()) {
////                SucCustomerDto pickupDto = pickupEntry.getValue();
////                Customer customer = pickupDto.getCustomer();
////                Route pickupRoute = pickupDto.getRoute();
////                Individual pickupIndividual = pickupDto.getIndividual();
////                int pickupRouteIdx = pickupDto.getRouteIdx();
////                int pickupIndividualIdx = pickupDto.getIndividualIdx();
////                GlobalData pickupGlobalData = pickupDto.getGlobalData();
////
////                long minDeliveryCost = Long.MAX_VALUE;
////                Route bestDeliveryRoute = null;
////                Individual bestDeliveryIndividual = null;
////                int deliveryRouteIdx = -1, deliveryIndividualIdx = -1;
////                GlobalData deliveryGlobalData = new GlobalData();
////
////                for (int i = 0; i < individuals.size(); i++) {
////                    Individual individual = individuals.get(i);
////                    for (int j = 0; j < individual.getRoutes().size(); j++) {
////                        Route r = individual.getRoutes().get(j);
////                        Tuple<Boolean, GlobalData> canAdd = r.canAddACustomerCollectFirst(customer, data, false);
////                        System.out.println("Can add this customer for delivery? " + canAdd.getFirst());
////                        if (canAdd!= null && canAdd.getFirst()) {
////                            long deliveryCost = r.computeCost(customer, data);
////                            System.out.println("The delivery cost of this route: " + deliveryCost);
////                            if (deliveryCost < minDeliveryCost) {
////                                minDeliveryCost = deliveryCost;
////                                bestDeliveryRoute = r;
////                                bestDeliveryIndividual = individual;
////                                deliveryIndividualIdx = i;
////                                deliveryRouteIdx = j;
////                                deliveryGlobalData = canAdd.getSecond();
////                            }
////                            deliveryGlobalData = canAdd.getSecond();
////                        }
////                    }
////                }
////
////                if (bestDeliveryRoute!= null) {
////                    long routeId = bestDeliveryRoute.addACustomer(customer, data, deliveryGlobalData);
////                    if (routeId == -1) {
////                        unassignedCustomer.add(customer);
////                    } else {
////                        // 更新送货相关信息
////                        double newFitness = bestDeliveryRoute.getOverallDuration() / (1000 * 60 * 60.0);
////                        bestDeliveryIndividual.setFitness(newFitness);
////                        List<Route> rawRoutes = bestDeliveryIndividual.getRoutes();
////                        rawRoutes.set(deliveryRouteIdx, bestDeliveryRoute);
////                        bestDeliveryIndividual.setRoutes(rawRoutes);
////                        individuals.set(deliveryIndividualIdx, bestDeliveryIndividual);
////                    }
////                } else {
////                    unassignedCustomer.add(customer);
////                }
////            }
//            // TODO 20241201 1436注释👆
//
//            // 对所有订单按照取货顺序排序
//            List<Customer> collectCustomers = new ArrayList<>(data.getCustomerList());
//            collectCustomers.sort((o1, o2) -> Long.compare(o1.getCollectSite().getId(), o2.getCollectSite().getId()));
//            System.out.println("Sorted collectCustomers: " + collectCustomers);
//
//            // 遍历取货
//            for (Customer customer : collectCustomers) {
//                long min = Long.MAX_VALUE;
//                Route bestRoute = null;
//                Individual bestIndividual = null;
//                int routeIdx = -1, individualIdx = -1;
//                GlobalData globalData = new GlobalData();
//
//                for (int i = 0; i < individuals.size(); i++) {
//                    Individual individual = individuals.get(i);
//                    for (int j = 0; j < individual.getRoutes().size(); j++) {
//                        Route r = individual.getRoutes().get(j);
//                        Tuple<Boolean, GlobalData> canAdd = r.canAddACustomerCollectFirst(customer, data, true);
//                        System.out.println("Can add this customer?? " + canAdd.getFirst());
//                        if (canAdd != null && canAdd.getFirst()) {
//                            long cost = canAdd.getSecond().getCurTime();
//                            System.out.println("The cost of this route::: " + cost);
//                            System.out.println("min cost::: " + min);
//                            if (cost < min) {
//                                min = cost;
//                                bestRoute = r;
//                                bestIndividual = individual;
//                                individualIdx = i;
//                                routeIdx = j;
//                                globalData = canAdd.getSecond();
//
//                                // 构造DTO加入列表
//                                SucCustomerDto dto = new SucCustomerDto(customer, bestIndividual, bestRoute, individualIdx, routeIdx, globalData);
//                                sucCustomers.put(routeIdx, dto);
////                                System.out.println("dto： "+ dto);
//                            }
////                            globalData = canAdd.getSecond();
//                        }
//                    }
//                }
//
//                if (bestRoute != null) {
//                    long routeId = bestRoute.addACustomer(customer, data, globalData); // TODO 改过了
//                    if (routeId == -1) {
//                        unassignedCustomer.add(customer);
//                    }
//                    double newFitness = bestRoute.getOverallDuration() / (1000 * 60 * 60.0);
//                    bestIndividual.setFitness(newFitness);
//                    List<Route> rawRoutes = bestIndividual.getRoutes();
//                    rawRoutes.set(routeIdx, bestRoute);
//                    bestIndividual.setRoutes(rawRoutes);
//                    individuals.set(individualIdx, bestIndividual);
//                } else {
//                    unassignedCustomer.add(customer);
//                }
//            }
////            System.out.println("大小: "+sucCustomers.toString());
//            // 对所有订单按照送货顺序进行排序
//            List<Customer> deliverCustomers = new ArrayList<>(data.getCustomerList());
//            deliverCustomers.sort((o1, o2) -> Long.compare(o1.getDeliverSite().getId(), o2.getDeliverSite().getId()));
//            System.out.println("Sorted deliverCustomers: " + deliverCustomers);
//
//            // 遍历送货
//            for (Customer customer : deliverCustomers) {
//                long min = Long.MAX_VALUE;
//                Route bestRoute = null;
//                Individual bestIndividual = null;
//                int routeIdx = -1, individualIdx = -1;
//                GlobalData globalData = new GlobalData();
//
//                for (int i = 0; i < individuals.size(); i++) {
//                    Individual individual = individuals.get(i);
//                    for (int j = 0; j < individual.getRoutes().size(); j++) {
//                        Route r = individual.getRoutes().get(j);
//                        Tuple<Boolean, GlobalData> canAdd = r.canAddACustomerCollectFirst(customer, data, false);
//                        if (canAdd != null && canAdd.getFirst()) {
//                            long cost = canAdd.getSecond().getCurTime();
////                            long cost = r.computeCost(customer, data);
//                            System.out.println("The cost of this route::！ " + cost);
//                            System.out.println("min cost::！ " + min);
//                            if (cost < min) {
//                                min = cost;
//                                bestRoute = r;
//                                bestIndividual = individual;
//                                individualIdx = i;
//                                routeIdx = j;
//                                globalData = canAdd.getSecond();
//                                System.out.println("min cost::！！ " + min);
//
//                                // 构造DTO加入列表
//                                SucCustomerDto dto = new SucCustomerDto(customer, bestIndividual, bestRoute, individualIdx, routeIdx, globalData);
//                                sucCustomers.put(routeIdx, dto);
////                                System.out.println("dto2： "+ dto);
//                            }
//                        }
//                    }
//                }
//
//                if (bestRoute != null) {
//                    long routeId = bestRoute.addACustomer(customer, data, globalData);
//                    if (routeId == -1) {
//                        System.out.println("这？");
//                        unassignedCustomer.add(customer);
//                    }
//                    double newFitness = bestRoute.getOverallDuration() / (1000 * 60 * 60.0);
//                    bestIndividual.setFitness(newFitness);
//                    List<Route> rawRoutes = bestIndividual.getRoutes();
//                    rawRoutes.set(routeIdx, bestRoute);
//                    bestIndividual.setRoutes(rawRoutes);
//                    individuals.set(individualIdx, bestIndividual);
//                } else {
//                    System.out.println("这2？");
//                    unassignedCustomer.add(customer);
//                }
//            }
//        } else {
//            // 以订单为单位,遍历所有请求Customer，为当前请求Customer分配一辆新车Vehicle，并为该车临时初始化一条新路线Route
//            for (int k = 0; k < data.getCustomerList().size(); k++) {
//                Customer customer = data.getCustomerList().get(k);
//                long min = Long.MAX_VALUE; // 设为最大值
//                Route bestRoute = null;
//                Individual bestIndividual = null;
//                int routeIdx = -1, individualIdx = -1; // 最优路线/个体的下标
//                GlobalData globalData = new GlobalData();
//
//                // 遍历所有个体的所有路线
//                for (int i = 0; i < individuals.size(); i++) {
//                    Individual individual = individuals.get(i);
//                    for (int j = 0; j < individual.getRoutes().size(); j++) {
//                        Route r = individual.getRoutes().get(j);
//                        Tuple<Boolean, GlobalData> canAdd = r.canAddACustomer(customer, data); // 在Route中检查是否满足约束，并计算成本
//                        System.out.println("Can add this customer? " + canAdd.getFirst());
//                        if (canAdd != null && canAdd.getFirst()) {
//                            // 能添加
////                            long cost = r.computeCost(customer, data); // 计算成本
//                            long cost = canAdd.getSecond().getCurTime();
//                            System.out.println("The cost of this route: " + cost);
//                            if (cost < min) {
//                                // 更新最小值
//                                min = cost;
//                                bestRoute = r;
//                                bestIndividual = individual;
//                                individualIdx = i;
//                                routeIdx = j;
//                                globalData = canAdd.getSecond();
//
//                                // 构造DTO加入列表
//                                SucCustomerDto dto = new SucCustomerDto(customer, bestIndividual, bestRoute, individualIdx, routeIdx, globalData);
//                                sucCustomers.put(routeIdx, dto);
////                                System.out.println("dto0： "+dto.toString());
//                            }
//                            globalData = canAdd.getSecond();
//                            // TODO  20241201 1025 正在弄若返程错误，则不add这个customer
//                        }
////                    else if (canAdd != null && !canAdd.getFirst()){ // unsuccessful
////                        throw new RuntimeException(""+canAdd.getFirst());
////                    }
//                    }
//                }
//
//                // TODO  20241130 1310尝试在循环外addACustomer
//                // 找到成本最低的分配路线
//                if (bestRoute != null) {
//                    long routeId = bestRoute.addACustomer(customer, data, globalData);
//                    if (routeId == -1) {
//                        unassignedCustomer.add(customer);
//                    }
//
//                    // 更新
////                    System.out.println("bestIndividual.getFitness(): "+bestIndividual.getFitness());
////                    System.out.println("bestRoute.getOverallDuration(): "+bestRoute.getOverallDuration());
////                    double newFitness = bestIndividual.getFitness() + bestRoute.getOverallDuration();
//                    double newFitness = bestRoute.getOverallDuration() / (1000 * 60 * 60.0);
//                    bestIndividual.setFitness(newFitness); // fitness
//                    List<Route> rawRoutes = bestIndividual.getRoutes();
//                    rawRoutes.set(routeIdx, bestRoute);
//                    bestIndividual.setRoutes(rawRoutes); // 更新route
//                    individuals.set(individualIdx, bestIndividual); // 更新individual
//                    // TODO  20241130 1310尝试在循环外addACustomer
//                } else {
//                    unassignedCustomer.add(customer);
//                }
//            }
//        }

        // TODO  处理unassignedCustomer
        System.out.println("unassigned: " + unassignedCustomer);

        // 执行每条路线的返程
        if (!sucCustomers.isEmpty()) {
            // 遍历每个路线,分别添加返程
            for (Entry<Integer, SucCustomerDto> entry : sucCustomers.entrySet()) {
//                int routeIdx=entry.getKey();
//                System.out.println("routeIdx: "+routeIdx);
                SucCustomerDto dto = entry.getValue();

                // 从dto获取数据
                Route route = dto.getRoute();
                Individual individual = dto.getIndividual();
                int routeIdx = dto.getRouteIdx();
                int individualIdx = dto.getIndividualIdx();
                Customer customer = dto.getCustomer();
                GlobalData globalData = dto.getGlobalData();
                long dailyDriveTime = globalData.getDailyDriveTime();
                System.out.println("请求是：" + customer);
                System.out.println("总驾驶：" + dailyDriveTime);

                List<Customer> customerList = route.getCustomers();
                if (!customerList.isEmpty()) { // 有订单,则尝试返程
                    boolean canReturn; // 是否能返程
                    try {
                        canReturn = route.returnToDepot(customer, data, dailyDriveTime); // input daily overall drive time
                    } catch (RuntimeException e) {
                        continue;
                    }
                    if (canReturn) {
                        // 更新
                        double newFitness = route.getOverallDuration() / (1000 * 60 * 60.0);
                        individual.setFitness(newFitness); // fitness
                        List<Route> rawRoutes = individual.getRoutes();
                        rawRoutes.set(routeIdx, route);
                        individual.setRoutes(rawRoutes); // 更新route
                        individuals.set(individualIdx, individual); // 更新individual
                    }
                }
            }
        }
        System.out.println("individuals: size: " + individuals.size() + "\n contents: " + individuals);

        // 5. 查看种群的总fitness
        double sum = 0.0;
        for (Individual individual : individuals) {
            sum += individual.getFitness();
        }
        population.setOverallFitness(sum);

        // 6. 给种群设置个体列表
        population.setIndividuals(individuals);
        return population;
    }

    protected static List<Individual> selection(PreProcessData data, Population population) {
        List<Individual> res = new ArrayList<>();

        if (population != null) {
            List<Individual> individuals = population.getIndividuals();
            if (individuals != null) {
                // 1. 计算指针的间距
                double F = population.getOverallFitness();
                int N = population.getIndividuals().size();
                double P = F / (N * 1.0);
//                System.out.println("F: " + F);
//                System.out.println("N: " + N);
//                System.out.println("P: " + P);

                // 2. 随机生成起始指针的位置
                Random random = data.getRandom();
                if (random != null) {
                    int start = random.nextInt((int) Math.floor(P) + 1); // [0, P)   Math.floor()向下取值
//                    System.out.println("start: " + start);

                    // 3. 计算各指针的位置
                    List<Double> pointers = new ArrayList<>();
                    for (int i = 0; i < N; i++) {
                        pointers.add(start + i * P);
                    }
                    population.setPointers(pointers);
//                    System.out.println("pointers: " + population.getPointers().toString());

                    // 4. 计算每个个体的fitness的与其前面所有个体fitness的累积和
                    List<Double> sumFitness = new ArrayList<>();
                    sumFitness.add(population.getIndividuals().get(0).getFitness()); // 第一个元素
                    // 每个元素的值=当前值+前一个位置的累积和
                    for (int i = 1; i < N; i++) {
                        double sum = population.getIndividuals().get(i).getFitness() + sumFitness.get(i - 1);
                        sumFitness.add(sum);
                    }
//                    System.out.println("sumFitness: " + sumFitness.toString());

                    // 5. 由指针位置找到个体
                    List<Integer> selectedIndividual = new ArrayList<>();
                    int i = 0, j = 0; // 遍历的指针
                    while (i < N && j < N) {
//                        System.out.println("j=" + j + ", i=" + i + ", pointers.get(j)=" + pointers.get(j) + ", sumFitness.get(i)=" + sumFitness.get(i) + ", " + (pointers.get(j) <= sumFitness.get(i)));
                        if (pointers.get(j) <= sumFitness.get(i)) {
                            selectedIndividual.add(i); // 符合条件的位置i加入列表
                            j++; // 遍历下一个pointer
                        } else {
                            i++; // 遍历下一个真实fitness
                        }
                    }
//                    System.out.println("selectedIndividual: " + selectedIndividual.toString());

                    // 6. 取出fitness最大的两个个体
                    if (N < 2) { // 只有一个个体，则该个体同时是两个parent
                        Individual individual = population.getIndividuals().get(N - 1);
                        res.add(individual);
                        res.add(individual);
                    } else { // 选择fitness最高的两个
                        // 根据fitness对所有个体降序排序
                        population.getIndividuals().sort((i1, i2) -> Double.compare(i2.getFitness(), i1.getFitness()));
                        Individual first = population.getIndividuals().get(0);
                        Individual second = population.getIndividuals().get(1);
                        res.add(first);
                        res.add(second);
                    }
                }
            } else {
                System.out.println("There are not individuals in the population.");
            }
        } else {
            System.out.println("Population is empty.");
        }
        return res;
    }

    protected static List<Individual> crossover(PreProcessData data, Population population, Double pCross, List<Individual> parents) {
        List<Individual> res = new ArrayList<>();

//        for (Individual individual:population.getIndividuals()){
//            System.out.println("11: "+individual.getRoutes().size());
//        }
        if (population != null) {
            int N = parents.get(1).getRoutes().size(); // 第一个parent的路线数
            System.out.println("N: " + N);
            if (parents != null && !parents.isEmpty()) {
                Random random = data.getRandom();
                if (random != null) {
//                    // 1. 随机生成两个交叉点
//                    int point1=0,point2=0;
//                    while(point1==point2){
//                        point1=random.nextInt(N);
//                         point2=random.nextInt(N);
//                    }
//                    System.out.println("point1: "+point1);
//                    System.out.println("point2: "+point2);

                    // 1. 均匀交叉
                }

            } else {
                System.out.println("Parents are empty.");
            }
        } else {
            System.out.println("Population is empty.");
        }
        return res;
    }

    /**
     * the implementation of the GGA algorithm
     */
    protected static Individual GGA(PreProcessData data) throws IllegalArgumentException {
        /**
         * 个体：每辆车的工作安排
         * 种群：所有车组成的一个调度规划
         * 适应度值：交付所有订单所需的总工作时间
         */
        // 0. parameters
        // TODO  20241129 1650暂时修改为1
        int nPop = 1; // size of population(number of individuals in the population)
        double pCross = 1.0; // 交叉概率
        double pMut = 0.5; // 变异概率
        int nMax = 15000; // maximum number of individual
        int nMaxWithoutImprovement = 3000; // unimproved maximum number of individual
        Individual bestIndividual = new Individual(); // best individual

        // 1. 初始化种群P
        Population population = initializePopulation(data, nPop);

        // 2. 设置迭代终止条件
        int termination = 0; // 终止条件

        // 3. 只要未满足终止条件，就循环
//        while (termination <= nMaxWithoutImprovement) {
//            // 1)选择: 根据适应度值，从P选择一对个体x、y作为parents
//            List<Individual> parents = selection(data, population);
//            Individual x = new Individual(), y = new Individual();
//            if (!parents.isEmpty()) {
//                x = parents.get(0);
//                y = parents.get(1);
//            }
//            System.out.println("first: "+x);
//            System.out.println("second: "+y);
//
//            // 2)交叉: 以pCross的概率应用交叉算子到x和y生成两个子代x',y'
//            List<Individual> children = crossover(data, population, pCross, parents);
//
//            // 3)变异: 以pMut的概率应用变异算子到x'和y'，分别生成两个修改后的子代x'',y''
//            mutation();
//
//            // 4)更新种群P: 将x''和y''插入P，并相应地从P中移除两个最差的个体
////            updatePopulation();
//
//            // 5)更新终止条件的值
//            termination++;
//        }

        // TODO  直接让Individual返回第一个
        bestIndividual = population.getIndividuals().get(0);

        // 4. 从种群P中返回最佳个体作为解
        return bestIndividual;
    }

    protected static String formatDate(double time, String type) throws ParseException {
        String res = "";
        Date date = new Date((long) time);

        if (type.equals("H'h'm'm'")) {
            int hours = (int) (time / (60 * 60 * 1000));
            int minutes = (int) ((time % (60 * 60 * 1000)) / (60 * 1000)); // remaining minutes

            String h = hours < 10 ? "" + hours : String.valueOf(hours);
            String m = minutes < 10 ? "" + minutes : String.valueOf(minutes);
            res += h + "h" + m + "m";
        } else if (type.equals("HH:mm")) {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm");
            res = sdf.format(date);
        } else if (type.equals("extract")) { // extract hours and minutes
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(date);
            res += calendar.get(Calendar.HOUR_OF_DAY) + "h" + calendar.get(Calendar.MINUTE) + "m";
        }
        return res;
    }

    protected static void getOutput(Individual bestIndividual) throws ParseException {
        if (bestIndividual != null) {
            System.out.println("Best Individual: " + bestIndividual + ";\n route size: "
                    + bestIndividual.getRoutes().size());
            // 1. output the header of the table
            System.out.println("VehicleName,JobId,JourneyTime,ArrivalTime,WaitTime," +
                    "DelayTime,ServiceTime,DepartureTime,Break1Time,Break1Duration," +
                    "Break2Time,Break2Duration,Distance,SequenceNo");

            // 2. get each route and process
            for (Route route : bestIndividual.getRoutes()) {
                StringBuilder output = new StringBuilder(); // final output

                // 1) get three time list respectively
                List<Time> driveTimeList = route.getVehicle().getDriveTimeList();
                List<Time> otherTimeList = route.getVehicle().getOtherTimeList();
                List<Time> breakTimeList = route.getVehicle().getBreakTimeList();

                // 2)integrate three lists into one
                List<Time> timeList = new ArrayList<>();
                timeList.addAll(driveTimeList);
                timeList.addAll(otherTimeList);
                timeList.addAll(breakTimeList);

                // 3)sort the integrated list
                timeList.sort((o1, o2) -> Long.compare(o1.getId(), o2.getId()));
//                System.out.println("after sorting: "+timeList.toString());

                // 4)get the id of vehicle
                long vehicleId = route.getVehicle().getId();

                // 5)add the first line
                Time firstTime = timeList.get(0); // first time entity
                output.append(vehicleId).append(","); // VehicleName
                output.append("Vehicle ").append(vehicleId).append(" start").append(","); // JobId
                output.append(formatDate(0, "H'h'm'm'")).append(","); // JourneyTime
                output.append(formatDate(firstTime.getStart().getTime(), "HH:mm")).append(","); // ArrivalTime(First job's start time)
                output.append(formatDate(0, "H'h'm'm'")).append(","); // WaitTime equals zero
                output.append(formatDate(0, "H'h'm'm'")).append(","); // DelayTime equals zero
                output.append(formatDate(0, "H'h'm'm'")).append(","); // ServiceTime equals zero
                output.append(formatDate(firstTime.getStart().getTime(), "extract")).append(","); // DepartureTime equals to ArrivalTime
                output.append(",").append(",").append(",").append(","); // related BreakTime are zero
                output.append(firstTime.getDistance()).append(","); // Distance
                output.append(1).append("\n"); // SequenceNo with \n

                // 4)output each item in the integrated list
                int seqR = 0; // control the sequenceNo of the tail
                int seqCD = 1; // control the sequenceNo of collect and deliver jobs
                for (int i = 0; i < timeList.size(); i++) {
                    Time curTime = timeList.get(i);
                    System.out.println("time: " + curTime);

                    // typesetting by job type
                    if (curTime.getJobId().equals("return")) {
                        // return to the depot
                        output.append(vehicleId).append(","); // VehicleName
                        output.append("Vehicle ").append(vehicleId).append(" end").append(","); // JobId
                        output.append(formatDate(curTime.getDuration(), "H'h'm'm'")).append(","); // JourneyTime
                        output.append(formatDate(curTime.getEnd().getTime(), "HH:mm")).append(","); // ArrivalTime(the end time of 'return' entity)
                        output.append(formatDate(0, "H'h'm'm'")).append(","); // WaitTime equals zero
                        output.append(formatDate(0, "H'h'm'm'")).append(","); // DelayTime equals zero
                        output.append(formatDate(0, "H'h'm'm'")).append(","); // ServiceTime equals zero
                        output.append(formatDate(curTime.getEnd().getTime(), "extract")).append(","); // DepartureTime equals to ArrivalTime
                        output.append(",").append(",").append(",").append(","); // related BreakTime are zero
                        output.append(curTime.getDistance()).append(","); // Distance
                        output.append(seqR + 2).append("\n"); // SequenceNo with \n, j+2 means existing the head and tail
                    } else if (curTime.getJobId().equals("break")) {
                        // break        // TODO
                    } else if (!curTime.getJobId().equals("drive")) {
                        // collect/deliver
                        seqCD++; // update the pointer
                        output.append(vehicleId).append(","); // VehicleName
                        output.append(curTime.getJobId()).append(","); // JobId
                        // the first item does not need to drive
                        if (i != 0) {
                            Time lastTime = timeList.get(i - 1);
                            if (lastTime != null && lastTime.getJobId().equals("drive")) { // obtain the drive duration as current job's JourneyTime
                                output.append(formatDate(lastTime.getDuration(), "H'h'm'm'")).append(","); // JourneyTime equals last drive duration
                            } else if (lastTime != null) {
                                output.append(formatDate(0, "H'h'm'm'")).append(","); // JourneyTime equals zero
                            }
                        } else {
                            output.append(formatDate(0, "H'h'm'm'")).append(","); // JourneyTime equals zero
                        }
                        output.append(formatDate(curTime.getStart().getTime(), "HH:mm")).append(","); // ArrivalTime
                        // the first item matched in this branch must not be 'wait' job
                        if (i != 0) {
                            Time lastTime = timeList.get(i - 1);
                            if (lastTime != null && lastTime.getJobId().equals("wait")) { // obtain the wait duration as current job's WaitTime
                                output.append(formatDate(lastTime.getDuration(), "H'h'm'm'")).append(","); // WaitTime
                            } else {
                                output.append(formatDate(0, "H'h'm'm'")).append(","); // WaitTime equals to zero
                            }
                        } else {
                            output.append(formatDate(0, "H'h'm'm'")).append(","); // WaitTime equals to zero
                        }
                        output.append(formatDate(0, "H'h'm'm'")).append(","); // DelayTime equals zero
                        output.append(formatDate(curTime.getDuration(), "H'h'm'm'")).append(","); // ServiceTime
                        output.append(formatDate(curTime.getEnd().getTime(), "extract")).append(","); // DepartureTime
                        // breakTime
                        if (i > 1) {
                            Time firstBreak = timeList.get(i - 2);
                            Time secondBreak = timeList.get(i - 1);
                            if (firstBreak.getJobId().equals("break") && secondBreak.getJobId().equals("break")) { // two breaks
                                output.append(formatDate(firstBreak.getStart().getTime(), "HH:mm")).append(",")
                                        .append(formatDate(firstBreak.getDuration(), "H'h'm'm'")).append(",")
                                        .append(formatDate(secondBreak.getEnd().getTime(), "HH:mm")).append(",")
                                        .append(formatDate(secondBreak.getDuration(), "H'h'm'm'")).append(",");
                            } else { // only one break
                                if (secondBreak.getJobId().equals("break")) {
                                    output.append(formatDate(secondBreak.getStart().getTime(), "HH:mm")).append(",")
                                            .append(formatDate(secondBreak.getDuration(), "H'h'm'm'")).append(",");
                                } else {
                                    output.append(",").append(",").append(",").append(","); // related BreakTime are zero
                                }
                            }
                        } else if (i == 1) {
                            Time lastBreak = timeList.get(i - 1);
                            if (lastBreak.getJobId().equals("break")) {
                                output.append(formatDate(lastBreak.getStart().getTime(), "HH:mm")).append(",")
                                        .append(formatDate(lastBreak.getDuration(), "H'h'm'm'")).append(",");
                            } else {
                                output.append(",").append(",").append(",").append(","); // related BreakTime are zero
                            }
                        } else {
                            output.append(",").append(",").append(",").append(","); // related BreakTime are zero
                        }
                        // obtain distance
                        if (i != 0) {
                            Time lastTime = timeList.get(i - 1);
                            if (lastTime != null && lastTime.getJobId().equals("drive")) { // obtain the drive distance as current job's Distance
                                output.append(lastTime.getDistance()).append(","); // Distance
                            } else {
                                output.append(0).append(","); // Distance equals to zero
                            }
                        } else {
                            output.append(0).append(","); // Distance equals to zero
                        }
                        output.append(seqCD).append("\n"); // SequenceNo with \n
                        seqR++; // update the pointer
                    }
                }

                // 6. output the answer
                System.out.println(output);
            }
        } else {
            System.out.println("There is no best individual.");
        }
    }

    public static void main(String[] args) throws IllegalAccessException, ParseException {
        long start = System.currentTimeMillis();

        // 1. read input from the input.json
        JSONObject rawData = getInput();

        // 2. preprocess the data
        PreProcessData data = preProcessData(rawData);

        // 3. GGA algorithm
        Individual bestIndividual = GGA(data);

        // 4. output the best individual
        getOutput(bestIndividual);

        // Output the time used
        long end = System.currentTimeMillis();
        System.out.println("overall time consuming: " + (end - start) * 1.0 / 1000 + "s");
    }
}