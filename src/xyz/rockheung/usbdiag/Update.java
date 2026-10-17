package xyz.rockheung.usbdiag;

import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub Releases 기반 OTA.
 * latest release 의 usbdiag-<versionCode>.apk 가 지금보다 높으면 받아서 PackageInstaller 로 설치한다.
 * 진위 검증은 Android 가 한다: 서명 키가 다르거나 버전이 낮으면 설치가 거부된다.
 * device owner 면 무인 설치, 아니면 TV 에 확인 창이 뜬다.
 */
public final class Update {
    static final String REPO = "Rockheung/mibox-kvm";
    static final Pattern ASSET = Pattern.compile("usbdiag-(\\d+)\\.apk");
    static final String ACTION_RESULT = "xyz.rockheung.usbdiag.INSTALL_RESULT";
    private static volatile long busySince; // 확인 창이 무시되면 30분 뒤 다시 시도한다

    static int myVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    static boolean deviceOwner(Context c) {
        return c.getSystemService(DevicePolicyManager.class).isDeviceOwnerApp(c.getPackageName());
    }

    static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setRequestProperty("User-Agent", "usbdiag-ota");
        h.setConnectTimeout(15000);
        h.setReadTimeout(30000);
        return h;
    }

    /** 새 버전이 있으면 설치를 시작한다. 결과 한 줄을 돌려준다. */
    static synchronized String check(Context c) {
        if (System.currentTimeMillis() - busySince < 30 * 60_000L) return "update already in progress";
        try {
            HttpURLConnection h = open("https://api.github.com/repos/" + REPO + "/releases/latest");
            if (h.getResponseCode() != 200) return "github api " + h.getResponseCode();
            JSONObject rel = new JSONObject(readAll(h.getInputStream()));
            JSONArray assets = rel.getJSONArray("assets");
            int mine = myVersion(c), best = 0;
            String url = null;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                Matcher m = ASSET.matcher(a.getString("name"));
                if (m.matches() && Integer.parseInt(m.group(1)) > best) {
                    best = Integer.parseInt(m.group(1));
                    url = a.getString("browser_download_url");
                }
            }
            if (url == null) return "no apk asset in " + rel.optString("tag_name");
            if (best <= mine) return "up to date (installed " + mine + ", latest " + best + ")";
            busySince = System.currentTimeMillis();
            install(c, url, best);
            return "installing " + best + " over " + mine + (deviceOwner(c) ? " (silent)" : " (confirm on TV)");
        } catch (Exception e) {
            busySince = 0;
            return "update check failed: " + e;
        }
    }

    static void install(Context c, String url, int version) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(c.getPackageName());
        int id = pi.createSession(p);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            HttpURLConnection h = open(url); // github → objects.githubusercontent.com 리다이렉트는 https 끼리라 따라간다
            try (InputStream in = h.getInputStream(); OutputStream out = s.openWrite("base.apk", 0, -1)) {
                byte[] buf = new byte[64 << 10];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent i = new Intent(ACTION_RESULT).setClass(c, Result.class).putExtra("version", version);
            s.commit(PendingIntent.getBroadcast(c, id, i, PendingIntent.FLAG_UPDATE_CURRENT).getIntentSender());
        } catch (Exception e) {
            pi.abandonSession(id);
            throw e;
        }
        Main.log("ota: downloaded " + version + ", committed session " + id);
    }

    static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
        in.close();
        return o.toString("UTF-8");
    }

    /** PackageInstaller 결과. 사용자 확인이 필요하면 확인 창을 띄운다. */
    public static final class Result extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                Main.log("ota: waiting for confirmation on TV");
                return;
            }
            busySince = 0;
            Main.log("ota: status=" + st + " " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        }
    }

    /** device owner 로 지정될 수 있게만 해 둔다. 지정은 adb 로 사람이 한다. */
    public static final class Admin extends android.app.admin.DeviceAdminReceiver {}
}
