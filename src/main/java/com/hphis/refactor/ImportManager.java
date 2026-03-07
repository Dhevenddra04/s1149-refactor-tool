package com.hphis.refactor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;

import java.util.HashSet;
import java.util.Set;

/**
 * Manages import statements during refactoring.
 * Adds required imports and removes obsolete ones.
 */
public class ImportManager {
    
    private final CompilationUnit cu;
    private final Set<String> existingImports = new HashSet<>();
    private final Set<String> importsToAdd = new HashSet<>();
    private final Set<String> importsToRemove = new HashSet<>();
    
    public ImportManager(CompilationUnit cu) {
        this.cu = cu;
        
        // Collect existing imports
        for (ImportDeclaration imp : cu.getImports()) {
            existingImports.add(imp.getNameAsString());
        }
    }
    
    /**
     * Adds an import if it doesn't already exist.
     */
    public void addImport(String importName) {
        if (!existingImports.contains(importName) && !importsToAdd.contains(importName)) {
            importsToAdd.add(importName);
        }
    }
    
    /**
     * Marks an import for removal if it's safe to remove.
     */
    public void removeImportIfUnused(String importName) {
        if (existingImports.contains(importName)) {
            importsToRemove.add(importName);
        }
    }
    
    /**
     * Applies all pending import changes.
     */
    public void applyChanges() {
        // Add new imports
        for (String importName : importsToAdd) {
            cu.addImport(importName);
        }
        
        // Remove obsolete imports
        NodeList<ImportDeclaration> imports = cu.getImports();
        imports.removeIf(imp -> importsToRemove.contains(imp.getNameAsString()));
    }
    
    /**
     * Checks if a specific import exists.
     */
    public boolean hasImport(String importName) {
        return existingImports.contains(importName) || importsToAdd.contains(importName);
    }
    
    /**
     * Checks if any import matching a pattern exists.
     */
    public boolean hasImportMatching(String pattern) {
        for (String imp : existingImports) {
            if (imp.contains(pattern)) {
                return true;
            }
        }
        for (String imp : importsToAdd) {
            if (imp.contains(pattern)) {
                return true;
            }
        }
        return false;
    }
}
