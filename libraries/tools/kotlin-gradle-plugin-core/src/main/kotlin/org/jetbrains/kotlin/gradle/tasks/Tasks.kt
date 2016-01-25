package org.jetbrains.kotlin.gradle.tasks

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.lang.Language
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.impl.PsiFileFactoryImpl
import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils
import org.apache.commons.lang.StringUtils
import org.codehaus.groovy.runtime.MethodClosure
import org.gradle.api.GradleException
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.plugins.ExtraPropertiesExtension
import org.gradle.api.tasks.SourceTask
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.api.tasks.incremental.IncrementalTaskInputs
import org.jetbrains.kotlin.build.GeneratedFile
import org.jetbrains.kotlin.cli.common.CLICompiler
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.K2JSCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.OutputMessageUtil
import org.jetbrains.kotlin.cli.js.K2JSCompiler
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.compilerRunner.ArgumentUtils
import org.jetbrains.kotlin.compilerRunner.OutputItemsCollector
import org.jetbrains.kotlin.compilerRunner.OutputItemsCollectorImpl
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.incremental.*
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.modules.TargetId
import org.jetbrains.kotlin.progress.CompilationCanceledStatus
import org.jetbrains.kotlin.utils.LibraryUtils
import java.io.File
import java.util.*

val DEFAULT_ANNOTATIONS = "org.jebrains.kotlin.gradle.defaultAnnotations"

val ANNOTATIONS_PLUGIN_NAME = "org.jetbrains.kotlin.kapt"

abstract class AbstractKotlinCompile<T : CommonCompilerArguments>() : AbstractCompile() {
    abstract protected val compiler: CLICompiler<T>
    abstract protected fun createBlankArgs(): T
    open protected fun afterCompileHook(args: T) {
    }
    abstract protected fun populateTargetSpecificArgs(args: T)

    var kotlinOptions: T = createBlankArgs()

    private val loggerInstance = Logging.getLogger(this.javaClass)
    override fun getLogger() = loggerInstance

    override fun compile() {
        assert(false, { "unexpected call to compile()" })
    }

    @TaskAction
    fun execute(inputs: IncrementalTaskInputs): Unit {
        logger.debug("Starting ${javaClass} task")
        logger.kotlinDebug("all sources ${getSource().joinToString { it.path }}")
        logger.kotlinDebug("is incremental == ${inputs.isIncremental}")
        val modified = arrayListOf<File>()
        val removed = arrayListOf<File>()
        if (inputs.isIncremental) {
            inputs.outOfDate { modified.add(it.file) }
            inputs.removed { removed.add(it.file) }
        }
        logger.kotlinDebug("modified ${modified.joinToString { it.path }}")
        logger.kotlinDebug("removed ${removed.joinToString { it.path }}")
        var commonArgs = createBlankArgs()
        val args = createBlankArgs()
        val sources = getKotlinSources()
        if (sources.isEmpty()) {
            logger.warn("No Kotlin files found, skipping Kotlin compiler task")
            return
        }

        populateCommonArgs(args)
        populateTargetSpecificArgs(args)
        val cachesDir = File("caches")
        callCompiler(args, sources, inputs.isIncremental, modified, removed, cachesDir)
        afterCompileHook(args)
    }

    private fun getKotlinSources(): List<File> = (getSource() as Iterable<File>).filter { it.isKotlinFile() }

    protected fun File.isClassFile(): Boolean {
        return when (FilenameUtils.getExtension(name).toLowerCase()) {
            "class" -> true
            else -> false
        }
    }

    protected fun File.isKotlinFile(): Boolean {
        return when (FilenameUtils.getExtension(name).toLowerCase()) {
            "kt", "kts" -> true
            else -> false
        }
    }

    private fun populateSources(args:T, sources: List<File>) {
        args.freeArgs = sources.map { it.absolutePath }
    }

    private fun populateCommonArgs(args: T) {
        args.suppressWarnings = kotlinOptions.suppressWarnings
        args.verbose = kotlinOptions.verbose
        args.version = kotlinOptions.version
        args.noInline = kotlinOptions.noInline
    }

