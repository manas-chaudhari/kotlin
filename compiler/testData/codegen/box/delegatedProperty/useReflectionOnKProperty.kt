import kotlin.reflect.KProperty

class Delegate {
    operator fun getValue(t: Any?, p: KProperty<*>): String {
        p.parameters
        p.returnType
        p.annotations
        return p.toString()
    }
}

val prop: String by Delegate()

fun box() = if (prop == "val prop: kotlin.String") "OK" else "Fail: $prop"
