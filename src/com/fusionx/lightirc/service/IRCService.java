/*
    LightIRC - an IRC client for Android

    Copyright 2013 Lalit Maganti

    This file is part of LightIRC.

    LightIRC is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    LightIRC is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with LightIRC. If not, see <http://www.gnu.org/licenses/>.
 */

package com.fusionx.lightirc.service;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.TaskStackBuilder;
import android.support.v4.content.LocalBroadcastManager;
import com.fusionx.lightirc.R;
import com.fusionx.lightirc.activity.IRCFragmentActivity;
import com.fusionx.lightirc.activity.MainServerListActivity;
import com.fusionx.lightirc.irc.LightBotFactory;
import com.fusionx.lightirc.irc.LightManager;
import com.fusionx.lightirc.listeners.ServiceListener;
import com.fusionx.lightirc.misc.LightThread;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.pircbotx.*;
import org.pircbotx.output.OutputChannel;

public class IRCService extends Service {
    // Binder which returns this service
    public class IRCBinder extends Binder {
        public IRCService getService() {
            return IRCService.this;
        }
    }

    @Getter(AccessLevel.PUBLIC)
    private final LightManager threadManager = new LightManager();
    private final IRCBinder mBinder = new IRCBinder();
    @Setter(AccessLevel.PUBLIC)
    private String boundToIRCFragmentActivity = null;

    public void connectToServer(final Configuration.Builder server) {
        final LightBotFactory factory = new LightBotFactory();
        factory.setApplicationContext(getApplicationContext());
        server.setBotFactory(factory);

        setupListeners(server);
        setupNotification();

        final Configuration configuration = server.buildConfiguration();

        final PircBotX bot = new PircBotX(configuration);
        bot.setStatus(getString(R.string.status_connecting));

        final LightThread thread = new LightThread(bot);
        thread.start();
        threadManager.put(server.getTitle(), thread);
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private void setupNotification() {
        final Intent intent = new Intent(this, MainServerListActivity.class);
        final Intent intent2 = new Intent(this, IRCService.class);
        intent2.putExtra("stop", true);
        final PendingIntent pIntent = PendingIntent.getActivity(this, 0, intent, 0);
        final PendingIntent pIntent2 = PendingIntent.getService(this, 0, intent2, 0);
        final NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.service_one_server_joined))
                .setTicker(getString(R.string.service_one_server_joined))
                        // TODO - change to a proper icon
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pIntent);

        Notification notification = builder.addAction(android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.service_disconnect_all), pIntent2).build();

        // Just a random number
        // TODO - maybe static int this?
        startForeground(1337, notification);
    }

    public void disconnectAll() {
        threadManager.disconnectAll();
        stopForeground(true);
        stopSelf();
        Intent intent = new Intent("serviceStopped");
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    public void disconnectFromServer(final String serverName) {
        getBot(serverName).setStatus(getString(R.string.status_disconnected));
        final DisconnectTask disconnectTask = new DisconnectTask();
        disconnectTask.execute(serverName);
    }

    private class DisconnectTask extends AsyncTask<String, Void, String> {
        protected String doInBackground(final String... strings) {
            final String botName = strings[0];
            if (getBot(botName).getStatus().equals(getString(R.string.status_connected))) {
                getBot(botName).shutdown();
            } else {
                getThreadManager().get(botName).interrupt();
            }
            return botName;
        }

        @Override
        protected void onPostExecute(final String botName) {
            threadManager.remove(botName);
            if (getThreadManager().keySet().isEmpty()) {
                stopForeground(true);
                stopSelf();
            }
        }
    }

    public PircBotX getBot(final String serverName) {
        if (threadManager.get(serverName) != null) {
            return threadManager.get(serverName).getBot();
        } else {
            return null;
        }
    }

    @Override
    public IBinder onBind(final Intent intent) {
        return mBinder;
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        if (intent != null) {
            boundToIRCFragmentActivity = intent.getStringExtra("setBound");
            if (intent.getBooleanExtra("stop", false)) {
                disconnectAll();
            }
            return 0;
        } else {
            return START_STICKY;
        }
    }

    @Override
    public boolean onUnbind(final Intent intent) {
        boundToIRCFragmentActivity = null;
        return true;
    }

    public void partFromChannel(final String serverName, final String channelName) {
        final UserChannelDao<User, Channel> dao = getBot(serverName).getUserChannelDao();
        final Channel channel = dao.getChannel(channelName);
        final OutputChannel outputChannel = channel.send();
        outputChannel.part();
    }

    public void removePrivateMessage(final String serverName, final String nick) {
        final UserChannelDao<User, Channel> dao = getBot(serverName).getUserChannelDao();
        dao.removePrivateMessage(nick);
        dao.getUser(nick).setBuffer("");
    }

    private void setupListeners(final Configuration.Builder bot) {
        final ServiceListener mServiceListener = new ServiceListener(this);

        bot.getListenerManager().addListener(mServiceListener);
    }

    public void mention(final String serverName, final String messageDest) {
        Notification notification;
        final NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.service_you_mentioned) + " " + messageDest)
                .setSmallIcon(R.drawable.ic_launcher)
                .setAutoCancel(true)
                .setTicker(getString(R.string.service_you_mentioned) + " " + messageDest);

        if (!serverName.equals(boundToIRCFragmentActivity)) {
            final Intent mIntent = new Intent(this, IRCFragmentActivity.class);
            mIntent.putExtra("server", new Configuration.Builder<PircBotX>(getBot(serverName).getConfiguration()));
            mIntent.putExtra("mention", messageDest);
            final TaskStackBuilder taskStackBuilder = TaskStackBuilder.create(this);
            taskStackBuilder.addParentStack(IRCFragmentActivity.class);
            taskStackBuilder.addNextIntent(mIntent);
            final PendingIntent pIntent = taskStackBuilder.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT);
            notification = builder.setContentIntent(pIntent).build();
        } else {
            notification = builder.build();
        }

        final NotificationManager mNotificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.notify(345, notification);
        mNotificationManager.cancel(345);
    }
}