    protected open fun callCompiler(args: T, sources: List<File>, isIncremental: Boolean, modified: List<File>, removed: List<File>, cachesBaseDir: File) {

        populateSources(args, sources)

        val messageCollector = GradleMessageCollector(logger)
        logger.debug("Calling compiler")
        val exitCode = compiler.exec(messageCollector, Services.EMPTY, args)

        when (exitCode) {
            ExitCode.COMPILATION_ERROR -> throw GradleException("Compilation error. See log for more details")
            ExitCode.INTERNAL_ERROR -> throw GradleException("Internal compiler error. See log for more details")
        }
    }

}


open class KotlinCompile() : AbstractKotlinCompile<K2JVMCompilerArguments>() {
    override val compiler = K2JVMCompiler()
    override fun createBlankArgs(): K2JVMCompilerArguments = K2JVMCompilerArguments()

    // Should be SourceDirectorySet or File
    val srcDirsSources = HashSet<Any>()

    private val testsMap = mapOf(
            "compileTestKotlin" to  "compileKotlin",
            "compileDebugUnitTestKotlin" to  "compileDebugKotlin",
            "compileReleaseUnitTestKotlin" to  "compileReleaseKotlin"
            )

    fun projectRelativePath(f: File) = f.toRelativeString(project.projectDir)
    fun projectRelativePath(p: String) = File(p).toRelativeString(project.projectDir)

    override fun populateTargetSpecificArgs(args: K2JVMCompilerArguments) {
        // show kotlin compiler where to look for java source files
//        args.freeArgs = (args.freeArgs + getJavaSourceRoots().map { it.getAbsolutePath() }).toSet().toList()
        logger.kotlinDebug("args.freeArgs = ${args.freeArgs}")

        if (StringUtils.isEmpty(kotlinOptions.classpath)) {
            args.classpath = classpath.filter({ it != null && it.exists() }).joinToString(File.pathSeparator)
            logger.kotlinDebug("args.classpath = ${args.classpath}")
        }

        args.destination = if (StringUtils.isEmpty(kotlinOptions.destination)) {
            destinationDir?.path
        } else {
            kotlinOptions.destination
        }
        logger.kotlinDebug("args.destination = ${args.destination}")

        val extraProperties = extensions.extraProperties
        args.pluginClasspaths = extraProperties.getOrNull<Array<String>>("compilerPluginClasspaths") ?: arrayOf()
        logger.kotlinDebug("args.pluginClasspaths = ${args.pluginClasspaths.joinToString(File.pathSeparator)}")
        val basePluginOptions = extraProperties.getOrNull<Array<String>>("compilerPluginArguments") ?: arrayOf()

        val pluginOptions = arrayListOf(*basePluginOptions)
        handleKaptProperties(extraProperties, pluginOptions)

        args.pluginOptions = pluginOptions.toTypedArray()
        logger.kotlinDebug("args.pluginOptions = ${args.pluginOptions.joinToString(File.pathSeparator)}")

        args.noStdlib = true
        args.noJdk = kotlinOptions.noJdk
        args.noInline = kotlinOptions.noInline
        args.noOptimize = kotlinOptions.noOptimize
        args.noCallAssertions = kotlinOptions.noCallAssertions
        args.noParamAssertions = kotlinOptions.noParamAssertions
        args.moduleName = kotlinOptions.moduleName ?: extraProperties.getOrNull<String>("defaultModuleName")

        fun  addFriendPathForTestTask(taskName: String) {
            logger.kotlinDebug("try to determine the output directory of corresponding $taskName task")
            val tasks = project.getTasksByName("$taskName", false)
            logger.kotlinDebug("tasks for $taskName: ${tasks}")
            if (tasks.size == 1) {
                val task = tasks.firstOrNull() as? KotlinCompile
                if (task != null) {
                    logger.kotlinDebug("destination directory for production = ${task.destinationDir}")
                    args.friendPaths = arrayOf(task.destinationDir.absolutePath)
                    args.moduleName = task.kotlinOptions.moduleName ?: task.extensions.extraProperties.getOrNull<String>("defaultModuleName")
                }
            }
        }

        testsMap.get(this.name)?.let {
            addFriendPathForTestTask(it)
        }

        logger.kotlinDebug("args.moduleName = ${args.moduleName}")
    }

