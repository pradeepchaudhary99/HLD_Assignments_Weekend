import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GIVEN — a tiny Redis client (about 150 lines) so you need nothing but the JDK.
 *
 * You send the SAME words you would type in redis-cli:
 *
 *     Redis.call("SET", "menu:r1", json, "EX", 60);          // -> "OK"
 *     String v  = Redis.str("GET", "menu:r1");               // -> the value, or null
 *     long n    = Redis.num("INCR", "rl:user42");            // -> the number
 *     List<Object> l = Redis.list("ZREVRANGE", "lb", 0, 9, "WITHSCORES");
 *
 * Replies come back as:  String (simple/bulk string), Long (integer), List<Object> (array), or null.
 * An error reply (e.g. "WRONGTYPE ...") is thrown as Redis.RedisError.
 *
 * In your own projects you'd use a library (Jedis, Lettuce, redis-py, ioredis...). The commands are identical:
 *     Redis.call("SET", k, v, "NX", "PX", 5000)   ==   jedis.set(k, v, SetParams.setParams().nx().px(5000))
 *
 * Connection settings come from environment variables: REDIS_HOST (localhost), REDIS_PORT (6379), REDIS_PASSWORD.
 */
public final class Redis implements AutoCloseable {

    public static final class RedisError extends RuntimeException {
        public RedisError(String msg) { super(msg); }
    }

    // =====================================================================
    // Shared connection pool — what you'll use 99% of the time
    // =====================================================================
    private static final int MAX_CONNECTIONS = 64;
    private static final LinkedBlockingQueue<Redis> POOL = new LinkedBlockingQueue<>();
    private static final AtomicInteger CREATED = new AtomicInteger();

    /** Runs one command on a pooled connection. Arguments can be strings, numbers, anything (toString is used). */
    public static Object call(Object... args) {
        Redis conn = borrow();
        boolean healthy = true;
        try {
            return conn.send(args);
        } catch (IOException e) {
            healthy = false;
            throw new UncheckedIOException("Redis connection failed (is Redis running on "
                    + host() + ":" + port() + "?)", e);
        } finally {
            if (healthy) POOL.offer(conn);
            else { conn.close(); CREATED.decrementAndGet(); }
        }
    }

    public static String str(Object... args) {
        Object r = call(args);
        return r == null ? null : r.toString();
    }

    /** For commands that return an integer. A null reply becomes 0. */
    public static long num(Object... args) {
        Object r = call(args);
        if (r == null) return 0;
        return r instanceof Long l ? l : Long.parseLong(r.toString());
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object... args) {
        Object r = call(args);
        return r == null ? null : (List<Object>) r;
    }

    /** EVAL helper: Redis.eval(script, List.of(key1, key2), arg1, arg2...) */
    public static Object eval(String luaScript, List<String> keys, Object... args) {
        Object[] all = new Object[3 + keys.size() + args.length];
        all[0] = "EVAL";
        all[1] = luaScript;
        all[2] = keys.size();
        for (int i = 0; i < keys.size(); i++) all[3 + i] = keys.get(i);
        System.arraycopy(args, 0, all, 3 + keys.size(), args.length);
        return call(all);
    }

