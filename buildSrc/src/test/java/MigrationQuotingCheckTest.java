import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class MigrationQuotingCheckTest {

    private static List<String> find(String sql) {
        return MigrationQuotingCheck.findUnquotedIdentifiers(sql);
    }

    @Test
    void fullyQuotedSqlYieldsNoViolations() {
        String sql = "create table \"wqPrjWebTransaction\" (\"userId\" bigint, \"occurredAt\" timestamptz);";
        assertEquals(List.of(), find(sql));
    }

    @Test
    void unquotedTableNameIsReported() {
        String sql = "create index on wqPrjWebTransaction (\"userId\");";
        assertEquals(List.of("wqPrjWebTransaction"), find(sql));
    }

    @Test
    void unquotedColumnInsideOtherwiseQuotedSqlIsReported() {
        String sql = "create index on \"wqPrjWebTransaction\" (occurredAt);";
        assertEquals(List.of("occurredAt"), find(sql));
    }

    @Test
    void lineCommentsAreIgnored() {
        String sql = """
                -- wqPrjWebTransaction is partitioned by occurredAt
                create table "wqPrjWebTransaction" ("userId" bigint);""";
        assertEquals(List.of(), find(sql));
    }

    @Test
    void blockCommentsAreIgnored() {
        String sql = """
                /* wqPrjWebTransaction block comment mentions camelCase occurredAt */
                create table "wqPrjWebTransaction" ("userId" bigint);""";
        assertEquals(List.of(), find(sql));
    }

    @Test
    void camelCaseWordInsideASingleQuotedLiteralIsNotReported() {
        String sql = "insert into \"wqTable\" (\"col\") values ('camelCaseValue');";
        assertEquals(List.of(), find(sql));
    }

    @Test
    void escapedQuoteInsideAStringLiteralDoesNotEndItEarly() {
        String sql = "insert into \"wqTable\" values ('it''s a camelCaseWord here');";
        assertEquals(List.of(), find(sql));
    }

    @Test
    void dollarQuotedFunctionBodyWithCamelCaseLocalsIsNotReported() {
        String tag = "$body$";
        String sql = """
                create function "wqFn"() returns void as %s
                declare
                    localVar int;
                begin
                    localVar := 1;
                end;
                %s language plpgsql;""".formatted(tag, tag);
        assertEquals(List.of(), find(sql));
    }

    @Test
    void doubleDashInsideAStringLiteralIsNotTreatedAsAComment() {
        String sql = "insert into \"wqTable\" values ('a -- not a comment'); create index on wqFoo (\"userId\");";
        assertEquals(List.of("wqFoo"), find(sql));
    }

    @Test
    void camelCaseIdentifierWithADigitInTheHumpIsReported() {
        String sql = "create index on \"t\" (transaction2Id);";
        assertEquals(List.of("transaction2Id"), find(sql));
    }
}