    override fun callCompiler(args: K2JVMCompilerArguments, sources: List<File>, isIncrementalRequested: Boolean, modified: List<File>, removed: List<File>, cachesBaseDir: File) {

        // TODO: consider other ways to pass incremental flag to compiler/builder
        System.setProperty("kotlin.incremental.compilation", "true")
        // TODO: experimental should be removed as soon as it becomes standard
        System.setProperty("kotlin.incremental.compilation.experimental", "true")

        val targetType = "java-production"
        val moduleName = args.moduleName
        val targets = listOf(TargetId(moduleName, targetType))
        val outputDir = File(args.destination)
        val caches = hashMapOf<TargetId, IncrementalCacheImpl<TargetId>>()
        val lookupStorage = LookupStorage(File(cachesBaseDir, "lookups"))
        val lookupTracker = LookupTrackerImpl(LookupTracker.DO_NOTHING)
        var currentRemoved = removed
        val allGeneratedFiles = hashSetOf<GeneratedFile<TargetId>>()
        val compiledSourcesSet = hashSetOf<File>()

        fun getOrCreateIncrementalCache(target: TargetId): IncrementalCacheImpl<TargetId> {
            val cacheDir = File(cachesBaseDir, "increCache.${target.name}")
            cacheDir.mkdirs()
            return IncrementalCacheImpl(targetDataRoot = cacheDir, targetOutputDir = outputDir, target = target)
        }

        fun getIncrementalCache(it: TargetId) = caches.getOrPut(it, { getOrCreateIncrementalCache(it) })

        fun PsiClass.findLookupSymbols(): Iterable<LookupSymbol> {
            val fqn = qualifiedName.orEmpty()
            return listOf(LookupSymbol(name.orEmpty(), if (fqn == name) "" else fqn.removeSuffix("." + name!!))) +
                    methods.map { LookupSymbol(it.name, fqn) } +
                    fields.map { LookupSymbol(it.name.orEmpty(), fqn) } +
                    innerClasses.flatMap { it.findLookupSymbols() }
        }

        fun Iterable<LookupSymbol>.files(filesFilter: (File) -> Boolean = { true }, logAction: (LookupSymbol, Iterable<File>) -> Unit = { l,fs -> }): Iterable<File> =
            flatMap { lookup ->
                val files = lookupStorage.get(lookup).map(::File).filter(filesFilter)
                if (files.any()) {
                    logAction(lookup, files)
                }
                files
            }

        fun dirtyKotlinSourcesFromGradle(): List<File> {
            // TODO: take into account deleted files (take their symbols from caches
            // TODO: handle classpath changes similarly - compare with cashed version (likely a big change, may be costly, some heuristics could be considered)
            val kotlinFiles = modified.filter { it.isKotlinFile() }
            val javaFiles = modified.filter { it.isJavaFile() }
            if (javaFiles.any()) {
                val rootDisposable = Disposer.newDisposable()
                val configuration = CompilerConfiguration()
                val environment = KotlinCoreEnvironment.createForProduction(rootDisposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES)
                val project = environment.project
                val psiFileFactory = PsiFileFactory.getInstance(project) as PsiFileFactoryImpl
                val lookupSymbols = javaFiles.flatMap {
                    val javaFile = psiFileFactory.createFileFromText(it.nameWithoutExtension, Language.findLanguageByID("JAVA")!!, it.readText())
                    if (javaFile is PsiJavaFile)
                        javaFile.classes.flatMap { it.findLookupSymbols() }
                    else listOf()
                }
//                logger.kotlinDebug("changed java symbols: ${lookupSymbols.joinToString { "${it.name}:${it.scope}" }}")
                if (lookupSymbols.any()) {
                    val kotlinFilesSet = kotlinFiles.toHashSet()
                    return kotlinFiles +
                            lookupSymbols.files(
                                    filesFilter = { it !in kotlinFilesSet },
                                    logAction = { lookup, files ->
                                        logger.kotlinInfo("changes in ${lookup.name} (${lookup.scope}) causes recompilation of ${files.joinToString { projectRelativePath(it) }}")
                                    })
                }
            }
            return kotlinFiles
        }

        fun isClassPathChanged(): Boolean {
            // TODO: that doesn't look to wise - join it first and then split here, consider storing it somewhere in between
            val classpath = args.classpath.split(File.pathSeparator).map { File(it) }.toHashSet()
            val changedClasspath = modified.filter { classpath.contains(it) }
            return changedClasspath.any()
        }

        fun allCachesVersions() = allCachesVersions(cachesBaseDir, listOf(cachesBaseDir))

        fun calculateSourcesToCompile(): Pair<List<File>, Boolean> {

            // TODO: more precise will be not to rebuild unconditionally on classpath changes, but retrieve lookup info and try to find out which sources are affected by cp changes
            if (!isIncrementalRequested || isClassPathChanged()) {
                logger.kotlinInfo(if (!isIncrementalRequested) "clean caches on rebuild" else "classpath changed, rebuilding all kotlin files")
                targets.forEach { getIncrementalCache(it).clean() }
                lookupStorage.clean()
                return Pair(sources, false)
            }
            val actions = if (isIncrementalRequested) allCachesVersions().map { it.checkVersion() }
                          else listOf(CacheVersion.Action.REBUILD_ALL_KOTLIN)
            // TODO: find out whether these flags should be emulated too
//            val hasKotlin = HasKotlinMarker(dataManager)
//            val rebuildAfterCacheVersionChanged = RebuildAfterCacheVersionChangeMarker(dataManager)

            for (status in actions.distinct().sorted()) {
                when (status) {
                    CacheVersion.Action.REBUILD_ALL_KOTLIN -> {
                        logger.kotlinInfo("Kotlin global lookup map format changed, rebuilding all kotlin files")
                        targets.forEach { getIncrementalCache(it).clean() }
                        lookupStorage.clean()
                        return Pair(sources, false)
                    }
                    CacheVersion.Action.REBUILD_CHUNK -> {
                        logger.kotlinInfo("Clearing caches for " + targets.joinToString { it.name })
                        targets.forEach { getIncrementalCache(it).clean() }
                    }
                    CacheVersion.Action.CLEAN_NORMAL_CACHES -> {
                        logger.kotlinInfo("Clearing caches for all targets")
                        targets.forEach { getIncrementalCache(it).clean() }
                    }
                    CacheVersion.Action.CLEAN_EXPERIMENTAL_CACHES -> {
                        logger.kotlinInfo("Clearing experimental caches for all targets")
                        targets.forEach { getIncrementalCache(it).cleanExperimental() }
                    }
                    CacheVersion.Action.CLEAN_DATA_CONTAINER -> {
                        logger.kotlinInfo("Clearing lookup cache")
                        lookupStorage.clean()
                        dataContainerCacheVersion(cachesBaseDir).clean()
                    }
                    else -> {
                        assert(status == CacheVersion.Action.DO_NOTHING) { "Unknown version status $status" }
                    }
                }
            }
            return Pair(dirtyKotlinSourcesFromGradle().distinct(), true)
        }

        fun cleanupOnError() {
            allGeneratedFiles.forEach {
                logger.kotlinInfo("deleting output on error: ${it.outputFile}")
                it.outputFile.delete()
            }
        }

        fun outputRelativePath(f: File) = f.toRelativeString(outputDir)


        // TODO: decide what to do if no files are considered dirty - rebuild or skip the module
        var (sourcesToCompile, isIncrementalDecided) = calculateSourcesToCompile()

        if (isIncrementalDecided) {
            // TODO: process as list here, merge into string later
            args.classpath = args.classpath + File.pathSeparator + outputDir.absolutePath
        }

        while (sourcesToCompile.any()) {
            logger.kotlinInfo("compile iteration: ${sourcesToCompile.joinToString{ projectRelativePath(it) }}")

            val (exitCode, generatedFiles) = compileChanged(
                    targets = targets,
                    sourcesToCompile = sourcesToCompile,
                    outputDir = outputDir,
                    args = args,
                    getIncrementalCache = ::getIncrementalCache,
                    lookupTracker = lookupTracker)

            allGeneratedFiles.addAll(generatedFiles)
            // save versions?

            val changes = updateIncrementalCaches(
                    targets = targets,
                    generatedFiles = generatedFiles,
                    compiledWithErrors = exitCode != ExitCode.OK,
                    getIncrementalCache = { caches[it]!! })

//            lookupTracker.lookups.entrySet().forEach {
//                logger.kotlinDebug("lookups to ${it.key.name}:${it.key.scope} from ${it.value.joinToString { projectRelativePath(it) }}")
//            }

            lookupStorage.update(lookupTracker, sourcesToCompile, currentRemoved)

            allCachesVersions().forEach { it.saveIfNeeded() }

            when (exitCode) {
                ExitCode.COMPILATION_ERROR -> {
                    cleanupOnError()
                    throw GradleException("Compilation error. See log for more details")
                }
                ExitCode.INTERNAL_ERROR -> {
                    cleanupOnError()
                    throw GradleException("Internal compiler error. See log for more details")
                }
                ExitCode.SCRIPT_EXECUTION_ERROR -> {
                    cleanupOnError()
                    throw GradleException("Script execution error. See log for more details")
                }
                ExitCode.OK -> {
                    logger.kotlinInfo("Compilation succeeded")
                }
            }

            if (!isIncrementalDecided) break;

//            logger.kotlinDebug("generated ${generatedFiles.joinToString { outputRelativePath(it.outputFile) }}")
//            logger.kotlinDebug("changes: ${changes.changes.joinToString { "${it.fqName}: ${it.javaClass.simpleName}" }}")
//
//            logger.kotlinLazyDebug({
//                "known lookups:\n${lookupStorage.dump(changes.changes.flatMap {
//                    change ->
//                        if (change is ChangeInfo.MembersChanged)
//                            change.names.asSequence().map { LookupSymbol(it, change.fqName.asString()) }
//                        else
//                            sequenceOf<LookupSymbol>()
//                }.toSet(), project.projectDir)}" })

            compiledSourcesSet.addAll(sourcesToCompile)

            val dirtyLookups = changes.dirtyLookups<TargetId>(caches.values.asSequence())

//            logger.kotlinDebug("dirty lookups: ${dirtyLookups.joinToString { "${it.name}:${it.scope}" }}")

            val dirty = dirtyLookups.files(
                    filesFilter = { it !in compiledSourcesSet },
                    logAction = { lookup, files ->
                        logger.kotlinInfo("changes in ${lookup.name} (${lookup.scope}) causes recompilation of ${files.joinToString { projectRelativePath(it) }}")
                    })
            sourcesToCompile = dirty.filter { it in sources }.toList()
            if (currentRemoved.any()) {
                currentRemoved = listOf()
            }
//            logger.kotlinDebug("dirty: ${dirty.joinToString { projectRelativePath(it) }}")
//            logger.kotlinDebug("to compile: ${sourcesToCompile.joinToString { projectRelativePath(it) }}")
        }
        lookupStorage.flush(false)
        lookupStorage.close()
        caches.values.forEach { it.flush(false); it.close() }
    }

