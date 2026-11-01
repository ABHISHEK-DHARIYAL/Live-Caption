package kotlinx.coroutines.flow
inline fun <T> MutableStateFlow<T>.update(function: (T) -> T) { while (true) { val p = value; if (compareAndSet(p, function(p))) return } }
