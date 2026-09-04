package dev.ujhhgtg.reflekt.utils

import java.lang.invoke.MethodType
import java.lang.reflect.Method
import kotlin.jvm.internal.CallableReference
import kotlin.jvm.internal.ClassBasedDeclarationContainer
import kotlin.reflect.KFunction

// provides a O(1)-ish way primarily for looking up external stub java methods
// we don't use findJavaDeclaration here since it iterates through all declaredMethods and calculates their signatures,
// where our 'parse descriptor and getDeclaredMethod' way should be slightly faster
// (anyways this is more like playing around with kotlin internals instead of an actual useful utility..?)
//
// update:
// so yeah... if you read the jvm source, you will surprisingly learn that getDeclaredMethod is STILL an O(N) iteration,
// and realize that this is still a boring O(N) scan and is pretty equivalent to kotin-stdlib's findJavaDeclaration,
// the only micro-optimization is jdk's own caching which saves tiny allocation
// so generally i should delete it and just use stdlib's equivalence, but i'm gonna keep this for fun lol
// also i think it's very obvious why i don't use javaMethod - it requires the bloated kotlin-reflekt and proguard/r8 is often unhappy with it
val KFunction<*>.fastJavaMethod: Method?
    get() {
        val ref = this as? CallableReference ?: return null
        val owner =
            ref.owner as? ClassBasedDeclarationContainer ?: return null

        val ownerClass = owner.jClass
        val signature = ref.signature

        val lParen = signature.indexOf('(')
        if (lParen <= 0)
            return null

        val jvmName = signature.substring(0, lParen)

        if (jvmName == "<init>")
            return null

        val methodType = MethodType.fromMethodDescriptorString(
            signature.substring(lParen),
            ownerClass.classLoader,
        )

        val parameterTypes = methodType.parameterArray()
        val returnType = methodType.returnType()

        val method = try {
            // fast path
            ownerClass.getDeclaredMethod(
                jvmName,
                *parameterTypes,
            )
        } catch (_: NoSuchMethodException) {
            return findExactMethod(
                ownerClass,
                jvmName,
                parameterTypes,
                returnType,
            )
        }

        if (method.returnType == returnType)
            return method

        return findExactMethod(
            ownerClass,
            jvmName,
            parameterTypes,
            returnType,
        )
    }

private fun findExactMethod(
    owner: Class<*>,
    name: String,
    parameterTypes: Array<Class<*>>,
    returnType: Class<*>,
): Method? =
    owner.declaredMethods.firstOrNull { method ->
        method.name == name &&
                method.returnType == returnType &&
                method.parameterTypes.contentEquals(parameterTypes)
    }
