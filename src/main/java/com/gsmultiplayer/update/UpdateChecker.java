package com.gsmultiplayer.update;

import com.gsmultiplayer.util.GsLog;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Checks GitHub Releases for a newer mod version. Never downloads or replaces
 * anything automatically - it only reports and lets the user open the release page.
 */
public final class UpdateChecker {

    public enum Status { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, ERROR }

    public static final class Result {
        public final Status status;
        public final String latestVersion;
        public final String releaseUrl;
        public final String jarUrl;
        public final String message;

        Result(Status status, String latestVersion, String releaseUrl, String jarUrl, String message) {
            this.status = status;
            this.latestVersion = latestVersion;
            this.releaseUrl = releaseUrl;
            this.jarUrl = jarUrl;
            this.message = message;
        }
    }

    private static final JsonParser PARSER = new JsonParser();

    private volatile Result result = new Result(Status.IDLE, null, null, null, null);

    public Result getResult() {
        return result;
    }

    public void checkAsync(String repoOwner, String repoName, String currentVersion) {
        if (repoOwner == null || repoName == null
                || repoOwner.isEmpty() || repoName.isEmpty()
                || "example".equalsIgnoreCase(repoOwner)) {
            result = new Result(Status.ERROR, null, null, null, "repo not configured");
            return;
        }
        result = new Result(Status.CHECKING, null, null, null, null);
        Thread thread = new Thread(() -> result = check(repoOwner, repoName, currentVersion), "gs-update-check");
        thread.setDaemon(true);
        thread.start();
    }

    private Result check(String repoOwner, String repoName, String currentVersion) {
        String url = "https://api.github.com/repos/" + repoOwner + "/" + repoName + "/releases/latest";
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "GS-Multiplayer-UpdateCheck");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            int code = conn.getResponseCode();
            if (code != 200) {
                return new Result(Status.ERROR, null, null, null, "HTTP " + code);
            }
            String body = readAll(conn.getInputStream());
            JsonObject json = PARSER.parse(body).getAsJsonObject();
            String tagName = json.has("tag_name") && json.get("tag_name").isJsonPrimitive()
                    ? json.get("tag_name").getAsString() : "";
            String releaseUrl = json.has("html_url") && json.get("html_url").isJsonPrimitive()
                    ? json.get("html_url").getAsString() : url;
            String jarUrl = null;
            JsonElement assets = json.get("assets");
            if (assets != null && assets.isJsonArray()) {
                for (JsonElement e : (JsonArray) assets) {
                    if (!e.isJsonObject()) {
                        continue;
                    }
                    JsonObject asset = e.getAsJsonObject();
                    String name = asset.has("name") && asset.get("name").isJsonPrimitive()
                            ? asset.get("name").getAsString() : "";
                    String download = asset.has("browser_download_url") && asset.get("browser_download_url").isJsonPrimitive()
                            ? asset.get("browser_download_url").getAsString() : null;
                    if (download != null && name.endsWith(".jar") && !name.contains("sources")) {
                        jarUrl = download;
                        break;
                    }
                }
            }
            String version = tagName.startsWith("v") ? tagName.substring(1) : tagName;
            if (compareVersions(version, currentVersion) > 0) {
                GsLog.info("Update available: " + version + " (current " + currentVersion + ")");
                return new Result(Status.AVAILABLE, version, releaseUrl, jarUrl, null);
            }
            GsLog.info("No updates, latest release: " + tagName);
            return new Result(Status.UP_TO_DATE, version, releaseUrl, jarUrl, null);
        } catch (Exception e) {
            GsLog.debug("Update check failed: " + e.getMessage());
            return new Result(Status.ERROR, null, null, null, e.getMessage());
        }
    }

    /** Compares dotted numeric versions; returns >0 when a is newer than b. */
    public static int compareVersions(String a, String b) {
        if (a == null || b == null) {
            return 0;
        }
        String[] pa = a.split("[.\\-+]");
        String[] pb = b.split("[.\\-+]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = part(pa, i);
            int vb = part(pb, i);
            if (va != vb) {
                return Integer.compare(va, vb);
            }
        }
        return 0;
    }

    private static int part(String[] parts, int i) {
        if (i >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[i].trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
