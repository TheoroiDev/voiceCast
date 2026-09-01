package com.theo.voicecast.model;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    @Test
    void parsesScalars() {
        assertEquals(42L, Json.parse("42"));
        assertEquals(-9007199254740993L, Json.parse("-9007199254740993"));
        assertEquals(42.5, Json.parse("42.5"));
        assertEquals(2.0, Json.parse("2.0"));
        assertEquals("hi", Json.parse("\"hi\""));
        assertEquals(Boolean.TRUE, Json.parse("true"));
        assertEquals(Boolean.FALSE, Json.parse("false"));
        assertNull(Json.parse("null"));
    }

    @Test
    void parsesEscapes() {
        assertEquals("a\nb\"c\\d", Json.parse("\"a\\nb\\\"c\\\\d\""));
        assertEquals("é", Json.parse("\"\\u00e9\""));
        assertEquals("tab\there", Json.parse("\"tab\\there\""));
    }

    @Test
    void parsesNestedStructuresWithOrder() {
        Map<String, Object> m = Json.parseObject("{\"a\": [1, {\"b\": true}], \"c\": null, \"z\": \"last\"}");
        List<Object> a = Json.getList(m, "a");
        assertEquals(1L, a.get(0));
        assertEquals(Boolean.TRUE, Json.asMap(a.get(1)).get("b"));
        assertTrue(m.containsKey("c") && m.get("c") == null);
        // LinkedHashMap preserves insertion order
        assertEquals(List.of("a", "c", "z"), List.copyOf(m.keySet()));
    }

    @Test
    void malformedInputThrows() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse(""));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1, 2"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("tru"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("\"unterminated"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":1} trailing"));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("[1, 2]"));
    }

    @Test
    void writeParseRoundtrip() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("str", "quote\" back\\slash newline\n");
        m.put("long", 42L);
        m.put("double", 2.5);
        m.put("bool", true);
        m.put("null", null);
        m.put("list", List.of("a", "b"));
        m.put("nested", Map.of("k", List.of(1L, 2L)));

        Map<String, Object> back = Json.parseObject(Json.write(m));
        assertEquals(m.get("str"), back.get("str"));
        assertEquals(42L, back.get("long"));
        assertEquals(2.5, back.get("double"));
        assertEquals(Boolean.TRUE, back.get("bool"));
        assertNull(back.get("null"));
        assertEquals(List.of("a", "b"), back.get("list"));
        assertEquals(List.of(1L, 2L), Json.getList(Json.asMap(back.get("nested")), "k"));
    }

    @Test
    void integralDoublesRenderWithoutDecimalPoint() {
        assertEquals("2", Json.write(2.0d));
        assertEquals("2", Json.write(2.0f));
        assertEquals("42", Json.write(42L));
        assertEquals("2.5", Json.write(2.5d));
    }

    @Test
    void writeEmptyCollectionsAndNull() {
        assertEquals("{}", Json.write(Map.of()));
        assertEquals("[]", Json.write(List.of()));
        assertEquals("null", Json.write((Object) null));
    }

    @Test
    void writeEscapesControlCharacters() {
        String out = Json.write("a\u0001b");
        assertTrue(out.contains("\\u0001"), "control char must be escaped: " + out);
    }

    @Test
    void typedAccessorsFallBackToDefaults() {
        Map<String, Object> m = Json.parseObject("{\"s\":\"x\",\"b\":true,\"n\":3,\"l\":[\"a\",\"\",\"b\"]}");
        assertEquals("x", Json.getString(m, "s", "d"));
        assertEquals("d", Json.getString(m, "missing", "d"));
        assertTrue(Json.getBool(m, "b", false));
        assertFalse(Json.getBool(m, "missing", false));
        assertEquals(3L, Json.getLong(m, "n", 0));
        assertEquals(7L, Json.getLong(m, "missing", 7));
        assertEquals(List.of("a", "b"), Json.getStringList(m, "l"));
        assertTrue(Json.getMap(m, "missing").isEmpty());
        assertTrue(Json.asMap("not a map").isEmpty());
        assertEquals(3L, Json.getLong(Json.asMap(m), "n", 0));
    }
}
