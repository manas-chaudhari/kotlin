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

package org.jetbrains.kotlin.idea.actions.generate

import com.intellij.codeInsight.CodeInsightBundle
import com.intellij.ide.util.MemberChooser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.refactoring.util.CommonRefactoringUtil
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.diagnostics.Errors
import org.jetbrains.kotlin.idea.caches.resolve.analyzeFully
import org.jetbrains.kotlin.idea.codeInsight.DescriptorToSourceUtilsIde
import org.jetbrains.kotlin.idea.core.CollectingNameValidator
import org.jetbrains.kotlin.idea.core.KotlinNameSuggester
import org.jetbrains.kotlin.idea.core.appendElement
import org.jetbrains.kotlin.idea.core.isVisible
import org.jetbrains.kotlin.idea.core.util.DescriptorMemberChooserObject
import org.jetbrains.kotlin.idea.quickfix.insertMembersAfter
import org.jetbrains.kotlin.idea.util.IdeDescriptorRenderers
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.siblings
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.descriptorUtil.builtIns
import org.jetbrains.kotlin.resolve.descriptorUtil.getSuperClassNotAny
import org.jetbrains.kotlin.resolve.source.getPsi
import org.jetbrains.kotlin.types.JetType
import org.jetbrains.kotlin.types.TypeSubstitutor
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.types.checker.JetTypeChecker
import org.jetbrains.kotlin.types.substitutions.getTypeSubstitutor
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstanceOrNull
import org.jetbrains.kotlin.utils.addToStdlib.lastIsInstanceOrNull
import org.jetbrains.kotlin.utils.addToStdlib.singletonOrEmptyList
import java.util.*

class KotlinGenerateSecondaryConstructorAction : KotlinGenerateMemberActionBase<KotlinGenerateSecondaryConstructorAction.Info>() {
    class Info(
            val propertiesToInitialize: List<PropertyDescriptor>,
            val superConstructors: List<ConstructorDescriptor>,
            val classDescriptor: ClassDescriptor
    )

    override fun isValidForClass(targetClass: JetClassOrObject): Boolean {
        return targetClass is JetClass && targetClass !is JetEnumEntry && !targetClass.isInterface() && !targetClass.isAnnotation()
    }

    private fun shouldPreselect(element: PsiElement) = element is JetProperty && !element.isVar

    private fun chooseSuperConstructors(klass: JetClassOrObject, classDescriptor: ClassDescriptor): List<DescriptorMemberChooserObject> {
        val project = klass.project
        val superClassDescriptor = classDescriptor.getSuperClassNotAny() ?: return emptyList()
        val candidates = superClassDescriptor.constructors
                .filter { it.isVisible(classDescriptor) }
                .map { DescriptorMemberChooserObject(DescriptorToSourceUtilsIde.getAnyDeclaration(project, it) ?: klass, it) }
        if (ApplicationManager.getApplication().isUnitTestMode || candidates.size <= 1) return candidates

        return with(MemberChooser(candidates.toTypedArray(), false, true, klass.project)) {
            title = CodeInsightBundle.message("generate.constructor.super.constructor.chooser.title")
            setCopyJavadocVisible(false)
            show()

            selectedElements ?: emptyList()
        }
    }

    private fun choosePropertiesToInitialize(klass: JetClassOrObject, context: BindingContext): List<DescriptorMemberChooserObject> {
        val candidates = klass.declarations
                .filterIsInstance<JetProperty>()
                .filter { it.isVar || context.diagnostics.forElement(it).any { it.factory in Errors.MUST_BE_INITIALIZED_DIAGNOSTICS } }
                .map { context.get(BindingContext.VARIABLE, it) as PropertyDescriptor }
                .map { DescriptorMemberChooserObject(it.source.getPsi()!!, it) }
        if (ApplicationManager.getApplication().isUnitTestMode || candidates.isEmpty()) return candidates

        return with(MemberChooser(candidates.toTypedArray(), true, true, klass.project, false, null)) {
            title = "Choose Properties to Initialize by Constructor"
            setCopyJavadocVisible(false)
            selectElements(candidates.filter { shouldPreselect(it.element) }.toTypedArray())
            show()

            selectedElements ?: emptyList()
        }
    }

    override fun prepareMembersInfo(klass: JetClassOrObject, project: Project): Info? {
        val context = klass.analyzeFully()
        val classDescriptor = context.get(BindingContext.CLASS, klass) ?: return null
        val superConstructors = chooseSuperConstructors(klass, classDescriptor).map { it.descriptor as ConstructorDescriptor }
        val propertiesToInitialize = choosePropertiesToInitialize(klass, context).map { it.descriptor as PropertyDescriptor }
        return Info(propertiesToInitialize, superConstructors, classDescriptor)
    }

