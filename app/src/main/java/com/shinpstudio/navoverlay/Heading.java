package com.shinpstudio.navoverlay;

final class Heading {
    private static final String[] DIRECTIONS = {
        "↑ 正面", "↗ 右前", "→ 右", "↘ 右後ろ", "↓ 後ろ", "↙ 左後ろ", "← 左", "↖ 左前"
    };

    private Heading() {}

    static double magneticDegrees(float[] screenToWorld) {
        // The screen-right axis is the first column. The horizontal forward
        // direction is perpendicular to it, so tilting the phone upward keeps
        // the same heading when the top edge is almost vertical.
        double east = -screenToWorld[3];
        double north = screenToWorld[0];
        if (Math.hypot(east, north) < 0.35) return Double.NaN;
        return Math.toDegrees(Math.atan2(east, north));
    }

    static int sector(double routeBearing, double deviceBearing, int previous) {
        double relative = Math.IEEEremainder(routeBearing - deviceBearing, 360);
        // Keep the previous sector through small compass fluctuations at a boundary.
        if (previous >= 0 && Math.abs(Math.IEEEremainder(relative - previous * 45, 360)) <= 28) {
            return previous;
        }
        return Math.floorMod((int) Math.round(relative / 45), DIRECTIONS.length);
    }

    static String direction(int sector) {
        return DIRECTIONS[sector];
    }
}
