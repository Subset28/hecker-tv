package com.hecking.tvlauncher;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Installs an APK the user chooses from the .apk files in storage.
 *
 * Nothing is downloaded or suggested here. The user picks a file from the list,
 * sees the app name, and confirms in Android's install screen. Android also asks
 * the user to allow "Install unknown apps" for this launcher the first time.
 */
public class InstallerActivity extends Activity {

    static final String ACTION_RESULT = "com.hecking.tvlauncher.INSTALL_RESULT";
    private static final int REQ_PERM = 42;

    private TextView status;
    private LinearLayout fileList;

    private final BroadcastReceiver result = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (code == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) startActivity(confirm);
                return;
            }
            String msg = (code == PackageInstaller.STATUS_SUCCESS)
                    ? "Installed"
                    : "Not installed: " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            status.setText(msg);
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF000000);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(48), dp(48), dp(48), dp(48));

        TextView title = text("Install an app file", 36, 0xFFF5F5F7);
        TextView help = text("Pick an .apk file from storage. Android will show the app name and its permissions, and you confirm before anything installs. Only install apps from sources you trust.", 18, 0xFF8E8E93);
        status = text("", 18, 0xFFF5F5F7);
        fileList = new LinearLayout(this);
        fileList.setOrientation(LinearLayout.VERTICAL);
        fileList.setGravity(Gravity.CENTER_HORIZONTAL);

        col.addView(title);
        col.addView(help);
        col.addView(fileList);
        col.addView(status);
        scroll.addView(col);
        setContentView(scroll);

        IntentFilter resultFilter = new IntentFilter(ACTION_RESULT);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            // our own result broadcast only: never exported (required on Android 13+)
            registerReceiver(result, resultFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(result, resultFilter);
        }
        // opened with a file already (for example from a file manager): install it straight away
        if (getIntent() != null && getIntent().getData() != null) {
            install(getIntent().getData(), null);
        } else {
            loadFiles();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // back from the All files access screen: reload only when there is nothing listed yet,
        // so an install result message is not wiped
        if (fileList != null && (!hasStorageAccess() || fileList.getChildCount() == 0)) loadFiles();
    }

    private boolean hasStorageAccess() {
        if (android.os.Build.VERSION.SDK_INT >= 30) return android.os.Environment.isExternalStorageManager();
        return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /** Lists .apk files in storage. Asks for storage access first if it was not given. */
    private void loadFiles() {
        fileList.removeAllViews();
        if (!hasStorageAccess()) {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                // Android 11+ hides .apk files from apps unless the user grants "All files access"
                status.setText("To list .apk files, allow All files access for TV Home, then come back here.");
                fileList.addView(button("Open All files access", v -> openAllFilesAccess()));
            } else {
                // Android 9-10: ordinary storage permission is enough
                status.setText("Allow storage access to list .apk files.");
                fileList.addView(button("Allow storage access", v -> requestPermissions(
                        new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM)));
            }
            return;
        }
        Uri files = MediaStore.Files.getContentUri("external");
        try (Cursor c = getContentResolver().query(files,
                new String[]{MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.SIZE},
                MediaStore.Files.FileColumns.DISPLAY_NAME + " LIKE ?", new String[]{"%.apk"},
                MediaStore.Files.FileColumns.DISPLAY_NAME + " COLLATE NOCASE")) {
            if (c == null || c.getCount() == 0) {
                status.setText("No .apk files found. Put the file in the Download folder, then reopen this screen.");
                return;
            }
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String name = c.getString(1);
                long size = c.getLong(2);
                Uri uri = Uri.withAppendedPath(files, String.valueOf(id));
                String label = name + "  (" + (size / (1024 * 1024)) + " MB)";
                fileList.addView(button(label, v -> install(uri, name)));
            }
        } catch (SecurityException e) {
            status.setText("Storage access was refused, so no files can be listed.");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM) loadFiles();
    }

    private void openAllFilesAccess() {
        Intent manage = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData(Uri.parse("package:" + getPackageName()));
        try {
            startActivity(manage);
        } catch (android.content.ActivityNotFoundException e) {
            // the screen exists from Android 11; fall back to the app's details page
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + getPackageName())));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(result); } catch (IllegalArgumentException ignored) { }
    }

    private void install(Uri apk, String name) {
        PackageManager pm = getPackageManager();
        String shown = name != null ? name : displayName(apk);
        // show what is about to be installed before it is committed
        status.setText("Preparing " + shown + " ...");
        new Thread(() -> {
            PackageInstaller.Session session = null;
            try {
                PackageInstaller installer = pm.getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                int sessionId = installer.createSession(params);
                session = installer.openSession(sessionId);
                try (InputStream in = getContentResolver().openInputStream(apk);
                     OutputStream out = session.openWrite("base.apk", 0, -1)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    session.fsync(out);
                }
                Intent done = new Intent(ACTION_RESULT).setPackage(getPackageName());
                PendingIntent pi = PendingIntent.getBroadcast(this, sessionId, done,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                session.commit(pi.getIntentSender());
                session.close();
                runOnUiThread(() -> status.setText("Installing " + shown + " ..."));
            } catch (Exception e) {
                // do not leave a half-written session behind
                if (session != null) session.abandon();
                runOnUiThread(() -> status.setText("Could not read this file: " + e.getMessage()));
            }
        }, "apk-install").start();
    }

    private String displayName(Uri uri) {
        String last = uri.getLastPathSegment();
        return last == null ? "this file" : last;
    }

    private TextView text(String s, int size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(12), 0, dp(12));
        return t;
    }

    private View button(String label, View.OnClickListener action) {
        TextView b = text(label, 22, 0xFFF5F5F7);
        b.setBackgroundColor(0xFF1C1C1E);
        b.setPadding(dp(32), dp(18), dp(32), dp(18));
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(action);
        b.setOnFocusChangeListener((v, f) -> b.setBackgroundColor(f ? 0xFF3A3A3C : 0xFF1C1C1E));
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