    override fun generateMembers(editor: Editor, info: Info): List<JetDeclaration> {
        val targetClass = info.classDescriptor.source.getPsi() as? JetClass ?: return emptyList()

        fun Info.findAnchor(): PsiElement? {
            targetClass.declarations.lastIsInstanceOrNull<JetSecondaryConstructor>()?.let { return it }
            val lastPropertyToInitialize = propertiesToInitialize.lastOrNull()?.source?.getPsi()
            val declarationsAfter = lastPropertyToInitialize?.siblings()?.filterIsInstance<JetDeclaration>() ?: targetClass.declarations.asSequence()
            val firstNonProperty = declarationsAfter.firstOrNull { it !is JetProperty } ?: return null
            return firstNonProperty.siblings(forward = false).firstIsInstanceOrNull<JetProperty>() ?: targetClass.getOrCreateBody().lBrace
        }

        return with(info) {
            val prototypes = if (superConstructors.isNotEmpty()) {
                superConstructors.map { generateConstructor(classDescriptor, propertiesToInitialize, it) }.filterNotNull()
            } else {
                generateConstructor(classDescriptor, propertiesToInitialize, null).singletonOrEmptyList()
            }

            if (prototypes.isEmpty()) {
                CommonRefactoringUtil.showErrorHint(targetClass.project, editor, "Constructor already exists", commandName, null)
                return emptyList()
            }

            insertMembersAfter(editor, targetClass, prototypes, findAnchor())
        }
    }

    private fun generateConstructor(
            classDescriptor: ClassDescriptor,
            propertiesToInitialize: List<PropertyDescriptor>,
            superConstructor: ConstructorDescriptor?
    ): JetSecondaryConstructor? {
        fun equalTypes(types1: Collection<JetType>, types2: Collection<JetType>): Boolean {
            return types1.size == types2.size && (types1.zip(types2)).all { JetTypeChecker.DEFAULT.equalTypes(it.first, it.second) }
        }

        val constructorParamTypes = propertiesToInitialize.map { it.type } +
                (superConstructor?.valueParameters?.map { it.varargElementType ?: it.type } ?: emptyList())

        if (classDescriptor.constructors.any { it.source.getPsi() is JetConstructor<*>
                && equalTypes(it.valueParameters.map { it.varargElementType ?: it.type }, constructorParamTypes) }) return null

        val targetClass = classDescriptor.source.getPsi() as JetClass
        val psiFactory = JetPsiFactory(targetClass)

        val validator = CollectingNameValidator()

        val constructor = psiFactory.createSecondaryConstructor("constructor()")
        val parameterList = constructor.valueParameterList!!

        if (superConstructor != null) {
            val substitutor = getTypeSubstitutor(superConstructor.containingDeclaration.defaultType, classDescriptor.defaultType)
                    ?: TypeSubstitutor.EMPTY
            val delegationCallArguments = ArrayList<String>()
            for (parameter in superConstructor.valueParameters) {
                val isVararg = parameter.varargElementType != null

                val paramName = KotlinNameSuggester.suggestNameByName(parameter.name.asString(), validator)

                val typeToUse = parameter.varargElementType ?: parameter.type
                val paramType = IdeDescriptorRenderers.SOURCE_CODE.renderType(
                        substitutor.substitute(typeToUse, Variance.INVARIANT) ?: classDescriptor.builtIns.anyType
                )

                val modifiers = if (isVararg) "vararg " else ""

                parameterList.addParameter(psiFactory.createParameter("$modifiers$paramName: $paramType"))
                delegationCallArguments.add(if (isVararg) "*$paramName" else paramName)
            }

            val delegationCall = psiFactory.createConstructorDelegationCall(delegationCallArguments.joinToString(prefix = "super(", postfix = ")"))
            constructor.replaceImplicitDelegationCallWithExplicit(false).replace(delegationCall)
        }

        if (propertiesToInitialize.isNotEmpty()) {
            val body = psiFactory.createEmptyBody()
            for (property in propertiesToInitialize) {
                val propertyName = property.name
                val paramName = KotlinNameSuggester.suggestNameByName(propertyName.asString(), validator)
                val paramType = IdeDescriptorRenderers.SOURCE_CODE.renderType(property.type)

                parameterList.addParameter(psiFactory.createParameter("$paramName: $paramType"))
                body.appendElement(psiFactory.createExpression("this.$propertyName = $paramName"), true)
            }

            constructor.add(body)
        }

        return constructor
    }
}