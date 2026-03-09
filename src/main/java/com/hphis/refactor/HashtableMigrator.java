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
 * EXCLUDES Properties (which extends Hashtable but must not be changed).
 * 
 * KEY DESIGN: ObjectCreationVisitor runs FIRST to change "new Hashtable()" → "new HashMap()".
 * Then TypeReplacementVisitor runs and changes all remaining "Hashtable" refs to "Map".
 */
public class HashtableMigrator {
    
    private final CompilationUnit cu;
    private final ImportManager importManager;
    private final ChangeReport report;
    private final String filePath;
    private int changeCount = 0;
    
    private final Set<String> knownHashtableVariables = new HashSet<>();
    private final Set<String> knownPropertiesVariables = new HashSet<>();
    private final Set<String> hashtableEnumerations = new HashSet<>();
    
    public HashtableMigrator(CompilationUnit cu, ImportManager importManager,
                            ChangeReport report, String filePath) {
        this.cu = cu;
        this.importManager = importManager;
        this.report = report;
        this.filePath = filePath;
    }
    
    public int migrate() {
        changeCount = 0;
        
        // 1. Collect Hashtable AND Properties variable names BEFORE any changes
        cu.accept(new VariableCollector(), null);
        
        // 2. Track Enumeration sources BEFORE any changes
        cu.accept(new EnumerationTracker(), null);
        
        // 3. Replace "new Hashtable()" → "new HashMap()" FIRST
        cu.accept(new ObjectCreationVisitor(), null);
        
        // 4. Replace all remaining "Hashtable" type refs → "Map"
        cu.accept(new TypeReplacementVisitor(), null);
        
        // 5. Migrate methods
        cu.accept(new MethodMigrationVisitor(), null);
        
        // 6. Migrate Enumerations
        cu.accept(new EnumerationMigrationVisitor(), null);
        
        // 7. Imports
        if (changeCount > 0) {
            importManager.addImport("java.util.HashMap");
            importManager.addImport("java.util.Map");
            if (!hashtableEnumerations.isEmpty()) {
                importManager.addImport("java.util.Iterator");
            }
            if (!isHashtableStillUsedInAST()) {
                importManager.removeImportIfUnused("java.util.Hashtable");
                report.addChange(filePath, "Hashtable", "import_change", 0,
                               "import java.util.Hashtable", "removed", "No longer used");
            }
            if (!hashtableEnumerations.isEmpty() && !isEnumerationStillUsedInAST()) {
                importManager.removeImportIfUnused("java.util.Enumeration");
                report.addChange(filePath, "Hashtable", "import_change", 0,
                               "import java.util.Enumeration", "removed", "Replaced with Iterator");
            }
        }
        
        return changeCount;
    }
    
    // === Pass 1: Collect variable names ===
    
    private class VariableCollector extends VoidVisitorAdapter<Void> {
        @Override
        public void visit(VariableDeclarator var, Void arg) {
            super.visit(var, arg);
            if (isHashtableType(var.getType())) knownHashtableVariables.add(var.getNameAsString());
            if (isPropertiesType(var.getType())) knownPropertiesVariables.add(var.getNameAsString());
        }
        @Override
        public void visit(Parameter param, Void arg) {
            super.visit(param, arg);
            if (isHashtableType(param.getType())) knownHashtableVariables.add(param.getNameAsString());
            if (isPropertiesType(param.getType())) knownPropertiesVariables.add(param.getNameAsString());
        }
        @Override
        public void visit(FieldDeclaration field, Void arg) {
            super.visit(field, arg);
            Type type = field.getCommonType();
            for (VariableDeclarator var : field.getVariables()) {
                if (isHashtableType(type)) knownHashtableVariables.add(var.getNameAsString());
                if (isPropertiesType(type)) knownPropertiesVariables.add(var.getNameAsString());
            }
        }
    }
    
    // === Pass 2: Track Enumeration sources ===
    
