// PROBLEM: none
// WITH_STDLIB
open class A(init: A.() -> Unit) {
    val prop: String = ""
}

object B : A({})

object C : A({
    fun foo() = <caret>B.prop.toString()
})

// IGNORE_FIR