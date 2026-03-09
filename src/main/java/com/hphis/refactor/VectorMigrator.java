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
 * 
 * KEY DESIGN: ObjectCreationVisitor runs FIRST to change "new Vector()" → "new ArrayList()".
 * Then TypeReplacementVisitor runs and changes all remaining "Vector" refs to "List".
 * Since ObjectCreation already changed the "new" types, TypeReplacement won't find
 * "Vector" inside new expressions — avoiding the "new List()" bug.
 */
public class VectorMigrator {
    
    private final CompilationUnit cu;
    private final ImportManager importManager;
    private final ChangeReport report;
    private final String filePath;
    private int changeCount = 0;
    
    private final Set<String> knownVectorVariables = new HashSet<>();
    private final Set<String> vectorEnumerations = new HashSet<>();
    
    public VectorMigrator(CompilationUnit cu, ImportManager importManager,
                         ChangeReport report, String filePath) {
        this.cu = cu;
        this.importManager = importManager;
        this.report = report;
        this.filePath = filePath;
    }
    
    public int migrate() {
        changeCount = 0;
        
        // 1. Collect Vector variable names BEFORE any changes
        cu.accept(new VectorVariableCollector(), null);
        
        // 2. Track Enumeration sources BEFORE any changes
        cu.accept(new EnumerationTracker(), null);
        
        // 3. Replace "new Vector()" → "new ArrayList()" FIRST
        cu.accept(new ObjectCreationVisitor(), null);
        
        // 4. Replace all remaining "Vector" type refs → "List"
        //    (won't touch new expressions since they're already "ArrayList")
        cu.accept(new TypeReplacementVisitor(), null);
        
        // 5. Migrate methods
        cu.accept(new MethodMigrationVisitor(), null);
        
        // 6. Migrate Enumerations
        cu.accept(new EnumerationMigrationVisitor(), null);
        
        // 7. Imports
        if (changeCount > 0) {
            importManager.addImport("java.util.ArrayList");
            importManager.addImport("java.util.List");
            if (!vectorEnumerations.isEmpty()) {
                importManager.addImport("java.util.Iterator");
            }
            if (!isVectorStillUsedInAST()) {
                importManager.removeImportIfUnused("java.util.Vector");
                report.addChange(filePath, "Vector", "import_change", 0,
                               "import java.util.Vector", "removed", "No longer used");
            }
            if (!vectorEnumerations.isEmpty() && !isEnumerationStillUsedInAST()) {
                importManager.removeImportIfUnused("java.util.Enumeration");
                report.addChange(filePath, "Vector", "import_change", 0,
                               "import java.util.Enumeration", "removed", "Replaced with Iterator");
            }
        }
        
        return changeCount;
    }
    
    // === Pass 1: Collect variable names ===
    
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
    
    // === Pass 2: Track Enumeration sources ===
    
    private class EnumerationTracker extends VoidVisitorAdapter<Void> {
        @Override
        public void visit(VariableDeclarator var, Void arg) {
            super.visit(var, arg);
            if (var.getType().toString().contains("Enumeration") && var.getInitializer().isPresent()) {
                Expression init = var.getInitializer().get();
                if (init.isMethodCallExpr()) {
                    MethodCallExpr mc = init.asMethodCallExpr();
                    if ("elements".equals(mc.getNameAsString())) {
                        String scope = getScopeName(mc);
                        if (scope != null && knownVectorVariables.contains(scope)) {
                            vectorEnumerations.add(var.getNameAsString());
                        }
                    }
                }
            }
        }
    }
    
