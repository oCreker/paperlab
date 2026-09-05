package paperlab.text;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Порт мини-языка разметки Carpet (его {@code carpet.utils.Messenger}) на Adventure.
 *
 * <p>Нужен он ради одного: чтобы наши сообщения выглядели <b>ровно</b> как карпетовские.
 * Пока каждое сообщение красилось руками, они и получались похожими, но не теми: другой
 * оттенок, другой отступ, кнопки не там. Имея тот же примитив, можно переносить строки
 * из исходников Carpet дословно — и расхождение становится невозможным, а не маловероятным.
 *
 * <h2>Язык</h2>
 * Каждое поле — строка вида {@code "<стиль> <текст>"}: до первого пробела стиль, дальше
 * текст. Стиль — набор букв, порядок в самой строке неважен, цвет побеждает последний
 * по порядку проверок:
 *
 * <pre>
 * i курсив    s зачёркнутый    u подчёркнутый     b жирный        o шифр
 * w белый     y жёлтый         m сиреневый        r красный       c бирюзовый
 * l зелёный   t синий          f тёмно-серый      g серый         d золотой
 * p фиолет.   n тёмно-красный  q тёмно-бирюзовый  e тёмно-зелёный v тёмно-синий
 * k чёрный    #rrggbb произвольный цвет
 * </pre>
 *
 * <p>Поле, начинающееся со служебного знака, не добавляет текст, а вешает событие на
 * <b>предыдущее</b> поле:
 *
 * <pre>
 * ^ подсказка при наведении    ! выполнить команду    ? подставить команду в строку
 * &#64; открыть ссылку              &amp; скопировать в буфер
 * </pre>
 *
 * <p>Строка, начинающаяся с пробела, считается белой: {@code " - foo"} — то же, что
 * {@code "w  - foo"}.
 *
 * <p>Отличие от Carpet чисто техническое: там компоненты изменяемые и событие вешается
 * правкой стиля на месте, у Adventure они неизменяемые — поэтому последний кусок держим
 * отдельно и подменяем целиком.
 */
public final class Msg {

    private Msg() {
    }

    /** Собрать одну строку из полей. */
    public static Component c(final Object... fields) {
        final List<Component> parts = new ArrayList<>();
        for (final Object field : fields) {
            if (field instanceof final Component ready) {
                parts.add(ready);
                continue;
            }
            final String text = String.valueOf(field);
            if (text.isEmpty()) {
                parts.add(Component.empty());
                continue;
            }
            String message = text;
            if (Character.isWhitespace(message.charAt(0))) {
                message = "w" + message;
            }
            // Служебное поле правит предыдущий кусок, обычное добавляет новый.
            //
            // Carpet отличает одно от другого сравнением ссылок: там компоненты
            // изменяемые, и модификатор возвращает тот же объект. У Adventure они
            // неизменяемые — ссылка всегда новая, и такая проверка молча дублирует
            // каждый кусок, к которому привязано событие. Поэтому смотрим на знак.
            if (!parts.isEmpty() && MODIFIERS.indexOf(message.charAt(0)) >= 0) {
                parts.set(parts.size() - 1, modify(parts.get(parts.size() - 1), message));
            } else {
                parts.add(literal(message));
            }
        }
        Component out = Component.empty();
        for (final Component part : parts) {
            out = out.append(part);
        }
        return out;
    }

    /** Собрать и отправить. */
    public static void m(final Audience target, final Object... fields) {
        if (target != null) {
            target.sendMessage(c(fields));
        }
    }

    /** Простой текст без разметки — как {@code Messenger.s}. */
    public static Component s(final String text) {
        return Component.text(text, NamedTextColor.WHITE);
    }

    // ------------------------------------------------------------------- разбор

    /** Знаки, которые вешают событие на предыдущий кусок вместо добавления текста. */
    private static final String MODIFIERS = "?!^@&";

    private static Component literal(final String message) {
        final int limit = message.indexOf(' ');
        final String desc = limit >= 0 ? message.substring(0, limit) : message;
        final String body = limit >= 0 ? message.substring(limit + 1) : "";
        return Component.text(body).style(parseStyle(desc));
    }