    private data class CompileChangedResults(val exitCode: ExitCode, val generatedFiles: List<GeneratedFile<TargetId>>)

    private fun compileChanged(targets: List<TargetId>,
                               sourcesToCompile: List<File>,
                               outputDir: File,
                               args: K2JVMCompilerArguments,
                               getIncrementalCache: (TargetId) -> IncrementalCacheImpl<TargetId>,
                               lookupTracker: LookupTracker)
            : CompileChangedResults
    {
        val moduleFile = makeModuleFile(args.moduleName, isTest = false, outputDir = outputDir, sourcesToCompile = sourcesToCompile, javaSourceRoots = getJavaSourceRoots(), classpath = classpath, friendDirs = listOf())

        logger.kotlinDebug("module file:\n${moduleFile.readText()}")

        args.module = moduleFile.absolutePath

        val outputItemCollector = OutputItemsCollectorImpl()

        val messageCollector = GradleMessageCollector(logger, outputItemCollector)

        try {
            val incrementalCaches = makeIncrementalCachesMap(targets, { listOf<TargetId>() }, getIncrementalCache, { this })

            val compilationCanceledStatus = object : CompilationCanceledStatus {
                override fun checkCanceled() {
                }
            }

            logger.kotlinDebug("compiling with args ${ArgumentUtils.convertArgumentsToStringList(args)}")

            val exitCode = compiler.exec(messageCollector, makeCompileServices(incrementalCaches, lookupTracker, compilationCanceledStatus), args)

            return CompileChangedResults(
                    exitCode,
                    outputItemCollector.generatedFiles(
                            targets = targets,
                            representativeTarget = targets.first(),
                            getSources = { sourcesToCompile },
                            getOutputDir = { outputDir }))
        }
        finally {
            moduleFile.delete()
        }
    }


