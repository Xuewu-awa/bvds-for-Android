package com.xuewu.bvds;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaScannerConnection;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BridgeInterface {

    private final Context context;
    private final WebView webView;
    private final Handler mainHandler;
    private final ExecutorService executor;
    private final SharedPreferences prefs;
    private final MediaMerger mediaMerger;

    private volatile String cookieStore = "";
    private volatile String lastHtml = "";  // 供日志复制使用

    // bvds.py QUALITY_MAP
    private static final int[][] QUALITY_MAP = {
        {120}, // 1: 4K
        {80},  // 2: 1080P
        {64},  // 3: 720P
        {32},  // 4: 480P
        {16},  // 5: 360P
    };

    public BridgeInterface(Context context, WebView webView) {
        this.context = context;
        this.webView = webView;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newFixedThreadPool(3);
        this.prefs = context.getSharedPreferences("bvds_config", Context.MODE_PRIVATE);
        this.mediaMerger = new MediaMerger();
    }

    // ═══════════════════════════════════════════════
    // 配置读写
    // ═══════════════════════════════════════════════

    @JavascriptInterface
    public String getConfig(String key) {
        return prefs.getString(key, "");
    }

    @JavascriptInterface
    public void setConfig(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }

    @JavascriptInterface
    public String getDefaultDownloadDir() {
        File dir = new File("/storage/emulated/0/Download/bvds");
        if (!dir.exists()) dir.mkdirs();
        return dir.getAbsolutePath();
    }

    // ═══════════════════════════════════════════════
    // 二维码
    // ═══════════════════════════════════════════════

    @JavascriptInterface
    public String generateQRCode(String text, int size) {
        try {
            return QRCodeGenerator.generateAsBase64(text, size);
        } catch (Exception e) {
            return "";
        }
    }

    // ═══════════════════════════════════════════════
    // 通用 HTTP（登录/轮询等简单场景）
    // ═══════════════════════════════════════════════

    @JavascriptInterface
    public void apiGet(String url, String callbackName) {
        executor.execute(() -> {
            try {
                HttpURLConnection conn = openConnection(url);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                String body = readStream(is);
                mergeAllCookies(conn);

                String result = new JSONObject()
                        .put("status", code)
                        .put("body", body)
                        .toString();
                callJS(callbackName, result);
            } catch (Exception e) {
                callJS(callbackName, "{\"status\":0,\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        });
    }

    @JavascriptInterface
    public void apiPost(String url, String postBody, String callbackName) {
        executor.execute(() -> {
            try {
                HttpURLConnection conn = openConnection(url);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                if (postBody != null && !postBody.isEmpty()) {
                    OutputStream os = conn.getOutputStream();
                    os.write(postBody.getBytes("UTF-8"));
                    os.close();
                }
                int code = conn.getResponseCode();
                InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                String body = readStream(is);
                mergeAllCookies(conn);

                String result = new JSONObject()
                        .put("status", code)
                        .put("body", body)
                        .toString();
                callJS(callbackName, result);
            } catch (Exception e) {
                callJS(callbackName, "{\"status\":0,\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        });
    }

    // ═══════════════════════════════════════════════
    // 提取 URL（批量输入 → URL 列表）
    // ═══════════════════════════════════════════════

    @JavascriptInterface
    public String extractUrls(String text) {
        if (text == null || text.trim().isEmpty()) return "[]";
        JSONArray arr = new JSONArray();
        String[] parts = text.split("[\\s,;\\n]+");
        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) continue;
            if (part.startsWith("http://") || part.startsWith("https://")) {
                arr.put(part);
            } else if (part.contains("bilibili.com") || part.contains("b23.tv")) {
                arr.put("https://" + part);
            }
        }
        return arr.toString();
    }

    // ═══════════════════════════════════════════════
    // 视频信息解析（对照 bvds.py get_video_urls / get_pgc_video_urls / get_video_info）
    // ═══════════════════════════════════════════════

    @JavascriptInterface
    public void fetchVideoInfo(String url, String qualityKey, String taskId) {
        executor.execute(() -> {
            try {
                int qualityCode = getQualityCode(qualityKey);
                JSONObject info = new JSONObject();
                jlog(taskId, "fetchVideoInfo 开始 url=" + url + " qualityCode=" + qualityCode);

                // 1. HTTP GET — 对照 bvds.py Session 自动跟跳 + 多次请求积累 cookie
                //    第一轮只跟跳不读 body（对应 get_real_url），第二轮读 body（对应 get_video_urls）
                jlog(taskId, "第1轮: 跟跳积累 cookie...");
                String[] result1 = followRedirectChain(url, taskId, false);
                if (result1 == null) return;

                jlog(taskId, "第2轮: 带 cookie 重新跟跳获取 HTML...");
                String[] result2 = followRedirectChain(url, taskId, true);
                if (result2 == null) return;
                String html = result2[0];
                String finalUrl = result2[1];

                jlog(taskId, "最终 URL=" + finalUrl);
                jlog(taskId, "HTML 长度=" + html.length());
                lastHtml = html;  // 保存供日志复制

                // 2. 提取标题
                String title = extractTitle(html);
                info.put("title", title);
                jlog(taskId, "标题=" + title);

                // 3. 提取 sourceId (BV/EP/SS) — 对照 bvds.py extract_source_id 用 real_url
                String sid = extractSourceId(finalUrl);
                info.put("sourceId", sid);
                jlog(taskId, "sourceId=" + sid);

                // 4. 对照 bvds.py: 先检查是否是 PGC
                boolean isPgc = url.contains("/ep") || url.contains("/bangumi");
                jlog(taskId, "isPgc=" + isPgc);
                if (isPgc) {
                    Pattern epP = Pattern.compile("ep(\\d+)");
                    Matcher epM = epP.matcher(url);
                    if (epM.find()) {
                        String epId = epM.group(1);
                        jlog(taskId, "走 PGC 通道 epId=" + epId);
                        fetchPgcUrls(epId, qualityCode, info, taskId);
                        return;
                    }
                    jlog(taskId, "PGC URL 但未匹配到 epId", true);
                }

                // 5. 对照 bvds.py：先试 __playinfo__，再试 __INITIAL_STATE__ + 官方播放 API
                jlog(taskId, "尝试解析 __playinfo__...");
                boolean ok = extractPlayInfo(html, qualityCode, info, taskId);
                if (!ok) {
                    jlog(taskId, "__playinfo__ 未找到，从 __INITIAL_STATE__ 提取 bvid/cid 调播放 API...");
                    ok = extractViaPlayApi(html, qualityCode, info, taskId);
                }
                if (!ok) {
                    jlog(taskId, "所有解析方式均失败", true);
                    info.put("error", "无法解析视频信息");
                } else {
                    jlog(taskId, "__playinfo__ 解析成功 videoUrl=" + (info.has("videoUrl") ? "OK" : "null"));
                }

                callJS("onVideoInfoResult",
                        "\"" + taskId + "\", " + info.toString());

            } catch (Exception e) {
                jlog(taskId, "异常: " + e.getMessage(), true);
                callJS("onVideoInfoResult",
                        "\"" + taskId + "\", \"{\\\"error\\\":\\\"" + escapeJson(e.getMessage()) + "\\\"}\"");
            }
        });
    }

    // --- fetchVideoInfo 子步骤 ---

    private String extractTitle(String html) {
        Pattern p = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        if (m.find()) {
            String t = m.group(1).replace("_哔哩哔哩_bilibili", "").trim();
            return t.isEmpty() ? "未知标题" : t;
        }
        return "未知标题";
    }

    private String extractSourceId(String url) {
        Pattern bvP = Pattern.compile("BV[0-9a-zA-Z]{10}");
        Matcher bvM = bvP.matcher(url);
        if (bvM.find()) return "BV:" + bvM.group();

        Pattern epP = Pattern.compile("ep(\\d+)");
        Matcher epM = epP.matcher(url);
        if (epM.find()) return "EP:" + epM.group(1);

        Pattern ssP = Pattern.compile("ss(\\d+)");
        Matcher ssM = ssP.matcher(url);
        if (ssM.find()) return "SS:" + ssM.group(1);

        return "URL:" + (url.length() > 30 ? url.substring(0, 30) + "..." : url);
    }

    /**
     * 对照 bvds.py get_video_urls: re.search(r'window.__playinfo__=(.*?)</script>', html)
     */
    private boolean extractPlayInfo(String html, int qualityCode, JSONObject info, String taskId) {
        try {
            // 对照 bvds.py: re.search(r'window.__playinfo__=(.*?)</script>', html)
            String rawJson = extractJsonAfterAssignment(html, "window.__playinfo__");
            if (rawJson == null) rawJson = extractJsonAfterAssignment(html, "__playinfo__");
            if (rawJson == null) rawJson = extractJsonAfterAssignment(html, "\"__playinfo__\"");
            if (rawJson == null) rawJson = extractJsonAfterAssignment(html, "'__playinfo__'");
            if (rawJson == null) {
                jlog(taskId, "  未找到 __playinfo__ 赋值", true);
                return false;
            }
            // 检查是否是真的 playinfo 数据（必须含 data 字段或 dash），排除误提取的 JS 对象
            if (rawJson.length() < 200) {
                jlog(taskId, "  提取的 JSON 太短 (" + rawJson.length() + "字节)，可能是误提取，跳过", true);
                return false;
            }
            jlog(taskId, "  提取到 JSON 长度=" + rawJson.length()
                    + " 前80字=" + rawJson.substring(0, Math.min(80, rawJson.length())));

            JSONObject playData = new JSONObject(rawJson);
            jlog(taskId, "  JSON 解析成功");
            JSONObject data = playData.optJSONObject("data");
            if (data == null) { jlog(taskId, "  无 data 字段", true); return false; }
            JSONObject dash = data.optJSONObject("dash");
            if (dash == null) { jlog(taskId, "  无 dash 字段", true); return false; }

            JSONArray videos = dash.optJSONArray("video");
            JSONArray audios = dash.optJSONArray("audio");
            jlog(taskId, "  video 数量=" + (videos != null ? videos.length() : 0) +
                        " audio 数量=" + (audios != null ? audios.length() : 0));
            if (videos == null || videos.length() == 0 || audios == null || audios.length() == 0) {
                jlog(taskId, "  video/audio 列表为空", true);
                return false;
            }

            // 按质量选视频
            JSONObject selectedVideo = null;
            int bestDiff = Integer.MAX_VALUE;
            for (int i = 0; i < videos.length(); i++) {
                JSONObject v = videos.getJSONObject(i);
                int vid = v.optInt("id", 0);
                if (vid == qualityCode) { selectedVideo = v; break; }
                int diff = Math.abs(vid - qualityCode);
                if (diff < bestDiff) { bestDiff = diff; selectedVideo = v; }
            }
            jlog(taskId, "  选中视频 id=" + (selectedVideo != null ? selectedVideo.optInt("id", -1) : -1));

            // 选最高码率音频
            JSONObject bestAudio = audios.getJSONObject(0);
            long bestBw = optLong(bestAudio, "bandwidth", 0);
            for (int i = 1; i < audios.length(); i++) {
                JSONObject a = audios.getJSONObject(i);
                long bw = optLong(a, "bandwidth", 0);
                if (bw > bestBw) { bestBw = bw; bestAudio = a; }
            }
            jlog(taskId, "  选中音频 bandwidth=" + bestBw);

            if (selectedVideo != null && bestAudio != null) {
                info.put("videoUrl", optString(selectedVideo, "baseUrl", "base_url"));
                info.put("audioUrl", optString(bestAudio, "baseUrl", "base_url"));
                return true;
            }
            jlog(taskId, "  未选出有效视频/音频", true);
            return false;
        } catch (Exception e) {
            jlog(taskId, "  JSON 解析异常: " + e.getMessage(), true);
            return false;
        }
    }

    /**
     * 对照 bvds.py get_pgc_video_urls
     */
    private void fetchPgcUrls(String epId, int qualityCode, JSONObject info, String taskId) {
        try {
            String apiUrl = "https://api.bilibili.com/pgc/player/web/playurl"
                    + "?ep_id=" + epId + "&qn=" + qualityCode + "&fnval=4048&fourk=1";
            jlog(taskId, "PGC API 请求: " + apiUrl);
            HttpURLConnection conn = openConnection(apiUrl);
            int code = conn.getResponseCode();
            jlog(taskId, "PGC API 响应 code=" + code);
            if (code != 200) {
                conn.disconnect();
                info.put("error", "PGC API 请求失败 HTTP " + code);
                callJS("onVideoInfoResult", "\"" + taskId + "\", " + info.toString());
                return;
            }
            String body = readStream(conn.getInputStream());
            mergeAllCookies(conn);
            conn.disconnect();
            jlog(taskId, "PGC 响应长度=" + body.length());

            JSONObject resp = new JSONObject(body);
            jlog(taskId, "PGC resp.code=" + resp.optInt("code"));
            if (resp.optInt("code") != 0) {
                info.put("error", "PGC API 返回错误");
                callJS("onVideoInfoResult", "\"" + taskId + "\", " + info.toString());
                return;
            }

            JSONObject result = resp.optJSONObject("result");
            if (result == null) { jlog(taskId, "PGC 无 result", true); info.put("error", "无 PGC 数据"); }
            else {
                JSONObject dash = result.optJSONObject("dash");
                if (dash == null) { jlog(taskId, "PGC 无 dash", true); info.put("error", "无 PGC dash 数据"); }
                else {
                    JSONArray videos = dash.optJSONArray("video");
                    JSONArray audios = dash.optJSONArray("audio");
                    jlog(taskId, "PGC video=" + (videos != null ? videos.length() : 0) +
                              " audio=" + (audios != null ? audios.length() : 0));
                    if (videos != null && videos.length() > 0 && audios != null && audios.length() > 0) {
                        // 按 qualityCode 匹配视频清晰度（与 extractPlayInfo 一致）
                        JSONObject bestV = videos.getJSONObject(0);
                        int bestDiff = Integer.MAX_VALUE;
                        for (int i = 0; i < videos.length(); i++) {
                            JSONObject v = videos.getJSONObject(i);
                            int vid = v.optInt("id", 0);
                            if (vid == qualityCode) { bestV = v; break; }
                            int diff = Math.abs(vid - qualityCode);
                            if (diff < bestDiff) { bestDiff = diff; bestV = v; }
                        }
                        JSONObject bestA = audios.getJSONObject(0);
                        long bestBwA = optLong(bestA, "bandwidth", 0);
                        for (int i = 1; i < audios.length(); i++) {
                            JSONObject a = audios.getJSONObject(i);
                            long bw = optLong(a, "bandwidth", 0);
                            if (bw > bestBwA) { bestBwA = bw; bestA = a; }
                        }
                        info.put("videoUrl", optString(bestV, "baseUrl", "base_url"));
                        info.put("audioUrl", optString(bestA, "baseUrl", "base_url"));
                        jlog(taskId, "PGC 解析成功 videoId=" + bestV.optInt("id"));
                    } else {
                        jlog(taskId, "PGC dash 为空", true);
                        info.put("error", "PGC dash 数据为空");
                    }
                }
            }
        } catch (Exception e) {
            jlog(taskId, "PGC 异常: " + e.getMessage(), true);
            try { info.put("error", "PGC 解析异常: " + e.getMessage()); } catch (Exception ignored) {}
        }
        callJS("onVideoInfoResult", "\"" + taskId + "\", " + info.toString());
    }

    // ═══════════════════════════════════════════════
    // 下载编排（对照 bvds.py download_video）
    // ═══════════════════════════════════════════════

    @JavascriptInterface
    public void downloadTask(String taskId, String videoUrl, String audioUrl,
                              String mode, String outputPath) {
        executor.execute(() -> {
            jlog(taskId, "downloadTask 开始 mode=" + mode + " output=" + outputPath);
            try {
                File outFile = new File(outputPath);
                File parent = outFile.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();

                String unique = UUID.randomUUID().toString().substring(0, 8);

                if ("1".equals(mode)) {
                    jlog(taskId, "模式: 仅视频 (.mp4)");
                    boolean ok = downloadSingleFile(videoUrl, outputPath, taskId, 0, 100);
                    if (ok) {
                        callJS("onTaskComplete", "\"" + taskId + "\", true, \"" + escapeJson(outputPath) + "\"");
                        scanMediaFile(outputPath);
                    } else {
                        callJS("onTaskFailed", "\"" + taskId + "\", \"视频下载失败\"");
                    }
                } else if ("2".equals(mode)) {
                    // 仅音频：B站音频流是 AAC 在 MP4 容器里，直接保存即可
                    // 所有 Android 设备原生支持 AAC 播放
                    jlog(taskId, "模式: 仅音频 (AAC 直存)");
                    String m4aPath = outputPath.replaceAll("\\.mp3$", ".m4a");
                    boolean ok = downloadSingleFile(audioUrl, m4aPath, taskId, 0, 100);
                    if (ok) {
                        jlog(taskId, "音频保存成功: " + m4aPath);
                        callJS("onTaskComplete", "\"" + taskId + "\", true, \"" + escapeJson(m4aPath) + "\"");
                        scanMediaFile(m4aPath);
                    } else {
                        callJS("onTaskFailed", "\"" + taskId + "\", \"音频下载失败\"");
                    }
                } else {
                    // 完整视频：下载到私有缓存 → 合并 → 复制到目标目录 → 清理缓存
                    File cacheDir = context.getCacheDir();
                    String videoPath = new File(cacheDir, "video_" + unique + ".m4v").getAbsolutePath();
                    String audioPath = new File(cacheDir, "audio_" + unique + ".m4a").getAbsolutePath();
                    String mergedPath = new File(cacheDir, "merged_" + unique + ".mp4").getAbsolutePath();

                    // 下载视频 → 缓存 (0-50%)
                    boolean vOk = downloadSingleFile(videoUrl, videoPath, taskId, 0, 50);
                    if (!vOk) {
                        new File(videoPath).delete();
                        callJS("onTaskFailed", "\"" + taskId + "\", \"视频流下载失败\"");
                        return;
                    }

                    // 下载音频 → 缓存 (50-95%)
                    boolean aOk = downloadSingleFile(audioUrl, audioPath, taskId, 50, 95);
                    if (!aOk) {
                        new File(videoPath).delete();
                        new File(audioPath).delete();
                        callJS("onTaskFailed", "\"" + taskId + "\", \"音频流下载失败\"");
                        return;
                    }

                    // 合并 → 缓存
                    jlog(taskId, "开始合并音视频...");
                    callJS("onTaskProgress", "\"" + taskId + "\", 96, \"merging\"");
                    boolean merged = mediaMerger.merge(videoPath, audioPath, mergedPath);

                    // 清理下载缓存
                    new File(videoPath).delete();
                    new File(audioPath).delete();

                    if (merged) {
                        // 复制到目标目录
                        jlog(taskId, "合并成功，复制到目标目录...");
                        callJS("onTaskProgress", "\"" + taskId + "\", 98, \"copying\"");
                        boolean copied = copyFile(mergedPath, outputPath);
                        new File(mergedPath).delete();

                        if (copied) {
                            jlog(taskId, "复制成功 output=" + outputPath);
                            callJS("onTaskComplete", "\"" + taskId + "\", true, \"" + escapeJson(outputPath) + "\"");
                            scanMediaFile(outputPath);
                        } else {
                            jlog(taskId, "复制到目标目录失败", true);
                            callJS("onTaskFailed", "\"" + taskId + "\", \"写入目标目录失败\"");
                        }
                    } else {
                        new File(mergedPath).delete();
                        jlog(taskId, "合并失败", true);
                        callJS("onTaskFailed", "\"" + taskId + "\", \"合并失败\"");
                    }
                }
            } catch (Exception e) {
                jlog(taskId, "下载异常: " + e.getMessage(), true);
                callJS("onTaskFailed", "\"" + taskId + "\", \"" + escapeJson(e.getMessage()) + "\"");
            }
        });
    }

    private boolean downloadSingleFile(String url, String destPath, String taskId,
                                        int progressStart, int progressEnd) {
        int maxRetries = 3;
        int delayMs = 500;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                jlog(taskId, "重试下载 第" + attempt + "次 (等待" + delayMs + "ms)...");
                try { Thread.sleep(delayMs); } catch (InterruptedException ignored) {}
                delayMs *= 2;
            }

            try {
                jlog(taskId, "下载文件: " + url.substring(url.lastIndexOf('/') + 1) +
                     " (进度 " + progressStart + "-" + progressEnd + "%)"
                     + (attempt > 0 ? " [重试" + attempt + "]" : ""));
                HttpURLConnection conn = openConnection(url);
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(30000);

                int code = conn.getResponseCode();
                jlog(taskId, "下载 HTTP code=" + code);

                // 5xx 服务端错误 → 可重试
                if (code >= 500 && code < 600) {
                    jlog(taskId, "服务端错误 HTTP " + code
                            + (attempt < maxRetries ? "，稍后重试..." : "，重试耗尽"), true);
                    conn.disconnect();
                    new File(destPath).delete();
                    if (attempt < maxRetries) continue;
                    return false;
                }

                // 其他非 2xx → 不可重试
                if (code < 200 || code >= 300) {
                    jlog(taskId, "下载失败 HTTP " + code, true);
                    conn.disconnect();
                    new File(destPath).delete();
                    return false;
                }

                long total = conn.getContentLengthLong();
                InputStream is = conn.getInputStream();
                FileOutputStream fos = new FileOutputStream(destPath);

                byte[] buf = new byte[8192];
                long downloaded = 0;
                int len;
                long lastReport = 0;

                while ((len = is.read(buf)) != -1) {
                    fos.write(buf, 0, len);
                    downloaded += len;
                    long now = System.currentTimeMillis();
                    if (total > 0 && now - lastReport > 200) {
                        lastReport = now;
                        int pct = progressStart + (int) (downloaded * (progressEnd - progressStart) / total);
                        callJS("onTaskProgress", "\"" + taskId + "\", " + pct + ", \"downloading\"");
                    }
                }
                fos.close();
                is.close();
                conn.disconnect();
                return true;
            } catch (java.io.IOException e) {
                jlog(taskId, "IO异常: " + e.getMessage()
                        + (attempt < maxRetries ? "，稍后重试..." : "，重试耗尽"), true);
                new File(destPath).delete();
                if (attempt < maxRetries) continue;
                return false;
            } catch (Exception e) {
                jlog(taskId, "下载异常: " + e.getMessage(), true);
                new File(destPath).delete();
                return false;
            }
        }
        return false;
    }

    // ═══════════════════════════════════════════════
    // 剪贴板
    // ═══════════════════════════════════════════════

    @JavascriptInterface
    public void copyToClipboard(String text) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                context.getSystemService(Context.CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText("bvds_log", text);
        cm.setPrimaryClip(clip);
    }

    @JavascriptInterface
    public boolean saveTextFile(String path, String content) {
        try {
            File f = new File(path);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(content.getBytes("UTF-8"));
            fos.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ═══════════════════════════════════════════════
    // Cookie 管理
    // ═══════════════════════════════════════════════

    @JavascriptInterface
    public String getLastHtml() {
        return lastHtml;
    }

    @JavascriptInterface
    public String getCookies() {
        return cookieStore;
    }

    @JavascriptInterface
    public void setCookies(String cookies) {
        this.cookieStore = cookies;
        prefs.edit().putString("cookies", cookies).apply();
        jlog("cookies", "保存 Cookie: " + (cookies != null && cookies.length() > 100 ? cookies.substring(0, 100) + "..." : cookies));
    }

    @JavascriptInterface
    public void loadSavedCookies() {
        String saved = prefs.getString("cookies", "");
        if (!saved.isEmpty()) {
            this.cookieStore = saved;
            jlog("cookies", "加载已保存 Cookie: " + (saved.length() > 100 ? saved.substring(0, 100) + "..." : saved));
        } else {
            jlog("cookies", "无已保存 Cookie");
        }
    }

    @JavascriptInterface
    public String getDownloadDir() {
        String dir = prefs.getString("download_dir", "");
        if (dir.isEmpty()) {
            dir = getDefaultDownloadDir();
        }
        return dir;
    }

    @JavascriptInterface
    public void setDownloadDir(String path) {
        prefs.edit().putString("download_dir", path).apply();
        File d = new File(path);
        if (!d.exists()) d.mkdirs();
    }

    @JavascriptInterface
    public void clearCookies() {
        cookieStore = "";
        prefs.edit().remove("cookies").apply();
    }

    /**
     * 关闭线程池，由 MainActivity.onDestroy() 调用防止泄漏。
     */
    public void shutdown() {
        executor.shutdown();
    }

    /**
     * 兜底：找 key 后面任意位置的 = 或 : 然后跟 {，中间可以跨越多字符。
     */
    private static String extractJsonAfterAnyAssignment(String html, String key) {
        int searchFrom = 0;
        while (searchFrom < html.length()) {
            int idx = html.indexOf(key, searchFrom);
            if (idx < 0) return null;
            // 从 key 之后找 = 或 :
            int after = idx + key.length();
            int sep = -1;
            for (int i = after; i < Math.min(html.length(), after + 100); i++) {
                char c = html.charAt(i);
                if (c == '=' || c == ':') { sep = i; break; }
                if (c == '<') break; // 不跨 HTML 标签
            }
            if (sep < 0) { searchFrom = idx + 1; continue; }
            // 找 sep 之后的 {
            int brace = html.indexOf('{', sep + 1);
            if (brace < 0 || brace > sep + 50) { searchFrom = idx + 1; continue; }
            // 数括号
            int depth = 0;
            int end = -1;
            for (int i = brace; i < html.length(); i++) {
                char c = html.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) { end = i + 1; break; }
                }
            }
            if (end < 0) { searchFrom = idx + 1; continue; }
            String json = html.substring(brace, end).trim();
            if (json.endsWith(";")) json = json.substring(0, json.length() - 1).trim();
            return json;
        }
        return null;
    }

    /**
     * 从 __INITIAL_STATE__ 中提取视频播放数据（B站新页面格式）
     */
    private boolean extractFromInitialState(String html, int qualityCode, JSONObject info, String taskId) {
        try {
            String json = extractJsonAfterAssignment(html, "__INITIAL_STATE__");
            if (json == null) {
                jlog(taskId, "  未找到 __INITIAL_STATE__", true);
                return false;
            }
            jlog(taskId, "  __INITIAL_STATE__ JSON 长度=" + json.length());
            JSONObject state = new JSONObject(json);
            // 打印顶层 key
            StringBuilder keys = new StringBuilder();
            java.util.Iterator<String> it = state.keys();
            while (it.hasNext()) { if (keys.length() > 0) keys.append(", "); keys.append(it.next()); }
            jlog(taskId, "  __INITIAL_STATE__ 顶层 keys: " + keys.toString());

            // 穷举可能的 playinfo 路径
            String[] paths = { "videoData", "videoInfo", "playInfo", "playinfo", "initPlayInfo" };
            for (String p : paths) {
                JSONObject pd = state.optJSONObject(p);
                if (pd != null && (pd.has("dash") || pd.has("data") || pd.has("video") || pd.has("timelength"))) {
                    jlog(taskId, "  在 " + p + " 中找到播放数据");
                    return extractDashFromPlayData(pd, qualityCode, info, taskId);
                }
            }
            // 可能在 videoData 的内部结构里
            JSONObject vd = state.optJSONObject("videoData");
            if (vd != null) {
                // 打印 videoData 的子 key
                StringBuilder vdKeys = new StringBuilder();
                java.util.Iterator<String> vit = vd.keys();
                while (vit.hasNext()) { if (vdKeys.length() > 0) vdKeys.append(", "); vdKeys.append(vit.next()); }
                jlog(taskId, "  videoData 子 keys: " + vdKeys.toString());

                // 直接在 videoData 自身找 dash
                if (vd.has("dash")) {
                    jlog(taskId, "  在 videoData 自身找到 dash");
                    return extractDashFromPlayData(vd, qualityCode, info, taskId);
                }
                // 在 videoData.data 找
                JSONObject inner = vd.optJSONObject("data");
                if (inner != null && inner.has("dash")) {
                    jlog(taskId, "  在 videoData.data 中找到 dash");
                    return extractDashFromPlayData(inner, qualityCode, info, taskId);
                }
            }
            // 试试 player 字段
            JSONObject player = state.optJSONObject("player");
            if (player != null) {
                StringBuilder pKeys = new StringBuilder();
                java.util.Iterator<String> pit = player.keys();
                while (pit.hasNext()) { if (pKeys.length() > 0) pKeys.append(", "); pKeys.append(pit.next()); }
                jlog(taskId, "  player 子 keys: " + pKeys.toString());
                if (player.has("dash")) {
                    jlog(taskId, "  在 player 中找到 dash");
                    return extractDashFromPlayData(player, qualityCode, info, taskId);
                }
            }
            jlog(taskId, "  __INITIAL_STATE__ 中未找到播放数据", true);
            return false;
        } catch (Exception e) {
            jlog(taskId, "  __INITIAL_STATE__ 解析异常: " + e.getMessage(), true);
            return false;
        }
    }

    /**
     * 从 playData JSON 中提取 dash 视频/音频 URL
     */
    private boolean extractDashFromPlayData(JSONObject playData, int qualityCode, JSONObject info, String taskId) {
        try {
            JSONObject data = playData.optJSONObject("data");
            if (data == null) data = playData;
            JSONObject dash = data.optJSONObject("dash");
            if (dash == null) {
                jlog(taskId, "  无 dash 字段", true);
                return false;
            }
            JSONArray videos = dash.optJSONArray("video");
            JSONArray audios = dash.optJSONArray("audio");
            jlog(taskId, "  video=" + (videos != null ? videos.length() : 0)
                    + " audio=" + (audios != null ? audios.length() : 0));
            if (videos == null || videos.length() == 0 || audios == null || audios.length() == 0) return false;
            // 选视频
            JSONObject bestV = videos.getJSONObject(0);
            int bestDiff = Math.abs(bestV.optInt("id", 0) - qualityCode);
            for (int i = 1; i < videos.length(); i++) {
                JSONObject v = videos.getJSONObject(i);
                int vid = v.optInt("id", 0);
                if (vid == qualityCode) { bestV = v; break; }
                int d = Math.abs(vid - qualityCode);
                if (d < bestDiff) { bestDiff = d; bestV = v; }
            }
            // 选音频
            JSONObject bestA = audios.getJSONObject(0);
            long bestBw = optLong(bestA, "bandwidth", 0);
            for (int i = 1; i < audios.length(); i++) {
                JSONObject a = audios.getJSONObject(i);
                long bw = optLong(a, "bandwidth", 0);
                if (bw > bestBw) { bestBw = bw; bestA = a; }
            }
            info.put("videoUrl", optString(bestV, "baseUrl", "base_url"));
            info.put("audioUrl", optString(bestA, "baseUrl", "base_url"));
            jlog(taskId, "  提取成功 videoId=" + bestV.optInt("id") + " audioBw=" + bestBw);
            return true;
        } catch (Exception e) {
            jlog(taskId, "  提取 dash 异常: " + e.getMessage(), true);
            return false;
        }
    }

    /**
     * 从 __INITIAL_STATE__ 提取 bvid+cid，调 B站官方播放 API 获取 dash URL。
     * 对照 bvds.py 的 get_pgc_video_urls 思路，但用于普通视频。
     */
    private boolean extractViaPlayApi(String html, int qualityCode, JSONObject info, String taskId) {
        try {
            String stateJson = extractJsonAfterAssignment(html, "__INITIAL_STATE__");
            if (stateJson == null) {
                jlog(taskId, "  未找到 __INITIAL_STATE__", true);
                return false;
            }
            JSONObject state = new JSONObject(stateJson);
            // 从 videoData 找 bvid / aid / cid
            JSONObject vd = state.optJSONObject("videoData");
            if (vd == null) {
                jlog(taskId, "  无 videoData", true);
                return false;
            }
            String bvid = vd.optString("bvid", state.optString("bvid", ""));
            long aid = optLong(vd, "aid", optLong(state, "aid", 0));
            long cid = optLong(vd, "cid", 0);

            jlog(taskId, "  bvid=" + bvid + " aid=" + aid + " cid=" + cid);

            if (bvid.isEmpty() || cid == 0) {
                jlog(taskId, "  缺少 bvid 或 cid", true);
                return false;
            }

            // 调 B站播放 API
            String apiUrl = "https://api.bilibili.com/x/player/playurl"
                    + "?bvid=" + bvid + "&cid=" + cid
                    + "&qn=" + qualityCode + "&fnval=4048&fourk=1";
            jlog(taskId, "  调播放 API: " + apiUrl.substring(0, Math.min(100, apiUrl.length())) + "...");

            HttpURLConnection conn = openConnection(apiUrl);
            int code = conn.getResponseCode();
            jlog(taskId, "  播放 API 响应 code=" + code);
            if (code != 200) {
                jlog(taskId, "  播放 API HTTP " + code, true);
                return false;
            }
            String body = readStream(conn.getInputStream());
            mergeAllCookies(conn);
            jlog(taskId, "  播放 API 响应长度=" + body.length());

            JSONObject resp = new JSONObject(body);
            jlog(taskId, "  播放 API code=" + resp.optInt("code"));
            if (resp.optInt("code") != 0) {
                String msg = resp.optString("message", "");
                jlog(taskId, "  播放 API 错误: " + msg, true);
                return false;
            }

            // B站播放 API 返回格式可能是 data 或 result
            JSONObject result = resp.optJSONObject("data");
            if (result == null) result = resp.optJSONObject("result");
            if (result == null) {
                // 打印顶层 key 帮助定位
                StringBuilder keys = new StringBuilder();
                java.util.Iterator<String> it = resp.keys();
                while (it.hasNext()) { if (keys.length() > 0) keys.append(", "); keys.append(it.next()); }
                jlog(taskId, "  播放 API 顶层 keys: " + keys.toString() + " — 无 data/result", true);
                return false;
            }
            JSONObject dash = result.optJSONObject("dash");
            if (dash == null) {
                jlog(taskId, "  播放 API 无 dash", true);
                return false;
            }

            // dash 里直接有 video 和 audio 数组
            JSONArray videos = dash.optJSONArray("video");
            JSONArray audios = dash.optJSONArray("audio");
            jlog(taskId, "  API video=" + (videos != null ? videos.length() : 0)
                    + " audio=" + (audios != null ? audios.length() : 0));
            if (videos == null || videos.length() == 0 || audios == null || audios.length() == 0) {
                jlog(taskId, "  API dash 列表为空", true);
                return false;
            }

            // 选最高码率视频
            JSONObject bestV = videos.getJSONObject(0);
            long bestBwV = optLong(bestV, "bandwidth", 0);
            for (int i = 1; i < videos.length(); i++) {
                JSONObject v = videos.getJSONObject(i);
                long bw = optLong(v, "bandwidth", 0);
                if (bw > bestBwV) { bestBwV = bw; bestV = v; }
            }
            // 选最高码率音频
            JSONObject bestA = audios.getJSONObject(0);
            long bestBwA = optLong(bestA, "bandwidth", 0);
            for (int i = 1; i < audios.length(); i++) {
                JSONObject a = audios.getJSONObject(i);
                long bw = optLong(a, "bandwidth", 0);
                if (bw > bestBwA) { bestBwA = bw; bestA = a; }
            }

            info.put("videoUrl", optString(bestV, "baseUrl", "base_url"));
            info.put("audioUrl", optString(bestA, "baseUrl", "base_url"));
            jlog(taskId, "  播放 API 提取成功 videoBw=" + bestBwV + " audioBw=" + bestBwA);
            return true;
        } catch (Exception e) {
            jlog(taskId, "  播放 API 异常: " + e.getMessage(), true);
            return false;
        }
    }

    // ═══════════════════════════════════════════════
    // 内部工具
    // ═══════════════════════════════════════════════

    private HttpURLConnection openConnection(String url) throws Exception {
        return openConnectionInternal(url, true);
    }

    /**
     * 手动跟重定向链，每跳积累 cookie。
     * @param readBody true=返回 [html, finalUrl]；false=返回 [null, finalUrl] 只跟跳不读 body
     * @return [html_or_null, finalUrl] 或 null 表示出错
     */
    private String[] followRedirectChain(String url, String taskId, boolean readBody) {
        try {
            String currentUrl = url;
            int maxHops = 10;
            for (int hop = 0; hop < maxHops; hop++) {
                HttpURLConnection conn = openConnectionNoFollow(currentUrl);
                int code = conn.getResponseCode();
                String setCookie = conn.getHeaderField("Set-Cookie");
                if (setCookie != null && !setCookie.isEmpty()) {
                    mergeCookies(setCookie);
                }
                String shortUrl = currentUrl.length() > 80 ? currentUrl.substring(0, 80) + "..." : currentUrl;
                jlog(taskId, "  hop" + hop + " " + shortUrl + " code=" + code
                        + (setCookie != null && !setCookie.isEmpty() ? " +cookie" : ""));

                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (loc != null && !loc.isEmpty()) {
                        if (!loc.startsWith("http")) {
                            java.net.URL base = new java.net.URL(currentUrl);
                            currentUrl = new java.net.URL(base, loc).toString();
                        } else {
                            currentUrl = loc;
                        }
                        continue;
                    }
                }

                if (code >= 200 && code < 300) {
                    String finalUrl = conn.getURL().toString();
                    String body = null;
                    if (readBody) {
                        body = readStream(conn.getInputStream());
                    }
                    conn.disconnect();
                    return new String[] { body, finalUrl };
                }

                conn.disconnect();
                jlog(taskId, "  HTTP " + code, true);
                return null;
            }
            jlog(taskId, "  重定向次数超限", true);
            return null;
        } catch (Exception e) {
            jlog(taskId, "  跟跳异常: " + e.getMessage(), true);
            return null;
        }
    }

    private HttpURLConnection openConnectionNoFollow(String url) throws Exception {
        return openConnectionInternal(url, false);
    }

    private HttpURLConnection openConnectionInternal(String url, boolean followRedirects) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setInstanceFollowRedirects(followRedirects);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        conn.setRequestProperty("Referer", "https://www.bilibili.com/");
        if (!cookieStore.isEmpty()) {
            conn.setRequestProperty("Cookie", cookieStore);
        }
        return conn;
    }

    /**
     * 用 ffmpeg 将音频转 MP3。尝试多个可能的 ffmpeg 路径。
     */
    private boolean copyFile(String src, String dest) {
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(src);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(dest);
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) != -1) fos.write(buf, 0, len);
            fos.close(); fis.close();
            return true;
        } catch (Exception e) {
            jlog("copyFile", "复制失败 src=" + src + " dest=" + dest + " error=" + e.getMessage(), true);
            return false;
        }
    }

    private int getQualityCode(String key) {
        try {
            int idx = Integer.parseInt(key) - 1;
            if (idx >= 0 && idx < QUALITY_MAP.length) return QUALITY_MAP[idx][0];
        } catch (NumberFormatException ignored) {}
        return 80; // 默认 1080P
    }

    private static long optLong(JSONObject obj, String key, long fallback) {
        try {
            if (obj.has(key)) return obj.getLong(key);
        } catch (Exception ignored) {}
        return fallback;
    }

    /**
     * 在 html 中查找 key 后紧跟 = 或 : 再跟 { 的 JSON 赋值，数括号提取。
     * 跳过 if(key)、key==null 等非赋值用法。
     */
    private static String extractJsonAfterAssignment(String html, String key) {
        int searchFrom = 0;
        while (searchFrom < html.length()) {
            int idx = html.indexOf(key, searchFrom);
            if (idx < 0) return null;
            // 跳过 key 本身，找后面第一个非空白字符
            int after = idx + key.length();
            while (after < html.length() && Character.isWhitespace(html.charAt(after))) after++;
            // 必须是 = 或 : 才认为是赋值
            if (after >= html.length()) { searchFrom = idx + 1; continue; }
            char sep = html.charAt(after);
            if (sep != '=' && sep != ':') { searchFrom = idx + 1; continue; }
            // 找到赋值符后找 {
            int brace = html.indexOf('{', after + 1);
            if (brace < 0) { searchFrom = idx + 1; continue; }
            // 确保 { 和赋值符之间只有空白/换行
            boolean onlyWhitespace = true;
            for (int i = after + 1; i < brace; i++) {
                if (!Character.isWhitespace(html.charAt(i))) { onlyWhitespace = false; break; }
            }
            if (!onlyWhitespace) { searchFrom = idx + 1; continue; }
            // 数括号
            int depth = 0;
            int end = -1;
            for (int i = brace; i < html.length(); i++) {
                char c = html.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) { end = i + 1; break; }
                }
            }
            if (end < 0) { searchFrom = idx + 1; continue; }
            String json = html.substring(brace, end).trim();
            if (json.endsWith(";")) json = json.substring(0, json.length() - 1).trim();
            return json;
        }
        return null;
    }

    private static String optString(JSONObject obj, String... keys) {
        for (String k : keys) {
            String v = obj.optString(k, null);
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }

    private void callJS(String function, String args) {
        mainHandler.post(() -> {
            webView.evaluateJavascript(function + "(" + args + ")", null);
        });
    }

    // 通知系统扫描媒体文件，使其在相册/文件管理器中可见
    private void scanMediaFile(String path) {
        try {
            MediaScannerConnection.scanFile(context, new String[]{path}, null, null);
            jlog("system", "媒体扫描已触发: " + path);
        } catch (Exception e) {
            // 扫描失败不影响主流程
        }
    }

    // 发送日志到 JS 面板（带 taskId 前缀）
    private void jlog(String taskId, String msg) {
        jlog(taskId, msg, false);
    }

    private void jlog(String taskId, String msg, boolean isErr) {
        String text = "[" + (taskId.length() > 12 ? taskId.substring(taskId.length() - 12) : taskId) + "] " + msg;
        mainHandler.post(() -> {
            webView.evaluateJavascript(
                "if(typeof log==='function')log(" + jsonString(text) + "," + isErr + ")", null);
        });
    }

    private static String jsonString(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }

    /**
     * 读取响应中所有 Set-Cookie 头并合并。
     */
    private void mergeAllCookies(HttpURLConnection conn) {
        // HttpURLConnection 可能返回多个同名字段，用 getHeaderFields 获取全部
        java.util.Map<String, java.util.List<String>> headers = conn.getHeaderFields();
        for (java.util.Map.Entry<String, java.util.List<String>> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase("Set-Cookie")) {
                for (String value : e.getValue()) {
                    mergeCookies(value);
                }
            }
        }
    }

    private void mergeCookies(String setCookie) {
        if (setCookie == null || setCookie.isEmpty()) return;
        for (String part : setCookie.split(";")) {
            part = part.trim();
            if (!part.contains("=")) continue;
            String low = part.toLowerCase();
            if (low.startsWith("path") || low.startsWith("domain") || low.startsWith("expires")
                    || low.startsWith("max-age") || low.startsWith("httponly")
                    || low.startsWith("secure") || low.startsWith("samesite")) continue;
            String key = part.split("=")[0].trim();
            String value = part.substring(part.indexOf('=') + 1).trim();
            // 手动替换，避免 key 中的正则特殊字符导致 PatternSyntaxException
            String prefix = key + "=";
            int idx = cookieStore.indexOf(prefix);
            if (idx >= 0) {
                int end = cookieStore.indexOf(";", idx);
                if (end < 0) end = cookieStore.length();
                cookieStore = cookieStore.substring(0, idx) + prefix + value + cookieStore.substring(end);
            } else {
                if (!cookieStore.isEmpty()) cookieStore += "; ";
                cookieStore += prefix + value;
            }
        }
    }

    private String readStream(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int len;
        while ((len = is.read(buf)) != -1) baos.write(buf, 0, len);
        is.close();
        return baos.toString("UTF-8");
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
