package foo

// NOTE THIS FILE IS AUTO-GENERATED by the generateTestDataForReservedWords.kt. DO NOT EDIT!

interface Trait {
    fun `package`()
}

class TraitImpl : Trait {
    override fun `package`() {
        `package`()
    }
}

class TestDelegate : Trait by TraitImpl() {
    fun test() {
        testNotRenamed("package", { ::`package` })
    }
}

fun box(): String {
    TestDelegate().test()

    return "OK"
}