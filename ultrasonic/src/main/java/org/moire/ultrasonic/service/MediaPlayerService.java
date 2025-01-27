package org.moire.ultrasonic.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.IBinder;
import timber.log.Timber;
import android.view.View;
import android.widget.RemoteViews;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.koin.java.KoinJavaComponent;
import org.moire.ultrasonic.R;
import org.moire.ultrasonic.activity.NavigationActivity;
import org.moire.ultrasonic.domain.MusicDirectory;
import org.moire.ultrasonic.domain.PlayerState;
import org.moire.ultrasonic.domain.RepeatMode;
import org.moire.ultrasonic.featureflags.Feature;
import org.moire.ultrasonic.featureflags.FeatureStorage;
import org.moire.ultrasonic.provider.UltrasonicAppWidgetProvider4X1;
import org.moire.ultrasonic.provider.UltrasonicAppWidgetProvider4X2;
import org.moire.ultrasonic.provider.UltrasonicAppWidgetProvider4X3;
import org.moire.ultrasonic.provider.UltrasonicAppWidgetProvider4X4;
import org.moire.ultrasonic.util.Constants;
import org.moire.ultrasonic.util.FileUtil;
import org.moire.ultrasonic.util.NowPlayingEventDistributor;
import org.moire.ultrasonic.util.ShufflePlayBuffer;
import org.moire.ultrasonic.util.SimpleServiceBinder;
import org.moire.ultrasonic.util.Util;

import kotlin.Lazy;

import static org.koin.java.KoinJavaComponent.inject;
import static org.moire.ultrasonic.domain.PlayerState.COMPLETED;
import static org.moire.ultrasonic.domain.PlayerState.DOWNLOADING;
import static org.moire.ultrasonic.domain.PlayerState.IDLE;
import static org.moire.ultrasonic.domain.PlayerState.PAUSED;
import static org.moire.ultrasonic.domain.PlayerState.PREPARING;
import static org.moire.ultrasonic.domain.PlayerState.STARTED;
import static org.moire.ultrasonic.domain.PlayerState.STOPPED;

/**
 * Android Foreground Service for playing music
 * while the rest of the Ultrasonic App is in the background.
 */
public class MediaPlayerService extends Service
{
    private static final String NOTIFICATION_CHANNEL_ID = "org.moire.ultrasonic";
    private static final String NOTIFICATION_CHANNEL_NAME = "Ultrasonic background service";
    private static final int NOTIFICATION_ID = 3033;

    private static MediaPlayerService instance = null;
    private static final Object instanceLock = new Object();

    private final IBinder binder = new SimpleServiceBinder<>(this);
    private final Scrobbler scrobbler = new Scrobbler();

    public Lazy<JukeboxMediaPlayer> jukeboxMediaPlayer = inject(JukeboxMediaPlayer.class);
    private final Lazy<DownloadQueueSerializer> downloadQueueSerializerLazy = inject(DownloadQueueSerializer.class);
    private final Lazy<ShufflePlayBuffer> shufflePlayBufferLazy = inject(ShufflePlayBuffer.class);
    private final Lazy<Downloader> downloaderLazy = inject(Downloader.class);
    private final Lazy<LocalMediaPlayer> localMediaPlayerLazy = inject(LocalMediaPlayer.class);
    private final Lazy<NowPlayingEventDistributor> nowPlayingEventDistributor = inject(NowPlayingEventDistributor.class);
    private LocalMediaPlayer localMediaPlayer;
    private Downloader downloader;
    private ShufflePlayBuffer shufflePlayBuffer;
    private DownloadQueueSerializer downloadQueueSerializer;

    private boolean isInForeground = false;
    private NotificationCompat.Builder notificationBuilder;

    public RepeatMode getRepeatMode() { return Util.getRepeatMode(this); }

