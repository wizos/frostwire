/*
 * Created by Angel Leon (@gubatron), Alden Torres (aldenml)
 * Copyright (c) 2011-2017, FrostWire(R). All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.frostwire.android.gui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.widget.RemoteViews;

import com.frostwire.android.R;
import com.frostwire.android.core.ConfigurationManager;
import com.frostwire.android.core.Constants;
import com.frostwire.android.gui.activities.MainActivity;
import com.frostwire.android.gui.transfers.TransferManager;
import com.frostwire.android.gui.util.UIUtils;
import com.frostwire.android.gui.views.TimerObserver;
import com.frostwire.android.gui.views.TimerService;
import com.frostwire.android.gui.views.TimerSubscription;
import com.frostwire.android.util.Asyncs;
import com.frostwire.util.Logger;

import androidx.core.app.NotificationCompat;

import static com.frostwire.android.util.Asyncs.async;

/**
 * @author gubatron
 * @author aldenml
 */
public final class NotificationUpdateDaemon implements TimerObserver {

    private static final Logger LOG = Logger.getLogger(NotificationUpdateDaemon.class);
    private static final int FROSTWIRE_STATUS_NOTIFICATION_UPDATE_INTERVAL_IN_SECS = 5;

    private final Context mParentContext;
    private static TimerSubscription mTimerSubscription;

    private RemoteViews notificationViews;
    private Notification notificationObject;

    public NotificationUpdateDaemon(Context parentContext) {
        mParentContext = parentContext;
        setupNotification();
    }

    public void start() {
        if (mTimerSubscription != null) {
            LOG.debug("Stopping before (re)starting permanent notification demon");
            if (mTimerSubscription.isSubscribed()) {
                mTimerSubscription.unsubscribe();
            }
            TimerService.reSubscribe(this, mTimerSubscription, FROSTWIRE_STATUS_NOTIFICATION_UPDATE_INTERVAL_IN_SECS);
            return;
        }
        mTimerSubscription = TimerService.subscribe(this, FROSTWIRE_STATUS_NOTIFICATION_UPDATE_INTERVAL_IN_SECS);
    }