    private fun handleKaptProperties(extraProperties: ExtraPropertiesExtension, pluginOptions: MutableList<String>) {
        val kaptAnnotationsFile = extraProperties.getOrNull<File>("kaptAnnotationsFile")
        if (kaptAnnotationsFile != null) {
            if (kaptAnnotationsFile.exists()) kaptAnnotationsFile.delete()
            pluginOptions.add("plugin:$ANNOTATIONS_PLUGIN_NAME:output=" + kaptAnnotationsFile)
        }

        val kaptClassFileStubsDir = extraProperties.getOrNull<File>("kaptStubsDir")
        if (kaptClassFileStubsDir != null) {
            pluginOptions.add("plugin:$ANNOTATIONS_PLUGIN_NAME:stubs=" + kaptClassFileStubsDir)
        }

        val supportInheritedAnnotations = extraProperties.getOrNull<Boolean>("kaptInheritedAnnotations")
        if (supportInheritedAnnotations != null && supportInheritedAnnotations) {
            pluginOptions.add("plugin:$ANNOTATIONS_PLUGIN_NAME:inherited=true")
        }
    }

    private fun getJavaSourceRoots(): Set<File> =
            getSource()
            .filter { it.isJavaFile() }
            .map { findSrcDirRoot(it) }
            .filterNotNull()
            .toSet()

