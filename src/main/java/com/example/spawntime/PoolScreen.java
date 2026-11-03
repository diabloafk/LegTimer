package com.example.spawntime;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Two views:
 *  - AT SPAWN TIME: legendaries that can spawn at the predicted time (Pixelmon time ranges + wiki data).
 *  - RIGHT NOW: the list from your latest /checkspawns legendary.
 * Click a name to run /wiki <name>; right-click opens the Pixelmon Wiki website.
 */
public class PoolScreen extends Screen {

    private static final int VIS = 9, ROW_H = 13, PW = 344, PH = 216;
    private static final String[] FILTERS = {"ALL LEGENDARIES", "SEEN ON SERVER", "IN MY POOL"};
    private static final int CONFIRMED = 0xFF7CFFB0, DEFAULT_TIME = 0xFFA8B0D8, DIM = 0xFF8890B8;

    private static final class Item {
        SpawnTimeMod.SpawnRow row;     // spawn-time row
        Map.Entry<String, Double> pool; // right-now row
        String unknown;                 // name without time data
        String header;                  // section header text
        String name() {
            if (row != null) return row.name;
            if (pool != null) return pool.getKey();
            return unknown;
        }
    }

    private final SpawnTimeMod mod;
    private final List<Item> items = new ArrayList<>();
    private int view, filter, scroll;
    private int px, py, listTop;
    private int tab0x0, tab0x1, tab1x0, tab1x1, fx0, fx1;

