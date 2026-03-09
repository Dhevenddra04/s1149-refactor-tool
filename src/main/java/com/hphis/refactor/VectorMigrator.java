package com.hphis.refactor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.ast.comments.LineComment;

import java.util.*;

/**
 * Migrates Vector to ArrayList/List.
 * Handles type replacements, method migrations, and parameter swaps.
 * 
 * IMPORTANT: Collects all Vector variable names BEFORE type replacement
 * to ensure method migrations are correctly scoped.
 */
public class VectorMigrator {
    
    private final CompilationUnit cu;
    private final ImportManager importManager;
    private final ChangeReport report;
    private final String filePath;
    private int changeCount = 0;
    
    // Pre-collected Vector variable names (collected BEFORE type replacement)
    private final Set<String> knownVectorVariables = new HashSet<>();
    
    // Track which Enumeration variables came from Vector.elements()
    private final Set<String> vectorEnumerations = new HashSet<>();
    
    public VectorMigrator(CompilationUnit cu, ImportManager importManager,
                         ChangeReport report, String filePath) {
        this.cu = cu;
        this.importManager = importManager;
        this.report = report;
        this.filePath = filePath;
    }
    
    /**
     * Performs the Vector → ArrayList/List migration.
     * Order matters:
     * 1. Collect variable names (before types are changed)
     * 2. Track Enumeration sources (before types are changed)
     * 3. Replace types
     * 4. Replace object creation
     * 5. Migrate methods (uses pre-collected variable names)
     * 6. Migrate Enumerations
     * 7. Handle imports
     */
    public int migrate() {
        changeCount = 0;
        
        // FIRST PASS: Collect all Vector variable names BEFORE any type changes
        cu.accept(new VectorVariableCollector(), null);
        
        // SECOND PASS: Track Enumeration sources from Vector.elements()
        // Must happen before type replacement so we can verify scope is Vector
        cu.accept(new EnumerationTracker(), null);
        
        // Replace all type references
        cu.accept(new TypeReplacementVisitor(), null);
        
        // Replace object creation
        cu.accept(new ObjectCreationVisitor(), null);
        
        // Migrate method calls (uses pre-collected knownVectorVariables)
        cu.accept(new MethodMigrationVisitor(), null);
        
        // Migrate Enumeration to Iterator (only for Vector-sourced)
        cu.accept(new EnumerationMigrationVisitor(), null);
        
        // Handle imports
        if (changeCount > 0) {
            importManager.addImport("java.util.ArrayList");
            importManager.addImport("java.util.List");
            
            if (!vectorEnumerations.isEmpty()) {
                importManager.addImport("java.util.Iterator");
            }
            
            // Remove Vector import if no longer used (AST-based check)
            if (!isVectorStillUsedInAST()) {
                importManager.removeImportIfUnused("java.util.Vector");
                report.addChange(filePath, "Vector", "import_change", 0,
                               "import java.util.Vector", "removed", "No longer used");
            }
            
            // Remove Enumeration import only if ALL usages replaced
            if (!vectorEnumerations.isEmpty() && !isEnumerationStillUsedInAST()) {
                importManager.removeImportIfUnused("java.util.Enumeration");
                report.addChange(filePath, "Vector", "import_change", 0,
                               "import java.util.Enumeration", "removed", 
                               "Replaced with Iterator");
            }
        }
        
        return changeCount;
    }
    
    /**
     * FIRST PASS: Collects all variable names declared as Vector BEFORE type replacement.
     * This ensures method migration can correctly identify Vector variables even after
     * their types have been changed to List in the AST.
     */
    private class VectorVariableCollector extends VoidVisitorAdapter<Void> {
        
        @Override
        public void visit(VariableDeclarator var, Void arg) {
            super.visit(var, arg);
            if (isVectorType(var.getType())) {
                knownVectorVariables.add(var.getNameAsString());
            }
        }
        
        @Override
        public void visit(Parameter param, Void arg) {
            super.visit(param, arg);
            if (isVectorType(param.getType())) {
                knownVectorVariables.add(param.getNameAsString());
            }
        }
        
        @Override
        public void visit(FieldDeclaration field, Void arg) {
            super.visit(field, arg);
            if (isVectorType(field.getCommonType())) {
                for (VariableDeclarator var : field.getVariables()) {
                    knownVectorVariables.add(var.getNameAsString());
                }
            }
        }
    }
    