    private fun File.isJavaFile() = extension.equals(JavaFileType.INSTANCE.defaultExtension, ignoreCase = true)

    // override setSource to track source directory sets and files (for generated android folders)
    override fun setSource(source: Any?) {
        srcDirsSources.clear()
        if (source is SourceDirectorySet) {
            srcDirsSources.add(source)
        }
        else if (source is File) {
            srcDirsSources.add(source)
        }
        super.setSource(source)
    }

    // override source to track source directory sets and files (for generated android folders)
    override fun source(vararg sources: Any?): SourceTask? {
        for (source in sources) {
            if (source is SourceDirectorySet) {
                srcDirsSources.add(source)
            }
            else if (source is File) {
                srcDirsSources.add(source)
            }
        }
        return super.source(sources)
    }

    fun findSrcDirRoot(file: File): File? {
        for (source in srcDirsSources) {
            if (source is SourceDirectorySet) {
                for (root in source.srcDirs) {
                    if (FileUtil.isAncestor(root, file, false)) {
                        return root
                    }
                }
            }
            else if (source is File) {
                if (FileUtil.isAncestor(source, file, false)) {
                    return source
                }
            }
        }
        return null
    }
}

open class Kotlin2JsCompile() : AbstractKotlinCompile<K2JSCompilerArguments>() {
    override val compiler = K2JSCompiler()

    override fun createBlankArgs(): K2JSCompilerArguments {
        val args = K2JSCompilerArguments()
        args.libraryFiles = arrayOf<String>()  // defaults to null
        args.metaInfo = true
        args.kjsm = true
        return args
    }

    fun addLibraryFiles(vararg fs: String) {
        kotlinOptions.libraryFiles += fs
    }

