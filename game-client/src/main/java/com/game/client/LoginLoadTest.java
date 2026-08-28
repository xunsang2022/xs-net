package com.game.client;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.game.message.c2s.LoginRequest;
import com.game.message.rpc.GameRpcMessages;
import com.google.protobuf.ByteString;
import com.google.protobuf.MessageLite;
import com.xs.net.message.RpcMessageSpec;
import com.xs.net.message.pb.NetMessage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 网关登录压测脚本：模拟 {@code count} 个玩家并发建立连接、完成 JWT 认证，
 * 并发送 {@code CS_Login} 等待 {@code SC_Login}（rpc 1002）回执，覆盖完整登录链路。
 *
 * <p>压测覆盖登录的两段闭环：</p>
 * <ul>
 *   <li><b>连接 + 认证</b>：TCP 首帧发 JWT(UTF-8)；UDP 发 {@code HELLO(JWT)} 等 {@code ACK}。</li>
 *   <li><b>进服登录</b>：认证通过后发送 {@code CS_Login}（rpc 1000），网关将其转换为内部
 *       {@code PlayerEnterCommand} 路由到 PlayerActor，进服完成后由网关回推 {@code SC_Login}（rpc 1002）。
 *       收到 {@code SC_Login} 即判定登录成功。</li>
 * </ul>
 *
 * <p>JWT 在本进程内按网关相同的密钥/声明（sub=playerId, cid=channelId）本地签发，
 * 避免压测时再依赖 Login HTTP 服务，专注压测网关登录链路。</p>
 *
 * <p>用法：{@code java LoginLoadTest --host=127.0.0.1 --protocol=tcp --port=8082 --count=1000 --concurrency=200}</p>
 */
public final class LoginLoadTest {

    // UDP 帧常量
    private static final byte TYPE_DATA = 1;
    private static final byte TYPE_ACK = 2;
    private static final byte TYPE_HELLO = 3;
    private static final int UDP_HEADER = 15;
    private static final byte MAGIC_0 = 0x58;
    private static final byte MAGIC_1 = 0x53;

    private final Config cfg;

    private LoginLoadTest(Config cfg) {
        this.cfg = cfg;
    }

    public static void main(String[] args) throws Exception {
        Config cfg = Config.parse(args);
        new LoginLoadTest(cfg).run();
    }

    private void run() throws Exception {
        System.out.printf("== 登录(认证)压测 == host=%s protocol=%s port=%d players=%d concurrency=%d timeout=%dms verify=%dms%n",
                cfg.host, cfg.protocol, cfg.port, cfg.count, cfg.concurrency, cfg.timeoutMs, cfg.verifyMs);

        // 预先生成所有玩家的凭据（playerId + channelId + token，token 本地签发不占网关资源）
        final List<PlayerCred> creds = new ArrayList<>(cfg.count);
        for (int i = 0; i < cfg.count; i++) {
            String playerId = String.valueOf(cfg.playerStart + i);
            String channelId = "load-" + playerId + "-" + UUID.randomUUID().toString().substring(0, 8);
            creds.add(new PlayerCred(playerId, channelId, mintJwt(cfg.secret, playerId, channelId, cfg.jwtExpMs)));
        }

        final int n = cfg.count;
        final AtomicInteger authed = new AtomicInteger(0);
        final AtomicInteger failed = new AtomicInteger(0);
        final AtomicLong latencySumNanos = new AtomicLong(0);
        final List<Long> latencies = java.util.Collections.synchronizedList(new ArrayList<>(n));
        final Map<String, Integer> failureReasons = new java.util.concurrent.ConcurrentHashMap<>();
        final CountDownLatch done = new CountDownLatch(n);

        // 用固定线程池限制并发，同一时刻最多 concurrency 个玩家在途，模拟持续并发登录
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(cfg.concurrency, n));
        long startNanos = System.nanoTime();

        for (int i = 0; i < n; i++) {
            final PlayerCred cred = creds.get(i);
            pool.submit(() -> {
                long t0 = System.nanoTime();
                try {
                    runOne(cred);
                    long latencyUs = (System.nanoTime() - t0) / 1000;
                    authed.incrementAndGet();
                    latencies.add(latencyUs);
                    latencySumNanos.addAndGet(System.nanoTime() - t0);
                } catch (Throwable e) {
                    failed.incrementAndGet();
                    String reason = e.getClass().getSimpleName() + ": " + firstLine(e.getMessage());
                    failureReasons.merge(reason, 1, Integer::sum);
                } finally {
                    done.countDown();
                }
            });
        }

