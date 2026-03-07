package com.hphis.refactor;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Tracks and reports all changes made during refactoring.
 * Generates CSV reports for review.
 */
public class ChangeReport {
    
    private final List<Change> changes = new ArrayList<>();
    private int filesProcessed = 0;
    private int filesModified = 0;
    private int filesSkipped = 0;
    private int errors = 0;
    
    /**
     * Records a single change.
     */
    public void addChange(String filePath, String migrationType, String changeType,
                         int lineNumber, String originalCode, String newCode, String notes) {
        changes.add(new Change(filePath, migrationType, changeType, lineNumber, 
                              originalCode, newCode, notes));
    }
    
    public void incrementFilesProcessed() {
        filesProcessed++;
    }
    
    public void incrementFilesModified() {
        filesModified++;
    }
    
    public void incrementFilesSkipped() {
        filesSkipped++;
    }
    
    public void incrementErrors() {
        errors++;
    }
    
    /**
     * Writes the report to a CSV file.
     */
    public void writeToFile(File reportFile) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(reportFile))) {
            // CSV header
            writer.println("File Path,Migration Type,Change Type,Line Number,Original Code,New Code,Notes");
            
            for (Change change : changes) {
                writer.println(escapeCsv(change.filePath) + "," +
                             escapeCsv(change.migrationType) + "," +
                             escapeCsv(change.changeType) + "," +
                             change.lineNumber + "," +
                             escapeCsv(change.originalCode) + "," +
                             escapeCsv(change.newCode) + "," +
                             escapeCsv(change.notes));
            }
        }
    }
    
    /**
     * Prints a summary to console.
     */
    public void printSummary() {
        System.out.println("\n=== Summary ===");
        System.out.println("Files processed: " + filesProcessed);
        System.out.println("Files modified: " + filesModified);
        System.out.println("Files skipped: " + filesSkipped);
        System.out.println("Errors: " + errors);
        System.out.println("Total changes: " + changes.size());
        
        if (!changes.isEmpty()) {
            System.out.println("\nChanges by migration type:");
            countByMigrationType();
            
            System.out.println("\nChanges by change type:");
            countByChangeType();
        }
    }
    
    private void countByMigrationType() {
        int stringBuffer = 0, vector = 0, hashtable = 0;
        for (Change change : changes) {
            if ("StringBuffer".equals(change.migrationType)) stringBuffer++;
            else if ("Vector".equals(change.migrationType)) vector++;
            else if ("Hashtable".equals(change.migrationType)) hashtable++;
        }
        System.out.println("  StringBuffer: " + stringBuffer);
        System.out.println("  Vector: " + vector);
        System.out.println("  Hashtable: " + hashtable);
    }
    
    private void countByChangeType() {
        int typeReplacement = 0, methodMigration = 0, importChange = 0, other = 0;
        for (Change change : changes) {
            if ("type_replacement".equals(change.changeType)) typeReplacement++;
            else if ("method_migration".equals(change.changeType)) methodMigration++;
            else if ("import_change".equals(change.changeType)) importChange++;
            else other++;
        }
        System.out.println("  Type replacements: " + typeReplacement);
        System.out.println("  Method migrations: " + methodMigration);
        System.out.println("  Import changes: " + importChange);
        System.out.println("  Other: " + other);
    }
    
    /**
     * Escapes a string for CSV format.
     */
    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        
        // If contains comma, quote, or newline, wrap in quotes and escape quotes
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        
        return value;
    }
    
    public int getChangeCount() {
        return changes.size();
    }
    
    /**
     * Represents a single change in the refactoring process.
     */
    private static class Change {
        final String filePath;
        final String migrationType;
        final String changeType;
        final int lineNumber;
        final String originalCode;
        final String newCode;
        final String notes;
        
        Change(String filePath, String migrationType, String changeType, int lineNumber,
               String originalCode, String newCode, String notes) {
            this.filePath = filePath;
            this.migrationType = migrationType;
            this.changeType = changeType;
            this.lineNumber = lineNumber;
            this.originalCode = originalCode;
            this.newCode = newCode;
            this.notes = notes;
        }
    }
}
