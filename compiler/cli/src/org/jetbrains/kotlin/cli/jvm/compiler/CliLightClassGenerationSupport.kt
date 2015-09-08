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

package org.jetbrains.kotlin.cli.jvm.compiler

import com.google.common.base.Predicate
import com.google.common.collect.Collections2
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchScopeUtil
import com.intellij.util.Function
import com.intellij.util.SmartList
import com.intellij.util.containers.ContainerUtil
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.asJava.KotlinLightClassForExplicitDeclaration
import org.jetbrains.kotlin.asJava.KotlinLightClassForFacade
import org.jetbrains.kotlin.asJava.LightClassConstructionContext
import org.jetbrains.kotlin.asJava.LightClassGenerationSupport
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.PackageViewDescriptor
import org.jetbrains.kotlin.load.kotlin.PackagePartClassUtils
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.*
import org.jetbrains.kotlin.resolve.lazy.KotlinCodeAnalyzer
import org.jetbrains.kotlin.resolve.lazy.ResolveSessionUtils
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.JetScope
import org.jetbrains.kotlin.util.slicedMap.ReadOnlySlice
import org.jetbrains.kotlin.util.slicedMap.WritableSlice
import org.jetbrains.kotlin.utils.*
import java.util.Collections

/**
 * This class solves the problem of interdependency between analyzing Kotlin code and generating JetLightClasses

 * Consider the following example:

 * KClass.kt refers to JClass.java and vice versa

 * To analyze KClass.kt we need to load descriptors from JClass.java, and to do that we need a JetLightClass instance for KClass,
 * which can only be constructed when the structure of KClass is known.

 * To mitigate this, CliLightClassGenerationSupport hold a trace that is shared between the analyzer and JetLightClasses
 */
public class CliLightClassGenerationSupport(project: Project) : LightClassGenerationSupport(), CodeAnalyzerInitializer {
    private val psiManager: PsiManager
    private var bindingContext: BindingContext? = null
    private var module: ModuleDescriptor? = null

    init {
        this.psiManager = PsiManager.getInstance(project)
    }

    override fun initialize(trace: BindingTrace, module: ModuleDescriptor, analyzer: KotlinCodeAnalyzer?) {
        this.bindingContext = trace.bindingContext
        this.module = module

        if (trace !is CliBindingTrace) {
            throw IllegalArgumentException("Shared trace is expected to be subclass of " + javaClass<CliBindingTrace>().simpleName + " class")
        }

        trace.setKotlinCodeAnalyzer(analyzer)
    }

    private fun getBindingContext(): BindingContext {
        assert(bindingContext != null, "Call initialize() first")
        return bindingContext
    }

    private fun getModule(): ModuleDescriptor {
        assert(module != null, "Call initialize() first")
        return module
    }

    override fun getContextForPackage(files: Collection<JetFile>): LightClassConstructionContext {
        return getContext()
    }

    override fun getContextForClassOrObject(classOrObject: JetClassOrObject): LightClassConstructionContext {
        return getContext()
    }

    private fun getContext(): LightClassConstructionContext {
        return LightClassConstructionContext(bindingContext, getModule())
    }

    override fun findClassOrObjectDeclarations(fqName: FqName, searchScope: GlobalSearchScope): Collection<JetClassOrObject> {
        val classDescriptors = ResolveSessionUtils.getClassDescriptorsByFqName(getModule(), fqName)

        return ContainerUtil.mapNotNull(classDescriptors, object : Function<ClassDescriptor, JetClassOrObject> {
            override fun `fun`(descriptor: ClassDescriptor): JetClassOrObject? {
                val element = DescriptorToSourceUtils.getSourceFromDescriptor(descriptor)
                if (element is JetClassOrObject && PsiSearchScopeUtil.isInScope(searchScope, element)) {
                    return element
                }
                return null
            }
        })
    }

    override fun findFilesForPackage(fqName: FqName, searchScope: GlobalSearchScope): Collection<JetFile> {
        val files = getBindingContext().get(BindingContext.PACKAGE_TO_FILES, fqName)
        if (files != null) {
            return Collections2.filter(files, object : Predicate<JetFile> {
                override fun apply(input: JetFile?): Boolean {
                    return PsiSearchScopeUtil.isInScope(searchScope, input)
                }
            })
        }
        return emptyList()
    }

    override fun findClassOrObjectDeclarationsInPackage(
            packageFqName: FqName, searchScope: GlobalSearchScope): Collection<JetClassOrObject> {
        val files = findFilesForPackage(packageFqName, searchScope)
        val result = SmartList<JetClassOrObject>()
        for (file in files) {
            for (declaration in file.declarations) {
                if (declaration is JetClassOrObject) {
                    result.add(declaration)
                }
            }
        }
        return result
    }

