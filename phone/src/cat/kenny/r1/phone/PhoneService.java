// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import cat.kenny.r1.phone.proto.Ancs;
import cat.kenny.r1.phone.proto.Inbox;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the link to the phone while the page is closed, and shows what the phone shows: its
 * notifications as the r1's own, a call as a card over everything.
 */
public final class PhoneService extends Service {
    private static final String ACTION_NO = "cat.kenny.r1.phone.NO";
    /** The self test's: a notification made up here, shown as the phone's are. */
    private static final String ACTION_REHEARSE = "cat.kenny.r1.phone.REHEARSE";
    private static final String EXTRA_UID = "uid";
    private static final String LINK = "link", NOTES = "notes", QUIET = "quiet", CALLS = "calls";
    private static final String MUSIC = "music";
    private static final int NOTE_LINK = 1;
    private static final String TAG_NOTE = "n", TAG_CALL = "call", TAG_APP = "app";
    /** The phone's apps as channels of their own, each to be silenced by itself. */
    private static final String APPS = "apps", APP = "app:";

    static PhoneService sService;
    static Runnable sListener;

    Link link;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Map<Long, String> mShown = new HashMap<>();
    private NotificationManager mNotes;
    private PhoneMedia mMedia;
    AppIcons icons;
    private final Map<String, Integer> mStacks = new HashMap<>();
    private final Set<String> mChannels = new HashSet<>();
    private long mCall = -1;
    private int mNotedState = -1;

    static boolean wanted(Context context) {
        return context.getSharedPreferences("phone", 0).getBoolean("on", false);
    }

    /** What the r1 shows of the phone outside its page, each on unless turned off. */
    static final String SHADE_OLD = "shade_old", SHADE_CALLS = "shade_calls",
            SHADE_MUSIC = "shade_music";

    static boolean shows(Context context, String what) {
        return context.getSharedPreferences("phone", 0).getBoolean(what, true);
    }

    static void setShows(Context context, String what, boolean on) {
        context.getSharedPreferences("phone", 0).edit().putBoolean(what, on).apply();
        if (sService != null) {
            // Shown again as now set: what is turned off goes from the shade at once
            sService.mHandler.removeCallbacks(sService.mShow);
            sService.mHandler.post(sService.mShow);
        }
    }

