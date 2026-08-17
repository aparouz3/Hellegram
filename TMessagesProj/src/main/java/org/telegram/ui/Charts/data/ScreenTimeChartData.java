/*
 * Simple ChartData subclass that accepts raw arrays directly (no JSON needed).
 * Used by ScreenTimeActivity to feed Telegram's native BarChartView / LinearBarChartView.
 */
package org.telegram.ui.Charts.data;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class ScreenTimeChartData extends ChartData {

    /**
     * Build chart data from raw arrays.
     * @param x        x-axis values (epoch timestamps or indices)
     * @param y        y-axis values (screen time in ms)
     * @param color    ARGB color for the line
     * @param lineId   line id string
     * @param hourLabels  if true, x labels are formatted as "HH:00"
     */
    public ScreenTimeChartData(long[] x, long[] y, int color, String lineId, boolean hourLabels) {
        this.x = x;
        if (x.length > 1) {
            timeStep = x[1] - x[0];
        } else {
            timeStep = 1;
        }
        if (timeStep <= 0) timeStep = 1;

        Line line = new Line();
        line.id = lineId;
        line.y = y;
        line.color = color;
        line.colorDark = color;
        line.colorKey = 0;
        line.maxValue = 0;
        line.minValue = Long.MAX_VALUE;
        for (long v : y) {
            if (v > line.maxValue) line.maxValue = v;
            if (v < line.minValue) line.minValue = v;
        }
        if (line.minValue == Long.MAX_VALUE) line.minValue = 0;
        lines = new ArrayList<>();
        lines.add(line);

        measure();

        // Override daysLookup for hour labels if needed
        if (hourLabels) {
            daysLookup = new String[x.length + 2];
            for (int i = 0; i < daysLookup.length; i++) {
                daysLookup[i] = String.format(Locale.ENGLISH, "%02d:00", Math.min(i, 23));
            }
        }

        maxValue = line.maxValue;
        minValue = 0;
    }
}