    public void stop() {
        LOG.debug("Stopping permanent NotificationUpdateDaemon");

        if (mTimerSubscription != null) {
            LOG.debug("stop() mTimerSubscription@" + mTimerSubscription.hashCode() + ".unsubscribe()");
            mTimerSubscription.unsubscribe();
        } else {
            LOG.debug("stop() mTimerSubscription was null!!!!!, can't unsubscribe!!!");
        }

        NotificationManager manager = (NotificationManager) mParentContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            try {
                manager.cancel(Constants.NOTIFICATION_FROSTWIRE_STATUS);
            } catch (SecurityException t) {
                LOG.warn(t.getMessage(), t);
                // possible java.lang.SecurityException
            }
        }
    }

    private void updateTransfersStatusNotification() {
        if (!ConfigurationManager.instance().getBoolean(Constants.PREF_KEY_GUI_ENABLE_PERMANENT_STATUS_NOTIFICATION)) {
            LOG.info("updateTransfersStatusNotification() aborted, PREF_KEY_GUI_ENABLE_PERMANENT_STATUS_NOTIFICATION = false");
            return;
        }

        if (notificationViews == null || notificationObject == null) {
            LOG.warn("updateTransfersStatusNotification() Notification views or object are null, review your logic");
            return;
        }

        // number of uploads (seeding) and downloads
        TransferManager transferManager;

        try {
            transferManager = TransferManager.instance();
        } catch (IllegalStateException btEngineNotReadyException) {
            LOG.error("updateTransfersStatusNotification() " + btEngineNotReadyException.getMessage(), btEngineNotReadyException);
            return;
        }

        if (transferManager != null) {
            int downloads = transferManager.getActiveDownloads();
            int uploads = transferManager.getActiveUploads();
            if (downloads == 0 && uploads == 0) {
                LOG.info("updateTransfersStatusNotification() no active transfers, cancelling notification");
                NotificationManager manager = (NotificationManager) mParentContext.getSystemService(Context.NOTIFICATION_SERVICE);
                if (manager != null) {
                    try {
                        manager.cancel(Constants.NOTIFICATION_FROSTWIRE_STATUS);
                    } catch (SecurityException ignored) {
                        // possible java.lang.SecurityException
                    }
                }
                return; // quick return
            }
            //  format strings
            String sDown = UIUtils.rate2speed(transferManager.getDownloadsBandwidth() / 1024);
            String sUp = UIUtils.rate2speed(transferManager.getUploadsBandwidth() / 1024);
            // Transfers status.
            try {
                notificationViews.setTextViewText(R.id.view_permanent_status_text_downloads, downloads + " @ " + sDown);
                notificationViews.setTextViewText(R.id.view_permanent_status_text_uploads, uploads + " @ " + sUp);
            } catch (Throwable t) {
                LOG.error("updateTransfersStatusNotification() error getting Remote notification views: " + t.getMessage(), t);
                //possible ArrayIndexOutOfBoundsException
            }
            final NotificationManager notificationManager = (NotificationManager) mParentContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        NotificationChannel channel = null;

                        try {
                            channel = notificationManager.getNotificationChannel(Constants.FROSTWIRE_NOTIFICATION_CHANNEL_ID);
                            //LOG.info("updateTransfersStatusNotification() got a channel with notificationManager.getNotificationChannel()? -> " + channel);
                        } catch (Throwable t) {
                            LOG.info("updateTransfersStatusNotification() " + t.getMessage(), t);
                        }

                        if (channel == null) {
                            channel = new NotificationChannel(Constants.FROSTWIRE_NOTIFICATION_CHANNEL_ID, "FrostWire", NotificationManager.IMPORTANCE_MIN);
                            channel.setSound(null, null);
                            notificationManager.createNotificationChannel(channel);
                            //LOG.info("updateTransfersStatusNotification() had to create a new channel with notificationManager.createNotificationChannel()");
                        }
                    }
                    notificationManager.notify(Constants.NOTIFICATION_FROSTWIRE_STATUS, notificationObject);
                    //LOG.info("updateTransfersStatusNotification() notificationManager.notify()!");
                } catch (SecurityException ignored) {
                    // possible java.lang.SecurityException
                    LOG.error("updateTransfersStatusNotification() " + ignored.getMessage(), ignored);
                } catch (Throwable ignored2) {
                    // possible android.os.TransactionTooLargeException
                    LOG.error("updateTransfersStatusNotification() " + ignored2.getMessage(), ignored2);
                }
            } else {
                LOG.info("updateTransfersStatusNotification() no notification manager available");
            }
        }
    }

    private void setupNotification() {
        if (!ConfigurationManager.instance().getBoolean(Constants.PREF_KEY_GUI_ENABLE_PERMANENT_STATUS_NOTIFICATION)) {
            LOG.info("setupNotification() aborted, PREF_KEY_GUI_ENABLE_PERMANENT_STATUS_NOTIFICATION=false");
            return;
        }

        RemoteViews remoteViews = new RemoteViews(mParentContext.getPackageName(),
                R.layout.view_permanent_status_notification);

        PendingIntent showFrostWireIntent = createShowFrostwireIntent();
        PendingIntent shutdownIntent = createShutdownIntent();

        remoteViews.setOnClickPendingIntent(R.id.view_permanent_status_shutdown, shutdownIntent);
        remoteViews.setOnClickPendingIntent(R.id.view_permanent_status_text_title, showFrostWireIntent);
        Notification notification = new NotificationCompat.Builder(mParentContext, Constants.FROSTWIRE_NOTIFICATION_CHANNEL_ID).
                setSmallIcon(R.drawable.frostwire_notification_flat).
                setContentIntent(showFrostWireIntent).
                setContent(remoteViews).
                build();

        notification.flags |= Notification.FLAG_NO_CLEAR;

        notificationViews = remoteViews;
        notificationObject = notification;
        LOG.info("setupNotification() notificationViews:" + notificationViews + " notificationObject:" + notificationObject);
    }

    private PendingIntent createShowFrostwireIntent() {
        return PendingIntent.getActivity(mParentContext,
                0,
                new Intent(mParentContext,
                        MainActivity.class).
                        setAction(Constants.ACTION_SHOW_TRANSFERS).
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_CLEAR_TASK),
                0);
    }

    private PendingIntent createShutdownIntent() {
        return PendingIntent.getActivity(mParentContext,
                1,
                new Intent(mParentContext,
                        MainActivity.class).
                        setAction(Constants.ACTION_REQUEST_SHUTDOWN).
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_CLEAR_TASK),
                0);
    }

    @Override
    public void onTime() {
        if (mTimerSubscription != null && mTimerSubscription.isSubscribed()) {
            if (Asyncs.Throttle.isReadyToSubmitTask("NotificationUpdateDaemon::onTimeRefresh)", (FROSTWIRE_STATUS_NOTIFICATION_UPDATE_INTERVAL_IN_SECS*1000)-100)) {
                async(this, NotificationUpdateDaemon::onTimeRefresh);
            }
        } else {
            LOG.error("NotificationUpdateDaemon::onTime() invoked by who?", new Throwable());
        }
    }

    @SuppressWarnings("deprecation")
    private boolean isScreenOn() {
        PowerManager pm = (PowerManager) mParentContext.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isScreenOn();
    }

    private void onTimeRefresh() {
        if (isScreenOn()) {
            updateTransfersStatusNotification();
        }
    }
}