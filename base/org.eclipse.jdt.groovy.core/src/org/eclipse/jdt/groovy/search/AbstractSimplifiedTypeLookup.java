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
package org.eclipse.jdt.groovy.search;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ClosureListExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.GStringExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ForStatement;
import org.eclipse.jdt.groovy.search.TypeLookupResult.TypeConfidence;

/**
 * A simplified type lookup that targets the general case where a provider wants to add
 * initialization to a class and add new methods or fields to certain types of objects.
 */
public abstract class AbstractSimplifiedTypeLookup implements ITypeLookupExtension {

    private Boolean isStatic;
    private Expression currentExpression;

    /**
     * Gives an option for descendants to set confidence by their own
     */
    protected TypeConfidence checkConfidence(Expression node, TypeConfidence originalConfidence, ASTNode declaration, String extraDoc) {
        return (originalConfidence == null ? confidence() : originalConfidence);
    }

    /**
     * @return the confidence level of lookup results for this type lookup. Defaults to {@link TypeConfidence#LOOSELY_INFERRED}
     */
    protected TypeConfidence confidence() {
        return TypeConfidence.LOOSELY_INFERRED;
    }

    /**
     * @return the expression AST node that is currently being inferred.
     */
    protected Expression getCurrentExpression() {
        return currentExpression;
    }

    /**
     * @return the variable AST node if declared within current or enclosing scope
     */
    protected Variable getDeclaredVariable(String name, VariableScope scope) {
        Variable var = null;
        VariableScope.VariableInfo info = scope.lookupName(name);
        if (info != null) {
            org.codehaus.groovy.ast.VariableScope groovyScope = null;
            if (info.scopeNode instanceof MethodNode) {
                groovyScope = ((MethodNode) info.scopeNode).getVariableScope();
            } else if (info.scopeNode instanceof ForStatement) {
                groovyScope = ((ForStatement) info.scopeNode).getVariableScope();
            } else if (info.scopeNode instanceof BlockStatement) {
                groovyScope = ((BlockStatement) info.scopeNode).getVariableScope();
            } else if (info.scopeNode instanceof ClosureExpression) {
                groovyScope = ((ClosureExpression) info.scopeNode).getVariableScope();
            } else if (info.scopeNode instanceof ClosureListExpression) {
                groovyScope = ((ClosureListExpression) info.scopeNode).getVariableScope();
            }
            while (groovyScope != null && (var = groovyScope.getDeclaredVariable(name)) == null) {
                groovyScope = groovyScope.getParent();
            }
        }
        return var;
    }

    /**
     * @return true iff the current expression being inferred is a quoted string
     */
    protected boolean isQuotedString() {
        return (currentExpression instanceof GStringExpression || (currentExpression instanceof ConstantExpression &&
            (currentExpression.getEnd() < 1 || currentExpression.getLength() != currentExpression.getText().length())));
    }

    /**
     * @return true iff the current lookup is in a static scope
     */
    protected boolean isStatic() {
        return isStatic.booleanValue();
    }

    @Override
    public final TypeLookupResult lookupType(Expression expression, VariableScope scope, ClassNode objectExpressionType, boolean isStaticObjectExpression) {
        if (expression instanceof VariableExpression || (expression instanceof ConstantExpression &&
                (expression.getEnd() < 1 || expression.getLength() == expression.getText().length()))) {
            String name = expression.getText();

            Variable variable = getDeclaredVariable(name, scope);
            if (variable != null && !variable.isDynamicTyped()) {
                return null; // var type is explicitly declared
            }

            ClassNode declaringType;
            if (objectExpressionType != null) {
                declaringType = objectExpressionType;
                if (isStaticObjectExpression && objectExpressionType.isUsingGenerics() && objectExpressionType.equals(VariableScope.CLASS_CLASS_NODE)) {
                    declaringType = objectExpressionType.getGenericsTypes()[0].getType();
                }
            } else {
                declaringType = scope.getDelegateOrThis();
                if (declaringType == null) {
                    declaringType = scope.getEnclosingTypeDeclaration();
                    if (declaringType == null) {
                        // part of an import statment
                        declaringType = VariableScope.OBJECT_CLASS_NODE;
                    }
                }
            }

            try {
                // I would have liked to pass these values into lookupTypeAndDeclaration, but I can't break API here...
                currentExpression = expression; isStatic = isStaticObjectExpression;

                TypeAndDeclaration result = lookupTypeAndDeclaration(declaringType, name, scope);
                if (result != null) {
                    TypeConfidence confidence = checkConfidence(expression, result.confidence, result.declaration, result.extraDoc);
                    return new TypeLookupResult(result.type, result.declaringType == null ? declaringType : result.declaringType, result.declaration, confidence, scope, result.extraDoc);
                }
            } finally {
                currentExpression = null; isStatic = null;
            }
        }
        return null;
    }

    @Override
    public final TypeLookupResult lookupType(FieldNode node, VariableScope scope) {
        return null;
    }

    @Override
    public final TypeLookupResult lookupType(MethodNode node, VariableScope scope) {
        return null;
    }

    @Override
    public final TypeLookupResult lookupType(AnnotationNode node, VariableScope scope) {
        return null;
    }

    @Override
    public final TypeLookupResult lookupType(ImportNode node, VariableScope scope) {
        return null;
    }

    @Override
    public final TypeLookupResult lookupType(ClassNode node, VariableScope scope) {
        return null;
    }

    @Override
    public final TypeLookupResult lookupType(Parameter node, VariableScope scope) {
        return null;
    }

    /**
     * Clients should return a {@link TypeAndDeclaration} corresponding to an additional
     *
     * @return the type and declaration corresponding to the name in the given declaring type. The declaration may be null, but this
     *         should be avoided in that it prevents the use of navigation and of javadoc hovers
     */
    protected abstract TypeAndDeclaration lookupTypeAndDeclaration(ClassNode declaringType, String name, VariableScope scope);

    public static class TypeAndDeclaration {

        public TypeAndDeclaration(ClassNode type, ASTNode declaration) {
            this.type = type;
            this.declaration = declaration;
            this.declaringType = null;
            this.extraDoc = null;
            this.confidence = null;
        }

        public TypeAndDeclaration(ClassNode type, ASTNode declaration, ClassNode declaringType) {
            this.type = type;
            this.declaration = declaration;
            this.declaringType = declaringType;
            this.extraDoc = null;
            this.confidence = null;
        }

        public TypeAndDeclaration(ClassNode type, ASTNode declaration, ClassNode declaringType, String extraDoc) {
            this.type = type;
            this.declaration = declaration;
            this.declaringType = declaringType;
            this.extraDoc = extraDoc;
            this.confidence = null;
        }

        public TypeAndDeclaration(ClassNode type, ASTNode declaration, ClassNode declaringType, String extraDoc, TypeConfidence confidence) {
            this.type = type;
            this.declaration = declaration;
            this.declaringType = declaringType;
            this.extraDoc = extraDoc;
            this.confidence = confidence;
        }

        protected final ClassNode type;
        protected final ClassNode declaringType;
        protected final ASTNode declaration;
        protected final String extraDoc;
        protected final TypeConfidence confidence;
    }
}
