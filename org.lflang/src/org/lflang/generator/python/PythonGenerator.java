/* Generator for the Python target. */

/*************
 * Copyright (c) 2022, The University of California at Berkeley.
 * Copyright (c) 2022, The University of Texas at Dallas.

 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:

 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.

 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.

 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL
 * THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF
 * THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ***************/
package org.lflang.generator.python;

import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.base.Objects;

import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.xtext.util.CancelIndicator;
import org.eclipse.xtext.xbase.lib.Exceptions;
import org.eclipse.xtext.xbase.lib.IterableExtensions;
import org.lflang.ErrorReporter;
import org.lflang.FileConfig;
import org.lflang.InferredType;
import org.lflang.JavaAstUtils;
import org.lflang.Target;
import org.lflang.TargetConfig.Mode;
import org.lflang.federated.FedFileConfig;
import org.lflang.federated.FederateInstance;
import org.lflang.federated.launcher.FedPyLauncher;
import org.lflang.federated.serialization.FedNativePythonSerialization;
import org.lflang.federated.serialization.SupportedSerializers;
import org.lflang.generator.CodeBuilder;
import org.lflang.generator.CodeMap;
import org.lflang.generator.GeneratorBase;
import org.lflang.generator.GeneratorResult;
import org.lflang.generator.IntegratedBuilder;
import org.lflang.generator.JavaGeneratorUtils;
import org.lflang.generator.LFGeneratorContext;
import org.lflang.generator.ReactionInstance;
import org.lflang.generator.ReactorInstance;
import org.lflang.generator.SubContext;
import org.lflang.generator.TargetTypes;
import org.lflang.generator.c.CGenerator;
import org.lflang.generator.c.CUtil;
import org.lflang.generator.c.CPreambleGenerator;
import org.lflang.generator.python.PythonDockerGenerator;
import org.lflang.generator.python.PyUtil;
import org.lflang.generator.python.PythonReactionGenerator;
import org.lflang.generator.python.PythonReactorGenerator;
import org.lflang.generator.python.PythonParameterGenerator;
import org.lflang.generator.python.PythonNetworkGenerator;
import org.lflang.generator.python.PythonPreambleGenerator;
import org.lflang.lf.Action;
import org.lflang.lf.Delay;
import org.lflang.lf.Input;
import org.lflang.lf.Model;
import org.lflang.lf.Output;
import org.lflang.lf.Port;
import org.lflang.lf.Reaction;
import org.lflang.lf.Reactor;
import org.lflang.lf.ReactorDecl;
import org.lflang.lf.StateVar;
import org.lflang.lf.Value;
import org.lflang.lf.VarRef;
import org.lflang.util.LFCommand;
import org.lflang.generator.python.PythonInfoGenerator;
import org.lflang.ASTUtils;
import org.lflang.JavaAstUtils;


/** 
 * Generator for Python target. This class generates Python code defining each reactor
 * class given in the input .lf file and imported .lf files.
 * 
 * Each class will contain all the reaction functions defined by the user in order, with the necessary ports/actions given as parameters.
 * Moreover, each class will contain all state variables in native Python format.
 * 
 * A backend is also generated using the CGenerator that interacts with the C code library (see CGenerator.xtend).
 * The backend is responsible for passing arguments to the Python reactor functions.
 * 
 * @author{Soroush Bateni <soroush@utdallas.edu>}
 */
public class PythonGenerator extends CGenerator {

    // Used to add statements that come before reactor classes and user code
    private CodeBuilder pythonPreamble = new CodeBuilder();

    // Used to add module requirements to setup.py (delimited with ,)
    private List<String> pythonRequiredModules = new ArrayList<>();

    private PythonTypes types;

    public PythonGenerator(FileConfig fileConfig, ErrorReporter errorReporter) {
        this(fileConfig, errorReporter, new PythonTypes(errorReporter));
    }

    private PythonGenerator(FileConfig fileConfig, ErrorReporter errorReporter, PythonTypes types) {
        super(fileConfig, errorReporter, false, types);
        this.targetConfig.compiler = "gcc";
        this.targetConfig.compilerFlags = new ArrayList<>();
        this.targetConfig.linkerFlags = "";
        this.types = types;
    }

