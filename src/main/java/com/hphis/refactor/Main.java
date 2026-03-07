package com.hphis.refactor;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Entry point for the S1149 refactoring tool.
 * Handles command-line arguments and orchestrates the refactoring process.
 */
public class Main {
    
    public static void main(String[] args) {
        try {
            Config config = parseArguments(args);
            
            if (config == null) {
                printUsage();
                System.exit(1);
            }
            
            System.out.println("=== S1149 Refactor Tool ===");
            System.out.println("Source directory: " + config.sourceDir);
            System.out.println("Migrations: " + config.migrations);
            System.out.println("Dry run: " + config.dryRun);
            if (config.reportFile != null) {
                System.out.println("Report file: " + config.reportFile);
            }
            System.out.println();
            
            RefactorEngine engine = new RefactorEngine(config);
            engine.execute();
            
            System.out.println("\n=== Refactoring Complete ===");
            
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static Config parseArguments(String[] args) {
        Config config = new Config();
        
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            
            if ("--source".equals(arg) && i + 1 < args.length) {
                config.sourceDir = new File(args[++i]);
            } else if ("--migrate".equals(arg) && i + 1 < args.length) {
                String[] migrations = args[++i].split(",");
                config.migrations = new HashSet<>(Arrays.asList(migrations));
            } else if ("--dry-run".equals(arg)) {
                config.dryRun = true;
            } else if ("--report".equals(arg) && i + 1 < args.length) {
                config.reportFile = new File(args[++i]);
            } else if ("--help".equals(arg) || "-h".equals(arg)) {
                return null;
            }
        }
        
        // Validate required arguments
        if (config.sourceDir == null || !config.sourceDir.exists()) {
            System.err.println("ERROR: --source directory must be specified and must exist");
            return null;
        }
        
        if (config.migrations == null || config.migrations.isEmpty()) {
            System.err.println("ERROR: --migrate must specify at least one migration type");
            return null;
        }
        
        // Validate migration types
        Set<String> validMigrations = new HashSet<>(Arrays.asList("StringBuffer", "Vector", "Hashtable"));
        for (String migration : config.migrations) {
            if (!validMigrations.contains(migration)) {
                System.err.println("ERROR: Invalid migration type: " + migration);
                System.err.println("Valid types: StringBuffer, Vector, Hashtable");
                return null;
            }
        }
        
        return config;
    }
    
    private static void printUsage() {
        System.out.println("Usage: java -jar s1149-refactor-tool.jar [OPTIONS]");
        System.out.println();
        System.out.println("Required Options:");
        System.out.println("  --source <path>           Source directory containing Java files");
        System.out.println("  --migrate <types>         Comma-separated migration types:");
        System.out.println("                            StringBuffer, Vector, Hashtable");
        System.out.println();
        System.out.println("Optional:");
        System.out.println("  --dry-run                 Show changes without writing files");
        System.out.println("  --report <file>           Output change report to CSV file");
        System.out.println("  --help, -h                Show this help message");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  java -jar s1149-refactor-tool.jar \\");
        System.out.println("    --source \"C:\\path\\to\\src\\main\\java\" \\");
        System.out.println("    --migrate StringBuffer,Vector,Hashtable \\");
        System.out.println("    --dry-run \\");
        System.out.println("    --report report.csv");
    }
    
    /**
     * Configuration holder for command-line arguments.
     */
    static class Config {
        File sourceDir;
        Set<String> migrations;
        boolean dryRun = false;
        File reportFile;
    }
}
