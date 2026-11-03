package wqs.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Plan D1: the transaction-type enum, its directions and its open-endedness. */
class TransactionTypeTest {

    @Test
    void play_codes_are_play() {
        for (String code : Set.of("TT_BUY", "BUY", "BET", "TT_PERIODIC_CHARGE")) {
            assertThat(TransactionType.directionOf(code)).as(code).isEqualTo(MoneyDirection.PLAY);
        }
    }

    @Test
    void win_codes_are_win() {
        for (String code : Set.of("TT_PRIZE_IN", "PRIZE")) {
            assertThat(TransactionType.directionOf(code)).as(code).isEqualTo(MoneyDirection.WIN);
        }
    }

    @Test
    void every_other_known_code_is_neutral() {
        Set<String> moving = Set.of("TT_BUY", "BUY", "BET", "TT_PERIODIC_CHARGE", "TT_PRIZE_IN", "PRIZE");
        Arrays.stream(TransactionType.values())
                .filter(t -> !moving.contains(t.code()))
                .forEach(t -> assertThat(t.direction()).as(t.code()).isEqualTo(MoneyDirection.NEUTRAL));
        assertThat(TransactionType.directionOf("TT_CANCEL")).isEqualTo(MoneyDirection.NEUTRAL);
        assertThat(TransactionType.directionOf("TT_BUY_UNDO")).isEqualTo(MoneyDirection.NEUTRAL);
    }

    @Test
    void an_unknown_code_is_neutral_never_an_error() {
        assertThat(TransactionType.directionOf("TT_NEVER_SEEN")).isEqualTo(MoneyDirection.NEUTRAL);
        assertThat(TransactionType.directionOf("tt_buy")).as("codes are exact").isEqualTo(MoneyDirection.NEUTRAL);
        assertThat(TransactionType.directionOf(null)).isEqualTo(MoneyDirection.NEUTRAL);
    }

    @Test
    void the_delta_decoder_synthetic_codes_are_listed() {
        assertThat(TransactionType.valueOf("PAYMENT").direction()).isEqualTo(MoneyDirection.NEUTRAL);
        assertThat(TransactionType.valueOf("CLAIMABLE_PRIZE").direction()).isEqualTo(MoneyDirection.NEUTRAL);
    }

    @Test
    void every_code_fits_the_wire_format() {
        for (TransactionType t : TransactionType.values()) {
            assertThat(t.code()).matches("[A-Z0-9_]{1,64}");
        }
    }

    /** Every code the source schema documents for walAccountTransactions.transactionType is listed. */
    @Test
    void lists_every_documented_account_transaction_type() throws IOException {
        Path schema = Stream.of(Path.of("docs/reference/source-schema.sql"), Path.of("../docs/reference/source-schema.sql"))
                .filter(Files::isRegularFile).findFirst()
                .orElseThrow(() -> new IllegalStateException("source-schema.sql not found from " + Path.of("").toAbsolutePath()));
        String sql = Files.readString(schema);
        Matcher comment = Pattern.compile(
                "comment on column public\\.walaccounttransactions\\.transactiontype is '(.*?)';", Pattern.DOTALL)
                .matcher(sql);
        assertThat(comment.find()).as("transactiontype comment").isTrue();
        Set<String> documented = new LinkedHashSet<>();
        Matcher line = Pattern.compile("(?m)^\\s+([A-Z0-9_, ]+?) \\(").matcher(comment.group(1));
        while (line.find()) {
            for (String code : line.group(1).split(",")) {
                documented.add(code.strip());
            }
        }
        assertThat(documented).hasSize(56);
        Set<String> listed = new LinkedHashSet<>();
        Arrays.stream(TransactionType.values()).forEach(t -> listed.add(t.code()));
        assertThat(listed).containsAll(documented);
    }
}
