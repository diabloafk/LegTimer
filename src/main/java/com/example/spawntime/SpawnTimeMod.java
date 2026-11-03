package com.example.spawntime;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.scores.DisplaySlot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spawn Time - client-only mod (NeoForge 21.1.x, Minecraft 1.21.1, official mappings).
 *
 * PASSIVE: the mod never hides or changes what the server sends and never sends anything by itself.
 * It reads the output of the /checkspawns legendary you type yourself. The one thing it can send is
 * /wiki <name>, and only when YOU click a name in the spawn pool screen (one click = one command, like typing it).
 *
 *  - Every check you make narrows the window in which the next spawn attempt must happen. Checks at random
 *    times work; the panel also tells you the best moment for the next one.
 *  - A panel above the scoreboard shows the server time, the time of day, the countdown and a 24h strip.
 *  - The spawn pool from each check is saved. Press the key (default K) or type /spawntime pool to open it.
 *    Click a name to run /wiki <name> on the server; right-click opens the Pixelmon Wiki website instead.
 *  - The spawn pool screen can also list which legendaries can spawn at the PREDICTED time. It uses Pixelmon's
 *    time-of-day ranges, the default legendary table, and what it learns when you view a legendary's
 *    "Spawning Wiki" in chat (/wiki <name>, then the [Spawning] button). Learned data overrides the defaults.
 */
@Mod(value = "spawntime", dist = Dist.CLIENT)
public class SpawnTimeMod {

    // ---- settings ---------------------------------------------------------------------------
    // Shift if the server clock differs from the world time, in minutes (+ = server clock is ahead).
    private static final int OFFSET_MINUTES = 0;
    // Starting guess for how the server rounds the shown time. The mod corrects itself if wrong.
    private static final boolean ROUND_UP_GUESS = true;
    // Soft "ding" when it is the best moment to run /checkspawns legendary again. /spawntime ping on|off.
    private static final boolean PING_SOUND = true;
    // A window this narrow counts as exact.
    private static final long EXACT_MS = 3000;
    // Assumed delay between two uses of the command (the mod learns the real one from the server's message).
    private static final long COOLDOWN_GUESS_MS = 16000;

    // Clicking a legendary in the spawn pool screen runs this server command (the same as typing it yourself).
    // Set WIKI_IN_GAME = false to make a click open the Pixelmon Wiki website instead.
    static final boolean WIKI_IN_GAME = true;
    static final String WIKI_COMMAND = "wiki";       // without the leading slash
    static final String WIKI_NAME_SPACE = "_";        // what to put where a name has a space (Tapu Bulu -> Tapu_Bulu)

    // Panel: right edge, directly ABOVE the scoreboard sidebar.
    private static final int SIDEBAR_LINES = 12;   // lines in your sidebar (blank lines count)
    private static final int PANEL_GAP = 4;
    private static final int PANEL_MIN_WIDTH = 124;
    private static final float PANEL_SCALE = 0.8f;
    private static final boolean HIDE_WITH_SIDEBAR = true; // hide while the sidebar is hidden (/sb)

    // Start hour (0-24) of each time of day, ascending. Edit to match your server.
    static final String[] NAMES = {"Midnight", "Dawn", "Morning", "Afternoon", "Dusk", "Night"};
    static final double[] STARTS = {0, 5, 7, 12, 17, 20};
    static final int[] COLORS = {0x6A6FE0, 0xFF9A3C, 0xFFD84A, 0x4DB6FF, 0xE0609A, 0x8A8FFF};
    // -----------------------------------------------------------------------------------------

    // Pixelmon's own time-of-day definitions, in world ticks (Pixelmon Wiki, "Spawn time"). They overlap:
    // DAY covers MORNING, MIDDAY and AFTERNOON, and NIGHT covers MIDNIGHT.
    static final String[] PX_TIMES = {"DAWN", "MORNING", "DAY", "MIDDAY", "AFTERNOON", "DUSK", "NIGHT", "MIDNIGHT"};

    static boolean pxActive(String label, int t) {
        switch (label) {
            case "DAWN": return t >= 22500 || t <= 300;
            case "MORNING": return t >= 22500 || t <= 6000;
            case "DAY": return t <= 12000;
            case "MIDDAY": return t >= 5500 && t <= 6500;
            case "AFTERNOON": return t >= 6000 && t <= 12000;
            case "DUSK": return t >= 12000 && t <= 13800;
            case "NIGHT": return t >= 13450 && t <= 22550;
            case "MIDNIGHT": return t >= 17500 && t <= 18500;
            default: return false;
        }
    }

