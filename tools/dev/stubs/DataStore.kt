package androidx.datastore.core
import kotlinx.coroutines.flow.Flow
interface DataStore<T> { val data: Flow<T> }
