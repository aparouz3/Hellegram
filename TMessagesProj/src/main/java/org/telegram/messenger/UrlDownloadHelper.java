package org.telegram.messenger;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UrlDownloadHelper {

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(https?://(?:www\.)?(?:youtube\.com/(?:watch\?v=|shorts/)|youtu\.be/|x\.com/|twitter\.com/)[^\s]+)",
            Pattern.CASE_INSENSITIVE);

    public static String findUrl(String text) {
        if (text == null) return null;
        Matcher m = URL_PATTERN.matcher(text);
        if (m.find()) return m.group(1);
        return null;
    }

    public static boolean isSupportedUrl(String text) {
        return findUrl(text) != null;
    }

    public interface Callback {
        void onDone(File file);
        void onError(Exception e);
    }

    public static void download(Context context, String urlStr, Callback cb) {
        new Thread(() -> {
            try {
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.connect();
                InputStream in = conn.getInputStream();
                File dir = new File(context.getCacheDir(), "url_downloads");
                dir.mkdirs();
                String name = Uri.parse(urlStr).getLastPathSegment();
                if (name == null || name.isEmpty()) name = "download";
                File out = new File(dir, name);
                FileOutputStream fos = new FileOutputStream(out);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
                fos.close();
                in.close();
                conn.disconnect();
                cb.onDone(out);
            } catch (Exception e) {
                cb.onError(e);
            }
        }).start();
    }
}
