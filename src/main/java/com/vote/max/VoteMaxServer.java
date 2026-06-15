package com.vote.max;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class VoteMaxServer {
    // ---------- 数据结构 ----------
    private static final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    // 客户只有一个有效会话(客户ID - sessionKey)
    private static final ConcurrentHashMap<Integer, String> customerSession = new ConcurrentHashMap<>();
    // 投注额存储: VoteProcess - (customerId - maxVote)
    private static final ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, Integer>> maxVote = new ConcurrentHashMap<>();

    // 变量配置
    private static final int SESSION_TTL_MS = 10 * 60 * 1000; // 10分钟
    private static final int CLEANUP_INTERVAL_SEC = 60;

    public static void main(String[] args) throws IOException {
        int port = 8001;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        // 设置线程池，提高并发能力
        server.setExecutor(Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2));
        // 注册处理器
        server.createContext("/", new RootHandler());
        server.start();
        System.out.println("VoteMaxServer started on port " + port);

        // 启动会话清理定时器
        ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor();
        cleaner.scheduleAtFixedRate(VoteMaxServer::cleanExpiredSessions, CLEANUP_INTERVAL_SEC, CLEANUP_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    // 会话对象
    static class Session {
        final int customerId;
        long expireTime; // 毫秒时间戳

        Session(int customerId, long expireTime) {
            this.customerId = customerId;
            this.expireTime = expireTime;
        }
    }

    // 清理过期会话
    private static void cleanExpiredSessions() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> {
            if (entry.getValue().expireTime < now) {
                // 同时移除 customerSession 中的映射
                customerSession.remove(entry.getValue().customerId, entry.getKey());
                return true;
            }
            return false;
        });
    }

    // 生成会话唯一Key
    private static String genSessionKey() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    // 验证会话Key并返回客户ID，如果有效则刷新过期时间，否则返回-1
    private static int validRefreshSession(String sessionKey) {
        Session sess = sessions.get(sessionKey);
        if (sess == null) return -1;
        long now = System.currentTimeMillis();
        if (sess.expireTime < now) {
            // 过期就清理并返回无效
            sessions.remove(sessionKey, sess);
            customerSession.remove(sess.customerId, sessionKey);
            return -1;
        }
        // 刷新过期时间
        sess.expireTime = now + SESSION_TTL_MS;
        return sess.customerId;
    }

    // 获取或创建会话保证原子性
    private static String getOrCreateSession(int customerId) {
        return customerSession.compute(customerId, (cid, oldKey) -> {
            if (oldKey != null) {
                Session sess = sessions.get(oldKey);
                if (sess != null && sess.expireTime > System.currentTimeMillis()) {
                    // 刷新过期时间
                    sess.expireTime = System.currentTimeMillis() + SESSION_TTL_MS;
                    return oldKey;
                } else {
                    // 旧会话已失效就删除它
                    if (sess != null) sessions.remove(oldKey);
                }
            }
            // 创建新会话
            String newKey = genSessionKey();
            sessions.put(newKey, new Session(customerId, System.currentTimeMillis() + SESSION_TTL_MS));
            return newKey;
        });
    }

    // 通用响应发送
    private static void sendResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // 根分发器，根据路径前缀路由到具体Handler
    static class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            // 解析 /customerid/session
            if (method.equals("GET") && path.matches("/\\d+/session")) {
                new SessionHandler().handle(exchange);
                return;
            }
            // 解析 /betofferid/stake?sessionkey=...
            if (method.equals("POST") && path.matches("/\\d+/stake")) {
                new StakeHandler().handle(exchange);
                return;
            }
            // 解析/betofferid/highVotes
            if (method.equals("GET") && path.matches("/\\d+/highstakes")) {
                new HighStakesHandler().handle(exchange);
                return;
            }
            sendResponse(exchange, 404, "Not Found");
        }
    }

    // 处理/{customerid}/session
    static class SessionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            int customerId;
            try {
                customerId = Integer.parseInt(path.split("/")[1]);
            } catch (Exception e) {
                sendResponse(exchange, 400, "Invalid customer id");
                return;
            }
            String sessionKey = getOrCreateSession(customerId);
            sendResponse(exchange, 200, sessionKey);
        }
    }

    // 处理POST /{betofferid}/stake?sessionkey=xxx
    static class StakeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            int betOfferId;
            try {
                betOfferId = Integer.parseInt(path.split("/")[1]);
            } catch (Exception e) {
                sendResponse(exchange, 400, "Invalid vote offer id");
                return;
            }

            String query = exchange.getRequestURI().getQuery();
            String sessionKey = null;
            if (query != null && query.startsWith("sessionkey=")) {
                sessionKey = query.substring(11);
            }
            if (sessionKey == null || sessionKey.isEmpty()) {
                sendResponse(exchange, 401, "Missing session key");
                return;
            }

            int customerId = validRefreshSession(sessionKey);
            if (customerId == -1) {
                sendResponse(exchange, 401, "Invalid or expired session");
                return;
            }

            InputStream is = exchange.getRequestBody();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            int len;
            while ((len = is.read(chunk)) != -1) {
                buffer.write(chunk, 0, len);
            }
            String bodyStr = new String(buffer.toByteArray(), StandardCharsets.UTF_8).trim();
            int stake;
            try {
                stake = Integer.parseInt(bodyStr);
            } catch (NumberFormatException e) {
                sendResponse(exchange, 400, "Invalid stake value");
                return;
            }

            maxVote.computeIfAbsent(betOfferId, k -> new ConcurrentHashMap<>())
                    .merge(customerId, stake, Math::max);

            sendResponse(exchange, 200, "");
        }
    }

    // 处理GET /{betofferid}/highstakes
    static class HighStakesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            int betOfferId;
            try {
                betOfferId = Integer.parseInt(path.split("/")[1]);
            } catch (Exception e) {
                sendResponse(exchange, 400, "Invalid bet offer id");
                return;
            }

            ConcurrentHashMap<Integer, Integer> customerStakes = maxVote.get(betOfferId);
            if (customerStakes == null || customerStakes.isEmpty()) {
                sendResponse(exchange, 200, "");
                return;
            }

            // 获取所有Entry按stake降序排序,取前20
            List<Map.Entry<Integer, Integer>> entries = new ArrayList<>(customerStakes.entrySet());
            entries.sort((e1, e2) -> e2.getValue().compareTo(e1.getValue()));

            int limit = Math.min(20, entries.size());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < limit; i++) {
                Map.Entry<Integer, Integer> e = entries.get(i);
                if (sb.length() > 0) sb.append(",");
                sb.append(e.getKey()).append("=").append(e.getValue());
            }
            sendResponse(exchange, 200, sb.toString());
        }
    }
}