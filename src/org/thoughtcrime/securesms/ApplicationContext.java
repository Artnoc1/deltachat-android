/*
 * Copyright (C) 2013 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms;

import android.annotation.SuppressLint;
import android.arch.lifecycle.DefaultLifecycleObserver;
import android.arch.lifecycle.LifecycleOwner;
import android.arch.lifecycle.ProcessLifecycleOwner;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.multidex.MultiDexApplication;
import android.util.Log;

import com.b44t.messenger.DcContext;
import com.google.android.gms.security.ProviderInstaller;

import org.thoughtcrime.securesms.crypto.PRNGFixes;
import org.thoughtcrime.securesms.dependencies.AxolotlStorageModule;
import org.thoughtcrime.securesms.dependencies.InjectableType;
import org.thoughtcrime.securesms.dependencies.SignalCommunicationModule;
import org.thoughtcrime.securesms.jobmanager.JobManager;
import org.thoughtcrime.securesms.jobmanager.dependencies.DependencyInjector;
import org.thoughtcrime.securesms.jobmanager.persistence.JavaJobSerializer;
import org.thoughtcrime.securesms.jobmanager.requirements.NetworkRequirementProvider;
import org.thoughtcrime.securesms.jobs.CreateSignedPreKeyJob;
import org.thoughtcrime.securesms.jobs.GcmRefreshJob;
import org.thoughtcrime.securesms.jobs.MultiDeviceContactUpdateJob;
import org.thoughtcrime.securesms.jobs.requirements.MasterSecretRequirementProvider;
import org.thoughtcrime.securesms.jobs.requirements.ServiceRequirementProvider;
import org.thoughtcrime.securesms.jobs.requirements.SqlCipherMigrationRequirementProvider;
import org.thoughtcrime.securesms.push.SignalServiceNetworkAccess;
import org.thoughtcrime.securesms.service.DirectoryRefreshListener;
import org.thoughtcrime.securesms.service.ExpiringMessageManager;
import org.thoughtcrime.securesms.service.LocalBackupListener;
import org.thoughtcrime.securesms.service.RotateSignedPreKeyListener;
import org.thoughtcrime.securesms.service.UpdateApkRefreshListener;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.PeerConnectionFactory.InitializationOptions;
import org.webrtc.voiceengine.WebRtcAudioManager;
import org.webrtc.voiceengine.WebRtcAudioUtils;
import org.whispersystems.libsignal.logging.SignalProtocolLoggerProvider;
import org.whispersystems.libsignal.util.AndroidSignalProtocolLogger;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import dagger.ObjectGraph;

/**
 * Will be called once when the TextSecure process is created.
 *
 * We're using this as an insertion point to patch up the Android PRNG disaster,
 * to initialize the job manager, and to check for GCM registration freshness.
 *
 * @author Moxie Marlinspike
 */
public class ApplicationContext extends MultiDexApplication implements DependencyInjector, DefaultLifecycleObserver {

  private static final String TAG = ApplicationContext.class.getName();

  private ExpiringMessageManager expiringMessageManager;
  private JobManager             jobManager;
  private ObjectGraph            objectGraph;

  private volatile boolean isAppVisible;

  public static ApplicationContext getInstance(Context context) {
    return (ApplicationContext)context.getApplicationContext();
  }

  @Override
  public void onCreate() {
    super.onCreate();

    System.loadLibrary("native-utils");
    DcContext.DcCallback(0, 0, 0); // do not remove this call; this makes sure, the function is not removed from build or warnings are printed!
    DcContext.init();

    initializeRandomNumberFix();
    initializeLogging();
    initializeDependencyInjection();
    initializeJobManager();
    initializeExpiringMessageManager();
    initializeGcmCheck();
    initializeSignedPreKeyCheck();
    initializePeriodicTasks();
    initializeCircumvention();
    initializeWebRtc();
    ProcessLifecycleOwner.get().getLifecycle().addObserver(this);
  }

  @Override
  public void onStart(@NonNull LifecycleOwner owner) {
    isAppVisible = true;
    Log.i(TAG, "App is now visible.");

    executePendingContactSync();
  }

  @Override
  public void onStop(@NonNull LifecycleOwner owner) {
    isAppVisible = false;
    Log.i(TAG, "App is no longer visible.");
  }

  @Override
  public void injectDependencies(Object object) {
    if (object instanceof InjectableType) {
      objectGraph.inject(object);
    }
  }

