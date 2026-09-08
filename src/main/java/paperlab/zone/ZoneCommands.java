package paperlab.zone;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import paperlab.command.LabPermissions;
import paperlab.core.CoreBridge;

/**
 * Brigadier commands for tick zones:
 * /tick zone, /zone, and /carpet zone.
 */
public final class ZoneCommands {

    public static final String HELP = "discrete ticking zones";

    /**
     * The console's stand-in for a player UUID, so that focus works without a player.
     *
     * <p>Focus is stored per player, and every zone-editing command reads it. That left the
     * console able to list and drive zones but not to build one: no focus, no {@code box add}.
     * The nil UUID is not a player's and cannot collide with one.
     */
    private static final UUID CONSOLE = new UUID(0L, 0L);

    private ZoneCommands() {
    }

    /** Who is acting: a player by their own UUID, anything else as the console. */
    private static UUID actor(final CommandSender sender) {
        return sender instanceof final Player player ? player.getUniqueId() : CONSOLE;
    }

    /**
     * Attaches /tick zone to vanilla /tick dispatcher.
     */
    public static void attachToVanillaTick(final ZoneService service) {
        try {
            final net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
            if (server == null) {
                return;
            }
            final var dispatcher = server.getCommands().getDispatcher();
            final var tickNode = dispatcher.getRoot().getChild("tick");
            if (tickNode != null) {
                tickNode.addChild(vanillaZoneNode(service).build());
            }
        } catch (final Throwable ignored) {
        }
    }