    protected PoolScreen(SpawnTimeMod mod) {
        super(Component.literal("Spawn Pool"));
        this.mod = mod;
        this.view = mod.hasWindow() ? 0 : 1;
        this.filter = mod.knownCount() > 0 ? 1 : 0;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private void buildItems() {
        items.clear();
        if (view == 0) {
            for (SpawnTimeMod.SpawnRow r : mod.spawnRows(filter)) {
                Item it = new Item();
                it.row = r;
                items.add(it);
            }
            List<String> unk = mod.unknownNames();
            if (!unk.isEmpty()) {
                Item h = new Item();
                h.header = "NO TIME DATA YET - click, then press [Spawning] in chat";
                items.add(h);
                for (String n : unk) {
                    Item it = new Item();
                    it.unknown = n;
                    items.add(it);
                }
            }
        } else {
            List<Map.Entry<String, Double>> es = new ArrayList<>(mod.pool().entrySet());
            es.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            for (Map.Entry<String, Double> e : es) {
                Item it = new Item();
                it.pool = e;
                items.add(it);
            }
        }
        scroll = Math.max(0, Math.min(scroll, Math.max(0, items.size() - VIS)));
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        Font f = this.font;
        buildItems();

        g.fill(0, 0, this.width, this.height, 0xB0000000);
        px = (this.width - PW) / 2;
        py = Math.max(4, (this.height - PH) / 2);
        g.fill(px, py, px + PW, py + PH, 0xE0101018);
        g.fill(px, py, px + PW, py + 1, SpawnTimeMod.CYAN);

        // title + close
        drawGradient(g, f, "SPAWN POOL", px + 10, py + 8, SpawnTimeMod.PINK, SpawnTimeMod.BLUE);
        boolean overX = mx >= px + PW - 18 && mx <= px + PW - 4 && my >= py + 4 && my <= py + 18;
        g.drawString(f, "X", px + PW - 14, py + 8, overX ? 0xFFFF6677 : SpawnTimeMod.LABEL, true);

        // tabs
        String t0 = "AT SPAWN TIME", t1 = "RIGHT NOW";
        tab0x0 = px + 10;
        tab0x1 = tab0x0 + f.width(t0);
        tab1x0 = tab0x1 + 16;
        tab1x1 = tab1x0 + f.width(t1);
        int ty = py + 23;
        g.drawString(f, t0, tab0x0, ty, view == 0 ? SpawnTimeMod.CYAN : SpawnTimeMod.LABEL, true);
        g.drawString(f, t1, tab1x0, ty, view == 1 ? SpawnTimeMod.CYAN : SpawnTimeMod.LABEL, true);
        if (view == 0) g.fill(tab0x0, ty + 10, tab0x1, ty + 11, SpawnTimeMod.CYAN);
        else g.fill(tab1x0, ty + 10, tab1x1, ty + 11, SpawnTimeMod.CYAN);

        fx0 = fx1 = -1;
        if (view == 0) {
            String ft = "SHOW: " + FILTERS[filter];
            fx1 = px + PW - 10;
            fx0 = fx1 - f.width(ft);
            boolean hf = mx >= fx0 && mx <= fx1 && my >= ty - 2 && my <= ty + 10;
            g.drawString(f, ft, fx0, ty, hf ? SpawnTimeMod.CYAN : SpawnTimeMod.GOLD, true);
        }

        // info lines
        int iy = py + 39;
        if (view == 0) {
            String wt = mod.windowText();
            if (wt.isEmpty()) {
                g.drawString(f, "No countdown yet - run /checkspawns legendary first.", px + 10, iy, SpawnTimeMod.LABEL, true);
            } else {
                g.drawString(f, "SPAWN ATTEMPT  " + wt, px + 10, iy, SpawnTimeMod.VALUE, true);
                g.drawString(f, "PIXELMON TIME: " + String.join(", ", mod.activeLabels()), px + 10, iy + 11, SpawnTimeMod.LABEL, true);
            }
        } else {
            String stamp = mod.poolStamp().isEmpty() ? "No check yet - run /checkspawns legendary" : "As of " + mod.poolStamp();
            if (mod.spawnChance() >= 0) stamp += "   SPAWN CHANCE " + trim(mod.spawnChance()) + "%";
            g.drawString(f, stamp, px + 10, iy, SpawnTimeMod.LABEL, true);
        }
        g.fill(px + 6, py + 62, px + PW - 6, py + 63, SpawnTimeMod.CYAN);

        // rows
        listTop = py + 67;
        int hovered = -1;
        double max = 1;
        if (view == 1 && !items.isEmpty() && items.get(0).pool != null) max = Math.max(1, items.get(0).pool.getValue());
        for (int i = 0; i < VIS && scroll + i < items.size(); i++) {
            Item it = items.get(scroll + i);
            int ry = listTop + i * ROW_H;
            if (it.header != null) {
                g.drawString(f, it.header, px + 10, ry + 1, DIM, true);
                continue;
            }
            boolean hov = mx >= px + 4 && mx <= px + PW - 12 && my >= ry - 1 && my < ry + ROW_H - 1;
            if (hov) {
                hovered = scroll + i;
                g.fill(px + 4, ry - 1, px + PW - 12, ry + ROW_H - 2, 0x33FFFFFF);
            }
            if (it.row != null) {
                SpawnTimeMod.SpawnRow r = it.row;
                int dot = r.inPool ? SpawnTimeMod.GREEN : (r.coverage < 0.999 ? SpawnTimeMod.GOLD : 0xFF6A7088);
                g.fill(px + 10, ry + 3, px + 14, ry + 7, dot);
                g.drawString(f, r.name, px + 18, ry + 1, hov ? SpawnTimeMod.CYAN : SpawnTimeMod.VALUE, true);
                String times = f.plainSubstrByWidth(SpawnTimeMod.timesText(r.times), 118);
                g.drawString(f, times, px + 124, ry + 1, r.confirmed ? CONFIRMED : DEFAULT_TIME, true);
                String right = "";
                int rc = SpawnTimeMod.LABEL;
                if (r.inPool) {
                    right = "IN POOL " + trim(r.pct) + "%";
                    rc = SpawnTimeMod.GREEN;
                } else if (r.coverage < 0.999) {
                    right = Math.round(r.coverage * 100) + "% OF WINDOW";
                    rc = SpawnTimeMod.GOLD;
                }
                g.drawString(f, right, px + PW - 16 - f.width(right), ry + 1, rc, true);
            } else if (it.unknown != null) {
                g.fill(px + 10, ry + 3, px + 14, ry + 7, 0xFF6A7088);
                g.drawString(f, it.unknown, px + 18, ry + 1, hov ? SpawnTimeMod.CYAN : DIM, true);
                String right = "? /wiki";
                g.drawString(f, right, px + PW - 16 - f.width(right), ry + 1, DIM, true);
            } else {
                Map.Entry<String, Double> e = it.pool;
                g.drawString(f, e.getKey(), px + 10, ry + 1, hov ? SpawnTimeMod.CYAN : SpawnTimeMod.VALUE, true);
                int bx = px + 116, bw = 96;
                g.fill(bx, ry + 3, bx + bw, ry + 8, 0x44FFFFFF);
                int fill = (int) (bw * (e.getValue() / max));
                g.fill(bx, ry + 3, bx + Math.max(2, fill), ry + 8, lerp(SpawnTimeMod.BLUE, SpawnTimeMod.PINK, e.getValue() / max));
                String pct = trim(e.getValue()) + "%";
                g.drawString(f, pct, px + 218 + (40 - f.width(pct)), ry + 1, SpawnTimeMod.VALUE, true);
                if (mod.spawnChance() >= 0) {
                    String eff = "(" + trim(e.getValue() * mod.spawnChance() / 100.0) + "%)";
                    g.drawString(f, eff, px + PW - 16 - f.width(eff), ry + 1, SpawnTimeMod.LABEL, true);
                }
            }
        }
        if (items.isEmpty()) {
            String empty = view == 0
                    ? (mod.hasWindow() ? "Nothing matches this filter - press F to change it." : "Waiting for a countdown.")
                    : "No spawn list captured yet.";
            g.drawString(f, empty, px + 10, listTop + 2, SpawnTimeMod.LABEL, true);
        }

        // scroll bar
        if (items.size() > VIS) {
            int trackH = VIS * ROW_H;
            int barH = Math.max(10, trackH * VIS / items.size());
            int barY = listTop + (trackH - barH) * scroll / Math.max(1, items.size() - VIS);
            g.fill(px + PW - 9, listTop, px + PW - 7, listTop + trackH, 0x33FFFFFF);
            g.fill(px + PW - 9, barY, px + PW - 7, barY + barH, SpawnTimeMod.CYAN);
        }

        // footer
        int fy = py + PH - 22;
        if (view == 0) {
            int total = 0, here = 0;
            for (Item it : items) {
                if (it.row != null) {
                    total++;
                    if (it.row.inPool) here++;
                }
            }
            g.drawString(f, total + " CAN SPAWN   -   " + here + " IN YOUR LATEST POOL   -   GREEN = CONFIRMED BY /WIKI",
                    px + 10, fy, SpawnTimeMod.LABEL, true);
        } else {
            g.drawString(f, items.size() + " LEGENDARIES IN YOUR LATEST /CHECKSPAWNS", px + 10, fy, SpawnTimeMod.LABEL, true);
        }
        g.drawString(f, SpawnTimeMod.WIKI_IN_GAME
                        ? "Click: /wiki   Right-click: website   Wheel: scroll   TAB: view   F: filter"
                        : "Click: website   Wheel: scroll   TAB: view   F: filter",
                px + 10, fy + 11, DIM, true);

        if (hovered >= 0) {
            String nm = wikiName(items.get(hovered).name());
            g.renderTooltip(f, Component.literal(SpawnTimeMod.WIKI_IN_GAME
                    ? "Click: /" + SpawnTimeMod.WIKI_COMMAND + " " + nm + "   -   Right-click: website"
                    : "Click: open the Pixelmon Wiki website"), mx, my);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            if (mx >= px + PW - 18 && mx <= px + PW - 4 && my >= py + 4 && my <= py + 18) {
                this.onClose();
                return true;
            }
            if (my >= py + 21 && my <= py + 35) {
                if (mx >= tab0x0 && mx <= tab0x1) { view = 0; scroll = 0; return true; }
                if (mx >= tab1x0 && mx <= tab1x1) { view = 1; scroll = 0; return true; }
                if (view == 0 && fx0 >= 0 && mx >= fx0 && mx <= fx1) { filter = (filter + 1) % FILTERS.length; scroll = 0; return true; }
            }
        }
        if (button == 0 || button == 1) {
            int idx = scroll + (int) ((my - listTop + 1) / ROW_H);
            int ry = listTop + (idx - scroll) * ROW_H;
            if (my >= listTop - 1 && idx >= scroll && idx < scroll + VIS && idx < items.size()
                    && mx >= px + 4 && mx <= px + PW - 12 && my >= ry - 1 && my < ry + ROW_H - 1) {
                Item it = items.get(idx);
                if (it.header == null) {
                    String raw = it.name();
                    if (SpawnTimeMod.WIKI_IN_GAME && button == 0) {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc.player != null) {
                            this.onClose(); // back to the game so you can read the answer in chat
                            mc.player.connection.sendCommand(SpawnTimeMod.WIKI_COMMAND + " " + wikiName(raw));
                        }
                    } else {
                        Util.getPlatform().openUri("https://pixelmonmod.com/wiki/" + raw.trim().replace(' ', '_'));
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (sy != 0) {
            scroll = Math.max(0, Math.min(scroll - (int) Math.signum(sy), Math.max(0, items.size() - VIS)));
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == InputConstants.KEY_TAB) {
            view = 1 - view;
            scroll = 0;
            return true;
        }
        if (keyCode == InputConstants.KEY_F && view == 0) {
            filter = (filter + 1) % FILTERS.length;
            scroll = 0;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private static String wikiName(String raw) {
        return raw.trim().replace(" ", SpawnTimeMod.WIKI_NAME_SPACE);
    }

    private static String trim(double v) {
        String s = String.format("%.2f", v);
        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    private static int lerp(int c1, int c2, double t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) (((c1 >> 16) & 255) * (1 - t) + ((c2 >> 16) & 255) * t);
        int gr = (int) (((c1 >> 8) & 255) * (1 - t) + ((c2 >> 8) & 255) * t);
        int b = (int) ((c1 & 255) * (1 - t) + (c2 & 255) * t);
        return 0xFF000000 | (r << 16) | (gr << 8) | b;
    }

    private static void drawGradient(GuiGraphics g, Font font, String s, int x, int y, int c1, int c2) {
        int cx = x;
        int n = Math.max(1, s.length() - 1);
        for (int i = 0; i < s.length(); i++) {
            String ch = String.valueOf(s.charAt(i));
            g.drawString(font, ch, cx, y, lerp(c1, c2, i / (double) n), true);
            cx += font.width(ch);
        }
    }
}
