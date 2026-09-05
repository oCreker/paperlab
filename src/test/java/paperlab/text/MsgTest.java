package paperlab.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/**
 * Проверки порта карпетовской разметки.
 *
 * <p>Главный из них — {@link #modifierDoesNotDuplicateText()}. Первая версия
 * {@link Msg} повторяла Carpet буквально: там служебное поле правит компонент на месте,
 * и признаком служило совпадение ссылок. У Adventure компоненты неизменяемые, ссылка
 * всегда новая, и каждый кусок с кнопкой или подсказкой молча дублировался. В логе это
 * выглядело как {@code - fakePlayerNameSuffix - fakePlayerNameSuffix - fakePlayerNameSuffix}.
 */
class MsgTest {

    private static String plain(final Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void plainFieldKeepsTextAfterStyle() {
        assertEquals("hello", plain(Msg.c("w hello")));
    }

    @Test
    void leadingSpaceMeansWhite() {
        // " - foo" превращается в "w - foo", и первый пробел съедается как разделитель
        // стиля и текста. Поэтому в Carpet отступ пишут двумя пробелами: "w  - foo".
        final Component one = Msg.c(" - foo");
        assertEquals("- foo", plain(one));
        assertEquals(NamedTextColor.WHITE, one.children().get(0).color());
        assertEquals(" - foo", plain(Msg.c("w  - foo")));
    }

    @Test
    void modifierDoesNotDuplicateText() {
        final Component out = Msg.c("w rule", "!/carpet rule", "^g hint");
        assertEquals("rule", plain(out));
    }

    @Test
    void modifierAttachesEventToPreviousPart() {
        final Component out = Msg.c("w rule", "!/carpet rule");
        final Component part = out.children().get(0);
        final ClickEvent click = part.clickEvent();
        assertNotNull(click);
        assertEquals(ClickEvent.Action.RUN_COMMAND, click.action());
        assertTrue(click.toString().contains("/carpet rule"), click.toString());
    }

    @Test
    void severalFieldsConcatenateInOrder() {
        assertEquals("a b", plain(Msg.c("w a", "g  b")));
    }

    @Test
    void lastColourCodeWins() {
        // "lb" — зелёный и жирный: буква цвета одна, b это начертание.
        final Component out = Msg.c("lb x");
        final Component part = out.children().get(0);
        assertEquals(NamedTextColor.GREEN, part.color());
        assertEquals(TextDecoration.State.TRUE, part.decoration(TextDecoration.BOLD));
    }

    @Test
    void hexColourIsParsed() {
        final Component part = Msg.c("#ff8800 x").children().get(0);
        assertNotNull(part.color());
        assertEquals(0xff8800, part.color().value());
    }

    @Test
    void emptyFieldProducesEmptyLine() {
        assertEquals("", plain(Msg.c("")));
    }

    @Test
    void modifierOnFirstFieldIsTreatedAsText() {
        // Вешать событие не на что — Carpet в этом случае создаёт обычный компонент.
        assertTrue(plain(Msg.c("^g hint")).contains("hint"));
    }

    @Test
    void heatmapMatchesCarpetThresholds() {
        assertEquals("e", Msg.heatmap(0.0D, 50.0D));
        assertEquals("y", Msg.heatmap(30.0D, 50.0D));
        assertEquals("r", Msg.heatmap(45.0D, 50.0D));
        assertEquals("m", Msg.heatmap(60.0D, 50.0D));
    }
}