    /**
     * Paper Brigadier builder for /zone and /carpet zone.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> paperZoneNode(final ZoneService service, final String name) {
        return Commands.literal(name)
            .requires(source -> source.getSender().hasPermission(LabPermissions.TICK_ZONE)
                || source.getSender().hasPermission(LabPermissions.TICK_ZONE_ADMIN))
            .executes(ctx -> executeList(ctx.getSource().getSender(), service))
            .then(Commands.literal("list")
                .executes(ctx -> executeList(ctx.getSource().getSender(), service)))
            .then(Commands.literal("create")
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> executeCreate(ctx.getSource().getSender(), service,
                        StringArgumentType.getString(ctx, "name"), null))
                    .then(Commands.argument("world", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            for (final org.bukkit.World world : Bukkit.getWorlds()) {
                                builder.suggest(world.getName());
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> executeCreate(ctx.getSource().getSender(), service,
                            StringArgumentType.getString(ctx, "name"),
                            StringArgumentType.getString(ctx, "world"))))))
            .then(Commands.literal("remove")
                .executes(ctx -> executeRemoveFocused(ctx.getSource().getSender(), service))
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        for (final ZoneModel z : service.allZones()) {
                            if (z.name().toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
                                builder.suggest(z.name());
                            }
                        }
                        return builder.buildFuture();
                    })
                    .executes(ctx -> executeRemove(ctx.getSource().getSender(), service,
                        StringArgumentType.getString(ctx, "name")))))
            .then(Commands.literal("focus")
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        for (final ZoneModel z : service.allZones()) {
                            if (z.name().toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
                                builder.suggest(z.name());
                            }
                        }
                        return builder.buildFuture();
                    })
                    .executes(ctx -> executeFocus(ctx.getSource().getSender(), service,
                        StringArgumentType.getString(ctx, "name")))))
            .then(Commands.literal("unfocus")
                .executes(ctx -> executeUnfocus(ctx.getSource().getSender(), service)))
            .then(Commands.literal("box")
                .executes(ctx -> executeBoxList(ctx.getSource().getSender(), service))
                .then(Commands.literal("list")
                    .executes(ctx -> executeBoxList(ctx.getSource().getSender(), service)))
                .then(Commands.literal("cancelselect")
                    .executes(ctx -> executeBoxCancelSelect(ctx.getSource().getSender(), service)))
                .then(Commands.literal("remove")
                    .then(Commands.argument("target", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            builder.suggest("all");
                            if (ctx.getSource().getSender() instanceof final Player p) {
                                final ZoneModel focused = service.getFocusedZone(p.getUniqueId());
                                if (focused != null) {
                                    for (int i = 1; i <= focused.boxes().size(); i++) {
                                        builder.suggest(String.valueOf(i));
                                    }
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> executeBoxRemove(ctx.getSource().getSender(), service,
                            StringArgumentType.getString(ctx, "target")))))
                .then(Commands.literal("add")
                    .executes(ctx -> executeBoxAddWand(ctx.getSource().getSender(), service))
                    .then(Commands.argument("x1", IntegerArgumentType.integer())
                    .then(Commands.argument("y1", IntegerArgumentType.integer())
                    .then(Commands.argument("z1", IntegerArgumentType.integer())
                    .then(Commands.argument("x2", IntegerArgumentType.integer())
                    .then(Commands.argument("y2", IntegerArgumentType.integer())
                    .then(Commands.argument("z2", IntegerArgumentType.integer())
                        .executes(ctx -> executeBoxAddCoords(ctx.getSource().getSender(), service,
                            IntegerArgumentType.getInteger(ctx, "x1"),
                            IntegerArgumentType.getInteger(ctx, "y1"),
                            IntegerArgumentType.getInteger(ctx, "z1"),
                            IntegerArgumentType.getInteger(ctx, "x2"),
                            IntegerArgumentType.getInteger(ctx, "y2"),
                            IntegerArgumentType.getInteger(ctx, "z2")))))))))))
            .then(Commands.literal("clock")
                .then(Commands.literal("on")
                    .executes(ctx -> executeClock(ctx.getSource().getSender(), service, true)))
                .then(Commands.literal("off")
                    .executes(ctx -> executeClock(ctx.getSource().getSender(), service, false))))
            .then(Commands.literal("highlight")
                .then(Commands.literal("zone")
                    .executes(ctx -> executeHighlightZone(ctx.getSource().getSender(), service)))
                .then(Commands.literal("off")
                    .executes(ctx -> executeHighlightOff(ctx.getSource().getSender(), service)))
                .then(Commands.literal("at")
                    .then(Commands.argument("y", IntegerArgumentType.integer(-2032, 2031))
                        .executes(ctx -> executeHighlightAt(ctx.getSource().getSender(), service,
                            IntegerArgumentType.getInteger(ctx, "y"))))))
            .then(Commands.literal("member")
                .then(Commands.literal("add")
                    .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            for (final Player p : Bukkit.getOnlinePlayers()) {
                                if (p.getName().toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
                                    builder.suggest(p.getName());
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> executeMemberAdd(ctx.getSource().getSender(), service,
                            StringArgumentType.getString(ctx, "player")))))
                .then(Commands.literal("remove")
                    .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            if (ctx.getSource().getSender() instanceof final Player caller) {
                                final ZoneModel focused = service.getFocusedZone(caller.getUniqueId());
                                if (focused != null) {
                                    for (final UUID m : focused.members()) {
                                        final Player p = Bukkit.getPlayer(m);
                                        final String pName = p != null ? p.getName() : m.toString();
                                        if (pName.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
                                            builder.suggest(pName);
                                        }
                                    }
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> executeMemberRemove(ctx.getSource().getSender(), service,
                            StringArgumentType.getString(ctx, "player"))))))
            .then(Commands.literal("dump")
                .executes(ctx -> executeDumpFocused(ctx.getSource().getSender(), service, 100))
                .then(Commands.literal("status")
                    .executes(ctx -> executeDumpStatus(ctx.getSource().getSender())))
                .then(Commands.literal("stop")
                    .executes(ctx -> executeDumpStop(ctx.getSource().getSender())))
                .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 100000))
                    .executes(ctx -> executeDumpFocused(ctx.getSource().getSender(), service, IntegerArgumentType.getInteger(ctx, "ticks"))))
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        for (final ZoneModel z : service.allZones()) {
                            if (z.name().toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
                                builder.suggest(z.name());
                            }
                        }
                        return builder.buildFuture();
                    })
                    .executes(ctx -> executeDumpZone(ctx.getSource().getSender(), service, StringArgumentType.getString(ctx, "name"), 100))
                    .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 100000))
                        .executes(ctx -> executeDumpZone(ctx.getSource().getSender(), service, StringArgumentType.getString(ctx, "name"), IntegerArgumentType.getInteger(ctx, "ticks"))))))
            .then(Commands.literal("rate")
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        for (final ZoneModel z : service.allZones()) {
                            if (z.name().toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
                                builder.suggest(z.name());
                            }
                        }
                        return builder.buildFuture();
                    })
                    .then(Commands.argument("rate", FloatArgumentType.floatArg(0.1F, 10000.0F))
                        .executes(ctx -> executeRate(ctx.getSource().getSender(), service,
                            StringArgumentType.getString(ctx, "name"),
                            FloatArgumentType.getFloat(ctx, "rate"))))))
            .then(Commands.literal("freeze")
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        for (final ZoneModel z : service.allZones()) {
                            if (z.name().toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
                                builder.suggest(z.name());
                            }
                        }
                        return builder.buildFuture();
                    })
                    .executes(ctx -> executeFreezeToggle(ctx.getSource().getSender(), service,
                        StringArgumentType.getString(ctx, "name")))))
            .then(Commands.literal("unfreeze")
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        for (final ZoneModel z : service.allZones()) {
                            if (z.name().toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
                                builder.suggest(z.name());
                            }
                        }
                        return builder.buildFuture();
                    })
                    .executes(ctx -> executeFreeze(ctx.getSource().getSender(), service,
                        StringArgumentType.getString(ctx, "name"), false))))
            .then(Commands.literal("step")
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        for (final ZoneModel z : service.allZones()) {
                            if (z.name().toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
                                builder.suggest(z.name());
                            }
                        }
                        return builder.buildFuture();
                    })
                    .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 100000))
                        .executes(ctx -> executeStep(ctx.getSource().getSender(), service,
                            StringArgumentType.getString(ctx, "name"),
                            IntegerArgumentType.getInteger(ctx, "ticks"))))))
            .then(Commands.literal("sprint")
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        for (final ZoneModel z : service.allZones()) {
                            if (z.name().toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
                                builder.suggest(z.name());
                            }
                        }
                        return builder.buildFuture();
                    })
                    .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 100000))
                        .executes(ctx -> executeSprint(ctx.getSource().getSender(), service,
                            StringArgumentType.getString(ctx, "name"),
                            IntegerArgumentType.getInteger(ctx, "ticks"))))))
            .then(Commands.literal("warp")
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        for (final ZoneModel z : service.allZones()) {
                            if (z.name().toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
                                builder.suggest(z.name());
                            }
                        }
                        return builder.buildFuture();
                    })
                    .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 100000))
                        .executes(ctx -> executeSprint(ctx.getSource().getSender(), service,
                            StringArgumentType.getString(ctx, "name"),
                            IntegerArgumentType.getInteger(ctx, "ticks"))))));
    }

    /**
     * Vanilla Brigadier builder for attaching directly into vanilla /tick.
     */
    public static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> vanillaZoneNode(final ZoneService service) {
        return net.minecraft.commands.Commands.literal("zone")
            .requires(source -> source.getBukkitSender().hasPermission(LabPermissions.TICK_ZONE)
                || source.getBukkitSender().hasPermission(LabPermissions.TICK_ZONE_ADMIN))
            .executes(ctx -> executeList(ctx.getSource().getBukkitSender(), service))
            .then(net.minecraft.commands.Commands.literal("list")
                .executes(ctx -> executeList(ctx.getSource().getBukkitSender(), service)))
            .then(net.minecraft.commands.Commands.literal("create")
                .then(net.minecraft.commands.Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> executeCreate(ctx.getSource().getBukkitSender(), service,
                        StringArgumentType.getString(ctx, "name"), null))
                    .then(net.minecraft.commands.Commands.argument("world", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            for (final org.bukkit.World world : Bukkit.getWorlds()) {
                                builder.suggest(world.getName());
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> executeCreate(ctx.getSource().getBukkitSender(), service,
                            StringArgumentType.getString(ctx, "name"),
                            StringArgumentType.getString(ctx, "world"))))))
            .then(net.minecraft.commands.Commands.literal("remove")
                .executes(ctx -> executeRemoveFocused(ctx.getSource().getBukkitSender(), service))
                .then(net.minecraft.commands.Commands.argument("name", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        for (final ZoneModel z : service.allZones()) {
                            if (z.name().toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
                                builder.suggest(z.name());
                            }
                        }
                        return builder.buildFuture();
                    })
                    .executes(ctx -> executeRemove(ctx.getSource().getBukkitSender(), service,
                        StringArgumentType.getString(ctx, "name")))))
            .then(net.minecraft.commands.Commands.literal("focus")
                .then(net.minecraft.commands.Commands.argument("name", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        for (final ZoneModel z : service.allZones()) {
                            if (z.name().toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
                                builder.suggest(z.name());
                            }
                        }
                        return builder.buildFuture();
                    })
                    .executes(ctx -> executeFocus(ctx.getSource().getBukkitSender(), service,
                        StringArgumentType.getString(ctx, "name")))))
            .then(net.minecraft.commands.Commands.literal("unfocus")
                .executes(ctx -> executeUnfocus(ctx.getSource().getBukkitSender(), service)))
            .then(net.minecraft.commands.Commands.literal("box")
                .executes(ctx -> executeBoxList(ctx.getSource().getBukkitSender(), service))
                .then(net.minecraft.commands.Commands.literal("list")
                    .executes(ctx -> executeBoxList(ctx.getSource().getBukkitSender(), service)))
                .then(net.minecraft.commands.Commands.literal("cancelselect")
                    .executes(ctx -> executeBoxCancelSelect(ctx.getSource().getBukkitSender(), service)))
                .then(net.minecraft.commands.Commands.literal("remove")
                    .then(net.minecraft.commands.Commands.argument("target", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            builder.suggest("all");
                            if (ctx.getSource().getBukkitSender() instanceof final Player p) {
                                final ZoneModel focused = service.getFocusedZone(p.getUniqueId());
                                if (focused != null) {
                                    for (int i = 1; i <= focused.boxes().size(); i++) {
                                        builder.suggest(String.valueOf(i));
                                    }
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> executeBoxRemove(ctx.getSource().getBukkitSender(), service,
                            StringArgumentType.getString(ctx, "target")))))
                .then(net.minecraft.commands.Commands.literal("add")
                    .executes(ctx -> executeBoxAddWand(ctx.getSource().getBukkitSender(), service))
                    .then(net.minecraft.commands.Commands.argument("x1", IntegerArgumentType.integer())
                    .then(net.minecraft.commands.Commands.argument("y1", IntegerArgumentType.integer())
                    .then(net.minecraft.commands.Commands.argument("z1", IntegerArgumentType.integer())
                    .then(net.minecraft.commands.Commands.argument("x2", IntegerArgumentType.integer())
                    .then(net.minecraft.commands.Commands.argument("y2", IntegerArgumentType.integer())
                    .then(net.minecraft.commands.Commands.argument("z2", IntegerArgumentType.integer())
                        .executes(ctx -> executeBoxAddCoords(ctx.getSource().getBukkitSender(), service,
                            IntegerArgumentType.getInteger(ctx, "x1"),
                            IntegerArgumentType.getInteger(ctx, "y1"),
                            IntegerArgumentType.getInteger(ctx, "z1"),
                            IntegerArgumentType.getInteger(ctx, "x2"),
                            IntegerArgumentType.getInteger(ctx, "y2"),
                            IntegerArgumentType.getInteger(ctx, "z2")))))))))))
            .then(net.minecraft.commands.Commands.literal("clock")
                .then(net.minecraft.commands.Commands.literal("on")
                    .executes(ctx -> executeClock(ctx.getSource().getBukkitSender(), service, true)))
                .then(net.minecraft.commands.Commands.literal("off")
                    .executes(ctx -> executeClock(ctx.getSource().getBukkitSender(), service, false))))
            .then(net.minecraft.commands.Commands.literal("highlight")
                .then(net.minecraft.commands.Commands.literal("zone")
                    .executes(ctx -> executeHighlightZone(ctx.getSource().getBukkitSender(), service)))
                .then(net.minecraft.commands.Commands.literal("off")
                    .executes(ctx -> executeHighlightOff(ctx.getSource().getBukkitSender(), service)))
                .then(net.minecraft.commands.Commands.literal("at")
                    .then(net.minecraft.commands.Commands.argument("y", IntegerArgumentType.integer(-2032, 2031))
                        .executes(ctx -> executeHighlightAt(ctx.getSource().getBukkitSender(), service,
                            IntegerArgumentType.getInteger(ctx, "y"))))))
            .then(net.minecraft.commands.Commands.literal("member")
                .then(net.minecraft.commands.Commands.literal("add")
                    .then(net.minecraft.commands.Commands.argument("player", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            for (final Player p : Bukkit.getOnlinePlayers()) {
                                if (p.getName().toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
                                    builder.suggest(p.getName());
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> executeMemberAdd(ctx.getSource().getBukkitSender(), service,
                            StringArgumentType.getString(ctx, "player")))))
                .then(net.minecraft.commands.Commands.literal("remove")
                    .then(net.minecraft.commands.Commands.argument("player", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            if (ctx.getSource().getBukkitSender() instanceof final Player caller) {
                                final ZoneModel focused = service.getFocusedZone(caller.getUniqueId());
                                if (focused != null) {
                                    for (final UUID m : focused.members()) {
                                        final Player p = Bukkit.getPlayer(m);
                                        final String pName = p != null ? p.getName() : m.toString();
                                        if (pName.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
                                            builder.suggest(pName);
                                        }
                                    }
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> executeMemberRemove(ctx.getSource().getBukkitSender(), service,
                            StringArgumentType.getString(ctx, "player"))))))
            .then(net.minecraft.commands.Commands.literal("dump")
                .executes(ctx -> executeDumpFocused(ctx.getSource().getBukkitSender(), service, 100))
                .then(net.minecraft.commands.Commands.literal("status")
                    .executes(ctx -> executeDumpStatus(ctx.getSource().getBukkitSender())))
                .then(net.minecraft.commands.Commands.literal("stop")
                    .executes(ctx -> executeDumpStop(ctx.getSource().getBukkitSender())))
                .then(net.minecraft.commands.Commands.argument("ticks", IntegerArgumentType.integer(1, 100000))
                    .executes(ctx -> executeDumpFocused(ctx.getSource().getBukkitSender(), service, IntegerArgumentType.getInteger(ctx, "ticks"))))
                .then(net.minecraft.commands.Commands.argument("name", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        for (final ZoneModel z : service.allZones()) {
                            if (z.name().toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
                                builder.suggest(z.name());
                            }
                        }
                        return builder.buildFuture();
                    })
                    .executes(ctx -> executeDumpZone(ctx.getSource().getBukkitSender(), service, StringArgumentType.getString(ctx, "name"), 100))
                    .then(net.minecraft.commands.Commands.argument("ticks", IntegerArgumentType.integer(1, 100000))
                        .executes(ctx -> executeDumpZone(ctx.getSource().getBukkitSender(), service, StringArgumentType.getString(ctx, "name"), IntegerArgumentType.getInteger(ctx, "ticks"))))));
    }


    // --- Execution Logic ---

    /**
     * Creates a zone.
     *
     * <p>{@code worldName} is optional for a player - their own world is used, they become the
     * owner, and the new zone is focused. From the console it is required and there is no owner:
     * an ownerless zone is one every holder of {@code paperlab.tick.zone} can drive, which is
     * what a zone set up from a start-up script or from RCON has to be. Without this path the
     * console could list and drive zones but not create one, so a headless bench had no way to
     * get its first zone.
     */
    private static int executeCreate(final CommandSender sender, final ZoneService service,
                                     final String name, final @Nullable String worldName) {
        if (service.getZone(name) != null) {
            sender.sendMessage(Component.text("[Zone] A zone named '" + name + "' already exists.", NamedTextColor.RED));
            return 0;
        }

        if (worldName != null) {
            final org.bukkit.World world = Bukkit.getWorld(worldName);
            if (world == null) {
                sender.sendMessage(Component.text("[Zone] No world named '" + worldName + "'.", NamedTextColor.RED));
                return 0;
            }
            final UUID owner = sender instanceof final Player player ? player.getUniqueId() : null;
            service.createZone(name, world.getName(), owner);
            if (owner != null) {
                service.setFocus(owner, name);
                sender.sendMessage(Component.text("[Zone " + name + "] Created and focused in "
                    + world.getName() + ".", NamedTextColor.AQUA));
            } else {
                sender.sendMessage(Component.text("[Zone " + name + "] Created in " + world.getName()
                    + ", no owner. Add a box: /zone box add <x1> <y1> <z1> <x2> <y2> <z2>",
                    NamedTextColor.AQUA));
            }
            return 1;
        }

        if (!(sender instanceof final Player player)) {
            sender.sendMessage(Component.text("[Zone] From the console, name the world: "
                + "/zone create " + name + " <world>", NamedTextColor.RED));
            return 0;
        }

        final String world = player.getWorld().getName();
        service.createZone(name, world, player.getUniqueId());
        service.setFocus(player.getUniqueId(), name);
        sender.sendMessage(Component.text("[Zone " + name + "] Created and focused in " + world + ".", NamedTextColor.AQUA));
        return 1;
    }

    private static int executeRemoveFocused(final CommandSender sender, final ZoneService service) {
        if (!(sender instanceof final Player player)) {
            sender.sendMessage(Component.text("[Zone] Specify a zone name: /zone remove <name>", NamedTextColor.RED));
            return 0;
        }
        final ZoneModel focused = service.getFocusedZone(player.getUniqueId());
        if (focused == null) {
            sender.sendMessage(Component.text("[Zone] No zone in focus. Specify a name: /zone remove <name>", NamedTextColor.RED));
            return 0;
        }
        return executeRemove(sender, service, focused.name());
    }

    private static int executeRemove(final CommandSender sender, final ZoneService service, final String name) {
        final ZoneModel zone = service.getZone(name);
        if (zone == null) {
            sender.sendMessage(Component.text("[Zone] Zone '" + name + "' not found.", NamedTextColor.RED));
            return 0;
        }

        if (sender instanceof final Player player) {
            final boolean isOwner = zone.owner() != null && zone.owner().equals(player.getUniqueId());
            if (!isOwner && !player.hasPermission(LabPermissions.TICK_ZONE_ADMIN)) {
                sender.sendMessage(Component.text("[Zone] You do not own this zone.", NamedTextColor.RED));
                return 0;
            }
        }

        service.removeZone(name);
        sender.sendMessage(Component.text("[Zone " + name + "] Removed.", NamedTextColor.YELLOW));
        return 1;
    }

    private static int executeFocus(final CommandSender sender, final ZoneService service, final String name) {
        final Player player = resolvePlayer(sender);

        final ZoneModel zone = service.getZone(name);
        if (zone == null) {
            sender.sendMessage(Component.text("[Zone] Zone '" + name + "' not found.", NamedTextColor.RED));
            return 0;
        }

        if (player != null && !zone.isMember(player.getUniqueId())
            && !player.hasPermission(LabPermissions.TICK_ZONE_ADMIN)) {
            sender.sendMessage(Component.text("[Zone] You are not a member of this zone.", NamedTextColor.RED));
            return 0;
        }

        service.setFocus(actor(sender), name);
        sender.sendMessage(Component.text("[Zone " + name + "] Focused.", NamedTextColor.GREEN));
        return 1;
    }

    private static int executeUnfocus(final CommandSender sender, final ZoneService service) {
        final UUID who = actor(sender);
        final String current = service.getFocus(who);
        if (current == null) {
            sender.sendMessage(Component.text("[Zone] Not currently focused on any zone.", NamedTextColor.DARK_GRAY));
            return 0;
        }

        service.clearFocus(who);
        service.clearHighlight(who);
        sender.sendMessage(Component.text("[Zone] Unfocused from " + current + ".", NamedTextColor.YELLOW));
        return 1;
    }

    private static int executeList(final CommandSender sender, final ZoneService service) {
        final var zones = service.allZones();
        if (zones.isEmpty()) {
            sender.sendMessage(Component.text("[Zone] No tick zones created.", NamedTextColor.DARK_GRAY));
            return 0;
        }

        sender.sendMessage(Component.text("[Zone] Tick zones (" + zones.size() + "):", NamedTextColor.AQUA));
        for (final ZoneModel z : zones) {
            final String state = z.isFrozen() ? "frozen" : String.format(Locale.ROOT, "%.1f r/s", z.tickRate());
            final int boxCount = z.boxes().size();
            sender.sendMessage(Component.text(" - " + z.name() + " (" + z.world() + "): "
                + boxCount + (boxCount == 1 ? " box" : " boxes") + ", " + state, NamedTextColor.GRAY));
        }
        return zones.size();
    }

    private static int executeBoxAddCoords(final CommandSender sender, final ZoneService service,
                                           final int x1, final int y1, final int z1,
                                           final int x2, final int y2, final int z2) {
        final ZoneModel zone = requireFocusedZone(sender, service);
        if (zone == null) {
            return 0;
        }

        final ZoneBox box = ZoneBox.of(x1, y1, z1, x2, y2, z2);
        service.addBoxToZone(zone, box);
        final int index = zone.boxes().size();
        sender.sendMessage(Component.text("[Zone " + zone.name() + "] Box #" + index + " added ("
            + box.sizeX() + "x" + box.sizeY() + "x" + box.sizeZ() + ").", NamedTextColor.GREEN));
        return 1;
    }

    private static int executeBoxAddWand(final CommandSender sender, final ZoneService service) {
        if (!(sender instanceof final Player player)) {
            sender.sendMessage(Component.text("[Zone] Only players can use the selection wand.", NamedTextColor.RED));
            return 0;
        }

        final ZoneModel zone = requireFocusedZone(sender, service);
        if (zone == null) {
            return 0;
        }

        return service.startSelection(player, zone) ? 1 : 0;
    }

    private static int executeBoxRemove(final CommandSender sender, final ZoneService service, final String target) {
        final ZoneModel zone = requireFocusedZone(sender, service);
        if (zone == null) {
            return 0;
        }

        if (target.equalsIgnoreCase("all")) {
            service.clearBoxesFromZone(zone);
            sender.sendMessage(Component.text("[Zone " + zone.name() + "] All boxes removed.", NamedTextColor.YELLOW));
            return 1;
        }

        try {
            final int userIndex = Integer.parseInt(target);
            final int internalIndex = userIndex - 1;
            if (service.removeBoxFromZone(zone, internalIndex)) {
                sender.sendMessage(Component.text("[Zone " + zone.name() + "] Box #" + userIndex + " removed.", NamedTextColor.YELLOW));
                return 1;
            } else {
                sender.sendMessage(Component.text("[Zone] Invalid box index: " + userIndex, NamedTextColor.RED));
                return 0;
            }
        } catch (final NumberFormatException e) {
            sender.sendMessage(Component.text("[Zone] Specify a box index number or 'all'.", NamedTextColor.RED));
            return 0;
        }
    }

    private static int executeBoxCancelSelect(final CommandSender sender, final ZoneService service) {
        if (!(sender instanceof final Player player)) {
            sender.sendMessage(Component.text("[Zone] Only players can cancel selection.", NamedTextColor.RED));
            return 0;
        }

        if (service.cancelSelection(player, true)) {
            return 1;
        } else {
            sender.sendMessage(Component.text("[Zone] No active selection session.", NamedTextColor.DARK_GRAY));
            return 0;
        }
    }

    private static int executeBoxList(final CommandSender sender, final ZoneService service) {
        final ZoneModel zone = requireFocusedZone(sender, service);
        if (zone == null) {
            return 0;
        }

        final List<ZoneBox> boxes = zone.boxes();
        if (boxes.isEmpty()) {
            sender.sendMessage(Component.text("[Zone " + zone.name() + "] Has no boxes.", NamedTextColor.DARK_GRAY));
            return 0;
        }

        sender.sendMessage(Component.text("[Zone " + zone.name() + "] Boxes (" + boxes.size() + "):", NamedTextColor.AQUA));
        for (int i = 0; i < boxes.size(); i++) {
            final ZoneBox box = boxes.get(i);
            final int userIndex = i + 1;
            final Color c = ZoneBox.getDistinctColor(i);
            final TextColor textColor = TextColor.color(c.getRed(), c.getGreen(), c.getBlue());
            final String hex = ZoneBox.getHexColor(i);

            final Component line = Component.text(" #" + userIndex + ": ", NamedTextColor.GRAY)
                .append(Component.text(box.minX() + " " + box.minY() + " " + box.minZ() + " -> "
                    + box.maxX() + " " + box.maxY() + " " + box.maxZ(), NamedTextColor.WHITE))
                .append(Component.text(" (" + box.sizeX() + "x" + box.sizeY() + "x" + box.sizeZ() + ") ", NamedTextColor.DARK_GRAY))
                .append(Component.text("■ " + hex, textColor));

            sender.sendMessage(line);
        }
        return boxes.size();
    }

    /**
     * Draws the border of the focused zone for everyone, as block displays.
     *
     * <p>The rail goes at the caller's feet, because that is the height they are building at. From
     * the console there is no such height, so it goes at sea level.
     */
    private static int executeHighlightZone(final CommandSender sender, final ZoneService service) {
        final ZoneModel zone = requireFocusedZone(sender, service);
        if (zone == null) {
            return 0;
        }
        final int railY = sender instanceof final Player player ? player.getLocation().getBlockY() : 64;
        final int pieces = service.showOutline(zone, railY);
        if (pieces == 0) {
            sender.sendMessage(Component.text("[Zone " + zone.name() + "] Nothing to outline: no boxes yet.",
                NamedTextColor.RED));
            return 0;
        }
        sender.sendMessage(Component.text("[Zone " + zone.name() + "] Border shown at y=" + railY
            + " (" + pieces + " markers). Everyone can see it.", NamedTextColor.AQUA));
        return 1;
    }

    /** The same, with the rail at a height of your choosing. */
    private static int executeHighlightAt(final CommandSender sender, final ZoneService service, final int railY) {
        final ZoneModel zone = requireFocusedZone(sender, service);
        if (zone == null) {
            return 0;
        }
        final int pieces = service.showOutline(zone, railY);
        sender.sendMessage(Component.text("[Zone " + zone.name() + "] Border shown at y=" + railY
            + " (" + pieces + " markers).", NamedTextColor.AQUA));
        return 1;
    }

    private static int executeHighlightOff(final CommandSender sender, final ZoneService service) {
        final ZoneModel zone = requireFocusedZone(sender, service);
        if (zone == null) {
            return 0;
        }
        final int removed = service.hideOutline(zone);
        sender.sendMessage(Component.text("[Zone " + zone.name() + "] Border hidden (" + removed + " markers).",
            NamedTextColor.DARK_GRAY));
        return 1;
    }

    private static int executeMemberAdd(final CommandSender sender, final ZoneService service, final String playerName) {
        final ZoneModel zone = requireFocusedZone(sender, service);
        if (zone == null) {
            return 0;
        }

        if (sender instanceof final Player player) {
            final boolean isOwner = zone.owner() != null && zone.owner().equals(player.getUniqueId());
            if (!isOwner && !player.hasPermission(LabPermissions.TICK_ZONE_ADMIN)) {
                sender.sendMessage(Component.text("[Zone] Only the zone owner or admin can add members.", NamedTextColor.RED));
                return 0;
            }
        }

        final Player target = Bukkit.getPlayerExact(playerName);
        final UUID targetUuid;
        if (target != null) {
            targetUuid = target.getUniqueId();
        } else {
            final org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(playerName);
            if (offline != null) {
                targetUuid = offline.getUniqueId();
            } else {
                sender.sendMessage(Component.text("[Zone] Player '" + playerName + "' not found (must be online or known to the server).", NamedTextColor.RED));
                return 0;
            }
        }

        service.addMemberToZone(zone, targetUuid);
        sender.sendMessage(Component.text("[Zone " + zone.name() + "] Member " + playerName + " added.", NamedTextColor.GREEN));
        return 1;
    }

    private static int executeMemberRemove(final CommandSender sender, final ZoneService service, final String playerName) {
        final ZoneModel zone = requireFocusedZone(sender, service);
        if (zone == null) {
            return 0;
        }

        if (sender instanceof final Player player) {
            final boolean isOwner = zone.owner() != null && zone.owner().equals(player.getUniqueId());
            if (!isOwner && !player.hasPermission(LabPermissions.TICK_ZONE_ADMIN)) {
                sender.sendMessage(Component.text("[Zone] Only the zone owner or admin can remove members.", NamedTextColor.RED));
                return 0;
            }
        }

        final Player target = Bukkit.getPlayerExact(playerName);
        final UUID targetUuid;
        if (target != null) {
            targetUuid = target.getUniqueId();
        } else {
            final org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(playerName);
            if (offline != null) {
                targetUuid = offline.getUniqueId();
            } else {
                sender.sendMessage(Component.text("[Zone] Player '" + playerName + "' not found (must be online or known to the server).", NamedTextColor.RED));
                return 0;
            }
        }

        service.removeMemberFromZone(zone, targetUuid);
        sender.sendMessage(Component.text("[Zone " + zone.name() + "] Member " + playerName + " removed.", NamedTextColor.YELLOW));
        return 1;
    }

    /**
     * Lends the caller's client the focused zone's clock, or gives the world's back.
     *
     * <p>The client has one tick rate and normally it is the world's, which is why a zone's piston
     * teleports instead of travelling. Borrowing the zone's clock fixes the zone and breaks
     * everything else, the player's own movement included - so it is a switch rather than a default.
     */
    private static int executeClock(final CommandSender sender, final ZoneService service, final boolean on) {
        if (!(sender instanceof final Player player)) {
            sender.sendMessage(Component.text("[Zone] Only a player has a client to sync.", NamedTextColor.RED));
            return 0;
        }
        if (!CoreBridge.PRESENT) {
            sender.sendMessage(Component.text("[Zone] Needs our core.", NamedTextColor.RED));
            return 0;
        }
        if (!on) {
            ClockDelegate.clear(player);
            sender.sendMessage(Component.text("[Zone] Your client is back on the world's clock.",
                NamedTextColor.YELLOW));
            return 1;
        }
        final ZoneModel zone = requireFocusedZone(sender, service);
        if (zone == null) {
            return 0;
        }
        if (!ClockDelegate.sync(player, zone.name())) {
            sender.sendMessage(Component.text("[Zone] Zone is not known to the core yet.", NamedTextColor.RED));
            return 0;
        }
        sender.sendMessage(Component.text("[Zone " + zone.name() + "] Your client now runs on this zone's "
            + "clock. Animations inside the zone are right; everything else, your own movement "
            + "included, is not. /zone clock off to undo.", NamedTextColor.AQUA));
        return 1;
    }

    /** Isolated so the class still loads on stock Paper. */
    private static final class ClockDelegate {
        static boolean sync(final Player player, final String zoneName) {
            final io.papermc.paper.lab.zone.LabTickZone zone =
                io.papermc.paper.lab.zone.LabTickZones.findZone(zoneName);
            if (zone == null) {
                return false;
            }
            return io.papermc.paper.lab.zone.LabTickZones.syncClientToZone(
                ((org.bukkit.craftbukkit.entity.CraftPlayer) player).getHandle(), zone);
        }

        static void clear(final Player player) {
            io.papermc.paper.lab.zone.LabTickZones.clearClientClock(
                ((org.bukkit.craftbukkit.entity.CraftPlayer) player).getHandle());
        }
    }

    private static ZoneModel requireFocusedZone(final CommandSender sender, final ZoneService service) {
        final ZoneModel zone = service.getFocusedZone(actor(sender));
        if (zone == null) {
            sender.sendMessage(Component.text("[Zone] No zone in focus. Use /tick zone focus <name> first.", NamedTextColor.RED));
            return null;
        }

        if (sender instanceof final Player player
            && !zone.isMember(player.getUniqueId())
            && !player.hasPermission(LabPermissions.TICK_ZONE_ADMIN)) {
            sender.sendMessage(Component.text("[Zone] You do not have permission to modify this zone.", NamedTextColor.RED));
            return null;
        }

        return zone;
    }

    private static int executeDumpStatus(final CommandSender sender) {
        if (!CoreBridge.PRESENT) {
            sender.sendMessage(Component.text("Dump tool requires PaperLab Core.", NamedTextColor.RED));
            return 0;
        }
        sender.sendMessage(Component.text(io.papermc.paper.lab.dump.ZoneDumpManager.getStatus(), NamedTextColor.YELLOW));
        return 1;
    }

    private static int executeDumpStop(final CommandSender sender) {
        if (!CoreBridge.PRESENT) {
            sender.sendMessage(Component.text("Dump tool requires PaperLab Core.", NamedTextColor.RED));
            return 0;
        }
        sender.sendMessage(Component.text(io.papermc.paper.lab.dump.ZoneDumpManager.stopDump(), NamedTextColor.GOLD));
        return 1;
    }

    private static int executeDumpFocused(final CommandSender sender, final ZoneService service, final int ticks) {
        if (!CoreBridge.PRESENT) {
            sender.sendMessage(Component.text("Dump tool requires PaperLab Core.", NamedTextColor.RED));
            return 0;
        }
        if (!(sender instanceof final Player player)) {
            sender.sendMessage(Component.text("Specify a zone name when using console: /zone dump <name> [ticks]", NamedTextColor.RED));
            return 0;
        }
        final ZoneModel zone = service.getFocusedZone(player.getUniqueId());
        if (zone == null) {
            sender.sendMessage(Component.text("No zone focused. Use /zone focus <name> or /zone dump <name> [ticks].", NamedTextColor.RED));
            return 0;
        }
        return executeDumpZone(sender, service, zone.name(), ticks);
    }

    private static int executeDumpZone(final CommandSender sender, final ZoneService service, final String zoneName, final int ticks) {
        if (!CoreBridge.PRESENT) {
            sender.sendMessage(Component.text("Dump tool requires PaperLab Core.", NamedTextColor.RED));
            return 0;
        }
        final io.papermc.paper.lab.zone.LabTickZone coreZone = io.papermc.paper.lab.zone.LabTickZones.findZone(zoneName);
        if (coreZone == null) {
            sender.sendMessage(Component.text("Zone not found in core: " + zoneName, NamedTextColor.RED));
            return 0;
        }
        final String res = io.papermc.paper.lab.dump.ZoneDumpManager.startZoneDump(coreZone, ticks);
        sender.sendMessage(Component.text(res, NamedTextColor.GREEN));
        return 1;
    }

    private static int executeRate(final CommandSender sender, final ZoneService service, final String name, final float rate) {
        final ZoneModel zone = service.getZone(name);
        if (zone == null) {
            sender.sendMessage(Component.text("[Zone] Zone '" + name + "' not found.", NamedTextColor.RED));
            return 0;
        }
        service.setZoneTickRate(zone, rate);
        sender.sendMessage(Component.text("[Zone " + name + "] Tick rate set to " + rate + " r/s.", NamedTextColor.GREEN));
        return 1;
    }

    private static int executeFreezeToggle(final CommandSender sender, final ZoneService service, final String name) {
        final ZoneModel zone = service.getZone(name);
        if (zone == null) {
            sender.sendMessage(Component.text("[Zone] Zone '" + name + "' not found.", NamedTextColor.RED));
            return 0;
        }
        final boolean newFreeze = !zone.isFrozen();
        service.setZoneFrozen(zone, newFreeze);
        sender.sendMessage(Component.text("[Zone " + name + "] " + (newFreeze ? "Frozen." : "Unfrozen."),
            newFreeze ? NamedTextColor.AQUA : NamedTextColor.GREEN));
        return 1;
    }

    private static int executeFreeze(final CommandSender sender, final ZoneService service, final String name, final boolean freeze) {
        final ZoneModel zone = service.getZone(name);
        if (zone == null) {
            sender.sendMessage(Component.text("[Zone] Zone '" + name + "' not found.", NamedTextColor.RED));
            return 0;
        }
        service.setZoneFrozen(zone, freeze);
        sender.sendMessage(Component.text("[Zone " + name + "] " + (freeze ? "Frozen." : "Unfrozen."),
            freeze ? NamedTextColor.AQUA : NamedTextColor.GREEN));
        return 1;
    }

    private static int executeStep(final CommandSender sender, final ZoneService service, final String name, final int ticks) {
        final ZoneModel zone = service.getZone(name);
        if (zone == null) {
            sender.sendMessage(Component.text("[Zone] Zone '" + name + "' not found.", NamedTextColor.RED));
            return 0;
        }
        if (!zone.isFrozen()) {
            sender.sendMessage(Component.text("[Zone " + name + "] Zone is not frozen.", NamedTextColor.RED));
            return 0;
        }
        final boolean success = service.stepZone(zone, ticks);
        if (success) {
            sender.sendMessage(Component.text("[Zone " + name + "] Stepping " + ticks + " ticks.", NamedTextColor.AQUA));
            return 1;
        } else {
            sender.sendMessage(Component.text("[Zone " + name + "] Failed to step zone.", NamedTextColor.RED));
            return 0;
        }
    }

    private static int executeSprint(final CommandSender sender, final ZoneService service, final String name, final int ticks) {
        final ZoneModel zone = service.getZone(name);
        if (zone == null) {
            sender.sendMessage(Component.text("[Zone] Zone '" + name + "' not found.", NamedTextColor.RED));
            return 0;
        }
        final boolean success = service.sprintZone(zone, ticks);
        if (success) {
            sender.sendMessage(Component.text("[Zone " + name + "] Sprinting " + ticks + " ticks.", NamedTextColor.AQUA));
            return 1;
        } else {
            sender.sendMessage(Component.text("[Zone " + name + "] Failed to sprint zone.", NamedTextColor.RED));
            return 0;
        }
    }

    private static CommandSender getSender(final net.minecraft.commands.CommandSourceStack source) {
        if (source.getEntity() instanceof final net.minecraft.server.level.ServerPlayer sp) {
            return sp.getBukkitEntity();
        }
        return source.getBukkitSender();
    }

    private static Player resolvePlayer(final CommandSender sender) {
        if (sender instanceof final Player player) {
            return player;
        }
        return Bukkit.getPlayerExact(sender.getName());
    }
}
