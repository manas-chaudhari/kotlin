package foo

// NOTE THIS FILE IS AUTO-GENERATED by the generateTestDataForReservedWords.kt. DO NOT EDIT!

data class DataClass(var `do`: String) {
    init {
        testNotRenamed("do", { `do` })
    }
}

fun box(): String {
    DataClass("123")

    return "OK"
}