  public JobManager getJobManager() {
    return jobManager;
  }

  public ExpiringMessageManager getExpiringMessageManager() {
    return expiringMessageManager;
  }

  public boolean isAppVisible() {
    return isAppVisible;
  }

  private void initializeRandomNumberFix() {
    PRNGFixes.apply();
  }

  private void initializeLogging() {
    SignalProtocolLoggerProvider.setProvider(new AndroidSignalProtocolLogger());
  }

  private void initializeJobManager() {
    this.jobManager = JobManager.newBuilder(this)
                                .withName("TextSecureJobs")
                                .withDependencyInjector(this)
                                .withJobSerializer(new JavaJobSerializer())
                                .withRequirementProviders(new MasterSecretRequirementProvider(this),
                                                          new ServiceRequirementProvider(this),
                                                          new NetworkRequirementProvider(this),
                                                          new SqlCipherMigrationRequirementProvider())
                                .withConsumerThreads(5)
                                .build();
  }

  private void initializeDependencyInjection() {
    this.objectGraph = ObjectGraph.create(new SignalCommunicationModule(this, new SignalServiceNetworkAccess(this)),
                                          new AxolotlStorageModule(this));
  }

  private void initializeGcmCheck() {
    if (TextSecurePreferences.isPushRegistered(this)) {
      long nextSetTime = TextSecurePreferences.getGcmRegistrationIdLastSetTime(this) + TimeUnit.HOURS.toMillis(6);

      if (TextSecurePreferences.getGcmRegistrationId(this) == null || nextSetTime <= System.currentTimeMillis()) {
        this.jobManager.add(new GcmRefreshJob(this));
      }
    }
  }

  private void initializeSignedPreKeyCheck() {
    if (!TextSecurePreferences.isSignedPreKeyRegistered(this)) {
      jobManager.add(new CreateSignedPreKeyJob(this));
    }
  }

  private void initializeExpiringMessageManager() {
    this.expiringMessageManager = new ExpiringMessageManager(this);
  }

  private void initializePeriodicTasks() {
    RotateSignedPreKeyListener.schedule(this);
    DirectoryRefreshListener.schedule(this);
    LocalBackupListener.schedule(this);

    if (BuildConfig.PLAY_STORE_DISABLED) {
      UpdateApkRefreshListener.schedule(this);
    }
  }

  private void initializeWebRtc() {
    try {
      Set<String> HARDWARE_AEC_BLACKLIST = new HashSet<String>() {{
        add("Pixel");
        add("Pixel XL");
        add("Moto G5");
        add("Moto G (5S) Plus");
        add("Moto G4");
        add("TA-1053");
        add("Mi A1");
        add("E5823"); // Sony z5 compact
      }};

      Set<String> OPEN_SL_ES_WHITELIST = new HashSet<String>() {{
        add("Pixel");
        add("Pixel XL");
      }};

      if (HARDWARE_AEC_BLACKLIST.contains(Build.MODEL)) {
        WebRtcAudioUtils.setWebRtcBasedAcousticEchoCanceler(true);
      }

      if (!OPEN_SL_ES_WHITELIST.contains(Build.MODEL)) {
        WebRtcAudioManager.setBlacklistDeviceForOpenSLESUsage(true);
      }

      PeerConnectionFactory.initialize(InitializationOptions.builder(this)
                                                            .setEnableVideoHwAcceleration(true)
                                                            .createInitializationOptions());
    } catch (UnsatisfiedLinkError e) {
      Log.w(TAG, e);
    }
  }

  @SuppressLint("StaticFieldLeak")
  private void initializeCircumvention() {
    AsyncTask<Void, Void, Void> task = new AsyncTask<Void, Void, Void>() {
      @Override
      protected Void doInBackground(Void... params) {
        if (new SignalServiceNetworkAccess(ApplicationContext.this).isCensored(ApplicationContext.this)) {
          try {
            ProviderInstaller.installIfNeeded(ApplicationContext.this);
          } catch (Throwable t) {
            Log.w(TAG, t);
          }
        }
        return null;
      }
    };

    task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
  }


  private void executePendingContactSync() {
    if (TextSecurePreferences.needsFullContactSync(this)) {
      ApplicationContext.getInstance(this).getJobManager().add(new MultiDeviceContactUpdateJob(this, true));
    }
  }
}