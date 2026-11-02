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
}
