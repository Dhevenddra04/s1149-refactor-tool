package com.hphis.test;

import java.util.Vector;
import java.util.Hashtable;
import java.util.Enumeration;
import java.util.Properties;

/**
 * Test file demonstrating all three migrations.
 * Contains Spanish characters: ñ, á, é, í, ó, ú
 * Comentario en español: configuración, año, información
 */
public class TestFile {
    
    // StringBuffer fields
    private StringBuffer buffer1 = new StringBuffer();
    private StringBuffer buffer2 = new StringBuffer("initial");
    
    // Vector fields
    private Vector<String> names = new Vector<>();
    private Vector numbers = new Vector(10);
    
    // Hashtable fields
    private Hashtable<String, Object> cache = new Hashtable<>();
    private Hashtable config = new Hashtable(20, 0.75f);
    
    // Properties - should NOT be changed
    private Properties props = new Properties();
    
    /**
     * StringBuffer method - should become StringBuilder
     */
    public StringBuffer buildMessage(StringBuffer input) {
        StringBuffer result = new StringBuffer();
        result.append("Mensaje: ");
        result.append(input.toString());
        return result;
    }
    
    /**
     * Vector method with legacy calls
     */
    public void processVector(Vector<String> items) {
        // Legacy method calls that need migration
        String first = items.firstElement();
        String last = items.lastElement();
        String atIndex = items.elementAt(2);
        
        items.addElement("nuevo");
        items.removeElement("viejo");
        items.removeElementAt(0);
        
        // CRITICAL: Parameter swap needed
        items.insertElementAt("elemento", 1);  // Should become add(1, "elemento")
        items.setElementAt("valor", 2);        // Should become set(2, "valor")
        
        items.removeAllElements();
        
        // Enumeration that should become Iterator
        Enumeration<String> e = items.elements();
        while (e.hasMoreElements()) {
            String item = e.nextElement();
            System.out.println(item);
        }
    }
    
    /**
     * Hashtable method with legacy calls
     */
    public void processHashtable(Hashtable<String, String> data) {
        // Legacy method calls
        if (data.contains("valor")) {  // Should become containsValue
            System.out.println("Encontrado");
        }
        
        // Enumeration over values
        Enumeration<String> values = data.elements();
        while (values.hasMoreElements()) {
            String value = values.nextElement();
            System.out.println(value);
        }
        
        // Enumeration over keys
        Enumeration<String> keys = data.keys();
        while (keys.hasMoreElements()) {
            String key = keys.nextElement();
            System.out.println(key);
        }
    }
    
    /**
     * Properties method - Enumeration should NOT be changed
     */
    public void processProperties() {
        // This Enumeration is from Properties, not Hashtable
        // Should NOT be migrated to Iterator
        Enumeration<?> propNames = props.propertyNames();
        while (propNames.hasMoreElements()) {
            String name = (String) propNames.nextElement();
            System.out.println(name);
        }
        
        // Properties.keys() should also NOT be changed
        Enumeration<?> propKeys = props.keys();
        while (propKeys.hasMoreElements()) {
            Object key = propKeys.nextElement();
            System.out.println(key);
        }
    }
    
    /**
     * Mixed types in method signature
     */
    public Hashtable<String, Vector<StringBuffer>> complexMethod(
            Vector<Hashtable<String, StringBuffer>> input) {
        
        Hashtable<String, Vector<StringBuffer>> result = new Hashtable<>();
        
        for (int i = 0; i < input.size(); i++) {
            Hashtable<String, StringBuffer> item = input.elementAt(i);
            Vector<StringBuffer> buffers = new Vector<>();
            
            Enumeration<String> keys = item.keys();
            while (keys.hasMoreElements()) {
                String key = keys.nextElement();
                StringBuffer value = item.get(key);
                buffers.addElement(value);
            }
            
            result.put("key" + i, buffers);
        }
        
        return result;
    }
    
    /**
     * Casts and instanceof checks
     */
    public void testCasts(Object obj) {
        if (obj instanceof Vector) {
            Vector v = (Vector) obj;
            System.out.println(v.size());
        }
        
        if (obj instanceof Hashtable) {
            Hashtable h = (Hashtable) obj;
            System.out.println(h.size());
        }
        
        if (obj instanceof StringBuffer) {
            StringBuffer sb = (StringBuffer) obj;
            System.out.println(sb.length());
        }
    }
}
