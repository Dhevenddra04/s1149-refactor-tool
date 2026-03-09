package com.hphis.refactor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

/**
 * Migrates StringBuffer to StringBuilder.
 * This is the simplest migration as all methods are identical and both
 * declaration types and constructor types should be StringBuilder.
 * 
 * Uses a SINGLE ClassOrInterfaceType visitor that handles ALL occurrences:
 * declarations, parameters, return types, generics, new expressions, casts, instanceof.
 * No separate ObjectCreation visitor needed since StringBuilder is a concrete class.
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
        
        // Single visitor handles ALL StringBuffer type references everywhere
        // This includes: variable types, new expressions, casts, instanceof, generics
        cu.accept(new AllReferencesVisitor(), null);
        
        // Handle imports
        if (changeCount > 0) {
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
     * Single visitor that replaces ALL StringBuffer references with StringBuilder.
     * Since StringBuilder is a concrete class (not an interface), we can safely
     * replace it everywhere including inside new expressions.
     */
    private class AllReferencesVisitor extends VoidVisitorAdapter<Void> {
        
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
}
