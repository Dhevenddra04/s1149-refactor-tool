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

import java.util.*;

/**
 * Migrates Hashtable to HashMap/Map.
 * Handles type replacements, method migrations, and Enumeration conversion.
 * EXCLUDES Properties (which extends Hashtable but must not be changed).
 * 
 * IMPORTANT: Collects all Hashtable and Properties variable names BEFORE type 
 * replacement to ensure method migrations are correctly scoped.
 */
public class HashtableMigrator {
    
    private final CompilationUnit cu;
    private final ImportManager importManager;
    private final ChangeReport report;
    private final String filePath;
    private int changeCount = 0;
    
    // Pre-collected variable names (collected BEFORE type replacement)
    private final Set<String> knownHashtableVariables = new HashSet<>();
    private final Set<String> knownPropertiesVariables = new HashSet<>();
    
    // Track which Enumeration variables came from Hashtable.elements() or .keys()
    private final Set<String> hashtableEnumerations = new HashSet<>();
    
    public HashtableMigrator(CompilationUnit cu, ImportManager importManager,
                            ChangeReport report, String filePath) {
        this.cu = cu;
        this.importManager = importManager;
        this.report = report;
        this.filePath = filePath;
    }
    
    /**
     * Performs the Hashtable → HashMap/Map migration.
     * Order matters:
     * 1. Collect Hashtable AND Properties variable names (before types are changed)
     * 2. Track Enumeration sources (before types are changed)
     * 3. Replace types
     * 4. Replace object creation
     * 5. Migrate methods (uses pre-collected variable names)
     * 6. Migrate Enumerations
     * 7. Handle imports
     */
    public int migrate() {
        changeCount = 0;
        
        // FIRST PASS: Collect all Hashtable and Properties variable names
        cu.accept(new VariableCollector(), null);
        
        // SECOND PASS: Track Enumeration sources from Hashtable.elements()/.keys()
        cu.accept(new EnumerationTracker(), null);
        
        // Replace all type references
        cu.accept(new TypeReplacementVisitor(), null);
        
        // Replace object creation
        cu.accept(new ObjectCreationVisitor(), null);
        
        // Migrate method calls (uses pre-collected knownHashtableVariables)
        cu.accept(new MethodMigrationVisitor(), null);
        
        // Migrate Enumeration to Iterator (only for Hashtable-sourced)
        cu.accept(new EnumerationMigrationVisitor(), null);
        
        // Handle imports
        if (changeCount > 0) {
            importManager.addImport("java.util.HashMap");
            importManager.addImport("java.util.Map");
            
            if (!hashtableEnumerations.isEmpty()) {
                importManager.addImport("java.util.Iterator");
            }
            
            // Remove Hashtable import if no longer used (AST-based check)
            if (!isHashtableStillUsedInAST()) {
                importManager.removeImportIfUnused("java.util.Hashtable");
                report.addChange(filePath, "Hashtable", "import_change", 0,
                               "import java.util.Hashtable", "removed", "No longer used");
            }
            
            // Remove Enumeration import only if ALL usages replaced
            if (!hashtableEnumerations.isEmpty() && !isEnumerationStillUsedInAST()) {
                importManager.removeImportIfUnused("java.util.Enumeration");
                report.addChange(filePath, "Hashtable", "import_change", 0,
                               "import java.util.Enumeration", "removed",
                               "Replaced with Iterator");
            }
        }
        
        return changeCount;
    }
    
    /**
     * FIRST PASS: Collects all Hashtable AND Properties variable names.
     * - Hashtable variables will be migrated
     * - Properties variables will be EXCLUDED from migration
     */
    private class VariableCollector extends VoidVisitorAdapter<Void> {
        
        @Override
        public void visit(VariableDeclarator var, Void arg) {
            super.visit(var, arg);
            Type type = var.getType();
            if (isHashtableType(type)) {
                knownHashtableVariables.add(var.getNameAsString());
            }
            if (isPropertiesType(type)) {
                knownPropertiesVariables.add(var.getNameAsString());
            }
        }
        
        @Override
        public void visit(Parameter param, Void arg) {
            super.visit(param, arg);
            Type type = param.getType();
            if (isHashtableType(type)) {
                knownHashtableVariables.add(param.getNameAsString());
            }
            if (isPropertiesType(type)) {
                knownPropertiesVariables.add(param.getNameAsString());
            }
        }
        
        @Override
        public void visit(FieldDeclaration field, Void arg) {
            super.visit(field, arg);
            Type type = field.getCommonType();
            for (VariableDeclarator var : field.getVariables()) {
                if (isHashtableType(type)) {
                    knownHashtableVariables.add(var.getNameAsString());
                }
                if (isPropertiesType(type)) {
                    knownPropertiesVariables.add(var.getNameAsString());
                }
            }
        }
    }
    
    /**
     * Tracks which Enumeration variables come from Hashtable.elements() or .keys().
     * Only tracks if the scope of the call is a known Hashtable variable.
     */
    private class EnumerationTracker extends VoidVisitorAdapter<Void> {
        