    /** 
     * Generic struct for ports with primitive types and
     * statically allocated arrays in Lingua Franca.
     * This template is defined as
     *   typedef struct {
     *       PyObject* value;
     *       bool is_present;
     *       int num_destinations;
     *       FEDERATED_CAPSULE_EXTENSION
     *   } generic_port_instance_struct;
     * 
     * @see reactor-c-py/lib/pythontarget.h
     */
    String genericPortType = "generic_port_instance_struct";

    /** 
     * Generic struct for ports with dynamically allocated
     * array types (a.k.a. token types) in Lingua Franca.
     * This template is defined as
     *   typedef struct {
     *       PyObject_HEAD
     *       PyObject* value;
     *       bool is_present;
     *       int num_destinations;
     *       lf_token_t* token;
     *       int length;
     *       FEDERATED_CAPSULE_EXTENSION
     *   } generic_port_instance_with_token_struct;
     * 
     * @see reactor-c-py/lib/pythontarget.h
     */
    String genericPortTypeWithToken = "generic_port_instance_with_token_struct";

    /**
     * Generic struct for actions.
     * This template is defined as
     *   typedef struct {
     *      trigger_t* trigger;
     *      PyObject* value;
     *      bool is_present;
     *      bool has_value;
     *      lf_token_t* token;
     *      FEDERATED_CAPSULE_EXTENSION
     *   } generic_action_instance_struct;
     * 
     * @see reactor-c-py/lib/pythontarget.h
     */
    String genericActionType = "generic_action_instance_struct";

    /** Returns the Target enum for this generator */
    @Override
    public Target getTarget() {
        return Target.Python;
    }

    private Set<String> protoNames = new HashSet<>();

    // //////////////////////////////////////////
    // // Public methods
    @Override
    public String printInfo() {
        System.out.println("Generating code for: " + fileConfig.resource.getURI().toString());
        System.out.println("******** Mode: " + fileConfig.context.getMode());
        System.out.println("******** Generated sources: " + fileConfig.getSrcGenPath());
        return null;
    }
    
    @Override
    public TargetTypes getTargetTypes() {
        return types;
    }

    // //////////////////////////////////////////
    // // Protected methods
    /**
     * Generate all Python classes if they have a reaction
     * @param federate The federate instance used to generate classes
     */
    public String generatePythonReactorClasses(FederateInstance federate) {
        CodeBuilder pythonClasses = new CodeBuilder();
        CodeBuilder pythonClassesInstantiation = new CodeBuilder();

        // Generate reactor classes in Python
        pythonClasses.pr(PythonReactorGenerator.generatePythonClass(main, federate, main, types));

        // Create empty lists to hold reactor instances
        pythonClassesInstantiation.pr(PythonReactorGenerator.generateListsToHoldClassInstances(main, federate));

        // Instantiate generated classes
        pythonClassesInstantiation.pr(PythonReactorGenerator.generatePythonClassInstantiations(main, federate, main));

        return String.join("\n", 
            pythonClasses.toString(), 
            "",
            "# Instantiate classes",
            pythonClassesInstantiation.toString()
        );
    }

    /**
     * Generate the Python code constructed from reactor classes and user-written classes.
     * @return the code body 
     */
    public String generatePythonCode(FederateInstance federate) {
        return String.join("\n", 
            "# List imported names, but do not use pylint's --extension-pkg-allow-list option",
            "# so that these names will be assumed present without having to compile and install.",
            "from LinguaFranca"+topLevelName+" import (  # pylint: disable=no-name-in-module",
            "    Tag, action_capsule_t, compare_tags, get_current_tag, get_elapsed_logical_time,",
            "    get_elapsed_physical_time, get_logical_time, get_microstep, get_physical_time,",
            "    get_start_time, port_capsule, port_instance_token, request_stop, schedule_copy,",
            "    start",
            ")",
            "from LinguaFrancaBase.constants import BILLION, FOREVER, NEVER, instant_t, interval_t",
            "from LinguaFrancaBase.functions import (",
            "    DAY, DAYS, HOUR, HOURS, MINUTE, MINUTES, MSEC, MSECS, NSEC, NSECS, SEC, SECS, USEC,",
            "    USECS, WEEK, WEEKS",
            ")",
            "from LinguaFrancaBase.classes import Make",
            "import sys",
            "import copy",
            "",
            pythonPreamble.toString(),
            "",
            generatePythonReactorClasses(federate),
            "",
            PythonMainGenerator.generateCode()
        );
    }

