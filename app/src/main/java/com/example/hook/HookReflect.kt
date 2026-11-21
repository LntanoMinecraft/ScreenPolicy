package com.example.hook

import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Lightweight, zero-dependency Kotlin reflection helpers replacing legacy XposedHelpers
 * for Modern Xposed API 102 hooks. Performs exact declaredMethods / declaredFields lookups
 * without scanning or hooking broad overloads.
 */
internal object HookReflect {

    fun findClassOrNull(className: String, classLoader: ClassLoader?): Class<*>? {
        return try {
            if (classLoader != null) {
                Class.forName(className, false, classLoader)
            } else {
                Class.forName(className)
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun findFieldOrNull(clazz: Class<*>, fieldName: String): Field? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            try {
                val field = current.getDeclaredField(fieldName)
                field.isAccessible = true
                return field
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    fun getObjectFieldOrNull(target: Any, fieldName: String): Any? {
        val field = findFieldOrNull(target.javaClass, fieldName) ?: return null
        return try {
            field.get(target)
        } catch (_: Throwable) {
            null
        }
    }

    fun findMethodExactOrNull(
        clazz: Class<*>,
        methodName: String,
        vararg paramTypes: Class<*>,
    ): Method? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            try {
                val method = current.getDeclaredMethod(methodName, *paramTypes)
                method.isAccessible = true
                return method
            } catch (_: NoSuchMethodException) {
                current = current.superclass
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    fun callZeroArgOrNull(target: Any, methodName: String): Any? {
        val method = findMethodExactOrNull(target.javaClass, methodName) ?: return null
        return try {
            method.invoke(target)
        } catch (_: Throwable) {
            null
        }
    }

    fun callStaticZeroArgOrNull(clazz: Class<*>, methodName: String): Any? {
        val method = findMethodExactOrNull(clazz, methodName) ?: return null
        return try {
            method.invoke(null)
        } catch (_: Throwable) {
            null
        }
    }

    fun callMethodExactOrNull(
        target: Any,
        methodName: String,
        paramTypes: Array<Class<*>>,
        vararg args: Any?,
    ): Any? {
        val method = findMethodExactOrNull(target.javaClass, methodName, *paramTypes) ?: return null
        return try {
            method.invoke(target, *args)
        } catch (_: Throwable) {
            null
        }
    }
}
