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
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
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
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
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
        List<BM> blackMarket = new ArrayList<>(List.of(
            new BM("item", "netherite_ingot", 1, "gems", 40), new BM("item", "nether_star", 1, "gems", 60), new BM("item", "elytra", 1, "gems", 120),
            new BM("item", "totem_of_undying", 1, "gems", 50), new BM("item", "enchanted_golden_apple", 2, "gems", 30), new BM("item", "diamond_block", 4, "gems", 25),
            new BM("item", "beacon", 1, "gems", 80), new BM("spawner", "blaze", 1, "gems", 90), new BM("spawner", "enderman", 1, "gems", 70),
            new BM("spawner", "iron_golem", 1, "gems", 110), new BM("key", "Mythic", 1, "gems", 25), new BM("key", "Infinity", 2, "gems", 20),
            new BM("item", "experience_bottle", 64, "coins", 200), new BM("item", "golden_carrot", 64, "coins", 150),
            new BM("item", "ender_pearl", 16, "coins", 120), new BM("item", "shulker_box", 1, "coins", 300)));

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

    static class Mail { String kind, payload, label; }          // kind: "stack" (item json) or "cmd" (give command with %P%)
    static class BM {                                            // black market offer
        String type, value, cur; int amount; long price;       // type: item | spawner | key   cur: gems | coins
        BM(String type, String value, int amount, String cur, long price) { this.type = type; this.value = value; this.amount = amount; this.cur = cur; this.price = price; }
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
        Map<String, String> crateAt = new LinkedHashMap<>();   // crate name -> "x,y,z" of its shulker box at spawn
        Map<String, Long> money = new HashMap<>();
        Map<String, Long> coins = new HashMap<>();
        Map<String, Long> gems = new HashMap<>();
        Map<String, List<Mail>> mail = new HashMap<>();
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
    static long coinsOf(ServerPlayer p) { return data.coins.getOrDefault(p.getStringUUID(), 0L); }
    static long gemsOf(ServerPlayer p) { return data.gems.getOrDefault(p.getStringUUID(), 0L); }
    static void addCoins(ServerPlayer p, long a) { data.coins.merge(p.getStringUUID(), a, Long::sum); }
    static void addGems(ServerPlayer p, long a) { data.gems.merge(p.getStringUUID(), a, Long::sum); }
    static String shortNum(long v) {
        if (v >= 1_000_000_000L) return String.format(Locale.US, "%.2fB", v / 1e9);
        if (v >= 1_000_000L) return String.format(Locale.US, "%.2fM", v / 1e6);
        if (v >= 10_000L) return String.format(Locale.US, "%.1fK", v / 1e3);
        return String.format(Locale.US, "%,d", v);
    }
    static String pretty(String id) {
        StringBuilder sb = new StringBuilder();
        for (String w : id.split("_")) { if (w.isEmpty()) continue; if (sb.length() > 0) sb.append(' '); sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)); }
        return sb.toString();
    }

    static void pay(ServerPlayer p, long a) {
        if (a <= 0) return;
        add(p, a);
        msg(p, "\u00a7a+" + fmt(a));
        soundAt(p, "entity.experience_orb.pickup", 0.25, 1.3);
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

    static void giveStack(ServerPlayer p, ItemStack st) { p.getInventory().add(st.copy()); }

    static boolean hasSpace(ServerPlayer p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < 36 && i < inv.getContainerSize(); i++) if (inv.getItem(i).isEmpty()) return true;
        return false;
    }

    /** Shift-click? (looked up by name so a renamed enum constant can't break the build) */
    static boolean shift(Object click) { return String.valueOf(click).contains("QUICK_MOVE"); }

    /** Finds an item by field name (first match wins), or returns the fallback if this version has none of them. */
    static Item item(Item fallback, String... names) {
        for (String n : names) {
            try { Object o = Items.class.getField(n).get(null); if (o instanceof Item it) return it; } catch (Throwable ignored) { }
        }
        return fallback;
    }
    static void soundAt(ServerPlayer p, String s, double vol, double pitch) {
        run("execute as " + p.getName().getString() + " at @s run playsound minecraft:" + s + " master @s ~ ~ ~ " + vol + " " + pitch);
    }
    static void sound(ServerPlayer p, String s) { soundAt(p, s, 1.0, 1.0); }

    static void celebrate(ServerPlayer p, String title, String sub) {
        String n = p.getName().getString();
        run("title " + n + " times 5 50 15");
        run("title " + n + " subtitle \"" + sub + "\"");
        run("title " + n + " title \"" + title + "\"");
        soundAt(p, "entity.player.levelup", 1.0, 1.0);
        run("execute as " + n + " at @s run particle minecraft:firework ~ ~1 ~ 0.6 0.6 0.6 0.1 40");
    }

    // ---- delivery: items go to the inventory, or to /mailbox when it is full (never dropped) ----
    static void addMail(String uuid, String kind, String payload, String label) {
        Mail m = new Mail();
        m.kind = kind; m.payload = payload; m.label = label;
        data.mail.computeIfAbsent(uuid, k -> new ArrayList<>()).add(m);
    }
    /** tmpl is a give command with %P% where the player name goes, e.g. "give %P% minecraft:diamond 8". */
    static void deliverGive(ServerPlayer p, String tmpl, String label) {
        if (hasSpace(p)) run(tmpl.replace("%P%", p.getName().getString()));
        else { addMail(p.getStringUUID(), "cmd", tmpl, label); msg(p, "\u00a7eInventory full - " + label + " was sent to /mailbox"); }
    }
    static void deliverStack(ServerPlayer p, ItemStack st, String label) {
        if (hasSpace(p)) { giveStack(p, st); return; }
        String j = ser(st);
        if (j != null) { addMail(p.getStringUUID(), "stack", j, label); msg(p, "\u00a7eInventory full - " + label + " was sent to /mailbox"); }
    }
    static boolean claimMail(ServerPlayer p, Mail m) {
        if (!hasSpace(p)) { msg(p, "\u00a7cFree an inventory slot first."); return false; }
        if (m.kind.equals("stack")) { ItemStack st = deser(m.payload); if (!st.isEmpty()) giveStack(p, st); }
        else run(m.payload.replace("%P%", p.getName().getString()));
        List<Mail> l = data.mail.get(p.getStringUUID());
        if (l != null) l.remove(m);
        return true;
    }
    static String uuidByName(String name) {
        for (Map.Entry<String, String> e : data.names.entrySet()) if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        return null;
    }
    static String spawnerGive(String mob) {
        return "give %P% minecraft:spawner[block_entity_data={id:\"minecraft:mob_spawner\",SpawnData:{entity:{id:\"minecraft:" + mob + "\"}}}] 1";
    }
    /** Looks an item up by id ("diamond_block"): first through the registry, then through the Items class. null if unknown. */
    static Item itemById(String id) {
        try {
            Class<?> idC = Class.forName("net.minecraft.resources.Identifier");
            Object key = idC.getMethod("parse", String.class).invoke(null, "minecraft:" + id);
            Object reg = BuiltInRegistries.ITEM;
            Object o = reg.getClass().getMethod("getValue", idC).invoke(reg, key);
            if (o instanceof Item it && it != Items.AIR) return it;
        } catch (Throwable ignored) { }
        try { Object o = Items.class.getField(id.toUpperCase(Locale.ROOT)).get(null); return o instanceof Item it && it != Items.AIR ? it : null; }
        catch (Throwable e) { return null; }
    }

    /** Sets a data component by field name through reflection, so a missing/renamed component can never break the build. */
    static void comp(ItemStack s, String field, Object val) {
        try {
            Object type = DataComponents.class.getField(field).get(null);
            ItemStack.class.getMethod("set", Class.forName("net.minecraft.core.component.DataComponentType"), Object.class).invoke(s, type, val);
        } catch (Throwable ignored) { }
    }
    static Item itemOr(String id, Item fallback) { Item it = itemById(id); return it == null ? fallback : it; }

    // ---- the menu item in the last hotbar slot ----
    static ItemStack menuItem() { return icon(Items.NETHER_STAR, "\u00a7aMenu \u00a77(Right Click)", "\u00a77Opens the Oneblox menu"); }
    static boolean isMenuItem(ItemStack st) { return !st.isEmpty() && st.getItem() == Items.NETHER_STAR && st.getHoverName().getString().contains("Menu"); }
    static void ensureMenuItem(ServerPlayer p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) if (isMenuItem(inv.getItem(i))) return;
        if (inv.getItem(8).isEmpty()) inv.setItem(8, menuItem());
        else if (hasSpace(p)) giveStack(p, menuItem());
    }

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
    interface Act { void run(ServerPlayer p, ContainerInput ct); }

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
            for (int i = 0; i < rows * 9; i++) if (box.getItem(i).isEmpty()) box.setItem(i, icon(item(Items.GLASS, "GRAY_STAINED_GLASS_PANE", "BLACK_STAINED_GLASS_PANE", "GLASS_PANE"), " "));
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
        public void clicked(int slot, int button, ContainerInput ct, Player player) {
            if (slot >= 0 && slot < g.rows * 9 && g.acts[slot] != null && player instanceof ServerPlayer sp) {
                LAST_BUTTON = button;
                soundAt(sp, "ui.button.click", 0.4, 1.2);
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
        Gui g = new Gui("\u00a78Oneblox", 4).fill();
        int mails = data.mail.getOrDefault(p.getStringUUID(), List.of()).size();
        g.set(4, icon(Items.GOLD_NUGGET, "\u00a76" + p.getName().getString(), "\u00a76Money: \u00a7f" + fmt(bal(p)), "\u00a7eCoins: \u00a7f" + shortNum(coinsOf(p)),
            "\u00a7dGems: \u00a7f" + shortNum(gemsOf(p)), "\u00a77Rank: \u00a7f" + pretty(rankId(p))), null);
        g.set(10, icon(Items.GRASS_BLOCK, "\u00a7aMy island", "\u00a77Click to teleport"), (pl, ct) -> { pl.closeContainer(); toHome(pl); });
        g.set(11, icon(Items.EMERALD, "\u00a7aShop", "\u00a77Buy blocks, food and tools"), (pl, ct) -> openShop(pl));
        g.set(12, icon(Items.GOLD_INGOT, "\u00a76Sell", "\u00a77Sell your items for money"), (pl, ct) -> openSell(pl));
        g.set(13, icon(Items.CHEST, "\u00a7eAuction house", "\u00a77Buy and sell with other players"), (pl, ct) -> openAuction(pl, 0));
        g.set(14, icon(Items.ENDER_CHEST, "\u00a7dCrates", "\u00a77Open crates with keys"), (pl, ct) -> openCrates(pl));
        g.set(15, icon(Items.SPAWNER, "\u00a7cSpawners", "\u00a77Buy mob spawners"), (pl, ct) -> openSpawners(pl));
        g.set(16, icon(Items.NETHER_STAR, "\u00a75Black market", "\u00a77Rare items for gems and coins", "\u00a77Stock changes every hour"), (pl, ct) -> openBlackMarket(pl));
        g.set(19, icon(Items.CLOCK, "\u00a7bDaily reward", "\u00a77Money, coins, gems and a key"), (pl, ct) -> claimDaily(pl));
        g.set(20, icon(Items.BARREL, "\u00a7fMailbox", mails > 0 ? "\u00a7aYou have " + mails + " item(s)!" : "\u00a77Empty", "\u00a78Gifts and items that did not fit"), (pl, ct) -> openMailbox(pl));
        g.set(21, icon(Items.BOOK, "\u00a7fLeaderboards", "\u00a77Richest players and top islands"), (pl, ct) -> openTop(pl));
        g.set(22, icon(Items.PLAYER_HEAD, "\u00a7fIsland panel", "\u00a77Members and permissions"), (pl, ct) -> openIslandPanel(pl));
        g.set(23, icon(Items.COMPASS, "\u00a7fHub", "\u00a77Go to spawn"), (pl, ct) -> { pl.closeContainer(); toHub(pl); });
        if (isAdmin(p)) g.set(25, icon(Items.COMMAND_BLOCK, "\u00a74Admin panel", "\u00a77Owner only"), (pl, ct) -> openAdmin(pl));
        g.open(p);
    }

    // =====================================================================
    //  SHOP + SELL
    // =====================================================================
    // =====================================================================
    //  SHOP: categories with pages, every survival block and item
    //  Left click buys 1, right click buys 16, shift-click buys 64.
    // =====================================================================
    static final String[] COLORS16 = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
    static final String[] WOODS = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak"};
    static int LAST_BUTTON = 0;

    static class Cat {
        String name, icon; List<String[]> items = new ArrayList<>(); List<String[]> vis;
        Cat(String name, String icon) { this.name = name; this.icon = icon; }
    }
    static List<Cat> SHOP;

    static void cat(List<Cat> out, String name, String icon, String list) {
        Cat c = new Cat(name, icon);
        for (String e : list.split(",")) {
            e = e.trim();
            if (e.isEmpty()) continue;
            String[] kv = e.split(":");
            c.items.add(new String[]{kv[0], kv[1]});
        }
        out.add(c);
    }

    /** "white_wool:10,orange_wool:10,..." for one colored block type */
    static String colored(String suffix, long price) {
        StringBuilder sb = new StringBuilder();
        for (String c : COLORS16) sb.append(c).append('_').append(suffix).append(':').append(price).append(',');
        return sb.toString();
    }

    static List<Cat> buildShop() {
        List<Cat> out = new ArrayList<>();

        cat(out, "Building Blocks", "stone_bricks",
            "stone:5,cobblestone:3,smooth_stone:8,stone_bricks:8,mossy_stone_bricks:10,cracked_stone_bricks:8,chiseled_stone_bricks:10,mossy_cobblestone:8,"
            + "andesite:6,polished_andesite:8,diorite:6,polished_diorite:8,granite:6,polished_granite:8,calcite:20,tuff:6,polished_tuff:8,tuff_bricks:10,"
            + "dripstone_block:15,deepslate:8,cobbled_deepslate:6,polished_deepslate:10,deepslate_bricks:12,deepslate_tiles:12,chiseled_deepslate:14,"
            + "bricks:20,mud_bricks:12,packed_mud:8,mud:4,sandstone:10,smooth_sandstone:12,cut_sandstone:12,chiseled_sandstone:14,red_sandstone:12,"
            + "smooth_red_sandstone:14,cut_red_sandstone:14,chiseled_red_sandstone:16,prismarine:20,prismarine_bricks:30,dark_prismarine:35,sea_lantern:100,"
            + "quartz_block:60,chiseled_quartz_block:65,quartz_pillar:65,smooth_quartz:65,quartz_bricks:70,purpur_block:30,purpur_pillar:32,"
            + "end_stone:10,end_stone_bricks:15,nether_bricks:15,red_nether_bricks:20,chiseled_nether_bricks:18,cracked_nether_bricks:16,"
            + "blackstone:10,polished_blackstone:14,polished_blackstone_bricks:16,chiseled_polished_blackstone:18,gilded_blackstone:60,"
            + "basalt:8,polished_basalt:10,smooth_basalt:10,obsidian:60,crying_obsidian:120,glowstone:60,shroomlight:40,terracotta:12,bone_block:20,"
            + "glass:6,tinted_glass:40,ice:20,packed_ice:40,blue_ice:80,snow_block:8,clay:10,honeycomb_block:50,copper_block:80,cut_copper:85,"
            + "chiseled_copper:90,exposed_copper:80,weathered_copper:80,oxidized_copper:80,copper_grate:90,copper_bulb:100,netherrack:3,magma_block:25,"
            + "soul_sand:6,soul_soil:6,sculk:25");

        StringBuilder wood = new StringBuilder();
        for (String w : WOODS)
            wood.append(w).append("_log:8,stripped_").append(w).append("_log:9,").append(w).append("_wood:9,stripped_").append(w)
                .append("_wood:10,").append(w).append("_planks:3,");
        wood.append("bamboo_block:10,stripped_bamboo_block:11,bamboo_planks:3,bamboo_mosaic:5,crimson_stem:12,stripped_crimson_stem:13,crimson_hyphae:13,"
            + "crimson_planks:4,warped_stem:12,stripped_warped_stem:13,warped_hyphae:13,warped_planks:4,nether_wart_block:12,warped_wart_block:12");
        cat(out, "Wood", "oak_log", wood.toString());

        cat(out, "Wool & Terracotta", "white_wool", colored("wool", 10) + colored("terracotta", 12) + colored("glazed_terracotta", 25));
        cat(out, "Concrete & Glass", "white_concrete", colored("concrete", 12) + colored("concrete_powder", 12) + colored("stained_glass", 8) + colored("dye", 8));

        cat(out, "Ores & Minerals", "diamond_ore",
            "coal_ore:15,deepslate_coal_ore:18,iron_ore:40,deepslate_iron_ore:45,copper_ore:25,deepslate_copper_ore:28,gold_ore:80,deepslate_gold_ore:90,"
            + "redstone_ore:35,deepslate_redstone_ore:40,lapis_ore:40,deepslate_lapis_ore:45,diamond_ore:250,deepslate_diamond_ore:280,emerald_ore:300,"
            + "deepslate_emerald_ore:320,nether_quartz_ore:30,nether_gold_ore:35,ancient_debris:1200,raw_iron:20,raw_copper:10,raw_gold:40,coal:10,charcoal:8,"
            + "iron_ingot:30,copper_ingot:15,gold_ingot:60,iron_nugget:4,gold_nugget:7,diamond:200,emerald:250,lapis_lazuli:15,redstone:10,quartz:12,"
            + "amethyst_shard:20,netherite_scrap:1000,netherite_ingot:4500,coal_block:90,iron_block:270,gold_block:540,redstone_block:90,lapis_block:135,"
            + "diamond_block:1800,emerald_block:2250,netherite_block:40000,raw_iron_block:180,raw_copper_block:90,raw_gold_block:360,amethyst_block:80");

        StringBuilder nat = new StringBuilder(
            "dirt:3,grass_block:6,podzol:8,mycelium:20,coarse_dirt:4,rooted_dirt:8,moss_block:12,sand:3,red_sand:4,gravel:3,sponge:150,cobweb:20,vine:6,"
            + "lily_pad:8,sugar_cane:12,bamboo:6,cactus:8,kelp:5,seagrass:4,sea_pickle:15,tube_coral_block:20,brain_coral_block:20,bubble_coral_block:20,"
            + "fire_coral_block:20,horn_coral_block:20,dandelion:5,poppy:5,blue_orchid:8,allium:8,azure_bluet:8,red_tulip:8,orange_tulip:8,white_tulip:8,"
            + "pink_tulip:8,oxeye_daisy:8,cornflower:8,lily_of_the_valley:10,sunflower:10,lilac:10,rose_bush:10,peony:10,torchflower:30,pitcher_plant:30,"
            + "wither_rose:200,spore_blossom:50,azalea:20,flowering_azalea:25,fern:4,large_fern:6,short_grass:3,tall_grass:5,dead_bush:4,red_mushroom:10,"
            + "brown_mushroom:10,red_mushroom_block:15,brown_mushroom_block:15,mushroom_stem:15,crimson_fungus:12,warped_fungus:12,crimson_roots:6,"
            + "warped_roots:6,nether_sprouts:6,weeping_vines:10,twisting_vines:10,glow_lichen:10,hanging_roots:10,big_dripleaf:15,small_dripleaf:12,"
            + "chorus_flower:60,chorus_plant:30,pointed_dripstone:20,moss_carpet:6,pale_moss_block:15,");
        for (String w : WOODS) nat.append(w).append("_leaves:3,");
        nat.append("azalea_leaves:3,flowering_azalea_leaves:4,");
        for (String w : WOODS) nat.append(w.equals("mangrove") ? "mangrove_propagule" : w + "_sapling").append(":15,");
        cat(out, "Nature", "oak_sapling", nat.toString());

        cat(out, "Farming", "wheat",
            "wheat_seeds:5,wheat:10,carrot:8,potato:8,beetroot_seeds:6,beetroot:8,melon_seeds:10,melon_slice:4,melon:30,pumpkin_seeds:10,pumpkin:20,"
            + "carved_pumpkin:25,sweet_berries:8,glow_berries:10,cocoa_beans:10,nether_wart:20,bone_meal:20,hay_block:90,egg:10,sugar:6,milk_bucket:60,"
            + "water_bucket:150,bucket:100,lava_bucket:300,honey_bottle:40,honeycomb:25,composter:30,iron_hoe:100,diamond_hoe:700,shears:120");

        cat(out, "Food", "cooked_beef",
            "apple:12,bread:6,cooked_beef:12,beef:5,cooked_porkchop:12,porkchop:5,cooked_chicken:10,chicken:4,cooked_mutton:10,mutton:4,cooked_rabbit:10,"
            + "rabbit:4,cooked_cod:8,cod:3,cooked_salmon:9,salmon:4,tropical_fish:6,pufferfish:8,baked_potato:8,poisonous_potato:3,golden_carrot:60,"
            + "golden_apple:600,enchanted_golden_apple:6000,cake:100,pumpkin_pie:25,cookie:4,dried_kelp:3,mushroom_stew:15,rabbit_stew:30,beetroot_soup:18,"
            + "chorus_fruit:30");

        cat(out, "Mob Drops", "ender_pearl",
            "leather:20,feather:8,string:10,slime_ball:30,spider_eye:12,gunpowder:25,blaze_rod:80,blaze_powder:40,ender_pearl:300,ender_eye:420,bone:10,"
            + "rotten_flesh:3,ink_sac:15,glow_ink_sac:30,nautilus_shell:400,scute:100,rabbit_hide:15,rabbit_foot:100,phantom_membrane:80,ghast_tear:200,"
            + "magma_cream:45,prismarine_shard:25,prismarine_crystals:40,shulker_shell:600,fermented_spider_eye:20,glistering_melon_slice:30,"
            + "breeze_rod:120,experience_bottle:150,name_tag:200,saddle:500,lead:40");

        cat(out, "Redstone", "redstone",
            "redstone:10,redstone_torch:12,redstone_block:90,repeater:40,comparator:60,piston:80,sticky_piston:120,observer:80,hopper:100,dropper:70,"
            + "dispenser:90,lever:8,stone_button:6,oak_button:5,polished_blackstone_button:8,stone_pressure_plate:10,oak_pressure_plate:8,"
            + "light_weighted_pressure_plate:60,heavy_weighted_pressure_plate:35,polished_blackstone_pressure_plate:14,tripwire_hook:20,target:50,"
            + "daylight_detector:70,note_block:40,jukebox:200,tnt:80,redstone_lamp:60,lightning_rod:60,sculk_sensor:120,calibrated_sculk_sensor:200,"
            + "slime_block:150,honey_block:120,rail:10,powered_rail:40,detector_rail:30,activator_rail:30,minecart:60,chest_minecart:90,hopper_minecart:160,"
            + "furnace_minecart:80,tnt_minecart:130,iron_door:80,iron_trapdoor:70,oak_door:8,oak_trapdoor:8,oak_fence_gate:8,bell:300,lectern:80,crafter:200,"
            + "trapped_chest:50");

        cat(out, "Utility", "crafting_table",
            "chest:30,barrel:25,ender_chest:600,shulker_box:300,furnace:20,blast_furnace:120,smoker:80,crafting_table:10,smithing_table:60,"
            + "fletching_table:40,cartography_table:40,loom:50,stonecutter:60,grindstone:50,anvil:400,enchanting_table:1500,brewing_stand:300,cauldron:200,"
            + "bookshelf:90,chiseled_bookshelf:100,ladder:5,scaffolding:20,torch:3,soul_torch:4,lantern:40,soul_lantern:45,campfire:50,soul_campfire:55,"
            + "candle:15,painting:20,item_frame:15,glow_item_frame:35,armor_stand:40,flower_pot:10,compass:50,clock:50,spyglass:100,fishing_rod:80,"
            + "flint_and_steel:40,oak_boat:20,book:20,writable_book:60,paper:3,white_bed:50,red_bed:50,blue_bed:50,black_bed:50,safari_net:25000,"
            + "end_crystal:2500,respawn_anchor:800,lodestone:900,beacon:12000,conduit:6000");

        cat(out, "Tools & Combat", "diamond_sword",
            "wooden_sword:20,wooden_pickaxe:20,wooden_axe:20,wooden_shovel:10,wooden_hoe:10,stone_sword:50,stone_pickaxe:50,stone_axe:50,stone_shovel:25,"
            + "stone_hoe:25,iron_sword:300,iron_pickaxe:250,iron_axe:250,iron_shovel:120,iron_hoe:100,golden_sword:200,golden_pickaxe:200,golden_axe:200,"
            + "golden_shovel:100,golden_hoe:100,diamond_sword:3000,diamond_pickaxe:2500,diamond_axe:2500,diamond_shovel:1200,diamond_hoe:700,"
            + "netherite_sword:12000,netherite_pickaxe:11000,netherite_axe:11000,netherite_shovel:9000,netherite_hoe:8000,bow:100,crossbow:150,arrow:5,"
            + "spectral_arrow:15,shield:100,trident:5000,leather_helmet:60,leather_chestplate:100,leather_leggings:80,leather_boots:50,chainmail_helmet:200,"
            + "chainmail_chestplate:320,chainmail_leggings:280,chainmail_boots:160,iron_helmet:500,iron_chestplate:800,iron_leggings:700,iron_boots:400,"
            + "golden_helmet:400,golden_chestplate:640,golden_leggings:560,golden_boots:320,diamond_helmet:4000,diamond_chestplate:6400,"
            + "diamond_leggings:5600,diamond_boots:3200,netherite_helmet:16000,netherite_chestplate:24000,netherite_leggings:20000,netherite_boots:14000");
        return out;
    }

    /** Items this game version really has (unknown ids are hidden instead of failing at purchase time). */
    static List<String[]> visible(Cat c) {
        if (c.vis != null) return c.vis;
        boolean lookupWorks = itemById("stone") != null;
        List<String[]> v = new ArrayList<>();
        for (String[] e : c.items) if (!lookupWorks || e[0].equals("safari_net") || itemById(e[0]) != null) v.add(e);
        c.vis = v;
        return v;
    }

    static void openShop(ServerPlayer p) {
        if (SHOP == null) SHOP = buildShop();
        Gui g = new Gui("\u00a78Shop  -  " + fmt(bal(p)), 4).fill();
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        for (int i = 0; i < SHOP.size() && i < slots.length; i++) {
            Cat c = SHOP.get(i);
            int ci = i;
            g.set(slots[i], icon(itemOr(c.icon, Items.CHEST), "\u00a7a" + c.name, "\u00a77" + visible(c).size() + " items", "\u00a78Click to browse"),
                (pl, ct) -> openShopCat(pl, ci, 0));
        }
        g.set(31, backIcon(), (pl, ct) -> openMain(pl));
        g.open(p);
    }

    static void openShopCat(ServerPlayer p, int ci, int page) {
        if (SHOP == null) SHOP = buildShop();
        Cat c = SHOP.get(ci);
        List<String[]> list = visible(c);
        int per = 45;
        int pages = Math.max(1, (list.size() + per - 1) / per);
        int pg = Math.max(0, Math.min(page, pages - 1));
        Gui g = new Gui("\u00a78" + c.name + "  (" + (pg + 1) + "/" + pages + ")  -  " + fmt(bal(p)), 6).fill();
        for (int i = 0; i < per; i++) {
            int idx = pg * per + i;
            if (idx >= list.size()) break;
            String id = list.get(idx)[0];
            long price = Long.parseLong(list.get(idx)[1]);
            ItemStack show = id.equals("safari_net") ? safariNet() : icon(itemOr(id, Items.CHEST), "\u00a7f" + pretty(id));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.literal("\u00a7r\u00a77Price: \u00a7a" + fmt(price) + " each"));
            lore.add(Component.literal("\u00a7r\u00a78Left: x1   Right: x16   Shift: x64"));
            show.set(DataComponents.LORE, new ItemLore(lore));
            g.set(i, show, (pl, ct) -> buyItem(pl, id, price, shift(ct) ? 64 : LAST_BUTTON == 1 ? 16 : 1));
        }
        g.set(45, icon(Items.ARROW, "\u00a7ePrevious page"), (pl, ct) -> openShopCat(pl, ci, pg - 1));
        g.set(49, icon(Items.CHEST, "\u00a7eAll categories"), (pl, ct) -> openShop(pl));
        g.set(53, icon(Items.ARROW, "\u00a7eNext page"), (pl, ct) -> openShopCat(pl, ci, pg + 1));
        g.open(p);
    }

    static void buyItem(ServerPlayer p, String id, long price, int n) {
        if (id.equals("safari_net")) n = 1;
        long cost = price * n;
        if (bal(p) < cost) { msg(p, "\u00a7cYou need " + fmt(cost)); return; }
        add(p, -cost);
        if (id.equals("safari_net")) deliverStack(p, safariNet(), "Safari Net");
        else deliverGive(p, "give %P% minecraft:" + id + " " + n, n + "x " + pretty(id));
        msg(p, "\u00a7aBought " + n + "x " + pretty(id) + " for " + fmt(cost));
        soundAt(p, "entity.item.pickup", 0.6, 1.0);
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
            deliverStack(p, st, l.count + "x " + l.name);
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
        deliverStack(p, st, l.count + "x " + l.name);
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
    // ---- physical keys: a colored candle named "<Crate> Key" ----
    static ItemStack keyStack(String crate, int n) {
        String f = crate.equals("Panda") ? "black_candle" : crate.equals("Infinity") ? "light_blue_candle" : crate.equals("Mythic") ? "purple_candle" : "candle";
        ItemStack s = icon(itemOr(f, itemOr("candle", Items.STICK)), "\u00a7e\u00a7l" + crate + " Key",
            "\u00a77Right-click a \u00a7f" + crate + " Crate \u00a77at spawn", "\u00a77to open it.");
        s.setCount(n);
        comp(s, "ENCHANTMENT_GLINT_OVERRIDE", Boolean.TRUE);
        return s;
    }
    static boolean isKey(ItemStack st, String crate) {
        return !st.isEmpty() && st.getHoverName().getString().contains(crate + " Key")
            && BuiltInRegistries.ITEM.getKey(st.getItem()).getPath().contains("candle");
    }
    static int keyCount(ServerPlayer p, String crate) {
        Inventory inv = p.getInventory();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) if (isKey(inv.getItem(i), crate)) n += inv.getItem(i).getCount();
        return n;
    }
    static boolean takeKey(ServerPlayer p, String crate) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (isKey(st, crate)) { st.shrink(1); return true; }
        }
        return false;
    }
    /** Gives physical keys (to /mailbox if the inventory is full). */
    static void addKey(ServerPlayer p, String crate, int n) {
        if (n > 0) deliverStack(p, keyStack(crate, n), n + "x " + crate + " Key");
    }

    static String rewardName(Reward r) {
        switch (r.type) {
            case "money": return "\u00a7a" + fmt(r.amount);
            case "coins": return "\u00a7e" + r.amount + " Coins";
            case "gems": return "\u00a7d" + r.amount + " Gems";
            case "item": return "\u00a7f" + r.amount + "x " + pretty(r.value);
            case "spawner": return "\u00a7c" + pretty(r.value) + " Spawner";
            default: return "\u00a76" + r.amount + "x " + r.value + " Key";
        }
    }
    static ItemStack rewardIcon(Reward r) {
        Item it = r.type.equals("money") ? Items.EMERALD : r.type.equals("spawner") ? Items.SPAWNER : r.type.equals("key") ? Items.TRIPWIRE_HOOK
            : (r.type.equals("coins") || r.type.equals("gems")) ? Items.GOLD_NUGGET : itemOr(r.value, Items.CHEST);
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
        switch (r.type) {
            case "money": pay(p, r.amount); break;
            case "coins": addCoins(p, r.amount); break;
            case "gems": addGems(p, r.amount); break;
            case "item": deliverGive(p, "give %P% minecraft:" + r.value + " " + r.amount, r.amount + "x " + pretty(r.value)); break;
            case "spawner": deliverGive(p, spawnerGive(r.value), pretty(r.value) + " Spawner"); break;
            default: addKey(p, r.value, r.amount);
        }
    }

    // =====================================================================
    //  PHYSICAL CRATES (shulker boxes at spawn), candle keys and THE ROLL
    // =====================================================================
    static final String[] SHULKERS = {"black_shulker_box", "light_blue_shulker_box", "purple_shulker_box", "red_shulker_box", "lime_shulker_box"};
    static final Set<String> ROLLING = new HashSet<>();

    static int totalWeight(List<Reward> pool) { int t = 0; for (Reward r : pool) t += Math.max(1, r.weight); return t; }
    static double chance(Reward r, int total) { return 100.0 * Math.max(1, r.weight) / total; }
    static String rarityColor(Reward r, int total) { double c = chance(r, total); return c < 3 ? "\u00a76" : c < 8 ? "\u00a75" : c < 16 ? "\u00a79" : "\u00a77"; }
    static String rarityName(Reward r, int total) { double c = chance(r, total); return c < 3 ? "LEGENDARY" : c < 8 ? "EPIC" : c < 16 ? "RARE" : "COMMON"; }
    static String plainName(Reward r) { return rewardName(r).replaceAll("\u00a7.", ""); }
    static ItemStack rewardIcon(Reward r, int total) {
        Item it = r.type.equals("money") ? Items.EMERALD : r.type.equals("spawner") ? Items.SPAWNER : r.type.equals("key") ? itemOr("tripwire_hook", Items.STICK)
            : (r.type.equals("coins") || r.type.equals("gems")) ? Items.GOLD_NUGGET : itemOr(r.value, Items.CHEST);
        String col = rarityColor(r, total);
        ItemStack s = icon(it, col + plainName(r), col + rarityName(r, total), String.format(Locale.US, "\u00a78%.1f%% chance", chance(r, total)));
        if (chance(r, total) < 8) comp(s, "ENCHANTMENT_GLINT_OVERRIDE", Boolean.TRUE);
        return s;
    }

    static int[] crateXYZ(String crate) {
        String v = data.crateAt.get(crate);
        if (v == null) return null;
        String[] a = v.split(",");
        return new int[]{Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2])};
    }

    /** Places the crate shulker boxes in a row, each with a floating label. x,y,z is the middle crate. */
    static void buildCrates(int x, int y, int z) {
        runIn("forceload add " + (x - 12) + " " + (z - 12) + " " + (x + 12) + " " + (z + 12));
        later(40, () -> {
            runIn("kill @e[type=minecraft:text_display,tag=oneblox_label]");
            int i = 0;
            for (String crate : cfg.crates.keySet()) {
                if (i >= 3) break;
                int cx = x + (i - 1) * 4;
                runIn("setblock " + cx + " " + (y - 1) + " " + z + " minecraft:gold_block");
                runIn("setblock " + cx + " " + y + " " + z + " minecraft:" + SHULKERS[i]);
                runIn("summon minecraft:text_display " + (cx + 0.5) + " " + (y + 1.9) + " " + (z + 0.5)
                    + " {Tags:[\"oneblox_label\"],billboard:\"center\",text:{text:\"" + crate.toUpperCase() + " CRATE\",color:\"gold\",bold:true}}");
                data.crateAt.put(crate, cx + "," + y + "," + z);
                i++;
            }
            saveAll();
        });
        later(120, () -> runIn("forceload remove " + (x - 12) + " " + (z - 12) + " " + (x + 12) + " " + (z + 12)));
    }

    static boolean crateClick(ServerPlayer p, BlockPos pos) {
        for (Map.Entry<String, String> e : data.crateAt.entrySet()) {
            String[] a = e.getValue().split(",");
            if (pos.getX() == Integer.parseInt(a[0]) && pos.getY() == Integer.parseInt(a[1]) && pos.getZ() == Integer.parseInt(a[2])) {
                if (keyCount(p, e.getKey()) > 0) openCrate(p, e.getKey());
                else { openCratePreview(p, e.getKey()); msg(p, "\u00a7cYou need a " + e.getKey() + " Key - buy one in /crates"); }
                return true;
            }
        }
        return false;
    }

    /** Sparkles above every crate so spawn feels alive. */
    static void crateAmbience() {
        for (String v : data.crateAt.values()) {
            String[] a = v.split(",");
            runIn("particle minecraft:end_rod " + (Integer.parseInt(a[0]) + 0.5) + " " + (Integer.parseInt(a[1]) + 1.3) + " " + (Integer.parseInt(a[2]) + 0.5) + " 0.3 0.5 0.3 0.02 3");
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
            String cr = crate;
            g.set(slots[n], icon(ci[n], "\u00a7d" + crate + " Crate", "\u00a77Your keys: \u00a7f" + keyCount(p, crate), "\u00a77Key price: \u00a7a" + fmt(price), " ",
                "\u00a7aClick: see the rewards", "\u00a7aShift-click: buy a key", "\u00a78Open it at the crates in the hub"),
                (pl, ct) -> { if (shift(ct)) buyKey(pl, cr); else openCratePreview(pl, cr); });
            n++;
        }
        g.set(22, icon(Items.COMPASS, "\u00a7fGo to the crates", "\u00a77Teleport to the hub crates"), (pl, ct) -> {
            int[] c = null;
            for (String name : cfg.crates.keySet()) { c = crateXYZ(name); if (c != null) break; }
            pl.closeContainer();
            if (c != null) tp(pl, c[0] + 4.5, c[1] + 1, c[2] - 3.5); else toHub(pl);
        });
        g.set(18, backIcon(), (pl, ct) -> openMain(pl));
        g.open(p);
    }

    static void openCratePreview(ServerPlayer p, String crate) {
        List<Reward> pool = cfg.crates.get(crate);
        if (pool == null) return;
        int total = totalWeight(pool);
        Gui g = new Gui("\u00a78" + crate + " Crate - rewards", 4).fill();
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        List<Reward> sorted = new ArrayList<>(pool);
        sorted.sort((x, y) -> Integer.compare(y.weight, x.weight));
        for (int i = 0; i < sorted.size() && i < slots.length; i++) g.set(slots[i], rewardIcon(sorted.get(i), total), null);
        long price = cfg.keyPrice.getOrDefault(crate, 10000L);
        g.set(30, icon(Items.GOLD_INGOT, "\u00a7aBuy a key", "\u00a77" + fmt(price), "\u00a77You have: \u00a7f" + keyCount(p, crate)), (pl, ct) -> { buyKey(pl, crate); openCratePreview(pl, crate); });
        g.set(32, icon(Items.ENDER_CHEST, "\u00a7eHow to open", "\u00a77Hold a " + crate + " Key and", "\u00a77right-click the crate at spawn"), null);
        g.set(31, icon(Items.ARROW, "\u00a7eBack"), (pl, ct) -> openCrates(pl));
        g.open(p);
    }

    static void buyKey(ServerPlayer p, String crate) {
        long price = cfg.keyPrice.getOrDefault(crate, 10000L);
        if (bal(p) < price) { msg(p, "\u00a7cYou need " + fmt(price)); return; }
        add(p, -price);
        addKey(p, crate, 1);
        msg(p, "\u00a7aBought a " + crate + " Key");
        soundAt(p, "entity.item.pickup", 0.6, 1.0);
    }

    // ---------------- the roll ----------------
    static final String[] RAINBOW = {"red", "orange", "yellow", "lime", "cyan", "light_blue", "purple", "magenta"};

    static void drawRoll(Gui g, List<Reward> strip, int off, int total, int color) {
        for (int j = 0; j < 9; j++) g.box.setItem(9 + j, rewardIcon(strip.get(off + j), total));
        Item pane = itemOr(RAINBOW[color % RAINBOW.length] + "_stained_glass_pane", itemOr("glass_pane", Items.GLASS));
        for (int i = 0; i < 27; i++) if (i < 9 || i >= 18) g.box.setItem(i, icon(pane, " "));
        g.box.setItem(4, icon(itemOr("hopper", Items.STICK), "\u00a7e\u25bc"));
        g.box.setItem(22, icon(itemOr("hopper", Items.STICK), "\u00a7e\u25b2"));
    }

    static void openCrate(ServerPlayer p, String crate) {
        List<Reward> pool = cfg.crates.get(crate);
        if (pool == null || pool.isEmpty()) return;
        String id = p.getStringUUID();
        if (ROLLING.contains(id)) { msg(p, "\u00a7cWait for your roll to finish."); return; }
        if (!takeKey(p, crate)) { msg(p, "\u00a7cYou need a " + crate + " Key."); return; }
        ROLLING.add(id);
        int total = totalWeight(pool);
        Reward win = pickReward(pool);
        int N = 40, W = 34;
        List<Reward> strip = new ArrayList<>();
        for (int i = 0; i < N; i++) strip.add(i == W ? win : pickReward(pool));
        Gui g = new Gui("\u00a78" + crate + " Crate", 3);
        drawRoll(g, strip, 0, total, 0);
        g.open(p);
        soundAt(p, "block.chest.open", 1.0, 1.0);
        int steps = W - 4;
        int t = 4;
        for (int off = 1; off <= steps; off++) {
            t += 1 + off / 6;                 // starts fast, slows down: tick, tick, tick ... tick ... tick
            int o = off, at = t;
            later(at, () -> {
                if (p.containerMenu instanceof GuiMenu gm && gm.g == g) {
                    drawRoll(g, strip, o, total, o);
                    gm.broadcastChanges();
                }
                soundAt(p, "block.note_block.hat", 0.7, 0.7 + 1.3 * o / steps);
            });
        }
        int end = t + 12;
        later(end, () -> {
            String col = rarityColor(win, total);
            boolean big = chance(win, total) < 8;
            if (p.containerMenu instanceof GuiMenu gm && gm.g == g) {
                Item gold = itemOr(big ? "yellow_stained_glass_pane" : "lime_stained_glass_pane", itemOr("glass_pane", Items.GLASS));
                for (int i = 0; i < 27; i++) if (i != 13 && i != 4 && i != 22) g.box.setItem(i, icon(gold, " "));
                g.box.setItem(13, rewardIcon(win, total));
                g.set(11, icon(itemOr("tripwire_hook", Items.STICK), "\u00a7aOpen again", "\u00a77Keys left: \u00a7f" + keyCount(p, crate)), (pl, ct) -> openCrate(pl, crate));
                g.set(15, icon(Items.ENDER_CHEST, "\u00a7eBack to crates"), (pl, ct) -> openCrates(pl));
                g.set(22, icon(Items.BARRIER, "\u00a7cClose"), (pl, ct) -> pl.closeContainer());
                gm.broadcastChanges();
            }
            giveReward(p, win);
            String n = p.getName().getString();
            run("title " + n + " times 5 60 20");
            run("title " + n + " subtitle \"" + col + rarityName(win, total) + " \u00a77- " + crate + " Crate\"");
            run("title " + n + " title \"" + col + "\u00a7l" + plainName(win) + "\"");
            soundAt(p, "entity.player.levelup", 1.0, big ? 1.2 : 1.0);
            if (big) {
                soundAt(p, "ui.toast.challenge_complete", 1.0, 1.0);
                soundAt(p, "entity.firework_rocket.blast", 1.0, 1.0);
                run("execute as " + n + " at @s run particle minecraft:totem_of_undying ~ ~1 ~ 0.7 0.9 0.7 0.5 80");
                if (chance(win, total) < 3)
                    server.getPlayerList().broadcastSystemMessage(Component.literal("\u00a76\u2726 " + n + " won \u00a7e\u00a7l" + plainName(win) + "\u00a76 from the " + crate + " Crate! \u2726"), false);
            } else run("execute as " + n + " at @s run particle minecraft:happy_villager ~ ~1 ~ 0.5 0.6 0.5 0.1 20");
            msg(p, "\u00a76You won: " + rewardName(win));
            ROLLING.remove(p.getStringUUID());
            saveAll();
        });
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
        giveReward(p, new Reward("spawner", mob, 1, 1));   // goes to /mailbox if the inventory is full
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
        addCoins(p, 10);
        addGems(p, 5);
        addKey(p, "Panda", 1);
        msg(p, "\u00a76Daily: " + fmt(cfg.dailyMoney) + ", 10 coins, 5 gems and a Panda key!");
        sound(p, "entity.player.levelup");
        saveAll();
    }

    // =====================================================================
    //  BLACK MARKET (rotating stock, paid in gems and coins)
    // =====================================================================
    static List<BM> bmStock() {
        long hour = System.currentTimeMillis() / 3_600_000L;
        List<BM> pool = new ArrayList<>(cfg.blackMarket);
        Collections.shuffle(pool, new Random(hour));
        return pool.subList(0, Math.min(7, pool.size()));
    }

    static void openBlackMarket(ServerPlayer p) {
        Gui g = new Gui("\u00a75Black Market", 3).fill();
        long mins = 60 - (System.currentTimeMillis() / 60_000L) % 60;
        int[] slots = {10, 11, 12, 13, 14, 15, 16};
        List<BM> stock = bmStock();
        for (int i = 0; i < stock.size(); i++) {
            BM b = stock.get(i);
            boolean gems = b.cur.equals("gems");
            String what = b.type.equals("spawner") ? pretty(b.value) + " Spawner" : b.type.equals("key") ? b.amount + "x " + b.value + " Key" : b.amount + "x " + pretty(b.value);
            Item it = b.type.equals("spawner") ? Items.SPAWNER : b.type.equals("key") ? Items.TRIPWIRE_HOOK : itemOr(b.value, Items.CHEST);
            g.set(slots[i], icon(it, "\u00a7f" + what, "\u00a77Price: " + (gems ? "\u00a7d" + b.price + " gems" : "\u00a7e" + b.price + " coins"),
                "\u00a78Click to buy", "\u00a78New stock in " + mins + " min"), (pl, ct) -> buyBM(pl, b));
        }
        g.set(4, icon(Items.GOLD_NUGGET, "\u00a7dGems: \u00a7f" + shortNum(gemsOf(p)), "\u00a7eCoins: \u00a7f" + shortNum(coinsOf(p))), null);
        g.set(22, backIcon(), (pl, ct) -> openMain(pl));
        g.open(p);
    }

    static void buyBM(ServerPlayer p, BM b) {
        boolean gems = b.cur.equals("gems");
        if ((gems ? gemsOf(p) : coinsOf(p)) < b.price) { msg(p, "\u00a7cYou need " + b.price + " " + b.cur); return; }
        if (gems) addGems(p, -b.price); else addCoins(p, -b.price);
        switch (b.type) {
            case "spawner": giveReward(p, new Reward("spawner", b.value, 1, 1)); break;
            case "key": addKey(p, b.value, b.amount); break;
            default: giveReward(p, new Reward("item", b.value, b.amount, 1));
        }
        msg(p, "\u00a7aPurchased from the black market!");
        sound(p, "entity.player.levelup");
        saveAll();
        openBlackMarket(p);
    }

    // =====================================================================
    //  MAILBOX + GIFTS
    // =====================================================================
    static void openMailbox(ServerPlayer p) {
        List<Mail> list = data.mail.computeIfAbsent(p.getStringUUID(), k -> new ArrayList<>());
        Gui g = new Gui("\u00a78Mailbox  (" + list.size() + ")", 6).fill();
        for (int i = 0; i < 45 && i < list.size(); i++) {
            Mail m = list.get(i);
            ItemStack show;
            if (m.kind.equals("stack")) {
                show = deser(m.payload).copy();
                if (show.isEmpty()) show = icon(Items.CHEST, "\u00a7f" + m.label);
                List<Component> lore = new ArrayList<>();
                lore.add(Component.literal("\u00a7r\u00a77" + m.label));
                lore.add(Component.literal("\u00a7r\u00a7aClick to claim"));
                show.set(DataComponents.LORE, new ItemLore(lore));
            } else show = icon(Items.CHEST, "\u00a7f" + m.label, "\u00a7aClick to claim");
            g.set(i, show, (pl, ct) -> { if (claimMail(pl, m)) { saveAll(); openMailbox(pl); } });
        }
        g.set(48, backIcon(), (pl, ct) -> openMain(pl));
        g.set(49, icon(Items.HOPPER, "\u00a7aClaim all", "\u00a77Takes everything that fits"), (pl, ct) -> {
            List<Mail> l = data.mail.getOrDefault(pl.getStringUUID(), new ArrayList<>());
            for (Mail m : new ArrayList<>(l)) if (!claimMail(pl, m)) break;
            saveAll();
            openMailbox(pl);
        });
        g.open(p);
    }

    static void giftHand(ServerPlayer p, String targetName) {
        String to = uuidByName(targetName);
        if (to == null) { msg(p, "\u00a7cI don't know a player called " + targetName); return; }
        if (to.equals(p.getStringUUID())) { msg(p, "\u00a7cYou can't gift yourself."); return; }
        ItemStack hand = p.getMainHandItem();
        if (hand.isEmpty()) { msg(p, "\u00a7cHold the item you want to gift."); return; }
        String json = ser(hand);
        if (json == null) { msg(p, "\u00a7cThis item can't be gifted."); return; }
        String label = "Gift from " + p.getName().getString() + ": " + hand.getCount() + "x " + hand.getHoverName().getString();
        addMail(to, "stack", json, label);
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        msg(p, "\u00a7aGift sent to " + data.names.get(to) + "!");
        ServerPlayer t = server.getPlayerList().getPlayer(UUID.fromString(to));
        if (t != null) msg(t, "\u00a7e" + label + "  \u00a77- open /mailbox");
        saveAll();
    }

    // =====================================================================
    //  SAFARI NET: catch one mob (100 uses), release it somewhere else
    // =====================================================================
    static ItemStack safariNet() {
        ItemStack s = icon(Items.LEAD, "\u00a7a\u00a7lSafari Net", "\u00a77Right-click a mob to catch it", "\u00a77Right-click the ground to release it", "\u00a78Uses left: 100");
        comp(s, "MAX_STACK_SIZE", 1);
        comp(s, "MAX_DAMAGE", 100);
        return s;
    }
    static boolean isNet(ItemStack st) { return !st.isEmpty() && st.getItem() == Items.LEAD && st.getHoverName().getString().contains("Safari Net"); }

    static String netHolding(ItemStack st) {
        ItemLore lore = st.get(DataComponents.LORE);
        if (lore == null) return null;
        for (Component c : lore.lines()) {
            String t = c.getString().replaceAll("\u00a7.", "");
            if (t.startsWith("Holding: ")) return t.substring(9).trim();
        }
        return null;
    }

    static void netSetLore(ItemStack st, String mob) {
        int left = Math.max(0, st.getMaxDamage() - st.getDamageValue());
        List<Component> l = new ArrayList<>();
        l.add(Component.literal("\u00a7r\u00a77" + (mob == null ? "Right-click a mob to catch it" : "Holding: \u00a7f" + mob)));
        l.add(Component.literal("\u00a7r\u00a77Right-click the ground to release it"));
        l.add(Component.literal("\u00a7r\u00a78Uses left: " + left));
        st.set(DataComponents.LORE, new ItemLore(l));
    }

    static InteractionResult netCatch(ServerPlayer p, InteractionHand hand, Entity e) {
        ItemStack st = p.getItemInHand(hand);
        if (!isNet(st)) return InteractionResult.PASS;
        if (netHolding(st) != null) { msg(p, "\u00a7cYour net already holds a mob. Release it first."); return InteractionResult.SUCCESS; }
        if (!(e instanceof Mob)) { msg(p, "\u00a7cYou can only catch mobs."); return InteractionResult.SUCCESS; }
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
        if (id.equals("ender_dragon") || id.equals("wither")) { msg(p, "\u00a7cThat mob is far too strong for a net."); return InteractionResult.SUCCESS; }
        boolean baby = e instanceof LivingEntity le && le.isBaby();
        e.discard();
        st.setDamageValue(st.getDamageValue() + 1);
        netSetLore(st, id + (baby ? " (baby)" : ""));
        msg(p, "\u00a7aCaught a " + pretty(id) + "!");
        soundAt(p, "entity.item.pickup", 1.0, 0.8);
        return InteractionResult.SUCCESS;
    }

    static boolean netRelease(ServerPlayer p, InteractionHand hand) {
        ItemStack st = p.getItemInHand(hand);
        if (!isNet(st)) return false;
        String held = netHolding(st);
        if (held == null) return false;
        boolean baby = held.contains("(baby)");
        String id = held.replace("(baby)", "").trim();
        run("execute as " + p.getName().getString() + " at @s rotated ~ 0 run summon minecraft:" + id + " ^ ^ ^2" + (baby ? " {Age:-24000,IsBaby:1b}" : ""));
        netSetLore(st, null);
        msg(p, "\u00a7aReleased the " + pretty(id) + "!");
        soundAt(p, "entity.item.pickup", 1.0, 1.4);
        if (st.getMaxDamage() > 0 && st.getDamageValue() >= st.getMaxDamage()) {
            p.setItemInHand(hand, ItemStack.EMPTY);
            msg(p, "\u00a7eYour Safari Net broke!");
            soundAt(p, "entity.item.break", 1.0, 1.0);
        }
        return true;
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
        g.set(11, icon(is.memberBuild ? item(Items.EMERALD, "LIME_DYE", "GREEN_DYE") : item(Items.REDSTONE, "RED_DYE"), "\u00a7fMembers can build: " + (is.memberBuild ? "\u00a7aYES" : "\u00a7cNO"),
            "\u00a77Break and place blocks on the island", "\u00a78Click to toggle"), (pl, ct) -> { is.memberBuild = !is.memberBuild; saveAll(); openPermissions(pl); });
        g.set(15, icon(is.memberInvite ? item(Items.EMERALD, "LIME_DYE", "GREEN_DYE") : item(Items.REDSTONE, "RED_DYE"), "\u00a7fMembers can invite: " + (is.memberInvite ? "\u00a7aYES" : "\u00a7cNO"),
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
        g.set(19, icon(Items.DIAMOND_BLOCK, "\u00a7bCreative mode"), (pl, ct) -> run("gamemode creative " + pl.getName().getString()));
        g.set(20, icon(Items.IRON_SWORD, "\u00a7fSurvival mode"), (pl, ct) -> run("gamemode survival " + pl.getName().getString()));
        g.set(21, icon(Items.CLOCK, "\u00a7eSet day"), (pl, ct) -> run("time set day"));
        g.set(22, icon(Items.WATER_BUCKET, "\u00a79Clear weather"), (pl, ct) -> run("weather clear"));
        g.set(23, icon(Items.GOLDEN_APPLE, "\u00a7cHeal and feed"), (pl, ct) -> {
            run("effect give " + pl.getStringUUID() + " minecraft:instant_health 1 10 true");
            run("effect give " + pl.getStringUUID() + " minecraft:saturation 5 10 true"); });
        g.set(24, icon(Items.PLAYER_HEAD, "\u00a7ePlayers", "\u00a77Teleport, give money, ranks, kick"), (pl, ct) -> openPlayers(pl));
        g.set(25, icon(Items.REDSTONE, "\u00a7cReload config", "\u00a77re-reads config.json"), (pl, ct) -> {
            cfg = load("config.json", Cfg.class, cfg); msg(pl, "\u00a7aConfig reloaded."); });
        g.set(26, icon(Items.WRITABLE_BOOK, "\u00a7aSave data now"), (pl, ct) -> { saveAll(); msg(pl, "\u00a7aSaved."); });
        g.set(27, icon(Items.NETHER_STAR, "\u00a7d+100 gems", "\u00a77for you"), (pl, ct) -> { addGems(pl, 100); msg(pl, "\u00a7d+100 gems"); });
        g.set(28, icon(Items.GOLD_NUGGET, "\u00a7e+1,000 coins", "\u00a77for you"), (pl, ct) -> { addCoins(pl, 1000); msg(pl, "\u00a7e+1,000 coins"); });
        g.set(29, icon(Items.TNT, "\u00a7cReset config to defaults", "\u00a77new prices, crates, black market"), (pl, ct) -> { cfg = new Cfg(); cfg.ownerUuid = pl.getStringUUID(); save("config.json", cfg); msg(pl, "\u00a7aConfig reset."); });
        g.set(30, icon(Items.SHULKER_BOX, "\u00a76Build crates here", "\u00a77Places the 3 crate boxes where you stand"),
            (pl, ct) -> { buildCrates((int) Math.floor(pl.getX()), (int) Math.floor(pl.getY()), (int) Math.floor(pl.getZ()) + 3); msg(pl, "\u00a7aBuilding crates..."); });
        g.set(32, icon(Items.LEAD, "\u00a7aSafari Net", "\u00a77give yourself one"), (pl, ct) -> deliverStack(pl, safariNet(), "Safari Net"));
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
    /** Starter kit: wooden tools, "Common Armor" (gray name) worn, 16 steaks, and the menu item. */
    static void giveKit(ServerPlayer p) {
        for (String t : new String[]{"sword", "pickaxe", "axe", "shovel", "hoe"})
            deliverGive(p, "give %P% minecraft:wooden_" + t + " 1", "Wooden " + t);
        deliverGive(p, "give %P% minecraft:cooked_beef 16", "16x Steak");
        String[] pieces = {"LEATHER_HELMET", "LEATHER_CHESTPLATE", "LEATHER_LEGGINGS", "LEATHER_BOOTS"};
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (int i = 0; i < 4; i++) {
            ItemStack a = icon(item(Items.LEATHER_CHESTPLATE, pieces[i]), "\u00a77Common Armor");
            try { p.setItemSlot(slots[i], a); } catch (Throwable e) { deliverStack(p, a, "Common Armor"); }
        }
        ensureMenuItem(p);
        msg(p, "\u00a7aYou received the starter kit!");
    }

    /** Called just before a spawner breaks: hands the player a spawner item for the same mob (any tool, no Silk Touch needed). */
    static void returnSpawner(ServerPlayer p, BlockPos pos) {
        Set<String> mobs = new LinkedHashSet<>(cfg.spawners.keySet());
        for (String[] list : PHASE_MOBS) mobs.addAll(Arrays.asList(list));
        for (String m : mobs) {
            runIn("execute if data block " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                + " SpawnData.entity{id:\"minecraft:" + m + "\"} run give " + p.getName().getString()
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
        later(60, () -> buildCrates(0, y, 6));
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
            // hub from an older version has no crates yet: add them next to it
            later(200, () -> {
                if (data.hubSet && data.crateAt.isEmpty())
                    buildCrates((int) Math.floor(data.hubX), (int) Math.floor(data.hubY), (int) Math.floor(data.hubZ) + 6);
            });
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
            if (is.broken % cfg.blocksPerPhase == 0 && is.broken / cfg.blocksPerPhase < PHASE_NAME.length) {
                for (String mm : is.members) {
                    ServerPlayer mp = server.getPlayerList().getPlayer(UUID.fromString(mm));
                    if (mp != null) { addGems(mp, 10); celebrate(mp, "\u00a76\u00a7lPHASE " + (phase(is) + 1), "\u00a7f" + PHASE_NAME[phase(is)] + "  \u00a7d+10 gems"); }
                }
            }
            if (is.broken % 500 == 0) { addCoins(sp, 10); msg(sp, "\u00a7e+10 coins"); }
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

        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (player instanceof ServerPlayer sp) {
                if (isMenuItem(player.getItemInHand(hand))) { openMain(sp); return InteractionResult.SUCCESS; }
                if (netRelease(sp, hand)) return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            if (hand == InteractionHand.MAIN_HAND && inVoid(sp) && crateClick(sp, hit.getBlockPos())) return InteractionResult.SUCCESS;
            if (netRelease(sp, hand)) return InteractionResult.SUCCESS;
            return InteractionResult.PASS;
        });
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (player instanceof ServerPlayer sp) return netCatch(sp, hand, entity);
            return InteractionResult.PASS;
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
            if (is.ready) ensureMenuItem(p);
            if (ticks % 1200 == 0) { int mc = data.mail.getOrDefault(id, List.of()).size(); if (mc > 0) msg(p, "\u00a7eYou have " + mc + " item(s) in /mailbox"); }

            int fish = p.getStats().getValue(Stats.CUSTOM.get(Stats.FISH_CAUGHT));
            Integer last = LAST_FISH.put(id, fish);
            if (last != null && fish > last) pay(p, (long) (fish - last) * cfg.fishPay);

            Map<String, Integer> oldKeys = data.keys.remove(id);   // keys from before they were physical items
            if (oldKeys != null) for (Map.Entry<String, Integer> e : oldKeys.entrySet()) addKey(p, e.getKey(), e.getValue());
            if (ticks % 100 == 0 && is.ready) {                   // infinite villager trades
                String pn = p.getName().getString();
                for (String who : new String[]{"villager", "wandering_trader"}) {
                    String base = "execute as " + pn + " at @s as @e[type=minecraft:" + who + ",distance=..24] run data modify entity @s Offers.Recipes[].";
                    run(base + "maxUses set value 1000000");
                    run(base + "uses set value 0");
                    run(base + "demand set value 0");
                }
            }
            perks(p);
            enforce(p, is);
            border(p, is);
            sidebar(p, is);
        }
        if (!online.isEmpty() && !data.crateAt.isEmpty()) crateAmbience();
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

    /** Score holder names may not contain normal spaces (the command parser stops at them), so use non-breaking spaces. */
    static String nb(String x) { return x.replace(' ', '\u00a0'); }

    static void sidebar(ServerPlayer p, Isl is) {
        int s = slotFor(p);
        String id = p.getStringUUID();
        int ph = phase(is);
        boolean last = ph >= PHASE_NAME.length - 1;
        int into = is.broken - ph * cfg.blocksPerPhase;
        int pct = last ? 100 : Math.min(100, into * 100 / cfg.blocksPerPhase);
        int filled = pct / 10;
        StringBuilder bar = new StringBuilder("\u00a7a");
        for (int i = 0; i < 10; i++) { if (i == filled) bar.append("\u00a78"); bar.append('\u2588'); }
        int level = Math.min(100, is.broken / 25);
        String owner = data.names.getOrDefault(is.owner, "?").toUpperCase();
        List<String> now = new ArrayList<>();
        now.add(nb("\u00a76MONEY: \u00a7f" + shortNum(bal(p))));
        now.add(nb("\u00a7eCOINS: \u00a7f" + shortNum(coinsOf(p))));
        now.add(nb("\u00a7dGEMS: \u00a7f" + shortNum(gemsOf(p))));
        now.add(nb("\u00a7bFLIGHT: \u00a7f" + (cfg.flyRanks.contains(rankId(p)) ? "\u221e" : "None")));
        now.add("\u00a71");
        now.add(nb("\u00a7a\u00a7l" + owner + "'S ISLAND"));
        now.add(nb("\u00a7bPHASE: \u00a7f" + PHASE_NAME[ph].toUpperCase() + " (" + pct + "%)"));
        now.add(bar.toString());
        now.add(nb("\u00a76LEVEL: \u00a7f" + level + "/100"));
        now.add("\u00a72");
        now.add(nb("\u00a77oneblox"));
        List<String> old = LINES.getOrDefault(id, List.of());
        if (now.equals(old)) return;
        for (int i = 0; i < now.size(); i++)
            run("scoreboard players set " + now.get(i) + " ob_" + s + " " + (now.size() - i));
        for (String o : old) if (!now.contains(o)) run("scoreboard players reset " + o + " ob_" + s);
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
            msg(p, "\u00a76" + fmt(bal(p)) + "  \u00a7e" + shortNum(coinsOf(p)) + " coins  \u00a7d" + shortNum(gemsOf(p)) + " gems");
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

        // ----- currencies, black market, mail -----
        d.register(Commands.literal("coins").executes(c -> { ServerPlayer p = c.getSource().getPlayerOrException(); msg(p, "\u00a7eCoins: \u00a7f" + shortNum(coinsOf(p))); return 1; }));
        d.register(Commands.literal("gems").executes(c -> { ServerPlayer p = c.getSource().getPlayerOrException(); msg(p, "\u00a7dGems: \u00a7f" + shortNum(gemsOf(p))); return 1; }));
        Command<CommandSourceStack> bmCmd = c -> { openBlackMarket(c.getSource().getPlayerOrException()); return 1; };
        d.register(Commands.literal("blackmarket").executes(bmCmd));
        d.register(Commands.literal("bm").executes(bmCmd));
        Command<CommandSourceStack> mailCmd = c -> { openMailbox(c.getSource().getPlayerOrException()); return 1; };
        d.register(Commands.literal("mailbox").executes(mailCmd));
        d.register(Commands.literal("mail").executes(mailCmd));
        d.register(Commands.literal("gift").then(Commands.argument("player", StringArgumentType.word()).executes(c -> {
            giftHand(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "player"));
            return 1;
        })));

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
