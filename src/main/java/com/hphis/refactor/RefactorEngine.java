package com.hphis.refactor;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Orchestrates the S1149 refactoring process.
 * Scans files, applies migrations, and generates reports.
 */
public class RefactorEngine {
    
    private final Main.Config config;
    private final ChangeReport report;
    
    public RefactorEngine(Main.Config config) {
        this.config = config;
        this.report = new ChangeReport();
    }
    
    /**
     * Executes the refactoring process.
     */
    public void execute() throws IOException {
        System.out.println("Scanning for Java files...");
        List<File> javaFiles = findJavaFiles(config.sourceDir);
        System.out.println("Found " + javaFiles.size() + " Java files\n");
        
        int processedCount = 0;
        for (File file : javaFiles) {
            processedCount++;
            if (processedCount % 100 == 0) {
                System.out.println("Progress: " + processedCount + "/" + javaFiles.size());
            }
            
            try {
                processFile(file);
            } catch (Exception e) {
                System.err.println("ERROR processing " + file.getPath() + ": " + e.getMessage());
                report.incrementErrors();
                if (config.dryRun) {
                    e.printStackTrace();
                }
            }
        }
        
        // Print summary
        report.printSummary();
        
        // Write report file if requested
        if (config.reportFile != null) {
            report.writeToFile(config.reportFile);
            System.out.println("\nReport written to: " + config.reportFile.getAbsolutePath());
        }
        
        if (config.dryRun) {
            System.out.println("\nDRY RUN - No files were modified");
        }
    }
    
    /**
     * Processes a single Java file.
     */
    private void processFile(File file) throws IOException {
        report.incrementFilesProcessed();
        
        // Detect original encoding
        Charset originalCharset = EncodingPreserver.detectEncoding(file);
        
        // Read file with original encoding
        String originalSource = EncodingPreserver.readFile(file, originalCharset);
        
        // Parse the file
        CompilationUnit cu;
        try {
            cu = StaticJavaParser.parse(originalSource);
        } catch (Exception e) {
            System.err.println("SKIP (parse error): " + file.getPath());
            report.incrementFilesSkipped();
            return;
        }
        
        // Enable lexical preservation to minimize changes
        LexicalPreservingPrinter.setup(cu);
        
        // Create import manager
        ImportManager importManager = new ImportManager(cu);
        
        // Track total changes for this file
        int totalChanges = 0;
        
        // Apply migrations based on configuration
        if (config.migrations.contains("StringBuffer")) {
            StringBufferMigrator stringBufferMigrator = 
                new StringBufferMigrator(cu, importManager, report, file.getPath());
            totalChanges += stringBufferMigrator.migrate();
        }
        
        if (config.migrations.contains("Vector")) {
            VectorMigrator vectorMigrator = 
                new VectorMigrator(cu, importManager, report, file.getPath());
            totalChanges += vectorMigrator.migrate();
        }
        
        if (config.migrations.contains("Hashtable")) {
            HashtableMigrator hashtableMigrator = 
                new HashtableMigrator(cu, importManager, report, file.getPath());
            totalChanges += hashtableMigrator.migrate();
        }
        
        // If no changes, skip writing
        if (totalChanges == 0) {
            return;
        }
        
        // Apply import changes
        importManager.applyChanges();
        
        // Generate modified source
        String modifiedSource;
        try {
            modifiedSource = LexicalPreservingPrinter.print(cu);
        } catch (Exception e) {
            // Fallback to regular printer if lexical preservation fails
            modifiedSource = cu.toString();
        }
        
        // Verify Spanish characters are preserved
        if (!EncodingPreserver.verifySpanishCharacters(originalSource, modifiedSource)) {
            System.err.println("WARNING: Spanish characters may have been corrupted in " + file.getPath());
            report.incrementErrors();
            return;
        }
        
        // Write file if not dry run
        if (!config.dryRun) {
            EncodingPreserver.writeFile(file, modifiedSource, originalCharset);
        }
        
        report.incrementFilesModified();
        
        if (config.dryRun) {
            System.out.println("WOULD MODIFY: " + file.getPath() + " (" + totalChanges + " changes)");
        }
    }
    
    /**
     * Recursively finds all .java files in a directory.
     */
    private List<File> findJavaFiles(File directory) throws IOException {
        List<File> javaFiles = new ArrayList<>();
        
        Files.walk(directory.toPath())
            .filter(Files::isRegularFile)
            .filter(path -> path.toString().endsWith(".java"))
            .filter(path -> !path.toString().endsWith(".jsp")) // Exclude JSP files
            .forEach(path -> javaFiles.add(path.toFile()));
        
        return javaFiles;
    }
}