        if (!done.await(cfg.timeoutMs + 60_000, TimeUnit.MILLISECONDS)) {
            System.err.println("WARN: 压测超时，未在预期时间内完成，强制结束统计。");
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        pool.shutdownNow();

        // ---- 汇总 ----
        System.out.printf("%n==== 结果汇总 ====%n");
        System.out.printf("总玩家:        %d%n", n);
        System.out.printf("认证成功:      %d%n", authed.get());
        System.out.printf("失败:          %d%n", failed.get());
        System.out.printf("总耗时:        %d ms%n", elapsedMs);
        double throughput = elapsedMs <= 0 ? 0 : authed.get() * 1000.0 / elapsedMs;
        System.out.printf("吞吐(登录/s):  %.1f%n", throughput);

        if (!latencies.isEmpty()) {
            long[] arr = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
            long avg = latencySumNanos.get() / 1000 / arr.length;
            System.out.printf("登录时延(us):  avg=%d p50=%d p95=%d p99=%d max=%d%n",
                    avg, percentile(arr, 50), percentile(arr, 95), percentile(arr, 99), arr[arr.length - 1]);
        }
        if (!failureReasons.isEmpty()) {
            System.out.println("失败原因分布:");
            failureReasons.forEach((k, v) -> System.out.printf("  %-40s %d%n", k, v));
        }
    }

    private void runOne(PlayerCred cred) throws Exception {
        if (cfg.protocol == Protocol.TCP) {
            runTcp(cred);
        } else {
            runUdp(cred);
        }
    }

