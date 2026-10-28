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

/** Native rendition of the approved 01-04 mockups, using UsbMode's workflows. */
public final class UsbActivity extends FragmentActivity {
    private static final int TREE = 1, IMPORT = 2;
    private static final int INK = 0xffe8eef0, MUTED = 0xffa7b4b9, ACCENT = 0xffa7d6e1;
    private static final int CARD = 0xff171717, GUIDE = 0xff131d20, CYAN = 0xff142125;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<View> targets = new ArrayList<>();
    private String page = "hub", previous = "hub", fingerprint = "", renderedScreen = "";
    private Images selected, nextDisc;
    private boolean readOnly, connected, configured, wasBusy, receiverRegistered;
    private String lastJob = "", createName = "transfer", filesystem = "fat32", uiError = "";
    private int gb = 4;
    private long lastCompletion;
    private float scale = 1;
    private boolean landscape;
    private LinearLayout root, body;
    private TextView back;
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
            page = saved.getString("page", "hub");
            previous = saved.getString("previous", "hub");
            createName = saved.getString("name", "transfer");
            filesystem = saved.getString("fs", "fat32"); gb = saved.getInt("gb", 4);
            readOnly = saved.getBoolean("ro");
            selected = restoreImage(saved, "selected"); nextDisc = restoreImage(saved, "next");
        } else if (Control.running() && !"android.service.quicksettings.action.QS_TILE_PREFERENCES".equals(getIntent().getAction())) page = "session";
        if ((page.equals("review") && selected == null) || (page.equals("confirm") && nextDisc == null)) page = "images";
        receiverRegistered = true;
        Intent sticky = registerReceiver(usb, new IntentFilter("android.hardware.usb.action.USB_STATE"), Context.RECEIVER_EXPORTED);
        if (sticky != null) { connected = sticky.getBooleanExtra("connected", false); configured = sticky.getBooleanExtra("configured", false); }
        if (Control.running() || Control.busy(this)) Control.request(this, "observe", null, true);
        lastCompletion = Control.prefs(this).getLong("completion", 0);
        render(false);
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if ("android.service.quicksettings.action.QS_TILE_PREFERENCES".equals(intent.getAction())) navigate("hub");
    }
    @Override protected void onResume() { super.onResume(); if (UserHandle.myUserId() == 0) handler.post(poll); }
    @Override protected void onPause() { handler.removeCallbacks(poll); super.onPause(); }
    @Override protected void onDestroy() { if (receiverRegistered) unregisterReceiver(usb); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("page", page); out.putString("previous", previous); out.putString("name", createName);
        out.putString("fs", filesystem); out.putInt("gb", gb); out.putBoolean("ro", readOnly);
        saveImage(out, "selected", selected); saveImage(out, "next", nextDisc);
    }
    private void saveImage(Bundle out, String key, Images image) {
        if (image != null) { out.putString(key, image.name); out.putString(key + "Stamp", image.stamp); out.putBoolean(key + "Ro", image.readOnly); }
    }
    private Images restoreImage(Bundle in, String key) {
        if (!in.containsKey(key)) return null;
        try { return new Images(in.getString(key), in.getString(key + "Stamp"), in.getBoolean(key + "Ro")); }
        catch (RuntimeException e) { return null; }
    }
    private void refresh() {
        boolean busy = Control.busy(this);
        if (busy) lastJob = Control.prefs(this).getString("job", "");
        long completion = Control.prefs(this).getLong("completion", 0);
        if ((wasBusy || completion != lastCompletion) && !busy) {
            lastJob = Control.prefs(this).getString("job", "");
            String error = Control.prefs(this).getString("error", "");
            if (error.isEmpty()) {
                if (Control.running() && !lastJob.equals("scan")) page = "session";
                else if (!lastJob.equals("scan")) page = "images";
            } else if (error.equals("cancelled")) page = lastJob.equals("create") ? "create" : "images";
        }
        wasBusy = busy;
        lastCompletion = completion;
        String state = page + busy + connected + configured + Control.prop("st.session")
                + Control.prop("st.stage") + Control.prop("st.percent") + Control.prop("st.result")
                + Control.prefs(this).getString("error", "") + Control.prefs(this).getInt("importPercent", 0)
                + Images.session()[1] + Control.hasFolder(this);
        if (!state.equals(fingerprint)) { fingerprint = state; render(true); }
    }
    private int px(float value) { return Math.round(value * scale); }
    private String s(int id, Object... values) { return getString(id, values); }
    private void navigate(String next) { previous = page; page = next; uiError = ""; fingerprint = ""; render(false); }
    private void scan() { if (Control.hasFolder(this) && !Control.busy(this)) Control.request(this, "scan", null, true); }

    private void render(boolean retainFocus) {
        String job = Control.prefs(this).getString("job", "");
        String screen = Control.busy(this) && !job.equals("scan") ? "progress:" + job : page;
        int focus = screen.equals(renderedScreen) ? targets.indexOf(getCurrentFocus()) : -1;
        renderedScreen = screen;
        targets.clear();
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        scale = dm.widthPixels / (landscape ? 640f : 480f);
        root = column(); root.setBackgroundColor(Color.BLACK);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        setContentView(root);
        root.requestApplyInsets();
        LinearLayout toolbar = row(); toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(px(20), 0, px(20), 0);
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, px(landscape ? 58 : 62)));
        back = text(s(R.string.back_arrow), 26, ACCENT); back.setGravity(Gravity.CENTER);
        back.setContentDescription(s(R.string.back)); back.setBackground(background(0xff1c2b30, 24));
        toolbar.addView(back, new LinearLayout.LayoutParams(px(46), px(46)));
        back.setFocusable(true); back.setFocusableInTouchMode(true); back.setOnClickListener(v -> goBack());
        TextView title = text(s(title()), 27, INK); title.setTypeface(Typeface.create("google-sans", Typeface.NORMAL));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1); tp.leftMargin = px(20); toolbar.addView(title, tp);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        scroll.setPadding(px(20), 0, px(20), 0);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        body = column(); scroll.addView(body, new ScrollView.LayoutParams(-1, -1));
        String error = uiError.isEmpty() ? Control.prefs(this).getString("error", "") : uiError;
        if (error.isEmpty() && Control.prop("st.result").endsWith(":error")
                && !Control.prop("st.result").equals(Control.prefs(this).getString("dismissed", ""))) error = Control.prop("st.error");
        if (!error.isEmpty() && !error.equals("cancelled")) {
            guide(body, R.drawable.ic_info, s(R.string.problem), errorText(error), 0xff30291f);
            if (!Control.running() && (error.equals("recovery") || error.equals("cleanup") || error.equals("hal_timeout"))) {
                action(body, s(R.string.stop), true, () -> Control.request(this, "stop", null, true), 64);
            }
            action(body, s(R.string.dismiss), false, () -> {
                uiError = ""; Control.prefs(this).edit().putString("error", "").putString("dismissed", Control.prop("st.result")).apply(); render(false);
            }, 42);
        }
        boolean busy = Control.busy(this);
        if (busy && !job.equals("scan")) progressPage(job);
        else switch (page) {
            case "images": imagesPage(false); break;
            case "review": reviewPage(); break;
            case "import": importPage(); break;
            case "create": createPage(); break;
            case "size": sizePage(); break;
            case "session": sessionPage(); break;
            case "swap": imagesPage(true); break;
            case "confirm": confirmPage(); break;
            default: hubPage(); break;
        }
        TextView foot = text(s(page.equals("session") ? (adb() ? R.string.session_footer : R.string.session_footer_no_adb) : R.string.wheel_footer), 14, MUTED);
        foot.setGravity(Gravity.CENTER_VERTICAL); foot.setPadding(px(30), 0, px(20), 0);
        root.addView(foot, new LinearLayout.LayoutParams(-1, px(landscape ? 28 : 35)));
        if (page.equals("session") && Control.running()) {
            for (int i = 0; i < targets.size(); i++) {
                View target = targets.get(i);
                if (target instanceof TextView && s(R.string.stop).contentEquals(((TextView) target).getText())) {
                    targets.remove(i); targets.add(0, target); break;
                }
            }
        }
        targets.add(back);
        wrapTargets();
        if (!targets.isEmpty()) targets.get(retainFocus && focus >= 0 ? Math.min(focus, targets.size() - 1) : 0).requestFocus();
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
            case "session": return R.string.session_title;
            case "swap": case "confirm": return R.string.change_disc;
            default: return R.string.app_name;
        }
    }
    private void hubPage() {
        note(body, s(R.string.breadcrumb), 14, 6);
        connection(body);
        LinearLayout choices = column(); add(body, choices, -2, 10);
        if (landscape) {
            // Fill the available height with the two grid rows; preserve type size.
            LinearLayout top = row(), bottom = row();
            choices.setMinimumHeight(px(187));
            add(choices, top, 90, 0); add(choices, bottom, 90, 7);
            hubChoice(top, R.string.drive_title, R.string.drive_sub, R.drawable.ic_drive, 0xff5ad2f5, true);
            hubChoice(top, R.string.sound, R.string.coming_soon, R.drawable.ic_sound, 0xffffafe8, false);
            hubChoice(bottom, R.string.keyboard, R.string.coming_soon, R.drawable.ic_keyboard, 0xffa1c7ff, false);
            hubChoice(bottom, R.string.flipper, R.string.coming_soon, R.drawable.ic_flipper, 0xffffb77e, false);
        } else {
            hubChoice(choices, R.string.drive_title, R.string.drive_sub, R.drawable.ic_drive, 0xff5ad2f5, true);
            hubChoice(choices, R.string.sound, R.string.coming_soon, R.drawable.ic_sound, 0xffffafe8, false);
            hubChoice(choices, R.string.keyboard, R.string.coming_soon, R.drawable.ic_keyboard, 0xffa1c7ff, false);
            hubChoice(choices, R.string.flipper, R.string.coming_soon, R.drawable.ic_flipper, 0xffffb77e, false);
        }
        View unavailable = card(s(R.string.flipper_combo), s(R.string.combo_unavailable), R.drawable.ic_combo, 0, false, null, landscape ? 54 : 58);
        unavailable.setAlpha(.55f); add(body, unavailable, landscape ? 54 : 58, landscape ? 8 : 10);
    }
    private void hubChoice(LinearLayout parent, int title, int sub, int icon, int color, boolean enabled) {
        View item = card(s(title), s(sub), icon, color, enabled,
                () -> { navigate(Control.running() ? "session" : "images"); if (!Control.running()) scan(); }, landscape ? 90 : 77);
        if (!enabled) { item.setEnabled(false); item.setAlpha(.55f); }
        if (landscape) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1); lp.rightMargin = px(7); parent.addView(item, lp);
        } else add(parent, item, 77, 2);
    }
    private void connection(LinearLayout parent) {
        String title = configured ? s(R.string.pc_connected) : connected ? s(R.string.cable_connected) : s(R.string.not_connected);
        String sub = configured ? (adb() ? s(R.string.adb_kept) : s(R.string.data_connected))
                : connected ? s(R.string.wait_pc) : s(R.string.plug_cable);
        View connection = card(title, sub, R.drawable.ic_usb, 0, false, null, landscape ? (page.equals("hub") ? 48 : 62) : 58);
        connection.setBackground(background(CYAN, 18)); add(parent, connection, landscape ? (page.equals("hub") ? 48 : 62) : 58, 9);
    }
    private boolean adb() { return ("," + android.os.SystemProperties.get("sys.usb.config", "") + ",").contains(",adb,"); }
    private void folderButton() {
        action(body, s(Control.hasFolder(this) ? R.string.change_folder : R.string.choose_folder), false, this::chooseFolder, 52);
    }
    private void imagesPage(boolean swap) {
        if (!swap && !Control.hasFolder(this)) {
            guide(body, R.drawable.ic_folder, s(R.string.choose_folder), s(R.string.folder_explanation), GUIDE);
            folderButton(); return;
        }
        List<Images> images = Images.inventory();
        if (swap) {
            guide(body, R.drawable.ic_lock, s(R.string.tray_notice), s(R.string.tray_detail), 0xff30291f);
            note(body, s(R.string.current_disc, Images.session()[1]), 14, 10);
        } else {
            note(body, s(R.string.free_images, size(Images.free())), 16, 10);
            ProgressBar meter = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            meter.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            meter.setProgress(Images.total() > 0 ? (int) (100 * (Images.total() - Images.free()) / Images.total()) : 0); add(body, meter, 5, 0);
        }
        if (Control.busy(this)) note(body, s(R.string.scanning), 16, 10);
        if (images.isEmpty() && !Control.busy(this)) note(body, s(R.string.no_images), 18, 14);
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
                    }, 74);
            if (!enabled) item.setAlpha(.5f);
            add(body, item, 74, 2);
        }
        if (swap) {
            action(body, s(R.string.keep_disc), false, () -> navigate("session"), 58);
            note(body, s(R.string.swap_explanation), 16, 10);
        } else {
            LinearLayout pair = row(); add(body, pair, 64, 12);
            pairAction(pair, s(R.string.create_title), false, () -> {
                createName = Images.nextName(images); gb = (int) Math.min(4, Images.maximum(Images.free()) / Images.GB);
                if (gb < 1) gb = 1; navigate("create");
            });
            pairAction(pair, s(R.string.import_title), false, () -> { navigate("import"); openImportPicker(); });
            note(body, s(R.string.images_explanation), 16, 12);
            folderButton();
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
        hero(body, selected.name, s(selected.iso ? R.string.pc_cd : R.string.pc_drive, size(selected.bytes)),
                selected.iso ? R.drawable.ic_disc : R.drawable.ic_drive);
        View access = field(s(R.string.access), s(selected.iso ? R.string.always_read_only
                : readOnly ? R.string.access_read_only : R.string.access_writable), "", true,
                !selected.readOnly ? () -> { readOnly = !readOnly; render(true); } : null);
        add(body, access, 69, 2);
        note(body, s(R.string.use_together), 14, 8);
        add(body, card(s(R.string.drive_only), s(adb() ? R.string.adb_included : R.string.android_functions), R.drawable.ic_radio_on, 0, false, null, 54), 54, 2);
        View input = card(s(R.string.drive_input), s(R.string.coming_soon), R.drawable.ic_radio, 0, false, null, 54);
        input.setAlpha(.5f); add(body, input, 54, 2);
        View other = card(s(R.string.add_sound_flipper), s(R.string.coming_soon), 0, 0, false, null, 50);
        other.setAlpha(.5f); add(body, other, 50, 2);
        View start = action(body, s(selected.iso ? R.string.start_cd : R.string.start_drive), true,
                () -> { Control.request(this, "start", selected, readOnly); refresh(); }, 58);
        note(body, s(selected.iso ? R.string.cd_guidance : R.string.drive_guidance), 15, 10);
        if (selected.iso) { targets.remove(start); targets.add(0, start); }
    }
    private void createPage() {
        note(body, s(R.string.free_create, size(Images.free())), 16, 10);
        add(body, field(s(R.string.drive_size), s(R.string.gb_integer, gb), s(R.string.choose), false, () -> navigate("size")), 81, 2);
        LinearLayout fs = column(); fs.setBackground(background(CARD, 4)); fs.setPadding(px(18), px(11), px(18), px(11));
        add(body, fs, 122, 0); note(fs, s(R.string.file_system), 15, 0);
        LinearLayout pair = row(); add(fs, pair, 43, 7);
        pairAction(pair, s(R.string.fat32), filesystem.equals("fat32"), () -> { filesystem = "fat32"; render(true); });
        pairAction(pair, s(R.string.exfat), filesystem.equals("exfat"), () -> { filesystem = "exfat"; render(true); });
        note(fs, s(filesystem.equals("fat32") ? R.string.fat_hint : R.string.exfat_hint), 14, 5);
        add(body, field(s(R.string.image_name), createName + ".img", s(R.string.edit), false, this::editName), 81, 2);
        LinearLayout actions = row(); add(body, actions, 64, 13);
        boolean enough = gb * Images.GB <= Images.maximum(Images.free());
        pairAction(actions, s(R.string.create_only), false, enough ? () -> create(false) : null);
        pairAction(actions, s(R.string.create_use), true, enough ? () -> create(true) : null);
        note(body, s(enough ? R.string.create_explanation : R.string.not_enough), 15, 12);
    }
    private void create(boolean use) {
        if (!Images.createName(createName)) { uiError = "name"; render(false); return; }
        Control.create(this, createName, gb, filesystem, use); refresh();
    }
    private void sizePage() {
        note(body, s(R.string.size_hint), 16, 12);
        for (int value : new int[] {1, 2, 4, 8, 16}) {
            boolean enough = value * Images.GB <= Images.maximum(Images.free());
            View item = card(s(R.string.gb_integer, value), enough
                    ? s(R.string.stays_free, size(Images.free() - value * Images.GB)) : s(R.string.not_enough_free, size(Images.free())),
                    value == gb ? R.drawable.ic_radio_on : R.drawable.ic_radio, 0, enough,
                    () -> { gb = value; render(true); }, 61);
            if (!enough) item.setAlpha(.45f); add(body, item, 61, 2);
        }
        action(body, s(R.string.keep_size, gb), true, () -> navigate("create"), 58);
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
        guide(body, R.drawable.ic_info, s(R.string.document_picker), s(R.string.import_explanation), GUIDE);
        action(body, s(R.string.browse), true, this::openImportPicker, 64);
        note(body, s(R.string.import_copy), 16, 12);
    }
    private void openImportPicker() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(picker, IMPORT);
    }
    private void progressPage(String job) {
        boolean importing = job.equals("import");
        if (job.equals("stop")) { note(body, s(R.string.stopping), 22, 12); return; }
        if (!job.equals("create") && !importing) {
            note(body, s(R.string.preparing), 22, 12);
            action(body, s(R.string.stop), false, () -> Control.request(this, "stop", null, true), 64); return;
        }
        hero(body, importing ? s(R.string.import_title) : Control.prefs(this).getString("jobImage", createName + ".img"),
                importing ? s(R.string.import_copy) : s(R.string.create_description,
                        Control.prefs(this).getInt("jobSize", gb), s("fat32".equals(Control.prefs(this).getString("jobFs", filesystem))
                                ? R.string.fat32 : R.string.exfat)), R.drawable.ic_drive);
        LinearLayout panel = column(); panel.setPadding(px(20), px(17), px(20), px(17)); panel.setBackground(background(CARD, 24));
        add(body, panel, -2, 12);
        note(panel, s(importing ? R.string.copying : R.string.creating_blank), 18, 0);
        int percent = importing ? Control.prefs(this).getInt("importPercent", 0) : parsePercent();
        TextView number = text(percent < 0 ? s(R.string.working) : s(R.string.percent, percent), 44, ACCENT); add(panel, number, 56, 4);
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        progress.setIndeterminate(percent < 0); progress.setProgress(percent); add(panel, progress, 8, 10);
        note(panel, stageText(Control.prop("st.stage")), 16, 12);
        if (!importing) {
            note(panel, s(R.string.step_check), 17, 12); note(panel, s(R.string.step_allocate), 17, 6); note(panel, s(R.string.step_format), 17, 6);
        }
        action(body, s(importing ? R.string.cancel_import : R.string.cancel_creation), false,
                () -> Control.request(this, "cancel", null, true), 57);
        note(body, s(importing ? R.string.import_copy : R.string.creation_progress_hint), 16, 12);
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
        String[] session = Images.session(); boolean cd = Control.prop("st.session").equals("cd");
        LinearLayout left = body, right = body;
        if (landscape) {
            LinearLayout split = row(); body.addView(split, new LinearLayout.LayoutParams(-1, 0, 1));
            left = column(); right = column();
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(270), -1); lp.rightMargin = px(13); split.addView(left, lp);
            split.addView(right, new LinearLayout.LayoutParams(0, -1, 1));
        }
        View stop = action(left, s(R.string.stop), true, () -> Control.request(this, "stop", null, true), landscape ? 66 : 64);
        ((LinearLayout.LayoutParams) stop.getLayoutParams()).topMargin = 0;
        connection(left);
        if (landscape) {
            note(left, s(cd ? (adb() ? R.string.pc_cd_hint : R.string.pc_cd_hint_no_adb) : R.string.drive_guidance), 16, 16);
            // Use the space beneath Stop for the PC-side eject/connection guidance.
            View spacer = new View(this); left.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1));
            LinearLayout keep = column(); keep.setPadding(px(14), px(12), px(14), px(12));
            keep.setBackground(background(GUIDE, 18));
            note(keep, s(R.string.session_persistence), 15, 0); add(left, keep, -2, 8);
        }
        LinearLayout seen = column(); seen.setBackground(background(CARD, 22)); seen.setPadding(px(18), px(12), px(18), px(15));
        add(right, seen, -2, landscape ? 0 : 11); note(seen, s(R.string.pc_sees), 14, 0);
        View device = card(s(cd ? R.string.cd_name : R.string.drive_name, session[1]),
                s(R.string.size_access, size(Long.parseLong(session[3])), s(session[2].equals("1") ? R.string.read_only : R.string.writable)),
                cd ? R.drawable.ic_disc : R.drawable.ic_drive, 0, false, null, landscape ? 102 : 112);
        add(seen, device, landscape ? 102 : 112, 0);
        if (cd) add(right, card(s(R.string.change_disc), s(R.string.swap_sub), R.drawable.ic_swap, 0, true,
                () -> { navigate("swap"); scan(); }, landscape ? 80 : 68), landscape ? 80 : 68, 10);
        else if (!landscape) guide(right, R.drawable.ic_info, s(R.string.eject_title), s(R.string.eject_guidance), GUIDE);
        guide(right, R.drawable.ic_info, s(R.string.qs_stop), s(R.string.qs_guidance), GUIDE);
    }
    private void confirmPage() {
        if (nextDisc == null || !Control.running()) { navigate("session"); return; }
        LinearLayout panel = column(); panel.setPadding(px(20), px(18), px(20), px(18)); panel.setBackground(background(CARD, 25)); add(body, panel, -2, 8);
        note(panel, s(R.string.change_locked), 25, 0); note(panel, s(R.string.locked_guidance), 18, 10);
        hero(panel, s(R.string.remove_disc, Images.session()[1]), s(R.string.insert_disc, nextDisc.name), R.drawable.ic_disc);
        action(panel, s(R.string.change_anyway), true, () -> { Control.request(this, "swap", nextDisc, true); refresh(); }, 53);
        action(panel, s(R.string.keep_disc), false, () -> navigate("session"), 53);
        guide(body, R.drawable.ic_info, s(R.string.session_stays), s(R.string.swap_keeps_running), GUIDE);
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
            Control.prefs(this).edit().putString("error", "").apply();
            startForegroundService(new Intent(this, SessionService.class).setAction("import").setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); refresh();
        }
    }
    private void goBack() {
        if (Control.busy(this)) { navigate("hub"); return; }
        switch (page) {
            case "hub": finish(); break;
            case "images": case "session": navigate("hub"); break;
            case "size": navigate("create"); break;
            case "confirm": navigate("swap"); break;
            case "swap": navigate("session"); break;
            default: navigate("images"); break;
        }
    }
    @Override public void onBackPressed() { goBack(); }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if ((event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP)
                && !targets.isEmpty()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                int at = targets.indexOf(getCurrentFocus());
                int delta = event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1;
                int next = at < 0 ? 0 : (at + delta + targets.size()) % targets.size();
                View view = targets.get(next); view.requestFocus();
                view.requestRectangleOnScreen(new android.graphics.Rect(0, 0, view.getWidth(), view.getHeight()));
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
    private void add(LinearLayout parent, View view, int height, int margin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, height < 0 ? height : px(height)); lp.topMargin = px(margin); parent.addView(view, lp);
    }
    private TextView text(String value, int size, int color) {
        TextView text = new TextView(this); text.setText(value); text.setTextColor(color);
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size)); text.setIncludeFontPadding(false); return text;
    }
    private void note(LinearLayout parent, String value, int size, int margin) {
        TextView note = text(value, size, MUTED); note.setLineSpacing(px(3), 1); add(parent, note, -2, margin);
    }
    private GradientDrawable shape(int color, int radius, boolean focus) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(px(radius));
        if (focus) d.setStroke(px(3), 0xff8edcf3); return d;
    }
    private StateListDrawable background(int color, int radius) {
        StateListDrawable state = new StateListDrawable();
        state.addState(new int[] {android.R.attr.state_focused}, focused(color, radius));
        state.addState(new int[] {android.R.attr.state_pressed}, focused(color, radius));
        state.addState(new int[] {}, shape(color, radius, false)); return state;
    }
    private android.graphics.drawable.Drawable focused(int color, int radius) {
        GradientDrawable outer = shape(color, radius, true);
        if (color != ACCENT) return outer;
        GradientDrawable inner = shape(Color.TRANSPARENT, radius - 3, false);
        inner.setStroke(px(2), 0xff194651);
        android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[] {outer, inner});
        layers.setLayerInset(1, px(3), px(3), px(3), px(3)); return layers;
    }
    private void target(View view, Runnable action) {
        if (action == null) return;
        view.setFocusable(true); view.setFocusableInTouchMode(true); view.setOnClickListener(v -> action.run()); targets.add(view);
    }
    private View field(String label, String value, String end, boolean access, Runnable click) {
        LinearLayout field = row(); field.setGravity(Gravity.CENTER_VERTICAL);
        field.setPadding(px(18), px(12), px(18), px(12)); field.setBackground(background(CARD, 20));
        LinearLayout copy = column(); field.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        add(copy, text(label, 15, MUTED), -2, 0);
        TextView content = text(value, 21, INK); content.setSingleLine(true); content.setEllipsize(TextUtils.TruncateAt.END);
        add(copy, content, -2, 3);
        if (access && selected != null && selected.iso) {
            ImageView lock = new ImageView(this); lock.setImageResource(R.drawable.ic_lock);
            field.addView(lock, new LinearLayout.LayoutParams(px(24), px(24)));
        } else if (access) {
            android.widget.Switch toggle = new android.widget.Switch(this); toggle.setChecked(!readOnly);
            toggle.setEnabled(click != null); toggle.setFocusable(false); toggle.setClickable(false);
            field.addView(toggle, new LinearLayout.LayoutParams(-2, -2));
        } else field.addView(text(end, 17, ACCENT));
        target(field, click); return field;
    }
    private View card(String title, String sub, int icon, int badge, boolean enabled, Runnable click, int height) {
        LinearLayout item = row(); item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(px(18), px(8), px(18), px(8)); item.setBackground(background(CARD, 20));
        if (icon != 0) {
            ImageView image = new ImageView(this); image.setImageResource(icon);
            int side = badge != 0 ? 46 : 28;
            if (badge != 0) { image.setPadding(px(9), px(9), px(9), px(9)); image.setBackground(shape(badge, 23, false)); image.setImageTintList(android.content.res.ColorStateList.valueOf(0xff10475b)); }
            else image.setImageTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(px(side), px(side)); ip.rightMargin = px(15); item.addView(image, ip);
        }
        LinearLayout copy = column(); item.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        TextView label = text(title, landscape && height < 100 ? 19 : 20, INK);
        label.setTypeface(Typeface.create("google-sans", Typeface.NORMAL)); label.setMaxLines(title.contains("\n") ? 3 : 2); label.setEllipsize(TextUtils.TruncateAt.END);
        add(copy, label, -2, 0);
        if (!sub.isEmpty()) { TextView detail = text(sub, landscape ? 14 : 16, MUTED); detail.setMaxLines(3); detail.setEllipsize(TextUtils.TruncateAt.END); add(copy, detail, -2, 2); }
        if (enabled && click != null) { TextView arrow = text(s(R.string.forward_arrow), 21, ACCENT); item.addView(arrow); target(item, click); }
        return item;
    }
    private void hero(LinearLayout parent, String title, String sub, int icon) {
        add(parent, card(title, sub, icon, 0xff5ad2f5, false, null, 83), 83, 4);
    }
    private View action(LinearLayout parent, String label, boolean primary, Runnable click, int height) {
        boolean stop = label.equals(s(R.string.stop));
        TextView button = text(label, stop ? 23 : 17, primary ? (stop ? 0xffd1eff4 : 0xff073743) : INK);
        button.setGravity(Gravity.CENTER); button.setBackground(background(primary ? (stop ? 0xff245761 : ACCENT) : 0xff202b2f, 20));
        target(button, click);
        if (stop && targets.remove(button)) targets.add(0, button);
        int icon = stop ? R.drawable.ic_stop : label.equals(s(R.string.start_cd)) || label.equals(s(R.string.start_drive))
                ? R.drawable.ic_usb : label.equals(s(R.string.change_anyway)) ? R.drawable.ic_swap : 0;
        if (icon != 0) iconLabel(button, label, icon, primary && !stop ? 0xff073743 : ACCENT);
        add(parent, button, height, 12); return button;
    }
    private void pairAction(LinearLayout pair, String label, boolean primary, Runnable click) {
        TextView button = text(label, 17, primary ? 0xff073743 : INK); button.setGravity(Gravity.CENTER);
        button.setBackground(background(primary ? ACCENT : 0xff202b2f, 18)); button.setPadding(px(6), 0, px(6), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1); lp.rightMargin = px(7); pair.addView(button, lp);
        if (label.equals(s(R.string.create_use))) iconLabel(button, label, R.drawable.ic_usb, 0xff073743);
        if (click == null) button.setAlpha(.45f); else target(button, click);
    }
    private void iconLabel(TextView view, String label, int icon, int color) {
        android.text.SpannableString text = new android.text.SpannableString(s(R.string.icon_button_label, label));
        android.graphics.drawable.Drawable drawable = getDrawable(icon).mutate();
        drawable.setTint(color); drawable.setBounds(0, 0, px(23), px(23));
        text.setSpan(new android.text.style.ImageSpan(drawable, android.text.style.ImageSpan.ALIGN_CENTER),
                0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(text); view.setContentDescription(label);
    }
    private void guide(LinearLayout parent, int icon, String title, String detail, int color) {
        View view = card(title, detail, icon, 0, false, null, 0); view.setBackground(background(color, 18)); add(parent, view, -2, 11);
    }
    private String errorText(String code) {
        switch (code) {
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