    private class EnumerationTracker extends VoidVisitorAdapter<Void> {
        @Override
        public void visit(VariableDeclarator var, Void arg) {
            super.visit(var, arg);
            if (var.getType().toString().contains("Enumeration") && var.getInitializer().isPresent()) {
                Expression init = var.getInitializer().get();
                if (init.isMethodCallExpr()) {
                    MethodCallExpr mc = init.asMethodCallExpr();
                    String methodName = mc.getNameAsString();
                    if ("elements".equals(methodName) || "keys".equals(methodName)) {
                        String scope = getScopeName(mc);
                        if (scope != null && knownHashtableVariables.contains(scope)
                                && !knownPropertiesVariables.contains(scope)) {
                            hashtableEnumerations.add(var.getNameAsString());
                        }
                    }
                }
            }
        }
    }
    
    // === Pass 3: Object creation (FIRST) ===
    
    private class ObjectCreationVisitor extends VoidVisitorAdapter<Void> {
        @Override
        public void visit(ObjectCreationExpr objCreation, Void arg) {
            super.visit(objCreation, arg);
            ClassOrInterfaceType type = objCreation.getType();
            if ("Hashtable".equals(type.getNameAsString())) {
                if (!isAssignedToProperties(objCreation)) {
                    int line = objCreation.getBegin().map(pos -> pos.line).orElse(0);
                    String original = objCreation.toString();
                    type.setName("HashMap");
                    report.addChange(filePath, "Hashtable", "type_replacement", line,
                                   original, objCreation.toString(), "new Hashtable → new HashMap");
                    changeCount++;
                }
            }
        }
    }
    
    // === Pass 4: Type replacement (AFTER object creation) ===
    
    private class TypeReplacementVisitor extends VoidVisitorAdapter<Void> {
        @Override
        public void visit(ClassOrInterfaceType type, Void arg) {
            super.visit(type, arg);
            // By now, all "Hashtable" inside "new" expressions are already "HashMap"
            if ("Hashtable".equals(type.getNameAsString())) {
                if (!isInPropertiesContext(type)) {
                    int line = type.getBegin().map(pos -> pos.line).orElse(0);
                    String original = type.toString();
                    type.setName("Map");
                    report.addChange(filePath, "Hashtable", "type_replacement", line,
                                   original, type.toString(), "Hashtable → Map");
                    changeCount++;
                }
            }
        }
    }
    
    // === Pass 5: Method migration ===
    
    private class MethodMigrationVisitor extends VoidVisitorAdapter<Void> {
        @Override
        public void visit(MethodCallExpr methodCall, Void arg) {
            super.visit(methodCall, arg);
            
            if (!methodCall.getScope().isPresent()) return;
            String scopeName = getScopeName(methodCall);
            if (scopeName == null) return;
            if (!knownHashtableVariables.contains(scopeName)) return;
            if (knownPropertiesVariables.contains(scopeName)) return;
            
            String methodName = methodCall.getNameAsString();
            int line = methodCall.getBegin().map(pos -> pos.line).orElse(0);
            String original = methodCall.toString();
            Expression scope = methodCall.getScope().get();
            
            switch (methodName) {
                case "contains":
                    methodCall.setName("containsValue");
                    report.addChange(filePath, "Hashtable", "method_migration", line,
                                   original, methodCall.toString(), ".contains() → .containsValue()");
                    changeCount++;
                    break;
                case "elements":
                    MethodCallExpr valuesCall = new MethodCallExpr(scope.clone(), "values");
                    MethodCallExpr iterCall = new MethodCallExpr(valuesCall, "iterator");
                    methodCall.replace(iterCall);
                    report.addChange(filePath, "Hashtable", "method_migration", line,
                                   original, iterCall.toString(), ".elements() → .values().iterator()");
                    changeCount++;
                    break;
                case "keys":
                    MethodCallExpr keySetCall = new MethodCallExpr(scope.clone(), "keySet");
                    MethodCallExpr iterCall2 = new MethodCallExpr(keySetCall, "iterator");
                    methodCall.replace(iterCall2);
                    report.addChange(filePath, "Hashtable", "method_migration", line,
                                   original, iterCall2.toString(), ".keys() → .keySet().iterator()");
                    changeCount++;
                    break;
            }
        }
    }
    
    // === Pass 6: Enumeration migration ===
    
