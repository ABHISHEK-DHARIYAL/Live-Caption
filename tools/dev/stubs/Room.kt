package androidx.room
import android.content.Context
import kotlin.reflect.KClass
annotation class Entity(val tableName: String = "", val primaryKeys: Array<String> = [], val foreignKeys: Array<ForeignKey> = [], val indices: Array<Index> = [])
annotation class ForeignKey(val entity: KClass<*>, val parentColumns: Array<String>, val childColumns: Array<String>, val onDelete: Int = 1) { companion object { const val CASCADE = 5 } }
annotation class Index(val value: Array<String> = [], val unique: Boolean = false)
annotation class PrimaryKey(val autoGenerate: Boolean = false)
annotation class Dao
annotation class Query(val value: String)
annotation class Upsert
annotation class Insert(val onConflict: Int = 3)
annotation class Transaction
annotation class Database(val entities: Array<KClass<*>>, val version: Int, val exportSchema: Boolean = true)
object OnConflictStrategy { const val IGNORE = 5; const val REPLACE = 1 }
abstract class RoomDatabase { class Builder<T : RoomDatabase> { fun build(): T = throw NotImplementedError() } }
object Room { fun <T : RoomDatabase> databaseBuilder(ctx: Context, klass: Class<T>, name: String) = RoomDatabase.Builder<T>() }
