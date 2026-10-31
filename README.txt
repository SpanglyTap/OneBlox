OXBLOX / ONEBLOX  (Fabric, Minecraft 26.3, server-side)
=======================================================
Players do NOT need the mod. Put it on a Fabric server (or in a singleplayer world's mods folder).

BUILD (GitHub, same as ChatSolver)
 1. New repo -> upload everything in this folder (drag the files in, NOT the zip).
 2. Actions tab -> "set up a workflow yourself" -> name it build.yml -> paste .github/workflows/build.yml -> Commit.
    (The hidden .github folder usually doesn't upload by drag and drop.)
 3. Wait for the green tick -> Artifacts -> download "oneblox-mod" -> unzip -> oneblox-1.0.0.jar.

SERVER
 - Fabric Loader 0.19.5+ for 26.3, Fabric API 0.161.0+26.3, Java 25.
 - Put oneblox-1.0.0.jar and the Fabric API jar in the server's mods folder.
 - For a void world, use a NEW world (delete the old world folder) and put this in server.properties:
     level-type=minecraft:flat
     generator-settings={"layers":[{"block":"minecraft:air","height":1}],"biome":"minecraft:the_void"}
     allow-flight=true
     spawn-protection=0
 - First start: the mod builds a stone-brick hub at 0,63,0 if the space is empty.
 - Every player gets their own island automatically when they join.

COMMANDS
 /is  /is home /is info /is members /is invite <p> /is accept /is leave /is kick <name>
 /spawn  /bal  /baltop  /pay <p> <amount>  /spawners  /buyspawner <mob>
 Admin (names in config.json "admins", default SpanglyTap; everyone is admin in singleplayer):
 /oneblox setspawn      /oneblox addmoney <player> <amount>

CONFIG
 config/oneblox/config.json  (prices, phase length, island size, admins, spawner prices)
 config/oneblox/data.json    (money + islands; back this up)

KNOWN LIMITS (v1)
 - Placing and re-breaking logs/ores pays again (no block tracking yet).
 - No shop/sell menu, ranks, visits, or custom items yet.