    fun addLibraryFiles(vararg fs: File) {
        val strs = fs.map { it.path }.toTypedArray()
        addLibraryFiles(*strs)
    }

    val outputFile: String?
            get() = kotlinOptions.outputFile

    val sourceMapDestinationDir: File
            get() = File(outputFile).let { if (it.isDirectory) it else it.parentFile!! }

    val sourceMap: Boolean
            get() = kotlinOptions.sourceMap

    init {
        outputs.file(MethodClosure(this, "getOutputFile"))
    }

    override fun populateTargetSpecificArgs(args: K2JSCompilerArguments) {
        args.noStdlib = true
        args.outputFile = outputFile
        args.outputPrefix = kotlinOptions.outputPrefix
        args.outputPostfix = kotlinOptions.outputPostfix
        args.metaInfo = kotlinOptions.metaInfo
        args.kjsm = kotlinOptions.kjsm

        val kotlinJsLibsFromDependencies =
                project.configurations.getByName("compile")
                        .filter { LibraryUtils.isKotlinJavascriptLibrary(it) }
                        .map { it.absolutePath }

        args.libraryFiles = kotlinOptions.libraryFiles + kotlinJsLibsFromDependencies
        args.target = kotlinOptions.target
        args.sourceMap = kotlinOptions.sourceMap

        if (args.outputFile == null) {
            throw GradleException("$name.kotlinOptions.outputFile should be specified.")
        }

        val outputDir = File(args.outputFile).let { if (it.isDirectory) it else it.parentFile!! }
        if (!outputDir.exists()) {
            if (!outputDir.mkdirs()) {
                throw GradleException("Failed to create output directory ${outputDir} or one of its ancestors")
            }
        }

        logger.debug("$name set libraryFiles to ${args.libraryFiles.joinToString(",")}")
        logger.debug("$name set outputFile to ${args.outputFile}")

    }
}

private fun <T: Any> ExtraPropertiesExtension.getOrNull(id: String): T? {
    try {
        @Suppress("UNCHECKED_CAST")
        return get(id) as? T
    }
    catch (e: ExtraPropertiesExtension.UnknownPropertyException) {
        return null
    }
}

class GradleMessageCollector(val logger: Logger, val outputCollector: OutputItemsCollector? = null) : MessageCollector {
    override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageLocation) {
        val text = with(StringBuilder()) {
            append(when (severity) {
                in CompilerMessageSeverity.VERBOSE -> "v"
                in CompilerMessageSeverity.ERRORS -> "e"
                CompilerMessageSeverity.INFO -> "i"
                CompilerMessageSeverity.WARNING -> "w"
                else -> throw IllegalArgumentException("Unknown CompilerMessageSeverity: $severity")
            })
            append(": ")

            val (path, line, column) = location
            if (path != null) {
                append(path)
                append(": ")
                if (line > 0 && column > 0) {
                    append("($line, $column): ")
                }
            }

            append(message)

            toString()
        }
        when (severity) {
            in CompilerMessageSeverity.VERBOSE -> logger.debug(text)
            in CompilerMessageSeverity.ERRORS -> logger.error(text)
            CompilerMessageSeverity.INFO -> logger.info(text)
            CompilerMessageSeverity.WARNING -> logger.warn(text)
            else -> throw IllegalArgumentException("Unknown CompilerMessageSeverity: $severity")
        }
        // TODO: consider adding some other way of passing input -> output mapping from compiler, e.g. dedicated service
        if (outputCollector != null && severity == CompilerMessageSeverity.OUTPUT) {
            OutputMessageUtil.parseOutputMessage(message)?.let {
                outputCollector.add(it.sourceFiles, it.outputFile)
            }
        }
    }
}

internal fun Logger.kotlinInfo(message: String) {
    this.info("[KOTLIN] $message")
}

internal fun Logger.kotlinDebug(message: String) {
    this.debug("[KOTLIN] $message")
}

internal fun Logger.kotlinLazyDebug(makeMessage: () -> String) {
    if (this.isInfoEnabled)
        kotlinDebug(makeMessage())
}