    public static MediaPlayerService getInstance(Context context)
    {
        synchronized (instanceLock) {
            for (int i = 0; i < 20; i++) {
                if (instance != null) return instance;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(new Intent(context, MediaPlayerService.class));
                } else {
                    context.startService(new Intent(context, MediaPlayerService.class));
                }

                Util.sleepQuietly(50L);
            }

            return instance;
        }
    }

    public static MediaPlayerService getRunningInstance()
    {
        synchronized (instanceLock)
        {
            return instance;
        }
    }

    public static void executeOnStartedMediaPlayerService(final Context context, final Consumer<MediaPlayerService> taskToExecute)
    {
        Thread t = new Thread()
        {
            public void run()
            {
                MediaPlayerService instance = getInstance(context);
                if (instance == null)
                {
                    Timber.e("ExecuteOnStartedMediaPlayerService failed to get a MediaPlayerService instance!");
                    return;
                }

                taskToExecute.accept(instance);
            }
        };
        t.start();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent)
    {
        return binder;
    }

    @Override
    public void onCreate()
    {
        super.onCreate();

        downloader = downloaderLazy.getValue();
        localMediaPlayer = localMediaPlayerLazy.getValue();
        shufflePlayBuffer = shufflePlayBufferLazy.getValue();
        downloadQueueSerializer = downloadQueueSerializerLazy.getValue();

        downloader.onCreate();
        shufflePlayBuffer.onCreate();

        localMediaPlayer.init();
        setupOnCurrentPlayingChangedHandler();
        setupOnPlayerStateChangedHandler();
        setupOnSongCompletedHandler();
        localMediaPlayer.onPrepared = new Runnable() {
            @Override
            public void run() {
                downloadQueueSerializer.serializeDownloadQueue(downloader.downloadList,
                        downloader.getCurrentPlayingIndex(), getPlayerPosition());
            }
        };
        localMediaPlayer.onNextSongRequested = new Runnable() {
            @Override
            public void run() {
                setNextPlaying();
            }
        };

        // Create Notification Channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            //The suggested importance of a startForeground service notification is IMPORTANCE_LOW
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
            channel.setLightColor(android.R.color.holo_blue_dark);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            manager.createNotificationChannel(channel);
        }

        // We should use a single notification builder, otherwise the notification may not be updated
        notificationBuilder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID);
        // Update notification early. It is better to show an empty one temporarily than waiting too long and letting Android kill the app
        updateNotification(IDLE, null);
        instance = this;

        Timber.i("MediaPlayerService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        super.onStartCommand(intent, flags, startId);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy()
    {
        super.onDestroy();

        instance = null;

        try {
            localMediaPlayer.release();
            downloader.stop();
            shufflePlayBuffer.onDestroy();
        } catch (Throwable ignored) {
        }

        Timber.i("MediaPlayerService stopped");
    }

    private void stopIfIdle()
    {
        synchronized (instanceLock)
        {
            // currentPlaying could be changed from another thread in the meantime, so check again before stopping for good
            if (localMediaPlayer.currentPlaying == null || localMediaPlayer.playerState == STOPPED) stopSelf();
        }
    }

    public synchronized void seekTo(int position)
    {
        if (jukeboxMediaPlayer.getValue().isEnabled())
        {
            jukeboxMediaPlayer.getValue().skip(downloader.getCurrentPlayingIndex(), position / 1000);
        }
        else
        {
            localMediaPlayer.seekTo(position);
        }
    }

    public synchronized int getPlayerPosition()
    {
        if (localMediaPlayer.playerState == IDLE || localMediaPlayer.playerState == DOWNLOADING || localMediaPlayer.playerState == PREPARING)
        {
            return 0;
        }

        return jukeboxMediaPlayer.getValue().isEnabled() ? jukeboxMediaPlayer.getValue().getPositionSeconds() * 1000 :
                localMediaPlayer.getPlayerPosition();
    }

    public synchronized int getPlayerDuration()
    {
        return localMediaPlayer.getPlayerDuration();
    }

    public synchronized void setCurrentPlaying(int currentPlayingIndex)
    {
        try
        {
            localMediaPlayer.setCurrentPlaying(downloader.downloadList.get(currentPlayingIndex));
        }
        catch (IndexOutOfBoundsException x)
        {
            // Ignored
        }
    }

    public void setupOnCurrentPlayingChangedHandler()
    {
        localMediaPlayer.onCurrentPlayingChanged = new Consumer<DownloadFile>() {
            @Override
            public void accept(DownloadFile currentPlaying) {
                if (currentPlaying != null)
                {
                    Util.broadcastNewTrackInfo(MediaPlayerService.this, currentPlaying.getSong());
                    Util.broadcastA2dpMetaDataChange(MediaPlayerService.this, getPlayerPosition(), currentPlaying,
                            downloader.getDownloads().size(), downloader.getCurrentPlayingIndex() + 1);
                }
                else
                {
                    Util.broadcastNewTrackInfo(MediaPlayerService.this, null);
                    Util.broadcastA2dpMetaDataChange(MediaPlayerService.this, getPlayerPosition(), null,
                            downloader.getDownloads().size(), downloader.getCurrentPlayingIndex() + 1);
                }

                // Update widget
                PlayerState playerState = localMediaPlayer.playerState;
                MusicDirectory.Entry song = currentPlaying == null? null : currentPlaying.getSong();
                UltrasonicAppWidgetProvider4X1.getInstance().notifyChange(MediaPlayerService.this, song, playerState == PlayerState.STARTED, false);
                UltrasonicAppWidgetProvider4X2.getInstance().notifyChange(MediaPlayerService.this, song, playerState == PlayerState.STARTED, true);
                UltrasonicAppWidgetProvider4X3.getInstance().notifyChange(MediaPlayerService.this, song, playerState == PlayerState.STARTED, false);
                UltrasonicAppWidgetProvider4X4.getInstance().notifyChange(MediaPlayerService.this, song, playerState == PlayerState.STARTED, false);

                if (currentPlaying != null)
                {
                    updateNotification(localMediaPlayer.playerState, currentPlaying);
                    nowPlayingEventDistributor.getValue().raiseShowNowPlayingEvent();
                }
                else
                {
                    nowPlayingEventDistributor.getValue().raiseHideNowPlayingEvent();
                    stopForeground(true);
                    localMediaPlayer.clearRemoteControl();
                    isInForeground = false;
                    stopIfIdle();
                }
            }
        };
    }

    public synchronized void setNextPlaying()
    {
        boolean gaplessPlayback = Util.getGaplessPlaybackPreference(this);

        if (!gaplessPlayback)
        {
            localMediaPlayer.clearNextPlaying(true);
            return;
        }

        int index = downloader.getCurrentPlayingIndex();

        if (index != -1)
        {
            switch (getRepeatMode())
            {
                case OFF:
                    index += 1;
                    break;
                case ALL:
                    index = (index + 1) % downloader.downloadList.size();
                    break;
                case SINGLE:
                default:
                    break;
            }
        }

        localMediaPlayer.clearNextPlaying(false);

        if (index < downloader.downloadList.size() && index != -1)
        {
            localMediaPlayer.setNextPlaying(downloader.downloadList.get(index));
        }
        else
        {
            localMediaPlayer.clearNextPlaying(true);
        }
    }

    public synchronized void togglePlayPause()
    {
        if (localMediaPlayer.playerState == PAUSED || localMediaPlayer.playerState == COMPLETED || localMediaPlayer.playerState == STOPPED)
        {
            start();
        }
        else if (localMediaPlayer.playerState == IDLE)
        {
            play();
        }
        else if (localMediaPlayer.playerState == STARTED)
        {
            pause();
        }
    }

    public synchronized void resumeOrPlay()
    {
        if (localMediaPlayer.playerState == PAUSED || localMediaPlayer.playerState == COMPLETED || localMediaPlayer.playerState == STOPPED)
        {
            start();
        }
        else if (localMediaPlayer.playerState == IDLE)
        {
            play();
        }
    }

    /**
     * Plays either the current song (resume) or the first/next one in queue.
     */
    public synchronized void play()
    {
        int current = downloader.getCurrentPlayingIndex();
        if (current == -1)
        {
            play(0);
        }
        else
        {
            play(current);
        }
    }

    public synchronized void play(int index)
    {
        play(index, true);
    }

    public synchronized void play(int index, boolean start)
    {
        Timber.v("play requested for %d", index);
        if (index < 0 || index >= downloader.downloadList.size())
        {
            resetPlayback();
        }
        else
        {
            setCurrentPlaying(index);

            if (start)
            {
                if (jukeboxMediaPlayer.getValue().isEnabled())
                {
                    jukeboxMediaPlayer.getValue().skip(index, 0);
                    localMediaPlayer.setPlayerState(STARTED);
                }
                else
                {
                    localMediaPlayer.play(downloader.downloadList.get(index));
                }
            }

            downloader.checkDownloads();
            setNextPlaying();
        }
    }

    private synchronized void resetPlayback()
    {
        localMediaPlayer.reset();
        localMediaPlayer.setCurrentPlaying(null);
        downloadQueueSerializer.serializeDownloadQueue(downloader.downloadList,
                downloader.getCurrentPlayingIndex(), getPlayerPosition());
    }

    public synchronized void pause()
    {
        if (localMediaPlayer.playerState == STARTED)
        {
            if (jukeboxMediaPlayer.getValue().isEnabled())
            {
                jukeboxMediaPlayer.getValue().stop();
            }
            else
            {
                localMediaPlayer.pause();
            }
            localMediaPlayer.setPlayerState(PAUSED);
        }
    }

    public synchronized void stop()
    {
        if (localMediaPlayer.playerState == STARTED)
        {
            if (jukeboxMediaPlayer.getValue().isEnabled())
            {
                jukeboxMediaPlayer.getValue().stop();
            }
            else
            {
                localMediaPlayer.pause();
            }
        }
        localMediaPlayer.setPlayerState(STOPPED);
    }

    public synchronized void start()
    {
        if (jukeboxMediaPlayer.getValue().isEnabled())
        {
            jukeboxMediaPlayer.getValue().start();
        }
        else
        {
            localMediaPlayer.start();
        }
        localMediaPlayer.setPlayerState(STARTED);
    }

    public void setupOnPlayerStateChangedHandler()
    {
        localMediaPlayer.onPlayerStateChanged = new BiConsumer<PlayerState, DownloadFile>() {
            @Override
            public void accept(PlayerState playerState, DownloadFile currentPlaying) {
                if (playerState == PAUSED)
                {
                    downloadQueueSerializer.serializeDownloadQueue(downloader.downloadList, downloader.getCurrentPlayingIndex(), getPlayerPosition());
                }

                boolean showWhenPaused = (playerState != PlayerState.STOPPED && Util.isNotificationAlwaysEnabled(MediaPlayerService.this));
                boolean show = playerState == PlayerState.STARTED || showWhenPaused;
                MusicDirectory.Entry song = currentPlaying == null? null : currentPlaying.getSong();

                Util.broadcastPlaybackStatusChange(MediaPlayerService.this, playerState);
                Util.broadcastA2dpPlayStatusChange(MediaPlayerService.this, playerState, song,
                        downloader.downloadList.size() + downloader.backgroundDownloadList.size(),
                        downloader.downloadList.indexOf(currentPlaying) + 1, getPlayerPosition());

                // Update widget
                UltrasonicAppWidgetProvider4X1.getInstance().notifyChange(MediaPlayerService.this, song, playerState == PlayerState.STARTED, false);
                UltrasonicAppWidgetProvider4X2.getInstance().notifyChange(MediaPlayerService.this, song, playerState == PlayerState.STARTED, true);
                UltrasonicAppWidgetProvider4X3.getInstance().notifyChange(MediaPlayerService.this, song, playerState == PlayerState.STARTED, false);
                UltrasonicAppWidgetProvider4X4.getInstance().notifyChange(MediaPlayerService.this, song, playerState == PlayerState.STARTED, false);

                if (show)
                {
                    // Only update notification if player state is one that will change the icon
                    if (playerState == PlayerState.STARTED || playerState == PlayerState.PAUSED)
                    {
                        updateNotification(playerState, currentPlaying);
                        nowPlayingEventDistributor.getValue().raiseShowNowPlayingEvent();
                    }
                }
                else
                {
                    nowPlayingEventDistributor.getValue().raiseHideNowPlayingEvent();
                    stopForeground(true);
                    localMediaPlayer.clearRemoteControl();
                    isInForeground = false;
                    stopIfIdle();
                }

                if (playerState == STARTED)
                {
                    scrobbler.scrobble(MediaPlayerService.this, currentPlaying, false);
                }
                else if (playerState == COMPLETED)
                {
                    scrobbler.scrobble(MediaPlayerService.this, currentPlaying, true);
                }
            }
        };
    }

    private void setupOnSongCompletedHandler()
    {
        localMediaPlayer.onSongCompleted = new Consumer<DownloadFile>() {
            @Override
            public void accept(DownloadFile currentPlaying) {
                int index = downloader.getCurrentPlayingIndex();

                if (currentPlaying != null)
                {
                    final MusicDirectory.Entry song = currentPlaying.getSong();

                    if (song != null && song.getBookmarkPosition() > 0 && Util.getShouldClearBookmark(MediaPlayerService.this))
                    {
                        MusicService musicService = MusicServiceFactory.getMusicService(MediaPlayerService.this);
                        try
                        {
                            musicService.deleteBookmark(song.getId(), MediaPlayerService.this);
                        }
                        catch (Exception ignored)
                        {

                        }
                    }
                }

                if (index != -1)
                {
                    switch (getRepeatMode())
                    {
                        case OFF:
                            if (index + 1 < 0 || index + 1 >= downloader.downloadList.size())
                            {
                                if (Util.getShouldClearPlaylist(MediaPlayerService.this))
                                {
                                    clear(true);
                                    jukeboxMediaPlayer.getValue().updatePlaylist();
                                }

                                resetPlayback();
                                break;
                            }

                            play(index + 1);
                            break;
                        case ALL:
                            play((index + 1) % downloader.downloadList.size());
                            break;
                        case SINGLE:
                            play(index);
                            break;
                        default:
                            break;
                    }
                }
            }
        };
    }

    public synchronized void clear(boolean serialize)
    {
        localMediaPlayer.reset();
        downloader.clear();
        localMediaPlayer.setCurrentPlaying(null);

        setNextPlaying();

        if (serialize) {
            downloadQueueSerializer.serializeDownloadQueue(downloader.downloadList,
                    downloader.getCurrentPlayingIndex(), getPlayerPosition());
        }
    }

    public void updateNotification(PlayerState playerState, DownloadFile currentPlaying)
    {
        if (Util.isNotificationEnabled(this)) {
            if (isInForeground) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    notificationManager.notify(NOTIFICATION_ID, buildForegroundNotification(playerState, currentPlaying));
                }
                else {
                    final NotificationManagerCompat notificationManager =
                            NotificationManagerCompat.from(this);
                    notificationManager.notify(NOTIFICATION_ID, buildForegroundNotification(playerState, currentPlaying));
                }
                Timber.w("--- Updated notification");
            }
            else {
                startForeground(NOTIFICATION_ID, buildForegroundNotification(playerState, currentPlaying));
                isInForeground = true;
                Timber.w("--- Created Foreground notification");
            }
        }
    }

    @SuppressWarnings("IconColors")
    private Notification buildForegroundNotification(PlayerState playerState, DownloadFile currentPlaying) {
        notificationBuilder.setSmallIcon(R.drawable.ic_stat_ultrasonic);

        notificationBuilder.setAutoCancel(false);
        notificationBuilder.setOngoing(true);
        notificationBuilder.setOnlyAlertOnce(true);
        notificationBuilder.setWhen(System.currentTimeMillis());
        notificationBuilder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        notificationBuilder.setPriority(NotificationCompat.PRIORITY_LOW);

        RemoteViews contentView = new RemoteViews(this.getPackageName(), R.layout.notification);
        Util.linkButtons(this, contentView, false);
        RemoteViews bigView = new RemoteViews(this.getPackageName(), R.layout.notification_large);
        Util.linkButtons(this, bigView, false);

        notificationBuilder.setContent(contentView);

        Intent notificationIntent = new Intent(this, NavigationActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        notificationIntent.putExtra(Constants.INTENT_EXTRA_NAME_SHOW_PLAYER, true);
        notificationBuilder.setContentIntent(PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT));

        if (playerState == PlayerState.PAUSED || playerState == PlayerState.IDLE) {
            contentView.setImageViewResource(R.id.control_play, R.drawable.media_start_normal_dark);
            bigView.setImageViewResource(R.id.control_play, R.drawable.media_start_normal_dark);
        } else if (playerState == PlayerState.STARTED) {
            contentView.setImageViewResource(R.id.control_play, R.drawable.media_pause_normal_dark);
            bigView.setImageViewResource(R.id.control_play, R.drawable.media_pause_normal_dark);
        }

        if (currentPlaying != null) {
            final MusicDirectory.Entry song = currentPlaying.getSong();
            final String title = song.getTitle();
            final String text = song.getArtist();
            final String album = song.getAlbum();
            final int rating = song.getUserRating() == null ? 0 : song.getUserRating();
            final int imageSize = Util.getNotificationImageSize(this);

            try {
                final Bitmap nowPlayingImage = FileUtil.getAlbumArtBitmap(this, currentPlaying.getSong(), imageSize, true);
                if (nowPlayingImage == null) {
                    contentView.setImageViewResource(R.id.notification_image, R.drawable.unknown_album);
                    bigView.setImageViewResource(R.id.notification_image, R.drawable.unknown_album);
                } else {
                    contentView.setImageViewBitmap(R.id.notification_image, nowPlayingImage);
                    bigView.setImageViewBitmap(R.id.notification_image, nowPlayingImage);
                }
            } catch (Exception x) {
                Timber.w(x, "Failed to get notification cover art");
                contentView.setImageViewResource(R.id.notification_image, R.drawable.unknown_album);
                bigView.setImageViewResource(R.id.notification_image, R.drawable.unknown_album);
            }

            contentView.setTextViewText(R.id.trackname, title);
            bigView.setTextViewText(R.id.trackname, title);
            contentView.setTextViewText(R.id.artist, text);
            bigView.setTextViewText(R.id.artist, text);
            contentView.setTextViewText(R.id.album, album);
            bigView.setTextViewText(R.id.album, album);

            boolean useFiveStarRating = KoinJavaComponent.get(FeatureStorage.class).isFeatureEnabled(Feature.FIVE_STAR_RATING);
            if (!useFiveStarRating)
                bigView.setViewVisibility(R.id.notification_rating, View.INVISIBLE);
            else {
                bigView.setImageViewResource(R.id.notification_five_star_1, rating > 0 ? R.drawable.ic_star_full_dark : R.drawable.ic_star_hollow_dark);
                bigView.setImageViewResource(R.id.notification_five_star_2, rating > 1 ? R.drawable.ic_star_full_dark : R.drawable.ic_star_hollow_dark);
                bigView.setImageViewResource(R.id.notification_five_star_3, rating > 2 ? R.drawable.ic_star_full_dark : R.drawable.ic_star_hollow_dark);
                bigView.setImageViewResource(R.id.notification_five_star_4, rating > 3 ? R.drawable.ic_star_full_dark : R.drawable.ic_star_hollow_dark);
                bigView.setImageViewResource(R.id.notification_five_star_5, rating > 4 ? R.drawable.ic_star_full_dark : R.drawable.ic_star_hollow_dark);
            }
        }

        Notification notification = notificationBuilder.build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            notification.bigContentView = bigView;
        }

        return notification;
    }
}
