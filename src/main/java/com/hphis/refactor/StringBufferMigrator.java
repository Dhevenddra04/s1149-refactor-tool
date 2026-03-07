package com.hphis.refactor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

/**
 * Migrates StringBuffer to StringBuilder.
 * This is the simplest migration as all methods are identical.
 * 
 * Only the ClassOrInterfaceType visitor does the actual replacement and reporting.
 * No separate Field/Method/Parameter/Variable visitors needed since
 * ClassOrInterfaceType covers all type references in the AST.
 */
public class StringBufferMigrator {
    
    private final CompilationUnit cu;
    private final ImportManager importManager;
    private final ChangeReport report;
    private final String filePath;
    private int changeCount = 0;
    
    public StringBufferMigrator(CompilationUnit cu, ImportManager importManager, 
                               ChangeReport report, String filePath) {
        this.cu = cu;
        this.importManager = importManager;
        this.report = report;
        this.filePath = filePath;
    }
    
    /**
     * Performs the StringBuffer → StringBuilder migration.
     */
    public int migrate() {
        changeCount = 0;
        
        // Replace all type references (covers fields, params, return types, variables, generics)
        cu.accept(new TypeReplacementVisitor(), null);
        
        // Replace object creation
        cu.accept(new ObjectCreationVisitor(), null);
        
        // Replace in cast expressions
        cu.accept(new CastAndInstanceOfVisitor(), null);
        
        // Handle imports
        if (changeCount > 0) {
            // StringBuffer and StringBuilder are both in java.lang, no import changes needed
            // But if there's an explicit import, remove it
            if (importManager.hasImport("java.lang.StringBuffer")) {
                importManager.removeImportIfUnused("java.lang.StringBuffer");
                report.addChange(filePath, "StringBuffer", "import_change", 0,
                               "import java.lang.StringBuffer", "removed", 
                               "StringBuffer/StringBuilder are in java.lang");
            }
        }
        
        return changeCount;
    }
    
    /**
     * Replaces all StringBuffer type references with StringBuilder.
     * This single visitor handles all type contexts: fields, parameters, 
     * return types, local variables, generic type arguments.
     * No separate visitors needed — avoids double-counting.
     */
    private class TypeReplacementVisitor extends VoidVisitorAdapter<Void> {
        
        @Override
        public void visit(ClassOrInterfaceType type, Void arg) {
            super.visit(type, arg);
            
            if ("StringBuffer".equals(type.getNameAsString())) {
                int line = type.getBegin().map(pos -> pos.line).orElse(0);
                String original = type.toString();
                
                type.setName("StringBuilder");
                
                report.addChange(filePath, "StringBuffer", "type_replacement", line,
                               original, type.toString(), "Type reference");
                changeCount++;
            }
        }
    }
    
    /**
     * Replaces new StringBuffer() with new StringBuilder().
     */
    private class ObjectCreationVisitor extends VoidVisitorAdapter<Void> {
        
        @Override
        public void visit(ObjectCreationExpr objCreation, Void arg) {
            super.visit(objCreation, arg);
            
            ClassOrInterfaceType type = objCreation.getType();
            if ("StringBuffer".equals(type.getNameAsString())) {
                int line = objCreation.getBegin().map(pos -> pos.line).orElse(0);
                String original = objCreation.toString();
                
                type.setName("StringBuilder");
                
                report.addChange(filePath, "StringBuffer", "type_replacement", line,
                               original, objCreation.toString(), "Object creation");
                changeCount++;
            }
        }
    }
    
    /**
     * Handles cast expressions and instanceof checks.
     */
    private class CastAndInstanceOfVisitor extends VoidVisitorAdapter<Void> {
        
        @Override
        public void visit(CastExpr cast, Void arg) {
            super.visit(cast, arg);
            
            Type castType = cast.getType();
            if (castType.isClassOrInterfaceType()) {
                ClassOrInterfaceType classType = castType.asClassOrInterfaceType();
                if ("StringBuffer".equals(classType.getNameAsString())) {
                    int line = cast.getBegin().map(pos -> pos.line).orElse(0);
                    String original = cast.toString();
                    
                    classType.setName("StringBuilder");
                    
                    report.addChange(filePath, "StringBuffer", "type_replacement", line,
                                   original, cast.toString(), "Cast expression");
                    changeCount++;
                }
            }
        }
        
        @Override
        public void visit(InstanceOfExpr instanceOf, Void arg) {
            super.visit(instanceOf, arg);
            
            Type type = instanceOf.getType();
            if (type.isClassOrInterfaceType()) {
                ClassOrInterfaceType classType = type.asClassOrInterfaceType();
                if ("StringBuffer".equals(classType.getNameAsString())) {
                    int line = instanceOf.getBegin().map(pos -> pos.line).orElse(0);
                    String original = instanceOf.toString();
                    
                    classType.setName("StringBuilder");
                    
                    report.addChange(filePath, "StringBuffer", "type_replacement", line,
                                   original, instanceOf.toString(), "instanceof check");
                    changeCount++;
                }
            }
        }
    }
}
