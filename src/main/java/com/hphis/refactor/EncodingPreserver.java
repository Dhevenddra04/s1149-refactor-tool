package com.hphis.refactor;

import org.mozilla.universalchardet.UniversalDetector;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Handles file encoding detection and preservation.
 * Critical for maintaining Spanish characters (ñ, á, é, í, ó, ú) in the HCIS codebase.
 */
public class EncodingPreserver {
    
    private static final Charset DEFAULT_CHARSET = Charset.forName("ISO-8859-1");
    
    /**
     * Detects the encoding of a file.
     * Tries multiple strategies:
     * 1. BOM detection
     * 2. UniversalChardet library
     * 3. UTF-8 validity check (if all bytes are valid UTF-8, use UTF-8)
     * 4. Falls back to ISO-8859-1 (most common in HCIS codebase)
     */
    public static Charset detectEncoding(File file) throws IOException {
        byte[] fileBytes = Files.readAllBytes(file.toPath());
        
        // Check for BOM
        if (fileBytes.length >= 3) {
            if (fileBytes[0] == (byte) 0xEF && fileBytes[1] == (byte) 0xBB && fileBytes[2] == (byte) 0xBF) {
                return StandardCharsets.UTF_8;
            }
        }
        if (fileBytes.length >= 2) {
            if (fileBytes[0] == (byte) 0xFE && fileBytes[1] == (byte) 0xFF) {
                return StandardCharsets.UTF_16BE;
            }
            if (fileBytes[0] == (byte) 0xFF && fileBytes[1] == (byte) 0xFE) {
                return StandardCharsets.UTF_16LE;
            }
        }
        
        // Use UniversalChardet for detection
        UniversalDetector detector = new UniversalDetector(null);
        int offset = 0;
        int chunkSize = 4096;
        while (offset < fileBytes.length && !detector.isDone()) {
            int length = Math.min(chunkSize, fileBytes.length - offset);
            detector.handleData(fileBytes, offset, length);
            offset += length;
        }
        detector.dataEnd();
        String detectedCharset = detector.getDetectedCharset();
        detector.reset();
        
        if (detectedCharset != null) {
            try {
                Charset charset = Charset.forName(detectedCharset);
                // If detected as UTF-8, verify it's valid UTF-8
                if (charset.equals(StandardCharsets.UTF_8) && isValidUtf8(fileBytes)) {
                    return StandardCharsets.UTF_8;
                }
                // For other detected charsets, trust the detector
                if (!charset.equals(StandardCharsets.UTF_8)) {
                    return charset;
                }
            } catch (Exception e) {
                // Invalid charset name, fall through
            }
        }
        
        // If file contains only ASCII (bytes 0-127), UTF-8 and ISO-8859-1 are identical
        if (isAsciiOnly(fileBytes)) {
            return StandardCharsets.UTF_8;
        }
        
        // Check if it's valid UTF-8 with multi-byte sequences
        if (isValidUtf8(fileBytes)) {
            // Could be UTF-8 or ISO-8859-1 — check for common Spanish UTF-8 patterns
            String asUtf8 = new String(fileBytes, StandardCharsets.UTF_8);
            if (!asUtf8.contains("\uFFFD") && containsSpanishChars(asUtf8)) {
                return StandardCharsets.UTF_8;
            }
        }
        
        // Default: ISO-8859-1 (most common in HCIS, and it can decode ANY byte sequence)
        return DEFAULT_CHARSET;
    }
    
    /**
     * Checks if all bytes are ASCII (0-127).
     */
    private static boolean isAsciiOnly(byte[] bytes) {
        for (byte b : bytes) {
            if ((b & 0x80) != 0) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Validates if a byte array is valid UTF-8.
     */
    private static boolean isValidUtf8(byte[] bytes) {
        int i = 0;
        while (i < bytes.length) {
            int b = bytes[i] & 0xFF;
            int expectedBytes;
            
            if (b <= 0x7F) {
                expectedBytes = 1;
            } else if (b >= 0xC2 && b <= 0xDF) {
                expectedBytes = 2;
            } else if (b >= 0xE0 && b <= 0xEF) {
                expectedBytes = 3;
            } else if (b >= 0xF0 && b <= 0xF4) {
                expectedBytes = 4;
            } else {
                return false; // Invalid UTF-8 start byte
            }
            
            if (i + expectedBytes > bytes.length) {
                return false;
            }
            
            for (int j = 1; j < expectedBytes; j++) {
                if ((bytes[i + j] & 0xC0) != 0x80) {
                    return false; // Invalid continuation byte
                }
            }
            
            i += expectedBytes;
        }
        return true;
    }
    
    /**
     * Checks if a string contains common Spanish characters.
     */
    private static boolean containsSpanishChars(String str) {
        char[] spanishChars = {'ñ', 'Ñ', 'á', 'é', 'í', 'ó', 'ú', 'Á', 'É', 'Í', 'Ó', 'Ú', 'ü', 'Ü'};
        for (char c : spanishChars) {
            if (str.indexOf(c) >= 0) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Reads a file preserving its original encoding.
     */
    public static String readFile(File file, Charset charset) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        return new String(bytes, charset);
    }
    
    /**
     * Writes a file using the specified encoding.
     */
    public static void writeFile(File file, String content, Charset charset) throws IOException {
        byte[] bytes = content.getBytes(charset);
        Files.write(file.toPath(), bytes);
    }
    
    /**
     * Verifies that Spanish characters are preserved after a write operation.
     */
    public static boolean verifySpanishCharacters(String original, String modified) {
        char[] spanishChars = {'ñ', 'Ñ', 'á', 'é', 'í', 'ó', 'ú', 'Á', 'É', 'Í', 'Ó', 'Ú', 'ü', 'Ü'};
        
        for (char c : spanishChars) {
            int originalCount = countOccurrences(original, c);
            int modifiedCount = countOccurrences(modified, c);
            
            if (modifiedCount < originalCount) {
                return false;
            }
        }
        
        return true;
    }
    
    private static int countOccurrences(String str, char c) {
        int count = 0;
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == c) {
                count++;
            }
        }
        return count;
    }
}