    // Default legendary spawn times (Pixelmon Wiki, "Legendary"). ANY = no time condition listed.
    // What the server tells you through /wiki overrides these.
    private static final String[] SEED = {
            "Mew|DAY", "Articuno|ANY", "Zapdos|ANY", "Moltres|ANY", "Raikou|DAWN/MORNING", "Entei|DAY", "Suicune|NIGHT",
            "Celebi|ANY", "Regirock|AFTERNOON", "Regice|MORNING", "Registeel|DAWN/MORNING", "Latias|MORNING",
            "Latios|AFTERNOON", "Kyogre|NIGHT", "Groudon|DAY", "Rayquaza|AFTERNOON/DUSK", "Jirachi|DAWN/MORNING",
            "Deoxys|AFTERNOON", "Uxie|AFTERNOON", "Mesprit|DAWN/MORNING", "Azelf|MORNING", "Dialga|ANY", "Palkia|ANY",
            "Heatran|ANY", "Regigigas|AFTERNOON/DUSK", "Giratina|ANY", "Cresselia|NIGHT", "Manaphy|MORNING",
            "Darkrai|NIGHT", "Shaymin|MORNING", "Arceus|ANY", "Victini|DAY", "Cobalion|NIGHT", "Terrakion|NIGHT",
            "Virizion|DAY", "Tornadus|AFTERNOON", "Thundurus|MORNING", "Reshiram|DAY", "Zekrom|NIGHT",
            "Landorus|DAWN/MORNING", "Kyurem|AFTERNOON/DUSK", "Keldeo|DAY", "Meloetta|NIGHT", "Genesect|NIGHT",
            "Xerneas|DAY", "Yveltal|NIGHT", "Diancie|MORNING", "Hoopa|AFTERNOON", "Volcanion|DAWN/MORNING",
            "Type: Null|DUSK/NIGHT", "Tapu Koko|MORNING", "Tapu Lele|NIGHT", "Tapu Bulu|DAY", "Tapu Fini|AFTERNOON",
            "Cosmog|DUSK/NIGHT", "Necrozma|NIGHT", "Magearna|DAY", "Marshadow|NIGHT", "Zeraora|AFTERNOON",
            "Zacian|DAY", "Zamazenta|NIGHT", "Eternatus|DAWN/MORNING", "Kubfu|AFTERNOON", "Zarude|NIGHT",
            "Regieleki|AFTERNOON", "Regidrago|DAY", "Glastrier|DAY", "Spectrier|NIGHT", "Calyrex|MORNING",
            "Enamorus|DAWN/DUSK", "Wo-Chien|NIGHT", "Chien-Pao|NIGHT", "Ting-Lu|NIGHT", "Chi-Yu|DAY",
            "Koraidon|DAY", "Miraidon|NIGHT"
    };
    private static final Map<String, TreeSet<String>> DEFAULTS = new HashMap<>();
    private static final Map<String, String> DEFAULT_NAMES = new LinkedHashMap<>();

    static {
        for (String line : SEED) {
            String[] p = line.split("\\|");
            TreeSet<String> set = new TreeSet<>();
            for (String t : p[1].split("/")) set.add(t);
            DEFAULTS.put(key(p[0]), set);
            DEFAULT_NAMES.put(key(p[0]), p[0]);
        }
    }

    static final int PINK = 0xE8579A, BLUE = 0x4A7BFF, CYAN = 0xFF1EA7E1;
    static final int LABEL = 0xFFB9C4FF, VALUE = 0xFFFFFFFF, GREEN = 0xFF55FF88, GOLD = 0xFFFFD23F;

