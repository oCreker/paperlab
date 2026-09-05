package paperlab.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import paperlab.log.LabHud;
import paperlab.log.LabLogger;
import paperlab.log.LabLoggers;
import paperlab.text.Msg;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /log} — подписки HUD, как в Carpet.
 *
 * <pre>
 * /log                        что есть и на что подписан
 * /log tps                    вкл/выкл
 * /log mobcaps [ник] [full]
 * /log counter &lt;цвет&gt; [full]
 * /log spawn [причина]
 * /log &lt;имя&gt; clear            снять подписки этого логгера
 * /log clear                  снять всё
 * </pre>
 *
 * Подписок на один логгер может быть несколько — по одной на цель. Повторная команда
 * с той же целью выключает её, с другим флагом — заменяет.
 */
public final class LabLogCommand {

    private LabLogCommand() {
    }

    /** {@value} — подпись в списке команд. */
    public static final String HELP = "tab-list subscriptions";

    public static LiteralArgumentBuilder<CommandSourceStack> node(final String name) {
        return Commands.literal(name)
                .requires(source -> source.getSender() instanceof Player
                    && source.getSender().hasPermission(LabPermissions.LOG))
                .executes(ctx -> list(ctx.getSource()))
                .then(Commands.literal("clear").executes(ctx -> clearAll(ctx.getSource())))
                .then(
                    Commands.argument("name", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            for (final LabLogger candidate : LabLoggers.all()) {
                                if (ctx.getSource().getSender()
                                    .hasPermission(LabLoggers.permissionOf(candidate))) {
                                    builder.suggest(candidate.name());
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> toggle(ctx, null))
                        .then(
                            Commands.argument("target", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    suggestions(StringArgumentType.getString(ctx, "name"))
                                        .forEach(builder::suggest);
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> toggle(ctx, StringArgumentType.getString(ctx, "target")))
                                .then(
                                    Commands.argument("flag", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            builder.suggest("full");
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> toggle(ctx,
                                            StringArgumentType.getString(ctx, "target")
                                                + " " + StringArgumentType.getString(ctx, "flag")))
                                )
                        )
                )
        ;
    }

    private static List<String> suggestions(final String loggerName) {
        final LabLogger logger = LabLoggers.get(loggerName);
        final List<String> opts = new ArrayList<>();
        if (logger != null) {
            if (logger == LabLoggers.MOBCAPS) {
                opts.add("full");
                Bukkit.getOnlinePlayers().forEach(p -> opts.add(p.getName()));
            } else if (logger == LabLoggers.COUNTER) {
                opts.add("full");
                for (final net.minecraft.world.item.DyeColor colour
                    : net.minecraft.world.item.DyeColor.values()) {
                    opts.add(colour.getName());
                }
            } else if (logger == LabLoggers.SPAWN) {
                opts.addAll(paperlab.spawn.SpawnView.options());
            } else if (logger == LabLoggers.ITEM) {
                opts.addAll(logger.options());
            } else if (logger == LabLoggers.MICROTIMING) {
                opts.addAll(logger.options());
            } else if (logger == LabLoggers.MOVEMENT) {
                opts.addAll(logger.options());
                opts.add("@s");
                Bukkit.getOnlinePlayers().forEach(p -> opts.add(p.getName()));
            } else if (logger.freeform()) {
                opts.add("full");
            } else {
                opts.addAll(logger.options());
            }
        }
        opts.add("clear");
        return opts;
    }

    /**
     * Список логгеров — раскладка Carpet: разделитель, заголовок, строка на логгер
     * с кнопками опций и крестиком отписки.
     *
     * <p>Отличие от Carpet ровно одно и оно от нашей модели подписок: у Carpet опция
     * у логгера одна, у нас их может быть несколько (мобкапы сразу нескольких игроков).
     * Поэтому подсвечены зелёным <b>все</b> активные, а не одна.
     */
    private static int list(final CommandSourceStack source) {
        final Player player = (Player) source.getSender();
        Msg.m(player, "w _____________________");
        Msg.m(player, "w Available logging options:");
        for (final LabLogger logger : LabLoggers.all()) {
            if (!player.hasPermission(LabLoggers.permissionOf(logger))) {
                continue;
            }
            final String name = logger.name();
            final Collection<String> active = logger.optionsFor(player.getName());
            final boolean subscribed = !active.isEmpty();
            final String colour = subscribed ? "w" : "g";

            final List<Object> comp = new ArrayList<>();
            comp.add("w  - " + name + ": ");

            final List<String> buttons = buttons(logger, active);
            if (buttons.isEmpty()) {
                if (subscribed) {
                    comp.add("l Subscribed ");
                } else {
                    comp.add(colour + " [Subscribe] ");
                    comp.add("^w subscribe to " + name);
                    comp.add("!/log " + name);
                }
            } else {
                for (final String option : buttons) {
                    if (containsIgnoreCase(active, option)) {
                        comp.add("l [" + option + "] ");
                    } else {
                        comp.add(colour + " [" + option + "] ");
                        comp.add("^w subscribe to " + name + " " + option);
                        comp.add("!/log " + name + " " + option);
                    }
                }
            }
            if (subscribed) {
                comp.add("nb [X]");
                comp.add("^w Click to unsubscribe");
                comp.add("!/log " + name + " clear");
            }
            Msg.m(player, comp.toArray());
        }
        return 1;
    }

    /**
     * Какие кнопки показать у логгера: его опции плюс активные подписки, которых
     * в списке опций нет.
     *
     * <p>Второе — не мелочь: у мобкапов цель это ник, списком его не предусмотреть,
     * и без этого собственная подписка не имела бы кнопки отключения.
     */
    private static List<String> buttons(final LabLogger logger, final Collection<String> active) {
        final List<String> out = new ArrayList<>();
        for (final String option : suggestions(logger.name())) {
            if (!option.equals("clear") && !containsIgnoreCase(out, option)) {
                out.add(option);
            }
        }
        for (final String option : active) {
            if (!containsIgnoreCase(out, option)) {
                out.add(option);
            }
        }
        return out;
    }

    private static boolean containsIgnoreCase(final Collection<String> haystack, final String needle) {
        for (final String candidate : haystack) {
            if (candidate.equalsIgnoreCase(needle)) {
                return true;
            }
        }
        return false;
    }

    private static int toggle(final CommandContext<CommandSourceStack> ctx, final @Nullable String option) {
        final Player player = (Player) ctx.getSource().getSender();
        final String loggerName = StringArgumentType.getString(ctx, "name");

        final LabLogger logger = LabLoggers.get(loggerName);
        if (logger == null) {
            Msg.m(player, "r Unknown logger: ", "rb " + loggerName);
            return 0;
        }
        // Логгер — аргумент, а не литерал, поэтому право проверяется здесь, а не в requires.
        final String permission = LabLoggers.permissionOf(logger);
        if (!player.hasPermission(permission)) {
            Msg.m(player, "r Missing permission: ", "rb " + permission);
            return 0;
        }

        if (option != null && option.equalsIgnoreCase("clear")) {
            logger.unsubscribeAll(player.getName());
            refresh(player);
            Msg.m(player, "gi Unsubscribed from " + logger.name());
            return 1;
        }

        final boolean on = logger.toggle(player.getName(), option);
        refresh(player);
        final String suffix = option == null || option.isBlank() ? "" : "(" + option + ")";
        Msg.m(player, "gi " + (on ? "Subscribed to " : "Unsubscribed from ")
            + logger.name() + suffix);
        return 1;
    }

    private static int clearAll(final CommandSourceStack source) {
        final Player player = (Player) source.getSender();
        LabLoggers.unsubscribeAll(player.getName());
        LabHud.clear(player);
        Msg.m(player, "gi Unsubscribed from all logs");
        return 1;
    }


    /** Если подписок больше нет — убрать футер сразу. */
    private static void refresh(final Player player) {
        for (final LabLogger logger : LabLoggers.all()) {
            if (logger.subscribed(player.getName())) {
                return;
            }
        }
        LabHud.clear(player);
    }
}
