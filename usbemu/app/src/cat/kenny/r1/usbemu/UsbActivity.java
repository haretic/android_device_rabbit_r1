// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.usbemu;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.fragment.app.FragmentActivity;
import cat.kenny.r1.wheel.WheelSelect;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Native rendition of the approved 01-06 mockups, using UsbMode's workflows. */
public final class UsbActivity extends FragmentActivity {
    private static final int TREE = 1, IMPORT = 2;
    private static final int INK = 0xffd9e3e5, MUTED = 0xffa4afb3, ACCENT = 0xffa7d6e1;
    private static final int CARD = 0xff171717, GUIDE = 0xff131d20, CYAN = 0xff142125;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<View> targets = new ArrayList<>();
    private final ArrayList<String> requests = new ArrayList<>();
    private String page = "hub", previous = "hub", fingerprint = "", renderedScreen = "";
    private Images selected, nextDisc;
    private boolean readOnly, connected, configured, receiverRegistered;
    private String lastJob = "", createName = "transfer", filesystem = "fat32", uiError = "";
    private int gb = 4;
    private long lastCompletion;
    private float scale = 1;
    private boolean landscape, combineInput, shiftKeys, numericKeys;
    private String draft = "";
    private HidInput hid;
    private int inputEpoch;
    private String inputGeneration = "";
    private View touchpad;
    private float touchX, touchY, startX, startY;
    private boolean moved, inputUsed;
    private static final int MICROPHONE = 3;
    private FrameLayout root;
    private LinearLayout body;
    private ScrollView scroll;
    private ImageView back;
    private Typeface regular, medium;
    private View initialFocus, scrollModeButton;
    private boolean scrollMode;
    private final BroadcastReceiver usb = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            connected = i.getBooleanExtra("connected", false);
            configured = i.getBooleanExtra("configured", false);
            refresh();
        }
    };
    private final Runnable poll = new Runnable() {
        @Override public void run() { refresh(); handler.postDelayed(this, 350); }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (UserHandle.myUserId() != 0) { finish(); return; }
        WheelSelect.attach(this);
        getWindow().setDecorFitsSystemWindows(false);
        if (saved != null) {
            combineInput = saved.getBoolean("combineInput"); draft = saved.getString("draft", "");
            page = saved.getString("page", "hub");
            previous = saved.getString("previous", "hub");
            createName = saved.getString("name", "transfer");
            filesystem = saved.getString("fs", "fat32"); gb = saved.getInt("gb", 4);
            readOnly = saved.getBoolean("ro");
            selected = restoreImage(saved, "selected"); nextDisc = restoreImage(saved, "next");
            ArrayList<String> pending = saved.getStringArrayList("requests");
            if (pending != null) requests.addAll(pending);
            uiError = saved.getString("uiError", "");
        } else if (Control.running() && !"android.service.quicksettings.action.QS_TILE_PREFERENCES".equals(getIntent().getAction())) page = "session";
        if ((page.equals("review") && selected == null) || (page.equals("confirm") && nextDisc == null)) page = "images";
        lastCompletion = saved == null ? Control.prefs(this).getLong("completion", 0) : saved.getLong("completion");
        receiverRegistered = true;
        Intent sticky = registerReceiver(usb, new IntentFilter("android.hardware.usb.action.USB_STATE"), Context.RECEIVER_EXPORTED);
        if (sticky != null) { connected = sticky.getBooleanExtra("connected", false); configured = sticky.getBooleanExtra("configured", false); }
        if (Control.running() || Control.busy(this)) Control.request(this, "observe", null, true);
        render(false);
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if ("android.service.quicksettings.action.QS_TILE_PREFERENCES".equals(intent.getAction())) navigate("hub");
    }
    @Override protected void onResume() { super.onResume(); if (UserHandle.myUserId() == 0) handler.post(poll); }
    @Override protected void onPause() { handler.removeCallbacks(poll); closeInput(); super.onPause(); }
    @Override protected void onDestroy() { if (receiverRegistered) unregisterReceiver(usb); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("combineInput", combineInput); out.putString("draft", draft);
        out.putString("page", page); out.putString("previous", previous); out.putString("name", createName);
        out.putString("fs", filesystem); out.putInt("gb", gb); out.putBoolean("ro", readOnly);
        saveImage(out, "selected", selected); saveImage(out, "next", nextDisc);
        out.putStringArrayList("requests", requests); out.putString("uiError", uiError);
        out.putLong("completion", lastCompletion);
    }
    private void saveImage(Bundle out, String key, Images image) {
        if (image != null) { out.putString(key, image.name); out.putString(key + "Stamp", image.stamp); out.putBoolean(key + "Ro", image.readOnly); }
    }
    private Images restoreImage(Bundle in, String key) {
        if (!in.containsKey(key)) return null;
        try { return new Images(in.getString(key), in.getString(key + "Stamp"), in.getBoolean(key + "Ro")); }
        catch (RuntimeException e) { return null; }
    }
    private void closeInput() { inputEpoch++; if (hid != null) hid.close(); hid = null; inputGeneration = ""; inputUsed = false; }
    private void syncInput() {
        String generation = Control.prop("st.generation");
        if (!Control.input() || !(page.equals("session") || page.equals("pointer") || page.equals("typing"))
                || generation.equals("0") || generation.isEmpty()) { closeInput(); return; }
        if (hid == null || !generation.equals(inputGeneration)) {
            closeInput(); inputGeneration = generation;
            final int epoch = inputEpoch;
            hid = new HidInput(generation, () -> handler.post(() -> {
                if (epoch == inputEpoch) {
                    // A passive reconnect to an older session must not create a
                    // launch banner. Report failure only after this UI used it.
                    if (inputUsed) uiError = "input";
                    closeInput();
                }
            }));
        }
    }
    private void refresh() {
        syncInput();
        boolean busy = Control.busy(this);
        long completion = Control.prefs(this).getLong("completion", 0);
        // A service or root result can outlive an activity, or belong to QS.
        // Only completions carrying a token submitted by this activity own its UI.
        if (completion != lastCompletion) {
            String token = Control.prefs(this).getString("completionToken", "");
            if (requests.remove(token)) {
                lastJob = Control.prefs(this).getString("completionJob", "");
                String error = Control.prefs(this).getString("completionError", "");
                uiError = error.equals("cancelled") ? "" : error;
                if (!busy && error.isEmpty()) {
                    if (Control.running() && !lastJob.equals("scan")) page = "session";
                    else if (!lastJob.equals("scan")) page = lastJob.equals("stop") ? "hub" : "images";
                } else if (!busy && error.equals("cancelled")) page = lastJob.equals("create") ? "create" : "images";
            } else uiError = "";
        }
        lastCompletion = completion;
        String state = page + busy + connected + configured + Control.prop("st.session")
                + Control.prop("st.stage") + Control.prop("st.percent") + Control.prop("st.result")
                + Control.prefs(this).getInt("importPercent", 0)
                + Images.session()[1] + Control.hasFolder(this) + uiError
                + (Control.prop("st.session").equals("sound") ? AudioBridge.micMuted + ":" + AudioBridge.speakerMuted + AudioBridge.status + AudioBridge.playbackLevel + ":" + AudioBridge.captureLevel : "");
        if (!state.equals(fingerprint)) { fingerprint = state; render(true); }
    }
    private int px(float value) { return Math.round(value * scale); }
    private String s(int id, Object... values) { return getString(id, values); }
    private void navigate(String next) { previous = page; page = next; uiError = ""; scrollMode = false; fingerprint = ""; render(false); }
    private void request(String action, Images image, boolean ro) {
        uiError = "";
        if (action.equals("cancel")) Control.request(this, action, image, ro);
        else { requests.clear(); requests.add(Control.request(this, action, image, ro)); }
        refresh();
    }
    private void scan() { if (Control.hasFolder(this) && !Control.busy(this)) request("scan", null, true); }

    private void render(boolean retainFocus) {
        if (!Control.busy(this) && (page.equals("session") && !Control.running()
                || (page.equals("pointer") || page.equals("typing")) && !Control.input())) page = "hub";
        String job = Control.prefs(this).getString("job", "");
        String screen = Control.busy(this) && !job.equals("scan") ? "progress:" + job : page;
        View focused = getCurrentFocus();
        Object focusKey = retainFocus && screen.equals(renderedScreen) && focused != null ? focused.getTag() : null;
        int scrollY = retainFocus && screen.equals(renderedScreen) && scroll != null ? scroll.getScrollY() : 0;
        renderedScreen = screen;
        targets.clear(); initialFocus = null; touchpad = null; scrollModeButton = null;
        landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        // All dimensions are mockup pixels, independent of the 1.16 Android density.
        scale = getResources().getDisplayMetrics().widthPixels / (landscape ? 640f : 480f);
        // The system's own sans faces: the mockups' Google Sans can't ship in the build
        regular = Typeface.create("sans-serif", Typeface.NORMAL);
        medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, 0, bars.right, 0); return insets;
        });
        setContentView(root); root.requestApplyInsets();
        LinearLayout toolbar = row(); toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(px(28), 0, px(20), 0);
        FrameLayout.LayoutParams bar = new FrameLayout.LayoutParams(-1, px(landscape ? 56 : 60));
        bar.topMargin = px(28); root.addView(toolbar, bar);
        back = new ImageView(this); back.setImageResource(R.drawable.ic_arrow_back);
        back.setScaleType(ImageView.ScaleType.CENTER_INSIDE); back.setPadding(px(10), px(10), px(10), px(10));
        back.setContentDescription(s(R.string.back)); back.setBackground(background(0xff202a2d, 23));
        toolbar.addView(back, new LinearLayout.LayoutParams(px(46), px(46)));
        TextView title = single(s(title()), 26, INK); title.setTypeface(medium);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1); tp.leftMargin = px(22); toolbar.addView(title, tp);
        scroll = new ScrollView(this); scroll.setFillViewport(false); scroll.setClipToPadding(false);
        scroll.setSmoothScrollingEnabled(false); scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(false); scroll.setPadding(0, 0, 0, px(landscape ? 28 : 37));
        int top = screen.startsWith("progress:") ? 96 : page.equals("hub") ? (landscape ? 86 : 91)
                : page.equals("session") ? (Control.input() || Control.prop("st.session").equals("sound") ? 92 : landscape ? 91 : 96)
                : page.equals("pointer") || page.equals("typing") ? 92 : page.equals("confirm") ? 98 : page.equals("import") ? 95 : 96;
        int bottom = landscape ? (page.equals("session") && !Control.input() && !Control.prop("st.session").equals("sound") ? 55 : 52)
                : page.equals("hub") ? 63 : 61;
        FrameLayout.LayoutParams content = new FrameLayout.LayoutParams(-1, -1);
        content.setMargins(px(18), px(top), px(18), px(bottom)); root.addView(scroll, content);
        body = column(); scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        if (!uiError.isEmpty()) errorBanner();
        // Banner actions remain reachable without taking initial focus from the page.
        List<View> bannerTargets = new ArrayList<>(targets); targets.clear();
        boolean busy = Control.busy(this);
        if (busy && !job.equals("scan")) progressPage(job);
        else switch (page) {
            case "images": imagesPage(false); break;
            case "review": reviewPage(); break;
            case "import": importPage(); break;
            case "create": createPage(); break;
            case "size": sizePage(); break;
            case "session": sessionPage(); break;
            case "pointer": pointerPage(); break;
            case "typing": typingPage(); break;
            case "swap": imagesPage(true); break;
            case "confirm": confirmPage(); break;
            default: hubPage(); break;
        }
        LinearLayout foot = row(); foot.setGravity(Gravity.CENTER_VERTICAL);
        ImageView wheel = icon(R.drawable.ic_wheel, ACCENT);
        LinearLayout.LayoutParams wi = new LinearLayout.LayoutParams(px(19), px(19)); wi.rightMargin = px(8); foot.addView(wheel, wi);
        TextView hint = single(s(footer()), 14, MUTED); foot.addView(hint, new LinearLayout.LayoutParams(0, -2, 1));
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(-1, px(landscape ? 20 : 29), Gravity.BOTTOM);
        fp.setMargins(px(28), 0, px(24), px(landscape ? 23 : 24)); root.addView(foot, fp);
        targets.addAll(bannerTargets); target(back, this::goBack); wrapTargets();
        View next = initialFocus != null ? initialFocus : targets.isEmpty() ? null : targets.get(0);
        if (focusKey != null) for (View candidate : targets) if (focusKey.equals(candidate.getTag())) { next = candidate; break; }
        final View focusTarget = next;
        scroll.post(() -> {
            scroll.scrollTo(0, scrollY);
            if (focusTarget != null && focusTarget.isEnabled()) { focusTarget.requestFocus(); reveal(focusTarget); }
        });
    }
    private int footer() {
        if (page.equals("typing")) return R.string.typing_footer;
        if (page.equals("pointer")) return R.string.pointer_footer;
        if (page.equals("session")) {
            if (Control.input()) return R.string.input_footer;
            if (Control.prop("st.session").equals("sound")) return adb() ? R.string.sound_footer : R.string.sound_footer_no_adb;
            return adb() ? R.string.session_footer : R.string.session_footer_no_adb;
        }
        return R.string.wheel_footer;
    }
    private void errorBanner() {
        LinearLayout banner = column(); banner.setPadding(px(16), px(12), px(16), px(8));
        banner.setBackground(background(0xff30291f, 18)); add(body, banner, -2, 0);
        guideContent(banner, R.drawable.ic_info, s(R.string.problem), errorText(uiError), 0xff30291f);
        LinearLayout actions = row(); actions.setGravity(Gravity.END); add(banner, actions, 36, 4);
        if (!Control.running() && (uiError.equals("recovery") || uiError.equals("cleanup") || uiError.equals("hal_timeout"))) {
            tonalAction(actions, s(R.string.stop), () -> request("stop", null, true));
        }
        tonalAction(actions, s(R.string.dismiss), () -> { uiError = ""; fingerprint = ""; render(false); });
        View gap = new View(this); add(body, gap, 10, 0);
    }
    private void tonalAction(LinearLayout parent, String label, Runnable click) {
        TextView button = single(label, 15, ACCENT); button.setGravity(Gravity.CENTER);
        button.setPadding(px(12), 0, px(12), 0); button.setBackground(background(0xff40392f, 18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -1); lp.leftMargin = px(8); parent.addView(button, lp);
        target(button, click);
    }
    private int title() {
        if (Control.busy(this)) {
            String job = Control.prefs(this).getString("job", "");
            if (job.equals("create")) return R.string.creating;
            if (job.equals("import")) return R.string.import_title;
        }
        switch (page) {
            case "images": return R.string.drive_title;
            case "review": return selected != null && selected.iso ? R.string.use_cd : R.string.use_drive;
            case "import": return R.string.import_title;
            case "create": return R.string.create_title;
            case "size": return R.string.drive_size;
            case "pointer": return R.string.move_pointer;
            case "typing": return R.string.type_text;
            case "session": return Control.prop("st.session").equals("sound") ? R.string.sound
                    : Control.input() ? R.string.keyboard : R.string.session_title;
            case "swap": case "confirm": return R.string.change_disc;
            default: return R.string.app_name;
        }
    }
    private void hubPage() {
        TextView breadcrumb = single(s(R.string.breadcrumb), 14, MUTED); breadcrumb.setPadding(px(10), 0, 0, 0);
        add(body, breadcrumb, 20, 0); connection(body);
        LinearLayout choices = landscape ? column() : stack(); add(body, choices, -2, landscape ? 9 : 10);
        if (landscape) {
            LinearLayout top = row(), bottom = row(); add(choices, top, -2, 0); add(choices, bottom, -2, 7);
            hubChoice(top, R.string.drive_title, R.string.drive_sub, R.drawable.ic_drive, 0xff5ad2f5, true);
            hubChoice(top, R.string.sound, R.string.sound_sub, R.drawable.ic_sound, 0xffffafe8, true);
            hubChoice(bottom, R.string.keyboard, R.string.input_sub, R.drawable.ic_keyboard, 0xffa1c7ff, true);
            hubChoice(bottom, R.string.flipper, R.string.coming_soon, R.drawable.ic_flipper, 0xffffb77e, false);
        } else {
            hubChoice(choices, R.string.drive_title, R.string.drive_sub, R.drawable.ic_drive, 0xff5ad2f5, true);
            hubChoice(choices, R.string.sound, R.string.sound_sub, R.drawable.ic_sound, 0xffffafe8, true);
            hubChoice(choices, R.string.keyboard, R.string.input_sub, R.drawable.ic_keyboard, 0xffa1c7ff, true);
            hubChoice(choices, R.string.flipper, R.string.coming_soon, R.drawable.ic_flipper, 0xffffb77e, false);
        }
        int combinedHeight = landscape ? 54 : 58;
        View combine = card(s(R.string.drive_input), s(R.string.combine_sub), R.drawable.ic_combo, 0, true,
                () -> { if (Control.running()) navigate("session"); else { combineInput = true; navigate("images"); scan(); } }, combinedHeight);
        cardText(combine, 18, 15); add(body, combine, -2, landscape ? 9 : 10);
        combinationStyle(combine);
        View unavailable = card(s(R.string.flipper_combo), s(R.string.combo_unavailable), R.drawable.ic_combo, 0, false, null, combinedHeight);
        cardText(unavailable, 17, 15); disabled(unavailable); add(body, unavailable, -2, landscape ? 9 : 10);
        combinationStyle(unavailable);
    }
    private void combinationStyle(View card) {
        card.setPadding(px(18), card.getPaddingTop(), px(20), card.getPaddingBottom());
        View icon = ((ViewGroup) card).getChildAt(0);
        LinearLayout.LayoutParams ip = (LinearLayout.LayoutParams) icon.getLayoutParams(); ip.width = ip.height = px(25); ip.rightMargin = px(12);
    }
    private void hubChoice(LinearLayout parent, int title, int sub, int icon, int color, boolean enabled) {
        View item = card(s(title), s(sub), icon, color, enabled,
                () -> {
                    if (Control.running()) { navigate("session"); return; }
                    if (title == R.string.sound || title == R.string.keyboard) request(title == R.string.sound ? "sound" : "input", null, true);
                    else { combineInput = false; navigate("images"); scan(); }
                }, landscape ? 90 : 77);
        if (!enabled) disabled(item);
        if (landscape) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (parent.getChildCount() > 0) lp.leftMargin = px(7); parent.addView(item, lp);
        } else add(parent, item, 77, parent.getChildCount() == 0 ? 0 : 2);
    }
    private void connection(LinearLayout parent) {
        boolean hub = page.equals("hub"), sound = Control.prop("st.session").equals("sound");
        String title = configured ? s(R.string.pc_connected) : connected ? s(R.string.cable_connected) : s(R.string.not_connected);
        String sub = configured ? (adb() ? s(R.string.adb_kept) : s(R.string.data_connected))
                : connected ? s(R.string.wait_pc) : s(R.string.plug_cable);
        int height = landscape && hub ? 48 : landscape ? 65 : sound && page.equals("session") ? 51 : 58;
        View connection = card(title, sub, R.drawable.ic_usb, 0, false, null, height);
        cardText(connection, landscape && !hub ? 17 : 18, landscape ? 14 : 15);
        connection.setPadding(px(15), px(3), px(15), px(3));
        ((LinearLayout.LayoutParams) ((ViewGroup) connection).getChildAt(0).getLayoutParams()).rightMargin = px(13);
        TextView detail = connection.findViewWithTag("detail"); ((LinearLayout.LayoutParams) detail.getLayoutParams()).topMargin = 0;
        detail.setTextColor(0xffa8c2c8);
        connection.setBackground(background(CYAN, 18)); add(parent, connection, height, hub ? (landscape ? 7 : 9) : 10);
    }
    private boolean adb() { return ("," + android.os.SystemProperties.get("sys.usb.config", "") + ",").contains(",adb,"); }
    private void folderButton() {
        action(body, s(Control.hasFolder(this) ? R.string.change_folder : R.string.choose_folder), false, this::chooseFolder, 52);
    }
    private LinearLayout[] columns(int leftWidth) {
        if (!landscape) return new LinearLayout[] {body, body};
        LinearLayout split = row(), left = column(), right = column(); add(body, split, -2, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(leftWidth), -2); lp.rightMargin = px(13); split.addView(left, lp);
        split.addView(right, new LinearLayout.LayoutParams(0, -2, 1)); return new LinearLayout[] {left, right};
    }
    private void imagesPage(boolean swap) {
        if (!swap && !Control.hasFolder(this)) {
            guide(body, R.drawable.ic_folder, s(R.string.choose_folder), s(R.string.folder_explanation), GUIDE);
            folderButton(); return;
        }
        List<Images> images = Images.inventory();
        LinearLayout[] panes = columns(330); LinearLayout list = panes[0], actions = panes[1];
        if (swap) {
            guide(actions, R.drawable.ic_lock, s(R.string.tray_notice), s(R.string.tray_detail), 0xff30291f);
            note(list, s(R.string.current_disc, Images.session()[1]), 14, landscape ? 0 : 10);
        } else {
            note(list, s(R.string.free_images, size(Images.free())), 16, 0);
            ProgressBar meter = meter(5, 0xff2c3234, 0xff75bdcf);
            meter.setProgress(Images.total() > 0 ? (int) (100 * (Images.total() - Images.free()) / Images.total()) : 0);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, px(5)); mp.setMargins(px(10), px(10), px(10), px(13)); list.addView(meter, mp);
        }
        if (Control.busy(this)) note(list, s(R.string.scanning), 16, 10);
        if (images.isEmpty() && !Control.busy(this)) note(list, s(R.string.no_images), 18, 14);
        LinearLayout rows = stack(); add(list, rows, -2, swap ? 10 : 0);
        for (Images image : images) {
            boolean current = swap && image.name.equals(Images.session()[1]);
            String sub = current ? s(R.string.current_image, size(image.bytes)) : description(image);
            if (swap && !image.iso) sub = s(R.string.not_cd);
            final boolean enabled = !swap || image.iso;
            View item = card(image.name, sub, image.iso ? R.drawable.ic_disc : R.drawable.ic_drive,
                    0xff91cbd9, enabled, () -> {
                        if (current) navigate("session");
                        else if (swap) { nextDisc = image; navigate("confirm"); }
                        else { selected = image; readOnly = image.readOnly; navigate("review"); }
                    }, swap ? 76 : 74);
            if (!enabled) disabled(item);
            if (current) {
                LinearLayout currentRow = (LinearLayout) item; currentRow.removeViewAt(currentRow.getChildCount() - 1);
                LinearLayout.LayoutParams check = new LinearLayout.LayoutParams(px(22), px(22)); check.leftMargin = px(10);
                currentRow.addView(icon(R.drawable.ic_check, ACCENT), check);
            }
            add(rows, item, swap ? 76 : 74, rows.getChildCount() == 0 ? 0 : 2);
        }
        if (swap) {
            action(actions, s(R.string.keep_disc), false, () -> navigate("session"), 58);
            note(actions, s(R.string.swap_explanation), 16, 8);
        } else {
            LinearLayout pair = landscape ? column() : row(); add(actions, pair, landscape ? -2 : 64, landscape ? 0 : 12);
            Runnable create = () -> {
                createName = Images.nextName(images); gb = (int) Math.min(4, Images.maximum(Images.free()) / Images.GB);
                if (gb < 1) gb = 1; navigate("create");
            };
            Runnable importing = () -> { navigate("import"); openImportPicker(); };
            if (landscape) {
                action(pair, s(R.string.create_title), false, create, 58);
                action(pair, s(R.string.import_title), false, importing, 58);
            } else {
                pairAction(pair, s(R.string.create_title), false, create);
                pairAction(pair, s(R.string.import_title), false, importing);
            }
            note(actions, s(R.string.images_explanation), 16, 12);
            action(actions, s(R.string.change_folder), false, this::chooseFolder, 42);
        }
    }
    private String description(Images image) {
        return s(image.iso ? R.string.cd_description : R.string.drive_description, size(image.bytes),
                s(image.readOnly ? R.string.read_only : R.string.writable));
    }
    private String size(long bytes) {
        return String.format(Locale.getDefault(), bytes >= Images.GB ? s(R.string.gb_format) : s(R.string.mb_format),
                bytes / (bytes >= Images.GB ? (double) Images.GB : 1048576.0));
    }
    private void reviewPage() {
        if (selected == null) { navigate("images"); return; }
        LinearLayout[] panes = columns(270); LinearLayout details = panes[0], choices = panes[1];
        hero(details, selected.name, s(selected.iso ? R.string.pc_cd : R.string.pc_drive, size(selected.bytes)),
                selected.iso ? R.drawable.ic_disc : R.drawable.ic_drive);
        LinearLayout accessGroup = stack(); add(details, accessGroup, -2, 10);
        View access = field(s(R.string.access), s(selected.iso ? R.string.always_read_only
                : readOnly ? R.string.access_read_only : R.string.access_writable), "", true,
                !selected.readOnly ? () -> { readOnly = !readOnly; render(true); } : null);
        add(accessGroup, access, 69, 0);
        TextView label = single(s(R.string.use_together), 14, ACCENT); label.setPadding(px(16), 0, 0, 0);
        add(choices, label, 20, landscape ? 0 : 8);
        LinearLayout combinations = stack(); add(choices, combinations, -2, 10);
        View drive = card(s(R.string.drive_only), s(R.string.adb_included),
                combineInput ? R.drawable.ic_radio : R.drawable.ic_radio_on, 0, true,
                () -> { combineInput = false; render(true); }, 54);
        cardText(drive, 18, 15); add(combinations, drive, 54, 0);
        View combo = card(s(R.string.drive_input), s(R.string.input_pairing),
                combineInput ? R.drawable.ic_radio_on : R.drawable.ic_radio, 0, true,
                () -> { combineInput = true; render(true); }, 54);
        cardText(combo, 18, 15); add(combinations, combo, 54, 2);
        View unavailable = card(s(R.string.add_sound_flipper), s(R.string.separate_session), 0, 0, false, null, 50);
        cardText(unavailable, 17, 15); disabled(unavailable); add(combinations, unavailable, 50, 2);
        View start = action(choices, s(selected.iso ? R.string.start_cd : R.string.start_drive), true,
                () -> request(combineInput ? "start_input" : "start", selected, readOnly), 58);
        note(landscape ? details : choices, s(selected.iso ? R.string.cd_guidance : R.string.drive_guidance), 15, 12);
        if (selected.iso) initialFocus = start;
    }
    private void createPage() {
        note(body, s(R.string.free_create, size(Images.free())), 16, 0);
        LinearLayout[] panes = columns(340); LinearLayout details = panes[0], buttons = panes[1];
        LinearLayout fields = stack(); add(details, fields, -2, 11);
        add(fields, field(s(R.string.drive_size), s(R.string.gb_integer, gb), s(R.string.choose), false, () -> navigate("size")), 81, 0);
        LinearLayout fs = column(); fs.setBackground(background(CARD, 0)); fs.setPadding(px(18), px(11), px(18), px(11));
        add(fields, fs, -2, 2); add(fs, single(s(R.string.file_system), 15, MUTED), 20, 0);
        LinearLayout pair = row(); add(fs, pair, 43, 7);
        segment(pair, s(R.string.fat32), filesystem.equals("fat32"), () -> { filesystem = "fat32"; render(true); });
        segment(pair, s(R.string.exfat), filesystem.equals("exfat"), () -> { filesystem = "exfat"; render(true); });
        TextView hint = text(s(filesystem.equals("fat32") ? R.string.fat_hint : R.string.exfat_hint), 14, MUTED);
        hint.setLineHeight(px(18)); add(fs, hint, -2, 7);
        fs.setMinimumHeight(px(122));
        add(fields, field(s(R.string.image_name), createName + ".img", s(R.string.edit), false, this::editName), 81, 2);
        LinearLayout actions = landscape ? column() : row(); add(buttons, actions, landscape ? -2 : 64, 13);
        boolean enough = gb * Images.GB <= Images.maximum(Images.free());
        if (landscape) {
            action(actions, s(R.string.create_only), false, enough ? () -> create(false) : null, 58);
            action(actions, s(R.string.create_use), true, enough ? () -> create(true) : null, 58);
        } else {
            pairAction(actions, s(R.string.create_only), false, enough ? () -> create(false) : null);
            pairAction(actions, s(R.string.create_use), true, enough ? () -> create(true) : null);
        }
        note(buttons, s(enough ? R.string.create_explanation : R.string.not_enough), 15, 12);
    }
    private void segment(LinearLayout parent, String label, boolean selected, Runnable click) {
        TextView button = single(label, 18, selected ? 0xffc7ebf3 : INK); button.setGravity(Gravity.CENTER);
        GradientDrawable normal = shape(selected ? 0xff254650 : CARD, 13, false); normal.setStroke(px(1), selected ? ACCENT : 0xff59666a);
        StateListDrawable states = new StateListDrawable(); states.addState(new int[] {android.R.attr.state_focused}, focused(selected ? 0xff254650 : CARD, 13));
        states.addState(new int[] {}, normal); button.setBackground(states);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1); if (parent.getChildCount() > 0) lp.leftMargin = px(8); parent.addView(button, lp);
        target(button, click);
    }
    private void create(boolean use) {
        if (!Images.createName(createName)) { uiError = "name"; render(false); return; }
        uiError = ""; requests.clear(); requests.add(Control.create(this, createName, gb, filesystem, use)); refresh();
    }
    private void sizePage() {
        note(body, s(R.string.size_hint), 16, 0);
        LinearLayout[] panes = columns(330); LinearLayout rows = stack(); add(panes[0], rows, -2, 12);
        for (int value : new int[] {1, 2, 4, 8, 16}) {
            boolean enough = value * Images.GB <= Images.maximum(Images.free());
            View item = card(s(R.string.gb_integer, value), enough
                    ? s(R.string.stays_free, size(Images.free() - value * Images.GB)) : s(R.string.not_enough_free, size(Images.free())),
                    value == gb ? R.drawable.ic_radio_on : R.drawable.ic_radio, 0, enough,
                    () -> { gb = value; render(true); }, enough ? 61 : 59);
            cardText(item, enough ? 20 : 17, 15);
            if (!enough) disabled(item); else if (value == gb) initialFocus = item;
            add(rows, item, enough ? 61 : 59, rows.getChildCount() == 0 ? 0 : 2);
        }
        action(panes[1], s(R.string.keep_size, gb), true, () -> navigate("create"), 64);
    }
    private void editName() {
        EditText input = new EditText(this); input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setText(createName); input.selectAll();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.image_name).setView(input)
                .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null).create();
        dialog.setOnShowListener(ignored -> {
            WheelSelect.attach(dialog);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String name = input.getText().toString();
                if (!Images.createName(name)) { input.setError(s(R.string.name_error)); return; }
                createName = name; dialog.dismiss(); render(true);
            });
        });
        dialog.show();
    }
    private void importPage() {
        LinearLayout[] panes = columns(330);
        guide(panes[0], R.drawable.ic_info, s(R.string.document_picker), s(R.string.import_explanation), GUIDE);
        action(panes[1], s(R.string.browse), true, this::openImportPicker, 64);
        note(panes[1], s(R.string.import_copy), 16, 12);
    }
    private void openImportPicker() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(picker, IMPORT);
    }
    private void progressPage(String job) {
        boolean importing = job.equals("import");
        if (job.equals("stop")) { note(body, s(R.string.stopping), 22, 0); return; }
        if (!job.equals("create") && !importing) {
            note(body, s(R.string.preparing), 22, 0);
            action(body, s(R.string.stop), true, () -> request("stop", null, true), 64); return;
        }
        LinearLayout[] panes = columns(246); LinearLayout summary = panes[0], details = panes[1];
        hero(summary, importing ? s(R.string.import_title) : Control.prefs(this).getString("jobImage", createName + ".img"),
                importing ? s(R.string.import_copy) : s(R.string.create_description,
                        Control.prefs(this).getInt("jobSize", gb), s("fat32".equals(Control.prefs(this).getString("jobFs", filesystem))
                                ? R.string.fat32 : R.string.exfat)), R.drawable.ic_drive);
        LinearLayout panel = column(); panel.setPadding(px(20), px(17), px(20), px(17)); panel.setBackground(background(CARD, 24));
        add(details, panel, -2, landscape ? 0 : 12); panel.setMinimumHeight(px(importing ? 180 : 262));
        add(panel, single(s(importing ? R.string.copying : R.string.creating_blank), 18, INK), 23, 0);
        int percent = importing ? Control.prefs(this).getInt("importPercent", 0) : parsePercent();
        TextView number = single(percent < 0 ? s(R.string.working) : s(R.string.percent, percent), 44, ACCENT); number.setTypeface(medium); add(panel, number, 52, 4);
        ProgressBar progress = meter(8, 0xff354348, ACCENT);
        progress.setIndeterminate(percent < 0); progress.setProgress(percent); add(panel, progress, 8, 10);
        TextView stage = single(stageText(Control.prop("st.stage")), 16, MUTED); add(panel, stage, 21, 12);
        if (!importing) {
            step(panel, R.drawable.ic_check, R.string.step_check, 10);
            step(panel, R.drawable.ic_drive, R.string.step_allocate, 6);
            step(panel, R.drawable.ic_file, R.string.step_format, 6);
        }
        action(landscape ? summary : details, s(importing ? R.string.cancel_import : R.string.cancel_creation), false,
                () -> request("cancel", null, true), 57);
        note(landscape ? summary : details, s(importing ? R.string.import_copy : R.string.creation_progress_hint), 16, 12);
    }
    private void step(LinearLayout parent, int resource, int label, int margin) {
        LinearLayout step = row(); step.setGravity(Gravity.CENTER_VERTICAL); add(parent, step, 24, margin);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(px(22), px(22)); ip.rightMargin = px(12); step.addView(icon(resource, ACCENT), ip);
        step.addView(single(s(label), 17, MUTED), new LinearLayout.LayoutParams(0, -2, 1));
    }
    private ProgressBar meter(int height, int track, int fill) {
        ProgressBar meter = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        android.graphics.drawable.ClipDrawable progress = new android.graphics.drawable.ClipDrawable(shape(fill, height / 2, false), Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[] {shape(track, height / 2, false), progress});
        layers.setId(0, android.R.id.background); layers.setId(1, android.R.id.progress); meter.setProgressDrawable(layers);
        meter.setProgressTintList(android.content.res.ColorStateList.valueOf(fill)); meter.setMax(100); return meter;
    }
    private int parsePercent() { try { return Integer.parseInt(Control.prop("st.percent")); } catch (NumberFormatException e) { return 0; } }
    private String stageText(String stage) {
        switch (stage) {
            case "allocate": return s(R.string.allocating);
            case "format": return s(R.string.formatting);
            case "save": return s(R.string.saving);
            case "connecting": return s(R.string.preparing);
            default: return s(R.string.checking);
        }
    }
    private void sessionPage() {
        if (!Control.running()) { page = "hub"; hubPage(); return; }
        if (Control.prop("st.session").equals("sound")) { soundPage(); return; }
        if (Control.input()) { inputPage(); return; }
        String[] session = Images.session(); boolean cd = Control.prop("st.session").startsWith("cd");
        LinearLayout[] panes = columns(270); LinearLayout left = panes[0], right = panes[1];
        View stop = action(left, s(R.string.stop), true, () -> request("stop", null, true), landscape ? 66 : 64);
        ((LinearLayout.LayoutParams) stop.getLayoutParams()).topMargin = 0;
        connection(left);
        if (landscape) {
            note(left, s(cd ? (adb() ? R.string.pc_cd_hint : R.string.pc_cd_hint_no_adb) : R.string.drive_guidance), 15, 13);
            TextView persistence = text(s(R.string.session_persistence), 14, MUTED); persistence.setLineHeight(px(19));
            persistence.setPadding(px(14), px(12), px(14), px(12)); persistence.setBackground(background(GUIDE, 18)); add(left, persistence, -2, 11);
        }
        LinearLayout seen = column(); seen.setBackground(background(CARD, 22));
        seen.setPadding(px(landscape ? 14 : 18), px(12), px(landscape ? 14 : 18), px(landscape ? 14 : 15));
        add(right, seen, -2, landscape ? 0 : 11); add(seen, single(s(R.string.pc_sees), 14, ACCENT), 20, 0);
        LinearLayout device = row(); device.setGravity(Gravity.CENTER_VERTICAL); add(seen, device, -2, landscape ? 10 : 12);
        int side = landscape ? 31 : 38;
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(px(side), px(side)); ip.rightMargin = px(landscape ? 10 : 15);
        device.addView(icon(cd ? R.drawable.ic_disc : R.drawable.ic_drive, ACCENT), ip);
        LinearLayout copy = column(); device.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        String[] name = s(cd ? R.string.cd_name : R.string.drive_name, session[1]).split("\n", 2);
        TextView kind = single(name[0], landscape ? 20 : 21, INK); kind.setTypeface(medium); add(copy, kind, landscape ? 25 : 27, 0);
        TextView filename = single(session[1], landscape ? 20 : 21, INK); filename.setTypeface(medium); add(copy, filename, landscape ? 25 : 27, 0);
        add(copy, single(s(R.string.size_access, size(Long.parseLong(session[3])), s(session[2].equals("1") ? R.string.read_only : R.string.writable)), landscape ? 15 : 16, MUTED), landscape ? 20 : 22, 0);
        if (cd) {
            View change = card(s(R.string.change_disc), s(R.string.swap_sub), R.drawable.ic_swap, 0, true,
                    () -> { navigate("swap"); scan(); }, landscape ? 73 : 68);
            cardText(change, landscape ? 19 : 20, landscape ? 14 : 15); add(right, change, landscape ? 73 : 68, 10);
            if (landscape) {
                change.setPadding(px(13), px(9), px(13), px(9));
                ((LinearLayout.LayoutParams) ((ViewGroup) change).getChildAt(0).getLayoutParams()).rightMargin = px(12);
            }
        } else if (!landscape) guide(right, R.drawable.ic_info, s(R.string.eject_title), s(R.string.eject_guidance), GUIDE);
        guide(right, R.drawable.ic_info, s(R.string.qs_stop), s(R.string.qs_guidance), GUIDE);
    }
    private void stopButton(LinearLayout parent) {
        int height = Control.prop("st.session").equals("sound") ? 58 : 55;
        View stop = action(parent, s(R.string.stop), true, () -> request("stop", null, true), height);
        ((LinearLayout.LayoutParams) stop.getLayoutParams()).topMargin = 0;
    }
    private LinearLayout sessionColumns() {
        if (!landscape) { stopButton(body); return body; }
        LinearLayout[] panes = columns(192); stopButton(panes[0]);
        note(panes[0], s(page.equals("pointer") ? R.string.pointer_guidance : R.string.input_actions_hint), 15, 10);
        if (Control.prop("st.session").contains("_")) note(panes[0], s(R.string.shared_image, Images.session()[1]), 14, 10);
        return panes[1];
    }
    private void soundPage() {
        LinearLayout[] panes = columns(230); LinearLayout controls = panes[1];
        stopButton(panes[0]); connection(panes[0]);
        audioCard(controls, R.string.speaker, AudioBridge.speakerMuted ? R.string.speaker_muted : R.string.speaker_live,
                R.drawable.ic_sound, !AudioBridge.speakerMuted, AudioBridge.playbackLevel,
                () -> Control.request(this, "speaker", null, true));
        audioCard(controls, R.string.microphone, AudioBridge.micMuted ? R.string.mic_muted : R.string.mic_live,
                R.drawable.ic_mic, !AudioBridge.micMuted, AudioBridge.captureLevel, this::toggleMic);
        guide(landscape ? panes[0] : controls, R.drawable.ic_info, s(R.string.select_on_pc),
                s(AudioBridge.status.equals("error") ? R.string.audio_error : R.string.audio_guidance), GUIDE);
    }
    private void audioCard(LinearLayout parent, int title, int detail, int resource, boolean on, int level, Runnable click) {
        LinearLayout panel = column(); panel.setBackground(background(CARD, 22));
        panel.setPadding(px(17), px(11), px(17), px(11)); add(parent, panel, 126, 10);
        LinearLayout heading = row(); heading.setGravity(Gravity.CENTER_VERTICAL); add(panel, heading, 45, 0);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(px(27), px(27)); ip.rightMargin = px(12); heading.addView(icon(resource, ACCENT), ip);
        LinearLayout copy = column(); heading.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        TextView label = single(s(title), 19, INK); label.setTypeface(medium); label.setTag("title"); add(copy, label, 25, 0);
        add(copy, single(s(detail), 15, MUTED), 20, 0);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(px(50), px(30)); sp.leftMargin = px(12); heading.addView(toggle(on), sp);
        LinearLayout levels = row(); levels.setGravity(Gravity.CENTER_VERTICAL); add(panel, levels, 19, 14);
        for (int i = 0; i < 22; i++) {
            View bar = new View(this); bar.setBackground(shape(i * 96 / 22 < level ? 0xff86c6d7 : 0xff303b3f, 2, false));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, px(15), 1); if (i > 0) bp.leftMargin = px(4); levels.addView(bar, bp);
        }
        LinearLayout captions = row(); add(panel, captions, 18, 8);
        captions.addView(single(s(resource == R.drawable.ic_mic ? R.string.mic_input : R.string.playback), 14, MUTED), new LinearLayout.LayoutParams(0, -2, 1));
        captions.addView(single(s(R.string.level_db, level - 96), 14, MUTED));
        panel.setContentDescription(s(title) + s(R.string.text_separator) + s(detail)); target(panel, click);
    }
    private void toggleMic() {
        if (AudioBridge.micMuted && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {android.Manifest.permission.RECORD_AUDIO}, MICROPHONE);
        } else Control.request(this, "microphone", null, true);
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == MICROPHONE && results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
                && Control.prop("st.session").equals("sound") && AudioBridge.micMuted) {
            Control.request(this, "microphone", null, true);
        }
    }
    private void sessionBrief(LinearLayout parent) {
        LinearLayout brief = row(); brief.setGravity(Gravity.CENTER_VERTICAL); brief.setPadding(px(10), 0, px(10), 0);
        add(parent, brief, 38, 0);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(px(19), px(19)); ip.rightMargin = px(7); brief.addView(icon(R.drawable.ic_usb, ACCENT), ip);
        brief.addView(single(s(adb() ? R.string.input_connected_adb : R.string.input_connected), 15, 0xffb0c4c9), new LinearLayout.LayoutParams(0, -2, 1));
    }
    private FrameLayout pointerPad(boolean directions) {
        FrameLayout pad = new FrameLayout(this);
        GradientDrawable normal = shape(0xff172023, 23, false); normal.setStroke(px(1), 0xff3c5056);
        StateListDrawable states = new StateListDrawable(); states.addState(new int[] {android.R.attr.state_focused}, focused(0xff172023, 23));
        states.addState(new int[] {}, normal); pad.setBackground(states);
        ImageView symbol = icon(directions ? R.drawable.ic_mouse : R.drawable.ic_touch, ACCENT);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(px(35), px(35), Gravity.TOP | Gravity.CENTER_HORIZONTAL); ip.topMargin = px(directions ? 18 : 26); pad.addView(symbol, ip);
        TextView title = single(s(directions ? R.string.pointer_title : R.string.touchpad_title), directions ? 21 : 22, INK);
        title.setTypeface(medium); title.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-1, px(28)); tp.setMargins(px(10), px(directions ? 62 : 74), px(10), 0); pad.addView(title, tp);
        TextView hint = single(s(directions ? R.string.pointer_hint : R.string.touchpad_hint), directions ? 14 : 15, 0xffa7c0c7); hint.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-1, px(20)); hp.setMargins(px(10), px(directions ? 98 : 110), px(10), 0); pad.addView(hint, hp);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(px(25), px(29), Gravity.TOP | Gravity.RIGHT); cp.topMargin = px(45); cp.rightMargin = px(73); pad.addView(icon(R.drawable.ic_pointer, ACCENT), cp);
        pad.setContentDescription(s(directions ? R.string.pointer_title : R.string.touchpad_title)); return pad;
    }
    private void inputPage() {
        LinearLayout panel = sessionColumns();
        if (!landscape) sessionBrief(panel);
        FrameLayout pad = pointerPad(false); touchpad = pad;
        target(pad, () -> click(1));
        pad.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    v.requestFocus(); v.getParent().requestDisallowInterceptTouchEvent(true);
                    startX = touchX = event.getX(); startY = touchY = event.getY(); moved = false; return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (Math.hypot(event.getX() - startX, event.getY() - startY) > px(6)) moved = true;
                    int x = (int) ((event.getX() - touchX) / scale), y = (int) ((event.getY() - touchY) / scale);
                    if (hid != null && (x != 0 || y != 0)) { inputUsed = true; hid.mouse(0, x, y, 0); }
                    touchX += x * scale; touchY += y * scale; return true;
                case android.view.MotionEvent.ACTION_UP:
                    if (!moved) v.performClick(); v.getParent().requestDisallowInterceptTouchEvent(false); return true;
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.getParent().requestDisallowInterceptTouchEvent(false); return true;
                default: return true;
            }
        });
        add(panel, pad, landscape ? 140 : 168, 0);
        LinearLayout clicks = row(); add(panel, clicks, 49, 8); clickActions(clicks);
        LinearLayout pair = row(); add(panel, pair, 49, 8);
        pairAction(pair, s(R.string.type_text), false, () -> navigate("typing"));
        pairAction(pair, s(R.string.move_pointer), false, () -> navigate("pointer"));
        TextView scrolling = (TextView) action(panel, s(scrollMode ? R.string.scroll_pc_active : R.string.scroll_pc), false, () -> {
            scrollMode = !scrollMode;
            if (scrollModeButton instanceof TextView) iconLabel((TextView) scrollModeButton, s(scrollMode ? R.string.scroll_pc_active : R.string.scroll_pc), R.drawable.ic_wheel, ACCENT);
        }, 54);
        ((LinearLayout.LayoutParams) scrolling.getLayoutParams()).topMargin = px(8);
        scrollModeButton = scrolling;
        scrolling.setTag(page + ":scroll-pc"); scrolling.setContentDescription(s(R.string.scroll_pc));
        if (!landscape) note(panel, s(R.string.input_actions_hint), 14, 8);
        if (Control.prop("st.session").contains("_") && !landscape) note(panel, s(R.string.shared_image, Images.session()[1]), 15, 8);
        if (Control.prop("st.session").equals("cd_input")) action(panel, s(R.string.change_disc), false,
                () -> { navigate("swap"); scan(); }, 48);
    }
    private void clickActions(LinearLayout parent) {
        pairAction(parent, s(R.string.left_click), false, () -> click(1));
        pairAction(parent, s(R.string.right_click), false, () -> click(2));
    }
    private void pointerPage() {
        if (!Control.input()) { navigate("hub"); return; }
        LinearLayout panel = sessionColumns(); if (!landscape) sessionBrief(panel);
        add(panel, pointerPad(true), landscape ? 136 : 152, 0);
        LinearLayout directions = row(); add(panel, directions, 46, 8);
        direction(directions, R.string.up, R.drawable.ic_up, 0, -16);
        direction(directions, R.string.right, R.drawable.ic_right, 16, 0);
        direction(directions, R.string.down, R.drawable.ic_down, 0, 16);
        direction(directions, R.string.left, R.drawable.ic_left, -16, 0);
        LinearLayout clicks = row(); add(panel, clicks, 47, 8); clickActions(clicks);
        View done = action(panel, s(R.string.pointer_done), false, () -> navigate("session"), 48);
        ((LinearLayout.LayoutParams) done.getLayoutParams()).topMargin = px(8);
        if (!landscape) note(panel, s(R.string.pointer_guidance), 15, 8);
    }
    private void direction(LinearLayout parent, int label, int icon, int x, int y) {
        pairAction(parent, s(label), false, () -> move(x, y));
        iconLabel((TextView) parent.getChildAt(parent.getChildCount() - 1), s(label), icon, ACCENT);
    }
    private void click(int button) { if (hid != null) { inputUsed = true; hid.click(button); } }
    private void move(int x, int y) { if (hid != null) { inputUsed = true; hid.mouse(0, x, y, 0); } }
    private void typingPage() {
        if (!Control.input()) { navigate("hub"); return; }
        LinearLayout[] panes = columns(210); LinearLayout draftPane = panes[0], keysPane = panes[1];
        note(draftPane, s(R.string.typing_hint), 16, 0);
        TextView preview = text(draft.isEmpty() ? s(R.string.draft_empty) : draft, 20, INK);
        preview.setTypeface(regular); preview.setLineHeight(px(28));
        GradientDrawable input = shape(0xff141b1e, 17, false); input.setStroke(px(1), 0xff5e777e);
        StateListDrawable states = new StateListDrawable(); states.addState(new int[] {android.R.attr.state_focused}, focused(0xff141b1e, 17)); states.addState(new int[] {}, input);
        preview.setBackground(states); preview.setPadding(px(16), px(12), px(16), px(12)); preview.setMinimumHeight(px(82)); add(draftPane, preview, -2, 10);
        LinearLayout send = landscape ? column() : row(); add(draftPane, send, landscape ? -2 : 49, 10);
        Runnable sendDraft = () -> {
            inputUsed = true;
            if (hid != null && hid.type(draft)) { draft = ""; navigate("session"); }
            else { uiError = "text"; render(true); }
        };
        View sendButton;
        if (landscape) {
            View cancel = action(send, s(R.string.cancel), false, () -> navigate("session"), 49);
            ((LinearLayout.LayoutParams) cancel.getLayoutParams()).topMargin = 0;
            sendButton = action(send, s(R.string.send_text), true, sendDraft, 49);
            ((LinearLayout.LayoutParams) sendButton.getLayoutParams()).topMargin = px(8);
        } else {
            pairAction(send, s(R.string.cancel), false, () -> navigate("session"));
            pairAction(send, s(R.string.send_text), true, sendDraft); sendButton = send.getChildAt(1);
        }
        initialFocus = sendButton;
        LinearLayout keyboard = column(); keyboard.setBackground(background(0xff1b2529, 23)); keyboard.setPadding(px(8), px(12), px(8), px(10));
        add(keysPane, keyboard, -2, landscape ? 0 : 14);
        add(keyboard, single(s(R.string.keyboard_layout), 14, MUTED), 20, 0);
        target(preview, () -> { keyboard.setVisibility(View.VISIBLE); });
        preview.setTag(page + ":draft");
        String keys = s(numericKeys ? R.string.number_keys : shiftKeys ? R.string.upper_keys : R.string.lower_keys);
        String[] rows = keys.split(" ");
        for (int i = 0; i < rows.length; i++) {
            LinearLayout row = row(); add(keyboard, row, 41, 5);
            for (char key : rows[i].toCharArray()) keyboardKey(row, Character.toString(key), 1, 20, () -> {
                if (draft.length() < 60) draft += key; preview.setText(draft);
            });
            if (i == rows.length - 1) keyboardKey(row, s(R.string.delete), 1.55f, 15, () -> {
                if (!draft.isEmpty()) draft = draft.substring(0, draft.length() - 1); preview.setText(draft.isEmpty() ? s(R.string.draft_empty) : draft);
            });
        }
        LinearLayout controls = row(); add(keyboard, controls, 41, 5);
        keyboardKey(controls, s(R.string.shift), 1.55f, 15, () -> { shiftKeys = !shiftKeys; render(true); });
        keyboardKey(controls, s(R.string.numbers), 1.55f, 15, () -> { numericKeys = !numericKeys; render(true); });
        keyboardKey(controls, s(R.string.space), 4, 17, () -> { if (draft.length() < 60) draft += " "; preview.setText(draft); });
        keyboardKey(controls, s(R.string.done), 1.55f, 15, () -> { keyboard.setVisibility(View.GONE); sendButton.requestFocus(); });
        note(keysPane, s(R.string.typing_keys_hint), 14, 8);
    }
    private void keyboardKey(LinearLayout parent, String label, float weight, int size, Runnable click) {
        TextView key = single(label, size, INK); key.setGravity(Gravity.CENTER); key.setBackground(background(0xff303d42, 9));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, weight); if (parent.getChildCount() > 0) lp.leftMargin = px(4); parent.addView(key, lp); target(key, click);
    }
    private void confirmPage() {
        if (nextDisc == null || !Control.running()) { navigate("session"); return; }
        LinearLayout[] panes = columns(330);
        LinearLayout panel = column(); panel.setPadding(px(20), px(18), px(20), px(18)); panel.setBackground(background(CARD, 25)); add(panes[0], panel, -2, 0);
        TextView title = text(s(R.string.change_locked), 25, INK); title.setTypeface(medium); add(panel, title, -2, 0);
        TextView warning = text(s(R.string.locked_guidance), 18, MUTED); warning.setLineHeight(px(25)); add(panel, warning, -2, 10);
        LinearLayout files = column(); files.setPadding(px(15), px(12), px(15), px(12)); files.setBackground(background(0xff232b2e, 15)); add(panel, files, -2, 17);
        discLabel(files, s(R.string.remove_disc, Images.session()[1]), false);
        discLabel(files, s(R.string.insert_disc, nextDisc.name), true);
        LinearLayout buttons = landscape ? panes[1] : panel;
        action(buttons, s(R.string.change_anyway), true, () -> request("swap", nextDisc, true), 53);
        action(buttons, s(R.string.keep_disc), false, () -> navigate("session"), 53);
        guide(panes[1], R.drawable.ic_info, s(R.string.session_stays), s(R.string.swap_keeps_running), GUIDE);
    }
    private void discLabel(LinearLayout parent, String value, boolean next) {
        String[] lines = value.split("\n", 2);
        add(parent, single(lines[0], 14, MUTED), 19, next ? 10 : 0);
        add(parent, single(lines.length > 1 ? lines[1] : "", 17, next ? ACCENT : INK), 24, 0);
    }
    private void chooseFolder() {
        if (Control.running()) { uiError = "active"; render(false); return; }
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION), TREE);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == TREE) {
            try {
                String folder = Control.primaryPath(uri);
                int grant = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                if (grant != (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) throw new IllegalArgumentException();
                getContentResolver().takePersistableUriPermission(uri, grant);
                Uri old = Control.tree(this);
                Control.prefs(this).edit().putString("tree", uri.toString()).putString("folder", folder).apply();
                if (old != null && !old.equals(uri)) {
                    try { getContentResolver().releasePersistableUriPermission(old, grant); } catch (SecurityException ignored) { }
                }
                navigate("images"); scan();
            } catch (IllegalArgumentException | SecurityException e) { uiError = "provider"; render(false); }
        } else if (request == IMPORT) {
            uiError = ""; requests.clear();
            requests.add(Control.submit(this, new Intent(this, SessionService.class).setAction("import").setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))); refresh();
        }
    }
    private void goBack() {
        if (Control.busy(this)) { navigate("hub"); return; }
        switch (page) {
            case "hub": finish(); break;
            case "images": case "session": navigate("hub"); break;
            case "size": navigate("create"); break;
            case "confirm": navigate("swap"); break;
            case "swap": case "pointer": case "typing": navigate("session"); break;
            default: navigate("images"); break;
        }
    }
    @Override public void onBackPressed() { goBack(); }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (scrollMode && scrollModeButton != null && scrollModeButton.hasFocus() && page.equals("session") && Control.input()) {
            int key = event.getKeyCode();
            if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && hid != null) { inputUsed = true; hid.mouse(0, 0, 0, key == KeyEvent.KEYCODE_DPAD_UP ? 1 : -1); }
                return true;
            }
            if (key == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    scrollMode = false; iconLabel((TextView) scrollModeButton, s(R.string.scroll_pc), R.drawable.ic_wheel, ACCENT);
                }
                return true;
            }
        }

        if ((event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP)
                && !targets.isEmpty()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                int at = targets.indexOf(getCurrentFocus());
                int delta = event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1;
                for (int step = 1; step <= targets.size(); step++) {
                    int next = at < 0 ? step - 1 : (at + delta * step + targets.size() * 2) % targets.size();
                    View view = targets.get(next);
                    if (view.isEnabled() && view.isShown() && view.isFocusable()) { view.requestFocus(); reveal(view); break; }
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    private void wrapTargets() {
        for (int i = 0; i < targets.size(); i++) {
            View view = targets.get(i); if (view.getId() == View.NO_ID) view.setId(View.generateViewId());
        }
        for (int i = 0; i < targets.size(); i++) {
            targets.get(i).setNextFocusForwardId(targets.get((i + 1) % targets.size()).getId());
        }
    }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private LinearLayout stack() {
        LinearLayout stack = column(); stack.setBackground(shape(CARD, 24, false)); stack.setClipToOutline(true); return stack;
    }
    private void add(LinearLayout parent, View view, int height, int margin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, height < 0 ? height : px(height)); lp.topMargin = px(margin); parent.addView(view, lp);
    }
    private TextView text(String value, int size, int color) {
        TextView text = new TextView(this); text.setText(value); text.setTextColor(color); text.setTypeface(regular);
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size)); text.setIncludeFontPadding(false); return text;
    }
    private TextView single(String value, int size, int color) {
        TextView text = text(value, size, color); text.setSingleLine(true); text.setEllipsize(TextUtils.TruncateAt.END); return text;
    }
    private ImageView icon(int resource, int tint) {
        ImageView image = new ImageView(this); image.setImageResource(resource);
        image.setImageTintList(android.content.res.ColorStateList.valueOf(tint == ACCENT ? 0xffacdce7 : tint)); return image;
    }
    private void note(LinearLayout parent, String value, int size, int margin) {
        TextView note = text(value, size, MUTED); note.setLineHeight(px(size + 5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(px(10), px(margin), px(10), 0); parent.addView(note, lp);
    }
    private GradientDrawable shape(int color, int radius, boolean focus) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(px(radius));
        if (focus) d.setStroke(px(3), 0xffade4ef); return d;
    }
    private StateListDrawable background(int color, int radius) { return background(color, radius, radius); }
    private StateListDrawable background(int color, int radius, int focusRadius) {
        StateListDrawable state = new StateListDrawable();
        state.addState(new int[] {android.R.attr.state_focused}, focused(color, focusRadius));
        state.addState(new int[] {android.R.attr.state_pressed}, focused(color, focusRadius));
        state.addState(new int[] {}, shape(color, radius, false)); return state;
    }
    private android.graphics.drawable.Drawable focused(int color, int radius) {
        GradientDrawable outer = shape(color, radius, true);
        if (color != ACCENT) return outer;
        GradientDrawable inner = shape(Color.TRANSPARENT, Math.max(0, radius - 3), false);
        inner.setStroke(px(2), 0xff194651);
        android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[] {outer, inner});
        layers.setLayerInset(1, px(3), px(3), px(3), px(3)); return layers;
    }
    private void reveal(View view) {
        if (!view.isAttachedToWindow()) return;
        int bottom = view.getHeight();
        // A trailing unavailable row has no focus target; reveal it with the last
        // enabled row so the wheel can still expose its explanation above the hint.
        if (view.getParent() instanceof LinearLayout) {
            LinearLayout parent = (LinearLayout) view.getParent();
            if (parent.getOrientation() == LinearLayout.VERTICAL) {
                for (int i = parent.indexOfChild(view) + 1; i < parent.getChildCount(); i++) {
                    View sibling = parent.getChildAt(i); if (sibling.isEnabled()) break;
                    bottom = sibling.getBottom() - view.getTop();
                }
            }
        }
        view.requestRectangleOnScreen(new android.graphics.Rect(0, 0, view.getWidth(), bottom), true);
    }
    private void target(View view, Runnable action) {
        if (action == null || !view.isEnabled()) return;
        if (view.getContentDescription() == null && view instanceof TextView) view.setContentDescription(((TextView) view).getText());
        // Stable across poll-driven redraws and the insertion/dismissal of a banner.
        TextView label = view.findViewWithTag("title");
        view.setTag(page + ":" + (label == null ? view.getContentDescription() : label.getText()));
        view.setFocusable(true); view.setFocusableInTouchMode(true); view.setOnClickListener(v -> action.run());
        view.setOnFocusChangeListener((v, focused) -> {
            if (focused) v.post(() -> { if (v.hasFocus()) reveal(v); });
            else if (v == scrollModeButton && scrollMode) {
                scrollMode = false; iconLabel((TextView) v, s(R.string.scroll_pc), R.drawable.ic_wheel, ACCENT);
            }
        });
        targets.add(view);
    }
    private View field(String label, String value, String end, boolean access, Runnable click) {
        LinearLayout field = row(); field.setGravity(Gravity.CENTER_VERTICAL);
        field.setContentDescription(label + s(R.string.text_separator) + value);
        field.setPadding(px(18), px(10), px(18), px(10)); field.setBackground(background(CARD, 0, 22));
        LinearLayout copy = column(); field.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        add(copy, single(label, 15, MUTED), 20, 0);
        TextView content = single(value, 21, INK); content.setTypeface(medium); add(copy, content, 27, 0);
        if (access && selected != null && selected.iso) field.addView(icon(R.drawable.ic_lock, ACCENT), new LinearLayout.LayoutParams(px(26), px(26)));
        else if (access) field.addView(toggle(!readOnly), new LinearLayout.LayoutParams(px(50), px(30)));
        else field.addView(single(end, 17, ACCENT));
        target(field, click); return field;
    }
    private View toggle(boolean on) {
        FrameLayout toggle = new FrameLayout(this);
        GradientDrawable track = shape(on ? ACCENT : 0xff20282b, 16, false); track.setStroke(px(2), on ? ACCENT : 0xff69777b); toggle.setBackground(track);
        View thumb = new View(this); thumb.setBackground(shape(on ? 0xff22515d : 0xff9eabad, 10, false));
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(px(20), px(20), Gravity.CENTER_VERTICAL | (on ? Gravity.RIGHT : Gravity.LEFT));
        if (on) tp.rightMargin = px(5); else tp.leftMargin = px(5); toggle.addView(thumb, tp);
        toggle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); return toggle;
    }
    private View card(String title, String sub, int icon, int badge, boolean enabled, Runnable click, int height) {
        boolean pageCards = !renderedScreen.startsWith("progress:");
        boolean grid = pageCards && landscape && page.equals("hub") && badge != 0;
        boolean grouped = pageCards && ((!landscape && page.equals("hub") && badge != 0) || page.equals("images") || page.equals("swap") || page.equals("size")
                || (page.equals("review") && height <= 54));
        LinearLayout item = row(); item.setGravity(Gravity.CENTER_VERTICAL); item.setMinimumHeight(px(height));
        item.setContentDescription(title + (sub.isEmpty() ? "" : s(R.string.text_separator) + sub));
        int vertical = grid ? 10 : Math.min(8, Math.max(0, (height - (sub.isEmpty() ? 25 : 48)) / 2));
        item.setPadding(px(grid ? 12 : 20), px(vertical), px(grid ? 12 : 20), px(vertical));
        item.setBackground(background(CARD, grouped ? 0 : grid ? 21 : 18, grouped ? 22 : grid ? 21 : 18));
        if (page.equals("review") && height <= 54) item.setPadding(px(17), px(vertical), px(17), px(vertical));
        if (icon != 0) {
            boolean imageRow = pageCards && (page.equals("images") || page.equals("swap")) && badge != 0;
            ImageView image = icon(icon, badge != 0 ? 0xff10475b : ACCENT);
            int side = badge != 0 ? (grid ? 41 : imageRow ? 42 : 46) : icon == R.drawable.ic_radio || icon == R.drawable.ic_radio_on ? 22 : 26;
            if (badge != 0) {
                int inset = grid || imageRow ? 7 : 9;
                image.setPadding(px(inset), px(inset), px(inset + (imageRow ? 0 : 1)), px(inset + 1));
                image.setBackground(shape(badge, imageRow ? 12 : 23, false));
            }
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(px(side), px(imageRow ? 44 : side));
            ip.rightMargin = px(grid ? 11 : page.equals("review") && height <= 54 ? 12 : page.equals("size") ? 16 : 15); item.addView(image, ip);
        }
        LinearLayout copy = column(); item.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        TextView label = single(title, grid ? 19 : 20, INK); label.setTypeface(medium); label.setTag("title");
        add(copy, label, grid ? 24 : 25, 0);
        if (!sub.isEmpty()) { TextView detail = single(sub, grid ? 14 : 16, MUTED); detail.setTag("detail"); add(copy, detail, grid ? 18 : 21, 2); }
        boolean radio = icon == R.drawable.ic_radio || icon == R.drawable.ic_radio_on;
        if (enabled && click != null) {
            if (!grid && !radio) { TextView arrow = single(s(R.string.forward_arrow), 21, ACCENT);
                LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2); ap.leftMargin = px(10); item.addView(arrow, ap); }
            target(item, click);
        }
        return item;
    }
    private void cardText(View card, int titleSize, int detailSize) {
        TextView title = card.findViewWithTag("title");
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(titleSize)); title.getLayoutParams().height = px(titleSize + 5);
        TextView detail = card.findViewWithTag("detail");
        if (detail != null) { detail.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(detailSize)); detail.getLayoutParams().height = px(detailSize == 14 ? 18 : detailSize + 5); }
    }
    private void disabled(View card) {
        card.setEnabled(false); card.setFocusable(false); targets.remove(card);
        TextView title = card.findViewWithTag("title"), detail = card.findViewWithTag("detail");
        if (title != null) title.setTextColor(0xff747d80); if (detail != null) detail.setTextColor(0xff747d80);
        if (card instanceof ViewGroup && ((ViewGroup) card).getChildAt(0) instanceof ImageView) ((ViewGroup) card).getChildAt(0).setAlpha(.48f);
    }
    private void hero(LinearLayout parent, String title, String sub, int icon) {
        View hero = card(title, sub, icon, 0xff5ad2f5, false, null, 83); cardText(hero, 22, 16);
        hero.setPadding(px(18), px(8), px(18), px(8));
        hero.findViewWithTag("title").getLayoutParams().height = px(28);
        ((LinearLayout.LayoutParams) hero.findViewWithTag("detail").getLayoutParams()).topMargin = px(3);
        hero.setBackground(background(CARD, 22)); add(parent, hero, 83, 0);
    }
    private View action(LinearLayout parent, String label, boolean primary, Runnable click, int height) {
        boolean stop = label.equals(s(R.string.stop));
        TextView button = single(label, stop ? 23 : 17, primary ? (stop ? 0xffd1eff4 : 0xff073743) : INK);
        button.setTypeface(medium); button.setGravity(Gravity.CENTER);
        button.setBackground(background(primary ? (stop ? 0xff245761 : ACCENT) : 0xff202b2f, stop ? 23 : 19));
        if (click == null) { button.setEnabled(false); button.setAlpha(.45f); }
        target(button, click);
        if (stop && targets.remove(button)) targets.add(0, button);
        int icon = stop ? R.drawable.ic_stop : label.equals(s(R.string.start_cd)) || label.equals(s(R.string.start_drive))
                ? R.drawable.ic_usb : label.equals(s(R.string.change_anyway)) ? R.drawable.ic_swap : label.equals(s(R.string.keep_size, gb)) ? R.drawable.ic_check
                : label.equals(s(R.string.create_title)) ? R.drawable.ic_plus : label.equals(s(R.string.import_title)) ? R.drawable.ic_import
                : label.equals(s(R.string.send_text)) ? R.drawable.ic_keyboard : label.equals(s(R.string.scroll_pc)) || label.equals(s(R.string.scroll_pc_active)) ? R.drawable.ic_wheel : 0;
        if (icon != 0) iconLabel(button, label, icon, primary && !stop ? 0xff073743 : ACCENT);
        add(parent, button, height, 12); return button;
    }
    private void pairAction(LinearLayout pair, String label, boolean primary, Runnable click) {
        TextView button = single(label, 17, primary ? 0xff073743 : INK); button.setTypeface(medium); button.setGravity(Gravity.CENTER);
        button.setBackground(background(primary ? ACCENT : 0xff202b2f, 16)); button.setPadding(px(5), 0, px(5), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1); if (pair.getChildCount() > 0) lp.leftMargin = px(8); pair.addView(button, lp);
        int icon = label.equals(s(R.string.create_use)) ? R.drawable.ic_usb : label.equals(s(R.string.create_title)) ? R.drawable.ic_plus
                : label.equals(s(R.string.import_title)) ? R.drawable.ic_import : label.equals(s(R.string.send_text)) || label.equals(s(R.string.type_text)) ? R.drawable.ic_keyboard
                : label.equals(s(R.string.move_pointer)) ? R.drawable.ic_pointer : label.equals(s(R.string.left_click)) || label.equals(s(R.string.right_click)) ? R.drawable.ic_mouse : 0;
        if (icon != 0) iconLabel(button, label, icon, primary ? 0xff073743 : ACCENT);
        if (click == null) { button.setEnabled(false); button.setAlpha(.45f); } else target(button, click);
    }
    private void iconLabel(TextView view, String label, int icon, int color) {
        android.text.SpannableString text = new android.text.SpannableString(s(R.string.icon_button_label, label));
        android.graphics.drawable.Drawable drawable = getDrawable(icon).mutate();
        int side = icon == R.drawable.ic_stop ? 24 : 22;
        drawable.setTint(color == ACCENT ? 0xffacdce7 : color); drawable.setBounds(0, 0, px(side), px(side));
        text.setSpan(new android.text.style.ImageSpan(drawable, android.text.style.ImageSpan.ALIGN_CENTER),
                0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(text); view.setContentDescription(label);
    }
    private void guideContent(LinearLayout parent, int icon, String title, String detail, int color) {
        LinearLayout content = row(); content.setGravity(Gravity.TOP); add(parent, content, -2, 0);
        ImageView image = icon(icon, color == 0xff30291f ? 0xffe5c7a4 : ACCENT);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(px(22), px(22)); ip.rightMargin = px(11); content.addView(image, ip);
        LinearLayout copy = column(); content.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        TextView label = text(title, color == 0xff30291f ? 18 : 16, color == 0xff30291f ? 0xfff4d8b7 : INK); label.setTypeface(medium);
        label.setLineHeight(px(23)); add(copy, label, -2, 0);
        TextView message = text(detail, 16, color == 0xff30291f ? 0xffe5c7a4 : MUTED);
        message.setTag("guide-detail"); message.setLineHeight(px(21)); add(copy, message, -2, 2);
    }
    private void guide(LinearLayout parent, int icon, String title, String detail, int color) {
        boolean sound = page.equals("session") && Control.prop("st.session").equals("sound");
        boolean compact = sound || landscape && page.equals("session") || page.equals("confirm");
        int horizontal = sound || page.equals("confirm") ? 14 : compact ? 13 : 16;
        int vertical = page.equals("confirm") ? 10 : compact ? 11 : 12;
        LinearLayout guide = column(); guide.setPadding(px(horizontal), px(vertical), px(horizontal), px(vertical));
        guide.setBackground(background(color, 18)); guideContent(guide, icon, title, detail, color);
        if (compact) {
            TextView message = guide.findViewWithTag("guide-detail"); message.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(15)); message.setLineHeight(px(20));
        }
        add(parent, guide, -2, compact ? 10 : 11);
    }
    private String errorText(String code) {
        switch (code) {
            case "input": return s(R.string.input_error);
            case "text": return s(R.string.text_error);
            case "endpoints": return s(R.string.endpoints_error);
            case "provider": return s(R.string.provider_error);
            case "folder": return s(R.string.folder_error);
            case "name": return s(R.string.name_error);
            case "exists": return s(R.string.exists_error);
            case "space": return s(R.string.not_enough);
            case "mounted": return s(R.string.mounted_error);
            case "changed": return s(R.string.changed_error);
            case "cd_size": case "size": return s(R.string.size_error);
            case "active": return s(R.string.active_error);
            case "hal_failed": return s(R.string.hal_error);
            case "hal_timeout": return s(R.string.timeout_error);
            case "recovery": case "cleanup": return s(R.string.recovery_error);
            case "formatter": case "format": case "format_timeout": return s(R.string.format_error);
            default: return s(R.string.io_error);
        }
    }
}
