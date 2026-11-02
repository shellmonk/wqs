import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction

private val LINE_COMMENT = Regex("--[^\n]*")
private val BLOCK_COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
private val QUOTED_SPAN = Regex("\"[^\"]*\"")

// Any camelCase identifier in our schema: wq-prefixed tables, or a column with an
// internal capital. Both are only legal in SQL when double-quoted.
private val CAMEL_IDENTIFIER = Regex("\\b(wq[A-Z]\\w*|[a-z]+[A-Z]\\w*)\\b")

// Column-ish words that appear in DDL keywords rather than as identifiers.
private val SQL_KEYWORDS = setOf("isClustered")

/**
 * Finds camelCase identifiers that are NOT double-quoted. Strategy: remove comments,
 * then remove every quoted span, then anything camelCase that survives was unquoted.
 */
fun findUnquotedIdentifiers(sql: String): List<String> {
    val withoutComments = sql
        .replace(BLOCK_COMMENT, " ")
        .replace(LINE_COMMENT, " ")
    val withoutQuoted = withoutComments.replace(QUOTED_SPAN, " ")

    return CAMEL_IDENTIFIER.findAll(withoutQuoted)
        .map { it.value }
        .filterNot { it in SQL_KEYWORDS }
        .distinct()
        .toList()
}

abstract class MigrationQuotingCheck : DefaultTask() {

    @get:InputFiles
    abstract val migrations: ConfigurableFileCollection

    @TaskAction
    fun check() {
        val failures = mutableListOf<String>()

        migrations.files.filter { it.extension == "sql" }.sorted().forEach { file: File ->
            val violations = findUnquotedIdentifiers(file.readText())
            if (violations.isNotEmpty()) {
                failures += "${file.name}: unquoted ${violations.joinToString(", ")}"
            }
        }

        if (failures.isNotEmpty()) {
            throw GradleException(
                "camelCase identifiers must be double-quoted (spec section 3):\n" +
                    failures.joinToString("\n") { "  - $it" }
            )
        }
    }
}
