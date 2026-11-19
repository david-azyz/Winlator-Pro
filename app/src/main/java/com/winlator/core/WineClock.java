package com.winlator.core;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.text.format.DateFormat;
import android.widget.Toast;

import com.winlator.R;
import com.winlator.container.Container;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Date, time and time zone that Windows programs see inside Wine.
 *
 * - The time zone always follows the phone (Wine reads TZ; without it the
 *   rootfs falls back to UTC, three hours ahead of Brasilia).
 * - A container can have its own date/time: Android does not let apps change
 *   the system clock, so Wine gets the real time plus an offset through
 *   libwinlator_timeshim.so (tools/timeshim), preloaded with BOX64_LD_PRELOAD.
 *   The clock keeps running from the chosen moment.
 */
public final class WineClock {
    public static final String EXTRA_TIME_OFFSET = "timeOffset";
    private static final String SHIM_ASSET = "time/libwinlator_timeshim.so";

    private WineClock() {}

    /** POSIX TZ for the phone's current offset, e.g. "<-03>3" for Brasilia. */
    public static String posixTimeZone() {
        int offsetMin = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000;
        int abs = Math.abs(offsetMin);
        int h = abs / 60, m = abs % 60;
        String sign = offsetMin < 0 ? "-" : "+";
        String name = m == 0 ? String.format(Locale.US, "<%s%02d>", sign, h) : String.format(Locale.US, "<%s%02d%02d>", sign, h, m);
        // POSIX offsets are west-positive: UTC-3 is written as "3".
        String posixSign = offsetMin <= 0 ? "" : "-";
        return name + posixSign + h + (m == 0 ? "" : ":" + m);
    }

    public static long getOffsetSeconds(Container container) {
        try {
            return Long.parseLong(container.getExtra(EXTRA_TIME_OFFSET, "0").trim());
        }
        catch (NumberFormatException e) {
            return 0;
        }
    }

    public static void setOffsetSeconds(Container container, long seconds) {
        container.putExtra(EXTRA_TIME_OFFSET, seconds == 0 ? null : String.valueOf(seconds));
        container.saveData();
    }

    /** Extracts the shim; returns its path or null. */
    public static String installShim(Context context) {
        File dir = new File(context.getFilesDir(), "time");
        if (!dir.isDirectory()) dir.mkdirs();
        File shim = new File(dir, "libwinlator_timeshim.so");
        try (InputStream in = context.getAssets().open(SHIM_ASSET); OutputStream out = new FileOutputStream(shim)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        }
        catch (Exception e) {
            return null;
        }
        shim.setReadable(true, false);
        return shim.getPath();
    }

    private static String format(Context context, long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return DateFormat.getDateFormat(context).format(c.getTime()) + " " + DateFormat.getTimeFormat(context).format(c.getTime());
    }

    /** Dialog to choose the date/time Windows programs see in this container. */
    public static void showDialog(Activity activity, Container container) {
        long offset = getOffsetSeconds(container);
        long now = System.currentTimeMillis();
        String message = offset == 0
            ? activity.getString(R.string.wine_clock_using_phone, format(activity, now))
            : activity.getString(R.string.wine_clock_using_custom, format(activity, now + offset * 1000L), format(activity, now));

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
            .setTitle(R.string.wine_clock_title)
            .setMessage(message)
            .setPositiveButton(R.string.wine_clock_choose, (d, w) -> pickDateTime(activity, container))
            .setNegativeButton(android.R.string.cancel, null);
        if (offset != 0) {
            builder.setNeutralButton(R.string.wine_clock_reset, (d, w) -> {
                setOffsetSeconds(container, 0);
                Toast.makeText(activity, R.string.wine_clock_reset_done, Toast.LENGTH_LONG).show();
            });
        }
        builder.show();
    }

    private static void pickDateTime(Activity activity, Container container) {
        final Calendar c = Calendar.getInstance();
        c.setTimeInMillis(System.currentTimeMillis() + getOffsetSeconds(container) * 1000L);
        new DatePickerDialog(activity, (view, year, month, day) -> {
            c.set(year, month, day);
            new TimePickerDialog(activity, (v, hour, minute) -> {
                c.set(Calendar.HOUR_OF_DAY, hour);
                c.set(Calendar.MINUTE, minute);
                c.set(Calendar.SECOND, 0);
                long seconds = (c.getTimeInMillis() - System.currentTimeMillis()) / 1000L;
                // Within a minute of the real time means "use the phone's clock".
                if (Math.abs(seconds) < 60) seconds = 0;
                setOffsetSeconds(container, seconds);
                Toast.makeText(activity, activity.getString(R.string.wine_clock_saved, format(activity, c.getTimeInMillis())), Toast.LENGTH_LONG).show();
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), DateFormat.is24HourFormat(activity)).show();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }
}
