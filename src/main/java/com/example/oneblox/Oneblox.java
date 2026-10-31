package com.example.oneblox;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.serialization.JsonOps;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * OneBlox: OneBlock islands, money, shop, auction house, crates, spawners, ranks, admin panel.
 * Server side only (players need no mod). Everything is configurable in config/oneblox/config.json.
 * Menus are normal chest GUIs, messages go to the action bar so chat stays clean.
 */
public class Oneblox implements ModInitializer {

    // =====================================================================
    //  DATA
    // =====================================================================
    static class Reward {
        String type, value; int amount, weight;      // type: money | item | spawner | key
        Reward(String type, String value, int amount, int weight) { this.type = type; this.value = value; this.amount = amount; this.weight = weight; }
    }

    static class Cfg {
        String owner = "SpanglyTap";   // the only person who can use admin features
        String ownerUuid = "";         // filled in automatically the first time the owner joins
        int spacing = 1000, radius = 50, blocksPerPhase = 150, mobChance = 8, spawnerChance = 2;
        int hubRadius = 300, hubBorder = 60, maxMembers = 5, ahTax = 5, ahMaxListings = 10;
        long fishPay = 10, dailyMoney = 2000;
        double mobPayPerHealth = 1.5;
        List<String> flyRanks = new ArrayList<>(List.of("allay", "owner"));
        List<String> ownerEffects = new ArrayList<>(List.of("haste:1", "speed:1"));
        Map<String, String> ranks = new LinkedHashMap<>();       // id -> chat prefix
        Map<String, Long> spawners = new LinkedHashMap<>();      // mob -> price
        Map<String, Long> sell = new LinkedHashMap<>();          // item id -> sell price each
        Map<String, Long> keyPrice = new LinkedHashMap<>();      // crate -> key price
        Map<String, List<Reward>> crates = new LinkedHashMap<>();

        Cfg() {
            ranks.put("member", ""); ranks.put("chicken", "\u00a7f[Chicken] "); ranks.put("parrot", "\u00a7c[Parrot] "); ranks.put("allay", "\u00a7b[Allay] ");
            spawners.put("chicken", 4000L); spawners.put("sheep", 4000L); spawners.put("pig", 5000L); spawners.put("cow", 6000L);
            spawners.put("zombie", 10000L); spawners.put("skeleton", 12000L); spawners.put("spider", 12000L); spawners.put("creeper", 20000L);
            spawners.put("enderman", 50000L); spawners.put("blaze", 80000L); spawners.put("iron_golem", 100000L);
            for (String l : new String[]{"oak", "birch", "spruce", "jungle", "acacia", "dark_oak"}) sell.put(l + "_log", 3L);
            String[] s = {"cobblestone", "1", "stone", "1", "deepslate", "1", "netherrack", "1", "end_stone", "2", "sand", "1", "gravel", "1",
                "sandstone", "1", "coal", "4", "raw_iron", "6", "iron_ingot", "8", "raw_copper", "3", "copper_ingot", "4", "gold_ingot", "14",
                "redstone", "3", "lapis_lazuli", "4", "quartz", "4", "diamond", "40", "emerald", "50", "ancient_debris", "80", "obsidian", "10",
                "melon", "4", "pumpkin", "4", "beef", "3", "cooked_beef", "6", "rotten_flesh", "1", "bone", "2", "string", "2", "gunpowder", "4",
                "spider_eye", "2", "ender_pearl", "20", "blaze_rod", "12", "cod", "3", "salmon", "4", "pufferfish", "6", "tropical_fish", "6"};
            for (int i = 0; i < s.length; i += 2) sell.put(s[i], Long.parseLong(s[i + 1]));
            keyPrice.put("Panda", 5000L); keyPrice.put("Infinity", 25000L); keyPrice.put("Mythic", 100000L);
            crates.put("Panda", new ArrayList<>(List.of(
                new Reward("money", "", 500, 30), new Reward("money", "", 1500, 20), new Reward("item", "iron_ingot", 16, 20),
                new Reward("item", "cooked_beef", 32, 15), new Reward("item", "experience_bottle", 16, 10), new Reward("item", "gold_ingot", 8, 10),
                new Reward("item", "diamond", 2, 5), new Reward("spawner", "chicken", 1, 3), new Reward("spawner", "pig", 1, 2),
                new Reward("key", "Infinity", 1, 1))));
            crates.put("Infinity", new ArrayList<>(List.of(
                new Reward("money", "", 5000, 25), new Reward("money", "", 15000, 10), new Reward("item", "diamond", 8, 20),
                new Reward("item", "emerald", 16, 15), new Reward("item", "gold_block", 4, 10), new Reward("item", "enchanted_golden_apple", 1, 5),
                new Reward("spawner", "zombie", 1, 8), new Reward("spawner", "skeleton", 1, 5), new Reward("spawner", "spider", 1, 5),
                new Reward("key", "Mythic", 1, 2))));
            crates.put("Mythic", new ArrayList<>(List.of(
                new Reward("money", "", 50000, 25), new Reward("money", "", 150000, 8), new Reward("item", "netherite_ingot", 2, 12),
                new Reward("item", "diamond_block", 4, 15), new Reward("item", "totem_of_undying", 1, 10), new Reward("item", "elytra", 1, 3),
                new Reward("spawner", "blaze", 1, 8), new Reward("spawner", "enderman", 1, 8), new Reward("spawner", "iron_golem", 1, 6),
                new Reward("item", "nether_star", 1, 5))));
        }
    }

    static class Listing {
        int id; String seller, sellerName, json, name; long price; int count; long time;
    }

    static class Isl {
        int idx; String owner; List<String> members = new ArrayList<>(); int broken; boolean ready;
        boolean memberBuild = true, memberInvite = false;
    }

    static class Data {
        int ver = 0;
        String mode;
        int nextIsland = 0, nextAuction = 1;
        boolean hubSet = false; double hubX = 0.5, hubY = 64, hubZ = 0.5;
        Set<String> kit = new HashSet<>();
        Map<String, Long> money = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        Map<String, String> rank = new HashMap<>();
        Map<String, Long> lastDaily = new HashMap<>();
        Map<String, Map<String, Integer>> keys = new HashMap<>();
        Map<String, Integer> islandOf = new HashMap<>();
        Map<Integer, Isl> islands = new HashMap<>();
        List<Listing> auctions = new ArrayList<>();
    }

    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static final Random RNG = new Random();
    static MinecraftServer server;
    static Path dir;
    static Cfg cfg = new Cfg();
    static Data data = new Data();
    static int ticks = 0;
    static final Map<String, Integer> INVITES = new HashMap<>();
    static final Map<String, String> TPA = new HashMap<>();      // target uuid -> requester uuid
    static final Map<String, Integer> LAST_FISH = new HashMap<>();
    static final Set<String> FLY = new HashSet<>();

    // Mode 1: own empty dimension "oneblox:void" (islands at y=64). Mode 2: overworld is already a void (Superflat).
    // Mode 3: fallback, floating islands at y=290 in the normal overworld.
    static boolean voidDim = false, voidWorld = false;
    static int Y() { return (voidDim || voidWorld) ? 64 : 290; }
    static String WORLD() { return voidDim ? "oneblox:void" : "minecraft:overworld"; }
    static String modeName() { return voidDim ? "void" : voidWorld ? "flat" : "sky"; }

    // =====================================================================
    //  OneBlock phases + money for blocks
    // =====================================================================
    static final String[] PHASE_NAME = {"Plains", "Underground", "Snowy Peaks", "Jungle", "Desert", "Nether", "The End", "Deep Dark"};
    static final String[][] PHASE_BLOCKS = {
        {"grass_block", "grass_block", "dirt", "oak_log", "oak_log", "oak_leaves", "birch_log", "sand", "gravel", "cobblestone"},
        {"stone", "stone", "cobblestone", "coal_ore", "iron_ore", "copper_ore", "andesite", "diorite", "granite", "gravel", "dirt"},
        {"snow_block", "ice", "packed_ice", "spruce_log", "spruce_leaves", "stone", "coal_ore", "iron_ore"},
        {"jungle_log", "jungle_log", "jungle_leaves", "mossy_cobblestone", "moss_block", "melon", "bamboo_block", "clay", "gold_ore"},
        {"sand", "sandstone", "red_sand", "red_sandstone", "terracotta", "cactus", "gold_ore", "lapis_ore", "redstone_ore"},
        {"netherrack", "netherrack", "nether_quartz_ore", "nether_gold_ore", "soul_sand", "nether_bricks", "magma_block", "glowstone", "basalt", "blackstone"},
        {"end_stone", "end_stone", "obsidian", "purpur_block", "end_stone_bricks", "crying_obsidian", "diamond_ore"},
        {"deepslate", "deepslate", "deepslate_iron_ore", "deepslate_diamond_ore", "deepslate_emerald_ore", "deepslate_gold_ore", "deepslate_redstone_ore", "deepslate_lapis_ore", "sculk", "ancient_debris", "tuff", "calcite"}
    };
    static final String[][] PHASE_MOBS = {
        {"cow", "pig", "sheep", "chicken"}, {"zombie", "skeleton", "spider", "creeper"}, {"stray", "wolf", "rabbit", "zombie"},
        {"ocelot", "parrot", "spider", "creeper"}, {"husk", "skeleton", "spider", "creeper"},
        {"zombified_piglin", "magma_cube", "blaze", "wither_skeleton"}, {"enderman", "endermite"}, {"zombie", "skeleton", "witch", "creeper"}
    };

