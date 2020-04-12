package me.devsaki.hentoid.activities;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import androidx.appcompat.app.AppCompatActivity;

import com.lmntrx.android.library.livin.missme.ProgressDialog;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;

import io.reactivex.Completable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;
import me.devsaki.hentoid.BuildConfig;
import me.devsaki.hentoid.HentoidApp;
import me.devsaki.hentoid.R;
import me.devsaki.hentoid.activities.bundles.ImportActivityBundle;
import me.devsaki.hentoid.database.ObjectBoxDB;
import me.devsaki.hentoid.events.AppUpdatedEvent;
import me.devsaki.hentoid.events.ImportEvent;
import me.devsaki.hentoid.notification.import_.ImportNotificationChannel;
import me.devsaki.hentoid.services.ImportService;
import me.devsaki.hentoid.util.FileHelper;
import me.devsaki.hentoid.util.Helper;
import me.devsaki.hentoid.util.Preferences;
import me.devsaki.hentoid.util.ThemeHelper;
import me.devsaki.hentoid.views.NestedScrollWebView;
import timber.log.Timber;

/**
 * Displays a Splash while starting up.
 * <p>
 * Nothing but a splash/activity selection should be defined here.
 */
public class SplashActivity extends AppCompatActivity {

    private ProgressDialog progressDialog;
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private ObjectBoxDB db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Timber.d("Splash / Init");

        EventBus.getDefault().register(this);
        // Pre-processing on app update
        if (Preferences.getLastKnownAppVersionCode() < BuildConfig.VERSION_CODE) {
            Timber.d("Splash / Update detected");
            onAppUpdated();
            Preferences.setLastKnownAppVersionCode(BuildConfig.VERSION_CODE);
        } else {
            followStartupFlow();
        }
    }

    private void followStartupFlow() {
        Timber.d("Splash / Startup flow initiated");
        if (Preferences.isFirstRun()) {
            goToActivity(new Intent(this, IntroActivity.class));
        } else if (hasToMigrateAndroid10()) {
            runAndroid10Migration();
        } else {
            goToLibraryActivity();
        }
    }

    /**
     * Test if the ImageFiles stored in the DB have their URI's filled
     * If not, it indicates the collection has not been updated to fit the Android 10 I/O update
     *
     * @return True if a migration has to happen; false if not
     */
    private boolean hasToMigrateAndroid10() {
        db = ObjectBoxDB.getInstance(this);
        long imagesKO = db.countDownloadedImagesWithoutUri();
        Timber.i(">> count10 %s", imagesKO);
        return imagesKO > 0;
    }

    private void runAndroid10Migration() {
        // Prior Library found, drop and recreate db
        cleanUpDB();
        // Send results to scan
        progressDialog = new ProgressDialog(this);
        progressDialog.setCancelable(false);
        progressDialog.setIndeterminate(false);
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setMax(0);
        progressDialog.setColor(ThemeHelper.getColor(this, R.color.secondary_light));
        progressDialog.setTextColor(R.color.white_opacity_87);
        progressDialog.setMessage(this.getString(R.string.updating_please_wait));
        progressDialog.show();

        ImportNotificationChannel.init(this);
        Intent intent = ImportService.makeIntent(this);

        ImportActivityBundle.Builder builder = new ImportActivityBundle.Builder();
        builder.setRefreshRename(false);
        builder.setRefreshCleanAbsent(false);
        builder.setRefreshCleanNoImages(false);
        builder.setRefreshCleanUnreadable(false);
        intent.putExtras(builder.getBundle());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onImportEvent(ImportEvent event) {
        if (ImportEvent.EV_PROGRESS == event.eventType) {
            progressDialog.setMax(event.booksTotal);
            progressDialog.setProgress(event.booksOK + event.booksKO);
        } else if (ImportEvent.EV_COMPLETE == event.eventType) {
            if (progressDialog != null) progressDialog.dismiss();
            goToLibraryActivity();
        }
    }

    private void cleanUpDB() {
        Timber.d("Cleaning up DB.");
        Context context = HentoidApp.getInstance();
        ObjectBoxDB db = ObjectBoxDB.getInstance(context);
        db.deleteAllBooks();
    }

    @Override
    protected void onDestroy() {
        EventBus.getDefault().unregister(this);
        compositeDisposable.clear();

        super.onDestroy();
    }

    private void goToActivity(Intent intent) {
        startActivity(intent);
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        finish();
    }

    private void goToLibraryActivity() {
        Timber.d("Splash / Launch library");
        Intent intent = new Intent(this, LibraryActivity.class);
        intent = UnlockActivity.wrapIntent(this, intent);
        goToActivity(intent);
    }

    private void onAppUpdated() {
        compositeDisposable.add(
                Completable.fromRunnable(this::doOnAppUpdated)
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(
                                this::followStartupFlow,
                                Timber::e)
        );
    }

    private void doOnAppUpdated() {
        Timber.d("Splash / Start update pre-processing");
        // Clear webview cache (needs to execute inside the activity's Looper)
        Timber.d("Splash / Clearing webview cache");
        Handler h = new Handler(Looper.getMainLooper());
        h.post(() -> {
            WebView webView;
            try {
                webView = new NestedScrollWebView(this);
            } catch (Resources.NotFoundException e) {
                // Some older devices can crash when instantiating a WebView, due to a Resources$NotFoundException
                // Creating with the application Context fixes this, but is not generally recommended for view creation
                webView = new NestedScrollWebView(Helper.getFixedContext(this));
            }
            webView.clearCache(true);
        });

        // Clear app cache
        Timber.d("Splash / Clearing app cache");
        try {
            File dir = this.getCacheDir();
            FileHelper.removeFile(dir);
        } catch (Exception e) {
            Timber.e(e, "Error when clearing app cache upon update");
        }

        EventBus.getDefault().postSticky(new AppUpdatedEvent());
        Timber.d("Splash / Update pre-processing complete");
    }
}
