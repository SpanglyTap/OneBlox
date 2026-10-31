package com.example.oneblox;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * OneBlox: OneBlock islands + money + spawners. Server side only (players need no mod).
 * Islands are far apart (can't see each other) and fenced in with a red glass wall.
 * All numbers live in config/oneblox/config.json.
 */
public class Oneblox implements ModInitializer {

    // ---------- data ----------
    static class Cfg {
        List<String> admins = new ArrayList<>(List.of("SpanglyTap"));
        int spacing = 1000;        // distance between islands (keeps them out of sight)
        int radius = 50;           // island half-size, the glass wall stands here
        int blocksPerPhase = 150;  // OneBlock blocks to break per phase
        int mobChance = 8;         // % chance a mob appears when the block is broken
        int spawnerChance = 2;     // % chance the next block is a spawner (phase 2+)
        int hubRadius = 300;       // area around hub where players may roam
        int maxMembers = 5;
        long fishPay = 10;
        double mobPayPerHealth = 1.5;
        Map<String, Long> spawners = new LinkedHashMap<>();
        Cfg() {
            spawners.put("chicken", 4000L); spawners.put("sheep", 4000L); spawners.put("pig", 5000L);
            spawners.put("cow", 6000L); spawners.put("zombie", 10000L); spawners.put("skeleton", 12000L);
            spawners.put("spider", 12000L); spawners.put("creeper", 20000L); spawners.put("enderman", 50000L);
            spawners.put("blaze", 80000L); spawners.put("iron_golem", 100000L);
        }
    }

    static class Isl {
        int idx; String owner; List<String> members = new ArrayList<>(); int broken; boolean ready;
    }

    static class Data {
        int nextIsland = 0;
        boolean hubSet = false; double hubX = 0.5, hubY = 64, hubZ = 0.5;
        Map<String, Long> money = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        Map<String, Integer> islandOf = new HashMap<>();
        Map<Integer, Isl> islands = new HashMap<>();
    }

    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static final Random RNG = new Random();
    static MinecraftServer server;
    static Path dir;
    static Cfg cfg = new Cfg();
    static Data data = new Data();
    static int ticks = 0;
    static final Map<String, Integer> INVITES = new HashMap<>();
    static final Map<String, Integer> LAST_FISH = new HashMap<>();

    // ---------- OneBlock phases ----------
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
        {"cow", "pig", "sheep", "chicken"},
        {"zombie", "skeleton", "spider", "creeper"},
        {"stray", "wolf", "rabbit", "zombie"},
        {"ocelot", "parrot", "spider", "creeper"},
        {"husk", "skeleton", "spider", "creeper"},
        {"zombified_piglin", "magma_cube", "blaze", "wither_skeleton"},
        {"enderman", "endermite"},
        {"zombie", "skeleton", "witch", "creeper"}
    };

    // ---------- money for blocks ----------
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

    // ---------- small helpers ----------
    static void msg(ServerPlayer p, String s) { p.sendSystemMessage(Component.literal(s)); }
    static String fmt(long v) { return "$" + String.format("%,d", v); }
    static long bal(ServerPlayer p) { return data.money.getOrDefault(p.getStringUUID(), 0L); }
    static void add(ServerPlayer p, long a) { data.money.merge(p.getStringUUID(), a, Long::sum); }

    static void pay(ServerPlayer p, long a) {
        if (a <= 0) return;
        add(p, a);
        p.connection.send(new ClientboundSetActionBarTextPacket(Component.literal("+" + fmt(a))));
    }

    static boolean isAdmin(ServerPlayer p) {
        return server.isSingleplayer() || cfg.admins.contains(p.getName().getString());
    }

    /** Runs a command as the server, without spamming chat. Used for blocks, teleports, mobs, gives. */
    static void run(String cmd) {
        try {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd);
        } catch (Exception e) { e.printStackTrace(); }
    }

    static void tp(ServerPlayer p, double x, double y, double z) {
        run("execute in minecraft:overworld run tp " + p.getStringUUID() + " " + x + " " + y + " " + z);
    }

    // delayed tasks
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

    // ---------- islands ----------
    static int cx(int idx) { return cfg.spacing * (1 + idx % 10); }
    static int cz(int idx) { return cfg.spacing * (1 + idx / 10); }
    static Isl islandOf(String uuid) { Integer i = data.islandOf.get(uuid); return i == null ? null : data.islands.get(i); }
    static int phase(Isl is) { return Math.min(PHASE_NAME.length - 1, is.broken / cfg.blocksPerPhase); }

    static void toHome(ServerPlayer p) {
        Isl is = islandOf(p.getStringUUID());
        if (is == null) { createIsland(p); return; }
        tp(p, cx(is.idx) + 0.5, 64, cz(is.idx) + 3.5);
    }

    static void toHub(ServerPlayer p) {
        if (data.hubSet) tp(p, data.hubX, data.hubY, data.hubZ);
        else msg(p, "No hub is set yet. An admin can stand somewhere and use /oneblox setspawn.");
    }

    static void createIsland(ServerPlayer p) {
        String id = p.getStringUUID();
        int idx = data.nextIsland++;
        Isl is = new Isl();
        is.idx = idx; is.owner = id; is.members.add(id);
        data.islands.put(idx, is);
        data.islandOf.put(id, idx);
        int x = cx(idx), z = cz(idx), R = cfg.radius;
        msg(p, "Building your island, one moment...");
        run("forceload add " + (x - R) + " " + (z - R) + " " + (x + R) + " " + (z + R));
        later(40, () -> {
            run("fill " + (x - 2) + " 63 " + (z - 2) + " " + (x + 2) + " 63 " + (z + 2) + " minecraft:grass_block");
            run("setblock " + x + " 64 " + z + " minecraft:grass_block");
            String g = " minecraft:red_stained_glass";
            run("fill " + (x - R) + " 58 " + (z - R) + " " + (x + R) + " 72 " + (z - R) + g);
            run("fill " + (x - R) + " 58 " + (z + R) + " " + (x + R) + " 72 " + (z + R) + g);
            run("fill " + (x - R) + " 58 " + (z - R) + " " + (x - R) + " 72 " + (z + R) + g);
            run("fill " + (x + R) + " 58 " + (z - R) + " " + (x + R) + " 72 " + (z + R) + g);
        });
        later(50, () -> { tp(p, x + 0.5, 64, z + 3.5); is.ready = true; msg(p, "Welcome to your island! Break the grass block."); });
        later(120, () -> run("forceload remove " + (x - R) + " " + (z - R) + " " + (x + R) + " " + (z + R)));
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
        run("setblock " + cx(is.idx) + " 64 " + cz(is.idx) + " " + b + " keep");
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
        return Math.abs(pos.getX() - cx(is.idx)) < cfg.radius && Math.abs(pos.getZ() - cz(is.idx)) < cfg.radius;
    }

    static boolean inHub(double x, double z) {
        return Math.abs(x - data.hubX) < cfg.hubRadius && Math.abs(z - data.hubZ) < cfg.hubRadius;
    }

    /** Keeps players inside their own island (or the hub) and out of the void. */
    static void enforce(ServerPlayer p, Isl is) {
        if (p.level().dimension() != Level.OVERWORLD || isAdmin(p)) return;
        if (is == null || !is.ready) return;
        double x = p.getX(), z = p.getZ();
        if (p.getY() < -10) { toHome(p); return; }
        if (inHub(x, z)) return;
        if (Math.abs(x - cx(is.idx)) > cfg.radius || Math.abs(z - cz(is.idx)) > cfg.radius) {
            msg(p, "You can't leave your island.");
            toHome(p);
        }
    }

    static void buildHub() {
        if (data.hubSet) return;
        var level = server.overworld();
        if (!level.getBlockState(new BlockPos(0, 63, 0)).isAir()) {
            System.out.println("[Oneblox] Something already exists at 0,63,0 (a map?). Use /oneblox setspawn to set the hub.");
            return;
        }
        run("forceload add -25 -25 25 25");
        later(40, () -> run("fill -20 63 -20 20 63 20 minecraft:stone_bricks"));
        later(120, () -> run("forceload remove -25 -25 25 25"));
        data.hubSet = true; data.hubX = 0.5; data.hubY = 64; data.hubZ = 0.5;
        saveAll();
    }

    // ---------- startup ----------
    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            dir = FabricLoader.getInstance().getConfigDir().resolve("oneblox");
            cfg = load("config.json", Cfg.class, new Cfg());
            data = load("data.json", Data.class, new Data());
            save("config.json", cfg);
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

        // mining + tree cutting; also drives the OneBlock
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, be) -> {
            if (!(player instanceof ServerPlayer sp) || isAdmin(sp)) return true;
            return canBuild(sp, pos);
        });
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
            if (!(player instanceof ServerPlayer sp)) return;
            Isl is = islandOf(sp.getStringUUID());
            boolean one = is != null && pos.getX() == cx(is.idx) && pos.getY() == 64 && pos.getZ() == cz(is.idx);
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
            pay(sp, blockPay(id, one));
            if (!one) return;
            is.broken++;
            if (is.broken % cfg.blocksPerPhase == 0 && phase(is) > 0 && is.broken / cfg.blocksPerPhase < PHASE_NAME.length)
                tellIsland(is, "New phase reached: " + PHASE_NAME[phase(is)] + "!");
            Isl fis = is;
            later(1, () -> placeNext(fis));
            if (RNG.nextInt(100) < cfg.mobChance) {
                String[] m = PHASE_MOBS[phase(is)];
                run("summon minecraft:" + m[RNG.nextInt(m.length)] + " " + (cx(is.idx) + 0.5) + " 65 " + (cz(is.idx) + 0.5));
            }
        });

        // hunting
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(source.getEntity() instanceof ServerPlayer sp) || !(entity instanceof Mob)) return;
            pay(sp, Math.max(1L, Math.round(entity.getMaxHealth() * cfg.mobPayPerHealth)));
        });

        CommandRegistrationCallback.EVENT.register((d, access, env) -> registerCommands(d));
    }

    static void everySecond() {
        for (ServerPlayer p : new ArrayList<>(server.getPlayerList().getPlayers())) {
            String id = p.getStringUUID();
            data.names.put(id, p.getName().getString());
            Isl is = islandOf(id);
            if (is == null) { createIsland(p); continue; }
            if (ticks % 100 == 0 && is.ready) placeNext(is);   // safety: refills the block if it ever vanished ("keep" = only if empty)

            // fishing: pay for every new catch
            int fish = p.getStats().getValue(Stats.CUSTOM.get(Stats.FISH_CAUGHT));
            Integer last = LAST_FISH.put(id, fish);
            if (last != null && fish > last) pay(p, (long) (fish - last) * cfg.fishPay);

            enforce(p, is);
        }
    }

    // ---------- commands ----------
    static void registerCommands(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> d) {
        Command<CommandSourceStack> balCmd = c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            msg(p, "Balance: " + fmt(bal(p)));
            return 1;
        };
        d.register(Commands.literal("bal").executes(balCmd));
        d.register(Commands.literal("money").executes(balCmd));

        d.register(Commands.literal("baltop").executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            List<Map.Entry<String, Long>> l = new ArrayList<>(data.money.entrySet());
            l.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            msg(p, "--- Richest players ---");
            for (int i = 0; i < Math.min(10, l.size()); i++)
                msg(p, (i + 1) + ". " + data.names.getOrDefault(l.get(i).getKey(), "?") + "  " + fmt(l.get(i).getValue()));
            return 1;
        }));

        d.register(Commands.literal("pay")
            .then(Commands.argument("player", EntityArgument.player())
            .then(Commands.argument("amount", LongArgumentType.longArg(1)).executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                ServerPlayer t = EntityArgument.getPlayer(c, "player");
                long a = LongArgumentType.getLong(c, "amount");
                if (bal(p) < a) { msg(p, "You don't have enough money."); return 0; }
                add(p, -a); add(t, a);
                msg(p, "Paid " + fmt(a) + " to " + t.getName().getString());
                msg(t, p.getName().getString() + " paid you " + fmt(a));
                return 1;
            }))));

        d.register(Commands.literal("spawn").executes(c -> { toHub(c.getSource().getPlayerOrException()); return 1; }));

        // ----- spawners -----
        d.register(Commands.literal("spawners").executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            msg(p, "--- Spawners (buy with /buyspawner <mob>) ---");
            for (var e : cfg.spawners.entrySet()) msg(p, e.getKey() + "  " + fmt(e.getValue()));
            return 1;
        }));
        d.register(Commands.literal("buyspawner")
            .then(Commands.argument("mob", StringArgumentType.word()).executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                String mob = StringArgumentType.getString(c, "mob").toLowerCase();
                Long price = cfg.spawners.get(mob);
                if (price == null) { msg(p, "Unknown mob. See /spawners"); return 0; }
                if (bal(p) < price) { msg(p, "You need " + fmt(price)); return 0; }
                add(p, -price);
                run("give " + p.getStringUUID() + " minecraft:spawner[block_entity_data={id:\"minecraft:mob_spawner\",SpawnData:{entity:{id:\"minecraft:" + mob + "\"}}}] 1");
                msg(p, "Bought a " + mob + " spawner for " + fmt(price));
                return 1;
            })));

        // ----- islands -----
        Command<CommandSourceStack> home = c -> { toHome(c.getSource().getPlayerOrException()); return 1; };

        LiteralArgumentBuilder<CommandSourceStack> is = Commands.literal("is").executes(home)
            .then(Commands.literal("home").executes(home))
            .then(Commands.literal("info").executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                Isl i = islandOf(p.getStringUUID());
                if (i == null) { msg(p, "You have no island yet."); return 0; }
                int ph = phase(i);
                msg(p, "Phase " + (ph + 1) + ": " + PHASE_NAME[ph] + "  |  blocks broken: " + i.broken
                    + "  |  members: " + i.members.size());
                return 1;
            }))
            .then(Commands.literal("members").executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                Isl i = islandOf(p.getStringUUID());
                if (i == null) return 0;
                StringBuilder sb = new StringBuilder("Members: ");
                for (String m : i.members) sb.append(data.names.getOrDefault(m, "?")).append(m.equals(i.owner) ? " (owner)" : "").append("  ");
                msg(p, sb.toString());
                return 1;
            }))
            .then(Commands.literal("invite").then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                ServerPlayer t = EntityArgument.getPlayer(c, "player");
                Isl i = islandOf(p.getStringUUID());
                if (i == null || !i.owner.equals(p.getStringUUID())) { msg(p, "Only the island owner can invite."); return 0; }
                if (i.members.size() >= cfg.maxMembers) { msg(p, "Island is full."); return 0; }
                INVITES.put(t.getStringUUID(), i.idx);
                msg(p, "Invited " + t.getName().getString());
                msg(t, p.getName().getString() + " invited you to their island. Type /is accept (your old island stays where it is).");
                return 1;
            })))
            .then(Commands.literal("accept").executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                String id = p.getStringUUID();
                Integer idx = INVITES.remove(id);
                Isl n = idx == null ? null : data.islands.get(idx);
                if (n == null) { msg(p, "You have no invite."); return 0; }
                Isl old = islandOf(id);
                if (old != null) old.members.remove(id);
                n.members.add(id);
                data.islandOf.put(id, n.idx);
                tellIsland(n, p.getName().getString() + " joined the island!");
                toHome(p);
                return 1;
            }))
            .then(Commands.literal("leave").executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                String id = p.getStringUUID();
                Isl i = islandOf(id);
                if (i == null) return 0;
                if (i.owner.equals(id)) { msg(p, "The owner can't leave. Kick the members first."); return 0; }
                i.members.remove(id);
                data.islandOf.remove(id);   // a fresh island is created for you next second
                msg(p, "You left the island.");
                return 1;
            }))
            .then(Commands.literal("kick").then(Commands.argument("name", StringArgumentType.word()).executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                Isl i = islandOf(p.getStringUUID());
                String name = StringArgumentType.getString(c, "name");
                if (i == null || !i.owner.equals(p.getStringUUID())) { msg(p, "Only the island owner can kick."); return 0; }
                for (String m : new ArrayList<>(i.members)) {
                    if (!m.equals(i.owner) && name.equalsIgnoreCase(data.names.get(m))) {
                        i.members.remove(m);
                        data.islandOf.remove(m);
                        msg(p, "Kicked " + name);
                        return 1;
                    }
                }
                msg(p, "No such member.");
                return 0;
            })));
        LiteralCommandNode<CommandSourceStack> node = d.register(is);
        d.register(Commands.literal("island").redirect(node));

        // ----- admin -----
        d.register(Commands.literal("oneblox")
            .then(Commands.literal("setspawn").executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                if (!isAdmin(p)) { msg(p, "Admins only."); return 0; }
                data.hubSet = true; data.hubX = p.getX(); data.hubY = p.getY(); data.hubZ = p.getZ();
                saveAll();
                msg(p, "Hub set here.");
                return 1;
            }))
            .then(Commands.literal("addmoney")
                .then(Commands.argument("player", EntityArgument.player())
                .then(Commands.argument("amount", LongArgumentType.longArg()).executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    if (!isAdmin(p)) { msg(p, "Admins only."); return 0; }
                    ServerPlayer t = EntityArgument.getPlayer(c, "player");
                    add(t, LongArgumentType.getLong(c, "amount"));
                    msg(p, "Done. " + t.getName().getString() + " now has " + fmt(bal(t)));
                    return 1;
                })))));
    }
}
