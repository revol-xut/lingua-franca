package org.lflang.generator.ts

import org.lflang.*
import org.lflang.generator.FederateInstance
import org.lflang.generator.PrependOperator
import org.lflang.lf.*
import java.lang.StringBuilder
import java.util.*
import kotlin.collections.LinkedHashMap

/**
 * Reactor generator for TypeScript target.
 *
 *  @author{Matt Weber <matt.weber@berkeley.edu>}
 *  @author{Edward A. Lee <eal@berkeley.edu>}
 *  @author{Marten Lohstroh <marten@berkeley.edu>}
 *  @author {Christian Menard <christian.menard@tu-dresden.de>}
 *  @author {Hokeun Kim <hokeunkim@berkeley.edu>}
 */
class TSReactorGenerator(
    private val tsGenerator: TSGenerator,
    private val errorReporter: ErrorReporter
) {
    private val code = StringBuilder()

    /**
     * Map from builder to its current indentation.
     */
    val indentation = LinkedHashMap<StringBuilder, String>()

    // Wrapper functions and their helpers
    private fun indent(builder: StringBuilder) = tsGenerator.indentw(builder)
    private fun unindent(builder: StringBuilder) = tsGenerator.unindentw(builder)
    private fun indent() = indent(code)
    private fun unindent() = unindent(code)

    private fun pr(builder: StringBuilder, text: Any) = tsGenerator.prw(builder, text)
    private fun pr(text: Any) = tsGenerator.prw(code, text)

    private fun Parameter.getTargetType(): String = tsGenerator.getTargetTypeW(this)

    private fun getInitializerList(param: Parameter): List<String> =
        tsGenerator.getInitializerListW(param)

    private fun federationRTIProperties(): LinkedHashMap<String, Any> {
        return tsGenerator.federationRTIPropertiesW()
    }

    // Initializer functions
    fun getTargetInitializerHelper(param: Parameter,
                                           list: List<String>): String {
        return if (list.size == 0) {
            errorReporter.reportError(param, "Parameters must have a default value!")
        } else if (list.size == 1) {
            list[0]
        } else {
            list.joinToString(", ", "[", "]")
        }
    }
    private fun getTargetInitializer(param: Parameter): String {
        return getTargetInitializerHelper(param, getInitializerList(param))
    }
    private fun initializeParameter(p: Parameter): String {
        return """${p.name}: ${p.getTargetType()} = ${getTargetInitializer(p)}"""
    }

    private fun generateConstructorArguments(reactor: Reactor): String {
        val arguments = LinkedList<String>()
        if (reactor.isMain || reactor.isFederated) {
            arguments.add("timeout: TimeValue | undefined = undefined")
            arguments.add("keepAlive: boolean = false")
            arguments.add("fast: boolean = false")
        } else {
            arguments.add("parent: __Reactor")
        }

        // For TS, parameters are arguments of the class constructor.
        for (parameter in reactor.parameters) {
            arguments.add(initializeParameter(parameter))
        }

        if (reactor.isMain || reactor.isFederated) {
            arguments.add("success?: () => void")
            arguments.add("fail?: () => void")
        }

        return with(PrependOperator) {
            """
                |constructor (
            ${" |    "..arguments.joinToString(", \n")}
                |)
            """.trimMargin()}
    }

    // If the app is federated, register its
    // networkMessageActions with the RTIClient
    private fun generateFederatePortActionRegistrations(networkMessageActions: List<Action>): String {
        var fedPortID = 0;
        val connectionInstantiations = LinkedList<String>()
        for (nAction in networkMessageActions) {
            val registration = """
                this.registerFederatePortAction(${fedPortID}, this.${nAction.name});
                """
            connectionInstantiations.add(registration)
            fedPortID++
        }
        return connectionInstantiations.joinToString("\n")
    }

    // TODO(hokeun): Split this method into smaller methods.
    fun generateReactorFederated(reactor: Reactor, federate: FederateInstance) {
        pr("// =============== START reactor class " + reactor.name)

        for (p in reactor.preambles?: emptyList()) {
            pr("// *********** From the preamble, verbatim:")
            pr(p.code.toText())
            pr("\n// *********** End of preamble.")
        }

        var reactorName = reactor.name
        if (!reactor.typeParms.isEmpty()) {
            reactorName +=
                reactor.typeParms.joinToString(", ", "<", ">") { it.toText() }
        }
        // NOTE: type parameters that are referenced in ports or actions must extend
        // Present in order for the program to type check.
        if (reactor.isMain()) {
            pr("class $reactorName extends __App {")
        } else if (reactor.isFederated) {
            pr("class $reactorName extends __FederatedApp {")
        } else {
            pr("export class $reactorName extends __Reactor {")
        }

        indent()

        val reactorConstructor = StringBuilder()
        pr(reactorConstructor, "${generateConstructorArguments(reactor)} {")
        indent(reactorConstructor)
        var superCall: String
        if (reactor.isMain) {
            superCall = "super(timeout, keepAlive, fast, success, fail);"
        } else if (reactor.isFederated) {
            var port = federationRTIProperties()["port"]
            // Default of 0 is an indicator to use the default port, 15045.
            if (port == 0) {
                port = 15045
            }
            superCall = """
            super(${federate.id}, ${port},
                "${federationRTIProperties()["host"]}",
                timeout, keepAlive, fast, success, fail);
            """
        } else {
            superCall = "super(parent);"
        }
        pr(reactorConstructor, superCall)

        var instanceGenerator = TSInstanceGenerator(tsGenerator, this, reactor, federate)
        var timerGenerator = TSTimerGenerator(tsGenerator, reactor.timers)
        var parameterGenerator = TSParameterGenerator(tsGenerator, reactor.parameters)
        val stateGenerator = TSStateGenerator(tsGenerator, reactor.stateVars)
        val actionGenerator = TSActionGenerator(tsGenerator, reactor.actions)
        val portGenerator = TSPortGenerator(tsGenerator, reactor.inputs, reactor.outputs)
        val connectionGenerator = TSConnectionGenerator(reactor.connections, errorReporter)

        pr(with(PrependOperator) {
            """
            ${" |"..instanceGenerator.generateClassProperties()}
            ${" |"..timerGenerator.generateClassProperties()}
            ${" |"..parameterGenerator.generateClassProperties()}
            ${" |"..stateGenerator.generateClassProperties()}
            ${" |"..actionGenerator.generateClassProperties()}
            ${" |"..portGenerator.generateClassProperties()}
            """.trimMargin()
        })

        pr(reactorConstructor, with(PrependOperator) {
            """
            ${" |"..instanceGenerator.generateInstantiations()}
            ${" |"..timerGenerator.generateInstantiations()}
            ${" |"..parameterGenerator.generateInstantiations()}
            ${" |"..stateGenerator.generateInstantiations()}
            ${" |"..actionGenerator.generateInstantiations()}
            ${" |"..portGenerator.generateInstantiations()}
            ${" |"..connectionGenerator.generateInstantiations()}
            ${" |"..if (reactor.isFederated) generateFederatePortActionRegistrations(federate.networkMessageActions) else ""}
            """.trimMargin()
        })

        // Next handle reaction instances.
        // If the app is federated, only generate
        // reactions that are contained by that federate
        val generatedReactions: List<Reaction>
        if (reactor.isFederated) {
            generatedReactions = LinkedList<Reaction>()
            for (reaction in reactor.reactions) {
                if (federate.containsReaction(reactor, reaction)) {
                    generatedReactions.add(reaction)
                }
            }
        } else {
            generatedReactions = reactor.reactions
        }

        ///////////////////// Reaction generation begins /////////////////////
        // TODO(hokeun): Consider separating this out as a new class.
        for (reaction in generatedReactions) {
            // Write the reaction itself
            val reactionGenerator = TSReactionGenerator(tsGenerator, errorReporter)
            pr(reactorConstructor, reactionGenerator.generateReaction(reactor, reaction))
        }
        ///////////////////// Reaction generation ends /////////////////////

        unindent(reactorConstructor)
        pr(reactorConstructor, "}")
        pr(reactorConstructor.toString())
        unindent()
        pr("}")
        pr("// =============== END reactor class " + reactor.name)
        pr("")

    }

    /** Generate the main app instance. This function is only used once
     *  because all other reactors are instantiated as properties of the
     *  main one.
     *  @param instance A reactor instance.
     */
    fun generateReactorInstance(defn: Instantiation, mainParameters: Set<Parameter>): String {
        val fullName = defn.name

        // Iterate through parameters in the order they appear in the
        // main reactor class. If the parameter is typed such that it can
        // be a custom command line argument, use the parameter's command line
        // assignment variable ("__CL" + the parameter's name). That variable will
        // be undefined if the command line argument wasn't specified. Otherwise
        // use undefined in the constructor.
        val mainReactorParams = StringJoiner(", ")
        for (parameter in defn.reactorClass.toDefinition().parameters) {

            if (mainParameters.contains(parameter)) {
                mainReactorParams.add("__CL" + parameter.name)
            } else {
                mainReactorParams.add("undefined")
            }
        }

        return with(PrependOperator) {
            """
            |// ************* Instance $fullName of class ${defn.reactorClass.name}
            |let __app;
            |if (!__noStart) {
            |    __app = new $fullName(__timeout, __keepAlive, __fast, $mainReactorParams);
            |}
            """
        }.trimMargin()
    }

    /** Generate code to call the _start function on the main App
     *  instance to start the runtime
     *  @param instance A reactor instance.
     */
    private fun generateRuntimeStart(defn: Instantiation): String {
        return with(PrependOperator) {
                """
            |// ************* Starting Runtime for ${defn.name} + of class ${defn.reactorClass.name}.
            |if (!__noStart && __app) {
            |    __app._start();
            |}
            """
            }.trimMargin()
    }

    fun generateReactor(reactor: Reactor, federate: FederateInstance) {
        generateReactorFederated(reactor, federate)
    }

    fun generateReactorInstanceAndStart(mainDef: Instantiation, mainParameters: Set<Parameter>) {
        pr(generateReactorInstance(mainDef, mainParameters))
        pr(generateRuntimeStart(mainDef))
    }

    fun getCode(): String {
        return code.toString()
    }
}