    /**
     * Generate the setup.py required to compile and install the module.
     * Currently, the package name is based on filename which does not support sharing the setup.py for multiple .lf files.
     * TODO: use an alternative package name (possibly based on folder name)
     * 
     * If the LF program itself is threaded or if tracing is enabled, NUMBER_OF_WORKERS is added as a macro
     * so that platform-specific C files will contain the appropriate functions.
     */
    public String generatePythonSetupFile() {
        String moduleName = "LinguaFranca" + topLevelName;

        List<String> sources = new ArrayList<>(targetConfig.compileAdditionalSources);
        sources.add(topLevelName + ".c");
        sources.replaceAll(PythonGenerator::addDoubleQuotes);

        List<String> macros = new ArrayList<>();
        macros.add(generateMacroEntry("MODULE_NAME", moduleName));
        if (targetConfig.threads != 0 || targetConfig.tracing != null) {
            macros.add(generateMacroEntry("NUMBER_OF_WORKERS", String.valueOf(targetConfig.threads)));
        }

        List<String> installRequires = new ArrayList<>(pythonRequiredModules);
        installRequires.add("LinguaFrancaBase");
        installRequires.replaceAll(PythonGenerator::addDoubleQuotes);

        return String.join("\n", 
            "from setuptools import setup, Extension",
            "",
            "linguafranca"+topLevelName+"module = Extension("+addDoubleQuotes(moduleName)+",",
            "                                            sources = ["+String.join(", ", sources)+"],",
            "                                            define_macros=["+String.join(", ", macros)+"])",
            "",
            "setup(name="+addDoubleQuotes(moduleName)+", version=\"1.0\",",
            "        ext_modules = [linguafranca"+topLevelName+"module],",
            "        install_requires=["+String.join(", ", installRequires)+"])"
        );
    }

    /**
     * Generate the necessary Python files.
     * @param federate The federate instance
     */
    public Map<Path, CodeMap> generatePythonFiles(FederateInstance federate) throws IOException {
        Path filePath = fileConfig.getSrcGenPath().resolve(topLevelName + ".py");
        File file = filePath.toFile();
        Files.deleteIfExists(filePath);
        // Create the necessary directories
        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }
        Map<Path, CodeMap> codeMaps = new HashMap<>();
        codeMaps.put(filePath, CodeMap.fromGeneratedCode(generatePythonCode(federate).toString()));
        JavaGeneratorUtils.writeToFile(codeMaps.get(filePath).getGeneratedCode(), filePath);
        
        Path setupPath = fileConfig.getSrcGenPath().resolve("setup.py");
        // Handle Python setup
        System.out.println("Generating setup file to " + setupPath);
        Files.deleteIfExists(setupPath);