    private static final String MARKER = "Next attempt ";
    private static final Pattern LINE = Pattern.compile("next spawn attempt:\\s*(.*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PART = Pattern.compile("(\\d+)\\s*(hours?|minutes?|mins?|seconds?|secs?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COOLDOWN = Pattern.compile("cannot type that command(?: for (\\d+) seconds?)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern POOL = Pattern.compile("([A-Za-z][A-Za-z'.\\- ]*?):\\s*(\\d+(?:\\.\\d+)?)%");
    private static final Pattern CHANCE = Pattern.compile("spawn chance:\\s*(\\d+(?:\\.\\d+)?)%", Pattern.CASE_INSENSITIVE);

    // Real-time window (epoch ms) in which the next attempt must happen. 0 = nothing known yet.
    private long winLo = 0, winHi = 0;
    private boolean roundUp = ROUND_UP_GUESS;
    private int conflicts = 0, checks = 0;
    private long lastCheckMs = 0, cooldownUntil = 0, pingedFor = 0;
    private boolean overlayEnabled = true, pingEnabled = PING_SOUND, openPoolNext = false;
    private KeyMapping poolKey;

    // Spawn pool of the latest check + history of which time of day each legendary was seen in.
    private final Map<String, Double> pool = new LinkedHashMap<>();
    private double spawnChance = -1;
    private String poolStamp = "";
    private int poolBucket = 0;
    private long captureUntil = 0;
    private boolean dirty = false;
    private final Map<String, TreeSet<Integer>> seenIn = new TreeMap<>();

    // Times learned from the server's "Spawning Wiki" chat output (/wiki <name>), keyed by lower-case name.
    static final class SpecInfo {
        final String name;
        String rarity = "";
        final TreeSet<String> times = new TreeSet<>();
        SpecInfo(String name) { this.name = name; }
    }

    private final Map<String, SpecInfo> wiki = new TreeMap<>();
    private String wikiName = null, wikiRarity = "";
    private long wikiUntil = 0;
    private final TreeSet<String> wikiTimes = new TreeSet<>();
    private boolean inBlock = false, blockHasTime = false, wikiDirty = false;

    public SpawnTimeMod(IEventBus modBus) {
        modBus.addListener(this::onRegisterKeys);
        NeoForge.EVENT_BUS.register(this);
        loadHistory();
        loadWiki();
    }

    // ---- keys, commands -----------------------------------------------------------------------

    private void onRegisterKeys(RegisterKeyMappingsEvent event) {
        poolKey = new KeyMapping("key.spawntime.pool", InputConstants.KEY_K, "SpawnTime");
        event.register(poolKey);
    }

    @SubscribeEvent
    public void onCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("spawntime")
                .executes(c -> { say("commands: pool, overlay [on|off], ping [on|off], reset, forget"); return 1; })
                .then(Commands.literal("pool").executes(c -> { openPoolNext = true; return 1; }))
                .then(Commands.literal("overlay")
                        .executes(c -> { setOverlay(!overlayEnabled); return 1; })
                        .then(Commands.literal("on").executes(c -> { setOverlay(true); return 1; }))
                        .then(Commands.literal("off").executes(c -> { setOverlay(false); return 1; })))
                .then(Commands.literal("ping")
                        .executes(c -> { setPing(!pingEnabled); return 1; })
                        .then(Commands.literal("on").executes(c -> { setPing(true); return 1; }))
                        .then(Commands.literal("off").executes(c -> { setPing(false); return 1; })))
                .then(Commands.literal("reset")
                        .executes(c -> { winLo = winHi = 0; checks = 0; say("cleared. Run /checkspawns legendary to start again."); return 1; }))
                .then(Commands.literal("forget")
                        .executes(c -> { seenIn.clear(); wiki.clear(); saveHistory(); saveWiki(); say("saved spawn history and /wiki times cleared."); return 1; })));
    }

    private void setOverlay(boolean on) { overlayEnabled = on; say("overlay " + (on ? "ON" : "OFF")); }

    private void setPing(boolean on) { pingEnabled = on; say("check reminder sound " + (on ? "ON" : "OFF")); }

    private static void say(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal("[Spawn Time] " + msg).withStyle(ChatFormatting.LIGHT_PURPLE), false);
        }
    }

    // ---- chat (read only) ---------------------------------------------------------------------

    // Server messages usually arrive as "system" messages, so listen for both event types.
    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) { handle(event); }

    @SubscribeEvent
    public void onSystemChat(ClientChatReceivedEvent.System event) { handle(event); }

    private void handle(ClientChatReceivedEvent event) {
        String text = event.getMessage().getString().replaceAll("\u00a7.", "");
        if (text.contains(MARKER)) return; // already handled (both listeners may fire)
        long nowMs = System.currentTimeMillis();

        Matcher cd = COOLDOWN.matcher(text);
        if (cd.find()) { // learn the real cooldown
            if (cd.group(1) != null) cooldownUntil = nowMs + (Long.parseLong(cd.group(1)) + 1) * 1000L;
            return;
        }

        if (nowMs < wikiUntil || text.contains("Spawning Wiki")) { // the server's /wiki output
            captureWiki(text, nowMs);
            return;
        }

        Matcher m = LINE.matcher(text);
        Minecraft mc = Minecraft.getInstance();
        if (!m.find()) {
            if (nowMs < captureUntil) capturePool(text); // the pool lines that follow the timer line
            return;
        }
        long[] d = parseDuration(m.group(1)); // {seconds, precision in seconds}
        if (d == null || mc.level == null) return;

        process(d, nowMs);
        beginCapture(mc, nowMs);
        capturePool(text);

        double[] w = window(mc, nowMs);
        boolean exact = winHi - winLo <= EXACT_MS;
        String[] p = describeParts(w[0], w[1]);
        Component extra = Component.literal(
                "\n" + MARKER + (w[1] <= 5 ? "at " : "between ") + p[0].replace(" - ", " and ") + " (" + p[1] + ")"
                        + "  [now " + format12h(toClockMinutes(mc.level.getDayTime())) + "]"
                        + (exact ? "  EXACT" : "")
                        + "  [" + keyName() + "] spawn pool")
                .withStyle(ChatFormatting.LIGHT_PURPLE);
        event.setMessage(event.getMessage().copy().append(extra));
    }

    private String keyName() {
        return poolKey == null ? "?" : poolKey.getTranslatedKeyMessage().getString();
    }

    private void beginCapture(Minecraft mc, long nowMs) {
        double clock = toClockMinutes(mc.level.getDayTime());
        poolBucket = bucketIndex(clock / 60.0);
        poolStamp = format12h(clock) + "  " + NAMES[poolBucket].toUpperCase();
        pool.clear();
        spawnChance = -1;
        captureUntil = nowMs + 1500;
    }