    private class EnumerationMigrationVisitor extends VoidVisitorAdapter<Void> {
        @Override
        public void visit(ClassOrInterfaceType type, Void arg) {
            super.visit(type, arg);
            if ("Enumeration".equals(type.getNameAsString())) {
                Node parent = type.getParentNode().orElse(null);
                if (parent instanceof VariableDeclarator) {
                    String varName = ((VariableDeclarator) parent).getNameAsString();
                    if (hashtableEnumerations.contains(varName)) {
                        int line = type.getBegin().map(pos -> pos.line).orElse(0);
                        type.setName("Iterator");
                        report.addChange(filePath, "Hashtable", "type_replacement", line, "Enumeration", "Iterator", "Hashtable-sourced Enumeration → Iterator");
                        changeCount++;
                    }
                }
            }
        }
        @Override
        public void visit(MethodCallExpr methodCall, Void arg) {
            super.visit(methodCall, arg);
            if (methodCall.getScope().isPresent()) {
                String scopeName = methodCall.getScope().get().toString();
                if (hashtableEnumerations.contains(scopeName)) {
                    int line = methodCall.getBegin().map(pos -> pos.line).orElse(0);
                    String original = methodCall.toString();
                    String methodName = methodCall.getNameAsString();
                    if ("hasMoreElements".equals(methodName)) {
                        methodCall.setName("hasNext");
                        report.addChange(filePath, "Hashtable", "method_migration", line, original, methodCall.toString(), ".hasMoreElements() → .hasNext()");
                        changeCount++;
                    } else if ("nextElement".equals(methodName)) {
                        methodCall.setName("next");
                        report.addChange(filePath, "Hashtable", "method_migration", line, original, methodCall.toString(), ".nextElement() → .next()");
                        changeCount++;
                    }
                }
            }
        }
    }
    
    // === Utility ===
    
    private boolean isHashtableType(Type type) {
        if (type.isClassOrInterfaceType()) return "Hashtable".equals(type.asClassOrInterfaceType().getNameAsString());
        return false;
    }
    
    private boolean isPropertiesType(Type type) {
        if (type.isClassOrInterfaceType()) return "Properties".equals(type.asClassOrInterfaceType().getNameAsString());
        return false;
    }
    
    private boolean isInPropertiesContext(ClassOrInterfaceType type) {
        Node parent = type.getParentNode().orElse(null);
        while (parent != null) {
            if (parent instanceof VariableDeclarator) {
                return knownPropertiesVariables.contains(((VariableDeclarator) parent).getNameAsString());
            }
            if (parent instanceof FieldDeclaration || parent instanceof MethodDeclaration || parent instanceof Parameter) break;
            parent = parent.getParentNode().orElse(null);
        }
        return false;
    }
    
    private boolean isAssignedToProperties(ObjectCreationExpr objCreation) {
        Node parent = objCreation.getParentNode().orElse(null);
        if (parent instanceof VariableDeclarator) {
            return knownPropertiesVariables.contains(((VariableDeclarator) parent).getNameAsString());
        }
        if (parent instanceof AssignExpr) {
            return knownPropertiesVariables.contains(((AssignExpr) parent).getTarget().toString());
        }
        return false;
    }
    
    private String getScopeName(MethodCallExpr methodCall) {
        if (!methodCall.getScope().isPresent()) return null;
        Expression scope = methodCall.getScope().get();
        if (scope.isNameExpr()) return scope.asNameExpr().getNameAsString();
        if (scope.isFieldAccessExpr()) return scope.asFieldAccessExpr().getNameAsString();
        return null;
    }
    
    private boolean isHashtableStillUsedInAST() {
        for (ClassOrInterfaceType t : cu.findAll(ClassOrInterfaceType.class)) {
            if ("Hashtable".equals(t.getNameAsString())) return true;
        }
        return false;
    }
    
    private boolean isEnumerationStillUsedInAST() {
        for (ClassOrInterfaceType t : cu.findAll(ClassOrInterfaceType.class)) {
            if ("Enumeration".equals(t.getNameAsString())) return true;
        }
        return false;
    }
}