    private static Redis borrow() {
        Redis conn = POOL.poll();
        if (conn != null) return conn;
        if (CREATED.incrementAndGet() <= MAX_CONNECTIONS) {
            try {
                return open();
            } catch (IOException e) {
                CREATED.decrementAndGet();
                throw new UncheckedIOException("Cannot connect to Redis at " + host() + ":" + port()
                        + " -- start it with:  docker compose up -d", e);
            }
        }
        CREATED.decrementAndGet();
        try {
            return POOL.take();   // all connections busy: wait for one
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // =====================================================================
    // Pub/Sub — a subscribed connection can't run other commands, so it gets its own
    // =====================================================================

    /** Opens a dedicated connection and SUBSCRIBEs to the channels. Close it when you're done. */
    public static Subscription subscribe(String... channels) {
        try {
            Redis conn = open();
            Object[] cmd = new Object[channels.length + 1];
            cmd[0] = "SUBSCRIBE";
            System.arraycopy(channels, 0, cmd, 1, channels.length);
            conn.write(cmd);
            for (int i = 0; i < channels.length; i++) conn.read();   // one "subscribe" confirmation per channel
            return new Subscription(conn);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static final class Subscription implements AutoCloseable {
        private final Redis conn;
        Subscription(Redis conn) { this.conn = conn; }

        /**
         * Waits up to timeoutMs for the next message on any subscribed channel.
         * Returns the message text, or null if nothing arrived in time.
         */
        @SuppressWarnings("unchecked")
        public String next(long timeoutMs) {
            try {
                Object first = conn.readWithTimeout(timeoutMs);
                if (first == null) return null;
                List<Object> msg = (List<Object>) first;            // ["message", channel, payload]
                return "message".equals(msg.get(0)) ? (String) msg.get(2) : next(timeoutMs);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override public void close() { conn.close(); }
    }

    // =====================================================================
    // One connection + the RESP protocol. Read it once — it's the whole wire format.
    //   request:  *3\r\n $3\r\nSET\r\n $1\r\nk\r\n $1\r\nv\r\n
    //   replies:  +OK   -ERR msg   :42   $5\r\nhello   *2 (array)   $-1 (null)
    // =====================================================================
    private final Socket socket;
    private final BufferedInputStream in;
    private final BufferedOutputStream out;

    private Redis(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = new BufferedOutputStream(socket.getOutputStream());
    }

    static Redis open() throws IOException {
        Socket s = new Socket(host(), port());
        s.setTcpNoDelay(true);
        Redis r = new Redis(s);
        String password = System.getenv("REDIS_PASSWORD");
        if (password != null && !password.isBlank()) r.send("AUTH", password);
        return r;
    }

    static String host() { return Optional.ofNullable(System.getenv("REDIS_HOST")).orElse("localhost"); }
    static int port() { return Integer.parseInt(Optional.ofNullable(System.getenv("REDIS_PORT")).orElse("6379")); }

    synchronized Object send(Object... args) throws IOException {
        write(args);
        Object reply = read();
        if (reply instanceof RedisError e) throw new RedisError(e.getMessage());
        return reply;
    }

    void write(Object... args) throws IOException {
        out.write(('*' + String.valueOf(args.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (Object a : args) {
            byte[] b = String.valueOf(a).getBytes(StandardCharsets.UTF_8);
            out.write(('$' + String.valueOf(b.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(b);
            out.write('\r');
            out.write('\n');
        }
        out.flush();
    }

    Object read() throws IOException {
        int type = in.read();
        if (type == -1) throw new EOFException("Redis closed the connection");
        return read(type);
    }

    /** Waits for the first byte with a timeout (null = nothing arrived), then reads the rest normally. */
    Object readWithTimeout(long timeoutMs) throws IOException {
        socket.setSoTimeout((int) Math.max(1, timeoutMs));
        int type;
        try {
            type = in.read();
        } catch (SocketTimeoutException e) {
            return null;
        } finally {
            socket.setSoTimeout(0);
        }
        if (type == -1) throw new EOFException("Redis closed the connection");
        return read(type);
    }

    private Object read(int type) throws IOException {
        String line = line();
        switch (type) {
            case '+': return line;
            case '-': return new RedisError(line);           // thrown by send(); kept as a value inside arrays
            case ':': return Long.parseLong(line);
            case '$': {
                int len = Integer.parseInt(line);
                if (len < 0) return null;
                byte[] data = in.readNBytes(len);
                in.readNBytes(2);                              // \r\n
                return new String(data, StandardCharsets.UTF_8);
            }
            case '*': {
                int n = Integer.parseInt(line);
                if (n < 0) return null;
                List<Object> items = new ArrayList<>(n);
                for (int i = 0; i < n; i++) items.add(read());
                return items;
            }
            default: throw new IOException("Unexpected reply type '" + (char) type + "'");
        }
    }

    private String line() throws IOException {
        StringBuilder b = new StringBuilder();
        int c;
        while ((c = in.read()) != '\r') {
            if (c == -1) throw new EOFException();
            b.append((char) c);
        }
        in.read(); // \n
        return b.toString();
    }

    @Override public void close() {
        try { socket.close(); } catch (IOException ignored) { }
    }
}