        // Create the setup file
        JavaGeneratorUtils.writeToFile(generatePythonSetupFile(), setupPath);
        return codeMaps;
    }

    /**
     * Execute the command that compiles and installs the current Python module
     */
    public void pythonCompileCode(LFGeneratorContext context) {
        // if we found the compile command, we will also find the install command
        LFCommand installCmd = commandFactory.createCommand(
            "python3", List.of("-m", "pip", "install", "--force-reinstall", "."), fileConfig.getSrcGenPath()
        );

        if (installCmd == null) {
            errorReporter.reportError(
                "The Python target requires Python >= 3.6, pip >= 20.0.2, and setuptools >= 45.2.0-1 to compile the generated code. " +
                    "Auto-compiling can be disabled using the \"no-compile: true\" target property.");
            return;
        }

        // Set compile time environment variables
        installCmd.setEnvironmentVariable("CC", targetConfig.compiler); // Use gcc as the compiler
        installCmd.setEnvironmentVariable("LDFLAGS", targetConfig.linkerFlags); // The linker complains about including pythontarget.h twice (once in the generated code and once in pythontarget.c)
        // To avoid this, we force the linker to allow multiple definitions. Duplicate names would still be caught by the 
        // compiler.
        if (installCmd.run(context.getCancelIndicator()) == 0) {
            System.out.println("Successfully installed python extension.");
        } else {
            errorReporter.reportError("Failed to install python extension due to the following errors:\n" +
                installCmd.getErrors());
        }
    }

    /** 
     * Generate top-level preambles and #include of pqueue.c and either reactor.c or reactor_threaded.c
     *  depending on whether threads are specified in target directive.
     *  As a side effect, this populates the runCommand and compileCommand
     *  private variables if such commands are specified in the target directive.
     * 
     * TODO: This function returns a boolean because xtend-generated parent function in CGenerator returns boolean
     */
    @Override
    public boolean generatePreamble() {
        Set<Model> models = new LinkedHashSet<>();
        for (Reactor r : ASTUtils.convertToEmptyListIfNull(reactors)) {
            // The following assumes all reactors have a container.
            // This means that generated reactors **have** to be
            // added to a resource; not doing so will result in a NPE.
            models.add((Model) ASTUtils.toDefinition(r).eContainer());
        }
        // Add the main reactor if it is defined
        if (this.mainDef != null) {
            models.add((Model) ASTUtils.toDefinition(this.mainDef.getReactorClass()).eContainer());
        }
        for (Model m : models) {
            pythonPreamble.pr(PythonPreambleGenerator.generatePythonPreambles(m.getPreambles()));
        }
        code.pr(CGenerator.defineLogLevel(this));
        if (isFederated) {
            code.pr(CPreambleGenerator.generateFederatedDirective(targetConfig.coordination));
            // Handle target parameters.
            // First, if there are federates, then ensure that threading is enabled.
            targetConfig.threads = CUtil.minThreadsToHandleInputPorts(federates);
        }
        includeTargetLanguageHeaders();
        code.pr(CPreambleGenerator.generateNumFederatesDirective(federates.size()));
        code.pr(CPreambleGenerator.generateMixedRadixIncludeHeader());
        super.includeTargetLanguageSourceFiles();
        super.parseTargetParameters();
        return false; // placeholder return value. See comment above
    }

    /**
     * Add necessary code to the source and necessary build supports to
     * enable the requested serializations in 'enabledSerializations'
     */
    @Override 
    public void enableSupportForSerializationIfApplicable(CancelIndicator cancelIndicator) {
        if (!IterableExtensions.isNullOrEmpty(targetConfig.protoFiles)) {
            // Enable support for proto serialization
            enabledSerializers.add(SupportedSerializers.PROTO);
        }
        for (SupportedSerializers serialization : enabledSerializers) {
            switch (serialization) {
                case NATIVE: {
                    FedNativePythonSerialization pickler = new FedNativePythonSerialization();
                    code.pr(pickler.generatePreambleForSupport().toString());
                }
                case PROTO: {
                    // Handle .proto files.
                    for (String name : targetConfig.protoFiles) {
                        this.processProtoFile(name, cancelIndicator);
                        int dotIndex = name.lastIndexOf(".");
                        String rootFilename = dotIndex > 0 ? name.substring(0, dotIndex) : name;
                        pythonPreamble.pr("import "+rootFilename+"_pb2 as "+rootFilename);
                        protoNames.add(rootFilename);
                    }
                }
                case ROS2: {
                    // FIXME: Not supported yet
                }
            }
        }
    }

    /**
     * Process a given .proto file.
     * 
     * Run, if possible, the proto-c protocol buffer code generator to produce
     * the required .h and .c files.
     * @param filename Name of the file to process.
     */
    @Override 
    public void processProtoFile(String filename, CancelIndicator cancelIndicator) {
        LFCommand protoc = commandFactory.createCommand(
            "protoc", List.of("--python_out="+fileConfig.getSrcGenPath(), filename), fileConfig.srcPath);
        
        if (protoc == null) {
            errorReporter.reportError("Processing .proto files requires libprotoc >= 3.6.1");
            return;
        }
        int returnCode = protoc.run(cancelIndicator);
        if (returnCode == 0) {
            pythonRequiredModules.add("google-api-python-client");
        } else {
            errorReporter.reportError("protoc returns error code " + returnCode);
        }
    }

    /**
     * Generate code for the body of a reaction that handles the
     * action that is triggered by receiving a message from a remote
     * federate.
     * @param action The action.
     * @param sendingPort The output port providing the data to send.
     * @param receivingPort The ID of the destination port.
     * @param receivingPortID The ID of the destination port.
     * @param sendingFed The sending federate.
     * @param receivingFed The destination federate.
     * @param receivingBankIndex The receiving federate's bank index, if it is in a bank.
     * @param receivingChannelIndex The receiving federate's channel index, if it is a multiport.
     * @param type The type.
     * @param isPhysical Indicates whether or not the connection is physical
     * @param serializer The serializer used on the connection.
     */
    @Override 
    public String generateNetworkReceiverBody(
        Action action,
        VarRef sendingPort,
        VarRef receivingPort,
        int receivingPortID,
        FederateInstance sendingFed,
        FederateInstance receivingFed,
        int receivingBankIndex,
        int receivingChannelIndex,
        InferredType type,
        boolean isPhysical,
        SupportedSerializers serializer
    ) {
        return PythonNetworkGenerator.generateNetworkReceiverBody(
            action, 
            sendingPort, 
            receivingPort, 
            receivingPortID, 
            sendingFed,
            receivingFed, 
            receivingBankIndex,
            receivingChannelIndex,
            type,
            isPhysical,
            serializer
        );
    }

    /**
     * Generate code for the body of a reaction that handles an output
     * that is to be sent over the network.
     * @param sendingPort The output port providing the data to send.
     * @param receivingPort The variable reference to the destination port.
     * @param receivingPortID The ID of the destination port.
     * @param sendingFed The sending federate.
     * @param sendingBankIndex The bank index of the sending federate, if it is a bank.
     * @param sendingChannelIndex The channel index of the sending port, if it is a multiport.
     * @param receivingFed The destination federate.
     * @param type The type.
     * @param isPhysical Indicates whether the connection is physical or not
     * @param delay The delay value imposed on the connection using after
     * @param serializer The serializer used on the connection.
     */
    @Override 
    public String generateNetworkSenderBody(
        VarRef sendingPort,
        VarRef receivingPort,
        int receivingPortID,
        FederateInstance sendingFed,
        int sendingBankIndex,
        int sendingChannelIndex,
        FederateInstance receivingFed,
        InferredType type,
        boolean isPhysical,
        Delay delay,
        SupportedSerializers serializer
    ) {
        return PythonNetworkGenerator.generateNetworkSenderBody(
            sendingPort,
            receivingPort,
            receivingPortID,
            sendingFed,
            sendingBankIndex,
            sendingChannelIndex,
            receivingFed,
            type,
            isPhysical,
            delay,
            serializer,
            targetConfig.coordination
        );
    }

    /**
     * Create a launcher script that executes all the federates and the RTI.
     * 
     * @param coreFiles The files from the core directory that must be
     *  copied to the remote machines.
     */
    @Override
    public void createFederatedLauncher(ArrayList<String> coreFiles) {
        FedPyLauncher launcher = new FedPyLauncher(
            targetConfig,
            fileConfig,
            errorReporter
        );
        try {
            launcher.createLauncher(
                coreFiles,
                federates,
                federationRTIProperties
            );
        } catch (IOException e) {
            // ignore
        }
    }

    /**
     * Generate the aliases for inputs, outputs, and struct type definitions for 
     * actions of the specified reactor in the specified federate.
     * @param reactor The parsed reactor data structure.
     * @param federate A federate name, or null to unconditionally generate.
     */
    @Override 
    public void generateAuxiliaryStructs(
        ReactorDecl decl
    ) {
        Reactor reactor = ASTUtils.toDefinition(decl);
        // First, handle inputs.
        for (Input input : ASTUtils.allInputs(reactor)) {
            generateAuxiliaryStructsForPort(decl, input);
        }
        // Next, handle outputs.
        for (Output output : ASTUtils.allOutputs(reactor)) {
            generateAuxiliaryStructsForPort(decl, output);
        }
        // Finally, handle actions.
        for (Action action : ASTUtils.allActions(reactor)) {
            generateAuxiliaryStructsForAction(decl, currentFederate, action);
        }
    }

    private void generateAuxiliaryStructsForPort(ReactorDecl decl,
                                                 Port port) {
        boolean isTokenType = CUtil.isTokenType(JavaAstUtils.getInferredType(port), types);
        code.pr(port, 
                PythonPortGenerator.generateAliasTypeDef(decl, port, isTokenType, 
                                                         genericPortTypeWithToken, 
                                                         genericPortType));
    }

    private void generateAuxiliaryStructsForAction(ReactorDecl decl,
                                                   FederateInstance federate,
                                                   Action action) {
        if (federate != null && !federate.contains(action)) {
            return;
        }
        code.pr(action, PythonActionGenerator.generateAliasTypeDef(decl, action, genericActionType));
    }

    /**
     * For the specified action, return a declaration for action struct to
     * contain the value of the action.
     * This will return an empty string for an action with no type.
     * @param action The action.
     * @return A string providing the value field of the action struct.
     */
    @Override
    public String valueDeclaration(Action action) {
        return "PyObject* value;";
    }

    /** Add necessary include files specific to the target language.
     *  Note. The core files always need to be (and will be) copied 
     *  uniformly across all target languages.
     */
    @Override
    public void includeTargetLanguageHeaders() {
        code.pr("#define _LF_GARBAGE_COLLECTED"); 
        if (targetConfig.tracing != null) {
            var filename = "";
            if (targetConfig.tracing.traceFileName != null) {
                filename = targetConfig.tracing.traceFileName;
            }
            code.pr("#define LINGUA_FRANCA_TRACE " + filename);
        }
                       
        code.pr("#include \"pythontarget.c\"");
        if (targetConfig.tracing != null) {
            code.pr("#include \"core/trace.c\"");
        }
    }

    /**
     * Return true if the host operating system is compatible and
     * otherwise report an error and return false.
     */
    @Override 
    public boolean isOSCompatible() {
        if (JavaGeneratorUtils.isHostWindows() && isFederated) {
            errorReporter.reportError(
                "Federated LF programs with a Python target are currently not supported on Windows. Exiting code generation."
            );
            // Return to avoid compiler errors
            return false;
        }
        return true;
    }

    /** Generate C code from the Lingua Franca model contained by the
     *  specified resource. This is the main entry point for code
     *  generation.
     *  @param resource The resource containing the source code.
     *  @param context Context relating to invocation of the code generator.
     */
    @Override 
    public void doGenerate(Resource resource, LFGeneratorContext context) {
        // If there are federates, assign the number of threads in the CGenerator to 1        
        if (isFederated) {
            targetConfig.threads = 1;
        }

        // Prevent the CGenerator from compiling the C code.
        // The PythonGenerator will compiler it.
        boolean compileStatus = targetConfig.noCompile;
        targetConfig.noCompile = true;
        targetConfig.useCmake = false; // Force disable the CMake because 
        // it interferes with the Python target functionality
        int cGeneratedPercentProgress = (IntegratedBuilder.VALIDATED_PERCENT_PROGRESS + 100) / 2;
        super.doGenerate(resource, new SubContext(
            context,
            IntegratedBuilder.VALIDATED_PERCENT_PROGRESS,
            cGeneratedPercentProgress
        ));
        SubContext compilingFederatesContext = new SubContext(context, cGeneratedPercentProgress, 100);
        targetConfig.noCompile = compileStatus;

        if (errorsOccurred()) {
            context.unsuccessfulFinish();
            return;
        }

        String baseFileName = topLevelName;
        // Keep a separate file config for each federate
        FileConfig oldFileConfig = fileConfig;
        var federateCount = 0;
        Map<Path, CodeMap> codeMaps = new HashMap<>();
        for (FederateInstance federate : federates) {
            federateCount++;
            if (isFederated) {
                topLevelName = baseFileName + '_' + federate.name;
                try {
                    fileConfig = new FedFileConfig(fileConfig, federate.name);
                } catch (IOException e) {
                    throw Exceptions.sneakyThrow(e);
                }
            }
            // Don't generate code if there is no main reactor
            if (this.main != null) {
                try {
                    Map<Path, CodeMap> codeMapsForFederate = generatePythonFiles(federate);
                    codeMaps.putAll(codeMapsForFederate);
                    PyUtil.copyTargetFiles(fileConfig);
                    if (!targetConfig.noCompile) {
                        compilingFederatesContext.reportProgress(
                            String.format("Validating %d/%d sets of generated files...", federateCount, federates.size()),
                            100 * federateCount / federates.size()
                        );
                        // If there are no federates, compile and install the generated code
                        new PythonValidator(fileConfig, errorReporter, codeMaps, protoNames).doValidate(context);
                        if (!errorsOccurred() && !Objects.equal(context.getMode(), Mode.LSP_MEDIUM)) {
                            compilingFederatesContext.reportProgress(
                                String.format("Validation complete. Compiling and installing %d/%d Python modules...",
                                    federateCount, federates.size()),
                                100 * federateCount / federates.size()
                            );
                            pythonCompileCode(context); // Why is this invoked here if the current federate is not a parameter?
                        }
                    } else {
                        System.out.println(PythonInfoGenerator.generateSetupInfo(fileConfig));
                    }
                } catch (Exception e) {
                    throw Exceptions.sneakyThrow(e);
                }

                if (!isFederated) {
                    System.out.println(PythonInfoGenerator.generateRunInfo(fileConfig, topLevelName));
                }
            }
            fileConfig = oldFileConfig;
        }
        if (isFederated) {
            System.out.println(PythonInfoGenerator.generateFedRunInfo(fileConfig));
        }
        // Restore filename
        topLevelName = baseFileName;
        if (errorReporter.getErrorsOccurred()) {
            context.unsuccessfulFinish();
        } else if (!isFederated) {
            context.finish(GeneratorResult.Status.COMPILED, topLevelName+".py", fileConfig.getSrcGenPath(), fileConfig,
                codeMaps, "python3");
        } else {
            context.finish(GeneratorResult.Status.COMPILED, fileConfig.name, fileConfig.binPath, fileConfig, codeMaps,
                "bash");
        }
    }
    

    /**
     * Generate code for the body of a reaction that takes an input and
     * schedules an action with the value of that input.
     * @param action The action to schedule
     * @param port The port to read from
     */
    @Override 
    public String generateDelayBody(Action action, VarRef port) {
        return PythonReactionGenerator.generateCDelayBody(action, port, CUtil.isTokenType(JavaAstUtils.getInferredType(action), types));
    }

    /**
     * Generate code for the body of a reaction that is triggered by the
     * given action and writes its value to the given port. This realizes
     * the receiving end of a logical delay specified with the 'after'
     * keyword.
     * @param action The action that triggers the reaction
     * @param port The port to write to.
     */
    @Override 
    public String generateForwardBody(Action action, VarRef port) {
        String outputName = JavaAstUtils.generateVarRef(port);
        if (CUtil.isTokenType(JavaAstUtils.getInferredType(action), types)) {
            return super.generateForwardBody(action, port);
        } else {
            return "SET("+outputName+", "+action.getName()+"->token->value);";
        }
    }

    /** Generate a reaction function definition for a reactor.
     *  This function has a single argument that is a void* pointing to
     *  a struct that contains parameters, state variables, inputs (triggering or not),
     *  actions (triggering or produced), and outputs.
     *  @param reaction The reaction.
     *  @param reactor The reactor.
     *  @param reactionIndex The position of the reaction within the reactor. 
     */
    @Override 
    public void generateReaction(Reaction reaction, ReactorDecl decl, int reactionIndex) {
        Reactor reactor = ASTUtils.toDefinition(decl);

        // Delay reactors and top-level reactions used in the top-level reactor(s) in federated execution are generated in C
        if (reactor.getName().contains(GEN_DELAY_CLASS_NAME) ||
            ((mainDef != null && decl == mainDef.getReactorClass() || mainDef == decl) && reactor.isFederated())) {
            super.generateReaction(reaction, decl, reactionIndex);
            return;
        }
        code.pr(PythonReactionGenerator.generateCReaction(reaction, decl, reactionIndex, mainDef, errorReporter, types, isFederatedAndDecentralized()));
    }

    /**
     * Generate code that initializes the state variables for a given instance.
     * Unlike parameters, state variables are uniformly initialized for all instances
     * of the same reactor. This task is left to Python code to allow for more liberal
     * state variable assignments.
     * @param instance The reactor class instance
     * @return Initialization code fore state variables of instance
     */
    @Override 
    public void generateStateVariableInitializations(ReactorInstance instance) {
        // Do nothing
    }

    /**
     * Generate code for parameter variables of a reactor in the form "parameter.type parameter.name;"
     * 
     * FIXME: for now we assume all parameters are int. This is to circumvent the issue of parameterized
     * port widths for now.
     * 
     * @param reactor The reactor.
     * @param builder The place that the generated code is written to.
     * @return 
     */
    @Override 
    public void generateParametersForReactor(CodeBuilder builder, Reactor reactor) {
        // Do nothing
        builder.pr(PythonParameterGenerator.generateCDeclarations(reactor));
    }

    /**
     * Generate runtime initialization code in C for parameters of a given reactor instance.
     * All parameters are also initialized in Python code, but those parameters that are
     * used as width must be also initialized in C.
     * 
     * FIXME: Here, we use a hack: we attempt to convert the parameter initialization to an integer.
     * If it succeeds, we proceed with the C initialization. If it fails, we defer initialization
     * to Python.
     * 
     * Generate runtime initialization code for parameters of a given reactor instance
     * @param instance The reactor instance.
     */
    @Override
    public void generateParameterInitialization(ReactorInstance instance) {
        // Do nothing
        initializeTriggerObjects.pr(PythonParameterGenerator.generateCInitializers(instance));
    }

    /**
     * This function is overridden in the Python generator to do nothing.
     * The state variables are initialized in Python code directly.
     * @param reactor The reactor.
     * @param builder The place that the generated code is written to.
     * @return 
     */
    @Override 
    public void generateStateVariablesForReactor(CodeBuilder builder, Reactor reactor) {        
        // Do nothing
    }

    /**
     * Generates C preambles defined by user for a given reactor
     * Since the Python generator expects preambles written in C,
     * this function is overridden and does nothing.
     * @param reactor The given reactor
     */
    @Override 
    public void generateUserPreamblesForReactor(Reactor reactor) {
        // Do nothing
    }

    /**
     * Generate code that is executed while the reactor instance is being initialized.
     * This wraps the reaction functions in a Python function.
     * @param instance The reactor instance.
     * @param reactions The reactions of this instance.
     */
    @Override 
    public void generateReactorInstanceExtension(
        ReactorInstance instance
    ) {
        initializeTriggerObjects.pr(PythonReactionGenerator.generateCPythonReactionLinkers(instance, mainDef, topLevelName));
    }

    /**
     * This function is provided to allow extensions of the CGenerator to append the structure of the self struct
     * @param selfStructBody The body of the self struct
     * @param decl The reactor declaration for the self struct
     * @param instance The current federate instance
     * @param constructorCode Code that is executed when the reactor is instantiated
     */
    @Override 
    public void generateSelfStructExtension(
        CodeBuilder selfStructBody, 
        ReactorDecl decl, 
        CodeBuilder constructorCode
    ) {
        Reactor reactor = ASTUtils.toDefinition(decl);
        // Add the name field
        selfStructBody.pr("char *_lf_name;");
        int reactionIndex = 0;
        for (Reaction reaction : ASTUtils.allReactions(reactor)) {
            // Create a PyObject for each reaction
            selfStructBody.pr("PyObject* "+PythonReactionGenerator.generateCPythonReactionFunctionName(reactionIndex)+";");
            if (reaction.getDeadline() != null) {                
                selfStructBody.pr("PyObject* "+PythonReactionGenerator.generateCPythonDeadlineFunctionName(reactionIndex)+";");
            }
            reactionIndex++;
        }
    }

    /**
     * Write a Dockerfile for the current federate as given by filename.
     * The file will go into src-gen/filename.Dockerfile.
     * If there is no main reactor, then no Dockerfile will be generated
     * (it wouldn't be very useful).
     * @param The directory where the docker compose file is generated.
     * @param The name of the docker file.
     * @param The name of the federate.
     */
    @Override 
    public void writeDockerFile(File dockerComposeDir, String dockerFileName, String federateName) {
        if (mainDef == null) {
            return;
        }
        Path srcGenPath = fileConfig.getSrcGenPath();
        String dockerFile = srcGenPath + File.separator + dockerFileName;
        CodeBuilder contents = new CodeBuilder();
        contents.pr(PythonDockerGenerator.generateDockerFileContent(topLevelName, srcGenPath));
        try {
            // If a dockerfile exists, remove it.
            Files.deleteIfExists(srcGenPath.resolve(dockerFileName));
            contents.writeToFile(dockerFile);
        } catch (IOException e) {
            Exceptions.sneakyThrow(e);
        }
        System.out.println(getDockerBuildCommand(dockerFile, dockerComposeDir, federateName));
    }

    private static String addDoubleQuotes(String str) {
        return "\""+str+"\"";
    }

    private static String generateMacroEntry(String key, String val) {
        return "(" + addDoubleQuotes(key) + ", " + addDoubleQuotes(val) + ")";
    }
}