    // ---------------- TCP ----------------
    private void runTcp(PlayerCred cred) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(cfg.host, cfg.port), cfg.timeoutMs);
            socket.setSoTimeout(cfg.timeoutMs);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

            NettyFrameCodec.write(out, cred.token.getBytes(StandardCharsets.UTF_8)); // 首帧 JWT 认证
            out.flush();

            // 认证通过后发送 CS_Login，等待 SC_Login（rpc 1002）回执
            NettyFrameCodec.write(out, encodeLoginRequest(cred));
            out.flush();
            waitLoginFrame(in);
        }
    }

    /** 循环读取 TCP 帧，直到收到 SC_Login（rpc 1002）即登录成功。 */
    private void waitLoginFrame(DataInputStream in) throws IOException {
        long deadline = System.currentTimeMillis() + cfg.timeoutMs;
        try {
            while (System.currentTimeMillis() < deadline) {
                byte[] body = NettyFrameCodec.read(in);
                NetMessage msg = NetMessage.parseFrom(body);
                if (msg.getRpcNum() == 1002) {
                    return;
                }
            }
            throw new IOException("TCP 登录响应超时（未收到 SC_Login）");
        } catch (SocketTimeoutException e) {
            throw new IOException("TCP 登录响应超时（未收到 SC_Login）", e);
        }
    }

    // ---------------- UDP（轻量可靠 UDP：HELLO 握手 + DATA 上行） ----------------
    private void runUdp(PlayerCred cred) throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(new InetSocketAddress(cfg.host, cfg.port));
            socket.setSoTimeout(cfg.timeoutMs);
            // 握手：HELLO(JWT)，网关校验通过回 ACK => 认证成功
            sendPacket(socket, TYPE_HELLO, 0, 0, cred.token.getBytes(StandardCharsets.UTF_8));
            waitAck(socket);
            // 认证通过后以可靠 DATA 上行 CS_Login，等待 SC_Login（rpc 1002）回执
            sendPacket(socket, TYPE_DATA, 1, 0, encodeLoginRequest(cred));
            waitLoginResponse(socket);
        }
    }

    /** 循环读取 UDP 包，直到收到携带 SC_Login（rpc 1002）的 DATA 帧即登录成功。 */
    private void waitLoginResponse(DatagramSocket socket) throws IOException {
        long deadline = System.currentTimeMillis() + cfg.timeoutMs;
        byte[] buffer = new byte[UDP_HEADER + 65536];
        while (System.currentTimeMillis() < deadline) {
            DatagramPacket p = new DatagramPacket(buffer, buffer.length);
            socket.receive(p);
            byte[] data = p.getData();
            int length = p.getLength();
            if (length < UDP_HEADER || data[0] != MAGIC_0 || data[1] != MAGIC_1) {
                continue;
            }
            byte type = data[2];
            int len = (int) readIntBE(data, 11);
            if (type == TYPE_DATA && length >= UDP_HEADER + len && len > 0) {
                byte[] payload = new byte[len];
                System.arraycopy(data, UDP_HEADER, payload, 0, len);
                NetMessage msg = NetMessage.parseFrom(payload);
                if (msg.getRpcNum() == 1002) {
                    return;
                }
            }
        }
        throw new IOException("UDP 登录响应超时（未收到 SC_Login）");
    }

    /** 编码 CS_Login（rpc 1000）为 NetMessage 字节。 */
    private byte[] encodeLoginRequest(PlayerCred cred) throws IOException {
        RpcMessageSpec<? extends MessageLite> spec = GameRpcMessages.INSTANCE.find(1000);
        if (spec == null) {
            throw new IOException("未注册 rpc 1000 (LoginRequest)");
        }
        LoginRequest req = new LoginRequest(cred.playerId, cfg.playerNamePrefix + cred.playerId, cred.channelId);
        MessageLite proto = spec.encode(req);
        return NetMessage.newBuilder()
                .setRpcNum(1000)
                .setData(ByteString.copyFrom(proto.toByteArray()))
                .build()
                .toByteArray();
    }

    private void waitAck(DatagramSocket socket) throws IOException {
        long deadline = System.currentTimeMillis() + cfg.timeoutMs;
        byte[] buffer = new byte[UDP_HEADER + 65536];
        while (System.currentTimeMillis() < deadline) {
            DatagramPacket p = new DatagramPacket(buffer, buffer.length);
            socket.receive(p);
            byte[] data = p.getData();
            if (p.getLength() < UDP_HEADER || data[0] != MAGIC_0 || data[1] != MAGIC_1) {
                continue;
            }
            if (data[2] == TYPE_ACK) {
                return;
            }
        }
        throw new IOException("UDP 握手超时（未收到 ACK）");
    }

    private void sendPacket(DatagramSocket socket, byte type, long seq, long ack, byte[] payload) throws IOException {
        int len = payload == null ? 0 : payload.length;
        byte[] bytes = new byte[UDP_HEADER + len];
        bytes[0] = MAGIC_0;
        bytes[1] = MAGIC_1;
        bytes[2] = type;
        writeIntBE(bytes, 3, seq);
        writeIntBE(bytes, 7, ack);
        writeIntBE(bytes, 11, len);
        if (len > 0) {
            System.arraycopy(payload, 0, bytes, UDP_HEADER, len);
        }
        socket.send(new DatagramPacket(bytes, bytes.length));
    }

    // ---------------- JWT（HS256，本地签发，与网关 JwtUtil 声明一致） ----------------
    static String mintJwt(String secret, String playerId, String channelId, long expMs) {
        String header = b64url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        long now = System.currentTimeMillis();
        String payload = "{\"sub\":\"" + playerId + "\",\"cid\":\"" + channelId
                + "\",\"iat\":" + (now / 1000) + ",\"exp\":" + ((now + expMs) / 1000) + "}";
        String body = b64url(payload.getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + body;
        byte[] sig = hmacSha256(secret.getBytes(StandardCharsets.UTF_8), signingInput.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + b64url(sig);
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }

    private static String b64url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static long percentile(long[] sorted, int p) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = Math.max(0, (int) Math.ceil(sorted.length * p / 100.0) - 1);
        return sorted[idx];
    }

    private static String firstLine(String msg) {
        if (msg == null) {
            return "";
        }
        int nl = msg.indexOf('\n');
        return nl < 0 ? msg : msg.substring(0, nl);
    }

    private static void writeIntBE(byte[] buf, int offset, long value) {
        buf[offset] = (byte) (value >> 24);
        buf[offset + 1] = (byte) (value >> 16);
        buf[offset + 2] = (byte) (value >> 8);
        buf[offset + 3] = (byte) value;
    }

    private static long readIntBE(byte[] buf, int offset) {
        return ((buf[offset] & 0xFFL) << 24)
                | ((buf[offset + 1] & 0xFFL) << 16)
                | ((buf[offset + 2] & 0xFFL) << 8)
                | (buf[offset + 3] & 0xFFL);
    }

    // ---------------- 配置 ----------------
    /** 单个玩家的凭据：playerId / channelId（兼作登录会话 ID）/ JWT token。 */
    private record PlayerCred(String playerId, String channelId, String token) {
    }

    enum Protocol {
        TCP, UDP;

        static Protocol parse(String s) {
            return "udp".equalsIgnoreCase(s) ? UDP : TCP;
        }
    }

    static final class Config {
        String host = "127.0.0.1";
        Protocol protocol = Protocol.TCP;
        int port = 8082;
        int count = 1000;
        int concurrency = 200;
        int playerStart = 10001;
        String playerNamePrefix = "load-";
        String secret = "xs-net-dev-jwt-secret-key-32bytes-minimum!!";
        long jwtExpMs = 86_400_000;
        int timeoutMs = 10_000;
        int verifyMs = 500;

        static Config parse(String[] args) {
            Config c = new Config();
            for (String arg : args) {
                String[] pair = arg.split("=", 2);
                if (pair.length != 2) {
                    throw new IllegalArgumentException("参数格式错误: " + arg);
                }
                switch (pair[0]) {
                    case "--host" -> c.host = pair[1];
                    case "--protocol" -> c.protocol = Protocol.parse(pair[1]);
                    case "--port" -> c.port = Integer.parseInt(pair[1]);
                    case "--count" -> c.count = Integer.parseInt(pair[1]);
                    case "--concurrency" -> c.concurrency = Integer.parseInt(pair[1]);
                    case "--player-start" -> c.playerStart = Integer.parseInt(pair[1]);
                    case "--player-name-prefix" -> c.playerNamePrefix = pair[1];
                    case "--secret" -> c.secret = pair[1];
                    case "--jwt-exp-ms" -> c.jwtExpMs = Long.parseLong(pair[1]);
                    case "--timeout-ms" -> c.timeoutMs = Integer.parseInt(pair[1]);
                    case "--verify-ms" -> c.verifyMs = Integer.parseInt(pair[1]);
                    default -> throw new IllegalArgumentException("未知参数: " + pair[0]);
                }
            }
            if (c.count < 1) {
                throw new IllegalArgumentException("--count 必须 >= 1");
            }
            if (c.concurrency < 1) {
                throw new IllegalArgumentException("--concurrency 必须 >= 1");
            }
            return c;
        }
    }
}
