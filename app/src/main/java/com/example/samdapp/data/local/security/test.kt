import net.zetetic.database.sqlcipher.SQLiteDatabase
fun main() {
    val methods = SQLiteDatabase::class.java.methods
    methods.filter { it.name.startsWith("open") }.forEach {
        println(it.name + "(" + it.parameterTypes.joinToString { p -> p.simpleName } + ")")
    }
}
