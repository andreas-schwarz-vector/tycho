package org.eclipse.tycho.core.resolver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.eclipse.tycho.targetplatform.TargetDefinition.MavenGAVLocation.DependencyDepth;
import org.junit.Test;

public class OverridePreconditionCheckerTest {

    private static final String ORIGINAL = "com.example.original";
    private static final String OVERRIDE = "com.example.override";

    @Test
    public void testDependencyDepthDiretIsRejected() {
        String result = OverridePreconditionChecker.checkOverridePreconditions(ORIGINAL, singleInstruction(OVERRIDE),
                DependencyDepth.DIRECT);
        assertEquals("The dependency depth must be none!", result);
    }

    @Test
    public void testDependencyDepthNullIsRejected() {
        String result = OverridePreconditionChecker.checkOverridePreconditions(ORIGINAL, singleInstruction(OVERRIDE),
                null);
        assertEquals("The dependency depth must be none!", result);
    }

    @Test
    public void testOriginalSymbolicNameNullIsRejected() {
        String result = OverridePreconditionChecker.checkOverridePreconditions(null, singleInstruction(OVERRIDE),
                DependencyDepth.NONE);
        assertEquals("The artifact is no bundle.", result);
    }

    @Test
    public void testInstructionsMapEmptyIsRejected() {
        String result = OverridePreconditionChecker.checkOverridePreconditions(ORIGINAL, Map.of(),
                DependencyDepth.NONE);
        assertEquals(
                "The location must contain excatly one bnd instruction which must contain a symbolic name that differs from the original one.",
                result);
    }

    @Test
    public void testInstructionsMapWithMultipleEntriesIsRejected() {
        Map<String, Properties> instructions = new HashMap<>();
        instructions.put("a", propertiesWithSymbolicName(OVERRIDE));
        instructions.put("b", propertiesWithSymbolicName("com.example.other"));
        String result = OverridePreconditionChecker.checkOverridePreconditions(ORIGINAL, instructions,
                DependencyDepth.NONE);
        assertEquals(
                "The location must contain excatly one bnd instruction which must contain a symbolic name that differs from the original one.",
                result);
    }

    @Test
    public void testNullPropertiesIsRejected() {
        Map<String, Properties> instructions = new HashMap<>();
        instructions.put("a", null);
        String result = OverridePreconditionChecker.checkOverridePreconditions(ORIGINAL, instructions,
                DependencyDepth.NONE);
        assertEquals(" The symbolic name in the bnd instructions must be defined and differ from the original one.",
                result);
    }

    @Test
    public void testMissingSymbolicNameIsRejected() {
        Map<String, Properties> instructions = new HashMap<>();
        instructions.put("a", new Properties());
        String result = OverridePreconditionChecker.checkOverridePreconditions(ORIGINAL, instructions,
                DependencyDepth.NONE);
        assertEquals(" The symbolic name in the bnd instructions must be defined and differ from the original one.",
                result);
    }

    @Test
    public void testSameSymbolicNameIsRejected() {
        String result = OverridePreconditionChecker.checkOverridePreconditions(ORIGINAL, singleInstruction(ORIGINAL),
                DependencyDepth.NONE);
        assertEquals(" The symbolic name in the bnd instructions must be defined and differ from the original one.",
                result);
    }

    @Test
    public void testDifferentSymbolicNameIsAccepted() {
        String result = OverridePreconditionChecker.checkOverridePreconditions(ORIGINAL, singleInstruction(OVERRIDE),
                DependencyDepth.NONE);
        assertNull(result);
    }

    @Test
    public void testSymbolicNameWithWhitespaceIsAccepted() {
        String result = OverridePreconditionChecker.checkOverridePreconditions(ORIGINAL,
                singleInstruction("  " + OVERRIDE + "  "), DependencyDepth.NONE);
        assertNull(result);
    }

    @Test(expected = NullPointerException.class)
    public void testNullInstructionsMapThrows() {
        OverridePreconditionChecker.checkOverridePreconditions(ORIGINAL, null, DependencyDepth.NONE);
    }

    private static Map<String, Properties> singleInstruction(String symbolicName) {
        Map<String, Properties> instructions = new HashMap<>();
        instructions.put("bnd", propertiesWithSymbolicName(symbolicName));
        return instructions;
    }

    private static Properties propertiesWithSymbolicName(String symbolicName) {
        Properties properties = new Properties();
        properties.put("Bundle-SymbolicName", symbolicName);
        return properties;
    }
}
