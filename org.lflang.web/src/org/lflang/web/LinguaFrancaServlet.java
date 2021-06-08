/*
 * generated by Xtext 2.18.0
 */
package org.lflang.web;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import org.eclipse.xtext.util.DisposableRegistry;
import org.eclipse.xtext.web.servlet.XtextServlet;

import com.google.inject.Injector;

/**
 * Deploy this class into a servlet container to enable DSL-specific services.
 */
@WebServlet(name = "XtextServices", urlPatterns = "/xtext-service/*")
class LinguaFrancaServlet extends XtextServlet {

	private DisposableRegistry disposableRegistry;


	@Override
	public void init() throws ServletException {
		super.init();
		Injector injector = new LinguaFrancaWebSetup().createInjectorAndDoEMFRegistration();
		disposableRegistry = injector.getInstance(DisposableRegistry.class);
	}


	@Override
	public void destroy() {
		if (disposableRegistry != null) {
			disposableRegistry.dispose();
			disposableRegistry = null;
		}
		super.destroy();
	}

}