    private void capturePool(String text) {
        if (text.contains("\u00bb")) return; // player chat
        Matcher sc = CHANCE.matcher(text);
        if (sc.find()) spawnChance = Double.parseDouble(sc.group(1));
        Matcher pm = POOL.matcher(text);
        while (pm.find()) {
            String name = fixName(pm.group(1).trim());
            if (name.isEmpty() || name.toLowerCase().startsWith("spawn chance")) continue;
            pool.put(name, Double.parseDouble(pm.group(2)));
            seenIn.computeIfAbsent(name, k -> new TreeSet<>()).add(poolBucket);
            dirty = true;
        }
    }

    // ---- narrowing the window -----------------------------------------------------------------

    private long[] bounds(long[] d, long nowMs) {
        long lo, hi;
        if (roundUp) { // "10 minutes" = more than 9 and up to 10 left
            lo = nowMs + (d[0] - d[1]) * 1000L;
            hi = nowMs + d[0] * 1000L;
        } else {       // "10 minutes" = 10 up to just under 11 left
            lo = nowMs + d[0] * 1000L;
            hi = nowMs + (d[0] + d[1]) * 1000L;
        }
        return new long[]{Math.max(lo, nowMs), hi};
    }

    private void process(long[] d, long nowMs) {
        long[] b = bounds(d, nowMs);
        boolean live = winHi > nowMs; // an earlier check of this same countdown is still valid
        long nl = Math.max(b[0], winLo), nh = Math.min(b[1], winHi);

        if (live && nl <= nh) {
            winLo = nl;
            winHi = nh;
            conflicts = 0;
            checks++;
        } else {
            if (live) {
                // Two checks of one countdown disagree. After repeated conflicts, the rounding guess is wrong.
                if (++conflicts >= 2) {
                    roundUp = !roundUp;
                    conflicts = 0;
                    b = bounds(d, nowMs);
                }
                checks++;
            } else {
                checks = 1;
            }
            winLo = b[0];
            winHi = b[1];
        }
        lastCheckMs = nowMs;
    }

    // Best moment (epoch ms) for the next check: one where the shown minutes flip exactly in the middle of the
    // window, so the answer cuts the remaining uncertainty in half. Allows being 3 s late.
    private long suggestAt(long nowMs) {
        if (winHi == 0) return -1;
        long mid = (winLo + winHi) / 2;
        long earliest = Math.max(Math.max(nowMs - 3000, lastCheckMs + COOLDOWN_GUESS_MS), cooldownUntil);
        long c = mid <= earliest ? earliest : mid - ((mid - earliest) / 60000L) * 60000L;
        return c > winHi ? -1 : c;
    }

    @SubscribeEvent
    public void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();

        while (poolKey != null && poolKey.consumeClick()) openPoolNext = true;
        if (openPoolNext) {
            openPoolNext = false;
            mc.setScreen(new PoolScreen(this));
        }
        if (dirty && now > captureUntil) {
            dirty = false;
            saveHistory();
        }
        if (wikiName != null && now > wikiUntil) finalizeWiki();
        if (wikiDirty && wikiName == null) {
            wikiDirty = false;
            saveWiki();
        }

