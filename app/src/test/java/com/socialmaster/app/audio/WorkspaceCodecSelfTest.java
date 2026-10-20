package com.socialmaster.app.audio;

import java.util.LinkedHashMap;
import java.util.Map;

public final class WorkspaceCodecSelfTest {
    public static void main(String[] args) {
        Map<String, String> state = new LinkedHashMap<>();
        state.put("screen", "mix");
        state.put("track.name", "Song = one | café\nTake");
        state.put("track.gain", "-3.5");
        state.put("empty", "");
        equal(state, WorkspaceCodec.decode(WorkspaceCodec.encode(state)));
        equal(Map.of(), WorkspaceCodec.decode("malformed=\\uZZZZ"));
        System.out.println("WorkspaceCodecSelfTest passed");
    }

    private static void equal(Map<String, String> expected, Map<String, String> actual) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " got " + actual);
    }
}