    /**
     * Tracks which Enumeration variables come from Vector.elements().
     * Only tracks if the scope of .elements() is a known Vector variable.
     */
    private class EnumerationTracker extends VoidVisitorAdapter<Void> {
        
        @Override
        public void visit(VariableDeclarator var, Void arg) {
            super.visit(var, arg);
            
            if (var.getType().toString().contains("Enumeration") && var.getInitializer().isPresent()) {
                Expression init = var.getInitializer().get();
                if (init.isMethodCallExpr()) {
                    MethodCallExpr methodCall = init.asMethodCallExpr();
                    if ("elements".equals(methodCall.getNameAsString())) {
                        // Verify the scope is a known Vector variable
                        String scopeName = getScopeName(methodCall);
                        if (scopeName != null && knownVectorVariables.contains(scopeName)) {
                            vectorEnumerations.add(var.getNameAsString());
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Replaces Vector type references with List.
     * IMPORTANT: Skips types inside ObjectCreationExpr (new Vector()) —
     * those are handled by ObjectCreationVisitor which correctly uses ArrayList.
     */
    private class TypeReplacementVisitor extends VoidVisitorAdapter<Void> {
        
        @Override
        public void visit(ClassOrInterfaceType type, Void arg) {
            super.visit(type, arg);
            
            if ("Vector".equals(type.getNameAsString())) {
                // Skip if this type is inside a "new" expression
                // ObjectCreationVisitor handles those (new Vector → new ArrayList)
                if (isInsideObjectCreation(type)) {
                    return;
                }
                
                int line = type.getBegin().map(pos -> pos.line).orElse(0);
                String original = type.toString();
                
                type.setName("List");
                
                report.addChange(filePath, "Vector", "type_replacement", line,
                               original, type.toString(), "Type declaration");
                changeCount++;
            }
        }
    }
    
    /**
     * Replaces new Vector() with new ArrayList().
     */
    private class ObjectCreationVisitor extends VoidVisitorAdapter<Void> {
        
        @Override
        public void visit(ObjectCreationExpr objCreation, Void arg) {
            super.visit(objCreation, arg);
            
            ClassOrInterfaceType type = objCreation.getType();
            if ("Vector".equals(type.getNameAsString())) {
                int line = objCreation.getBegin().map(pos -> pos.line).orElse(0);
                String original = objCreation.toString();
                
                type.setName("ArrayList");
                
                report.addChange(filePath, "Vector", "type_replacement", line,
                               original, objCreation.toString(), "Object creation");
                changeCount++;
            }
        }
    }
    
    /**
     * Migrates Vector-specific method calls to List equivalents.
     * CRITICAL: Only replaces methods when the scope is a known Vector variable.
     */
    private class MethodMigrationVisitor extends VoidVisitorAdapter<Void> {
        
        @Override
        public void visit(MethodCallExpr methodCall, Void arg) {
            super.visit(methodCall, arg);
            
            String methodName = methodCall.getNameAsString();
            
            // Must have a scope (object the method is called on)
            if (!methodCall.getScope().isPresent()) {
                return;
            }
            
            // CRITICAL: Only process if scope is a known Vector variable
            String scopeName = getScopeName(methodCall);
            if (scopeName == null || !knownVectorVariables.contains(scopeName)) {
                return;
            }
            
            int line = methodCall.getBegin().map(pos -> pos.line).orElse(0);
            String original = methodCall.toString();
            
            switch (methodName) {
                case "elementAt":
                    methodCall.setName("get");
                    report.addChange(filePath, "Vector", "method_migration", line,
                                   original, methodCall.toString(), ".elementAt() → .get()");
                    changeCount++;
                    break;
                    
                case "addElement":
                    methodCall.setName("add");
                    report.addChange(filePath, "Vector", "method_migration", line,
                                   original, methodCall.toString(), ".addElement() → .add()");
                    changeCount++;
                    break;
                    
                case "removeElement":
                    methodCall.setName("remove");
                    report.addChange(filePath, "Vector", "method_migration", line,
                                   original, methodCall.toString(), ".removeElement() → .remove()");
                    changeCount++;
                    break;
                    
                case "removeElementAt":
                    methodCall.setName("remove");
                    report.addChange(filePath, "Vector", "method_migration", line,
                                   original, methodCall.toString(), ".removeElementAt() → .remove()");
                    changeCount++;
                    break;
                    
                case "insertElementAt":
                    // CRITICAL: Parameter swap! insertElementAt(element, index) → add(index, element)
                    if (methodCall.getArguments().size() == 2) {
                        Expression element = methodCall.getArgument(0).clone();
                        Expression index = methodCall.getArgument(1).clone();
                        methodCall.setName("add");
                        methodCall.setArguments(new com.github.javaparser.ast.NodeList<>(index, element));
                        report.addChange(filePath, "Vector", "method_migration", line,
                                       original, methodCall.toString(), 
                                       ".insertElementAt(elem, idx) → .add(idx, elem) - PARAMETERS SWAPPED");
                        changeCount++;
                    }
                    break;
                    
                case "setElementAt":
                    // CRITICAL: Parameter swap! setElementAt(element, index) → set(index, element)
                    if (methodCall.getArguments().size() == 2) {
                        Expression element = methodCall.getArgument(0).clone();
                        Expression index = methodCall.getArgument(1).clone();
                        methodCall.setName("set");
                        methodCall.setArguments(new com.github.javaparser.ast.NodeList<>(index, element));
                        report.addChange(filePath, "Vector", "method_migration", line,
                                       original, methodCall.toString(),
                                       ".setElementAt(elem, idx) → .set(idx, elem) - PARAMETERS SWAPPED");
                        changeCount++;
                    }
                    break;
                    
                case "firstElement":
                    methodCall.setName("get");
                    methodCall.setArguments(new com.github.javaparser.ast.NodeList<>(
                        new IntegerLiteralExpr("0")));
                    report.addChange(filePath, "Vector", "method_migration", line,
                                   original, methodCall.toString(), ".firstElement() → .get(0)");
                    changeCount++;
                    break;
                    
                case "lastElement":
                    if (methodCall.getScope().isPresent()) {
                        Expression scope = methodCall.getScope().get();
                        methodCall.setName("get");
                        MethodCallExpr sizeCall = new MethodCallExpr(scope.clone(), "size");
                        BinaryExpr indexExpr = new BinaryExpr(sizeCall, 
                            new IntegerLiteralExpr("1"), BinaryExpr.Operator.MINUS);
                        methodCall.setArguments(new com.github.javaparser.ast.NodeList<>(indexExpr));
                        report.addChange(filePath, "Vector", "method_migration", line,
                                       original, methodCall.toString(), 
                                       ".lastElement() → .get(size() - 1)");
                        changeCount++;
                    }
                    break;
                    
                case "removeAllElements":
                    methodCall.setName("clear");
                    report.addChange(filePath, "Vector", "method_migration", line,
                                   original, methodCall.toString(), ".removeAllElements() → .clear()");
                    changeCount++;
                    break;
                    
                case "elements":
                    methodCall.setName("iterator");
                    report.addChange(filePath, "Vector", "method_migration", line,
                                   original, methodCall.toString(), ".elements() → .iterator()");
                    changeCount++;
                    break;
                    
                case "copyInto":
                    methodCall.setName("toArray");
                    report.addChange(filePath, "Vector", "method_migration", line,
                                   original, methodCall.toString(), ".copyInto() → .toArray()");
                    changeCount++;
                    break;
                    
                case "setSize":
                    // No direct equivalent - add TODO comment
                    if (methodCall.getParentNode().isPresent()) {
                        methodCall.getParentNode().get().setComment(
                            new LineComment(" TODO: Vector.setSize() has no direct ArrayList equivalent - review this code"));
                        report.addChange(filePath, "Vector", "method_migration", line,
                                       original, original + " // TODO", 
                                       ".setSize() has no direct equivalent - TODO added");
                        changeCount++;
                    }
                    break;
                    
                case "capacity":
                    // ArrayList doesn't expose capacity - add TODO comment
                    if (methodCall.getParentNode().isPresent()) {
                        methodCall.getParentNode().get().setComment(
                            new LineComment(" TODO: ArrayList doesn't expose capacity() - review this code"));
                        report.addChange(filePath, "Vector", "method_migration", line,
                                       original, original + " // TODO",
                                       ".capacity() not available on ArrayList - TODO added");
                        changeCount++;
                    }
                    break;
            }
        }
    }
    
    /**
     * Migrates Enumeration to Iterator (only for Vector-sourced Enumerations).
     */
    private class EnumerationMigrationVisitor extends VoidVisitorAdapter<Void> {
        
        @Override
        public void visit(ClassOrInterfaceType type, Void arg) {
            super.visit(type, arg);
            
            if ("Enumeration".equals(type.getNameAsString())) {
                // Check if this is a variable we tracked as Vector-sourced
                Node parent = type.getParentNode().orElse(null);
                if (parent instanceof VariableDeclarator) {
                    VariableDeclarator var = (VariableDeclarator) parent;
                    if (vectorEnumerations.contains(var.getNameAsString())) {
                        int line = type.getBegin().map(pos -> pos.line).orElse(0);
                        type.setName("Iterator");
                        report.addChange(filePath, "Vector", "type_replacement", line,
                                       "Enumeration", "Iterator", 
                                       "Vector-sourced Enumeration → Iterator");
                        changeCount++;
                    }
                }
            }
        }
        
        @Override
        public void visit(MethodCallExpr methodCall, Void arg) {
            super.visit(methodCall, arg);
            
            String methodName = methodCall.getNameAsString();
            
            if (methodCall.getScope().isPresent()) {
                String scopeName = methodCall.getScope().get().toString();
                
                if (vectorEnumerations.contains(scopeName)) {
                    int line = methodCall.getBegin().map(pos -> pos.line).orElse(0);
                    String original = methodCall.toString();
                    
                    if ("hasMoreElements".equals(methodName)) {
                        methodCall.setName("hasNext");
                        report.addChange(filePath, "Vector", "method_migration", line,
                                       original, methodCall.toString(), 
                                       ".hasMoreElements() → .hasNext()");
                        changeCount++;
                    } else if ("nextElement".equals(methodName)) {
                        methodCall.setName("next");
                        report.addChange(filePath, "Vector", "method_migration", line,
                                       original, methodCall.toString(),
                                       ".nextElement() → .next()");
                        changeCount++;
                    }
                }
            }
        }
    }
    
    // ===== Utility Methods =====
    
    /**
     * Checks if a type node is the direct type of an ObjectCreationExpr (new X()).
     * Only returns true if the IMMEDIATE parent is ObjectCreationExpr,
     * not if it's just somewhere in the same statement.
     * 
     * AST structure for "Vector<String> params = new Vector<String>()":
     *   VariableDeclarator
     *     type: ClassOrInterfaceType "Vector" ← this should NOT be skipped
     *     initializer: ObjectCreationExpr
     *       type: ClassOrInterfaceType "Vector" ← this SHOULD be skipped
     */
    private boolean isInsideObjectCreation(ClassOrInterfaceType type) {
        Node parent = type.getParentNode().orElse(null);
        if (parent instanceof ObjectCreationExpr) {
            return true;
        }
        return false;
    }
    
    /**
     * Checks if a type is Vector.
     */
    private boolean isVectorType(Type type) {
        if (type.isClassOrInterfaceType()) {
            ClassOrInterfaceType classType = type.asClassOrInterfaceType();
            return "Vector".equals(classType.getNameAsString());
        }
        return false;
    }
    
    /**
     * Extracts the variable name from a method call's scope.
     */
    private String getScopeName(MethodCallExpr methodCall) {
        if (!methodCall.getScope().isPresent()) {
            return null;
        }
        Expression scope = methodCall.getScope().get();
        if (scope.isNameExpr()) {
            return scope.asNameExpr().getNameAsString();
        } else if (scope.isFieldAccessExpr()) {
            return scope.asFieldAccessExpr().getNameAsString();
        } else if (scope.isThisExpr()) {
            // this.vectorField - need to check field name from context
            return null;
        }
        return null;
    }
    
    /**
     * AST-based check for remaining Vector usage.
     * Searches for ClassOrInterfaceType nodes named "Vector" instead of string matching.
     */
    private boolean isVectorStillUsedInAST() {
        List<ClassOrInterfaceType> types = cu.findAll(ClassOrInterfaceType.class);
        for (ClassOrInterfaceType type : types) {
            if ("Vector".equals(type.getNameAsString())) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * AST-based check for remaining Enumeration usage.
     * Searches for ClassOrInterfaceType nodes named "Enumeration".
     */
    private boolean isEnumerationStillUsedInAST() {
        List<ClassOrInterfaceType> types = cu.findAll(ClassOrInterfaceType.class);
        for (ClassOrInterfaceType type : types) {
            if ("Enumeration".equals(type.getNameAsString())) {
                return true;
            }
        }
        return false;
    }
}
