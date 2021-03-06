/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.load.java.structure.impl

import com.intellij.psi.impl.JavaConstantExpressionEvaluator
import com.intellij.psi.util.PsiUtil
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.load.java.structure.JavaField
import org.jetbrains.kotlin.load.java.structure.JavaPropertyInitializerEvaluator
import org.jetbrains.kotlin.resolve.constants.ConstantValue
import org.jetbrains.kotlin.resolve.constants.ConstantValueFactory
import org.jetbrains.kotlin.resolve.descriptorUtil.builtIns

class JavaPropertyInitializerEvaluatorImpl : JavaPropertyInitializerEvaluator {
    override fun getInitializerConstant(field: JavaField, descriptor: PropertyDescriptor): ConstantValue<*>? {
        val initializer = (field as JavaFieldImpl).initializer
        val evaluated = JavaConstantExpressionEvaluator.computeConstantExpression(initializer, false) ?: return null
        val factory = ConstantValueFactory(descriptor.builtIns)
        when (evaluated) {
            //Note: evaluated expression may be of class that does not match field type in some cases
            // tested for Int, left other checks just in case
            is Byte, is Short, is Int, is Long -> {
                return factory.createIntegerConstantValue((evaluated as Number).toLong(), descriptor.type)
            }
            else -> {
                return factory.createConstantValue(evaluated)
            }
        }
    }

    override fun isNotNullCompileTimeConstant(field: JavaField): Boolean {
        // PsiUtil.isCompileTimeConstant returns false for null-initialized fields,
        // see com.intellij.psi.util.IsConstantExpressionVisitor.visitLiteralExpression()
        return PsiUtil.isCompileTimeConstant((field as JavaFieldImpl).psi)
    }
}
