package remappedParameterInDefaultInline

inline fun inlineDefault(p: Int = 1, f: (Int) -> Unit) {
    // EXPRESSION: p
    // RESULT: 1: I
    //Breakpoint!
    f(p)
}

fun main(args: Array<String>) {
    inlineDefault { it }
}