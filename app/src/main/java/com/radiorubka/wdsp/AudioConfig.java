package com.radiorubka.wdsp;

/**
 * Shared audio configuration and constants for wDSP.
 * Centralizing these allows easy tuning of the Fletcher-Munson curve 
 * and fatigue trim offsets.
 */
@SuppressWarnings("SpellCheckingInspection")
public class AudioConfig {
    public static final int NUM_BANDS = 16;
    
    // Index-to-MCU Gain value mapping
    public static final int[] GAIN_MAP = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};

    // ISO 226 Max Offsets (dB) at Volume 1 relative to calibration point
    public static final float[] ISO_MAX_OFFSETS = {
            12.0f, 10.0f, 8.0f, 6.0f, 4.0f, 2.0f, 1.0f, 0.5f,
            0.0f, 0.5f, 1.0f, 2.0f, 3.0f, 4.0f, 6.0f, 8.0f
    };

    // Fatigue Trim Max Offsets (dB) at Volume 32 relative to calibration point
    public static final float[] FATIGUE_MAX_OFFSETS = {
            0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 0.0f, -1.0f, -2.0f, -3.0f, -3.5f
    };
    
    // Frequency labels for UI and Logging
    public static final String[] BAND_LABELS = {
            "20", "31.5", "50", "80", "125", "200", "315", "500", 
            "800", "1.25k", "2k", "3.15k", "5k", "8k", "12.5k", "20k"
    };
}