    private static Component modify(final Component previous, final String message) {
        final String payload = message.substring(1);
        return switch (message.charAt(0)) {
            case '?' -> previous.clickEvent(ClickEvent.suggestCommand(payload));
            case '!' -> previous.clickEvent(ClickEvent.runCommand(payload));
            case '^' -> previous.hoverEvent(HoverEvent.showText(c(payload)));
            case '@' -> previous.clickEvent(ClickEvent.openUrl(URI.create(payload).toString()));
            case '&' -> previous.clickEvent(ClickEvent.copyToClipboard(payload));
            default -> previous;
        };
    }

    /**
     * Разобрать набор букв стиля.
     *
     * <p>Как в Carpet: строка просматривается на каждый код целиком, поэтому порядок букв
     * в самом поле ничего не решает — решает порядок проверок здесь. Цвет по умолчанию белый.
     */
    static Style parseStyle(final String desc) {
        Style.Builder style = Style.style().color(NamedTextColor.WHITE);
        if (desc.indexOf('i') >= 0) {
            style = style.decorate(TextDecoration.ITALIC);
        }
        if (desc.indexOf('s') >= 0) {
            style = style.decorate(TextDecoration.STRIKETHROUGH);
        }
        if (desc.indexOf('u') >= 0) {
            style = style.decorate(TextDecoration.UNDERLINED);
        }
        if (desc.indexOf('b') >= 0) {
            style = style.decorate(TextDecoration.BOLD);
        }
        if (desc.indexOf('o') >= 0) {
            style = style.decorate(TextDecoration.OBFUSCATED);
        }
        for (final Code code : COLOURS) {
            if (desc.indexOf(code.letter()) >= 0) {
                style = style.color(code.colour());
            }
        }
        final int hash = desc.indexOf('#');
        if (hash >= 0 && desc.length() >= hash + 7) {
            final TextColor custom = TextColor.fromHexString(desc.substring(hash, hash + 7));
            if (custom != null) {
                style = style.color(custom);
            }
        }
        return style.build();
    }

    private record Code(char letter, NamedTextColor colour) {
    }

    /** Порядок тот же, что в перечислении Carpet: последнее совпадение побеждает. */
    private static final Code[] COLOURS = {
        new Code('w', NamedTextColor.WHITE),
        new Code('y', NamedTextColor.YELLOW),
        new Code('m', NamedTextColor.LIGHT_PURPLE),
        new Code('r', NamedTextColor.RED),
        new Code('c', NamedTextColor.AQUA),
        new Code('l', NamedTextColor.GREEN),
        new Code('t', NamedTextColor.BLUE),
        new Code('f', NamedTextColor.DARK_GRAY),
        new Code('g', NamedTextColor.GRAY),
        new Code('d', NamedTextColor.GOLD),
        new Code('p', NamedTextColor.DARK_PURPLE),
        new Code('n', NamedTextColor.DARK_RED),
        new Code('q', NamedTextColor.DARK_AQUA),
        new Code('e', NamedTextColor.DARK_GREEN),
        new Code('v', NamedTextColor.DARK_BLUE),
        new Code('k', NamedTextColor.BLACK),
    };

    /**
     * Цветовая шкала Carpet: насколько {@code actual} близко к {@code reference}.
     *
     * <p>Нужна там, где число само по себе ни о чём не говорит: мобкапы, счётчики.
     * «Почти упёрлись» и «упёрлись» должны читаться цветом, а не сравнением глазами.
     */
    public static String heatmap(final double actual, final double reference) {
        String colour = "g";
        if (actual >= 0.0D) {
            colour = "e";
        }
        if (actual > 0.5D * reference) {
            colour = "y";
        }
        if (actual > 0.8D * reference) {
            colour = "r";
        }
        if (actual > reference) {
            colour = "m";
        }
        return colour;
    }

    /** Цвет категории мобов — тот же, что в Carpet. */
    public static String creatureTypeColour(final String category) {
        return switch (category.toLowerCase(Locale.ROOT)) {
            case "monster" -> "n";
            case "creature" -> "e";
            case "ambient" -> "f";
            case "water_creature" -> "v";
            case "water_ambient" -> "q";
            default -> "w";
        };
    }

    /** Ссылка-телепорт на координаты — как {@code Messenger.tp}. */
    public static Component tp(final String style, final int x, final int y, final int z) {
        return Component.text(String.format("[ %d, %d, %d ]", x, y, z))
            .style(parseStyle(style))
            .clickEvent(ClickEvent.suggestCommand(String.format("/tp %d %d %d", x, y, z)))
            .hoverEvent(HoverEvent.showText(Component.text("Click to teleport")));
    }
}