    override fun packageExists(fqName: FqName, scope: GlobalSearchScope): Boolean {
        return !getModule().getPackage(fqName).isEmpty()
    }

    override fun getSubPackages(fqn: FqName, scope: GlobalSearchScope): Collection<FqName> {
        val packageView = getModule().getPackage(fqn)
        val members = packageView.memberScope.getDescriptors(DescriptorKindFilter.PACKAGES, JetScope.ALL_NAME_FILTER)
        return ContainerUtil.mapNotNull(members, object : Function<DeclarationDescriptor, FqName> {
            override fun `fun`(member: DeclarationDescriptor): FqName? {
                if (member is PackageViewDescriptor) {
                    return member.fqName
                }
                return null
            }
        })
    }

    override fun getPsiClass(classOrObject: JetClassOrObject): PsiClass? {
        return KotlinLightClassForExplicitDeclaration.create(psiManager, classOrObject)
    }

    override fun getPackageClasses(packageFqName: FqName, scope: GlobalSearchScope): Collection<PsiClass> {
        val filesInPackage = findFilesForPackage(packageFqName, scope)

        val filesWithCallables = PackagePartClassUtils.getFilesWithCallables(filesInPackage)
        if (filesWithCallables.isEmpty()) return emptyList()

        //noinspection RedundantTypeArguments
        return emptyOrSingletonList<PsiClass>(
                KotlinLightClassForFacade.createForPackageFacade(psiManager, packageFqName, scope, filesWithCallables))
    }

    override fun resolveClassToDescriptor(classOrObject: JetClassOrObject): ClassDescriptor? {
        return bindingContext!!.get(BindingContext.CLASS, classOrObject)
    }

    override fun getFacadeClasses(facadeFqName: FqName, scope: GlobalSearchScope): Collection<PsiClass> {
        val filesForFacade = findFilesForFacade(facadeFqName, scope)
        if (filesForFacade.isEmpty()) return emptyList()

        //noinspection RedundantTypeArguments
        return emptyOrSingletonList<PsiClass>(
                KotlinLightClassForFacade.createForFacade(psiManager, facadeFqName, scope, filesForFacade))
    }

    override fun findFilesForFacade(facadeFqName: FqName, scope: GlobalSearchScope): Collection<JetFile> {
        // TODO We need a way to plug some platform-dependent stuff into LazyTopDownAnalyzer.
        // It already performs some ad hoc stuff for packages->files mapping, anyway.
        val filesInPackage = findFilesForPackage(facadeFqName.parent(), scope)
        return PackagePartClassUtils.getFilesForPart(facadeFqName, filesInPackage)
    }

    override fun getContextForFacade(files: Collection<JetFile>): LightClassConstructionContext {
        return getContext()
    }

    override fun createTrace(): BindingTraceContext {
        return NoScopeRecordCliBindingTrace()
    }

    public class NoScopeRecordCliBindingTrace : CliBindingTrace() {
        override fun <K, V> record(slice: WritableSlice<K, V>, key: K, value: V) {
            if (slice === BindingContext.RESOLUTION_SCOPE || slice === BindingContext.TYPE_RESOLUTION_SCOPE || slice === BindingContext.LEXICAL_SCOPE) {
                // In the compiler there's no need to keep scopes
                return
            }
            super.record(slice, key, value)
        }

        override fun toString(): String {
            return javaClass<NoScopeRecordCliBindingTrace>().name
        }
    }

    public open class CliBindingTrace
    TestOnly
    constructor() : BindingTraceContext() {
        private var kotlinCodeAnalyzer: KotlinCodeAnalyzer? = null

        override fun toString(): String {
            return javaClass<CliBindingTrace>().name
        }

        public fun setKotlinCodeAnalyzer(kotlinCodeAnalyzer: KotlinCodeAnalyzer) {
            this.kotlinCodeAnalyzer = kotlinCodeAnalyzer
        }

        override fun <K, V> get(slice: ReadOnlySlice<K, V>, key: K): V? {
            val value = super.get(slice, key)

            if (value == null) {
                if (BindingContext.FUNCTION === slice || BindingContext.VARIABLE === slice) {
                    if (key is JetDeclaration) {
                        if (!JetPsiUtil.isLocal(key)) {
                            kotlinCodeAnalyzer!!.resolveToDescriptor(key)
                            return super.get(slice, key)
                        }
                    }
                }
            }

            return value
        }
    }
}