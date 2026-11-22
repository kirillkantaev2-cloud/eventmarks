package me.user.eventmark;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EventMark implements ClientModInitializer {

    // ---------- config ----------
    public static class Config {
        public boolean enabled = true;
        public boolean sound = true;
        public boolean showInChat = true;
        public String keywords = "ивент,event,метеорит,аирдроп,airdrop";
        public int lifetimeSec = 120;
        public int colorIndex = 0;
        public int hudX = 6;
        public int hudY = 40;
    }

    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("eventmark.json");
    public static Config cfg = new Config();
    static final int[] COLORS = {0xFFFF5555, 0xFFFFAA00, 0xFFFFFF55, 0xFF55FF55, 0xFF55FFFF, 0xFFFF55FF, 0xFFFFFFFF};
    static final String[] COLOR_NAMES = {"Красный", "Оранжевый", "Жёлтый", "Зелёный", "Голубой", "Розовый", "Белый"};

    static void load() {
        try {
            if (Files.exists(FILE)) {
                Config c = GSON.fromJson(Files.readString(FILE), Config.class);
                if (c != null) cfg = c;
            }
        } catch (Exception ignored) {}
    }

    static void save() {
        try { Files.writeString(FILE, GSON.toJson(cfg)); } catch (Exception ignored) {}
    }

    // ---------- state ----------
    static double mx, my, mz;
    static boolean hasY;
    static long expireAt = 0;
    static String label = "";
    static boolean openMenu = false;
    static KeyBinding menuKey;

    static final Pattern XYZ = Pattern.compile("(-?\\d{1,6})[\\s,;]+(-?\\d{1,3})[\\s,;]+(-?\\d{1,6})");
    static final Pattern XZ = Pattern.compile("(-?\\d{1,6})[\\s,;]+(-?\\d{1,6})");

    @Override
    public void onInitializeClient() {
        load();
        menuKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.eventmark.menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "category.eventmark"));

        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> { if (!overlay) handle(msg.getString()); });
        ClientReceiveMessageEvents.CHAT.register((msg, sig, sender, params, ts) -> handle(msg.getString()));

        ClientCommandRegistrationCallback.EVENT.register((d, r) -> {
            d.register(ClientCommandManager.literal("evmark").executes(c -> { openMenu = true; return 1; }));
            d.register(ClientCommandManager.literal("evmark").then(ClientCommandManager.literal("clear")
                    .executes(c -> { expireAt = 0; return 1; })));
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (menuKey.wasPressed()) openMenu = true;
            if (openMenu && client.currentScreen == null) {
                openMenu = false;
                client.setScreen(new MenuScreen());
            }
        });

        HudRenderCallback.EVENT.register(EventMark::renderHud);
    }

    static void handle(String raw) {
        if (!cfg.enabled) return;
        String text = raw.replaceAll("§.", "");
        String low = text.toLowerCase();
        boolean match = false;
        for (String k : cfg.keywords.split(",")) {
            k = k.trim().toLowerCase();
            if (!k.isEmpty() && low.contains(k)) { match = true; break; }
        }
        if (!match) return;

        Matcher m = XYZ.matcher(text);
        if (m.find()) {
            mx = Integer.parseInt(m.group(1)); my = Integer.parseInt(m.group(2)); mz = Integer.parseInt(m.group(3));
            hasY = true;
        } else {
            m = XZ.matcher(text);
            if (!m.find()) return;
            mx = Integer.parseInt(m.group(1)); mz = Integer.parseInt(m.group(2));
            hasY = false;
        }
        label = text.length() > 40 ? text.substring(0, 40) + "…" : text;
        expireAt = System.currentTimeMillis() + cfg.lifetimeSec * 1000L;

        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            if (mc.player == null) return;
            if (cfg.sound) mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
            if (cfg.showInChat)
                mc.player.sendMessage(Text.literal("[EventMark] Метка: " + (int) mx + (hasY ? " " + (int) my : "") + " " + (int) mz), false);
        });
    }

    static void renderHud(DrawContext ctx, net.minecraft.client.render.RenderTickCounter tick) {
        if (!cfg.enabled || System.currentTimeMillis() > expireAt) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerEntity p = mc.player;
        if (p == null || mc.options.hudHidden) return;

        double dx = mx + 0.5 - p.getX();
        double dz = mz + 0.5 - p.getZ();
        double dist = hasY
                ? Math.sqrt(dx * dx + dz * dz + Math.pow(my - p.getY(), 2))
                : Math.sqrt(dx * dx + dz * dz);
        float targetYaw = (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);
        float diff = MathHelper.wrapDegrees(targetYaw - p.getYaw());
        String[] arrows = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
        int idx = Math.floorMod(Math.round(diff / 45f), 8);
        int left = (int) ((expireAt - System.currentTimeMillis()) / 1000);

        int color = COLORS[cfg.colorIndex % COLORS.length];
        String l1 = arrows[idx] + " ИВЕНТ  " + (int) dist + " м";
        String l2 = (int) mx + (hasY ? " " + (int) my : "") + " " + (int) mz + "  (" + left + " с)";
        ctx.drawTextWithShadow(mc.textRenderer, l1, cfg.hudX, cfg.hudY, color);
        ctx.drawTextWithShadow(mc.textRenderer, l2, cfg.hudX, cfg.hudY + 11, 0xFFCCCCCC);
    }

    // ---------- menu ----------
    public static class MenuScreen extends Screen {
        private TextFieldWidget keywordsField;

        protected MenuScreen() { super(Text.literal("EventMark")); }

        @Override
        protected void init() {
            int w = 220, x = width / 2 - w / 2, y = height / 2 - 90, step = 24;

            addDrawableChild(ButtonWidget.builder(onOff("Мод", cfg.enabled), b -> {
                cfg.enabled = !cfg.enabled; b.setMessage(onOff("Мод", cfg.enabled));
            }).dimensions(x, y, w, 20).build());

            addDrawableChild(ButtonWidget.builder(onOff("Звук", cfg.sound), b -> {
                cfg.sound = !cfg.sound; b.setMessage(onOff("Звук", cfg.sound));
            }).dimensions(x, y + step, w, 20).build());

            addDrawableChild(ButtonWidget.builder(onOff("Сообщение в чат", cfg.showInChat), b -> {
                cfg.showInChat = !cfg.showInChat; b.setMessage(onOff("Сообщение в чат", cfg.showInChat));
            }).dimensions(x, y + step * 2, w, 20).build());

            addDrawableChild(ButtonWidget.builder(Text.literal("Цвет: " + COLOR_NAMES[cfg.colorIndex]), b -> {
                cfg.colorIndex = (cfg.colorIndex + 1) % COLORS.length;
                b.setMessage(Text.literal("Цвет: " + COLOR_NAMES[cfg.colorIndex]));
            }).dimensions(x, y + step * 3, w, 20).build());

            ButtonWidget time = ButtonWidget.builder(Text.literal("Время метки: " + cfg.lifetimeSec + " с"), b -> {
                cfg.lifetimeSec = cfg.lifetimeSec >= 600 ? 30 : cfg.lifetimeSec + 30;
                b.setMessage(Text.literal("Время метки: " + cfg.lifetimeSec + " с"));
            }).dimensions(x, y + step * 4, w, 20).build();
            addDrawableChild(time);

            keywordsField = new TextFieldWidget(textRenderer, x, y + step * 5 + 12, w, 20, Text.literal("Ключевые слова"));
            keywordsField.setMaxLength(200);
            keywordsField.setText(cfg.keywords);
            keywordsField.setChangedListener(s -> cfg.keywords = s);
            addDrawableChild(keywordsField);

            addDrawableChild(ButtonWidget.builder(Text.literal("Готово"), b -> close())
                    .dimensions(x, y + step * 6 + 16, w, 20).build());
        }

        private static Text onOff(String name, boolean v) {
            return Text.literal(name + ": " + (v ? "ВКЛ" : "ВЫКЛ"));
        }

        @Override
        public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
            super.render(ctx, mouseX, mouseY, delta);
            ctx.drawCenteredTextWithShadow(textRenderer, "EventMark", width / 2, height / 2 - 112, 0xFFFFFFFF);
            ctx.drawTextWithShadow(textRenderer, "Ключевые слова через запятую:", width / 2 - 110, height / 2 - 90 + 24 * 5, 0xFFAAAAAA);
        }

        @Override
        public void close() { save(); super.close(); }
    }
}