    // === Pass 3: Object creation (runs FIRST before type replacement) ===
    
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
                               original, objCreation.toString(), "new Vector → new ArrayList");
                changeCount++;
            }
        }
    }
    
    // === Pass 4: Type replacement (runs AFTER object creation) ===
    
    private class TypeReplacementVisitor extends VoidVisitorAdapter<Void> {
        @Override
        public void visit(ClassOrInterfaceType type, Void arg) {
            super.visit(type, arg);
            // By now, all "Vector" inside "new" expressions are already "ArrayList"
            // So any remaining "Vector" is a declaration/parameter/return/generic/cast type
            if ("Vector".equals(type.getNameAsString())) {
                int line = type.getBegin().map(pos -> pos.line).orElse(0);
                String original = type.toString();
                type.setName("List");
                report.addChange(filePath, "Vector", "type_replacement", line,
                               original, type.toString(), "Vector → List");
                changeCount++;
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
            if (scopeName == null || !knownVectorVariables.contains(scopeName)) return;
            
            String methodName = methodCall.getNameAsString();
            int line = methodCall.getBegin().map(pos -> pos.line).orElse(0);
            String original = methodCall.toString();
            
            switch (methodName) {
                case "elementAt":
                    methodCall.setName("get");
                    report.addChange(filePath, "Vector", "method_migration", line, original, methodCall.toString(), ".elementAt() → .get()");
                    changeCount++;
                    break;
                case "addElement":
                    methodCall.setName("add");
                    report.addChange(filePath, "Vector", "method_migration", line, original, methodCall.toString(), ".addElement() → .add()");
                    changeCount++;
                    break;
                case "removeElement":
                    methodCall.setName("remove");
                    report.addChange(filePath, "Vector", "method_migration", line, original, methodCall.toString(), ".removeElement() → .remove()");
                    changeCount++;
                    break;
                case "removeElementAt":
                    methodCall.setName("remove");
                    report.addChange(filePath, "Vector", "method_migration", line, original, methodCall.toString(), ".removeElementAt() → .remove()");
                    changeCount++;
                    break;
                case "insertElementAt":
                    if (methodCall.getArguments().size() == 2) {
                        Expression element = methodCall.getArgument(0).clone();
                        Expression index = methodCall.getArgument(1).clone();
                        methodCall.setName("add");
                        methodCall.setArguments(new com.github.javaparser.ast.NodeList<>(index, element));
                        report.addChange(filePath, "Vector", "method_migration", line, original, methodCall.toString(), "PARAMS SWAPPED: .insertElementAt(e,i) → .add(i,e)");
                        changeCount++;
                    }
                    break;
                case "setElementAt":
                    if (methodCall.getArguments().size() == 2) {
                        Expression element = methodCall.getArgument(0).clone();
                        Expression index = methodCall.getArgument(1).clone();
                        methodCall.setName("set");
                        methodCall.setArguments(new com.github.javaparser.ast.NodeList<>(index, element));
                        report.addChange(filePath, "Vector", "method_migration", line, original, methodCall.toString(), "PARAMS SWAPPED: .setElementAt(e,i) → .set(i,e)");
                        changeCount++;
                    }
                    break;
                case "firstElement":
                    methodCall.setName("get");
                    methodCall.setArguments(new com.github.javaparser.ast.NodeList<>(new IntegerLiteralExpr("0")));
                    report.addChange(filePath, "Vector", "method_migration", line, original, methodCall.toString(), ".firstElement() → .get(0)");
                    changeCount++;
                    break;
                case "lastElement":
                    if (methodCall.getScope().isPresent()) {
                        Expression scope = methodCall.getScope().get();
                        methodCall.setName("get");
                        MethodCallExpr sizeCall = new MethodCallExpr(scope.clone(), "size");
                        BinaryExpr indexExpr = new BinaryExpr(sizeCall, new IntegerLiteralExpr("1"), BinaryExpr.Operator.MINUS);
                        methodCall.setArguments(new com.github.javaparser.ast.NodeList<>(indexExpr));
                        report.addChange(filePath, "Vector", "method_migration", line, original, methodCall.toString(), ".lastElement() → .get(size()-1)");
                        changeCount++;
                    }
                    break;
                case "removeAllElements":
                    methodCall.setName("clear");
                    report.addChange(filePath, "Vector", "method_migration", line, original, methodCall.toString(), ".removeAllElements() → .clear()");
                    changeCount++;
                    break;
                case "elements":
                    methodCall.setName("iterator");
                    report.addChange(filePath, "Vector", "method_migration", line, original, methodCall.toString(), ".elements() → .iterator()");
                    changeCount++;
                    break;
                case "copyInto":
                    methodCall.setName("toArray");
                    report.addChange(filePath, "Vector", "method_migration", line, original, methodCall.toString(), ".copyInto() → .toArray()");
                    changeCount++;
                    break;
                case "setSize":
                    if (methodCall.getParentNode().isPresent()) {
                        methodCall.getParentNode().get().setComment(new LineComment(" TODO: Vector.setSize() has no direct ArrayList equivalent"));
                        report.addChange(filePath, "Vector", "method_migration", line, original, original + " // TODO", ".setSize() - TODO added");
                        changeCount++;
                    }
                    break;
                case "capacity":
                    if (methodCall.getParentNode().isPresent()) {
                        methodCall.getParentNode().get().setComment(new LineComment(" TODO: ArrayList doesn't expose capacity()"));
                        report.addChange(filePath, "Vector", "method_migration", line, original, original + " // TODO", ".capacity() - TODO added");
                        changeCount++;
                    }
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
                    if (vectorEnumerations.contains(varName)) {
                        int line = type.getBegin().map(pos -> pos.line).orElse(0);
                        type.setName("Iterator");
                        report.addChange(filePath, "Vector", "type_replacement", line, "Enumeration", "Iterator", "Vector-sourced Enumeration → Iterator");
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
                if (vectorEnumerations.contains(scopeName)) {
                    int line = methodCall.getBegin().map(pos -> pos.line).orElse(0);
                    String original = methodCall.toString();
                    String methodName = methodCall.getNameAsString();
                    if ("hasMoreElements".equals(methodName)) {
                        methodCall.setName("hasNext");
                        report.addChange(filePath, "Vector", "method_migration", line, original, methodCall.toString(), ".hasMoreElements() → .hasNext()");
                        changeCount++;
                    } else if ("nextElement".equals(methodName)) {
                        methodCall.setName("next");
                        report.addChange(filePath, "Vector", "method_migration", line, original, methodCall.toString(), ".nextElement() → .next()");
                        changeCount++;
                    }
                }
            }
        }
    }
    
    // === Utility ===
    
    private boolean isVectorType(Type type) {
        if (type.isClassOrInterfaceType()) {
            return "Vector".equals(type.asClassOrInterfaceType().getNameAsString());
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
    
    private boolean isVectorStillUsedInAST() {
        for (ClassOrInterfaceType t : cu.findAll(ClassOrInterfaceType.class)) {
            if ("Vector".equals(t.getNameAsString())) return true;
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
