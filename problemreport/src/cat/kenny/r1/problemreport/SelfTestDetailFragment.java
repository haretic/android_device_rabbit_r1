// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.problemreport;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;

import cat.kenny.r1.wheel.WheelSelect;

import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A test's failure text and the screenshots its log names. */
public class SelfTestDetailFragment extends SettingsBasePreferenceFragment {

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor();

    static SelfTestDetailFragment forTest(String token, SelfTest.Test test) {
        final Bundle args = new Bundle();
        args.putString(SelfTest.EXTRA_TOKEN, token);
        args.putString("name", test.name);
        args.putString("verdict", test.verdict);
        args.putBoolean("retried", test.retried);
        args.putString("failures", String.join("\n", test.failures));
        args.putStringArrayList("shots", new ArrayList<>(test.shots));
        final SelfTestDetailFragment fragment = new SelfTestDetailFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        final Context context = requireContext();
        final Bundle args = requireArguments();
        final String name = args.getString("name");
        final String verdict = SelfTestFragment.verdict(context,
                args.getString("verdict", ""), args.getBoolean("retried"));
        final String failures = args.getString("failures", "");
        final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
        setPreferenceScreen(screen);
        final Preference text = new SelfTestFragment.TextPreference(context);
        text.setIconSpaceReserved(false);
        text.setSingleLineTitle(false);
        text.setTitle(getString(R.string.self_test_test_verdict, name, verdict));
        text.setSummary(failures);
        text.setOnPreferenceClickListener(p -> {
            message(context, name, failures.isEmpty() ? verdict : failures);
            return true;
        });
        screen.addPreference(text);

        final int width = getResources().getDisplayMetrics().widthPixels;
        for (String shot : args.getStringArrayList("shots")) {
            final ImagePreference image = new ImagePreference(context, shot);
            screen.addPreference(image);
            final String token = args.getString(SelfTest.EXTRA_TOKEN);
            mWorker.execute(() -> {
                final Bitmap bitmap = load(SelfTest.shot(token, shot), width);
                mHandler.post(() -> {
                    if (isAdded()) image.loaded(bitmap);
                });
            });
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mWorker.shutdownNow();
        mHandler.removeCallbacksAndMessages(null);
    }

    static void message(Context context, String title, String text) {
        WheelSelect.attach(new AlertDialog.Builder(context)
                .setTitle(title).setMessage(text)
                .setPositiveButton(R.string.self_test_close, null).show());
    }

    private static Bitmap load(File file, int width) {
        if (file == null) return null;
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        options.inSampleSize = 1;
        while (options.outWidth / options.inSampleSize > width * 2
                || options.outHeight / options.inSampleSize > width * 3) {
            options.inSampleSize *= 2;
        }
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(file.getPath(), options);
    }

    private static class ImagePreference extends Preference {
        private Bitmap mBitmap;

        ImagePreference(Context context, String name) {
            super(context);
            setTitle(name);
            setSummary(R.string.self_test_loading_shot);
            setLayoutResource(R.layout.self_test_image);
            setSelectable(false);
            setPersistent(false);
        }

        void loaded(Bitmap bitmap) {
            mBitmap = bitmap;
            setSummary(bitmap == null ? getContext().getString(R.string.self_test_missing_shot)
                    : null);
            setSelectable(bitmap != null);
            setOnPreferenceClickListener(bitmap == null ? null : p -> {
                final ImageView image = new ImageView(getContext());
                image.setImageBitmap(mBitmap);
                image.setAdjustViewBounds(true);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setMaxHeight(Math.max(1, getContext().getResources().getDisplayMetrics()
                        .heightPixels - (int) (160 * getContext().getResources()
                                .getDisplayMetrics().density)));
                WheelSelect.attach(new AlertDialog.Builder(getContext())
                        .setTitle(getTitle()).setView(image)
                        .setPositiveButton(R.string.self_test_close, null).show());
                return true;
            });
            notifyChanged();
        }

        @Override
        public void onBindViewHolder(PreferenceViewHolder holder) {
            super.onBindViewHolder(holder);
            ((ImageView) holder.findViewById(R.id.self_test_image)).setImageBitmap(mBitmap);
        }
    }
}
