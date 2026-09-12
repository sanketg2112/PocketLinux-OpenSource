package com.sg.linuxgo.build

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * androidx.core gates AccessibilityEvent.setAccessibilityDataSensitive at API 34,
 * but some API 34/35 images ship a framework without that method. Compose then
 * dies with NoSuchMethodError (Crash J).
 *
 * Wrap the two Api34Impl methods so a missing method is a no-op instead of a crash.
 */
abstract class FixAccessibilityEventCompatFactory :
    AsmClassVisitorFactory<InstrumentationParameters.None> {

    override fun isInstrumentable(classData: ClassData): Boolean {
        val name = classData.className
        return name == TARGET ||
            name.endsWith(".AccessibilityEventCompat\$Api34Impl") ||
            name.endsWith(".AccessibilityEventCompat.Api34Impl") ||
            name.endsWith("AccessibilityEventCompat\$Api34Impl")
    }

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor,
    ): ClassVisitor {
        return object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor {
                val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
                if (name == "setAccessibilityDataSensitive" && descriptor == SET_DESC) {
                    writeSet(mv)
                    return object : MethodVisitor(Opcodes.ASM9) {}
                }
                if (name == "isAccessibilityDataSensitive" && descriptor == GET_DESC) {
                    writeGet(mv)
                    return object : MethodVisitor(Opcodes.ASM9) {}
                }
                return mv
            }
        }
    }

    private fun writeSet(mv: MethodVisitor) {
        val start = Label()
        val end = Label()
        val handler = Label()
        mv.visitCode()
        mv.visitTryCatchBlock(start, end, handler, NSME)
        mv.visitLabel(start)
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitVarInsn(Opcodes.ILOAD, 1)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVENT, "setAccessibilityDataSensitive", SET_DESC, false)
        mv.visitLabel(end)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitLabel(handler)
        mv.visitInsn(Opcodes.POP)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(2, 2)
        mv.visitEnd()
    }

    private fun writeGet(mv: MethodVisitor) {
        val start = Label()
        val end = Label()
        val handler = Label()
        mv.visitCode()
        mv.visitTryCatchBlock(start, end, handler, NSME)
        mv.visitLabel(start)
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVENT, "isAccessibilityDataSensitive", GET_DESC, false)
        mv.visitLabel(end)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitLabel(handler)
        mv.visitInsn(Opcodes.POP)
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitMaxs(1, 1)
        mv.visitEnd()
    }

    companion object {
        private const val TARGET =
            "androidx.core.view.accessibility.AccessibilityEventCompat\$Api34Impl"
        private const val EVENT = "android/view/accessibility/AccessibilityEvent"
        private const val NSME = "java/lang/NoSuchMethodError"
        private const val SET_DESC = "(Z)V"
        private const val GET_DESC = "()Z"
    }
}
