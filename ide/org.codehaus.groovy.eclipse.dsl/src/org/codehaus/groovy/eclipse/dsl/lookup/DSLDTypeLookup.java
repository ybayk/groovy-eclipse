/*
 * Copyright 2009-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.codehaus.groovy.eclipse.dsl.lookup;

import java.util.List;
import java.util.Set;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.eclipse.dsl.DSLDStore;
import org.codehaus.groovy.eclipse.dsl.DSLDStoreManager;
import org.codehaus.groovy.eclipse.dsl.DSLPreferences;
import org.codehaus.groovy.eclipse.dsl.GroovyDSLCoreActivator;
import org.codehaus.groovy.eclipse.dsl.contributions.IContributionElement;
import org.codehaus.groovy.eclipse.dsl.pointcuts.GroovyDSLDContext;
import org.codehaus.jdt.groovy.internal.compiler.ast.JDTResolver;
import org.codehaus.jdt.groovy.model.GroovyCompilationUnit;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.groovy.search.AbstractSimplifiedTypeLookup;
import org.eclipse.jdt.groovy.search.ITypeLookup;
import org.eclipse.jdt.groovy.search.ITypeResolver;
import org.eclipse.jdt.groovy.search.TypeLookupResult.TypeConfidence;
import org.eclipse.jdt.groovy.search.VariableScope;

/**
 * Uses the current set of DSLDs for this project to look up types.
 */
public class DSLDTypeLookup extends AbstractSimplifiedTypeLookup implements ITypeLookup, ITypeResolver {

    DSLDStoreManager contextStoreManager = GroovyDSLCoreActivator.getDefault().getContextStoreManager();

    private DSLDStore store;
    private ModuleNode module;
    private JDTResolver resolver;
    private GroovyDSLDContext context;
    private Set<String> disabledScriptsAsSet;

    @Override
    public void setResolverInformation(ModuleNode module, JDTResolver resolver) {
        this.module = module;
        this.resolver = resolver;
    }

    @Override
    public void initialize(GroovyCompilationUnit unit, VariableScope topLevelScope) {
        if (!GroovyDSLCoreActivator.getDefault().isDSLDDisabled()) {
            // run referesh dependencies synchronously if DSLD store doesn't exist yet
            contextStoreManager.ensureInitialized(unit.getJavaProject().getProject(), true);
        }
        disabledScriptsAsSet = DSLPreferences.getDisabledScriptsAsSet();
        try {
            context = new GroovyDSLDContext(unit, module, resolver);
            context.setCurrentScope(topLevelScope);
        } catch (CoreException e) {
            GroovyDSLCoreActivator.logException(e);
        }
        store = contextStoreManager.getDSLDStore(unit.getJavaProject());
        store = store.createSubStore(context);
    }

    @Override
    protected TypeAndDeclaration lookupTypeAndDeclaration(ClassNode declaringType, String name, VariableScope scope) {
        if (!(scope.getCurrentNode() instanceof ConstantExpression) || !(scope.getEnclosingNode() instanceof MapEntryExpression) ||
            scope.getCurrentNode() != ((MapEntryExpression) scope.getEnclosingNode()).getKeyExpression() || inPointcutExpression(scope)) {

            context.setStatic(isStatic());
            context.setCurrentScope(scope);
            context.setTargetType(declaringType);
            List<IContributionElement> contributions = store.findContributions(context, disabledScriptsAsSet);

            declaringType = context.getCurrentType(); // may have changed via a setDelegateType
            for (IContributionElement contribution : contributions) {
                TypeAndDeclaration td = contribution.lookupType(name, declaringType, context.getResolverCache());
                if (td != null) {
                    return td;
                }
            }
        }
        return null;
    }

    /**
     * setDelegateType must be called even for empty block statements
     */
    @Override
    public void lookupInBlock(BlockStatement node, VariableScope scope) {
        context.setPrimaryNode(true);
        context.setCurrentScope(scope);
        context.setStatic(scope.isStatic());
        ClassNode delegateOrThis = scope.getDelegateOrThis();
        if (delegateOrThis != null) {
            context.setTargetType(delegateOrThis);
            store.findContributions(context, disabledScriptsAsSet);
        }
        // no need to return anything; setDelegateType is called and evaluated implicitly
    }

    /*
     * Checks explicitly if the confidence decision should be made later.
     */
    @Override
    protected TypeConfidence checkConfidence(Expression node, TypeConfidence confidence, ASTNode declaration, String extraDoc) {
        if (confidence == null) confidence = confidence();
        if (declaration instanceof MethodNode && extraDoc != null && extraDoc.contains("Provided by Grails ORM DSL")) {
            // give a chance for TypeLookups called later
            confidence = TypeConfidence.LOOSELY_INFERRED;
        }
        return confidence;
    }

    @Override
    protected TypeConfidence confidence() {
        return TypeConfidence.INFERRED;
    }

    private boolean inPointcutExpression(VariableScope scope) {
        return (context.simpleFileName != null && context.simpleFileName.endsWith(".dsld") && (scope.getEnclosingClosure() == null ||
            scope.getAllEnclosingMethodCallExpressions().stream().noneMatch(cat -> cat.call.getMethodAsString().matches("accept|contribute"))));
    }
}