        @Override
        public void visit(VariableDeclarator var, Void arg) {
            super.visit(var, arg);
            
            if (var.getType().toString().contains("Enumeration") && var.getInitializer().isPresent()) {
                Expression init = var.getInitializer().get();
                if (init.isMethodCallExpr()) {
                    MethodCallExpr methodCall = init.asMethodCallExpr();
                    String methodName = methodCall.getNameAsString();
                    if ("elements".equals(methodName) || "keys".equals(methodName)) {
                        // Verify the scope is a known Hashtable variable (not Properties)
                        String scopeName = getScopeName(methodCall);
                        if (scopeName != null && knownHashtableVariables.contains(scopeName)
                                && !knownPropertiesVariables.contains(scopeName)) {
                            hashtableEnumerations.add(var.getNameAsString());
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Replaces Hashtable type references with Map.
     * EXCLUDES Properties.
     * IMPORTANT: Skips types inside ObjectCreationExpr (new Hashtable()) —
     * those are handled by ObjectCreationVisitor which correctly uses HashMap.
     */
    private class TypeReplacementVisitor extends VoidVisitorAdapter<Void> {
        
        @Override
        public void visit(ClassOrInterfaceType type, Void arg) {
            super.visit(type, arg);
            
            if ("Hashtable".equals(type.getNameAsString())) {
                // Skip if this type is inside a "new" expression
                // ObjectCreationVisitor handles those (new Hashtable → new HashMap)
                if (isInsideObjectCreation(type)) {
                    return;
                }
                
                // Check if this is in a Properties context using AST
                if (!isInPropertiesContext(type)) {
                    int line = type.getBegin().map(pos -> pos.line).orElse(0);
                    String original = type.toString();
                    
                    type.setName("Map");
                    
                    report.addChange(filePath, "Hashtable", "type_replacement", line,
                                   original, type.toString(), "Type declaration");
                    changeCount++;
                }
            }
        }
    }
    
    /**
     * Replaces new Hashtable() with new HashMap().
     */
    private class ObjectCreationVisitor extends VoidVisitorAdapter<Void> {
        
        @Override
        public void visit(ObjectCreationExpr objCreation, Void arg) {
            super.visit(objCreation, arg);
            
            ClassOrInterfaceType type = objCreation.getType();
            if ("Hashtable".equals(type.getNameAsString())) {
                // Check if this Hashtable is being assigned to a Properties variable
                if (!isAssignedToProperties(objCreation)) {
                    int line = objCreation.getBegin().map(pos -> pos.line).orElse(0);
                    String original = objCreation.toString();
                    
                    type.setName("HashMap");
                    
                    report.addChange(filePath, "Hashtable", "type_replacement", line,
                                   original, objCreation.toString(), "Object creation");
                    changeCount++;
                }
            }
        }
    }
    
    /**
     * Migrates Hashtable-specific method calls to Map equivalents.
     * CRITICAL: Only replaces methods when the scope is a known Hashtable variable
     * and NOT a Properties variable.
     */
    private class MethodMigrationVisitor extends VoidVisitorAdapter<Void> {
        
        @Override
        public void visit(MethodCallExpr methodCall, Void arg) {
            super.visit(methodCall, arg);
            
            String methodName = methodCall.getNameAsString();
            
            // Must have a scope
            if (!methodCall.getScope().isPresent()) {
                return;
            }
            
            // Get the scope variable name
            String scopeName = getScopeName(methodCall);
            if (scopeName == null) {
                return;
            }
            
            // CRITICAL: Only process if scope is a known Hashtable variable
            // AND NOT a Properties variable
            if (!knownHashtableVariables.contains(scopeName)) {
                return;
            }
            if (knownPropertiesVariables.contains(scopeName)) {
                return;
            }
            
            int line = methodCall.getBegin().map(pos -> pos.line).orElse(0);
            String original = methodCall.toString();
            Expression scope = methodCall.getScope().get();
            
            switch (methodName) {
                case "contains":
                    // Hashtable.contains(value) checks values, not keys
                    // Map equivalent is containsValue(value)
                    methodCall.setName("containsValue");
                    report.addChange(filePath, "Hashtable", "method_migration", line,
                                   original, methodCall.toString(), 
                                   ".contains() → .containsValue()");
                    changeCount++;
                    break;
                    
                case "elements":
                    // Returns Enumeration over values
                    // Map equivalent: values().iterator()
                    MethodCallExpr valuesCall = new MethodCallExpr(scope.clone(), "values");
                    MethodCallExpr iteratorCall = new MethodCallExpr(valuesCall, "iterator");
                    methodCall.replace(iteratorCall);
                    report.addChange(filePath, "Hashtable", "method_migration", line,
                                   original, iteratorCall.toString(),
                                   ".elements() → .values().iterator()");
                    changeCount++;
                    break;
                    
                case "keys":
                    // Returns Enumeration over keys
                    // Map equivalent: keySet().iterator()
                    MethodCallExpr keySetCall = new MethodCallExpr(scope.clone(), "keySet");
                    MethodCallExpr iteratorCall2 = new MethodCallExpr(keySetCall, "iterator");
                    methodCall.replace(iteratorCall2);
                    report.addChange(filePath, "Hashtable", "method_migration", line,
                                   original, iteratorCall2.toString(),
                                   ".keys() → .keySet().iterator()");
                    changeCount++;
                    break;
                    
                // These methods are identical on Hashtable and Map - no change needed:
                // put, get, remove, containsKey, containsValue, size, isEmpty, clear,
                // keySet, values, entrySet, putAll
            }
        }
    }
    
    /**
     * Migrates Enumeration to Iterator (only for Hashtable-sourced Enumerations).
     */
    private class EnumerationMigrationVisitor extends VoidVisitorAdapter<Void> {
        
        @Override
        public void visit(ClassOrInterfaceType type, Void arg) {
            super.visit(type, arg);
            
            if ("Enumeration".equals(type.getNameAsString())) {
                Node parent = type.getParentNode().orElse(null);
                if (parent instanceof VariableDeclarator) {
                    VariableDeclarator var = (VariableDeclarator) parent;
                    if (hashtableEnumerations.contains(var.getNameAsString())) {
                        int line = type.getBegin().map(pos -> pos.line).orElse(0);
                        type.setName("Iterator");
                        report.addChange(filePath, "Hashtable", "type_replacement", line,
                                       "Enumeration", "Iterator",
                                       "Hashtable-sourced Enumeration → Iterator");
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
                
                if (hashtableEnumerations.contains(scopeName)) {
                    int line = methodCall.getBegin().map(pos -> pos.line).orElse(0);
                    String original = methodCall.toString();
                    
                    if ("hasMoreElements".equals(methodName)) {
                        methodCall.setName("hasNext");
                        report.addChange(filePath, "Hashtable", "method_migration", line,
                                       original, methodCall.toString(),
                                       ".hasMoreElements() → .hasNext()");
                        changeCount++;
                    } else if ("nextElement".equals(methodName)) {
                        methodCall.setName("next");
                        report.addChange(filePath, "Hashtable", "method_migration", line,
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
     * Checks if a type node is inside an ObjectCreationExpr (new X()).
     * Used to prevent TypeReplacementVisitor from changing "new Hashtable" to "new Map"
     * (which is invalid since Map is an interface).
     */
    private boolean isInsideObjectCreation(ClassOrInterfaceType type) {
        Node parent = type.getParentNode().orElse(null);
        while (parent != null) {
            if (parent instanceof ObjectCreationExpr) {
                return true;
            }
            if (parent instanceof com.github.javaparser.ast.stmt.Statement) {
                break;
            }
            parent = parent.getParentNode().orElse(null);
        }
        return false;
    }
    
    private boolean isHashtableType(Type type) {
        if (type.isClassOrInterfaceType()) {
            ClassOrInterfaceType classType = type.asClassOrInterfaceType();
            return "Hashtable".equals(classType.getNameAsString());
        }
        return false;
    }
    
    private boolean isPropertiesType(Type type) {
        if (type.isClassOrInterfaceType()) {
            ClassOrInterfaceType classType = type.asClassOrInterfaceType();
            return "Properties".equals(classType.getNameAsString());
        }
        return false;
    }
    
    /**
     * Checks if a Hashtable type reference is within a Properties context.
     * Uses AST parent traversal instead of string matching.
     */
    private boolean isInPropertiesContext(ClassOrInterfaceType type) {
        // Check if the parent variable/field/parameter is known as Properties
        Node parent = type.getParentNode().orElse(null);
        
        // Walk up to find the variable declaration
        while (parent != null) {
            if (parent instanceof VariableDeclarator) {
                String varName = ((VariableDeclarator) parent).getNameAsString();
                return knownPropertiesVariables.contains(varName);
            }
            if (parent instanceof FieldDeclaration || parent instanceof MethodDeclaration
                    || parent instanceof Parameter) {
                break;
            }
            parent = parent.getParentNode().orElse(null);
        }
        return false;
    }
    
    /**
     * Checks if a new Hashtable() is being assigned to a Properties variable.
     */
    private boolean isAssignedToProperties(ObjectCreationExpr objCreation) {
        Node parent = objCreation.getParentNode().orElse(null);
        if (parent instanceof VariableDeclarator) {
            String varName = ((VariableDeclarator) parent).getNameAsString();
            return knownPropertiesVariables.contains(varName);
        }
        if (parent instanceof AssignExpr) {
            AssignExpr assign = (AssignExpr) parent;
            String targetName = assign.getTarget().toString();
            return knownPropertiesVariables.contains(targetName);
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
            return null;
        }
        return null;
    }
    
    /**
     * AST-based check for remaining Hashtable usage.
     * Searches for ClassOrInterfaceType nodes named "Hashtable".
     */
    private boolean isHashtableStillUsedInAST() {
        List<ClassOrInterfaceType> types = cu.findAll(ClassOrInterfaceType.class);
        for (ClassOrInterfaceType type : types) {
            if ("Hashtable".equals(type.getNameAsString())) {
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