        if (winHi != 0 && now > winHi + 3000) { // the attempt has happened
            winLo = winHi = 0;
            checks = 0;
            return;
        }
        // Reminder ding at the best moment for the next manual check.
        if (pingEnabled && winHi != 0 && winHi - winLo > EXACT_MS && mc.player != null) {
            long c = suggestAt(now);
            long rem = c - now;
            if (c > 0 && rem <= 0 && rem > -1500 && pingedFor != c) {
                pingedFor = c;
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.3f));
            }
        }
    }

    // ---- panel --------------------------------------------------------------------------------

    @SubscribeEvent
    public void onRender(RenderGuiEvent.Post event) {
        if (!overlayEnabled || winHi == 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        // Scoreboard hidden (for example with /sb)? Then hide the panel too.
        if (HIDE_WITH_SIDEBAR && mc.level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR) == null) return;
        long now = System.currentTimeMillis();
        if (now > winHi + 3000) return;

        long lo = Math.max(winLo, now), hi = Math.max(winHi, lo);
        double[] wnd = window(mc, now);
        double startClock = wnd[0], widthMin = wnd[1];
        double nowClock = toClockMinutes(mc.level.getDayTime());
        List<Integer> span = spanBuckets(startClock, widthMin);

        boolean exact = (winHi - winLo) <= EXACT_MS;
        long secLeft = Math.max(0, ((lo + hi) / 2 - now) / 1000);
        double progress = exact ? 1.0
                : Math.max(0.03, Math.min(1, Math.log(60000.0 / Math.max(1, winHi - winLo)) / Math.log(60000.0 / EXACT_MS)));

        String hint;
        int hintColor;
        if (exact) {
            hint = "LOCKED";
            hintColor = GREEN;
        } else {
            long c = suggestAt(now);
            if (c < 0) {
                hint = "TOO LATE";
                hintColor = LABEL;
            } else if (c - now <= 2000) {
                hint = "CHECK NOW";
                hintColor = ((now / 350) % 2 == 0) ? GREEN : 0xFFB7FFD0;
            } else {
                long s = (c - now + 999) / 1000;
                hint = "CHECK IN " + (s / 60) + ":" + String.format("%02d", s % 60);
                hintColor = CYAN;
            }
        }

        String title = "LEGENDARY SPAWN";
        String acc = exact ? "EXACT" : "+/-" + Math.max(1, (hi - lo) / 2000) + "S";
        String time = timeText(startClock, widthMin).toUpperCase();
        String inLbl = "IN ";
        String inVal = (secLeft / 60) + "M " + String.format("%02d", secLeft % 60) + "S";
        String foot = "[" + keyName() + "] SPAWN POOL" + (pool.isEmpty() ? "" : " (" + pool.size() + ")");
        String chk = "CHECKS " + checks;

        GuiGraphics g = event.getGuiGraphics();
        Font font = mc.font;

        int periodW = 0;
        for (int i = 0; i < span.size(); i++) periodW += font.width(NAMES[span.get(i)].toUpperCase()) + (i > 0 ? font.width(" / ") : 0);

        int w = Math.max(PANEL_MIN_WIDTH, Math.max(Math.max(
                font.width(title) + 10 + font.width(acc),
                font.width(time) + 6 + periodW),
                Math.max(font.width(inLbl + inVal) + 10 + font.width(hint),
                        font.width(foot) + 10 + font.width(chk))) + 16);
        int h = 71;

        // Directly above the sidebar (its top edge is at guiHeight/2 - 6 * lines - 10).
        int sidebarTop = g.guiHeight() / 2 - 6 * SIDEBAR_LINES - 10;
        int px = g.guiWidth() - (int) (w * PANEL_SCALE) - 1;
        int py = Math.max(2, sidebarTop - PANEL_GAP - (int) (h * PANEL_SCALE));

        g.pose().pushPose();
        g.pose().translate((float) px, (float) py, 0f);
        g.pose().scale(PANEL_SCALE, PANEL_SCALE, 1f);

        g.fill(0, 0, w, h, 0x99000000);
        g.fill(0, 0, w, 1, CYAN); // top accent line

        // title row
        drawGradient(g, font, title, 8, 5, PINK, BLUE);
        g.drawString(font, acc, w - 6 - font.width(acc), 5, exact ? GREEN : VALUE, true);
        g.fill(3, 4, 4, 14, 0xFF000000 | PINK);
        g.fill(3, 16, w - 3, 17, CYAN);

        // time + time of day (each colored)
        g.fill(3, 19, 4, 40, 0xFF000000 | PINK);
        g.drawString(font, time, 8, 20, VALUE, true);
        int cx = 8 + font.width(time) + 6;
        for (int i = 0; i < span.size(); i++) {
            if (i > 0) {
                g.drawString(font, " / ", cx, 20, LABEL, true);
                cx += font.width(" / ");
            }
            String nm = NAMES[span.get(i)].toUpperCase();
            g.drawString(font, nm, cx, 20, 0xFF000000 | COLORS[span.get(i)], true);
            cx += font.width(nm);
        }

        // countdown + best-next-check hint
        g.drawString(font, inLbl, 8, 31, LABEL, true);
        g.drawString(font, inVal, 8 + font.width(inLbl), 31, VALUE, true);
        g.drawString(font, hint, w - 6 - font.width(hint), 31, hintColor, true);

        // 24h strip with colored times of day, "now" marker and the spawn window
        int sx = 8, sw = w - 16, sy = 43, sh = 6;
        for (int i = 0; i < NAMES.length; i++) {
            double a = STARTS[i], bnd = i + 1 < NAMES.length ? STARTS[i + 1] : 24;
            g.fill(sx + (int) (sw * a / 24), sy, sx + (int) (sw * bnd / 24), sy + sh, 0xCC000000 | COLORS[i]);
        }
        if (STARTS[0] > 0) g.fill(sx, sy, sx + (int) (sw * STARTS[0] / 24), sy + sh, 0xCC000000 | COLORS[NAMES.length - 1]);
        double s0 = startClock, s1 = startClock + widthMin;
        if (s1 <= 1440) {
            fillSpan(g, sx, sw, sy, sh, s0, s1);
        } else {
            fillSpan(g, sx, sw, sy, sh, s0, 1440);
            fillSpan(g, sx, sw, sy, sh, 0, s1 - 1440);
        }
        int nx = sx + (int) (sw * nowClock / 1440.0);
        g.fill(nx, sy - 2, nx + 1, sy + sh + 2, 0xFFFFFFFF);

        // precision bar
        g.fill(8, 53, w - 8, 56, 0x55FFFFFF);
        g.fill(8, 53, 8 + (int) ((w - 16) * progress), 56, exact ? GREEN : CYAN);

        // footer
        g.drawString(font, foot, 8, 60, LABEL, true);
        g.drawString(font, chk, w - 6 - font.width(chk), 60, LABEL, true);

        g.pose().popPose();
    }

    private static void fillSpan(GuiGraphics g, int sx, int sw, int sy, int sh, double m0, double m1) {
        int x0 = sx + (int) (sw * m0 / 1440.0);
        int x1 = sx + (int) (sw * m1 / 1440.0);
        if (x1 - x0 < 2) x1 = x0 + 2;
        g.fill(x0, sy - 1, x1, sy + sh + 1, GOLD);
    }

    private static void drawGradient(GuiGraphics g, Font font, String s, int x, int y, int c1, int c2) {
        int cx = x;
        int n = Math.max(1, s.length() - 1);
        for (int i = 0; i < s.length(); i++) {
            float t = i / (float) n;
            int r = (int) (((c1 >> 16) & 255) * (1 - t) + ((c2 >> 16) & 255) * t);
            int gr = (int) (((c1 >> 8) & 255) * (1 - t) + ((c2 >> 8) & 255) * t);
            int b = (int) ((c1 & 255) * (1 - t) + (c2 & 255) * t);
            String ch = String.valueOf(s.charAt(i));
            g.drawString(font, ch, cx, y, 0xFF000000 | (r << 16) | (gr << 8) | b, true);
            cx += font.width(ch);
        }
    }

    // ---- data for the pool screen ---------------------------------------------------------------

    Map<String, Double> pool() { return pool; }
    double spawnChance() { return spawnChance; }
    String poolStamp() { return poolStamp; }
    Map<String, TreeSet<Integer>> seenIn() { return seenIn; }

    // Times of day the spawn attempt may fall in (empty if no countdown is known).
    List<Integer> predictedBuckets() {
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        if (winHi == 0 || mc.level == null || now > winHi + 3000) return new ArrayList<>();
        double[] w = window(mc, now);
        return spanBuckets(w[0], w[1]);
    }

    // ---- /wiki capture --------------------------------------------------------------------------

    private static final Pattern WIKI_HEAD = Pattern.compile("(.+?)['\u2019]s Spawning Wiki", Pattern.CASE_INSENSITIVE);

    // Reads the "<Name>'s Spawning Wiki" output: every "Time:" line adds to that legendary's spawn times.
    private void captureWiki(String text, long nowMs) {
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.contains("\u00bb")) continue;
            Matcher h = WIKI_HEAD.matcher(line);
            if (h.find()) {
                if (wikiName != null) finalizeWiki();
                wikiName = h.group(1).trim();
                wikiRarity = "";
                wikiTimes.clear();
                inBlock = false;
                blockHasTime = false;
            } else if (wikiName == null) {
                continue;
            } else if (line.regionMatches(true, 0, "Spec:", 0, 5)) {
                if (inBlock && !blockHasTime) wikiTimes.add("ANY"); // no Time line = any time
                inBlock = true;
                blockHasTime = false;
            } else if (line.regionMatches(true, 0, "Time:", 0, 5)) {
                for (String tok : line.substring(5).split("[,/ ]+")) {
                    String t = tok.trim().toUpperCase();
                    if (t.equals("ALL") || t.equals("ANY")) wikiTimes.add("ANY");
                    else for (String p : PX_TIMES) if (p.equals(t)) wikiTimes.add(t);
                }
                blockHasTime = true;
            } else if (line.regionMatches(true, 0, "Rarity:", 0, 7)) {
                if (wikiRarity.isEmpty()) wikiRarity = line.substring(7).trim();
            }
        }
        if (wikiName != null) wikiUntil = nowMs + 2500;
    }

    private void finalizeWiki() {
        if (wikiName == null) return;
        if (inBlock && !blockHasTime) wikiTimes.add("ANY");
        if (!wikiTimes.isEmpty()) {
            SpecInfo info = new SpecInfo(wikiName);
            info.rarity = wikiRarity;
            info.times.addAll(wikiTimes);
            wiki.put(key(wikiName), info);
            wikiDirty = true;
            say("learned " + wikiName + " spawns at: " + timesText(info.times));
        }
        wikiName = null;
        inBlock = false;
        blockHasTime = false;
    }

    // ---- spawn pool at the predicted time ---------------------------------------------------------

    static final class SpawnRow {
        final String name;
        final Set<String> times;
        final boolean confirmed;  // times come from the server's /wiki output (not the default table)
        final double coverage;    // share of the predicted window in which this legendary's time is active
        final boolean inPool;     // it is in the latest /checkspawns list (valid here, apart from time)
        final double pct;         // its chance in that list
        SpawnRow(String name, Set<String> times, boolean confirmed, double coverage, boolean inPool, double pct) {
            this.name = name;
            this.times = times;
            this.confirmed = confirmed;
            this.coverage = coverage;
            this.inPool = inPool;
            this.pct = pct;
        }
    }

    // {start tick of the window in the day (0-23999), width in ticks}, or null when no countdown is known.
    private int[] windowTicks() {
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        if (winHi == 0 || mc.level == null || now > winHi + 3000) return null;
        long lo = Math.max(winLo, now), hi = Math.max(winHi, lo);
        long start = mc.level.getDayTime() + Math.round((lo - now) * 0.02);
        return new int[]{(int) (start % 24000L), (int) Math.round((hi - lo) * 0.02)};
    }

    private static List<Integer> sampleTicks(int[] w) {
        List<Integer> out = new ArrayList<>();
        for (int o = 0; o < w[1]; o += 25) out.add((w[0] + o) % 24000);
        out.add((w[0] + w[1]) % 24000);
        return out;
    }

    boolean hasWindow() { return windowTicks() != null; }

    int knownCount() { return seenIn.size() + wiki.size(); }

    // Pixelmon times (DAY, MIDDAY, ...) that are active somewhere in the predicted window.
    List<String> activeLabels() {
        List<String> out = new ArrayList<>();
        int[] w = windowTicks();
        if (w == null) return out;
        List<Integer> samples = sampleTicks(w);
        for (String l : PX_TIMES) {
            for (int t : samples) {
                if (pxActive(l, t)) { out.add(l); break; }
            }
        }
        return out;
    }

    String windowText() {
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        if (winHi == 0 || mc.level == null || now > winHi + 3000) return "";
        double[] w = window(mc, now);
        return timeText(w[0], w[1]);
    }

    private Set<String> timesOf(String key) {
        SpecInfo i = wiki.get(key);
        if (i != null) return i.times;
        return DEFAULTS.get(key);
    }

    private static double coverage(Set<String> times, List<Integer> samples) {
        if (times.contains("ANY")) return 1.0;
        int hit = 0;
        for (int t : samples) {
            for (String l : times) {
                if (pxActive(l, t)) { hit++; break; }
            }
        }
        return hit / (double) samples.size();
    }

    // filter: 0 = all legendaries in the default table, 1 = ones seen on this server, 2 = only your latest pool
    List<SpawnRow> spawnRows(int filter) {
        List<SpawnRow> out = new ArrayList<>();
        int[] w = windowTicks();
        if (w == null) return out;
        List<Integer> samples = sampleTicks(w);

        Map<String, Double> poolPct = new HashMap<>();
        Map<String, String> universe = new LinkedHashMap<>();
        if (filter == 0) universe.putAll(DEFAULT_NAMES);
        if (filter <= 1) {
            for (SpecInfo i : wiki.values()) universe.put(key(i.name), i.name);
            for (String n : seenIn.keySet()) universe.put(key(n), n);
        }
        for (Map.Entry<String, Double> e : pool.entrySet()) {
            poolPct.put(key(e.getKey()), e.getValue());
            universe.put(key(e.getKey()), e.getKey());
        }

        for (Map.Entry<String, String> e : universe.entrySet()) {
            Set<String> times = timesOf(e.getKey());
            if (times == null) continue; // unknown times: listed separately
            double cov = coverage(times, samples);
            if (cov <= 0) continue;
            Double pct = poolPct.get(e.getKey());
            out.add(new SpawnRow(e.getValue(), times, wiki.containsKey(e.getKey()), cov, pct != null, pct == null ? 0 : pct));
        }
        out.sort((a, b) -> {
            if (a.inPool != b.inPool) return a.inPool ? -1 : 1;
            if (a.inPool) return Double.compare(b.pct, a.pct);
            return a.name.compareToIgnoreCase(b.name);
        });
        return out;
    }

    // Legendaries from your checks/history that have no known spawn times yet.
    List<String> unknownNames() {
        Map<String, String> u = new LinkedHashMap<>();
        for (String n : pool.keySet()) u.put(key(n), n);
        for (String n : seenIn.keySet()) u.put(key(n), n);
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : u.entrySet()) {
            if (timesOf(e.getKey()) == null) out.add(e.getValue());
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    static String timesText(Set<String> times) {
        List<String> parts = new ArrayList<>();
        for (String p : PX_TIMES) {
            if (times.contains(p)) parts.add(p);
        }
        if (times.contains("ANY")) parts.add(0, "ANY");
        return String.join("/", parts);
    }

    static String key(String n) {
        String k = n.trim().toLowerCase().replace('\u2019', '\'');
        return k.equals("null") ? "type: null" : k;
    }

    // The /checkspawns list prints "Type: Null" in a way that parses as just "Null".
    static String fixName(String n) { return n.equalsIgnoreCase("null") ? "Type: Null" : n; }

    // ---- saved wiki times ---------------------------------------------------------------------

    private Path wikiFile() { return FMLPaths.CONFIGDIR.get().resolve("spawntime-wiki.txt"); }

    private void loadWiki() {
        try {
            Path p = wikiFile();
            if (!Files.exists(p)) return;
            for (String line : Files.readAllLines(p)) {
                String[] a = line.split("\\|", -1);
                if (a.length < 3 || a[0].isEmpty()) continue;
                SpecInfo i = new SpecInfo(a[0]);
                i.rarity = a[1];
                for (String t : a[2].split(",")) {
                    if (!t.isBlank()) i.times.add(t.trim());
                }
                if (!i.times.isEmpty()) wiki.put(key(a[0]), i);
            }
        } catch (IOException ignored) { }
    }

    private void saveWiki() {
        try {
            List<String> out = new ArrayList<>();
            for (SpecInfo i : wiki.values()) {
                out.add(i.name + "|" + i.rarity + "|" + String.join(",", i.times));
            }
            Files.write(wikiFile(), out);
        } catch (IOException ignored) { }
    }

    // ---- saved history ------------------------------------------------------------------------

    private Path historyFile() { return FMLPaths.CONFIGDIR.get().resolve("spawntime-history.txt"); }

    private void loadHistory() {
        try {
            Path p = historyFile();
            if (!Files.exists(p)) return;
            for (String line : Files.readAllLines(p)) {
                int i = line.lastIndexOf('=');
                if (i < 1) continue;
                TreeSet<Integer> set = new TreeSet<>();
                for (String t : line.substring(i + 1).split(",")) {
                    try {
                        int k = Integer.parseInt(t.trim());
                        if (k >= 0 && k < NAMES.length) set.add(k);
                    } catch (NumberFormatException ignored) { }
                }
                if (!set.isEmpty()) seenIn.put(line.substring(0, i), set);
            }
        } catch (IOException ignored) { }
    }

    private void saveHistory() {
        try {
            List<String> out = new ArrayList<>();
            for (Map.Entry<String, TreeSet<Integer>> e : seenIn.entrySet()) {
                StringBuilder sb = new StringBuilder(e.getKey()).append('=');
                boolean first = true;
                for (int k : e.getValue()) {
                    if (!first) sb.append(',');
                    sb.append(k);
                    first = false;
                }
                out.add(sb.toString());
            }
            Files.write(historyFile(), out);
        } catch (IOException ignored) { }
    }

    // ---- helpers ------------------------------------------------------------------------------

    // {start of the window on the server clock (minutes since midnight), width in game minutes}
    private double[] window(Minecraft mc, long now) {
        long lo = Math.max(winLo, now), hi = Math.max(winHi, lo);
        long dayTime = mc.level.getDayTime();
        double startClock = toClockMinutes(dayTime + Math.round((lo - now) * 0.02)); // 20 ticks per second
        double widthMin = (hi - lo) / 1000.0 * 1.2;
        return new double[]{startClock, widthMin};
    }

    static List<Integer> spanBuckets(double startClock, double widthMin) {
        List<Integer> out = new ArrayList<>();
        for (double x = 0; x < widthMin; x += 5) addUnique(out, bucketIndex(((startClock + x) % 1440) / 60.0));
        addUnique(out, bucketIndex(((startClock + widthMin) % 1440) / 60.0));
        return out;
    }

    private static void addUnique(List<Integer> l, int v) { if (!l.contains(v)) l.add(v); }

    private static String timeText(double startClock, double widthMin) {
        return widthMin <= 5
                ? format12h((startClock + widthMin / 2) % 1440)
                : format12h(startClock) + " - " + format12h((startClock + widthMin) % 1440);
    }

    // {time text, time-of-day text}
    private static String[] describeParts(double startClock, double widthMin) {
        List<String> names = new ArrayList<>();
        for (int i : spanBuckets(startClock, widthMin)) names.add(NAMES[i]);
        return new String[]{timeText(startClock, widthMin), String.join(" / ", names)};
    }

    // Minutes since midnight on the server clock for a given world day time in ticks (tick 0 = 6:00 AM).
    private static double toClockMinutes(long ticks) {
        double minutes = (ticks % 24000L) * 0.06 + 360 + OFFSET_MINUTES;
        return ((minutes % 1440) + 1440) % 1440;
    }

    // Returns {total seconds, precision in seconds} or null. Precision is the smallest unit shown.
    private static long[] parseDuration(String s) {
        Matcher m = PART.matcher(s);
        long total = 0, precision = 3600;
        boolean found = false;
        while (m.find()) {
            found = true;
            long n = Long.parseLong(m.group(1));
            char u = Character.toLowerCase(m.group(2).charAt(0));
            if (u == 'h') { total += n * 3600; }
            else if (u == 'm') { total += n * 60; precision = Math.min(precision, 60); }
            else { total += n; precision = 1; }
        }
        return found ? new long[]{total, precision} : null;
    }

    static int bucketIndex(double hour) {
        int result = NAMES.length - 1;
        for (int i = 0; i < STARTS.length; i++) {
            if (hour >= STARTS[i]) result = i;
        }
        return result;
    }

    static String format12h(double minutes) {
        int h = (int) (minutes / 60);
        int m = (int) (minutes % 60);
        String ap = h >= 12 ? "PM" : "AM";
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return h12 + ":" + String.format("%02d", m) + " " + ap;
    }
}
