import kotlin.test.Test
import kotlin.test.assertEquals

class MigrationQuotingCheckTest {

    @Test
    fun `fully quoted sql yields no violations`() {
        val sql = """create table "wqWebItem" ("userId" bigint, "occurredAt" timestamptz);"""
        assertEquals(emptyList(), findUnquotedIdentifiers(sql))
    }

    @Test
    fun `unquoted table name is reported`() {
        val sql = """create index on wqWebItem ("userId");"""
        assertEquals(listOf("wqWebItem"), findUnquotedIdentifiers(sql))
    }

    @Test
    fun `unquoted column inside otherwise quoted sql is reported`() {
        val sql = """create index on "wqWebItem" (occurredAt);"""
        assertEquals(listOf("occurredAt"), findUnquotedIdentifiers(sql))
    }

    @Test
    fun `line comments are ignored`() {
        val sql = """
            -- wqWebItem is partitioned by occurredAt
            create table "wqWebItem" ("userId" bigint);
        """.trimIndent()
        assertEquals(emptyList(), findUnquotedIdentifiers(sql))
    }

    @Test
    fun `block comments are ignored`() {
        val sql = """
            /* wqWebItem block comment mentions camelCase occurredAt */
            create table "wqWebItem" ("userId" bigint);
        """.trimIndent()
        assertEquals(emptyList(), findUnquotedIdentifiers(sql))
    }

    @Test
    fun `camelCase word inside a single-quoted literal is not reported`() {
        val sql = """insert into "wqTable" ("col") values ('camelCaseValue');"""
        assertEquals(emptyList(), findUnquotedIdentifiers(sql))
    }

    @Test
    fun `escaped quote inside a string literal does not end it early`() {
        val sql = """insert into "wqTable" values ('it''s a camelCaseWord here');"""
        assertEquals(emptyList(), findUnquotedIdentifiers(sql))
    }

    @Test
    fun `dollar-quoted function body with camelCase locals is not reported`() {
        val tag = "${'$'}body${'$'}"
        val sql = """
            create function "wqFn"() returns void as $tag
            declare
                localVar int;
            begin
                localVar := 1;
            end;
            $tag language plpgsql;
        """.trimIndent()
        assertEquals(emptyList(), findUnquotedIdentifiers(sql))
    }

    @Test
    fun `double dash inside a string literal is not treated as a comment`() {
        val sql = """insert into "wqTable" values ('a -- not a comment'); create index on wqFoo ("userId");"""
        assertEquals(listOf("wqFoo"), findUnquotedIdentifiers(sql))
    }

    @Test
    fun `camelCase identifier with a digit in the hump is reported`() {
        val sql = """create index on "t" (item2Id);"""
        assertEquals(listOf("item2Id"), findUnquotedIdentifiers(sql))
    }
}
