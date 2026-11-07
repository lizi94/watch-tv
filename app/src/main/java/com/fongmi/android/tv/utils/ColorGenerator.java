package com.fongmi.android.tv.utils;

import com.github.bassaer.library.MDColor;

import java.util.ArrayList;
import java.util.List;

public class ColorGenerator {

    private static final List<Integer> PALETTE_400;

    static {
        PALETTE_400 = new ArrayList<>();
        PALETTE_400.add(MDColor.RED_400);
        PALETTE_400.add(MDColor.PINK_400);
        PALETTE_400.add(MDColor.PURPLE_400);
        PALETTE_400.add(MDColor.DEEP_PURPLE_400);
        PALETTE_400.add(MDColor.INDIGO_400);
        PALETTE_400.add(MDColor.BLUE_400);
        PALETTE_400.add(MDColor.LIGHT_BLUE_400);
        PALETTE_400.add(MDColor.CYAN_400);
        PALETTE_400.add(MDColor.TEAL_400);
        PALETTE_400.add(MDColor.GREEN_400);
        PALETTE_400.add(MDColor.LIGHT_GREEN_400);
        PALETTE_400.add(MDColor.LIME_400);
        PALETTE_400.add(MDColor.YELLOW_400);
        PALETTE_400.add(MDColor.AMBER_400);
        PALETTE_400.add(MDColor.ORANGE_400);
        PALETTE_400.add(MDColor.DEEP_ORANGE_400);
        PALETTE_400.add(MDColor.BROWN_400);
        PALETTE_400.add(MDColor.GREY_400);
        PALETTE_400.add(MDColor.BLUE_GREY_400);

    }

    public static int get400(String key) {
        return PALETTE_400.get((key.hashCode() & Integer.MAX_VALUE) % PALETTE_400.size());
    }
}
