# S1149 Refactor Tool

AST-based Java refactoring tool for SonarQube S1149 rule (synchronized collections migration).

## Overview

This tool automatically migrates synchronized collection types to their unsynchronized equivalents:
- `StringBuffer` → `StringBuilder`
- `Vector` → `ArrayList`/`List`
- `Hashtable` → `HashMap`/`Map`

The tool uses JavaParser to perform AST-based transformations, preserving file encoding and Spanish characters (ñ, á, é, í, ó, ú) that are common in the HCIS healthcare application codebase.

## Features

- **AST-based transformation**: Structural code changes, not text replacement
- **Encoding preservation**: Maintains original file encoding (ISO-8859-1, windows-1252, UTF-8)
- **Method migration**: Handles legacy method calls and parameter swaps
- **Import management**: Adds/removes imports automatically
- **Enumeration tracking**: Only migrates Enumeration from Vector/Hashtable sources
- **Exclusions**: Skips Properties, Stack, and non-Java files
- **Dry run mode**: Preview changes without modifying files
- **Detailed reporting**: CSV report of all changes

## Building

```bash
cd s1149-refactor-tool
mvn clean package
```

This creates `target/s1149-refactor-tool-1.0.0.jar`.

## Usage

### Basic Usage

```bash
java -jar target/s1149-refactor-tool-1.0.0.jar \
  --source "C:\path\to\src\main\java" \
  --migrate StringBuffer,Vector,Hashtable
```

### Dry Run (Preview Changes)

```bash
java -jar target/s1149-refactor-tool-1.0.0.jar \
  --source "C:\path\to\src\main\java" \
  --migrate StringBuffer,Vector,Hashtable \
  --dry-run
```

### With Report

```bash
java -jar target/s1149-refactor-tool-1.0.0.jar \
  --source "C:\path\to\src\main\java" \
  --migrate StringBuffer,Vector,Hashtable \
  --report changes-report.csv
```

### Command-Line Options

- `--source <path>` (required): Source directory containing Java files
- `--migrate <types>` (required): Comma-separated migration types (StringBuffer, Vector, Hashtable)
- `--dry-run` (optional): Show changes without writing files
- `--report <file>` (optional): Output change report to CSV file
- `--help`, `-h`: Show help message

## Migrations

### StringBuffer → StringBuilder

**Type Replacements:**
- All declarations: `StringBuffer` → `StringBuilder`
- Object creation: `new StringBuffer()` → `new StringBuilder()`
- Casts: `(StringBuffer)` → `(StringBuilder)`
- instanceof: `instanceof StringBuffer` → `instanceof StringBuilder`

**Method Calls:**
- No changes needed - all methods are identical

**Imports:**
- Both are in `java.lang` - no import changes needed

### Vector → ArrayList/List

**Type Replacements:**
- Public/protected signatures: `Vector` → `List`
- Private signatures: `Vector` → `List` (preferred)
- Object creation: `new Vector()` → `new ArrayList()`
- Preserves generic type parameters and raw types

**Method Migrations:**

| Old Method | New Method | Notes |
|------------|------------|-------|
| `.elementAt(i)` | `.get(i)` | Direct replacement |
| `.addElement(x)` | `.add(x)` | Direct replacement |
| `.removeElement(x)` | `.remove(x)` | Direct replacement |
| `.removeElementAt(i)` | `.remove(i)` | Direct replacement |
| `.insertElementAt(x, i)` | `.add(i, x)` | **PARAMETERS SWAPPED!** |
| `.setElementAt(x, i)` | `.set(i, x)` | **PARAMETERS SWAPPED!** |
| `.firstElement()` | `.get(0)` | Direct replacement |
| `.lastElement()` | `.get(size() - 1)` | Uses actual variable name |
| `.removeAllElements()` | `.clear()` | Direct replacement |
| `.elements()` | `.iterator()` | Returns Iterator not Enumeration |
| `.copyInto(arr)` | `.toArray(arr)` | Direct replacement |
| `.setSize(n)` | Add TODO comment | No direct equivalent |
| `.capacity()` | Add TODO comment | ArrayList doesn't expose capacity |

**Enumeration → Iterator:**
- Only for Vector-sourced Enumerations
- `Enumeration` → `Iterator`
- `.hasMoreElements()` → `.hasNext()`
- `.nextElement()` → `.next()`
- Does NOT touch Enumeration from Servlet APIs or Properties

**Imports:**
- Adds: `java.util.ArrayList`, `java.util.List`, `java.util.Iterator`
- Removes: `java.util.Vector`, `java.util.Enumeration` (if safe)

### Hashtable → HashMap/Map

**Type Replacements:**
- Public/protected signatures: `Hashtable` → `Map`
- Private signatures: `Hashtable` → `Map` (preferred)
- Object creation: `new Hashtable()` → `new HashMap()`
- Preserves generic type parameters and raw types

**Method Migrations:**

