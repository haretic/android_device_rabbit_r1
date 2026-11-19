// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels;

import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The account's devices, when it has as many as Mullvad allows: one to remove, so that this
 * r1 can be added. A press or a tap arms a device's Remove, a second removes it, and the
 * set-up tries again.
 */
public final class MullvadDevicesActivity extends Page {
    private static final long CONFIRM_MS = 4000;

    private View mStatus;
    private View mArmed;
    private boolean mBusy;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Draft.mullvad == null) {
            finish();
            return;
        }
        title(R.string.kind_mullvad);
        lead(getText(R.string.devices_lead));
        mStatus = note(mBody, R.drawable.ic_info, getText(R.string.mullvad_asking));
        hint(R.string.hint_device);
        load();
    }

    private void load() {
        final Mullvad mullvad = Draft.mullvad;
        new Thread(() -> {
            try {
                final JSONArray devices = mullvad.devices();
                runOnUiThread(() -> show(devices));
            } catch (Mullvad.Failure f) {
                runOnUiThread(() -> noteText(mStatus, f.isRateLimit()
                        ? getText(R.string.mullvad_wait)
                        : EngineUi.error(this, f.error), true));
            } catch (IOException e) {
                runOnUiThread(() -> noteText(mStatus, EngineUi.error(this, Mullvad.errorOf(e)), true));
            }
        }).start();
    }

    private void show(JSONArray devices) {
        if (isDestroyed()) {
            return;
        }
        noteText(mStatus, "", true);
        final SimpleDateFormat format = new SimpleDateFormat(
                DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMMMMyyyy"),
                Locale.getDefault());
        View first = null;
        for (int i = 0; i < devices.length(); i++) {
            final JSONObject device = devices.optJSONObject(i);
            if (device == null) {
                continue;
            }
            final String id = device.optString("id", "");
            final long created = Mullvad.time(device.optString("created", ""));
            final String added = created > 0
                    ? getString(R.string.device_sum, format.format(new Date(created))) : "";
            final View row = row(mBody, R.drawable.ic_device, device.optString("name", id),
                    added);
            endText(row, getText(R.string.remove));
            pressHint(row, getText(R.string.hint_device));
            row.setOnClickListener(v -> remove(row, id, added));
            if (first == null) {
                first = row;
            }
        }
        // The status line goes under the list
        mBody.removeView(mStatus);
        mBody.addView(mStatus);
        if (first != null) {
            first.requestFocus();
        }
    }

    private void remove(View row, String id, String added) {
        if (mBusy || id.isEmpty()) {
            return;
        }
        if (mArmed != row) {
            if (mArmed != null) {
                summary(mArmed, (CharSequence) mArmed.getTag());
            }
            mArmed = row;
            row.setTag(added);
            summary(row, getText(R.string.remove_confirm));
            later(() -> {
                if (mArmed == row) {
                    mArmed = null;
                    summary(row, added);
                }
            }, CONFIRM_MS);
            return;
        }
        mBusy = true;
        noteText(mStatus, getText(R.string.mullvad_asking), false);
        final Mullvad mullvad = Draft.mullvad;
        new Thread(() -> {
            try {
                mullvad.removeDevice(id);
                runOnUiThread(() -> {
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (IOException e) {
                Log.w(Store.TAG, "Device not removed: " + e.getClass().getSimpleName());
                runOnUiThread(() -> {
                    mBusy = false;
                    noteText(mStatus, EngineUi.error(this, Mullvad.errorOf(e)), true);
                });
            }
        }).start();
    }
}
