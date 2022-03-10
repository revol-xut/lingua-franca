package org.lflang.generator.c;

import java.util.List;
import java.util.ArrayList;

import org.lflang.ASTUtils;
import org.lflang.federated.FederateInstance;
import org.lflang.generator.ActionInstance;
import org.lflang.generator.CodeBuilder;
import org.lflang.generator.GeneratorBase;
import org.lflang.generator.ReactorInstance;
import org.lflang.lf.Action;
import org.lflang.lf.Reactor;
import org.lflang.lf.ReactorDecl;

/**
 * Generates code for actions (logical or physical) for the C and CCpp target.
 * 
 * @author{Edward A. Lee <eal@berkeley.edu>}
 * @author{Marten Lohstroh <marten@berkeley.edu>}
 * @author{Mehrdad Niknami <mniknami@berkeley.edu>}
 * @author{Christian Menard <christian.menard@tu-dresden.de>}
 * @author{Matt Weber <matt.weber@berkeley.edu>}
 * @author{Soroush Bateni <soroush@utdallas.edu>
 * @author{Alexander Schulz-Rosengarten <als@informatik.uni-kiel.de>}
 * @author{Hou Seng Wong <housengw@berkeley.edu>}
 */
public class CActionGenerator {
    /**
     * For each action of the specified reactor instance, generate initialization code
     * for the offset and period fields. 
     * @param instance The reactor.
     * @param currentFederate The federate we are 
     */
    public static String generateInitializers(
        ReactorInstance instance, 
        FederateInstance currentFederate
    ) {
        List<String> code = new ArrayList<>();
        for (ActionInstance action : instance.actions) {
            if (currentFederate.contains(action.getDefinition()) &&
                !action.isShutdown()
            ) {
                var triggerStructName = CUtil.reactorRef(action.getParent()) + "->_lf__" + action.getName();
                var minDelay = action.getMinDelay();
                var minSpacing = action.getMinSpacing();
                var offsetInitializer = triggerStructName+".offset = " + GeneratorBase.timeInTargetLanguage(minDelay) + ";";
                var periodInitializer = triggerStructName+".period = " + (minSpacing != null ? 
                                                                         GeneratorBase.timeInTargetLanguage(minSpacing) :
                                                                         CGenerator.UNDEFINED_MIN_SPACING) + ";";
                code.addAll(List.of(
                    "// Initializing action "+action.getFullName(),
                    offsetInitializer,
                    periodInitializer
                ));
                
                var mode = action.getMode(false);
                if (mode != null) {
                    var modeParent = mode.getParent();
                    var modeRef = "&"+CUtil.reactorRef(modeParent)+"->_lf__modes["+modeParent.modes.indexOf(mode)+"];";
                    code.add(triggerStructName+".mode = "+modeRef+";");
                } else {
                    code.add(triggerStructName+".mode = NULL;");
                }
            }
        }
        return String.join("\n", code);
    }

    /**
     * Create a reference token initialized to the payload size.
     * This token is marked to not be freed so that the trigger_t struct
     * always has a reference token.
     * At the start of each time step, we need to initialize the is_present field
     * of each action's trigger object to false and free a previously
     * allocated token if appropriate. This code sets up the table that does that.
     * 
     * @param selfStruct The variable name of the self struct
     * @param actionName The action name
     * @param payloadSize The code that returns the size of the action's payload in C.
     */
    public static String generateTokenInitializer(
        String selfStruct,
        String actionName, 
        String payloadSize
    ) {
        return String.join("\n", 
            selfStruct+"->_lf__"+actionName+".token = _lf_create_token("+payloadSize+");",
            selfStruct+"->_lf__"+actionName+".status = absent;",
            "_lf_tokens_with_ref_count[_lf_tokens_with_ref_count_count].token = &"+selfStruct+"->_lf__"+actionName+".token;",
            "_lf_tokens_with_ref_count[_lf_tokens_with_ref_count_count].status = &"+selfStruct+"->_lf__"+actionName+".status;",
            "_lf_tokens_with_ref_count[_lf_tokens_with_ref_count_count++].reset_is_present = true;"
        );
    }

    public static void generateDeclarations(
        Reactor reactor, 
        ReactorDecl decl,
        FederateInstance currentFederate,
        CodeBuilder body,
        CodeBuilder constructorCode
    ) {
        for (Action action : ASTUtils.allActions(reactor)) {
            if (currentFederate.contains(action)) {
                var actionName = action.getName();
                body.pr(action, CGenerator.variableStructType(action, decl)+" _lf_"+actionName+";");
                // Initialize the trigger pointer in the action.
                constructorCode.pr(action, "self->_lf_"+actionName+".trigger = &self->_lf__"+actionName+";");
            }
        }
    }
}