    static final Map<String, Integer> PAY = new HashMap<>();
    static final Set<String> STONEY = Set.of("stone", "cobblestone", "deepslate", "netherrack", "end_stone", "andesite",
            "diorite", "granite", "tuff", "basalt", "blackstone", "sandstone", "red_sandstone");
    static {
        PAY.put("coal_ore", 4); PAY.put("iron_ore", 8); PAY.put("copper_ore", 3); PAY.put("gold_ore", 14);
        PAY.put("redstone_ore", 6); PAY.put("lapis_ore", 8); PAY.put("diamond_ore", 40); PAY.put("emerald_ore", 50);
        PAY.put("nether_quartz_ore", 6); PAY.put("nether_gold_ore", 8); PAY.put("ancient_debris", 80);
        PAY.put("melon", 4); PAY.put("pumpkin", 4);
    }

    static int blockPay(String id, boolean isOneBlock) {
        if (id.endsWith("_log") || id.endsWith("_stem")) return 3;
        String b = id.startsWith("deepslate_") ? id.substring(10) : id;
        Integer v = PAY.get(b);
        if (v != null) return v;
        return (isOneBlock && STONEY.contains(id)) ? 1 : 0;
    }

    // =====================================================================
    //  SMALL HELPERS
    // =====================================================================
    /** Short messages go to the action bar so chat stays clean. */
    static void msg(ServerPlayer p, String s) { p.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(s))); }
    /** Real chat message, only for things that must stay readable (invites, teleport requests). */
    static void chat(ServerPlayer p, String s) { p.sendSystemMessage(Component.literal(s)); }
    static String fmt(long v) { return "$" + String.format(Locale.US, "%,d", v); }
    static long bal(ServerPlayer p) { return data.money.getOrDefault(p.getStringUUID(), 0L); }
    static void add(ServerPlayer p, long a) { data.money.merge(p.getStringUUID(), a, Long::sum); }
    static String pretty(String id) {
        StringBuilder sb = new StringBuilder();
        for (String w : id.split("_")) { if (w.isEmpty()) continue; if (sb.length() > 0) sb.append(' '); sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)); }
        return sb.toString();
    }

    static void pay(ServerPlayer p, long a) {
        if (a <= 0) return;
        add(p, a);
        msg(p, "\u00a7a+" + fmt(a));
    }

    /** Only the configured owner (default SpanglyTap) is an admin. */
    static boolean isAdmin(ServerPlayer p) {
        return p.getName().getString().equals(cfg.owner) && (cfg.ownerUuid.isEmpty() || cfg.ownerUuid.equals(p.getStringUUID()));
    }
    static String rankId(ServerPlayer p) { return isAdmin(p) ? "owner" : data.rank.getOrDefault(p.getStringUUID(), "member"); }
    static String prefix(ServerPlayer p) {
        String r = rankId(p);
        if (r.equals("owner")) return "\u00a76\u2726 \u00a7e\u00a7lO\u00a76\u00a7lw\u00a7e\u00a7ln\u00a76\u00a7le\u00a7e\u00a7lr \u00a76\u2726 \u00a7r";
        return cfg.ranks.getOrDefault(r, "");
    }

    /** Runs a command as the server, silently. Used for blocks, teleports, gives, effects. */
    static void run(String cmd) {
        try {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd);
        } catch (Exception e) { e.printStackTrace(); }
    }
    static void runIn(String cmd) { run("execute in " + WORLD() + " run " + cmd); }
    static boolean inVoid(ServerPlayer p) { return String.valueOf(p.level().dimension()).contains(WORLD()); }
    static boolean inOverworld(ServerPlayer p) { return String.valueOf(p.level().dimension()).contains("minecraft:overworld"); }
    static void tp(ServerPlayer p, double x, double y, double z) {
        run("execute in " + WORLD() + " run tp " + p.getStringUUID() + " " + x + " " + y + " " + z);
    }

    static class Task { int t; Runnable r; Task(int t, Runnable r) { this.t = t; this.r = r; } }
    static final List<Task> TASKS = new ArrayList<>();
    static void later(int t, Runnable r) { TASKS.add(new Task(t, r)); }

    static <T> T load(String name, Class<T> c, T def) {
        try {
            Path p = dir.resolve(name);
            if (Files.exists(p)) { T v = GSON.fromJson(Files.readString(p), c); if (v != null) return v; }
        } catch (Exception e) { e.printStackTrace(); }
        return def;
    }
    static void save(String name, Object o) {
        try { Files.createDirectories(dir); Files.writeString(dir.resolve(name), GSON.toJson(o)); }
        catch (Exception e) { e.printStackTrace(); }
    }
    static void saveAll() { save("data.json", data); }

    static void giveStack(ServerPlayer p, ItemStack st) {
        ItemStack c = st.copy();
        p.getInventory().add(c);
        if (!c.isEmpty()) p.drop(c, false);
    }
    static void sound(ServerPlayer p, String s) { run("playsound minecraft:" + s + " master " + p.getStringUUID()); }

    // item <-> text (for the auction house)
    static String ser(ItemStack st) {
        try {
            var ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
            return ItemStack.CODEC.encodeStart(ops, st).result().map(JsonElement::toString).orElse(null);
        } catch (Throwable e) { e.printStackTrace(); return null; }
    }
    static ItemStack deser(String json) {
        try {
            var ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
            return ItemStack.CODEC.parse(ops, JsonParser.parseString(json)).result().orElse(ItemStack.EMPTY);
        } catch (Throwable e) { return ItemStack.EMPTY; }
    }

    // reflection so a renamed field/method can never break the build
    static boolean flyWarned = false;
    static void setFly(ServerPlayer p, boolean on) {
        try {
            Object ab = p.getClass().getMethod("getAbilities").invoke(p);
            ab.getClass().getField("mayfly").setBoolean(ab, on);
            if (!on) ab.getClass().getField("flying").setBoolean(ab, false);
            p.getClass().getMethod("onUpdateAbilities").invoke(p);
        } catch (Throwable e) { if (!flyWarned) { flyWarned = true; System.out.println("[Oneblox] Could not change flight: " + e); } }
    }

    // =====================================================================
    //  GUI FRAMEWORK (chest menus built on the server, players need no mod)
    // =====================================================================
    interface Act { void run(ServerPlayer p, ClickType ct); }

    static MenuType<?> menuType(int rows) {
        switch (rows) {
            case 1: return MenuType.GENERIC_9x1;
            case 2: return MenuType.GENERIC_9x2;
            case 3: return MenuType.GENERIC_9x3;
            case 4: return MenuType.GENERIC_9x4;
            case 5: return MenuType.GENERIC_9x5;
            default: return MenuType.GENERIC_9x6;
        }
    }

    static class Gui {
        final String title; final int rows; final SimpleContainer box; final Act[] acts;
        Gui(String title, int rows) { this.title = title; this.rows = rows; box = new SimpleContainer(rows * 9); acts = new Act[rows * 9]; }
        Gui set(int slot, ItemStack s, Act a) {
            if (slot >= 0 && slot < rows * 9) { box.setItem(slot, s); acts[slot] = a; }
            return this;
        }
        Gui fill() {
            for (int i = 0; i < rows * 9; i++) if (box.getItem(i).isEmpty()) box.setItem(i, icon(Items.GRAY_STAINED_GLASS_PANE, " "));
            return this;
        }
        void open(ServerPlayer p) {
            Gui me = this;
            p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new GuiMenu(id, inv, me), Component.literal(title)));
        }
    }

    /** A chest menu where every click is cancelled and turned into a button press. */
    static class GuiMenu extends ChestMenu {
        final Gui g;
        GuiMenu(int id, Inventory inv, Gui g) { super(menuType(g.rows), id, inv, g.box, g.rows); this.g = g; }

        @Override
        public void clicked(int slot, int button, ClickType ct, Player player) {
            if (slot >= 0 && slot < g.rows * 9 && g.acts[slot] != null && player instanceof ServerPlayer sp) {
                try { g.acts[slot].run(sp, ct); } catch (Exception e) { e.printStackTrace(); }
            }
            if (player instanceof ServerPlayer sp2 && sp2.containerMenu == this) this.sendAllDataToRemote();
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
    }

    static ItemStack icon(Item item, String name, String... lore) {
        ItemStack s = new ItemStack(item);
        s.set(DataComponents.CUSTOM_NAME, Component.literal("\u00a7r" + name));
        if (lore.length > 0) {
            List<Component> l = new ArrayList<>();
            for (String x : lore) l.add(Component.literal("\u00a7r" + x));
            s.set(DataComponents.LORE, new ItemLore(l));
        }
        return s;
    }
    static ItemStack backIcon() { return icon(Items.ARROW, "\u00a7eBack to menu"); }

    // =====================================================================
    //  MAIN MENU
    // =====================================================================
    static void openMain(ServerPlayer p) {
        Gui g = new Gui("\u00a78Oneblox", 3).fill();
        g.set(4, icon(Items.GOLD_NUGGET, "\u00a76" + fmt(bal(p)), "\u00a77Rank: \u00a7f" + pretty(rankId(p))), null);
        g.set(10, icon(Items.GRASS_BLOCK, "\u00a7aMy island", "\u00a77Click to teleport"), (pl, ct) -> { pl.closeContainer(); toHome(pl); });
        g.set(11, icon(Items.EMERALD, "\u00a7aShop", "\u00a77Buy blocks, food and tools"), (pl, ct) -> openShop(pl));
        g.set(12, icon(Items.GOLD_INGOT, "\u00a76Sell", "\u00a77Sell your items for money"), (pl, ct) -> openSell(pl));
        g.set(13, icon(Items.CHEST, "\u00a7eAuction house", "\u00a77Buy and sell with other players"), (pl, ct) -> openAuction(pl, 0));
        g.set(14, icon(Items.ENDER_CHEST, "\u00a7dCrates", "\u00a77Open crates with keys"), (pl, ct) -> openCrates(pl));
        g.set(15, icon(Items.SPAWNER, "\u00a7cSpawners", "\u00a77Buy mob spawners"), (pl, ct) -> openSpawners(pl));
        g.set(16, icon(Items.NETHER_STAR, "\u00a7bDaily reward", "\u00a77Free money and a key every 24h"), (pl, ct) -> claimDaily(pl));
        g.set(19, icon(Items.COMPASS, "\u00a7fHub", "\u00a77Go to spawn"), (pl, ct) -> { pl.closeContainer(); toHub(pl); });
        g.set(20, icon(Items.BOOK, "\u00a7fLeaderboards", "\u00a77Richest players and top islands"), (pl, ct) -> openTop(pl));
        g.set(21, icon(Items.BARREL, "\u00a7fIsland panel", "\u00a77Members and permissions"), (pl, ct) -> openIslandPanel(pl));
        if (isAdmin(p)) g.set(25, icon(Items.COMMAND_BLOCK, "\u00a74Admin panel", "\u00a77Owner only"), (pl, ct) -> openAdmin(pl));
        g.open(p);
    }

    // =====================================================================
    //  SHOP + SELL
    // =====================================================================
    static Object[][] shop() {
        return new Object[][]{
            {"oak_log", Items.OAK_LOG, 6L}, {"oak_planks", Items.OAK_PLANKS, 2L}, {"cobblestone", Items.COBBLESTONE, 3L}, {"dirt", Items.DIRT, 2L},
            {"sand", Items.SAND, 3L}, {"glass", Items.GLASS, 6L}, {"stone_bricks", Items.STONE_BRICKS, 6L}, {"white_wool", Items.WHITE_WOOL, 10L},
            {"torch", Items.TORCH, 3L}, {"bread", Items.BREAD, 6L}, {"cooked_beef", Items.COOKED_BEEF, 12L}, {"golden_apple", Items.GOLDEN_APPLE, 600L},
            {"bone_meal", Items.BONE_MEAL, 20L}, {"oak_sapling", Items.OAK_SAPLING, 15L}, {"wheat_seeds", Items.WHEAT_SEEDS, 5L},
            {"sugar_cane", Items.SUGAR_CANE, 12L}, {"bucket", Items.BUCKET, 100L}, {"water_bucket", Items.WATER_BUCKET, 150L},
            {"lava_bucket", Items.LAVA_BUCKET, 300L}, {"iron_pickaxe", Items.IRON_PICKAXE, 250L}, {"diamond_pickaxe", Items.DIAMOND_PICKAXE, 2500L},
            {"ender_pearl", Items.ENDER_PEARL, 300L}, {"experience_bottle", Items.EXPERIENCE_BOTTLE, 150L}
        };
    }

    static void openShop(ServerPlayer p) {
        Gui g = new Gui("\u00a78Shop  -  " + fmt(bal(p)), 6).fill();
        Object[][] shop = shop();
        for (int i = 0; i < shop.length && i < 45; i++) {
            String id = (String) shop[i][0];
            Item it = (Item) shop[i][1];
            long price = (Long) shop[i][2];
            g.set(i, icon(it, "\u00a7f" + pretty(id), "\u00a77Price: \u00a7a" + fmt(price) + " each", "\u00a78Click: buy 1  |  Shift-click: buy 16"),
                (pl, ct) -> { buyItem(pl, id, price, ct == ClickType.QUICK_MOVE ? 16 : 1); });
        }
        g.set(49, backIcon(), (pl, ct) -> openMain(pl));
        g.open(p);
    }

    static void buyItem(ServerPlayer p, String id, long price, int n) {
        long cost = price * n;
        if (bal(p) < cost) { msg(p, "\u00a7cYou need " + fmt(cost)); return; }
        add(p, -cost);
        run("give " + p.getStringUUID() + " minecraft:" + id + " " + n);
        msg(p, "\u00a7aBought " + n + "x " + pretty(id) + " for " + fmt(cost));
    }

    static long sellValue(ServerPlayer p, boolean remove) {
        Inventory inv = p.getInventory();
        long total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty()) continue;
            Long v = cfg.sell.get(BuiltInRegistries.ITEM.getKey(st.getItem()).getPath());
            if (v == null) continue;
            total += v * st.getCount();
            if (remove) inv.setItem(i, ItemStack.EMPTY);
        }
        return total;
    }

    static void sellHand(ServerPlayer p) {
        ItemStack st = p.getMainHandItem();
        Long v = st.isEmpty() ? null : cfg.sell.get(BuiltInRegistries.ITEM.getKey(st.getItem()).getPath());
        if (v == null) { msg(p, "\u00a7cYou can't sell that."); return; }
        long total = v * st.getCount();
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        pay(p, total);
    }

    static void openSell(ServerPlayer p) {
        Gui g = new Gui("\u00a78Sell", 3).fill();
        long worth = sellValue(p, false);
        g.set(11, icon(Items.GOLD_INGOT, "\u00a76Sell everything", "\u00a77Sells every sellable item you carry", "\u00a77Worth right now: \u00a7a" + fmt(worth)),
            (pl, ct) -> { long t = sellValue(pl, true); if (t > 0) pay(pl, t); else msg(pl, "\u00a7cNothing to sell."); openSell(pl); });
        g.set(15, icon(Items.GOLD_NUGGET, "\u00a7eSell item in hand", "\u00a77Hold an item and click"),
            (pl, ct) -> { pl.closeContainer(); sellHand(pl); });
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Long> e : cfg.sell.entrySet()) {
            if (lines.size() >= 18) break;
            lines.add("\u00a77" + pretty(e.getKey()) + "  \u00a7a" + fmt(e.getValue()));
        }
        lines.add("\u00a78(full list in config.json)");
        g.set(13, icon(Items.BOOK, "\u00a7ePrices", lines.toArray(new String[0])), null);
        g.set(22, backIcon(), (pl, ct) -> openMain(pl));
        g.open(p);
    }

    // =====================================================================
    //  AUCTION HOUSE
    // =====================================================================
    static final Map<String, Integer> AH_PAGE = new HashMap<>();

    static void openAuction(ServerPlayer p, int page) {
        int per = 45;
        List<Listing> all = data.auctions;
        int pages = Math.max(1, (all.size() + per - 1) / per);
        int pg = Math.max(0, Math.min(page, pages - 1));
        AH_PAGE.put(p.getStringUUID(), pg);
        Gui g = new Gui("\u00a78Auction House  (" + (pg + 1) + "/" + pages + ")", 6).fill();
        for (int i = 0; i < per; i++) {
            int idx = pg * per + i;
            if (idx >= all.size()) break;
            Listing l = all.get(idx);
            ItemStack st = deser(l.json);
            if (st.isEmpty()) continue;
            boolean mine = l.seller.equals(p.getStringUUID());
            ItemStack show = st.copy();
            List<Component> lore = new ArrayList<>();
            lore.add(Component.literal("\u00a7r\u00a77Price: \u00a7a" + fmt(l.price)));
            lore.add(Component.literal("\u00a7r\u00a77Seller: \u00a7f" + l.sellerName));
            lore.add(Component.literal(mine ? "\u00a7r\u00a7cClick to cancel your listing" : "\u00a7r\u00a7aClick to buy"));
            show.set(DataComponents.LORE, new ItemLore(lore));
            int id = l.id;
            g.set(i, show, (pl, ct) -> buyListing(pl, id));
        }
        g.set(45, icon(Items.ARROW, "\u00a7ePrevious page"), (pl, ct) -> openAuction(pl, pg - 1));
        g.set(49, icon(Items.PAPER, "\u00a7eSell the item in your hand", "\u00a77Hold it and type", "\u00a7f/ah sell <price>", "\u00a77Tax: " + cfg.ahTax + "%"), null);
        g.set(48, backIcon(), (pl, ct) -> openMain(pl));
        g.set(53, icon(Items.ARROW, "\u00a7eNext page"), (pl, ct) -> openAuction(pl, pg + 1));
        g.open(p);
    }

    static void buyListing(ServerPlayer p, int id) {
        Listing l = null;
        for (Listing x : data.auctions) if (x.id == id) l = x;
        int back = AH_PAGE.getOrDefault(p.getStringUUID(), 0);
        if (l == null) { msg(p, "\u00a7cThat listing is gone."); openAuction(p, back); return; }
        ItemStack st = deser(l.json);
        if (st.isEmpty()) { data.auctions.remove(l); openAuction(p, back); return; }
        if (l.seller.equals(p.getStringUUID())) {
            data.auctions.remove(l);
            giveStack(p, st);
            msg(p, "\u00a7eListing cancelled, item returned.");
            saveAll();
            openAuction(p, back);
            return;
        }
        if (bal(p) < l.price) { msg(p, "\u00a7cYou need " + fmt(l.price)); return; }
        data.auctions.remove(l);
        add(p, -l.price);
        long net = l.price - l.price * cfg.ahTax / 100;
        data.money.merge(l.seller, net, Long::sum);
        ServerPlayer seller = server.getPlayerList().getPlayer(UUID.fromString(l.seller));
        if (seller != null) msg(seller, "\u00a7aYour " + l.name + " sold for " + fmt(net));
        giveStack(p, st);
        msg(p, "\u00a7aBought " + l.name + " for " + fmt(l.price));
        saveAll();
        openAuction(p, back);
    }

    static void sellOnAuction(ServerPlayer p, long price) {
        ItemStack hand = p.getMainHandItem();
        if (hand.isEmpty()) { msg(p, "\u00a7cHold the item you want to sell."); return; }
        int mine = 0;
        for (Listing x : data.auctions) if (x.seller.equals(p.getStringUUID())) mine++;
        if (mine >= cfg.ahMaxListings) { msg(p, "\u00a7cYou already have " + mine + " listings."); return; }
        String json = ser(hand);
        if (json == null) { msg(p, "\u00a7cThis item can't be listed."); return; }
        Listing l = new Listing();
        l.id = data.nextAuction++;
        l.seller = p.getStringUUID();
        l.sellerName = p.getName().getString();
        l.json = json;
        l.name = hand.getHoverName().getString();
        l.count = hand.getCount();
        l.price = price;
        l.time = System.currentTimeMillis();
        data.auctions.add(l);
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        msg(p, "\u00a7aListed " + l.count + "x " + l.name + " for " + fmt(price));
        saveAll();
    }

    // =====================================================================
    //  CRATES + KEYS + SPAWNERS + DAILY
    // =====================================================================
    static int keyCount(ServerPlayer p, String crate) {
        return data.keys.computeIfAbsent(p.getStringUUID(), k -> new HashMap<>()).getOrDefault(crate, 0);
    }
    static void addKey(ServerPlayer p, String crate, int n) {
        data.keys.computeIfAbsent(p.getStringUUID(), k -> new HashMap<>()).merge(crate, n, Integer::sum);
    }

    static String rewardName(Reward r) {
        switch (r.type) {
            case "money": return "\u00a7a" + fmt(r.amount);
            case "item": return "\u00a7f" + r.amount + "x " + pretty(r.value);
            case "spawner": return "\u00a7c" + pretty(r.value) + " Spawner";
            default: return "\u00a76" + r.amount + "x " + r.value + " Key";
        }
    }
    static ItemStack rewardIcon(Reward r) {
        Item it = r.type.equals("money") ? Items.EMERALD : r.type.equals("spawner") ? Items.SPAWNER : r.type.equals("key") ? Items.TRIPWIRE_HOOK : Items.CHEST;
        return icon(it, rewardName(r));
    }
    static Reward pickReward(List<Reward> pool) {
        int total = 0;
        for (Reward r : pool) total += Math.max(1, r.weight);
        int roll = RNG.nextInt(total);
        for (Reward r : pool) { roll -= Math.max(1, r.weight); if (roll < 0) return r; }
        return pool.get(0);
    }
    static void giveReward(ServerPlayer p, Reward r) {
        String u = p.getStringUUID();
        switch (r.type) {
            case "money": pay(p, r.amount); break;
            case "item": run("give " + u + " minecraft:" + r.value + " " + r.amount); break;
            case "spawner":
                run("give " + u + " minecraft:spawner[block_entity_data={id:\"minecraft:mob_spawner\",SpawnData:{entity:{id:\"minecraft:" + r.value + "\"}}}] 1");
                break;
            default: addKey(p, r.value, r.amount);
        }
    }

    static void openCrates(ServerPlayer p) {
        Gui g = new Gui("\u00a78Crates", 3).fill();
        Item[] ci = {Items.CHEST, Items.ENDER_CHEST, Items.BARREL};
        int[] slots = {11, 13, 15};
        int n = 0;
        for (String crate : cfg.crates.keySet()) {
            if (n >= 3) break;
            long price = cfg.keyPrice.getOrDefault(crate, 10000L);
            List<String> lore = new ArrayList<>();
            lore.add("\u00a77Your keys: \u00a7f" + keyCount(p, crate));
            lore.add("\u00a77Key price: \u00a7a" + fmt(price));
            lore.add(" ");
            lore.add("\u00a7eRewards:");
            for (Reward r : cfg.crates.get(crate)) lore.add(" \u00a78- " + rewardName(r));
            lore.add(" ");
            lore.add("\u00a7aClick: open (uses 1 key)");
            lore.add("\u00a7aShift-click: buy a key");
            String cr = crate;
            g.set(slots[n], icon(ci[n], "\u00a7d" + crate + " Crate", lore.toArray(new String[0])),
                (pl, ct) -> { if (ct == ClickType.QUICK_MOVE) buyKey(pl, cr); else openCrate(pl, cr); });
            n++;
        }
        g.set(22, backIcon(), (pl, ct) -> openMain(pl));
        g.open(p);
    }

    static void buyKey(ServerPlayer p, String crate) {
        long price = cfg.keyPrice.getOrDefault(crate, 10000L);
        if (bal(p) < price) { msg(p, "\u00a7cYou need " + fmt(price)); return; }
        add(p, -price);
        addKey(p, crate, 1);
        msg(p, "\u00a7aBought a " + crate + " key");
        openCrates(p);
    }

    static void openCrate(ServerPlayer p, String crate) {
        List<Reward> pool = cfg.crates.get(crate);
        if (pool == null || pool.isEmpty()) return;
        if (keyCount(p, crate) < 1) { msg(p, "\u00a7cNo " + crate + " keys. Shift-click the crate to buy one."); return; }
        addKey(p, crate, -1);
        Reward win = pickReward(pool);
        Gui g = new Gui("\u00a78" + crate + " Crate", 3).fill();
        g.open(p);
        for (int i = 1; i <= 12; i++) {
            int step = i;
            later(step * 3, () -> {
                if (p.containerMenu instanceof GuiMenu gm && gm.g == g) {
                    Reward r = step == 12 ? win : pool.get(RNG.nextInt(pool.size()));
                    g.box.setItem(13, rewardIcon(r));
                    gm.broadcastChanges();
                    sound(p, step == 12 ? "entity.player.levelup" : "ui.button.click");
                }
            });
        }
        later(40, () -> { giveReward(p, win); msg(p, "\u00a76You won: " + rewardName(win)); saveAll(); });
    }

    static void openSpawners(ServerPlayer p) {
        Gui g = new Gui("\u00a78Spawners  -  " + fmt(bal(p)), 4).fill();
        int i = 0;
        for (Map.Entry<String, Long> e : cfg.spawners.entrySet()) {
            if (i >= 27) break;
            String mob = e.getKey();
            g.set(i++, icon(Items.SPAWNER, "\u00a7c" + pretty(mob) + " Spawner", "\u00a77Price: \u00a7a" + fmt(e.getValue()), "\u00a78Click to buy"),
                (pl, ct) -> { buySpawner(pl, mob); openSpawners(pl); });
        }
        g.set(31, backIcon(), (pl, ct) -> openMain(pl));
        g.open(p);
    }

    static void buySpawner(ServerPlayer p, String mob) {
        Long price = cfg.spawners.get(mob);
        if (price == null) { msg(p, "\u00a7cUnknown mob. Open /menu, Spawners."); return; }
        if (bal(p) < price) { msg(p, "\u00a7cYou need " + fmt(price)); return; }
        add(p, -price);
        giveReward(p, new Reward("spawner", mob, 1, 1));
        msg(p, "\u00a7aBought a " + pretty(mob) + " spawner for " + fmt(price));
    }

    static void claimDaily(ServerPlayer p) {
        long now = System.currentTimeMillis();
        long last = data.lastDaily.getOrDefault(p.getStringUUID(), 0L);
        if (now - last < 86_400_000L) {
            long h = (86_400_000L - (now - last)) / 3_600_000L;
            msg(p, "\u00a7cDaily reward ready in about " + (h + 1) + "h");
            return;
        }
        data.lastDaily.put(p.getStringUUID(), now);
        add(p, cfg.dailyMoney);
        addKey(p, "Panda", 1);
        msg(p, "\u00a76Daily reward: " + fmt(cfg.dailyMoney) + " and a Panda key!");
        sound(p, "entity.player.levelup");
        saveAll();
    }

    // =====================================================================
    //  LEADERBOARDS
    // =====================================================================
    static void openTop(ServerPlayer p) {
        Gui g = new Gui("\u00a78Leaderboards", 4).fill();
        List<Map.Entry<String, Long>> rich = new ArrayList<>(data.money.entrySet());
        rich.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        g.set(0, icon(Items.GOLD_INGOT, "\u00a76Richest players"), null);
        for (int i = 0; i < Math.min(7, rich.size()); i++)
            g.set(1 + i, icon(Items.PAPER, "\u00a7f#" + (i + 1) + " " + data.names.getOrDefault(rich.get(i).getKey(), "?"), "\u00a7a" + fmt(rich.get(i).getValue())), null);
        List<Isl> isl = new ArrayList<>(data.islands.values());
        isl.sort((a, b) -> Integer.compare(b.broken, a.broken));
        g.set(18, icon(Items.GRASS_BLOCK, "\u00a7aTop islands"), null);
        for (int i = 0; i < Math.min(7, isl.size()); i++) {
            Isl x = isl.get(i);
            g.set(19 + i, icon(Items.PAPER, "\u00a7f#" + (i + 1) + " " + data.names.getOrDefault(x.owner, "?"),
                "\u00a77Phase " + (phase(x) + 1) + ": " + PHASE_NAME[phase(x)], "\u00a77Blocks: \u00a7f" + x.broken), null);
        }
        g.set(31, backIcon(), (pl, ct) -> openMain(pl));
        g.open(p);
    }

    // =====================================================================
    //  ISLAND PANEL, MEMBERS, PERMISSIONS
    // =====================================================================
    static void openIslandPanel(ServerPlayer p) {
        Isl is = islandOf(p.getStringUUID());
        if (is == null) { msg(p, "\u00a7cYou have no island yet."); return; }
        int ph = phase(is);
        boolean owner = is.owner.equals(p.getStringUUID());
        Gui g = new Gui("\u00a78Island Panel", 3).fill();
        g.set(10, icon(Items.GRASS_BLOCK, "\u00a7aGo to island", "\u00a77Click to teleport"), (pl, ct) -> { pl.closeContainer(); toHome(pl); });
        g.set(11, icon(Items.BOOK, "\u00a7eIsland info", "\u00a77Phase: \u00a7f" + (ph + 1) + " " + PHASE_NAME[ph],
            "\u00a77Blocks broken: \u00a7f" + is.broken, "\u00a77Members: \u00a7f" + is.members.size() + "/" + cfg.maxMembers), null);
        g.set(12, icon(Items.PLAYER_HEAD, "\u00a7bMembers", "\u00a77Click to view"), (pl, ct) -> openMembers(pl));
        g.set(13, icon(Items.PAPER, "\u00a7fInvite a player", "\u00a77Type: \u00a7f/is invite <player>"), null);
        if (owner) g.set(14, icon(Items.REPEATER, "\u00a76Permissions", "\u00a77What members may do"), (pl, ct) -> openPermissions(pl));
        else g.set(14, icon(Items.BARRIER, "\u00a7cLeave island", "\u00a77Click to leave"), (pl, ct) -> { pl.closeContainer(); leaveIsland(pl); });
        g.set(22, backIcon(), (pl, ct) -> openMain(pl));
        g.open(p);
    }

    static void openMembers(ServerPlayer p) {
        Isl is = islandOf(p.getStringUUID());
        if (is == null) return;
        boolean owner = is.owner.equals(p.getStringUUID());
        Gui g = new Gui("\u00a78Island Members", 3).fill();
        int i = 10;
        for (String m : is.members) {
            if (i > 16) break;
            boolean isOwner = m.equals(is.owner);
            Act a = (owner && !isOwner) ? (pl, ct) -> { kickMember(pl, m); openMembers(pl); } : null;
            g.set(i++, icon(Items.PLAYER_HEAD, "\u00a7f" + data.names.getOrDefault(m, "?"),
                isOwner ? "\u00a76Island owner" : (owner ? "\u00a7cClick to kick" : "\u00a77Member")), a);
        }
        g.set(22, icon(Items.ARROW, "\u00a7eBack"), (pl, ct) -> openIslandPanel(pl));
        g.open(p);
    }

    static void openPermissions(ServerPlayer p) {
        Isl is = islandOf(p.getStringUUID());
        if (is == null || !is.owner.equals(p.getStringUUID())) { msg(p, "\u00a7cOnly the island owner can do that."); return; }
        Gui g = new Gui("\u00a78Island Permissions", 3).fill();
        g.set(11, icon(is.memberBuild ? Items.LIME_DYE : Items.RED_DYE, "\u00a7fMembers can build: " + (is.memberBuild ? "\u00a7aYES" : "\u00a7cNO"),
            "\u00a77Break and place blocks on the island", "\u00a78Click to toggle"), (pl, ct) -> { is.memberBuild = !is.memberBuild; saveAll(); openPermissions(pl); });
        g.set(15, icon(is.memberInvite ? Items.LIME_DYE : Items.RED_DYE, "\u00a7fMembers can invite: " + (is.memberInvite ? "\u00a7aYES" : "\u00a7cNO"),
            "\u00a77Invite new people to the island", "\u00a78Click to toggle"), (pl, ct) -> { is.memberInvite = !is.memberInvite; saveAll(); openPermissions(pl); });
        g.set(22, icon(Items.ARROW, "\u00a7eBack"), (pl, ct) -> openIslandPanel(pl));
        g.open(p);
    }

    // =====================================================================
    //  ADMIN PANEL (owner only)
    // =====================================================================
    static void openAdmin(ServerPlayer p) {
        if (!isAdmin(p)) { msg(p, "\u00a7cAdmins only."); return; }
        String u = p.getStringUUID();
        Gui g = new Gui("\u00a74Admin Panel", 4).fill();
        g.set(10, icon(Items.COMPASS, "\u00a7aSet hub here", "\u00a77Players /spawn to where you stand"), (pl, ct) -> {
            data.hubSet = true; data.hubX = pl.getX(); data.hubY = pl.getY(); data.hubZ = pl.getZ(); saveAll(); msg(pl, "\u00a7aHub set here."); });
        g.set(11, icon(Items.GOLD_INGOT, "\u00a76+$100,000", "\u00a77for you"), (pl, ct) -> { add(pl, 100000); msg(pl, "\u00a76Balance: " + fmt(bal(pl))); });
        g.set(12, icon(Items.GOLD_BLOCK, "\u00a76+$1,000,000", "\u00a77for you"), (pl, ct) -> { add(pl, 1000000); msg(pl, "\u00a76Balance: " + fmt(bal(pl))); });
        g.set(13, icon(Items.LEATHER_CHESTPLATE, "\u00a77Starter kit", "\u00a77give it to yourself"), (pl, ct) -> giveKit(pl));
        int s = 14;
        for (String crate : cfg.crates.keySet()) {
            if (s > 16) break;
            String cr = crate;
            g.set(s++, icon(Items.TRIPWIRE_HOOK, "\u00a7d+5 " + crate + " keys", "\u00a77for you"), (pl, ct) -> { addKey(pl, cr, 5); msg(pl, "\u00a7d+5 " + cr + " keys"); });
        }
        g.set(19, icon(Items.DIAMOND_BLOCK, "\u00a7bCreative mode"), (pl, ct) -> run("gamemode creative " + pl.getStringUUID()));
        g.set(20, icon(Items.IRON_SWORD, "\u00a7fSurvival mode"), (pl, ct) -> run("gamemode survival " + pl.getStringUUID()));
        g.set(21, icon(Items.CLOCK, "\u00a7eSet day"), (pl, ct) -> run("time set day"));
        g.set(22, icon(Items.WATER_BUCKET, "\u00a79Clear weather"), (pl, ct) -> run("weather clear"));
        g.set(23, icon(Items.GOLDEN_APPLE, "\u00a7cHeal and feed"), (pl, ct) -> {
            run("effect give " + pl.getStringUUID() + " minecraft:instant_health 1 10 true");
            run("effect give " + pl.getStringUUID() + " minecraft:saturation 5 10 true"); });
        g.set(24, icon(Items.PLAYER_HEAD, "\u00a7ePlayers", "\u00a77Teleport, give money, ranks, kick"), (pl, ct) -> openPlayers(pl));
        g.set(25, icon(Items.REDSTONE, "\u00a7cReload config", "\u00a77re-reads config.json"), (pl, ct) -> {
            cfg = load("config.json", Cfg.class, cfg); msg(pl, "\u00a7aConfig reloaded."); });
        g.set(26, icon(Items.WRITABLE_BOOK, "\u00a7aSave data now"), (pl, ct) -> { saveAll(); msg(pl, "\u00a7aSaved."); });
        g.set(31, backIcon(), (pl, ct) -> openMain(pl));
        g.open(p);
    }

    static void openPlayers(ServerPlayer p) {
        if (!isAdmin(p)) return;
        Gui g = new Gui("\u00a74Players", 4).fill();
        int i = 0;
        for (ServerPlayer t : server.getPlayerList().getPlayers()) {
            if (i >= 27) break;
            String tid = t.getStringUUID();
            g.set(i++, icon(Items.PLAYER_HEAD, "\u00a7e" + t.getName().getString(), "\u00a77Rank: \u00a7f" + pretty(rankId(t)), "\u00a77Money: \u00a7a" + fmt(bal(t)), "\u00a78Click to manage"),
                (pl, ct) -> openPlayerActions(pl, tid));
        }
        g.set(31, icon(Items.ARROW, "\u00a7eBack"), (pl, ct) -> openAdmin(pl));
        g.open(p);
    }

    static void openPlayerActions(ServerPlayer p, String tid) {
        if (!isAdmin(p)) return;
        ServerPlayer t = server.getPlayerList().getPlayer(UUID.fromString(tid));
        if (t == null) { msg(p, "\u00a7cThat player is offline."); openPlayers(p); return; }
        Gui g = new Gui("\u00a74" + t.getName().getString(), 3).fill();
        g.set(10, icon(Items.ENDER_PEARL, "\u00a7aTeleport to them"), (pl, ct) -> run("tp " + pl.getStringUUID() + " " + tid));
        g.set(11, icon(Items.LEAD, "\u00a7aBring them here"), (pl, ct) -> run("tp " + tid + " " + pl.getStringUUID()));
        g.set(12, icon(Items.GOLD_INGOT, "\u00a76Give $10,000"), (pl, ct) -> { add(t, 10000); msg(pl, "\u00a76Gave $10,000"); });
        g.set(13, icon(Items.NAME_TAG, "\u00a7bRank: " + pretty(data.rank.getOrDefault(tid, "member")), "\u00a77Click to change"), (pl, ct) -> {
            List<String> rk = new ArrayList<>(cfg.ranks.keySet());
            int at = rk.indexOf(data.rank.getOrDefault(tid, "member"));
            data.rank.put(tid, rk.get((at + 1) % rk.size()));
            saveAll();
            openPlayerActions(pl, tid); });
        g.set(14, icon(Items.TNT, "\u00a7cKick"), (pl, ct) -> { pl.closeContainer(); run("kick " + t.getName().getString() + " Kicked by the owner"); });
        g.set(22, icon(Items.ARROW, "\u00a7eBack"), (pl, ct) -> openPlayers(pl));
        g.open(p);
    }

    // =====================================================================
    //  ISLANDS
    // =====================================================================
    static int cx(int idx) { return cfg.spacing * (1 + idx % 10); }
    static int cz(int idx) { return cfg.spacing * (1 + idx / 10); }
    static Isl islandOf(String uuid) { Integer i = data.islandOf.get(uuid); return i == null ? null : data.islands.get(i); }
    static int phase(Isl is) { return Math.min(PHASE_NAME.length - 1, is.broken / cfg.blocksPerPhase); }

    static void toHome(ServerPlayer p) {
        Isl is = islandOf(p.getStringUUID());
        if (is == null) { createIsland(p); return; }
        tp(p, cx(is.idx) + 0.5, Y() + 1, cz(is.idx) + 0.5);
    }

    static void toHub(ServerPlayer p) {
        if (data.hubSet) tp(p, data.hubX, data.hubY, data.hubZ);
        else msg(p, "\u00a7cNo hub is set yet (admin: open /admin and click Set hub here).");
    }

    static void createIsland(ServerPlayer p) {
        String id = p.getStringUUID();
        int idx = data.nextIsland++;
        Isl is = new Isl();
        is.idx = idx; is.owner = id; is.members.add(id);
        data.islands.put(idx, is);
        data.islandOf.put(id, idx);
        int x = cx(idx), z = cz(idx);
        msg(p, "\u00a7eBuilding your island, one moment...");
        runIn("forceload add " + x + " " + z);
        later(40, () -> {
            runIn("setblock " + x + " " + (Y() - 1) + " " + z + " minecraft:obsidian");
            runIn("setblock " + x + " " + Y() + " " + z + " minecraft:grass_block");
        });
        later(50, () -> {
            tp(p, x + 0.5, Y() + 1, z + 0.5);
            is.ready = true;
            msg(p, "\u00a7aWelcome to your island! Break the grass block. Type /menu for everything.");
        });
        later(120, () -> runIn("forceload remove " + x + " " + z));
        saveAll();
    }

    static void placeNext(Isl is) {
        int ph = phase(is);
        String[] blocks = PHASE_BLOCKS[ph];
        String b = "minecraft:" + blocks[RNG.nextInt(blocks.length)];
        if (ph >= 1 && RNG.nextInt(100) < cfg.spawnerChance) {
            String[] m = PHASE_MOBS[ph];
            b = "minecraft:spawner{SpawnData:{entity:{id:\"minecraft:" + m[RNG.nextInt(m.length)] + "\"}}}";
        }
        runIn("setblock " + cx(is.idx) + " " + Y() + " " + cz(is.idx) + " " + b + " keep");
    }

    static void tellIsland(Isl is, String s) {
        for (String m : is.members) {
            ServerPlayer sp = server.getPlayerList().getPlayer(UUID.fromString(m));
            if (sp != null) msg(sp, s);
        }
    }

    static boolean canBuild(ServerPlayer p, BlockPos pos) {
        Isl is = islandOf(p.getStringUUID());
        if (is == null) return false;
        if (!is.owner.equals(p.getStringUUID()) && !is.memberBuild) return false;
        return Math.abs(pos.getX() - cx(is.idx)) < cfg.radius && Math.abs(pos.getZ() - cz(is.idx)) < cfg.radius;
    }

    static boolean inHub(double x, double z) {
        return Math.abs(x - data.hubX) < cfg.hubRadius && Math.abs(z - data.hubZ) < cfg.hubRadius;
    }

    /** Keeps players inside their own island (or the hub) and out of the void. */
    static void enforce(ServerPlayer p, Isl is) {
        if (is == null || !is.ready) return;
        if (voidDim && inOverworld(p) && !isAdmin(p)) { toHome(p); return; }   // respawned in the normal overworld
        if (!inVoid(p) || isAdmin(p)) return;
        double x = p.getX(), z = p.getZ();
        if (p.getY() < ((voidDim || voidWorld) ? -10 : Y() - 40)) { toHome(p); return; }
        if (inHub(x, z)) return;
        if (Math.abs(x - cx(is.idx)) > cfg.radius || Math.abs(z - cz(is.idx)) > cfg.radius) {
            msg(p, "\u00a7cYou can't leave your island.");
            toHome(p);
        }
    }

    // ---- island management used by commands and menus ----
    static void invite(ServerPlayer p, ServerPlayer t) {
        Isl i = islandOf(p.getStringUUID());
        boolean owner = i != null && i.owner.equals(p.getStringUUID());
        if (i == null || (!owner && !i.memberInvite)) { msg(p, "\u00a7cYou are not allowed to invite."); return; }
        if (i.members.size() >= cfg.maxMembers) { msg(p, "\u00a7cYour island is full."); return; }
        INVITES.put(t.getStringUUID(), i.idx);
        msg(p, "\u00a7aInvited " + t.getName().getString());
        chat(t, "\u00a7e" + p.getName().getString() + " invited you to their island. Type \u00a7f/is accept\u00a7e (your old island stays where it is).");
    }

    static void acceptInvite(ServerPlayer p) {
        String id = p.getStringUUID();
        Integer idx = INVITES.remove(id);
        Isl n = idx == null ? null : data.islands.get(idx);
        if (n == null) { msg(p, "\u00a7cYou have no invite."); return; }
        Isl old = islandOf(id);
        if (old != null) old.members.remove(id);
        n.members.add(id);
        data.islandOf.put(id, n.idx);
        tellIsland(n, "\u00a7a" + p.getName().getString() + " joined the island!");
        toHome(p);
        saveAll();
    }

    static void leaveIsland(ServerPlayer p) {
        String id = p.getStringUUID();
        Isl i = islandOf(id);
        if (i == null) return;
        if (i.owner.equals(id)) { msg(p, "\u00a7cThe owner can't leave. Kick the members first."); return; }
        i.members.remove(id);
        data.islandOf.remove(id);   // a fresh island is created for you next second
        msg(p, "\u00a7eYou left the island.");
        saveAll();
    }

    static void kickMember(ServerPlayer p, String memberUuid) {
        Isl i = islandOf(p.getStringUUID());
        if (i == null || !i.owner.equals(p.getStringUUID())) { msg(p, "\u00a7cOnly the island owner can kick."); return; }
        if (memberUuid.equals(i.owner) || !i.members.remove(memberUuid)) return;
        data.islandOf.remove(memberUuid);
        msg(p, "\u00a7eKicked " + data.names.getOrDefault(memberUuid, "player"));
        saveAll();
    }

    static boolean sameIsland(ServerPlayer a, ServerPlayer b) {
        Isl x = islandOf(a.getStringUUID());
        return x != null && x == islandOf(b.getStringUUID());
    }

    // ---- teleport requests ----
    static void tpaRequest(ServerPlayer p, ServerPlayer t) {
        if (p == t) { msg(p, "\u00a7cYou can't teleport to yourself."); return; }
        if (!(sameIsland(p, t) || (inHub(p.getX(), p.getZ()) && inHub(t.getX(), t.getZ())))) {
            msg(p, "\u00a7c/tpa works in the hub and between island members only (islands are private).");
            return;
        }
        TPA.put(t.getStringUUID(), p.getStringUUID());
        msg(p, "\u00a7aTeleport request sent to " + t.getName().getString());
        chat(t, "\u00a7e" + p.getName().getString() + " wants to teleport to you. Type \u00a7f/tpaccept\u00a7e or \u00a7f/tpdeny");
    }

    static void tpaAnswer(ServerPlayer t, boolean accept) {
        String from = TPA.remove(t.getStringUUID());
        ServerPlayer p = from == null ? null : server.getPlayerList().getPlayer(UUID.fromString(from));
        if (p == null) { msg(t, "\u00a7cNo teleport request."); return; }
        if (!accept) { msg(t, "\u00a7eRequest denied."); msg(p, "\u00a7c" + t.getName().getString() + " denied your request."); return; }
        run("tp " + p.getStringUUID() + " " + t.getStringUUID());
        msg(p, "\u00a7aTeleported!");
        msg(t, "\u00a7aRequest accepted.");
    }

    // ---- kit, spawners, hub ----
    /** Starter kit: wooden tools, "Common Armor" (gray name) worn, 16 steaks. */
    static void giveKit(ServerPlayer p) {
        String u = p.getStringUUID();
        for (String t : new String[]{"sword", "pickaxe", "axe", "shovel", "hoe"}) run("give " + u + " minecraft:wooden_" + t + " 1");
        String name = "[custom_name={text:\"Common Armor\",color:\"gray\",italic:false}]";
        run("item replace entity " + u + " armor.head with minecraft:leather_helmet" + name);
        run("item replace entity " + u + " armor.chest with minecraft:leather_chestplate" + name);
        run("item replace entity " + u + " armor.legs with minecraft:leather_leggings" + name);
        run("item replace entity " + u + " armor.feet with minecraft:leather_boots" + name);
        run("give " + u + " minecraft:cooked_beef 16");
        msg(p, "\u00a7aYou received the starter kit!");
    }

    /** Called just before a spawner breaks: hands the player a spawner item for the same mob (any tool, no Silk Touch needed). */
    static void returnSpawner(ServerPlayer p, BlockPos pos) {
        Set<String> mobs = new LinkedHashSet<>(cfg.spawners.keySet());
        for (String[] list : PHASE_MOBS) mobs.addAll(Arrays.asList(list));
        for (String m : mobs) {
            runIn("execute if data block " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                + " SpawnData.entity{id:\"minecraft:" + m + "\"} run give " + p.getStringUUID()
                + " minecraft:spawner[block_entity_data={id:\"minecraft:mob_spawner\",SpawnData:{entity:{id:\"minecraft:" + m + "\"}}}] 1");
        }
    }

    static void buildHub() {
        if (data.hubSet) return;
        int y = Y();
        runIn("forceload add -25 -25 25 25");
        later(40, () -> runIn("fill -20 " + (y - 1) + " -20 20 " + (y - 1) + " 20 minecraft:stone_bricks"));
        later(120, () -> runIn("forceload remove -25 -25 25 25"));
        data.hubSet = true; data.hubX = 0.5; data.hubY = y; data.hubZ = 0.5;
        saveAll();
    }

    // =====================================================================
    //  STARTUP + EVENTS
    // =====================================================================
    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            dir = FabricLoader.getInstance().getConfigDir().resolve("oneblox");
            cfg = load("config.json", Cfg.class, new Cfg());
            data = load("data.json", Data.class, new Data());
            save("config.json", cfg);
            voidDim = false;
            for (var lvl : server.getAllLevels()) if (String.valueOf(lvl.dimension()).contains("oneblox:void")) voidDim = true;
            voidWorld = false;
            if (!voidDim) {
                var ow = server.overworld();
                voidWorld = ow.getBlockState(new BlockPos(1000, 40, 1000)).isAir()
                    && ow.getBlockState(new BlockPos(1000, 0, 1000)).isAir()
                    && ow.getBlockState(new BlockPos(500, 20, 500)).isAir();
            }
            String mode = modeName();
            if (data.ver != 4 || !mode.equals(data.mode)) { data = new Data(); data.ver = 4; data.mode = mode; }
            System.out.println("[Oneblox] Mode: " + (voidDim ? "own void dimension" : voidWorld ? "void overworld (Superflat void)" : "FALLBACK - floating islands in the normal overworld (oneblox:void did not load)"));
            buildHub();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> saveAll());

        ServerTickEvents.END_SERVER_TICK.register(s -> {
            server = s;
            ticks++;
            for (Task t : new ArrayList<>(TASKS)) {
                if (--t.t <= 0) { TASKS.remove(t); try { t.r.run(); } catch (Exception e) { e.printStackTrace(); } }
            }
            if (ticks % 20 == 0) everySecond();
            if (ticks % 6000 == 0) saveAll();
        });

        // chat with ranks (the Owner rank is shiny)
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            String text = message.decoratedContent().getString();
            boolean owner = rankId(sender).equals("owner");
            server.getPlayerList().broadcastSystemMessage(Component.literal(
                prefix(sender) + (owner ? "\u00a7e" : "\u00a77") + sender.getName().getString() + " \u00a78\u00bb \u00a7f" + text), false);
            return false;
        });

        // breaking blocks: protection, spawner return, money, OneBlock
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, be) -> {
            if (!(player instanceof ServerPlayer sp)) return true;
            boolean ok = isAdmin(sp) || !inVoid(sp) || canBuild(sp, pos);
            Isl mine = islandOf(sp.getStringUUID());
            if (ok && mine != null && inVoid(sp) && pos.getX() == cx(mine.idx) && pos.getZ() == cz(mine.idx) && pos.getY() == Y() - 1)
                ok = false;   // the obsidian under the OneBlock stays
            if (ok && !sp.isCreative() && BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().equals("spawner"))
                returnSpawner(sp, pos);
            return ok;
        });
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
            if (!(player instanceof ServerPlayer sp)) return;
            Isl is = islandOf(sp.getStringUUID());
            boolean one = is != null && inVoid(sp) && pos.getX() == cx(is.idx) && pos.getY() == Y() && pos.getZ() == cz(is.idx);
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
            pay(sp, blockPay(id, one));
            if (!one) return;
            is.broken++;
            if (is.broken % cfg.blocksPerPhase == 0 && is.broken / cfg.blocksPerPhase < PHASE_NAME.length)
                tellIsland(is, "\u00a76New phase reached: " + PHASE_NAME[phase(is)] + "!");
            if (is.broken % 100 == 0 && RNG.nextInt(100) < 30) { addKey(sp, "Panda", 1); msg(sp, "\u00a7dYou found a Panda key!"); }
            Isl fis = is;
            placeNext(is);                    // instantly, in the same tick
            later(1, () -> placeNext(fis));   // second try in case the block was not empty yet ("keep" makes it harmless)
            if (RNG.nextInt(100) < cfg.mobChance) {
                String[] m = PHASE_MOBS[phase(is)];
                runIn("summon minecraft:" + m[RNG.nextInt(m.length)] + " " + (cx(is.idx) + 0.5) + " " + (Y() + 1) + " " + (cz(is.idx) + 0.5));
            }
        });

        // hunting
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(source.getEntity() instanceof ServerPlayer sp) || !(entity instanceof Mob)) return;
            pay(sp, Math.max(1L, Math.round(entity.getMaxHealth() * cfg.mobPayPerHealth)));
        });

        CommandRegistrationCallback.EVENT.register((d, access, env) -> registerCommands(d));
    }

    // =====================================================================
    //  PER-SECOND WORK: islands, kit, fishing, perks, border, sidebar
    // =====================================================================
    static void perks(ServerPlayer p) {
        String id = p.getStringUUID();
        String r = rankId(p);
        if (!p.isCreative() && !p.isSpectator()) {
            boolean fly = cfg.flyRanks.contains(r);
            if (fly != FLY.contains(id)) {
                setFly(p, fly);
                if (fly) FLY.add(id); else FLY.remove(id);
            }
        }
        if (r.equals("owner") && ticks % 200 == 0) {
            for (String e : cfg.ownerEffects) {
                String[] a = e.split(":");
                if (a.length == 2) run("effect give " + id + " minecraft:" + a[0] + " 30 " + a[1] + " true");
            }
        }
    }

    static void everySecond() {
        Set<String> online = new HashSet<>();
        for (ServerPlayer p : new ArrayList<>(server.getPlayerList().getPlayers())) {
            String id = p.getStringUUID();
            online.add(id);
            data.names.put(id, p.getName().getString());
            if (cfg.ownerUuid.isEmpty() && p.getName().getString().equals(cfg.owner)) { cfg.ownerUuid = id; save("config.json", cfg); }
            Isl is = islandOf(id);
            if (is == null) { createIsland(p); continue; }
            if (ticks % 100 == 0 && is.ready) {
                placeNext(is);
                runIn("setblock " + cx(is.idx) + " " + (Y() - 1) + " " + cz(is.idx) + " minecraft:obsidian keep");
            }
            if (is.ready && data.kit.add(id)) giveKit(p);

            int fish = p.getStats().getValue(Stats.CUSTOM.get(Stats.FISH_CAUGHT));
            Integer last = LAST_FISH.put(id, fish);
            if (last != null && fish > last) pay(p, (long) (fish - last) * cfg.fishPay);

            perks(p);
            enforce(p, is);
            border(p, is);
            sidebar(p, is);
        }
        SLOT.keySet().removeIf(k -> !online.contains(k));
        FLY.removeIf(k -> !online.contains(k));
    }

    // ---------- per-player world border (the normal blue/red vanilla border) ----------
    static boolean borderWarned = false;
    static void sendBorder(ServerPlayer p, double x, double z, double size) {
        try {
            Class<?> wbC = Class.forName("net.minecraft.world.level.border.WorldBorder");
            Object wb = wbC.getConstructor().newInstance();
            wbC.getMethod("setCenter", double.class, double.class).invoke(wb, x, z);
            wbC.getMethod("setSize", double.class).invoke(wb, size);
            Class<?> pk = Class.forName("net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket");
            Object pkt = pk.getConstructor(wbC).newInstance(wb);
            p.connection.send((Packet<?>) pkt);
        } catch (Throwable e) {
            if (!borderWarned) { borderWarned = true; System.out.println("[Oneblox] Could not send the border packet: " + e); }
        }
    }

    static void border(ServerPlayer p, Isl is) {
        if (!inVoid(p)) return;
        if (inHub(p.getX(), p.getZ())) sendBorder(p, data.hubX, data.hubZ, cfg.hubBorder * 2 + 1);
        else sendBorder(p, cx(is.idx) + 0.5, cz(is.idx) + 0.5, cfg.radius * 2 + 1);
    }

    // ---------- sidebar (one per player, using the 16 team-colour sidebar slots) ----------
    static final String[] COLORS = {"black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
            "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"};
    static final Map<String, Integer> SLOT = new HashMap<>();
    static final Map<String, List<String>> LINES = new HashMap<>();

    static int slotFor(ServerPlayer p) {
        String id = p.getStringUUID();
        Integer have = SLOT.get(id);
        if (have != null) return have;
        int s = 0;
        while (SLOT.containsValue(s) && s < 15) s++;
        SLOT.put(id, s);
        String c = COLORS[s];
        run("scoreboard objectives add ob_" + s + " dummy {text:\"ONEBLOX\",color:\"gold\",bold:true}");
        run("scoreboard objectives setdisplay sidebar.team." + c + " ob_" + s);
        run("team add ob_" + s);
        run("team modify ob_" + s + " color " + c);
        run("team join ob_" + s + " " + p.getName().getString());
        LINES.remove(id);
        return s;
    }

    static void sidebar(ServerPlayer p, Isl is) {
        int s = slotFor(p);
        String id = p.getStringUUID();
        int ph = phase(is);
        int into = is.broken - ph * cfg.blocksPerPhase;
        List<String> now = new ArrayList<>();
        now.add("\u00a76Money: \u00a7f" + fmt(bal(p)));
        now.add("\u00a7bPhase " + (ph + 1) + ": \u00a7f" + PHASE_NAME[ph]);
        now.add("\u00a7aBlocks: \u00a7f" + (ph >= PHASE_NAME.length - 1 ? String.valueOf(is.broken) : into + "/" + cfg.blocksPerPhase));
        now.add("\u00a7eRank: \u00a7f" + pretty(rankId(p)));
        now.add("\u00a7dMembers: \u00a7f" + is.members.size());
        now.add("\u00a77oneblox");
        List<String> old = LINES.getOrDefault(id, List.of());
        if (now.equals(old)) return;
        for (int i = 0; i < now.size(); i++)
            run("scoreboard players set \"" + now.get(i) + "\" ob_" + s + " " + (now.size() - i));
        for (String o : old) if (!now.contains(o)) run("scoreboard players reset \"" + o + "\" ob_" + s);
        LINES.put(id, now);
    }

    // =====================================================================
    //  COMMANDS
    // =====================================================================
    static void registerCommands(CommandDispatcher<CommandSourceStack> d) {
        Command<CommandSourceStack> menu = c -> { openMain(c.getSource().getPlayerOrException()); return 1; };
        d.register(Commands.literal("menu").executes(menu));
        d.register(Commands.literal("ob").executes(menu));
        d.register(Commands.literal("m").executes(menu));

        Command<CommandSourceStack> balCmd = c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            msg(p, "\u00a76Balance: \u00a7f" + fmt(bal(p)));
            return 1;
        };
        d.register(Commands.literal("bal").executes(balCmd));
        d.register(Commands.literal("money").executes(balCmd));

        Command<CommandSourceStack> topCmd = c -> { openTop(c.getSource().getPlayerOrException()); return 1; };
        d.register(Commands.literal("baltop").executes(topCmd));
        d.register(Commands.literal("top").executes(topCmd));

        d.register(Commands.literal("pay")
            .then(Commands.argument("player", EntityArgument.player())
            .then(Commands.argument("amount", LongArgumentType.longArg(1)).executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                ServerPlayer t = EntityArgument.getPlayer(c, "player");
                long a = LongArgumentType.getLong(c, "amount");
                if (bal(p) < a) { msg(p, "\u00a7cYou don't have enough money."); return 0; }
                add(p, -a); add(t, a);
                msg(p, "\u00a7aPaid " + fmt(a) + " to " + t.getName().getString());
                msg(t, "\u00a7a" + p.getName().getString() + " paid you " + fmt(a));
                return 1;
            }))));

        LiteralCommandNode<CommandSourceStack> spawnNode =
            d.register(Commands.literal("spawn").executes(c -> { toHub(c.getSource().getPlayerOrException()); return 1; }));
        d.register(Commands.literal("hub").redirect(spawnNode));

        d.register(Commands.literal("shop").executes(c -> { openShop(c.getSource().getPlayerOrException()); return 1; }));
        d.register(Commands.literal("sell").executes(c -> { openSell(c.getSource().getPlayerOrException()); return 1; }));
        d.register(Commands.literal("crates").executes(c -> { openCrates(c.getSource().getPlayerOrException()); return 1; }));
        d.register(Commands.literal("spawners").executes(c -> { openSpawners(c.getSource().getPlayerOrException()); return 1; }));
        d.register(Commands.literal("daily").executes(c -> { claimDaily(c.getSource().getPlayerOrException()); return 1; }));
        d.register(Commands.literal("buyspawner")
            .then(Commands.argument("mob", StringArgumentType.word()).executes(c -> {
                buySpawner(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "mob").toLowerCase());
                return 1;
            })));

        d.register(Commands.literal("ah")
            .executes(c -> { openAuction(c.getSource().getPlayerOrException(), 0); return 1; })
            .then(Commands.literal("sell").then(Commands.argument("price", LongArgumentType.longArg(1)).executes(c -> {
                sellOnAuction(c.getSource().getPlayerOrException(), LongArgumentType.getLong(c, "price"));
                return 1;
            }))));

        // ----- teleport requests -----
        d.register(Commands.literal("tpa").then(Commands.argument("player", EntityArgument.player()).executes(c -> {
            tpaRequest(c.getSource().getPlayerOrException(), EntityArgument.getPlayer(c, "player"));
            return 1;
        })));
        d.register(Commands.literal("tpaccept").executes(c -> { tpaAnswer(c.getSource().getPlayerOrException(), true); return 1; }));
        d.register(Commands.literal("tpdeny").executes(c -> { tpaAnswer(c.getSource().getPlayerOrException(), false); return 1; }));

        // ----- islands -----
        Command<CommandSourceStack> home = c -> { toHome(c.getSource().getPlayerOrException()); return 1; };
        Command<CommandSourceStack> panel = c -> { openIslandPanel(c.getSource().getPlayerOrException()); return 1; };

        LiteralArgumentBuilder<CommandSourceStack> is = Commands.literal("is").executes(home)
            .then(Commands.literal("go").executes(home))
            .then(Commands.literal("home").executes(home))
            .then(Commands.literal("panel").executes(panel))
            .then(Commands.literal("info").executes(panel))
            .then(Commands.literal("members").executes(c -> { openMembers(c.getSource().getPlayerOrException()); return 1; }))
            .then(Commands.literal("permission").executes(c -> { openPermissions(c.getSource().getPlayerOrException()); return 1; }))
            .then(Commands.literal("invite").then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                invite(c.getSource().getPlayerOrException(), EntityArgument.getPlayer(c, "player"));
                return 1;
            })))
            .then(Commands.literal("accept").executes(c -> { acceptInvite(c.getSource().getPlayerOrException()); return 1; }))
            .then(Commands.literal("leave").executes(c -> { leaveIsland(c.getSource().getPlayerOrException()); return 1; }))
            .then(Commands.literal("kick").then(Commands.argument("name", StringArgumentType.word()).executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                Isl i = islandOf(p.getStringUUID());
                String name = StringArgumentType.getString(c, "name");
                if (i != null) for (String m : new ArrayList<>(i.members))
                    if (name.equalsIgnoreCase(data.names.get(m))) { kickMember(p, m); return 1; }
                msg(p, "\u00a7cNo such member.");
                return 0;
            })));
        LiteralCommandNode<CommandSourceStack> node = d.register(is);
        d.register(Commands.literal("island").redirect(node));

        // ----- admin (owner only) -----
        d.register(Commands.literal("admin").executes(c -> { openAdmin(c.getSource().getPlayerOrException()); return 1; }));
        d.register(Commands.literal("oneblox")
            .then(Commands.literal("setspawn").executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                if (!isAdmin(p)) { msg(p, "\u00a7cAdmins only."); return 0; }
                data.hubSet = true; data.hubX = p.getX(); data.hubY = p.getY(); data.hubZ = p.getZ();
                saveAll();
                msg(p, "\u00a7aHub set here.");
                return 1;
            }))
            .then(Commands.literal("kit").executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                if (!isAdmin(p)) { msg(p, "\u00a7cAdmins only."); return 0; }
                giveKit(p);
                return 1;
            }))
            .then(Commands.literal("mode").executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                msg(p, "\u00a7eOneblox mode: " + modeName() + " (void = own dimension, flat = void overworld, sky = fallback)");
                return 1;
            }))
            .then(Commands.literal("rank")
                .then(Commands.argument("player", EntityArgument.player())
                .then(Commands.argument("rank", StringArgumentType.word()).executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    if (!isAdmin(p)) { msg(p, "\u00a7cAdmins only."); return 0; }
                    ServerPlayer t = EntityArgument.getPlayer(c, "player");
                    String r = StringArgumentType.getString(c, "rank").toLowerCase();
                    if (!cfg.ranks.containsKey(r)) { msg(p, "\u00a7cRanks: " + String.join(", ", cfg.ranks.keySet())); return 0; }
                    data.rank.put(t.getStringUUID(), r);
                    saveAll();
                    msg(p, "\u00a7a" + t.getName().getString() + " is now " + pretty(r));
                    return 1;
                }))))
            .then(Commands.literal("addmoney")
                .then(Commands.argument("player", EntityArgument.player())
                .then(Commands.argument("amount", LongArgumentType.longArg()).executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    if (!isAdmin(p)) { msg(p, "\u00a7cAdmins only."); return 0; }
                    ServerPlayer t = EntityArgument.getPlayer(c, "player");
                    add(t, LongArgumentType.getLong(c, "amount"));
                    msg(p, "\u00a7aDone. " + t.getName().getString() + " now has " + fmt(bal(t)));
                    return 1;
                })))));
    }
}