    static void want(Context context, boolean on) {
        context.getSharedPreferences("phone", 0).edit().putBoolean("on", on).apply();
        final Intent service = new Intent(context, PhoneService.class);
        if (on) {
            context.startForegroundService(service);
        } else {
            context.stopService(service);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mNotes = getSystemService(NotificationManager.class);
        channel(LINK, R.string.channel_link, NotificationManager.IMPORTANCE_MIN);
        channel(NOTES, R.string.channel_notes, NotificationManager.IMPORTANCE_DEFAULT);
        channel(QUIET, R.string.channel_quiet, NotificationManager.IMPORTANCE_LOW);
        channel(CALLS, R.string.channel_calls, NotificationManager.IMPORTANCE_HIGH);
        channel(MUSIC, R.string.channel_music, NotificationManager.IMPORTANCE_LOW);
        mNotes.createNotificationChannelGroup(new android.app.NotificationChannelGroup(APPS,
                getString(R.string.channel_apps)));
        mMedia = new PhoneMedia(this, MUSIC, page());
        icons = new AppIcons(this, () -> {
            // Shown once more, now with their icons; none of them sounds again
            mShown.clear();
            mHandler.removeCallbacks(mShow);
            mHandler.post(mShow);
        });
        startForeground(NOTE_LINK, linkNote(Link.WAITING),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        link = new Link(this, () -> {
            // A burst of packets is one change to show
            mHandler.removeCallbacks(mShow);
            mHandler.postDelayed(mShow, 150);
        });
        sService = this;
        link.start();
    }

    private void channel(String id, int name, int importance) {
        mNotes.createNotificationChannel(new NotificationChannel(id, getString(name),
                importance));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_NO.equals(intent.getAction())) {
            final long uid = intent.getLongExtra(EXTRA_UID, -1);
            if (uid >= 0 && uid <= 0xffffffffL) {
                link.act(uid, false);
            }
        }
        if (intent != null && ACTION_REHEARSE.equals(intent.getAction())
                && intent.hasExtra("player")) {
            link.rehearseMusic(intent.getStringExtra("player"), intent.getStringExtra("title"),
                    intent.getStringExtra("artist"));
        } else if (intent != null && ACTION_REHEARSE.equals(intent.getAction())) {
            link.rehearse(intent.getLongExtra(EXTRA_UID, 0xf0000000L),
                    intent.getIntExtra("category", 0), intent.getStringExtra("app"),
                    intent.getStringExtra("name"), intent.getStringExtra("title"),
                    intent.getStringExtra("message"), intent.getStringExtra("date"));
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        link.stop();
        for (long uid : mShown.keySet()) {
            mNotes.cancel(TAG_NOTE, (int) uid);
        }
        for (String app : mStacks.keySet()) {
            mNotes.cancel(TAG_APP, app.hashCode());
        }
        mNotes.cancel(TAG_CALL, 0);
        mMedia.hide();
        icons.close();
        sService = null;
        if (sListener != null) {
            sListener.run();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private PendingIntent page() {
        return PendingIntent.getActivity(this, 0, new Intent(this, PhoneActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent no(long uid) {
        return PendingIntent.getService(this, (int) uid, new Intent(this, PhoneService.class)
                .setAction(ACTION_NO).putExtra(EXTRA_UID, uid),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification linkNote(int state) {
        return new Notification.Builder(this, LINK).setSmallIcon(R.drawable.ic_phone)
                .setContentTitle(getString(state == Link.ON ? R.string.link_on
                        : R.string.link_waiting))
                .setContentIntent(page()).setOngoing(true).build();
    }

    private final Runnable mShow = this::show;

    private void show() {
        if (link.state() != mNotedState) {
            mNotedState = link.state();
            mNotes.notify(NOTE_LINK, linkNote(mNotedState));
        }
        if (shows(this, SHADE_MUSIC)) {
            mMedia.show(link);
        } else {
            mMedia.hide();
        }
        final boolean old = shows(this, SHADE_OLD);
        final boolean calls = shows(this, SHADE_CALLS);
        final Set<Long> gone = new HashSet<>(mShown.keySet());
        final Map<String, Integer> stacks = new HashMap<>();
        final Map<String, Inbox.Notice> newest = new HashMap<>();
        long call = -1;
        for (Inbox.Notice notice : link.inbox.notifications()) {
            if (notice.title().isEmpty() && notice.message().isEmpty()) {
                // Only its arrival is known yet, not what it says
                continue;
            }
            if (notice.category() == Ancs.INCOMING_CALL) {
                if (calls) {
                    call = notice.uid();
                    showCall(notice);
                }
                continue;
            }
            if (notice.preExisting() && !old) {
                // From before the r1 came by, and turned off in the settings: the page has it
                continue;
            }
            gone.remove(notice.uid());
            stacks.merge(notice.appId(), 1, Integer::sum);
            newest.putIfAbsent(notice.appId(), notice);
            final String now = notice.appName() + '\n' + notice.title() + '\n'
                    + notice.subtitle() + '\n' + notice.message();
            if (!now.equals(mShown.put(notice.uid(), now))) {
                showNote(notice);
            }
        }
        for (long uid : gone) {
            mShown.remove(uid);
            mNotes.cancel(TAG_NOTE, (int) uid);
        }
        // Each of the phone's apps is a stack of its own, as the r1's own apps are
        for (Map.Entry<String, Integer> stack : stacks.entrySet()) {
            if (!stack.getValue().equals(mStacks.get(stack.getKey()))) {
                showStack(newest.get(stack.getKey()));
            }
        }
        for (String app : mStacks.keySet()) {
            if (!stacks.containsKey(app)) {
                mNotes.cancel(TAG_APP, app.hashCode());
            }
        }
        mStacks.clear();
        mStacks.putAll(stacks);
        if (call < 0 && mCall >= 0) {
            mNotes.cancel(TAG_CALL, 0);
        }
        mCall = call;
        if (sListener != null) {
            sListener.run();
        }
    }

    static int icon(int category) {
        switch (category) {
            case Ancs.INCOMING_CALL: case Ancs.MISSED_CALL: case Ancs.VOICEMAIL:
                return R.drawable.ic_call;
            case Ancs.SOCIAL:
                return R.drawable.ic_chat;
            case Ancs.SCHEDULE:
                return R.drawable.ic_event;
            case Ancs.EMAIL:
                return R.drawable.ic_mail;
            default:
                return R.drawable.ic_note;
        }
    }

    static String text(Inbox.Notice notice) {
        return notice.subtitle().isEmpty() ? notice.message()
                : notice.message().isEmpty() ? notice.subtitle()
                : notice.subtitle() + "\n" + notice.message();
    }

    /** The channel of one of the phone's apps, made when the app first shows something. */
    private String channel(Inbox.Notice notice) {
        if (notice.appId().isEmpty() || notice.appName().isEmpty()) {
            return NOTES;
        }
        final String id = APP + notice.appId();
        if (mChannels.add(id)) {
            final NotificationChannel channel = new NotificationChannel(id, notice.appName(),
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setGroup(APPS);
            mNotes.createNotificationChannel(channel);
        }
        return id;
    }

    /** Named and pictured as the phone's app, where the r1's shade names the app. */
    private Notification.Builder asApp(Notification.Builder note, Inbox.Notice notice) {
        if (!notice.appName().isEmpty()) {
            final android.os.Bundle extras = new android.os.Bundle();
            extras.putString(Notification.EXTRA_SUBSTITUTE_APP_NAME, notice.appName());
            note.addExtras(extras);
        }
        final android.graphics.Bitmap icon = icons.get(notice.appId());
        if (icon != null) {
            note.setLargeIcon(icon);
        }
        return note.setGroup(APP + notice.appId());
    }

    private void showStack(Inbox.Notice notice) {
        mNotes.notify(TAG_APP, notice.appId().hashCode(), asApp(new Notification.Builder(this,
                QUIET).setSmallIcon(icon(notice.category())).setGroupSummary(true)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
                .setContentIntent(page()), notice).build());
    }

    private void showNote(Inbox.Notice notice) {
        // What was on the phone before the r1 came by, or came in silently there, makes no
        // sound here either
        final boolean quiet = notice.preExisting() || notice.silent();
        final String text = text(notice);
        final Notification.Builder note = asApp(new Notification.Builder(this,
                quiet ? QUIET : channel(notice))
                .setSmallIcon(icon(notice.category())).setContentTitle(notice.title())
                .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                .setOnlyAlertOnce(true).setContentIntent(page()).setAutoCancel(false), notice);
        if (notice.date() != null) {
            note.setShowWhen(true).setWhen(notice.date().atZone(ZoneId.systemDefault())
                    .toInstant().toEpochMilli());
        }
        if (notice.negativeAction()) {
            // Swiped away here, cleared on the phone
            note.setDeleteIntent(no(notice.uid()));
        }
        mNotes.notify(TAG_NOTE, (int) notice.uid(), note.build());
    }

    private void showCall(Inbox.Notice notice) {
        final Notification.Builder note = new Notification.Builder(this, CALLS)
                .setSmallIcon(R.drawable.ic_call).setCategory(Notification.CATEGORY_CALL)
                .setContentTitle(notice.title().isEmpty() ? getString(R.string.calling_unknown)
                        : getString(R.string.calling, notice.title()))
                .setContentText(getString(R.string.calling_text))
                .setVisibility(Notification.VISIBILITY_PUBLIC).setOngoing(true)
                .setOnlyAlertOnce(true).setContentIntent(page());
        if (notice.negativeAction()) {
            note.addAction(new Notification.Action.Builder(null,
                    notice.negativeLabel().isEmpty() ? getString(R.string.decline)
                            : notice.negativeLabel(), no(notice.uid())).build());
        }
        mNotes.notify(TAG_CALL, 0, note.build());
    }
}
