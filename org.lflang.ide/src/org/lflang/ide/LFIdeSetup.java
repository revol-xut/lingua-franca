/*
 * generated by Xtext 2.23.0
 */
package org.lflang.ide;

import org.lflang.LFRuntimeModule;
import org.lflang.LFStandaloneSetup;

/**
 * Initialization support for running Xtext languages as language servers.
 */
public class LFIdeSetup extends LFStandaloneSetup {

	public LFIdeSetup() {
		super(new LFRuntimeModule(), new LFIdeModule());
	}

	public static void doSetup() {
		new LFIdeSetup().createInjectorAndDoEMFRegistration();
	}

}
