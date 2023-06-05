import annotation.Inject
import annotation.Provides
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.reflect.KClass

object Injector {

    private val methodToClassMap: MutableMap<Method, Class<*>> by lazy {
        mutableMapOf()
    }

    private val classToMethodMap: MutableMap<Class<*>, Method> by lazy {
        mutableMapOf()
    }

    private val classDependencies: MutableMap<Method, List<Class<*>>> by lazy {
        mutableMapOf()
    }

    private val methodTree: MutableMap<Method, MutableSet<Method>> by lazy {
        mutableMapOf()
    }

    private val appDependencies: MutableMap<Class<*>, Any> by lazy {
        mutableMapOf()
    }

    fun <T : DIModule> injectApp(clazz: KClass<T>) {
        saveMethods(clazz)

        generateDependenciesTree()

        methodTree.keys.forEach { method ->
            constructDependenciesByDFS(method, clazz.java.getConstructor().newInstance())
        }
    }

    fun <T : DIModule, R : Any> inject(module: KClass<T>, clazz: R) {

        saveMethods(module)
        generateDependenciesTree()

        val instnace = module.java.getConstructor().newInstance()

        // construct and inject dependencies

        clazz.javaClass.fields.filter { field ->
            field.isAnnotationPresent(Inject::class.java)
        }.onEach { field ->
            constructAndCacheDependencies(field, instnace)
        }.forEach { field ->
            field.set(clazz, appDependencies[field.type])
        }
    }

    private fun <T> constructAndCacheDependencies(field: Field?, instnace: T) {
        if (appDependencies.containsKey(field?.type)) {
            return
        }
        val rootMethod = classToMethodMap[field?.type]
        val safeRootMethod =
            rootMethod ?: throw IllegalArgumentException("Should have root entry point")
        constructDependenciesByDFS(safeRootMethod, instnace)
    }

    private fun <T> constructDependenciesByDFS(methodKey: Method, newInstance: T) {
        val methodDependencies = methodTree[methodKey].orEmpty()

        methodDependencies.forEach { method ->
            constructDependenciesByDFS(method, newInstance)
        }

        val parameters = classDependencies[methodKey]
            ?.map { clazz -> appDependencies[clazz] }
            ?.toTypedArray()
            .orEmpty()

        methodToClassMap[methodKey]?.let {
            appDependencies[it] = methodKey.invoke(newInstance, *parameters)
        }


    }

    private fun generateDependenciesTree() {
        methodToClassMap.keys.forEach { method ->
            if (methodTree[method] == null) {
                methodTree[method] = mutableSetOf()
            }

            val safeMethods: MutableList<Method> = mutableListOf()

            classDependencies[method]?.forEach { dependency ->
                val dependencyMethod = classToMethodMap[dependency]
                if (dependencyMethod != null) {
                    safeMethods.add(method)
                }
            }

            methodTree[method]?.addAll(safeMethods)
        }
    }

    private fun <T : DIModule> saveMethods(kClass: KClass<T>) {
        val methods = kClass::class.java.declaredMethods
        methods.filter { method ->
            method.isAnnotationPresent(Provides::class.java)
        }.forEach { method ->
            saveMethod(method)
        }
    }

    private fun saveMethod(method: Method) {
        methodToClassMap[method] = method.returnType
        classToMethodMap[method.returnType] = method
        classDependencies[method] = method.parameterTypes.toList()
    }

}