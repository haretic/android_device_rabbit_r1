// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.phone;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.SystemClock;

import cat.kenny.r1.phone.proto.Ams;

/**
 * The phone's music as the r1's own: a media session, so that the system shows its player
 * for it, in the shade and on the lock screen, with the keys the phone allows, and so that the
 * r1's volume is the phone's while the phone plays. Nothing sounds on the r1.
 */
final class PhoneMedia {
    static final int NOTE = 2;
    /** The phone's volume goes from silent to full in sixteen steps. */
    private static final int STEPS = 16;

    private final Context mContext;
    private final String mChannel;
    private final PendingIntent mPage;
    private final NotificationManager mNotes;
    private MediaSession mSession;
    private Link mLink;
    private String mShown = "";

    PhoneMedia(Context context, String channel, PendingIntent page) {
        mContext = context;
        mChannel = channel;
        mPage = page;
        mNotes = context.getSystemService(NotificationManager.class);
    }

    /** Shows what the phone plays now, or with nothing known of a track, no player. */
    void show(Link link) {
        mLink = link;
        final Link.Music music = link.music;
        if (link.state() != Link.ON || (music.title.isEmpty() && !music.playing())) {
            hide();
            return;
        }
        if (mSession == null) {
            mSession = new MediaSession(mContext, "R1Phone");
            mSession.setCallback(mKeys);
            mSession.setSessionActivity(mPage);
        }
        final int volume = music.volume == null ? STEPS / 2 : Math.round(music.volume * STEPS);
        // Told apart by what the player shows: the clock alone does not redraw it
        final String now = music.title + '\n' + music.artist + '\n' + music.album + '\n'
                + music.duration + '\n' + music.playing() + '\n' + music.commands + '\n'
                + volume + '\n' + (music.info == null ? "" : music.info.elapsedSeconds());
        if (now.equals(mShown)) {
            return;
        }
        mShown = now;
        final MediaMetadata.Builder data = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, music.title.isEmpty()
                        ? mContext.getString(R.string.nothing_playing) : music.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, music.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, music.album);
        if (music.duration != null) {
            data.putLong(MediaMetadata.METADATA_KEY_DURATION, (long) (music.duration * 1000));
        }
        mSession.setMetadata(data.build());
        long actions = 0;
        if (music.commands.contains(Ams.PLAY)) {
            actions |= PlaybackState.ACTION_PLAY;
        }
        if (music.commands.contains(Ams.PAUSE)) {
            actions |= PlaybackState.ACTION_PAUSE;
        }
        if (music.commands.contains(Ams.TOGGLE)) {
            actions |= PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_PLAY
                    | PlaybackState.ACTION_PAUSE;
        }
        if (music.commands.contains(Ams.NEXT)) {
            actions |= PlaybackState.ACTION_SKIP_TO_NEXT;
        }
        if (music.commands.contains(Ams.PREVIOUS)) {
            actions |= PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        }
        mSession.setPlaybackState(new PlaybackState.Builder().setActions(actions)
                .setState(music.playing() ? PlaybackState.STATE_PLAYING
                        : PlaybackState.STATE_PAUSED, (long) (music.elapsed() * 1000),
                        music.playing() && music.info != null ? (float) music.info.rate() : 0f,
                        SystemClock.elapsedRealtime()).build());
        mSession.setPlaybackToRemote(new VolumeProvider(VolumeProvider.VOLUME_CONTROL_RELATIVE,
                STEPS, volume) {
            @Override
            public void onAdjustVolume(int direction) {
                if (direction != 0) {
                    press(direction > 0 ? Ams.VOLUME_UP : Ams.VOLUME_DOWN);
                }
            }
        });
        mSession.setActive(true);
        mNotes.notify(NOTE, new Notification.Builder(mContext, mChannel)
                .setSmallIcon(R.drawable.ic_phone)
                .setContentTitle(music.title).setContentText(music.artist)
                .setSubText(music.player.isEmpty() ? null : music.player)
                .setContentIntent(mPage).setOngoing(music.playing())
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(mSession.getSessionToken())).build());
    }

    void hide() {
        mShown = "";
        mNotes.cancel(NOTE);
        if (mSession != null) {
            mSession.release();
            mSession = null;
        }
    }

    private void press(int command) {
        if (mLink != null) {
            mLink.press(command);
        }
    }

    private final MediaSession.Callback mKeys = new MediaSession.Callback() {
        @Override
        public void onPlay() {
            press(mLink != null && mLink.music.commands.contains(Ams.PLAY) ? Ams.PLAY
                    : Ams.TOGGLE);
        }

        @Override
        public void onPause() {
            press(mLink != null && mLink.music.commands.contains(Ams.PAUSE) ? Ams.PAUSE
                    : Ams.TOGGLE);
        }

        @Override
        public void onSkipToNext() {
            press(Ams.NEXT);
        }

        @Override
        public void onSkipToPrevious() {
            press(Ams.PREVIOUS);
        }
    };
}
