package com.fangsu.ui.train;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class TrainDescriptionCache {
    private static final int MAX_ENTRIES = 256;
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), task -> {
                Thread thread = new Thread(task, "FangSu train description");
                thread.setDaemon(true);
                return thread;
            });
    private static final Map<String, CompletableFuture<String>> ARTICLES = new LinkedHashMap<>(16, 0.75F, true);

    private TrainDescriptionCache() {}

    static CompletableFuture<String> request(String article) {
        CompletableFuture<String> existing = ARTICLES.get(article);
        if (existing != null) return existing;
        CompletableFuture<String> pending;
        try {
            pending = CompletableFuture.supplyAsync(() -> fetch(article), WORKER);
        } catch (RejectedExecutionException ignored) {
            pending = CompletableFuture.completedFuture("");
        }
        ARTICLES.put(article, pending);
        if (ARTICLES.size() > MAX_ENTRIES) ARTICLES.remove(ARTICLES.keySet().iterator().next());
        return pending;
    }

    private static String fetch(String article) {
        String title = URLEncoder.encode(article, StandardCharsets.UTF_8);
        String url = "https://en.wikipedia.org/w/api.php?format=json&action=query&prop=extracts&explaintext&exintro&titles=" + title;
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            try {
                connection.setConnectTimeout(4000);
                connection.setReadTimeout(4000);
                connection.setRequestProperty("User-Agent", "FangSu (Minecraft Transit Railway train selection)");
                try (InputStreamReader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    JsonObject pages = root.getAsJsonObject("query").getAsJsonObject("pages");
                    for (Map.Entry<String, JsonElement> page : pages.entrySet()) {
                        JsonElement extract = page.getValue().getAsJsonObject().get("extract");
                        if (extract != null && extract.isJsonPrimitive()) return extract.getAsString();
                    }
                }
            } finally {
                connection.disconnect();
            }
        } catch (Exception ignored) {
            // 本地车辆资料在请求失败时仍可阅读。
        }
        return "";
    }
}
