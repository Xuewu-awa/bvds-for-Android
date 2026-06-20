package com.xuewu.bvds;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 简单的文件下载工具。
 */
public class DownloadManager {

    public static long download(String url, String destPath, String cookieStore) {
        try {
            File destFile = new File(destPath);
            File parent = destFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Referer", "https://www.bilibili.com/");
            if (cookieStore != null && !cookieStore.isEmpty()) {
                conn.setRequestProperty("Cookie", cookieStore);
            }

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return -1;
            }

            InputStream is = conn.getInputStream();
            FileOutputStream fos = new FileOutputStream(destFile);

            byte[] buf = new byte[8192];
            long total = 0;
            int len;
            while ((len = is.read(buf)) != -1) {
                fos.write(buf, 0, len);
                total += len;
            }

            fos.close();
            is.close();
            return total;

        } catch (Exception e) {
            return -1;
        }
    }

    public static String extractFilename(String url) {
        String name = url.substring(url.lastIndexOf('/') + 1);
        int q = name.indexOf('?');
        if (q > 0) name = name.substring(0, q);
        return name;
    }
}