| Old Method | New Method | Notes |
|------------|------------|-------|
| `.contains(value)` | `.containsValue(value)` | Different name! |
| `.elements()` | `.values().iterator()` | Returns Iterator over values |
| `.keys()` | `.keySet().iterator()` | Returns Iterator over keys |
| `.put()`, `.get()`, `.remove()` | Same | No change |
| `.containsKey()`, `.containsValue()` | Same | No change |
| `.size()`, `.isEmpty()`, `.clear()` | Same | No change |
| `.keySet()`, `.values()`, `.entrySet()` | Same | No change |
| `.putAll()` | Same | No change |

**Enumeration → Iterator:**
- Same rules as Vector migration
- Only for Hashtable-sourced Enumerations

**Imports:**
- Adds: `java.util.HashMap`, `java.util.Map`, `java.util.Iterator`
- Removes: `java.util.Hashtable`, `java.util.Enumeration` (if safe)

**Exclusions:**
- **Properties**: `java.util.Properties` extends Hashtable but is NOT changed
- Properties method calls (`.keys()`, `.elements()`) are preserved

## Critical Features

### Encoding Preservation

The tool detects and preserves file encoding:
1. Checks for BOM (Byte Order Mark)
2. Uses UniversalChardet library for detection
3. Tests common encodings (ISO-8859-1, windows-1252, UTF-8)
4. Defaults to ISO-8859-1 (most common in HCIS)
5. Verifies Spanish characters after writing

### Lexical Preservation

Uses JavaParser's `LexicalPreservingPrinter` to:
- Minimize changes to the file
- Preserve formatting, comments, and whitespace
- Only modify AST nodes that need changes

### Enumeration Source Tracking

The tool tracks which `Enumeration` variables come from:
- Vector `.elements()` → Migrate to Iterator
- Hashtable `.elements()` or `.keys()` → Migrate to Iterator
- Servlet APIs (HttpServletRequest, ServletContext) → DO NOT migrate
- Properties → DO NOT migrate

### Parameter Swap Detection

Automatically handles parameter order changes:
- `Vector.insertElementAt(element, index)` → `List.add(index, element)`
- `Vector.setElementAt(element, index)` → `List.set(index, element)`

## Report Format

The CSV report includes:
- File path
- Migration type (StringBuffer/Vector/Hashtable)
- Change type (type_replacement/method_migration/import_change)
- Line number
- Original code
- New code
- Notes (e.g., "parameter order swapped", "TODO added")

## Error Handling

- Parse errors: File is skipped, logged, and counted
- Encoding detection failures: Falls back to ISO-8859-1
- Spanish character corruption: File is not written, error logged
- Lexical preservation failures: Falls back to regular printer
- Individual file failures: Logged but don't stop the process

## Testing

Run the tool on a test directory first:

```bash
# Dry run to preview changes
java -jar target/s1149-refactor-tool-1.0.0.jar \
  --source "test-files" \
  --migrate StringBuffer,Vector,Hashtable \
  --dry-run \
  --report test-report.csv

# Review the report
cat test-report.csv

# Apply changes
java -jar target/s1149-refactor-tool-1.0.0.jar \
  --source "test-files" \
  --migrate StringBuffer,Vector,Hashtable \
  --report test-report.csv
```

## Verification

After running the tool:

1. **Compile the code:**
   ```bash
   mvn clean install -Dmaven.test.skip=true
   ```

2. **Check for encoding issues:**
   ```bash
   # Search for replacement characters (indicates encoding corruption)
   grep -r "�" src/main/java/
   ```

3. **Review the report:**
   - Check for unexpected changes
   - Verify parameter swaps are correct
   - Confirm TODO comments are appropriate

4. **Run tests:**
   ```bash
   mvn test
   ```

## Idempotency

The tool is idempotent - running it multiple times produces the same result:
- Already-migrated code is not changed again
- Import management checks for existing imports
- Type checks prevent double-replacement

## Limitations

1. **Complex expressions**: Some complex nested expressions may not be handled perfectly
2. **Dynamic types**: Cannot determine types that require runtime information
3. **Reflection**: Code using reflection to access Vector/Hashtable methods is not changed
4. **Comments**: Comments mentioning Vector/Hashtable are not updated
5. **String literals**: String literals containing "Vector" or "Hashtable" are not changed

## Troubleshooting

### "Parse error" messages
- File has syntax errors - fix manually or skip
- File uses Java 9+ features - tool targets JDK 8

### "Spanish characters may have been corrupted"
- Encoding detection failed
- File is not written to prevent corruption
- Manually specify encoding or fix file

### "No changes detected" but SonarQube still reports issues
- Issue may be in comments or string literals
- Issue may be in excluded code (Properties, Stack)
- Run with `--dry-run --report` to see what was skipped

### Tool runs slowly
- Large codebase - normal for 13,000+ files
- Progress is printed every 100 files
- Consider running on subdirectories

## License

Internal tool for HCIS project.

## Support

For issues or questions, contact the development team.
